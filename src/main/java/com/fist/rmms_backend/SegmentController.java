package com.fist.rmms_backend;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/segments")
public class SegmentController {

    private final SegmentService service;

    public SegmentController(SegmentService service) {
        this.service = service;
    }

    /** Build (or rebuild) the linearly-referenced coloured segments. */
    @PostMapping("/build")
    public Map<String, Object> build() {
        Map<String, Object> result = new HashMap<>();
        try {
            int n = service.buildSegments();
            result.put("status", "ok");
            result.put("segments", n);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", ApiErrors.safe("segment build", e));
        }
        return result;
    }

    /** Serve the segments as GeoJSON for the map.
     *  Defaults to the active survey period; ?period_id= selects another. */
    @GetMapping(value = "/geojson", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> geojson(@RequestParam(value = "period_id", required = false) Integer periodId,
                                          @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        // no-cache + ETag: the browser must revalidate every load, so freshly
        // built segments show up on a normal reload (no hard-refresh / restart
        // needed). The body is served from the in-memory cache, and when it is
        // unchanged the ETag turns the revalidation into an empty 304 — no
        // multi-MB re-download on a repeat map open.
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
