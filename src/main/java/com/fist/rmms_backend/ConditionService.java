package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

    public ConditionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
                geom geometry(LineString, 4326)
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS condition_geom_idx ON condition USING GIST (geom)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS condition_section_idx ON condition (section_label)");
    }

    @Transactional
    public int loadCsv(InputStream in) throws Exception {
        ensureSchema();
        // Additive by section: replace only the section labels present in THIS file,
        // so uploading another section adds to the data and re-uploading a section
        // refreshes just that one. (Previously this TRUNCATEd the whole table on
        // every upload, which wiped all previously-loaded sections.)

        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String headerLine = br.readLine();
        if (headerLine == null) return 0;

        String[] headers = parseCsvLine(headerLine);
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            idx.put(headers[i].trim().replace("\uFEFF", ""), i);
        }

        final String sql =
            "INSERT INTO condition (survey_type, section_label, xsp, iri, crack, pothole, rutting, " +
            "texture, patch_work, ravelling, start_chainage, end_chainage, start_lat, start_lng, " +
            "end_lat, end_lng, geom) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, " +
            "ST_SetSRID(ST_MakeLine(ST_MakePoint(?,?), ST_MakePoint(?,?)), 4326))";

        // Track which section labels we've already cleared during this upload so each
        // incoming section is wiped once (removing its prior rows) before we append.
        Set<String> replaced = new HashSet<>();
        List<Object[]> batch = new ArrayList<>();
        String line;
        int count = 0;
        while ((line = br.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            String[] c = parseCsvLine(line);

            String section = get(c, idx, "Section_Label");
            if (section != null && replaced.add(section)) {
                jdbc.update("DELETE FROM condition WHERE section_label = ?", section);
            }

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
                slng, slat, elng, elat
            });
            count++;
            if (batch.size() >= 500) {
                jdbc.batchUpdate(sql, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) jdbc.batchUpdate(sql, batch);
        return count;
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM condition", Long.class);
        return n == null ? 0 : n;
    }

    private static String get(String[] c, Map<String, Integer> idx, String name) {
        Integer i = idx.get(name);
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
