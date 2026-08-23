package com.fist.rmms_backend;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Attribute Data API.
 *
 * Writes inherit the ADMIN requirement SecurityConfig already applies to every
 * mutating {@code /api/**} call, so there is no role annotation here to drift
 * out of step with it.
 */
@RestController
@RequestMapping("/api/attributes")
public class LayerAttributeController {

    private final LayerAttributeService attrs;

    public LayerAttributeController(LayerAttributeService attrs) {
        this.attrs = attrs;
    }

    /** Every dataset and attribute of one layer. */
    @GetMapping("/layer/{layerId}")
    public ResponseEntity<?> forLayer(@PathVariable int layerId) {
        try {
            return ResponseEntity.ok(attrs.forLayer(layerId));
        } catch (Exception e) {
            return fail("layer attributes", e);
        }
    }

    /** The contract the import screen maps a file's columns onto. */
    @GetMapping("/layer/{layerId}/import-spec")
    public ResponseEntity<?> importSpec(@PathVariable int layerId,
                                        @RequestParam(required = false) String dataset) {
        try {
            return ResponseEntity.ok(Map.of("attributes", attrs.importSpec(layerId, dataset)));
        } catch (Exception e) {
            return fail("import spec", e);
        }
    }

    @GetMapping("/lookups")
    public ResponseEntity<?> lookups() {
        try {
            return ResponseEntity.ok(Map.of("sets", attrs.lookupSets()));
        } catch (Exception e) {
            return fail("lookup sets", e);
        }
    }

    @PostMapping("/layer/{layerId}")
    public ResponseEntity<?> add(@PathVariable int layerId, @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(attrs.addAttribute(layerId, body));
        } catch (Exception e) {
            return fail("add attribute", e);
        }
    }

    @PutMapping("/{attrId}")
    public ResponseEntity<?> update(@PathVariable int attrId, @RequestBody Map<String, Object> body) {
        try {
            attrs.updateAttribute(attrId, body);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            return fail("update attribute", e);
        }
    }

    @DeleteMapping("/{attrId}")
    public ResponseEntity<?> delete(@PathVariable int attrId) {
        try {
            attrs.deleteAttribute(attrId);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            return fail("delete attribute", e);
        }
    }

    /**
     * A protected-attribute refusal is a 403 carrying its own explanation —
     * the caller is not wrong about the request shape, they are asking for
     * something the layer's source type forbids.
     */
    private ResponseEntity<?> fail(String context, Exception e) {
        if (e instanceof LayerAttributeService.ProtectedAttributeException) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("ok", false, "error", e.getMessage()));
        }
        return ResponseEntity.badRequest()
                .body(Map.of("ok", false, "error", ApiErrors.safe(context, e)));
    }
}
