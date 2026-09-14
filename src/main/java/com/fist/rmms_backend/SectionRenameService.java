package com.fist.rmms_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

/**
 * KLRAMS — renaming one road section label, everywhere, in one transaction.
 *
 * <h2>Why this exists</h2>
 *
 * {@code roads."Section_La"} is the join key for every linear-referenced layer in the
 * system, and it is a bare {@code text} column: no foreign key, no {@code ON UPDATE
 * CASCADE}, nothing. Every dependent table holds the label as a copied string. So there
 * is no such thing as "renaming a section" — changing the label in {@code roads} alone
 * silently splits the network in two:
 *
 * <ul>
 *   <li>Layers that STORE their geometry ({@code road_assets}, {@code traffic_stations})
 *       keep drawing in the right place, so the map looks healthy, while every join that
 *       supplies their district / road class misses and they drift into the blank bucket
 *       of every grouped dashboard. They also become "orphans" in the Data Console, one
 *       click away from a permanent delete, and the next
 *       {@link PlacementService#replaceAssetGeom} orphans their geometry outright.</li>
 *   <li>Layers JOINED at build or render time (condition segments, 2 km IRI, FWD) simply
 *       vanish for that section — FWD on the next map pan, since its tiles join
 *       {@code roads} per request.</li>
 *   <li>Quieter still: the dual-carriageway correction in {@link CalcRuleService} looks
 *       its width group up by label, misses, and stops applying — no error, only wrong
 *       kilometres.</li>
 * </ul>
 *
 * A road-network re-upload does not solve this either. In {@code merge} mode
 * {@link RoadUploadController} deletes only the INCOMING label, so a renamed section
 * leaves its old row behind: two centrelines for one road, network length inflated, and
 * every dependent row still pointing at the old one.
 *
 * <h2>How the target columns are found</h2>
 *
 * Not from a hard-coded list — that list would go stale the first time a layer was added.
 * Every base table in the schema carrying a {@code section_label} column is a target,
 * discovered from {@code information_schema}. That automatically covers the core tables
 * ({@code condition}, {@code road_assets}, {@code road_video}, {@code calc_cw_member}),
 * the derived ones ({@code condition_segments}, {@code fwd_segments},
 * {@code iri_2km_segments}) and every user layer created through Layer Management, whose
 * physical tables are generated with exactly that column name
 * (see {@link LayerRegistryService}). The two tables that spell it differently are named
 * in {@link #EXCEPTIONS}.
 *
 * <h2>Two modes, decided by the data, not by the caller</h2>
 *
 * <b>{@code rename}</b> — the old label IS on the network. The label is rewritten
 * everywhere, {@code roads} included. Nothing moves: no re-placement, no rebuild.
 *
 * <b>{@code recover}</b> — the old label is GONE from the network and the new one is
 * already there. This is the aftermath of a road re-upload that renamed a section before
 * anyone thought to rename it here — a {@code replace}-mode upload wipes the old row, and
 * every dependent table is left pointing at a label the network no longer has. The
 * dependents are re-pointed at the new label; {@code roads} is not touched, because the row
 * it would have renamed does not exist and the row it would have renamed INTO already does.
 *
 * <p>The two are mutually exclusive for any given pair, so there is nothing for the caller
 * to choose and no flag to get wrong — the only question the caller could answer is one the
 * database already answers. The mode actually taken is reported back.
 *
 * <p><b>Recovery does not leave the map correct on its own</b>, and says so in its response.
 * A plain rename moves nothing, but a recovery means the centreline under the new label came
 * from a re-upload and may have been redrawn. Stored asset and traffic points were
 * interpolated against the OLD geometry, and the derived tables were cut against it, so both
 * may now be stale. Those repairs — {@code /api/placement/replace} and a condition-segment
 * rebuild — are named in the {@code follow_up} list rather than run here: they are minutes
 * of work over the whole network, and whether the geometry actually changed is something
 * only the person who did the upload knows.
 *
 * <h2>Three rules this follows</h2>
 *
 * <b>It refuses to merge.</b> The new label must be free in every DEPENDENT table, in both
 * modes, and in {@code rename} mode it must be free in {@code roads} too. Renaming A to B
 * where B already carries survey data is not a rename, it is a merge of two sections'
 * history, and it cannot be undone by renaming back.
 *
 * <b>It does not rebuild the derived tables.</b> In {@code rename} mode a label change moves
 * no geometry, so the condition segments, FWD stretches and 2 km IRI bins are still cut in
 * exactly the right place; updating their label in place is equivalent to a rebuild at a
 * fraction of the cost. In {@code recover} mode they are relabelled too — leaving them on a
 * label nothing else uses would be strictly worse — and the rebuild is named in
 * {@code follow_up}.
 *
 * <b>Everything or nothing.</b> One transaction. A half-applied rename is strictly worse
 * than none at all, because the layers that survived and the layers that did not would
 * both look correct in isolation.
 */
@Service
public class SectionRenameService {

    private static final Logger log = LoggerFactory.getLogger(SectionRenameService.class);

    /** The column every linear-referenced table names its section label. */
    private static final String STANDARD_COLUMN = "section_label";

    /** The two tables that spell it otherwise: the road network itself (a shapefile
     *  column truncated to the 10-char DBF limit) and traffic stations. */
    private static final Map<String, String> EXCEPTIONS = Map.of(
            "roads", "Section_La",
            "traffic_stations", "section");

    /** Tables discovered by column name that are NOT section-label references and must
     *  never be rewritten. Empty today — it exists so that adding a table whose
     *  {@code section_label} means something else has an obvious place to be excluded,
     *  rather than being silently swept up by the discovery query. */
    private static final Set<String> EXCLUDED = Set.of();

    /** What a {@code recover} still needs afterwards, in the order it should be run.
     *  Named rather than executed: each is minutes of work across the whole network, and
     *  whether the re-upload actually redrew the centreline is something only the person
     *  who did it knows. {@code /api/segments/build} rebuilds the 2 km IRI roll-up with the
     *  condition segments, so that layer needs no separate entry. */
    private static final List<Map<String, String>> RECOVERY_FOLLOW_UP = List.of(
            Map.of("endpoint", "POST /api/placement/replace",
                   "why", "Re-place stored road-asset and traffic-station points against the "
                        + "current centreline. They still hold the position the old geometry implied."),
            Map.of("endpoint", "POST /api/segments/build",
                   "why", "Re-cut the condition segments (and the 2 km IRI bins) onto the current "
                        + "centreline. Relabelling moved their label, not their geometry."),
            Map.of("endpoint", "POST /api/fwd-segments/build",
                   "why", "Same for the FWD stretches, if this section carries FWD data."));

    /** Identifier guard. Table names come from {@code information_schema} so they are real,
     *  but they are concatenated into SQL (a table name cannot be a bind parameter), so
     *  they are checked against the same shape {@link LayerDataService} requires. */
    private static final Pattern SAFE_IDENT = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private final JdbcTemplate jdbc;
    private final RoadController roads;
    private final SegmentService segments;
    private final FwdSegmentService fwdSegments;
    private final IriSegmentService iriSegments;
    private final RoadAttrService attrs;

    public SectionRenameService(JdbcTemplate jdbc, RoadController roads, SegmentService segments,
                                FwdSegmentService fwdSegments, IriSegmentService iriSegments,
                                RoadAttrService attrs) {
        this.jdbc = jdbc;
        this.roads = roads;
        this.segments = segments;
        this.fwdSegments = fwdSegments;
        this.iriSegments = iriSegments;
        this.attrs = attrs;
    }

    /** One table's section-label column. */
    private record Target(String table, String column) {}

    /**
     * Points everything that references {@code from} at {@code to} instead, in one
     * transaction — renaming the road section itself when it is still on the network
     * ({@code mode: "rename"}), or re-pointing only the layers left stranded by a
     * re-upload that already renamed it ({@code mode: "recover"}).
     *
     * <p>Returns a per-table row count on success, or a {@code status} of
     * {@code not_found} / {@code exists} / {@code conflict} with nothing written.
     *
     * @param username the signed-in user, for the audit row; taken from the session by
     *                 the controller, never from the request body.
     */
    @Transactional
    public Map<String, Object> rename(String from, String to, String username) {
        String oldLabel = from == null ? "" : from.trim();
        String newLabel = to == null ? "" : to.trim();

        if (oldLabel.isEmpty() || newLabel.isEmpty())
            throw new IllegalArgumentException("Both the current and the new section label are required.");
        if (oldLabel.equals(newLabel))
            throw new IllegalArgumentException("The new section label is the same as the current one.");

        // The road network's own column may be width-limited (it arrives from a shapefile);
        // an over-long label would fail the UPDATE mid-transaction instead of here.
        Integer maxLen = roadsLabelMaxLength();
        if (maxLen != null && newLabel.length() > maxLen)
            throw new IllegalArgumentException(
                    "The new section label is " + newLabel.length() + " characters; the road network's "
                  + "Section_La column holds at most " + maxLen + ".");

        List<Target> targets = targets();

        /* --- preflight, all of it before anything is written --- */

        boolean fromOnNetwork = count("roads", "Section_La", oldLabel) > 0;
        boolean toOnNetwork   = count("roads", "Section_La", newLabel) > 0;

        if (fromOnNetwork && toOnNetwork) {
            return status("exists", oldLabel, newLabel,
                    "Both \"" + oldLabel + "\" and \"" + newLabel + "\" are road sections on the network. "
                  + "Renaming one into the other would merge two live sections' survey history, which "
                  + "cannot be undone by renaming back, so nothing was changed.");
        }

        /* Which mode the data allows. Not a caller's choice: exactly one of these can be
           true, and the database is what decides which. */
        boolean recovery = !fromOnNetwork;

        if (recovery) {
            if (!toOnNetwork) {
                return status("not_found", oldLabel, newLabel,
                        "Neither \"" + oldLabel + "\" nor \"" + newLabel + "\" is a road section on the "
                      + "network, so there is nothing to rename and nothing to re-point to. Check both "
                      + "labels against /api/roads/index. Nothing was changed.");
            }
            // "from" is off the network but "to" is on it — a re-upload renamed the section.
            // Only worth doing if something still refers to the old label.
            long stranded = dependentRows(targets, oldLabel);
            if (stranded == 0) {
                return status("not_found", oldLabel, newLabel,
                        "\"" + newLabel + "\" is on the road network and nothing anywhere still refers to "
                      + "\"" + oldLabel + "\", so there is nothing to repair. Nothing was changed.");
            }
        }

        /* The new label must be free in every DEPENDENT table, in BOTH modes. In a plain
           rename, rows already holding it are orphans from an earlier mistake. In a recovery,
           they are data imported under the new label after the re-upload — usually because
           someone re-uploaded a CSV to work around the problem. Either way, re-pointing into
           them merges two sets of survey rows silently and irreversibly, and the totals double.
           roads itself is excluded: it was settled by the mode check above. */
        List<Map<String, Object>> occupied = new ArrayList<>();
        for (Target t : targets) {
            if (t.table().equals("roads")) continue;
            long n = count(t.table(), t.column(), newLabel);
            if (n > 0) occupied.add(Map.of("table", t.table(), "rows", n));
        }
        if (!occupied.isEmpty()) {
            Map<String, Object> r = status("conflict", oldLabel, newLabel,
                    "\"" + newLabel + "\" is already referenced by other data (listed below), so moving "
                  + "\"" + oldLabel + "\"'s rows onto it would merge two sets of survey records and "
                  + "double every total. Clear or reconcile those rows first; nothing was changed.");
            r.put("mode", recovery ? "recover" : "rename");
            r.put("conflicts", occupied);
            return r;
        }

        /* --- the write --- */

        List<Map<String, Object>> applied = new ArrayList<>();
        long total = 0;
        for (Target t : targets) {
            // In recovery there is no roads row to rename, and the one it would be renamed
            // into is already there. Skipped rather than run as a guaranteed no-op, so the
            // per-table report cannot be misread as "the road network was left behind".
            if (recovery && t.table().equals("roads")) continue;
            int n = jdbc.update(
                    "UPDATE \"" + t.table() + "\" SET \"" + t.column() + "\" = ? WHERE \"" + t.column() + "\" = ?",
                    newLabel, oldLabel);
            total += n;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("table", t.table());
            row.put("column", t.column());
            row.put("updated", n);
            applied.add(row);
        }

        String mode = recovery ? "recover" : "rename";
        audit(mode, oldLabel, newLabel, total, username);

        Map<String, Object> r = status("ok", oldLabel, newLabel, recovery
                ? "Re-pointed " + total + " stranded row(s) across " + applied.size() + " table(s) from "
                + "\"" + oldLabel + "\" to the section now labelled \"" + newLabel + "\". The road network "
                + "was not modified. IMPORTANT: the centreline under this label came from a re-upload and "
                + "may have been redrawn, so stored points and cut segments may still sit against the old "
                + "geometry — see follow_up."
                : "Renamed across " + applied.size() + " table(s). Geometry is unchanged, so no "
                + "re-placement or segment rebuild is needed — but browsers hold map tiles for 30 days, "
                + "so a hard reload may be needed to see the new label in a popup.");
        r.put("mode", mode);
        r.put("tables", applied);
        r.put("total", total);
        if (recovery) r.put("follow_up", RECOVERY_FOLLOW_UP);
        log.info("section {} \"{}\" -> \"{}\" by {}: {} rows across {} tables",
                mode, oldLabel, newLabel, username, total, applied.size());
        return r;
    }

    /* ================= internals ================= */

    /**
     * Every table holding a section label: the exceptions first (the road network itself
     * leads, so the rename reads in dependency order), then every base table in the current
     * schema with a {@code section_label} column.
     */
    private List<Target> targets() {
        List<Target> out = new ArrayList<>();
        for (Map.Entry<String, String> e : EXCEPTIONS.entrySet()) {
            if (tableExists(e.getKey())) out.add(new Target(e.getKey(), e.getValue()));
        }
        // roads first — it is the one every other table's label refers to.
        out.sort(Comparator.comparing((Target t) -> t.table().equals("roads") ? 0 : 1)
                           .thenComparing(Target::table));

        List<String> discovered = jdbc.queryForList("""
                SELECT c.table_name
                FROM information_schema.columns c
                JOIN information_schema.tables t
                  ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                WHERE c.table_schema = current_schema()
                  AND t.table_type = 'BASE TABLE'
                  AND c.column_name = ?
                ORDER BY c.table_name
                """, String.class, STANDARD_COLUMN);

        for (String table : discovered) {
            if (EXCLUDED.contains(table) || EXCEPTIONS.containsKey(table)) continue;
            if (!SAFE_IDENT.matcher(table).matches()) {
                // Cannot be quoted into SQL safely. Refuse the whole rename rather than
                // skip it — a partial rename is the failure mode this class exists to prevent.
                throw new IllegalArgumentException(
                        "Table \"" + table + "\" carries a section label but its name cannot be used "
                      + "safely in a statement. The rename was not attempted.");
            }
            out.add(new Target(table, STANDARD_COLUMN));
        }
        return out;
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL", Boolean.class, table));
    }

    /** How many rows outside {@code roads} still carry {@code label} — what a recovery
     *  would move, and the test for whether there is anything to recover at all. */
    private long dependentRows(List<Target> targets, String label) {
        long n = 0;
        for (Target t : targets) {
            if (t.table().equals("roads")) continue;
            n += count(t.table(), t.column(), label);
        }
        return n;
    }

    private long count(String table, String column, String label) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM \"" + table + "\" WHERE \"" + column + "\" = ?", Long.class, label);
        return n == null ? 0 : n;
    }

    /** {@code character_maximum_length} of {@code roads."Section_La"}, or null if the column
     *  is unbounded {@code text} (which is the normal case). */
    private Integer roadsLabelMaxLength() {
        List<Integer> v = jdbc.queryForList(
                "SELECT character_maximum_length FROM information_schema.columns " +
                "WHERE table_schema = current_schema() AND table_name = 'roads' AND column_name = 'Section_La'",
                Integer.class);
        return v.isEmpty() ? null : v.get(0);
    }

    private void audit(String mode, String oldLabel, String newLabel, long total, String username) {
        try {
            jdbc.update("INSERT INTO upload_log(dataset,filename,status,detail,username) VALUES (?,?,?,?,?)",
                    "recover".equals(mode) ? "Road section recovery" : "Road section rename", null, "ok",
                    "\"" + oldLabel + "\" -> \"" + newLabel + "\" (" + total + " rows)", username);
        } catch (Exception e) {
            // An audit row is not worth rolling the rename back for.
            log.warn("section rename audit row failed", e);
        }
    }

    private static Map<String, Object> status(String status, String from, String to, String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", status);
        r.put("from", from);
        r.put("to", to);
        r.put("message", message);
        return r;
    }

    /**
     * Clears the in-memory GeoJSON caches that carry a section label in their payload.
     *
     * <p>Called AFTER the transaction commits, never inside it: clearing a cache is not
     * transactional, so doing it early would let a concurrent request rebuild the cache
     * from uncommitted-or-rolled-back data and then keep serving it.
     *
     * <p>Vector tiles are built per request from the tables and need no clearing — but the
     * browser holds them, which is why the response says so.
     */
    void clearCaches() {
        roads.refresh();            // road GeoJSON + index (also clears the attribute/legend cache)
        attrs.clearCache();         // belt and braces: refresh() already does this today
        segments.clearCache();
        fwdSegments.clearCache();
        iriSegments.clearCache();
    }
}
