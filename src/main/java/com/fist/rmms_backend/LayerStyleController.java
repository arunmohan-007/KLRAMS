package com.fist.rmms_backend;

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
 * Style &amp; Label Management API.
 *
 * <p>Writes need no extra role annotation — {@code SecurityConfig} already
 * requires ADMIN for POST/PUT/DELETE on {@code /api/**}, and a second rule here
 * would only be somewhere for the first to drift out of step with.
 *
 * <p>{@code GET /api/layer-styles} is the one endpoint the viewer calls, and it
 * is deliberately the cheapest thing here: one small document holding only the
 * layers somebody has actually styled.
 */
@RestController
@RequestMapping("/api/layer-styles")
public class LayerStyleController {

    private final LayerStyleService styles;

    public LayerStyleController(LayerStyleService styles) {
        this.styles = styles;
    }

    /** Every saved style, keyed by layer key. What the map viewer loads. */
    @GetMapping
    public ResponseEntity<?> all() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("styles", styles.allStyles());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return fail("layer styles", e);
        }
    }

    /**
     * The Style Management screen's whole payload: every stylable layer with its
     * attributes and current style, plus the template library.
     */
    @GetMapping("/manage")
    public ResponseEntity<?> manage() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("layers", styles.stylableLayers());
            out.put("templates", styles.templates());
            out.put("icons", LayerStyleService.ICONS);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return fail("style manager", e);
        }
    }

    @GetMapping("/templates")
    public ResponseEntity<?> templates() {
        try {
            return ResponseEntity.ok(Map.of("templates", styles.templates()));
        } catch (Exception e) {
            return fail("style templates", e);
        }
    }

    /**
     * The distinct values of one attribute, for building a category list.
     *
     * <p>Offered rather than left to the user to type: a colour-by-class style
     * is only correct if its values match the ones the data actually holds, and
     * "SH" versus "S.H." is not a difference anyone spots by eye.
     */
    @GetMapping("/{layerKey}/values")
    public ResponseEntity<?> values(@PathVariable String layerKey,
                                    @RequestParam String attribute,
                                    @RequestParam(defaultValue = "60") int limit) {
        try {
            List<String> values = styles.valuesOf(layerKey, attribute, limit);
            return ResponseEntity.ok(Map.of("values", values));
        } catch (Exception e) {
            return fail("attribute values", e);
        }
    }

    @GetMapping("/{layerKey}")
    public ResponseEntity<?> one(@PathVariable String layerKey) {
        try {
            Map<String, Object> style = styles.styleOf(layerKey);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("layerKey", layerKey);
            // null, not an empty object: "no style saved" is a different answer
            // from "an empty style", and the viewer treats them differently.
            out.put("style", style);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return fail("layer style", e);
        }
    }

    @PutMapping("/{layerKey}")
    public ResponseEntity<?> save(@PathVariable String layerKey,
                                  @RequestBody Map<String, Object> body,
                                  Authentication auth) {
        try {
            return ResponseEntity.ok(styles.save(layerKey, body, name(auth)));
        } catch (Exception e) {
            return fail("save style", e);
        }
    }

    /** Put a layer back to the built-in look its module draws by default. */
    @DeleteMapping("/{layerKey}")
    public ResponseEntity<?> reset(@PathVariable String layerKey) {
        try {
            styles.reset(layerKey);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            return fail("reset style", e);
        }
    }

    /**
     * Apply one template to one layer, a folder's worth, or all of them.
     *
     * <p>Body: {@code {templateKey, layerKeys:[…], overrides:{…}}}.
     */
    @PostMapping("/apply")
    public ResponseEntity<?> apply(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            @SuppressWarnings("unchecked")
            List<String> keys = (body.get("layerKeys") instanceof List<?> l)
                    ? (List<String>) l : List.<String>of();
            @SuppressWarnings("unchecked")
            Map<String, Object> overrides = (body.get("overrides") instanceof Map<?, ?> m)
                    ? (Map<String, Object>) m : Map.<String, Object>of();
            String tpl = body.get("templateKey") == null ? null : String.valueOf(body.get("templateKey"));
            return ResponseEntity.ok(styles.applyTemplate(tpl, keys, overrides, name(auth)));
        } catch (Exception e) {
            return fail("apply template", e);
        }
    }

    @PostMapping("/templates")
    public ResponseEntity<?> saveTemplate(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            return ResponseEntity.ok(styles.saveTemplate(body, name(auth)));
        } catch (Exception e) {
            return fail("save template", e);
        }
    }

    @DeleteMapping("/templates/{key}")
    public ResponseEntity<?> deleteTemplate(@PathVariable String key) {
        try {
            styles.deleteTemplate(key);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            return fail("delete template", e);
        }
    }

    private static String name(Authentication auth) {
        return auth == null ? "unknown" : auth.getName();
    }

    private ResponseEntity<?> fail(String context, Exception e) {
        return ResponseEntity.badRequest()
                .body(Map.of("ok", false, "error", ApiErrors.safe(context, e)));
    }
}
