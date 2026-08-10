package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers the one question a vector tile genuinely cannot answer for the road network: what are
 * the distinct values of an attribute, across every road, so the map can be coloured by it.
 *
 * <p>{@code buildAttrMeta()} in {@code 05-road-network.js} answers this today by scanning
 * {@code gj.features} — every property of every road — because a MapLibre {@code match}
 * expression has to have every category and its colour baked in before the first tile is even
 * requested; a paint expression cannot compute "what values exist" from data it hasn't seen yet.
 * That scan is exactly the kind of whole-network read a tile can never serve, so it is answered
 * from SQL instead: a {@code GROUP BY} over one column, not a download of the network.
 */
@Service
public class RoadAttrService {

    private static final String SAFE_NAME = "[a-zA-Z][a-zA-Z0-9_]*";

    private final JdbcTemplate jdbc;
    private final RoadColumns columns;

    /** Distinct values tracked per attribute. 05-road-network.js caps at the same number for the
     *  same reason: a categorical palette beyond a few hundred entries stops being a legend and
     *  starts being a wall of colour swatches nobody can read. */
    private static final int MAX_DISTINCT = 400;

    public RoadAttrService(JdbcTemplate jdbc, RoadColumns columns) {
        this.jdbc = jdbc;
        this.columns = columns;
    }

    /** Every colourable attribute, and whether the client should treat it as numeric or
     *  categorical — the same set {@code buildAttrMeta} offers today (every real column; the
     *  road/name/len convenience aliases and the surrogate id are never colourable attributes). */
    public List<Map<String, Object>> attrs() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String c : columns.get()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("attr", c);
            row.put("numeric", columns.isNumeric(c));
            out.add(row);
        }
        return out;
    }

    /**
     * Stats for one attribute: {@code {numeric, min, max}} for a numeric column, or
     * {@code {numeric, values, valuesByFreq}} for a categorical one — {@code values} sorted
     * alphabetically for a filter dropdown, {@code valuesByFreq} sorted by how much of the
     * network carries that value, for a legend where the values covering the most road get a
     * colour before rarer ones do.
     *
     * @throws IllegalArgumentException if {@code attr} is not a real, current roads column
     */
    public Map<String, Object> meta(String attr) {
        if (!columns.isValid(attr)) {
            throw new IllegalArgumentException("unknown road attribute: " + attr);
        }
        // Belt and braces: isValid() already restricts attr to a known column name, but the
        // check that matters is the one right next to the interpolation below, not the one two
        // calls upstream — same reasoning as SegmentLaneColumns.checked().
        if (!attr.matches(SAFE_NAME)) {
            throw new IllegalStateException("unsafe column name from roads schema: " + attr);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        boolean numeric = columns.isNumeric(attr);
        out.put("numeric", numeric);

        if (numeric) {
            jdbc.query("SELECT min(\"" + attr + "\") AS lo, max(\"" + attr + "\") AS hi FROM roads",
                rs -> {
                    Object lo = rs.getObject("lo"), hi = rs.getObject("hi");
                    out.put("min", lo == null ? null : ((Number) lo).doubleValue());
                    out.put("max", hi == null ? null : ((Number) hi).doubleValue());
                });
            return out;
        }

        List<String> valuesByFreq = new ArrayList<>();
        jdbc.query(
            "SELECT \"" + attr + "\" AS v, count(*) AS n FROM roads " +
            "WHERE \"" + attr + "\" IS NOT NULL AND \"" + attr + "\"::text <> '' " +
            "GROUP BY \"" + attr + "\" ORDER BY n DESC, v LIMIT ?",
            rs -> { valuesByFreq.add(rs.getString("v")); },
            MAX_DISTINCT);
        out.put("valuesByFreq", valuesByFreq);
        out.put("values", valuesByFreq.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
        return out;
    }
}
