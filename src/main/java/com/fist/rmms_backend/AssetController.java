package com.fist.rmms_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
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

    private final JdbcTemplate jdbc;
    private final ObjectMapper om = new ObjectMapper();

    public AssetController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
                geom geometry
            )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS road_assets_type_idx ON road_assets(asset_type)");
    }

    @PostMapping("/{type}/upload")
    @Transactional
    public Map<String, Object> upload(@PathVariable String type,
                                      @RequestParam("file") MultipartFile file) {
        Map<String, Object> r = new HashMap<>();
        type = type.toLowerCase();
        boolean isLine = LINE_TYPES.contains(type);
        if (!isLine && !POINT_TYPES.contains(type)) {
            r.put("status", "error"); r.put("message", "Unknown asset type: " + type);
            return r;
        }
        try {
            ensure();
            BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
            String header = br.readLine();
            if (header == null) { r.put("status","error"); r.put("message","Empty CSV"); return r; }
            String[] cols = parse(header);
            Map<String,Integer> idx = new HashMap<>();
            for (int i=0;i<cols.length;i++)
                idx.put(cols[i].trim().toLowerCase().replace("\uFEFF","").replace(' ','_'), i);

            Integer iSec = first(idx, "section_label","section_la","section_label_code","label","road");
            Integer iStart = first(idx, "start_chainage","start_chiange","start","chainage","chiange","from_chainage","from");
            Integer iLat = first(idx, "point_latitude","latitude","lat");
            Integer iLon = first(idx, "point_longitude","longitude","lon","lng");
            Integer iEnd = first(idx, "end_chainage","end_chiange","end","to_chainage");
            if (iSec == null || iStart == null)
                { r.put("status","error"); r.put("message","CSV must have Section_Label and "+(isLine?"Start_Chainage/End_Chainage":"Chainage")); return r; }
            if (isLine && iEnd == null)
                { r.put("status","error"); r.put("message","Line assets need End_Chainage too"); return r; }

            // replace this asset type's rows
            jdbc.update("DELETE FROM road_assets WHERE asset_type = ?", type);

            int loaded=0, skipped=0;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] c = parse(line);
                String sec = val(c, iSec);
                Double s = num(val(c, iStart));
                Double e = isLine ? num(val(c, iEnd)) : null;
                if (sec == null || s == null || (isLine && (e == null || e <= s))) { skipped++; continue; }
                // keep every column as attrs
                Map<String,String> attrs = new LinkedHashMap<>();
                for (int i=0;i<cols.length && i<c.length;i++) {
                    String v = c[i].trim();
                    if (!v.isEmpty()) attrs.put(cols[i].trim(), v);
                }
                Double lat = iLat != null ? num(val(c, iLat)) : null;
                Double lon = iLon != null ? num(val(c, iLon)) : null;
                if (!isLine && lat != null && lon != null && lat != 0 && lon != 0) {
                    jdbc.update("INSERT INTO road_assets (asset_type, section_label, start_chainage, end_chainage, attrs, geom) VALUES (?,?,?,?,?::jsonb, ST_SetSRID(ST_MakePoint(?,?),4326))",
                            type, sec, s, e, om.writeValueAsString(attrs), lon, lat);
                } else {
                    jdbc.update("INSERT INTO road_assets (asset_type, section_label, start_chainage, end_chainage, attrs) VALUES (?,?,?,?,?::jsonb)",
                            type, sec, s, e, om.writeValueAsString(attrs));
                }
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

    @GetMapping(value = "/{type}/geojson", produces = MediaType.APPLICATION_JSON_VALUE)
    public String geojson(@PathVariable String type) {
        try {
            ensure();
            String g = jdbc.queryForObject("""
                SELECT json_build_object('type','FeatureCollection','features',
                    COALESCE(json_agg(json_build_object(
                        'type','Feature',
                        'geometry', ST_AsGeoJSON(geom)::json,
                        'properties', jsonb_build_object(
                            'road', section_label,
                            'from_ch', start_chainage,
                            'to_ch', end_chainage) || COALESCE(attrs,'{}'::jsonb)
                    )), '[]'::json))::text
                FROM road_assets WHERE asset_type = ? AND geom IS NOT NULL
                """, String.class, type.toLowerCase());
            return g;
        } catch (Exception e) {
            return "{\"type\":\"FeatureCollection\",\"features\":[]}";
        }
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
