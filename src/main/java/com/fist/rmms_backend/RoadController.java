package com.fist.rmms_backend;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private volatile String cachedIndex;
    private volatile String cachedIndexEtag;

    public RoadController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Builds the caches if they aren't already warm. Called on startup so the first real request
     *  is fast — including the index, now that tile mode makes it the FIRST thing a login fetches
     *  rather than a side effect of the full GeoJSON already being in memory. */
    public void warm() {
        if (cachedGeojson == null) {
            synchronized (this) {
                if (cachedGeojson == null) {
                    cachedGeojson = buildGeojson();
                    cachedEtag = GeoJsonResponse.contentTag(cachedGeojson);
                }
            }
        }
        if (cachedIndex == null) {
            synchronized (this) {
                if (cachedIndex == null) {
                    cachedIndex = buildIndex();
                    cachedIndexEtag = GeoJsonResponse.contentTag(cachedIndex);
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
            cachedIndex = null;
            cachedIndexEtag = null;
        }
        return "{\"ok\":true,\"message\":\"road geojson cache cleared\"}";
    }

    /**
     * Every road's metadata, with NO geometry — search, the network attribute filter and the
     * asset register table all need to scan every road, but none of them read a coordinate.
     * Coordinates are the entire cost of the road network's payload (measured: 4.0 MB full
     * GeoJSON versus 92 KB for this same query with geometry left out, on a 133-road test
     * network — the ratio only widens as roads get longer and carry more vertices), so this is
     * cheap enough to load unconditionally even in tile mode, the same way the old code loaded
     * the whole GeoJSON unconditionally — it is just 40x lighter.
     *
     * <p>A plain JSON array, not a FeatureCollection: there is no geometry to make it a
     * "Feature", and every consumer (search, the filter, the register) wants an array of
     * property bags, not something it has to reach into {@code .properties} for.
     */
    @GetMapping(value = "/index", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> index(@RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        String body = cachedIndex, tag = cachedIndexEtag;
        if (body == null || tag == null) {
            synchronized (this) {
                if (cachedIndex == null) {
                    cachedIndex = buildIndex();
                    cachedIndexEtag = GeoJsonResponse.contentTag(cachedIndex);
                }
                body = cachedIndex;
                tag = cachedIndexEtag;
            }
        }
        return GeoJsonResponse.conditional(body, tag, ifNoneMatch);
    }

    private String buildIndex() {
        String sql = """
            SELECT COALESCE(json_agg(
                (to_jsonb(r) - 'geom')
                    || jsonb_build_object(
                         'road', r."Section_La",
                         'name', r."Road_Name",
                         'len',  r."Measrd_Len"
                       )
            ), '[]'::json)::text
            FROM roads r
            WHERE r.geom IS NOT NULL
            """;
        return jdbc.queryForObject(sql, String.class);
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

    /** Hard cap on the {@code section=} scope list, so a filtered road can never build an
     *  unbounded IN-list. Far above any real road's section count (the largest here is ~40). */
    private static final int MAX_SCOPE_SECTIONS = 500;

    /**
     * Where on the map is ROAD chainage X? — the Chainage Locator's one query.
     *
     * <p>The chainage an engineer quotes ("2 600 on the Kollam–Punalur road") is measured along
     * the WHOLE road, but geometry is stored per SECTION, and each section carries only the slice
     * of road chainage it covers, in {@code Rd_Str_cha}..{@code Rd_End_cha}. So locating it is two
     * steps, and the first is the one that is easy to skip:
     *
     * <ol>
     *   <li><b>Find the section</b> whose road-chainage range contains the value — 2 600 falls in
     *       the section running 2 000..3 000.</li>
     *   <li><b>Offset into that section</b>: 2 600 − 2 000 = 600 m, placed by linear reference
     *       along that section's centreline.</li>
     * </ol>
     *
     * <p>Treating the typed number as a section-local chainage instead — interpolating 2 600 m
     * into a 1 000 m section — is precisely the bug this endpoint exists to prevent; it would
     * clamp to the section's far end and be silently wrong for every section after the first.
     *
     * <p>The linear reference is deliberately the same calibration the rest of the system already
     * uses for condition segments, assets and traffic stations (CLAUDE.md): the fraction is
     * {@code (ch − Rd_Str_cha) / (Rd_End_cha − Rd_Str_cha)}, which IS "within-section chainage ÷
     * reference length" whenever the chainage columns are present, fed to
     * {@code ST_LineInterpolatePoint}. Any other formula would drop the pin somewhere the
     * condition segment for that same chainage is not drawn, which is the one thing a locator
     * must never do. A section whose two chainage columns are absent or equal carries no
     * road-chainage range at all, so it is not a candidate — there is nothing to search it by.
     *
     * <p>EVERY section carrying the chainage is returned, not just one: link roads and dual
     * carriageways genuinely give two (or more) sections the same road chainage, and both are the
     * right answer — the caller pins both. The single exception is the section-join artifact,
     * where a chainage lands on the exact boundary between consecutive sections (3 000 = end of
     * one, start of the next); there the section that merely ENDS there is dropped in favour of
     * the one that starts there, because that is one place, not two.
     *
     * @param roadName {@code Road_Name}, the road as a whole (not a section label).
     * @param chainage road chainage in metres.
     * @param sections optional scope: when the viewer has a Road Network filter applied, only the
     *                 surviving section labels are searched, so the locator can never place a pin
     *                 on a section the user has filtered off the map.
     */
    @GetMapping(value = "/chainage/locate", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> locateChainage(@RequestParam("name") String roadName,
                                              @RequestParam("chainage") double chainage,
                                              @RequestParam(value = "section", required = false) List<String> sections) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("road_name", roadName);
        out.put("chainage", chainage);

        List<String> scope = (sections == null) ? List.of()
                : sections.stream().filter(s -> s != null && !s.isBlank()).limit(MAX_SCOPE_SECTIONS).toList();
        String scopeSql = scope.isEmpty() ? ""
                : " AND r.\"Section_La\" IN (" + String.join(",", java.util.Collections.nCopies(scope.size(), "?")) + ")";

        List<Object> args = new ArrayList<>();
        args.add(chainage);
        args.add(roadName);
        args.addAll(scope);

        // ST_GeometryN(ST_LineMerge(...), 1) is a no-op for the LINESTRING case (PostGIS returns
        // the geometry itself when it is not a collection) and keeps a road whose parts will not
        // merge from raising "line_interpolate_point: 1st arg isn't a line" — the same assumption
        // TrafficController already places stations under, minus the hard failure.
        String sql = """
            WITH p AS (SELECT CAST(? AS double precision) AS ch),
            s AS (
                SELECT r."Section_La"                  AS section,
                       r."Rd_Str_cha"::double precision AS str_ch,
                       r."Rd_End_cha"::double precision AS end_ch,
                       ST_GeometryN(ST_LineMerge(r.geom), 1) AS line
                FROM roads r
                WHERE r.geom IS NOT NULL AND r."Road_Name" = ?%s
            ),
            m AS (
                SELECT s.section, s.str_ch, s.end_ch, s.line, p.ch,
                       LEAST(s.str_ch, s.end_ch)    AS lo,
                       GREATEST(s.str_ch, s.end_ch) AS hi,
                       (p.ch - s.str_ch) / (s.end_ch - s.str_ch) AS frac
                FROM s CROSS JOIN p
                WHERE s.str_ch IS NOT NULL AND s.end_ch IS NOT NULL AND s.str_ch <> s.end_ch
            )
            SELECT section, str_ch, end_ch, lo, hi, frac,
                   ST_X(ST_LineInterpolatePoint(line, GREATEST(LEAST(frac, 1.0), 0.0))) AS lng,
                   ST_Y(ST_LineInterpolatePoint(line, GREATEST(LEAST(frac, 1.0), 0.0))) AS lat
            FROM m
            WHERE ch >= lo AND ch <= hi
            ORDER BY (ch >= hi), section
            """.formatted(scopeSql);

        List<Map<String, Object>> rows = jdbc.queryForList(sql, args.toArray());
        if (!rows.isEmpty()) {
            // Section-join artifact only: a section whose range ENDS exactly here is dropped when
            // another match STARTS exactly here — that is one point on the network, described
            // twice. Sections that overlap for any other reason (link roads, A/B carriageways)
            // are all kept, because the chainage really does sit on each of them.
            final double EPS = 1e-6;
            boolean anyStartsHere = rows.stream()
                    .anyMatch(r -> Math.abs(((Number) r.get("lo")).doubleValue() - chainage) < EPS);
            List<Map<String, Object>> matches = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                boolean endsHere = Math.abs(((Number) r.get("hi")).doubleValue() - chainage) < EPS;
                if (endsHere && anyStartsHere) continue;
                double strCh = ((Number) r.get("str_ch")).doubleValue();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("section", r.get("section"));
                m.put("lng", r.get("lng"));
                m.put("lat", r.get("lat"));
                m.put("road_start", strCh);
                m.put("road_end", ((Number) r.get("end_ch")).doubleValue());
                m.put("offset", chainage - strCh);          // the "+600" the engineer reads off
                m.put("fraction", r.get("frac"));
                matches.add(m);
            }
            out.put("ok", true);
            out.put("matches", matches);
            return out;
        }

        // No section carries this chainage — say what the road actually covers rather than
        // just "not found", because the usual cause is a typo or km entered as metres.
        String rangeSql = """
            SELECT MIN(LEAST(r."Rd_Str_cha"::double precision, r."Rd_End_cha"::double precision))    AS lo,
                   MAX(GREATEST(r."Rd_Str_cha"::double precision, r."Rd_End_cha"::double precision)) AS hi,
                   COUNT(*) AS n
            FROM roads r
            WHERE r.geom IS NOT NULL AND r."Road_Name" = ?%s
              AND r."Rd_Str_cha" IS NOT NULL AND r."Rd_End_cha" IS NOT NULL
              AND r."Rd_Str_cha"::double precision <> r."Rd_End_cha"::double precision
            """.formatted(scopeSql);
        List<Object> rangeArgs = new ArrayList<>();
        rangeArgs.add(roadName);
        rangeArgs.addAll(scope);
        Map<String, Object> range = jdbc.queryForMap(rangeSql, rangeArgs.toArray());
        long n = ((Number) range.get("n")).longValue();

        out.put("ok", false);
        out.put("reason", n == 0 ? "no_chainage" : "out_of_range");
        out.put("sections", n);
        out.put("min", range.get("lo"));
        out.put("max", range.get("hi"));
        return out;
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
