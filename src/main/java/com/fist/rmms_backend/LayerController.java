package com.fist.rmms_backend;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Layer Management APIs — Folder → Layer. Hide is soft; only boundary layers may be hidden.
 */
@RestController
@RequestMapping("/api/layers")
public class LayerController {

    private final LayerSchemaService schema;
    private final ManagedLayerService managed;

    public LayerController(LayerSchemaService schema, ManagedLayerService managed) {
        this.schema = schema;
        this.managed = managed;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(value = "includeHidden", defaultValue = "false") boolean includeHidden) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("folders", managed.listFolders(includeHidden));
        out.put("boundaries", managed.listBoundaries());
        out.put("conditionColumns", managed.conditionColumnChoices());
        return out;
    }

    @GetMapping("/folders")
    public List<Map<String, Object>> folders(
            @RequestParam(value = "includeHidden", defaultValue = "false") boolean includeHidden) {
        return managed.listFolders(includeHidden);
    }

    @PostMapping("/folders")
    public Map<String, Object> createFolder(@RequestBody Map<String, String> body) {
        return managed.createFolder(
                body == null ? null : body.get("key"),
                body == null ? null : body.get("label"),
                body == null ? null : body.get("kind"));
    }

    /** Soft-hide / unhide a folder. Non-boundary → "Mandatory Layer - Can't Hide". */
    @PostMapping("/folders/{folderKey}/hide")
    public Map<String, Object> hideFolder(@PathVariable String folderKey,
                                          @RequestBody(required = false) Map<String, Object> body) {
        boolean hide = body == null || !Boolean.FALSE.equals(body.get("hidden"));
        return managed.hideFolder(folderKey, hide);
    }

    @PostMapping("/folders/{folderKey}/layers")
    public Map<String, Object> createLayerInFolder(@PathVariable String folderKey,
                                                   @RequestBody Map<String, String> body) {
        return managed.createLayerInFolder(
                body == null ? null : body.get("key"),
                body == null ? null : body.get("label"),
                folderKey,
                body == null ? null : body.get("valueColumn"),
                body == null ? null : body.get("featureType"),
                body == null ? null : body.get("format"));
    }

    /** Choices offered by the "New layer" form for custom (boundary-kind) layers. */
    @GetMapping("/layer-options")
    public Map<String, Object> layerOptions() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("featureTypes", ManagedLayerService.FEATURE_TYPES);
        out.put("importFormats", ManagedLayerService.IMPORT_FORMATS);
        out.put("defaultSrid", 4326);
        out.put("defaultProjection", "EPSG:4326 — WGS 1984");
        return out;
    }

    @GetMapping("/boundaries")
    public List<Map<String, Object>> boundaries() {
        return managed.listBoundaries();
    }

    @PostMapping("/boundaries")
    public Map<String, Object> createBoundary(@RequestBody Map<String, String> body) {
        String folder = body == null ? null : body.get("folderKey");
        if (folder == null || folder.isBlank()) folder = "administrative_boundary";
        return managed.createLayerInFolder(
                body == null ? null : body.get("key"),
                body == null ? null : body.get("label"),
                folder,
                null,
                body == null ? null : body.get("featureType"),
                body == null ? null : body.get("format"));
    }

    /** Soft-hide / unhide a layer. Non-boundary → "Mandatory Layer - Can't Hide". */
    @PostMapping("/boundaries/{key}/hide")
    public Map<String, Object> hideLayer(@PathVariable String key,
                                         @RequestBody(required = false) Map<String, Object> body) {
        boolean hide = body == null || !Boolean.FALSE.equals(body.get("hidden"));
        return managed.hideLayer(key, hide);
    }

    @GetMapping("/condition-columns")
    public List<Map<String, String>> conditionColumns() {
        return managed.conditionColumnChoices();
    }

    @GetMapping("/{layerKey}/import-status")
    public ResponseEntity<?> importStatus(@PathVariable String layerKey) {
        try {
            return ResponseEntity.ok(schema.importStatus(layerKey));
        } catch (IllegalArgumentException e) {
            return notFound(e.getMessage());
        }
    }

    @GetMapping("/{layerKey}")
    public ResponseEntity<?> one(@PathVariable String layerKey) {
        try {
            return ResponseEntity.ok(schema.layerDetail(layerKey));
        } catch (IllegalArgumentException e) {
            return notFound(e.getMessage());
        }
    }

    @GetMapping("/{layerKey}/attributes")
    public ResponseEntity<?> attributes(@PathVariable String layerKey) {
        try {
            return ResponseEntity.ok(schema.attributes(layerKey));
        } catch (IllegalArgumentException e) {
            return notFound(e.getMessage());
        }
    }

    private static ResponseEntity<Map<String, Object>> notFound(String msg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", msg);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }
}
