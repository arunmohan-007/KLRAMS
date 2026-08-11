package com.fist.rmms_backend;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Full Road Network as Mapbox Vector Tiles.
 *
 * <p>Additive: {@code /api/full-network/geojson} is untouched for {@code ?tiles=0} and
 * any analysis that still wants the whole FeatureCollection. Separate from
 * {@link FullNetworkController} so binary MVT is not mixed into the JSON upload/count
 * endpoints.
 */
@RestController
public class FullNetworkTileController {

    private static final String MVT = "application/vnd.mapbox-vector-tile";

    private final FullNetworkTileService tiles;

    public FullNetworkTileController(FullNetworkTileService tiles) {
        this.tiles = tiles;
    }

    /**
     * One tile of full-road centrelines. 400 on a malformed address, 204 when the
     * address is valid but empty, otherwise the tile with a weak ETag for revalidation
     * after an upload.
     */
    @GetMapping(value = "/api/full-network/tiles/{z}/{x}/{y}.mvt", produces = MVT)
    public ResponseEntity<byte[]> tile(@PathVariable int z,
                                       @PathVariable int x,
                                       @PathVariable int y,
                                       @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        TileCoordinate coord;
        try {
            coord = TileCoordinate.of(z, x, y, tiles.maxZoom());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        byte[] body = tiles.tile(coord);
        if (body == null) return ResponseEntity.noContent().build();

        String tag = GeoJsonResponse.contentTag(body);
        if (GeoJsonResponse.matches(ifNoneMatch, tag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(tag).cacheControl(CacheControl.noCache()).build();
        }
        // no-cache: an upload replaces roads with no build-version counter in the URL.
        return ResponseEntity.ok()
                .eTag(tag).cacheControl(CacheControl.noCache())
                .header("Content-Type", MVT)
                .body(body);
    }
}
