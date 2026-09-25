package com.fist.rmms_backend;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.ColorModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;

/**
 * Georeferencing for a plain JPG/PNG placed on the map with a {@code .jgw}/
 * {@code .pgw} world-file sidecar, rather than an embedded GeoTIFF header.
 *
 * <p>A world file carries six numbers, one per line, in the standard order —
 * pixel size X, rotation, rotation, pixel size Y (negative, since rows grow
 * downward while northings grow upward), then the model X/Y of the CENTRE of
 * the top-left pixel:
 *
 * <pre>
 *   0.00005   (A) pixel size in the x-direction
 *   0.0       (D) rotation about y-axis
 *   0.0       (B) rotation about x-axis
 *  -0.00005   (E) pixel size in the y-direction, negative
 *   76.9500   (C) x-coordinate of the centre of the upper-left pixel
 *   8.5000    (F) y-coordinate of the centre of the upper-left pixel
 * </pre>
 *
 * <p>Unlike a GeoTIFF, a world file names no coordinate system at all — by
 * convention (and per the confirmed requirement for this upload path) the
 * model coordinates are assumed to already be WGS84 degrees, so
 * {@link #fromWgs84}/{@link #toWgs84} are the identity. A rotated/sheared
 * transform (nonzero D/B terms) is accepted — the maths handles it — but is
 * unusual enough for a hand-georeferenced scan that it is worth flagging as a
 * warning at upload; see {@link UserRasterService}.
 */
final class WorldFileGeoRef implements RasterGeoRef {

    final int width;
    final int height;
    final boolean hasAlpha;
    final boolean rotated;

    private final double ax, bx, cx;
    private final double ay, by, cy;
    private final double ia, ib, ic, id, ie, iff;

    private WorldFileGeoRef(int width, int height, boolean hasAlpha, double[] t) {
        this.width = width;
        this.height = height;
        this.hasAlpha = hasAlpha;
        this.ax = t[0]; this.bx = t[1]; this.cx = t[2];
        this.ay = t[3]; this.by = t[4]; this.cy = t[5];
        this.rotated = bx != 0 || ay != 0;

        double det = ax * by - bx * ay;
        if (det == 0)
            throw new IllegalArgumentException("The world file's transform is degenerate (zero pixel size).");
        this.ia = by / det;
        this.ib = -bx / det;
        this.ic = (bx * cy - by * cx) / det;
        this.id = -ay / det;
        this.ie = ax / det;
        this.iff = (ay * cx - ax * cy) / det;
    }

    /**
     * Read an image's dimensions/colour model and pair them with a parsed
     * world-file sidecar.
     */
    static WorldFileGeoRef read(Path imageFile, Path worldFile) throws IOException {
        double[] t = parseWorldFile(worldFile);

        int width, height;
        boolean alpha;
        try (ImageInputStream iis = ImageIO.createImageInputStream(imageFile.toFile())) {
            if (iis == null) throw new IllegalArgumentException("The image file could not be opened.");
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext())
                throw new IllegalArgumentException("No reader can decode this image — only JPG and PNG are accepted.");
            ImageReader reader = it.next();
            try {
                reader.setInput(iis, true, true);
                width = reader.getWidth(0);
                height = reader.getHeight(0);
                ColorModel cm = reader.getRawImageType(0) != null ? reader.getRawImageType(0).getColorModel() : null;
                alpha = cm != null && cm.hasAlpha();
            } finally {
                reader.dispose();
            }
        }
        if (width <= 0 || height <= 0)
            throw new IllegalArgumentException("The image declares no usable size.");

        // World-file affine pins the CENTRE of the top-left pixel; the tiepoint
        // convention GeoTiffMeta's affine uses is the same, so t[] is already in
        // that (a,b,c / d,e,f) shape and needs no adjustment here.
        return new WorldFileGeoRef(width, height, alpha, t);
    }

    private static double[] parseWorldFile(Path worldFile) throws IOException {
        String raw = Files.readString(worldFile, StandardCharsets.US_ASCII);
        String[] lines = raw.strip().split("\\s+");
        if (lines.length < 6)
            throw new IllegalArgumentException(
                    "The world file must carry six numbers (pixel size, rotation, rotation, "
                  + "pixel size, origin X, origin Y), one per line.");
        double[] v = new double[6];
        for (int i = 0; i < 6; i++) {
            try { v[i] = Double.parseDouble(lines[i].trim()); }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException("The world file's line " + (i + 1) + " is not a number.");
            }
        }
        double a = v[0], d = v[1], b = v[2], e = v[3], c = v[4], f = v[5];
        // World-file order is A,D,B,E,C,F; GeoTiffMeta's affine is ax,bx,cx / ay,by,cy
        // where (ax,bx) scale column/row into X and (ay,by) into Y — i.e. A<->ax,
        // B<->bx, C<->cx, D<->ay, E<->by, F<->cy.
        return new double[]{a, b, c, d, e, f};
    }

    @Override public int width() { return width; }
    @Override public int height() { return height; }
    @Override public int samplesPerPixel() { return hasAlpha ? 4 : 3; }
    @Override public int photometric() { return 2; }   // always treated as RGB
    @Override public int alphaBand() { return hasAlpha ? 3 : -1; }
    @Override public double noData() { return Double.NaN; }

    @Override
    public double[] pixelToModel(double col, double row) {
        double c = col + 0.5, r = row + 0.5;
        return new double[]{ax * c + bx * r + cx, ay * c + by * r + cy};
    }

    @Override
    public double[] modelToPixel(double x, double y) {
        return new double[]{ia * x + ib * y + ic - 0.5, id * x + ie * y + iff - 0.5};
    }

    @Override public double resX() { return Math.hypot(ax, ay); }
    @Override public double resY() { return Math.hypot(bx, by); }

    @Override
    public double[] wgs84Bounds() {
        double minLon = Double.MAX_VALUE, minLat = Double.MAX_VALUE;
        double maxLon = -Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double[][] corners = {{0, 0}, {width, 0}, {0, height}, {width, height},
                             {width / 2.0, 0}, {width / 2.0, height},
                             {0, height / 2.0}, {width, height / 2.0}};
        for (double[] p : corners) {
            double[] m = pixelToModel(p[0] - 0.5, p[1] - 0.5);
            minLon = Math.min(minLon, m[0]); maxLon = Math.max(maxLon, m[0]);
            minLat = Math.min(minLat, m[1]); maxLat = Math.max(maxLat, m[1]);
        }
        return new double[]{minLon, minLat, maxLon, maxLat};
    }

    @Override
    public double resolutionMetres() {
        double[] b = wgs84Bounds();
        double midLat = Math.toRadians((b[1] + b[3]) / 2);
        double mPerDegLon = 111320 * Math.cos(midLat);
        return (resX() * mPerDegLon + resY() * 110540) / 2;
    }

    /** Assumed WGS84 by construction — see the class note. */
    @Override public double[] fromWgs84(double lon, double lat) { return new double[]{lon, lat}; }
    @Override public double[] toWgs84(double x, double y) { return new double[]{x, y}; }

    @Override
    public int[] displayBands() {
        return hasAlpha ? new int[]{0, 1, 2} : new int[]{0, 1, 2};
    }

    String dataType() {
        return "Unsigned integer 8-bit";
    }

    String colourInterpretation() {
        return hasAlpha ? "RGB + alpha" : "RGB";
    }

    String pixelSummary() {
        return samplesPerPixel() + "-band · " + dataType() + " · " + colourInterpretation();
    }
}
