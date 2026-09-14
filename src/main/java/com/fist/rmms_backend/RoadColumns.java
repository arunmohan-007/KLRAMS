package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The road network's column list, discovered from the database rather than hard-coded.
 *
 * <p>Unlike {@code condition_segments}, {@code roads} has no jsonb column to flatten — every
 * field is already a scalar, typed column. But it also has no fixed, known list the way
 * {@link SegmentLaneColumns} gave the condition tile: {@link RoadController#buildGeojson} emits
 * whatever columns exist via {@code to_jsonb(r) - 'geom'}, precisely so a shapefile re-import that
 * renames or adds a field doesn't need a matching Java change.
 *
 * <p>The tile projection needs an explicit column list — MVT properties are not a jsonb blob —
 * but a list a developer has to remember to update by hand is exactly the kind of staleness this
 * codebase has already been burned by once (the FWD hide-list in {@code 06-assets.js} is the same
 * shape of bug). So this reads the list from {@code information_schema.columns} at first use and
 * caches it, the same trust boundary {@code RoadController}'s own cache already relies on: it is
 * the database's own catalogue, never request input, so no name here can be attacker-controlled —
 * but every name is still re-checked against {@link #SAFE_NAME} at the point it is interpolated,
 * because a column added by a future migration is not guaranteed to satisfy it.
 *
 * <p>Also classifies each column numeric or not, from its declared SQL type rather than by
 * sniffing values — {@code 05-road-network.js}'s {@code buildAttrMeta()} decides "numeric" by
 * trying to parse every value client-side, which needs the whole network in hand. The column's
 * own type answers the same question from the catalogue alone: {@code Road_Num} is declared
 * {@code bigint}, {@code District} is declared {@code character varying}, and that has never
 * disagreed with the value-sniffed answer for this schema because a road attribute here is either
 * consistently a measurement or consistently a code — never a text column that happens to look
 * numeric for every row so far.
 *
 * <p>Cached for the process lifetime, matching {@code RoadController}'s existing cache: a schema
 * change (new shapefile column) needs an app restart to be picked up, same as a road upload needs
 * {@code POST /api/roads/geojson/refresh} to be picked up today.
 */
@Component
class RoadColumns {

    private static final String SAFE_NAME = "[a-zA-Z][a-zA-Z0-9_]*";

    /** Never in the projection: the surrogate key and the geometry itself (handled separately). */
    private static final List<String> EXCLUDED = List.of("id", "geom");

    /** Postgres type names ({@code information_schema.columns.data_type}) treated as numeric. */
    private static final Set<String> NUMERIC_TYPES = Set.of(
        "bigint", "integer", "smallint", "numeric", "double precision", "real", "decimal");

    private final JdbcTemplate jdbc;
    private volatile List<String> columns;
    private volatile Map<String, Boolean> numeric;

    RoadColumns(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private void load() {
        if (columns != null) return;
        synchronized (this) {
            if (columns != null) return;
            Map<String, Boolean> byName = new LinkedHashMap<>();
            jdbc.query(
                "SELECT column_name, data_type FROM information_schema.columns " +
                "WHERE table_name = 'roads' ORDER BY ordinal_position",
                rs -> {
                    String name = rs.getString("column_name");
                    if (EXCLUDED.contains(name)) return;
                    byName.put(name, NUMERIC_TYPES.contains(rs.getString("data_type")));
                });
            // An empty answer is never cached. It means the catalogue could not see a `roads`
            // table at that instant — mid-import, before the schema was created, or under a role
            // that could not yet see it (information_schema is privilege-filtered). Caching that
            // would pin "this network has no columns" for the life of the PROCESS: an empty
            // colour-by dropdown and an empty Road Network filter, on a map that still draws
            // roads perfectly, because the tiles carry their own geometry and never consult this
            // list. That failure is invisible until someone opens the filter, and survives every
            // cache refresh — only a restart clears it. Leave the fields null and ask again next
            // call instead.
            if (byName.isEmpty()) return;
            numeric = byName;
            columns = List.copyOf(byName.keySet());
        }
    }

    /**
     * Drops the cached column list so the next call re-reads {@code information_schema}.
     *
     * <p>Cleared on the same event as {@link RoadAttrService#clearCache()}: a road upload can add,
     * drop or rename a column, and until this existed the list a tile projection was built from
     * outlived the schema it described.
     */
    void clearCache() {
        synchronized (this) {
            columns = null;
            numeric = null;
        }
    }

    List<String> get() {
        load();
        return columns;
    }

    /** True if {@code attr} is a real, current roads column — the check every request-supplied
     *  attribute name must pass before it is ever interpolated into SQL. */
    boolean isValid(String attr) {
        load();
        return attr != null && numeric.containsKey(attr);
    }

    /** True if the column's declared SQL type is numeric. Caller must have checked
     *  {@link #isValid} first. */
    boolean isNumeric(String attr) {
        load();
        return Boolean.TRUE.equals(numeric.get(attr));
    }

    /** The columns as a SELECT-list fragment, aliased under the given table alias. */
    String selectList(String alias) {
        StringBuilder sb = new StringBuilder();
        for (String c : get()) {
            sb.append(selectOne(alias, c));
        }
        return sb.toString();
    }

    /**
     * One column as a leading-comma SELECT-list fragment, for a projection that names the few
     * columns it wants rather than taking every one.
     *
     * <p>The name is re-checked against {@link #SAFE_NAME} here, next to the interpolation, for the
     * same reason {@link #selectList} checks it: the caller may have gone through
     * {@link #isValid} two calls upstream, but this is the line that would build broken — or
     * injectable — SQL out of a name that does not satisfy it.
     */
    String selectOne(String alias, String column) {
        if (column == null || !column.matches(SAFE_NAME)) {
            throw new IllegalStateException("unsafe column name from roads schema: " + column);
        }
        return ", " + alias + ".\"" + column + "\" AS \"" + column + "\"";
    }

    /**
     * One column as text, trimmed, under a fixed alias — the shape a saved style's lifted
     * {@code __style} / {@code __label} property needs.
     *
     * <p>Text because a MapLibre {@code match} compares strings and its {@code to-number} parses
     * a numeric string back, so one projection serves a class list and a numeric band alike.
     *
     * <p>{@code btrim} because this is the only place in the chain where a trim is available at
     * all: the style spec has no {@code ["trim"]}, so a value stored as {@code "SH "} can never
     * be matched against a class list browser-side. It is stripped here instead.
     *
     * <p>{@code as} is a literal this class chooses, never request input; {@code column} is
     * re-checked against {@link #SAFE_NAME} for the same reason {@link #selectOne} checks it.
     */
    String selectAs(String alias, String column, String as) {
        return ", " + trimmedText(alias, column) + " AS \"" + as + "\"";
    }

    /**
     * One column as a trimmed text SQL expression, for a projection that has to build the value
     * inline rather than as a SELECT-list entry — {@code jsonb_build_object} in
     * {@code RoadController.buildGeojson}, which needs the same lifted value the tile carries.
     *
     * <p>{@code column} is re-checked against {@link #SAFE_NAME} here, next to the interpolation,
     * for the same reason {@link #selectOne} checks it.
     */
    String trimmedText(String alias, String column) {
        if (column == null || !column.matches(SAFE_NAME)) {
            throw new IllegalStateException("unsafe column name from roads schema: " + column);
        }
        return "btrim(" + alias + ".\"" + column + "\"::text)";
    }
}
