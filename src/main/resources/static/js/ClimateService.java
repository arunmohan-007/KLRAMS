package com.fist.rmms_backend.climate;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Climate module core (build 76).
 *  - seeds default editable config on first start
 *  - config CRUD (key/value JSON)
 *  - seeds the per-chainage grid from condition_segments
 *  - recalc(): re-derives idx_* -> cvi -> category from stored raw values
 *
 * NOTE: the heavy spatial extraction that fills raw_* lives in build 77 (PostGIS).
 * Nothing here touches a raster.
 */
@Service
public class ClimateService {

    private final ClimateConfigRepository configRepo;
    private final ClimateSegmentRepository segRepo;
    private final JdbcTemplate jdbc;
    private final ObjectMapper om = new ObjectMapper();

    public ClimateService(ClimateConfigRepository configRepo,
                          ClimateSegmentRepository segRepo,
                          JdbcTemplate jdbc) {
        this.configRepo = configRepo;
        this.segRepo = segRepo;
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------------
    // Defaults
    // ---------------------------------------------------------------------
    @PostConstruct
    public void seedDefaults() {
        putIfMissing("layers", DEFAULT_LAYERS);
        putIfMissing("class_rfi", DEFAULT_CLASS_RFI);
        putIfMissing("class_sli", DEFAULT_CLASS_SLI);
        putIfMissing("class_fsi", DEFAULT_CLASS_FSI);
        putIfMissing("cvi", DEFAULT_CVI);
        putIfMissing("cvi_bands", DEFAULT_CVI_BANDS);
    }

    private void putIfMissing(String key, String json) {
        if (!configRepo.existsById(key)) {
            configRepo.save(new ClimateConfig(key, json));
        }
    }

    // ---------------------------------------------------------------------
    // Config CRUD
    // ---------------------------------------------------------------------
    public Map<String, Object> getAllConfig() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ClimateConfig c : configRepo.findAll()) {
            out.put(c.getCkey(), readJson(c.getCvalue()));
        }
        return out;
    }

    public Object getConfig(String key) {
        return configRepo.findById(key)
                .map(c -> readJson(c.getCvalue()))
                .orElse(null);
    }

    /** Store a config key. Body must be valid JSON (validated by re-serialising). */
    public void putConfig(String key, String rawJson) {
        Object parsed = readJson(rawJson);          // throws if invalid
        String canonical = writeJson(parsed);
        ClimateConfig c = configRepo.findById(key).orElse(new ClimateConfig());
        c.setCkey(key);
        c.setCvalue(canonical);
        c.setUpdatedAt(LocalDateTime.now());
        configRepo.save(c);
    }

    // ---------------------------------------------------------------------
    // Grid seeding from condition_segments
    // ---------------------------------------------------------------------
    /**
     * Materialise one climate row per (section_label, start_chainage, end_chainage)
     * present in condition_segments. Existing rows are kept (ON CONFLICT DO NOTHING).
     * Returns rows inserted.
     *
     * If condition_segments uses different column names, adjust the SELECT below.
     */
    @Transactional
    public int seedGrid() {
        return jdbc.update(
            "INSERT INTO climate_segment_index (section_label, start_chainage, end_chainage) " +
            "SELECT DISTINCT section_label, start_chainage, end_chainage " +
            "FROM condition_segments " +
            "WHERE section_label IS NOT NULL " +
            "ON CONFLICT (section_label, start_chainage, end_chainage) DO NOTHING");
    }

    // ---------------------------------------------------------------------
    // Recalc — cheap, no raster
    // ---------------------------------------------------------------------
    @Transactional
    public int recalc() {
        List<Map<String, Object>> bRfi = bands("class_rfi");
        List<Map<String, Object>> bSli = bands("class_sli");
        List<Map<String, Object>> bFsi = bands("class_fsi");
        List<Map<String, Object>> bCvi = bands("cvi_bands");

        Map<String, Object> cvi = asMap(getConfig("cvi"));
        Map<String, Object> weights = asMap(cvi.get("weights"));
        Double wf = dbl(weights.get("fsi"));
        Double wr = dbl(weights.get("rfi"));
        Double ws = dbl(weights.get("sli"));

        List<ClimateSegmentIndex> all = segRepo.findAll();
        LocalDateTime now = LocalDateTime.now();

        for (ClimateSegmentIndex s : all) {
            Double idxRfi = classify(s.getRfiRaw(), s.getRfiClass(), bRfi);
            Double idxSli = classify(s.getSliRaw(), null, bSli);
            Double idxFsi = classify(s.getFsiRaw(), s.getFsiClass(), bFsi);
            s.setIdxRfi(idxRfi);
            s.setIdxSli(idxSli);
            s.setIdxFsi(idxFsi);

            Double cviVal = null;
            String cat = null;
            if (idxRfi != null && idxSli != null && idxFsi != null
                    && wf != null && wr != null && ws != null) {
                cviVal = wf * idxFsi + wr * idxRfi + ws * idxSli;
                cat = bandLabel(cviVal, bCvi);
            }
            s.setCvi(cviVal);
            s.setCviCategory(cat);
            s.setLastUpdated(now);
        }
        segRepo.saveAll(all);
        return all.size();
    }

    // ---------------------------------------------------------------------
    // Read helpers for viewer / dashboard
    // ---------------------------------------------------------------------
    public List<ClimateSegmentIndex> allSegments() {
        return segRepo.findAll();
    }

    /** Count of segments by CVI category (quick dashboard probe). */
    public Map<String, Long> categorySummary() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (ClimateSegmentIndex s : segRepo.findAll()) {
            String k = s.getCviCategory() == null ? "Unclassified" : s.getCviCategory();
            out.merge(k, 1L, Long::sum);
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Classification engine — handles BOTH range bands and category bands
    // ---------------------------------------------------------------------
    private Double classify(Double raw, String cls, List<Map<String, Object>> bands) {
        if (bands == null) return null;
        for (Map<String, Object> b : bands) {
            Object sc = b.get("sourceClass");
            if (sc != null && cls != null && sc.toString().equalsIgnoreCase(cls.trim())) {
                return dbl(b.get("indexValue"));
            }
            Double mn = dbl(b.get("rangeMin"));
            Double mx = dbl(b.get("rangeMax"));
            if ((mn != null || mx != null) && raw != null) {
                double lo = (mn == null) ? Double.NEGATIVE_INFINITY : mn;
                double hi = (mx == null) ? Double.POSITIVE_INFINITY : mx;
                if (raw >= lo && raw < hi) return dbl(b.get("indexValue"));
            }
        }
        return null;
    }

    private String bandLabel(Double val, List<Map<String, Object>> bands) {
        if (val == null || bands == null) return null;
        for (Map<String, Object> b : bands) {
            Double mn = dbl(b.get("rangeMin"));
            Double mx = dbl(b.get("rangeMax"));
            double lo = (mn == null) ? Double.NEGATIVE_INFINITY : mn;
            double hi = (mx == null) ? Double.POSITIVE_INFINITY : mx;
            if (val >= lo && val < hi) {
                Object lbl = b.get("label");
                return lbl == null ? null : lbl.toString();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // JSON / type helpers
    // ---------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> bands(String key) {
        Object o = getConfig(key);
        if (o instanceof List) return (List<Map<String, Object>>) o;
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        return new LinkedHashMap<>();
    }

    private Double dbl(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(o.toString().trim()); }
        catch (Exception e) { return null; }
    }

    private Object readJson(String s) {
        try { return om.readValue(s, Object.class); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid JSON: " + e.getMessage()); }
    }

    private String writeJson(Object o) {
        try { return om.writeValueAsString(o); }
        catch (Exception e) { throw new IllegalArgumentException("Serialise failed: " + e.getMessage()); }
    }

    // ---------------------------------------------------------------------
    // Default config JSON
    // ---------------------------------------------------------------------
    private static final String DEFAULT_LAYERS = "["
        + "{\"indexCode\":\"RFI\",\"layerName\":\"EHRF Base\",\"dataType\":\"raster\","
        + "\"sourceTable\":\"climate_ehrf\",\"valueField\":null,\"srid\":32643,"
        + "\"sourceInfo\":\"Extreme Heavy Rainfall Frequency\",\"lastUpdated\":null},"
        + "{\"indexCode\":\"SLI\",\"layerName\":\"Slope\",\"dataType\":\"raster\","
        + "\"sourceTable\":\"climate_slope\",\"valueField\":null,\"srid\":32643,"
        + "\"sourceInfo\":\"Terrain slope (%)\",\"lastUpdated\":null},"
        + "{\"indexCode\":\"FSI\",\"layerName\":\"Flood Susceptibility\",\"dataType\":\"vector\","
        + "\"sourceTable\":\"climate_flood\",\"valueField\":\"flood_cat\",\"srid\":32643,"
        + "\"sourceInfo\":\"Flood susceptibility polygons\",\"lastUpdated\":null}"
        + "]";

    // Rainfall: category-based by default (edit to ranges in the Console if continuous).
    private static final String DEFAULT_CLASS_RFI = "["
        + "{\"seq\":1,\"sourceClass\":\"Very Low\",\"rangeMin\":null,\"rangeMax\":null,\"label\":\"Very Low\",\"indexValue\":10},"
        + "{\"seq\":2,\"sourceClass\":\"Low\",\"rangeMin\":null,\"rangeMax\":null,\"label\":\"Low\",\"indexValue\":30},"
        + "{\"seq\":3,\"sourceClass\":\"Moderate\",\"rangeMin\":null,\"rangeMax\":null,\"label\":\"Moderate\",\"indexValue\":50},"
        + "{\"seq\":4,\"sourceClass\":\"High\",\"rangeMin\":null,\"rangeMax\":null,\"label\":\"High\",\"indexValue\":75},"
        + "{\"seq\":5,\"sourceClass\":\"Very High\",\"rangeMin\":null,\"rangeMax\":null,\"label\":\"Very High\",\"indexValue\":100}"
        + "]";

    // Slope: range-based (% grade). Last band open-ended (>35).
    private static final String DEFAULT_CLASS_SLI = "["
        + "{\"seq\":1,\"sourceClass\":null,\"rangeMin\":0,\"rangeMax\":3,\"label\":\"0-3%\",\"indexValue\":10},"
        + "{\"seq\":2,\"sourceClass\":null,\"rangeMin\":3,\"rangeMax\":10,\"label\":\"3-10%\",\"indexValue\":30},"
        + "{\"seq\":3,\"sourceClass\":null,\"rangeMin\":10,\"rangeMax\":20,\"label\":\"10-20%\",\"indexValue\":50},"
        + "{\"seq\":4,\"sourceClass\":null,\"rangeMin\":20,\"rangeMax\":35,\"label\":\"20-35%\",\"indexValue\":75},"
        + "{\"seq\":5,\"sourceClass\":null,\"rangeMin\":35,\"rangeMax\":null,\"label\":\">35%\",\"indexValue\":100}"
        + "]";

    // Flood: category-based.
    private static final String DEFAULT_CLASS_FSI = "["
        + "{\"seq\":1,\"sourceClass\":\"Very Low\",\"rangeMin\":null,\"rangeMax\":null,\"label\":\"Very Low\",\"indexValue\":10},"
        + "{\"seq\":2,\"sourceClass\":\"Low\",\"rangeMin\":null,\"rangeMax\":null,\"label\":\"Low\",\"indexValue\":30},"
        + "{\"seq\":3,\"sourceClass\":\"Moderate\",\"rangeMin\":null,\"rangeMax\":null,\"label\":\"Moderate\",\"indexValue\":50},"
        + "{\"seq\":4,\"sourceClass\":\"High\",\"rangeMin\":null,\"rangeMax\":null,\"label\":\"High\",\"indexValue\":75},"
        + "{\"seq\":5,\"sourceClass\":\"Very High\",\"rangeMin\":null,\"rangeMax\":null,\"label\":\"Very High\",\"indexValue\":100}"
        + "]";

    private static final String DEFAULT_CVI = "{"
        + "\"weights\":{\"fsi\":0.45,\"rfi\":0.35,\"sli\":0.20},"
        + "\"formula\":\"(FSI*0.45)+(RFI*0.35)+(SLI*0.20)\""
        + "}";

    // Half-open bands so integer CVI follows the spec (0-25 Low, 26-50 Moderate, ...).
    private static final String DEFAULT_CVI_BANDS = "["
        + "{\"seq\":1,\"rangeMin\":0,\"rangeMax\":26,\"label\":\"Low\"},"
        + "{\"seq\":2,\"rangeMin\":26,\"rangeMax\":51,\"label\":\"Moderate\"},"
        + "{\"seq\":3,\"rangeMin\":51,\"rangeMax\":76,\"label\":\"High\"},"
        + "{\"seq\":4,\"rangeMin\":76,\"rangeMax\":null,\"label\":\"Severe\"}"
        + "]";
}
