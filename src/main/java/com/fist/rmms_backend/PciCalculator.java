package com.fist.rmms_backend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IRC:82-2023 Pavement Condition Index — Java port of the browser engine in
 * js/14-pci-engine.js, used to store a PCI per segment at build time.
 *
 * The weights and good/poor thresholds are the IRC:82-2023 numbers, editable in
 * the Calculation Rules module and pushed in here at startup by
 * {@link CalcRuleService}. The viewer reads the same saved numbers (see
 * js/00-calc-rules.js), so the map, the report and the stored value agree.
 *
 * <b>The stored value is only as fresh as the last segment build.</b> Editing
 * these numbers does not rewrite condition_segments — {@link SegmentService}
 * must rebuild, which is why the module asks for one after a save. A user who
 * nudges the weights inside the viewer is a separate, unsaved case: the browser
 * recomputes on the fly and ignores what is stored here.
 *
 * PCI is a NON-LINEAR function of the distresses, so aggregation order matters
 * (see the build-75 note in 14-pci-engine.js):
 *   - Worst-Lane PCI = MIN of the per-lane PCIs
 *   - Composite PCI  = pool the lane distresses first, then compute ONE PCI
 */
final class PciCalculator {

    private PciCalculator() {}

    /** The parameters PCI is built from, in PCI_PARAMS order. The SET is fixed —
     *  only the weights and thresholds are editable. */
    static final List<String> PARAM_KEYS =
        List.of("crack", "ravelling", "pothole", "patch_work", "rutting", "iri");

    static final Map<String, String> PARAM_LABELS = new LinkedHashMap<>();

    /** The IRC:82-2023 numbers this ships with — what "Reset to default" restores. */
    static final Map<String, Double> DEFAULT_WEIGHTS = new LinkedHashMap<>();
    static final Map<String, double[]> DEFAULT_THRESHOLDS = new LinkedHashMap<>();
    static {
        PARAM_LABELS.put("crack", "Cracking");
        PARAM_LABELS.put("ravelling", "Ravelling");
        PARAM_LABELS.put("pothole", "Pothole");
        PARAM_LABELS.put("patch_work", "Patch work");
        PARAM_LABELS.put("rutting", "Rut depth");
        PARAM_LABELS.put("iri", "IRI (roughness)");

        DEFAULT_WEIGHTS.put("crack", 0.12);      DEFAULT_THRESHOLDS.put("crack", new double[]{5, 15});
        DEFAULT_WEIGHTS.put("ravelling", 0.10);  DEFAULT_THRESHOLDS.put("ravelling", new double[]{5, 10});
        DEFAULT_WEIGHTS.put("pothole", 0.16);    DEFAULT_THRESHOLDS.put("pothole", new double[]{1, 3});
        DEFAULT_WEIGHTS.put("patch_work", 0.08); DEFAULT_THRESHOLDS.put("patch_work", new double[]{5, 10});
        DEFAULT_WEIGHTS.put("rutting", 0.14);    DEFAULT_THRESHOLDS.put("rutting", new double[]{5, 10});
        DEFAULT_WEIGHTS.put("iri", 0.40);        DEFAULT_THRESHOLDS.put("iri", new double[]{2.55, 3.30});
    }

    /* The numbers actually in force. Held as volatile references to immutable
       maps rather than mutable maps, so a segment rebuild running while an admin
       saves new weights sees one complete set or the other, never a half-applied
       mix. Set from calc_setting at startup by CalcRuleService. */
    private static volatile Map<String, Double> weights = Map.copyOf(DEFAULT_WEIGHTS);
    private static volatile Map<String, double[]> thresh = Map.copyOf(DEFAULT_THRESHOLDS);

    /** Parameter -> weight in force. */
    static Map<String, Double> weights() { return weights; }

    /** Parameter -> {fair, poor} threshold in force. */
    static Map<String, double[]> thresholds() { return thresh; }

    /** Replace the numbers in force. Callers must have validated them already. */
    static void configure(Map<String, Double> newWeights, Map<String, double[]> newThresholds) {
        Map<String, Double> w = new LinkedHashMap<>(DEFAULT_WEIGHTS);
        Map<String, double[]> t = new LinkedHashMap<>();
        DEFAULT_THRESHOLDS.forEach((k, v) -> t.put(k, new double[]{v[0], v[1]}));
        if (newWeights != null) newWeights.forEach((k, v) -> { if (w.containsKey(k)) w.put(k, v); });
        if (newThresholds != null) newThresholds.forEach((k, v) -> {
            if (t.containsKey(k) && v != null && v.length == 2) t.put(k, new double[]{v[0], v[1]});
        });
        weights = Map.copyOf(w);
        thresh = t;
    }

    /** Are the numbers in force still the shipped IRC ones? */
    static boolean atDefault() {
        for (String k : PARAM_KEYS) {
            if (Math.abs(weights.get(k) - DEFAULT_WEIGHTS.get(k)) > 1e-9) return false;
            double[] a = thresh.get(k), b = DEFAULT_THRESHOLDS.get(k);
            if (Math.abs(a[0] - b[0]) > 1e-9 || Math.abs(a[1] - b[1]) > 1e-9) return false;
        }
        return true;
    }

    /**
     * Per-parameter condition index (0-100) — the piecewise curve of indIndex():
     * 100..80 up to "fair", 80..40 up to "poor", 40..0 up to twice "poor", then 0.
     */
    static Double index(String key, Double v) {
        double[] t = thresh.get(key);
        if (t == null || v == null || v.isNaN()) return null;
        double x = Math.max(0, v);
        double fair = t[0], poor = t[1];
        if (!(poor > fair && fair > 0)) return null;
        if (x <= fair) return 100 - (x / fair) * 20;
        if (x <= poor) return 80 - ((x - fair) / (poor - fair)) * 40;
        double cap = 2 * poor;
        if (x <= cap) return 40 - ((x - poor) / poor) * 40;
        return 0.0;
    }

    /** IRC:82-2023 Appendix 2, Table A2.1. */
    private static Double highwayIndex(String key, Double raw) {
        if (raw == null || !Double.isFinite(raw) || raw < 0) return null;
        double x = raw;
        double score = switch (key) {
            case "crack", "rutting" -> 7231 / (x * x - 0.737 * x + 73.09);
            case "ravelling", "patch_work" ->
                52.92 * Math.exp(-0.02525 * x) + 44.1 * Math.exp(-0.2899 * x);
            case "pothole" -> x >= 10 ? 0 :
                (((-80.32 * x + 1129) * x - 3524) * x + 3566) /
                (((x + 5.791) * x - 28.67) * x + 35.72);
            case "iri" -> 100 / (Math.pow(x, 1.91) - 3.542 * x + 4.315);
            default -> throw new IllegalArgumentException(key);
        };
        return Math.max(0, Math.min(100, score));
    }

    /** Appendix 2, Table A2.2 Part A, with the draft amendment's pothole correction. */
    private static Double mdrIndex(String key, Double raw, String surface) {
        if (raw == null || !Double.isFinite(raw) || raw < 0) return null;
        double x = raw;
        double score = switch (key) {
            case "crack", "ravelling", "rutting" ->
                (((-0.5662 * x + 85.14) * x - 3377) * x + 51820) / ((x - 29.31) * x + 523.4);
            case "patch_work" -> (((-0.001723 * x - 0.08273) * x + 21.39) * x + 419.1) / (x + 4.267);
            case "pothole" -> x >= 10 ? 0 : ((((0.0113 * x - 0.2825) * x + 2.6246) * x - 19.18) * x + 99.529);
            case "iri" -> mdrIri(x, surface);
            default -> throw new IllegalArgumentException(key);
        };
        return Double.isFinite(score) ? Math.max(0, Math.min(100, score)) : null;
    }

    private static double mdrIri(double iri, String surface) {
        double x = Math.max(2, Math.min(20, iri));
        return switch (surface) {
            case "sd", "surface dressing" ->
                (((((-0.09535 * x + 4.235) * x - 74.43) * x + 642.4) * x - 2625) * x + 4034)
                    / ((x - 9.065) * x + 20.71);
            case "ogpc", "open graded premix carpet", "open-graded premix carpet" ->
                (((-4.842 * x + 121.5) * x - 875.9) * x + 2029) / ((x - 8.389) * x + 19.85);
            case "mss", "mix seal surfacing" ->
                ((((-2.686 * x + 64.96) * x - 437.2) * x + 1046) * x - 596.2)
                    / (((x - 9.766) * x + 31.09) * x - 30.94);
            case "sdbc", "semi dense bituminous concrete", "semi-dense bituminous concrete",
                 "semi dense bituminous course", "semi-dense bituminous course" ->
                (((-4.468 * x + 106.2) * x - 694.7) * x + 1507) / ((x - 6.778) * x + 14.51);
            case "bc", "bt", "bituminous", "bitumious", "bituminous concrete" ->
                ((((-2.52 * x + 55.18) * x - 292.4) * x + 476.4) * x - 5.565)
                    / (((x - 7.458) * x + 17.77) * x - 12.09);
            default -> Double.NaN;
        };
    }

    /** Weighted PCI from one raw distress set; null when nothing is measurable. */
    static Double pciFrom(Map<String, Double> dist, boolean highway, boolean mdr, String surface) {
        if (dist == null) return null;
        double weighted = 0, usedWeight = 0;
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            double w = e.getValue();
            if (w <= 0) continue;
            Double raw = dist.get(e.getKey());
            Double idx = highway ? highwayIndex(e.getKey(), raw)
                : mdr ? mdrIndex(e.getKey(), raw, surface) : index(e.getKey(), raw);
            if (idx == null) continue;
            weighted += w * idx;
            usedWeight += w;
        }
        return usedWeight > 0 ? weighted / usedWeight : null;
    }

    /**
     * PCI for one segment. {@code lanes} is the per-lane distress breakdown
     * (lane_vals); {@code fallback} is the segment-level distress used when the
     * stretch has no lane breakdown — the MAX columns for "worst", the avg_*
     * columns for "composite".
     */
    static Double segPci(Map<String, Map<String, Double>> lanes, Map<String, Double> fallback, boolean worstBasis, boolean highway, boolean mdr, String surface) {
        List<Double> lanePcis = new ArrayList<>();
        if (lanes != null) {
            for (Map<String, Double> d : lanes.values()) {
                Double v = pciFrom(d, highway, mdr, surface);
                if (v != null) lanePcis.add(v);
            }
        }
        if (lanePcis.isEmpty()) return pciFrom(fallback, highway, mdr, surface);

        if (worstBasis) {
            double min = lanePcis.get(0);
            for (double v : lanePcis) if (v < min) min = v;
            return min;
        }

        /* Composite — area-weighted distress average: pool the lane distresses
           across the carriageway using equal surveyed lane areas. The highway
           pothole input is an equivalent count, so lane counts are additive. */
        Map<String, Double> pooled = new LinkedHashMap<>();
        for (String key : PARAM_KEYS) {
            double sum = 0;
            int n = 0;
            for (Map<String, Double> d : lanes.values()) {
                Double v = d.get(key);
                if (v != null) { sum += v; n++; }
            }
            if (n > 0) pooled.put(key, (highway || mdr) && key.equals("pothole") ? sum : sum / n);
        }
        Double v = pciFrom(pooled, highway, mdr, surface);
        if (v != null) return v;
        double sum = 0;
        for (double x : lanePcis) sum += x;
        return sum / lanePcis.size();
    }

    /** Two decimals — enough for a 0-100 index, and keeps the GeoJSON small. */
    static Double round2(Double v) {
        return v == null ? null : Math.round(v * 100.0) / 100.0;
    }
}
