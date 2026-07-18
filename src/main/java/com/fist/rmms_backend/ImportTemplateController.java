package com.fist.rmms_backend;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Import templates — one per dataset, defining the mapping between the KLRAMS
 * field (the canonical column name each importer expects) and the CSV column
 * name as it appears in the uploaded file, plus its data type / unit / example.
 *
 * Used for three things:
 *   1. A sample-CSV download so surveyors get the exact expected file layout.
 *   2. Pre-import validation: required columns present, number/date cells valid.
 *   3. Header renaming: when the template's CSV column differs from the KLRAMS
 *      field, the console rewrites the header before uploading, so files with
 *      renamed columns still import correctly.
 *
 * One template per dataset is "enabled" at a time — enabling one disables the
 * others of the same dataset. Default templates matching the current importers
 * are seeded on first start (builtin = true) and stay editable.
 */
@RestController
@RequestMapping("/api/templates")
public class ImportTemplateController {

    /** dataset key -> {label, category}; keys match the importer endpoints. */
    static final Map<String, String[]> DATASETS = new LinkedHashMap<>();
    static {
        DATASETS.put("condition",        new String[]{"Condition Survey",        "Condition Data"});
        DATASETS.put("bridge",           new String[]{"Bridges (line)",          "Structures & Furniture"});
        DATASETS.put("culvert",          new String[]{"Culverts (point)",        "Structures & Furniture"});
        DATASETS.put("furniture_line",   new String[]{"Road Furniture — Line",   "Structures & Furniture"});
        DATASETS.put("furniture_point",  new String[]{"Road Furniture — Point",  "Structures & Furniture"});
        DATASETS.put("subgrade",         new String[]{"Sub-Grade Soil",          "Pavement & Geotechnical"});
        DATASETS.put("bituminous_core",  new String[]{"Bituminous Core",         "Pavement & Geotechnical"});
        DATASETS.put("pavement_crust",   new String[]{"Pavement Crust",          "Pavement & Geotechnical"});
        DATASETS.put("fwd",              new String[]{"FWD Deflection",          "FWD"});
        DATASETS.put("video_catalog",    new String[]{"Video Catalogue",         "Survey Videos"});
        DATASETS.put("traffic_stations", new String[]{"Traffic Stations",        "Traffic"});
        DATASETS.put("traffic_counts",   new String[]{"Traffic Counts",          "Traffic"});
    }

    private final JdbcTemplate jdbc;

    public ImportTemplateController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void ensure() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS import_templates (
                id serial PRIMARY KEY,
                name text NOT NULL,
                dataset_key text NOT NULL,
                category text,
                file_format text DEFAULT 'CSV',
                enabled boolean DEFAULT true,
                builtin boolean DEFAULT false,
                created_at timestamptz DEFAULT now(),
                updated_at timestamptz DEFAULT now()
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS import_template_columns (
                id serial PRIMARY KEY,
                template_id integer NOT NULL REFERENCES import_templates(id) ON DELETE CASCADE,
                field_name text NOT NULL,
                csv_column text NOT NULL,
                data_type text DEFAULT 'text',
                unit text,
                required boolean DEFAULT false,
                example text,
                sort integer DEFAULT 0
            )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS itc_template_idx ON import_template_columns(template_id)");
        seedDefaults();
    }

    /* ============================== CRUD ============================== */

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
            SELECT t.id, t.name, t.dataset_key, t.category, t.file_format, t.enabled, t.builtin,
                   to_char(t.updated_at, 'DD-Mon-YYYY') AS updated,
                   (SELECT count(*) FROM import_template_columns c WHERE c.template_id = t.id) AS columns
            FROM import_templates t ORDER BY t.category, t.name
            """);
    }

    @GetMapping("/datasets")
    public List<Map<String, String>> datasets() {
        List<Map<String, String>> out = new ArrayList<>();
        DATASETS.forEach((k, v) -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("key", k); m.put("label", v[0]); m.put("category", v[1]);
            out.add(m);
        });
        return out;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable int id) {
        Map<String, Object> t = jdbc.queryForMap(
            "SELECT id, name, dataset_key, category, file_format, enabled, builtin FROM import_templates WHERE id = ?", id);
        t.put("columns", jdbc.queryForList("""
            SELECT field_name, csv_column, data_type, unit, required, example
            FROM import_template_columns WHERE template_id = ? ORDER BY sort, id
            """, id));
        return t;
    }

    @PostMapping
    @Transactional
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        String key = str(body.get("dataset_key"));
        if (key == null || !DATASETS.containsKey(key)) return err("Unknown dataset: " + key);
        String name = str(body.get("name"));
        if (name == null) return err("Template name is required.");
        List<?> cols = body.get("columns") instanceof List<?> l ? l : List.of();
        if (cols.isEmpty()) return err("Add at least one column mapping.");
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        Integer id = jdbc.queryForObject("""
            INSERT INTO import_templates (name, dataset_key, category, enabled, builtin)
            VALUES (?,?,?,?,false) RETURNING id
            """, Integer.class, name, key, DATASETS.get(key)[1], enabled);
        saveColumns(id, cols);
        if (enabled) disableOthers(key, id);
        return Map.of("status", "ok", "id", id);
    }

    @PutMapping("/{id}")
    @Transactional
    public Map<String, Object> update(@PathVariable int id, @RequestBody Map<String, Object> body) {
        String key = str(body.get("dataset_key"));
        if (key == null || !DATASETS.containsKey(key)) return err("Unknown dataset: " + key);
        String name = str(body.get("name"));
        if (name == null) return err("Template name is required.");
        List<?> cols = body.get("columns") instanceof List<?> l ? l : List.of();
        if (cols.isEmpty()) return err("Add at least one column mapping.");
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        int n = jdbc.update("""
            UPDATE import_templates SET name = ?, dataset_key = ?, category = ?, enabled = ?, updated_at = now()
            WHERE id = ?
            """, name, key, DATASETS.get(key)[1], enabled, id);
        if (n == 0) return err("Template not found.");
        jdbc.update("DELETE FROM import_template_columns WHERE template_id = ?", id);
        saveColumns(id, cols);
        if (enabled) disableOthers(key, id);
        return Map.of("status", "ok", "id", id);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Object> delete(@PathVariable int id) {
        int n = jdbc.update("DELETE FROM import_templates WHERE id = ?", id);
        return n > 0 ? Map.of("status", "ok") : err("Template not found.");
    }

    @PostMapping("/{id}/clone")
    @Transactional
    public Map<String, Object> clone(@PathVariable int id) {
        Integer nid;
        try {
            nid = jdbc.queryForObject("""
                INSERT INTO import_templates (name, dataset_key, category, file_format, enabled, builtin)
                SELECT name || ' (copy)', dataset_key, category, file_format, false, false
                FROM import_templates WHERE id = ? RETURNING id
                """, Integer.class, id);
        } catch (Exception e) { return err("Template not found."); }
        jdbc.update("""
            INSERT INTO import_template_columns (template_id, field_name, csv_column, data_type, unit, required, example, sort)
            SELECT ?, field_name, csv_column, data_type, unit, required, example, sort
            FROM import_template_columns WHERE template_id = ?
            """, nid, id);
        return Map.of("status", "ok", "id", nid);
    }

    /* ======================= sample CSV download ======================= */

    @GetMapping("/{id}/sample")
    public ResponseEntity<byte[]> sample(@PathVariable int id) {
        Map<String, Object> t = jdbc.queryForMap("SELECT name FROM import_templates WHERE id = ?", id);
        List<Map<String, Object>> cols = jdbc.queryForList(
            "SELECT csv_column, example FROM import_template_columns WHERE template_id = ? ORDER BY sort, id", id);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(csv(str(cols.get(i).get("csv_column"))));
        }
        sb.append("\r\n");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(csv(str(cols.get(i).get("example"))));
        }
        sb.append("\r\n");
        String fname = String.valueOf(t.get("name")).replaceAll("[^A-Za-z0-9 _.-]", "").trim().replace(' ', '_');
        if (fname.isEmpty()) fname = "template";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fname + "_sample.csv\"")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /* ========================= import validation ========================= */

    /**
     * Validate an uploaded CSV against the enabled template of its dataset.
     * Nothing is imported here — the console calls this first and only proceeds
     * to the real importer when the file passes.
     *
     * Response:
     *   no_template — no enabled template for this dataset; import proceeds as-is
     *   invalid     — missing required columns and/or bad cells (errors capped)
     *   ok          — passes; "rename" maps actual header -> KLRAMS field for
     *                 every matched column whose header differs
     */
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestParam("dataset") String dataset,
                                        @RequestParam("file") MultipartFile file) {
        Map<String, Object> r = new LinkedHashMap<>();
        List<Map<String, Object>> tpl = jdbc.queryForList("""
            SELECT id, name FROM import_templates
            WHERE dataset_key = ? AND enabled = true ORDER BY updated_at DESC LIMIT 1
            """, dataset);
        if (tpl.isEmpty()) { r.put("status", "no_template"); return r; }
        int tid = ((Number) tpl.get(0).get("id")).intValue();
        r.put("template", tpl.get(0).get("name"));
        List<Map<String, Object>> cols = jdbc.queryForList("""
            SELECT field_name, csv_column, data_type, required
            FROM import_template_columns WHERE template_id = ? ORDER BY sort, id
            """, tid);
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(file.getBytes()), StandardCharsets.UTF_8));
            String headerLine = br.readLine();
            if (headerLine == null) { r.put("status", "invalid"); r.put("missing", List.of("(empty file)")); return r; }
            String[] header = parse(headerLine);
            Map<String, Integer> byNorm = new HashMap<>();
            for (int i = 0; i < header.length; i++)
                byNorm.putIfAbsent(norm(header[i]), i);

            // match each template column to an actual header cell
            List<String> missing = new ArrayList<>();
            Map<String, String> rename = new LinkedHashMap<>();   // actual header -> field_name
            Map<Integer, Map<String, Object>> matched = new LinkedHashMap<>(); // header idx -> template col
            for (Map<String, Object> c : cols) {
                String field = str(c.get("field_name")), csvCol = str(c.get("csv_column"));
                Integer i = byNorm.get(norm(csvCol));
                if (i == null) i = byNorm.get(norm(field));
                if (i == null) {
                    if (Boolean.TRUE.equals(c.get("required"))) missing.add(csvCol);
                    continue;
                }
                matched.put(i, c);
                String actual = header[i].trim().replace("﻿", "");
                if (field != null && !actual.equals(field)) rename.put(actual, field);
            }
            // columns in the file that no template column claims (info only —
            // asset/geo importers keep them as extra attributes)
            List<String> extra = new ArrayList<>();
            Set<String> claimed = new HashSet<>();
            for (Map<String, Object> c : cols) { claimed.add(norm(str(c.get("csv_column")))); claimed.add(norm(str(c.get("field_name")))); }
            for (String h : header) { String n = norm(h); if (!n.isEmpty() && !claimed.contains(n)) extra.add(h.trim()); }

            // cell checks: numbers parse, dates parse, required cells non-blank
            List<Map<String, Object>> errors = new ArrayList<>();
            int totalErrors = 0, rows = 0;
            String line;
            while ((line = br.readLine()) != null && rows < 20000) {
                if (line.trim().isEmpty()) continue;
                rows++;
                String[] c = parse(line);
                for (Map.Entry<Integer, Map<String, Object>> e : matched.entrySet()) {
                    int i = e.getKey();
                    String v = i < c.length ? c[i].trim() : "";
                    String type = String.valueOf(e.getValue().get("data_type"));
                    boolean req = Boolean.TRUE.equals(e.getValue().get("required"));
                    String problem = null;
                    if (v.isEmpty()) {
                        if (req) problem = "required value is blank";
                    } else if ("number".equals(type) && !isNumber(v)) {
                        problem = "not a number";
                    } else if ("date".equals(type) && !isDate(v)) {
                        problem = "not a recognised date (use dd/mm/yyyy, yyyy-mm-dd or 15-Apr-2020)";
                    }
                    if (problem != null) {
                        totalErrors++;
                        if (errors.size() < 30) {
                            Map<String, Object> em = new LinkedHashMap<>();
                            em.put("row", rows + 1);   // +1 for the header line
                            em.put("column", header[i].trim());
                            em.put("value", v.length() > 40 ? v.substring(0, 40) + "…" : v);
                            em.put("problem", problem);
                            errors.add(em);
                        }
                    }
                }
            }
            r.put("checked_rows", rows);
            if (!missing.isEmpty() || totalErrors > 0) {
                r.put("status", "invalid");
                if (!missing.isEmpty()) r.put("missing", missing);
                if (totalErrors > 0) { r.put("errors", errors); r.put("total_errors", totalErrors); }
                return r;
            }
            r.put("status", "ok");
            if (!rename.isEmpty()) r.put("rename", rename);
            if (!extra.isEmpty()) r.put("extra", extra);
            return r;
        } catch (Exception ex) {
            r.put("status", "error");
            r.put("message", "Could not read the file: " + ex.getMessage());
            return r;
        }
    }

    /* ============================ helpers ============================ */

    private void saveColumns(int templateId, List<?> cols) {
        int sort = 0;
        for (Object o : cols) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String field = str(m.get("field_name"));
            if (field == null) continue;
            String csvCol = str(m.get("csv_column"));
            String type = str(m.get("data_type"));
            jdbc.update("""
                INSERT INTO import_template_columns
                    (template_id, field_name, csv_column, data_type, unit, required, example, sort)
                VALUES (?,?,?,?,?,?,?,?)
                """, templateId, field, csvCol == null ? field : csvCol,
                type == null ? "text" : type, str(m.get("unit")),
                Boolean.TRUE.equals(m.get("required")), str(m.get("example")), sort++);
        }
    }

    private void disableOthers(String datasetKey, int keepId) {
        jdbc.update("UPDATE import_templates SET enabled = false WHERE dataset_key = ? AND id <> ?", datasetKey, keepId);
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error"); m.put("message", msg);
        return m;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    /** header normalisation for matching: lowercase, non-alphanumerics stripped */
    private static String norm(String s) {
        return s == null ? "" : s.replace("﻿", "").toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static boolean isNumber(String v) {
        try { Double.parseDouble(v.replace(",", "").replace(" ", "")); return true; }
        catch (Exception e) { return false; }
    }

    private static boolean isDate(String v) {
        return v.matches("\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{4}.*")      // dd/mm/yyyy
            || v.matches("\\d{4}[/\\-.]\\d{1,2}[/\\-.]\\d{1,2}.*")      // yyyy-mm-dd
            || v.matches("(?i)\\d{1,2}[\\- ][a-z]{3,9}[\\- ]\\d{4}");   // 15-Apr-2020
    }

    private static String csv(String v) {
        if (v == null) return "";
        return v.contains(",") || v.contains("\"") || v.contains("\n")
            ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private static String[] parse(String line) {
        List<String> out = new ArrayList<>(); StringBuilder sb = new StringBuilder(); boolean q = false;
        for (int i = 0; i < line.length(); i++) { char ch = line.charAt(i);
            if (q) { if (ch == '"') { if (i + 1 < line.length() && line.charAt(i + 1) == '"') { sb.append('"'); i++; } else q = false; } else sb.append(ch); }
            else { if (ch == '"') q = true; else if (ch == ',') { out.add(sb.toString()); sb.setLength(0); } else sb.append(ch); } }
        out.add(sb.toString());
        return out.toArray(new String[0]);
    }

    /* ====================== default template seeds ====================== */

    /** field, type, unit, required, example — csv_column starts equal to field. */
    private record Col(String field, String type, String unit, boolean req, String ex) {}

    private void seedDefaults() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM import_templates", Long.class);
        if (n != null && n > 0) return;

        seed("condition", List.of(
            new Col("Survey_Type", "text", null, false, "NSV"),
            new Col("Section_Label", "text", null, true, "TVM_MDR_0001"),
            new Col("XSP", "text", null, true, "L1"),
            new Col("Start_Chainage", "number", "Meters", true, "0"),
            new Col("End_Chainage", "number", "Meters", true, "100"),
            new Col("IRI", "number", "m/km", false, "3.2"),
            new Col("CRACK", "number", "%", false, "1.5"),
            new Col("Pothole", "number", "Count", false, "0"),
            new Col("Rutting", "number", "Millimeters", false, "4.1"),
            new Col("Texture", "number", "Millimeters", false, "0.7"),
            new Col("Patch_Work", "number", "%", false, "0.4"),
            new Col("Ravelling", "number", "%", false, "0.2"),
            new Col("Start_Latitude", "number", "Degrees", false, "8.5241"),
            new Col("Start_Longitude", "number", "Degrees", false, "76.9366"),
            new Col("End_Latitude", "number", "Degrees", false, "8.5249"),
            new Col("End_Longitude", "number", "Degrees", false, "76.9374")));

        seed("bridge", List.of(
            new Col("Section_Label", "text", null, true, "TVM_MDR_0001"),
            new Col("Start_Chainage", "number", "Meters", true, "1200"),
            new Col("End_Chainage", "number", "Meters", true, "1260"),
            new Col("Bridge_Name", "text", null, false, "Karamana Bridge"),
            new Col("Structure_Type", "text", null, false, "RCC"),
            new Col("Remarks", "text", null, false, "Good condition")));

        seed("culvert", List.of(
            new Col("Section_Label", "text", null, true, "TVM_MDR_0001"),
            new Col("Chainage", "number", "Meters", true, "850"),
            new Col("Culvert_Type", "text", null, false, "Pipe"),
            new Col("Latitude", "number", "Degrees", false, "8.5241"),
            new Col("Longitude", "number", "Degrees", false, "76.9366"),
            new Col("Remarks", "text", null, false, "Remarks")));

        seed("furniture_line", List.of(
            new Col("Section_Label", "text", null, true, "TVM_MDR_0001"),
            new Col("Start_Chainage", "number", "Meters", true, "400"),
            new Col("End_Chainage", "number", "Meters", true, "520"),
            new Col("Furniture_Type", "text", null, false, "Crash Barrier"),
            new Col("Side", "text", null, false, "Left"),
            new Col("Remarks", "text", null, false, "Remarks")));

        seed("furniture_point", List.of(
            new Col("Section_Label", "text", null, true, "TVM_MDR_0001"),
            new Col("Chainage", "number", "Meters", true, "300"),
            new Col("Furniture_Type", "text", null, false, "Sign Board"),
            new Col("Side", "text", null, false, "Right"),
            new Col("Latitude", "number", "Degrees", false, "8.5241"),
            new Col("Longitude", "number", "Degrees", false, "76.9366"),
            new Col("Remarks", "text", null, false, "Remarks")));

        seed("subgrade", List.of(
            new Col("Section_Label", "text", null, true, "TVM_MDR_0001"),
            new Col("Chainage", "number", "Meters", true, "500"),
            new Col("CBR", "number", "%", false, "6.5"),
            new Col("MDD", "number", "g/cc", false, "1.92"),
            new Col("OMC", "number", "%", false, "11.4"),
            new Col("FDD", "number", "g/cc", false, "1.85"),
            new Col("FMC", "number", "%", false, "10.2"),
            new Col("LL", "number", "%", false, "34"),
            new Col("PL", "number", "%", false, "21"),
            new Col("PI", "number", "%", false, "13"),
            new Col("Soil Type", "text", null, false, "SC"),
            new Col("Date", "date", null, false, "15-Apr-2020"),
            new Col("Remarks", "text", null, false, "Remarks")));

        seed("bituminous_core", List.of(
            new Col("Section_Label", "text", null, true, "TVM_MDR_0001"),
            new Col("Chainage", "number", "Meters", true, "500"),
            new Col("Core No", "text", null, false, "C-12"),
            new Col("Bulk Density of Binder Course gmcc", "number", "g/cc", false, "1.2"),
            new Col("Bulk Density of Wearing Course gmcc", "number", "g/cc", false, "1.4"),
            new Col("Total Observed bituminous layers thickness mm", "number", "Millimeters", false, "2.2"),
            new Col("Date", "date", null, false, "15-Apr-2020"),
            new Col("Remarks", "text", null, false, "Remarks")));

        seed("pavement_crust", List.of(
            new Col("Section_Label", "text", null, true, "TVM_MDR_0001"),
            new Col("Chainage", "number", "Meters", true, "500"),
            new Col("Surface Thickness", "number", "Millimeters", false, "40"),
            new Col("Surface Type", "text", null, false, "BC"),
            new Col("Base Thickness", "number", "Millimeters", false, "250"),
            new Col("Base Type", "text", null, false, "WMM"),
            new Col("Sub Base Thickness", "number", "Millimeters", false, "200"),
            new Col("Sub Base Type", "text", null, false, "GSB"),
            new Col("Sub Grade CBR", "number", "%", false, "6.5"),
            new Col("Sub Grade Soil Type", "text", null, false, "SC"),
            new Col("Date", "date", null, false, "15-Apr-2020"),
            new Col("Remarks", "text", null, false, "Remarks")));

        List<Col> fwd = new ArrayList<>(List.of(
            new Col("Section_Label", "text", null, true, "TVM_MDR_0001"),
            new Col("From", "number", "Meters", true, "0"),
            new Col("To", "number", "Meters", true, "100"),
            new Col("D0", "number", "Microns", true, "412")));
        for (int i = 1; i <= 9; i++)
            fwd.add(new Col("D" + i, "number", "Microns", false, String.valueOf(400 - i * 35)));
        seed("fwd", fwd);

        seed("video_catalog", List.of(
            new Col("section_label", "text", null, true, "TVM_MDR_0001"),
            new Col("video_file", "text", null, true, "TVM_MDR_0001_front.mp4"),
            new Col("direction", "text", null, true, "front")));

        seed("traffic_stations", List.of(
            new Col("Station Name", "text", null, true, "TVM_STN_021"),
            new Col("Description", "text", null, false, "Kazhakkoottam Junction"),
            new Col("Section Label", "text", null, true, "TVM_MDR_0001"),
            new Col("Chainage", "number", "Meters", true, "1500"),
            new Col("Latitude", "number", "Degrees", false, "8.5241"),
            new Col("Longitude", "number", "Degrees", false, "76.9366"),
            new Col("Xsp Code", "text", null, false, "L1")));

        seed("traffic_counts", List.of(
            new Col("STATION_NAME", "text", null, true, "TVM_STN_021"),
            new Col("DATE", "date", null, true, "15/04/2026"),
            new Col("TIME", "text", null, true, "08:00"),
            new Col("DIRECTION", "text", null, false, "Up"),
            new Col("Car", "number", "Count", false, "42"),
            new Col("Bus", "number", "Count", false, "6"),
            new Col("LCV", "number", "Count", false, "9"),
            new Col("HCV", "number", "Count", false, "4"),
            new Col("Two Wheeler", "number", "Count", false, "88"),
            new Col("Three Wheeler", "number", "Count", false, "17"),
            new Col("Bicycle", "number", "Count", false, "5")));
    }

    private void seed(String key, List<Col> cols) {
        String[] meta = DATASETS.get(key);
        Integer id = jdbc.queryForObject("""
            INSERT INTO import_templates (name, dataset_key, category, enabled, builtin)
            VALUES (?,?,?,true,true) RETURNING id
            """, Integer.class, meta[0], key, meta[1]);
        int sort = 0;
        for (Col c : cols)
            jdbc.update("""
                INSERT INTO import_template_columns
                    (template_id, field_name, csv_column, data_type, unit, required, example, sort)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, c.field(), c.field(), c.type(), c.unit(), c.req(), c.ex(), sort++);
    }
}
