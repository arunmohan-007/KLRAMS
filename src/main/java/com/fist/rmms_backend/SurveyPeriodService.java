package com.fist.rmms_backend;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Survey Periods — named survey cycles (e.g. "Survey 1", 01-Nov-2025 to
 * 01-Sep-2026). Every field-survey upload (condition, FWD, soil, bituminous
 * core, pavement crust, traffic) is tagged with the period chosen at import
 * time, so a new survey cycle is stored ALONGSIDE the old ones instead of
 * replacing them. Exactly one period is "active": the main map viewer and all
 * default /geojson responses show the active period; the Survey Archive page
 * can request any period via ?period_id=.
 *
 * On first startup after this feature ships, all pre-existing survey rows are
 * migrated into "Survey 1" (01-Nov-2025 → 01-Sep-2026), which becomes active.
 */
@Service
public class SurveyPeriodService {

    private static final Logger log = LoggerFactory.getLogger(SurveyPeriodService.class);

    /** Tables that carry a period_id. road_assets is special-cased in the
     *  migration (only survey asset types get tagged; bridges etc. stay NULL). */
    static final List<String> PERIOD_TABLES = List.of(
            "condition", "road_assets", "traffic_stations", "traffic_counts",
            "condition_segments", "fwd_segments", "road_video");

    /** Asset types in road_assets that belong to a survey cycle. */
    static final List<String> SURVEY_ASSET_TYPES = List.of(
            "fwd", "subgrade", "bituminous_core", "pavement_crust");

    private final JdbcTemplate jdbc;

    /* Active period id is read on every map/geojson request — cache it and
       invalidate whenever the active period changes. */
    private volatile Integer cachedActiveId;

    public SurveyPeriodService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    @Transactional
    public void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS survey_periods (
                id serial PRIMARY KEY,
                name text UNIQUE NOT NULL,
                start_date date,
                end_date date,
                is_active boolean DEFAULT false,
                created_at timestamp DEFAULT now()
            )""");

        // Make sure every existing data table has the period_id column before
        // anything queries it (tables themselves are created lazily elsewhere).
        for (String t : PERIOD_TABLES) addPeriodColumn(t);

        Long n = jdbc.queryForObject("SELECT count(*) FROM survey_periods", Long.class);
        if (n != null && n == 0) {
            jdbc.update("INSERT INTO survey_periods(name, start_date, end_date, is_active) " +
                    "VALUES ('Survey 1', DATE '2025-11-01', DATE '2026-09-01', true)");
            Integer id = jdbc.queryForObject(
                    "SELECT id FROM survey_periods WHERE name = 'Survey 1'", Integer.class);
            migrateExistingData(id);
            log.info("Survey periods: created 'Survey 1' (01-Nov-2025 → 01-Sep-2026) and tagged all existing survey data with it");
        }
    }

    /** Adds period_id + its index to a table if the table already exists.
     *  Safe to call repeatedly and before the table is first created. */
    void addPeriodColumn(String table) {
        if (!tableExists(table)) return;
        jdbc.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS period_id integer");
        jdbc.execute("CREATE INDEX IF NOT EXISTS " + table + "_period_idx ON " + table + "(period_id)");
    }

    private void migrateExistingData(int periodId) {
        for (String t : PERIOD_TABLES) {
            if (!tableExists(t)) continue;
            int updated;
            if (t.equals("road_assets")) {
                updated = jdbc.update(
                        "UPDATE road_assets SET period_id = ? WHERE period_id IS NULL AND asset_type IN " +
                        "('fwd','subgrade','bituminous_core','pavement_crust')", periodId);
            } else {
                updated = jdbc.update("UPDATE " + t + " SET period_id = ? WHERE period_id IS NULL", periodId);
            }
            if (updated > 0) log.info("Survey periods: tagged {} existing rows in {} as period {}", updated, t, periodId);
        }
    }

    /** Replaces a legacy single-column primary key (e.g. traffic_stations.name,
     *  road_video.section_label) with a surrogate id PK, so the natural key can
     *  repeat across survey periods. Idempotent: the legacy PK is only dropped
     *  when it is actually on the legacy column, and the id PK is only added
     *  when the table has no PK. */
    void ensureSurrogatePk(String table, String legacyPkColumn) {
        Map<String, String> pk = jdbc.query(
                "SELECT tc.constraint_name, k.column_name " +
                "FROM information_schema.table_constraints tc " +
                "JOIN information_schema.key_column_usage k " +
                "  ON k.constraint_name = tc.constraint_name AND k.table_name = tc.table_name " +
                "WHERE tc.table_name = ? AND tc.constraint_type = 'PRIMARY KEY' LIMIT 1",
                rs -> rs.next() ? Map.of("name", rs.getString(1), "col", rs.getString(2)) : null,
                table);
        if (pk != null && legacyPkColumn.equals(pk.get("col"))) {
            jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT \"" + pk.get("name") + "\"");
            pk = null;
        }
        jdbc.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS id serial");
        if (pk == null) jdbc.execute("ALTER TABLE " + table + " ADD PRIMARY KEY (id)");
    }

    boolean tableExists(String table) {
        String reg = jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, "public." + table);
        return reg != null;
    }

    /** Id of the active period (what the main map shows). Cached. */
    public int activePeriodId() {
        Integer id = cachedActiveId;
        if (id == null) {
            id = jdbc.query("SELECT id FROM survey_periods WHERE is_active ORDER BY id LIMIT 1",
                    rs -> rs.next() ? rs.getInt(1) : null);
            if (id == null) {
                // No active period (shouldn't happen) — fall back to the newest one.
                id = jdbc.query("SELECT id FROM survey_periods ORDER BY id DESC LIMIT 1",
                        rs -> rs.next() ? rs.getInt(1) : null);
            }
            if (id == null) throw new IllegalStateException("No survey periods exist");
            cachedActiveId = id;
        }
        return id;
    }

    /** requested period id if given, otherwise the active period. */
    public int resolve(Integer requested) {
        return requested != null ? requested : activePeriodId();
    }

    public boolean exists(int id) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM survey_periods WHERE id = ?", Long.class, id);
        return n != null && n > 0;
    }

    public String nameOf(int id) {
        List<String> l = jdbc.queryForList("SELECT name FROM survey_periods WHERE id = ?", String.class, id);
        return l.isEmpty() ? null : l.get(0);
    }

    @Transactional
    public void activate(int id) {
        jdbc.update("UPDATE survey_periods SET is_active = (id = ?)", id);
        cachedActiveId = null;
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList(
                "SELECT id, name, to_char(start_date,'DD-Mon-YYYY') AS start_date, " +
                "to_char(end_date,'DD-Mon-YYYY') AS end_date, is_active, " +
                "to_char(created_at,'DD-Mon-YYYY HH24:MI') AS created_at " +
                "FROM survey_periods ORDER BY start_date DESC NULLS LAST, id DESC");
    }
}
