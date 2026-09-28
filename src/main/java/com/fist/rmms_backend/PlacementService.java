package com.fist.rmms_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * KLRAMS — linear-reference placement, shared by every layer that is positioned by
 * {@code Section_Label + chainage} rather than by its own coordinates.
 *
 * Two jobs:
 *
 * <ol>
 *   <li><b>Placement at import</b> ({@code placeAssets} / {@code placeTrafficStations}) —
 *       resolve chainage against the road centreline and STORE the result. Called by
 *       {@link AssetController} and {@link TrafficController} after their rows land.</li>
 *   <li><b>Re-placement</b> ({@code replaceAssetGeom} / {@code replaceTrafficGeom}) —
 *       recompute points already stored, against the road network as it stands now.</li>
 * </ol>
 *
 * Re-placement exists because a stored point is a snapshot taken when the file was
 * imported. Re-import the road network — redrawn centrelines, a corrected
 * {@code Rd_End_cha}, a re-split section — and every asset on that road is silently
 * left at the position the OLD geometry implied. Nothing detects this: the rows are
 * present, the map draws them, they are simply in the wrong place. Neither importer
 * re-places on its own (both only fill {@code geom IS NULL}), so without this the only
 * remedy is re-uploading every CSV.
 *
 * <h3>Two rules this follows, both deliberate</h3>
 *
 * <b>Re-placement never deletes.</b> Import deletes rows it cannot place, which is right
 * for a file someone just uploaded and can fix and re-send. Applying that here would mean
 * a road re-import that renames a section silently destroys the survey records attached to
 * it. Unplaceable rows are given a NULL geom instead — they drop off the map, stay in the
 * table, and are named in the response so the section label can be corrected.
 *
 * <b>There is no "blank everything, then re-place" step.</b> Setting every geom to NULL and
 * re-filling would leave the map empty for the duration, and empty for good if the second
 * statement failed. Instead the re-place is one UPDATE over the join (every row that still
 * matches a road gets its new position) followed by one UPDATE that orphans only the rows
 * that no longer match. Both run in a single transaction.
 */
@Service
public class PlacementService {

    private static final Logger log = LoggerFactory.getLogger(PlacementService.class);

    private final JdbcTemplate jdbc;

    /** Resolves the road network's columns by system attribute name, and owns the one
     *  definition of the reference length — see {@link RoadColumns#lenExpr}. */
    private final RoadColumns roadColumns;

    public PlacementService(JdbcTemplate jdbc, RoadColumns roadColumns) {
        this.jdbc = jdbc;
        this.roadColumns = roadColumns;
    }

    /** Reference length for a section, aliased {@code r}. Was a constant naming the DBF
     *  columns directly; now resolved by meaning, so a road import that spells a field
     *  differently does not silently break every placement in the system. */
    private String lenExpr() {
        return roadColumns.lenExpr("r");
    }

    /** {@code r."Section_La"} — whichever column currently carries the section label. */
    private String sectionCol() {
        return roadColumns.col("r", LayerAttributeCatalog.SECTION_LABEL);
    }

    /* ================= placement (import path) ================= */

    /** Place road assets of one type that have no geom yet. Returns rows placed. */
    public int placeAssets(String type, boolean isLine) {
        return jdbc.update(assetPlaceSql(isLine) + " AND a.geom IS NULL", type);
    }

    /** Place traffic stations that have no geom yet, optionally limited to one survey
     *  period. Returns rows placed. */
    public int placeTrafficStations(Integer periodId) {
        String sql = trafficPlaceSql() + " AND t.geom IS NULL";
        return periodId == null ? jdbc.update(sql) : jdbc.update(sql + " AND t.period_id = ?", periodId);
    }

    /* ================= re-placement ================= */

    /** Recompute every stored point/stretch of one asset type against the current road
     *  network. Unmatched rows are orphaned (geom NULL), never deleted. */
    @Transactional
    public Map<String, Object> replaceAssetGeom(String type, boolean isLine) {
        int placed = jdbc.update(assetPlaceSql(isLine), type);
        int orphaned = jdbc.update("""
            UPDATE road_assets a SET geom = NULL
            WHERE a.asset_type = ? AND a.geom IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM roads r
                              WHERE %s = a.section_label AND r.geom IS NOT NULL)
            """.formatted(sectionCol()), type);
        List<Map<String, Object>> unplaced = jdbc.queryForList("""
            SELECT DISTINCT section_label FROM road_assets
            WHERE asset_type = ? AND geom IS NULL ORDER BY section_label LIMIT 50
            """, type);
        return report(type, placed, orphaned, unplaced, "section_label",
                jdbc.queryForObject("SELECT count(*) FROM road_assets WHERE asset_type = ? AND geom IS NULL",
                        Integer.class, type));
    }

    /** Recompute every stored traffic-station point against the current road network.
     *  Unmatched stations are orphaned (geom NULL), never deleted. */
    @Transactional
    public Map<String, Object> replaceTrafficGeom() {
        int placed = jdbc.update(trafficPlaceSql());
        int orphaned = jdbc.update("""
            UPDATE traffic_stations t SET geom = NULL
            WHERE t.geom IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM roads r
                              WHERE %s = t.section AND r.geom IS NOT NULL)
            """.formatted(sectionCol()));
        List<Map<String, Object>> unplaced = jdbc.queryForList("""
            SELECT name, section FROM traffic_stations WHERE geom IS NULL ORDER BY name LIMIT 50
            """);
        return report("traffic_stations", placed, orphaned, unplaced, "name",
                jdbc.queryForObject("SELECT count(*) FROM traffic_stations WHERE geom IS NULL", Integer.class));
    }

    /**
     * Re-place every stored point and stretch on ONE section, against that section's current
     * calibration. Returns the number of rows repositioned per layer.
     *
     * <p>Exists for {@code SectionLineageService}: correcting a single section's chainage
     * changes the divisor for that section alone, so re-placing the whole network would be
     * minutes of work to fix a handful of rows. The SQL is the same
     * {@link #assetPlaceSql}/{@link #trafficPlaceSql} every other path uses, narrowed by one
     * extra predicate — the two must never compute a position differently.
     *
     * <p>Like the network-wide re-place, this <b>never deletes</b>: a row it cannot place keeps
     * its data and loses its geometry. Unlike it, this does not orphan rows either — the section
     * is known to exist (the caller just wrote it), so a row that fails to place here has a
     * chainage problem of its own, and blanking a geometry that is merely stale would remove it
     * from the map for a reason the operator did not ask for.
     */
    @Transactional
    public Map<String, Object> replaceForSection(String sectionLabel) {
        Map<String, Object> byLayer = new LinkedHashMap<>();
        int total = 0;

        for (String type : AssetController.LINE_TYPES) {
            int n = jdbc.update(assetPlaceSql(true) + " AND a.section_label = ?", type, sectionLabel);
            if (n > 0) byLayer.put(type, n);
            total += n;
        }
        for (String type : AssetController.POINT_TYPES) {
            int n = jdbc.update(assetPlaceSql(false) + " AND a.section_label = ?", type, sectionLabel);
            if (n > 0) byLayer.put(type, n);
            total += n;
        }
        int traffic = jdbc.update(trafficPlaceSql() + " AND t.section = ?", sectionLabel);
        if (traffic > 0) byLayer.put("traffic_stations", traffic);
        total += traffic;

        log.info("re-placed {} row(s) on section \"{}\" after a chainage correction", total, sectionLabel);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("section", sectionLabel);
        out.put("replaced", total);
        out.put("by_layer", byLayer);
        return out;
    }

    /** Per-layer stored/unplaced counts — the before-and-after view for the re-place. */
    public List<Map<String, Object>> status() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT asset_type AS layer, count(*) AS rows,
                       count(geom) AS placed, count(*) - count(geom) AS unplaced
                FROM road_assets GROUP BY asset_type ORDER BY asset_type
                """)) out.add(r);
        out.add(jdbc.queryForMap("""
                SELECT 'traffic_stations' AS layer, count(*) AS rows,
                       count(geom) AS placed, count(*) - count(geom) AS unplaced
                FROM traffic_stations
                """));
        return out;
    }

    /* ================= internals ================= */

    /* The placement UPDATEs, without the "only rows missing a geom" clause — the import
       path appends it, the re-place path does not. That one clause is the ONLY difference
       between placing and re-placing, which is exactly why they share this SQL: the two
       must never drift into computing a position differently. */
    private String assetPlaceSql(boolean isLine) {
        /* The two fractions are ordered with LEAST/GREATEST, not passed straight through.
           ST_LineSubstring raises "2nd arg must be smaller then 3rd arg" when the start
           fraction exceeds the end one, and 182 rows of the current road_assets table have
           start_chainage > end_chainage — a From/To recorded the wrong way round. Because
           placement is ONE statement per asset type, a single such row aborts the update for
           every asset of that type, so /api/placement/replace fails wholesale rather than
           skipping the row.

           Ordering them places a reversed row as the stretch between its two chainages, which
           is what the row means whichever end was written first. It changes nothing for a
           correctly-ordered row: LEAST is then the start and GREATEST the end. Equal chainages
           need no guard — PostGIS returns a POINT for them rather than raising. */
        String target = isLine
                ? """
                  ST_LineSubstring(ST_LineMerge(r.geom),
                      LEAST(
                          GREATEST(LEAST(a.start_chainage / %1$s, 1.0), 0.0),
                          GREATEST(LEAST(a.end_chainage   / %1$s, 1.0), 0.0)),
                      GREATEST(
                          GREATEST(LEAST(a.start_chainage / %1$s, 1.0), 0.0),
                          GREATEST(LEAST(a.end_chainage   / %1$s, 1.0), 0.0)))
                  """.formatted(lenExpr())
                : """
                  ST_LineInterpolatePoint(ST_LineMerge(r.geom),
                      GREATEST(LEAST(a.start_chainage / %1$s, 1.0), 0.0))
                  """.formatted(lenExpr());
        return """
               UPDATE road_assets a SET geom = %s
               FROM roads r
               WHERE a.asset_type = ? AND %s = a.section_label AND r.geom IS NOT NULL
               """.formatted(target, sectionCol());
    }

    private String trafficPlaceSql() {
        return """
               UPDATE traffic_stations t SET geom = ST_LineInterpolatePoint(
                   ST_LineMerge(r.geom),
                   GREATEST(LEAST(t.chainage / %s, 1.0), 0.0))
               FROM roads r
               WHERE t.chainage IS NOT NULL AND %s = t.section AND r.geom IS NOT NULL
               """.formatted(lenExpr(), sectionCol());
    }

    private Map<String, Object> report(String layer, int placed, int orphaned,
                                       List<Map<String, Object>> unplaced, String label, Integer unplacedTotal) {
        log.info("re-placed {}: {} rows repositioned, {} orphaned (section no longer on the network), {} unplaced in total",
                layer, placed, orphaned, unplacedTotal);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("layer", layer);
        m.put("replaced", placed);
        m.put("orphaned", orphaned);
        m.put("unplaced", unplacedTotal == null ? 0 : unplacedTotal);
        m.put("unplaced_sample", unplaced);
        m.put("unplaced_sample_key", label);
        return m;
    }
}
