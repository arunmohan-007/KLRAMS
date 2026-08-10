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

    /**
     * ONE road's full, unclipped geometry — for NSV, and only for NSV.
     *
     * <p>{@code 12-nsv-video.js} runs {@code turf.nearestPointOnLine} over the entire road
     * LineString to convert a video-frame click into a chainage. A vector tile carries only the
     * geometry clipped to that tile's boundary, so feeding tile-sourced geometry into that same
     * calculation would silently miscompute chainage near every tile edge. This endpoint is the
     * escape hatch: the map's condition/road RENDERING can come from tiles, while NSV fetches the
     * one road it is actually playing, in full, on demand.
     *
     * <p>Deliberately uncached and uncompressed-by-cache — one road is a few KB, nowhere near the
     * multi-MB payloads {@link GeoJsonResponse}'s ETag machinery exists for, and NSV needs this
     * exactly once per road selected, not on every frame.
     *
     * <p>{@code section} is a query parameter, not a {@code /roads/{id}/geojson} path segment,
     * because a section label is not a safe path segment: labels look like
     * {@code KPWD/MDR/501010103/17} — a literal {@code /} in the value — and Tomcat rejects an
     * encoded slash in a path segment by default. A query parameter has no such restriction; the
     * value is bound, never concatenated into SQL either way.
     */
    @GetMapping(value = "/one/geojson", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> oneRoadGeojson(@RequestParam("section") String sectionLabel) {
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
            WHERE r.geom IS NOT NULL AND r."Section_La" = ?
            """;
        String body = jdbc.queryForObject(sql, String.class, sectionLabel);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
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
