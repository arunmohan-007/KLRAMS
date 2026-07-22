package com.fist.rmms_backend;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * User-manual figure images for /manual.html.
 *
 *  The manual page ships with a placeholder for every figure; administrators
 *  upload the real screenshots from the page itself. Images are stored inside
 *  PostgreSQL (bytea) like Government Orders, so pg_dump keeps the manual
 *  complete — nothing loose on disk.
 *
 *  Access (see SecurityConfig — no extra rules needed):
 *    GET  /api/manual/images        any signed-in user (list + upload dates)
 *    GET  /api/manual/images/{name} any signed-in user (the image bytes)
 *    POST /api/manual/images        ADMIN (wildcard POST /api/** rule)
 *    DELETE /api/manual/images/{n}  ADMIN (wildcard DELETE /api/** rule)
 *
 *  Responses carry an explicit Cache-Control so Spring Security's no-store
 *  default doesn't force a re-download of every figure on each visit; the
 *  page cache-busts with ?v=<uploaded_at> after a replacement.
 */
@RestController
@RequestMapping("/api/manual")
public class ManualController {

    /** fig-04-01-gis-overview.png etc. — lower-case, no paths, image extension only. */
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,120}\\.(png|jpg|jpeg|webp)$");

    private final JdbcTemplate jdbc;
    public ManualController(JdbcTemplate jdbc){ this.jdbc = jdbc; }

    @PostConstruct
    public void init(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS manual_images (" +
                "name TEXT PRIMARY KEY, content_type TEXT, size_bytes BIGINT, " +
                "data BYTEA NOT NULL, uploaded_at TIMESTAMPTZ DEFAULT now())");
    }

    @GetMapping("/images")
    public List<Map<String,Object>> list(){
        return jdbc.queryForList(
                "SELECT name, content_type, size_bytes, " +
                "       (extract(epoch FROM uploaded_at)*1000)::bigint AS uploaded_at " +
                "FROM manual_images ORDER BY name");
    }

    @GetMapping("/images/{name}")
    public ResponseEntity<byte[]> image(@PathVariable String name){
        if(!SAFE_NAME.matcher(name).matches()) return ResponseEntity.badRequest().build();
        List<Map<String,Object>> rows = jdbc.queryForList(
                "SELECT content_type, data FROM manual_images WHERE name=?", name);
        if(rows.isEmpty()) return ResponseEntity.notFound().build();
        String ct = (String) rows.get(0).get("content_type");
        if(ct == null || ct.isEmpty()) ct = "image/png";
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePrivate())
                .header(HttpHeaders.CONTENT_TYPE, ct)
                .body((byte[]) rows.get(0).get("data"));
    }

    @PostMapping("/images")
    public Map<String,Object> upload(@RequestParam("name") String name,
                                     @RequestParam("file") MultipartFile file){
        Map<String,Object> r = new HashMap<>();
        try {
            name = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
            if(!SAFE_NAME.matcher(name).matches()){ r.put("ok", false); r.put("error", "Invalid figure name"); return r; }
            if(file == null || file.isEmpty()){ r.put("ok", false); r.put("error", "No file selected"); return r; }
            String ct = file.getContentType();
            if(ct == null || !ct.startsWith("image/")){ r.put("ok", false); r.put("error", "Only image files are accepted"); return r; }
            jdbc.update("INSERT INTO manual_images(name, content_type, size_bytes, data) VALUES (?,?,?,?) " +
                        "ON CONFLICT (name) DO UPDATE SET content_type=EXCLUDED.content_type, " +
                        "size_bytes=EXCLUDED.size_bytes, data=EXCLUDED.data, uploaded_at=now()",
                    name, ct, file.getSize(), file.getBytes());
            r.put("ok", true); r.put("name", name);
        } catch(Exception e){ r.put("ok", false); r.put("error", ApiErrors.safe("manual image upload", e)); }
        return r;
    }

    @DeleteMapping("/images/{name}")
    public Map<String,Object> remove(@PathVariable String name){
        Map<String,Object> r = new HashMap<>();
        if(!SAFE_NAME.matcher(name).matches()){ r.put("ok", false); r.put("error", "Invalid figure name"); return r; }
        jdbc.update("DELETE FROM manual_images WHERE name=?", name);
        r.put("ok", true);
        return r;
    }
}
