package com.fist.rmms_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Road assets, linearly referenced onto the network:
 *   bridge          -> LINE  (Start_Chainage..End_Chainage via ST_LineSubstring)
 *   furniture_line  -> LINE
 *   fwd             -> LINE  (From..To chainage + D0..Dn in attrs; lat/lng kept in attrs
 *                             only for display — placement is always LRS, like bridges)
 *   culvert         -> POINT (Chainage via ST_LineInterpolatePoint)
 *   furniture_point -> POINT
 *
 * CSV requirements (case-insensitive headers):
 *   lines : Section_Label, Start_Chainage/From, End_Chainage/To, ...any other columns
 *   points: Section_Label, Chainage (or Start_Chainage), ...any other columns
 * Every other CSV column is kept and shown in the popup.
 *
 * Reference length = Rd_End_cha - Rd_Str_cha (fallback Measrd_Len, then geometry),
 * the same rule as the condition segmentation. Rows with missing/invalid chainage
 * or unknown Section_Label are skipped and reported.
 */
@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private static final Logger log = LoggerFactory.getLogger(AssetController.class);

    private static final Set<String> LINE_TYPES  = Set.of("bridge", "furniture_line", "fwd");
    private static final Set<String> POINT_TYPES = Set.of("culvert", "furniture_point", "subgrade", "bituminous_core", "pavement_crust");
    /* Field-survey streams belong to a survey period; permanent inventory
       (bridge, culvert, furniture) does not. */
    private static final Set<String> SURVEY_TYPES = Set.of("fwd", "subgrade", "bituminous_core", "pavement_crust");

    /* Every geotechnical survey stream shares the Section_Label + Chainage
       columns, so a bituminous-core file used to import cleanly as sub-grade
       soil. Each stream is now identified by its own lab columns (headers
       compared with all non-alphanumerics stripped): the upload is rejected
       unless at least one signature column of the requested type is present. */
    private static final Map<String, List<String>> GEO_SIG = Map.of(
        "subgrade",        List.of("cbr","mdd","omc","fdd","fmc","ll","pl","pi","soiltype","gravelcontent","sandcontent"),
        "bituminous_core", List.of("coreno","bulkdensity","observedthickness","bituminouslayers"),
        "pavement_crust",  List.of("basethickness","basetype","subbasethickness","subbasetype",
                                   "surfacethickness","surfacetype","subgradecbr","subgradesoiltype"),
        "fwd",             List.of("d0","do"));
    private static final Map<String, String> GEO_TITLE = Map.of(
        "subgrade","Sub-Grade Soil", "bituminous_core","Bituminous Core",
        "pavement_crust","Pavement Crust", "fwd","FWD deflection");
    private static final Map<String, String> GEO_EXPECT = Map.of(
        "subgrade","CBR, MDD, OMC, FDD, FMC, LL/PL/PI, Soil Type",
        "bituminous_core","Core No, Bulk Density of Binder/Wearing Course, Observed Thickness",
        "pavement_crust","Base/Sub Base/Surface Thickness and Type, Sub Grade CBR, Sub Grade Soil Type",
        "fwd","D0…Dn deflection columns");

    private final JdbcTemplate jdbc;
    private final SurveyPeriodService periods;
    private final LayerAttributeService attributes;
    private final FwdTileService fwdTiles;
    private final ObjectMapper om = new ObjectMapper();

    public AssetController(JdbcTemplate jdbc, SurveyPeriodService periods,
                           LayerAttributeService attributes, FwdTileService fwdTiles) {
        this.jdbc = jdbc;
        this.periods = periods;
        this.attributes = attributes;
        this.fwdTiles = fwdTiles;
    }

    private void ensure() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS road_assets (
                id serial PRIMARY KEY,
                asset_type text,
                section_label text,
                start_chainage double precision,
                end_chainage double precision,
                attrs jsonb,
                geom geometry,
                period_id integer
            )""");
        jdbc.execute("ALTER TABLE road_assets ADD COLUMN IF NOT EXISTS period_id integer");
        jdbc.execute("CREATE INDEX IF NOT EXISTS road_assets_type_idx ON road_assets(asset_type)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS road_assets_period_idx ON road_assets(period_id)");
        /* Older FWD uploads stored a start-chainage POINT while From..To lived only in
           attrs. Promote those rows to LINE stretches so the map/tiles paint the same
           geom upload writes for new FWD CSVs. Safe to re-run: only touches FWD rows
           whose geom is still a Point (or null) and that have a usable to-chainage. */
        relocateFwdLineGeoms();
    }

    /** Called from startup warm-up so FWD vector tiles see line geoms before the first map pan. */
    public void warm() {
        ensure();
    }

    /**
     * Backfill {@code end_chainage} from attrs when missing, then set {@code geom} with
     * {@code ST_LineSubstring} for FWD rows that still carry a point (legacy) or no geom.
     * Latitude/Longitude in attrs are left alone — display only, never used for placement.
     */
    private void relocateFwdLineGeoms() {
        try {
            Boolean built = jdbc.queryForObject(
                    "SELECT to_regclass('road_assets') IS NOT NULL", Boolean.class);
            if (!Boolean.TRUE.equals(built)) return;

            /* Pull From/To out of free-form attrs when the typed columns are blank —
               same key lists FwdTileService / the viewer have always accepted. */
            jdbc.update("""
                UPDATE road_assets a SET
                    start_chainage = COALESCE(a.start_chainage, (
                        SELECT (e.value)::double precision
                          FROM jsonb_each_text(a.attrs) e
                         WHERE regexp_replace(lower(e.key), '[^a-z0-9]', '', 'g') IN
                               ('fromch','fromchainage','startch','startchainage','chainagefrom',
                                'chfrom','frch','fromm','startm','from','start','chainage')
                           AND e.value ~ '^-?[0-9]+(\\.[0-9]+)?$'
                         LIMIT 1)),
                    end_chainage = COALESCE(a.end_chainage, (
                        SELECT (e.value)::double precision
                          FROM jsonb_each_text(a.attrs) e
                         WHERE regexp_replace(lower(e.key), '[^a-z0-9]', '', 'g') IN
                               ('toch','tochainage','endch','endchainage','chainageto','chto',
                                'tch','tom','endm','to','end')
                           AND e.value ~ '^-?[0-9]+(\\.[0-9]+)?$'
                         LIMIT 1))
                WHERE a.asset_type = 'fwd'
                  AND a.attrs IS NOT NULL
                  AND (a.end_chainage IS NULL OR a.start_chainage IS NULL)
                """);

            String lenExpr = """
                COALESCE(
                    NULLIF(r."Rd_End_cha"::double precision - r."Rd_Str_cha"::double precision, 0),
                    NULLIF(r."Measrd_Len"::double precision, 0),
                    ST_Length(r.geom::geography))
                """;
            jdbc.update("""
                UPDATE road_assets a SET geom = ST_LineSubstring(
                    ST_LineMerge(r.geom),
                    GREATEST(LEAST(LEAST(a.start_chainage, a.end_chainage) / %s, 1.0), 0.0),
                    GREATEST(LEAST(GREATEST(a.start_chainage, a.end_chainage) / %s, 1.0), 0.0))
                FROM roads r
                WHERE a.asset_type = 'fwd'
                  AND a.start_chainage IS NOT NULL
                  AND a.end_chainage IS NOT NULL
                  AND a.end_chainage <> a.start_chainage
                  AND r."Section_La" = a.section_label
                  AND r.geom IS NOT NULL
                  AND (a.geom IS NULL OR GeometryType(a.geom) IN ('POINT','MULTIPOINT'))
                """.formatted(lenExpr, lenExpr));
        } catch (Exception e) {
            /* Non-fatal by design: on a first boot the roads table may not exist yet,
               and the next upload/ensure() retries. But it must not be INVISIBLE —
               a silent catch here once hid a wrong column name in the SQL above,
               which meant the promotion never ran and nothing said so. Logged at
               warn so a genuine failure is findable without stopping startup. */
            log.warn("FWD line-geometry promotion did not run — legacy FWD rows may still "
                    + "carry point geometry. Retried on the next asset upload. Cause: {}",
                    e.toString(), e);
        }
    }

    @PostMapping("/{type}/upload")
    @Transactional
    public Map<String, Object> upload(@PathVariable String type,
                                      @RequestParam("file") MultipartFile file,
                                      @RequestParam(value = "periodId", required = false) Integer periodId,
                                      @RequestParam(value = "force", defaultValue = "false") boolean force) {
        Map<String, Object> r = new HashMap<>();
        type = type.toLowerCase();
        boolean isLine = LINE_TYPES.contains(type);
        if (!isLine && !POINT_TYPES.contains(type)) {
            r.put("status", "error"); r.put("message", "Unknown asset type: " + type);
            return r;
        }
        boolean isSurvey = SURVEY_TYPES.contains(type);
        if (isSurvey && (periodId == null || !periods.exists(periodId))) {
            r.put("status", "error");
            r.put("message", "Select the survey period this data belongs to before importing.");
            return r;
        }
        if (!isSurvey) periodId = null;   // permanent inventory is period-less
        try {
            ensure();
            byte[] data = file.getBytes();
            // Wrong-dataset guard: reject e.g. a core CSV sent to the soil importer
            // before any other processing (never bypassed by force).
            Map<String, Object> wrongType = validateGeoHeader(type, data);
            if (wrongType != null) return wrongType;
            // Date-format guard: every date column must use the KLRAMS standard
            // dd-mmm-yyyy (also never bypassed by force).
            Map<String, Object> badDates = validateDates(data);
            if (badDates != null) return badDates;
            // Re-upload guard: a section in this file may already have rows of this
            // type (same period for survey streams). Importing would silently replace
            // them, so when force=false nothing is written — the response lists the
            // affected sections and the console asks the user to confirm. Re-posting
            // with force=true performs the replace.
            if (!force) {
                Map<String, Object> exists = analyzeExisting(type, isSurvey ? periodId : null, data);
                if (exists != null) return exists;
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8));
            String header = br.readLine();
            if (header == null) { r.put("status","error"); r.put("message","Empty CSV"); return r; }
            String[] cols = parse(header);
            Map<String,Integer> idx = new HashMap<>();
            for (int i=0;i<cols.length;i++)
                idx.put(cols[i].trim().toLowerCase().replace("\uFEFF","").replace(' ','_'), i);

            /* Which column is the section label, and which the chainages, is
               answered by the layer's own attribute list first — the aliases the
               RMMS cell maintains in Attribute Data. The hard-coded lists below
               stay as the fallback for a database where the registry never came
               up, but they are no longer how a new district's spelling gets
               supported: adding it on that screen is, with no code change. */
            LayerAttributeService.HeaderResolver headers =
                    attributes.headerResolver(type, "default");

            Integer iSec = firstNonNull(headers.columnFor(cols, "SECTION_LABEL"),
                    () -> first(idx, "section_label","section_la","section_label_code","label","road"));
            Integer iStart = firstNonNull(
                    // A point layer declares one CHAINAGE, a line layer a
                    // START_CHAINAGE — ask for whichever this type carries.
                    headers.columnFor(cols, isLine ? "START_CHAINAGE" : "CHAINAGE"),
                    () -> first(idx, "start_chainage","start_chiange","start","chainage","chiange","from_chainage","from"));
            Integer iEnd = firstNonNull(headers.columnFor(cols, "END_CHAINAGE"),
                    () -> first(idx, "end_chainage","end_chiange","end","to_chainage",
                    "to_ch","toch","tochainage","chainage_to","chainageto","end_ch","endch","to"));
            if (iSec == null || iStart == null)
                { r.put("status","error"); r.put("message","CSV must have Section_Label and "+(isLine?"Start_Chainage/From and End_Chainage/To":"Chainage")); return r; }
            if (isLine && iEnd == null)
                { r.put("status","error"); r.put("message","Line assets (including FWD) need End_Chainage / To as well as Start / From"); return r; }

            // Additive by section: replace only the section labels present in THIS
            // file (of this asset type), so uploading another section adds to the
            // data instead of wiping the whole type. Re-uploading a section refreshes
            // just that section. Cleared once per (type, section) as we stream.
            Set<String> replacedSections = new HashSet<>();

            int loaded=0, skipped=0;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] c = parse(line);
                String sec = val(c, iSec);
                Double s = num(val(c, iStart));
                Double e = isLine ? num(val(c, iEnd)) : null;
                if (sec == null || s == null || (isLine && (e == null || e.equals(s)))) { skipped++; continue; }
                // Allow reversed From/To (common in FWD surveys): store lo..hi for LRS.
                if (isLine && e < s) { Double t = s; s = e; e = t; }
                // first row for this section in this upload -> clear its old rows of
                // this type, only within the chosen survey period (older periods keep
                // their data; inventory types have no period)
                if (replacedSections.add(sec)) {
                    if (periodId != null) {
                        jdbc.update("DELETE FROM road_assets WHERE asset_type = ? AND section_label = ? AND period_id = ?",
                                type, sec, periodId);
                    } else {
                        jdbc.update("DELETE FROM road_assets WHERE asset_type = ? AND section_label = ?", type, sec);
                    }
                }
                /* Keep every column as attrs, under the storage key the layer
                   declares for it rather than under whatever this file's header
                   happened to say. That is what makes "Section_Label" from
                   Malappuram and "Section Label" from Idukki one field instead
                   of two, so a card, a filter or a dashboard that finds the
                   value in one district finds it in all of them.

                   A header the layer does not recognise keeps its own name. It
                   is a real value someone recorded, and dropping it or forcing
                   it into a neighbouring key would lose or corrupt data; stored
                   under its own header it is visible, and the next boot's
                   discovery pass turns it into an attribute of its own. */
                Map<String,String> attrs = new LinkedHashMap<>();
                for (int i=0;i<cols.length && i<c.length;i++) {
                    String v = c[i].trim();
                    if (v.isEmpty()) continue;
                    String col = cols[i].trim();
                    String key = headers.keyFor(col);
                    attrs.put(key != null ? key : col, v);
                }
                // Every asset type is placed by linear reference ONLY — Section_Label
                // + chainage must resolve to a road, same as traffic stations. Any
                // GPS columns in the CSV are kept as attrs for display but never used
                // for placement — a bad Section_Label can no longer sneak a point
                // past the unmatched-row cleanup below by getting a geom from GPS
                // instead (it used to; that's how points with a typo'd section still
                // showed up on the map but as "(unmapped)" / OTHER in the dashboards).
                jdbc.update("INSERT INTO road_assets (asset_type, section_label, start_chainage, end_chainage, attrs, period_id) VALUES (?,?,?,?,?::jsonb,?)",
                        type, sec, s, e, om.writeValueAsString(attrs), periodId);
                loaded++;
            }

            // linear-reference geometry in one pass
            String lenExpr = """
                COALESCE(
                    NULLIF(r."Rd_End_cha"::double precision - r."Rd_Str_cha"::double precision, 0),
                    NULLIF(r."Measrd_Len"::double precision, 0),
                    ST_Length(r.geom::geography))
                """;
            int placed;
            if (isLine) {
                placed = jdbc.update("""
                    UPDATE road_assets a SET geom = ST_LineSubstring(
                        ST_LineMerge(r.geom),
                        GREATEST(LEAST(a.start_chainage / %s, 1.0), 0.0),
                        GREATEST(LEAST(a.end_chainage   / %s, 1.0), 0.0))
                    FROM roads r
                    WHERE a.asset_type = ? AND a.geom IS NULL AND r."Section_La" = a.section_label AND r.geom IS NOT NULL
                    """.formatted(lenExpr, lenExpr), type);
            } else {
                placed = jdbc.update("""
                    UPDATE road_assets a SET geom = ST_LineInterpolatePoint(
                        ST_LineMerge(r.geom),
                        GREATEST(LEAST(a.start_chainage / %s, 1.0), 0.0))
                    FROM roads r
                    WHERE a.asset_type = ? AND a.geom IS NULL AND r."Section_La" = a.section_label AND r.geom IS NOT NULL
                    """.formatted(lenExpr), type);
            }
            int unmatched = jdbc.update("DELETE FROM road_assets WHERE asset_type = ? AND geom IS NULL", type);

            r.put("status","ok");
            r.put("loaded", loaded - unmatched);
            r.put("skipped_rows", skipped);
            r.put("unmatched_section_label", unmatched);
            return r;
        } catch (Exception ex) {
            // Keep the cause for the server log; don't echo the raw driver message to the client.
            throw new RuntimeException("Asset upload failed", ex);
        }
    }

    /** Permanently removes rows of {type} (optionally scoped to one survey period)
     *  whose Section_Label matches no road — the only kind of row that can exist
     *  today, since import no longer places anything by GPS for survey streams
     *  (see upload()); this cleans up rows left over from before that fix, or from
     *  a genuinely stale/renamed road section. Rows with a matched road but a
     *  blank Road_Class are NOT touched — that's a road-network data gap, not an
     *  import error, and the point itself is real. */
    @DeleteMapping("/{type}/orphans")
    public Map<String, Object> deleteOrphans(@PathVariable String type,
                                             @RequestParam(value = "periodId", required = false) Integer periodId) {
        ensure();
        type = type.toLowerCase();
        int n = periodId != null
            ? jdbc.update("DELETE FROM road_assets a WHERE a.asset_type = ? AND a.period_id = ? " +
                "AND NOT EXISTS (SELECT 1 FROM roads r WHERE r.\"Section_La\" = a.section_label)", type, periodId)
            : jdbc.update("DELETE FROM road_assets a WHERE a.asset_type = ? " +
                "AND NOT EXISTS (SELECT 1 FROM roads r WHERE r.\"Section_La\" = a.section_label)", type);
        Map<String, Object> r = new HashMap<>();
        r.put("status", "ok");
        r.put("deleted", n);
        return r;
    }

    /** Per (type, survey period) counts of orphan rows — Section_Label matches no
     *  road — across every asset type in road_assets, for the Data Console's
     *  "Data Cleanup" panel (Super Admin only). Every type is now placed by
     *  linear reference only (see upload()), so an orphan here can only be a
     *  leftover from before that fix, or a genuine bad Section_Label — never
     *  expected behaviour. Inventory types (bridge, culvert, furniture_line,
     *  furniture_point) have period_id NULL; period_name falls back to "—". */
    @GetMapping("/orphans/summary")
    public List<Map<String, Object>> orphanSummary() {
        ensure();
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT a.asset_type AS type, a.period_id AS period_id, count(*) AS n
            FROM road_assets a
            WHERE NOT EXISTS (SELECT 1 FROM roads r WHERE r."Section_La" = a.section_label)
            GROUP BY 1, 2
            ORDER BY 1, 2
            """);
        Map<Integer, String> names = new HashMap<>();
        for (Map<String, Object> p : periods.list()) names.put(((Number) p.get("id")).intValue(), (String) p.get("name"));
        for (Map<String, Object> row : rows) {
            Number pid = (Number) row.get("period_id");
            row.put("period_name", pid != null ? names.getOrDefault(pid.intValue(), "period " + pid) : "—");
        }
        return rows;
    }

    @GetMapping(value = "/{type}/geojson", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> geojson(@PathVariable String type,
                          @RequestParam(value = "period_id", required = false) Integer periodId,
                          @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        String body;
        try {
            ensure();
            String base = """
                SELECT json_build_object('type','FeatureCollection','features',
                    COALESCE(json_agg(json_build_object(
                        'type','Feature',
                        'geometry', ST_AsGeoJSON(geom, 6)::json,
                        'properties', jsonb_build_object(
                            'road', section_label,
                            'from_ch', start_chainage,
                            'to_ch', end_chainage,
                            '__id', id) || COALESCE(attrs,'{}'::jsonb)
                    )), '[]'::json))::text
                FROM road_assets WHERE asset_type = ? AND geom IS NOT NULL
                """;
            String t = type.toLowerCase();
            // Survey streams are filtered to one period (default: the active one);
            // inventory types (bridge, culvert, furniture) ignore the parameter.
            if (SURVEY_TYPES.contains(t)) {
                int resolvedPeriod = periods.resolve(periodId);
                body = jdbc.queryForObject(base + " AND period_id = ?",
                        String.class, t, resolvedPeriod);
                if ("fwd".equals(t)) body = stampFwdScale(body, resolvedPeriod);
            } else {
                body = jdbc.queryForObject(base, String.class, t);
            }
        } catch (Exception e) {
            body = "{\"type\":\"FeatureCollection\",\"features\":[]}";
        }
        if (body == null) body = "{\"type\":\"FeatureCollection\",\"features\":[]}";
        // Assets aren't cached in memory, but a content ETag still lets a repeat
        // map open skip the re-download when the data is unchanged (304).
        return GeoJsonResponse.conditional(body, GeoJsonResponse.contentTag(body), ifNoneMatch);
    }

    /**
     * Stamp {@code __dscale}/{@code __d0} onto every FWD feature before this
     * whole-network GeoJSON goes out, using {@link FwdTileService#resolveD0Scale}
     * — the same period override / guess the map's tiles are coloured from.
     *
     * <p>Before this, {@code js/06-assets.js} and {@code js/24-fwd.js} each
     * re-guessed the mm-vs-µm scale independently from whatever slice of D0
     * values they happened to have, so an export or the chainage lookup could
     * disagree with what the map showed for the very same period. Stamping it
     * here means every consumer reads one number instead of computing three.
     *
     * <p>Failure is swallowed and the un-stamped GeoJSON returned as-is: a
     * client that finds no {@code __dscale} falls back to its own guess, which
     * is exactly what it did before this existed.
     */
    @SuppressWarnings("unchecked")
    private String stampFwdScale(String geojson, int periodId) {
        try {
            int scale = fwdTiles.resolveD0Scale(periodId);
            Map<String, Object> fc = om.readValue(geojson, Map.class);
            List<Map<String, Object>> feats = (List<Map<String, Object>>) fc.get("features");
            if (feats != null) {
                for (Map<String, Object> f : feats) {
                    Map<String, Object> props = (Map<String, Object>) f.get("properties");
                    if (props == null) continue;
                    props.put("__dscale", scale);
                    Object raw = fwdD0Value(props);
                    if (raw != null) {
                        try {
                            props.put("__d0", Math.round(Double.parseDouble(String.valueOf(raw)) * scale));
                        } catch (NumberFormatException ignore) { /* non-numeric D0, leave unstamped */ }
                    }
                }
            }
            return om.writeValueAsString(fc);
        } catch (Exception e) {
            log.warn("Could not stamp the FWD D0 scale onto the GeoJSON export — clients will "
                    + "fall back to their own guess: {}", e.toString());
            return geojson;
        }
    }

    /** The D0 reading under whichever case/spacing the CSV used, or null. */
    private static Object fwdD0Value(Map<String, Object> props) {
        for (Map.Entry<String, Object> e : props.entrySet()) {
            String k = e.getKey().toLowerCase().replaceAll("[^a-z0-9]", "");
            Object v = e.getValue();
            if (("d0".equals(k) || "do".equals(k)) && v != null && !"".equals(v)) return v;
        }
        return null;
    }

    /** How many signature columns of a stream appear in the (normalised) header.
     *  Short tokens must match a column exactly; long ones (>= 10 chars) may also
     *  appear inside a longer column name (e.g. "bulkdensity" inside
     *  "Bulk Density of Binder Course gmcc"). */
    private static int sigMatches(Set<String> normHeader, List<String> sig) {
        int n = 0;
        for (String s : sig)
            for (String h : normHeader)
                if (h.equals(s) || (s.length() >= 10 && h.contains(s))) { n++; break; }
        return n;
    }

    /** null when the CSV header carries the requested stream's signature columns
     *  (or the type has no signature); otherwise an error response saying what was
     *  expected and, when recognisable, which importer the file belongs to. */
    private Map<String, Object> validateGeoHeader(String type, byte[] data) throws Exception {
        List<String> sig = GEO_SIG.get(type);
        if (sig == null) return null;    // bridge / culvert / furniture: free-form columns
        BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8));
        String header = br.readLine();
        if (header == null) return null; // empty file is reported by the main flow
        Set<String> norm = new HashSet<>();
        for (String c : parse(header))
            norm.add(c.toLowerCase().replaceAll("[^a-z0-9]", ""));
        if (sigMatches(norm, sig) > 0) return null;
        String looksLike = null;
        int best = 1;                    // need >= 2 matches to name another stream
        for (Map.Entry<String, List<String>> e : GEO_SIG.entrySet()) {
            if (e.getKey().equals(type)) continue;
            int m = sigMatches(norm, e.getValue());
            if (m > best) { best = m; looksLike = GEO_TITLE.get(e.getKey()); }
        }
        Map<String, Object> r = new HashMap<>();
        r.put("status", "error");
        r.put("message", "This file doesn't look like " + GEO_TITLE.get(type)
            + " data — none of its columns match (expected e.g. " + GEO_EXPECT.get(type) + ")."
            + (looksLike != null ? " It looks like " + looksLike + " data — use that importer instead." : "")
            + " Nothing was imported.");
        return r;
    }

    /** Is this a date column? Matched on whole words so "Updated"/"Validated" don't qualify. */
    private static boolean isDateColumn(String header) {
        for (String t : header.toLowerCase().split("[^a-z]+"))
            if (t.equals("date") || t.equals("dates")) return true;
        return false;
    }

    /**
     * null when every date cell uses the KLRAMS standard dd-mmm-yyyy; otherwise an
     * error response naming the offending column and values. Dates arrive here as
     * free-text attrs, so nothing downstream would flag a wrongly-formatted one —
     * and a date nobody can parse is worse than a missing one, because 05/04/2026
     * reads as 5 April or 4 May depending on who exported the file. Blank cells are
     * allowed: the date is optional on these streams.
     */
    private Map<String, Object> validateDates(byte[] data) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8));
        String header = br.readLine();
        if (header == null) return null;
        String[] cols = parse(header);
        List<Integer> dateCols = new ArrayList<>();
        for (int i = 0; i < cols.length; i++)
            if (isDateColumn(cols[i].replace("﻿", "").trim())) dateCols.add(i);
        if (dateCols.isEmpty()) return null;

        int bad = 0, row = 1;
        String badCol = null;
        List<String> samples = new ArrayList<>();
        String line;
        while ((line = br.readLine()) != null) {
            row++;
            if (line.trim().isEmpty()) continue;
            String[] c = parse(line);
            for (int i : dateCols) {
                String v = i < c.length ? c[i].trim() : "";
                if (v.isEmpty() || ImportTemplateController.isDate(v)) continue;
                bad++;
                if (badCol == null) badCol = cols[i].replace("﻿", "").trim();
                if (samples.size() < 5 && !samples.contains(v)) samples.add("“" + v + "” (row " + row + ")");
            }
        }
        if (bad == 0) return null;
        Map<String, Object> r = new HashMap<>();
        r.put("status", "error");
        r.put("message", bad + " value(s) in the \"" + badCol + "\" column are not in the required date format "
            + ImportTemplateController.DATE_FORMAT_HINT + ": " + String.join(", ", samples)
            + ". Re-export that column in that format. Nothing was imported.");
        return r;
    }

    /** Which sections in the CSV already have rows of this type (and period, when
     *  given)? Returns a status="exists" response with a per-section breakdown
     *  (row count + stored chainage range), or null when nothing would be replaced. */
    private Map<String, Object> analyzeExisting(String type, Integer periodId, byte[] data) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8));
        String header = br.readLine();
        if (header == null) return null;
        String[] cols = parse(header);
        Map<String,Integer> idx = new HashMap<>();
        for (int i=0;i<cols.length;i++)
            idx.put(cols[i].trim().toLowerCase().replace("\uFEFF","").replace(' ','_'), i);
        Integer iSec = first(idx, "section_label","section_la","section_label_code","label","road");
        if (iSec == null) return null;   // the upload itself reports the missing column
        Set<String> secs = new LinkedHashSet<>();
        String line;
        while ((line = br.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            String sec = val(parse(line), iSec);
            if (sec != null) secs.add(sec);
        }
        List<Map<String,Object>> hits = new ArrayList<>();
        for (String sec : secs) {
            Map<String,Object> row = periodId != null
                ? jdbc.queryForMap("SELECT count(*) AS n, min(start_chainage) AS from_ch, " +
                        "max(COALESCE(end_chainage, start_chainage)) AS to_ch FROM road_assets " +
                        "WHERE asset_type = ? AND section_label = ? AND period_id = ?", type, sec, periodId)
                : jdbc.queryForMap("SELECT count(*) AS n, min(start_chainage) AS from_ch, " +
                        "max(COALESCE(end_chainage, start_chainage)) AS to_ch FROM road_assets " +
                        "WHERE asset_type = ? AND section_label = ?", type, sec);
            if (((Number) row.get("n")).intValue() > 0) {
                Map<String,Object> m = new HashMap<>();
                m.put("section", sec);
                m.put("n", row.get("n"));
                m.put("from_ch", row.get("from_ch"));
                m.put("to_ch", row.get("to_ch"));
                hits.add(m);
            }
        }
        if (hits.isEmpty()) return null;
        Map<String,Object> r = new HashMap<>();
        r.put("status", "exists");
        r.put("existing", hits);
        return r;
    }

    /**
     * The registry's answer, or the built-in fallback if it has none.
     *
     * The fallback is a supplier so the hard-coded alias scan does not run at
     * all when the registry already resolved the column.
     */
    private static Integer firstNonNull(Integer preferred, java.util.function.Supplier<Integer> fallback) {
        return preferred != null ? preferred : fallback.get();
    }

    private static Integer first(Map<String,Integer> idx, String... names) {
        for (String n : names) if (idx.containsKey(n)) return idx.get(n);
        return null;
    }
    private static String val(String[] c, int i) { if (i>=c.length) return null; String v=c[i].trim(); return v.isEmpty()?null:v; }
    private static Double num(String s) { if (s==null) return null; try { return Double.parseDouble(s); } catch (Exception e) { return null; } }
    private static String[] parse(String line) {
        List<String> out = new ArrayList<>(); StringBuilder sb = new StringBuilder(); boolean q=false;
        for (int i=0;i<line.length();i++) { char ch=line.charAt(i);
            if (q) { if (ch=='"') { if (i+1<line.length() && line.charAt(i+1)=='"') { sb.append('"'); i++; } else q=false; } else sb.append(ch); }
            else { if (ch=='"') q=true; else if (ch==',') { out.add(sb.toString()); sb.setLength(0); } else sb.append(ch); } }
        out.add(sb.toString());
        return out.toArray(new String[0]);
    }
}
