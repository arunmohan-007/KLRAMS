package com.fist.rmms_backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * One-off, user-authorised: remove the integration-test entries from the section change log.
 *
 * <p>The suite applies real splits and merges and undoes them in a {@code finally}, so the DATA
 * is untouched — but an operator's History tab is left full of rows by users called {@code it-*}
 * and {@code integration-test*}. Only those go. {@code admin} (the real edits), {@code repair-402}
 * (the record of the hand repair) and {@code demo} stay.
 *
 * <p>Guard: a row is only deleted when it is not describing live data — it has been undone, or it
 * is itself an undo, or the sections it claims to have produced do not exist in the road network.
 * Anything else means a test left a real change standing and its record must survive.
 *
 * <pre>./mvnw -o test "-Dtest=TmpCleanLog" -DcleanLog=yes</pre>
 */
@SpringBootTest
class TmpCleanLog {

    private static final String TEST_USERS = "(username LIKE 'it-%' OR username LIKE 'integration-test%')";

    @Autowired JdbcTemplate jdbc;
    @Autowired RoadColumns roadColumns;

    @Test
    void clean() {
        String lbl = roadColumns.col(LayerAttributeCatalog.SECTION_LABEL);

        Long total = jdbc.queryForObject("SELECT count(*) FROM section_change_log", Long.class);
        Long doomed = jdbc.queryForObject(
                "SELECT count(*) FROM section_change_log WHERE " + TEST_USERS, Long.class);
        System.out.println("== change log: " + total + " rows, " + doomed + " written by test users ==");

        /* Only split and merge change which sections EXIST, so only those can leave the network
           in a state whose record must be kept. A chainage_correction adjusts a section in place
           and the suite's paired "-restore" entry puts it back, so its result label existing is
           normal and says nothing about whether it is still applied. */
        List<Map<String, Object>> standing = jdbc.queryForList(
                "SELECT id, operation, username, result_labels::text AS res FROM section_change_log "
              + "WHERE " + TEST_USERS + " AND operation IN ('split','merge') AND undone_at IS NULL "
              + "ORDER BY id");
        int live = 0;
        for (Map<String, Object> r : standing) {
            String res = String.valueOf(r.get("res"));
            Long exists = jdbc.queryForObject(
                    "SELECT count(*) FROM roads WHERE " + lbl + " = ANY (SELECT jsonb_array_elements_text(?::jsonb))",
                    Long.class, res);
            if (exists != null && exists > 0) {
                System.out.println("   LIVE — will NOT delete: " + r + " (" + exists + " section(s) exist)");
                live++;
            }
        }
        System.out.println("== still-applied test changes describing live sections: " + live + " ==");
        if (live > 0) {
            System.out.println("   refusing to delete anything — undo these first");
            return;
        }

        if (!"yes".equals(System.getProperty("cleanLog"))) {
            System.out.println("== DRY RUN — re-run with -DcleanLog=yes to delete ==");
            return;
        }

        int n = jdbc.update("DELETE FROM section_change_log WHERE " + TEST_USERS);
        System.out.println("== deleted " + n + " test row(s) ==");

        System.out.println("== what remains ==");
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT id, operation, username, source_labels::text AS src, result_labels::text AS res, "
              + "undone_at FROM section_change_log ORDER BY id"))
            System.out.println("   " + r);
    }
}
