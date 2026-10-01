package com.fist.rmms_backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * KLRAMS — Traffic stations persistence (PostgreSQL).
 *
 * Replaces the browser-localStorage store used by the Data Console and the
 * map viewer. Two tables are created automatically on startup, so no manual
 * SQL is required. Endpoints (all under the existing login / no CSRF):
 *
 *   POST /api/traffic/stations        replace all stations  (JSON array)
 *   POST /api/traffic/counts          replace all counts    (JSON object keyed by station name)
 *   GET  /api/traffic/store           full store {v,savedAt,stations:[...],counts:{...}}
 *   GET  /api/traffic/stations/geojson  stations as a GeoJSON FeatureCollection
 *   POST /api/traffic/clear           delete everything
 */
@RestController
@RequestMapping("/api/traffic")
public class TrafficController {

    private static final Logger log = LoggerFactory.getLogger(TrafficController.class);

    private final JdbcTemplate jdbc;
    private final SurveyPeriodService periods;
    private final PlacementService placement;
    private final ObjectMapper om = new ObjectMapper();

    private final RoadColumns roadColumns;

    public TrafficController(JdbcTemplate jdbc, SurveyPeriodService periods, PlacementService placement,
                             RoadColumns roadColumns) {
        this.roadColumns = roadColumns;
        this.jdbc = jdbc;
        this.periods = periods;   // also orders startup: survey_periods migration runs first
        this.placement = placement;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS traffic_stations (" +
                "name TEXT, road TEXT, section TEXT, chainage DOUBLE PRECISION, " +
                "lat DOUBLE PRECISION, lng DOUBLE PRECISION, xsp TEXT, updated_at TIMESTAMP DEFAULT now(), " +
                "period_id INTEGER)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS traffic_counts (" +
                "name TEXT, data JSONB, updated_at TIMESTAMP DEFAULT now(), period_id INTEGER)");
        /* Every count row of an upload, kept so a re-upload can REPLACE the rows with the same
           Station Name + Section Label + Date + Time (+ Direction, + lane) and ADD the rest; the
           per-station summary in traffic_counts is rebuilt from these rows. */
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS traffic_count_rows (" +
                    "id SERIAL PRIMARY KEY, period_id INTEGER NOT NULL, station_name TEXT NOT NULL, " +
                    "section_label TEXT NOT NULL DEFAULT '', count_date TEXT NOT NULL, count_time TEXT NOT NULL DEFAULT '', " +
                    "direction TEXT NOT NULL DEFAULT '', xsp TEXT NOT NULL DEFAULT '', cells JSONB NOT NULL, " +
                    "updated_at TIMESTAMP DEFAULT now())");
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS traffic_count_rows_key_ux ON traffic_count_rows " +
                    "(period_id, station_name, section_label, count_date, count_time, direction, xsp)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS traffic_count_rows_station_idx ON traffic_count_rows (period_id, station_name)");
        } catch (Exception e) {
            log.error("traffic_count_rows setup failed — count uploads will not be able to merge", e);
        }
        // A station name repeats across survey periods, so identity is
        // (name, period_id). Older databases had name as the primary key —
        // swap it for a surrogate id + a composite unique index (idempotent).
        for (String t : List.of("traffic_stations", "traffic_counts")) {
            try {
                jdbc.execute("ALTER TABLE " + t + " ADD COLUMN IF NOT EXISTS period_id integer");
                periods.ensureSurrogatePk(t, "name");
                periods.dedupeKeepingLatest(t, "name", "period_id");
                jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS " + t + "_name_period_ux ON " + t + "(name, period_id)");
                jdbc.execute("CREATE INDEX IF NOT EXISTS " + t + "_period_idx ON " + t + "(period_id)");
            } catch (Exception e) {
                // Never let a schema-migration hiccup on one table take down the whole
                // app at boot — that would break every module, not just traffic.
                log.error("Traffic schema migration failed for {} — traffic endpoints may be degraded, " +
                        "but the app will keep starting", t, e);
            }
        }

        // Stored geometry, exactly as road_assets does it for culverts / sub-grade soil /
        // bituminous core: the chainage linear reference is resolved ONCE at import and the
        // resulting point is kept, instead of being recomputed on every request. Placement
        // is unchanged — same ST_LineInterpolatePoint over the same reference length — only
        // *when* it runs. lat/lng stay display-only columns and are still never used to
        // place a station (see placeStations()).
        try {
            jdbc.execute("ALTER TABLE traffic_stations ADD COLUMN IF NOT EXISTS geom geometry(Point,4326)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS traffic_stations_geom_idx ON traffic_stations USING GIST(geom)");
            // Rows imported before the column existed have no point yet. Deliberately NOT
            // followed by the unmatched-row delete that saveStations() runs: a backfill is
            // not an import, and silently deleting existing rows at boot is not something
            // a restart should ever do.
            int n = placeStations(null);
            if (n > 0) log.info("traffic_stations: stored linear-referenced geom for {} existing rows", n);
        } catch (Exception e) {
            log.error("traffic_stations geom migration failed — traffic endpoints keep working, " +
                    "stations without a stored point are just not returned", e);
        }
    }

    private int placeStations(Integer periodId) {
        return placement.placeTrafficStations(periodId);
    }

    /** Add/update stations by (name, survey period) — additive. Body: JSON array of
     *  {name,road,section,ch,lat,lng,xsp}. Stations of other periods are never touched.
     *
     *  Placement is by chainage, not lat/lng (see stationsGeojson()), so a row whose
     *  section text doesn't match any roads."Section_La" can never be placed — it is
     *  rejected here rather than imported silently, and named back to the caller so
     *  the bad section label can be fixed at the source. */
    @PostMapping("/stations")
    public Map<String, Object> saveStations(@RequestBody String body,
                                            @RequestParam(value = "periodId", required = false) Integer periodId) throws Exception {
        if (periodId == null || !periods.exists(periodId))
            return Map.of("saved", 0, "error", "Select the survey period this data belongs to before importing.");
        JsonNode arr = om.readTree(body);
        int n = 0, addedStations = 0, replacedStations = 0;
        /* Stations are placed by linear reference, so Section Label must name a road. Check it
           BEFORE writing: the upsert below overwrites an existing station's section, and a
           mistyped label would otherwise replace a good station and then be deleted as
           unplaceable. A row that fails is skipped untouched and named back to the caller. */
        List<Map<String, Object>> rejected = new ArrayList<>();
        Set<String> knownSections = new HashSet<>();
        if (arr != null && arr.isArray()) {
            Set<String> wanted = new LinkedHashSet<>();
            for (JsonNode s : arr) { String sec = txt(s, "section"); if (sec != null && !sec.isEmpty()) wanted.add(sec); }
            if (!wanted.isEmpty()) {
                String secCol = roadColumns.col("r", LayerAttributeCatalog.SECTION_LABEL);
                List<String> list = new ArrayList<>(wanted);
                for (int i = 0; i < list.size(); i += 500) {
                    List<String> chunk = list.subList(i, Math.min(i + 500, list.size()));
                    knownSections.addAll(jdbc.queryForList("SELECT DISTINCT " + secCol + " FROM roads r WHERE " + secCol
                            + " IN (" + String.join(",", Collections.nCopies(chunk.size(), "?")) + ")",
                            String.class, chunk.toArray()));
                }
            }
        }
        Map<String, String> sectionInFile = new HashMap<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode s : arr) {
                String name = txt(s, "name");
                if (name == null || name.isEmpty()) continue;
                String secLabel = txt(s, "section");
                String why = (secLabel == null || secLabel.isEmpty()) ? "no Section Label"
                        : !knownSections.contains(secLabel) ? "Section Label not found in the Road Network"
                        : dbl(s, "ch") == null ? "no chainage" : null;
                /* A station is Section Label + Station Name, and counts/map join on the name, so one
                   name cannot sit on two sections in a period. Same name + same section replaces the
                   station; same name + a different section is a conflict and changes nothing. */
                if (why == null) {
                    String prev = sectionInFile.putIfAbsent(name, secLabel);
                    if (prev != null && !prev.equals(secLabel)) {
                        why = "station name is used for two sections in this file (" + prev + " and " + secLabel + ")";
                    } else {
                        List<String> have = jdbc.queryForList(
                                "SELECT section FROM traffic_stations WHERE name = ? AND period_id = ?",
                                String.class, name, periodId);
                        if (!have.isEmpty() && have.get(0) != null && !have.get(0).equals(secLabel))
                            why = "station name already exists in this survey period under Section Label " + have.get(0);
                    }
                }
                if (why != null) {
                    Map<String, Object> bad = new LinkedHashMap<>();
                    bad.put("name", name); bad.put("section", secLabel); bad.put("reason", why);
                    rejected.add(bad);
                    continue;
                }
                // geom is cleared on update, not carried over: this row's section or chainage
                // may have just changed, and a stored point from the previous import would
                // then be a stale position that the placement pass below would skip.
                Integer had = jdbc.queryForObject(
                        "SELECT count(*) FROM traffic_stations WHERE name = ? AND period_id = ?", Integer.class, name, periodId);
                if (had != null && had > 0) replacedStations++; else addedStations++;
                jdbc.update("INSERT INTO traffic_stations(name,road,section,chainage,lat,lng,xsp,period_id,updated_at) " +
                                "VALUES(?,?,?,?,?,?,?,?,now()) ON CONFLICT(name,period_id) DO UPDATE SET " +
                                "road=EXCLUDED.road,section=EXCLUDED.section,chainage=EXCLUDED.chainage," +
                                "lat=EXCLUDED.lat,lng=EXCLUDED.lng,xsp=EXCLUDED.xsp,geom=NULL,updated_at=now()",
                        name, txt(s, "road"), txt(s, "section"), dbl(s, "ch"),
                        dbl(s, "lat"), dbl(s, "lng"), txt(s, "xsp"), periodId);
                n++;
            }
        }

        // Store the linear-referenced point for everything just imported, then reject whatever
        // could not be placed — the same two steps, in the same order, as AssetController.
        // "Could not be placed" now means geom IS NULL, which covers a blank section, a blank
        // chainage, a section label matching no road, AND a matched road with no geometry;
        // the old text-only EXISTS check missed that last case and kept an unplaceable row.
        placeStations(periodId);
        List<Map<String, Object>> skipped = jdbc.queryForList(
            "SELECT name, section FROM traffic_stations WHERE period_id = ? AND geom IS NULL ORDER BY name", periodId);
        if (!skipped.isEmpty()) {
            jdbc.update("DELETE FROM traffic_stations WHERE period_id = ? AND geom IS NULL", periodId);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        List<Map<String, Object>> allSkipped = new ArrayList<>(rejected);
        for (Map<String, Object> sk : skipped) {
            Map<String, Object> bad = new LinkedHashMap<>(sk);
            bad.put("reason", "could not be placed on the road");
            allSkipped.add(bad);
        }
        res.put("added", addedStations);
        res.put("replaced", replacedStations);
        res.put("saved", n - skipped.size());
        res.put("skipped", allSkipped.size());
        res.put("skipped_stations", allSkipped);
        return res;
    }

    /** Add/update counts by (station name, survey period) — additive. Body: JSON object
     *  { "<station name>": {...}, ... }. Counts of other periods are never touched. */
    @PostMapping("/counts")
    public Map<String, Object> saveCounts(@RequestBody String body,
                                          @RequestParam(value = "periodId", required = false) Integer periodId) throws Exception {
        if (periodId == null || !periods.exists(periodId))
            return Map.of("saved", 0, "error", "Select the survey period this data belongs to before importing.");
        JsonNode obj = om.readTree(body);
        int n = 0;
        if (obj != null && obj.isObject()) {
            Iterator<String> it = obj.fieldNames();
            while (it.hasNext()) {
                String name = it.next();
                String json = om.writeValueAsString(obj.get(name));
                jdbc.update("INSERT INTO traffic_counts(name,data,period_id,updated_at) VALUES(?, ?::jsonb, ?, now()) " +
                        "ON CONFLICT(name,period_id) DO UPDATE SET data=EXCLUDED.data, updated_at=now()", name, json, periodId);
                n++;
            }
        }
        /* Counts are matched on station name. A name with no station in this period has no
           section and no position, so it is saved but reported. */
        List<String> noStation = new ArrayList<>();
        if (obj != null && obj.isObject()) {
            Iterator<String> it2 = obj.fieldNames();
            while (it2.hasNext()) {
                String nm = it2.next();
                Integer have = jdbc.queryForObject(
                        "SELECT count(*) FROM traffic_stations WHERE name = ? AND period_id = ?", Integer.class, nm, periodId);
                if (have == null || have == 0) noStation.add(nm);
            }
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("saved", n);
        res.put("unknown_stations", noStation);
        return res;
    }

    /**
     * Upsert raw count rows. A row is identified by Station Name + Section Label + Date + Time
     * (+ Direction, + lane where the file has them) within the survey period: the same identity
     * replaces that row, anything else is added, and rows not in the file are left alone.
     * Body: {rows:[{station, section, date, time, direction, xsp, cells:{header:value,...}}]}.
     * Returns added/replaced counts, rows rejected, and stations whose earlier data existed only
     * as a summary (no raw rows to merge with).
     */
    @PostMapping("/count-rows")
    public Map<String, Object> saveCountRows(@RequestBody String body,
                                             @RequestParam(value = "periodId", required = false) Integer periodId) throws Exception {
        if (periodId == null || !periods.exists(periodId))
            return Map.of("error", "Select the survey period this data belongs to before importing.");
        JsonNode rows = om.readTree(body).get("rows");
        int added = 0, replaced = 0;
        List<Map<String, Object>> rejected = new ArrayList<>();
        Set<String> summaryOnly = new LinkedHashSet<>();
        Map<String, String> stationSection = new HashMap<>();
        Set<String> checked = new HashSet<>();
        if (rows != null && rows.isArray()) {
            for (JsonNode r : rows) {
                String st = txt(r, "station"), date = txt(r, "date");
                if (st == null || st.isEmpty() || date == null || date.isEmpty()) continue;
                String sec = txt(r, "section") == null ? "" : txt(r, "section");
                if (checked.add(st)) {
                    List<String> have = jdbc.queryForList(
                            "SELECT section FROM traffic_stations WHERE name = ? AND period_id = ?", String.class, st, periodId);
                    stationSection.put(st, have.isEmpty() || have.get(0) == null ? "" : have.get(0));
                    Integer rowsHave = jdbc.queryForObject(
                            "SELECT count(*) FROM traffic_count_rows WHERE station_name = ? AND period_id = ?", Integer.class, st, periodId);
                    Integer sumHave = jdbc.queryForObject(
                            "SELECT count(*) FROM traffic_counts WHERE name = ? AND period_id = ?", Integer.class, st, periodId);
                    if (rowsHave != null && rowsHave == 0 && sumHave != null && sumHave > 0) summaryOnly.add(st);
                }
                String stationSec = stationSection.get(st);
                if (sec.isEmpty()) sec = stationSec;      // the station's own section is the default
                if (!stationSec.isEmpty() && !sec.equals(stationSec)) {
                    Map<String, Object> bad = new LinkedHashMap<>();
                    bad.put("station", st); bad.put("section", sec);
                    bad.put("reason", "this station is on Section Label " + stationSec);
                    if (rejected.size() < 50) rejected.add(bad);
                    continue;
                }
                Boolean inserted = jdbc.queryForObject(
                        "INSERT INTO traffic_count_rows(period_id, station_name, section_label, count_date, count_time, direction, xsp, cells, updated_at) " +
                        "VALUES(?,?,?,?,?,?,?,?::jsonb, now()) " +
                        "ON CONFLICT (period_id, station_name, section_label, count_date, count_time, direction, xsp) " +
                        "DO UPDATE SET cells = EXCLUDED.cells, updated_at = now() RETURNING (xmax = 0)",
                        Boolean.class, periodId, st, sec, date,
                        txt(r, "time") == null ? "" : txt(r, "time"),
                        txt(r, "direction") == null ? "" : txt(r, "direction"),
                        txt(r, "xsp") == null ? "" : txt(r, "xsp"),
                        om.writeValueAsString(r.get("cells")));
                if (Boolean.TRUE.equals(inserted)) added++; else replaced++;
            }
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("added", added);
        res.put("replaced", replaced);
        res.put("rejected", rejected);
        res.put("summary_only", new ArrayList<>(summaryOnly));
        return res;
    }

    /** Every stored raw count row of the named stations, for rebuilding their summaries.
     *  Body: {stations:["TVM_STN_001", ...]} -> {station: [cells, ...]}. */
    @PostMapping("/count-rows/fetch")
    public Map<String, Object> fetchCountRows(@RequestBody String body,
                                              @RequestParam(value = "periodId", required = false) Integer periodId) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        if (periodId == null) return out;
        JsonNode names = om.readTree(body).get("stations");
        if (names == null || !names.isArray()) return out;
        for (JsonNode n : names) {
            String st = n.asText();
            List<Object> cells = new ArrayList<>();
            for (String c : jdbc.queryForList(
                    "SELECT cells::text FROM traffic_count_rows WHERE period_id = ? AND station_name = ? ORDER BY id",
                    String.class, periodId, st)) {
                cells.add(om.readValue(c, Object.class));
            }
            out.put(st, cells);
        }
        return out;
    }

    /** Full store, in the same shape the viewer/Data Console use.
     *  Defaults to the active survey period; ?period_id= selects another. */
    @GetMapping("/store")
    public Map<String, Object> store(@RequestParam(value = "period_id", required = false) Integer periodId) {
        int pid = periods.resolve(periodId);
        List<Map<String, Object>> stations = jdbc.query(
                "SELECT name,road,section,chainage,lat,lng,xsp FROM traffic_stations WHERE period_id = ? ORDER BY name",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", rs.getString("name"));
                    m.put("road", rs.getString("road"));
                    m.put("section", rs.getString("section"));
                    m.put("ch", rs.getObject("chainage"));
                    m.put("lat", rs.getObject("lat"));
                    m.put("lng", rs.getObject("lng"));
                    m.put("xsp", rs.getString("xsp"));
                    return m;
                }, pid);
        Map<String, Object> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT name, data::text AS d FROM traffic_counts WHERE period_id = ?", pid)) {
            try {
                // Plain Maps/Lists, NOT JsonNode: Spring Boot 4 serialises responses with
                // Jackson 3, which renders a Jackson-2 JsonNode as its bean properties
                // ({"array":false,"nodeType":"OBJECT",...}) instead of the JSON content.
                counts.put((String) row.get("name"), om.readValue((String) row.get("d"), Object.class));
            } catch (Exception ignore) { }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("v", 1);
        out.put("savedAt", new Date().toInstant().toString());
        out.put("stations", stations);
        out.put("counts", counts);
        return out;
    }

    /** Stations as a GeoJSON FeatureCollection, read from the stored point — the linear
     *  reference (chainage along the matching roads."Section_La" centreline) was resolved
     *  at import, not here. The station's lat/lng is returned as a property but is still
     *  not what places it. Defaults to the active survey period; ?period_id= selects another. */
    @GetMapping("/stations/geojson")
    public Map<String, Object> stationsGeojson(@RequestParam(value = "period_id", required = false) Integer periodId) {
        int pid = periods.resolve(periodId);
        List<Map<String, Object>> feats = jdbc.query("""
                SELECT t.name, t.road, t.section, t.chainage, t.lat, t.lng, t.xsp,
                       ST_X(t.geom) AS px, ST_Y(t.geom) AS py
                FROM traffic_stations t
                WHERE t.geom IS NOT NULL AND t.period_id = ?
                ORDER BY t.name
                """,
                (rs, i) -> {
                    Map<String, Object> geom = new LinkedHashMap<>();
                    geom.put("type", "Point");
                    geom.put("coordinates", Arrays.asList(rs.getDouble("px"), rs.getDouble("py")));
                    Map<String, Object> props = new LinkedHashMap<>();
                    props.put("name", rs.getString("name"));
                    props.put("road", rs.getString("road"));
                    props.put("section", rs.getString("section"));
                    props.put("ch", rs.getObject("chainage"));
                    props.put("lat", rs.getObject("lat"));
                    props.put("lng", rs.getObject("lng"));
                    props.put("xsp", rs.getString("xsp"));
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("type", "Feature");
                    f.put("geometry", geom);
                    f.put("properties", props);
                    return f;
                }, pid);
        Map<String, Object> fc = new LinkedHashMap<>();
        fc.put("type", "FeatureCollection");
        fc.put("features", feats);
        return fc;
    }

    /** Clears ONE survey period's stations and counts (other periods are kept). */
    @PostMapping("/clear")
    public Map<String, Object> clear(@RequestParam(value = "periodId", required = false) Integer periodId) {
        if (periodId == null || !periods.exists(periodId))
            return Map.of("cleared", false, "error", "Select the survey period to clear.");
        int a = jdbc.update("DELETE FROM traffic_stations WHERE period_id = ?", periodId);
        int b = jdbc.update("DELETE FROM traffic_counts WHERE period_id = ?", periodId);
        jdbc.update("DELETE FROM traffic_count_rows WHERE period_id = ?", periodId);
        return Map.of("cleared", true, "stations", a, "counts", b);
    }

    /* Station names and section labels are matched and grouped as strings, so a
       stray space or newline around one would make it a different station — see
       ImportText. */
    private String txt(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return (v == null || v.isNull()) ? null : ImportText.clean(v.asText());
    }

    private Double dbl(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull() || (v.isTextual() && v.asText().trim().isEmpty())) return null;
        try { return v.asDouble(); } catch (Exception e) { return null; }
    }
}
