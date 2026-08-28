package com.fist.rmms_backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builds Mapbox Vector Tiles for user-created and temporary layers.
 *
 * <p>Same bounds/index pattern as {@link IriTileService} and
 * {@link SegmentTileService}: the GiST index on each user table's {@code geom}
 * is built on untransformed 4326 geometry, so the tile envelope is reprojected
 * once and used for the index hit, and only the survivors are transformed for
 * {@code ST_AsMVTGeom}.
 *
 * <h2>Why this one interpolates a table name</h2>
 * Every other tile service targets a table known at compile time. A user layer's
 * table is not — it is {@code physical_table} on its {@code layer_definition}
 * row. That name is generated server-side by {@link LayerRegistryService} as
 * {@code ul_<id>_<slug>} and never supplied by a caller, but it is still
 * re-checked against {@link #SAFE_TABLE} at the point it is concatenated, for
 * the same reason {@link RoadColumns#selectList} re-checks names that came from
 * the database's own catalogue: the value is trusted, the interpolation is not.
 *
 * <p>The MVT layer name is {@code features} for every user layer rather than the
 * layer's own name. The source id already identifies which layer a tile belongs
 * to ({@code ul-<id>}), so a per-layer {@code source-layer} would force the
 * client to know two identifiers where one will do — and layer names can be
 * renamed, which would silently break a style that pinned the old one.
 */
@Service
public class UserLayerTileService {

    /** The MVT layer name clients bind to as {@code source-layer}. */
    static final String LAYER_NAME = "features";

    /** Matches only the names {@code LayerRegistryService} generates. */
    private static final Pattern SAFE_TABLE = Pattern.compile("^ul_[0-9]+_[a-z0-9_]{1,40}$");

    private final JdbcTemplate jdbc;
    private final SurveyPeriodService periods;
    private final LayerStyleService styles;

    private final int extent;
    private final int buffer;
    private final int maxZoom;

    public UserLayerTileService(JdbcTemplate jdbc,
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
     * One tile of a user layer, or {@code null} when there is nothing to draw —
     * unknown layer, table never created, or a tile off the data. The controller
     * turns null into 204.
     *
     * <p>{@code user} is the caller's name: a temporary layer is only tiled for
     * whoever created it, so the visibility rule that
     * {@link LayerDataService#viewerLayers} applies to the list is applied again
     * here. Without it, a temporary layer would be hidden from the panel but
     * still readable by anyone who guessed the URL.
     */
    byte[] tile(int layerId, TileCoordinate t, String user, Integer requestedPeriodId) {
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap(
                    "SELECT layer_key, physical_table, temporary, created_by, frozen, hidden, "
                  + "period_scoped FROM layer_definition WHERE id = ? AND source_type = 'USER'", layerId);
        } catch (Exception e) {
            return null;      // no such user layer
        }

        // Frozen data is not used for anything, and hidden data is not drawn on
        // the map — both include tiles. The check belongs here and not only in
        // the layer list, or a frozen/hidden layer would still paint for anyone
        // whose style already held its source.
        if (Boolean.TRUE.equals(row.get("frozen")) || Boolean.TRUE.equals(row.get("hidden"))) return null;

        Object tableObj = row.get("physical_table");
        if (tableObj == null) return null;
        String table = String.valueOf(tableObj);
        if (!SAFE_TABLE.matcher(table).matches()) return null;

        if (Boolean.TRUE.equals(row.get("temporary"))
                && !String.valueOf(row.get("created_by")).equals(user)) {
            return null;
        }

        Boolean built = jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table);
        if (!Boolean.TRUE.equals(built)) return null;

        boolean scoped = Boolean.TRUE.equals(row.get("period_scoped"));
        // The attribute this layer is coloured and labelled by, lifted out of
        // the jsonb bag so a MapLibre expression can read it — see
        // LayerStyleService.tileKeys(). Both are null for an unstyled layer, in
        // which case the two columns come back null and cost nothing.
        String[] keys = styles.tileKeys(String.valueOf(row.get("layer_key")));
        byte[] tile = scoped
                ? jdbc.queryForObject(sql(table, true), byte[].class,
                        t.z(), t.x(), t.y(), keys[0], keys[1],
                        extent, buffer, periods.resolve(requestedPeriodId), extent)
                : jdbc.queryForObject(sql(table, false), byte[].class,
                        t.z(), t.x(), t.y(), keys[0], keys[1], extent, buffer, extent);

        return (tile == null || tile.length == 0) ? null : tile;
    }

    /**
     * The tile query for one user table.
     *
     * <p>{@code attrs} is a jsonb bag whose keys differ per layer, and MVT
     * properties must be flat scalars — jsonb cannot ride in a tile. Rather than
     * discovering each layer's attribute list and building a projection per
     * layer, the whole bag is emitted as one text property and expanded by the
     * client, which is what the popup wants anyway. {@code lane_avgs} in
     * {@link IriTileService} already ships as text for the same reason.
     *
     * <p>The two exceptions are {@code __style} and {@code __label}: the client
     * CAN read a flat property in a paint expression and cannot read inside the
     * JSON text, so the one attribute the layer is coloured by and the one it is
     * labelled with are lifted out alongside. They ride under fixed names so the
     * attribute is a bind parameter — no key from the style document is ever
     * concatenated into this SQL. The {@code ::text} cast is what lets a null
     * key bind at all; without it Postgres cannot infer the parameter's type.
     */
    private static String sql(String table, boolean periodScoped) {
        return """
            WITH bounds AS (
                SELECT merc, ST_Transform(merc, 4326) AS wgs
                FROM (SELECT ST_TileEnvelope(?, ?, ?) AS merc) e
            ),
            src AS (
                SELECT
                    t.id,
                    COALESCE(t.attrs, '{}'::jsonb)::text AS attrs,
                    t.attrs->>(?::text) AS __style,
                    t.attrs->>(?::text) AS __label,
                    ST_AsMVTGeom(ST_Transform(t.geom, 3857), b.merc, ?, ?, true) AS geom
                FROM %s t, bounds b
                WHERE t.geom IS NOT NULL
                  %s
                  AND t.geom && b.wgs
            )
            """.formatted(table, periodScoped ? "AND t.period_id = ?" : "")
            + "SELECT ST_AsMVT(src, '" + LAYER_NAME + "', ?, 'geom') FROM src WHERE geom IS NOT NULL";
    }
}
