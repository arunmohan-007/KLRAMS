package com.fist.rmms_backend;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Data Import API for user and temporary layers.
 *
 * The file itself never reaches this controller as a file: shapefiles are
 * unzipped by shpjs in the browser and CSVs are parsed there too, so what
 * arrives is already columns and rows. That is the pattern the rest of KLRAMS
 * uses ("Shapefile parsing happens in the browser; backend receives GeoJSON")
 * and it keeps a 2 GB upload limit from being the thing that decides whether an
 * import works.
 */
@RestController
@RequestMapping("/api/layer-data")
public class LayerDataController {

    private final LayerDataService data;

    public LayerDataController(LayerDataService data) {
        this.data = data;
    }

    /** Suggest a column-to-attribute mapping for the columns found in a file. */
    @PostMapping("/{layerId}/preview")
    public ResponseEntity<?> preview(@PathVariable int layerId, @RequestBody Map<String, Object> body) {
        try {
            List<String> cols = new ArrayList<>();
            if (body.get("columns") instanceof List<?> l) {
                for (Object o : l) if (o != null) cols.add(String.valueOf(o));
            }
            return ResponseEntity.ok(data.preview(layerId, str(body.get("dataset")), cols));
        } catch (Exception e) {
            return fail("import preview", e);
        }
    }

    /**
     * Load the rows.
     *
     * {@code mapping} is attribute storage key -> file column, exactly as the
     * preview returned it once the user has confirmed or corrected it.
     */
    @PostMapping("/{layerId}/import")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> load(@PathVariable int layerId, @RequestBody Map<String, Object> body) {
        try {
            Map<String, String> mapping = (Map<String, String>) body.getOrDefault("mapping", Map.of());
            List<Map<String, Object>> rows =
                    (List<Map<String, Object>>) body.getOrDefault("rows", List.of());
            List<String> geoms = new ArrayList<>();
            if (body.get("geometries") instanceof List<?> l) {
                for (Object o : l) geoms.add(o == null ? null : String.valueOf(o));
            }
            boolean replace = Boolean.TRUE.equals(body.get("replace"));
            Integer periodId = (body.get("periodId") instanceof Number n) ? n.intValue() : null;
            return ResponseEntity.ok(data.importRows(
                    layerId, str(body.get("dataset")), mapping, rows, geoms, replace, periodId));
        } catch (Exception e) {
            return fail("import", e);
        }
    }

    /** The layer's features, for the viewer. */
    @GetMapping(value = "/{layerId}/geojson", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> geojson(@PathVariable int layerId,
                                     @RequestParam(value = "period_id", required = false) Integer periodId) {
        try {
            return ResponseEntity.ok(data.geojson(layerId, periodId));
        } catch (Exception e) {
            return fail("layer geojson", e);
        }
    }

    /**
     * Layers the Console can import into: every live user layer, plus this
     * user's temporary ones.
     *
     * Frozen layers are listed but flagged, rather than hidden. The Console is
     * where someone goes to fix that, so silently omitting a layer they know
     * exists would be the unhelpful answer.
     */
    @GetMapping("/import-targets")
    public ResponseEntity<?> importTargets(Authentication auth) {
        try {
            String user = (auth == null) ? "unknown" : auth.getName();
            return ResponseEntity.ok(Map.of("layers", data.importTargets(user)));
        } catch (Exception e) {
            return fail("import targets", e);
        }
    }

    /** User and temporary layers this user may see in the viewer. */
    @GetMapping("/viewer-layers")
    public ResponseEntity<?> viewerLayers(Authentication auth) {
        try {
            String user = (auth == null) ? "unknown" : auth.getName();
            return ResponseEntity.ok(Map.of("layers", data.viewerLayers(user)));
        } catch (Exception e) {
            return fail("viewer layers", e);
        }
    }

    /** Empty a layer without deleting it — the "load a different file" case. */
    @DeleteMapping("/{layerId}/rows")
    public ResponseEntity<?> clear(@PathVariable int layerId) {
        try {
            return ResponseEntity.ok(Map.of("ok", true, "deleted", data.clearRows(layerId)));
        } catch (Exception e) {
            return fail("clear layer", e);
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private ResponseEntity<?> fail(String context, Exception e) {
        return ResponseEntity.badRequest()
                .body(Map.of("ok", false, "error", ApiErrors.safe(context, e)));
    }
}
