package com.fist.rmms_backend;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Marching squares, checked against surfaces whose contours are known by hand.
 *
 * <p>Contouring is the kind of geometry that looks plausible on a map long after it
 * has stopped being correct — a dropped case in the sixteen-way switch shows up as
 * a line with a nick in it, not as an error. So these check shape and position, not
 * just that something came back.
 */
class ContourTracingTest {

    /** z rises with the column: every contour is a straight vertical line. */
    private static double[][] ramp(int w, int h) {
        double[][] g = new double[h][w];
        for (int r = 0; r < h; r++)
            for (int c = 0; c < w; c++) g[r][c] = c;
        return g;
    }

    @Test
    void tracesAPlanarRampAsStraightLinesAtTheRightPlace() {
        double[][] g = ramp(11, 9);

        for (double level : new double[]{2, 5, 7.5}) {
            List<List<double[]>> lines = DroneContourService.linesAt(g, level);
            assertEquals(1, lines.size(), "a ramp has exactly one contour per level at " + level);

            List<double[]> line = lines.get(0);
            assertTrue(line.size() >= 2, "a contour needs at least two points");
            for (double[] p : line) {
                assertEquals(level, p[0], 1e-6,
                        "on z=x the contour for " + level + " sits at column " + level);
            }
            // and it should span the full height of the grid
            double minRow = Double.MAX_VALUE, maxRow = -Double.MAX_VALUE;
            for (double[] p : line) { minRow = Math.min(minRow, p[1]); maxRow = Math.max(maxRow, p[1]); }
            assertEquals(0, minRow, 1e-9);
            assertEquals(g.length - 1, maxRow, 1e-9);
        }
    }

    /** Segments must be joined, not left as one line per cell. */
    @Test
    void chainsSegmentsIntoWholeLinesRatherThanPerCellPieces() {
        double[][] g = ramp(11, 41);
        List<List<double[]>> lines = DroneContourService.linesAt(g, 4);

        assertEquals(1, lines.size(), "40 cells of one contour should chain into a single line");
        assertEquals(41, lines.get(0).size(), "one vertex per grid row");
    }

    /** A cone gives closed rings: first point meets last. */
    @Test
    void tracesAConeAsAClosedRing() {
        int n = 41;
        double[][] g = new double[n][n];
        double mid = (n - 1) / 2.0;
        for (int r = 0; r < n; r++)
            for (int c = 0; c < n; c++) g[r][c] = 20 - Math.hypot(c - mid, r - mid);

        List<List<double[]>> lines = DroneContourService.linesAt(g, 10);
        assertEquals(1, lines.size(), "one ring at this level");

        List<double[]> ring = lines.get(0);
        double[] first = ring.get(0), last = ring.get(ring.size() - 1);
        assertEquals(first[0], last[0], 1e-6, "a ring closes on itself");
        assertEquals(first[1], last[1], 1e-6);

        // every vertex should sit ~10 units from the peak, since z = 20 - radius
        for (double[] p : ring) {
            assertEquals(10.0, Math.hypot(p[0] - mid, p[1] - mid), 0.15,
                    "cone contour at z=10 is the circle of radius 10");
        }
    }

    /** Cells touching nodata are skipped rather than contoured against NaN. */
    @Test
    void skipsCellsWithNoData() {
        double[][] g = ramp(11, 9);
        for (int r = 0; r < 4; r++) g[r][5] = Double.NaN;

        List<List<double[]>> lines = DroneContourService.linesAt(g, 5);
        for (List<double[]> line : lines)
            for (double[] p : line) {
                assertFalse(Double.isNaN(p[0]) || Double.isNaN(p[1]), "no NaN vertices");
                assertTrue(p[1] >= 3, "contour at the nodata column starts below the hole");
            }
    }

    /** A level outside the surface's range produces nothing at all. */
    @Test
    void producesNothingOutsideTheSurfaceRange() {
        double[][] g = ramp(11, 9);
        assertTrue(DroneContourService.linesAt(g, 99).isEmpty());
        assertTrue(DroneContourService.linesAt(g, -5).isEmpty());
    }
}
