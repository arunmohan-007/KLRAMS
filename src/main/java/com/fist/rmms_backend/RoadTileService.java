package com.fist.rmms_backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** The column every road layer paints by default: {@code roadClassKey()} in
     *  {@code 04-geo-helpers-boundaries.js} drives colour, width, casing and sort order from it. */
    private static final String CLASS_COLUMN = "Road_Class";

    /**
     * One tile's worth of road centrelines, or {@code null} when there is nothing to draw.
     *
     * @param attr an extra column to carry because the viewer is colouring by it, or {@code null}
     *             for the default class colouring. Must already have passed
     *             {@link RoadColumns#isValid}.
     */
    byte[] tile(TileCoordinate t, String attr) {
        byte[] tile = jdbc.queryForObject(sqlFor(attr), byte[].class,
                t.z(), t.x(), t.y(), extent, buffer, extent);

        return (tile == null || tile.length == 0) ? null : tile;
    }

    /**
     * The tile query, cached per colour-by attribute.
     *
     * <p>This used to project {@link RoadColumns#selectList} — every one of the roads table's 29
     * columns, on every road, in every tile, at every zoom. In an MVT the per-feature tag list is
     * the dominant cost, so that was most of the tile, and nothing rendered read those columns: the
     * paint expressions read {@code Road_Class} (or the one attribute the user is colouring by),
     * the popup reads {@code road}/{@code name}/{@code len}, and every whole-network question —
     * search, the attribute filter's value pickers, the asset register — is answered from
     * {@code /api/roads/index}, which carries all 29 without geometry for 92 KB once per login.
     *
     * <p>So a tile carries four properties, plus a fifth when {@code ?attr=} names a column outside
     * that set. The Road Network filter no longer needs its attribute in the tile either: it scopes
     * the road layers by matched section label ({@code applyNetScope}), the same mechanism that
     * already scoped {@code roadnet-hit} and every road-linked data layer.
     */
    private final Map<String, String> sqlByAttr = new ConcurrentHashMap<>();

    private String sqlFor(String attr) {
        return sqlByAttr.computeIfAbsent(attr == null ? "" : attr, a -> {
            StringBuilder extra = new StringBuilder();
            // Road_Class only if the schema still has it — a re-imported shapefile could drop or
            // rename it, and the paint already falls back to its own default for a missing key.
            if (columns.isValid(CLASS_COLUMN)) extra.append(columns.selectOne("r", CLASS_COLUMN));
            if (!a.isEmpty() && !a.equals(CLASS_COLUMN)) extra.append(columns.selectOne("r", a));
            return
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
                + extra
                + """
                        , ST_AsMVTGeom(ST_Transform(r.geom, 3857), b.merc, ?, ?, true) AS geom
                    FROM roads r, bounds b
                    WHERE r.geom IS NOT NULL
                      AND r.geom && b.wgs
                )
                """
                + "SELECT ST_AsMVT(src, '" + LAYER_NAME + "', ?, 'geom') FROM src WHERE geom IS NOT NULL";
        });
    }
}
