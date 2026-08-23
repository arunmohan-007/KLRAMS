package com.fist.rmms_backend;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Layer Management API.
 *
 * Writes need no extra role annotation: SecurityConfig already requires ADMIN
 * for POST/PUT/DELETE/PATCH on {@code /api/**}, so adding one here would only
 * create a second place for the rule to drift out of step with the first.
 *
 * Protection is enforced in the service, not by the UI hiding buttons — a
 * request that reaches {@code DELETE /api/layers/3} for a system-generated
 * layer is refused on the server whatever the client believes.
 */
@RestController
@RequestMapping("/api/layers")
public class LayerRegistryController {

    private final LayerRegistryService layers;

    public LayerRegistryController(LayerRegistryService layers) {
        this.layers = layers;
    }

    /** Folders with their layers, in Layers-panel order. */
    @GetMapping("/tree")
    public ResponseEntity<?> tree() {
        try {
            List<Map<String, Object>> tree = layers.tree();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("folders", tree);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return fail("layer tree", e);
        }
    }

    @PostMapping("/folders")
    public ResponseEntity<?> createFolder(@RequestBody Map<String, Object> body) {
        try {
            Object name = body.get("name");
            return ResponseEntity.ok(layers.createFolder(name == null ? null : String.valueOf(name)));
        } catch (Exception e) {
            return fail("create folder", e);
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            String user = (auth == null) ? "unknown" : auth.getName();
            return ResponseEntity.ok(layers.createLayer(body, user));
        } catch (Exception e) {
            return fail("create layer", e);
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable int id, @RequestBody Map<String, Object> body) {
        try {
            layers.updateLayer(id, body);
            return ok();
        } catch (Exception e) {
            return fail("update layer", e);
        }
    }

    /**
     * Retire a user layer. {@code ?purge=true} additionally drops its table —
     * the deliberate second step, so a mis-click cannot destroy uploaded data.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable int id,
                                    @RequestParam(defaultValue = "false") boolean purge) {
        try {
            layers.deleteLayer(id, purge);
            return ok();
        } catch (Exception e) {
            return fail("delete layer", e);
        }
    }

    private ResponseEntity<?> ok() {
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * A protected-layer refusal is a 403 with its own wording — it is not the
     * user mistyping something, and the message explains WHY the layer cannot
     * be touched (generated, core, or rename-only). Everything else goes
     * through ApiErrors so driver/schema detail never reaches the client.
     */
    private ResponseEntity<?> fail(String context, Exception e) {
        if (e instanceof LayerRegistryService.ProtectedLayerException) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("ok", false, "error", e.getMessage()));
        }
        return ResponseEntity.badRequest()
                .body(Map.of("ok", false, "error", ApiErrors.safe(context, e)));
    }
}
