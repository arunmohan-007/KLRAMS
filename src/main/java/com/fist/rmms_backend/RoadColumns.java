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
            numeric = byName;
            columns = List.copyOf(byName.keySet());
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
}
