package com.fist.rmms_backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Linear referencing for condition data.
 * Reference length = (Rd_End_cha - Rd_Str_cha), fallback Measrd_Len, then geometry.
 *
 * Per segment this emits THREE views of the lane data (XSP = CC / CL1 / CL2 / CR1 / CR2):
 *   - worst (MAX) per parameter  -> used for layer colouring & filters (iri, crack, ...)
 *   - average per parameter      -> used for the popup (avg_iri, avg_crack, ...);
 *                                   when only CC exists, avg == the road's single value
 *   - per-lane values (lane_vals jsonb) -> used by the NSV player to show the
 *                                   value of the lane being driven
 */
@Service
public class SegmentService {

    private final JdbcTemplate jdbc;
    private final SurveyPeriodService periods;

    /* The segment GeoJSON is large and unchanged between rebuilds, so assemble it
       once and serve every later request from memory. Only one period (normally
       the active one) is cached; other periods — rare, Survey Archive views —
       are built per request. Cleared on buildSegments(). */
    private volatile String cachedGeoJson;
    private volatile Integer cachedPeriodId;
    private volatile String cachedEtag;

    public SegmentService(JdbcTemplate jdbc, SurveyPeriodService periods) {
        this.jdbc = jdbc;
        this.periods = periods;
    }

    @Transactional
    public int buildSegments() {
        jdbc.execute("DROP TABLE IF EXISTS condition_segments");

        jdbc.execute("""
            CREATE TABLE condition_segments AS
            WITH agg AS (
                SELECT
                    section_label, start_chainage, end_chainage, period_id,
                    MAX(iri) AS iri, MAX(crack) AS crack, MAX(pothole) AS pothole,
                    MAX(rutting) AS rutting, MAX(texture) AS texture,
                    MAX(patch_work) AS patch_work, MAX(ravelling) AS ravelling,
                    ROUND(AVG(iri)::numeric,2)        AS avg_iri,
                    ROUND(AVG(crack)::numeric,2)      AS avg_crack,
                    ROUND(AVG(pothole)::numeric,2)    AS avg_pothole,
                    ROUND(AVG(rutting)::numeric,2)    AS avg_rutting,
                    ROUND(AVG(texture)::numeric,2)    AS avg_texture,
                    ROUND(AVG(patch_work)::numeric,2) AS avg_patch_work,
                    ROUND(AVG(ravelling)::numeric,2)  AS avg_ravelling,
                    COUNT(DISTINCT xsp) AS lane_count,
                    string_agg(DISTINCT xsp, ',' ORDER BY xsp) AS xsp_list,
                    jsonb_object_agg(xsp, jsonb_build_object(
                        'iri', ROUND(iri::numeric,2), 'crack', ROUND(crack::numeric,2),
                        'pothole', pothole, 'rutting', ROUND(rutting::numeric,2),
                        'texture', ROUND(texture::numeric,2),
                        'patch_work', ROUND(patch_work::numeric,2),
                        'ravelling', ROUND(ravelling::numeric,2))) AS lane_vals
                FROM condition
                WHERE start_chainage IS NOT NULL
                  AND end_chainage   IS NOT NULL
                  AND end_chainage > start_chainage
                GROUP BY section_label, start_chainage, end_chainage, period_id
            ),
            joined AS (
                SELECT a.*, r.geom AS road_geom,
                    COALESCE(
                        NULLIF(r."Rd_End_cha"::double precision - r."Rd_Str_cha"::double precision, 0),
                        NULLIF(r."Measrd_Len"::double precision, 0),
                        ST_Length(r.geom::geography)) AS measured_len
                FROM agg a
                JOIN roads r ON r."Section_La" = a.section_label
                WHERE r.geom IS NOT NULL
            )
            SELECT
                section_label, start_chainage, end_chainage, period_id,
                iri, crack, pothole, rutting, texture, patch_work, ravelling,
                avg_iri, avg_crack, avg_pothole, avg_rutting, avg_texture, avg_patch_work, avg_ravelling,
                lane_count, xsp_list, lane_vals,
                ST_LineSubstring(road_geom,
                    GREATEST(LEAST(start_chainage / measured_len, 1.0), 0.0),
                    GREATEST(LEAST(end_chainage   / measured_len, 1.0), 0.0)) AS geom
            FROM joined
            WHERE measured_len IS NOT NULL AND measured_len > 0
            """);

        jdbc.execute("DELETE FROM condition_segments WHERE geom IS NULL OR ST_IsEmpty(geom)");
        jdbc.execute("ALTER TABLE condition_segments ADD COLUMN seg_id serial PRIMARY KEY");
        jdbc.execute("CREATE INDEX condition_segments_geom_idx ON condition_segments USING GIST (geom)");
        jdbc.execute("CREATE INDEX condition_segments_period_idx ON condition_segments (period_id)");

        storeDefaultPci();

        Long n = jdbc.queryForObject("SELECT count(*) FROM condition_segments", Long.class);
        cachedGeoJson = null;   // segments changed -> next /geojson rebuilds the cache
        cachedEtag = null;
        return n == null ? 0 : n.intValue();
    }

    /**
     * Computes the IRC:82-2023 PCI for every segment with the DEFAULT weights and
     * stores it in condition_segments (pci_def_avg = Composite, pci_def_worst =
     * Worst-Lane), so the viewer no longer has to score 30k+ segments in the
     * browser on every map open. The browser still recomputes on the fly — and
     * ignores these columns — while the user has the weights off their defaults.
     *
     * Runs inside buildSegments(), i.e. once per upload, not per request.
     */
    private void storeDefaultPci() {
        ensurePciColumns();

        final ObjectMapper json = new ObjectMapper();
        final String sql = "UPDATE condition_segments SET pci_def_avg = ?, pci_def_worst = ? WHERE seg_id = ?";
        final List<Object[]> batch = new ArrayList<>();

        jdbc.query(
            "SELECT seg_id, lane_vals, iri, crack, pothole, rutting, patch_work, ravelling, " +
            "       avg_iri, avg_crack, avg_pothole, avg_rutting, avg_patch_work, avg_ravelling " +
            "FROM condition_segments",
            (RowCallbackHandler) rs -> {
                Map<String, Map<String, Double>> lanes = parseLaneVals(json, rs.getString("lane_vals"));
                // Segment-level fallback when a stretch has no lane breakdown:
                // worst -> the MAX columns, composite -> the avg_* columns.
                Map<String, Double> worstFallback = new LinkedHashMap<>();
                Map<String, Double> avgFallback = new LinkedHashMap<>();
                for (String key : PciCalculator.WEIGHTS.keySet()) {
                    Double max = dbl(rs.getObject(key));
                    Double avg = dbl(rs.getObject("avg_" + key));
                    if (max != null) worstFallback.put(key, max);
                    Double composite = (avg != null) ? avg : max;
                    if (composite != null) avgFallback.put(key, composite);
                }

                batch.add(new Object[]{
                    PciCalculator.round2(PciCalculator.segPci(lanes, avgFallback, false)),
                    PciCalculator.round2(PciCalculator.segPci(lanes, worstFallback, true)),
                    rs.getInt("seg_id")
                });
                if (batch.size() >= 1000) {
                    jdbc.batchUpdate(sql, batch);
                    batch.clear();
                }
            });
        if (!batch.isEmpty()) jdbc.batchUpdate(sql, batch);
    }

    /**
     * Adds the stored-PCI columns when they are missing. Databases whose segments
     * were built before this feature still carry the old table, and buildGeoJson()
     * selects both columns — so this runs there too, leaving the values NULL until
     * the next Build Segments (the viewer then falls back to computing in-browser).
     */
    private void ensurePciColumns() {
        Boolean exists = jdbc.queryForObject(
            "SELECT to_regclass('condition_segments') IS NOT NULL", Boolean.class);
        if (!Boolean.TRUE.equals(exists)) return;
        jdbc.execute("ALTER TABLE condition_segments " +
                     "ADD COLUMN IF NOT EXISTS pci_def_avg double precision, " +
                     "ADD COLUMN IF NOT EXISTS pci_def_worst double precision");
    }

    /** lane_vals jsonb -> { laneName: { param: value } }, keeping only PCI parameters. */
    private static Map<String, Map<String, Double>> parseLaneVals(ObjectMapper json, String raw) {
        Map<String, Map<String, Double>> lanes = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return lanes;
        try {
            JsonNode root = json.readTree(raw);
            root.fields().forEachRemaining(lane -> {
                Map<String, Double> dist = new LinkedHashMap<>();
                for (String key : PciCalculator.WEIGHTS.keySet()) {
                    JsonNode v = lane.getValue().get(key);
                    if (v != null && v.isNumber()) dist.put(key, v.doubleValue());
                }
                lanes.put(lane.getKey(), dist);
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();   // malformed lane_vals -> segment-level fallback
        }
        return lanes;
    }

    private static Double dbl(Object o) {
        return (o instanceof Number n) ? n.doubleValue() : null;
    }

    /** GeoJSON of one survey period's segments (null = active period), paired with
     *  a content ETag so the controller can answer conditional requests. */
    public GeoJsonResponse.Payload segmentsPayload(Integer requestedPeriodId) {
        int pid = periods.resolve(requestedPeriodId);
        if (cachedGeoJson != null && cachedEtag != null && cachedPeriodId != null && cachedPeriodId == pid)
            return new GeoJsonResponse.Payload(cachedGeoJson, cachedEtag);
        synchronized (this) {
            if (cachedGeoJson != null && cachedEtag != null && cachedPeriodId != null && cachedPeriodId == pid)
                return new GeoJsonResponse.Payload(cachedGeoJson, cachedEtag);
            String body = buildGeoJson(pid);
            String etag = GeoJsonResponse.contentTag(body);
            // Keep the active period resident: it serves every map open. A stale
            // cache from a previous active period is simply replaced.
            if (pid == periods.activePeriodId()) {
                cachedGeoJson = body;
                cachedPeriodId = pid;
                cachedEtag = etag;
            }
            return new GeoJsonResponse.Payload(body, etag);
        }
    }

    /** GeoJSON of one survey period's segments (null = active period). */
    public String segmentsGeoJson(Integer requestedPeriodId) {
        return segmentsPayload(requestedPeriodId).body();
    }

    public String segmentsGeoJson() {
        return segmentsGeoJson(null);
    }

    private String buildGeoJson(int periodId) {
        ensurePciColumns();
        // ST_AsGeoJSON(geom, 6): 6-decimal coordinates (~0.1 m) cut the payload size
        // substantially versus the 9-decimal default, with no visible loss for roads.
        String sql = """
            SELECT json_build_object('type','FeatureCollection','features',
                COALESCE(json_agg(json_build_object(
                    'type','Feature',
                    'geometry', ST_AsGeoJSON(geom, 6)::json,
                    'properties', json_build_object(
                        'road', section_label, 'from_ch', start_chainage, 'to_ch', end_chainage,
                        'iri', iri, 'crack', crack, 'pothole', pothole, 'rutting', rutting,
                        'texture', texture, 'patch_work', patch_work, 'ravelling', ravelling,
                        'avg_iri', avg_iri, 'avg_crack', avg_crack, 'avg_pothole', avg_pothole,
                        'avg_rutting', avg_rutting, 'avg_texture', avg_texture,
                        'avg_patch_work', avg_patch_work, 'avg_ravelling', avg_ravelling,
                        'lane_count', lane_count, 'xsp_list', xsp_list, 'lane_vals', lane_vals,
                        'pci_def_avg', pci_def_avg, 'pci_def_worst', pci_def_worst)
                )), '[]'::json))::text
            FROM condition_segments WHERE period_id = ?
            """;
        return jdbc.queryForObject(sql, String.class, periodId);
    }

    public long count() {
        try {
            Long n = jdbc.queryForObject("SELECT count(*) FROM condition_segments", Long.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }
}
