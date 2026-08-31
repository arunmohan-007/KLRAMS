package com.fist.rmms_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Contour lines traced from a published DEM.
 *
 * <h2>Why lines in PostGIS and not another raster pyramid</h2>
 * The orthomosaic and the DEM are pictures, so they are tiled as PNGs. A contour is
 * not a picture — it is a line with an elevation, something to label, snap a query
 * to and read a number off. Stored as geometry it becomes an ordinary KLRAMS vector
 * layer: PostGIS table, MVT endpoint, the same shape as every other line layer in
 * the system. Drawing them into the DEM's own pixels would make them unlabellable
 * and unqueryable, and would need the whole pyramid rebuilt to change the interval.
 *
 * <h2>How</h2>
 * Marching squares over the elevation grid, one pass per contour level. Each grid
 * cell contributes at most two segments; the segments are then chained end-to-end
 * into polylines, because ten thousand two-point lines is a slow, unlabellable
 * layer while a few hundred long ones is a fast one.
 */
@Service
public class DroneContourService {

    private static final Logger log = LoggerFactory.getLogger(DroneContourService.class);

    /** Contour every {@code INDEX_EVERY}th level is drawn heavier and labelled. */
    static final int INDEX_EVERY = 5;
    /** Refuse to trace more levels than this — a 0.01 m interval on a hill is not a map. */
    private static final int MAX_LEVELS = 400;
    /** Longest grid edge used for tracing; larger DEMs are thinned to it. */
    private static final int MAX_GRID = 2000;
    /** Endpoint matching tolerance when chaining segments, in grid cells. */
    private static final double JOIN_EPS = 1e-6;
    /** Shorter than this (in grid cells) is numerical noise, not a contour. */
    private static final double MIN_LINE_CELLS = 1e-4;

    static final String PENDING = "PROCESSING";
    static final String READY = "READY";
    static final String FAILED = "FAILED";

    private final DroneService drone;
    private final DroneRasterService rasters;
    private final JdbcTemplate jdbc;

    /** Single-threaded for the same reason the tile builder is: it shares the box. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "drone-contour");
        t.setDaemon(true);
        return t;
    });

    public DroneContourService(DroneService drone, DroneRasterService rasters, JdbcTemplate jdbc) {
        this.drone = drone;
        this.rasters = rasters;
        this.jdbc = jdbc;
    }

    /* ------------------------------------------------------------------
       Generate
       ------------------------------------------------------------------ */

    /**
     * Queue a contour trace over the dataset's DEM. Returns at once; progress shows
     * in the dataset's {@code contour_status}.
     */
    void generate(int datasetId, double interval) {
        Map<String, Object> d = drone.dataset(datasetId);
        if (!DroneService.DEM.equals(d.get("dataset_type")))
            throw new IllegalArgumentException("Contours are traced from a DEM, not an orthomosaic.");
        if (!(interval > 0))
            throw new IllegalArgumentException("Choose a contour interval greater than zero.");

        Number lo = (Number) d.get("elevation_min"), hi = (Number) d.get("elevation_max");
        if (lo != null && hi != null) {
            double levels = (hi.doubleValue() - lo.doubleValue()) / interval;
            if (levels > MAX_LEVELS)
                throw new IllegalArgumentException(String.format(
                        "An interval of %s m over a %.1f m range would draw %.0f contours. "
                      + "Use a larger interval — %.1f m or more keeps it under %d.",
                        trim(interval), hi.doubleValue() - lo.doubleValue(), levels,
                        (hi.doubleValue() - lo.doubleValue()) / MAX_LEVELS, MAX_LEVELS));
        }

        jdbc.update("UPDATE drone_dataset SET contour_status = ?, contour_interval = ?, "
                  + "contour_message = NULL, updated_at = now() WHERE id = ?",
                PENDING, interval, datasetId);

        worker.submit(() -> {
            try {
                int n = trace(datasetId, interval);
                jdbc.update("UPDATE drone_dataset SET contour_status = ?, contour_count = ?, "
                          + "updated_at = now() WHERE id = ?", READY, n, datasetId);
                log.info("Drone dataset {}: traced {} contours at {} m", datasetId, n, trim(interval));
            } catch (Exception e) {
                log.error("Contour trace failed for drone dataset " + datasetId, e);
                jdbc.update("UPDATE drone_dataset SET contour_status = ?, contour_message = ?, "
                          + "contour_count = 0, updated_at = now() WHERE id = ?",
                        FAILED, String.valueOf(e.getMessage()), datasetId);
            }
        });
    }

    /* ------------------------------------------------------------------
       Import
       ------------------------------------------------------------------ */

    /** Field names that commonly hold a contour's height, tried in this order. */
    private static final List<String> ELEVATION_FIELDS = List.of(
            "elevation", "elev", "contour", "level", "height", "rl", "z", "value", "alt", "altitude");

    /**
     * Store contours that came from a survey rather than from a DEM.
     *
     * <p>Arrives as GeoJSON because that is how every other importer in KLRAMS
     * receives geometry: the browser unzips the shapefile with shpjs, or reads the
     * KML with {@code js/kml-reader.js}, and posts the result. Keeping that division
     * means no shapefile or KML parser on the server and one code path here.
     *
     * <p>The contour set becomes a dataset of the project, alongside its orthomosaic
     * and DEM, so it inherits the whole list-toggle-info machinery rather than
     * needing a parallel one.
     */
    @SuppressWarnings("unchecked")
    int importFeatures(int projectId, String name, String fileName, String elevationField,
                       List<Map<String, Object>> features, String user) {
        drone.project(projectId);
        if (features == null || features.isEmpty())
            throw new IllegalArgumentException("That file contains no contour lines.");

        String field = elevationField;
        if (DroneService.blankToNull(field) == null) field = detectElevationField(features);
        if (field == null)
            throw new IllegalArgumentException(
                    "No elevation field found. The file needs a numeric attribute holding each "
                  + "contour's height — commonly ELEV, ELEVATION, CONTOUR or LEVEL — and you can "
                  + "name it explicitly if it is called something else.");

        List<Object[]> rows = new ArrayList<>();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        int skipped = 0;

        for (Map<String, Object> f : features) {
            Object propsRaw = f.get("properties");
            Map<String, Object> props = propsRaw instanceof Map ? (Map<String, Object>) propsRaw : Map.of();
            Double elev = number(valueOf(props, field));
            Object geom = f.get("geometry");
            if (elev == null || !(geom instanceof Map)) { skipped++; continue; }

            for (List<double[]> line : linesOf((Map<String, Object>) geom)) {
                if (line.size() < 2) { skipped++; continue; }
                StringBuilder wkt = new StringBuilder("LINESTRING(");
                for (int i = 0; i < line.size(); i++) {
                    if (i > 0) wkt.append(',');
                    wkt.append(fmt(line.get(i)[0])).append(' ').append(fmt(line.get(i)[1]));
                    minX = Math.min(minX, line.get(i)[0]); maxX = Math.max(maxX, line.get(i)[0]);
                    minY = Math.min(minY, line.get(i)[1]); maxY = Math.max(maxY, line.get(i)[1]);
                }
                rows.add(new Object[]{elev, wkt.append(')').toString()});
                minZ = Math.min(minZ, elev);
                maxZ = Math.max(maxZ, elev);
            }
        }
        if (rows.isEmpty())
            throw new IllegalArgumentException(
                    "No usable contour lines found. The file must contain lines with a numeric \""
                  + field + "\" value; " + skipped + " feature(s) had neither.");

        /* The index interval is inferred rather than asked for: an imported set
           already has its interval baked into the values, and spacing every fifth
           DISTINCT height is what a reader expects whatever that spacing is. */
        double interval = commonSpacing(rows);

        jdbc.update("DELETE FROM drone_dataset WHERE project_id = ? AND dataset_type = ?",
                projectId, DroneService.CONTOUR);

        Integer datasetId = jdbc.queryForObject("""
            INSERT INTO drone_dataset
                (project_id, dataset_name, dataset_type, file_name, file_path, file_size, format,
                 epsg, crs_name, min_x, min_y, max_x, max_y, elevation_min, elevation_max,
                 footprint, status, published, contour_status, contour_count, contour_interval, created_by)
            VALUES (?, ?, ?, ?, '', 0, ?, 4326, 'EPSG:4326 — WGS 84', ?, ?, ?, ?, ?, ?,
                    ST_MakeEnvelope(?, ?, ?, ?, 4326), ?, true, ?, ?, ?, ?)
            RETURNING id
            """, Integer.class,
            projectId, name, DroneService.CONTOUR, fileName,
            rows.size() + " contour lines · elevation from \"" + field + "\"",
            minX, minY, maxX, maxY, minZ, maxZ,
            minX, minY, maxX, maxY, DroneService.PUBLISHED,
            READY, rows.size(), interval > 0 ? interval : null, user);

        for (Object[] row : rows) {
            double elev = (Double) row[0];
            boolean index = interval > 0
                    && Math.floorMod(Math.round(elev / interval), (long) INDEX_EVERY) == 0;
            jdbc.update("""
                INSERT INTO drone_contour (dataset_id, elevation, is_index, geom)
                VALUES (?, ?, ?, ST_SetSRID(ST_GeomFromText(?), 4326))
                """, datasetId, elev, index, row[1]);
        }
        log.info("Drone project {}: imported {} contour lines from {} (elevation field \"{}\")",
                projectId, rows.size(), fileName, field);
        return datasetId;
    }

    /** The first field whose name looks like a height AND parses as a number. */
    private static String detectElevationField(List<Map<String, Object>> features) {
        for (Map<String, Object> f : features) {
            Object p = f.get("properties");
            if (!(p instanceof Map<?, ?> props)) continue;
            for (String want : ELEVATION_FIELDS)
                for (Object k : props.keySet())
                    if (String.valueOf(k).trim().equalsIgnoreCase(want)
                            && number(props.get(k)) != null) return String.valueOf(k);
        }
        return null;
    }

    /** Case-insensitive property lookup — shapefile field names arrive shouted. */
    private static Object valueOf(Map<String, Object> props, String field) {
        Object direct = props.get(field);
        if (direct != null) return direct;
        for (Map.Entry<String, Object> e : props.entrySet())
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(field)) return e.getValue();
        return null;
    }

    private static Double number(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return null;
        try {
            return Double.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** LineStrings out of any geometry, flattening Multi and collection types. */
    @SuppressWarnings("unchecked")
    private static List<List<double[]>> linesOf(Map<String, Object> geom) {
        List<List<double[]>> out = new ArrayList<>();
        String type = String.valueOf(geom.get("type"));
        Object coords = geom.get("coordinates");

        switch (type) {
            case "LineString" -> { if (coords instanceof List) out.add(coordList((List<Object>) coords)); }
            case "MultiLineString", "Polygon" -> {
                if (coords instanceof List<?> parts)
                    for (Object part : parts)
                        if (part instanceof List) out.add(coordList((List<Object>) part));
            }
            case "MultiPolygon" -> {
                if (coords instanceof List<?> polys)
                    for (Object poly : polys)
                        if (poly instanceof List<?> rings)
                            for (Object ring : rings)
                                if (ring instanceof List) out.add(coordList((List<Object>) ring));
            }
            case "GeometryCollection" -> {
                Object gs = geom.get("geometries");
                if (gs instanceof List<?> list)
                    for (Object g : list)
                        if (g instanceof Map) out.addAll(linesOf((Map<String, Object>) g));
            }
            default -> { }
        }
        out.removeIf(l -> l.size() < 2);
        return out;
    }

    private static List<double[]> coordList(List<Object> coords) {
        List<double[]> out = new ArrayList<>();
        double lastX = Double.NaN, lastY = Double.NaN;
        for (Object c : coords) {
            if (!(c instanceof List<?> pair) || pair.size() < 2) continue;
            Double x = number(pair.get(0)), y = number(pair.get(1));
            if (x == null || y == null) continue;
            // PostGIS rejects a line whose consecutive points coincide.
            if (x == lastX && y == lastY) continue;
            out.add(new double[]{x, y});
            lastX = x;
            lastY = y;
        }
        return out;
    }

    /**
     * The smallest gap between distinct heights — the set's own contour interval.
     *
     * <p>Taken as the minimum rather than the average because a set missing a few
     * lines (a flat area with no contour through it) would otherwise report an
     * interval larger than the one it was drawn at.
     */
    private static double commonSpacing(List<Object[]> rows) {
        java.util.TreeSet<Double> levels = new java.util.TreeSet<>();
        for (Object[] r : rows) levels.add((Double) r[0]);
        if (levels.size() < 2) return 0;

        double smallest = Double.MAX_VALUE;
        Double prev = null;
        for (Double v : levels) {
            if (prev != null) smallest = Math.min(smallest, v - prev);
            prev = v;
        }
        return smallest > 0 && smallest < Double.MAX_VALUE ? smallest : 0;
    }

    /** Remove a dataset's contours and forget the interval. */
    void clear(int datasetId) {
        jdbc.update("DELETE FROM drone_contour WHERE dataset_id = ?", datasetId);
        jdbc.update("UPDATE drone_dataset SET contour_status = NULL, contour_count = 0, "
                  + "contour_interval = NULL, contour_message = NULL WHERE id = ?", datasetId);
    }

    private int trace(int datasetId, double interval) throws IOException {
        Path file = drone.filePath(datasetId);
        GeoTiffMeta meta = GeoTiffMeta.read(file);

        int step = Math.max(1, (int) Math.ceil(Math.max(meta.width, meta.height) / (double) MAX_GRID));
        double[][] grid = rasters.readGrid(file, meta, step);

        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double[] row : grid)
            for (double v : row) {
                if (Double.isNaN(v)) continue;
                if (v < lo) lo = v;
                if (v > hi) hi = v;
            }
        if (lo > hi) throw new IllegalStateException("The DEM has no usable elevation values.");

        jdbc.update("DELETE FROM drone_contour WHERE dataset_id = ?", datasetId);

        int first = (int) Math.ceil(lo / interval);
        int last = (int) Math.floor(hi / interval);
        int written = 0;

        for (int k = first; k <= last; k++) {
            double level = k * interval;
            List<List<double[]>> lines = linesAt(grid, level);
            boolean index = Math.floorMod(k, INDEX_EVERY) == 0;
            for (List<double[]> line : lines) {
                String wkt = toWkt(line, grid, meta, step);
                if (wkt == null) continue;
                jdbc.update("""
                    INSERT INTO drone_contour (dataset_id, elevation, is_index, geom)
                    VALUES (?, ?, ?, ST_SetSRID(ST_GeomFromText(?), 4326))
                    """, datasetId, level, index, wkt);
                written++;
            }
        }
        return written;
    }

    /* ------------------------------------------------------------------
       Marching squares
       ------------------------------------------------------------------ */

    /**
     * Chained contour polylines for one level, in fractional grid coordinates.
     *
     * <p>Pieces of no length are dropped. Where a contour grazes a grid node, the two
     * crossings computed either side of it land a billionth of a cell apart and chain
     * into a "line" that goes nowhere — real enough to count, far too small to draw.
     */
    static List<List<double[]>> linesAt(double[][] grid, double level) {
        List<List<double[]>> lines = chain(segmentsAt(grid, nudge(level)));
        lines.removeIf(line -> length(line) < MIN_LINE_CELLS);
        return lines;
    }

    /** Total length of a polyline, in grid cells. */
    private static double length(List<double[]> line) {
        double total = 0;
        for (int i = 1; i < line.size(); i++)
            total += Math.hypot(line.get(i)[0] - line.get(i - 1)[0],
                                line.get(i)[1] - line.get(i - 1)[1]);
        return total;
    }

    /**
     * Move a level infinitesimally off any value a sample might hold exactly.
     *
     * <p>When a contour passes exactly through a grid node — which happens constantly
     * on synthetic and on gently sloping ground, wherever a sample lands on a round
     * number — the crossing is computed AT that node, several cells produce segments
     * ending on the same point, and the chain has no way to tell which pair continues
     * which. The ring comes apart into arcs. Displacing the level by a part in a
     * billion removes every one of those coincidences, and moves the drawn line by
     * far less than the DEM's own vertical precision.
     */
    private static double nudge(double level) {
        return level + Math.max(Math.abs(level), 1.0) * 1e-9;
    }

    /**
     * Segments of one contour level, in fractional grid coordinates.
     *
     * <p>Each cell is classified by which of its four corners sit above the level;
     * that gives sixteen cases, of which fourteen produce one or two segments. The
     * two ambiguous saddles (corners 0/2 above, or 1/3) are resolved by the cell's
     * average, which is the standard tie-break and keeps adjacent cells agreeing so
     * the chains join up.
     */
    private static List<double[]> segmentsAt(double[][] g, double level) {
        List<double[]> out = new ArrayList<>();
        int h = g.length, w = g[0].length;

        for (int r = 0; r < h - 1; r++) {
            for (int c = 0; c < w - 1; c++) {
                double tl = g[r][c], tr = g[r][c + 1], br = g[r + 1][c + 1], bl = g[r + 1][c];
                if (Double.isNaN(tl) || Double.isNaN(tr) || Double.isNaN(br) || Double.isNaN(bl)) continue;

                int code = (tl > level ? 8 : 0) | (tr > level ? 4 : 0)
                         | (br > level ? 2 : 0) | (bl > level ? 1 : 0);
                if (code == 0 || code == 15) continue;

                // Crossing points on the four edges, in cell-local coordinates.
                double[] top    = {c + frac(tl, tr, level), r};
                double[] right  = {c + 1, r + frac(tr, br, level)};
                double[] bottom = {c + frac(bl, br, level), r + 1};
                double[] left   = {c, r + frac(tl, bl, level)};

                switch (code) {
                    case 1, 14 -> add(out, left, bottom);
                    case 2, 13 -> add(out, bottom, right);
                    case 3, 12 -> add(out, left, right);
                    case 4, 11 -> add(out, top, right);
                    case 6, 9  -> add(out, top, bottom);
                    case 7, 8  -> add(out, left, top);
                    case 5 -> {
                        if ((tl + tr + br + bl) / 4 > level) { add(out, left, top); add(out, bottom, right); }
                        else { add(out, left, bottom); add(out, top, right); }
                    }
                    case 10 -> {
                        if ((tl + tr + br + bl) / 4 > level) { add(out, top, right); add(out, left, bottom); }
                        else { add(out, left, top); add(out, bottom, right); }
                    }
                    default -> { }
                }
            }
        }
        return out;
    }

    private static void add(List<double[]> out, double[] a, double[] b) {
        // A zero-length segment carries no direction, so it would break a chain
        // rather than extend it.
        if (a[0] == b[0] && a[1] == b[1]) return;
        out.add(new double[]{a[0], a[1], b[0], b[1]});
    }

    /** Where between two corner values the level falls, guarding the equal case. */
    private static double frac(double a, double b, double level) {
        double d = b - a;
        if (d == 0) return 0.5;
        double t = (level - a) / d;
        return t < 0 ? 0 : (t > 1 ? 1 : t);
    }

    /**
     * Chain loose segments into polylines.
     *
     * <p>Marching squares emits each segment independently, so a ridge line arrives
     * as a few thousand disconnected pieces. Joining them on shared endpoints turns
     * that into a handful of real lines — smaller to store, far quicker to draw, and
     * the only form on which a contour label can be placed sensibly.
     */
    private static List<List<double[]>> chain(List<double[]> segs) {
        Map<Long, List<Integer>> byPoint = new HashMap<>();
        for (int i = 0; i < segs.size(); i++) {
            double[] s = segs.get(i);
            byPoint.computeIfAbsent(key(s[0], s[1]), k -> new ArrayList<>()).add(i);
            byPoint.computeIfAbsent(key(s[2], s[3]), k -> new ArrayList<>()).add(i);
        }

        boolean[] used = new boolean[segs.size()];
        List<List<double[]>> lines = new ArrayList<>();

        for (int i = 0; i < segs.size(); i++) {
            if (used[i]) continue;
            used[i] = true;
            double[] s = segs.get(i);
            List<double[]> line = new ArrayList<>();
            line.add(new double[]{s[0], s[1]});
            line.add(new double[]{s[2], s[3]});

            // Extend from the tail, then from the head, so a chain found from its
            // middle still comes out as one line rather than two.
            extend(line, segs, used, byPoint, true);
            extend(line, segs, used, byPoint, false);
            if (line.size() >= 2) lines.add(line);
        }
        return lines;
    }

    private static void extend(List<double[]> line, List<double[]> segs, boolean[] used,
                               Map<Long, List<Integer>> byPoint, boolean tail) {
        while (true) {
            double[] end = tail ? line.get(line.size() - 1) : line.get(0);
            List<Integer> candidates = byPoint.get(key(end[0], end[1]));
            if (candidates == null) return;

            int next = -1;
            double[] point = null;
            for (int idx : candidates) {
                if (used[idx]) continue;
                double[] s = segs.get(idx);
                if (near(s[0], s[1], end)) { next = idx; point = new double[]{s[2], s[3]}; break; }
                if (near(s[2], s[3], end)) { next = idx; point = new double[]{s[0], s[1]}; break; }
            }
            if (next < 0) return;
            used[next] = true;
            if (tail) line.add(point); else line.add(0, point);
        }
    }

    private static boolean near(double x, double y, double[] p) {
        return Math.abs(x - p[0]) < JOIN_EPS && Math.abs(y - p[1]) < JOIN_EPS;
    }

    /** Quantised endpoint key — floats that should be equal are, to 1e-6 of a cell. */
    private static long key(double x, double y) {
        long qx = Math.round(x * 1_000_000L);
        long qy = Math.round(y * 1_000_000L);
        return qx * 31_000_003L + qy;
    }

    /* ------------------------------------------------------------------
       Output
       ------------------------------------------------------------------ */

    /** Grid coordinates to a WGS84 LINESTRING, or null if too short to be a line. */
    private static String toWkt(List<double[]> line, double[][] grid, GeoTiffMeta meta, int step) {
        StringBuilder sb = new StringBuilder("LINESTRING(");
        int n = 0;
        double lastLon = Double.NaN, lastLat = Double.NaN;
        for (double[] p : line) {
            double[] model = meta.pixelToModel(p[0] * step, p[1] * step);
            double[] ll = meta.crs.toWgs84(model[0], model[1]);
            // Drop repeated vertices; PostGIS rejects a line whose points coincide.
            if (n > 0 && Math.abs(ll[0] - lastLon) < 1e-12 && Math.abs(ll[1] - lastLat) < 1e-12) continue;
            if (n > 0) sb.append(',');
            sb.append(fmt(ll[0])).append(' ').append(fmt(ll[1]));
            lastLon = ll[0];
            lastLat = ll[1];
            n++;
        }
        return n >= 2 ? sb.append(')').toString() : null;
    }

    private static String fmt(double v) {
        // ~1 cm at this latitude; more digits only inflate the geometry.
        return String.format(java.util.Locale.ROOT, "%.7f", v);
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v)
                                 : String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
