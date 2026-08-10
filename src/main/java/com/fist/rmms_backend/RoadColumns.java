package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

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
 * <p>Cached for the process lifetime, matching {@code RoadController}'s existing cache: a schema
 * change (new shapefile column) needs an app restart to be picked up, same as a road upload needs
 * {@code POST /api/roads/geojson/refresh} to be picked up today.
 */
@Component
class RoadColumns {

    private static final String SAFE_NAME = "[a-zA-Z][a-zA-Z0-9_]*";

    /** Never in the projection: the surrogate key and the geometry itself (handled separately). */
    private static final List<String> EXCLUDED = List.of("id", "geom");

    private final JdbcTemplate jdbc;
    private volatile List<String> columns;

    RoadColumns(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<String> get() {
        List<String> c = columns;
        if (c != null) return c;
        synchronized (this) {
            if (columns == null) {
                columns = jdbc.queryForList(
                    "SELECT column_name FROM information_schema.columns " +
                    "WHERE table_name = 'roads' ORDER BY ordinal_position",
                    String.class
                ).stream().filter(name -> !EXCLUDED.contains(name)).toList();
            }
            return columns;
        }
    }

    /** The columns as a SELECT-list fragment, aliased under the given table alias. */
    String selectList(String alias) {
        StringBuilder sb = new StringBuilder();
        for (String c : get()) {
            if (!c.matches(SAFE_NAME)) {
                throw new IllegalStateException("unsafe column name from roads schema: " + c);
            }
            sb.append(", ").append(alias).append(".\"").append(c).append("\" AS \"").append(c).append('"');
        }
        return sb.toString();
    }
}
