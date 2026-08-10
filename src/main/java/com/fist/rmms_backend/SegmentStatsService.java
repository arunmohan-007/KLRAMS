package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers the whole-network questions the viewer currently answers by scanning
 * a downloaded array.
 *
 * <p>The viewer asks four things that a vector tile can never answer, because a tile only ever
 * carries the current viewport: how many segments exist, how far a section runs, how many match
 * the condition filters, and where those matches are. Today each is answered by iterating the
 * full segments GeoJSON, which is why that GeoJSON has to be downloaded before the map is useful.
 * Answered here instead, they cost a few hundred bytes.
 *
 * <p>The client-side implementations in {@code 02c-segment-data.js} remain the reference. Every
 * query below is written to match them exactly, including where they are surprising:
 *
 * <ul>
 *   <li>A segment whose value is NULL never matches any comparison. The JS returns false for a
 *       missing value before it compares; SQL yields NULL for the same comparison, which is not
 *       TRUE, so the row drops. Same outcome, and it must stay that way for {@code any} mode too,
 *       where one NULL must not poison an OR that another predicate satisfies.</li>
 *   <li>{@code mode=all} is AND, {@code mode=any} is OR, and an empty predicate list means "no
 *       filter" rather than "match nothing" — the caller distinguishes those, so the controller
 *       does too.</li>
 * </ul>
 */
@Service
public class SegmentStatsService {

    private final JdbcTemplate jdbc;
    private final SurveyPeriodService periods;

    public SegmentStatsService(JdbcTemplate jdbc, SurveyPeriodService periods) {
        this.jdbc = jdbc;
        this.periods = periods;
    }

    /**
     * SQL comparison operators, keyed by the token the filter UI uses.
     *
     * <p>A fixed map rather than a pass-through: the operator is the one part of a filter that
     * lands in SQL as text rather than as a bound parameter, so it can only ever be one of these
     * five strings. The parameter name is checked the same way, against the known column list.
     */
    private static final Map<String, String> OPS = Map.of(
            "gt", ">", "gte", ">=", "lt", "<", "lte", "<=", "eq", "=");

    private boolean built() {
        Boolean b = jdbc.queryForObject(
                "SELECT to_regclass('condition_segments') IS NOT NULL", Boolean.class);
        return Boolean.TRUE.equals(b);
    }

    /** How many segments the period holds. */
    public long count(Integer requestedPeriodId) {
        if (!built()) return 0;
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM condition_segments WHERE period_id = ?",
                Long.class, periods.resolve(requestedPeriodId));
        return n == null ? 0 : n;
    }

    /**
     * The chainage a section spans, as {@code [from, to]}, or {@code [null, null]}.
     *
     * <p>Rounded to whole metres because the caller rounds: the asset register prints these
     * directly and a half-metre would render as a long decimal that means nothing on a chainage.
     */
    public Map<String, Object> chainExtent(String section, Integer requestedPeriodId) {
        Map<String, Object> out = new HashMap<>();
        out.put("from", null);
        out.put("to", null);
        if (!built() || section == null) return out;
        jdbc.query("""
                SELECT round(min(start_chainage)::numeric) AS lo,
                       round(max(end_chainage)::numeric)   AS hi
                FROM condition_segments
                WHERE period_id = ? AND section_label = ?
                """,
                rs -> {
                    Object lo = rs.getObject("lo");
                    if (lo != null) {
                        out.put("from", ((Number) lo).longValue());
                        out.put("to", ((Number) rs.getObject("hi")).longValue());
                    }
                },
                periods.resolve(requestedPeriodId), section);
        return out;
    }

    /**
     * How many segments match the condition filters, and the bounding box of those that do.
     *
     * <p>The count replaces "N of M segments match"; the box replaces the fit-to-matches the map
     * does after a filter settles. Both are what the viewer actually uses the filtered array for —
     * it never renders those features itself, the paint expression does that from the tile.
     *
     * @param specs each {@code param:op:value}, e.g. {@code iri:gt:3}
     * @param mode  {@code all} (AND) or {@code any} (OR)
     */
    public Map<String, Object> match(List<String> specs, String mode, Integer requestedPeriodId) {
        int periodId = periods.resolve(requestedPeriodId);
        Map<String, Object> out = new HashMap<>();
        long total = count(requestedPeriodId);
        out.put("total", total);

        List<String> clauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        args.add(periodId);
        for (String spec : (specs == null ? List.<String>of() : specs)) {
            String[] bits = spec.split(":", 3);
            if (bits.length != 3) throw new IllegalArgumentException("bad filter: " + spec);
            String column = bits[0], op = OPS.get(bits[1]);
            if (!SegmentLaneColumns.PARAMS.contains(column)) {
                throw new IllegalArgumentException("unknown filter field: " + bits[0]);
            }
            if (op == null) throw new IllegalArgumentException("unknown operator: " + bits[1]);
            double value;
            try {
                value = Double.parseDouble(bits[2]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("filter value is not a number: " + bits[2]);
            }
            clauses.add(column + " " + op + " ?");
            args.add(value);
        }

        // No predicates means no filter, which the caller reads as "everything", not "nothing".
        if (clauses.isEmpty()) {
            out.put("count", total);
            out.put("filtered", false);
            out.put("bbox", null);
            return out;
        }

        String joiner = "any".equals(mode) ? " OR " : " AND ";
        String sql = "SELECT count(*) AS n, ST_Extent(geom) AS box FROM condition_segments "
                   + "WHERE period_id = ? AND (" + String.join(joiner, clauses) + ")";

        jdbc.query(sql, rs -> {
            out.put("count", rs.getLong("n"));
            Object box = rs.getObject("box");
            out.put("bbox", box == null ? null : bboxOf(box.toString()));
        }, args.toArray());
        out.put("filtered", true);
        return out;
    }

    /**
     * {@code BOX(xmin ymin,xmax ymax)} to {@code [xmin, ymin, xmax, ymax]}.
     *
     * <p>PostGIS returns its box type as this text form and there is no numeric accessor for it
     * over plain JDBC, so it is parsed rather than cast. Returns null on anything unexpected: a
     * map that does not auto-zoom is a much smaller problem than one that throws.
     */
    private static double[] bboxOf(String box) {
        try {
            String inner = box.substring(box.indexOf('(') + 1, box.indexOf(')'));
            String[] corners = inner.split(",");
            String[] lo = corners[0].trim().split("\\s+");
            String[] hi = corners[1].trim().split("\\s+");
            return new double[]{
                    Double.parseDouble(lo[0]), Double.parseDouble(lo[1]),
                    Double.parseDouble(hi[0]), Double.parseDouble(hi[1])};
        } catch (Exception e) {
            return null;
        }
    }
}
