package com.fist.rmms_backend;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The GeoTIFF header reader and the UTM maths, checked against real GDAL output.
 *
 * <p>The fixtures under {@code src/test/resources/drone} were written by GDAL —
 * LZW, internally tiled, one of them float32 with a nodata value — which is what
 * Pix4D, Agisoft and OpenDroneMap actually produce. Hand-rolled TIFFs would only
 * prove the reader agrees with itself.
 *
 * <p>They are anchored at lon 76.95 / lat 8.52 (the viewer's default centre in
 * {@code 02-map-core.js}) in EPSG:32643, so a projection error of even a few
 * metres shows up as a failed assertion rather than an image drawn in the sea.
 */
class GeoTiffMetaTest {

    private static final double ANCHOR_LON = 76.95;
    private static final double ANCHOR_LAT = 8.52;

    private static Path fixture(String name) {
        return Paths.get("src", "test", "resources", "drone", name);
    }

    @Test
    void readsUtmOrthoHeader() throws Exception {
        GeoTiffMeta m = GeoTiffMeta.read(fixture("ortho-utm43n.tif"));

        assertEquals(320, m.width);
        assertEquals(240, m.height);
        assertEquals(3, m.samplesPerPixel);
        assertEquals(8, m.bitsPerSample);
        assertEquals(32643, m.crs.epsg());
        assertFalse(m.crs.isGeographic());
        assertEquals(0.05, m.resX(), 1e-9);
        assertEquals(0.05, m.resY(), 1e-9);
        assertEquals(0.05, m.resolutionMetres(), 1e-6);
    }

    @Test
    void placesTheUtmOrthoAtItsAnchor() throws Exception {
        GeoTiffMeta m = GeoTiffMeta.read(fixture("ortho-utm43n.tif"));

        // The tiepoint is the NW corner, so it must land exactly on the anchor.
        double[] nw = corner(m, 0, 0);
        assertEquals(ANCHOR_LON, nw[0], 1e-9);
        assertEquals(ANCHOR_LAT, nw[1], 1e-9);

        // Taken back to UTM, the four corners must still be the 16 m x 12 m grid
        // rectangle the fixture was written as (320 x 240 px at 5 cm).
        double[] ne = corner(m, 320, 0), sw = corner(m, 0, 240);
        double[] nwGrid = m.crs.fromWgs84(nw[0], nw[1]);
        double[] neGrid = m.crs.fromWgs84(ne[0], ne[1]);
        double[] swGrid = m.crs.fromWgs84(sw[0], sw[1]);
        assertEquals(16.0, neGrid[0] - nwGrid[0], 1e-6);
        assertEquals(12.0, nwGrid[1] - swGrid[1], 1e-6);
    }

    /**
     * A UTM raster is axis-aligned to GRID north, not true north, so in lon/lat it
     * is a slightly rotated rectangle — here by the 0.289 degrees of meridian
     * convergence at 76.95E. Its bounding box is therefore legitimately WIDER than
     * the image, and it has to be: a box drawn to the raster's own dimensions would
     * clip the two corners that stick out, and the viewer would refuse to load the
     * tiles under them.
     */
    @Test
    void boundsContainTheRotatedFootprint() throws Exception {
        GeoTiffMeta m = GeoTiffMeta.read(fixture("ortho-utm43n.tif"));
        double[] b = m.wgs84Bounds();

        double[][] corners = {corner(m, 0, 0), corner(m, 320, 0), corner(m, 320, 240), corner(m, 0, 240)};
        for (double[] c : corners) {
            assertTrue(c[0] >= b[0] && c[0] <= b[2], "corner longitude outside the bounds");
            assertTrue(c[1] >= b[1] && c[1] <= b[3], "corner latitude outside the bounds");
        }

        // And no larger than that rotation demands: w*cos(y) + h*sin(y) across,
        // h*cos(y) + w*sin(y) down. Within a centimetre.
        double gamma = Math.toRadians(0.289011);
        double mPerDegLon = 110105.6, mPerDegLat = 110598.5;   // WGS84, at latitude 8.52
        assertEquals(16 * Math.cos(gamma) + 12 * Math.sin(gamma), (b[2] - b[0]) * mPerDegLon, 0.01);
        assertEquals(12 * Math.cos(gamma) + 16 * Math.sin(gamma), (b[3] - b[1]) * mPerDegLat, 0.01);
    }

    private static double[] corner(GeoTiffMeta m, double col, double row) {
        double[] model = m.pixelToModel(col - 0.5, row - 0.5);
        return m.crs.toWgs84(model[0], model[1]);
    }

    @Test
    void readsGeographicOrthoHeader() throws Exception {
        GeoTiffMeta m = GeoTiffMeta.read(fixture("ortho-wgs84.tif"));

        assertEquals(4326, m.crs.epsg());
        assertTrue(m.crs.isGeographic());
        double[] b = m.wgs84Bounds();
        assertEquals(ANCHOR_LON, b[0], 1e-9);
        assertEquals(ANCHOR_LAT, b[3], 1e-9);
        // A degree pixel size must still be reported to the user in metres.
        assertEquals(0.0000005 * 111320 * Math.cos(Math.toRadians(ANCHOR_LAT)),
                m.resolutionMetres(), 0.005);
    }

    @Test
    void readsDemHeaderIncludingNoData() throws Exception {
        GeoTiffMeta m = GeoTiffMeta.read(fixture("dem-utm43n.tif"));

        assertEquals(1, m.samplesPerPixel);
        assertEquals(32, m.bitsPerSample);
        assertEquals(3, m.sampleFormat, "float32 DEM must report IEEE sample format");
        assertEquals(-9999.0, m.noData, 1e-9);
        assertEquals(0.2, m.resX(), 1e-9);
    }

    @Test
    void pixelAndModelCoordinatesRoundTrip() throws Exception {
        GeoTiffMeta m = GeoTiffMeta.read(fixture("ortho-utm43n.tif"));

        for (double[] px : new double[][]{{0, 0}, {12.5, 7.25}, {319, 239}}) {
            double[] model = m.pixelToModel(px[0], px[1]);
            double[] back = m.modelToPixel(model[0], model[1]);
            assertEquals(px[0], back[0], 1e-6);
            assertEquals(px[1], back[1], 1e-6);
        }
    }

    @Test
    void utmAndWgs84RoundTripToMillimetres() {
        DroneCrs utm = DroneCrs.of(32643);

        for (double[] ll : new double[][]{{76.95, 8.52}, {74.9, 12.9}, {77.4, 10.85}}) {
            double[] xy = utm.fromWgs84(ll[0], ll[1]);
            double[] back = utm.toWgs84(xy[0], xy[1]);
            // 1e-9 degrees is well under a millimetre.
            assertEquals(ll[0], back[0], 1e-9);
            assertEquals(ll[1], back[1], 1e-9);
        }
    }

    /** Zone 43N's central meridian is 75°E, where easting must be exactly false-easting. */
    @Test
    void utmCentralMeridianHasNoOffset() {
        double[] xy = DroneCrs.of(32643).fromWgs84(75.0, 10.0);
        assertEquals(500000.0, xy[0], 1e-6);
    }

    @Test
    void rejectsUnsupportedProjectionWithAUsefulMessage() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> DroneCrs.of(24378));
        assertTrue(e.getMessage().contains("EPSG:24378"));
        assertTrue(e.getMessage().contains("32643"), "the message should name the CRS to re-export in");
    }

    @Test
    void rejectsANonGeoreferencedFile() throws Exception {
        Path plain = java.nio.file.Files.createTempFile("plain", ".tif");
        try {
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
            javax.imageio.ImageIO.write(img, "tiff", plain.toFile());

            IllegalArgumentException e =
                    assertThrows(IllegalArgumentException.class, () -> GeoTiffMeta.read(plain));
            assertTrue(e.getMessage().toLowerCase().contains("georeferenc")
                            || e.getMessage().toLowerCase().contains("coordinate system"),
                    "expected a georeferencing complaint, got: " + e.getMessage());
        } finally {
            java.nio.file.Files.deleteIfExists(plain);
        }
    }
}
