package com.fist.rmms_backend;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves user-created and temporary layers as Mapbox Vector Tiles.
 *
 * <p>Additive, like every other tile controller here: the
 * {@code /api/layer-data/{id}/geojson} endpoint is untouched and still used for
 * export and analysis. Kept apart from {@link LayerDataController} for the
 * reason {@link IriTileController} sits apart from its build controller — a
 * binary content type does not belong branching inside JSON endpoints.
 */
@RestController
public class UserLayerTileController {

    private static final String MVT = "application/vnd.mapbox-vector-tile";

    private final UserLayerTileService tiles;

    public UserLayerTileController(UserLayerTileService tiles) {
        this.tiles = tiles;
    }

    /**
     * One tile of a user layer.
     *
     * <p>400 on a malformed address, 204 when the address is valid but there is
     * nothing to draw, otherwise the tile with a weak ETag for revalidation.
     */
    @GetMapping(value = "/api/layer-data/{layerId}/tiles/{z}/{x}/{y}.mvt", produces = MVT)
    public ResponseEntity<byte[]> tile(@PathVariable int layerId,
                                       @PathVariable int z,
                                       @PathVariable int x,
                                       @PathVariable int y,
                                       @RequestParam(value = "period_id", required = false) Integer periodId,
                                       Authentication auth,
                                       @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        TileCoordinate coord;
        try {
            coord = TileCoordinate.of(z, x, y, tiles.maxZoom());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        String user = (auth == null) ? "unknown" : auth.getName();
        byte[] body = tiles.tile(layerId, coord, user, periodId);
        if (body == null) return ResponseEntity.noContent().build();

        String tag = GeoJsonResponse.contentTag(body);
        if (GeoJsonResponse.matches(ifNoneMatch, tag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(tag).cacheControl(CacheControl.noCache()).build();
        }
        // no-cache: re-importing a layer rewrites every tile and there is no
        // build-version counter in the URL. ETag revalidation keeps a fresh
        // import from going stale while repeat pans cost only a 304.
        return ResponseEntity.ok()
                .eTag(tag).cacheControl(CacheControl.noCache())
                .header("Content-Type", MVT)
                .body(body);
    }
}
