package com.fist.rmms_backend;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the road network as Mapbox Vector Tiles.
 *
 * <p>Additive, same as {@link SegmentTileController}: {@code /api/roads/geojson} is untouched and
 * still serves the whole network to every current consumer. Nothing requests a road tile yet.
 */
@RestController
public class RoadTileController {

    private static final String MVT = "application/vnd.mapbox-vector-tile";

    private final RoadTileService tiles;

    public RoadTileController(RoadTileService tiles) {
        this.tiles = tiles;
    }

    @GetMapping(value = "/api/roads/tiles/{z}/{x}/{y}.mvt", produces = MVT)
    public ResponseEntity<byte[]> tile(@PathVariable int z, @PathVariable int x, @PathVariable int y,
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
        // no-cache, not a long max-age: an upload replaces the network with no build-version
        // counter to key a longer cache on, same reasoning as SegmentTileController.
        return ResponseEntity.ok()
                .eTag(tag).cacheControl(CacheControl.noCache())
                .header("Content-Type", MVT)
                .body(body);
    }
}
