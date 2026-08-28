package com.fist.rmms_backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Builds Mapbox Vector Tiles for the seven "simple" {@code road_assets} types, straight out of
 * PostGIS. Same shape as {@link SegmentTileService} / {@link RoadTileService}: additive, next to
 * the untouched {@code /api/assets/{type}/geojson}.
 *
 * <p>FWD is served by {@link FwdTileService} (same URL under {@link AssetTileController}) because
 * its tiles also stamp network-wide {@code __d0}/{@code __dscale} for the deflection colour
 * legend. Upload now stores FWD as a LINE stretch ({@code ST_LineSubstring} of From..To), so the
 * tile paints stored geom rather than a client-side re-derivation.
 *
 * <p>{@code attrs} is free-form per CSV upload — unlike the condition/road columns, there is no
 * fixed catalogue to project column-by-column, so it ships as one JSON-text property
 * ({@code attrs_json}) the client parses back into the flat shape {@code assetPopup()} already
 * expects, mirroring the flattening the GeoJSON endpoint does with {@code || attrs} in SQL.
 */
@Service
public class AssetTileService {

    static final String LAYER_NAME = "assets";

    /** Types whose stored geom is already correct for direct MVT rendering. FWD uses
     *  {@link FwdTileService} for the extra D0 colour properties. */
    static final Set<String> TILED_TYPES = Set.of(
            "bridge", "furniture_line", "culvert", "furniture_point",
            "subgrade", "bituminous_core", "pavement_crust");

    /** Field-survey streams are scoped to a survey period; permanent inventory is not — same
     *  split as {@link AssetController}'s SURVEY_TYPES, minus fwd (handled by FwdTileService). */
    private static final Set<String> SURVEY_TYPES = Set.of("subgrade", "bituminous_core", "pavement_crust");

    /** Types stored as a LINE stretch by {@code ST_LineSubstring} — mirrors
     *  {@link AssetController}'s LINE_TYPES (FWD included there, but tiled separately). */
    private static final Set<String> LINE_TYPES = Set.of("bridge", "furniture_line");

    private final JdbcTemplate jdbc;
    private final SurveyPeriodService periods;
    private final LayerStyleService styles;

    private final int extent;
    private final int buffer;
    private final int maxZoom;

    public AssetTileService(JdbcTemplate jdbc,
                             SurveyPeriodService periods,
                             LayerStyleService styles,
                             @Value("${app.tile.extent:4096}") int extent,
                             @Value("${app.tile.buffer:64}") int buffer,
                             @Value("${app.tile.max-zoom:20}") int maxZoom) {
        this.jdbc = jdbc;
        this.periods = periods;
        this.styles = styles;
        this.extent = extent;
        this.buffer = buffer;
        this.maxZoom = maxZoom;
    }

    int maxZoom() {
        return maxZoom;
    }

    /**
     * Does this type carry any chainage RANGE rows (an end chainage past the start)?
     *
     * <p>The client needs this before it builds a layer, and it is the one question that decides
     * whether a tile can represent the type at all. {@code AssetController.upload()} stores a point
     * type's geom as a single {@code ST_LineInterpolatePoint} at the START chainage; when the rows
     * are really ranges, the viewer does not draw that point — {@code linRefFeatures()} re-derives a
     * STRETCH along the centreline client-side and draws that instead. A tile built from the stored
     * geom would therefore show dots where the map shows lines.
     *
     * <p>Rather than duplicate the linear referencing here (which would also break the tile's bbox
     * filter, since a long stretch can cover a tile its start point falls outside of — the same
     * reason FWD is excluded), the client asks this and falls back to the GeoJSON path for a type
     * that answers true. No current dataset does; this keeps a future upload from silently
     * rendering the wrong geometry.
     */
    boolean hasRangeRows(String type, Integer requestedPeriodId) {
        if (!TILED_TYPES.contains(type)) return false;
        // A LINE type is a range by definition, and upload() already stored its geometry as the
        // ST_LineSubstring stretch — so it is always tileable, and the viewer never re-derives it
        // (linRefFeatures is gated on kind==='point'). Only a POINT type can diverge, so asking the
        // question of a line type would needlessly force the biggest payloads back onto GeoJSON.
        if (LINE_TYPES.contains(type)) return false;
        Boolean built = jdbc.queryForObject("SELECT to_regclass('road_assets') IS NOT NULL", Boolean.class);
        if (!Boolean.TRUE.equals(built)) return false;

        String sql = "SELECT EXISTS (SELECT 1 FROM road_assets WHERE asset_type = ?"
                + " AND geom IS NOT NULL AND end_chainage IS NOT NULL AND end_chainage > start_chainage"
                + (SURVEY_TYPES.contains(type) ? " AND period_id = ?" : "") + ")";
        Boolean any = SURVEY_TYPES.contains(type)
                ? jdbc.queryForObject(sql, Boolean.class, type, periods.resolve(requestedPeriodId))
                : jdbc.queryForObject(sql, Boolean.class, type);
        return Boolean.TRUE.equals(any);
    }

    /**
     * One tile's worth of one asset type, or {@code null} when there is nothing to draw — an
     * unknown/untiled type, a database built before {@code road_assets} existed, or a tile that
     * simply lands off the network.
     */
    byte[] tile(TileCoordinate t, String type, Integer requestedPeriodId) {
        if (!TILED_TYPES.contains(type)) return null;

        Boolean built = jdbc.queryForObject("SELECT to_regclass('road_assets') IS NOT NULL", Boolean.class);
        if (!Boolean.TRUE.equals(built)) return null;

        boolean isSurvey = SURVEY_TYPES.contains(type);

        // __sec duplicates `road` under the name the network-scope filter (05-road-network.js,
        // scopePropFor) already reads on every as-* layer — emitted directly rather than left
        // for the client to derive, so the GeoJSON path's pickProp() fuzzy-matching has nothing
        // to disagree with.
        String sql =
                """
                WITH bounds AS (
                    SELECT merc, ST_Transform(merc, 4326) AS wgs
                    FROM (SELECT ST_TileEnvelope(?, ?, ?) AS merc) e
                ),
                src AS (
                    SELECT
                        a.id              AS asset_id,
                        a.section_label   AS road,
                        a.section_label   AS __sec,
                        a.start_chainage  AS from_ch,
                        a.end_chainage    AS to_ch,
                        a.attrs::text     AS attrs_json,
                        a.attrs->>(?::text) AS __style,
                        a.attrs->>(?::text) AS __label,
                        ST_AsMVTGeom(ST_Transform(a.geom, 3857), b.merc, ?, ?, true) AS geom
                    FROM road_assets a, bounds b
                    WHERE a.asset_type = ?
                      AND a.geom IS NOT NULL
                      AND a.geom && b.wgs
                """
                + (isSurvey ? "      AND a.period_id = ?\n" : "")
                + """
                )
                """
                + "SELECT ST_AsMVT(src, '" + LAYER_NAME + "', ?, 'geom') FROM src WHERE geom IS NOT NULL";

        /* The attribute this asset type is coloured and labelled by, lifted out
           of the free-form attrs bag so a paint expression can read it — an MVT
           property has to be a flat scalar, and attrs_json is a string as far as
           MapLibre is concerned. Both null for an unstyled type, which costs a
           pair of null columns and nothing else. */
        String[] keys = styles.tileKeys(type);

        Object[] args = isSurvey
                ? new Object[]{t.z(), t.x(), t.y(), keys[0], keys[1], extent, buffer, type,
                               periods.resolve(requestedPeriodId), extent}
                : new Object[]{t.z(), t.x(), t.y(), keys[0], keys[1], extent, buffer, type, extent};

        byte[] tile = jdbc.queryForObject(sql, byte[].class, args);

        // ST_AsMVT over zero surviving rows yields an empty buffer, not NULL — normalise both.
        return (tile == null || tile.length == 0) ? null : tile;
    }
}
