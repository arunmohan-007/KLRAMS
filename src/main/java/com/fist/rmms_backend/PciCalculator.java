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

        DEFAULT_WEIGHTS.put("crack", 0.16);      DEFAULT_THRESHOLDS.put("crack", new double[]{5, 15});
        DEFAULT_WEIGHTS.put("ravelling", 0.12);  DEFAULT_THRESHOLDS.put("ravelling", new double[]{5, 10});
        DEFAULT_WEIGHTS.put("pothole", 0.08);    DEFAULT_THRESHOLDS.put("pothole", new double[]{1, 3});
        DEFAULT_WEIGHTS.put("patch_work", 0.10); DEFAULT_THRESHOLDS.put("patch_work", new double[]{5, 10});
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

    /** Weighted PCI from one raw distress set; null when nothing is measurable. */
    static Double pciFrom(Map<String, Double> dist) {
        if (dist == null) return null;
        double weighted = 0, usedWeight = 0;
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            double w = e.getValue();
            if (w <= 0) continue;
            Double idx = index(e.getKey(), dist.get(e.getKey()));
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
    static Double segPci(Map<String, Map<String, Double>> lanes, Map<String, Double> fallback, boolean worstBasis) {
        List<Double> lanePcis = new ArrayList<>();
        if (lanes != null) {
            for (Map<String, Double> d : lanes.values()) {
                Double v = pciFrom(d);
                if (v != null) lanePcis.add(v);
            }
        }
        if (lanePcis.isEmpty()) return pciFrom(fallback);

        if (worstBasis) {
            double min = lanePcis.get(0);
            for (double v : lanePcis) if (v < min) min = v;
            return min;
        }

        /* Composite — area-weighted distress average: pool the lane distresses
           across the carriageway (equal-area lanes -> mean per parameter), then
           compute ONE PCI from the pooled distress. */
        Map<String, Double> pooled = new LinkedHashMap<>();
        for (String key : PARAM_KEYS) {
            double sum = 0;
            int n = 0;
            for (Map<String, Double> d : lanes.values()) {
                Double v = d.get(key);
                if (v != null) { sum += v; n++; }
            }
            if (n > 0) pooled.put(key, sum / n);
        }
        Double v = pciFrom(pooled);
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
