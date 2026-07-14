package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * Editable public-site content (About, Contact) for the KHRI / RMMS Cell
 * public portal. Stored as simple key/value rows in PostgreSQL so the text
 * can be edited from the Data Console and survives restarts.
 *
 * GET /api/site/content?key=about|contact  is public (read).
 * POST /api/site/content                   is authenticated (admin edit).
 */
@RestController
@RequestMapping("/api/site")
public class SiteController {

    private final JdbcTemplate jdbc;
    public SiteController(JdbcTemplate jdbc){ this.jdbc = jdbc; }

    @PostConstruct
    public void init(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS site_content (" +
                "key TEXT PRIMARY KEY, value TEXT, updated_at TIMESTAMPTZ DEFAULT now())");
        seed("about", DEFAULT_ABOUT);
        seed("contact", DEFAULT_CONTACT);
        seed("faq", DEFAULT_FAQ);
    }
    private void seed(String k, String v){
        Integer c = jdbc.queryForObject("SELECT count(*) FROM site_content WHERE key=?", Integer.class, k);
        if(c == null || c == 0) jdbc.update("INSERT INTO site_content(key,value) VALUES (?,?)", k, v);
    }

    @GetMapping("/content")
    public Map<String,Object> get(@RequestParam String key){
        List<Map<String,Object>> rows = jdbc.queryForList(
                "SELECT value, updated_at FROM site_content WHERE key=?", key);
        Map<String,Object> m = new HashMap<>();
        m.put("key", key);
        m.put("value", rows.isEmpty() ? "" : rows.get(0).get("value"));
        m.put("updated_at", rows.isEmpty() ? null : rows.get(0).get("updated_at"));
        return m;
    }

    @PostMapping("/content")
    public Map<String,Object> set(@RequestBody Map<String,String> body){
        Map<String,Object> r = new HashMap<>();
        String key = body.get("key");
        String value = body.getOrDefault("value", "");
        if(key == null || key.trim().isEmpty()){ r.put("ok", false); r.put("error", "key required"); return r; }
        jdbc.update("INSERT INTO site_content(key,value,updated_at) VALUES (?,?,now()) " +
                    "ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value, updated_at=now()",
                key.trim(), value);
        r.put("ok", true);
        return r;
    }

    private static final String DEFAULT_ABOUT =
        "The Kerala Highway Research Institute (KHRI) is the research and quality-assurance wing of the " +
        "Public Works Department, Government of Kerala. The RMMS Cell at KHRI operates the Road Maintenance " +
        "Management System (RMMS) — a GIS-based platform for surveying, assessing and managing the condition " +
        "of Kerala's road network.\n\n" +
        "The system maintains road inventory, pavement condition, geotechnical (FWD) data, traffic surveys and " +
        "network-level asset information to support data-driven maintenance planning and prioritisation for the " +
        "Department.\n\n" +
        "(This text can be edited from Site Control.)";

    private static final String DEFAULT_CONTACT =
        "Kerala Highway Research Institute (KHRI) — RMMS Cell\n" +
        "Public Works Department, Government of Kerala\n" +
        "Thiruvananthapuram, Kerala\n\n" +
        "Email:  rmms.khri@kerala.gov.in\n" +
        "Phone:  +91 \n\n" +
        "(This text can be edited from Site Control.)";

    private static final String DEFAULT_FAQ = """
[{"q":"What is KLRAMS?","a":"KLRAMS (Kerala Road Asset Management System) is a GIS-based platform of the Public Works Department, Government of Kerala, for surveying, assessing and managing the condition of the State's road network. It is operated by the RMMS Cell at the Kerala Highway Research Institute (KHRI)."},
{"q":"What does the system manage?","a":"Road inventory and classification, pavement condition (PCI), traffic surveys, Falling Weight Deflectometer (FWD) geotechnical data, and a repository of Government Orders on an interactive map."},
{"q":"What is the Pavement Condition Index (PCI)?","a":"PCI is a measure of pavement condition computed as per IRC:82-2023, used to prioritise maintenance across the network."},
{"q":"What is FWD?","a":"The Falling Weight Deflectometer measures pavement deflection (D0); these readings indicate the structural condition of the road."},
{"q":"Where can I find Government Orders?","a":"Open the GOs tab on the public portal to browse and search Government Orders and circulars by folder and name."}]
""";
}
