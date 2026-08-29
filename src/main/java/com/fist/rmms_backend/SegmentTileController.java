package com.fist.rmms_backend;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the condition segments as Mapbox Vector Tiles.
 *
 * <p>Additive: {@code /api/segments/geojson} is untouched and still serves the whole network to
 * every current consumer. Nothing in the viewer requests a tile yet — this exists so the tile
 * output can be verified against the GeoJSON before anything depends on it.
 *
 * <p>Separate from {@link SegmentController} despite sharing a URL prefix because the two answer
 * different questions with different content types, and keeping the tile path out of the
 * build/count controller means neither has to grow a binary branch.
 */
@RestController
public class SegmentTileController {

    /** The MVT media type. Registered explicitly — Spring has no default mapping for it. */
    private static final String MVT = "application/vnd.mapbox-vector-tile";

    private final SegmentTileService tiles;

    public SegmentTileController(SegmentTileService tiles) {
        this.tiles = tiles;
    }

    /**
     * One tile of condition segments. Defaults to the active survey period; {@code ?period_id=}
     * selects another, matching the GeoJSON endpoint.
     *
     * <p>{@code ?p=} names the condition parameter whose per-lane values the tile carries — the one
     * the viewer is currently painting. A tile that carried all seven parameters for all five lanes
     * spent most of its bytes on 30 columns nothing was reading; the client re-points the source
     * when the metric changes, which costs one viewport of tiles instead of every tile carrying
     * every metric forever. Defaults to {@code iri}, the metric the viewer opens on.
     *
     * <p>Three outcomes rather than two. A malformed address — or an unknown parameter — is a 400,
     * validated here rather than left to {@link GlobalExceptionHandler}, which would answer a
     * binary endpoint with a JSON error body. An address that is valid but holds nothing to draw is
     * a 204, so a client can tell "off the network" from "something broke". Everything else is the
     * tile, with a weak ETag so a pan back over ground already visited revalidates to an empty 304.
     */
    @GetMapping(value = "/api/segments/tiles/{z}/{x}/{y}.mvt", produces = MVT)
    public ResponseEntity<byte[]> tile(@PathVariable int z,
                                       @PathVariable int x,
                                       @PathVariable int y,
                                       @RequestParam(value = "period_id", required = false) Integer periodId,
                                       @RequestParam(value = "p", required = false) String param,
                                       @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        TileCoordinate coord;
        try {
            coord = TileCoordinate.of(z, x, y, tiles.maxZoom());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        String p = (param == null || param.isBlank())
                ? SegmentTileService.DEFAULT_PARAM : param;
        if (!SegmentLaneColumns.isParam(p)) return ResponseEntity.badRequest().build();

        byte[] body = tiles.tile(coord, periodId, p);
        if (body == null) return ResponseEntity.noContent().build();

        String tag = GeoJsonResponse.contentTag(body);
        if (GeoJsonResponse.matches(ifNoneMatch, tag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(tag).cacheControl(CacheControl.noCache()).build();
        }
        // no-cache, not a long max-age: a Build Segments run changes every tile, and there is no
        // build-version counter to put in the URL. Revalidation keeps a rebuild from being served
        // stale; the ETag keeps the repeat cost at 304-with-no-body. If tiles later become hot
        // enough for that round trip to matter, the fix is a build counter in the path, not a
        // longer max-age.
        return ResponseEntity.ok()
                .eTag(tag).cacheControl(CacheControl.noCache())
                .header("Content-Type", MVT)
                .body(body);
    }
}
