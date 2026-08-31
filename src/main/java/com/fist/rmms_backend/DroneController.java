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

    private final DroneService drone;
    private final DroneRasterService rasters;

    public DroneController(DroneService drone, DroneRasterService rasters) {
        this.drone = drone;
        this.rasters = rasters;
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
                                      @RequestParam("file") MultipartFile file,
                                      Authentication auth) {
        try {
            int id = rasters.store(projectId, type, name, file, auth == null ? null : auth.getName());
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
