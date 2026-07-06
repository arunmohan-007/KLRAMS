package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Linear referencing for FWD (Falling Weight Deflectometer) data — the FWD
 * survey is uploaded as the "fwd" asset (points), but each row is really a
 * chainage RANGE (From..To) carrying D0..Dn deflections, exactly like the
 * condition survey. This service cuts those ranges into coloured line segments
 * along the road centreline, mirroring {@link SegmentService} for condition.
 *
 * Reference length = (Rd_End_cha - Rd_Str_cha), fallback Measrd_Len, then geometry.
 * D0 is pulled from the row's kept attributes (attrs jsonb) — the characteristic
 * deflection used for colouring; the raw value is stored as-is.
 */
@Service
public class FwdSegmentService {

    private final JdbcTemplate jdbc;

    /* Assemble the segment GeoJSON once and serve later requests from memory;
       cleared on every build. */
    private volatile String cachedGeoJson;

    public FwdSegmentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public int buildSegments() {
        jdbc.execute("DROP TABLE IF EXISTS fwd_segments");

        jdbc.execute("""
            CREATE TABLE fwd_segments AS
            WITH src AS (
                SELECT a.section_label, a.start_chainage, a.end_chainage, a.attrs,
                    (SELECT (e.value)::double precision
                       FROM jsonb_each_text(a.attrs) e
                      WHERE regexp_replace(lower(e.key), '[^a-z0-9]', '', 'g') IN ('d0','do')
                        AND e.value ~ '^-?[0-9]+(\\.[0-9]+)?$'
                      LIMIT 1) AS d0
                FROM road_assets a
                WHERE a.asset_type = 'fwd'
                  AND a.start_chainage IS NOT NULL
                  AND a.end_chainage   IS NOT NULL
                  AND a.end_chainage > a.start_chainage
            ),
            joined AS (
                SELECT s.*, r.geom AS road_geom,
                    COALESCE(
                        NULLIF(r."Rd_End_cha"::double precision - r."Rd_Str_cha"::double precision, 0),
                        NULLIF(r."Measrd_Len"::double precision, 0),
                        ST_Length(r.geom::geography)) AS measured_len
                FROM src s
                JOIN roads r ON r."Section_La" = s.section_label
                WHERE r.geom IS NOT NULL
            )
            SELECT
                section_label, start_chainage, end_chainage, d0,
                ST_LineSubstring(ST_LineMerge(road_geom),
                    GREATEST(LEAST(start_chainage / measured_len, 1.0), 0.0),
                    GREATEST(LEAST(end_chainage   / measured_len, 1.0), 0.0)) AS geom
            FROM joined
            WHERE measured_len IS NOT NULL AND measured_len > 0
            """);

        jdbc.execute("DELETE FROM fwd_segments WHERE geom IS NULL OR ST_IsEmpty(geom)");
        jdbc.execute("ALTER TABLE fwd_segments ADD COLUMN seg_id serial PRIMARY KEY");
        jdbc.execute("CREATE INDEX fwd_segments_geom_idx ON fwd_segments USING GIST (geom)");

        Long n = jdbc.queryForObject("SELECT count(*) FROM fwd_segments", Long.class);
        cachedGeoJson = null;   // segments changed -> next /geojson rebuilds the cache
        return n == null ? 0 : n.intValue();
    }

    public String segmentsGeoJson() {
        String body = cachedGeoJson;
        if (body == null) {
            synchronized (this) {
                if (cachedGeoJson == null) cachedGeoJson = buildGeoJson();
                body = cachedGeoJson;
            }
        }
        return body;
    }

    private String buildGeoJson() {
        try {
            String sql = """
                SELECT json_build_object('type','FeatureCollection','features',
                    COALESCE(json_agg(json_build_object(
                        'type','Feature',
                        'geometry', ST_AsGeoJSON(geom, 6)::json,
                        'properties', json_build_object(
                            'road', section_label, 'from_ch', start_chainage,
                            'to_ch', end_chainage, 'd0', d0)
                    )), '[]'::json))::text
                FROM fwd_segments
                """;
            return jdbc.queryForObject(sql, String.class);
        } catch (Exception e) {
            return "{\"type\":\"FeatureCollection\",\"features\":[]}";
        }
    }

    public long count() {
        try {
            Long n = jdbc.queryForObject("SELECT count(*) FROM fwd_segments", Long.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }
}
