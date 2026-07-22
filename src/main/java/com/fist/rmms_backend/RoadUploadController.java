package com.fist.rmms_backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Road network upload. Accepts GeoJSON (the shapefile is parsed in the browser),
 * validates it, then either REPLACES the whole roads table or MERGES by
 * "Section_La" (update existing, add new, keep the rest).
 *
 * Safety:
 *  - requires a non-empty FeatureCollection of (Multi)LineStrings
 *  - requires every feature to carry a non-blank Section_La
 *  - only writes columns that already exist in the roads table (case-sensitive),
 *    casting numerics by the table's own column types
 *  - validation failures abort BEFORE any change to existing data
 */
@RestController
@RequestMapping("/api/roads")
public class RoadUploadController {

    private final JdbcTemplate jdbc;
    private final ObjectMapper om = new ObjectMapper();
    private final RoadController roadController;   // to clear its in-memory GeoJSON cache after upload

    public RoadUploadController(JdbcTemplate jdbc, RoadController roadController) {
        this.jdbc = jdbc;
        this.roadController = roadController;
    }

    @PostMapping("/upload")
    @Transactional
    public Map<String, Object> upload(@RequestParam(defaultValue = "merge") String mode,
                                      @RequestParam(defaultValue = "false") boolean force,
                                      @RequestBody String body) {
        Map<String, Object> r = new HashMap<>();
        try {
            JsonNode gj = om.readTree(body);
            JsonNode feats = gj.get("features");
            if (feats == null || !feats.isArray() || feats.size() == 0)
                return err(r, "No features found in the uploaded file.");

            // table columns and types (excluding id and geom)
            List<Map<String, Object>> cols = jdbc.queryForList(
                "SELECT column_name, data_type, character_maximum_length FROM information_schema.columns " +
                "WHERE table_name='roads' AND column_name NOT IN ('id','geom')");
            Map<String, String> colType = new LinkedHashMap<>();
            Map<String, Integer> colMaxLen = new LinkedHashMap<>();   // width-limited char columns only
            for (Map<String, Object> c : cols) {
                String name = String.valueOf(c.get("column_name"));
                String type = String.valueOf(c.get("data_type"));
                colType.put(name, type);
                Object ml = c.get("character_maximum_length");
                if (ml instanceof Number && (type.startsWith("character")))
                    colMaxLen.put(name, ((Number) ml).intValue());
            }
            if (!colType.containsKey("Section_La"))
                return err(r, "roads table has no Section_La column.");

            // validate every feature first (abort before touching data)
            List<String> problems = new ArrayList<>();
            for (int i = 0; i < feats.size(); i++) {
                JsonNode f = feats.get(i);
                JsonNode p = f.get("properties");
                JsonNode g = f.get("geometry");
                String sec = p != null && p.hasNonNull("Section_La") ? p.get("Section_La").asText().trim() : "";
                if (sec.isEmpty()) problems.add("feature " + (i + 1) + ": missing Section_La");
                String gt = g != null && g.hasNonNull("type") ? g.get("type").asText() : "";
                if (!gt.equals("LineString") && !gt.equals("MultiLineString"))
                    problems.add("feature " + (i + 1) + ": geometry is " + (gt.isEmpty() ? "missing" : gt) + ", expected LineString");
                if (problems.size() >= 8) break;
            }
            if (!problems.isEmpty())
                return err(r, "Validation failed — nothing was changed. " + String.join("; ", problems));

            // Re-upload guard (merge mode): sections already in the roads table would
            // be silently overwritten by this file. When force=false nothing is
            // written — the response lists them (status="exists") and the console
            // asks the user to confirm; re-posting with force=true performs the
            // update. Replace mode is confirmed client-side before the request.
            if (!mode.equalsIgnoreCase("replace") && !force) {
                Set<String> incoming = new LinkedHashSet<>();
                for (JsonNode f : feats)
                    incoming.add(f.get("properties").get("Section_La").asText().trim());
                String in = String.join(",", Collections.nCopies(incoming.size(), "?"));
                List<String> existing = jdbc.queryForList(
                        "SELECT DISTINCT \"Section_La\" FROM roads WHERE \"Section_La\" IN (" + in + ") ORDER BY 1",
                        String.class, incoming.toArray());
                if (!existing.isEmpty()) {
                    r.put("status", "exists");
                    r.put("existing", existing);
                    r.put("incoming_sections", incoming.size());
                    return r;
                }
            }

            // The roads table inherited fixed widths from the original shapefile
            // import (e.g. Road_Name varchar(42)); longer values from a new
            // district would abort the whole upload. Widen any char column the
            // incoming data overflows to text before inserting.
            Set<String> widen = new LinkedHashSet<>();
            for (JsonNode f : feats) {
                JsonNode p = f.get("properties");
                Iterator<String> wi = p.fieldNames();
                while (wi.hasNext()) {
                    String k = wi.next();
                    Integer cap = colMaxLen.get(k);
                    JsonNode v = p.get(k);
                    if (cap == null || v == null || v.isNull()) continue;
                    if (v.asText().length() > cap) widen.add(k);
                }
            }
            for (String k : widen) {
                // Defence in depth: this DDL interpolates the column name, so refuse
                // anything that isn't a real roads column or that carries a double
                // quote (the only breakout char inside a quoted identifier). Keys
                // here already come from information_schema, so this never rejects
                // legitimate uploads — it just closes the interpolation off entirely.
                if (!colType.containsKey(k) || k.indexOf('"') >= 0) continue;
                jdbc.execute("ALTER TABLE roads ALTER COLUMN \"" + k + "\" TYPE text");
            }
            if (!widen.isEmpty()) r.put("widened_columns", new ArrayList<>(widen));

            int replaced = 0, inserted = 0, updated = 0;
            if (mode.equalsIgnoreCase("replace")) {
                replaced = jdbc.update("DELETE FROM roads");
            }

            for (JsonNode f : feats) {
                JsonNode p = f.get("properties");
                String sec = p.get("Section_La").asText().trim();
                String geomJson = om.writeValueAsString(f.get("geometry"));

                if (!mode.equalsIgnoreCase("replace")) {
                    int d = jdbc.update("DELETE FROM roads WHERE \"Section_La\" = ?", sec);
                    if (d > 0) updated++; else inserted++;
                } else inserted++;

                // build insert from intersecting columns
                List<String> names = new ArrayList<>();
                List<Object> vals = new ArrayList<>();
                Iterator<String> it = p.fieldNames();
                while (it.hasNext()) {
                    String k = it.next();
                    if (!colType.containsKey(k)) continue;
                    JsonNode v = p.get(k);
                    if (v == null || v.isNull()) continue;
                    String t = colType.get(k);
                    Object val;
                    String tl = t.toLowerCase();
                    if (tl.contains("bigint") || tl.contains("integer") || tl.equals("smallint")
                            || tl.contains("int8") || tl.contains("int4") || tl.contains("int2")) {
                        // whole-number columns must receive a Long, never a Double,
                        // or Postgres rejects "type double precision" into bigint/int.
                        try {
                            String sv = v.asText().trim();
                            if (sv.isEmpty()) continue;
                            val = Long.valueOf(Math.round(Double.parseDouble(sv)));
                        } catch (Exception e) { continue; }
                    } else if (tl.contains("numeric") || tl.contains("decimal")
                            || tl.contains("double") || tl.contains("real") || tl.contains("float")) {
                        try { val = Double.parseDouble(v.asText().trim()); }
                        catch (Exception e) { continue; }
                    } else {
                        val = v.asText();
                    }
                    names.add('"' + k + '"');
                    vals.add(val);
                }
                String sql = "INSERT INTO roads (" + String.join(",", names) + ", geom) VALUES (" +
                        String.join(",", Collections.nCopies(names.size(), "?")) +
                        ", ST_SetSRID(ST_Multi(ST_GeomFromGeoJSON(?)),4326))";
                vals.add(geomJson);
                jdbc.update(sql, vals.toArray());
            }

            r.put("status", "ok");
            r.put("mode", mode);
            r.put("inserted", inserted);
            r.put("updated", updated);
            if (mode.equalsIgnoreCase("replace")) r.put("removed_old", replaced);
            Long total = jdbc.queryForObject("SELECT count(*) FROM roads", Long.class);
            r.put("total_roads", total);
            // Roads changed -> drop RoadController's cached GeoJSON so the very next
            // map load serves the new network (no restart / manual refresh needed).
            roadController.refresh();
            return r;
        } catch (Exception e) {
            // surface the real cause to the Data Console instead of a blank 500
            Throwable root = e; while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            return err(r, "Upload failed: " + (root.getMessage() != null ? root.getMessage() : root.toString()));
        }
    }

    private Map<String, Object> err(Map<String, Object> r, String m) {
        r.put("status", "error");
        r.put("message", m);
        return r;
    }
}
