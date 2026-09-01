package com.fist.rmms_backend;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Drone module's API: projects, dataset upload, publishing, and the raster
 * tiles the viewer draws.
 *
 * <pre>
 *   GET    /api/drone/summary                              dashboard counters
 *   GET    /api/drone/projects                             project list (with dataset status)
 *   POST   /api/drone/projects                             create
 *   PUT    /api/drone/projects/{id}                        edit
 *   DELETE /api/drone/projects/{id}                        delete (cascades to datasets)
 *   GET    /api/drone/datasets?project_id=                 dataset list + full metadata
 *   POST   /api/drone/datasets                             upload a GeoTIFF (multipart)
 *   POST   /api/drone/datasets/{id}/publish                queue the tile build
 *   POST   /api/drone/datasets/{id}/unpublish              take it off the map
 *   DELETE /api/drone/datasets/{id}                        delete row + files
 *   GET    /api/drone/published                            what the viewer may draw
 *   GET    /api/drone/datasets/{id}/tiles/{z}/{x}/{y}.png  one raster tile
 *   GET    /api/drone/datasets/{id}/elevation?lng=&lat=    DEM height at a point
 * </pre>
 *
 * <p>Writes land under the blanket {@code POST/PUT/DELETE /api/**} ADMIN rule in
 * {@link SecurityConfig}, so view-only accounts can browse and view drone data but
 * cannot upload, publish or delete it. No new security rule is needed.
 */
@RestController
@RequestMapping("/api/drone")
public class DroneController {

    /** Deepest contour tile served; past this the lines are drawn from the z22 tile. */
    private static final int CONTOUR_MAX_ZOOM = 22;

    private final DroneService drone;
    private final DroneRasterService rasters;
    private final DroneContourService contours;
    private final DroneContourTileService contourTiles;

    public DroneController(DroneService drone, DroneRasterService rasters,
                           DroneContourService contours, DroneContourTileService contourTiles) {
        this.drone = drone;
        this.rasters = rasters;
        this.contours = contours;
        this.contourTiles = contourTiles;
    }

    /* ---------------- dashboard ---------------- */

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return drone.summary();
    }

    /* ---------------- projects ---------------- */

    @GetMapping("/projects")
    public List<Map<String, Object>> projects() {
        return drone.listProjects();
    }

    @PostMapping("/projects")
    public Map<String, Object> createProject(@RequestBody Map<String, String> body, Authentication auth) {
        try {
            int id = drone.createProject(body.get("project_code"), body.get("project_name"),
                    body.get("survey_date"), body.get("road_section"), body.get("location"),
                    body.get("pwd_section"), body.get("description"),
                    auth == null ? null : auth.getName());
            return ok("id", id);
        } catch (Exception e) {
            return fail("drone create project", e);
        }
    }

    @PutMapping("/projects/{id}")
    public Map<String, Object> updateProject(@PathVariable int id, @RequestBody Map<String, String> body) {
        try {
            drone.updateProject(id, body.get("project_code"), body.get("project_name"),
                    body.get("survey_date"), body.get("road_section"), body.get("location"),
                    body.get("pwd_section"), body.get("description"));
            return ok("id", id);
        } catch (Exception e) {
            return fail("drone update project", e);
        }
    }

    /**
     * Deleting a project deletes its datasets with it — the foreign key cascades
     * the rows, and this removes their files, which the database cannot.
     */
    @DeleteMapping("/projects/{id}")
    public Map<String, Object> deleteProject(@PathVariable int id) {
        try {
            for (Map<String, Object> d : drone.listDatasets(id))
                rasters.delete(((Number) d.get("id")).intValue());
            drone.deleteProject(id);
            return ok("deleted", id);
        } catch (Exception e) {
            return fail("drone delete project", e);
        }
    }

    /* ---------------- datasets ---------------- */

    @GetMapping("/datasets")
    public List<Map<String, Object>> datasets(@RequestParam(value = "project_id", required = false) Integer projectId) {
        return drone.listDatasets(projectId);
    }

    @GetMapping("/datasets/{id}")
    public Map<String, Object> dataset(@PathVariable int id) {
        return drone.dataset(id);
    }

    /**
     * Upload one orthomosaic or DEM. The response carries the extracted metadata
     * straight back, so the upload screen can show CRS, extent and resolution
     * without a second request — and so a georeferencing problem is visible at the
     * moment of upload rather than at publish.
     */
    @PostMapping("/datasets")
    public Map<String, Object> upload(@RequestParam("project_id") int projectId,
                                      @RequestParam("dataset_type") String type,
                                      @RequestParam(value = "dataset_name", required = false) String name,
                                      @RequestParam(value = "geoid_model", required = false) String geoid,
                                      @RequestParam("file") MultipartFile file,
                                      Authentication auth) {
        try {
            int id = rasters.store(projectId, type, name, geoid, file,
                    auth == null ? null : auth.getName());
            Map<String, Object> res = ok("id", id);
            res.put("dataset", drone.dataset(id));
            return res;
        } catch (Exception e) {
            return fail("drone upload", e);
        }
    }

    @PostMapping("/datasets/{id}/publish")
    public Map<String, Object> publish(@PathVariable int id) {
        try {
            rasters.publish(id);
            return ok("status", DroneService.PROCESSING);
        } catch (Exception e) {
            return fail("drone publish", e);
        }
    }

    @PostMapping("/datasets/{id}/unpublish")
    public Map<String, Object> unpublish(@PathVariable int id) {
        try {
            rasters.unpublish(id);
            return ok("status", DroneService.UPLOADED);
        } catch (Exception e) {
            return fail("drone unpublish", e);
        }
    }

    @DeleteMapping("/datasets/{id}")
    public Map<String, Object> deleteDataset(@PathVariable int id) {
        try {
            rasters.delete(id);
            return ok("deleted", id);
        } catch (Exception e) {
            return fail("drone delete dataset", e);
        }
    }

    /**
     * Record the vertical reference a GeoTIFF does not carry.
     *
     * <p>A geoid model is almost always in the processing report rather than in the
     * file — Pix4D and Metashape write the horizontal CRS into the GeoTIFF and leave
     * the vertical side to their PDF. So it is entered by hand, separately from
     * {@code geo_details}, which stays strictly what the file itself declares.
     *
     * <p>Editable after upload because the documentation frequently arrives later
     * than the raster does.
     */
    @PutMapping("/datasets/{id}/geoid")
    public Map<String, Object> setGeoid(@PathVariable int id, @RequestBody Map<String, String> body) {
        try {
            drone.setGeoidModel(id, body == null ? null : body.get("geoid_model"));
            return ok("id", id);
        } catch (Exception e) {
            return fail("drone geoid", e);
        }
    }

    /* ---------------- contours ---------------- */

    /**
     * Trace contours from a DEM at the given interval, in metres.
     *
     * <p>Queued, like publishing: the response says PROCESSING and the dataset's
     * {@code contour_status} carries the outcome.
     */
    @PostMapping("/datasets/{id}/contours")
    public Map<String, Object> makeContours(@PathVariable int id, @RequestBody Map<String, Object> body) {
        try {
            Object raw = body == null ? null : body.get("interval");
            double interval = raw == null ? 1 : Double.parseDouble(String.valueOf(raw));
            contours.generate(id, interval);
            return ok("contour_status", DroneContourService.PENDING);
        } catch (NumberFormatException e) {
            return fail("drone contours", new IllegalArgumentException("The interval must be a number."));
        } catch (Exception e) {
            return fail("drone contours", e);
        }
    }

    /**
     * Import contour lines from a survey file.
     *
     * <p>Takes GeoJSON, not the file itself: shapefiles are unzipped by shpjs in the
     * browser and KML is read by {@code js/kml-reader.js}, exactly as the Layer
     * Management importer already does it. That keeps shapefile and KML parsing off
     * the server entirely.
     */
    @PostMapping("/projects/{projectId}/contours/import")
    @SuppressWarnings("unchecked")
    public Map<String, Object> importContours(@PathVariable int projectId,
                                              @RequestBody Map<String, Object> body,
                                              Authentication auth) {
        try {
            Object raw = body.get("features");
            if (!(raw instanceof List<?> list) || list.isEmpty())
                throw new IllegalArgumentException("That file contains no features to import.");

            String name = str(body.get("dataset_name"));
            String file = str(body.get("file_name"));
            if (name == null) name = file == null ? "Imported contours" : file;

            int id = contours.importFeatures(projectId, name, file == null ? "" : file,
                    str(body.get("elevation_field")), str(body.get("geoid_model")),
                    (List<Map<String, Object>>) list, auth == null ? null : auth.getName());
            Map<String, Object> res = ok("id", id);
            res.put("dataset", drone.dataset(id));
            return res;
        } catch (Exception e) {
            return fail("drone contour import", e);
        }
    }

    private static String str(Object v) {
        String s = v == null ? null : String.valueOf(v).trim();
        return s == null || s.isEmpty() ? null : s;
    }

    @DeleteMapping("/datasets/{id}/contours")
    public Map<String, Object> dropContours(@PathVariable int id) {
        try {
            contours.clear(id);
            return ok("deleted", id);
        } catch (Exception e) {
            return fail("drone contours", e);
        }
    }

    /**
     * One tile of a dataset's contours.
     *
     * <p>204 for an empty tile, matching the raster endpoint and the rest of the
     * MVT layers. Not cached as hard as a raster tile: re-tracing at a different
     * interval replaces the lines in place without a version to bust a cache with,
     * so a short cache keeps a re-trace visible.
     */
    @GetMapping(value = "/datasets/{id}/contours/tiles/{z}/{x}/{y}.mvt",
                produces = "application/vnd.mapbox-vector-tile")
    public ResponseEntity<byte[]> contourTile(@PathVariable int id, @PathVariable int z,
                                              @PathVariable int x, @PathVariable int y) {
        TileCoordinate t = TileCoordinate.of(z, x, y, CONTOUR_MAX_ZOOM);
        byte[] tile = contourTiles.tile(id, t);
        if (tile == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(tile);
    }

    /* ---------------- viewer ---------------- */

    @GetMapping("/published")
    public List<Map<String, Object>> published() {
        return drone.publishedDatasets();
    }

    /**
     * One 256px raster tile.
     *
     * <p>204 rather than 404 where the pyramid has nothing: MapLibre treats a 404
     * as an error worth logging on every pan past the edge of the image, while an
     * empty 204 is simply nothing to draw.
     *
     * <p>Cached hard, because a published pyramid is immutable — re-publishing
     * bumps {@code build_version}, which the viewer carries in the URL, so a rebuilt
     * dataset is a different URL rather than a stale cache entry.
     */
    @GetMapping(value = "/datasets/{id}/tiles/{z}/{x}/{y}.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> tile(@PathVariable int id,
                                       @PathVariable int z,
                                       @PathVariable int x,
                                       @PathVariable int y) {
        if (z < 0 || z > 24 || x < 0 || y < 0 || x >= (1 << z) || y >= (1 << z))
            return ResponseEntity.badRequest().build();
        try {
            Path png = rasters.tileFile(id, z, x, y);
            if (png == null) return ResponseEntity.noContent().build();
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                    .contentType(MediaType.IMAGE_PNG)
                    .body(Files.readAllBytes(png));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/datasets/{id}/elevation")
    public Map<String, Object> elevation(@PathVariable int id,
                                         @RequestParam("lng") double lng,
                                         @RequestParam("lat") double lat) {
        try {
            Double v = rasters.elevationAt(id, lng, lat);
            Map<String, Object> res = ok("id", id);
            res.put("elevation", v);
            return res;
        } catch (Exception e) {
            return fail("drone elevation", e);
        }
    }

    /* ---------------- helpers ---------------- */

    private static Map<String, Object> ok(String key, Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put("ok", true);
        m.put(key, value);
        return m;
    }

    private static Map<String, Object> fail(String context, Exception e) {
        Map<String, Object> m = new HashMap<>();
        m.put("ok", false);
        m.put("error", ApiErrors.safe(context, e));
        return m;
    }
}
