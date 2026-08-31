package com.fist.rmms_backend;

import java.awt.image.Raster;

/**
 * Per-band value range for a raster, and the display window derived from it.
 *
 * <h2>Why a display window is needed at all</h2>
 * An 8-bit image already speaks the display's language: 0-255 in, 0-255 out. Any
 * other type does not. A 16-bit drone orthomosaic typically occupies a small part
 * of its range — 1 000 to 4 000 out of a possible 65 535 is ordinary — and mapping
 * that range onto 0-255 by dividing by the type's maximum puts every pixel in the
 * bottom few percent of the output. The picture is technically correct and looks
 * almost black. Stretching the values the raster ACTUALLY uses across the output
 * range is what makes it look like the scene it is.
 *
 * <p>The window is the 2nd to 98th percentile rather than the plain minimum and
 * maximum, because a handful of specular highlights or a single dead pixel would
 * otherwise set the ceiling and flatten everything real back down again. This is
 * the same default a GIS applies when it says "stretch to 98%".
 */
final class RasterBandStats {

    /** Bins used to find the percentiles. Fine enough for 16-bit data. */
    private static final int BINS = 4096;

    final int bands;
    final double[] min;
    final double[] max;
    /** Low and high ends of the display window (2nd / 98th percentile). */
    final double[] low;
    final double[] high;

    private RasterBandStats(int bands) {
        this.bands = bands;
        this.min = new double[bands];
        this.max = new double[bands];
        this.low = new double[bands];
        this.high = new double[bands];
    }

    /**
     * Measure sampled values, one array per band, skipping nodata.
     *
     * <p>Two passes over data already in memory: one for the extremes, one to build
     * a histogram for the percentiles. Cheap next to the decode that produced it.
     */
    static RasterBandStats measure(double[][] samples, GeoTiffMeta meta) {
        int nb = samples.length;
        RasterBandStats s = new RasterBandStats(nb);

        for (int b = 0; b < nb; b++) {
            double[] vals = samples[b];
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (double v : vals) {
                if (skip(v, meta, b)) continue;
                if (v < lo) lo = v;
                if (v > hi) hi = v;
            }
            if (lo > hi) { lo = 0; hi = 0; }
            s.min[b] = lo;
            s.max[b] = hi;

            if (hi <= lo) { s.low[b] = lo; s.high[b] = lo + 1; continue; }

            long[] hist = new long[BINS];
            long total = 0;
            double span = hi - lo;
            for (double v : vals) {
                if (skip(v, meta, b)) continue;
                int bin = (int) ((v - lo) / span * (BINS - 1));
                if (bin < 0) bin = 0;
                if (bin >= BINS) bin = BINS - 1;
                hist[bin]++;
                total++;
            }
            s.low[b] = percentile(hist, total, lo, span, 0.02);
            s.high[b] = percentile(hist, total, lo, span, 0.98);
            // A flat band (every pixel identical) would give a zero-width window and
            // divide by zero downstream.
            if (s.high[b] <= s.low[b]) { s.low[b] = lo; s.high[b] = hi > lo ? hi : lo + 1; }
        }
        return s;
    }

    private static double percentile(long[] hist, long total, double lo, double span, double frac) {
        long want = (long) (total * frac);
        long seen = 0;
        for (int i = 0; i < hist.length; i++) {
            seen += hist[i];
            if (seen >= want) return lo + (i / (double) (hist.length - 1)) * span;
        }
        return lo + span;
    }

    /**
     * Nodata is excluded from the statistics; without that, a nodata fill of 0 (or
     * -9999) drags the window open and flattens the real pixels.
     *
     * <p>The alpha band is measured as-is: it is not image content, but its range is
     * what tells the renderer whether alpha runs 0-255 or 0-65535.
     */
    private static boolean skip(double v, GeoTiffMeta meta, int band) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return true;
        if (band == meta.alphaBand) return false;
        return !Double.isNaN(meta.noData) && Math.abs(v - meta.noData) < 1e-9;
    }

    /** JSON for the {@code band_stats} column and the viewer's info panel. */
    String toJson(GeoTiffMeta meta) {
        String[] labels = meta.bandLabels();
        StringBuilder sb = new StringBuilder("[");
        for (int b = 0; b < bands; b++) {
            if (b > 0) sb.append(',');
            sb.append("{\"band\":").append(b + 1)
              .append(",\"label\":\"").append(b < labels.length ? labels[b] : "Band " + (b + 1)).append('"')
              .append(",\"min\":").append(num(min[b]))
              .append(",\"max\":").append(num(max[b]))
              .append(",\"low\":").append(num(low[b]))
              .append(",\"high\":").append(num(high[b]))
              .append('}');
        }
        return sb.append(']').toString();
    }

    private static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "null";
        // Whole numbers print as integers; a 16-bit level of "1098.0" reads oddly.
        return v == Math.rint(v) && Math.abs(v) < 1e15
                ? String.valueOf((long) v)
                : String.format(java.util.Locale.ROOT, "%.4f", v);
    }
}
