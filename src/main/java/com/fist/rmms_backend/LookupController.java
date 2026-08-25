package com.fist.rmms_backend;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Lookup &amp; Short Code API.
 *
 * Writes inherit the ADMIN requirement SecurityConfig already applies to every
 * mutating {@code /api/**} call, so there is no role annotation here to drift
 * out of step with it.
 */
@RestController
@RequestMapping("/api/lookups")
public class LookupController {

    private final LookupService lookups;

    public LookupController(LookupService lookups) {
        this.lookups = lookups;
    }

    /** Every code list, its codes, and the attributes reading it. */
    @GetMapping
    public ResponseEntity<?> list() {
        try {
            return ResponseEntity.ok(Map.of("sets", lookups.sets()));
        } catch (Exception e) {
            return fail("code lists", e);
        }
    }

    /** Layers and the attributes that could carry a code — the binding picker. */
    @GetMapping("/bindable")
    public ResponseEntity<?> bindable() {
        try {
            return ResponseEntity.ok(Map.of("layers", lookups.bindableAttributes()));
        } catch (Exception e) {
            return fail("bindable attributes", e);
        }
    }

    /**
     * The decode table the viewer expands codes with: set key -> code -> value.
     *
     * Separate from the editing endpoint above because the callers differ. This
     * one is fetched on page load by every map card at once and carries only
     * what an expansion needs; that one serves the screen editing one list.
     */
    @GetMapping("/decode")
    public ResponseEntity<?> decode() {
        try {
            return ResponseEntity.ok(Map.of("sets", lookups.decodeTable()));
        } catch (Exception e) {
            return fail("decode table", e);
        }
    }

    @PostMapping
    public ResponseEntity<?> createSet(@RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(lookups.createSet(str(body.get("name"))));
        } catch (Exception e) {
            return fail("create code list", e);
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> renameSet(@PathVariable int id, @RequestBody Map<String, Object> body) {
        try {
            lookups.renameSet(id, str(body.get("name")));
            return ok();
        } catch (Exception e) {
            return fail("rename code list", e);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteSet(@PathVariable int id) {
        try {
            lookups.deleteSet(id);
            return ok();
        } catch (Exception e) {
            return fail("delete code list", e);
        }
    }

    /** Add a code, or change what an existing one stands for. */
    @PostMapping("/{id}/values")
    public ResponseEntity<?> putValue(@PathVariable int id, @RequestBody Map<String, Object> body) {
        try {
            Object act = body.get("active");
            lookups.putValue(id, str(body.get("code")), str(body.get("label")),
                    str(body.get("dependsOn")),
                    act == null ? null : Boolean.valueOf(String.valueOf(act)));
            return ok();
        } catch (Exception e) {
            return fail("save code", e);
        }
    }

    /* ------------------------------------------------------------------
       Per-attribute — what the Lookup screen works through
       ------------------------------------------------------------------ */

    /** One attribute's data type, its code list, its values, and who shares it. */
    @GetMapping("/attribute/{attributeId}")
    public ResponseEntity<?> forAttribute(@PathVariable int attributeId) {
        try {
            return ResponseEntity.ok(lookups.forAttribute(attributeId));
        } catch (Exception e) {
            return fail("attribute lookup", e);
        }
    }

    /**
     * Turn the lookup on for an attribute — switches its data type to LOOKUP and
     * gives it a code list, creating one unless {@code setKey} names an existing
     * list to share.
     */
    @PostMapping("/attribute/{attributeId}/enable")
    public ResponseEntity<?> enable(@PathVariable int attributeId, @RequestBody(required = false) Map<String, Object> body) {
        try {
            return ResponseEntity.ok(lookups.enable(attributeId,
                    body == null ? null : str(body.get("setKey"))));
        } catch (Exception e) {
            return fail("enable lookup", e);
        }
    }

    /** Turn it off — the attribute goes back to free text; the list is kept. */
    @PostMapping("/attribute/{attributeId}/disable")
    public ResponseEntity<?> disable(@PathVariable int attributeId) {
        try {
            lookups.disable(attributeId);
            return ok();
        } catch (Exception e) {
            return fail("disable lookup", e);
        }
    }

    @DeleteMapping("/values/{valueId}")
    public ResponseEntity<?> deleteValue(@PathVariable int valueId) {
        try {
            lookups.deleteValue(valueId);
            return ok();
        } catch (Exception e) {
            return fail("delete code", e);
        }
    }

    /** Point one attribute at a code list, or clear it with a blank key. */
    @PostMapping("/bind/{attributeId}")
    public ResponseEntity<?> bind(@PathVariable int attributeId, @RequestBody Map<String, Object> body) {
        try {
            lookups.bindAttribute(attributeId, str(body.get("setKey")));
            return ok();
        } catch (Exception e) {
            return fail("bind attribute", e);
        }
    }

    private static ResponseEntity<?> ok() {
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private ResponseEntity<?> fail(String context, Exception e) {
        return ResponseEntity.badRequest()
                .body(Map.of("ok", false, "error", ApiErrors.safe(context, e)));
    }
}
