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
 * <h2>Why two providers</h2>
 * The box is a type-ahead: it fires after three characters and again on every
 * keystroke. Nominatim's {@code /search} is a <em>whole-word</em> geocoder — it
 * has no prefix matching, so "vyt" returns nothing at all while "Vythiri" returns
 * the town. Typing anything but the complete, correctly spelled name therefore
 * read as "No matching place", which is what made the search look broken.
 *
 * <p>Photon is the OSM-based geocoder built for exactly this: it indexes for
 * prefix search, so "vyt" → Vyttila, "vythir" → Vythiri, "kalpett" → Kalpetta.
 * It is queried first; Nominatim stays as the fallback for when Photon is
 * unreachable or has nothing, so the deployment keeps working if either host is
 * blocked. Either can be switched off on its own ({@code app.geocode.photon-url},
 * {@code app.geocode.url}) or the whole thing disabled — and coordinate search in
 * the viewer keeps working regardless, because that is parsed in the browser and
 * needs no service at all.
 *
 * <p>Results are cached and outbound calls throttled, so repeat searches for the
 * same town cost nothing — which matters more now that a type-ahead can ask about
 * every prefix of a word on the way to it.
 */
@RestController
public class GeocodeController {

    private static final Logger log = LoggerFactory.getLogger(GeocodeController.class);

    private static final int MAX_QUERY_CHARS = 120;
    private static final int MAX_CACHE_ENTRIES = 500;
    /** Nominatim's public policy: no more than one request per second. */
    private static final long NOMINATIM_INTERVAL_MS = 1100;
    /**
     * Photon publishes no hard rate limit, only a fair-use request. A type-ahead
     * that had to wait a second between calls would be useless, so this is set to
     * roughly the debounce the viewer already applies (350 ms) — one server, one
     * user typing, nothing like a crawl.
     */
    private static final long PHOTON_INTERVAL_MS = 250;
    /** Never make a request thread wait longer than this for the throttle. */
    private static final long MAX_THROTTLE_WAIT_MS = 3000;

    /** How many raw rows to ask each provider for, before dedupe trims to {@link #MAX_RESULTS}. */
    private static final int PROVIDER_LIMIT = 15;
    private static final int MAX_RESULTS = 8;

    /**
     * Bias results towards Kerala: left, top, right, bottom in degrees. Not
     * {@code bounded}, so a search for a place outside the state still resolves —
     * it just ranks below the local one when names collide, and there are a lot of
     * colliding place names in India.
     */
    private static final String KERALA_VIEWBOX = "74.8,12.9,77.6,8.1";
    /** The same bias for Photon, which takes a centre point rather than a box. */
    private static final String KERALA_LAT = "10.5";
    private static final String KERALA_LON = "76.3";

    private final boolean enabled;
    private final String endpoint;
    private final String photonEndpoint;
    private final String userAgent;

    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /* Insertion-ordered so the oldest entry is the one evicted at the cap. */
    private final LinkedHashMap<String, List<Map<String, Object>>> cache = new LinkedHashMap<>();
    private final Object nominatimLock = new Object();
    private final Object photonLock = new Object();
    private long lastNominatimAt = 0;
    private long lastPhotonAt = 0;

    public GeocodeController(
            @Value("${app.geocode.enabled:true}") boolean enabled,
            @Value("${app.geocode.url:https://nominatim.openstreetmap.org/search}") String endpoint,
            @Value("${app.geocode.photon-url:https://photon.komoot.io/api/}") String photonEndpoint,
            // Nominatim requires a User-Agent that identifies the application. A
            // contact URL satisfies that; deployments may add an address here.
            @Value("${app.geocode.user-agent:KLRAMS/1.0 (Kerala PWD road asset management)}") String userAgent) {
        this.enabled = enabled;
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.photonEndpoint = photonEndpoint == null ? "" : photonEndpoint.trim();
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

    /**
     * Photon first (it can prefix-match, which is what a type-ahead needs), then
     * Nominatim if Photon is switched off, unreachable, or has nothing to say.
     * A failure in one provider is logged and falls through rather than aborting:
     * the point of having two is that one being blocked is survivable.
     */
    private List<Map<String, Object>> lookup(String query) throws Exception {
        if (!photonEndpoint.isEmpty()) {
            try {
                List<Map<String, Object>> hits = photon(query);
                if (!hits.isEmpty()) return hits;
            } catch (InterruptedException ie) {
                throw ie;
            } catch (Exception e) {
                log.warn("Photon lookup failed for \"{}\": {}", query, e.toString());
            }
        }
        if (endpoint.isEmpty()) return List.of();
        return nominatim(query);
    }

    /* ---- Photon: prefix-capable, used first ---- */

    private List<Map<String, Object>> photon(String query) throws Exception {
        throttlePhoton();

        /* The only caller-controlled part of the URL is the encoded q value, so this
           cannot be steered at another host — the endpoint itself comes from config,
           never from the request. lat/lon bias towards Kerala without bounding, for
           the same reason the Nominatim call uses viewbox and not bounded=1: a place
           outside the state should still resolve, just below the local one. */
        String url = photonEndpoint
                + (photonEndpoint.contains("?") ? "&" : "?")
                + "q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&limit=" + PROVIDER_LIMIT
                + "&lang=en"
                + "&lat=" + KERALA_LAT
                + "&lon=" + KERALA_LON;

        String body = get(url);
        if (body.isEmpty()) return List.of();
        JsonNode feats = om.readTree(body).path("features");
        if (!feats.isArray()) return List.of();

        List<Map<String, Object>> out = new ArrayList<>();
        var seen = new java.util.HashSet<String>();
        for (JsonNode f : feats) {
            JsonNode p = f.path("properties");
            // Photon has no country filter, so India is enforced here rather than in
            // the query. Anything else is a same-named place on another continent.
            if (!"IN".equalsIgnoreCase(p.path("countrycode").asText(""))) continue;

            JsonNode c = f.path("geometry").path("coordinates");
            if (!c.isArray() || c.size() < 2) continue;
            double lon = c.get(0).asDouble(Double.NaN), lat = c.get(1).asDouble(Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) continue;

            String name = p.path("name").asText("");
            if (name.isBlank()) continue;

            /* A town is usually in OSM three times over — the place node, the
               administrative boundary and sometimes a relation — and Photon returns
               all of them. Identical name in the identical district is one place as
               far as the search box is concerned. */
            String label = displayName(p, name);
            if (!seen.add(label.toLowerCase(Locale.ROOT))) continue;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", label);
            // osm_value is the specific kind ("town", "village", "river"); osm_key
            // would only say "place" or "waterway", which tells the user nothing.
            m.put("kind", p.path("osm_value").asText(""));
            m.put("lat", lat);
            m.put("lng", lon);
            // Photon's extent is [west, north, east, south]; the viewer wants
            // [west, south, east, north], the same order Nominatim's box is mapped to.
            JsonNode ext = p.path("extent");
            if (ext.isArray() && ext.size() == 4) {
                m.put("bbox", List.of(ext.get(0).asDouble(), ext.get(3).asDouble(),
                                      ext.get(2).asDouble(), ext.get(1).asDouble()));
            }
            out.add(m);
            if (out.size() >= MAX_RESULTS) break;
        }
        return out;
    }

    /**
     * Photon returns the address in separate fields; the viewer's list splits a
     * single comma string into head (place) and tail (context), so one is rebuilt
     * here in Nominatim's shape. District/state only — house numbers and postcodes
     * do not help tell two Kerala results apart.
     */
    private static String displayName(JsonNode p, String name) {
        StringBuilder sb = new StringBuilder(name);
        var seen = new java.util.HashSet<String>();
        seen.add(name.toLowerCase(Locale.ROOT));
        for (String field : new String[]{"district", "city", "county", "state"}) {
            String v = p.path(field).asText("");
            if (v.isBlank() || !seen.add(v.toLowerCase(Locale.ROOT))) continue;
            sb.append(", ").append(v);
        }
        return sb.append(", India").toString();
    }

    /* ---- Nominatim: whole-word only, kept as the fallback ---- */

    private List<Map<String, Object>> nominatim(String query) throws Exception {
        throttleNominatim();

        String url = endpoint
                + "?format=jsonv2"
                + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&countrycodes=in"
                + "&limit=" + MAX_RESULTS
                + "&addressdetails=0"
                + "&viewbox=" + KERALA_VIEWBOX;

        String body = get(url);
        if (body.isEmpty()) return List.of();
        JsonNode arr = om.readTree(body);
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

    /** One GET, identified and time-boxed. Returns "" on any non-200. */
    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(6))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            log.warn("Geocode service returned HTTP {}", res.statusCode());
            return "";
        }
        return res.body();
    }

    /* Separate throttles on separate locks: the two providers ask for very
       different spacing, and a Photon call must not queue behind a thread sleeping
       out Nominatim's full second. */

    private void throttleNominatim() throws InterruptedException {
        synchronized (nominatimLock) {
            long wait = Math.min(MAX_THROTTLE_WAIT_MS,
                    lastNominatimAt + NOMINATIM_INTERVAL_MS - System.currentTimeMillis());
            if (wait > 0) Thread.sleep(wait);
            lastNominatimAt = System.currentTimeMillis();
        }
    }

    private void throttlePhoton() throws InterruptedException {
        synchronized (photonLock) {
            long wait = Math.min(MAX_THROTTLE_WAIT_MS,
                    lastPhotonAt + PHOTON_INTERVAL_MS - System.currentTimeMillis());
            if (wait > 0) Thread.sleep(wait);
            lastPhotonAt = System.currentTimeMillis();
        }
    }
}
