package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the RMMS road-condition CSV and stores every row in the "condition" table.
 * Milestone 1: each segment's geometry is a straight line between its start and end
 * GPS coordinates. Milestone 2 will replace this with true linear referencing
 * (ST_LineSubstring) along the road centreline.
 */
@Service
public class ConditionService {

    private final JdbcTemplate jdbc;
    private final LayerAttributeService attributes;
    private final RoadColumns roadColumns;

    public ConditionService(JdbcTemplate jdbc, LayerAttributeService attributes, RoadColumns roadColumns) {
        this.jdbc = jdbc;
        this.attributes = attributes;
        this.roadColumns = roadColumns;
    }

    /** Create/extend the table at boot, so a new column is there before the first upload or read. */
    @jakarta.annotation.PostConstruct
    void init() {
        try { ensureSchema(); } catch (Exception e) { /* the first upload retries */ }
    }

    @Transactional
    public void ensureSchema() {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS postgis");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS condition (
                id serial PRIMARY KEY,
                survey_type text,
                section_label text,
                xsp text,
                iri double precision,
                crack double precision,
                pothole double precision,
                rutting double precision,
                texture double precision,
                patch_work double precision,
                ravelling double precision,
                start_chainage double precision,
                end_chainage double precision,
                start_lat double precision,
                start_lng double precision,
                end_lat double precision,
                end_lng double precision,
                geom geometry(LineString, 4326),
                period_id integer
            )
            """);
        // Older databases predate the period_id column (see SurveyPeriodService).
        jdbc.execute("ALTER TABLE condition ADD COLUMN IF NOT EXISTS period_id integer");
        // Part of a condition row's identity (with section, lane and chainage range).
        jdbc.execute("ALTER TABLE condition ADD COLUMN IF NOT EXISTS section_start_date text");
        jdbc.execute("CREATE INDEX IF NOT EXISTS condition_geom_idx ON condition USING GIST (geom)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS condition_section_idx ON condition (section_label)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS condition_period_idx ON condition (period_id)");
    }

    /** Why this file cannot be imported (a mandatory key column is absent), or null. */
    public String missingKeyColumn(InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String headerLine = br.readLine();
        if (headerLine == null) return null;
        Map<String, Integer> idx = indexHeaders(parseCsvLine(headerLine));
        if (!idx.containsKey(LayerAttributeService.norm("Section_Start_Date"))) {
            return "Section_Start_Date is a mandatory column for condition data (Layer Management). "
                 + "A condition row is identified by Section Label + XSP + From/To chainage + Section Start Date. "
                 + "Add the column and upload again.";
        }
        return null;
    }

    /** Section Label -> the distinct Section_Start_Date values in the file. */
    public Map<String, Set<String>> startDatesBySection(InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        Map<String, Set<String>> out = new LinkedHashMap<>();
        String headerLine = br.readLine();
        if (headerLine == null) return out;
        Map<String, Integer> idx = indexHeaders(parseCsvLine(headerLine));
        String line;
        while ((line = br.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            String[] c = parseCsvLine(line);
            String sec = get(c, idx, "Section_Label"), d = get(c, idx, "Section_Start_Date");
            if (sec != null && d != null) out.computeIfAbsent(sec, k -> new LinkedHashSet<>()).add(d);
        }
        return out;
    }

    /** @return {rows loaded, rows that replaced an existing row, rows skipped for a blank Section_Start_Date} */
    @Transactional
    public int[] loadCsv(InputStream in, int periodId) throws Exception {
        ensureSchema();
        // Additive by section WITHIN the chosen survey period: replace only the
        // section labels present in THIS file for THIS period, so uploading a new
        // survey cycle never touches older periods' data, and re-uploading a
        // section refreshes just that one within its period.

        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String headerLine = br.readLine();
        if (headerLine == null) return new int[]{0, 0, 0};

        String[] headers = parseCsvLine(headerLine);
        Map<String, Integer> idx = indexHeaders(headers);

        final String sql =
            "INSERT INTO condition (survey_type, section_label, xsp, iri, crack, pothole, rutting, " +
            "texture, patch_work, ravelling, start_chainage, end_chainage, start_lat, start_lng, " +
            "end_lat, end_lng, period_id, section_start_date, geom) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, " +
            "ST_SetSRID(ST_MakeLine(ST_MakePoint(?,?), ST_MakePoint(?,?)), 4326))";

        // A row is identified by Section_Label + XSP + From/To chainage + Section Start Date
        // (within the survey period): a row with the same identity replaces that one row,
        // any other is added, and nothing else in the section is touched.
        List<Object[]> batch = new ArrayList<>();
        String line;
        int count = 0, replacedRows = 0, skippedRows = 0;
        while ((line = br.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            String[] c = parseCsvLine(line);

            String section = get(c, idx, "Section_Label");
            String startDate = get(c, idx, "Section_Start_Date");
            if (section == null || startDate == null) { skippedRows++; continue; }
            replacedRows += jdbc.update(
                "DELETE FROM condition WHERE section_label = ? AND period_id = ? "
                + "AND xsp IS NOT DISTINCT FROM ? AND start_chainage IS NOT DISTINCT FROM ? "
                + "AND end_chainage IS NOT DISTINCT FROM ? AND (section_start_date = ? OR section_start_date IS NULL)",
                section, periodId, get(c, idx, "XSP"),
                num(get(c, idx, "Start_Chainage")), num(get(c, idx, "End_Chainage")), startDate);

            Double slng = num(get(c, idx, "Start_Longitude"));
            Double slat = num(get(c, idx, "Start_Latitude"));
            Double elng = num(get(c, idx, "End_Longitude"));
            Double elat = num(get(c, idx, "End_Latitude"));

            batch.add(new Object[]{
                get(c, idx, "Survey_Type"),
                section,
                get(c, idx, "XSP"),
                num(get(c, idx, "IRI")),
                num(get(c, idx, "CRACK")),
                num(get(c, idx, "Pothole")),
                num(get(c, idx, "Rutting")),
                num(get(c, idx, "Texture")),
                num(get(c, idx, "Patch_Work")),
                num(get(c, idx, "Ravelling")),
                num(get(c, idx, "Start_Chainage")),
                num(get(c, idx, "End_Chainage")),
                slat, slng, elat, elng,
                periodId, startDate,
                slng, slat, elng, elat
            });
            count++;
            if (batch.size() >= 500) {
                jdbc.batchUpdate(sql, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) jdbc.batchUpdate(sql, batch);
        return new int[]{count, replacedRows, skippedRows};
    }

    /**
     * Scans the CSV for duplicate survey rows — the same (Section_Label, XSP,
     * Start_Chainage, End_Chainage) appearing more than once. Duplicates inflate
     * every lane-km total (dashboard, network-scope card, reports) because a raw
     * row sum counts the stretch twice while the segment build collapses it, so
     * the upload pauses for user confirmation whenever any are present.
     * Returns: rows (total data rows), duplicates (extra copies beyond the first),
     * dup_km (lane-km those extra copies double-count), sections (per-section
     * breakdown, worst first).
     */
    public Map<String, Object> analyzeDuplicates(InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String headerLine = br.readLine();
        Map<String, Object> rep = new HashMap<>();
        rep.put("rows", 0); rep.put("duplicates", 0); rep.put("dup_km", 0.0);
        rep.put("sections", new ArrayList<>());
        if (headerLine == null) return rep;

        String[] headers = parseCsvLine(headerLine);
        Map<String, Integer> idx = indexHeaders(headers);

        Map<String, Integer> seen = new HashMap<>();
        Map<String, double[]> perSection = new HashMap<>();   // section -> {extraRows, extraMetres}
        String line;
        int total = 0, extra = 0;
        double extraM = 0;
        while ((line = br.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            String[] c = parseCsvLine(line);
            total++;
            String section = get(c, idx, "Section_Label");
            Double s = num(get(c, idx, "Start_Chainage"));
            Double e = num(get(c, idx, "End_Chainage"));
            String key = section + "|" + get(c, idx, "XSP") + "|" + s + "|" + e;
            if (seen.merge(key, 1, Integer::sum) > 1) {
                double len = (s != null && e != null && e > s) ? (e - s) : 0;
                extra++;
                extraM += len;
                perSection.computeIfAbsent(section == null ? "?" : section, k -> new double[2]);
                double[] agg = perSection.get(section == null ? "?" : section);
                agg[0]++; agg[1] += len;
            }
        }

        List<Map<String, Object>> sections = new ArrayList<>();
        perSection.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue()[1], a.getValue()[1]))
            .forEach(en -> {
                Map<String, Object> m = new HashMap<>();
                m.put("section", en.getKey());
                m.put("rows", (int) en.getValue()[0]);
                m.put("km", Math.round(en.getValue()[1] / 10.0) / 100.0);
                sections.add(m);
            });

        rep.put("rows", total);
        rep.put("duplicates", extra);
        rep.put("dup_km", Math.round(extraM / 10.0) / 100.0);
        rep.put("sections", sections);
        return rep;
    }

    /**
     * Which Section_Labels in the CSV already carry condition rows in the chosen
     * survey period? Importing would replace those sections' rows (see loadCsv),
     * so the upload pauses for user confirmation when any are present. Returns a
     * per-section breakdown: section, n (stored rows), from_ch/to_ch (stored
     * chainage range) — empty when nothing would be replaced.
     */
    public List<Map<String, Object>> analyzeExisting(InputStream in, int periodId) throws Exception {
        ensureSchema();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String headerLine = br.readLine();
        List<Map<String, Object>> hits = new ArrayList<>();
        if (headerLine == null) return hits;

        String[] headers = parseCsvLine(headerLine);
        Map<String, Integer> idx = indexHeaders(headers);

        Set<String> sections = new LinkedHashSet<>();
        String line;
        while ((line = br.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            String section = get(parseCsvLine(line), idx, "Section_Label");
            if (section != null) sections.add(section);
        }

        for (String section : sections) {
            Map<String, Object> row = jdbc.queryForMap(
                "SELECT count(*) AS n, min(start_chainage) AS from_ch, max(end_chainage) AS to_ch " +
                "FROM condition WHERE section_label = ? AND period_id = ?", section, periodId);
            if (((Number) row.get("n")).intValue() > 0) {
                Map<String, Object> m = new HashMap<>();
                m.put("section", section);
                m.put("n", row.get("n"));
                m.put("from_ch", row.get("from_ch"));
                m.put("to_ch", row.get("to_ch"));
                hits.add(m);
            }
        }
        return hits;
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM condition", Long.class);
        return n == null ? 0 : n;
    }

    /** Below this, a "gap" is noise (survey rounding, adjoining rows a metre apart) rather
     *  than a real missed stretch. */
    private static final double GAP_MIN_M = 1.0;
    /** Two intervals within this of each other are treated as touching, not gapped —
     *  the same rounding slack {@link VideoService} uses for chainage checks. */
    private static final double TOUCH_TOLERANCE_M = 1.0;

    /**
     * Chainage coverage gaps in the raw condition rows, for every Section_Label that
     * carries at least one row in the chosen survey period — a section with no data
     * uploaded at all is not a "gap", it simply has nothing to report, so it is left out
     * entirely rather than showing as one gap spanning the whole road.
     *
     * <p>For each such section, every row's [start_chainage, end_chainage] is merged into
     * a coverage span (independent of XSP — one lane surveyed at a stretch counts that
     * stretch covered), then compared against the section's own reference length
     * ({@link RoadColumns#lenExpr}, the same chainage-span/measured-length/geometry
     * fallback every other linear-referenced module uses). Any uncovered stretch over
     * {@link #GAP_MIN_M} — including a missed run at the very start or end of the section —
     * is one row of the report.
     *
     * <p>A section whose label does not match the road network at all is skipped here
     * (its reference length is unknown, so "gap" is not answerable) — that mismatch is
     * already surfaced by {@code /api/survey-dashboard/unmapped?type=nsv_lane_km}.
     *
     * <p>Sorted district, then section, then chainage — district-wise reading is the
     * point of the report, and {@code (unmapped)} sorts last on purpose so real
     * districts are not pushed down by rows the roads join could not place.
     */
    public List<Map<String, Object>> gapReport(int periodId) {
        Map<String, List<double[]>> bySection = new LinkedHashMap<>();
        jdbc.query(
            "SELECT section_label, start_chainage, end_chainage FROM condition " +
            "WHERE period_id = ? AND section_label IS NOT NULL ORDER BY section_label, start_chainage",
            rs -> {
                String sec = rs.getString("section_label");
                double s = rs.getDouble("start_chainage");
                double e = rs.getDouble("end_chainage");
                if (e < s) { double t = s; s = e; e = t; }
                bySection.computeIfAbsent(sec, k -> new ArrayList<>()).add(new double[]{s, e});
            }, periodId);
        if (bySection.isEmpty()) return List.of();

        Map<String, Double> lengths = new HashMap<>();
        Map<String, String> districts = new HashMap<>();
        List<String> sections = new ArrayList<>(bySection.keySet());
        String placeholders = String.join(",", Collections.nCopies(sections.size(), "?"));
        try {
            jdbc.query(
                "SELECT r.\"Section_La\" AS sec, " + roadColumns.lenExpr("r") + " AS len, " +
                "COALESCE(NULLIF(trim(r.\"District\"),''),'(unmapped)') AS district " +
                "FROM roads r WHERE r.\"Section_La\" IN (" + placeholders + ")",
                rs -> {
                    lengths.put(rs.getString("sec"), rs.getDouble("len"));
                    districts.put(rs.getString("sec"), rs.getString("district"));
                }, sections.toArray());
        } catch (Exception e) {
            // roads table missing/unavailable — nothing is answerable; report stays empty
            // rather than guessing a length.
            return List.of();
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<double[]>> en : bySection.entrySet()) {
            String sec = en.getKey();
            Double length = lengths.get(sec);
            if (length == null || length <= 0) continue; // unmatched road / no reference length

            List<double[]> merged = mergeIntervals(en.getValue());
            double cursor = 0;
            for (double[] iv : merged) {
                if (iv[0] - cursor > GAP_MIN_M) out.add(gapRow(sec, districts.get(sec), cursor, iv[0]));
                cursor = Math.max(cursor, iv[1]);
            }
            if (length - cursor > GAP_MIN_M) out.add(gapRow(sec, districts.get(sec), cursor, length));
        }

        out.sort(Comparator
            .<Map<String, Object>, String>comparing(m -> "(unmapped)".equals(m.get("district")) ? "￿" : (String) m.get("district"))
            .thenComparing(m -> (String) m.get("section_label"), Comparator.nullsLast(String::compareTo))
            .thenComparing(m -> (Double) m.get("gap_start_m")));
        return out;
    }

    private static Map<String, Object> gapRow(String section, String district, double from, double to) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("section_label", section);
        m.put("district", district == null ? "(unmapped)" : district);
        m.put("gap_start_m", round1(from));
        m.put("gap_end_m", round1(to));
        m.put("gap_length_m", round1(to - from));
        return m;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** Sorted, overlap/near-touch merged coverage intervals for one section. */
    private static List<double[]> mergeIntervals(List<double[]> raw) {
        List<double[]> ivs = new ArrayList<>(raw);
        ivs.removeIf(iv -> iv[1] <= iv[0]);
        ivs.sort(Comparator.comparingDouble(iv -> iv[0]));
        List<double[]> out = new ArrayList<>();
        for (double[] iv : ivs) {
            if (!out.isEmpty() && iv[0] <= out.get(out.size() - 1)[1] + TOUCH_TOLERANCE_M) {
                double[] last = out.get(out.size() - 1);
                last[1] = Math.max(last[1], iv[1]);
            } else {
                out.add(iv.clone());
            }
        }
        return out;
    }

    /**
     * Index a CSV header by the storage key the condition layer declares for
     * each column, resolved through its alias list — and by the raw header too.
     *
     * This used to be an exact string match on the header alone, which meant a
     * return spelling it "Section Label" rather than "Section_Label" imported
     * every row with a null section and null readings: no error, just an empty
     * survey. Districts do not agree on these spellings, so the match now goes
     * through Attribute Data, where a new one is added without a code change.
     *
     * The raw header stays indexed as well, so a database where the registry
     * never initialised still resolves the canonical spellings the lookups
     * below ask for. putIfAbsent throughout: on a file that carries both a raw
     * header and another column aliased to the same key, the leftmost wins,
     * which is the same column the old exact match would have taken.
     *
     * Shared by the importer and by both pre-import guards, so a file that
     * imports is the same file the duplicate and re-upload checks looked at.
     */
    private Map<String, Integer> indexHeaders(String[] headers) {
        LayerAttributeService.HeaderResolver resolver =
                attributes.headerResolver("condition", "default");
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            String raw = headers[i].trim().replace("﻿", "");
            // Normalised, so case, spaces and underscores never have to agree:
            // "Section_Label", "Section Label" and "section label" are one key,
            // and the lookups below keep working with no registry at all.
            idx.putIfAbsent(LayerAttributeService.norm(raw), i);
            /* The alias list catches what normalising alone cannot — "Label"
               for the section, "Cracking" for crack.

               Both the storage key and the LABEL are indexed, because the
               lookups below name the field the way the survey return does
               (Start_Latitude) while the column is start_lat. Indexing only one
               of the two would leave half of them unresolvable. */
            String key = resolver.keyFor(raw);
            if (key != null) idx.putIfAbsent(LayerAttributeService.norm(key), i);
            String label = resolver.labelFor(raw);
            if (label != null) idx.putIfAbsent(LayerAttributeService.norm(label), i);
        }
        return idx;
    }

    private static String get(String[] c, Map<String, Integer> idx, String name) {
        Integer i = idx.get(LayerAttributeService.norm(name));
        if (i == null || i >= c.length) return null;
        String v = c[i].trim();
        return v.isEmpty() ? null : v;
    }

    private static Double num(String s) {
        if (s == null) return null;
        try { return Double.parseDouble(s); } catch (Exception e) { return null; }
    }

    /** Minimal CSV line parser that respects double-quoted fields. */
    private static String[] parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { sb.append('"'); i++; }
                    else inQuotes = false;
                } else sb.append(ch);
            } else {
                if (ch == '"') inQuotes = true;
                else if (ch == ',') { out.add(sb.toString()); sb.setLength(0); }
                else sb.append(ch);
            }
        }
        out.add(sb.toString());
        return out.toArray(new String[0]);
    }
}
