package com.fist.rmms_backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Builds Mapbox Vector Tiles for the condition segments, straight out of PostGIS.
 *
 * <p>The map currently downloads every segment as one multi-megabyte GeoJSON on every open. A tile
 * carries only what the current viewport shows, which is the whole point of the exercise — but the
 * two have to describe a segment IDENTICALLY or the map would change appearance the moment it
 * switched sources. That is why the property projection is generated from
 * {@link SegmentLaneColumns} rather than written out here: the GeoJSON endpoint generates its own
 * from the same list, so a lane or parameter added there appears in both or neither.
 *
 * <p>This is deliberately simpler than the equivalent in the water-utility codebase this pattern
 * came from. That one derives its projection at runtime from an attribute catalogue an
 * administrator can edit, which forces an identifier into SQL as text and a matching injection
 * defence around it. KLRAMS has no such catalogue — the column list is a compile-time constant —
 * so no identifier here originates from a request and every value is a bound parameter.
 */
@Service
public class SegmentTileService {

    /** The MVT layer name clients bind to as {@code source-layer}. Changing it breaks every style. */
    static final String LAYER_NAME = "segments";

    private final JdbcTemplate jdbc;
    private final SurveyPeriodService periods;
    private final SegmentService segments;

    private final int extent;
    private final int buffer;
    private final int maxZoom;

    public SegmentTileService(JdbcTemplate jdbc,
                              SurveyPeriodService periods,
                              SegmentService segments,
                              @Value("${app.tile.extent:4096}") int extent,
                              @Value("${app.tile.buffer:64}") int buffer,
                              @Value("${app.tile.max-zoom:20}") int maxZoom) {
        this.jdbc = jdbc;
        this.periods = periods;
        this.segments = segments;
        this.extent = extent;
        this.buffer = buffer;
        this.maxZoom = maxZoom;
    }

    int maxZoom() {
        return maxZoom;
    }

    /**
     * One tile's worth of condition segments, or {@code null} when there is nothing to draw.
     *
     * <p>Null covers three genuinely different "no data" cases that all mean the same thing to a
     * client — the segments table has never been built, the period holds no segments, or the tile
     * simply lands off the network — and the controller turns all of them into 204. A zero-byte
     * 200 would be indistinguishable from a corrupt tile.
     */
    byte[] tile(TileCoordinate t, Integer requestedPeriodId) {
        Boolean built = jdbc.queryForObject(
                "SELECT to_regclass('condition_segments') IS NOT NULL", Boolean.class);
        if (!Boolean.TRUE.equals(built)) return null;

        // Columns + values: a DB built before stored PCI gets NULL pci_def_* until
        // Build Segments (or this backfill). Tile paint has no browser fallback.
        segments.ensureDefaultPci();

        int periodId = periods.resolve(requestedPeriodId);

        byte[] tile = jdbc.queryForObject(TILE_SQL, byte[].class,
                t.z(), t.x(), t.y(), extent, buffer, periodId, extent);

        // ST_AsMVT over zero surviving rows yields an empty buffer, not NULL — normalise both.
        return (tile == null || tile.length == 0) ? null : tile;
    }

    /**
     * The tile query, assembled once at class load because none of it varies per request —
     * extent, buffer, period and the tile address are all bound parameters, in that order.
     *
     * <p>Two envelopes, and the second one is the reason this is fast. {@code ST_AsMVTGeom} needs
     * the tile in Web Mercator, but {@code condition_segments.geom} is 4326 and its GiST index
     * ({@code condition_segments_geom_idx}) is built on the UNTRANSFORMED geometry. Filtering with
     * {@code ST_Transform(s.geom, 3857) && merc} would reproject all 33k rows and use no index at
     * all; filtering with {@code s.geom && wgs} reprojects one envelope and uses the index. Only
     * the rows that survive are then transformed for output.
     *
     * <p>{@code ST_AsMVTGeom} returns NULL for a feature that clips away to nothing, so the outer
     * query drops those rather than emitting empty features.
     */
    private static final String TILE_SQL =
            """
            WITH bounds AS (
                SELECT merc, ST_Transform(merc, 4326) AS wgs
                FROM (SELECT ST_TileEnvelope(?, ?, ?) AS merc) e
            ),
            src AS (
                SELECT
                    s.seg_id,
                    s.section_label   AS road,
                    s.start_chainage  AS from_ch,
                    s.end_chainage    AS to_ch,
                    s.iri, s.crack, s.pothole, s.rutting, s.texture, s.patch_work, s.ravelling,
                    s.avg_iri, s.avg_crack, s.avg_pothole, s.avg_rutting, s.avg_texture,
                    s.avg_patch_work, s.avg_ravelling,
                    s.lane_count, s.xsp_list,
                    s.pci_def_avg, s.pci_def_worst
            """
            + SegmentLaneColumns.flatSelectList("s")
            + """
                    , ST_AsMVTGeom(ST_Transform(s.geom, 3857), b.merc, ?, ?, true) AS geom
                FROM condition_segments s, bounds b
                WHERE s.period_id = ?
                  AND s.geom && b.wgs
            )
            """
            + "SELECT ST_AsMVT(src, '" + LAYER_NAME + "', ?, 'geom') FROM src WHERE geom IS NOT NULL";
}
