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
import java.util.Map;

/**
 * Raster upload, publish and tile/pixel serving for Temporary Layers'
 * raster support.
 *
 * <p>Kept apart from {@link LayerDataController} for the same reason
 * {@link UserLayerTileController} sits apart from it: a binary content type
 * and a file lookup on disk do not belong branching inside JSON endpoints.
 *
 * <pre>
 *   POST   /api/layer-data/{layerId}/raster/geotiff             upload a GeoTIFF
 *   POST   /api/layer-data/{layerId}/raster/worldfile           upload image + world file
 *   POST   /api/layer-data/{layerId}/raster/publish             queue the tile build
 *   GET    /api/layer-data/{layerId}/raster/status               UPLOADED|PROCESSING|PUBLISHED|FAILED
 *   GET    /api/layer-data/{layerId}/raster/tiles/{z}/{x}/{y}.png one raster tile
 *   GET    /api/layer-data/{layerId}/raster/pixel?lng=&lat=       band values at a point
 * </pre>
 *
 * <p>Writes fall under the blanket {@code POST/PUT/DELETE /api/**} ADMIN rule
 * in {@code SecurityConfig}; the tile and pixel endpoints are gated on the
 * same viewer-visibility rule as {@link UserLayerTileService} (owner/shared,
 * not hidden, not frozen) plus the raster being published.
 */
@RestController
@RequestMapping("/api/layer-data/{layerId}/raster")
public class UserRasterController {

    private final UserRasterService rasters;

    public UserRasterController(UserRasterService rasters) {
        this.rasters = rasters;
    }

    @PostMapping("/geotiff")
    public Map<String, Object> uploadGeoTiff(@PathVariable int layerId,
                                             @RequestParam("file") MultipartFile file,
                                             @RequestParam(value = "colourRamp", required = false) String colourRamp,
                                             @RequestParam(value = "valueLabel", required = false) String valueLabel,
                                             Authentication auth) {
        try {
            Map<String, Object> row = rasters.store(layerId, file, colourRamp, valueLabel,
                    auth == null ? null : auth.getName());
            return ok("raster", row);
        } catch (Exception e) {
            return fail("raster geotiff upload", e);
        }
    }

    @PostMapping("/worldfile")
    public Map<String, Object> uploadWorldFile(@PathVariable int layerId,
                                               @RequestParam("image") MultipartFile image,
                                               @RequestParam("worldfile") MultipartFile worldfile,
                                               Authentication auth) {
        try {
            Map<String, Object> row = rasters.storeWorldFile(layerId, image, worldfile,
                    auth == null ? null : auth.getName());
            return ok("raster", row);
        } catch (Exception e) {
            return fail("raster world-file upload", e);
        }
    }

    @PostMapping("/publish")
    public Map<String, Object> publish(@PathVariable int layerId) {
        try {
            rasters.publish(layerId);
            return ok("status", UserRasterService.PROCESSING);
        } catch (Exception e) {
            return fail("raster publish", e);
        }
    }

    @GetMapping("/status")
    public Map<String, Object> status(@PathVariable int layerId) {
        try {
            return ok("raster", rasters.row(layerId));
        } catch (Exception e) {
            return fail("raster status", e);
        }
    }

    /** The map viewer's opacity slider — persisted so it survives a reload. */
    @PutMapping("/opacity")
    public Map<String, Object> opacity(@PathVariable int layerId, @RequestBody Map<String, Object> body) {
        try {
            Object v = body == null ? null : body.get("opacity");
            if (!(v instanceof Number n)) throw new IllegalArgumentException("opacity must be a number 0-1.");
            rasters.setOpacity(layerId, n.doubleValue());
            return ok("opacity", n.doubleValue());
        } catch (Exception e) {
            return fail("raster opacity", e);
        }
    }

    @GetMapping(value = "/tiles/{z}/{x}/{y}.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> tile(@PathVariable int layerId, @PathVariable int z,
                                       @PathVariable int x, @PathVariable int y,
                                       Authentication auth) {
        if (z < 0 || z > 24 || x < 0 || y < 0 || x >= (1 << z) || y >= (1 << z))
            return ResponseEntity.badRequest().build();
        String user = (auth == null) ? "unknown" : auth.getName();
        if (!rasters.isDrawable(layerId, user)) return ResponseEntity.noContent().build();
        try {
            Path png = rasters.tileFile(layerId, z, x, y);
            if (png == null) return ResponseEntity.noContent().build();
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                    .contentType(MediaType.IMAGE_PNG)
                    .body(Files.readAllBytes(png));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/pixel")
    public Map<String, Object> pixel(@PathVariable int layerId,
                                     @RequestParam("lng") double lng,
                                     @RequestParam("lat") double lat,
                                     Authentication auth) {
        String user = (auth == null) ? "unknown" : auth.getName();
        if (!rasters.isDrawable(layerId, user))
            return fail("raster pixel", new IllegalArgumentException("That layer is not available."));
        try {
            Map<String, Object> v = rasters.pixelValueAt(layerId, lng, lat);
            Map<String, Object> res = ok("id", layerId);
            res.put("bands", v == null ? null : v.get("bands"));
            return res;
        } catch (Exception e) {
            return fail("raster pixel", e);
        }
    }

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
