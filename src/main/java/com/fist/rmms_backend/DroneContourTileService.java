package com.fist.rmms_backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Contours as Mapbox Vector Tiles, the same way every other KLRAMS line layer is
 * served.
 *
 * <p>Simplified per zoom in the query rather than at trace time. The traced geometry
 * is the survey's own detail and is kept; what a 1:5000 view needs is a thinned copy
 * of it, and the tolerance for that depends on the zoom being drawn. Simplifying
 * once on the way in would throw away detail that the deepest zoom wants.
 */
@Service
class DroneContourTileService {

    private static final String LAYER_NAME = "contours";

    private final JdbcTemplate jdbc;
    private final int extent;
    private final int buffer;

    DroneContourTileService(JdbcTemplate jdbc,
                            @Value("${app.tile.extent:4096}") int extent,
                            @Value("${app.tile.buffer:64}") int buffer) {
        this.jdbc = jdbc;
        this.extent = extent;
        this.buffer = buffer;
    }

    /** One tile of a dataset's contours, or null when the tile holds none. */
    byte[] tile(int datasetId, TileCoordinate t) {
        byte[] tile = jdbc.queryForObject("""
            WITH bounds AS (
                SELECT merc, ST_Transform(merc, 4326) AS wgs
                FROM (SELECT ST_TileEnvelope(?, ?, ?) AS merc) e
            ),
            src AS (
                SELECT c.elevation,
                       c.is_index,
                       ST_AsMVTGeom(
                           ST_Transform(
                               /* Tolerance in degrees, halving with each zoom level.
                                  At z22 it is ~2 mm and effectively a no-op; by z14
                                  it is ~4 m, which is what stops a dense DEM's
                                  contours from filling an overview tile. */
                               ST_SimplifyPreserveTopology(c.geom, 0.5 / power(2, ?)),
                               3857),
                           b.merc, ?, ?, true) AS geom
                FROM drone_contour c, bounds b
                WHERE c.dataset_id = ?
                  AND c.geom && b.wgs
            )
            SELECT ST_AsMVT(src, ?, ?, 'geom') FROM src WHERE geom IS NOT NULL
            """, byte[].class,
            t.z(), t.x(), t.y(), t.z(), extent, buffer, datasetId, LAYER_NAME, extent);

        return (tile == null || tile.length == 0) ? null : tile;
    }
}
