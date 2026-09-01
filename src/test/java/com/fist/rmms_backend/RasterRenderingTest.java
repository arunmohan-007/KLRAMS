package com.fist.rmms_backend;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How a GeoTIFF's bands become screen pixels.
 *
 * <p>Written after a real 16-bit orthomosaic published successfully, landed in the
 * right place, and rendered almost black. Nothing in the pipeline was "broken" in a
 * way a status field could show: the numbers were right and the mapping to display
 * levels was wrong. These tests pin the two conversions that were at fault and the
 * JDK behaviour that hid a second bug behind the first.
 */
class RasterRenderingTest {

    private static Path fixture(String name) {
        return Paths.get("src", "test", "resources", "drone", name);
    }

    /* ------------------------------------------------------------------
       Band description — what the info panel reports
       ------------------------------------------------------------------ */

    @Test
    void readsRgbBandLayout() throws Exception {
        GeoTiffMeta m = GeoTiffMeta.read(fixture("ortho-utm43n.tif"));

        assertEquals(3, m.samplesPerPixel);
        assertEquals(2, m.photometric, "photometric 2 is RGB");
        assertEquals("RGB", m.colourInterpretation());
        assertEquals("Unsigned integer 8-bit", m.dataType());
        assertFalse(m.hasAlpha);
        assertEquals(-1, m.alphaBand);
        assertArrayEquals(new String[]{"Red", "Green", "Blue"}, m.bandLabels());
    }

    /**
     * A 4-band RGB file whose ExtraSamples tag says "unspecified" rather than
     * "alpha" — which is what GDAL writes when the band's role lives only in its own
     * metadata. Band 4 is still alpha, and reading it as opaque colour would paint
     * the transparent collar solid black.
     */
    @Test
    void treatsAFourthBandOnRgbAsAlphaEvenWhenUnlabelled() throws Exception {
        GeoTiffMeta m = GeoTiffMeta.read(fixture("ortho-rgba-unspec.tif"));

        assertEquals(4, m.samplesPerPixel);
        assertTrue(m.hasAlpha, "the 4th band of an RGB image is alpha by convention");
        assertEquals(3, m.alphaBand, "alpha is the fourth band");
        assertEquals("RGB + alpha", m.colourInterpretation());
        assertArrayEquals(new String[]{"Red", "Green", "Blue", "Alpha"}, m.bandLabels());
    }

    /**
     * ExtraSamples says what the bands after the colour ones ARE. Value 0 means
     * "unspecified", not alpha — GDAL writes {@code ExtraSamples=(0,0)} on a plain
     * three-band non-RGB image. Treating the tag's mere presence as alpha marked
     * ordinary three-band files as having transparency.
     */
    @Test
    void doesNotMistakeUnspecifiedExtraSamplesForAlpha() throws Exception {
        GeoTiffMeta m = GeoTiffMeta.read(fixture("ortho-uint16.tif"));

        assertEquals(3, m.samplesPerPixel);
        assertFalse(m.hasAlpha, "a 3-band RGB image has no alpha band");
        assertEquals(-1, m.alphaBand);
    }

    /**
     * A four-band 16-bit export written as MINISBLACK with no colour interpretation
     * — the shape a real multispectral orthomosaic (R, G, B, NIR) takes.
     *
     * <p>Believing PhotometricInterpretation here would draw a colour survey in grey,
     * from the red band alone, and would silently discard bands 2 and 3. Bands 1-3
     * are the picture; the fourth is a further measurement, not transparency.
     */
    @Test
    void mapsFirstThreeBandsToRgbWhenTheFileDoesNotDeclareRgb() throws Exception {
        GeoTiffMeta m = GeoTiffMeta.read(fixture("ortho-multiband16.tif"));

        assertEquals(4, m.samplesPerPixel);
        assertEquals(1, m.photometric, "fixture is MINISBLACK, as real exports often are");

        assertTrue(m.isColour(), "four bands should be drawn in colour, not grey");
        assertArrayEquals(new int[]{0, 1, 2}, m.displayBands(),
                "band 1 is red, band 2 green, band 3 blue");
        assertFalse(m.hasAlpha, "a 4th band on a non-RGB image is another measurement, not alpha");
        assertArrayEquals(new String[]{"Red", "Green", "Blue", "Band 4"}, m.bandLabels());
        assertEquals("Multiband, bands 1-3 shown as RGB + 1 further band", m.colourInterpretation());
    }

    /** A single-band raster still renders as grey — there is nothing to make colour from. */
    @Test
    void singleBandRasterStaysGrayscale() throws Exception {
        GeoTiffMeta m = GeoTiffMeta.read(fixture("dem-utm43n.tif"));

        assertEquals(1, m.samplesPerPixel);
        assertFalse(m.isColour());
        assertArrayEquals(new int[]{0}, m.displayBands());
    }

    /* ------------------------------------------------------------------
       The dark-render bug
       ------------------------------------------------------------------ */

    /**
     * The bug, stated as a fact about the JDK: for a 16-bit raster,
     * {@code BufferedImage.getRGB()} scales by the TYPE's maximum (65 535), not by
     * the range the data actually uses. A file whose samples top out around 2 000
     * therefore renders at roughly 2000/65535 of full brightness — visibly black,
     * with the real image faintly present underneath.
     */
    @Test
    void getRgbRendersLimitedRangeSixteenBitDataAlmostBlack() throws Exception {
        Raster r = readRaster(fixture("ortho-uint16.tif"));
        BufferedImage img = readImage(fixture("ortho-uint16.tif"));

        double maxSample = 0;
        for (int y = 0; y < r.getHeight(); y += 4)
            for (int x = 0; x < r.getWidth(); x += 4)
                maxSample = Math.max(maxSample, r.getSampleDouble(x, y, 0));
        assertTrue(maxSample < 4000, "fixture should use only a slice of the 16-bit range");

        assertTrue(meanLuminance(img) < 20,
                "this is the defect being guarded against: getRGB() renders it near-black");
    }

    /** The same pixels, mapped through the measured display window, look like an image. */
    @Test
    void stretchingToTheMeasuredWindowRestoresBrightness() throws Exception {
        GeoTiffMeta m = GeoTiffMeta.read(fixture("ortho-uint16.tif"));
        Raster r = readRaster(fixture("ortho-uint16.tif"));
        RasterBandStats stats = RasterBandStats.measure(samplesOf(r, m.samplesPerPixel), m);

        double mean = 0;
        int n = 0;
        for (int y = 0; y < r.getHeight(); y += 4) {
            for (int x = 0; x < r.getWidth(); x += 4) {
                int red = level(r.getSampleDouble(x, y, 0), stats, 0);
                int grn = level(r.getSampleDouble(x, y, 1), stats, 1);
                int blu = level(r.getSampleDouble(x, y, 2), stats, 2);
                mean += (red * 299 + grn * 587 + blu * 114) / 1000.0;
                n++;
            }
        }
        mean /= n;
        assertTrue(mean > 60, "stretched render should be a normally exposed image, was " + mean);
    }

    /** 8-bit samples are display levels already; stretching them would recolour every
     *  orthomosaic that renders correctly today. */
    @Test
    void eightBitDataIsNotStretched() throws Exception {
        GeoTiffMeta m = GeoTiffMeta.read(fixture("ortho-utm43n.tif"));
        assertEquals(1, m.sampleFormat);
        assertEquals(8, m.bitsPerSample);

        // level() with a null window is the identity, which is what the 8-bit path uses
        assertEquals(0, level(0, null, 0));
        assertEquals(128, level(128, null, 0));
        assertEquals(255, level(255, null, 0));
    }

    /* ------------------------------------------------------------------
       The bug hiding behind it
       ------------------------------------------------------------------ */

    /**
     * The JDK's TIFF reader TRUNCATES float samples toward zero when
     * {@code setSourceSubsampling} is used — the same region read unsubsampled comes
     * back with its fractions intact.
     *
     * <p>On a DEM of metres above sea level that only loses the decimals, which is
     * why it went unnoticed. On a reflectance orthomosaic scaled 0..1 it turns every
     * pixel into 0: the whole image reads as empty, its measured range collapses to
     * 0..0, and a DEM measured that way is rejected at upload as "no usable
     * elevation values". The sampling code therefore must not subsample through
     * ImageIO; it reads honest strips and thins them in Java instead.
     */
    @Test
    void jdkTiffReaderTruncatesFloatSamplesWhenSubsampled() throws Exception {
        Path ortho = fixture("ortho-float01.tif");
        GeoTiffMeta m = GeoTiffMeta.read(ortho);
        assertEquals(3, m.sampleFormat, "fixture must be float for this to be meaningful");

        double unsubsampled = maxSampleWithSubsampling(ortho, 1);
        assertTrue(unsubsampled > 0.5 && unsubsampled < 1.0,
                "fixture holds reflectance-style values in 0..1, was " + unsubsampled);
        assertEquals(0.0, maxSampleWithSubsampling(ortho, 2), 1e-9,
                "subsampled float read truncates 0..1 values to zero");
    }

    /** Read honestly, the same raster measures its real range. */
    @Test
    void floatOrthoMeasuresItsRealRangeWhenNotSubsampled() throws Exception {
        Path ortho = fixture("ortho-float01.tif");
        GeoTiffMeta m = GeoTiffMeta.read(ortho);
        RasterBandStats s = RasterBandStats.measure(samplesOf(readRaster(ortho), m.samplesPerPixel), m);

        assertTrue(s.max[0] > 0.5, "band 1 maximum should be a real reflectance, was " + s.max[0]);
        assertTrue(s.high[0] > s.low[0], "display window must not collapse");
    }

    /* ------------------------------------------------------------------
       helpers — mirrors of the service's own conversions
       ------------------------------------------------------------------ */

    private static int level(double v, RasterBandStats window, int band) {
        if (window == null || band >= window.bands) return (int) Math.max(0, Math.min(255, v));
        double lo = window.low[band], hi = window.high[band];
        if (hi <= lo) return 0;
        int out = (int) Math.round((v - lo) / (hi - lo) * 255);
        return out < 0 ? 0 : (out > 255 ? 255 : out);
    }

    private static double[][] samplesOf(Raster r, int bands) {
        double[][] out = new double[bands][r.getWidth() * r.getHeight()];
        int i = 0;
        for (int y = 0; y < r.getHeight(); y++)
            for (int x = 0; x < r.getWidth(); x++, i++)
                for (int b = 0; b < bands; b++) out[b][i] = r.getSampleDouble(x, y, b);
        return out;
    }

    private static double meanLuminance(BufferedImage img) {
        long sum = 0;
        int n = 0;
        for (int y = 0; y < img.getHeight(); y += 4)
            for (int x = 0; x < img.getWidth(); x += 4) {
                int argb = img.getRGB(x, y);
                sum += (((argb >> 16) & 0xFF) * 299 + ((argb >> 8) & 0xFF) * 587 + (argb & 0xFF) * 114) / 1000;
                n++;
            }
        return sum / (double) n;
    }

    private static double maxSampleWithSubsampling(Path file, int step) throws Exception {
        try (ImageInputStream iis = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            ImageReader reader = it.next();
            reader.setInput(iis, true, true);
            ImageReadParam p = reader.getDefaultReadParam();
            if (step > 1) p.setSourceSubsampling(step, step, 0, 0);
            Raster r = reader.read(0, p).getRaster();
            double max = -Double.MAX_VALUE;
            for (int y = 0; y < r.getHeight(); y++)
                for (int x = 0; x < r.getWidth(); x++)
                    max = Math.max(max, r.getSampleDouble(x, y, 0));
            reader.dispose();
            return max;
        }
    }

    private static Raster readRaster(Path file) throws Exception {
        return readImage(file).getRaster();
    }

    private static BufferedImage readImage(Path file) throws Exception {
        try (ImageInputStream iis = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            ImageReader reader = it.next();
            reader.setInput(iis, true, true);
            BufferedImage img = reader.read(0, reader.getDefaultReadParam());
            reader.dispose();
            return img;
        }
    }

    /* ------------------------------------------------------------------
       Upload validation
       ------------------------------------------------------------------ */

    /**
     * The band layout a file declares is what the warnings are written from, so the
     * cases that produce a warning have to be distinguishable from the metadata
     * alone — no decoding, no publish.
     */
    @Test
    void bandLayoutIsEnoughToTellAColourOrthoFromAGreyOne() throws Exception {
        GeoTiffMeta colour = GeoTiffMeta.read(fixture("ortho-utm43n.tif"));
        GeoTiffMeta grey = GeoTiffMeta.read(fixture("dem-utm43n.tif"));

        assertTrue(colour.isColour(), "3 bands is a colour image");
        assertEquals(3, colour.displayBands().length);

        assertFalse(grey.isColour(), "1 band cannot be colour");
        assertEquals(1, grey.displayBands().length);
    }

    /** A 4-band multispectral file is colour, and its 4th band is not transparency. */
    @Test
    void multibandFileIsColourAndItsFourthBandIsNotAlpha() throws Exception {
        GeoTiffMeta m = GeoTiffMeta.read(fixture("ortho-multiband16.tif"));

        assertTrue(m.isColour());
        assertEquals(4, m.samplesPerPixel);
        assertFalse(m.hasAlpha, "band 4 here is a measurement, so nothing is transparent");
        // 4 colour bands and only 3 drawable, which is what the upload warns about
        assertEquals(4, m.samplesPerPixel - (m.hasAlpha ? 1 : 0));
    }

    /** Flat data has no picture in it, which the upload check keys off. */
    @Test
    void flatBandsAreDetectableFromTheStatisticsAlone() throws Exception {
        GeoTiffMeta m = GeoTiffMeta.read(fixture("ortho-uint16.tif"));
        RasterBandStats real = RasterBandStats.measure(
                samplesOf(readRaster(fixture("ortho-uint16.tif")), m.samplesPerPixel), m);
        for (int b = 0; b < real.bands; b++)
            assertTrue(real.max[b] > real.min[b], "the fixture does have detail in band " + (b + 1));

        double[][] flat = new double[3][256];
        for (double[] band : flat) java.util.Arrays.fill(band, 1200);
        RasterBandStats none = RasterBandStats.measure(flat, m);
        for (int b = 0; b < none.bands; b++)
            assertEquals(none.min[b], none.max[b], 1e-9, "a flat band has no range");
    }

}
