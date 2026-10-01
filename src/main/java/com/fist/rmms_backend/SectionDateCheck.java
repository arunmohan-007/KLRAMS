package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

/**
 * A section's Start Date lives on the road network, and every other layer that
 * carries a Section Start Date (condition, FWD, bridges, culverts, furniture…)
 * must agree with it. An upload whose dates disagree with the Road Network is
 * refused before anything is written, so the same section never carries two
 * different start dates across layers.
 */
@Component
class SectionDateCheck {

    static final String SYSTEM_NAME = "Section Start Date";
    private static final int MAX_LISTED = 8;

    private static final List<DateTimeFormatter> FORMATS = List.of(
            fmt("d-MMM-uuuu"), fmt("d-MMM-uu"), fmt("d/M/uuuu"), fmt("d-M-uuuu"), fmt("uuuu-M-d"), fmt("uuuu/M/d"));

    private static DateTimeFormatter fmt(String p) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p)
                .toFormatter(Locale.ENGLISH);
    }

    private final JdbcTemplate jdbc;
    private final RoadColumns roadColumns;

    SectionDateCheck(JdbcTemplate jdbc, RoadColumns roadColumns) {
        this.jdbc = jdbc;
        this.roadColumns = roadColumns;
    }

    /** A date in any of the formats a survey return or a shapefile export uses, or null. */
    static LocalDate parse(String raw) {
        if (raw == null) return null;
        // A shapefile export can leave escape backslashes in a date: 01\-Jan\-2025
        String s = raw.replace("\\", "").trim();
        if (s.isEmpty()) return null;
        int cut = s.indexOf(' ');
        int t = s.indexOf('T');
        if (t > 0 && (cut < 0 || t < cut)) cut = t;
        if (cut > 0) s = s.substring(0, cut);
        for (DateTimeFormatter f : FORMATS) {
            try { return LocalDate.parse(s, f); } catch (Exception ignored) { }
        }
        return null;
    }

    /**
     * @param datesBySection Section Label -> the distinct Section Start Date values the file carries
     * @param what           the layer being imported, for the message
     * @return a refusal message, or null when every date agrees with the Road Network
     */
    String check(Map<String, Set<String>> datesBySection, String what) {
        if (datesBySection.isEmpty()) return null;
        String secCol, dateCol;
        try {
            secCol = roadColumns.col("r", LayerAttributeCatalog.SECTION_LABEL);
            dateCol = roadColumns.col("r", SYSTEM_NAME);
        } catch (Exception e) {
            return null;   // the road network carries no start date to compare with
        }
        Map<String, Set<LocalDate>> road = new HashMap<>();
        List<String> labels = new ArrayList<>(datesBySection.keySet());
        for (int i = 0; i < labels.size(); i += 500) {
            List<String> chunk = labels.subList(i, Math.min(i + 500, labels.size()));
            String in = String.join(",", Collections.nCopies(chunk.size(), "?"));
            jdbc.query("SELECT " + secCol + " AS s, " + dateCol + "::text AS d FROM roads r WHERE "
                    + secCol + " IN (" + in + ")", rs -> {
                LocalDate d = parse(rs.getString("d"));
                if (d != null) road.computeIfAbsent(rs.getString("s"), k -> new HashSet<>()).add(d);
            }, chunk.toArray());
        }
        DateTimeFormatter out = DateTimeFormatter.ofPattern("dd-MMM-uuuu", Locale.ENGLISH);
        List<String> bad = new ArrayList<>();
        int total = 0;
        for (Map.Entry<String, Set<String>> e : new TreeMap<>(datesBySection).entrySet()) {
            Set<LocalDate> want = road.get(e.getKey());
            if (want == null) continue;   // unknown section, or a road with no start date yet
            for (String v : e.getValue()) {
                LocalDate d = parse(v);
                if (d != null && want.contains(d)) continue;
                total++;
                if (bad.size() < MAX_LISTED) {
                    StringBuilder rd = new StringBuilder();
                    for (LocalDate w : new TreeSet<>(want)) rd.append(rd.length() > 0 ? "/" : "").append(w.format(out));
                    bad.add(e.getKey() + " (file " + (d == null ? "“" + v + "” — not a valid date" : d.format(out))
                            + ", Road Network " + rd + ")");
                }
            }
        }
        if (total == 0) return null;
        return "Section Start Date in this " + what + " file does not match the Road Network for "
                + total + " section(s): " + String.join("; ", bad) + (total > bad.size() ? "; …" : "")
                + ". A section has one Start Date across every layer. Correct the file (or the Road Network "
                + "start date) and upload again. Nothing was imported.";
    }
}
