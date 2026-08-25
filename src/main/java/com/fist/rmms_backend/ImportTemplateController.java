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
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final LayerAttributeService attributes;
    private final LookupService lookups;

    public ImportTemplateController(JdbcTemplate jdbc, LayerAttributeService attributes,
                                    LookupService lookups) {
        this.jdbc = jdbc;
        this.attributes = attributes;
        this.lookups = lookups;
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
        fixDateExamples();
    }

    /**
     * Templates seeded before the dd-mmm-yyyy standard carry examples in the old
     * numeric formats (traffic_counts DATE was 15/04/2026). Those examples feed the
     * sample-CSV download, so leaving them in place hands surveyors a file that the
     * validator now rejects. Rewrite any date example that fails isDate().
     */
    private void fixDateExamples() {
        for (Map<String, Object> c : jdbc.queryForList(
                "SELECT id, example FROM import_template_columns WHERE data_type = 'date'")) {
            String ex = str(c.get("example"));
            if (ex != null && isDate(ex)) continue;
            jdbc.update("UPDATE import_template_columns SET example = ? WHERE id = ?",
                        toStdDate(ex), c.get("id"));
        }
    }

    /** Best-effort rewrite of a legacy example into dd-mmm-yyyy; falls back to a canonical one. */
    private static String toStdDate(String v) {
        Matcher m = Pattern.compile("^(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{4})$")
                           .matcher(v == null ? "" : v.trim());
        if (m.matches()) {
            int mo = Integer.parseInt(m.group(2));
            if (mo >= 1 && mo <= 12) {
                String mon = MONTHS.get(mo - 1);
                return String.format("%02d-%s%s-%s", Integer.parseInt(m.group(1)),
                    mon.substring(0, 1).toUpperCase(), mon.substring(1), m.group(3));
            }
        }
        return "15-Apr-2020";
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
        Map<String, Object> t = jdbc.queryForMap(
            "SELECT name, dataset_key, builtin FROM import_templates WHERE id = ?", id);
        List<Map<String, Object>> cols = jdbc.queryForList(
            "SELECT csv_column, example FROM import_template_columns WHERE template_id = ? ORDER BY sort, id", id);

        /* Same rule as validate(): a template the RMMS cell built themselves is
           theirs, but a built-in one no longer decides anything — the layer's
           attributes do. Handing a surveyor a six-column sample while the
           mapping window expects seventeen is how a "correct" file comes back
           with eleven columns the system did not ask for. */
        if (Boolean.TRUE.equals(t.get("builtin"))) {
            List<Map<String, Object>> declared = attributes.importColumns(str(t.get("dataset_key")));
            if (!declared.isEmpty()) {
                Map<String, String> examples = new LinkedHashMap<>();
                for (Map<String, Object> c : cols) {
                    examples.put(norm(str(c.get("csv_column"))), str(c.get("example")));
                }
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> d : declared) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    String label = str(d.get("field_name"));
                    m.put("csv_column", label);
                    // Reuse the seeded example where the two lists overlap, so a
                    // column that already had a realistic sample value keeps it.
                    String ex = examples.get(norm(label));
                    if (ex == null) ex = examples.get(norm(str(d.get("storage_key"))));
                    m.put("example", ex != null ? ex : exampleFor(str(d.get("data_type"))));
                    out.add(m);
                }
                cols = out;
            }
        }
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

        /* Where the expected column list comes from, in priority order:
             1. a template the RMMS cell BUILT themselves and enabled — an
                explicit override, so it wins outright;
             2. the layer's attributes, which is the list Attribute Data shows
                and the importers actually resolve against;
             3. the seeded built-in template, for a dataset with no layer
                (video_catalog) or a database where the registry never came up.
           Order matters: putting the attributes above the seeded templates is
           what stops this screen and Attribute Data disagreeing about what a
           dataset's columns are. */
        List<Map<String, Object>> tpl = jdbc.queryForList("""
            SELECT id, name, builtin FROM import_templates
            WHERE dataset_key = ? AND enabled = true ORDER BY updated_at DESC LIMIT 1
            """, dataset);
        boolean custom = !tpl.isEmpty() && !Boolean.TRUE.equals(tpl.get(0).get("builtin"));

        List<Map<String, Object>> cols;
        if (custom) {
            r.put("template", tpl.get(0).get("name"));
            cols = jdbc.queryForList("""
                SELECT field_name, csv_column, data_type, required
                FROM import_template_columns WHERE template_id = ? ORDER BY sort, id
                """, ((Number) tpl.get(0).get("id")).intValue());
        } else {
            cols = attributes.importColumns(dataset);
            if (!cols.isEmpty()) {
                r.put("template", "Attribute Data · " + dataset);
            } else if (!tpl.isEmpty()) {
                r.put("template", tpl.get(0).get("name"));
                cols = jdbc.queryForList("""
                    SELECT field_name, csv_column, data_type, required
                    FROM import_template_columns WHERE template_id = ? ORDER BY sort, id
                    """, ((Number) tpl.get(0).get("id")).intValue());
            }
        }
        if (cols.isEmpty()) { r.put("status", "no_template"); return r; }
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
            /* Every expected column and the file column it resolved to, matched
               or not. Returned always, not only when something is wrong: the
               screen's job is to show WHAT it mapped, and a bare "all columns
               match" tick gives whoever is importing no way to check that the
               system understood their file the way they meant it. */
            List<Map<String, Object>> mapping = new ArrayList<>();

            for (Map<String, Object> c : cols) {
                String field = str(c.get("field_name")), csvCol = str(c.get("csv_column"));
                Integer i = byNorm.get(norm(csvCol));
                if (i == null) i = byNorm.get(norm(field));
                // The accepted column names from Attribute Data. Without this a
                // district whose return says "Section Label" while the attribute
                // is labelled "Section_Label" would be told a required column is
                // missing — even though the importer would have resolved it.
                if (i == null) {
                    for (String alias : aliasesOf(c)) {
                        i = byNorm.get(norm(alias));
                        if (i != null) break;
                    }
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("field", field);
                row.put("storageKey", c.get("storage_key"));
                row.put("type", c.get("data_type"));
                row.put("required", Boolean.TRUE.equals(c.get("required")));
                mapping.add(row);

                if (i == null) {
                    row.put("column", null);
                    if (Boolean.TRUE.equals(c.get("required"))) missing.add(csvCol);
                    continue;
                }
                matched.put(i, c);
                String actual = header[i].trim().replace("﻿", "");
                row.put("column", actual);
                if (field != null && !actual.equals(field)) rename.put(actual, field);
            }
            // columns in the file that no expected column claims (info only —
            // asset/geo importers keep them as extra attributes)
            List<String> extra = new ArrayList<>();
            Set<String> claimed = new HashSet<>();
            for (Map<String, Object> c : cols) {
                claimed.add(norm(str(c.get("csv_column"))));
                claimed.add(norm(str(c.get("field_name"))));
                claimed.add(norm(str(c.get("storage_key"))));
                for (String alias : aliasesOf(c)) claimed.add(norm(alias));
            }
            for (String h : header) { String n = norm(h); if (!n.isEmpty() && !claimed.contains(n)) extra.add(h.trim()); }

            /* The permitted values of every coded column in this file, resolved
               once. Per column rather than per cell: the list is fixed for the
               whole file, and looking it up per row would put a query inside the
               parse loop. */
            Map<Integer, Set<String>> permitted = new HashMap<>();
            String[] layerTarget = attributes.layerForDataset(dataset);
            if (layerTarget != null) {
                for (Map.Entry<Integer, Map<String, Object>> e : matched.entrySet()) {
                    if (e.getValue().get("lookup_key") == null) continue;
                    Set<String> ok = lookups.permittedValues(
                            layerTarget[0], layerTarget[1], str(e.getValue().get("field_name")));
                    if (ok != null && !ok.isEmpty()) permitted.put(e.getKey(), ok);
                }
            }

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
                        problem = "wrong date format — use " + DATE_FORMAT_HINT;
                    } else if (permitted.containsKey(i) && !permitted.get(i).contains(norm(v))) {
                        /* A coded column accepts its short codes and its lookup
                           values, and nothing else. Without this the restriction
                           would be something the Lookup screen claims and the
                           importer ignores, and a typo would land in the data as
                           a value no card can decode. */
                        problem = "not one of the permitted values for this attribute";
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
            // The mapping goes out on BOTH paths. A file that failed a cell check
            // still mapped its columns, and hiding that is what leaves someone
            // guessing whether the failure is their data or our matching.
            r.put("mapping", mapping);
            if (!extra.isEmpty()) r.put("extra", extra);
            if (!rename.isEmpty()) r.put("rename", rename);
            if (!missing.isEmpty() || totalErrors > 0) {
                r.put("status", "invalid");
                if (!missing.isEmpty()) r.put("missing", missing);
                if (totalErrors > 0) { r.put("errors", errors); r.put("total_errors", totalErrors); }
                return r;
            }
            r.put("status", "ok");
            return r;
        } catch (Exception ex) {
            r.put("status", "error");
            r.put("message", ApiErrors.safe("template validation", ex));
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

    /**
     * KLRAMS standard date format — dd-mmm-yyyy (15-Apr-2020) and nothing else.
     *
     * Numeric formats used to be accepted too, but they are ambiguous across the
     * agencies that submit data: 05/04/2026 reads as either 5 April or 4 May, and
     * a 2-digit year (05-04-26) is unreadable altogether. A mis-read date silently
     * collapses a multi-day survey into one day and inflates every per-day figure
     * derived from it, so files are now rejected at upload rather than guessed at.
     */
    static final String DATE_FORMAT_HINT = "dd-mmm-yyyy (e.g. 15-Apr-2020)";
    private static final Pattern DATE_STD =
        Pattern.compile("(?i)^(\\d{2})-(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)-(\\d{4})$");
    private static final List<String> MONTHS =
        List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");

    static boolean isDate(String v) {
        Matcher m = DATE_STD.matcher(v == null ? "" : v.trim());
        if (!m.matches()) return false;
        // the pattern allows 00-31 in any month; reject days the calendar does not have
        try {
            LocalDate.of(Integer.parseInt(m.group(3)),
                         MONTHS.indexOf(m.group(2).toLowerCase()) + 1,
                         Integer.parseInt(m.group(1)));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String csv(String v) {
        if (v == null || v.isEmpty()) return "";
        // Neutralise spreadsheet formula injection: a cell beginning with = + - @
        // (or a leading tab/CR) is evaluated as a formula by Excel/Sheets. Prefix
        // such values with a single quote so they render as literal text.
        char c0 = v.charAt(0);
        if (c0 == '=' || c0 == '+' || c0 == '-' || c0 == '@' || c0 == '\t' || c0 == '\r') v = "'" + v;
        return v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")
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

    /**
     * The accepted column names carried on an expected column, or empty.
     *
     * A column sourced from a stored template has none — the aliases live in
     * Attribute Data — so this quietly returns nothing rather than making every
     * caller check which source the list came from.
     */
    /**
     * A placeholder for a declared column the seeded templates never had.
     *
     * A blank cell would be worse than a wrong one: the sample is what tells a
     * surveyor what shape a value takes, and a date in particular has exactly
     * one accepted format that the row has to demonstrate.
     */
    private static String exampleFor(String type) {
        return switch (type == null ? "text" : type) {
            case "number" -> "0";
            case "date" -> "15-Apr-2020";
            default -> "";
        };
    }

    private static List<String> aliasesOf(Map<String, Object> col) {
        String raw = str(col.get("aliases"));
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String a : raw.split(",")) if (!a.isBlank()) out.add(a.trim());
        return out;
    }

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
        fwd.add(new Col("Latitude", "number", "Degrees", false, "8.5241"));
        fwd.add(new Col("Longitude", "number", "Degrees", false, "76.9366"));
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
            new Col("DATE", "date", null, true, "15-Apr-2026"),
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
