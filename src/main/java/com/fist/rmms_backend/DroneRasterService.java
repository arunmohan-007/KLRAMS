package com.fist.rmms_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Comparator;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * The drone raster pipeline: store the upload, read its georeferencing, and on
 * publish turn it into web map tiles.
 *
 * <h2>Why a tile pyramid instead of a COG</h2>
 * The point of a COG is that a browser can fetch byte ranges of one large file
 * instead of the whole thing. Producing one requires GDAL, which would mean a
 * native toolchain in an image that currently ships nothing but a JRE. A
 * pre-built XYZ pyramid achieves the same end — the browser never downloads the
 * GeoTIFF, only 256px PNGs for the view it is looking at — using the TIFF reader
 * already in the JDK. The original upload is kept untouched beside the tiles, so
 * nothing is lost if a COG pipeline is added later.
 *
 * <h2>On-disk layout</h2>
 * <pre>
 *   ${app.drone-dir}/{datasetId}/original/{filename}.tif
 *   ${app.drone-dir}/{datasetId}/tiles/{z}/{x}/{y}.png
 * </pre>
 * Tiles are addressed by the standard XYZ scheme (Web Mercator, y increasing
 * southwards), which is what MapLibre's {@code raster} source expects with no
 * translation.
 */
@Service
public class DroneRasterService {

    private static final Logger log = LoggerFactory.getLogger(DroneRasterService.class);

    private static final int TILE = 256;
    private static final double MERCATOR_EXTENT = 20037508.342789244;
    /** Web Mercator ground resolution at zoom 0, metres per pixel at the equator. */
    private static final double Z0_RESOLUTION = 2 * MERCATOR_EXTENT / TILE;
    private static final int MAX_ZOOM_CEILING = 22;
    /** Stop deepening the pyramid past this many tiles on its base level. */
    private static final int MAX_BASE_TILES = 60_000;
    /** The largest region read out of the source in one go, per tile. */
    private static final int MAX_READ_SPAN = 2048;

    private final DroneService drone;
    private final JdbcTemplate jdbc;

    /**
     * Publishing is single-threaded on purpose. A pyramid build is CPU- and
     * IO-bound and the box also serves the map; running two at once would make
     * both slow and neither finish sooner. Datasets queue instead, which is why
     * the UI shows a PROCESSING state rather than a progress bar.
     */
    private final ExecutorService builder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "drone-tile-builder");
        t.setDaemon(true);
        return t;
    });

    public DroneRasterService(DroneService drone, JdbcTemplate jdbc) {
        this.drone = drone;
        this.jdbc = jdbc;
    }

    /* ------------------------------------------------------------------
       Upload
       ------------------------------------------------------------------ */

    /**
     * Validate the upload, extract its metadata and record it against the project.
     *
     * <p>The file is parsed BEFORE its row is written, so a raster with no CRS or
     * an unsupported one is rejected outright rather than sitting in the list as
     * an entry that can never be published.
     */
    int store(int projectId, String type, String datasetName, MultipartFile file, String user) throws IOException {
        drone.project(projectId);   // 404s here rather than on the foreign key
        String kind = normaliseType(type);
        String original = safeFileName(file == null ? null : file.getOriginalFilename());
        if (file == null || file.isEmpty())
            throw new IllegalArgumentException("Choose a GeoTIFF file to upload.");
        String lower = original.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".tif") && !lower.endsWith(".tiff"))
            throw new IllegalArgumentException(
                    "Only GeoTIFF (.tif / .tiff) is accepted. Export the " + kind.toLowerCase(Locale.ROOT)
                  + " as a GeoTIFF with its coordinate system embedded.");

        if (jdbc.queryForObject("SELECT count(*) FROM drone_dataset WHERE project_id = ? AND dataset_type = ?",
                Integer.class, projectId, kind) > 0)
            throw new IllegalArgumentException(
                    "This project already has " + (DroneService.DEM.equals(kind) ? "a DEM" : "an orthomosaic")
                  + ". Delete it first, or create a new project for the re-flight.");

        Path staging = Files.createTempFile(drone.root(), "upload-", ".tif");
        try {
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, staging, StandardCopyOption.REPLACE_EXISTING);
            }

            GeoTiffMeta meta = GeoTiffMeta.read(staging);
            double[] elev = DroneService.DEM.equals(kind) ? sampleElevationRange(staging, meta) : null;
            if (elev != null && elev[0] >= elev[1])
                throw new IllegalArgumentException(
                        "The DEM contains no usable elevation values — every pixel is nodata.");

            double[] b = meta.wgs84Bounds();
            String name = DroneService.blankToNull(datasetName);
            if (name == null) name = original;

            Integer id = jdbc.queryForObject("""
                INSERT INTO drone_dataset
                    (project_id, dataset_name, dataset_type, file_name, file_path, file_size, format,
                     epsg, crs_name, res_x, res_y, raster_width, raster_height,
                     min_x, min_y, max_x, max_y, elevation_min, elevation_max,
                     footprint, status, created_by)
                VALUES (?, ?, ?, ?, '', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ST_MakeEnvelope(?, ?, ?, ?, 4326), ?, ?)
                RETURNING id
                """, Integer.class,
                    projectId, name, kind, original, file.getSize(), meta.pixelSummary(),
                    meta.crs.epsg(), meta.crs.label(), meta.resX(), meta.resY(), meta.width, meta.height,
                    b[0], b[1], b[2], b[3],
                    elev == null ? null : elev[0], elev == null ? null : elev[1],
                    b[0], b[1], b[2], b[3], DroneService.UPLOADED, user);

            Path target = originalFile(id, original);
            Files.createDirectories(target.getParent());
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            jdbc.update("UPDATE drone_dataset SET file_path = ? WHERE id = ?", target.toString(), id);
            return id;
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    /* ------------------------------------------------------------------
       Publish / unpublish / delete
       ------------------------------------------------------------------ */

    /** Queue a pyramid build. Returns immediately; the row's status tracks progress. */
    void publish(int datasetId) {
        Map<String, Object> d = drone.dataset(datasetId);
        if (DroneService.PROCESSING.equals(d.get("status")))
            throw new IllegalArgumentException("This dataset is already being processed.");

        int version = ((Number) d.get("build_version")).intValue() + 1;
        jdbc.update("UPDATE drone_dataset SET status = ?, status_message = NULL, published = false, "
                + "build_version = ?, updated_at = now() WHERE id = ?",
                DroneService.PROCESSING, version, datasetId);

        builder.submit(() -> {
            try {
                int[] zooms = buildPyramid(datasetId, d);
                jdbc.update("UPDATE drone_dataset SET status = ?, published = true, min_zoom = ?, max_zoom = ?, "
                        + "status_message = NULL, updated_at = now() WHERE id = ?",
                        DroneService.PUBLISHED, zooms[0], zooms[1], datasetId);
                log.info("Drone dataset {} published: zoom {}..{}", datasetId, zooms[0], zooms[1]);
            } catch (Throwable e) {
                log.error("Drone dataset {} tile build failed", datasetId, e);
                jdbc.update("UPDATE drone_dataset SET status = ?, published = false, status_message = ?, "
                        + "updated_at = now() WHERE id = ?",
                        DroneService.FAILED, ApiErrors.safe("drone tile build " + datasetId, e), datasetId);
            }
        });
    }

    void unpublish(int datasetId) {
        drone.dataset(datasetId);
        jdbc.update("UPDATE drone_dataset SET published = false, status = ?, updated_at = now() WHERE id = ?",
                DroneService.UPLOADED, datasetId);
    }

    void delete(int datasetId) throws IOException {
        drone.dataset(datasetId);
        jdbc.update("DELETE FROM drone_dataset WHERE id = ?", datasetId);
        deleteTree(datasetDir(datasetId));
    }

    /* ------------------------------------------------------------------
       Tile serving
       ------------------------------------------------------------------ */

    /** The PNG for one tile, or {@code null} when nothing was rendered there. */
    Path tileFile(int datasetId, int z, int x, int y) {
        Path p = datasetDir(datasetId).resolve("tiles")
                .resolve(String.valueOf(z)).resolve(String.valueOf(x)).resolve(y + ".png");
        return Files.isRegularFile(p) ? p : null;
    }

    /**
     * The DEM's elevation at a WGS84 point, or {@code null} outside the raster or
     * on nodata.
     *
     * <p>Read from the ORIGINAL GeoTIFF, not from the tiles: the tiles are a colour
     * ramp and have thrown the real numbers away. A one-pixel region read is cheap
     * enough to do per click.
     */
    Double elevationAt(int datasetId, double lon, double lat) throws IOException {
        Map<String, Object> d = drone.dataset(datasetId);
        if (!DroneService.DEM.equals(d.get("dataset_type")))
            throw new IllegalArgumentException("Elevation can only be sampled from a DEM.");

        Path file = drone.filePath(datasetId);
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("The DEM file is missing from storage.");

        GeoTiffMeta meta = GeoTiffMeta.read(file);
        double[] model = meta.crs.fromWgs84(lon, lat);
        double[] px = meta.modelToPixel(model[0], model[1]);
        int col = (int) Math.round(px[0]), row = (int) Math.round(px[1]);
        if (col < 0 || row < 0 || col >= meta.width || row >= meta.height) return null;

        try (Reading r = Reading.open(file)) {
            Raster raster = r.readRaster(col, row, 1, 1, 1);
            double v = raster.getSampleDouble(0, 0, 0);
            return isNoData(v, meta) ? null : v;
        }
    }

    /* ------------------------------------------------------------------
       Pyramid build
       ------------------------------------------------------------------ */

    /** @return {@code {minZoom, maxZoom}} actually written. */
    private int[] buildPyramid(int datasetId, Map<String, Object> dataset) throws IOException {
        Path file = drone.filePath(datasetId);
        if (!Files.isRegularFile(file))
            throw new IllegalArgumentException("The uploaded file is missing from storage.");

        boolean dem = DroneService.DEM.equals(dataset.get("dataset_type"));
        GeoTiffMeta meta = GeoTiffMeta.read(file);
        double[] bounds = meta.wgs84Bounds();

        double elevMin = 0, elevMax = 1;
        if (dem) {
            Number lo = (Number) dataset.get("elevation_min"), hi = (Number) dataset.get("elevation_max");
            double[] range = (lo == null || hi == null)
                    ? sampleElevationRange(file, meta)
                    : new double[]{lo.doubleValue(), hi.doubleValue()};
            elevMin = range[0];
            elevMax = range[1] > range[0] ? range[1] : range[0] + 1;
        }

        int maxZoom = nativeZoom(meta, bounds);
        int minZoom = overviewZoom(bounds);

        Path tiles = datasetDir(datasetId).resolve("tiles");
        deleteTree(tiles);
        Files.createDirectories(tiles);

        try (Reading reading = Reading.open(file)) {
            int[] range = tileRange(bounds, maxZoom);
            for (int x = range[0]; x <= range[2]; x++) {
                for (int y = range[1]; y <= range[3]; y++) {
                    BufferedImage img = renderTile(reading, meta, dem, elevMin, elevMax, maxZoom, x, y);
                    if (img != null) writeTile(tiles, maxZoom, x, y, img);
                }
            }
        }

        // Overviews are averaged down from the level below rather than re-read from
        // the source: the source is only touched once, and every level stays
        // consistent with the one under it.
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
    private BufferedImage renderTile(Reading reading, GeoTiffMeta meta, boolean dem,
                                     double elevMin, double elevMax,
                                     int z, int tx, int ty) throws IOException {
        double span = 2 * MERCATOR_EXTENT / (1 << z);
        double west = -MERCATOR_EXTENT + tx * span;
        double north = MERCATOR_EXTENT - ty * span;
        double step = span / TILE;

        // Source pixels this tile needs. Sampled on a coarse grid over the tile
        // rather than only at its corners, because the source CRS and Web Mercator
        // do not agree on straight lines.
        double minCol = Double.MAX_VALUE, minRow = Double.MAX_VALUE;
        double maxCol = -Double.MAX_VALUE, maxRow = -Double.MAX_VALUE;
        for (int i = 0; i <= 8; i++) {
            for (int j = 0; j <= 8; j++) {
                double[] p = sourcePixel(meta, west + i * span / 8.0, north - j * span / 8.0);
                minCol = Math.min(minCol, p[0]); maxCol = Math.max(maxCol, p[0]);
                minRow = Math.min(minRow, p[1]); maxRow = Math.max(maxRow, p[1]);
            }
        }
        int x0 = (int) Math.floor(minCol) - 1, y0 = (int) Math.floor(minRow) - 1;
        int x1 = (int) Math.ceil(maxCol) + 1, y1 = (int) Math.ceil(maxRow) + 1;
        x0 = Math.max(0, x0); y0 = Math.max(0, y0);
        x1 = Math.min(meta.width - 1, x1); y1 = Math.min(meta.height - 1, y1);
        if (x1 < x0 || y1 < y0) return null;

        int w = x1 - x0 + 1, h = y1 - y0 + 1;
        // Cap the read: an overview tile can span the whole raster, and decoding
        // 100 megapixels to fill 65 536 output pixels would be pure waste.
        int sub = Math.max(1, (int) Math.ceil(Math.max(w, h) / (double) MAX_READ_SPAN));

        BufferedImage out = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[TILE];
        boolean any = false;

        if (dem) {
            Raster src = reading.readRaster(x0, y0, w, h, sub);
            for (int py = 0; py < TILE; py++) {
                double my = north - (py + 0.5) * step;
                for (int px = 0; px < TILE; px++) {
                    double[] p = sourcePixel(meta, west + (px + 0.5) * step, my);
                    int sx = (int) ((p[0] - x0) / sub), sy = (int) ((p[1] - y0) / sub);
                    if (sx < 0 || sy < 0 || sx >= src.getWidth() || sy >= src.getHeight()) { row[px] = 0; continue; }
                    double v = src.getSampleDouble(sx, sy, 0);
                    if (isNoData(v, meta)) { row[px] = 0; continue; }
                    row[px] = elevationColour((v - elevMin) / (elevMax - elevMin));
                    any = true;
                }
                out.setRGB(0, py, TILE, 1, row, 0, TILE);
            }
        } else {
            BufferedImage src = reading.readImage(x0, y0, w, h, sub);
            int sw = src.getWidth(), sh = src.getHeight();
            boolean alpha = src.getColorModel().hasAlpha();
            for (int py = 0; py < TILE; py++) {
                double my = north - (py + 0.5) * step;
                for (int px = 0; px < TILE; px++) {
                    double[] p = sourcePixel(meta, west + (px + 0.5) * step, my);
                    int sx = (int) ((p[0] - x0) / sub), sy = (int) ((p[1] - y0) / sub);
                    if (sx < 0 || sy < 0 || sx >= sw || sy >= sh) { row[px] = 0; continue; }
                    int argb = src.getRGB(sx, sy);
                    // A raster with no alpha band has no way to say "outside the
                    // flight" other than the black collar the exporter leaves, so
                    // pure black is treated as nothing to draw.
                    if (!alpha && (argb & 0xFFFFFF) == 0) { row[px] = 0; continue; }
                    if (alpha && (argb >>> 24) == 0) { row[px] = 0; continue; }
                    row[px] = argb | 0xFF000000;
                    any = true;
                }
                out.setRGB(0, py, TILE, 1, row, 0, TILE);
            }
        }
        return any ? out : null;
    }

    /** Web Mercator metres to fractional source pixel. */
    private static double[] sourcePixel(GeoTiffMeta meta, double mx, double my) {
        double lon = mx / MERCATOR_EXTENT * 180;
        double lat = Math.toDegrees(2 * Math.atan(Math.exp(Math.toRadians(my / MERCATOR_EXTENT * 180))) - Math.PI / 2);
        double[] model = meta.crs.fromWgs84(lon, lat);
        return meta.modelToPixel(model[0], model[1]);
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
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
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

    /* ------------------------------------------------------------------
       Zoom levels and tile addressing
       ------------------------------------------------------------------ */

    /**
     * The deepest zoom worth writing: where a tile pixel is about the size of a
     * source pixel. Going deeper only stores upsampled copies of data the file
     * does not contain.
     */
    private int nativeZoom(GeoTiffMeta meta, double[] bounds) {
        double midLat = Math.toRadians((bounds[1] + bounds[3]) / 2);
        double metresPerPixel = Math.max(1e-4, meta.resolutionMetres());
        int z = (int) Math.round(Math.log(Z0_RESOLUTION * Math.cos(midLat) / metresPerPixel) / Math.log(2));
        z = Math.max(1, Math.min(MAX_ZOOM_CEILING, z));
        // A very large survey at full native resolution can run to hundreds of
        // thousands of tiles. Back off a level at a time rather than refusing the
        // upload: one zoom coarser is still far sharper than any basemap.
        while (z > 1) {
            int[] r = tileRange(bounds, z);
            long count = (long) (r[2] - r[0] + 1) * (r[3] - r[1] + 1);
            if (count <= MAX_BASE_TILES) break;
            z--;
        }
        return z;
    }

    /** The shallowest zoom at which the whole footprint still fits in a couple of tiles. */
    private int overviewZoom(double[] bounds) {
        for (int z = 0; z <= MAX_ZOOM_CEILING; z++) {
            int[] r = tileRange(bounds, z);
            if ((r[2] - r[0]) >= 1 || (r[3] - r[1]) >= 1) return Math.max(0, z - 1);
        }
        return 0;
    }

    /** {@code {minX, minY, maxX, maxY}} XYZ tile indices covering a WGS84 bbox. */
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

    /* ------------------------------------------------------------------
       Pixel helpers
       ------------------------------------------------------------------ */

    /**
     * A hypsometric ramp, green through yellow to red-brown. Deliberately the only
     * DEM rendering offered: reading relative height off a map is what a colour
     * ramp is for, and anything beyond it is terrain analysis.
     */
    private static int elevationColour(double t) {
        double v = Math.max(0, Math.min(1, t));
        int[][] stops = {{26, 152, 80}, {166, 217, 106}, {255, 255, 191}, {253, 174, 97}, {215, 48, 39}};
        double scaled = v * (stops.length - 1);
        int i = Math.min(stops.length - 2, (int) scaled);
        double f = scaled - i;
        int r = (int) Math.round(stops[i][0] + f * (stops[i + 1][0] - stops[i][0]));
        int g = (int) Math.round(stops[i][1] + f * (stops[i + 1][1] - stops[i][1]));
        int b = (int) Math.round(stops[i][2] + f * (stops[i + 1][2] - stops[i][2]));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static boolean isNoData(double v, GeoTiffMeta meta) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return true;
        // Exporters that declare no nodata tag still write one of these sentinels.
        if (v <= -1e30 || v >= 1e30) return true;
        if (!Double.isNaN(meta.noData) && Math.abs(v - meta.noData) < 1e-6) return true;
        return false;
    }

    /**
     * Min/max elevation over a subsampled read of the whole DEM.
     *
     * <p>Subsampled rather than exhaustive: a full pass over a 100-megapixel float
     * raster costs seconds and several hundred megabytes, and the answer moves by
     * centimetres. The values drive a colour ramp and a summary line, neither of
     * which is a survey deliverable.
     */
    private double[] sampleElevationRange(Path file, GeoTiffMeta meta) throws IOException {
        int step = Math.max(1, (int) Math.ceil(Math.max(meta.width, meta.height) / 1200.0));
        try (Reading r = Reading.open(file)) {
            Raster raster = r.readRaster(0, 0, meta.width, meta.height, step);
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (int y = 0; y < raster.getHeight(); y++) {
                for (int x = 0; x < raster.getWidth(); x++) {
                    double v = raster.getSampleDouble(x, y, 0);
                    if (isNoData(v, meta)) continue;
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
            }
            return min > max ? new double[]{0, 0} : new double[]{min, max};
        }
    }

    /* ------------------------------------------------------------------
       Source reading
       ------------------------------------------------------------------ */

    /**
     * One open TIFF reader, reused for every tile of a build.
     *
     * <p>Region reads matter more than they look: {@code ImageReadParam}'s source
     * region lets the JDK decode only the strips or tiles a region touches, so a
     * pyramid over a multi-gigabyte GeoTIFF never holds more than one tile's worth
     * of pixels in memory.
     */
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
                throw new IllegalArgumentException("No reader can decode this TIFF. It may use an "
                        + "unsupported compression — re-export it with LZW or DEFLATE compression.");
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

        /**
         * Raw sample values, for a DEM's float heights.
         *
         * <p>Via {@code read().getRaster()} and NOT {@code readRaster()}: the JDK's
         * TIFF plugin returns false from {@code canReadRaster()} and throws
         * {@link UnsupportedOperationException} from the latter. Taking the raster
         * off the decoded image is equivalent here — a raster carries sample values
         * with no colour model applied, so a float32 elevation comes back as the
         * metre value that is in the file, not a display-scaled one.
         */
        Raster readRaster(int x, int y, int w, int h, int sub) throws IOException {
            return reader.read(0, param(x, y, w, h, sub)).getRaster();
        }

        /** Fully interpreted pixels — photometric conversion applied, as an ortho needs. */
        BufferedImage readImage(int x, int y, int w, int h, int sub) throws IOException {
            return reader.read(0, param(x, y, w, h, sub));
        }

        @Override
        public void close() throws IOException {
            reader.dispose();
            stream.close();
        }
    }

    /* ------------------------------------------------------------------
       Paths
       ------------------------------------------------------------------ */

    private Path datasetDir(int datasetId) {
        return drone.root().resolve(String.valueOf(datasetId));
    }

    private Path originalFile(int datasetId, String fileName) {
        return datasetDir(datasetId).resolve("original").resolve(fileName);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static String normaliseType(String type) {
        String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (DroneService.ORTHO.equals(t) || DroneService.DEM.equals(t)) return t;
        throw new IllegalArgumentException("Dataset Type must be ORTHOMOSAIC or DEM.");
    }

    /**
     * Strip every path element from the client-supplied name.
     *
     * <p>The value is concatenated into a storage path, so a name like
     * {@code ../../etc/passwd} must not be able to escape the dataset's folder.
     */
    private static String safeFileName(String raw) {
        String s = raw == null ? "" : raw.trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) s = s.substring(slash + 1);
        s = s.replaceAll("[^A-Za-z0-9._-]", "_");
        while (s.startsWith(".")) s = s.substring(1);
        if (s.isEmpty()) throw new IllegalArgumentException("The uploaded file has no usable name.");
        return s.length() > 120 ? s.substring(s.length() - 120) : s;
    }
}
