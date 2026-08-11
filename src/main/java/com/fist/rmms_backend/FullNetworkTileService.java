package com.fist.rmms_backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Builds Mapbox Vector Tiles for the Full Road Network (by road name), straight out of
 * PostGIS.
 *
 * <p>Same bounds/index pattern as {@link RoadTileService}: the GiST on
 * {@code full_road_network.geom} is on untransformed 4326 geometry, so the tile envelope
 * is reprojected once and used for the index hit; only survivors are transformed for
 * {@code ST_AsMVTGeom}.
 *
 * <p>Property names match what {@code 22-road-merged.js} already reads: {@code id} (for
 * {@code promoteId} / selection), {@code road_key}, {@code road_name}, {@code road_num},
 * {@code len} (full geography length — tile-clipped geom must not be measured in the
 * browser), and {@code props_json} (shapefile attrs as text, same idea as asset
 * {@code attrs_json}).
 */
@Service
public class FullNetworkTileService {

    /** The MVT layer name clients bind to as {@code source-layer}. Changing it breaks the style. */
    static final String LAYER_NAME = "fullroads";

    private final JdbcTemplate jdbc;

    private final int extent;
    private final int buffer;
    private final int maxZoom;

    public FullNetworkTileService(JdbcTemplate jdbc,
                                  @Value("${app.tile.extent:4096}") int extent,
                                  @Value("${app.tile.buffer:64}") int buffer,
                                  @Value("${app.tile.max-zoom:20}") int maxZoom) {
        this.jdbc = jdbc;
        this.extent = extent;
        this.buffer = buffer;
        this.maxZoom = maxZoom;
    }

    int maxZoom() {
        return maxZoom;
    }

    /**
     * One tile's worth of full-road centrelines, or {@code null} when there is nothing to
     * draw (empty table or tile off the network). The controller turns null into 204.
     */
    byte[] tile(TileCoordinate t) {
        byte[] tile = jdbc.queryForObject(TILE_SQL, byte[].class,
                t.z(), t.x(), t.y(), extent, buffer, extent);

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
                    f.id,
                    f.road_key,
                    f.road_name,
                    f.road_num,
                    ROUND(ST_Length(f.geom::geography)) AS len,
                    f.props::text AS props_json,
                    ST_AsMVTGeom(ST_Transform(f.geom, 3857), b.merc, ?, ?, true) AS geom
                FROM full_road_network f, bounds b
                WHERE f.geom IS NOT NULL
                  AND f.geom && b.wgs
            )
            """
            + "SELECT ST_AsMVT(src, '" + LAYER_NAME + "', ?, 'geom') FROM src WHERE geom IS NOT NULL";
}
