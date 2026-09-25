package com.fist.rmms_backend;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Raster storage and tile pyramid build for Temporary Layers.
 *
 * <p>The raster analogue of {@link DroneRasterService}, scoped to
 * {@code layer_raster} instead of {@code drone_dataset}, and generalised over
 * {@link RasterGeoRef} so the same pyramid builder and tile/pixel endpoints
 * serve both a GeoTIFF (georeferencing embedded, read by {@link GeoTiffMeta})
 * and a plain JPG/PNG placed with a {@code .jgw}/{@code .pgw} world file
 * ({@link WorldFileGeoRef}, which assumes WGS84 — see its class note).
 *
 * <p>On-disk layout, matching {@link DroneRasterService}'s convention:
 * <pre>
 *   ${app.raster-layer-dir}/{layerId}/original/{filename}[, {filename}.wld]
 *   ${app.raster-layer-dir}/{layerId}/tiles/{z}/{x}/{y}.png
 * </pre>
 */
@Service
public class UserRasterService {

    private static final Logger log = LoggerFactory.getLogger(UserRasterService.class);

    static final String GEOTIFF = "GEOTIFF";
    static final String WORLDFILE = "WORLDFILE";

    static final String UPLOADED = "UPLOADED";
    static final String PROCESSING = "PROCESSING";
    static final String PUBLISHED = "PUBLISHED";
    static final String FAILED = "FAILED";

    private static final int TILE = 256;
    private static final double MERCATOR_EXTENT = 20037508.342789244;
    private static final double Z0_RESOLUTION = 2 * MERCATOR_EXTENT / TILE;
    private static final int MAX_ZOOM_CEILING = 22;
    private static final int MAX_BASE_TILES = 60_000;
    private static final int MAX_READ_SPAN = 2048;
    private static final long MAX_PIXELS = 800_000_000L;
    private static final java.util.Set<Integer> SUPPORTED_DEPTHS = java.util.Set.of(8, 16, 32, 64);

    private final JdbcTemplate jdbc;
    private final Path root;

    /** Single-threaded, same reasoning as {@link DroneRasterService}'s builder —
     *  a pyramid build is CPU/IO bound and the box also serves the map. Its own
     *  executor rather than sharing Drone's, so a queue of drone publishes cannot
     *  starve a small temp-layer raster and vice versa. */
    private final ExecutorService builder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "user-raster-tile-builder");
        t.setDaemon(true);
        return t;
    });

    public UserRasterService(JdbcTemplate jdbc, @Value("${app.raster-layer-dir:raster-layer-store}") String dir) {
        this.jdbc = jdbc;
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    @PostConstruct
    void ensure() {
        try {
            Files.createDirectories(root);
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS postgis");
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS layer_raster (
                    layer_id       integer PRIMARY KEY REFERENCES layer_definition(id) ON DELETE CASCADE,
                    source_format  text NOT NULL,
                    file_name      text NOT NULL,
                    file_path      text NOT NULL,
                    sidecar_path   text,
                    file_size      bigint,
                    epsg           integer,
                    crs_name       text,
                    res_x          double precision,
                    res_y          double precision,
                    raster_width   integer,
                    raster_height  integer,
                    min_x          double precision,
                    min_y          double precision,
                    max_x          double precision,
                    max_y          double precision,
                    band_count     integer,
                    data_type      text,
                    colour_interp  text,
                    band_stats     jsonb,
                    no_data        double precision,
                    is_dem         boolean NOT NULL DEFAULT false,
                    elevation_min  double precision,
                    elevation_max  double precision,
                    warnings       text,
                    geo_details    json,
                    footprint      geometry(Polygon,4326),
                    status         text NOT NULL DEFAULT 'UPLOADED',
                    status_message text,
                    min_zoom       integer,
                    max_zoom       integer,
                    default_opacity double precision NOT NULL DEFAULT 0.85,
                    build_version  integer NOT NULL DEFAULT 0,
                    created_by     text,
                    created_at     timestamptz NOT NULL DEFAULT now(),
                    updated_at     timestamptz NOT NULL DEFAULT now()
                )""");
            // Added after the table already shipped once — ALTER rather than only
            // the CREATE above, so an existing layer_raster table (this module is
            // new, but so is every database it has already run against once)
            // picks up the DEM columns instead of failing every insert.
            jdbc.execute("ALTER TABLE layer_raster ADD COLUMN IF NOT EXISTS is_dem boolean NOT NULL DEFAULT false");
            jdbc.execute("ALTER TABLE layer_raster ADD COLUMN IF NOT EXISTS elevation_min double precision");
            jdbc.execute("ALTER TABLE layer_raster ADD COLUMN IF NOT EXISTS elevation_max double precision");
            // Generalises the DEM-only ramp above to any single-band continuous
            // value — rainfall, population density, and so on — not just height.
            // colour_ramp NULL/blank means "off" (drawn grey/RGB as before);
            // otherwise it names which palette (see rampColour) and value_label
            // is what the click popup shows instead of a hardcoded "Elevation".
            jdbc.execute("ALTER TABLE layer_raster ADD COLUMN IF NOT EXISTS colour_ramp text");
            jdbc.execute("ALTER TABLE layer_raster ADD COLUMN IF NOT EXISTS value_label text");
            jdbc.execute("ALTER TABLE layer_raster ADD COLUMN IF NOT EXISTS value_min double precision");
            jdbc.execute("ALTER TABLE layer_raster ADD COLUMN IF NOT EXISTS value_max double precision");
            jdbc.execute("CREATE INDEX IF NOT EXISTS layer_raster_footprint_idx ON layer_raster USING GIST (footprint)");
        } catch (Exception e) {
            log.error("User raster module init failed — raster temporary layers may be degraded, "
                    + "but the app will keep starting", e);
        }
    }

    /* ------------------------------------------------------------------
       Upload
       ------------------------------------------------------------------ */

    /** Palettes {@link #rampColour} understands. Anything else falls back to ELEVATION. */
    static final java.util.Set<String> RAMPS = java.util.Set.of("ELEVATION", "BLUE", "HEAT");

    /**
     * Store a GeoTIFF upload against an already-created RASTER layer.
     *
     * @param colourRamp when non-blank, the raster is single-band continuous data
     *            (elevation, rainfall, population density, …) coloured by the
     *            named ramp — see {@link #rampColour} — rather than drawn
     *            grayscale/RGB. Explicit rather than auto-detected: a
     *            single-band GeoTIFF is just as often a scanned drawing or a
     *            classification map, and guessing wrong would silently paint
     *            one kind of value as if it were another.
     * @param valueLabel what the click popup calls the value, e.g.
     *            "Elevation (m)", "Rainfall (mm)", "Population density
     *            (people/km²)". Falls back to "Value" if blank.
     */
    Map<String, Object> store(int layerId, MultipartFile file, String colourRamp, String valueLabel, String user)
            throws IOException {
        requireNoExistingRaster(layerId);
        String original = safeFileName(file == null ? null : file.getOriginalFilename());
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("Choose a GeoTIFF file to upload.");
        String lower = original.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".tif") && !lower.endsWith(".tiff"))
            throw new IllegalArgumentException("Only GeoTIFF (.tif / .tiff) is accepted here — "
                    + "for a JPG/PNG, use the \"image + world file\" upload instead.");
        String ramp = (colourRamp == null || colourRamp.isBlank()) ? null : colourRamp.trim().toUpperCase(Locale.ROOT);
        boolean rampMode = ramp != null;

        Path staging = Files.createTempFile(root, "upload-", ".tif");
        try {
            copy(file, staging);
            GeoTiffMeta meta = GeoTiffMeta.read(staging);
            double[] range = null;
            if (rampMode) {
                range = sampleElevationRange(staging, meta);
                if (range[0] >= range[1])
                    throw new IllegalArgumentException(
                            "This raster contains no usable values to colour by — every pixel is nodata.");
            }
            String warnings = validate(meta, GEOTIFF, rampMode);
            return finishStore(layerId, GEOTIFF, staging, original, null, meta, warnings,
                    ramp, DroneService.blankToNull(valueLabel), range, user);
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    /** Store a JPG/PNG + {@code .jgw}/{@code .pgw} world-file pair. */
    Map<String, Object> storeWorldFile(int layerId, MultipartFile image, MultipartFile worldfile, String user)
            throws IOException {
        requireNoExistingRaster(layerId);
        if (image == null || image.isEmpty())
            throw new IllegalArgumentException("Choose an image file (JPG or PNG) to upload.");
        if (worldfile == null || worldfile.isEmpty())
            throw new IllegalArgumentException("Choose the matching world file (.jgw/.pgw/.wld).");

        String original = safeFileName(image.getOriginalFilename());
        String lower = original.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg") && !lower.endsWith(".png"))
            throw new IllegalArgumentException("Only JPG or PNG is accepted here — for a GeoTIFF, "
                    + "use the GeoTIFF upload instead.");

        Path stagingImg = Files.createTempFile(root, "upload-", "-" + original);
        Path stagingWld = Files.createTempFile(root, "upload-", ".wld");
        try {
            copy(image, stagingImg);
            copy(worldfile, stagingWld);
            WorldFileGeoRef geo = WorldFileGeoRef.read(stagingImg, stagingWld);
            String warnings = validateWorldFile(geo);
            return finishStore(layerId, WORLDFILE, stagingImg, original, stagingWld, geo, warnings,
                    null, null, null, user);
        } finally {
            Files.deleteIfExists(stagingImg);
            Files.deleteIfExists(stagingWld);
        }
    }

    private void requireNoExistingRaster(int layerId) {
        requireRasterLayer(layerId);
        Integer n = jdbc.queryForObject("SELECT count(*) FROM layer_raster WHERE layer_id = ?", Integer.class, layerId);
        if (n != null && n > 0)
            throw new IllegalArgumentException("This layer already has a raster uploaded. Discard the "
                    + "layer and create a new one to replace it.");
    }

    private void requireRasterLayer(int layerId) {
        String geom;
        try {
            geom = jdbc.queryForObject(
                    "SELECT geometry_type FROM layer_definition WHERE id = ? AND source_type = 'USER'",
                    String.class, layerId);
        } catch (Exception e) {
            throw new IllegalArgumentException("No such layer.");
        }
        if (!"RASTER".equals(geom)) throw new IllegalArgumentException("This layer is not a raster layer.");
    }

    private static void copy(MultipartFile src, Path dest) throws IOException {
        try (InputStream in = src.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Map<String, Object> finishStore(int layerId, String format, Path stagingImage, String original,
                                            Path stagingSidecar, RasterGeoRef geo, String warnings,
                                            String colourRamp, String valueLabel, double[] valueRange, String user)
            throws IOException {
        double[] b = geo.wgs84Bounds();
        int epsg = format.equals(GEOTIFF) ? ((GeoTiffMeta) geo).crs.epsg() : 4326;
        String crsName = format.equals(GEOTIFF) ? ((GeoTiffMeta) geo).crs.label() : "EPSG:4326 — assumed (world file)";
        String dataType = format.equals(GEOTIFF) ? ((GeoTiffMeta) geo).dataType() : ((WorldFileGeoRef) geo).dataType();
        String colourInterp = format.equals(GEOTIFF)
                ? ((GeoTiffMeta) geo).colourInterpretation() : ((WorldFileGeoRef) geo).colourInterpretation();
        double noData = geo.noData();

        jdbc.update("""
            INSERT INTO layer_raster
                (layer_id, source_format, file_name, file_path, sidecar_path, file_size,
                 epsg, crs_name, res_x, res_y, raster_width, raster_height,
                 min_x, min_y, max_x, max_y, band_count, data_type, colour_interp, no_data,
                 colour_ramp, value_label, value_min, value_max, warnings,
                 footprint, status, created_by)
            VALUES (?, ?, ?, '', '', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ST_MakeEnvelope(?, ?, ?, ?, 4326), ?, ?)
            """,
                layerId, format, original, Files.size(stagingImage),
                epsg, crsName, geo.resX(), geo.resY(), geo.width(), geo.height(),
                b[0], b[1], b[2], b[3], geo.samplesPerPixel(), dataType, colourInterp,
                Double.isNaN(noData) ? null : noData,
                colourRamp, valueLabel, valueRange == null ? null : valueRange[0],
                valueRange == null ? null : valueRange[1], warnings,
                b[0], b[1], b[2], b[3], UPLOADED, user);

        Path dir = originalDir(layerId);
        Files.createDirectories(dir);
        Path targetImage = dir.resolve(original);
        Files.move(stagingImage, targetImage, StandardCopyOption.REPLACE_EXISTING);
        String sidecarPath = "";
        if (stagingSidecar != null) {
            Path targetSidecar = dir.resolve(original + ".wld");
            Files.move(stagingSidecar, targetSidecar, StandardCopyOption.REPLACE_EXISTING);
            sidecarPath = targetSidecar.toString();
        }
        jdbc.update("UPDATE layer_raster SET file_path = ?, sidecar_path = ? WHERE layer_id = ?",
                targetImage.toString(), sidecarPath, layerId);

        return row(layerId);
    }

    private String validate(GeoTiffMeta meta, String kind, boolean rampMode) {
        if (!SUPPORTED_DEPTHS.contains(meta.bitsPerSample))
            throw new IllegalArgumentException(meta.bitsPerSample + "-bit imagery cannot be read. "
                    + "Re-export it as 8-bit or 16-bit GeoTIFF.");
        long pixels = (long) meta.width * meta.height;
        if (pixels > MAX_PIXELS)
            throw new IllegalArgumentException(String.format(
                    "This raster is %,d × %,d, which is too large to tile here. Split it, or downsample it.",
                    meta.width, meta.height));
        List<String> notes = new ArrayList<>();
        if (rampMode) {
            if (meta.samplesPerPixel > 1)
                notes.add("Band 1 will be read as the value to colour by; the other "
                        + (meta.samplesPerPixel - 1) + " will be ignored.");
            if (meta.sampleFormat == 1 && meta.bitsPerSample == 8)
                notes.add("Values are 8-bit whole numbers (0-255). If this is a photograph "
                        + "or classification map rather than continuous data, upload it without "
                        + "\"Colour by value\" ticked.");
            return notes.isEmpty() ? null : String.join(" ", notes);
        }
        int colourBands = meta.samplesPerPixel - (meta.hasAlpha ? 1 : 0);
        if (colourBands == 1 && meta.photometric != 3)
            notes.add("Only one band, so this will be drawn in grey rather than colour.");
        else if (colourBands > 3)
            notes.add("Bands 1-3 will be drawn as red, green and blue; the rest will not be shown.");
        if (Double.isNaN(meta.noData) && meta.alphaBand < 0)
            notes.add("No nodata value and no alpha band, so any collar around the image "
                    + "will only be transparent where it is pure black.");
        return notes.isEmpty() ? null : String.join(" ", notes);
    }

    private String validateWorldFile(WorldFileGeoRef geo) {
        long pixels = (long) geo.width * geo.height;
        if (pixels > MAX_PIXELS)
            throw new IllegalArgumentException(String.format(
                    "This image is %,d × %,d, which is too large to tile here. Split it, or downsample it.",
                    geo.width, geo.height));
        List<String> notes = new ArrayList<>();
        if (geo.rotated)
            notes.add("The world file declares a rotated/sheared transform; this is drawn correctly "
                    + "but is unusual for a hand-georeferenced scan — double-check the sidecar values.");
        return notes.isEmpty() ? null : String.join(" ", notes);
    }

    /* ------------------------------------------------------------------
       Publish
       ------------------------------------------------------------------ */

    void publish(int layerId) {
        Map<String, Object> d = row(layerId);
        if (PROCESSING.equals(d.get("status")))
            throw new IllegalArgumentException("This raster is already being processed.");

        int version = ((Number) d.get("buildVersion")).intValue() + 1;
        jdbc.update("UPDATE layer_raster SET status = ?, status_message = NULL, build_version = ?, "
                + "updated_at = now() WHERE layer_id = ?", PROCESSING, version, layerId);

        builder.submit(() -> {
            try {
                int[] zooms = buildPyramid(layerId, d);
                jdbc.update("UPDATE layer_raster SET status = ?, min_zoom = ?, max_zoom = ?, "
                        + "status_message = NULL, updated_at = now() WHERE layer_id = ?",
                        PUBLISHED, zooms[0], zooms[1], layerId);
                log.info("User raster layer {} published: zoom {}..{}", layerId, zooms[0], zooms[1]);
            } catch (Throwable e) {
                log.error("User raster layer {} tile build failed", layerId, e);
                jdbc.update("UPDATE layer_raster SET status = ?, status_message = ?, updated_at = now() "
                        + "WHERE layer_id = ?", FAILED, ApiErrors.safe("raster layer build " + layerId, e), layerId);
            }
        });
    }

    private RasterGeoRef geoRefOf(Map<String, Object> d) throws IOException {
        String format = String.valueOf(d.get("sourceFormat"));
        Path file = Path.of(String.valueOf(d.get("filePath")));
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("The uploaded file is missing from storage.");
        if (GEOTIFF.equals(format)) return GeoTiffMeta.read(file);
        Path sidecar = Path.of(String.valueOf(d.get("sidecarPath")));
        return WorldFileGeoRef.read(file, sidecar);
    }

    private int[] buildPyramid(int layerId, Map<String, Object> dataset) throws IOException {
        RasterGeoRef geo = geoRefOf(dataset);
        Path file = Path.of(String.valueOf(dataset.get("filePath")));
        double[] bounds = geo.wgs84Bounds();

        String ramp = (String) dataset.get("colourRamp");
        boolean rampMode = ramp != null && !ramp.isBlank();
        double valueMin = 0, valueMax = 1;
        if (rampMode) {
            Number lo = (Number) dataset.get("valueMin"), hi = (Number) dataset.get("valueMax");
            double[] range = (lo == null || hi == null) ? sampleElevationRange(file, geo)
                    : new double[]{lo.doubleValue(), hi.doubleValue()};
            valueMin = range[0];
            valueMax = range[1] > range[0] ? range[1] : range[0] + 1;
        }

        // Only a non-ramp GeoTIFF that isn't already 8-bit needs a stretched
        // display window; a ramp layer is coloured by valueMin/valueMax instead,
        // and 8-bit data already speaks 0-255.
        RasterBandStats window = null;
        if (!rampMode && geo instanceof GeoTiffMeta meta && needsStretch(meta)) {
            window = RasterBandStats.measure(sampleValues(file, geo), meta);
        }

        int maxZoom = nativeZoom(geo, bounds);
        int minZoom = overviewZoom(bounds);

        Path tiles = tilesDir(layerId);
        deleteTree(tiles);
        Files.createDirectories(tiles);

        try (Reading reading = Reading.open(file)) {
            int[] range = tileRange(bounds, maxZoom);
            for (int x = range[0]; x <= range[2]; x++) {
                for (int y = range[1]; y <= range[3]; y++) {
                    BufferedImage img = renderTile(reading, geo, window, rampMode ? ramp : null, valueMin, valueMax, maxZoom, x, y);
                    if (img != null) writeTile(tiles, maxZoom, x, y, img);
                }
            }
        }
        for (int z = maxZoom - 1; z >= minZoom; z--) {
            int[] range = tileRange(bounds, z);
            for (int x = range[0]; x <= range[2]; x++) {
                for (int y = range[1]; y <= range[3]; y++) {
                    BufferedImage img = mergeChildren(tiles, z, x, y);
                    if (img != null) writeTile(tiles, z, x, y, img);
                }
            }
        }
        return new int[]{minZoom, maxZoom};
    }

    /** One 256px tile, or {@code null} when every pixel of it falls outside the raster. */
    private BufferedImage renderTile(Reading reading, RasterGeoRef geo, RasterBandStats window,
                                     String ramp, double valueMin, double valueMax,
                                     int z, int tx, int ty) throws IOException {
        double span = 2 * MERCATOR_EXTENT / (1 << z);
        double west = -MERCATOR_EXTENT + tx * span;
        double north = MERCATOR_EXTENT - ty * span;
        double step = span / TILE;

        double minCol = Double.MAX_VALUE, minRow = Double.MAX_VALUE;
        double maxCol = -Double.MAX_VALUE, maxRow = -Double.MAX_VALUE;
        for (int i = 0; i <= 8; i++) {
            for (int j = 0; j <= 8; j++) {
                double[] p = sourcePixel(geo, west + i * span / 8.0, north - j * span / 8.0);
                minCol = Math.min(minCol, p[0]); maxCol = Math.max(maxCol, p[0]);
                minRow = Math.min(minRow, p[1]); maxRow = Math.max(maxRow, p[1]);
            }
        }
        int x0 = (int) Math.floor(minCol) - 1, y0 = (int) Math.floor(minRow) - 1;
        int x1 = (int) Math.ceil(maxCol) + 1, y1 = (int) Math.ceil(maxRow) + 1;
        x0 = Math.max(0, x0); y0 = Math.max(0, y0);
        x1 = Math.min(geo.width() - 1, x1); y1 = Math.min(geo.height() - 1, y1);
        if (x1 < x0 || y1 < y0) return null;

        int w = x1 - x0 + 1, h = y1 - y0 + 1;
        int sub = Math.max(1, (int) Math.ceil(Math.max(w, h) / (double) MAX_READ_SPAN));

        BufferedImage out = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[TILE];
        boolean any = false;

        if (ramp != null) {
            Raster src = reading.readRaster(x0, y0, w, h, sub);
            for (int py = 0; py < TILE; py++) {
                double my = north - (py + 0.5) * step;
                for (int px = 0; px < TILE; px++) {
                    double[] p = sourcePixel(geo, west + (px + 0.5) * step, my);
                    int sx = (int) ((p[0] - x0) / sub), sy = (int) ((p[1] - y0) / sub);
                    if (sx < 0 || sy < 0 || sx >= src.getWidth() || sy >= src.getHeight()) { row[px] = 0; continue; }
                    double v = src.getSampleDouble(sx, sy, 0);
                    if (isNoData(v, geo)) { row[px] = 0; continue; }
                    row[px] = rampColour(ramp, (v - valueMin) / (valueMax - valueMin));
                    any = true;
                }
                out.setRGB(0, py, TILE, 1, row, 0, TILE);
            }
        } else if (geo.photometric() == 3) {
            BufferedImage src = reading.readImage(x0, y0, w, h, sub);
            int sw = src.getWidth(), sh = src.getHeight();
            for (int py = 0; py < TILE; py++) {
                double my = north - (py + 0.5) * step;
                for (int px = 0; px < TILE; px++) {
                    double[] p = sourcePixel(geo, west + (px + 0.5) * step, my);
                    int sx = (int) ((p[0] - x0) / sub), sy = (int) ((p[1] - y0) / sub);
                    if (sx < 0 || sy < 0 || sx >= sw || sy >= sh) { row[px] = 0; continue; }
                    int argb = src.getRGB(sx, sy);
                    if ((argb >>> 24) == 0) { row[px] = 0; continue; }
                    row[px] = argb | 0xFF000000;
                    any = true;
                }
                out.setRGB(0, py, TILE, 1, row, 0, TILE);
            }
        } else {
            Raster src = reading.readRaster(x0, y0, w, h, sub);
            int sw = src.getWidth(), sh = src.getHeight(), nb = src.getNumBands();
            int[] show = geo.displayBands();
            boolean rgb = show.length == 3 && nb >= 3;
            int rB = show[0], gB = rgb ? show[1] : show[0], bB = rgb ? show[2] : show[0];
            int aB = (geo.alphaBand() >= 0 && geo.alphaBand() < nb) ? geo.alphaBand() : -1;

            for (int py = 0; py < TILE; py++) {
                double my = north - (py + 0.5) * step;
                for (int px = 0; px < TILE; px++) {
                    double[] p = sourcePixel(geo, west + (px + 0.5) * step, my);
                    int sx = (int) ((p[0] - x0) / sub), sy = (int) ((p[1] - y0) / sub);
                    if (sx < 0 || sy < 0 || sx >= sw || sy >= sh) { row[px] = 0; continue; }

                    if (aB >= 0 && src.getSampleDouble(sx, sy, aB) <= 0) { row[px] = 0; continue; }

                    double r0 = src.getSampleDouble(sx, sy, rB);
                    if (isNoData(r0, geo)) { row[px] = 0; continue; }

                    int r = level(r0, window, rB);
                    int g = rgb ? level(src.getSampleDouble(sx, sy, gB), window, gB) : r;
                    int b = rgb ? level(src.getSampleDouble(sx, sy, bB), window, bB) : r;

                    if (aB < 0 && Double.isNaN(geo.noData()) && r == 0 && g == 0 && b == 0) {
                        row[px] = 0;
                        continue;
                    }
                    row[px] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    any = true;
                }
                out.setRGB(0, py, TILE, 1, row, 0, TILE);
            }
        }
        return any ? out : null;
    }

    private static int level(double v, RasterBandStats window, int band) {
        if (window == null || band >= window.bands) return (int) Math.max(0, Math.min(255, v));
        double lo = window.low[band], hi = window.high[band];
        if (hi <= lo) return 0;
        double t = (v - lo) / (hi - lo);
        int out = (int) Math.round(t * 255);
        return out < 0 ? 0 : (out > 255 ? 255 : out);
    }

    private static double[] sourcePixel(RasterGeoRef geo, double mx, double my) {
        double lon = mx / MERCATOR_EXTENT * 180;
        double lat = Math.toDegrees(2 * Math.atan(Math.exp(Math.toRadians(my / MERCATOR_EXTENT * 180))) - Math.PI / 2);
        double[] model = geo.fromWgs84(lon, lat);
        return geo.modelToPixel(model[0], model[1]);
    }

    private BufferedImage mergeChildren(Path tiles, int z, int x, int y) throws IOException {
        BufferedImage out = null;
        Graphics2D g = null;
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 2; dy++) {
                Path child = tiles.resolve(String.valueOf(z + 1))
                        .resolve(String.valueOf(x * 2 + dx)).resolve((y * 2 + dy) + ".png");
                if (!Files.isRegularFile(child)) continue;
                BufferedImage img = ImageIO.read(child.toFile());
                if (img == null) continue;
                if (out == null) {
                    out = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
                    g = out.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                }
                g.drawImage(img, dx * TILE / 2, dy * TILE / 2, TILE / 2, TILE / 2, null);
            }
        }
        if (g != null) g.dispose();
        return out;
    }

    private static void writeTile(Path tiles, int z, int x, int y, BufferedImage img) throws IOException {
        Path dir = tiles.resolve(String.valueOf(z)).resolve(String.valueOf(x));
        Files.createDirectories(dir);
        ImageIO.write(img, "png", dir.resolve(y + ".png").toFile());
    }

    private int nativeZoom(RasterGeoRef geo, double[] bounds) {
        double midLat = Math.toRadians((bounds[1] + bounds[3]) / 2);
        double metresPerPixel = Math.max(1e-4, geo.resolutionMetres());
        int z = (int) Math.round(Math.log(Z0_RESOLUTION * Math.cos(midLat) / metresPerPixel) / Math.log(2));
        z = Math.max(1, Math.min(MAX_ZOOM_CEILING, z));
        while (z > 1) {
            int[] r = tileRange(bounds, z);
            long count = (long) (r[2] - r[0] + 1) * (r[3] - r[1] + 1);
            if (count <= MAX_BASE_TILES) break;
            z--;
        }
        return z;
    }

    private int overviewZoom(double[] bounds) {
        for (int z = 0; z <= MAX_ZOOM_CEILING; z++) {
            int[] r = tileRange(bounds, z);
            if ((r[2] - r[0]) >= 1 || (r[3] - r[1]) >= 1) return Math.max(0, z - 1);
        }
        return 0;
    }

    private static int[] tileRange(double[] b, int z) {
        int n = 1 << z;
        int minX = clamp((int) Math.floor((b[0] + 180) / 360 * n), n);
        int maxX = clamp((int) Math.floor((b[2] + 180) / 360 * n), n);
        int minY = clamp((int) Math.floor(yTile(b[3], n)), n);
        int maxY = clamp((int) Math.floor(yTile(b[1], n)), n);
        return new int[]{minX, minY, maxX, maxY};
    }

    private static double yTile(double lat, int n) {
        double r = Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, lat)));
        return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * n;
    }

    private static int clamp(int v, int n) {
        return Math.max(0, Math.min(n - 1, v));
    }

    /**
     * Named colour ramps for single-band continuous data — elevation, rainfall,
     * population density, or anything else where "low value = one colour, high
     * value = another" reads naturally. {@code t} is 0-1, already normalised
     * against the raster's own measured range.
     */
    private static int rampColour(String ramp, double t) {
        double v = Math.max(0, Math.min(1, t));
        int[][] stops = switch (ramp) {
            // Hypsometric: green through yellow to red-brown — the same ramp
            // DroneRasterService uses for a DEM, so elevation reads the same way
            // whether it came in through Drone or as a temporary layer.
            case "BLUE" ->
                // Light to dark blue — rainfall, water depth, anything where
                // "more" should read as "deeper", not "hotter".
                    new int[][]{{247, 251, 255}, {198, 219, 239}, {107, 174, 214}, {33, 113, 181}, {8, 48, 107}};
            case "HEAT" ->
                // Pale yellow through orange to dark red — density-style data
                // (population, traffic, incidence) where "more" should read as
                // "hotter", not "deeper" or "higher".
                    new int[][]{{255, 255, 204}, {254, 217, 118}, {253, 141, 60}, {227, 26, 28}, {128, 0, 38}};
            default ->
                    new int[][]{{26, 152, 80}, {166, 217, 106}, {255, 255, 191}, {253, 174, 97}, {215, 48, 39}};
        };
        double scaled = v * (stops.length - 1);
        int i = Math.min(stops.length - 2, (int) scaled);
        double f = scaled - i;
        int r = (int) Math.round(stops[i][0] + f * (stops[i + 1][0] - stops[i][0]));
        int g = (int) Math.round(stops[i][1] + f * (stops[i + 1][1] - stops[i][1]));
        int b = (int) Math.round(stops[i][2] + f * (stops[i + 1][2] - stops[i][2]));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Min/max over a subsampled read of the whole raster's band 0 — see sampleValues. */
    private double[] sampleElevationRange(Path file, RasterGeoRef geo) throws IOException {
        double[][] samples = sampleValues(file, geo);
        if (samples.length == 0) return new double[]{0, 0};
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double v : samples[0]) {
            if (isNoData(v, geo)) continue;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        return min > max ? new double[]{0, 0} : new double[]{min, max};
    }

    private static boolean isNoData(double v, RasterGeoRef geo) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return true;
        if (v <= -1e30 || v >= 1e30) return true;
        double nd = geo.noData();
        return !Double.isNaN(nd) && Math.abs(v - nd) < 1e-6;
    }

    private static boolean needsStretch(GeoTiffMeta meta) {
        return !(meta.sampleFormat == 1 && meta.bitsPerSample == 8);
    }

    private static final int TARGET_SAMPLES = 400_000;
    private static final int SAMPLE_STRIP_ROWS = 64;
    private static final int MAX_SAMPLE_STRIPS = 32;

    /** Values per band, sampled across the raster — see DroneRasterService.sampleValues for why
     *  this reads honest strips rather than using ImageIO's subsampling (it truncates floats). */
    private double[][] sampleValues(Path file, RasterGeoRef geo) throws IOException {
        int bands = geo.samplesPerPixel();
        int height = geo.height(), width = geo.width();
        int rows = Math.min(SAMPLE_STRIP_ROWS, height);
        int strips = Math.max(1, Math.min(MAX_SAMPLE_STRIPS, height / rows));
        int stride = Math.max(1, (strips * rows * width) / TARGET_SAMPLES);

        double[][] out = new double[bands][];
        int[] n = new int[bands];
        int cap = Math.max(1024, (strips * rows * width) / stride + strips);
        for (int b = 0; b < bands; b++) out[b] = new double[cap];

        try (Reading r = Reading.open(file)) {
            for (int s = 0; s < strips; s++) {
                int y0 = (int) ((long) s * (height - rows) / Math.max(1, strips - 1));
                if (strips == 1) y0 = 0;
                Raster ras = r.readRaster(0, y0, width, rows, 1);
                int rw = ras.getWidth(), rh = ras.getHeight();
                int nb = Math.min(bands, ras.getNumBands());
                for (int i = 0; i < rw * rh; i += stride) {
                    int x = i % rw, y = i / rw;
                    if (y >= rh) break;
                    for (int b = 0; b < nb; b++) {
                        if (n[b] < cap) out[b][n[b]++] = ras.getSampleDouble(x, y, b);
                    }
                }
            }
        }
        for (int b = 0; b < bands; b++) out[b] = java.util.Arrays.copyOf(out[b], n[b]);
        return out;
    }

    /* ------------------------------------------------------------------
       Reads
       ------------------------------------------------------------------ */

    Map<String, Object> row(int layerId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT layer_id, source_format AS "sourceFormat", file_name AS "fileName",
                   file_path AS "filePath", sidecar_path AS "sidecarPath", file_size AS "fileSize",
                   epsg, crs_name AS "crsName", res_x AS "resX", res_y AS "resY",
                   raster_width AS "rasterWidth", raster_height AS "rasterHeight",
                   min_x AS "minX", min_y AS "minY", max_x AS "maxX", max_y AS "maxY",
                   band_count AS "bandCount", data_type AS "dataType", colour_interp AS "colourInterp",
                   no_data AS "noData", colour_ramp AS "colourRamp", value_label AS "valueLabel",
                   value_min AS "valueMin", value_max AS "valueMax", warnings, status, status_message AS "statusMessage",
                   min_zoom AS "minZoom", max_zoom AS "maxZoom", default_opacity AS "defaultOpacity",
                   build_version AS "buildVersion", created_by AS "createdBy", created_at AS "createdAt"
            FROM layer_raster WHERE layer_id = ?
            """, layerId);
        if (rows.isEmpty()) throw new IllegalArgumentException("This layer has no raster uploaded yet.");
        return rows.get(0);
    }

    boolean hasRaster(int layerId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM layer_raster WHERE layer_id = ?", Integer.class, layerId);
        return n != null && n > 0;
    }

    /** Persist the map viewer's opacity slider so it survives a reload. */
    void setOpacity(int layerId, double opacity) {
        double clamped = Math.max(0, Math.min(1, opacity));
        int n = jdbc.update("UPDATE layer_raster SET default_opacity = ?, updated_at = now() WHERE layer_id = ?",
                clamped, layerId);
        if (n == 0) throw new IllegalArgumentException("This layer has no raster uploaded yet.");
    }

    /**
     * May this raster's tiles/pixels be served to {@code user}?
     *
     * <p>Same visibility rule {@link UserLayerTileService#tile} applies to a
     * vector temp layer — owner or shared, not hidden, not frozen — plus the
     * raster itself having a built pyramid.
     */
    boolean isDrawable(int layerId, String user) {
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap(
                    "SELECT temporary, created_by, shared, frozen, hidden FROM layer_definition "
                  + "WHERE id = ? AND source_type = 'USER' AND geometry_type = 'RASTER'", layerId);
        } catch (Exception e) {
            return false;
        }
        if (Boolean.TRUE.equals(row.get("frozen")) || Boolean.TRUE.equals(row.get("hidden"))) return false;
        if (Boolean.TRUE.equals(row.get("temporary"))
                && !Boolean.TRUE.equals(row.get("shared"))
                && !String.valueOf(row.get("created_by")).equals(user)) {
            return false;
        }
        String status = jdbc.query(
                "SELECT status FROM layer_raster WHERE layer_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, layerId);
        return PUBLISHED.equals(status);
    }

    /* ------------------------------------------------------------------
       Tile serving / pixel sampling
       ------------------------------------------------------------------ */

    Path tileFile(int layerId, int z, int x, int y) {
        Path p = tilesDir(layerId).resolve(String.valueOf(z)).resolve(String.valueOf(x)).resolve(y + ".png");
        return Files.isRegularFile(p) ? p : null;
    }

    /** All bands at a WGS84 point, or {@code null} outside the raster. */
    Map<String, Object> pixelValueAt(int layerId, double lon, double lat) throws IOException {
        Map<String, Object> d = row(layerId);
        RasterGeoRef geo = geoRefOf(d);
        double[] model = geo.fromWgs84(lon, lat);
        double[] px = geo.modelToPixel(model[0], model[1]);
        int col = (int) Math.round(px[0]), row = (int) Math.round(px[1]);
        if (col < 0 || row < 0 || col >= geo.width() || row >= geo.height()) return null;

        Path file = Path.of(String.valueOf(d.get("filePath")));
        try (Reading r = Reading.open(file)) {
            if (geo.photometric() == 3) {
                BufferedImage img = r.readImage(col, row, 1, 1, 1);
                int argb = img.getRGB(0, 0);
                return Map.of("bands", List.of((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF));
            }
            Raster raster = r.readRaster(col, row, 1, 1, 1);
            int nb = raster.getNumBands();
            List<Double> values = new ArrayList<>();
            for (int b = 0; b < nb; b++) {
                if (b == geo.alphaBand()) continue;
                double v = raster.getSampleDouble(0, 0, b);
                values.add(isNoData(v, geo) ? null : v);
            }
            return Map.of("bands", values);
        }
    }

    /* ------------------------------------------------------------------
       Delete
       ------------------------------------------------------------------ */

    /** Remove the raster's on-disk files. Called unconditionally when a raster
     *  layer's registry row is deleted — see LayerRegistryService.deleteLayer:
     *  an orphaned raster directory with no row pointing at it can never be
     *  found again, unlike a named PostGIS table. */
    void deleteFiles(int layerId) throws IOException {
        deleteTree(layerDir(layerId));
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private Path layerDir(int layerId) {
        return root.resolve(String.valueOf(layerId));
    }

    private Path originalDir(int layerId) {
        return layerDir(layerId).resolve("original");
    }

    private Path tilesDir(int layerId) {
        return layerDir(layerId).resolve("tiles");
    }

    private static String safeFileName(String raw) {
        String s = raw == null ? "" : raw.trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        s = s.replaceAll("[^A-Za-z0-9._-]", "_");
        while (s.startsWith(".")) s = s.substring(1);
        if (s.isEmpty()) throw new IllegalArgumentException("The uploaded file has no usable name.");
        return s.length() > 120 ? s.substring(s.length() - 120) : s;
    }

    /* ------------------------------------------------------------------
       Source reading — same approach as DroneRasterService.Reading: region
       reads bound memory regardless of file size, and read().getRaster() is
       used (not readRaster()) because the JDK TIFF plugin's canReadRaster()
       returns false and throws from the direct call.
       ------------------------------------------------------------------ */
    private static final class Reading implements AutoCloseable {
        private final ImageInputStream stream;
        private final ImageReader reader;

        private Reading(ImageInputStream stream, ImageReader reader) {
            this.stream = stream;
            this.reader = reader;
        }

        static Reading open(Path file) throws IOException {
            ImageInputStream iis = ImageIO.createImageInputStream(file.toFile());
            if (iis == null) throw new IllegalArgumentException("The raster file could not be opened.");
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext()) {
                iis.close();
                throw new IllegalArgumentException("No reader can decode this file.");
            }
            ImageReader r = it.next();
            r.setInput(iis, true, true);
            return new Reading(iis, r);
        }

        private ImageReadParam param(int x, int y, int w, int h, int sub) {
            ImageReadParam p = reader.getDefaultReadParam();
            p.setSourceRegion(new java.awt.Rectangle(x, y, w, h));
            if (sub > 1) p.setSourceSubsampling(sub, sub, 0, 0);
            return p;
        }

        Raster readRaster(int x, int y, int w, int h, int sub) throws IOException {
            return reader.read(0, param(x, y, w, h, sub)).getRaster();
        }

        BufferedImage readImage(int x, int y, int w, int h, int sub) throws IOException {
            return reader.read(0, param(x, y, w, h, sub));
        }

        @Override
        public void close() throws IOException {
            reader.dispose();
            stream.close();
        }
    }
}
