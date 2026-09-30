package com.fist.rmms_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

/**
 * KLRAMS — permanently deleting every survey/asset row for a set of road sections, without
 * touching the road network itself.
 *
 * <p>Used from the Data Console's "Remove Data" panel when a section was surveyed in error,
 * imported twice under the wrong label, or is being retired from the programme — the operator
 * picks section labels and every row that hangs off them (condition, road_assets, video,
 * traffic, the derived segment tables, user layers linked by Section_Label) is removed in one
 * transaction. {@code roads} is never a target: the section stays on the network with no
 * survey history, exactly like a section that has never been surveyed.
 *
 * <p>Targets are discovered the same way {@link SectionRenameService} discovers them — every
 * base table in the schema carrying a {@code section_label} column, plus {@code
 * traffic_stations} (spelled {@code section}) — rather than a hand-maintained list, for the
 * same reason: a list goes stale the first time a layer is added. {@code traffic_counts} has
 * no section column of its own (it is keyed by station name), so its rows are found by joining
 * through the stations about to be deleted.
 */
@Service
public class SectionDataRemovalService {

    private static final Logger log = LoggerFactory.getLogger(SectionDataRemovalService.class);

    private static final String STANDARD_COLUMN = "section_label";
    private static final Map<String, String> EXCEPTIONS = Map.of("traffic_stations", "section");

    /** Never a target — the road network itself. Deleting a section's data must leave the
     *  section on the network with no survey history, not remove the section too. */
    private static final Set<String> NEVER_TOUCH = Set.of("roads");

    /** Human-readable names for the log — what the Data Console and the audit trail call each
     *  table, or each {@code road_assets} row type, since one physical table holds seven
     *  different layers distinguished only by {@code asset_type}. Anything not named here
     *  (a user layer created through Layer Management) is looked up in {@code layer_definition}
     *  by physical table at audit time, falling back to the raw table name. */
    private static final Map<String, String> LAYER_NAME = Map.ofEntries(
            Map.entry("condition", "Road Condition Data"),
            Map.entry("condition_segments", "Condition Segments"),
            Map.entry("iri_2km_segments", "IRI 2km Segments"),
            Map.entry("fwd_segments", "FWD Segments"),
            Map.entry("road_video", "NSV Video"),
            Map.entry("traffic_stations", "Traffic Stations"),
            Map.entry("traffic_counts", "Traffic Counts"),
            Map.entry("calc_cw_member", "Dual-Carriageway Pairing"),
            Map.entry("road_assets:bridge", "Bridges"),
            Map.entry("road_assets:culvert", "Culverts"),
            Map.entry("road_assets:furniture_line", "Road Furniture (Line)"),
            Map.entry("road_assets:furniture_point", "Road Furniture (Point)"),
            Map.entry("road_assets:fwd", "FWD Deflection"),
            Map.entry("road_assets:subgrade", "Sub-Grade Soil"),
            Map.entry("road_assets:bituminous_core", "Bituminous Core"),
            Map.entry("road_assets:pavement_crust", "Pavement Crust"));

    private static final Pattern SAFE_IDENT = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private final JdbcTemplate jdbc;

    public SectionDataRemovalService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Target(String table, String column) {}

    /**
     * Deletes every row referencing any of {@code labels} across every discovered table
     * except {@code roads}, in one transaction. Returns a per-table row count.
     *
     * @param username the signed-in user, for the audit row; taken from the session by the
     *                 controller, never from the request body.
     */
    @Transactional
    public Map<String, Object> remove(List<String> labels, String username) {
        List<String> clean = new ArrayList<>();
        if (labels != null) {
            for (String l : labels) {
                if (l != null && !l.isBlank() && !clean.contains(l.trim())) clean.add(l.trim());
            }
        }
        if (clean.isEmpty())
            throw new IllegalArgumentException("At least one section label is required.");

        List<Target> targets = targets();

        List<Map<String, Object>> applied = new ArrayList<>();
        long total = 0;

        // traffic_counts has no section column of its own — it is keyed by station name, so
        // its rows must be found through traffic_stations before that table's rows are gone.
        if (tableExists("traffic_counts") && tableExists("traffic_stations")) {
            int n = jdbc.update(
                    "DELETE FROM traffic_counts WHERE name IN " +
                    "(SELECT name FROM traffic_stations WHERE section = ANY(?))",
                    (Object) clean.toArray(new String[0]));
            total += n;
            applied.add(row("traffic_counts", "name (via traffic_stations)", n, LAYER_NAME.get("traffic_counts")));
        }

        for (Target t : targets) {
            if (t.table().equals("road_assets")) {
                // One physical table, seven layers — break the count down by asset_type so
                // the log says which layers actually had data, not just "road_assets".
                for (Map<String, Object> g : jdbc.queryForList(
                        "SELECT asset_type, count(*) AS n FROM road_assets WHERE section_label = ANY(?) GROUP BY asset_type",
                        (Object) clean.toArray(new String[0]))) {
                    String assetType = (String) g.get("asset_type");
                    long n = ((Number) g.get("n")).longValue();
                    total += n;
                    applied.add(row("road_assets", "asset_type=" + assetType, n,
                            LAYER_NAME.getOrDefault("road_assets:" + assetType, "road_assets (" + assetType + ")")));
                }
                jdbc.update(
                        "DELETE FROM road_assets WHERE section_label = ANY(?)", (Object) clean.toArray(new String[0]));
                continue;
            }
            int n = jdbc.update(
                    "DELETE FROM \"" + t.table() + "\" WHERE \"" + t.column() + "\" = ANY(?)",
                    (Object) clean.toArray(new String[0]));
            total += n;
            applied.add(row(t.table(), t.column(), n, layerName(t.table())));
        }

        audit(clean, applied, total, username);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "ok");
        r.put("sections", clean);
        r.put("tables", applied);
        r.put("total", total);
        log.info("removed section data for {}: {} rows across {} tables, by {}",
                clean, total, applied.size(), username);
        return r;
    }

    /** {@link #LAYER_NAME} for the built-ins; a user layer's own name from {@code
     *  layer_definition} otherwise, since Layer Management gives each one a physical table
     *  the operator never sees. Falls back to the raw table name if neither has it — a
     *  table discovered by column name always gets SOME label in the log. */
    private String layerName(String table) {
        String known = LAYER_NAME.get(table);
        if (known != null) return known;
        try {
            List<String> names = jdbc.queryForList(
                    "SELECT name FROM layer_definition WHERE physical_table = ?", String.class, table);
            if (!names.isEmpty() && names.get(0) != null) return names.get(0);
        } catch (Exception ignored) {
            // layer_definition may not exist yet on a fresh schema — fall through.
        }
        return table;
    }

    /* ================= internals ================= */

    /** Every table holding a section label except {@code roads}: the exception column
     *  ({@code traffic_stations.section}) first, then every base table discovered from
     *  {@code information_schema} with a {@code section_label} column. */
    private List<Target> targets() {
        List<Target> out = new ArrayList<>();
        for (Map.Entry<String, String> e : EXCEPTIONS.entrySet()) {
            if (!NEVER_TOUCH.contains(e.getKey()) && tableExists(e.getKey()))
                out.add(new Target(e.getKey(), e.getValue()));
        }

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
            if (NEVER_TOUCH.contains(table) || EXCEPTIONS.containsKey(table)) continue;
            if (!SAFE_IDENT.matcher(table).matches()) {
                // Cannot be quoted into SQL safely. Refuse the whole removal rather than skip
                // it — a partial removal is the failure mode this class exists to prevent.
                throw new IllegalArgumentException(
                        "Table \"" + table + "\" carries a section label but its name cannot be used "
                      + "safely in a statement. Nothing was removed.");
            }
            out.add(new Target(table, STANDARD_COLUMN));
        }
        return out;
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT to_regclass(?) IS NOT NULL", Boolean.class, table));
    }

    private static Map<String, Object> row(String table, String column, long n, String layer) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("table", table);
        row.put("column", column);
        row.put("layer", layer);
        row.put("deleted", n);
        return row;
    }

    /** Records not just the total but which layers actually had data for these sections —
     *  the point of the audit trail is answering "what did removing this section actually
     *  take with it", and a bare row count cannot answer that. Tables with zero matching rows
     *  are left out; a section rarely carries every layer, and listing every layer at "0"
     *  would bury the ones that mattered. */
    private void audit(List<String> labels, List<Map<String, Object>> applied, long total, String username) {
        StringBuilder detail = new StringBuilder();
        detail.append(String.join(", ", labels)).append(" — ").append(total).append(" row(s) total");
        List<String> nonZero = new ArrayList<>();
        for (Map<String, Object> row : applied) {
            long n = ((Number) row.get("deleted")).longValue();
            if (n > 0) nonZero.add(row.get("layer") + " (" + n + ")");
        }
        if (!nonZero.isEmpty()) detail.append("; layers: ").append(String.join(", ", nonZero));
        try {
            jdbc.update("INSERT INTO upload_log(dataset,filename,status,detail,username) VALUES (?,?,?,?,?)",
                    "Section data removal", null, "ok", detail.toString(), username);
        } catch (Exception e) {
            // An audit row is not worth rolling the removal back for.
            log.warn("section data removal audit row failed", e);
        }
    }
}
