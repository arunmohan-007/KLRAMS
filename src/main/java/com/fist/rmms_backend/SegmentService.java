package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

        Long n = jdbc.queryForObject("SELECT count(*) FROM condition_segments", Long.class);
        cachedGeoJson = null;   // segments changed -> next /geojson rebuilds the cache
        return n == null ? 0 : n.intValue();
    }

    /** GeoJSON of one survey period's segments (null = active period). */
    public String segmentsGeoJson(Integer requestedPeriodId) {
        int pid = periods.resolve(requestedPeriodId);
        String body = cachedGeoJson;
        Integer cachedPid = cachedPeriodId;
        if (body != null && cachedPid != null && cachedPid == pid) return body;
        synchronized (this) {
            if (cachedGeoJson != null && cachedPeriodId != null && cachedPeriodId == pid) return cachedGeoJson;
            body = buildGeoJson(pid);
            // Keep the active period resident: it serves every map open. A stale
            // cache from a previous active period is simply replaced.
            if (pid == periods.activePeriodId()) {
                cachedGeoJson = body;
                cachedPeriodId = pid;
            }
            return body;
        }
    }

    public String segmentsGeoJson() {
        return segmentsGeoJson(null);
    }

    private String buildGeoJson(int periodId) {
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
                        'lane_count', lane_count, 'xsp_list', xsp_list, 'lane_vals', lane_vals)
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
