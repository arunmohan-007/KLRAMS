package com.fist.rmms_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    private static final Logger log = LoggerFactory.getLogger(VideoService.class);

    private final JdbcTemplate jdbc;
    private final SurveyPeriodService periods;
    private final Path videoDir;
    /** Incomplete uploads live here as "<name>.part" until the last chunk arrives. */
    private final Path partsDir;

    public VideoService(JdbcTemplate jdbc, SurveyPeriodService periods,
                        @Value("${app.video-dir:video-store}") String dir) throws IOException {
        this.jdbc = jdbc;
        this.periods = periods;
        this.videoDir = Paths.get(dir).toAbsolutePath();
        this.partsDir = this.videoDir.resolve(".uploads");
        Files.createDirectories(this.videoDir);
        Files.createDirectories(this.partsDir);
    }

    @jakarta.annotation.PostConstruct
    @Transactional
    public void ensureSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS road_video (
                section_label text,
                video_file    text,
                direction     text,
                period_id     integer
            )
            """);
        // The NSV video is recorded during a survey cycle, so the catalog rows
        // carry the survey period: a section may map to a different video in
        // each period. Older databases had section_label as the primary key —
        // swap it for a surrogate id + (section, period) uniqueness, and adopt
        // pre-period rows into the current (active) period.
        jdbc.execute("ALTER TABLE road_video ADD COLUMN IF NOT EXISTS period_id integer");
        try {
            periods.ensureSurrogatePk("road_video", "section_label");
            periods.dedupeKeepingLatest("road_video", "section_label", "period_id");
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS road_video_sec_period_ux ON road_video(section_label, period_id)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS road_video_period_idx ON road_video(period_id)");
        } catch (Exception e) {
            // Never let a schema-migration hiccup here take down the whole app at
            // boot — that would break every module, not just the video catalog.
            log.error("Video catalog schema migration failed — video endpoints may be degraded, " +
                    "but the app will keep starting", e);
        }
        jdbc.update("UPDATE road_video SET period_id = ? WHERE period_id IS NULL", periods.activePeriodId());
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

    // ------------------------------------------------------------------
    //  Resumable, per-file chunked upload
    //  The browser sends one video at a time in ~5 MB chunks; each chunk is
    //  appended to "<name>.part". When the part reaches the declared total it
    //  is atomically moved to the final file. A dropped connection only loses
    //  the current chunk — uploadedBytes() lets the client resume from there.
    // ------------------------------------------------------------------

    /** basename only -> protects against path traversal (same guard as storeZip). */
    private static String safeName(String name) {
        if (name == null) throw new IllegalArgumentException("missing file name");
        String base = Paths.get(name).getFileName().toString();
        if (base.isEmpty() || base.equals(".") || base.equals("..")) {
            throw new IllegalArgumentException("invalid file name");
        }
        return base;
    }

    /** Bytes the server already holds for this file (size of its .part), or 0. */
    public long uploadedBytes(String name) throws IOException {
        Path part = partsDir.resolve(safeName(name) + ".part");
        return Files.exists(part) ? Files.size(part) : 0L;
    }

    /**
     * Append one chunk. {@code offset} must equal the bytes already stored; if
     * the client is out of step we reply "resync" with the true offset instead
     * of corrupting the file. When the part reaches {@code total} it is moved
     * into place and we reply "complete"; otherwise "partial".
     */
    public Map<String, Object> putChunk(String name, long offset, long total, MultipartFile chunk)
            throws IOException {
        String base = safeName(name);
        Path part = partsDir.resolve(base + ".part");
        Map<String, Object> r = new HashMap<>();

        long current = Files.exists(part) ? Files.size(part) : 0L;
        if (offset != current) {
            r.put("status", "resync");
            r.put("uploaded", current);
            return r;
        }

        long written = 0;
        byte[] buf = new byte[8192];
        try (InputStream in = chunk.getInputStream();
             OutputStream os = Files.newOutputStream(part,
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            int n;
            while ((n = in.read(buf)) > 0) { os.write(buf, 0, n); written += n; }
        }

        long newLen = current + written;
        if (total > 0 && newLen >= total) {
            Files.move(part, videoDir.resolve(base), StandardCopyOption.REPLACE_EXISTING);
            r.put("status", "complete");
            r.put("uploaded", total);
        } else {
            r.put("status", "partial");
            r.put("uploaded", newLen);
        }
        return r;
    }

    /** Read the catalog CSV (section_label, video_file, direction) into one survey period. */
    @Transactional
    public int loadCatalog(InputStream in, int periodId) throws Exception {
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
        /* Read and check the whole file BEFORE writing anything, the same way the
         * road-network upload does: a catalogue is edited by hand in a spreadsheet,
         * so a bad row is usually one of several, and importing the good half would
         * leave the admin guessing which sections actually took. */
        List<String[]> rows = new ArrayList<>();     // {road, file, dir}
        List<String> problems = new ArrayList<>();
        int lineNo = 1;                              // the header was line 1
        String line;
        while ((line = br.readLine()) != null) {
            lineNo++;
            if (line.trim().isEmpty()) continue;
            String[] c = parse(line);
            String road = val(c, iRoad);
            String file = val(c, iFile);
            String dir  = normDir(iDir != null ? val(c, iDir) : null);
            if (road == null || file == null) continue;
            String bad = checkVideoRef(file);
            if (bad != null) {
                if (problems.size() < MAX_REPORTED_PROBLEMS)
                    problems.add("line " + lineNo + " (" + road + "): " + bad);
                else if (problems.size() == MAX_REPORTED_PROBLEMS)
                    problems.add("… and further rows with the same kind of problem");
                continue;
            }
            rows.add(new String[]{road, file, dir});
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(
                    "The catalogue was not imported — fix these rows and upload it again:\n"
                            + String.join("\n", problems));
        }
        int count = 0;
        for (String[] r : rows) {
            jdbc.update("""
                INSERT INTO road_video (section_label, video_file, direction, period_id)
                VALUES (?,?,?,?)
                ON CONFLICT (section_label, period_id)
                DO UPDATE SET video_file = EXCLUDED.video_file, direction = EXCLUDED.direction
                """, r[0], r[1], r[2], periodId);
            count++;
        }
        return count;
    }

    /** Enough to show the shape of the mistake without a wall of text. */
    private static final int MAX_REPORTED_PROBLEMS = 15;

    /**
     * Hosts that hand out a *web page* for a video rather than the video itself.
     * A share link from one of these looks perfectly reasonable in a spreadsheet
     * and never plays, because &lt;video src&gt; needs the media file, not the
     * page that displays it.
     */
    private static final Set<String> SHARE_HOSTS = Set.of(
            "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be",
            "drive.google.com", "docs.google.com", "photos.google.com", "photos.app.goo.gl",
            "dropbox.com", "www.dropbox.com", "db.tt",
            "onedrive.live.com", "1drv.ms", "sharepoint.com",
            "vimeo.com", "www.vimeo.com", "player.vimeo.com",
            "facebook.com", "www.facebook.com", "fb.watch",
            "mega.nz", "mediafire.com", "www.mediafire.com", "wetransfer.com", "we.tl");

    /** Extensions that mean "this is a page", not "this is a video". */
    private static final Set<String> PAGE_EXTENSIONS = Set.of("html", "htm", "php", "asp", "aspx", "jsp");

    /**
     * Check one {@code video_file} cell, returning a plain-English problem or null
     * if it is usable.
     *
     *  WHY AT IMPORT TIME. The players accept either a bare file name (served from
     *  the video folder) or a full URL used as-is — see js/12-nsv-video.js. Nothing
     *  validated the URL, so a wrong one imported silently and only failed later as
     *  a dead player with no message, on a different page, for whoever clicked that
     *  road. These are the three ways it actually goes wrong.
     */
    static String checkVideoRef(String ref) {
        String s = ref == null ? "" : ref.trim();
        if (s.isEmpty()) return "is empty";

        if (s.contains("://")) {
            URI u;
            try {
                u = URI.create(s);
            } catch (Exception e) {
                return "is not a valid web address";
            }
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if ("http".equals(scheme))
                return "starts with http:// — KLRAMS is served over HTTPS, so the browser will refuse to play it. Use https://";
            if (!"https".equals(scheme))
                return "starts with " + scheme + ":// — only https:// links can be played";

            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            if (host.isEmpty()) return "is not a valid web address";
            boolean share = SHARE_HOSTS.contains(host)
                    || SHARE_HOSTS.stream().anyMatch(h -> host.endsWith("." + h));
            if (share)
                return "is a " + host + " share link, which is a web page rather than the video file itself. "
                        + "Use a direct link that ends in the file (for example .mp4)";

            String path = u.getPath() == null ? "" : u.getPath();
            String ext = extensionOf(path);
            if (PAGE_EXTENSIONS.contains(ext))
                return "points at a web page (." + ext + ") rather than a video file";
            return null;
        }

        /* Not a URL, so it is a file name inside the video folder. It is joined onto
         * that folder's path, so it must stay a plain name. */
        if (s.contains("/") || s.contains("\\") || s.contains(".."))
            return "looks like a path — use just the file name of an uploaded video, or a full https:// link";
        return null;
    }

    /** Lower-cased extension of a URL path, or "" when there is none. */
    private static String extensionOf(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot < slash || dot == path.length() - 1) return "";
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Catalog of one survey period (null = the active period the viewer shows). */
    public List<Map<String, Object>> catalog(Integer periodId) {
        try {
            return jdbc.queryForList(
                "SELECT section_label AS road, video_file AS file, direction FROM road_video WHERE period_id = ?",
                periods.resolve(periodId));
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
