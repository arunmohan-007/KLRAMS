package com.fist.rmms_backend;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Serves the seven simple {@code road_assets} types as Mapbox Vector Tiles — everything
 * {@link AssetTileService#TILED_TYPES} covers; FWD is not one of them (see that class).
 * Additive, same as {@link SegmentTileController} / {@link RoadTileController}:
 * {@code /api/assets/{type}/geojson} is untouched and still serves every current consumer.
 */
@RestController
public class AssetTileController {

    private static final String MVT = "application/vnd.mapbox-vector-tile";

    private final AssetTileService tiles;

    public AssetTileController(AssetTileService tiles) {
        this.tiles = tiles;
    }

    /**
     * Whether this type can be drawn from tiles, and whether its rows are chainage ranges.
     *
     * <p>Asked once per layer before it is built. {@code tiled:false} or {@code stretch:true} both
     * mean "use the GeoJSON path" — see {@link AssetTileService#hasRangeRows}. Answering for an
     * unknown type is deliberately a 200 with {@code tiled:false} rather than a 404: the caller's
     * question is "can I tile this?", and "no" is a valid answer, not an error.
     */
    @GetMapping("/api/assets/{type}/tile-info")
    public Map<String, Object> tileInfo(@PathVariable String type,
                                        @RequestParam(value = "period_id", required = false) Integer periodId) {
        String t = type.toLowerCase();
        boolean tiled = AssetTileService.TILED_TYPES.contains(t);
        return Map.of("tiled", tiled, "stretch", tiled && tiles.hasRangeRows(t, periodId));
    }

    /**
     * One tile of one asset type. {@code type} outside {@link AssetTileService#TILED_TYPES}
     * (including {@code fwd}) is a 404, not a 400 — it is a valid asset type, just not one this
     * endpoint serves, and the caller (06-assets.js) uses the type's presence in that set to
     * decide whether to request tiles at all, so this only ever fires from a coding mistake.
     */
    @GetMapping(value = "/api/assets/{type}/tiles/{z}/{x}/{y}.mvt", produces = MVT)
    public ResponseEntity<byte[]> tile(@PathVariable String type,
                                        @PathVariable int z,
                                        @PathVariable int x,
                                        @PathVariable int y,
                                        @RequestParam(value = "period_id", required = false) Integer periodId,
                                        @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        String t = type.toLowerCase();
        if (!AssetTileService.TILED_TYPES.contains(t)) return ResponseEntity.notFound().build();

        TileCoordinate coord;
        try {
            coord = TileCoordinate.of(z, x, y, tiles.maxZoom());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        byte[] body = tiles.tile(coord, t, periodId);
        if (body == null) return ResponseEntity.noContent().build();

        String tag = GeoJsonResponse.contentTag(body);
        if (GeoJsonResponse.matches(ifNoneMatch, tag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(tag).cacheControl(CacheControl.noCache()).build();
        }
        return ResponseEntity.ok()
                .eTag(tag).cacheControl(CacheControl.noCache())
                .header("Content-Type", MVT)
                .body(body);
    }
}
