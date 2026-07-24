package com.fist.rmms_backend;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.TimeUnit;

/**
 * Serves road centrelines as GeoJSON. Properties include EVERY shapefile column
 * (via to_jsonb, so no column name is hard-coded and a rename can't break it),
 * plus convenience aliases road/name/len used by the map for geometry + sync.
 *
 * The road network is effectively static, so the assembled GeoJSON is built once
 * and cached in memory. Subsequent requests are served instantly from the cache.
 * After uploading new roads, POST /api/roads/geojson/refresh (or restart the app)
 * to rebuild the cache.
 */
@RestController
@RequestMapping("/api/roads")
public class RoadController {

    private final JdbcTemplate jdbc;
    private volatile String cachedGeojson;
    private volatile String cachedEtag;

    public RoadController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Builds the cache if it isn't already warm. Called on startup so the first real request is fast. */
    public void warm() {
        if (cachedGeojson == null) {
            synchronized (this) {
                if (cachedGeojson == null) {
                    cachedGeojson = buildGeojson();
                    cachedEtag = GeoJsonResponse.contentTag(cachedGeojson);
                }
            }
        }
    }

    @GetMapping(value = "/geojson", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> geojson(@RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        String body = cachedGeojson, tag = cachedEtag;
        if (body == null || tag == null) {
            synchronized (this) {
                if (cachedGeojson == null) {
                    cachedGeojson = buildGeojson();
                    cachedEtag = GeoJsonResponse.contentTag(cachedGeojson);
                }
                body = cachedGeojson;
                tag = cachedEtag;
            }
        }
        // no-cache + ETag: the browser revalidates each load, so newly uploaded
        // roads show on a normal reload once the cache is refreshed (see
        // /geojson/refresh, called automatically after an upload); when the data
        // is unchanged the ETag turns that revalidation into an empty 304.
        return GeoJsonResponse.conditional(body, tag, ifNoneMatch);
    }

    /** Clears the cache so the next request rebuilds from the DB. Call after uploading roads. */
    @PostMapping("/geojson/refresh")
    public String refresh() {
        synchronized (this) {
            cachedGeojson = null;
            cachedEtag = null;
        }
        return "{\"ok\":true,\"message\":\"road geojson cache cleared\"}";
    }

    private String buildGeojson() {
        String sql = """
            SELECT json_build_object(
                'type','FeatureCollection',
                'features', COALESCE(json_agg(
                    json_build_object(
                        'type','Feature',
                        'geometry', ST_AsGeoJSON(r.geom, 6)::json,
                        'properties', (to_jsonb(r) - 'geom')
                            || jsonb_build_object(
                                 'road', r."Section_La",
                                 'name', r."Road_Name",
                                 'len',  r."Measrd_Len"
                               )
                    )
                ), '[]'::json)
            )::text
            FROM roads r
            WHERE r.geom IS NOT NULL
            """;
        return jdbc.queryForObject(sql, String.class);
    }
}
