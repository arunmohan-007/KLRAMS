package com.fist.rmms_backend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IRC:82-2023 Pavement Condition Index — Java port of the browser engine in
 * js/14-pci-engine.js, used to store a PCI per segment at build time.
 *
 * The stored value always uses the DEFAULT weights and the DEFAULT good/poor
 * thresholds (PCI_W_DEFAULT in 14-pci-engine.js, PARAMS in 01-config.js). When
 * the user edits the weights in the viewer the browser recomputes on the fly and
 * ignores what is stored here — so this class must stay in step with those two
 * files or the default-weight map would disagree with a weights-reset map.
 *
 * PCI is a NON-LINEAR function of the distresses, so aggregation order matters
 * (see the build-75 note in 14-pci-engine.js):
 *   - Worst-Lane PCI = MIN of the per-lane PCIs
 *   - Composite PCI  = pool the lane distresses first, then compute ONE PCI
 */
final class PciCalculator {

    private PciCalculator() {}

    /** Parameter -> default weight. Ordered to match PCI_PARAMS. */
    static final Map<String, Double> WEIGHTS = new LinkedHashMap<>();
    /** Parameter -> {fair, poor} threshold. */
    private static final Map<String, double[]> THRESH = new LinkedHashMap<>();
    static {
        WEIGHTS.put("crack", 0.16);      THRESH.put("crack", new double[]{5, 15});
        WEIGHTS.put("ravelling", 0.12);  THRESH.put("ravelling", new double[]{5, 10});
        WEIGHTS.put("pothole", 0.08);    THRESH.put("pothole", new double[]{1, 3});
        WEIGHTS.put("patch_work", 0.10); THRESH.put("patch_work", new double[]{5, 10});
        WEIGHTS.put("rutting", 0.14);    THRESH.put("rutting", new double[]{5, 10});
        WEIGHTS.put("iri", 0.40);        THRESH.put("iri", new double[]{2.55, 3.30});
    }

    /**
     * Per-parameter condition index (0-100) — the piecewise curve of indIndex():
     * 100..80 up to "fair", 80..40 up to "poor", 40..0 up to twice "poor", then 0.
     */
    static Double index(String key, Double v) {
        double[] t = THRESH.get(key);
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
        for (Map.Entry<String, Double> e : WEIGHTS.entrySet()) {
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
        for (String key : WEIGHTS.keySet()) {
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
