package com.fist.rmms_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes stray whitespace from text already stored in the database.
 *
 * <h2>Why</h2>
 * {@link ImportText} cleans values on the way IN, but that only helps the next
 * upload. Anything already stored keeps whatever it arrived with — and a value
 * that differs from another only by an invisible character is counted, filtered
 * and listed as a separate value everywhere at once. The real case: two road
 * sections whose owner was stored as {@code "PWD Maintenanace\n"} showed up as a
 * second owner in the dashboard breakdown AND as a second, identical-looking
 * entry in the map's filter value picker.
 *
 * Fixing it in the DATA fixes it in all of those at once. The alternative —
 * trimming in each query and each screen — would have to be remembered forever,
 * in every new report, and would hide the problem from the people who can
 * correct it at source.
 *
 * <h2>What it touches</h2>
 * Leading and trailing whitespace, non-breaking space and BOM: exactly the set
 * {@link ImportText} strips, so stored data and freshly imported data end up
 * identical. Nothing inside a value is altered and no spelling is changed — a
 * misspelled owner stays misspelled and stays visible, which is how it gets
 * noticed and corrected in the road data.
 *
 * <h2>When it runs</h2>
 * Every startup, not once: it is idempotent, it only UPDATEs rows that actually
 * carry the problem, and running it each boot means a restored dump or a load
 * done outside the app is healed too. It logs only when it changed something.
 */
@Service
public class DataHygieneService {

    private static final Logger log = LoggerFactory.getLogger(DataHygieneService.class);

    /** The tables whose text is matched, grouped or listed, so whitespace in them is visible to users. */
    private static final List<String> TABLES = List.of("roads", "traffic_stations", "condition", "road_assets");

    /* Same character set as ImportText.strippable(): POSIX whitespace (space, tab,
       newline, CR, form feed, vertical tab) plus NBSP and BOM, which routinely
       survive a spreadsheet export and which SQL's plain btrim() would leave. */
    private static final String EDGE_WS = "'^[[:space:]\\u00A0\\uFEFF]+|[[:space:]\\u00A0\\uFEFF]+$'";

    private final JdbcTemplate jdbc;

    public DataHygieneService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Called from {@link LayerRegistryService#ensure()} so it runs after the
     * upload services have created their tables.
     */
    public void ensure() {
        for (String table : TABLES) {
            try {
                int n = trimTable(table);
                if (n > 0) log.info("Data hygiene: trimmed stray whitespace from {} row(s) in {}", n, table);
            } catch (Exception e) {
                // A missing table (fresh install) or a permissions problem must
                // never stop the app booting — this is tidying, not a migration
                // anything else depends on.
                log.debug("Data hygiene skipped for {}", table, e);
            }
        }
    }

    /** One UPDATE over every text column of {@code table}; returns rows changed. */
    private int trimTable(String table) {
        List<String> cols = jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns " +
            "WHERE table_schema = 'public' AND table_name = ? " +
            "  AND data_type IN ('text','character varying')",
            String.class, table);

        List<String> sets = new ArrayList<>();
        List<String> where = new ArrayList<>();
        for (String c : cols) {
            // Column names come from information_schema, so they are real; the
            // quote check closes off the identifier interpolation regardless.
            if (c.indexOf('"') >= 0) continue;
            String q = '"' + c + '"';
            String trimmed = "regexp_replace(" + q + ", " + EDGE_WS + ", '', 'g')";
            sets.add(q + " = " + trimmed);
            where.add(q + " IS NOT NULL AND " + q + " <> " + trimmed);
        }
        if (sets.isEmpty()) return 0;

        /* One statement, one pass: the WHERE means a clean table is a single scan
           that updates nothing, rather than a rewrite of every row. */
        return jdbc.update("UPDATE " + table + " SET " + String.join(", ", sets) +
                           " WHERE (" + String.join(") OR (", where) + ")");
    }
}
