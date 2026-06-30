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
            result.put("message", String.valueOf(e.getMessage()));
        }
        return result;
    }

    /** Serve the segments as GeoJSON for the map. */
    @GetMapping(value = "/geojson", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> geojson() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
                .contentType(MediaType.APPLICATION_JSON)
                .body(service.segmentsGeoJson());
    }

    @GetMapping("/count")
    public Map<String, Object> count() {
        Map<String, Object> result = new HashMap<>();
        result.put("count", service.count());
        return result;
    }
}
