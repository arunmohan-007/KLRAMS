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
 * Serves {@code road_assets} as Mapbox Vector Tiles.
 * Additive: {@code /api/assets/{type}/geojson} is untouched.
 * FWD is answered by {@link FwdTileService} (line stretches + D0 colour props).
 */
@RestController
public class AssetTileController {

    private static final String MVT = "application/vnd.mapbox-vector-tile";

    private final AssetTileService tiles;
    private final FwdTileService fwdTiles;

    public AssetTileController(AssetTileService tiles, FwdTileService fwdTiles) {
        this.tiles = tiles;
        this.fwdTiles = fwdTiles;
    }

    /** Every type this endpoint serves. FWD is included and answered by {@link FwdTileService}. */
    private static boolean tileable(String type) {
        return AssetTileService.TILED_TYPES.contains(type) || "fwd".equals(type);
    }

    /**
     * Whether this type can be drawn from tiles, and whether its rows are chainage ranges
     * that still need a GeoJSON stretch fallback ({@link AssetTileService#hasRangeRows}).
     * FWD is never a stretch fallback: it is uploaded/stored as a line and tiled as one.
     */
    @GetMapping("/api/assets/{type}/tile-info")
    public Map<String, Object> tileInfo(@PathVariable String type,
                                        @RequestParam(value = "period_id", required = false) Integer periodId) {
        String t = type.toLowerCase();
        boolean tiled = tileable(t);
        boolean stretch = tiled && !"fwd".equals(t) && tiles.hasRangeRows(t, periodId);
        return Map.of("tiled", tiled, "stretch", stretch);
    }

    /**
     * One tile of one asset type. Unknown/untileable types are 404.
     */
    @GetMapping(value = "/api/assets/{type}/tiles/{z}/{x}/{y}.mvt", produces = MVT)
    public ResponseEntity<byte[]> tile(@PathVariable String type,
                                        @PathVariable int z,
                                        @PathVariable int x,
                                        @PathVariable int y,
                                        @RequestParam(value = "period_id", required = false) Integer periodId,
                                        @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        String t = type.toLowerCase();
        if (!tileable(t)) return ResponseEntity.notFound().build();

        boolean isFwd = "fwd".equals(t);
        TileCoordinate coord;
        try {
            coord = TileCoordinate.of(z, x, y, isFwd ? fwdTiles.maxZoom() : tiles.maxZoom());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        byte[] body = isFwd ? fwdTiles.tile(coord, periodId) : tiles.tile(coord, t, periodId);
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
