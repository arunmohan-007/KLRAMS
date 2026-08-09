package com.fist.rmms_backend;

import java.util.List;

/**
 * The single definition of the condition segment's lane/parameter grid.
 *
 * <p>A condition segment carries its distresses twice over: once at segment level (the MAX
 * columns and their {@code avg_*} siblings) and once per carriageway lane, packed into the
 * {@code lane_vals} jsonb as <code>{"CC":{"iri":2.1,...},"CL1":{...}}</code>. The viewer needs
 * that inner object FLATTENED — {@code CC_iri}, {@code CL1_crack} … — because MapLibre paint
 * expressions read a flat property bag, and {@code laneColorExpr()} builds its key as
 * <code>lane + '_' + param</code>.
 *
 * <p>Until now that flattening happened in the browser, in {@code 07-data-loaders.js}, after the
 * GeoJSON landed. A vector tile cannot be post-processed that way — MVT properties must be flat
 * scalars and jsonb cannot ride in a tile at all — so the flattening moves into SQL. That leaves
 * two producers of the same property names (the GeoJSON endpoint and the tile endpoint), and two
 * producers is two chances to disagree. This class exists so there is only one list: both
 * endpoints generate their projection from it, and a lane or parameter added here appears in both
 * or neither.
 *
 * <p>Nothing here changes the stored schema. {@code condition_segments} keeps {@code lane_vals}
 * exactly as {@code buildSegments()} wrote it and the flat names are derived in the SELECT, so
 * this works against a database built before the feature with no migration and no rebuild.
 */
final class SegmentLaneColumns {

    private SegmentLaneColumns() {
    }

    /**
     * Carriageway lanes, in the order the viewer offsets them: centre, then left, then right.
     *
     * <p>These are the {@code XSP} values the condition import accepts, and they match
     * {@code LANE_SLOTS} in {@code 07-data-loaders.js}. They are used to build identifiers, so
     * they must stay inside {@link #SAFE_NAME}.
     */
    static final List<String> LANES = List.of("CC", "CL1", "CL2", "CR1", "CR2");

    /**
     * The surveyed condition parameters, matching {@code PARAMS} in {@code 01-config.js}.
     *
     * <p>Seven, not six: {@code texture} is surveyed and rendered but is deliberately absent from
     * {@code PCI_PARAMS} — it feeds no PCI. Anything reading this list for styling wants all
     * seven; anything scoring PCI must not use it as the PCI input set.
     */
    static final List<String> PARAMS =
            List.of("iri", "crack", "pothole", "rutting", "texture", "patch_work", "ravelling");

    /**
     * The grammar every generated identifier must satisfy before it reaches SQL.
     *
     * <p>The names above are compile-time constants, so this can never fail in practice — which is
     * exactly why it is cheap to assert. The check is here rather than at the declaration because
     * the value that matters is the one next to the interpolation: if a future edit adds a lane or
     * a parameter with a quote or a space in it, the build should stop at the point where that
     * character would have escaped a quoted identifier, not silently produce broken SQL.
     */
    private static final String SAFE_NAME = "[a-zA-Z][a-zA-Z0-9_]*";

    /** Flattened property name for a lane/parameter pair, e.g. {@code CL1_crack}. */
    static String flatName(String lane, String param) {
        return lane + "_" + param;
    }

    /** Presence marker for a lane, e.g. {@code L_CC} — what {@code condLaneFilter()} tests. */
    static String presenceName(String lane) {
        return "L_" + lane;
    }

    private static String checked(String name) {
        if (!name.matches(SAFE_NAME)) {
            throw new IllegalStateException("unsafe generated column name: " + name);
        }
        return name;
    }

    /**
     * The flat lane projection as SQL select-list entries, for a query that has {@code lane_vals}
     * in scope under the given table alias.
     *
     * <p>Emits one {@code double precision} column per lane/parameter pair plus one {@code 1/0}
     * presence marker per lane. See {@link #paramExpr} / {@link #presenceExpr} for why the
     * expressions are shaped the way they are.
     */
    static String flatSelectList(String alias) {
        StringBuilder sb = new StringBuilder();
        for (String lane : LANES) {
            for (String param : PARAMS) {
                String col = checked(flatName(lane, param));
                sb.append(", ").append(paramExpr(alias, lane, param))
                  .append(" AS \"").append(col).append('"');
            }
            String marker = checked(presenceName(lane));
            sb.append(", ").append(presenceExpr(alias, lane))
              .append(" AS \"").append(marker).append('"');
        }
        return sb.toString();
    }

    /**
     * The same flat projection as a single {@code jsonb_build_object(...)} expression, for merging
     * into a GeoJSON properties bag.
     *
     * <p>{@code json_build_object} accepts at most 100 arguments. The segment properties object is
     * already near that ceiling, so the 40 flat lane fields cannot be more key/value pairs in the
     * same call — they have to arrive as a jsonb value and be concatenated on. Forty pairs is 80
     * arguments, which fits here on its own.
     */
    static String flatJsonbObject(String alias) {
        StringBuilder sb = new StringBuilder("jsonb_build_object(");
        boolean first = true;
        for (String lane : LANES) {
            for (String param : PARAMS) {
                String col = checked(flatName(lane, param));
                if (!first) sb.append(", ");
                first = false;
                sb.append('\'').append(col).append("', ").append(paramExpr(alias, lane, param));
            }
            String marker = checked(presenceName(lane));
            sb.append(", '").append(marker).append("', ").append(presenceExpr(alias, lane));
        }
        return sb.append(')').toString();
    }

    /** Just the generated column names, quoted, for an outer SELECT over a subquery. */
    static String flatColumnRefs() {
        StringBuilder sb = new StringBuilder();
        for (String lane : LANES) {
            for (String param : PARAMS) {
                sb.append(", \"").append(checked(flatName(lane, param))).append('"');
            }
            sb.append(", \"").append(checked(presenceName(lane))).append('"');
        }
        return sb.toString();
    }

    /**
     * One flat parameter value pulled out of {@code lane_vals}.
     *
     * <p>{@code ->>} then a cast, rather than {@code ->} alone, so a value stored as a JSON string
     * still arrives as a number. The importer writes numerics, but a hand-repaired row or a
     * restored dump need not have.
     */
    private static String paramExpr(String alias, String lane, String param) {
        return "NULLIF(" + alias + ".lane_vals -> '" + lane + "' ->> '" + param
                + "', '')::double precision";
    }

    /**
     * {@code 1} when the lane key is present in {@code lane_vals}, else {@code 0}.
     *
     * <p>{@code jsonb_exists(...)} rather than the {@code ?} existence operator. {@code ?} is also
     * the JDBC placeholder character, and PostgreSQL's driver would try to bind it — the function
     * form is the only way to ask this question from prepared SQL.
     */
    private static String presenceExpr(String alias, String lane) {
        return "(CASE WHEN jsonb_exists(" + alias + ".lane_vals, '" + lane
                + "') THEN 1 ELSE 0 END)";
    }
}
