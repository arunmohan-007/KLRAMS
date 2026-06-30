package com.fist.rmms_backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Handles retained survey videos:
 *  - storeZip:    extract an uploaded .zip of videos into the video folder (kept on disk).
 *  - loadCatalog: read a CSV (section_label, video_file, direction) into road_video table.
 *  - catalog:     return the catalog for the map to look up videos by road.
 */
@Service
public class VideoService {

    private final JdbcTemplate jdbc;
    private final Path videoDir;

    public VideoService(JdbcTemplate jdbc, @Value("${app.video-dir:video-store}") String dir) throws IOException {
        this.jdbc = jdbc;
        this.videoDir = Paths.get(dir).toAbsolutePath();
        Files.createDirectories(this.videoDir);
    }

    @Transactional
    public void ensureSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS road_video (
                section_label text PRIMARY KEY,
                video_file    text,
                direction     text
            )
            """);
    }

    /** Extract every file from the uploaded zip into the video folder (retained). */
    public int storeZip(MultipartFile zip) throws IOException {
        int count = 0;
        byte[] buf = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(zip.getInputStream())) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) { zis.closeEntry(); continue; }
                // basename only -> protects against zip-slip path traversal
                String name = Paths.get(e.getName()).getFileName().toString();
                if (name.isEmpty()) { zis.closeEntry(); continue; }
                Path out = videoDir.resolve(name);
                try (OutputStream os = Files.newOutputStream(out,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    int n;
                    while ((n = zis.read(buf)) > 0) os.write(buf, 0, n);
                }
                count++;
                zis.closeEntry();
            }
        }
        return count;
    }

    /** Read the catalog CSV: section_label, video_file, direction. */
    @Transactional
    public int loadCatalog(InputStream in) throws Exception {
        ensureSchema();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String header = br.readLine();
        if (header == null) return 0;
        String[] cols = parse(header);
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < cols.length; i++) {
            idx.put(cols[i].trim().toLowerCase().replace("\uFEFF", ""), i);
        }
        Integer iRoad = first(idx, "section_label", "section_la", "road");
        Integer iFile = first(idx, "video_file", "video", "file", "link");
        Integer iDir  = first(idx, "direction", "dir");
        if (iRoad == null || iFile == null) {
            throw new IllegalArgumentException("CSV must have columns: section_label and video_file (and optionally direction).");
        }
        int count = 0;
        String line;
        while ((line = br.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            String[] c = parse(line);
            String road = val(c, iRoad);
            String file = val(c, iFile);
            String dir  = normDir(iDir != null ? val(c, iDir) : null);
            if (road == null || file == null) continue;
            jdbc.update("""
                INSERT INTO road_video (section_label, video_file, direction)
                VALUES (?,?,?)
                ON CONFLICT (section_label)
                DO UPDATE SET video_file = EXCLUDED.video_file, direction = EXCLUDED.direction
                """, road, file, dir);
            count++;
        }
        return count;
    }

    public List<Map<String, Object>> catalog() {
        try {
            return jdbc.queryForList(
                "SELECT section_label AS road, video_file AS file, direction FROM road_video");
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static String normDir(String d) {
        if (d == null) return "forward";
        d = d.trim().toLowerCase();
        if (d.startsWith("rev") || d.startsWith("back") || d.startsWith("dec")) return "reverse";
        return "forward";
    }

    private static Integer first(Map<String, Integer> idx, String... names) {
        for (String n : names) if (idx.containsKey(n)) return idx.get(n);
        return null;
    }

    private static String val(String[] c, int i) {
        if (i >= c.length) return null;
        String v = c[i].trim();
        return v.isEmpty() ? null : v;
    }

    private static String[] parse(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { sb.append('"'); i++; }
                    else q = false;
                } else sb.append(ch);
            } else {
                if (ch == '"') q = true;
                else if (ch == ',') { out.add(sb.toString()); sb.setLength(0); }
                else sb.append(ch);
            }
        }
        out.add(sb.toString());
        return out.toArray(new String[0]);
    }
}
