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
 * Serves the road network as Mapbox Vector Tiles.
 *
 * <p>Additive, same as {@link SegmentTileController}: {@code /api/roads/geojson} is untouched and
 * still serves the whole network to every current consumer. Nothing requests a road tile yet.
 */
@RestController
public class RoadTileController {

    private static final String MVT = "application/vnd.mapbox-vector-tile";

    private final RoadTileService tiles;
    private final RoadColumns columns;

    public RoadTileController(RoadTileService tiles, RoadColumns columns) {
        this.tiles = tiles;
        this.columns = columns;
    }

    /**
     * One tile of road centrelines.
     *
     * <p>{@code ?attr=} names the column the viewer is colouring by, so the tile carries it in
     * addition to the four properties every road layer needs. An unknown name is a 400 rather than
     * a silently attribute-less tile: it means the client and the schema disagree, and a blank map
     * with no error is the harder version of that to diagnose.
     */
    @GetMapping(value = "/api/roads/tiles/{z}/{x}/{y}.mvt", produces = MVT)
    public ResponseEntity<byte[]> tile(@PathVariable int z, @PathVariable int x, @PathVariable int y,
                                       @RequestParam(value = "attr", required = false) String attr,
                                       @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        TileCoordinate coord;
        try {
            coord = TileCoordinate.of(z, x, y, tiles.maxZoom());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        String a = (attr == null || attr.isBlank()) ? null : attr;
        if (a != null && !columns.isValid(a)) return ResponseEntity.badRequest().build();

        byte[] body = tiles.tile(coord, a);
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
