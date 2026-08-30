package com.fist.rmms_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calculation Rules — the corrections and constants the dashboards and reports
 * apply, held as data instead of code.
 *
 * <h2>Why this exists</h2>
 * Every figure KLRAMS publishes carries a correction that used to be a hardcoded
 * rule inside a query or a JS file:
 *
 * <ul>
 *   <li><b>Carriageway correction</b> — a dual road is drawn as two centrelines
 *       that each carry the FULL length of one physical stretch, so summing them
 *       double-counts the network. The old rule guessed the pair from a trailing
 *       A/B in {@code Section_La}. It now comes from an explicit group the RMMS
 *       cell maintains here, and only the group's AVERAGE length is counted.</li>
 *   <li><b>Traffic station grouping</b> — the two carriageways of one physical
 *       count station are stored as two rows (TVM_STN_021A / …B). The old rule
 *       stripped the A/B with a regex; the pairing is now an explicit group.</li>
 *   <li><b>Pavement width bands</b> — the metres behind the {@code Pavement_W}
 *       band code (1–5), used to area-weight the PCI ranking, plus the halving
 *       applied to each carriageway of a dual road.</li>
 *   <li><b>PCI weights and thresholds</b> — the IRC:82-2023 numbers behind every
 *       PCI value. Changing these invalidates the PCI stored on every segment,
 *       so a save is followed by a segment rebuild (see {@link SegmentService}).</li>
 * </ul>
 *
 * <h2>Seeded from what the system does today</h2>
 * On first startup every table is filled with the result the hardcoded rule was
 * already producing — the A/B pairs become groups and the band metres
 * become rows. So the day this ships, no
 * published figure moves; from then on the tables are what the code reads and
 * the guessing regexes are never consulted again. Seeding is recorded in {@code calc_seed} and never
 * repeats, so a group somebody deletes stays deleted.
 *
 * <h2>What is deliberately NOT here</h2>
 * Road class, pavement surface and owner name are <b>attributes the road network
 * already defines</b>, not corrections. The dashboards group on the value the
 * data holds and take the wording from the Lookup &amp; Short Code module, which
 * is the one place a short code's meaning is set. Restating any of them here
 * would be a second place to keep right — and a spelling that is wrong is a data
 * fix, not something to paper over downstream.
 *
 * <h2>How the rules reach a query</h2>
 * Almost everything is a JOIN, not generated SQL: {@code calc_cw_member},
 * {@code calc_stn_member} and {@code calc_width_band} are joined straight into
 * the dashboard queries, so there is nothing to cache and nothing to escape.
 * The only values interpolated into SQL are the two width scalars, each
 * validated as a double before it goes anywhere near a statement.
 */
@Service
public class CalcRuleService {

    private static final Logger log = LoggerFactory.getLogger(CalcRuleService.class);

    /* Setting keys. */
    private static final String S_WIDTH_DEFAULT = "width_default_m";
    private static final String S_WIDTH_DUAL = "width_dual_factor";
    private static final String S_PCI = "pci_weights";

    /** Defaults, kept here so "Reset to default" has something to reset to. */
    static final double DEF_WIDTH_DEFAULT = 7.0;
    static final double DEF_WIDTH_DUAL = 0.5;
    private static final Map<String, Double> DEF_BANDS = new LinkedHashMap<>();
    static {
        DEF_BANDS.put("1", 4.5);
        DEF_BANDS.put("2", 6.25);
        DEF_BANDS.put("3", 8.5);
        DEF_BANDS.put("4", 11.5);
        DEF_BANDS.put("5", 14.0);
    }

    private final JdbcTemplate jdbc;

    public CalcRuleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ==================================================================
       Startup
       ================================================================== */

    /**
     * Called by {@link LayerRegistryService#ensure()} rather than from a
     * {@code @PostConstruct}, for the same reason the lookup seeding is: the
     * seeding reads {@code roads} and {@code traffic_stations}, which the upload
     * and period services must have created first.
     */
    public void ensure() {
        try {
            ensureSchema();
            seedOnce();
            applyPciSettings();
        } catch (Exception e) {
            log.error("Calculation Rules init failed — the module may be degraded, "
                    + "but the app will keep starting", e);
        }
    }

    private void ensureSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS calc_cw_group (
                id         serial PRIMARY KEY,
                name       text NOT NULL,
                note       text,
                created_by text,
                created_at timestamp NOT NULL DEFAULT now(),
                updated_at timestamp NOT NULL DEFAULT now()
            )""");
        /* section_label is the PRIMARY KEY, not just a column: that single
           constraint is what makes "a section label may appear in at most one
           group" impossible to violate, whatever the UI does. */
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS calc_cw_member (
                section_label text PRIMARY KEY,
                group_id      int NOT NULL REFERENCES calc_cw_group(id) ON DELETE CASCADE
            )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS calc_cw_member_grp ON calc_cw_member(group_id)");

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS calc_stn_group (
                id         serial PRIMARY KEY,
                name       text NOT NULL,
                note       text,
                created_by text,
                created_at timestamp NOT NULL DEFAULT now(),
                updated_at timestamp NOT NULL DEFAULT now()
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS calc_stn_member (
                station_name text PRIMARY KEY,
                group_id     int NOT NULL REFERENCES calc_stn_group(id) ON DELETE CASCADE
            )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS calc_stn_member_grp ON calc_stn_member(group_id)");

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS calc_width_band (
                code    text PRIMARY KEY,
                width_m double precision NOT NULL,
                note    text
            )""");

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS calc_setting (
                skey       text PRIMARY KEY,
                sval       text,
                updated_by text,
                updated_at timestamp NOT NULL DEFAULT now()
            )""");

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS calc_seed (
                skey    text PRIMARY KEY,
                done_at timestamp NOT NULL DEFAULT now(),
                detail  text
            )""");
    }

    /** Has this seeding step already run? Recorded so it never runs twice. */
    private boolean seeded(String key) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM calc_seed WHERE skey = ?", Integer.class, key);
        return n != null && n > 0;
    }

    private void markSeeded(String key, String detail) {
        jdbc.update("INSERT INTO calc_seed(skey, detail) VALUES (?,?) ON CONFLICT (skey) DO NOTHING", key, detail);
    }

    @Transactional
    protected void seedOnce() {
        seedWidthBands();
        seedCarriagewayGroups();
        seedStationGroups();
    }

    private void seedWidthBands() {
        if (seeded("width_bands")) return;
        DEF_BANDS.forEach((code, m) -> jdbc.update(
            "INSERT INTO calc_width_band(code, width_m, note) VALUES (?,?,?) ON CONFLICT (code) DO NOTHING",
            code, m, "IRC band " + code));
        putSettingIfAbsent(S_WIDTH_DEFAULT, String.valueOf(DEF_WIDTH_DEFAULT));
        putSettingIfAbsent(S_WIDTH_DUAL, String.valueOf(DEF_WIDTH_DUAL));
        markSeeded("width_bands", DEF_BANDS.size() + " bands");
    }

    /**
     * Turn today's A/B guess into explicit groups — once. Every {@code Single_Du
     * = 'Dual'} section whose label ends in A or B is grouped with the sections
     * sharing its label minus that letter. A group of one is not a correction,
     * so it is not created: a lone …A with no …B keeps its own full length,
     * exactly as the old {@code AVG} over a single row did.
     */
    private void seedCarriagewayGroups() {
        if (seeded("cw_groups")) return;
        List<Map<String, Object>> pairs = jdbc.queryForList("""
            SELECT left("Section_La", length("Section_La")-1) AS base_label,
                   MAX(NULLIF(trim("Road_Name"),'')) AS road_name,
                   count(*) AS n
            FROM roads
            WHERE lower(trim("Single_Du")) = 'dual' AND "Section_La" ~ '[AB]$'
            GROUP BY 1 HAVING count(*) > 1
            ORDER BY 1""");

        int made = 0;
        for (Map<String, Object> p : pairs) {
            String base = (String) p.get("base_label");
            String roadName = (String) p.get("road_name");
            String name = (roadName == null || roadName.isBlank()) ? base : roadName + " · " + base;
            Integer gid = jdbc.queryForObject(
                "INSERT INTO calc_cw_group(name, note, created_by) VALUES (?,?,?) RETURNING id",
                Integer.class, trunc(name, 200), "Seeded from the A/B section labels", "system");
            int added = jdbc.update("""
                INSERT INTO calc_cw_member(section_label, group_id)
                SELECT "Section_La", ?
                FROM roads
                WHERE lower(trim("Single_Du")) = 'dual'
                  AND left("Section_La", length("Section_La")-1) = ?
                  AND "Section_La" ~ '[AB]$'
                ON CONFLICT (section_label) DO NOTHING""", gid, base);
            if (added < 2) {
                // Nothing to correct — drop the group again rather than leave a stub.
                jdbc.update("DELETE FROM calc_cw_group WHERE id = ?", gid);
            } else {
                made++;
            }
        }
        markSeeded("cw_groups", made + " groups from A/B labels");
        log.info("Calculation Rules: seeded {} carriageway group(s) from A/B section labels", made);
    }

    /** The same one-time conversion for the traffic stations' A/B pairs. */
    private void seedStationGroups() {
        if (seeded("stn_groups")) return;
        int made = rescanStationPairs("system");
        markSeeded("stn_groups", made + " groups from A/B station names");
        log.info("Calculation Rules: seeded {} traffic-station group(s)", made);
    }

    /* ==================================================================
       SQL the dashboards build on
       ================================================================== */

    /**
     * The corrected-length CTE every road-network figure is built on.
     *
     * {@code base_label} is the carriageway GROUP when the section belongs to
     * one and the section label itself otherwise, so a grouped stretch collapses
     * to one corridor counted at the group's AVERAGE length and an ungrouped
     * section keeps its own. This replaced the A/B string-stripping the query
     * used to do; the grouping now comes from {@code calc_cw_member}.
     */
    static final String CORR = """
        WITH base AS (
          SELECT r.*,
                 COALESCE('g' || m.group_id::text, r."Section_La") AS base_label,
                 (m.group_id IS NOT NULL) AS is_grouped
          FROM roads r
          LEFT JOIN calc_cw_member m ON m.section_label = r."Section_La"),
        corr AS (
          SELECT base_label,
            bool_or(is_grouped) AS is_dual,
            CASE WHEN bool_or(is_grouped) THEN AVG("Measrd_Len"::double precision)
                 ELSE MAX("Measrd_Len"::double precision) END AS corr_len,
            MAX("District")   AS district,
            MAX("Road_Class") AS road_class,
            MAX("Cons_Type")  AS cons_type,
            MAX("PWD_Sec")    AS pwd_sec,
            /* Taken EXACTLY as stored — not trimmed, not normalised, not aliased.
               Two rows reading "PWD Maintenanace" that differ only by a trailing
               newline is not a display fault to smooth over: it is the road data
               saying something is wrong with it, and the breakdown is where that
               gets noticed. Once the value is corrected at source the duplicate
               row disappears for good, which a rule here could never achieve. */
            MAX("Current_Ow")  AS current_ow
          FROM base GROUP BY base_label)
        """;

    /**
     * Same correction, but a corridor keeps its own district / class / number /
     * name so a highway crossing several districts is not collapsed onto one.
     * Used by the "longest roads" ranking.
     */
    static final String LONG_CORR = """
        WITH base AS (
          SELECT r."District" AS district, r."Road_Class" AS road_class,
                 r."Road_Num" AS road_num, r."Road_Name" AS road_name,
                 r."Measrd_Len"::double precision AS len,
                 (m.group_id IS NOT NULL) AS is_grouped,
                 COALESCE('g' || m.group_id::text, r."Section_La") AS base_label
          FROM roads r
          LEFT JOIN calc_cw_member m ON m.section_label = r."Section_La"),
        corr AS (
          SELECT district, road_class, road_num, road_name,
            CASE WHEN bool_or(is_grouped) THEN AVG(len) ELSE MAX(len) END AS corr_len
          FROM base GROUP BY district, road_class, road_num, road_name, base_label)
        """;

    /** The JOIN {@link #widthSql} needs on {@code roads r}. */
    static final String RULE_JOINS =
        " LEFT JOIN calc_width_band wb ON wb.code = trim(r.\"Pavement_W\"::text) ";

    /**
     * Carriageway width in metres for area weighting: the band's metres, the
     * default when the code is unknown, halved for a dual road because the band
     * describes the WHOLE road while each of its two centrelines is one half.
     * Requires {@link #RULE_JOINS}.
     */
    String widthSql() {
        double def = settingDouble(S_WIDTH_DEFAULT, DEF_WIDTH_DEFAULT);
        double dual = settingDouble(S_WIDTH_DUAL, DEF_WIDTH_DUAL);
        return "(COALESCE(wb.width_m, " + num(def) + ")"
             + " * CASE WHEN lower(trim(r.\"Single_Du\")) = 'dual' THEN " + num(dual) + " ELSE 1 END)";
    }

    /* ==================================================================
       Traffic-station grouping (resolved in Java and in SQL)
       ================================================================== */

    /**
     * SQL for "which physical station is this row" — the group when the station
     * belongs to one, its own name otherwise. Joins {@code calc_stn_member} as
     * {@code sgm} on the alias given for the traffic_stations table.
     */
    static String stationKeySql(String stnAlias) {
        return "COALESCE('g' || sgm.group_id::text, trim(" + stnAlias + ".name))";
    }

    static String stationJoin(String stnAlias) {
        return " LEFT JOIN calc_stn_member sgm ON sgm.station_name = trim(" + stnAlias + ".name) ";
    }

    /** section label -> group key, for the correction the browser repeats in the
     *  map viewer's network scope card. */
    Map<String, String> carriagewayGroupOf() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT section_label, group_id FROM calc_cw_member", rs -> {
            out.put(rs.getString(1), "g" + rs.getInt(2));
        });
        return out;
    }

    /** station name -> group key, for the merge {@link TrafficDashboardController} does in Java. */
    Map<String, String> stationKeys() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT station_name, group_id FROM calc_stn_member", rs -> {
            out.put(rs.getString(1), "g" + rs.getInt(2));
        });
        return out;
    }

    /** Group id -> display name, so a merged station can be labelled. */
    Map<String, String> stationGroupNames() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT id, name FROM calc_stn_group", rs -> {
            out.put("g" + rs.getInt(1), rs.getString(2));
        });
        return out;
    }

    /**
     * Pull any still-ungrouped A/B station pairs into groups — the button that
     * lets a new import be folded in without typing every name. Returns how many
     * groups it made. A station already in a group is never moved.
     */
    @Transactional
    public int rescanStationPairs(String user) {
        List<Map<String, Object>> pairs = jdbc.queryForList("""
            SELECT regexp_replace(trim(t.name), '([0-9])[ABab]$', '\\1') AS base, count(*) AS n
            FROM (SELECT DISTINCT trim(name) AS name FROM traffic_stations) t
            LEFT JOIN calc_stn_member m ON m.station_name = trim(t.name)
            WHERE m.station_name IS NULL AND trim(t.name) ~ '[0-9][ABab]$'
            GROUP BY 1 HAVING count(*) > 1
            ORDER BY 1""");

        int made = 0;
        for (Map<String, Object> p : pairs) {
            String base = (String) p.get("base");
            Integer gid = jdbc.queryForObject(
                "INSERT INTO calc_stn_group(name, note, created_by) VALUES (?,?,?) RETURNING id",
                Integer.class, base, "Matched on the A/B station-name suffix", user);
            int added = jdbc.update("""
                INSERT INTO calc_stn_member(station_name, group_id)
                SELECT DISTINCT trim(name), ?
                FROM traffic_stations
                WHERE regexp_replace(trim(name), '([0-9])[ABab]$', '\\1') = ?
                  AND trim(name) ~ '[0-9][ABab]$'
                ON CONFLICT (station_name) DO NOTHING""", gid, base);
            if (added < 2) jdbc.update("DELETE FROM calc_stn_group WHERE id = ?", gid);
            else made++;
        }
        return made;
    }

    /* ==================================================================
       Carriageway groups — read and edit
       ================================================================== */

    /** Every group with its members and the length the correction produces. */
    public List<Map<String, Object>> carriagewayGroups() {
        List<Map<String, Object>> groups = jdbc.queryForList("""
            SELECT g.id, g.name, g.note, g.created_by, g.updated_at,
                   count(m.section_label) AS members,
                   ROUND(AVG(r."Measrd_Len"::double precision)::numeric, 1) AS corrected_m,
                   ROUND(SUM(r."Measrd_Len"::double precision)::numeric, 1) AS raw_m
            FROM calc_cw_group g
            LEFT JOIN calc_cw_member m ON m.group_id = g.id
            LEFT JOIN roads r ON r."Section_La" = m.section_label
            GROUP BY g.id ORDER BY g.name""");

        Map<Integer, List<Map<String, Object>>> byGroup = new LinkedHashMap<>();
        for (Map<String, Object> m : jdbc.queryForList("""
                SELECT m.group_id, m.section_label,
                       r."Road_Name" AS road_name, r."Road_Class" AS road_class,
                       r."District" AS district, r."Single_Du" AS single_du,
                       r."Measrd_Len"::double precision AS length_m,
                       (r."Section_La" IS NULL) AS missing
                FROM calc_cw_member m
                LEFT JOIN roads r ON r."Section_La" = m.section_label
                ORDER BY m.section_label""")) {
            byGroup.computeIfAbsent(((Number) m.get("group_id")).intValue(), k -> new ArrayList<>()).add(m);
        }
        for (Map<String, Object> g : groups) {
            g.put("members", byGroup.getOrDefault(((Number) g.get("id")).intValue(), List.of()));
        }
        return groups;
    }

    /**
     * The dual sections available to group — {@code Single_Du = 'Dual'} only,
     * as agreed: a section whose carriageway attribute is wrong is a data fix,
     * not something to paper over here. {@code grouped} says whether it is
     * already spoken for, and by which group.
     */
    public List<Map<String, Object>> carriagewayCandidates() {
        return jdbc.queryForList("""
            SELECT r."Section_La" AS section_label, r."Road_Name" AS road_name,
                   NULLIF(trim(r."Road_Num"::text),'') AS road_num,
                   r."Road_Class" AS road_class, r."District" AS district,
                   r."PWD_Sec" AS pwd_sec,
                   r."Measrd_Len"::double precision AS length_m,
                   m.group_id AS group_id, g.name AS group_name
            FROM roads r
            LEFT JOIN calc_cw_member m ON m.section_label = r."Section_La"
            LEFT JOIN calc_cw_group g ON g.id = m.group_id
            WHERE lower(trim(r."Single_Du")) = 'dual'
            ORDER BY r."District", r."Road_Name", r."Section_La\"""");
    }

    @Transactional
    public int createCarriagewayGroup(String name, List<String> sections, String note, String user) {
        List<String> members = clean(sections);
        if (members.size() < 2)
            throw new IllegalArgumentException("A carriageway group needs at least two section labels — "
                    + "a single section has nothing to average against.");
        String label = (name == null || name.isBlank()) ? members.get(0) : name.trim();
        Integer gid = jdbc.queryForObject(
            "INSERT INTO calc_cw_group(name, note, created_by) VALUES (?,?,?) RETURNING id",
            Integer.class, trunc(label, 200), blankToNull(note), user);
        addCarriagewayMembers(gid, members);
        return gid;
    }

    @Transactional
    public void addCarriagewayMembers(int groupId, List<String> sections) {
        for (String s : clean(sections)) {
            requireDualSection(s);
            String held = jdbc.query(
                "SELECT g.name FROM calc_cw_member m JOIN calc_cw_group g ON g.id = m.group_id "
              + "WHERE m.section_label = ?",
                rs -> rs.next() ? rs.getString(1) : null, s);
            if (held != null) {
                // The primary key would refuse this anyway; say which group holds it.
                throw new IllegalArgumentException(
                    "\"" + s + "\" is already in the group \"" + held + "\". "
                  + "Remove it from that group first — a section may only be counted in one.");
            }
            jdbc.update("INSERT INTO calc_cw_member(section_label, group_id) VALUES (?,?)", s, groupId);
        }
        touch("calc_cw_group", groupId);
    }

    private void requireDualSection(String section) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM roads WHERE \"Section_La\" = ? AND lower(trim(\"Single_Du\")) = 'dual'",
            Integer.class, section);
        if (n == null || n == 0) {
            throw new IllegalArgumentException(
                "\"" + section + "\" is not a section marked as a dual carriageway. "
              + "Correct its Carriageway attribute in the road data first.");
        }
    }

    @Transactional
    public void removeCarriagewayMember(int groupId, String section) {
        jdbc.update("DELETE FROM calc_cw_member WHERE group_id = ? AND section_label = ?", groupId, section);
        // A group of one corrects nothing — clear it rather than leave a stub behind.
        Integer left = jdbc.queryForObject(
            "SELECT COUNT(*) FROM calc_cw_member WHERE group_id = ?", Integer.class, groupId);
        if (left != null && left < 2) jdbc.update("DELETE FROM calc_cw_group WHERE id = ?", groupId);
        else touch("calc_cw_group", groupId);
    }

    @Transactional
    public void renameCarriagewayGroup(int groupId, String name, String note) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("The group needs a name.");
        jdbc.update("UPDATE calc_cw_group SET name = ?, note = ?, updated_at = now() WHERE id = ?",
                trunc(name.trim(), 200), blankToNull(note), groupId);
    }

    @Transactional
    public void deleteCarriagewayGroup(int groupId) {
        jdbc.update("DELETE FROM calc_cw_group WHERE id = ?", groupId);  // members cascade
    }

    /* ==================================================================
       Traffic-station groups — read and edit
       ================================================================== */

    public List<Map<String, Object>> stationGroups() {
        List<Map<String, Object>> groups = jdbc.queryForList("""
            SELECT g.id, g.name, g.note, g.created_by, g.updated_at,
                   count(m.station_name) AS member_count
            FROM calc_stn_group g
            LEFT JOIN calc_stn_member m ON m.group_id = g.id
            GROUP BY g.id ORDER BY g.name""");
        Map<Integer, List<Map<String, Object>>> byGroup = new LinkedHashMap<>();
        for (Map<String, Object> m : jdbc.queryForList("""
                SELECT m.group_id, m.station_name,
                       (SELECT count(*) FROM traffic_stations t WHERE trim(t.name) = m.station_name) AS rows_in_data
                FROM calc_stn_member m ORDER BY m.station_name""")) {
            byGroup.computeIfAbsent(((Number) m.get("group_id")).intValue(), k -> new ArrayList<>()).add(m);
        }
        for (Map<String, Object> g : groups) {
            g.put("members", byGroup.getOrDefault(((Number) g.get("id")).intValue(), List.of()));
        }
        return groups;
    }

    /** Every station name in the data, with the group holding it (if any). */
    public List<Map<String, Object>> stationCandidates() {
        try {
            return jdbc.queryForList("""
                SELECT t.name AS station_name, MAX(t.section) AS section,
                       MAX(m.group_id) AS group_id, MAX(g.name) AS group_name,
                       count(*) AS rows_in_data
                FROM (SELECT DISTINCT trim(name) AS name, section FROM traffic_stations) t
                LEFT JOIN calc_stn_member m ON m.station_name = t.name
                LEFT JOIN calc_stn_group g ON g.id = m.group_id
                GROUP BY t.name ORDER BY t.name""");
        } catch (Exception e) {
            log.warn("Traffic stations unavailable for the rules module", e);
            return List.of();
        }
    }

    @Transactional
    public int createStationGroup(String name, List<String> stations, String note, String user) {
        List<String> members = clean(stations);
        if (members.size() < 2)
            throw new IllegalArgumentException("A station group needs at least two station names — "
                    + "a single station is already counted once.");
        String label = (name == null || name.isBlank())
                ? members.get(0).replaceAll("([0-9])[ABab]$", "$1") : name.trim();
        Integer gid = jdbc.queryForObject(
            "INSERT INTO calc_stn_group(name, note, created_by) VALUES (?,?,?) RETURNING id",
            Integer.class, trunc(label, 200), blankToNull(note), user);
        addStationMembers(gid, members);
        return gid;
    }

    @Transactional
    public void addStationMembers(int groupId, List<String> stations) {
        for (String s : clean(stations)) {
            String held = jdbc.query(
                "SELECT g.name FROM calc_stn_member m JOIN calc_stn_group g ON g.id = m.group_id "
              + "WHERE m.station_name = ?",
                rs -> rs.next() ? rs.getString(1) : null, s);
            if (held != null)
                throw new IllegalArgumentException(
                    "\"" + s + "\" is already in the group \"" + held + "\". "
                  + "Remove it from that group first — a station may only be counted in one.");
            jdbc.update("INSERT INTO calc_stn_member(station_name, group_id) VALUES (?,?)", s, groupId);
        }
        touch("calc_stn_group", groupId);
    }

    @Transactional
    public void removeStationMember(int groupId, String station) {
        jdbc.update("DELETE FROM calc_stn_member WHERE group_id = ? AND station_name = ?", groupId, station);
        Integer left = jdbc.queryForObject(
            "SELECT COUNT(*) FROM calc_stn_member WHERE group_id = ?", Integer.class, groupId);
        if (left != null && left < 2) jdbc.update("DELETE FROM calc_stn_group WHERE id = ?", groupId);
        else touch("calc_stn_group", groupId);
    }

    @Transactional
    public void renameStationGroup(int groupId, String name, String note) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("The group needs a name.");
        jdbc.update("UPDATE calc_stn_group SET name = ?, note = ?, updated_at = now() WHERE id = ?",
                trunc(name.trim(), 200), blankToNull(note), groupId);
    }

    @Transactional
    public void deleteStationGroup(int groupId) {
        jdbc.update("DELETE FROM calc_stn_group WHERE id = ?", groupId);
    }

    /* ==================================================================
       Width bands, value maps, PCI
       ================================================================== */

    public Map<String, Object> widthBands() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bands", jdbc.queryForList(
            "SELECT code, width_m, note FROM calc_width_band ORDER BY code"));
        out.put("default_m", settingDouble(S_WIDTH_DEFAULT, DEF_WIDTH_DEFAULT));
        out.put("dual_factor", settingDouble(S_WIDTH_DUAL, DEF_WIDTH_DUAL));
        out.put("default_bands", DEF_BANDS);
        out.put("factory_default_m", DEF_WIDTH_DEFAULT);
        out.put("factory_dual_factor", DEF_WIDTH_DUAL);
        return out;
    }

    @Transactional
    public void putWidthBand(String code, double widthM, String note) {
        String c = (code == null ? "" : code.trim());
        if (c.isEmpty()) throw new IllegalArgumentException("The band needs a code.");
        if (!(widthM > 0) || widthM > 200)
            throw new IllegalArgumentException("Width must be a positive number of metres under 200.");
        jdbc.update("INSERT INTO calc_width_band(code, width_m, note) VALUES (?,?,?) "
                  + "ON CONFLICT (code) DO UPDATE SET width_m = EXCLUDED.width_m, note = EXCLUDED.note",
                c, widthM, blankToNull(note));
    }

    @Transactional
    public void deleteWidthBand(String code) {
        jdbc.update("DELETE FROM calc_width_band WHERE code = ?", code == null ? "" : code.trim());
    }

    @Transactional
    public void putWidthScalars(Double defaultM, Double dualFactor, String user) {
        if (defaultM != null) {
            if (!(defaultM > 0) || defaultM > 200)
                throw new IllegalArgumentException("The default width must be a positive number of metres under 200.");
            putSetting(S_WIDTH_DEFAULT, String.valueOf(defaultM), user);
        }
        if (dualFactor != null) {
            if (!(dualFactor > 0) || dualFactor > 1)
                throw new IllegalArgumentException("The dual-carriageway factor must be between 0 and 1 "
                        + "(0.5 halves the banded width across the two centrelines).");
            putSetting(S_WIDTH_DUAL, String.valueOf(dualFactor), user);
        }
    }

    /** The PCI weights and thresholds in force, alongside the IRC defaults. */
    public Map<String, Object> pciSettings() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> params = new ArrayList<>();
        Map<String, Double> w = PciCalculator.weights();
        Map<String, double[]> t = PciCalculator.thresholds();
        for (String k : PciCalculator.PARAM_KEYS) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("key", k);
            p.put("label", PciCalculator.PARAM_LABELS.get(k));
            p.put("weight", w.get(k));
            p.put("fair", t.get(k)[0]);
            p.put("poor", t.get(k)[1]);
            p.put("default_weight", PciCalculator.DEFAULT_WEIGHTS.get(k));
            p.put("default_fair", PciCalculator.DEFAULT_THRESHOLDS.get(k)[0]);
            p.put("default_poor", PciCalculator.DEFAULT_THRESHOLDS.get(k)[1]);
            params.add(p);
        }
        out.put("params", params);
        double sum = 0;
        for (double v : w.values()) sum += v;
        out.put("weight_sum", Math.round(sum * 10000.0) / 10000.0);
        out.put("at_default", PciCalculator.atDefault());
        return out;
    }

    /**
     * Save the PCI numbers. The stored per-segment PCI was computed with the OLD
     * numbers and is now stale — the caller is told to rebuild the segments,
     * which is what actually recomputes it.
     */
    @Transactional
    public void putPciSettings(List<Map<String, Object>> params, String user) {
        if (params == null || params.isEmpty()) throw new IllegalArgumentException("No PCI parameters were sent.");
        Map<String, Double> weights = new LinkedHashMap<>();
        Map<String, double[]> thresholds = new LinkedHashMap<>();
        for (Map<String, Object> p : params) {
            String k = String.valueOf(p.get("key"));
            if (!PciCalculator.PARAM_KEYS.contains(k))
                throw new IllegalArgumentException("Unknown PCI parameter: " + k);
            double w = dbl(p.get("weight"), "weight for " + k);
            double fair = dbl(p.get("fair"), "Good threshold for " + k);
            double poor = dbl(p.get("poor"), "Poor threshold for " + k);
            if (w < 0 || w > 1)
                throw new IllegalArgumentException("The weight for " + k + " must be between 0 and 1.");
            if (!(fair > 0) || !(poor > fair))
                throw new IllegalArgumentException(
                    "For " + k + ", the Poor threshold must be greater than the Good threshold, "
                  + "and both greater than zero.");
            weights.put(k, w);
            thresholds.put(k, new double[]{fair, poor});
        }
        for (String k : PciCalculator.PARAM_KEYS)
            if (!weights.containsKey(k))
                throw new IllegalArgumentException("The PCI parameter " + k + " was missing from the save.");

        StringBuilder json = new StringBuilder("{");
        for (String k : PciCalculator.PARAM_KEYS) {
            if (json.length() > 1) json.append(',');
            json.append('"').append(k).append("\":[")
                .append(weights.get(k)).append(',')
                .append(thresholds.get(k)[0]).append(',')
                .append(thresholds.get(k)[1]).append(']');
        }
        json.append('}');
        putSetting(S_PCI, json.toString(), user);
        PciCalculator.configure(weights, thresholds);
    }

    @Transactional
    public void resetPciSettings(String user) {
        jdbc.update("DELETE FROM calc_setting WHERE skey = ?", S_PCI);
        PciCalculator.configure(PciCalculator.DEFAULT_WEIGHTS, PciCalculator.DEFAULT_THRESHOLDS);
    }

    /** Push the saved PCI numbers into {@link PciCalculator} at startup. */
    private void applyPciSettings() {
        String raw = setting(S_PCI, null);
        if (raw == null || raw.isBlank()) return;
        try {
            Map<String, Double> weights = new LinkedHashMap<>();
            Map<String, double[]> thresholds = new LinkedHashMap<>();
            for (String k : PciCalculator.PARAM_KEYS) {
                int i = raw.indexOf('"' + k + "\":[");
                if (i < 0) return;                       // partial save — keep the defaults
                int s = raw.indexOf('[', i) + 1, e = raw.indexOf(']', s);
                String[] parts = raw.substring(s, e).split(",");
                if (parts.length != 3) return;
                weights.put(k, Double.parseDouble(parts[0].trim()));
                thresholds.put(k, new double[]{
                    Double.parseDouble(parts[1].trim()), Double.parseDouble(parts[2].trim())});
            }
            PciCalculator.configure(weights, thresholds);
            log.info("Calculation Rules: PCI weights/thresholds loaded from calc_setting");
        } catch (Exception e) {
            log.warn("Stored PCI settings could not be read — the IRC defaults stay in force", e);
        }
    }

    /* ==================================================================
       Before / after — what each correction actually did
       ================================================================== */

    /**
     * Every correction with the figure before it and the figure after it, which
     * is what the dashboards show under "corrections applied" and what the module
     * shows beside each rule. Read-only and cheap enough to compute per request.
     */
    public List<Map<String, Object>> effects() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(carriagewayEffect());
        out.add(stationEffect());
        out.add(widthEffect());
        return out;
    }

    /** Network length with every section counted, versus each group counted once. */
    public Map<String, Object> carriagewayEffect() {
        Map<String, Object> r = jdbc.queryForMap(CORR + """
            SELECT
              (SELECT ROUND(SUM("Measrd_Len"::double precision)::numeric/1000, 2) FROM roads) AS before_km,
              (SELECT count(*) FROM roads) AS before_count,
              ROUND(SUM(corr_len)::numeric/1000, 2) AS after_km,
              count(*) AS after_count,
              SUM(CASE WHEN is_dual THEN 1 ELSE 0 END) AS grouped_corridors
            FROM corr""");
        Integer groups = jdbc.queryForObject("SELECT count(*) FROM calc_cw_group", Integer.class);
        Integer members = jdbc.queryForObject("SELECT count(*) FROM calc_cw_member", Integer.class);
        return effect("carriageway", "Carriageway correction",
                "Road network length (km)",
                r.get("before_km"), r.get("after_km"), "km",
                r.get("before_count"), r.get("after_count"), "sections → corridors",
                groups + " group(s) covering " + members + " section label(s); "
              + "each group is counted once, at the average of its members' measured lengths.");
    }

    /** Station rows in the data, versus physical stations after grouping. */
    public Map<String, Object> stationEffect() {
        long before = 0, after = 0;
        try {
            Long b = jdbc.queryForObject(
                "SELECT count(DISTINCT trim(name)) FROM traffic_stations", Long.class);
            before = b == null ? 0 : b;
            Long a = jdbc.queryForObject(
                "SELECT count(DISTINCT " + stationKeySql("t") + ") FROM traffic_stations t"
              + stationJoin("t"), Long.class);
            after = a == null ? 0 : a;
        } catch (Exception e) {
            log.debug("Traffic stations unavailable for the effects report", e);
        }
        Integer groups = jdbc.queryForObject("SELECT count(*) FROM calc_stn_group", Integer.class);
        Integer members = jdbc.queryForObject("SELECT count(*) FROM calc_stn_member", Integer.class);
        return effect("traffic_station", "Traffic station grouping",
                "Traffic stations counted",
                before, after, "stations", null, null, null,
                groups + " group(s) covering " + members + " station name(s); "
              + "each group counts as one physical station.");
    }

    /** Total area the PCI ranking weights by, with and without the dual halving. */
    public Map<String, Object> widthEffect() {
        double def = settingDouble(S_WIDTH_DEFAULT, DEF_WIDTH_DEFAULT);
        double dual = settingDouble(S_WIDTH_DUAL, DEF_WIDTH_DUAL);
        Map<String, Object> r = jdbc.queryForMap(
            "SELECT ROUND(SUM(r.\"Measrd_Len\"::double precision "
          + "  * COALESCE(wb.width_m, " + num(def) + "))::numeric/1000000, 3) AS before_km2, "
          + "       ROUND(SUM(r.\"Measrd_Len\"::double precision * " + widthSql()
          + "  )::numeric/1000000, 3) AS after_km2 "
          + "FROM roads r" + RULE_JOINS);
        Integer bands = jdbc.queryForObject("SELECT count(*) FROM calc_width_band", Integer.class);
        return effect("pavement_width", "Pavement width bands",
                "Pavement area used for weighting (km²)",
                r.get("before_km2"), r.get("after_km2"), "km²", null, null, null,
                bands + " band(s), " + def + " m when the band code is missing; "
              + "each carriageway of a dual road is weighted at ×" + dual + " of the band width.");
    }

    /**
     * Where each rule is actually consumed — so nobody has to guess whether
     * changing a number here moves the Road Network dashboard, the PCI report or
     * both. Each entry is {screen, what it changes there}. Kept next to the rules
     * themselves because it is the first question anyone asks before editing one.
     */
    private static final Map<String, List<String[]>> USED_BY = new LinkedHashMap<>();
    static {
        USED_BY.put("carriageway", List.of(
            new String[]{"Dashboard · Road Network", "Total network length, and the length in every "
                       + "breakdown — by district, road class, PWD section, owner and construction type"},
            new String[]{"Dashboard · Longest Roads", "The corrected length each SH number and MDR name is ranked on"},
            new String[]{"Map viewer · Network scope card", "The \"Length\" tile (the \"Road Length (Carriageway "
                       + "considered)\" tile beside it is deliberately the uncorrected total)"},
            new String[]{"Reports · Road network exports", "Any length taken from the dashboard figures"}));
        USED_BY.put("traffic_station", List.of(
            new String[]{"Dashboard · Traffic", "One record per physical station — ADT, vehicle-class mix "
                       + "and the 24-hour profile of a group's members are merged"},
            new String[]{"Dashboard · Surveys", "The traffic-station count per district and period"},
            new String[]{"Map viewer · Network scope card", "The \"Traffic stations\" tile"},
            new String[]{"Map viewer · NSV / condition popup", "The station count shown for a stretch"}));
        USED_BY.put("pavement_width", List.of(
            new String[]{"PCI Report", "Area weighting — a road's PCI contribution is its length × this width"},
            new String[]{"Dashboard · Condition", "The worst-ranked roads and sections, ranked by area"}));
        USED_BY.put("pci", List.of(
            new String[]{"Map viewer · PCI layers", "Every PCI value drawn on the map (avg and worst basis)"},
            new String[]{"PCI Report", "Every PCI figure, band and length-by-band split"},
            new String[]{"Dashboard · Condition", "The PCI-derived rankings"},
            new String[]{"Stored segment data", "condition_segments.pci_avg / pci_worst — stale until the "
                       + "segments are rebuilt"}));
    }

    /** {screen, what it changes} for one rule; empty when the rule is unknown. */
    public static List<Map<String, String>> usedBy(String ruleKey) {
        List<Map<String, String>> out = new ArrayList<>();
        for (String[] u : USED_BY.getOrDefault(ruleKey, List.<String[]>of())) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("where", u[0]);
            m.put("what", u[1]);
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> effect(String key, String label, String metric,
                                              Object before, Object after, String unit,
                                              Object beforeCount, Object afterCount, String countUnit,
                                              String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("used_by", usedBy(key));
        m.put("metric", metric);
        m.put("unit", unit);
        m.put("before", before);
        m.put("after", after);
        m.put("delta", delta(before, after));
        if (beforeCount != null) {
            m.put("before_count", beforeCount);
            m.put("after_count", afterCount);
            m.put("count_unit", countUnit);
        }
        m.put("note", note);
        return m;
    }

    private static Double delta(Object before, Object after) {
        if (!(before instanceof Number b) || !(after instanceof Number a)) return null;
        return Math.round((a.doubleValue() - b.doubleValue()) * 1000.0) / 1000.0;
    }

    /* ==================================================================
       Small helpers
       ================================================================== */

    private void touch(String table, int id) {
        jdbc.update("UPDATE " + table + " SET updated_at = now() WHERE id = ?", id);
    }

    private static List<String> clean(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in == null) return out;
        for (String s : in) {
            if (s == null) continue;
            String t = s.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String trunc(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }

    private static double dbl(Object o, String what) {
        if (o == null) throw new IllegalArgumentException("Missing " + what + ".");
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + o + "\" is not a number — " + what + ".");
        }
    }

    /** A double rendered for SQL. Nothing but digits can survive the parse, so
     *  this cannot carry anything injectable into the statement. */
    private static String num(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d))
            throw new IllegalArgumentException("A calculation setting holds a value that is not a number.");
        return Double.toString(d);
    }

    private String setting(String key, String fallback) {
        try {
            return jdbc.query("SELECT sval FROM calc_setting WHERE skey = ?",
                    rs -> rs.next() ? rs.getString(1) : fallback, key);
        } catch (Exception e) {
            return fallback;
        }
    }

    private double settingDouble(String key, double fallback) {
        String v = setting(key, null);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void putSetting(String key, String val, String user) {
        jdbc.update("INSERT INTO calc_setting(skey, sval, updated_by, updated_at) VALUES (?,?,?,now()) "
                  + "ON CONFLICT (skey) DO UPDATE SET sval = EXCLUDED.sval, "
                  + "  updated_by = EXCLUDED.updated_by, updated_at = now()",
                key, val, user);
    }

    private void putSettingIfAbsent(String key, String val) {
        jdbc.update("INSERT INTO calc_setting(skey, sval, updated_by) VALUES (?,?,'system') "
                  + "ON CONFLICT (skey) DO NOTHING", key, val);
    }
}
