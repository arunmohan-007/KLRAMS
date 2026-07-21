package com.fist.rmms_backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Traffic Dashboard figures — per survey period, state-wide and district-wise,
 * built from the classified traffic-count stations ({@code traffic_stations})
 * and their count payloads ({@code traffic_counts.data} JSONB).
 *
 * The heavy lifting (ranking, vehicle-class mix, peak-hour profile) is done in
 * the browser (js/31-traffic-dashboard.js); this endpoint just returns, per
 * period, one compact record per station:
 *
 *   name, section (roads."Section_La"), road name / class / number, district,
 *   ADT (avg daily = total ÷ survey days), survey days, date range, the peak
 *   hour, the vehicle-class split (by_class) and the 24-hour profile (by_hour).
 *
 * A dual-carriageway station is stored as an A/B pair (TVM_STN_021A / …B) whose
 * count entries are the two carriageways of the same station — they are merged
 * back into one station here (base name = the number without the A/B suffix),
 * exactly as every other KLRAMS dashboard treats the pair as one station.
 *
 * District / road attributes are resolved by joining the station's section
 * label to roads."Section_La" (unique per section).
 */
@RestController
@RequestMapping("/api/traffic-dashboard")
public class TrafficDashboardController {

    private static final Logger log = LoggerFactory.getLogger(TrafficDashboardController.class);

    private final JdbcTemplate jdbc;
    private final SurveyPeriodService periods;
    private final ObjectMapper om = new ObjectMapper();

    public TrafficDashboardController(JdbcTemplate jdbc, SurveyPeriodService periods) {
        this.jdbc = jdbc;
        this.periods = periods;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        int activeId = periods.activePeriodId();

        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> defaultPeriod = null;

        for (Map<String, Object> p : periods.list()) {
            int pid = ((Number) p.get("id")).intValue();

            Map<String, Object> period = new LinkedHashMap<>();
            period.put("id", pid);
            period.put("name", p.get("name"));
            period.put("range", rangeLabel(p));
            period.put("is_active", Boolean.TRUE.equals(p.get("is_active")));
            period.put("stations", stationsForPeriod(pid));
            out.add(period);

            if (pid == activeId) defaultPeriod = Map.of("id", pid, "name", p.get("name"));
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("default_period", defaultPeriod);
        res.put("periods", out);
        return res;
    }

    /** One merged station record per physical count station in the period. */
    private List<Map<String, Object>> stationsForPeriod(int pid) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(
                "SELECT t.name AS name, t.section AS section, " +
                "       r.\"Road_Name\" AS road_name, r.\"Road_Class\" AS road_class, " +
                "       NULLIF(trim(r.\"Road_Num\"::text),'') AS road_num, " +
                "       COALESCE(NULLIF(trim(r.\"District\"),''),'(unmapped)') AS district, " +
                "       t.road AS stn_road, tc.data::text AS cdata " +
                "FROM traffic_counts tc " +
                "JOIN traffic_stations t ON t.name = tc.name AND t.period_id = tc.period_id " +
                "LEFT JOIN roads r ON r.\"Section_La\" = t.section " +
                "WHERE tc.period_id = ? ORDER BY t.name", pid);
        } catch (Exception e) {
            // Never let a missing/degraded traffic table break the dashboard hub.
            log.warn("Traffic dashboard query failed for period {} — returning empty", pid, e);
            return Collections.emptyList();
        }

        // Merge A/B dual-carriageway pairs onto one base station.
        Map<String, Station> byBase = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String name = (String) row.get("name");
            if (name == null || name.isBlank()) continue;
            String base = name.trim().replaceAll("([0-9])[ABab]$", "$1");
            Station st = byBase.computeIfAbsent(base, k -> new Station(base));
            st.merge(row);
        }

        List<Map<String, Object>> list = new ArrayList<>(byBase.size());
        for (Station st : byBase.values()) list.add(st.toMap());
        return list;
    }

    /** Accumulator for one (possibly A/B-merged) station. */
    private final class Station {
        final String name;
        String section, roadName, roadClass, roadNum, district, stnRoad;
        double total = 0;
        int days = 0;
        final double[] byHour = new double[24];
        final Map<String, Double> byClass = new LinkedHashMap<>();
        String dateMin, dateMax;

        Station(String name) { this.name = name; }

        void merge(Map<String, Object> row) {
            if (section == null)   section   = (String) row.get("section");
            if (roadName == null)  roadName  = (String) row.get("road_name");
            if (roadClass == null) roadClass = (String) row.get("road_class");
            if (roadNum == null)   roadNum   = (String) row.get("road_num");
            if (stnRoad == null)   stnRoad   = (String) row.get("stn_road");
            // Prefer a real district over the "(unmapped)" placeholder from either carriageway.
            String d = (String) row.get("district");
            if (district == null || "(unmapped)".equals(district)) district = d;

            JsonNode c = parse((String) row.get("cdata"));
            if (c == null) return;

            total += num(c.get("total"));
            days = Math.max(days, (int) Math.max(1, num(c.get("days"))));

            JsonNode bh = c.get("byHour");
            if (bh != null && bh.isArray())
                for (int i = 0; i < 24 && i < bh.size(); i++) byHour[i] += num(bh.get(i));

            JsonNode bc = c.get("byClass");
            if (bc != null && bc.isObject())
                bc.fieldNames().forEachRemaining(k -> byClass.merge(k, num(bc.get(k)), Double::sum));

            String dmin = txt(c.get("dateMin")), dmax = txt(c.get("dateMax"));
            if (dmin != null && (dateMin == null || dmin.compareTo(dateMin) < 0)) dateMin = dmin;
            if (dmax != null && (dateMax == null || dmax.compareTo(dateMax) > 0)) dateMax = dmax;
        }

        Map<String, Object> toMap() {
            int d = Math.max(1, days);
            long adt = Math.round(total / d);

            // Peak hour from the merged 24-hour profile, averaged per day.
            double peakV = 0; int peakH = -1;
            for (int i = 0; i < 24; i++) {
                double a = byHour[i] / d;
                if (a > peakV) { peakV = a; peakH = i; }
            }
            String peakT = peakH >= 0 ? (pad(peakH) + ":00–" + pad((peakH + 1) % 24) + ":00") : null;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("section", section);
            m.put("road", (roadName != null && !roadName.isBlank()) ? roadName
                        : (stnRoad != null && !stnRoad.isBlank() ? stnRoad : name));
            m.put("road_class", roadClass);
            m.put("road_num", roadNum);
            m.put("district", district != null ? district : "(unmapped)");
            m.put("adt", adt);
            m.put("total", Math.round(total));
            m.put("days", d);
            m.put("peak_v", Math.round(peakV));
            m.put("peak_t", peakT);
            m.put("date_min", dateMin);
            m.put("date_max", dateMax);
            m.put("by_class", byClass);
            m.put("by_hour", byHour);
            return m;
        }
    }

    private JsonNode parse(String json) {
        if (json == null) return null;
        try { return om.readTree(json); } catch (Exception e) { return null; }
    }

    private static double num(JsonNode n) {
        if (n == null || n.isNull()) return 0;
        if (n.isNumber()) return n.asDouble();
        if (n.isTextual()) {
            try { return Double.parseDouble(n.asText().replaceAll("[, ]", "")); }
            catch (Exception e) { return 0; }
        }
        return 0;
    }

    private static String txt(JsonNode n) {
        return (n == null || n.isNull()) ? null : n.asText();
    }

    private static String pad(int n) { return (n < 10 ? "0" : "") + n; }

    private static String rangeLabel(Map<String, Object> p) {
        Object s = p.get("start_date"), e = p.get("end_date");
        if (s == null && e == null) return "";
        return (s == null ? "…" : s) + " – " + (e == null ? "…" : e);
    }
}
