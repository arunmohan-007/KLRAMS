package com.fist.rmms_backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Builds Mapbox Vector Tiles for the FWD deflection survey.
 *
 * <p>FWD is uploaded as a <em>line</em> asset ({@code From..To} + D0..Dn in attrs;
 * lat/lng kept in attrs for display only). {@link AssetController#upload} stores
 * {@code geom} with {@code ST_LineSubstring}, so tiles can paint the stored stretch
 * directly — the same shape bridges use.
 *
 * <p>Legacy rows that still carry a start-chainage POINT are upgraded by
 * {@code AssetController.relocateFwdLineGeoms()} on ensure; until that runs, this
 * query still falls back to cutting the stretch from the road centreline so old
 * databases keep drawing correctly.
 *
 * <p>D0 is shown in the unit it was recorded in — millimetres — everywhere:
 * this tile, the legend, Style &amp; Label Management, the FWD Dashboard and
 * every export. There is deliberately no scale detection or conversion here
 * any more; a survey is read exactly as uploaded.
 */
@Service
public class FwdTileService {

    /** Same MVT layer name the other asset tiles use, so the client binds one {@code source-layer}. */
    static final String LAYER_NAME = AssetTileService.LAYER_NAME;

    private final JdbcTemplate jdbc;
    private final SurveyPeriodService periods;
    private final LayerStyleService styles;

    private final int extent;
    private final int buffer;
    private final int maxZoom;

    /** Resolves the road network's columns by system attribute name. */
    private final RoadColumns roadColumns;

    public FwdTileService(JdbcTemplate jdbc,
                          SurveyPeriodService periods,
                          LayerStyleService styles,
                          RoadColumns roadColumns,
                          @Value("${app.tile.extent:4096}") int extent,
                          @Value("${app.tile.buffer:64}") int buffer,
                          @Value("${app.tile.max-zoom:20}") int maxZoom) {
        this.jdbc = jdbc;
        this.periods = periods;
        this.styles = styles;
        this.roadColumns = roadColumns;
        this.extent = extent;
        this.buffer = buffer;
        this.maxZoom = maxZoom;
    }

    int maxZoom() {
        return maxZoom;
    }

    /** One tile's worth of FWD, or {@code null} when there is nothing to draw. */
    byte[] tile(TileCoordinate t, Integer requestedPeriodId) {
        Boolean built = jdbc.queryForObject("SELECT to_regclass('road_assets') IS NOT NULL", Boolean.class);
        if (!Boolean.TRUE.equals(built)) return null;

        int periodId = periods.resolve(requestedPeriodId);
        // The attribute a saved style colours and labels FWD by, lifted out of
        // attrs so a paint expression can read it. Null unless someone has
        // styled the layer, and the D0 colouring above is untouched either way.
        String[] keys = styles.tileKeys("fwd");
        byte[] tile = jdbc.queryForObject(tileSql(), byte[].class,
                t.z(), t.x(), t.y(), periodId, periodId, keys[0], keys[1], extent, buffer, extent);

        return (tile == null || tile.length == 0) ? null : tile;
    }

    /* Built per call rather than held as a constant: the road network's column names come
       from the last shapefile import, so they are resolved by meaning at request time (and
       RoadColumns caches the lookup, so this is not a per-tile query). */
    private String tileSql() {
        return """
            WITH bounds AS (
                SELECT merc, ST_Transform(merc, 4326) AS wgs
                FROM (SELECT ST_TileEnvelope(?, ?, ?) AS merc) e
            ),
            d0 AS (
                SELECT a.id,
                       (SELECT (e.value)::double precision
                          FROM jsonb_each_text(a.attrs) e
                         WHERE regexp_replace(lower(e.key), '[^a-z0-9]', '', 'g') IN ('d0','do')
                           AND e.value ~ '^-?[0-9]+(\\.[0-9]+)?$'
                         LIMIT 1) AS v
                FROM road_assets a
                WHERE a.asset_type = 'fwd' AND a.period_id = ?
            ),
            cand AS (
                SELECT a.id, a.section_label, a.attrs, a.geom AS stored_geom,
                       a.start_chainage AS from_ch, a.end_chainage AS to_ch,
                       r.geom AS road_geom,
                       d0.v AS d0,
                       LEAST(a.start_chainage, COALESCE(a.end_chainage, a.start_chainage)) AS lo,
                       GREATEST(a.start_chainage, COALESCE(a.end_chainage, a.start_chainage)) AS hi,
                       %1$s AS measured_len
                FROM road_assets a
                JOIN roads r ON %2$s = a.section_label AND r.geom IS NOT NULL
                LEFT JOIN d0 ON d0.id = a.id
                CROSS JOIN bounds b
                WHERE a.asset_type = 'fwd'
                  AND a.period_id = ?
                  AND (
                        (a.geom IS NOT NULL AND a.geom && b.wgs)
                     OR (a.geom IS NULL OR GeometryType(a.geom) IN ('POINT','MULTIPOINT'))
                        AND r.geom && b.wgs
                  )
            ),
            src AS (
                SELECT
                    c.id            AS asset_id,
                    c.section_label AS road,
                    c.section_label AS __sec,
                    c.from_ch,
                    c.to_ch,
                    c.attrs::text   AS attrs_json,
                    c.attrs->>(?::text) AS __style,
                    c.attrs->>(?::text) AS __label,
                    ROUND(c.d0::numeric, 3)::double precision AS __d0,
                    ST_AsMVTGeom(ST_Transform(
                        CASE
                            /* Prefer the line stretch stored at upload time. */
                            WHEN c.stored_geom IS NOT NULL
                             AND GeometryType(c.stored_geom) IN ('LINESTRING','MULTILINESTRING')
                            THEN c.stored_geom
                            /* Legacy point rows: cut From..To on the road (same as before). */
                            WHEN c.lo IS NOT NULL AND c.hi IS NOT NULL
                             AND c.measured_len > 0
                             AND c.hi - c.lo > 0.001
                            THEN ST_LineSubstring(ST_LineMerge(c.road_geom),
                                     GREATEST(LEAST(c.lo / c.measured_len, 1.0), 0.0),
                                     GREATEST(LEAST(c.hi / c.measured_len, 1.0), 0.0))
                            ELSE c.stored_geom
                        END, 3857), b.merc, ?, ?, true) AS geom
                FROM cand c, bounds b
            )
            """.formatted(roadColumns.lenExpr("r"),
                          roadColumns.col("r", LayerAttributeCatalog.SECTION_LABEL))
            + "SELECT ST_AsMVT(src, '" + LAYER_NAME + "', ?, 'geom') FROM src WHERE geom IS NOT NULL";
    }
}
