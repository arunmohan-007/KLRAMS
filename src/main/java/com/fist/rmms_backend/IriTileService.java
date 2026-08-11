package com.fist.rmms_backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Builds Mapbox Vector Tiles for the 2 km IRI roll-up, straight out of PostGIS.
 *
 * <p>Same bounds/index pattern as {@link SegmentTileService}: the GiST on
 * {@code iri_2km_segments.geom} is built on untransformed 4326 geometry, so the tile
 * envelope is reprojected once and used for the index hit; only survivors are transformed
 * for {@code ST_AsMVTGeom}.
 *
 * <p>Property names mirror {@link IriSegmentService}'s GeoJSON exactly — {@code road},
 * {@code from_ch}, {@code to_ch}, {@code worst_iri}, {@code lane_avgs}, etc. — so the
 * viewer style and popup need no second code path. {@code lane_avgs} is emitted as text
 * (jsonb → string); the client already accepts either form.
 */
@Service
public class IriTileService {

    /** The MVT layer name clients bind to as {@code source-layer}. Changing it breaks the style. */
    static final String LAYER_NAME = "iri2km";

    private final JdbcTemplate jdbc;
    private final SurveyPeriodService periods;

    private final int extent;
    private final int buffer;
    private final int maxZoom;

    public IriTileService(JdbcTemplate jdbc,
                          SurveyPeriodService periods,
                          @Value("${app.tile.extent:4096}") int extent,
                          @Value("${app.tile.buffer:64}") int buffer,
                          @Value("${app.tile.max-zoom:20}") int maxZoom) {
        this.jdbc = jdbc;
        this.periods = periods;
        this.extent = extent;
        this.buffer = buffer;
        this.maxZoom = maxZoom;
    }

    int maxZoom() {
        return maxZoom;
    }

    /**
     * One tile's worth of 2 km IRI bins, or {@code null} when there is nothing to draw
     * (table never built, period empty, or tile off the network). The controller turns
     * null into 204.
     */
    byte[] tile(TileCoordinate t, Integer requestedPeriodId) {
        Boolean built = jdbc.queryForObject(
                "SELECT to_regclass('iri_2km_segments') IS NOT NULL", Boolean.class);
        if (!Boolean.TRUE.equals(built)) return null;

        int periodId = periods.resolve(requestedPeriodId);

        byte[] tile = jdbc.queryForObject(TILE_SQL, byte[].class,
                t.z(), t.x(), t.y(), extent, buffer, periodId, extent);

        return (tile == null || tile.length == 0) ? null : tile;
    }

    private static final String TILE_SQL =
            """
            WITH bounds AS (
                SELECT merc, ST_Transform(merc, 4326) AS wgs
                FROM (SELECT ST_TileEnvelope(?, ?, ?) AS merc) e
            ),
            src AS (
                SELECT
                    s.seg_id,
                    s.section_label AS road,
                    s.start_chainage AS from_ch,
                    s.end_chainage   AS to_ch,
                    s.bin,
                    s.lane_avgs::text AS lane_avgs,
                    s.lane_list,
                    s.lane_count,
                    s.n_rows,
                    s.worst_iri,
                    s.worst_lane,
                    ROUND(s.surveyed_len::numeric, 0) AS surveyed_len,
                    ST_AsMVTGeom(ST_Transform(s.geom, 3857), b.merc, ?, ?, true) AS geom
                FROM iri_2km_segments s, bounds b
                WHERE s.period_id = ?
                  AND s.geom && b.wgs
            )
            """
            + "SELECT ST_AsMVT(src, '" + LAYER_NAME + "', ?, 'geom') FROM src WHERE geom IS NOT NULL";
}
