package com.fist.rmms_backend;

import jakarta.annotation.PostConstruct;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Upload log — an audit trail of every dataset import done from the Data Console:
 * date/time, dataset, file name, status and a short detail, plus the signed-in
 * user (taken server-side from the session, never trusted from the client).
 * The console posts one entry per import and reads the list back for display.
 */
@RestController
@RequestMapping("/api/upload-log")
public class UploadLogController {

    private final JdbcTemplate jdbc;

    public UploadLogController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS upload_log (
                id serial PRIMARY KEY,
                ts timestamptz NOT NULL DEFAULT now(),
                dataset text,
                filename text,
                status text,
                detail text,
                username text
            )""");
        /* The uploaded CSV itself, kept so it can be downloaded from the log. Held for
           RETAIN_DAYS and then purged; the log line itself stays. */
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS upload_log_file (
                log_id integer PRIMARY KEY REFERENCES upload_log(id) ON DELETE CASCADE,
                created timestamptz NOT NULL DEFAULT now(),
                content_type text,
                size_bytes bigint,
                content bytea NOT NULL
            )""");
    }

    static final int RETAIN_DAYS = 30;

    private void purgeExpired() {
        try {
            jdbc.update("DELETE FROM upload_log_file WHERE created < now() - interval '" + RETAIN_DAYS + " days'");
        } catch (Exception ignored) { }
    }

    @PostMapping("/{id}/file")
    public Map<String, Object> attach(@PathVariable int id, @RequestParam("file") MultipartFile file) {
        Map<String, Object> r = new HashMap<>();
        try {
            purgeExpired();
            jdbc.update("""
                INSERT INTO upload_log_file(log_id, content_type, size_bytes, content) VALUES (?,?,?,?)
                ON CONFLICT (log_id) DO UPDATE SET content = EXCLUDED.content, size_bytes = EXCLUDED.size_bytes,
                    content_type = EXCLUDED.content_type, created = now()""",
                    id, file.getContentType(), file.getSize(), file.getBytes());
            r.put("status", "ok");
        } catch (Exception e) {
            r.put("status", "error");
            r.put("message", ApiErrors.safe("upload log file", e));
        }
        return r;
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> download(@PathVariable int id) {
        purgeExpired();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT l.filename, f.content_type, f.content FROM upload_log_file f " +
                "JOIN upload_log l ON l.id = f.log_id WHERE f.log_id = ?", id);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        Map<String, Object> row = rows.get(0);
        String name = row.get("filename") == null ? "upload" : String.valueOf(row.get("filename"));
        String ct = row.get("content_type") == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : String.valueOf(row.get("content_type"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(name, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(ct.contains("html") ? MediaType.APPLICATION_OCTET_STREAM_VALUE : ct))
                .body((byte[]) row.get("content"));
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "200") int limit) {
        int lim = Math.max(1, Math.min(limit, 1000));
        try {
            purgeExpired();
            return jdbc.queryForList(
                "SELECT l.id, to_char(l.ts,'YYYY-MM-DD HH24:MI:SS') AS ts, l.dataset, l.filename, l.status, l.detail, l.username, " +
                "(f.log_id IS NOT NULL) AS has_file, f.size_bytes, " +
                "to_char(f.created + interval '" + RETAIN_DAYS + " days','YYYY-MM-DD') AS expires " +
                "FROM upload_log l LEFT JOIN upload_log_file f ON f.log_id = l.id " +
                "ORDER BY l.ts DESC, l.id DESC LIMIT " + lim);
        } catch (Exception e) {
            return List.of();
        }
    }

    @PostMapping
    public Map<String, Object> record(@RequestBody Map<String, Object> body, Authentication auth) {
        Map<String, Object> r = new HashMap<>();
        try {
            String user = (auth != null) ? auth.getName() : null;
            Integer id = jdbc.queryForObject(
                    "INSERT INTO upload_log(dataset,filename,status,detail,username) VALUES (?,?,?,?,?) RETURNING id",
                    Integer.class,
                    str(body.get("dataset")), str(body.get("filename")),
                    str(body.get("status")), str(body.get("detail")), user);
            r.put("status", "ok");
            r.put("id", id);
        } catch (Exception e) {
            r.put("status", "error");
            r.put("message", ApiErrors.safe("upload log read", e));
        }
        return r;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
