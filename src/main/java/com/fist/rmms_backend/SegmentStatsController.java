package com.fist.rmms_backend;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Whole-network answers about the condition segments, without the whole network.
 *
 * <p>Additive. Nothing in the viewer calls these yet; the client-side implementations in
 * {@code 02c-segment-data.js} remain in charge until each has been checked against its endpoint
 * on real data. The point of them is that a vector tile can serve the map's RENDERING but can
 * never serve these questions, because a tile only ever carries the current viewport — so until
 * they can be answered from the server, switching the map to tiles would mean downloading tiles
 * AND the full GeoJSON.
 *
 * <p>All GET, deliberately. {@code SecurityConfig} restricts {@code POST /api/**} to ADMIN, so a
 * POST here would lock every ordinary staff user out of their own map filters. That does cap how
 * much a request can carry, which is why the network-scope total is not here: its scope is a list
 * of section labels derived from road attributes on the client, and hundreds of them do not fit
 * in a URL. That one needs a different shape, not a bigger query string.
 */
@RestController
public class SegmentStatsController {

    private final SegmentStatsService stats;

    public SegmentStatsController(SegmentStatsService stats) {
        this.stats = stats;
    }

    /** Segment count for a period. Replaces {@code Segs.count()}. */
    @GetMapping("/api/segments/stats")
    public Map<String, Object> stats(@RequestParam(value = "period_id", required = false) Integer periodId) {
        Map<String, Object> out = new HashMap<>();
        out.put("count", stats.count(periodId));
        return out;
    }

    /** Chainage span of one section. Replaces {@code Segs.chainExtent(section)}. */
    @GetMapping("/api/segments/chain-extent")
    public Map<String, Object> chainExtent(@RequestParam("section") String section,
                                           @RequestParam(value = "period_id", required = false) Integer periodId) {
        return stats.chainExtent(section, periodId);
    }

    /**
     * Filter match count and bounding box. Replaces {@code Segs.matching(rows, mode)}.
     *
     * <p>Filters arrive as repeated {@code f=param:op:value} — {@code f=iri:gt:3&f=crack:gte:5}.
     * Operators are the five the filter UI offers, spelled {@code gt gte lt lte eq} because
     * {@code >} and {@code <} in a query string are a needless encoding hazard.
     *
     * <p>A malformed filter is a 400 through {@link GlobalExceptionHandler}, which returns the
     * developer-authored message — these are all of the form "unknown filter field: x", safe to
     * show and useful when a new parameter is added to the UI but not to the column list.
     */
    @GetMapping("/api/segments/match")
    public Map<String, Object> match(@RequestParam(value = "f", required = false) List<String> f,
                                     @RequestParam(value = "mode", defaultValue = "all") String mode,
                                     @RequestParam(value = "period_id", required = false) Integer periodId) {
        return stats.match(f, mode, periodId);
    }
}
