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
 * Serves the 2 km IRI roll-up as Mapbox Vector Tiles.
 *
 * <p>Additive: {@code /api/iri-2km/geojson} is untouched. Separate from
 * {@link IriSegmentController} for the same reason {@link SegmentTileController} sits
 * apart from the build/count controller — different content type, no binary branch
 * mixed into the JSON endpoints.
 */
@RestController
public class IriTileController {

    private static final String MVT = "application/vnd.mapbox-vector-tile";

    private final IriTileService tiles;

    public IriTileController(IriTileService tiles) {
        this.tiles = tiles;
    }

    /**
     * One tile of 2 km IRI bins. Defaults to the active survey period;
     * {@code ?period_id=} selects another, matching the GeoJSON endpoint.
     *
     * <p>400 on a malformed address, 204 when the address is valid but empty,
     * otherwise the tile with a weak ETag for revalidation after a rebuild.
     */
    @GetMapping(value = "/api/iri-2km/tiles/{z}/{x}/{y}.mvt", produces = MVT)
    public ResponseEntity<byte[]> tile(@PathVariable int z,
                                       @PathVariable int x,
                                       @PathVariable int y,
                                       @RequestParam(value = "period_id", required = false) Integer periodId,
                                       @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        TileCoordinate coord;
        try {
            coord = TileCoordinate.of(z, x, y, tiles.maxZoom());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        byte[] body = tiles.tile(coord, periodId);
        if (body == null) return ResponseEntity.noContent().build();

        String tag = GeoJsonResponse.contentTag(body);
        if (GeoJsonResponse.matches(ifNoneMatch, tag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(tag).cacheControl(CacheControl.noCache()).build();
        }
        // no-cache: a Build Avg IRI run changes every tile with no build-version
        // counter in the URL. Revalidation + ETag keeps a rebuild from going stale
        // while repeat pans cost a 304.
        return ResponseEntity.ok()
                .eTag(tag).cacheControl(CacheControl.noCache())
                .header("Content-Type", MVT)
                .body(body);
    }
}
