package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Data Console summary — one call that returns the imported-record counts for
 * every dataset/layer, so the console can show a complete "what's loaded" panel
 * (road network, condition, segments, video, FWD, bituminous core, sub-grade,
 * pavement crust, structures, traffic, boundaries). Each count is guarded so a
 * not-yet-created table simply reports 0 rather than failing the whole call.
 */
@RestController
@RequestMapping("/api/console")
public class ConsoleController {

    private final JdbcTemplate jdbc;

    public ConsoleController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private long cnt(String sql, Object... args) {
        try {
            Long n = jdbc.queryForObject(sql, Long.class, args);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    private double dbl(String sql, Object... args) {
        try {
            Double n = jdbc.queryForObject(sql, Double.class, args);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roads", cnt("SELECT count(*) FROM roads"));
        m.put("roads_km", Math.round(dbl("SELECT COALESCE(SUM(ST_Length(geom::geography)),0)/1000.0 FROM roads") * 10) / 10.0);
        m.put("full_network", cnt("SELECT count(*) FROM full_road_network"));
        m.put("condition", cnt("SELECT count(*) FROM condition"));
        m.put("segments", cnt("SELECT count(*) FROM condition_segments"));
        m.put("video", cnt("SELECT count(*) FROM road_video"));
        for (String t : new String[]{"fwd", "bituminous_core", "subgrade", "pavement_crust",
                "bridge", "culvert", "furniture_line", "furniture_point"}) {
            m.put(t, cnt("SELECT count(*) FROM road_assets WHERE asset_type = ?", t));
        }
        m.put("traffic_stations", cnt("SELECT count(*) FROM traffic_stations"));
        m.put("traffic_counts", cnt("SELECT count(*) FROM traffic_counts"));
        m.put("boundary", cnt("SELECT count(*) FROM boundary"));
        return m;
    }
}
