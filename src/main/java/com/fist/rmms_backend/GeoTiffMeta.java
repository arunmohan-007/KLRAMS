package com.fist.rmms_backend;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads a GeoTIFF's georeferencing header — the tags that say where on Earth the
 * pixels are — without decoding a single pixel.
 *
 * <p>The JDK's own {@code ImageIO} TIFF plugin decodes the imagery but exposes the
 * GeoTIFF tags only as raw, undocumented metadata nodes, and no library on the
 * classpath understands them. This parses the four tags that matter straight out
 * of the file's first IFD, which is a few hundred bytes of reading no matter how
 * large the raster is:
 *
 * <ul>
 *   <li>33550 {@code ModelPixelScale} and 33922 {@code ModelTiepoint} — the usual
 *       north-up pair: one anchor point plus a pixel size.</li>
 *   <li>34264 {@code ModelTransformation} — the general 4x4 affine, used instead
 *       of the pair when the raster is rotated. Takes precedence when present.</li>
 *   <li>34735 {@code GeoKeyDirectory} — carries the EPSG code, as either
 *       {@code ProjectedCSTypeGeoKey} (3072) or {@code GeographicTypeGeoKey}
 *       (2048).</li>
 * </ul>
 *
 * <p>BigTIFF (magic 43) is detected and rejected: the JDK reader cannot decode it,
 * so accepting one here would only defer the failure to publish time, where the
 * cause would be far less obvious.
 */
final class GeoTiffMeta {

    /* TIFF baseline tags */
    private static final int IMAGE_WIDTH = 256;
    private static final int IMAGE_LENGTH = 257;
    private static final int BITS_PER_SAMPLE = 258;
    private static final int PHOTOMETRIC = 262;
    private static final int COLOR_MAP = 320;
    private static final int SAMPLES_PER_PIXEL = 277;
    private static final int EXTRA_SAMPLES = 338;
    private static final int SAMPLE_FORMAT = 339;
    /* GeoTIFF + GDAL tags */
    private static final int MODEL_PIXEL_SCALE = 33550;
    private static final int MODEL_TIEPOINT = 33922;
    private static final int MODEL_TRANSFORMATION = 34264;
    private static final int GEO_KEY_DIRECTORY = 34735;
    private static final int GEO_DOUBLE_PARAMS = 34736;
    private static final int GEO_ASCII_PARAMS = 34737;
    private static final int IMAGE_DESCRIPTION = 270;
    private static final int SOFTWARE = 305;
    private static final int DATE_TIME = 306;
    private static final int COMPRESSION = 259;
    private static final int TILE_WIDTH = 322;
    private static final int TILE_LENGTH = 323;
    private static final int ROWS_PER_STRIP = 278;
    private static final int GDAL_NODATA = 42113;

    final int width;
    final int height;
    final int samplesPerPixel;
    final int bitsPerSample;
    /** TIFF SampleFormat: 1 unsigned int, 2 signed int, 3 IEEE float. */
    final int sampleFormat;
    /** TIFF PhotometricInterpretation: 0 white-is-zero, 1 black-is-zero, 2 RGB, 3 palette. */
    final int photometric;
    final boolean hasAlpha;
    /**
     * Which band carries alpha, or -1. Taken from ExtraSamples, whose VALUE says what
     * a band is: 1 associated (premultiplied) alpha, 2 unassociated alpha, 0 simply
     * "unspecified". Checking only that the tag exists gets this wrong — GDAL writes
     * {@code ExtraSamples=(0,0)} on a plain 3-band grayscale-plus-extras image, which
     * has no alpha at all.
     */
    final int alphaBand;
    final DroneCrs crs;
    /** NaN when the file declares no nodata value. */
    final double noData;
    /**
     * Datum, projection, geoid and the rest of what the header says about itself —
     * insertion-ordered so it reads top to bottom. Only keys the file actually
     * carries are present; nothing is guessed.
     */
    final java.util.LinkedHashMap<String, String> details;

    /* Pixel (column,row) -> model (x,y), as a 6-parameter affine. */
    private final double ax, bx, cx;
    private final double ay, by, cy;
    /* Its inverse, precomputed. */
    private final double ia, ib, ic, id, ie, iff;

    private GeoTiffMeta(int width, int height, int samplesPerPixel, int bitsPerSample, int sampleFormat,
                        int photometric, int alphaBand, DroneCrs crs, double noData,
                        java.util.LinkedHashMap<String, String> details, double[] t) {
        this.width = width;
        this.height = height;
        this.samplesPerPixel = samplesPerPixel;
        this.bitsPerSample = bitsPerSample;
        this.sampleFormat = sampleFormat;
        this.photometric = photometric;
        this.alphaBand = alphaBand;
        this.hasAlpha = alphaBand >= 0;
        this.crs = crs;
        this.noData = noData;
        this.details = details == null ? new java.util.LinkedHashMap<>() : details;
        this.ax = t[0]; this.bx = t[1]; this.cx = t[2];
        this.ay = t[3]; this.by = t[4]; this.cy = t[5];

        double det = ax * by - bx * ay;
        if (det == 0)
            throw new IllegalArgumentException("The GeoTIFF's georeferencing is degenerate (zero pixel size).");
        this.ia = by / det;
        this.ib = -bx / det;
        this.ic = (bx * cy - by * cx) / det;
        this.id = -ay / det;
        this.ie = ax / det;
        this.iff = (ay * cx - ax * cy) / det;
    }

    /** Model coordinate at the CENTRE of pixel {@code (col,row)}. */
    double[] pixelToModel(double col, double row) {
        double c = col + 0.5, r = row + 0.5;
        return new double[]{ax * c + bx * r + cx, ay * c + by * r + cy};
    }

    /** Fractional pixel column/row holding the given model coordinate. */
    double[] modelToPixel(double x, double y) {
        return new double[]{ia * x + ib * y + ic - 0.5, id * x + ie * y + iff - 0.5};
    }

    /** Ground sample distance along each axis, in the CRS's own units. */
    double resX() {
        return Math.hypot(ax, ay);
    }

    double resY() {
        return Math.hypot(bx, by);
    }

    /**
     * WGS84 bounding box {@code {minLon, minLat, maxLon, maxLat}}.
     *
     * <p>Built from all four corners rather than two, because a projected raster's
     * edges are curved in lon/lat: taking only the SW and NE corners would clip a
     * sliver off the north or south edge of a UTM image.
     */
    double[] wgs84Bounds() {
        double minLon = Double.MAX_VALUE, minLat = Double.MAX_VALUE;
        double maxLon = -Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        // Corners plus edge midpoints — enough to bound the curvature at any
        // sane raster size, and free compared with walking every edge pixel.
        double[][] corners = {{0, 0}, {width, 0}, {0, height}, {width, height},
                             {width / 2.0, 0}, {width / 2.0, height},
                             {0, height / 2.0}, {width, height / 2.0}};
        for (double[] p : corners) {
            double[] m = pixelToModel(p[0] - 0.5, p[1] - 0.5);
            double[] ll = crs.toWgs84(m[0], m[1]);
            minLon = Math.min(minLon, ll[0]); maxLon = Math.max(maxLon, ll[0]);
            minLat = Math.min(minLat, ll[1]); maxLat = Math.max(maxLat, ll[1]);
        }
        return new double[]{minLon, minLat, maxLon, maxLat};
    }

    /** Ground sample distance in metres, whatever the CRS's units are. */
    double resolutionMetres() {
        if (!crs.isGeographic()) return (resX() + resY()) / 2;
        double[] b = wgs84Bounds();
        double midLat = Math.toRadians((b[1] + b[3]) / 2);
        double mPerDegLon = 111320 * Math.cos(midLat);
        return (resX() * mPerDegLon + resY() * 110540) / 2;
    }

    /* ------------------------------------------------------------------
       Parsing
       ------------------------------------------------------------------ */

    static GeoTiffMeta read(Path file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            byte[] head = new byte[8];
            raf.readFully(head);
            ByteOrder order;
            if (head[0] == 'I' && head[1] == 'I') order = ByteOrder.LITTLE_ENDIAN;
            else if (head[0] == 'M' && head[1] == 'M') order = ByteOrder.BIG_ENDIAN;
            else throw new IllegalArgumentException("Not a TIFF file — the file does not start with a TIFF header.");

            ByteBuffer hb = ByteBuffer.wrap(head).order(order);
            int magic = hb.getShort(2) & 0xFFFF;
            if (magic == 43)
                throw new IllegalArgumentException(
                        "This is a BigTIFF. Re-export it as a standard GeoTIFF "
                      + "(in GDAL: -co BIGTIFF=NO), or tile the survey into smaller images.");
            if (magic != 42)
                throw new IllegalArgumentException("Not a TIFF file — bad magic number " + magic + ".");

            long ifdOffset = hb.getInt(4) & 0xFFFFFFFFL;
            Map<Integer, Object> tags = readIfd(raf, order, ifdOffset);

            int width = (int) num(tags, IMAGE_WIDTH, -1);
            int height = (int) num(tags, IMAGE_LENGTH, -1);
            if (width <= 0 || height <= 0)
                throw new IllegalArgumentException("The TIFF does not declare a valid image size.");

            int spp = (int) num(tags, SAMPLES_PER_PIXEL, 1);
            int bps = (int) num(tags, BITS_PER_SAMPLE, 8);
            int fmt = (int) num(tags, SAMPLE_FORMAT, 1);
            // Photometric defaults to RGB for a 3+ band file and black-is-zero
            // otherwise, matching what a reader assumes when the tag is absent.
            int photo = (int) num(tags, PHOTOMETRIC, spp >= 3 ? 2 : 1);
            if (tags.containsKey(COLOR_MAP)) photo = 3;

            /* ExtraSamples describes the bands AFTER the colour ones, in order:
               1 is associated (premultiplied) alpha, 2 unassociated, 0 "unspecified". */
            int colourBands = (photo == 2) ? 3 : 1;
            int alphaBand = -1;
            double[] extras = doubles(tags, EXTRA_SAMPLES);
            if (extras != null) {
                for (int i = 0; i < extras.length; i++) {
                    if (extras[i] == 1 || extras[i] == 2) { alphaBand = colourBands + i; break; }
                }
            }
            /* Fall back to the count. Plenty of real files label a genuine alpha band
               "unspecified" — GDAL writes ExtraSamples=0 and records the band's role
               only in its own colour-interpretation metadata, which is not a TIFF tag.
               Trusting ExtraSamples alone renders those files' transparent collar as
               opaque black. A 4th band on an RGB image, or a 2nd on a grayscale one,
               is alpha by overwhelming convention. */
            if (alphaBand < 0 && spp == colourBands + 1) alphaBand = colourBands;
            if (alphaBand >= spp) alphaBand = -1;

            double[] transform = affine(tags, height);
            DroneCrs crs = DroneCrs.of(epsg(tags));

            double nodata = Double.NaN;
            Object nd = tags.get(GDAL_NODATA);
            if (nd instanceof String s) {
                try { nodata = Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) { }
            }

            return new GeoTiffMeta(width, height, spp, bps, fmt, photo, alphaBand, crs, nodata,
                                   describe(tags, crs), transform);
        }
    }

    /** Pixel->model affine, from ModelTransformation if present, else scale + tiepoint. */
    private static double[] affine(Map<Integer, Object> tags, int height) {
        double[] m = doubles(tags, MODEL_TRANSFORMATION);
        if (m != null && m.length >= 16)
            return new double[]{m[0], m[1], m[3], m[4], m[5], m[7]};

        double[] scale = doubles(tags, MODEL_PIXEL_SCALE);
        double[] tie = doubles(tags, MODEL_TIEPOINT);
        if (scale == null || scale.length < 2 || tie == null || tie.length < 6)
            throw new IllegalArgumentException(
                    "The file carries no georeferencing — it is a plain TIFF, not a GeoTIFF. "
                  + "Export it with the coordinate system embedded.");

        // Row indices grow downwards while northings grow upwards, hence -scale[1].
        double x0 = tie[3] - tie[0] * scale[0];
        double y0 = tie[4] + tie[1] * scale[1];
        return new double[]{scale[0], 0, x0, 0, -scale[1], y0};
    }

    private static int epsg(Map<Integer, Object> tags) {
        double[] dir = doubles(tags, GEO_KEY_DIRECTORY);
        if (dir == null || dir.length < 4)
            throw new IllegalArgumentException(
                    "The GeoTIFF declares no coordinate system. Re-export it with the CRS embedded "
                  + "(WGS 84 / UTM zone 43N — EPSG:32643 — for Kerala).");

        int projected = 0, geographic = 0;
        int count = (int) dir[3];
        for (int i = 0; i < count; i++) {
            int at = 4 + i * 4;
            if (at + 3 >= dir.length) break;
            int key = (int) dir[at], location = (int) dir[at + 1], value = (int) dir[at + 3];
            if (location != 0) continue;   // value stored in another tag, not inline — not an EPSG code
            if (key == 3072) projected = value;
            else if (key == 2048) geographic = value;
        }
        int code = projected != 0 && projected != 32767 ? projected : geographic;
        if (code == 0 || code == 32767)
            throw new IllegalArgumentException(
                    "The GeoTIFF uses a user-defined coordinate system with no EPSG code. "
                  + "Re-export it in EPSG:4326 or EPSG:32643.");
        return code;
    }

    /* ------------------------------------------------------------------
       Header description — datum, projection, geoid, provenance
       ------------------------------------------------------------------ */

    /**
     * Everything the header states about itself, in reading order.
     *
     * <p>A GeoKey's value is not always inline. The entry's {@code TIFFTagLocation}
     * says where it lives: 0 means the value IS the fourth field, 34736 means it is
     * a double in GeoDoubleParams, and 34737 means it is a slice of the
     * GeoAsciiParams string. Reading only the inline case — which is all the EPSG
     * lookup needed — silently skips every citation, and the citations are exactly
     * where an exporter writes the datum and the geoid in words.
     */
    private static java.util.LinkedHashMap<String, String> describe(
            Map<Integer, Object> tags, DroneCrs crs) {

        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        Map<Integer, Object> keys = geoKeys(tags);

        Integer model = intKey(keys, 1024);
        if (model != null)
            out.put("Model type", switch (model) {
                case 1 -> "Projected";
                case 2 -> "Geographic (lat/lon)";
                case 3 -> "Geocentric";
                default -> "code " + model;
            });

        /* GDAL normally writes the datum, ellipsoid and projection method by EPSG
           reference rather than as their own GeoKeys, so these are usually absent
           from the file. Where they are, the file's own answer wins; where they are
           not, the CRS supplies it and says so, because a blank "Datum" is a worse
           answer than a derived one for the person checking a survey. */
        String datum = GeoKeyNames.datum(code(keys, 2050));
        put(out, "Datum", datum != null ? datum : crs.datum() + " — implied by EPSG:" + crs.epsg());
        String ellipsoid = GeoKeyNames.ellipsoid(code(keys, 2056));
        put(out, "Ellipsoid", ellipsoid != null ? ellipsoid : crs.ellipsoid());
        String proj = GeoKeyNames.projection(code(keys, 3075));
        put(out, "Projection", proj != null ? proj : crs.projectionMethod());
        put(out, "Linear units", GeoKeyNames.linearUnit(code(keys, 3076)));
        put(out, "Angular units", GeoKeyNames.angularUnit(code(keys, 2054)));

        /* The vertical side. Its absence is itself worth reporting: heights with no
           declared vertical CRS are ellipsoidal, which is metres away from the
           orthometric heights a road level is quoted in. */
        String vcrs = GeoKeyNames.verticalCrs(code(keys, 4096));
        String vdatum = GeoKeyNames.verticalDatum(code(keys, 4098));
        put(out, "Vertical CRS / geoid", vcrs);
        put(out, "Vertical datum", vdatum);
        put(out, "Vertical units", GeoKeyNames.linearUnit(code(keys, 4099)));
        if (vcrs == null && vdatum == null)
            out.put("Vertical CRS / geoid", "not declared — heights are above the ellipsoid");

        Integer raster = intKey(keys, 1025);
        if (raster != null)
            out.put("Pixel is", raster == 2 ? "point (value at the pixel centre)"
                                            : "area (value covers the pixel)");

        // Citations last: they repeat some of the above, but in the exporter's own
        // words, which is often more specific than the codes.
        put(out, "CRS citation", ascii(keys, 1026));
        put(out, "Projected CRS citation", ascii(keys, 3073));
        put(out, "Geographic CRS citation", ascii(keys, 2049));

        Object software = tags.get(SOFTWARE);
        if (software instanceof String s && !s.isBlank()) out.put("Produced by", s.trim());
        Object when = tags.get(DATE_TIME);
        if (when instanceof String s && !s.isBlank()) out.put("File date", s.trim());
        Object desc = tags.get(IMAGE_DESCRIPTION);
        if (desc instanceof String s && !s.isBlank()) out.put("Description", s.trim());

        double[] comp = doubles(tags, COMPRESSION);
        if (comp != null && comp.length > 0) out.put("Compression", GeoKeyNames.compression((int) comp[0]));

        double[] tw = doubles(tags, TILE_WIDTH), th = doubles(tags, TILE_LENGTH);
        double[] rps = doubles(tags, ROWS_PER_STRIP);
        if (tw != null && th != null && tw.length > 0 && th.length > 0)
            out.put("Layout", "tiled " + (int) tw[0] + " × " + (int) th[0]);
        else if (rps != null && rps.length > 0)
            out.put("Layout", "stripped, " + (int) rps[0] + " rows per strip");

        return out;
    }

    private static void put(java.util.LinkedHashMap<String, String> out, String k, String v) {
        if (v != null && !v.isBlank()) out.put(k, v);
    }

    /** A GeoKey's numeric code, or 0 when the file does not carry that key. */
    private static int code(Map<Integer, Object> keys, int key) {
        Integer v = intKey(keys, key);
        return v == null ? 0 : v;
    }

    private static Integer intKey(Map<Integer, Object> keys, int key) {
        Object v = keys.get(key);
        return v instanceof Integer i ? i : null;
    }

    private static String ascii(Map<Integer, Object> keys, int key) {
        Object v = keys.get(key);
        if (!(v instanceof String s)) return null;
        // GeoAsciiParams separates entries with '|', which is not part of the text.
        String t = s.replace('|', ' ').trim();
        return t.isEmpty() ? null : t;
    }

    /** The GeoKeyDirectory as key -> Integer or String, values resolved to their store. */
    private static Map<Integer, Object> geoKeys(Map<Integer, Object> tags) {
        Map<Integer, Object> out = new HashMap<>();
        double[] dir = doubles(tags, GEO_KEY_DIRECTORY);
        if (dir == null || dir.length < 4) return out;

        double[] dbl = doubles(tags, GEO_DOUBLE_PARAMS);
        Object asciiRaw = tags.get(GEO_ASCII_PARAMS);
        String asc = asciiRaw instanceof String s ? s : null;

        int count = (int) dir[3];
        for (int i = 0; i < count; i++) {
            int at = 4 + i * 4;
            if (at + 3 >= dir.length) break;
            int key = (int) dir[at], location = (int) dir[at + 1];
            int len = (int) dir[at + 2], offset = (int) dir[at + 3];

            if (location == 0) {
                out.put(key, offset);
            } else if (location == GEO_DOUBLE_PARAMS && dbl != null && offset < dbl.length) {
                out.put(key, String.valueOf(dbl[offset]));
            } else if (location == GEO_ASCII_PARAMS && asc != null
                    && offset >= 0 && offset + len <= asc.length()) {
                out.put(key, asc.substring(offset, offset + len));
            }
        }
        return out;
    }

    /** The description as JSON, for the {@code geo_details} column. */
    String detailsJson() {
        if (details.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : details.entrySet()) {
            if (!first) sb.append(',');
            sb.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
            first = false;
        }
        return sb.append('}').toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n', '\r', '\t' -> sb.append(' ');
                default -> {
                    if (c < 0x20) sb.append(' ');
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    /* ------------------------------------------------------------------
       Minimal TIFF IFD reader
       ------------------------------------------------------------------ */

    private static Map<Integer, Object> readIfd(RandomAccessFile raf, ByteOrder order, long offset)
            throws IOException {
        Map<Integer, Object> tags = new HashMap<>();
        raf.seek(offset);
        byte[] cnt = new byte[2];
        raf.readFully(cnt);
        int entries = ByteBuffer.wrap(cnt).order(order).getShort() & 0xFFFF;

        byte[] block = new byte[entries * 12];
        raf.readFully(block);
        ByteBuffer bb = ByteBuffer.wrap(block).order(order);

        for (int i = 0; i < entries; i++) {
            int base = i * 12;
            int tag = bb.getShort(base) & 0xFFFF;
            int type = bb.getShort(base + 2) & 0xFFFF;
            long count = bb.getInt(base + 4) & 0xFFFFFFFFL;
            int size = typeSize(type);
            if (size == 0 || count <= 0 || count > 1_000_000) continue;

            long bytes = size * count;
            byte[] data;
            if (bytes <= 4) {
                data = new byte[(int) bytes];
                System.arraycopy(block, base + 8, data, 0, (int) bytes);
            } else {
                long at = bb.getInt(base + 8) & 0xFFFFFFFFL;
                data = new byte[(int) bytes];
                raf.seek(at);
                raf.readFully(data);
            }
            tags.put(tag, decode(type, (int) count, ByteBuffer.wrap(data).order(order)));
        }
        return tags;
    }

    private static int typeSize(int type) {
        return switch (type) {
            case 1, 2, 6, 7 -> 1;
            case 3, 8 -> 2;
            case 4, 9, 11 -> 4;
            case 5, 10, 12 -> 8;
            default -> 0;
        };
    }

    /** ASCII tags come back as a String; everything numeric as a double[]. */
    private static Object decode(int type, int count, ByteBuffer bb) {
        if (type == 2) {
            byte[] raw = new byte[bb.remaining()];
            bb.get(raw);
            int end = raw.length;
            while (end > 0 && raw[end - 1] == 0) end--;
            return new String(raw, 0, end, StandardCharsets.US_ASCII);
        }
        double[] out = new double[count];
        for (int i = 0; i < count; i++) {
            out[i] = switch (type) {
                case 1, 7 -> bb.get() & 0xFF;
                case 6 -> bb.get();
                case 3 -> bb.getShort() & 0xFFFF;
                case 8 -> bb.getShort();
                case 4 -> bb.getInt() & 0xFFFFFFFFL;
                case 9 -> bb.getInt();
                case 11 -> bb.getFloat();
                case 12 -> bb.getDouble();
                case 5 -> ratio(bb.getInt() & 0xFFFFFFFFL, bb.getInt() & 0xFFFFFFFFL);
                case 10 -> ratio(bb.getInt(), bb.getInt());
                default -> 0;
            };
        }
        return out;
    }

    private static double ratio(double n, double d) {
        return d == 0 ? 0 : n / d;
    }

    private static double[] doubles(Map<Integer, Object> tags, int tag) {
        Object v = tags.get(tag);
        return v instanceof double[] d ? d : null;
    }

    private static double num(Map<Integer, Object> tags, int tag, double fallback) {
        double[] d = doubles(tags, tag);
        return d == null || d.length == 0 ? fallback : d[0];
    }

    /** "Unsigned integer 16-bit", "Float 32-bit", … */
    String dataType() {
        String kind = switch (sampleFormat) {
            case 3 -> "Float";
            case 2 -> "Signed integer";
            default -> "Unsigned integer";
        };
        return kind + " " + bitsPerSample + "-bit";
    }

    /**
     * The bands to draw, as source indices: three for colour, or one for grayscale.
     *
     * <p>Bands 1-3 become red, green and blue whenever there are three non-alpha
     * bands to work with — <b>even when PhotometricInterpretation does not say RGB</b>.
     * A great many real orthomosaics are written as MINISBLACK with three or four
     * bands, because the exporter recorded each band's role in its own metadata
     * rather than in the TIFF tag. Believing the tag on those files renders a colour
     * survey as a grey one built from the red band alone. This is the same default
     * GDAL and QGIS apply: more than one band means multiband colour, 1-2-3 to
     * R-G-B.
     *
     * <p>A palette image is excluded — its single band is an index into a colour
     * table, not a channel.
     */
    int[] displayBands() {
        int[] colour = new int[samplesPerPixel];
        int n = 0;
        for (int i = 0; i < samplesPerPixel; i++)
            if (i != alphaBand) colour[n++] = i;

        if (photometric != 3 && n >= 3) return new int[]{colour[0], colour[1], colour[2]};
        return new int[]{n > 0 ? colour[0] : 0};
    }

    /** True when {@link #displayBands()} gives three channels rather than grey. */
    boolean isColour() {
        return displayBands().length == 3;
    }

    /** How the bands are read for display: "RGB", "RGB + alpha", "Grayscale", … */
    String colourInterpretation() {
        if (photometric == 3) return "Palette";

        int colourBands = samplesPerPixel - (hasAlpha ? 1 : 0);
        String base;
        if (isColour()) {
            // Say so explicitly when the tag disagrees with how it is being drawn —
            // otherwise the panel claims "grayscale" over a full-colour image.
            base = photometric == 2 ? "RGB" : "Multiband, bands 1-3 shown as RGB";
        } else {
            base = photometric == 0 ? "Grayscale (white is zero)" : "Grayscale";
        }
        if (hasAlpha) base += " + alpha";
        int spare = colourBands - (isColour() ? 3 : 1);
        if (spare > 0) base += " + " + spare + " further band" + (spare == 1 ? "" : "s");
        return base;
    }

    /** Per-band role, for the info panel: Red / Green / Blue / Alpha / Band n. */
    String[] bandLabels() {
        String[] out = new String[samplesPerPixel];
        int[] shown = displayBands();
        String[] rgb = {"Red", "Green", "Blue"};
        for (int i = 0; i < samplesPerPixel; i++) {
            if (i == alphaBand) { out[i] = "Alpha"; continue; }
            if (photometric == 3) { out[i] = "Palette index"; continue; }
            String label = null;
            for (int k = 0; k < shown.length; k++)
                if (shown[k] == i) label = shown.length == 3 ? rgb[k] : "Gray";
            out[i] = label != null ? label : "Band " + (i + 1);
        }
        return out;
    }

    /** For the metadata panel: "3-band · Unsigned integer 8-bit · RGB". */
    String pixelSummary() {
        return samplesPerPixel + "-band · " + dataType() + " · " + colourInterpretation();
    }
}
