package com.fist.rmms_backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A stable fingerprint of every dashboard's output, for verifying a refactor changed nothing.
 *
 * <p>The dashboards produce the figures the RMMS cell publishes, and they are built from SQL
 * that names the road network's columns. Replacing those names with catalogue lookups cannot
 * be verified by compiling: a lookup that resolves to the wrong column still produces valid
 * SQL, and a join that then matches nothing yields zeroes — a dashboard full of plausible,
 * wrong numbers.
 *
 * <p>So this prints a digest of each endpoint's whole response. Run it before a change, run it
 * after, and compare: identical digests mean every number the dashboards report is unchanged,
 * without needing to enumerate what those numbers are.
 *
 * <p>Deliberately not an assertion against a stored baseline. The figures move whenever data is
 * imported, so a checked-in expected value would be wrong within a week and would start failing
 * for reasons that have nothing to do with the code.
 */
@SpringBootTest
class DashboardDigestIT {

    @Autowired DashboardController network;
    @Autowired ConditionDashboardController condition;
    @Autowired FwdDashboardController fwd;
    @Autowired TrafficDashboardController traffic;
    @Autowired SurveyDashboardController survey;
    @Autowired CalcRuleService rules;

    /**
     * The response's STRUCTURE with every value stripped: map keys and collection sizes only.
     *
     * <p>This is the part that must never change, and the part a mis-resolved column actually
     * breaks — a join matching nothing empties a list or drops a bucket, which shows here
     * whatever the arithmetic does. Unlike the figures, it does not depend on row order or on
     * floating-point association, so it is exactly reproducible run to run.
     */
    private static String shape(Object v) {
        StringBuilder sb = new StringBuilder();
        shape(sb, v);
        return hash(sb.toString()) + " (" + sb.length() + ")";
    }

    private static void shape(StringBuilder sb, Object v) {
        if (v instanceof Map<?, ?> m) {
            sb.append('{');
            new java.util.TreeMap<>(toStringKeys(m)).forEach((k, val) -> {
                sb.append(k).append(':');
                shape(sb, val);
                sb.append(',');
            });
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            int n = 0;
            Object first = null;
            for (Object o : it) { if (n == 0) first = o; n++; }
            sb.append("[n=").append(n);
            if (first != null) { sb.append(' '); shape(sb, first); }
            sb.append(']');
        } else {
            sb.append(v == null ? "null" : "v");
        }
    }

    private static String hash(String text) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A short, order-stable hash of a response, plus its size, so a difference is obvious. */
    private static String digest(Object value) {
        String text = render(value);
        return hash(text) + " (" + text.length() + " chars)";
    }

    /**
     * Renders a response deterministically. Maps are sorted by key — several dashboards build
     * their results into HashMaps, whose iteration order is stable within a JVM but is not part
     * of the answer, and sorting keeps that from showing up as a false difference.
     */
    private static String render(Object v) {
        StringBuilder sb = new StringBuilder();
        append(sb, v);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object v) {
        if (v instanceof Map<?, ?> m) {
            sb.append('{');
            new java.util.TreeMap<>(toStringKeys(m)).forEach((k, val) -> {
                sb.append(k).append('=');
                append(sb, val);
                sb.append(',');
            });
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            for (Object o : it) { append(sb, o); sb.append(','); }
            sb.append(']');
        } else if (v instanceof Number n) {
            /* Rounded hard, and to ONE decimal.

               Several dashboards average or sum over rows Postgres returns in no guaranteed
               order, and floating-point addition is not associative, so the same data yields
               figures that differ in the last bits between runs. At three decimals that showed
               up as a different digest — and briefly as a refactor appearing to change the
               traffic dashboard, which it had not touched at all.

               One decimal is finer than any figure these dashboards publish and coarse enough
               to be stable. SHAPE, which is what actually catches a mis-resolved column, is
               digested separately and carries no values at all. */
            sb.append(Math.round(n.doubleValue() * 10) / 10.0);
        } else {
            sb.append(v);
        }
    }

    private static Map<String, Object> toStringKeys(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    @Test
    void printEveryDashboardDigest() {
        Map<String, Object> responses = new LinkedHashMap<>();
        responses.put("network/summary", network.summary());
        responses.put("network/longest", network.longest(null));
        responses.put("condition/summary", condition.summary("iri", "avg", null));
        responses.put("fwd/summary", fwd.summary());
        responses.put("fwd/unmapped", fwd.unmapped(null));
        responses.put("traffic/summary", traffic.summary());
        responses.put("survey/summary", survey.summary());

        /* The queries the sweep actually rewrote: everything built from CalcRuleService's
           CORR / LONG_CORR / RULE_JOINS fragments, plus the condition tables that join roads.
           The summaries above would pass even if these were broken. */
        responses.put("condition/table", condition.table("iri", "avg", "gte", 0,
                null, null, null, null, 200));
        responses.put("condition/top-roads", condition.topRoads("iri", "avg", null, null, 10, 5, 5, 5));
        responses.put("network/cons-type-sections", network.consTypeSections(null, null));
        responses.put("rules/carriageway-effect", rules.carriagewayEffect());
        responses.put("rules/width-effect", rules.widthEffect());
        responses.put("rules/station-effect", rules.stationEffect());
        responses.put("rules/carriageway-groups", rules.carriagewayGroups());
        responses.put("rules/station-groups", rules.stationGroups());

        Map<String, String> digests = new LinkedHashMap<>();
        Map<String, String> shapes = new LinkedHashMap<>();
        responses.forEach((k, v) -> { digests.put(k, digest(v)); shapes.put(k, shape(v)); });

        System.out.println("---- DASHBOARD DIGESTS ----");
        digests.forEach((k, v) -> System.out.printf("[digest] %-22s %s%n", k, v));
        System.out.println("---- SHAPES (must be exactly reproducible) ----");
        shapes.forEach((k, v) -> System.out.printf("[shape]  %-22s %s%n", k, v));
        System.out.println("---- END ----");

        /* A SUMMARY is a map of figures and must never come back empty — that is exactly what
           a mis-resolved column produces, since the join still runs and simply matches nothing.

           A LIST endpoint is judged only on having executed. Several legitimately return
           nothing: /unmapped lists rows that could NOT be matched, so empty is its healthy
           state, and cons-type-sections asked with no filter has nothing to select. Asserting
           otherwise would demand a data fault in order to pass. */
        responses.forEach((k, v) -> {
            if (v instanceof Map<?, ?> m) {
                assertFalse(m.isEmpty(), k + " returned an empty summary — its query matched nothing");
            } else {
                assertNotNull(v, k + " returned null");
            }
        });
    }
}
