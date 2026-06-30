package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * Government Orders (GO) repository for the KHRI / RMMS Cell portal.
 *
 *  Folders are stored as plain path strings, so "RMMS Cell", "CRN" and any
 *  custom or nested folder such as "RMMS Cell/Circulars" all work — the UI
 *  builds the path; this controller just stores it.
 *
 *  Files are kept inside PostgreSQL (bytea), so a single database backup
 *  (pg_dump) preserves every Government Order — nothing lives loose on disk.
 *
 *  Tables are created automatically on startup. CSRF is disabled and /api/**
 *  is session-authenticated, so these endpoints work over the normal login.
 *
 *  NOTE: PDFs can exceed Spring's default 1 MB upload limit. Add to
 *  application.properties:
 *      spring.servlet.multipart.max-file-size=25MB
 *      spring.servlet.multipart.max-request-size=30MB
 */
@RestController
@RequestMapping("/api/go")
public class GoController {

    private final JdbcTemplate jdbc;
    public GoController(JdbcTemplate jdbc){ this.jdbc = jdbc; }

    @PostConstruct
    public void init(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS go_folders (" +
                "id SERIAL PRIMARY KEY, name TEXT UNIQUE NOT NULL, created_at TIMESTAMPTZ DEFAULT now())");
        jdbc.execute("CREATE TABLE IF NOT EXISTS go_documents (" +
                "id SERIAL PRIMARY KEY, go_name TEXT NOT NULL, go_number TEXT, folder TEXT NOT NULL, " +
                "orig_name TEXT, content_type TEXT, size_bytes BIGINT, data BYTEA NOT NULL, " +
                "uploaded_at TIMESTAMPTZ DEFAULT now())");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_go_documents_folder ON go_documents(folder)");
        seed("RMMS Cell");
        seed("CRN");
    }
    private void seed(String n){
        Integer c = jdbc.queryForObject("SELECT count(*) FROM go_folders WHERE name=?", Integer.class, n);
        if(c == null || c == 0) jdbc.update("INSERT INTO go_folders(name) VALUES (?)", n);
    }

    /* ---- folders ---- */

    @GetMapping("/folders")
    public List<String> folders(){
        return jdbc.queryForList("SELECT name FROM go_folders ORDER BY name", String.class);
    }

    @PostMapping("/folders")
    public Map<String,Object> addFolder(@RequestBody Map<String,String> body){
        Map<String,Object> r = new HashMap<>();
        String name = body.get("name");
        if(name == null || name.trim().isEmpty()){ r.put("ok", false); r.put("error", "Folder name required"); return r; }
        name = name.trim().replaceAll("\\s*/\\s*", "/");   // tidy any path separators
        try {
            jdbc.update("INSERT INTO go_folders(name) VALUES (?) ON CONFLICT (name) DO NOTHING", name);
            r.put("ok", true); r.put("name", name);
        } catch(Exception e){ r.put("ok", false); r.put("error", e.getMessage()); }
        return r;
    }

    @DeleteMapping("/folders")
    public Map<String,Object> delFolder(@RequestParam String name){
        Map<String,Object> r = new HashMap<>();
        Integer docs = jdbc.queryForObject(
                "SELECT count(*) FROM go_documents WHERE folder=? OR folder LIKE ?",
                Integer.class, name, name + "/%");
        if(docs != null && docs > 0){ r.put("ok", false); r.put("error", "Folder still has Government Orders in it"); return r; }
        jdbc.update("DELETE FROM go_folders WHERE name=? OR name LIKE ?", name, name + "/%");
        r.put("ok", true);
        return r;
    }

    /* ---- documents ---- */

    @GetMapping("/docs")
    public List<Map<String,Object>> docs(@RequestParam(required=false) String folder,
                                         @RequestParam(required=false) String q){
        StringBuilder sql = new StringBuilder(
                "SELECT id, go_name, go_number, folder, orig_name, content_type, size_bytes, uploaded_at " +
                "FROM go_documents WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if(folder != null && !folder.isEmpty()){ sql.append(" AND folder=?"); args.add(folder); }
        if(q != null && !q.trim().isEmpty()){
            sql.append(" AND (go_name ILIKE ? OR go_number ILIKE ? OR orig_name ILIKE ?)");
            String like = "%" + q.trim() + "%";
            args.add(like); args.add(like); args.add(like);
        }
        sql.append(" ORDER BY uploaded_at DESC");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @PostMapping("/upload")
    public Map<String,Object> upload(@RequestParam("file") MultipartFile file,
                                     @RequestParam("go_name") String goName,
                                     @RequestParam(value="go_number", required=false) String goNumber,
                                     @RequestParam("folder") String folder){
        Map<String,Object> r = new HashMap<>();
        try {
            if(file == null || file.isEmpty()){ r.put("ok", false); r.put("error", "No file selected"); return r; }
            if(goName == null || goName.trim().isEmpty()){ r.put("ok", false); r.put("error", "GO name required"); return r; }
            if(folder == null || folder.trim().isEmpty()) folder = "RMMS Cell";
            folder = folder.trim();
            jdbc.update("INSERT INTO go_folders(name) VALUES (?) ON CONFLICT (name) DO NOTHING", folder);
            jdbc.update("INSERT INTO go_documents(go_name, go_number, folder, orig_name, content_type, size_bytes, data) " +
                        "VALUES (?,?,?,?,?,?,?)",
                    goName.trim(), goNumber, folder, file.getOriginalFilename(),
                    file.getContentType(), file.getSize(), file.getBytes());
            r.put("ok", true);
        } catch(Exception e){ r.put("ok", false); r.put("error", e.getMessage()); }
        return r;
    }

    @GetMapping("/file/{id}")
    public ResponseEntity<byte[]> file(@PathVariable long id){
        List<Map<String,Object>> rows = jdbc.queryForList(
                "SELECT orig_name, content_type, data FROM go_documents WHERE id=?", id);
        if(rows.isEmpty()) return ResponseEntity.notFound().build();
        Map<String,Object> m = rows.get(0);
        byte[] data = (byte[]) m.get("data");
        String ct = (String) m.get("content_type");
        if(ct == null || ct.isEmpty()) ct = "application/octet-stream";
        String fn = (String) m.get("orig_name");
        if(fn == null || fn.isEmpty()) fn = "GO-" + id;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, ct)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fn.replace("\"", "") + "\"")
                .body(data);
    }

    @DeleteMapping("/docs/{id}")
    public Map<String,Object> delDoc(@PathVariable long id){
        jdbc.update("DELETE FROM go_documents WHERE id=?", id);
        Map<String,Object> r = new HashMap<>(); r.put("ok", true); return r;
    }
}
