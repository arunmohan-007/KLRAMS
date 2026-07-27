package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Avg IRI (2 km · worst lane)" — the condition survey is collected in short
 * stretches (typically 100 m per lane), which is far finer than the 2 km unit
 * used for reporting roughness. This service rolls the raw IRI up into fixed
 * 2 km bins per road section and stores the result in its own table
 * (iri_2km_segments), mirroring {@link SegmentService} / {@link FwdSegmentService}.
 *
 * Per 2 km bin it stores:
 *   - lane_avgs (jsonb) — LENGTH-WEIGHTED average IRI for EVERY cross-section
 *     position the section carries (a 200 m row counts twice as much as a 100 m
 *     one), keyed by XSP: CC / CL1 / CL2 / CR1 / CR2
 *   - worst_iri / worst_lane — the highest of those lane averages and which lane
 *     it came from; this is what colours the map layer
 *
 * Which lanes exist varies by section, exactly as in {@link SegmentService}: a
 * single carriageway may be surveyed as CC alone or as CL1 + CR1, while a dual
 * is drawn as two centrelines whose Section_La differ by a trailing A/B, each
 * carrying only its own side (…A → CL1/CL2, …B → CR1/CR2). So the worst lane is
 * taken over whatever the section actually has, never a fixed pair.
 *
 * Binning: bin = floor(start_chainage / 2000), i.e. 0-2000, 2000-4000, … from the
 * section's own chainage origin. A survey row is assigned to the bin its START
 * chainage falls in, so a row straddling a boundary belongs wholly to the earlier
 * bin (survey rows are 100 m aligned, so this is an edge case).
 *
 * Reference length = (Rd_End_cha - Rd_Str_cha), fallback Measrd_Len, then geometry
 * — the same linear-reference rule used everywhere else.
 */
@Service
public class IriSegmentService {

    /** Reporting unit for the roll-up, in metres. */
    private static final int BIN_METRES = 2000;

    private final JdbcTemplate jdbc;
    private final SurveyPeriodService periods;

    /* Assemble the GeoJSON once and serve later requests from memory; only the
       active period is cached (other periods are Survey Archive requests, built
       per request); cleared on every build. */
    private volatile String cachedGeoJson;
    private volatile Integer cachedPeriodId;
    private volatile String cachedEtag;

    public IriSegmentService(JdbcTemplate jdbc, SurveyPeriodService periods) {
        this.jdbc = jdbc;
        this.periods = periods;
    }

    @Transactional
    public int buildSegments() {
        jdbc.execute("DROP TABLE IF EXISTS iri_2km_segments");

        jdbc.execute("""
            CREATE TABLE iri_2km_segments AS
            WITH src AS (
                SELECT section_label, period_id,
                    COALESCE(NULLIF(upper(btrim(xsp)), ''), 'CC') AS lane,
                    start_chainage, end_chainage, iri,
                    floor(start_chainage / %d.0)::int AS bin
                FROM condition
                WHERE iri IS NOT NULL
                  AND start_chainage IS NOT NULL
                  AND end_chainage   IS NOT NULL
                  AND end_chainage > start_chainage
            ),
            per_lane AS (
                SELECT section_label, period_id, bin, lane,
                    ROUND((SUM(iri * (end_chainage - start_chainage))
                         / NULLIF(SUM(end_chainage - start_chainage), 0)
                          )::numeric, 2)::double precision AS avg_iri,
                    MIN(start_chainage) AS from_ch,
                    MAX(end_chainage)   AS to_ch,
                    SUM(end_chainage - start_chainage) AS lane_len,
                    COUNT(*)::int AS n_rows
                FROM src
                GROUP BY section_label, period_id, bin, lane
            ),
            binned AS (
                SELECT section_label, period_id, bin,
                    MIN(from_ch) AS from_ch,
                    MAX(to_ch)   AS to_ch,
                    SUM(lane_len) AS surveyed_len,
                    SUM(n_rows)::int AS n_rows,
                    COUNT(*)::int AS lane_count,
                    string_agg(lane, ',' ORDER BY lane) AS lane_list,
                    jsonb_object_agg(lane, avg_iri) AS lane_avgs,
                    MAX(avg_iri) AS worst_iri,
                    -- highest lane average wins; ties settle on lane name so the
                    -- popup and the colouring always name the same lane
                    (array_agg(lane ORDER BY avg_iri DESC NULLS LAST, lane))[1] AS worst_lane
                FROM per_lane
                GROUP BY section_label, period_id, bin
            ),
            joined AS (
                SELECT b.*, ST_LineMerge(r.geom) AS road_geom,
                    COALESCE(
                        NULLIF(r."Rd_End_cha"::double precision - r."Rd_Str_cha"::double precision, 0),
                        NULLIF(r."Measrd_Len"::double precision, 0),
                        ST_Length(r.geom::geography)) AS measured_len
                FROM binned b
                JOIN roads r ON r."Section_La" = b.section_label
                WHERE r.geom IS NOT NULL
                  AND ST_GeometryType(ST_LineMerge(r.geom)) = 'ST_LineString'
            )
            SELECT
                section_label, period_id, bin,
                from_ch AS start_chainage, to_ch AS end_chainage, surveyed_len,
                lane_avgs, lane_list, lane_count, n_rows, worst_iri, worst_lane,
                ST_LineSubstring(road_geom,
                    GREATEST(LEAST(from_ch / measured_len, 1.0), 0.0),
                    GREATEST(LEAST(to_ch   / measured_len, 1.0), 0.0)) AS geom
            FROM joined
            WHERE measured_len IS NOT NULL AND measured_len > 0
              AND worst_iri IS NOT NULL
            """.formatted(BIN_METRES));

        jdbc.execute("DELETE FROM iri_2km_segments WHERE geom IS NULL OR ST_IsEmpty(geom)");
        jdbc.execute("ALTER TABLE iri_2km_segments ADD COLUMN seg_id serial PRIMARY KEY");
        jdbc.execute("CREATE INDEX iri_2km_segments_geom_idx ON iri_2km_segments USING GIST (geom)");
        jdbc.execute("CREATE INDEX iri_2km_segments_period_idx ON iri_2km_segments (period_id)");
        jdbc.execute("CREATE INDEX iri_2km_segments_section_idx ON iri_2km_segments (section_label)");

        Long n = jdbc.queryForObject("SELECT count(*) FROM iri_2km_segments", Long.class);
        cachedGeoJson = null;   // rebuilt -> next /geojson refreshes the cache
        cachedEtag = null;
        return n == null ? 0 : n.intValue();
    }

    /** GeoJSON of one survey period's 2 km IRI bins (null = active period), paired
     *  with a content ETag so the controller can answer conditional requests. */
    public GeoJsonResponse.Payload segmentsPayload(Integer requestedPeriodId) {
        int pid = periods.resolve(requestedPeriodId);
        if (cachedGeoJson != null && cachedEtag != null && cachedPeriodId != null && cachedPeriodId == pid)
            return new GeoJsonResponse.Payload(cachedGeoJson, cachedEtag);
        synchronized (this) {
            if (cachedGeoJson != null && cachedEtag != null && cachedPeriodId != null && cachedPeriodId == pid)
                return new GeoJsonResponse.Payload(cachedGeoJson, cachedEtag);
            String body = buildGeoJson(pid);
            String etag = GeoJsonResponse.contentTag(body);
            if (pid == periods.activePeriodId()) {
                cachedGeoJson = body;
                cachedPeriodId = pid;
                cachedEtag = etag;
            }
            return new GeoJsonResponse.Payload(body, etag);
        }
    }

    /** GeoJSON of one survey period's 2 km IRI bins (null = active period). */
    public String segmentsGeoJson(Integer requestedPeriodId) {
        return segmentsPayload(requestedPeriodId).body();
    }

    public String segmentsGeoJson() {
        return segmentsGeoJson(null);
    }

    private String buildGeoJson(int periodId) {
        try {
            String sql = """
                SELECT json_build_object('type','FeatureCollection','features',
                    COALESCE(json_agg(json_build_object(
                        'type','Feature',
                        'geometry', ST_AsGeoJSON(geom, 6)::json,
                        'properties', json_build_object(
                            'road', section_label, 'from_ch', start_chainage,
                            'to_ch', end_chainage, 'bin', bin,
                            'lane_avgs', lane_avgs, 'lane_list', lane_list,
                            'lane_count', lane_count, 'n_rows', n_rows,
                            'worst_iri', worst_iri, 'worst_lane', worst_lane,
                            'surveyed_len', ROUND(surveyed_len::numeric, 0))
                    )), '[]'::json))::text
                FROM iri_2km_segments WHERE period_id = ?
                """;
            return jdbc.queryForObject(sql, String.class, periodId);
        } catch (Exception e) {
            // table not built yet -> empty collection, same as the FWD layer
            return "{\"type\":\"FeatureCollection\",\"features\":[]}";
        }
    }

    public long count() {
        try {
            Long n = jdbc.queryForObject("SELECT count(*) FROM iri_2km_segments", Long.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }
}
