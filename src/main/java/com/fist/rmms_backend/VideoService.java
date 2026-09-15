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
 *  - loadCatalog: read a CSV (section_label, video_file, direction, from_chainage, to_chainage)
 *                 into road_video table; a road can have several rows (clips).
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
        // A section can now have several clips (one per surveyed chainage stretch),
        // so from_ch/to_ch identify a clip's own span; NULL means a pre-existing
        // "legacy" row that the frontend treats as spanning the whole road.
        jdbc.execute("ALTER TABLE road_video ADD COLUMN IF NOT EXISTS from_ch double precision");
        jdbc.execute("ALTER TABLE road_video ADD COLUMN IF NOT EXISTS to_ch double precision");
        try {
            periods.ensureSurrogatePk("road_video", "section_label");
            periods.dedupeKeepingLatest("road_video", "section_label", "period_id");
            // Was a UNIQUE index on (section_label, period_id) — that enforced exactly
            // one video per section per period, which no longer holds once a section
            // can have multiple clips. Drop it and keep a plain lookup index instead.
            jdbc.execute("DROP INDEX IF EXISTS road_video_sec_period_ux");
            jdbc.execute("CREATE INDEX IF NOT EXISTS road_video_sec_period_idx ON road_video(section_label, period_id)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS road_video_period_idx ON road_video(period_id)");
        } catch (Exception e) {
            // Never let a schema-migration hiccup here take down the whole app at
            // boot — that would break every module, not just the video catalog.
            log.error("Video catalog schema migration failed — video endpoints may be degraded, " +
                    "but the app will keep starting", e);
        }
        jdbc.update("UPDATE road_video SET period_id = ? WHERE period_id IS NULL", periods.activePeriodId());
        backfillLegacyChainage();
    }

    /**
     * Rows imported before per-clip chainage existed have from_ch/to_ch NULL. We now
     * know each road's own measured chainage (roads."Measrd_Len" - the same value the
     * viewer already uses as a road's length), so fill those old rows in with the
     * road's full span (0..len) instead of leaving the frontend to guess it — a
     * legacy single video keeps covering the whole road exactly as it always did.
     * Roads with no usable length are left NULL; the frontend still falls back to
     * 0..len (from the clicked feature) for those.
     */
    private void backfillLegacyChainage() {
        try {
            jdbc.update("""
                UPDATE road_video v
                SET from_ch = 0, to_ch = r."Measrd_Len"
                FROM roads r
                WHERE v.section_label = r."Section_La"
                  AND v.from_ch IS NULL AND v.to_ch IS NULL
                  AND r."Measrd_Len" IS NOT NULL AND r."Measrd_Len" > 0
                """);
        } catch (Exception e) {
            // roads table may not exist yet on a brand-new database — harmless, nothing to backfill.
            log.debug("Video catalogue legacy-chainage backfill skipped", e);
        }
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
        // "chiange" is not a typo here — it is the spelling several survey
        // spreadsheets actually use for "chainage" (see LayerAttributeCatalog
        // and AssetController, which tolerate it for the same reason).
        Integer iFrom = first(idx, "from_chainage", "from_chiange", "from_ch", "start_chainage",
                "start_chiange", "start_ch", "start");
        Integer iTo   = first(idx, "to_chainage", "to_chiange", "to_ch", "end_chainage",
                "end_chiange", "end_ch", "end");
        if (iRoad == null || iFile == null) {
            throw new IllegalArgumentException("CSV must have columns: section_label and video_file (and optionally direction).");
        }
        if (iFrom == null || iTo == null) {
            throw new IllegalArgumentException(
                    "CSV must also have from_chainage and to_chainage columns - a road can now carry "
                            + "several video clips, so each row must state the chainage stretch its clip covers.");
        }
        /* Read and check the whole file BEFORE writing anything, the same way the
         * road-network upload does: a catalogue is edited by hand in a spreadsheet,
         * so a bad row is usually one of several, and importing the good half would
         * leave the admin guessing which sections actually took. */
        List<String[]> rawRows = new ArrayList<>();
        List<Integer> rawLineNos = new ArrayList<>();
        int lineNo = 1;                              // the header was line 1
        String line;
        while ((line = br.readLine()) != null) {
            lineNo++;
            if (line.trim().isEmpty()) continue;
            rawRows.add(parse(line));
            rawLineNos.add(lineNo);
        }
        // A clip's own chainage must fall within the road it belongs to — otherwise
        // a typo (an extra digit, a road confused with a longer one) silently creates
        // a clip that claims footage past the end of the actual road. Look every
        // section up once, in bulk, rather than a query per row.
        Set<String> roadLabels = new java.util.LinkedHashSet<>();
        for (String[] c : rawRows) { String r = val(c, iRoad); if (r != null) roadLabels.add(r); }
        Map<String, Double> roadLens = roadLengths(roadLabels);

        List<Object[]> rows = new ArrayList<>();     // {road, file, dir, fromCh, toCh}
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < rawRows.size(); i++) {
            String[] c = rawRows.get(i);
            int ln = rawLineNos.get(i);
            String road = val(c, iRoad);
            String file = val(c, iFile);
            String dir  = normDir(iDir != null ? val(c, iDir) : null);
            if (road == null || file == null) continue;
            String bad = checkVideoRef(file);
            if (bad == null) bad = checkChainage(val(c, iFrom), val(c, iTo));
            Double f = null, t = null;
            if (bad == null) {
                f = Double.valueOf(val(c, iFrom));
                t = Double.valueOf(val(c, iTo));
                if (roadLens != null) {
                    Double roadLen = roadLens.get(road);
                    if (roadLen == null) {
                        bad = "section_label not found in the road network - cannot verify its chainage range";
                    } else if (t > roadLen + CHAINAGE_TOLERANCE_M) {
                        bad = "to_chainage " + Math.round(t) + " m is beyond this road's own length ("
                                + Math.round(roadLen) + " m)";
                    }
                }
            }
            if (bad != null) {
                if (problems.size() < MAX_REPORTED_PROBLEMS)
                    problems.add("line " + ln + " (" + road + "): " + bad);
                else if (problems.size() == MAX_REPORTED_PROBLEMS)
                    problems.add("... and further rows with the same kind of problem");
                continue;
            }
            rows.add(new Object[]{road, file, dir, f, t});
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(
                    "The catalogue was not imported - fix these rows and upload it again:\n"
                            + String.join("\n", problems));
        }
        Set<String> sections = new java.util.LinkedHashSet<>();
        for (Object[] r : rows) sections.add((String) r[0]);
        for (String road : sections) {
            jdbc.update("DELETE FROM road_video WHERE section_label = ? AND period_id = ?", road, periodId);
        }
        int count = 0;
        for (Object[] r : rows) {
            jdbc.update("""
                INSERT INTO road_video (section_label, video_file, direction, period_id, from_ch, to_ch)
                VALUES (?,?,?,?,?,?)
                """, r[0], r[1], r[2], periodId, r[3], r[4]);
            count++;
        }
        return count;
    }

    /** Rounding slack between a hand-typed chainage and the road's own stored length. */
    private static final double CHAINAGE_TOLERANCE_M = 1.0;

    /**
     * Bulk-fetch each label's road length ("Measrd_Len" — the same value the map
     * viewer itself uses as a road's length), for checking a clip's chainage falls
     * within its own road. Returns null (skip the check entirely) if the lookup
     * itself fails — a missing/broken roads table is an environment problem, not
     * a reason to reject every row as "unmatched".
     */
    private Map<String, Double> roadLengths(Set<String> labels) {
        Map<String, Double> out = new HashMap<>();
        if (labels.isEmpty()) return out;
        try {
            String placeholders = String.join(",", Collections.nCopies(labels.size(), "?"));
            List<Map<String, Object>> rs = jdbc.queryForList(
                    "SELECT \"Section_La\" AS s, \"Measrd_Len\" AS len FROM roads WHERE \"Section_La\" IN (" + placeholders + ")",
                    labels.toArray());
            for (Map<String, Object> r : rs) {
                Object lenObj = r.get("len");
                if (lenObj instanceof Number) out.put((String) r.get("s"), ((Number) lenObj).doubleValue());
            }
            return out;
        } catch (Exception e) {
            log.warn("Video catalogue chainage-range check could not look up road lengths — skipping that check", e);
            return null;
        }
    }

    /** Validate one row's from/to chainage cell pair, mirroring checkVideoRef's style. */
    private static String checkChainage(String from, String to) {
        if (from == null || to == null) return "is missing from_chainage or to_chainage";
        double f, t;
        try {
            f = Double.parseDouble(from);
            t = Double.parseDouble(to);
        } catch (NumberFormatException e) {
            return "has a non-numeric from_chainage/to_chainage";
        }
        if (f < 0 || t < 0) return "has a negative from_chainage/to_chainage";
        if (t <= f) return "has to_chainage not greater than from_chainage";
        return null;
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

    /** Catalog of one survey period (null = the active period the viewer shows).
     *  A road may return several rows (clips); the frontend groups them by road,
     *  ordered here by from_ch so it receives each road's clips in travel order.
     *  from_ch/to_ch are null on rows imported before per-clip chainage existed -
     *  the frontend treats those as spanning the whole road. */
    public List<Map<String, Object>> catalog(Integer periodId) {
        try {
            return jdbc.queryForList(
                "SELECT section_label AS road, video_file AS file, direction, from_ch, to_ch " +
                "FROM road_video WHERE period_id = ? ORDER BY section_label, from_ch",
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
