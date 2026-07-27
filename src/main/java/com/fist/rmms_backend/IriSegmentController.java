package com.fist.rmms_backend;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Avg IRI (2 km · worst lane) — build the 2 km IRI roll-up and serve it,
 * mirroring {@link FwdSegmentController} for FWD deflection.
 */
@RestController
@RequestMapping("/api/iri-2km")
public class IriSegmentController {

    private final IriSegmentService service;

    public IriSegmentController(IriSegmentService service) {
        this.service = service;
    }

    /** Build (or rebuild) the 2 km IRI bins from the uploaded condition survey. */
    @PostMapping("/build")
    public Map<String, Object> build() {
        Map<String, Object> result = new HashMap<>();
        try {
            int n = service.buildSegments();
            result.put("status", "ok");
            result.put("segments", n);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", ApiErrors.safe("2 km IRI build", e));
        }
        return result;
    }

    /** Serve the 2 km IRI bins as GeoJSON for the map.
     *  Defaults to the active survey period; ?period_id= selects another. */
    @GetMapping(value = "/geojson", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> geojson(@RequestParam(value = "period_id", required = false) Integer periodId,
                                          @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        GeoJsonResponse.Payload p = service.segmentsPayload(periodId);
        return GeoJsonResponse.conditional(p.body(), p.etag(), ifNoneMatch);
    }

    @GetMapping("/count")
    public Map<String, Object> count() {
        Map<String, Object> result = new HashMap<>();
        result.put("count", service.count());
        return result;
    }
}
