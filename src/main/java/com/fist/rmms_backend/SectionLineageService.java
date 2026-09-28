package com.fist.rmms_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * KLRAMS — splitting one road section into two, and merging several into one.
 *
 * <p><b>Phase 1: this class only ever READS.</b> Every method here answers "what would happen",
 * and none of them writes. The commit path is deliberately absent rather than present and
 * guarded, so that until it is written there is no code path — no flag, no parameter, no
 * mistake — that can change data through this service.
 *
 * <h2>Why a dry run comes first</h2>
 *
 * This is the most destructive operation the system will have. {@link SectionRenameService}
 * rewrites the join key every linear-referenced layer hangs off; this rewrites that key AND
 * the coordinate frame the data is measured in. Chainage is stored section-local everywhere
 * except {@code roads} (see {@link SectionLineage}), so a split or merge that relabels without
 * re-basing produces rows that are present, drawn, and proportionally in the wrong place —
 * the one failure mode nothing in the system detects.
 *
 * <p>So the report this produces is the deliverable, not a preview of one. The RMMS cell runs
 * it against real sections and reads the counts before any code exists that could act on them.
 *
 * <h2>How the affected tables are found</h2>
 *
 * By discovery from {@code information_schema}, never a hard-coded list — the same decision
 * {@link SectionRenameService} made and for the same reason: a list maintained by hand goes
 * stale the first time Layer Management creates a table. A table is in scope when it carries a
 * section-label column; its chainage columns are then discovered by name, because they are the
 * fields that have to be re-based and a table without them needs only a relabel.
 *
 * <p>Three groups get special treatment:
 *
 * <ul>
 *   <li><b>Derived tables</b> ({@code condition_segments}, {@code fwd_segments},
 *       {@code iri_2km_segments}) are NOT migrated. They are cut from the centreline, and a
 *       split or merge moves the centreline, so relabelling them would leave geometry cut
 *       against a road that no longer exists. They are rebuilt instead, and this report says
 *       so rather than counting their rows as work.</li>
 *   <li><b>{@code calc_cw_member}</b> keys {@code section_label} as its PRIMARY KEY, so the
 *       blanket {@code UPDATE … SET label = new} that a rename uses cannot express a split
 *       (one row must become two) and would raise a duplicate-key error on a merge (two rows
 *       must become one). It is reported separately so the commit path cannot forget it.</li>
 *   <li><b>{@code saved_filters}</b> holds section labels inside a {@code jsonb} payload with
 *       no {@code section_label} column at all, so column discovery cannot see it. Left alone
 *       it silently drops the section from every saved network filter that names it.</li>
 * </ul>
 */
@Service
public class SectionLineageService {

    private static final Logger log = LoggerFactory.getLogger(SectionLineageService.class);

    /** Metres of disagreement tolerated between a section's four chainage columns. */
    private static final double TOLERANCE = 0.5;

    /** The column most linear-referenced tables name their section label. Created by this
     *  codebase (see {@link LayerRegistryService}), not by a survey return, so it is stable
     *  by construction — unlike the road network's columns, which are whatever the last
     *  shapefile import truncated them to. */
    private static final String STANDARD_LABEL = "section_label";

    /** Traffic stations spell the section label differently; also code-created and stable.
     *  The road network is deliberately NOT here — its label column is resolved through
     *  {@link LayerAttributeCatalog}, because it is the one that can change. */
    private static final Map<String, String> LABEL_EXCEPTIONS = Map.of(
            "traffic_stations", "section");

    /** Candidate chainage column pairs, most specific first. A table matching none of them
     *  carries a label but no position, so it needs a relabel and no re-basing. */
    private static final List<String[]> CHAINAGE_PAIRS = List.of(
            new String[]{"start_chainage", "end_chainage"},   // condition, road_assets, user layers
            new String[]{"from_ch", "to_ch"},                 // road_video
            new String[]{"chainage", "chainage"});            // traffic_stations (a point)

    /** Cut from the centreline rather than stored against it: rebuilt after a change, never
     *  migrated. Keep in step with {@link SegmentService}, {@link FwdSegmentService} and
     *  {@link IriSegmentService}. */
    private static final Set<String> DERIVED = Set.of(
            "condition_segments", "fwd_segments", "iri_2km_segments");

    /** Carries a section label but cannot be handled by a blanket UPDATE — see the class note. */
    private static final Set<String> SPECIAL = Set.of("calc_cw_member");

    /**
     * Tables whose straddling rows are DIVIDED by a split rather than assigned whole, and what
     * each one's division means.
     *
     * <p>Declared once because the preview and the commit both need it and must agree. They did
     * not: the preview knew only about {@code condition}, so a video clip the cut passed through
     * was reported as "moved whole, second half 0" while the commit divided it and created a row
     * on the second half. A preview that does not describe the write it is previewing is worse
     * than no preview.
     *
     * <ul>
     *   <li><b>condition</b> — an interval measurement. Each half keeps the same values, because
     *       that is what the measurement asserts about every metre of its span.</li>
     *   <li><b>road_video</b> — one recording, two windows into it. Nothing is re-encoded and no
     *       file is touched; each half plays only its own part (see the file sub-range columns
     *       in {@link VideoService} and {@code buildTravelPlan} in js/12-nsv-video.js).</li>
     * </ul>
     */
    private static final Map<String, String> DIVIDED_ON_SPLIT = Map.of(
            "condition", "divided into two rows, both carrying the same values",
            "road_video", "divided in two — both halves play their own part of the same "
                        + "recording, which is not altered or re-encoded",
            "road_assets", "measurement and continuous-run assets are divided in two; a "
                        + "structure the cut passes through refuses the split instead");

    /**
     * Line assets a split DIVIDES, because half of one is still a true statement.
     *
     * <p>An FWD stretch is a measurement over a length, exactly like a condition row: cutting it
     * at 300 m leaves two stretches whose readings still describe the ground under them. A
     * furniture line is a continuous run — a guardrail, kerb or drain — and half a guardrail is
     * a guardrail. One of them on this network is 18.5 km long, so treating it as indivisible
     * would block splitting most of the road it follows.
     */
    private static final Set<String> DIVISIBLE_ASSET_TYPES = Set.of("fwd", "furniture_line");

    /**
     * Line assets a split must NOT cut, and will not: the operator is sent to move the chainage.
     *
     * <p>A bridge cut in half is not two bridges. Dividing one would invent a structure that does
     * not exist, assigning it whole would put its deck partly outside the section that claims it,
     * and either way the asset register stops describing the road. A cut through one is a mistake
     * in the cut, so it is refused rather than warned about — the preview names a chainage clear
     * of every structure.
     */
    private static final Set<String> INDIVISIBLE_ASSET_TYPES = Set.of("bridge");

    /** Asset types a split already has an answer for — divided, or refused. */
    private static String[] handledAssetTypes() {
        Set<String> all = new LinkedHashSet<>(DIVISIBLE_ASSET_TYPES);
        all.addAll(INDIVISIBLE_ASSET_TYPES);
        return all.toArray(new String[0]);
    }

    /** What a straddling row in this table becomes. */
    private static String straddleHandling(String table) {
        return DIVIDED_ON_SPLIT.getOrDefault(table,
                "moved whole to the half containing the start chainage");
    }

    /** Identifier guard. Table names come from {@code information_schema}, so they are real,
     *  but a table name cannot be a bind parameter and has to be concatenated in. */
    private static final Pattern SAFE_IDENT = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private final JdbcTemplate jdbc;
    private final PlacementService placement;

    /**
     * Used instead of {@code @Transactional} on the write method, deliberately.
     *
     * <p>{@code @Transactional} is implemented by a proxy around the bean, so it does nothing
     * when one method of a class calls another directly — the call never leaves the object and
     * never passes the proxy. The write here IS called from a sibling method
     * ({@link #applyChainageCorrection}, which has to run the re-place afterwards), so an
     * annotation would have been silently inert: the roads UPDATE and its log row would have
     * committed independently, and a failure between them would leave a corrected section with
     * no record of who corrected it or what it looked like before.
     *
     * <p>Being explicit also puts the commit boundary in the code rather than in an annotation's
     * semantics, which matters here because the ordering — commit, THEN re-place — is the whole
     * point.
     */
    private final org.springframework.transaction.support.TransactionTemplate tx;

    /* Cleared after every write — see clearCaches(). */
    private final RoadController roads;
    private final RoadAttrService attrs;
    private final SegmentService segments;
    private final FwdSegmentService fwdSegments;
    private final IriSegmentService iriSegments;

    public SectionLineageService(JdbcTemplate jdbc, PlacementService placement,
                                 org.springframework.transaction.PlatformTransactionManager txManager,
                                 RoadController roads, RoadAttrService attrs, SegmentService segments,
                                 FwdSegmentService fwdSegments, IriSegmentService iriSegments) {
        this.jdbc = jdbc;
        this.placement = placement;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.roads = roads;
        this.attrs = attrs;
        this.segments = segments;
        this.fwdSegments = fwdSegments;
        this.iriSegments = iriSegments;
    }

    /**
     * Drops the in-memory GeoJSON caches that carry a section label.
     *
     * <p>Without this a split writes correctly and then serves the OLD network: the road index
     * and GeoJSON are cached as {@code volatile String} fields (see CLAUDE.md), so
     * {@code /api/roads/index} keeps returning the section that no longer exists and the map
     * shows it even after a page reload. The operator sees "I split it and nothing changed",
     * which is the worst possible outcome for a write they were asked to confirm.
     *
     * <p>Called AFTER the transaction commits, never inside it: clearing a cache is not
     * transactional, so an early clear lets a concurrent request rebuild it from uncommitted —
     * or rolled-back — data and then keep serving that. {@link SectionRenameService} clears the
     * same set for the same reasons.
     */
    private void clearCaches() {
        try {
            roads.refresh();            // road GeoJSON + index (also clears the attribute cache)
            attrs.clearCache();
            segments.clearCache();
            fwdSegments.clearCache();
            iriSegments.clearCache();
        } catch (Exception e) {
            // A stale cache is bad, but not a reason to report a committed write as failed.
            log.error("section change committed but the map caches could not be cleared — "
                    + "a restart or POST /api/roads/geojson/refresh will fix it", e);
        }
    }

    /**
     * Further chainage columns that must shift with a row, beyond the pair that positions it.
     *
     * <p>{@code road_video} carries the span its FILE covers alongside the span its CLIP covers.
     * Both are section-local, so both move when a row is re-based — shifting only the clip
     * window would leave the file window measured from the old section's origin, and the player
     * maps one onto the other to find its place in the footage. The result is a clip that plays
     * the wrong part of the video: no error, just the wrong road on screen.
     */
    private static final Map<String, List<String>> EXTRA_CHAINAGE = Map.of(
            "road_video", List.of("file_from_ch", "file_to_ch"));

    /** One table's section-label column and, when it has them, its chainage columns. */
    record Target(String table, String labelColumn, String startColumn, String endColumn,
                  List<String> extraChainage) {
        boolean isPoint() { return startColumn != null && startColumn.equals(endColumn); }
        boolean hasChainage() { return startColumn != null; }

        /** {@code , "file_from_ch" = "file_from_ch" + ?} … one clause per extra column. */
        String shiftClause(String op) {
            StringBuilder sb = new StringBuilder();
            for (String c : extraChainage)
                sb.append(", \"").append(c).append("\" = \"").append(c).append("\" ").append(op).append(" ?");
            return sb.toString();
        }
    }

    /* ================= chainage correction (Phase 1.5) ================= */

    /**
     * The lineage log: every change this module makes to a section's identity or calibration,
     * with enough of the previous state to put it back.
     *
     * <p>Kept apart from {@code upload_log}, which {@link SectionRenameService} writes a line of
     * prose into. A rename is an event; this is LINEAGE, and lineage gets asked questions —
     * "what happened to KPWD/MDR/501010103/17", "who changed this and when", "what did it look
     * like before". Prose in a text column cannot answer those.
     *
     * <p>{@code snapshot} holds the affected {@code roads} rows exactly as they were. It is what
     * makes an undo possible at all: a correction is not self-inverting, because the values it
     * overwrote are not derivable from the ones it wrote.
     */
    @jakarta.annotation.PostConstruct
    public void ensureSchema() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS section_change_log (
                    id           serial PRIMARY KEY,
                    operation    text NOT NULL,
                    performed_at timestamptz NOT NULL DEFAULT now(),
                    username     text,
                    source_labels jsonb,
                    result_labels jsonb,
                    detail       jsonb,
                    snapshot     jsonb,
                    undone_at    timestamptz
                )""");
            jdbc.execute("CREATE INDEX IF NOT EXISTS section_change_log_at_idx "
                       + "ON section_change_log(performed_at DESC)");
        } catch (Exception e) {
            // Never let this take the whole app down at boot — it would break every module,
            // not just section lineage.
            log.error("section_change_log schema could not be ensured; lineage logging may be "
                    + "degraded, but the app will keep starting", e);
        }
    }

    /**
     * What correcting one section's chainage would do. Writes nothing.
     *
     * <p>Correcting a section is not the harmless metadata edit it looks like. The reference
     * length is the DIVISOR in every placement query, so the effect depends entirely on whether
     * the correction changes it:
     *
     * <ul>
     *   <li><b>The section moves along its road, same length</b> — start and end shift
     *       together. The divisor is unchanged, so no stored row moves at all. This is the
     *       purely descriptive case.</li>
     *   <li><b>The length changes</b> — every row on the section re-places proportionally.
     *       A row at 900 m on a section shortened from 3960 to 3200 does not stay at 900 m
     *       along the ground; it moves to 900/3200 of the line instead of 900/3960.</li>
     * </ul>
     *
     * <p>And rows whose own chainage exceeds the NEW length cannot be placed at all — the
     * fraction clamps at 1.0 and they pile up on the section's final point. Those are counted
     * and sampled here, because they are the rows a shortening silently destroys the position
     * of, and no other part of the system will mention them.
     */
    public Map<String, Object> previewChainageCorrection(
            String label, Double newRoadStart, Double newRoadEnd, Double newMeasured, String username) {

        Map<String, Object> out = base("chainage_correction", username);
        out.put("section", label);

        String unresolved = unresolvedRoadAttributes();
        if (unresolved != null) return refuse(out, unresolved);

        SectionLineage.Section current = loadSection(label);
        if (current == null)
            return refuse(out, "\"" + label + "\" is not a road section on the network.");

        if (newRoadStart == null || newRoadEnd == null)
            return refuse(out, "Both " + LayerAttributeCatalog.ROAD_START_CHAINAGE + " and "
                             + LayerAttributeCatalog.ROAD_END_CHAINAGE + " are required.");
        if (newRoadEnd <= newRoadStart)
            return refuse(out, LayerAttributeCatalog.ROAD_END_CHAINAGE + " (" + fmt(newRoadEnd)
                             + ") must be greater than " + LayerAttributeCatalog.ROAD_START_CHAINAGE
                             + " (" + fmt(newRoadStart) + "). A section cannot run backwards.");

        double newSpan = newRoadEnd - newRoadStart;
        Double measured = newMeasured != null ? newMeasured : newSpan;
        SectionLineage.Section proposed =
                new SectionLineage.Section(label, newRoadStart, newRoadEnd, measured, current.geomLen());

        out.put("before", describe(current));
        out.put("after", describe(proposed));

        double oldLen = Math.abs(current.refLen());
        double newLen = Math.abs(proposed.refLen());
        boolean lengthChanges = Math.abs(oldLen - newLen) > TOLERANCE;
        Map<String, Object> effect = new LinkedHashMap<>();
        effect.put("length_before", round(oldLen));
        effect.put("length_after", round(newLen));
        effect.put("length_changes", lengthChanges);
        effect.put("scale_factor", oldLen > 0 ? round(newLen / oldLen) : null);
        effect.put("data_moves", lengthChanges);
        effect.put("explanation", lengthChanges
                ? "The reference length changes, so every stored point and stretch on this section "
                + "re-places proportionally. Their chainages are NOT edited — the divisor is."
                : "The reference length is unchanged, so no stored row moves. Only the section's "
                + "position on its road is being restated.");
        out.put("effect", effect);

        List<Map<String, Object>> tables = new ArrayList<>();
        long overflow = 0;
        List<Map<String, Object>> overflowSample = new ArrayList<>();
        for (Target t : targets()) {
            if (t.table().equals("roads")) continue;
            long rows = count(t, label);
            if (rows == 0) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("table", t.table());
            row.put("rows", rows);
            if (t.hasChainage()) {
                long beyond = countBeyond(t, label, newLen);
                overflow += beyond;
                row.put("beyond_new_length", beyond);
                if (beyond > 0) overflowSample.addAll(sampleBeyond(t, label, newLen));
            }
            tables.add(row);
        }
        out.put("tables", tables);
        out.put("rows_beyond_new_length", overflow);
        out.put("rows_beyond_sample", overflowSample);

        List<Map<String, Object>> warnings = new ArrayList<>();
        if (overflow > 0) {
            warnings.add(warn("high", "rows_beyond_new_length",
                    overflow + " row(s) have a chainage past the new length of " + fmt(newLen)
                  + " m. Their position cannot be computed — they clamp to the section's final "
                  + "point and stack there. Shortening a section is what does this, and nothing "
                  + "else in the system reports it."));
        }
        String disagree = proposed.chainageInconsistency(TOLERANCE);
        if (disagree != null) {
            warnings.add(warn("medium", "chainage_disagreement", disagree
                  + " Placement uses the chainage span, not " + LayerAttributeCatalog.MEASURED_LENGTH
                  + ", so this section would stay unsplittable until they agree."));
        }
        if (lengthChanges) {
            warnings.add(warn("info", "rebuild_required",
                    "Condition segments, 2 km IRI bins and FWD stretches on this section are cut "
                  + "from the centreline against the old length and must be rebuilt."));
        }
        out.put("warnings", warnings);
        out.put("rebuild_after", lengthChanges ? REBUILD_AFTER : List.of());
        return ok(out);
    }

    /**
     * Applies a chainage correction to one section, in one transaction, and logs it.
     *
     * <p>The re-placement deliberately runs AFTER the transaction commits, not inside it: it
     * reads the section's new calibration back out of {@code roads}, so running it in the same
     * transaction would place rows against values that a rollback could still erase. The window
     * between the two is a section whose rows are positioned against its previous length — the
     * same state the whole network is in after a road re-upload, and the state
     * {@code /api/placement/replace} exists to resolve.
     *
     * @param confirm must be true. A preview is one endpoint away and this is the first call in
     *                the module that changes data.
     */
    public Map<String, Object> applyChainageCorrection(
            String label, Double newRoadStart, Double newRoadEnd, Double newMeasured,
            boolean confirm, boolean acceptOverflow, String username) {

        Map<String, Object> preview = previewChainageCorrection(
                label, newRoadStart, newRoadEnd, newMeasured, username);
        if (!"ok".equals(preview.get("status"))) return preview;

        if (!confirm) {
            preview.put("status", "refused");
            preview.put("message", "Nothing was changed. Re-send with \"confirm\": true to apply "
                                 + "this correction.");
            return preview;
        }

        long overflow = ((Number) preview.get("rows_beyond_new_length")).longValue();
        if (overflow > 0 && !acceptOverflow) {
            preview.put("status", "refused");
            preview.put("message", overflow + " row(s) would fall past the new length and lose "
                                 + "their position. Nothing was changed. Correct those rows first, "
                                 + "or re-send with \"accept_overflow\": true if that is intended.");
            return preview;
        }

        Map<String, Object> result = tx.execute(s ->
                writeCorrection(label, newRoadStart, newRoadEnd, newMeasured, preview, username));

        // After the commit, never inside it — see the method note.
        try {
            result.put("replaced", placement.replaceForSection(label));
        } catch (Exception e) {
            log.error("chainage correction committed for \"{}\" but the re-place failed", label, e);
            result.put("replaced", Map.of("status", "failed",
                    "message", "The correction was saved, but re-placing this section's stored "
                             + "points failed. Run POST /api/placement/replace to finish."));
        }
        return result;
    }

    /** The transactional half: the roads row and its log entry, together or not at all.
     *  Run through {@link #tx}, never called directly — see that field's note. */
    private Map<String, Object> writeCorrection(String label, Double newRoadStart, Double newRoadEnd,
                                        Double newMeasured, Map<String, Object> preview,
                                        String username) {
        RoadColumnMap c = roadColumns();
        double newSpan = newRoadEnd - newRoadStart;
        Double measured = newMeasured != null ? newMeasured : newSpan;

        String snapshot = snapshotOf(List.of(label));

        StringBuilder sql = new StringBuilder("UPDATE roads SET \"" + c.roadStart() + "\" = ?, \""
                + c.roadEnd() + "\" = ?");
        List<Object> args = new ArrayList<>(List.of(newRoadStart, newRoadEnd));
        if (c.measured() != null) { sql.append(", \"").append(c.measured()).append("\" = ?"); args.add(measured); }
        // The section-local pair is derived, never typed: 0 and the section's own length.
        if (c.sectionStart() != null) { sql.append(", \"").append(c.sectionStart()).append("\" = 0"); }
        if (c.sectionEnd() != null) { sql.append(", \"").append(c.sectionEnd()).append("\" = ?"); args.add(Math.abs(newSpan)); }
        sql.append(" WHERE \"").append(c.label()).append("\" = ?");
        args.add(label);

        int updated = jdbc.update(sql.toString(), args.toArray());

        jdbc.update("INSERT INTO section_change_log"
                  + "(operation, username, source_labels, result_labels, detail, snapshot) "
                  + "VALUES (?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb)",
                "chainage_correction", username,
                toJson(List.of(label)), toJson(List.of(label)),
                toJson(Map.of("before", preview.get("before"), "after", preview.get("after"),
                              "effect", preview.get("effect"))),
                snapshot);

        Map<String, Object> out = new LinkedHashMap<>(preview);
        out.put("dry_run", false);
        out.put("status", "ok");
        out.put("rows_updated", updated);
        out.put("message", "Corrected. " + (Boolean.TRUE.equals(
                ((Map<?, ?>) preview.get("effect")).get("length_changes"))
                ? "The reference length changed, so this section's stored points were re-placed "
                + "and its cut segments must be rebuilt — see rebuild_after."
                : "The reference length is unchanged, so nothing moved."));
        log.info("chainage correction on \"{}\" by {}: {} row(s)", label, username, updated);
        return out;
    }

    /* ================= the lineage log and undo ================= */

    /** Writes one change to {@code section_change_log} and returns its id. */
    private long writeLog(String operation, List<String> sources, List<String> results,
                          Map<String, Object> detail, Map<String, Object> undoPlan,
                          String snapshot, String username) {
        Map<String, Object> keep = new LinkedHashMap<>();
        for (String k : List.of("plan", "effect", "tables", "warnings", "at_chainage",
                                "straddling_total", "before", "after"))
            if (detail.containsKey(k)) keep.put(k, detail.get(k));
        keep.put("row_counts", rowCountsFor(results));
        /* Recorded, never derived later: neither a split nor a merge is self-inverting, so the
           exact amounts each row was shifted by are only knowable here. */
        keep.put("undo_plan", undoPlan);

        Long id = jdbc.queryForObject(
                "INSERT INTO section_change_log"
              + "(operation, username, source_labels, result_labels, detail, snapshot) "
              + "VALUES (?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb) RETURNING id",
                Long.class, operation, username, toJson(sources), toJson(results),
                toJson(keep), snapshot);
        return id == null ? -1 : id;
    }

    /**
     * Per-table row counts for the resulting labels, recorded at commit.
     *
     * <p>This is what makes the undo rule checkable. An undo is only safe while nothing has
     * landed on the new labels since the change — a survey imported onto a freshly split section
     * has no place to go if the split is reversed, and reversing it would either destroy those
     * rows or strand them on a label that no longer exists. Comparing these counts against the
     * table now answers "has anything arrived?" exactly, without needing an import to announce
     * itself.
     */
    private Map<String, Long> rowCountsFor(List<String> labels) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Target t : targets()) {
            if (t.table().equals("roads")) continue;
            long n = 0;
            for (String l : labels) n += count(t, l);
            out.put(t.table(), n);
        }
        return out;
    }

    /** The lineage log, newest first — the Section History view. */
    public List<Map<String, Object>> history(int limit) {
        int n = Math.max(1, Math.min(limit, 500));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, operation, performed_at, username, source_labels, result_labels, "
              + "       undone_at, (snapshot IS NOT NULL) AS can_restore "
              + "FROM section_change_log ORDER BY id DESC LIMIT " + n);
        for (Map<String, Object> r : rows) {
            /* jsonb arrives as a driver object whose toString is the JSON text, so serialising
               the row straight out hands the browser a STRING where it expects an array — and
               "(r.source_labels || []).join is not a function" is all the operator sees. Parsed
               here so the API returns the arrays it documents. The snapshot column had exactly
               this bug; this is the same trap in a second place. */
            r.put("source_labels", fromJsonList(String.valueOf(r.get("source_labels"))));
            r.put("result_labels", fromJsonList(String.valueOf(r.get("result_labels"))));

            if (r.get("undone_at") == null && Boolean.TRUE.equals(r.get("can_restore"))) {
                String blocked = undoBlockedReason(((Number) r.get("id")).longValue());
                r.put("undoable", blocked == null);
                if (blocked != null) r.put("undo_blocked", blocked);
            } else {
                r.put("undoable", false);
            }
        }
        return rows;
    }

    /**
     * Why this change can no longer be undone, or null when it still can.
     *
     * <p>The rule: an undo stays available until new data is imported against the labels the
     * change produced. After that the snapshot no longer describes a state the database can be
     * returned to — the rows that arrived since were measured against the NEW sections, and
     * putting the old ones back would leave them referring to labels the network does not have.
     */
    private String undoBlockedReason(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT operation, result_labels, detail, undone_at FROM section_change_log WHERE id = ?", id);
        if (rows.isEmpty()) return "No change with id " + id + ".";
        Map<String, Object> row = rows.get(0);
        if (row.get("undone_at") != null) return "This change has already been undone.";

        List<String> results = fromJsonList(String.valueOf(row.get("result_labels")));
        Map<String, Object> detail = fromJsonMap(String.valueOf(row.get("detail")));
        Object recorded = detail.get("row_counts");
        if (!(recorded instanceof Map<?, ?> before)) return null;   // pre-dates the record; allow

        Map<String, Long> now = rowCountsFor(results);
        for (Map.Entry<?, ?> e : before.entrySet()) {
            String table = String.valueOf(e.getKey());
            long was = ((Number) e.getValue()).longValue();
            long is = now.getOrDefault(table, 0L);
            if (is != was)
                return "Data has changed on these sections since: " + table + " held " + was
                     + " row(s) at the time and holds " + is + " now. Undoing would strand or "
                     + "destroy what arrived after.";
        }
        return null;
    }

    /**
     * Restores the road network rows a change overwrote, and re-points every dependent row back.
     *
     * <p>Not a general inverse — it replays the snapshot. A merge is not self-inverting (it
     * destroys the join chainage), and a split's inverse would have to know the cut, so both are
     * reversed by putting the recorded {@code roads} rows back and re-basing the dependent rows
     * from the result labels onto the sources using the same arithmetic that moved them.
     */
    public Map<String, Object> undo(long id, boolean confirm, String username) {
        Map<String, Object> out = base("undo", username);
        out.put("change_id", id);

        String blocked = undoBlockedReason(id);
        if (blocked != null) return refuse(out, blocked);
        if (!confirm)
            return refuse(out, "Nothing was changed. Re-send with \"confirm\": true to undo change " + id + ".");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT operation, source_labels, result_labels, detail, snapshot "
              + "FROM section_change_log WHERE id = ?", id);
        String operation = String.valueOf(row.get("operation"));
        List<String> sources = fromJsonList(String.valueOf(row.get("source_labels")));
        List<String> results = fromJsonList(String.valueOf(row.get("result_labels")));
        List<Map<String, Object>> snapshot = fromJsonRows(String.valueOf(row.get("snapshot")));
        Object planObj = fromJsonMap(String.valueOf(row.get("detail"))).get("undo_plan");
        if (!(planObj instanceof Map))
            return refuse(out, "Change " + id + " carries no undo plan — it predates them, so the "
                             + "exact chainage shifts it applied are not recorded and reversing it "
                             + "automatically would move rows to the wrong place.");
        @SuppressWarnings("unchecked")
        Map<String, Object> undoPlan = (Map<String, Object>) planObj;

        Map<String, Object> result = tx.execute(s -> {
            RoadColumnMap c = roadColumns();
            /* Dependent rows first, while the result labels still exist. Everything goes back to
               the FIRST source label — for a split that is the parent both halves came from; for
               a merge it is the section the merged row was kept as. Chainages are restored by
               re-running the original arithmetic in reverse. */
            Map<String, Object> moved = restoreDependents(undoPlan);

            for (String l : results)
                jdbc.update("DELETE FROM roads WHERE \"" + c.label() + "\" = ?", l);
            int restored = restoreRoadRows(snapshot);

            jdbc.update("UPDATE section_change_log SET undone_at = now() WHERE id = ?", id);
            jdbc.update("INSERT INTO section_change_log"
                      + "(operation, username, source_labels, result_labels, detail) "
                      + "VALUES (?,?,?::jsonb,?::jsonb,?::jsonb)",
                    "undo", username, toJson(results), toJson(sources),
                    toJson(Map.of("undid_change_id", id, "undid_operation", operation)));

            Map<String, Object> r = new LinkedHashMap<>(out);
            r.put("status", "ok");
            r.put("dry_run", false);
            r.put("undid", operation);
            r.put("roads_restored", restored);
            r.put("moved", moved);
            r.put("message", "Change " + id + " (" + operation + ") was undone: "
                    + String.join(", ", quoted(sources)) + " restored. The centreline moved again, "
                    + "so the cut segments must be rebuilt — see rebuild_after.");
            return r;
        });

        if (result != null) {
            result.put("rebuild_after", REBUILD_AFTER);
            afterCommit(result, sources);
        }
        log.info("undid change {} ({}) by {}", id, operation, username);
        return result;
    }

    /**
     * Puts dependent rows back on the source labels, reversing the chainage shift exactly.
     *
     * <p>The shift has to be undone arithmetically, not left to the re-placement: re-placement
     * recomputes a row's GEOMETRY from its chainage, so a row returned to the parent label with
     * a chainage still measured from the second half's origin is re-placed confidently in the
     * wrong spot. The amounts come from the undo plan recorded when the change was written —
     * neither operation is self-inverting, so nothing here is derived after the fact.
     *
     * <p>Rows that a split DIVIDED are not re-joined. The two halves are returned to the parent
     * and together cover exactly the original span, which is the same measurement expressed as
     * two adjacent rows; merging them back would mean deciding which of two equal values
     * survives. The undo response says so rather than leaving it to be discovered.
     */
    private Map<String, Object> restoreDependents(Map<String, Object> undoPlan) {
        Map<String, Object> moved = new LinkedHashMap<>();
        String kind = String.valueOf(undoPlan.get("kind"));

        if ("split".equals(kind)) {
            String parent = String.valueOf(undoPlan.get("parent"));
            String first = String.valueOf(undoPlan.get("first"));
            String second = String.valueOf(undoPlan.get("second"));
            double cut = ((Number) undoPlan.get("cut")).doubleValue();

            for (Target t : targets()) {
                if (t.table().equals("roads")) continue;
                int n = jdbc.update(shiftSql(t, parent, second, +cut), shiftArgs(t, parent, second, cut));
                n += jdbc.update("UPDATE \"" + t.table() + "\" SET \"" + t.labelColumn() + "\" = ? "
                               + "WHERE \"" + t.labelColumn() + "\" = ?", parent, first);
                if (n > 0) moved.put(t.table(), n);
            }
            int rejoined = rejoinVideoClips(parent, cut);
            if (rejoined > 0) moved.put("road_video_rejoined", rejoined);

            moved.put("note", "rows returned to \"" + parent + "\"; the second half's chainages were "
                    + "shifted back by " + fmt(cut) + " m. Video clips the split divided were "
                    + "re-joined. Condition rows it divided remain two adjacent rows covering the "
                    + "original span — they carry measurements, and re-joining them would mean "
                    + "choosing which of two equal values survives.");

        } else if ("merge".equals(kind)) {
            String result = String.valueOf(undoPlan.get("result"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ranges = (List<Map<String, Object>>) undoPlan.get("ranges");

            /* When the merge kept one of the sources' own labels as the result, the rows are
               parked on a label nothing else uses for the duration. Without it, the source that
               shares the result's label hands its rows straight back to a WHERE clause that is
               still looking for that label — a shallower range then claims them a second time
               and carries the whole section off onto the wrong source. */
            boolean reused = false;
            for (Map<String, Object> range : ranges)
                if (result.equals(String.valueOf(range.get("label")))) reused = true;

            for (Target t : targets()) {
                if (t.table().equals("roads")) continue;
                String held = result;
                if (reused) {
                    held = scratchLabel(t);
                    jdbc.update("UPDATE \"" + t.table() + "\" SET \"" + t.labelColumn() + "\" = ? "
                              + "WHERE \"" + t.labelColumn() + "\" = ?", held, result);
                }
                int n = 0;
                // Deepest offset first, so a row is claimed by the section it actually falls in.
                for (int i = ranges.size() - 1; i >= 0; i--) {
                    Map<String, Object> range = ranges.get(i);
                    String source = String.valueOf(range.get("label"));
                    double from = ((Number) range.get("from")).doubleValue();
                    n += jdbc.update(
                            partitionSql(t, held, source, from),
                            partitionArgs(t, held, source, from));
                }
                // Only a row with no chainage at all can be left behind; put it back where it was.
                if (reused)
                    jdbc.update("UPDATE \"" + t.table() + "\" SET \"" + t.labelColumn() + "\" = ? "
                              + "WHERE \"" + t.labelColumn() + "\" = ?", result, held);
                if (n > 0) moved.put(t.table(), n);
            }
            moved.put("note", "rows were returned to the section whose chainage range they fall in, "
                    + "each shifted back by that section's offset.");
        }
        return moved;
    }

    /**
     * Puts a video clip that a split divided back together, after both halves have been returned
     * to the parent section.
     *
     * <p>Unlike a divided condition row, this is unambiguous and therefore worth doing. A
     * condition row carries a measurement, so re-joining two halves means choosing which of two
     * equal values survives — a decision the code should not make silently. A road_video row
     * carries no measurement at all: it is a window onto a file. Two adjacent windows onto the
     * SAME file, meeting exactly at the cut, describe precisely the one window they were made
     * from, so rebuilding it loses nothing.
     *
     * <p>The file sub-range is cleared when it once again matches the clip's own span — that is
     * what NULL means, and it is the state the row was in before the split, so the catalogue is
     * returned byte for byte rather than merely equivalently.
     */
    private int rejoinVideoClips(String section, double cut) {
        if (!tableExists("road_video")) return 0;
        int rejoined = 0;
        for (Map<String, Object> pair : jdbc.queryForList(
                "SELECT lo.id AS lo_id, hi.id AS hi_id, hi.to_ch AS hi_to, hi.file_to_ch AS hi_file_to "
              + "FROM road_video lo JOIN road_video hi "
              + "  ON hi.section_label = lo.section_label "
              + " AND hi.video_file = lo.video_file "
              + " AND hi.direction IS NOT DISTINCT FROM lo.direction "
              + " AND hi.period_id IS NOT DISTINCT FROM lo.period_id "
              + " AND hi.from_ch = lo.to_ch "
              + "WHERE lo.section_label = ? AND lo.to_ch = ?", section, cut)) {
            jdbc.update("UPDATE road_video SET to_ch = ?, file_to_ch = ? WHERE id = ?",
                    pair.get("hi_to"), pair.get("hi_file_to"), pair.get("lo_id"));
            jdbc.update("DELETE FROM road_video WHERE id = ?", pair.get("hi_id"));
            rejoined++;
        }
        // A file window identical to the clip's own span is what NULL means; restore that form.
        jdbc.update("UPDATE road_video SET file_from_ch = NULL, file_to_ch = NULL "
                  + "WHERE section_label = ? AND file_from_ch = from_ch AND file_to_ch = to_ch",
                section);
        return rejoined;
    }

    /**
     * A label no row of this table currently carries, to hold rows on mid-restore.
     *
     * <p>Kept deliberately short: a section label column can be a width-limited {@code varchar}
     * that a shapefile import created, and a scratch value too long for it would fail the update
     * rather than merely look odd.
     */
    private String scratchLabel(Target t) {
        for (int i = 0; i < 100; i++) {
            String candidate = "~undo" + i;
            Long n = jdbc.queryForObject("SELECT count(*) FROM \"" + t.table() + "\" WHERE \""
                    + t.labelColumn() + "\" = ?", Long.class, candidate);
            if (n != null && n == 0) return candidate;
        }
        throw new IllegalStateException("no free scratch label on " + t.table());
    }

    /** Relabel + shift every row currently on {@code from} onto {@code to}, by {@code delta}. */
    private String shiftSql(Target t, String to, String from, double delta) {
        String lab = t.labelColumn();
        if (!t.hasChainage())
            return "UPDATE \"" + t.table() + "\" SET \"" + lab + "\" = ? WHERE \"" + lab + "\" = ?";
        if (t.isPoint())
            return "UPDATE \"" + t.table() + "\" SET \"" + lab + "\" = ?, \"" + t.startColumn()
                 + "\" = \"" + t.startColumn() + "\" + ? WHERE \"" + lab + "\" = ?";
        return "UPDATE \"" + t.table() + "\" SET \"" + lab + "\" = ?, \"" + t.startColumn()
             + "\" = \"" + t.startColumn() + "\" + ?, \"" + t.endColumn() + "\" = \""
             + t.endColumn() + "\" + ?" + t.shiftClause("+") + " WHERE \"" + lab + "\" = ?";
    }

    private Object[] shiftArgs(Target t, String to, String from, double delta) {
        if (!t.hasChainage()) return new Object[]{to, from};
        if (t.isPoint()) return new Object[]{to, delta, from};
        List<Object> a = new ArrayList<>(List.of(to, delta, delta));
        for (int i = 0; i < t.extraChainage().size(); i++) a.add(delta);
        a.add(from);
        return a.toArray();
    }

    /** Claim the rows of the merged section that belong to one source, shifting them back. */
    private String partitionSql(Target t, String result, String source, double from) {
        String lab = t.labelColumn();
        if (!t.hasChainage())
            return "UPDATE \"" + t.table() + "\" SET \"" + lab + "\" = ? WHERE \"" + lab + "\" = ?";
        String start = t.startColumn();
        if (t.isPoint())
            return "UPDATE \"" + t.table() + "\" SET \"" + lab + "\" = ?, \"" + start + "\" = \""
                 + start + "\" - ? WHERE \"" + lab + "\" = ? AND \"" + start + "\" >= ?";
        return "UPDATE \"" + t.table() + "\" SET \"" + lab + "\" = ?, \"" + start + "\" = \"" + start
             + "\" - ?, \"" + t.endColumn() + "\" = \"" + t.endColumn() + "\" - ?"
             + t.shiftClause("-") + " WHERE \"" + lab + "\" = ? AND \"" + start + "\" >= ?";
    }

    private Object[] partitionArgs(Target t, String result, String source, double from) {
        if (!t.hasChainage()) return new Object[]{source, result};
        if (t.isPoint()) return new Object[]{source, from, result, from};
        List<Object> a = new ArrayList<>(List.of(source, from, from));
        for (int i = 0; i < t.extraChainage().size(); i++) a.add(from);
        a.add(result);
        a.add(from);
        return a.toArray();
    }

    /** Re-inserts the {@code roads} rows exactly as the snapshot recorded them. */
    private int restoreRoadRows(List<Map<String, Object>> snapshot) {
        int n = 0;
        for (Map<String, Object> entry : snapshot) {
            Object rowObj = entry.get("row");
            if (!(rowObj instanceof Map<?, ?> row)) continue;
            String wkt = entry.get("geom_wkt") == null ? null : String.valueOf(entry.get("geom_wkt"));

            List<String> names = new ArrayList<>(), holders = new ArrayList<>();
            List<Object> args = new ArrayList<>();
            for (Map.Entry<?, ?> f : row.entrySet()) {
                String col = String.valueOf(f.getKey());
                if (col.equals("id")) continue;          // let the sequence assign a fresh key
                names.add("\"" + col + "\"");
                holders.add("?");
                args.add(f.getValue());
            }
            if (wkt != null) { names.add("geom"); holders.add("ST_GeomFromText(?, 4326)"); args.add(wkt); }
            n += jdbc.update("INSERT INTO roads (" + String.join(", ", names) + ") VALUES ("
                           + String.join(", ", holders) + ")", args.toArray());
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private List<String> fromJsonList(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJsonMap(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fromJsonRows(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** The affected {@code roads} rows exactly as they are now, for the undo path. */
    private String snapshotOf(List<String> labels) {
        RoadColumnMap c = roadColumns();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String l : labels) {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT to_jsonb(r) - 'geom' AS row, ST_AsText(r.geom) AS geom_wkt "
                  + "FROM roads r WHERE r.\"" + c.label() + "\" = ?", l)) {
                /* to_jsonb comes back as a driver object whose toString is the JSON text, not as
                   a Map. Serialising it directly would store the row as a JSON *string* — which
                   round-trips as a String, fails the restore's type check, and puts back nothing
                   at all without raising. Parsed here so the snapshot is real structure. */
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("row", fromJsonMap(String.valueOf(r.get("row"))));
                entry.put("geom_wkt", r.get("geom_wkt"));
                rows.add(entry);
            }
        }
        return toJson(rows);
    }

    /** Rows whose chainage runs past a proposed new section length. */
    private long countBeyond(Target t, String label, double newLength) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM \"" + t.table() + "\" WHERE \"" + t.labelColumn() + "\" = ? "
              + "AND \"" + t.endColumn() + "\" > ?", Long.class, label, newLength + TOLERANCE);
        return n == null ? 0 : n;
    }

    private List<Map<String, Object>> sampleBeyond(Target t, String label, double newLength) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT \"" + t.startColumn() + "\" AS start_ch, \"" + t.endColumn() + "\" AS end_ch "
              + "FROM \"" + t.table() + "\" WHERE \"" + t.labelColumn() + "\" = ? "
              + "AND \"" + t.endColumn() + "\" > ? ORDER BY 2 DESC LIMIT 5",
                label, newLength + TOLERANCE)) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("table", t.table());
            out.add(m);
        }
        return out;
    }

    private String toJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            // A log row is not worth failing the correction for, but an empty one would be a lie.
            log.warn("could not serialise section lineage detail", e);
            return "null";
        }
    }

    private static String fmt(double v) {
        return String.valueOf(Math.round(v * 1000) / 1000.0);
    }

    /* ================= the two dry runs ================= */

    /**
     * What splitting {@code label} at an absolute road chainage would do. Writes nothing.
     *
     * @param username recorded in the log line only; this method changes no data.
     */
    public Map<String, Object> previewSplit(String label, double atChainage,
                                            String firstLabel, String secondLabel, String username) {
        Map<String, Object> out = base("split", username);
        out.put("section", label);
        out.put("at_chainage", atChainage);

        String unresolved = unresolvedRoadAttributes();
        if (unresolved != null) return refuse(out, unresolved);

        SectionLineage.Section parent = loadSection(label);
        if (parent == null) {
            return refuse(out, "\"" + label + "\" is not a road section on the network. Check it "
                             + "against /api/roads/index.");
        }

        String inconsistent = parent.chainageInconsistency(TOLERANCE);
        if (inconsistent != null) {
            return refuse(out, inconsistent + " A section whose chainage columns already disagree "
                             + "cannot be split — whichever value were used would be baked into both "
                             + "halves and the discrepancy would stop being visible. Correct the "
                             + "section's chainage first.");
        }

        String badGeometry = geometryProblem(label);
        if (badGeometry != null) return refuse(out, badGeometry);

        SectionLineage.SplitPlan plan;
        try {
            plan = SectionLineage.planSplit(parent, atChainage, firstLabel, secondLabel);
        } catch (IllegalArgumentException e) {
            return refuse(out, e.getMessage());
        }

        /* A structure the cut passes through is a refusal, not a warning: there is no correct
           thing to do with half a bridge, so the answer is always to move the cut. */
        String structure = structureAcrossCut(label, plan.localSplit(), parent);
        if (structure != null) return refuse(out, structure);

        List<String> taken = new ArrayList<>();
        for (String l : List.of(firstLabel, secondLabel)) {
            if (!l.equals(label) && labelInUse(l)) taken.add(l);
        }
        if (!taken.isEmpty()) {
            return refuse(out, "Label(s) " + String.join(", ", quoted(taken)) + " are already used by "
                             + "a road section or by existing survey data. Splitting onto them would "
                             + "merge two sets of records.");
        }

        out.put("plan", Map.of(
                "first", describe(plan.first()),
                "second", describe(plan.second()),
                "local_split", round(plan.localSplit()),
                "geometry_fraction", plan.fraction()));

        /* How many rows each half actually receives.

           Counted here with the same predicates moveRowsForSplit uses, rather than left for the
           caller to infer: "rows minus straddling" is not the answer, and reporting that made
           the second half read as if nothing moved to it at all. The rules being mirrored are:

             - a row at or past the cut moves to the SECOND half, shifted back;
             - everything else stays with the FIRST half, whose frame is the parent's;
             - a condition row across the cut is DIVIDED, so it is counted in BOTH halves —
               the totals deliberately exceed the row count, because the split creates rows;
             - any other row across the cut is assigned whole by its start chainage, so it is
               already inside the "stays with the first half" count. */
        List<Map<String, Object>> tables = new ArrayList<>();
        long straddling = 0;
        double cut = plan.localSplit();
        for (Target t : targets()) {
            if (t.table().equals("roads")) continue;
            long rows = count(t, label);
            if (rows == 0) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("table", t.table());
            row.put("rows", rows);

            long toSecond = 0, toFirst = rows, divided = 0;
            if (t.hasChainage()) {
                toSecond = countFromCut(t, label, cut);
                toFirst = rows - toSecond;
                if (!t.isPoint()) {
                    long s = countStraddling(t, label, cut);
                    straddling += s;
                    row.put("straddling", s);
                    row.put("straddle_handling", straddleHandling(t.table()));
                    if (DIVIDED_ON_SPLIT.containsKey(t.table())) {
                        divided = s;
                        toSecond += s;     // the divided copy lands on the second half as well
                    }
                }
            } else {
                row.put("note", "no chainage on this table — every row follows the first half");
            }
            row.put("to_first", toFirst);
            row.put("to_second", toSecond);
            if (divided > 0) row.put("divided", divided);
            row.put("rebased", t.hasChainage());
            tables.add(row);
        }
        out.put("tables", tables);
        out.put("straddling_total", straddling);
        out.put("warnings", warnings(List.of(label), plan.localSplit()));
        out.put("attributes", editableAttributes(List.of(label)));
        out.put("rebuild_after", REBUILD_AFTER);
        return ok(out);
    }

    /** What merging {@code labels} into one section would do. Writes nothing. */
    public Map<String, Object> previewMerge(List<String> labels, String resultLabel, String username) {
        Map<String, Object> out = base("merge", username);
        out.put("sections", labels);

        String unresolved = unresolvedRoadAttributes();
        if (unresolved != null) return refuse(out, unresolved);

        List<SectionLineage.Section> sources = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String l : labels == null ? List.<String>of() : labels) {
            SectionLineage.Section s = loadSection(l);
            if (s == null) missing.add(l); else sources.add(s);
        }
        if (!missing.isEmpty())
            return refuse(out, "Not road sections on the network: " + String.join(", ", quoted(missing)) + ".");

        List<String> bad = new ArrayList<>();
        for (SectionLineage.Section s : sources) {
            String problem = s.chainageInconsistency(TOLERANCE);
            if (problem != null) bad.add(problem);
        }
        if (!bad.isEmpty())
            return refuse(out, String.join(" ", bad) + " Correct them before merging.");

        /* Same road, checked before the chainage arithmetic.
           Contiguity alone is not enough to establish that two sections belong together. The
           absolute chainage frame is only partly populated — 39% of the current network's
           sections start at 0 — so two sections of unrelated roads can abut by coincidence and
           read as consecutive.

           The road is taken from the Road Name attribute, NOT from the section label: the
           label's road code differs between sections of a single road
           (KPWD/MDR/501010103/18 and KPWD/MDR/501010104/5 are both "Parassala - Panachamoodu -
           Anappara Road"), so parsing the label would refuse legitimate merges. */
        Map<String, String> roads = roadNamesOf(labels);
        Set<String> distinct = new LinkedHashSet<>(roads.values());
        if (distinct.size() > 1) {
            Map<String, Object> r = refuse(out,
                    "These sections are not all on the same road, so merging them would join "
                  + "unrelated stretches whose chainages happen to meet. Roads involved: "
                  + String.join("; ", quoted(new ArrayList<>(distinct))) + ".");
            r.put("roads", roads);
            return r;
        }

        for (String l : labels) {
            String badGeometry = geometryProblem(l);
            if (badGeometry != null) return refuse(out, badGeometry);
        }

        String disjoint = joinedGeometryProblem(labels);
        if (disjoint != null) return refuse(out, disjoint);

        SectionLineage.MergePlan plan;
        try {
            plan = SectionLineage.planMerge(sources, resultLabel);
        } catch (IllegalArgumentException e) {
            return refuse(out, e.getMessage());
        }

        if (!labels.contains(resultLabel) && labelInUse(resultLabel))
            return refuse(out, "\"" + resultLabel + "\" is already used by a road section or by "
                             + "existing survey data outside this merge.");

        out.put("plan", Map.of(
                "result", describe(plan.result()),
                "offsets", plan.offsets()));

        List<Map<String, Object>> tables = new ArrayList<>();
        for (Target t : targets()) {
            if (t.table().equals("roads")) continue;
            /* EVERY section being merged is listed, including the ones holding nothing in this
               layer. Omitting an empty section reads as "its data is missing from the report",
               which is a different statement from "it has none" — and only one of them is true.
               The counts are also guaranteed to sum to the total this way, so the breakdown can
               be checked against it rather than taken on trust. */
            Map<String, Long> perLabel = new LinkedHashMap<>();
            long total = 0;
            for (String l : labels) {
                long n = count(t, l);
                perLabel.put(l, n);
                total += n;
            }
            if (total == 0) continue;   // no section has rows here: the layer itself is not involved
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("table", t.table());
            row.put("rows", total);
            row.put("by_section", perLabel);
            row.put("rebased", t.hasChainage());
            tables.add(row);
        }
        out.put("tables", tables);
        out.put("straddling_total", 0);   // a merge cuts nothing, so nothing can straddle
        out.put("warnings", warnings(labels, null));
        out.put("attributes", editableAttributes(labels));
        out.put("rebuild_after", REBUILD_AFTER);
        return ok(out);
    }

    /* ================= applying a split or a merge (Phase 2) ================= */

    /**
     * Whether a section's centreline can be cut or joined at all, or null when it can.
     *
     * <p>Every linear-reference query in the system runs {@code ST_LineMerge} over the stored
     * geometry and works on the result. A section drawn as several disjoint parts merges to a
     * MULTILINESTRING, and {@code ST_LineSubstring} rejects that outright — so the split would
     * fail mid-transaction rather than being refused here, and the condition segmentation has
     * always silently skipped such sections (see the geometry-type filter in
     * {@link SegmentService}). Refusing up front says so instead.
     */
    private String geometryProblem(String label) {
        RoadColumnMap c = roadColumns();
        List<String> types = jdbc.queryForList(
                "SELECT ST_GeometryType(ST_LineMerge(r.geom)) FROM roads r "
              + "WHERE r.\"" + c.label() + "\" = ? AND r.geom IS NOT NULL", String.class, label);
        if (types.isEmpty())
            return "\"" + label + "\" has no centreline geometry, so there is nothing to cut or join.";
        if (!"ST_LineString".equals(types.get(0)))
            return "\"" + label + "\" is drawn as " + types.get(0) + " — its parts do not join into a "
                 + "single line, so it cannot be cut or merged by linear reference. The condition "
                 + "segmentation already skips sections in this state; fix the centreline first.";
        return null;
    }

    /**
     * Whether the sections' centrelines actually join into one line, or null when they do.
     *
     * <p>Abutting CHAINAGE does not imply touching GEOMETRY. Two sections can meet exactly at
     * chainage 2 840 and still have centrelines that stop metres apart, because the chainage is
     * surveyed and the line is digitised. {@code ST_LineMerge(ST_Union(...))} then returns a
     * MULTILINESTRING, and {@code roads.geom} is typed {@code LineString} — so the merge fails
     * at the database mid-transaction rather than being refused with a reason.
     *
     * <p>Refusing here is also the right answer on its own terms: a merged section whose
     * geometry is two disconnected pieces cannot be linearly referenced at all, so every row
     * moved onto it would be unplaceable.
     */
    private String joinedGeometryProblem(List<String> labels) {
        RoadColumnMap c = roadColumns();
        String in = String.join(",", Collections.nCopies(labels.size(), "?"));
        List<String> type = jdbc.queryForList(
                "SELECT ST_GeometryType(ST_LineMerge(ST_Union(r.geom))) FROM roads r "
              + "WHERE r.\"" + c.label() + "\" IN (" + in + ")", String.class, labels.toArray());
        if (type.isEmpty() || type.get(0) == null)
            return "These sections have no joinable geometry.";
        if ("ST_LineString".equals(type.get(0))) return null;

        Double gap = jdbc.queryForObject(
                "SELECT MIN(ST_Distance(a.geom::geography, b.geom::geography)) FROM roads a, roads b "
              + "WHERE a.\"" + c.label() + "\" IN (" + in + ") AND b.\"" + c.label() + "\" IN (" + in + ") "
              + "  AND a.\"" + c.label() + "\" <> b.\"" + c.label() + "\"",
                Double.class, concat(labels, labels));
        return "These sections' centrelines do not join into a single line — merging them gives "
             + type.get(0) + (gap == null ? "" : ", with a gap of about " + fmt(gap) + " m between them")
             + ". Their chainages meet, but the drawn lines do not: chainage is surveyed and the "
             + "centreline is digitised, so the two can disagree. A merged section drawn in two "
             + "disconnected pieces cannot be linearly referenced at all, so every row moved onto "
             + "it would be unplaceable. Correct the centrelines first.";
    }

    private static Object[] concat(List<String> a, List<String> b) {
        List<Object> out = new ArrayList<>(a);
        out.addAll(b);
        return out.toArray();
    }

    /**
     * Splits one section in two: the road network row, the geometry, and every dependent row's
     * chainage, in one transaction.
     *
     * @param confirm        must be true — the preview is one endpoint away.
     * @param acceptWarnings must be true when the preview raised any high-severity warning
     *                       (a dual carriageway whose other leg is not being split, or video
     *                       clips the cut passes through).
     */
    public Map<String, Object> applySplit(String label, double atChainage, String firstLabel,
                                          String secondLabel, boolean confirm, boolean acceptWarnings,
                                          Map<String, Object> firstAttrs, Map<String, Object> secondAttrs,
                                          String username) {
        Map<String, Object> preview = previewSplit(label, atChainage, firstLabel, secondLabel, username);
        if (!"ok".equals(preview.get("status"))) return preview;

        Map<String, Object> gate = gate(preview, confirm, acceptWarnings);
        if (gate != null) return gate;

        SectionLineage.Section parent = loadSection(label);
        SectionLineage.SplitPlan plan =
                SectionLineage.planSplit(parent, atChainage, firstLabel, secondLabel);

        Map<String, Object> result = tx.execute(s ->
                writeSplit(plan, preview, firstAttrs, secondAttrs, username));
        afterCommit(result, List.of(firstLabel, secondLabel));
        return result;
    }

    /**
     * Merges two or more sections into one: the surviving road network row, the joined
     * geometry, and every dependent row's chainage re-based, in one transaction.
     */
    public Map<String, Object> applyMerge(List<String> labels, String resultLabel, boolean confirm,
                                          boolean acceptWarnings, Map<String, Object> resultAttrs,
                                          String username) {
        Map<String, Object> preview = previewMerge(labels, resultLabel, username);
        if (!"ok".equals(preview.get("status"))) return preview;

        Map<String, Object> gate = gate(preview, confirm, acceptWarnings);
        if (gate != null) return gate;

        List<SectionLineage.Section> sources = new ArrayList<>();
        for (String l : labels) sources.add(loadSection(l));
        SectionLineage.MergePlan plan = SectionLineage.planMerge(sources, resultLabel);

        Map<String, Object> result = tx.execute(s -> writeMerge(plan, preview, resultAttrs, username));
        afterCommit(result, List.of(resultLabel));
        return result;
    }

    /**
     * The two refusals that stand between a good preview and a write, or null to proceed.
     *
     * <p>High-severity warnings have to be acknowledged rather than merely displayed. The
     * dual-carriageway case is the reason: splitting one leg of a grouped pair leaves the
     * network looking complete while the length correction silently stops applying, and that is
     * not something an operator should be able to do by clicking through.
     */
    private Map<String, Object> gate(Map<String, Object> preview, boolean confirm, boolean acceptWarnings) {
        if (!confirm) {
            Map<String, Object> r = new LinkedHashMap<>(preview);
            r.put("status", "refused");
            r.put("message", "Nothing was changed. Re-send with \"confirm\": true to apply this.");
            return r;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> warnings = (List<Map<String, Object>>) preview.get("warnings");
        List<String> high = new ArrayList<>();
        if (warnings != null)
            for (Map<String, Object> w : warnings)
                if ("high".equals(w.get("severity"))) high.add(String.valueOf(w.get("kind")));
        if (!high.isEmpty() && !acceptWarnings) {
            Map<String, Object> r = new LinkedHashMap<>(preview);
            r.put("status", "refused");
            r.put("message", "This change carries warning(s) that must be acknowledged: "
                           + String.join(", ", high) + ". Read them below; if they are intended, "
                           + "re-send with \"accept_warnings\": true. Nothing was changed.");
            return r;
        }
        return null;
    }

    /** The split, inside one transaction. */
    private Map<String, Object> writeSplit(SectionLineage.SplitPlan plan, Map<String, Object> preview,
                                           Map<String, Object> firstAttrs, Map<String, Object> secondAttrs,
                                           String username) {
        String parent = plan.parent().label();
        String first = plan.first().label();
        String second = plan.second().label();
        String snapshot = snapshotOf(List.of(parent));

        /* Geometry first, from the parent's line, BEFORE the parent row is rewritten — the
           second child is cut from the same geometry the first one keeps. The parent row is then
           found by the id captured here, not by its label: the SECOND child may legitimately keep
           the parent's label, and the row just inserted for it carries that label too, so
           rewriting "the row labelled parent" would rewrite them both as the first child. */
        RoadColumnMap pc = roadColumns();
        List<Long> parentIds = jdbc.queryForList(
                "SELECT id FROM roads WHERE \"" + pc.label() + "\" = ?", Long.class, parent);
        insertSplitChild(plan);
        updateSplitParent(plan, parentIds);

        /* Operator edits land after both rows exist, so an attribute set on one half cannot be
           copied onto the other by the INSERT that builds it from the parent. */
        applyAttributeOverrides(first, firstAttrs);
        applyAttributeOverrides(second, secondAttrs);

        Map<String, Object> moved = new LinkedHashMap<>();
        for (Target t : targets()) {
            if (t.table().equals("roads")) continue;
            if (SPECIAL.contains(t.table())) continue;      // handled below
            moved.put(t.table(), moveRowsForSplit(t, parent, plan));
        }
        moved.put("calc_cw_member", splitDualCarriagewayMembership(parent, first, second));
        moved.put("saved_filters", rewriteSavedFilters(Map.of(parent, List.of(first, second))));

        Map<String, Object> undoPlan = new LinkedHashMap<>();
        undoPlan.put("kind", "split");
        undoPlan.put("parent", parent);
        undoPlan.put("first", first);
        undoPlan.put("second", second);
        undoPlan.put("cut", plan.localSplit());
        long logId = writeLog("split", List.of(parent), List.of(first, second),
                preview, undoPlan, snapshot, username);

        Map<String, Object> out = new LinkedHashMap<>(preview);
        out.put("dry_run", false);
        out.put("status", "ok");
        out.put("change_id", logId);
        out.put("moved", moved);
        out.put("message", "\"" + parent + "\" is now \"" + first + "\" and \"" + second + "\". Every "
                + "row on it was re-based onto the half it falls in. The centreline moved, so the cut "
                + "segments must be rebuilt — see rebuild_after.");
        log.info("split \"{}\" at {} into \"{}\"/\"{}\" by {} (change {})",
                parent, plan.localSplit(), first, second, username, logId);
        return out;
    }

    /** The merge, inside one transaction. */
    private Map<String, Object> writeMerge(SectionLineage.MergePlan plan, Map<String, Object> preview,
                                           Map<String, Object> resultAttrs, String username) {
        List<String> sources = new ArrayList<>();
        for (SectionLineage.Section s : plan.sources()) sources.add(s.label());
        String result = plan.result().label();
        String snapshot = snapshotOf(sources);

        Map<String, Object> moved = new LinkedHashMap<>();
        for (Target t : targets()) {
            if (t.table().equals("roads")) continue;
            if (SPECIAL.contains(t.table())) continue;
            moved.put(t.table(), moveRowsForMerge(t, plan));
        }
        moved.put("calc_cw_member", mergeDualCarriagewayMembership(sources, result));

        Map<String, List<String>> remap = new LinkedHashMap<>();
        for (String s : sources) remap.put(s, List.of(result));
        moved.put("saved_filters", rewriteSavedFilters(remap));

        /* The road network last: the dependent rows above are re-based against the SOURCE
           sections' lengths, which are still what plan.offsets() was computed from. */
        mergeRoadsRows(plan);

        Map<String, Object> undoPlan = new LinkedHashMap<>();
        undoPlan.put("kind", "merge");
        undoPlan.put("result", result);
        List<Map<String, Object>> ranges = new ArrayList<>();
        for (Map.Entry<String, Double> e : plan.offsets().entrySet())
            ranges.add(Map.of("label", e.getKey(), "from", e.getValue()));
        undoPlan.put("ranges", ranges);
        long logId = writeLog("merge", sources, List.of(result),
                preview, undoPlan, snapshot, username);

        Map<String, Object> out = new LinkedHashMap<>(preview);
        out.put("dry_run", false);
        out.put("status", "ok");
        out.put("change_id", logId);
        out.put("moved", moved);
        out.put("message", String.join(", ", quoted(sources)) + " are now \"" + result + "\". Each "
                + "section's rows were shifted by the length of everything before it. The centreline "
                + "changed, so the cut segments must be rebuilt — see rebuild_after.");
        log.info("merged {} into \"{}\" by {} (change {})", sources, result, username, logId);
        return out;
    }

    /* ---- the road network rows ---- */

    /**
     * Adds the second child as a new {@code roads} row: every attribute copied from the parent,
     * with the label, the four chainage columns and the geometry overridden.
     *
     * <p>The column list comes from the catalogue rather than being written out, so an attribute
     * added by a future shapefile import is carried onto the new section instead of silently
     * arriving blank there.
     */
    private void insertSplitChild(SectionLineage.SplitPlan plan) {
        RoadColumnMap c = roadColumns();

        /* Each overridden column is paired with its VALUE here, and the argument list is built
           while walking the columns in the order the projection emits them.

           Collecting the arguments separately, in the order they were thought of, is what this
           avoids: the placeholders come out in ordinal_position order, so the two lists line up
           only by coincidence. They did not — the second half was written with its measured
           length in Rd_End_cha, which is valid SQL, a plausible-looking row, and a section half
           the length it should be. */
        Map<String, Object> override = new LinkedHashMap<>();
        override.put(c.label(), plan.second().label());
        override.put(c.roadStart(), plan.second().startCh());
        override.put(c.roadEnd(), plan.second().endCh());
        if (c.measured() != null) override.put(c.measured(), plan.second().measuredLen());
        if (c.sectionStart() != null) override.put(c.sectionStart(), 0.0);
        if (c.sectionEnd() != null) override.put(c.sectionEnd(), plan.second().localEndCh());

        List<Object> args = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (String col : roadColumnNames()) {
            names.add("\"" + col + "\"");
            if (override.containsKey(col)) {
                values.add("?");
                args.add(override.get(col));
            } else {
                values.add("r.\"" + col + "\"");
            }
        }
        names.add("geom");
        values.add("ST_LineSubstring(ST_LineMerge(r.geom), ?, 1.0)");
        args.add(plan.fraction());
        args.add(plan.parent().label());

        jdbc.update("INSERT INTO roads (" + String.join(", ", names) + ") SELECT "
                  + String.join(", ", values) + " FROM roads r WHERE r.\"" + c.label() + "\" = ?",
                args.toArray());
    }

    /**
     * Rewrites the parent row in place as the FIRST child, trimming its geometry to the cut.
     *
     * @param parentIds the parent's row id(s), read before the second child was inserted — see
     *                  the note in {@code writeSplit}
     */
    private void updateSplitParent(SectionLineage.SplitPlan plan, List<Long> parentIds) {
        if (parentIds.isEmpty()) return;
        RoadColumnMap c = roadColumns();
        StringBuilder sql = new StringBuilder("UPDATE roads SET \"" + c.label() + "\" = ?, \""
                + c.roadStart() + "\" = ?, \"" + c.roadEnd() + "\" = ?");
        List<Object> args = new ArrayList<>(List.of(
                plan.first().label(), plan.first().startCh(), plan.first().endCh()));
        if (c.measured() != null) { sql.append(", \"").append(c.measured()).append("\" = ?");
                                    args.add(plan.first().measuredLen()); }
        if (c.sectionStart() != null) sql.append(", \"").append(c.sectionStart()).append("\" = 0");
        if (c.sectionEnd() != null) { sql.append(", \"").append(c.sectionEnd()).append("\" = ?");
                                      args.add(plan.first().localEndCh()); }
        sql.append(", geom = ST_LineSubstring(ST_LineMerge(geom), 0.0, ?)");
        args.add(plan.fraction());
        sql.append(" WHERE id IN (")
           .append(String.join(",", Collections.nCopies(parentIds.size(), "?"))).append(")");
        args.addAll(parentIds);
        jdbc.update(sql.toString(), args.toArray());
    }

    /**
     * Rewrites the FIRST source section as the merged one — joined geometry, spanning chainages
     * — and deletes the others.
     *
     * <p>The first source is kept rather than a new row inserted so that every attribute the
     * merged section did not explicitly set keeps a real value rather than a null, and so the
     * row's identity (and anything keyed on it outside this schema) survives.
     *
     * <p>The others are deleted <b>by id, captured before the keeper is relabelled</b>. The result
     * may legitimately reuse one of the merged sections' labels — {@link #checkLabel} offers
     * exactly that — and when the label it reuses is not the FIRST source's, the keeper comes out
     * of the UPDATE carrying a label that is also on the delete list. Deleting by label then
     * destroys the merged section itself: the merge reports success, every dependent row is
     * re-pointed at a section label the road network no longer has, and the section is simply
     * gone from the map.
     */
    private void mergeRoadsRows(SectionLineage.MergePlan plan) {
        RoadColumnMap c = roadColumns();
        List<String> sources = new ArrayList<>();
        for (SectionLineage.Section s : plan.sources()) sources.add(s.label());
        String keep = sources.get(0);
        List<String> drop = sources.subList(1, sources.size());

        List<Long> dropIds = drop.isEmpty() ? List.of() : jdbc.queryForList(
                "SELECT id FROM roads WHERE \"" + c.label() + "\" IN ("
              + String.join(",", Collections.nCopies(drop.size(), "?")) + ")",
                Long.class, drop.toArray());

        String in = String.join(",", Collections.nCopies(sources.size(), "?"));
        List<Object> geomArgs = new ArrayList<>(sources);

        /* Where the merged section ENDS is now the last source's end, so its end location has to
           travel with it. Keeping the first source's row carries every other attribute across
           correctly — but that row's Road End Location names the join, which is now the middle of
           the section. Left alone, a section running Thycad->Karette->Nedumangad reports that it
           ends at Karette, and the viewer's route label and the NSV route card both say so. */
        String endLocValue = null;
        boolean setEndLoc = c.endLoc() != null && sources.size() > 1;
        if (setEndLoc) {
            List<String> found = jdbc.queryForList(
                    "SELECT \"" + c.endLoc() + "\" FROM roads WHERE \"" + c.label() + "\" = ?",
                    String.class, sources.get(sources.size() - 1));
            if (found.isEmpty()) setEndLoc = false; else endLocValue = found.get(0);
        }

        StringBuilder sql = new StringBuilder("UPDATE roads SET \"" + c.label() + "\" = ?, \""
                + c.roadStart() + "\" = ?, \"" + c.roadEnd() + "\" = ?");
        List<Object> args = new ArrayList<>(List.of(
                plan.result().label(), plan.result().startCh(), plan.result().endCh()));
        if (c.measured() != null) { sql.append(", \"").append(c.measured()).append("\" = ?");
                                    args.add(plan.result().measuredLen()); }
        if (setEndLoc) { sql.append(", \"").append(c.endLoc()).append("\" = ?");
                         args.add(endLocValue); }
        if (c.sectionStart() != null) sql.append(", \"").append(c.sectionStart()).append("\" = 0");
        if (c.sectionEnd() != null) { sql.append(", \"").append(c.sectionEnd()).append("\" = ?");
                                      args.add(plan.result().localEndCh()); }
        sql.append(", geom = (SELECT ST_LineMerge(ST_Union(r2.geom)) FROM roads r2 WHERE r2.\"")
           .append(c.label()).append("\" IN (").append(in).append("))");
        args.addAll(geomArgs);
        sql.append(" WHERE \"").append(c.label()).append("\" = ?");
        args.add(keep);
        jdbc.update(sql.toString(), args.toArray());

        for (Long id : dropIds)
            jdbc.update("DELETE FROM roads WHERE id = ?", id);
    }

    /** Every {@code roads} column except the surrogate key and the geometry. */
    private List<String> roadColumnNames() {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
              + "WHERE table_schema = current_schema() AND table_name = 'roads' "
              + "  AND column_name NOT IN ('id','geom') ORDER BY ordinal_position", String.class);
    }

    /* ---- the dependent rows ---- */

    /**
     * Moves one table's rows onto the two children, re-basing their chainage.
     *
     * <p>Three groups, and the third is the one that needs a decision:
     *
     * <ul>
     *   <li>Wholly before the cut — relabelled, chainage untouched (the first child starts where
     *       the parent did, so its frame is identical).</li>
     *   <li>Wholly after — relabelled and shifted back by the cut offset.</li>
     *   <li><b>Straddling</b> — divided in two for {@code condition}, because an IRI or cracking
     *       value is an assertion about every metre of its span; assigned whole by start chainage
     *       for everything else, because a bridge cut in half is two bridges.</li>
     * </ul>
     */
    private Map<String, Object> moveRowsForSplit(Target t, String parent, SectionLineage.SplitPlan plan) {
        String table = t.table(), lab = t.labelColumn();
        double cut = plan.localSplit();
        String first = plan.first().label(), second = plan.second().label();
        Map<String, Object> report = new LinkedHashMap<>();

        if (!t.hasChainage()) {
            report.put("relabelled", jdbc.update(
                    "UPDATE \"" + table + "\" SET \"" + lab + "\" = ? WHERE \"" + lab + "\" = ?",
                    first, parent));
            report.put("note", "no chainage on this table — every row follows the first half");
            return report;
        }

        String start = t.startColumn(), end = t.endColumn();
        int divided = 0;

        /* Everything goes onto the first child up front, and the second half is taken off it
           below. This way round because the second child may keep the PARENT's label: with the
           second half moved off first, the sweep that collects "whatever is still on the parent"
           would collect it again and pull the whole second half back into the first child. After
           this relabel there is no "still on the parent" left to get wrong — the parent's rows
           are exactly the rows labelled `first`, whether or not `first` is the parent's own
           label, which is the one thing that cannot collide with either child. */
        int relabelled = jdbc.update(
                "UPDATE \"" + table + "\" SET \"" + lab + "\" = ? WHERE \"" + lab + "\" = ?",
                first, parent);

        /* A video clip the cut passes through is divided, not assigned.
           The FILE is not touched: the second half gets a row pointing at the same video with
           its own window, and a file window that says where that footage starts relative to the
           new section's origin — a NEGATIVE number, because the recording began before this
           section does. Defaulting file_from_ch/file_to_ch from the clip's own span first is
           what makes the arithmetic right for rows that never carried an explicit one. */
        if ("road_video".equals(table) && DIVIDED_ON_SPLIT.containsKey(table)
                && !t.extraChainage().isEmpty()) {
            jdbc.update("UPDATE road_video SET file_from_ch = COALESCE(file_from_ch, from_ch), "
                      + "       file_to_ch = COALESCE(file_to_ch, to_ch) "
                      + "WHERE section_label = ? AND from_ch < ? AND to_ch > ?", first, cut, cut);
            divided = jdbc.update(
                    "INSERT INTO road_video (section_label, video_file, direction, period_id, "
                  + "                        from_ch, to_ch, file_from_ch, file_to_ch) "
                  + "SELECT ?, video_file, direction, period_id, "
                  + "       0, to_ch - ?, file_from_ch - ?, file_to_ch - ? "
                  + "FROM road_video WHERE section_label = ? AND from_ch < ? AND to_ch > ?",
                    second, cut, cut, cut, first, cut, cut);
            jdbc.update("UPDATE road_video SET to_ch = ? "
                      + "WHERE section_label = ? AND from_ch < ? AND to_ch > ?",
                    cut, first, cut, cut);
        }

        if (!t.isPoint()) {
            /* Straddling rows first, while they are all still on the first child.
               For condition the row is duplicated onto the second child and then truncated on
               the first; the copy carries every other column, so the measurement travels with
               its two halves. */
            if ("condition".equals(table) && DIVIDED_ON_SPLIT.containsKey(table)) {
                List<String> cols = jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns "
                      + "WHERE table_schema = current_schema() AND table_name = ? "
                      + "  AND column_name <> 'id' ORDER BY ordinal_position", String.class, table);
                List<String> names = new ArrayList<>(), values = new ArrayList<>();
                for (String col : cols) {
                    names.add("\"" + col + "\"");
                    if (col.equals(lab)) values.add("?");
                    else if (col.equals(start)) values.add("0");
                    else if (col.equals(end)) values.add("\"" + end + "\" - ?");
                    else values.add("\"" + col + "\"");
                }
                divided = jdbc.update(
                        "INSERT INTO \"" + table + "\" (" + String.join(", ", names) + ") SELECT "
                      + String.join(", ", values) + " FROM \"" + table + "\" WHERE \"" + lab + "\" = ? "
                      + "  AND \"" + start + "\" < ? AND \"" + end + "\" > ?",
                        second, cut, first, cut, cut);
                jdbc.update(
                        "UPDATE \"" + table + "\" SET \"" + end + "\" = ? WHERE \"" + lab + "\" = ? "
                      + "  AND \"" + start + "\" < ? AND \"" + end + "\" > ?",
                        cut, first, cut, cut);
            }

            /* road_assets is mixed: one table holding measurements, continuous runs and
               structures. Only the first two are divided — a structure never reaches here
               because previewSplit refuses a cut that crosses one. Anything else (an asset type
               nobody has classified yet) keeps the old behaviour of moving whole by its start,
               and straddlingLineAssetWarning tells the operator so before they commit. */
            if ("road_assets".equals(table) && !DIVISIBLE_ASSET_TYPES.isEmpty()) {
                String[] types = DIVISIBLE_ASSET_TYPES.toArray(new String[0]);
                List<String> cols = jdbc.queryForList(
                        "SELECT column_name FROM information_schema.columns "
                      + "WHERE table_schema = current_schema() AND table_name = ? "
                      + "  AND column_name <> 'id' ORDER BY ordinal_position", String.class, table);
                List<String> names = new ArrayList<>(), values = new ArrayList<>();
                for (String col : cols) {
                    names.add("\"" + col + "\"");
                    if (col.equals(lab)) values.add("?");
                    else if (col.equals(start)) values.add("0");
                    else if (col.equals(end)) values.add("\"" + end + "\" - ?");
                    else if (col.equals("geom")) values.add("NULL");   // re-placed against the new line
                    else values.add("\"" + col + "\"");
                }
                int cutAssets = jdbc.update(
                        "INSERT INTO \"" + table + "\" (" + String.join(", ", names) + ") SELECT "
                      + String.join(", ", values) + " FROM \"" + table + "\" WHERE \"" + lab + "\" = ? "
                      + "  AND asset_type = ANY (?) AND \"" + start + "\" < ? AND \"" + end + "\" > ?",
                        second, cut, first, types, cut, cut);
                jdbc.update(
                        "UPDATE \"" + table + "\" SET \"" + end + "\" = ? WHERE \"" + lab + "\" = ? "
                      + "  AND asset_type = ANY (?) AND \"" + start + "\" < ? AND \"" + end + "\" > ?",
                        cut, first, types, cut, cut);
                divided += cutAssets;
            }
        }

        /* Everything from the cut onwards moves to the second child, shifted back. A row that
           merely ENDS at the cut stays with the first child — the same tie-break the Chainage
           Locator applies at a section join. */
        List<Object> shiftArgs = new ArrayList<>(List.of(second, cut));
        if (!t.isPoint()) shiftArgs.add(cut);
        for (int i = 0; i < t.extraChainage().size(); i++) shiftArgs.add(cut);
        shiftArgs.add(first);
        shiftArgs.add(cut);
        int toSecond = jdbc.update(
                "UPDATE \"" + table + "\" SET \"" + lab + "\" = ?, \"" + start + "\" = \"" + start + "\" - ?"
              + (t.isPoint() ? "" : ", \"" + end + "\" = \"" + end + "\" - ?")
              + t.shiftClause("-")
              + " WHERE \"" + lab + "\" = ? AND \"" + start + "\" >= ?",
                shiftArgs.toArray());

        report.put("to_first", relabelled - toSecond);
        report.put("to_second", toSecond);
        if (divided > 0) report.put("divided", divided);
        return report;
    }

    /**
     * Re-points one table's rows at the merged section, adding each source's offset.
     *
     * <p>The source that KEEPS its label as the result is moved first. Every other source label
     * differs from the result, so once that one has been shifted nothing else can be caught by
     * its {@code WHERE}. In source order it would be moved last instead — and by then the earlier
     * sources' rows already carry the result label, so they are shifted a SECOND time by this
     * source's offset, landing every one of them past the end of the section they measure.
     */
    private Map<String, Object> moveRowsForMerge(Target t, SectionLineage.MergePlan plan) {
        String table = t.table(), lab = t.labelColumn();
        String result = plan.result().label();
        Map<String, Object> per = new LinkedHashMap<>();

        List<Map.Entry<String, Double>> order = new ArrayList<>(plan.offsets().entrySet());
        order.sort(Comparator.comparing(e -> !e.getKey().equals(result)));   // stable: rest unchanged

        for (Map.Entry<String, Double> e : order) {
            String source = e.getKey();
            double offset = e.getValue();
            int n;
            if (!t.hasChainage()) {
                n = jdbc.update("UPDATE \"" + table + "\" SET \"" + lab + "\" = ? WHERE \"" + lab + "\" = ?",
                        result, source);
            } else if (t.isPoint()) {
                n = jdbc.update("UPDATE \"" + table + "\" SET \"" + lab + "\" = ?, \""
                              + t.startColumn() + "\" = \"" + t.startColumn() + "\" + ? WHERE \"" + lab + "\" = ?",
                        result, offset, source);
            } else {
                List<Object> a = new ArrayList<>(List.of(result, offset, offset));
                for (int i = 0; i < t.extraChainage().size(); i++) a.add(offset);
                a.add(source);
                n = jdbc.update("UPDATE \"" + table + "\" SET \"" + lab + "\" = ?, \""
                              + t.startColumn() + "\" = \"" + t.startColumn() + "\" + ?, \""
                              + t.endColumn() + "\" = \"" + t.endColumn() + "\" + ?"
                              + t.shiftClause("+") + " WHERE \"" + lab + "\" = ?",
                        a.toArray());
            }
            if (n > 0) per.put(source, n);
        }
        return per;
    }

    /* ---- the two tables the generic path cannot handle ---- */

    /**
     * Keeps a split section's dual-carriageway membership.
     *
     * <p>{@code calc_cw_member} keys {@code section_label} as its PRIMARY KEY, so one row cannot
     * become two by an UPDATE — the first child inherits the parent's row and the second needs an
     * INSERT into the same group. Without this the second half silently leaves the group, and the
     * length correction starts counting it as its own corridor.
     */
    private Map<String, Object> splitDualCarriagewayMembership(String parent, String first, String second) {
        if (!tableExists("calc_cw_member")) return Map.of();
        List<Integer> group = jdbc.queryForList(
                "SELECT group_id FROM calc_cw_member WHERE section_label = ?", Integer.class, parent);
        if (group.isEmpty()) return Map.of("members", 0);
        int updated = jdbc.update(
                "UPDATE calc_cw_member SET section_label = ? WHERE section_label = ?", first, parent);
        int inserted = jdbc.update(
                "INSERT INTO calc_cw_member(section_label, group_id) VALUES (?,?) "
              + "ON CONFLICT (section_label) DO NOTHING", second, group.get(0));
        return Map.of("group_id", group.get(0), "updated", updated, "inserted", inserted);
    }

    /**
     * Collapses a merge's dual-carriageway memberships into one.
     *
     * <p>Several sources relabelled to one result would collide on the primary key, so the first
     * membership is kept and the rest are deleted. When the sources sat in DIFFERENT groups that
     * is reported rather than resolved: merging across two dual-carriageway corridors is a
     * modelling question the length correction cannot answer on its own.
     */
    private Map<String, Object> mergeDualCarriagewayMembership(List<String> sources, String result) {
        if (!tableExists("calc_cw_member")) return Map.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String s : sources)
            rows.addAll(jdbc.queryForList(
                    "SELECT section_label, group_id FROM calc_cw_member WHERE section_label = ?", s));
        if (rows.isEmpty()) return Map.of("members", 0);

        Set<Object> groups = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) groups.add(r.get("group_id"));

        int deleted = 0;
        for (String s : sources)
            deleted += jdbc.update("DELETE FROM calc_cw_member WHERE section_label = ?", s);
        int inserted = jdbc.update(
                "INSERT INTO calc_cw_member(section_label, group_id) VALUES (?,?) "
              + "ON CONFLICT (section_label) DO NOTHING", result, rows.get(0).get("group_id"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups_involved", groups.size());
        out.put("deleted", deleted);
        out.put("inserted", inserted);
        if (groups.size() > 1)
            out.put("note", "the merged sections belonged to " + groups.size() + " different "
                  + "dual-carriageway groups; the merged section was placed in the first. Review "
                  + "the grouping in Calculation Rules.");
        return out;
    }

    /**
     * Rewrites section labels inside saved network filters.
     *
     * <p>{@code saved_filters} stores its labels in a {@code jsonb} payload and has no
     * {@code section_label} column, so the discovery that finds every other table is blind to
     * it. Left alone, a split or merge silently drops the section from every saved filter that
     * names it — the filter still loads, just quietly missing a road.
     *
     * <p>Done as a text substitution on the serialised payload rather than a structural edit:
     * the payload's shape differs per panel, and a label is a quoted string wherever it appears.
     * A split maps one label to two, so the replacement inserts both.
     */
    private Map<String, Object> rewriteSavedFilters(Map<String, List<String>> remap) {
        if (!tableExists("saved_filters")) return Map.of();
        int touched = 0;
        for (Map.Entry<String, List<String>> e : remap.entrySet()) {
            String from = "\"" + e.getKey() + "\"";
            StringBuilder to = new StringBuilder();
            for (String r : e.getValue()) {
                if (to.length() > 0) to.append(",");
                to.append("\"").append(r).append("\"");
            }
            touched += jdbc.update(
                    "UPDATE saved_filters SET payload = replace(payload::text, ?, ?)::jsonb, "
                  + "       updated_at = now() "
                  + "WHERE payload::text LIKE ?",
                    from, to.toString(), "%" + from + "%");
        }
        return Map.of("filters_updated", touched);
    }

    /* ---- after the commit ---- */

    /**
     * Re-places the affected sections' stored points and clears the caches.
     *
     * <p>Outside the transaction, deliberately: re-placement reads the new calibration back out
     * of {@code roads}, and clearing a cache is not transactional — doing either inside would let
     * a rollback leave re-placed rows or a cache rebuilt from data that no longer exists.
     */
    private void afterCommit(Map<String, Object> result, List<String> labels) {
        if (result == null || !"ok".equals(result.get("status"))) return;
        clearCaches();
        List<Map<String, Object>> replaced = new ArrayList<>();
        for (String l : labels) {
            try {
                replaced.add(placement.replaceForSection(l));
            } catch (Exception e) {
                log.error("section change committed but re-placing \"{}\" failed", l, e);
                replaced.add(Map.of("section", l, "status", "failed",
                        "message", "Re-placement failed. Run POST /api/placement/replace to finish."));
            }
        }
        result.put("replaced", replaced);
    }

    /* ================= warnings ================= */

    /** Named, not run: each is minutes of work across the whole network. */
    private static final List<Map<String, String>> REBUILD_AFTER = List.of(
            Map.of("endpoint", "POST /api/placement/replace",
                   "why", "Stored road-asset and traffic-station points hold the position the OLD "
                        + "centreline implied and must be recomputed."),
            Map.of("endpoint", "POST /api/segments/build",
                   "why", "Re-cut the condition segments and the 2 km IRI bins. These are cut from "
                        + "the centreline, so they are rebuilt rather than relabelled."),
            Map.of("endpoint", "POST /api/fwd-segments/build",
                   "why", "Same for the FWD stretches, if these sections carry FWD data."));

    /**
     * Everything the operator should read before committing, in severity order.
     *
     * <p>These are not refusals. Each names a consequence that is correct to accept in some
     * circumstances and wrong to accept unknowingly — which is exactly the set that belongs in
     * front of a person rather than in an {@code if}.
     */
    private List<Map<String, Object>> warnings(List<String> labels, Double localSplit) {
        List<Map<String, Object>> out = new ArrayList<>();

        /* Dual carriageway. Detected from the Calculation Rules grouping, NEVER from a trailing
           A/B in the label: the grouping is what the dashboard length correction actually reads,
           and a label suffix is a naming convention that no query depends on. A road whose legs
           are grouped must have BOTH legs split at the same chainage, or the correction silently
           stops applying to a road that still looks complete. */
        for (String l : labels) {
            for (Map<String, Object> partner : dualCarriagewayPartners(l)) {
                Map<String, Object> w = new LinkedHashMap<>(partner);
                w.put("severity", "high");
                w.put("kind", "dual_carriageway");
                w.put("message", "\"" + l + "\" is grouped as a dual carriageway with \""
                        + partner.get("partner") + "\" in Calculation Rules. Both legs must be "
                        + "changed the same way, at the same chainage — otherwise the dual-"
                        + "carriageway length correction stops applying and network kilometres "
                        + "are silently wrong.");
                out.add(w);
            }
        }

        /* calc_cw_member cannot be migrated by the generic UPDATE. */
        long cw = 0;
        for (String l : labels) cw += countWhere("calc_cw_member", "section_label", l);
        if (cw > 0) {
            out.add(warn("high", "calc_cw_member",
                    "These sections are members of a dual-carriageway group. That table keys "
                  + "section_label as its PRIMARY KEY, so a split has to INSERT a second member "
                  + "and a merge has to collapse two into one — neither is a relabel."));
        }

        /* saved_filters is invisible to column discovery. */
        long filters = savedFiltersReferencing(labels);
        if (filters > 0) {
            out.add(warn("medium", "saved_filters",
                    filters + " saved network filter(s) name these sections inside a jsonb payload. "
                  + "They have no section_label column, so nothing else in this report covers them; "
                  + "left alone the sections silently disappear from those filters."));
        }

        /* Video clips a split would cut through. */
        if (localSplit != null) {
            long clips = 0;
            for (String l : labels) clips += countStraddlingVideo(l, localSplit);
            if (clips > 0) {
                out.add(warn("info", "road_video",
                        clips + " NSV video clip(s) span the split point and will be divided in two. "
                      + "No video file is altered or re-encoded: both halves point at the same "
                      + "recording and each plays only its own part of it."));
            }
        }

        /* Line assets the cut would pass through. */
        if (localSplit != null) {
            for (String l : labels) {
                Map<String, Object> w = straddlingLineAssetWarning(l, localSplit);
                if (w != null) out.add(w);
            }
        }

        /* Everything is period-versioned, and the re-basing crosses all of it. */
        Set<Integer> periods = periodsTouched(labels);
        if (periods.size() > 1) {
            out.add(warn("info", "survey_periods",
                    "These sections carry data in " + periods.size() + " survey periods. Re-basing "
                  + "applies to all of them, so historic surveys are re-expressed in the new "
                  + "section's chainage frame."));
        }
        return out;
    }

    /* ================= reads ================= */

    /**
     * The road network's columns, resolved by what they MEAN rather than by the spelling one
     * shapefile import happened to produce.
     *
     * <p>{@code roads} is whatever the last import created, and DBF truncates every field name
     * to 10 characters, so {@code "Rd_Str_cha"} is not a fact about the system — it is a fact
     * about one survey return. The system attribute names in {@link LayerAttributeCatalog} are
     * the stable identifiers; a re-import that spells a field differently is absorbed by adding
     * a spelling there, not by editing this class.
     *
     * <p>Resolved per call against the live catalogue rather than cached: a road re-upload can
     * change the column list within the life of the process.
     */
    record RoadColumnMap(String label, String roadName, String roadStart, String roadEnd,
                         String sectionStart, String sectionEnd, String measured, String endLoc) {

        /** Names the system attributes the road network is missing, or empty when complete. */
        List<String> missing() {
            List<String> out = new ArrayList<>();
            if (label == null) out.add(LayerAttributeCatalog.SECTION_LABEL);
            if (roadStart == null) out.add(LayerAttributeCatalog.ROAD_START_CHAINAGE);
            if (roadEnd == null) out.add(LayerAttributeCatalog.ROAD_END_CHAINAGE);
            return out;
        }
    }

    RoadColumnMap roadColumns() {
        List<String> actual = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_schema = current_schema() AND table_name = 'roads'", String.class);
        return new RoadColumnMap(
                LayerAttributeCatalog.roadColumnFor(LayerAttributeCatalog.SECTION_LABEL, actual),
                LayerAttributeCatalog.roadColumnFor(LayerAttributeCatalog.ROAD_NAME, actual),
                LayerAttributeCatalog.roadColumnFor(LayerAttributeCatalog.ROAD_START_CHAINAGE, actual),
                LayerAttributeCatalog.roadColumnFor(LayerAttributeCatalog.ROAD_END_CHAINAGE, actual),
                LayerAttributeCatalog.roadColumnFor(LayerAttributeCatalog.SECTION_START_CHAINAGE, actual),
                LayerAttributeCatalog.roadColumnFor(LayerAttributeCatalog.SECTION_END_CHAINAGE, actual),
                LayerAttributeCatalog.roadColumnFor(LayerAttributeCatalog.MEASURED_LENGTH, actual),
                LayerAttributeCatalog.roadColumnFor(LayerAttributeCatalog.ROAD_END_LOCATION, actual));
    }

    /**
     * A message naming the system attributes the road network does not currently carry, or
     * null when everything needed is present.
     *
     * <p>Checked before the section lookup so that a road network missing a chainage field
     * reports THAT, rather than reporting every section as "not on the network" — which is
     * what a caller would otherwise see, and which points at entirely the wrong problem.
     */
    private String unresolvedRoadAttributes() {
        List<String> missing = roadColumns().missing();
        if (missing.isEmpty()) return null;
        return "The road network does not carry " + String.join(" or ", quoted(missing))
             + ". These are system attributes (Layer Management), resolved against whatever "
             + "columns the current road upload created. If the survey return spells the field "
             + "differently, add that spelling to the attribute catalogue rather than renaming "
             + "the column. Nothing was changed.";
    }

    /** Loads one section's calibration from {@code roads}, or null when it is not on the network. */
    SectionLineage.Section loadSection(String label) {
        if (label == null || label.isBlank()) return null;
        RoadColumnMap c = roadColumns();
        if (!c.missing().isEmpty()) return null;   // reported by the caller, which can say why

        String measured = c.measured() == null ? "NULL" : "r.\"" + c.measured() + "\"::double precision";
        List<SectionLineage.Section> found = jdbc.query(
                "SELECT r.\"" + c.label() + "\" AS label, " +
                "       r.\"" + c.roadStart() + "\"::double precision AS str_ch, " +
                "       r.\"" + c.roadEnd() + "\"::double precision AS end_ch, " +
                        measured + " AS measured, " +
                "       ST_Length(r.geom::geography) AS geom_len " +
                "FROM roads r WHERE r.\"" + c.label() + "\" = ?",
                (rs, i) -> new SectionLineage.Section(
                        rs.getString("label"),
                        (Double) rs.getObject("str_ch"),
                        (Double) rs.getObject("end_ch"),
                        (Double) rs.getObject("measured"),
                        (Double) rs.getObject("geom_len")),
                label);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Every table carrying a section label, with its chainage columns where it has them.
     * Derived tables are excluded — they are rebuilt, not migrated.
     */
    List<Target> targets() {
        List<Target> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // The road network's label column is resolved by meaning, not assumed — see roadColumns().
        String roadLabelColumn = roadColumns().label();
        if (roadLabelColumn != null && tableExists("roads")) {
            out.add(new Target("roads", roadLabelColumn, null, null, List.of()));
            seen.add("roads");
        }

        for (Map.Entry<String, String> e : LABEL_EXCEPTIONS.entrySet()) {
            if (!tableExists(e.getKey())) continue;
            out.add(target(e.getKey(), e.getValue()));
            seen.add(e.getKey());
        }

        for (String table : jdbc.queryForList("""
                SELECT c.table_name
                FROM information_schema.columns c
                JOIN information_schema.tables t
                  ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                WHERE c.table_schema = current_schema()
                  AND t.table_type = 'BASE TABLE'
                  AND c.column_name = ?
                ORDER BY c.table_name
                """, String.class, STANDARD_LABEL)) {
            if (seen.contains(table) || DERIVED.contains(table)) continue;
            if (!SAFE_IDENT.matcher(table).matches())
                throw new IllegalStateException(
                        "Table \"" + table + "\" carries a section label but its name cannot be used "
                      + "safely in a statement. Nothing was reported for it, so the report would have "
                      + "understated the change.");
            out.add(target(table, STANDARD_LABEL));
        }

        out.sort(Comparator.comparing((Target t) -> t.table().equals("roads") ? 0 : 1)
                           .thenComparing(Target::table));
        return out;
    }

    /** Finds a table's chainage columns by name, or leaves them null when it has none. */
    private Target target(String table, String labelColumn) {
        Set<String> cols = new HashSet<>(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_schema = current_schema() AND table_name = ?", String.class, table));
        List<String> extra = new ArrayList<>();
        for (String c : EXTRA_CHAINAGE.getOrDefault(table, List.of()))
            if (cols.contains(c)) extra.add(c);
        for (String[] pair : CHAINAGE_PAIRS) {
            if (cols.contains(pair[0]) && cols.contains(pair[1]))
                return new Target(table, labelColumn, pair[0], pair[1], extra);
        }
        return new Target(table, labelColumn, null, null, List.of());
    }

    private long count(Target t, String label) {
        return countWhere(t.table(), t.labelColumn(), label);
    }

    private long countWhere(String table, String column, String label) {
        if (!tableExists(table)) return 0;
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM \"" + table + "\" WHERE \"" + column + "\" = ?", Long.class, label);
        return n == null ? 0 : n;
    }

    /**
     * Rows that move to the SECOND half: those starting at or past the cut.
     *
     * <p>Exactly the predicate {@link #moveRowsForSplit} uses, so the preview cannot promise a
     * different distribution from the one the commit performs. A row merely ENDING at the cut
     * stays with the first half — the same tie-break the Chainage Locator applies at a section
     * join, and the reason the comparison is {@code >=} on the START column alone.
     */
    private long countFromCut(Target t, String label, double cut) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM \"" + t.table() + "\" WHERE \"" + t.labelColumn() + "\" = ? "
              + "AND \"" + t.startColumn() + "\" >= ?", Long.class, label, cut);
        return n == null ? 0 : n;
    }

    /** Rows whose span crosses the cut — the ones that must be divided or assigned. */
    private long countStraddling(Target t, String label, double localSplit) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM \"" + t.table() + "\" WHERE \"" + t.labelColumn() + "\" = ? " +
                "AND \"" + t.startColumn() + "\" < ? AND \"" + t.endColumn() + "\" > ?",
                Long.class, label, localSplit, localSplit);
        return n == null ? 0 : n;
    }

    /**
     * A cut that passes through a line asset, and where to put it instead.
     *
     * <p>A condition row or a video clip the cut crosses is DIVIDED, so the two halves still
     * describe the same ground. A line asset is not: it is assigned whole, by start chainage, on
     * the principle that a bridge cut in half is two bridges and the code must not invent the
     * second one. The consequence is that a guardrail or an FWD stretch beginning before the cut
     * stays on the first child carrying an end chainage past that child's own length — a row
     * claiming ground its section does not have, which no longer places correctly. Two such rows
     * already exist on the network from an earlier split.
     *
     * <p>Neither dividing nor truncating is the code's call to make, so the operator is told
     * before committing, and given the nearest chainage that clears every straddler — cutting
     * just before the earliest one starts, or just after the last one ends, whichever moves the
     * cut less. Returns null when the cut passes through nothing.
     */
    /**
     * Why this cut cannot be made, when it passes through a structure — or null when it doesn't.
     *
     * <p>Names the structures and the nearest chainage clear of ALL of them, so the refusal is
     * actionable rather than a dead end. "Clear of all" rather than "clear of these": moving off
     * one bridge and onto another would just refuse again with a different name.
     */
    private String structureAcrossCut(String label, double localSplit, SectionLineage.Section parent) {
        if (!tableExists("road_assets")) return null;
        List<Map<String, Object>> all;
        try {
            all = jdbc.queryForList(
                    "SELECT asset_type, start_chainage AS a, end_chainage AS b FROM road_assets "
                  + "WHERE section_label = ? AND asset_type = ANY (?) "
                  + "  AND start_chainage IS NOT NULL AND end_chainage > start_chainage ORDER BY 2",
                    label, INDIVISIBLE_ASSET_TYPES.toArray(new String[0]));
        } catch (Exception e) {
            return null;                                   // no such table/column here
        }
        List<Map<String, Object>> across = new ArrayList<>();
        for (Map<String, Object> r : all) {
            double a = ((Number) r.get("a")).doubleValue(), b = ((Number) r.get("b")).doubleValue();
            if (a < localSplit && b > localSplit) across.add(r);
        }
        if (across.isEmpty()) return null;

        double len = Math.abs(parent.refLen());
        StringBuilder names = new StringBuilder();
        for (Map<String, Object> r : across) {
            if (names.length() > 0) names.append(", ");
            names.append(r.get("asset_type")).append(' ')
                 .append(fmt(((Number) r.get("a")).doubleValue())).append('–')
                 .append(fmt(((Number) r.get("b")).doubleValue())).append(" m");
        }

        /* Nearest chainage outside every structure on the section. */
        Double best = null;
        for (Map<String, Object> r : all) {
            double a = ((Number) r.get("a")).doubleValue(), b = ((Number) r.get("b")).doubleValue();
            for (double edge : new double[]{a, b}) {
                if (edge <= 0.5 || edge >= len - 0.5) continue;
                boolean clear = true;
                for (Map<String, Object> o : all) {
                    double oa = ((Number) o.get("a")).doubleValue(), ob = ((Number) o.get("b")).doubleValue();
                    if (oa < edge && ob > edge) { clear = false; break; }
                }
                if (!clear) continue;
                if (best == null || Math.abs(edge - localSplit) < Math.abs(best - localSplit)) best = edge;
            }
        }

        return "The cut at " + fmt(localSplit) + " m passes through a structure — " + names
             + ". A bridge cut in half is not two bridges: dividing it would invent a structure "
             + "that does not exist, and assigning it whole would leave its deck partly outside "
             + "the section that claims it. Move the cut"
             + (best != null ? " to " + fmt(best) + " m" : " clear of it")
             + " and try again. Nothing was changed.";
    }

    private Map<String, Object> straddlingLineAssetWarning(String label, double localSplit) {
        /* Every line asset on the section, not only the ones this cut crosses: moving the cut
           clear of THESE can land it inside ANOTHER, so the alternative has to be chosen against
           the whole picture. Overlapping assets are merged into runs first — the cut is safe
           exactly where it falls outside every run. */
        List<double[]> spans = new ArrayList<>();
        Map<String, Integer> perTable = new LinkedHashMap<>();
        for (Target t : targets()) {
            if (t.table().equals("roads") || !t.hasChainage() || t.isPoint()) continue;
            boolean assets = "road_assets".equals(t.table());
            /* road_assets is the one mixed table: some of its types are divided and structures
               are refused outright, so it cannot be skipped wholesale like condition or video —
               only the types already handled are excluded, leaving this warning to cover an
               asset type nobody has classified yet. */
            if (!assets && DIVIDED_ON_SPLIT.containsKey(t.table())) continue;
            List<Map<String, Object>> rows;
            try {
                rows = jdbc.queryForList(
                        "SELECT \"" + t.startColumn() + "\" AS a, \"" + t.endColumn() + "\" AS b "
                      + "FROM \"" + t.table() + "\" WHERE \"" + t.labelColumn() + "\" = ? "
                      + "  AND \"" + t.startColumn() + "\" IS NOT NULL AND \"" + t.endColumn() + "\" IS NOT NULL "
                      + "  AND \"" + t.endColumn() + "\" > \"" + t.startColumn() + "\""
                      + (assets ? " AND NOT (asset_type = ANY (?))" : ""),
                        assets ? new Object[]{label, handledAssetTypes()} : new Object[]{label});
            } catch (Exception e) {
                continue;                                               // table absent in this database
            }
            int straddling = 0;
            for (Map<String, Object> r : rows) {
                double a = ((Number) r.get("a")).doubleValue(), b = ((Number) r.get("b")).doubleValue();
                spans.add(new double[]{a, b});
                if (a < localSplit && b > localSplit) straddling++;
            }
            if (straddling > 0) perTable.put(t.table(), straddling);
        }
        if (perTable.isEmpty()) return null;                            // the cut crosses nothing

        spans.sort((x, y) -> Double.compare(x[0], y[0]));
        List<double[]> runs = new ArrayList<>();
        for (double[] s : spans) {
            if (!runs.isEmpty() && s[0] <= runs.get(runs.size() - 1)[1])
                runs.get(runs.size() - 1)[1] = Math.max(runs.get(runs.size() - 1)[1], s[1]);
            else runs.add(new double[]{s[0], s[1]});
        }

        /* The run the cut is inside. Its two edges are the nearest clear chainages; either may
           fall outside the section, in which case it is not an option. */
        double len = Math.abs(loadSection(label).refLen());
        Double suggested = null;
        for (double[] run : runs) {
            if (localSplit <= run[0] || localSplit >= run[1]) continue;
            boolean loOk = run[0] > 0.5 && run[0] < len - 0.5;
            boolean hiOk = run[1] > 0.5 && run[1] < len - 0.5;
            if (loOk && hiOk) suggested = (localSplit - run[0] <= run[1] - localSplit) ? run[0] : run[1];
            else if (loOk) suggested = run[0];
            else if (hiOk) suggested = run[1];
            break;
        }

        long total = 0;
        StringBuilder what = new StringBuilder();
        for (Map.Entry<String, Integer> e : perTable.entrySet()) {
            total += e.getValue();
            if (what.length() > 0) what.append(", ");
            what.append(e.getValue()).append(" in ").append(e.getKey());
        }

        Map<String, Object> w = new LinkedHashMap<>();
        w.put("severity", "high");
        w.put("kind", "line_assets_straddle_split");
        w.put("straddling", perTable);
        if (suggested != null) w.put("suggested_local_chainage", suggested);
        w.put("message", "The cut at " + fmt(localSplit) + " m passes through " + total
                + " line asset(s) — " + what + ". Unlike condition rows and video clips, these "
                + "are not divided: each is assigned whole to the half its START falls in, so "
                + "one beginning before the cut stays on the first half with an end chainage "
                + "past that half's length, and no longer places correctly on the map. "
                + (suggested != null
                        ? "Move the cut to " + fmt(suggested) + " m to avoid breaking them, or "
                        + "accept this and correct the affected assets afterwards."
                        : "There is no clear chainage inside this section — the assets run to its "
                        + "edges — so either accept this and correct them afterwards, or shorten "
                        + "the assets first."));
        return w;
    }

    private long countStraddlingVideo(String label, double localSplit) {
        if (!tableExists("road_video")) return 0;
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM road_video WHERE section_label = ? " +
                "AND from_ch < ? AND to_ch > ?", Long.class, label, localSplit, localSplit);
        return n == null ? 0 : n;
    }

    /**
     * The other members of every dual-carriageway group these sections belong to.
     *
     * <p>Read from {@code calc_cw_member}, which is what {@link CalcRuleService} actually
     * consults when it applies the correction — not from the A/B suffix convention in the
     * label, which nothing queries.
     */
    private List<Map<String, Object>> dualCarriagewayPartners(String label) {
        if (!tableExists("calc_cw_member")) return List.of();
        return jdbc.queryForList("""
                SELECT m2.section_label AS partner, m2.group_id AS group_id
                FROM calc_cw_member m1
                JOIN calc_cw_member m2 ON m2.group_id = m1.group_id
                                      AND m2.section_label <> m1.section_label
                WHERE m1.section_label = ?
                ORDER BY m2.section_label
                """, label);
    }

    /** Saved network filters naming any of these sections inside their jsonb payload. */
    private long savedFiltersReferencing(List<String> labels) {
        if (!tableExists("saved_filters") || labels == null || labels.isEmpty()) return 0;
        // The payload's shape varies by panel, so this asks whether the label appears anywhere
        // in it as a json string rather than assuming a key. A report that missed one would be
        // worse than one that occasionally counts a coincidental match.
        long n = 0;
        for (String l : labels) {
            Long c = jdbc.queryForObject(
                    "SELECT count(*) FROM saved_filters WHERE payload::text LIKE ?",
                    Long.class, "%\"" + l + "\"%");
            n += c == null ? 0 : c;
        }
        return n;
    }

    private Set<Integer> periodsTouched(List<String> labels) {
        Set<Integer> out = new TreeSet<>();
        if (!tableExists("condition")) return out;
        for (String l : labels) {
            out.addAll(jdbc.queryForList(
                    "SELECT DISTINCT period_id FROM condition WHERE section_label = ? " +
                    "AND period_id IS NOT NULL", Integer.class, l));
        }
        return out;
    }

    /**
     * The road each section belongs to, keyed by section label.
     *
     * <p>A section whose Road Name is absent maps to a placeholder rather than being dropped,
     * so "these two are on different roads" and "we cannot tell" are not silently the same
     * answer — an unnamed road must not merge cleanly into a named one.
     */
    private Map<String, String> roadNamesOf(List<String> labels) {
        Map<String, String> out = new LinkedHashMap<>();
        RoadColumnMap c = roadColumns();
        if (c.roadName() == null) return out;   // network carries no road name: nothing to compare
        for (String l : labels) {
            List<String> found = jdbc.queryForList(
                    "SELECT COALESCE(NULLIF(TRIM(r.\"" + c.roadName() + "\"), ''), '(road name missing)') " +
                    "FROM roads r WHERE r.\"" + c.label() + "\" = ?", String.class, l);
            out.put(l, found.isEmpty() ? "(road name missing)" : found.get(0));
        }
        return out;
    }

    /**
     * Whether a label may be given to a new section, and if not, what already holds it.
     *
     * <p>For the editor to answer while the operator is still typing, rather than only when the
     * preview runs. The answer is the same one {@link #previewSplit} and {@link #previewMerge}
     * enforce, from the same {@link #labelInUse} check — so the field cannot say "available" for
     * a label the apply would then refuse.
     *
     * <p>{@code excluding} is the labels this operation is consuming. They are not "in use" for
     * this purpose: keeping the parent's label on one half of a split, or keeping one of the
     * merged sections' labels on the result, is the normal thing to do and must not read as a
     * clash with itself.
     *
     * <p>"In use" means referenced by ANY layer, not just present on the road network. A label
     * whose road section is long gone but whose survey rows are still there is the dangerous
     * case: adopting it would silently graft another section's history onto the new one.
     */
    public Map<String, Object> checkLabel(String label, List<String> excluding) {
        Map<String, Object> out = new LinkedHashMap<>();
        String l = label == null ? "" : label.trim();
        out.put("label", l);
        if (l.isEmpty()) {
            out.put("available", false);
            out.put("message", "A section label is required.");
            return out;
        }
        List<String> free = excluding == null ? List.of() : excluding;
        if (free.contains(l)) {
            out.put("available", true);
            out.put("message", "This is one of the sections being changed, so its label is free to reuse.");
            return out;
        }

        Integer maxLen = roadsLabelMaxLength();
        if (maxLen != null && l.length() > maxLen) {
            out.put("available", false);
            out.put("message", "This label is " + l.length() + " characters; the road network's "
                             + LayerAttributeCatalog.SECTION_LABEL + " column holds at most " + maxLen + ".");
            return out;
        }

        List<Map<String, Object>> used = new ArrayList<>();
        for (Target t : targets()) {
            long n = count(t, l);
            if (n > 0) used.add(Map.of("table", t.table(), "rows", n));
        }
        out.put("available", used.isEmpty());
        out.put("used_by", used);
        out.put("message", used.isEmpty()
                ? "Available."
                : "Already used by " + used.size() + " table(s) — "
                  + used.stream().map(u -> u.get("table") + " (" + u.get("rows") + ")")
                        .reduce((a, b) -> a + ", " + b).orElse("")
                  + ". Using it would merge two sets of records.");
        return out;
    }

    /** {@code character_maximum_length} of the road network's label column, or null when it is
     *  unbounded text. A shapefile import can produce a width-limited column. */
    private Integer roadsLabelMaxLength() {
        String col = roadColumns().label();
        if (col == null) return null;
        List<Integer> v = jdbc.queryForList(
                "SELECT character_maximum_length FROM information_schema.columns "
              + "WHERE table_schema = current_schema() AND table_name = 'roads' AND column_name = ?",
                Integer.class, col);
        return v.isEmpty() ? null : v.get(0);
    }

    /**
     * One section's chainage frame and its editable attributes.
     *
     * <p>The editor needs this before any preview runs, for two reasons. It lets the split point
     * be entered either way round — as road chainage or as a distance into the section — with
     * each shown against the other, which is the ambiguity that made a cut at "5800" mean 1 560 m
     * in on a section that starts at 4 240. And it supplies the attribute values the operator is
     * allowed to change on the resulting sections.
     */
    public Map<String, Object> sectionInfo(String label) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", label);
        SectionLineage.Section s = loadSection(label);
        if (s == null) {
            out.put("found", false);
            return out;
        }
        out.put("found", true);
        out.putAll(describe(s));
        out.put("length", round(Math.abs(s.refLen())));
        out.put("attributes", editableAttributes(List.of(label)));
        return out;
    }

    /**
     * The road-network attributes an operator may set on a resulting section, with each source
     * section's current value.
     *
     * <p>The label and the four chainage columns are excluded: those are computed by the split or
     * merge and editing them here would contradict the arithmetic that moved the data. Everything
     * else the network carries is offered, named by its system attribute where the catalogue
     * knows one — a column added by a future shapefile import therefore appears on its own,
     * rather than being silently undeclared and left blank on the new section.
     */
    List<Map<String, Object>> editableAttributes(List<String> labels) {
        RoadColumnMap c = roadColumns();
        Set<String> computed = new HashSet<>(Arrays.asList(
                c.label(), c.roadStart(), c.roadEnd(), c.sectionStart(), c.sectionEnd(), c.measured()));
        computed.remove(null);

        List<Map<String, Object>> out = new ArrayList<>();
        for (String column : roadColumnNames()) {
            if (computed.contains(column)) continue;
            Map<String, Object> values = new LinkedHashMap<>();
            for (String l : labels) {
                List<Map<String, Object>> row = jdbc.queryForList(
                        "SELECT \"" + column + "\" AS v FROM roads WHERE \"" + c.label() + "\" = ?", l);
                values.put(l, row.isEmpty() ? null : row.get(0).get("v"));
            }
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("attribute", LayerAttributeCatalog.roadSystemName(column) != null
                    ? LayerAttributeCatalog.roadSystemName(column) : column);
            a.put("column", column);
            a.put("values", values);
            out.add(a);
        }
        return out;
    }

    /**
     * Writes operator-chosen attribute values onto one resulting section.
     *
     * <p>Keys are COLUMN names, taken from what {@link #editableAttributes} handed out, and every
     * one is re-checked against the live column list before it is interpolated — the round trip
     * through a browser is exactly the boundary where a name stops being trustworthy. The label
     * and chainage columns are refused outright rather than ignored, because a request trying to
     * set them is asking for something this path cannot honour.
     */
    private int applyAttributeOverrides(String label, Map<String, Object> attrs) {
        if (attrs == null || attrs.isEmpty()) return 0;
        RoadColumnMap c = roadColumns();
        Set<String> computed = new HashSet<>(Arrays.asList(
                c.label(), c.roadStart(), c.roadEnd(), c.sectionStart(), c.sectionEnd(), c.measured()));
        computed.remove(null);

        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Object> e : attrs.entrySet()) {
            String column = e.getKey();
            if (computed.contains(column))
                throw new IllegalArgumentException("\"" + column + "\" is computed by the split or "
                        + "merge and cannot be set here.");
            if (!roadColumnNames().contains(column))
                throw new IllegalArgumentException("The road network has no column \"" + column + "\".");
            sets.add("\"" + column + "\" = ?");
            Object v = e.getValue();
            args.add(v == null || "".equals(v) ? null : v);
        }
        if (sets.isEmpty()) return 0;
        args.add(label);
        return jdbc.update("UPDATE roads SET " + String.join(", ", sets)
                         + " WHERE \"" + c.label() + "\" = ?", args.toArray());
    }

    /** Whether a label is taken — by the network, or by data left behind under it. */
    private boolean labelInUse(String label) {
        for (Target t : targets()) {
            if (count(t, label) > 0) return true;
        }
        return false;
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL", Boolean.class, table));
    }

    /* ================= shaping the report ================= */

    private static Map<String, Object> base(String op, String username) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("operation", op);
        m.put("dry_run", true);
        m.put("requested_by", username);
        return m;
    }

    private static Map<String, Object> ok(Map<String, Object> m) {
        m.put("status", "ok");
        m.put("message", "Nothing was changed. This is what the operation would do.");
        return m;
    }

    private static Map<String, Object> refuse(Map<String, Object> m, String why) {
        m.put("status", "refused");
        m.put("message", why);
        return m;
    }

    private static Map<String, Object> warn(String severity, String kind, String message) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("severity", severity);
        w.put("kind", kind);
        w.put("message", message);
        return w;
    }

    /**
     * A section's four chainage values as they would be written, keyed by SYSTEM ATTRIBUTE
     * NAME — "Road Start Chainage", not {@code Rd_Str_cha}.
     *
     * <p>The report is read by people and rendered in a form, and the column spelling is an
     * implementation detail of the last shapefile import. Keying on it would put a truncated
     * DBF name in front of an engineer and would change the API's shape the next time a survey
     * return spelled a field differently.
     */
    private static Map<String, Object> describe(SectionLineage.Section s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(LayerAttributeCatalog.SECTION_LABEL, s.label());
        m.put(LayerAttributeCatalog.ROAD_START_CHAINAGE, round(s.startCh()));
        m.put(LayerAttributeCatalog.ROAD_END_CHAINAGE, round(s.endCh()));
        m.put(LayerAttributeCatalog.SECTION_START_CHAINAGE, round(s.localStartCh()));
        m.put(LayerAttributeCatalog.SECTION_END_CHAINAGE, round(s.localEndCh()));
        m.put(LayerAttributeCatalog.MEASURED_LENGTH,
                s.measuredLen() == null ? null : round(s.measuredLen()));
        return m;
    }

    private static Double round(Double v) {
        return v == null ? null : Math.round(v * 1000) / 1000.0;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private static List<String> quoted(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) out.add("\"" + s + "\"");
        return out;
    }
}
