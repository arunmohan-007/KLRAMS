package com.fist.rmms_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
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
 *   culvert         -> POINT (Chainage via ST_LineInterpolatePoint)
 *   furniture_point -> POINT
 *
 * CSV requirements (case-insensitive headers):
 *   lines : Section_Label, Start_Chainage, End_Chainage, ...any other columns
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

    private static final Set<String> LINE_TYPES  = Set.of("bridge", "furniture_line");
    private static final Set<String> POINT_TYPES = Set.of("culvert", "furniture_point", "subgrade", "bituminous_core", "pavement_crust", "fwd");
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
    private final ObjectMapper om = new ObjectMapper();

    public AssetController(JdbcTemplate jdbc, SurveyPeriodService periods) {
        this.jdbc = jdbc;
        this.periods = periods;
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

            Integer iSec = first(idx, "section_label","section_la","section_label_code","label","road");
            Integer iStart = first(idx, "start_chainage","start_chiange","start","chainage","chiange","from_chainage","from");
            Integer iEnd = first(idx, "end_chainage","end_chiange","end","to_chainage",
                    "to_ch","toch","tochainage","chainage_to","chainageto","end_ch","endch","to");
            if (iSec == null || iStart == null)
                { r.put("status","error"); r.put("message","CSV must have Section_Label and "+(isLine?"Start_Chainage/End_Chainage":"Chainage")); return r; }
            if (isLine && iEnd == null)
                { r.put("status","error"); r.put("message","Line assets need End_Chainage too"); return r; }

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
                // Lines always carry an end chainage. FWD is a point layer but its
                // survey rows are chainage RANGES (From..To) — keep the end so the
                // FWD segments can be cut later (see FwdSegmentService). Other point
                // types stay single-chainage (end = null).
                Double e = isLine ? num(val(c, iEnd))
                        : (type.equals("fwd") && iEnd != null ? num(val(c, iEnd)) : null);
                if (sec == null || s == null || (isLine && (e == null || e <= s))) { skipped++; continue; }
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
                // keep every column as attrs
                Map<String,String> attrs = new LinkedHashMap<>();
                for (int i=0;i<cols.length && i<c.length;i++) {
                    String v = c[i].trim();
                    if (!v.isEmpty()) attrs.put(cols[i].trim(), v);
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
            throw new RuntimeException("Asset upload failed: " + ex.getMessage(), ex);
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
    public String geojson(@PathVariable String type,
                          @RequestParam(value = "period_id", required = false) Integer periodId) {
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
                            'to_ch', end_chainage) || COALESCE(attrs,'{}'::jsonb)
                    )), '[]'::json))::text
                FROM road_assets WHERE asset_type = ? AND geom IS NOT NULL
                """;
            String t = type.toLowerCase();
            // Survey streams are filtered to one period (default: the active one);
            // inventory types (bridge, culvert, furniture) ignore the parameter.
            if (SURVEY_TYPES.contains(t)) {
                return jdbc.queryForObject(base + " AND period_id = ?",
                        String.class, t, periods.resolve(periodId));
            }
            return jdbc.queryForObject(base, String.class, t);
        } catch (Exception e) {
            return "{\"type\":\"FeatureCollection\",\"features\":[]}";
        }
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
