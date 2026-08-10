package com.fist.rmms_backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Builds Mapbox Vector Tiles for the road network, straight out of PostGIS.
 *
 * <p>Structurally the same as {@link SegmentTileService} — same bounds CTE, same reason for it
 * (the GIST index, {@code roads_geom_idx}, is built on the untransformed 4326 geometry, so the
 * tile envelope is reprojected once rather than every row) — but the projection comes from
 * {@link RoadColumns} instead of a fixed list, because roads has no closed, known schema the way
 * the condition parameters do.
 *
 * <p>Reuses the {@code app.tile.*} settings {@link SegmentTileService} already defines: extent,
 * buffer and max zoom are MVT protocol conventions, not something specific to one dataset, so
 * every tile endpoint in this app should agree on them rather than drift independently.
 */
@Service
public class RoadTileService {

    static final String LAYER_NAME = "roads";

    private final JdbcTemplate jdbc;
    private final RoadColumns columns;

    private final int extent;
    private final int buffer;
    private final int maxZoom;

    public RoadTileService(JdbcTemplate jdbc,
                           RoadColumns columns,
                           @Value("${app.tile.extent:4096}") int extent,
                           @Value("${app.tile.buffer:64}") int buffer,
                           @Value("${app.tile.max-zoom:20}") int maxZoom) {
        this.jdbc = jdbc;
        this.columns = columns;
        this.extent = extent;
        this.buffer = buffer;
        this.maxZoom = maxZoom;
    }

    int maxZoom() {
        return maxZoom;
    }

    /** One tile's worth of road centrelines, or {@code null} when there is nothing to draw. */
    byte[] tile(TileCoordinate t) {
        String sql =
            """
            WITH bounds AS (
                SELECT merc, ST_Transform(merc, 4326) AS wgs
                FROM (SELECT ST_TileEnvelope(?, ?, ?) AS merc) e
            ),
            src AS (
                /* road / name / len duplicate Section_La / Road_Name / Measrd_Len under the
                   convenience aliases RoadController's GeoJSON already carries — buildPopup()
                   reads props.name and props.len directly, onPick keys the whole app off
                   properties.road, and this tile has to answer to the same property names or
                   every one of those call sites needs a second code path just for tile mode. */
                SELECT r."Section_La" AS road, r."Road_Name" AS name, r."Measrd_Len" AS len
            """
            + columns.selectList("r")
            + """
                    , ST_AsMVTGeom(ST_Transform(r.geom, 3857), b.merc, ?, ?, true) AS geom
                FROM roads r, bounds b
                WHERE r.geom IS NOT NULL
                  AND r.geom && b.wgs
            )
            """
            + "SELECT ST_AsMVT(src, '" + LAYER_NAME + "', ?, 'geom') FROM src WHERE geom IS NOT NULL";

        byte[] tile = jdbc.queryForObject(sql, byte[].class,
                t.z(), t.x(), t.y(), extent, buffer, extent);

        return (tile == null || tile.length == 0) ? null : tile;
    }
}
