package com.fist.rmms_backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Place-name lookup for the map's "find a place" box, proxied through the server.
 *
 * <h2>Why the server and not the browser</h2>
 * The browser could call Nominatim directly — it sends CORS headers — but going
 * through here buys three things that matter for this deployment:
 * <ul>
 *   <li>It works on the PWD office networks that reach KLRAMS but not arbitrary
 *       third-party hosts, which is the same constraint that shapes the viewer's
 *       basemap behaviour.</li>
 *   <li>Staff IP addresses and their typed queries are not handed to a third party
 *       one browser at a time; one server-side identity makes the call.</li>
 *   <li>Nominatim's usage policy asks for an identifying User-Agent and at most one
 *       request a second. A single server can honour both; thirty browsers cannot
 *       coordinate to.</li>
 * </ul>
 *
 * <p>Results are cached and outbound calls throttled, so repeat searches for the
 * same town cost nothing. The endpoint is configurable ({@code app.geocode.*}) so
 * the RMMS cell can point it at a self-hosted Nominatim, or switch it off entirely,
 * without a code change — and coordinate search in the viewer keeps working either
 * way, because that is parsed in the browser and needs no service at all.
 */
@RestController
public class GeocodeController {

    private static final Logger log = LoggerFactory.getLogger(GeocodeController.class);

    private static final int MAX_QUERY_CHARS = 120;
    private static final int MAX_CACHE_ENTRIES = 500;
    /** Nominatim's public policy: no more than one request per second. */
    private static final long MIN_INTERVAL_MS = 1100;
    /** Never make a request thread wait longer than this for the throttle. */
    private static final long MAX_THROTTLE_WAIT_MS = 3000;

    /**
     * Bias results towards Kerala: left, top, right, bottom in degrees. Not
     * {@code bounded}, so a search for a place outside the state still resolves —
     * it just ranks below the local one when names collide, and there are a lot of
     * colliding place names in India.
     */
    private static final String KERALA_VIEWBOX = "74.8,12.9,77.6,8.1";

    private final boolean enabled;
    private final String endpoint;
    private final String userAgent;

    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /* Insertion-ordered so the oldest entry is the one evicted at the cap. */
    private final LinkedHashMap<String, List<Map<String, Object>>> cache = new LinkedHashMap<>();
    private long lastCallAt = 0;

    public GeocodeController(
            @Value("${app.geocode.enabled:true}") boolean enabled,
            @Value("${app.geocode.url:https://nominatim.openstreetmap.org/search}") String endpoint,
            // Nominatim requires a User-Agent that identifies the application. A
            // contact URL satisfies that; deployments may add an address here.
            @Value("${app.geocode.user-agent:KLRAMS/1.0 (Kerala PWD road asset management)}") String userAgent) {
        this.enabled = enabled;
        this.endpoint = endpoint;
        this.userAgent = userAgent;
    }

    /**
     * Places matching {@code q}, best first.
     *
     * <p>Always 200 with a list — an empty one when the service is off, unreachable
     * or has nothing. A geocoder being down is not an error the user can act on, and
     * the box it feeds also accepts coordinates, which never touch this endpoint.
     */
    @GetMapping("/api/geocode")
    public List<Map<String, Object>> search(@RequestParam("q") String q) {
        String query = q == null ? "" : q.trim();
        if (!enabled || query.length() < 3) return List.of();
        if (query.length() > MAX_QUERY_CHARS) query = query.substring(0, MAX_QUERY_CHARS);

        String key = query.toLowerCase(Locale.ROOT);
        synchronized (cache) {
            List<Map<String, Object>> hit = cache.get(key);
            if (hit != null) return hit;
        }

        List<Map<String, Object>> results;
        try {
            results = lookup(query);
        } catch (Exception e) {
            // Logged, not surfaced: the caller gets an empty list and the UI says
            // "no match", which is the same thing from the user's point of view.
            log.warn("Geocode lookup failed for \"{}\": {}", query, e.toString());
            return List.of();
        }

        synchronized (cache) {
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                var it = cache.keySet().iterator();
                if (it.hasNext()) { it.next(); it.remove(); }
            }
            cache.put(key, results);
        }
        return results;
    }

    private List<Map<String, Object>> lookup(String query) throws Exception {
        throttle();

        /* The only caller-controlled part of the URL is the encoded q value, so this
           cannot be steered at another host — the endpoint itself comes from config,
           never from the request. */
        String url = endpoint
                + "?format=jsonv2"
                + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&countrycodes=in"
                + "&limit=8"
                + "&addressdetails=0"
                + "&viewbox=" + KERALA_VIEWBOX;

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(6))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            log.warn("Geocode service returned HTTP {}", res.statusCode());
            return List.of();
        }

        JsonNode arr = om.readTree(res.body());
        List<Map<String, Object>> out = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                double lat = n.path("lat").asDouble(Double.NaN);
                double lon = n.path("lon").asDouble(Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", n.path("display_name").asText(""));
                m.put("kind", n.path("type").asText(""));
                m.put("lat", lat);
                m.put("lng", lon);
                // boundingbox is [south, north, west, east] — handed on as-is so the
                // viewer can frame a town rather than drop a pin at its centroid.
                JsonNode bb = n.path("boundingbox");
                if (bb.isArray() && bb.size() == 4) {
                    m.put("bbox", List.of(bb.get(2).asDouble(), bb.get(0).asDouble(),
                                          bb.get(3).asDouble(), bb.get(1).asDouble()));
                }
                out.add(m);
            }
        }
        return out;
    }

    /** Space outbound calls at least {@link #MIN_INTERVAL_MS} apart. */
    private synchronized void throttle() throws InterruptedException {
        long now = System.currentTimeMillis();
        long wait = Math.min(MAX_THROTTLE_WAIT_MS, lastCallAt + MIN_INTERVAL_MS - now);
        if (wait > 0) Thread.sleep(wait);
        lastCallAt = System.currentTimeMillis();
    }
}
