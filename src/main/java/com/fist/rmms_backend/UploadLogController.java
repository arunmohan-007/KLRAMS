package com.fist.rmms_backend;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
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
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "200") int limit) {
        int lim = Math.max(1, Math.min(limit, 1000));
        try {
            return jdbc.queryForList(
                "SELECT to_char(ts,'YYYY-MM-DD HH24:MI:SS') AS ts, dataset, filename, status, detail, username " +
                "FROM upload_log ORDER BY ts DESC, id DESC LIMIT " + lim);
        } catch (Exception e) {
            return List.of();
        }
    }

    @PostMapping
    public Map<String, Object> record(@RequestBody Map<String, Object> body, Authentication auth) {
        Map<String, Object> r = new HashMap<>();
        try {
            String user = (auth != null) ? auth.getName() : null;
            jdbc.update("INSERT INTO upload_log(dataset,filename,status,detail,username) VALUES (?,?,?,?,?)",
                    str(body.get("dataset")), str(body.get("filename")),
                    str(body.get("status")), str(body.get("detail")), user);
            r.put("status", "ok");
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
