package com.fist.rmms_backend;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Layer Management registry: every layer lives under a <b>Folder → Layer</b> name.
 *
 * <p>Hide is soft (does not delete data). Only <b>boundary</b> folders/layers may be hidden;
 * inventory and condition layers are mandatory — hide attempts return
 * {@code "Mandatory Layer - Can't Hide"}.
 */
@Service
public class ManagedLayerService {

    private static final Pattern SAFE_KEY = Pattern.compile("^[a-z][a-z0-9_]{1,62}$");
    private static final String MANDATORY_MSG = "Mandatory Layer - Can't Hide";

    static final Set<String> CONDITION_VALUE_COLUMNS = Set.of(
            "iri", "crack", "pothole", "rutting", "texture", "patch_work", "ravelling");

    private final JdbcTemplate jdbc;

    public ManagedLayerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS layer_folders (
                folder_key text PRIMARY KEY,
                label text NOT NULL,
                kind text NOT NULL DEFAULT 'boundary',
                sort_order integer NOT NULL DEFAULT 100,
                created_at timestamp NOT NULL DEFAULT now()
            )
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS admin_boundary_layers (
                layer_key text PRIMARY KEY,
                label text NOT NULL,
                folder_key text,
                sort_order integer NOT NULL DEFAULT 100,
                created_at timestamp NOT NULL DEFAULT now()
            )
            """);
        jdbc.execute("ALTER TABLE admin_boundary_layers ADD COLUMN IF NOT EXISTS folder_key text");
        jdbc.execute("ALTER TABLE admin_boundary_layers ADD COLUMN IF NOT EXISTS value_column text");
        jdbc.execute("ALTER TABLE admin_boundary_layers ADD COLUMN IF NOT EXISTS catalog_key text");
        jdbc.execute("ALTER TABLE admin_boundary_layers ADD COLUMN IF NOT EXISTS hidden boolean DEFAULT false");
        jdbc.execute("ALTER TABLE admin_boundary_layers ADD COLUMN IF NOT EXISTS feature_type text");
        jdbc.execute("ALTER TABLE admin_boundary_layers ADD COLUMN IF NOT EXISTS import_format text");
        jdbc.execute("ALTER TABLE admin_boundary_layers ADD COLUMN IF NOT EXISTS srid integer DEFAULT 4326");
        jdbc.execute("ALTER TABLE layer_folders ADD COLUMN IF NOT EXISTS hidden boolean DEFAULT false");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS layer_mgmt_settings (
                key text PRIMARY KEY,
                value text NOT NULL
            )
            """);

        // --- Folders (all existing groups) ---
        seedFolder("system_generated", "System Generated", "system", 5);
        seedFolder("network", "Road Network", "catalog", 10);
        seedFolder("condition_data", "Condition Data", "condition", 20);
        seedFolder("assets", "Structures & Furniture", "catalog", 30);
        seedFolder("geotech", "Pavement & Geotechnical", "catalog", 40);
        seedFolder("fwd", "FWD", "catalog", 50);
        seedFolder("traffic", "Traffic", "catalog", 60);
        seedFolder("video", "Survey Videos", "catalog", 65);
        seedFolder("administrative_boundary", "Administrative Boundary", "boundary", 70);

        // --- System Generated (auto-built roll-ups; not edited here) ---
        seedCatalogLayer("sys_pci_avg", "PCI (Composite)", "system_generated", "pci-avg", 10);
        seedCatalogLayer("sys_pci_worst", "PCI (Worst-Lane)", "system_generated", "pci-worst", 20);
        seedCatalogLayer("sys_iri2km", "Avg IRI (2 km)", "system_generated", "iri2km", 30);

        // --- Road Network ---
        seedCatalogLayer("roadnet", "Road network", "network", "roadnet", 10);
        seedCatalogLayer("roadnet2", "Full / merged road network", "network", "roadnet2", 20);

        // --- Condition (one column per layer) ---
        int ord = 10;
        for (Map<String, String> p : LayerCatalog.CONDITION_PARAMS) {
            seedConditionLayer("cond_" + p.get("key"), p.get("label"), p.get("key"), ord);
            ord += 10;
        }

        // --- Structures & Furniture ---
        seedCatalogLayer("bridge", "Bridge", "assets", "bridge", 10);
        seedCatalogLayer("culvert", "Culvert", "assets", "culvert", 20);
        seedCatalogLayer("furn-line", "Furniture (line)", "assets", "furn-line", 30);
        seedCatalogLayer("furn-pt", "Furniture (point)", "assets", "furn-pt", 40);

        // --- Geotech ---
        seedCatalogLayer("soil", "Sub-Grade Soil", "geotech", "soil", 10);
        seedCatalogLayer("core", "Bituminous Core", "geotech", "core", 20);
        seedCatalogLayer("crust", "Pavement Crust", "geotech", "crust", 30);

        // --- FWD ---
        seedCatalogLayer("fwd", "FWD", "fwd", "fwd", 10);

        // --- Traffic ---
        seedCatalogLayer("traffic", "Traffic stations", "traffic", "traffic", 10);

        // --- Survey Videos ---
        seedCatalogLayer("video-catalog", "Video Catalogue", "video", "video-catalog", 10);

        // --- Administrative Boundary ---
        seedBoundaryLayer("district", "District boundary", 10);
        seedBoundaryLayer("constituency", "Constituency boundary", 20);

        jdbc.update("""
            UPDATE admin_boundary_layers
               SET folder_key = 'administrative_boundary'
             WHERE (folder_key IS NULL OR folder_key = '')
               AND layer_key IN ('district','constituency')
            """);
    }

    private void seedFolder(String key, String label, String kind, int order) {
        jdbc.update("""
            INSERT INTO layer_folders (folder_key, label, kind, sort_order, hidden)
            VALUES (?, ?, ?, ?, false)
            ON CONFLICT (folder_key) DO NOTHING
            """, key, label, kind, order);
        jdbc.update("""
            UPDATE layer_folders SET label = ?, kind = ?, sort_order = ?
             WHERE folder_key = ?
            """, label, kind, order, key);
    }

    private void seedCatalogLayer(String key, String label, String folder, String catalogKey, int order) {
        jdbc.update("""
            INSERT INTO admin_boundary_layers
              (layer_key, label, folder_key, value_column, catalog_key, sort_order, hidden)
            VALUES (?, ?, ?, NULL, ?, ?, false)
            ON CONFLICT (layer_key) DO NOTHING
            """, key, label, folder, catalogKey, order);
        jdbc.update("""
            UPDATE admin_boundary_layers
               SET folder_key = ?, label = ?, catalog_key = COALESCE(catalog_key, ?),
                   sort_order = ?
             WHERE layer_key = ?
            """, folder, label, catalogKey, order, key);
    }

    private void seedConditionLayer(String key, String label, String valueColumn, int order) {
        jdbc.update("""
            INSERT INTO admin_boundary_layers
              (layer_key, label, folder_key, value_column, catalog_key, sort_order, hidden)
            VALUES (?, ?, 'condition_data', ?, NULL, ?, false)
            ON CONFLICT (layer_key) DO NOTHING
            """, key, label, valueColumn, order);
        jdbc.update("""
            UPDATE admin_boundary_layers
               SET folder_key = 'condition_data', label = ?,
                   value_column = COALESCE(value_column, ?), sort_order = ?
             WHERE layer_key = ?
            """, label, valueColumn, order, key);
    }

    private void seedBoundaryLayer(String key, String label, int order) {
        jdbc.update("""
            INSERT INTO admin_boundary_layers
              (layer_key, label, folder_key, value_column, catalog_key, sort_order, hidden)
            VALUES (?, ?, 'administrative_boundary', NULL, NULL, ?, false)
            ON CONFLICT (layer_key) DO NOTHING
            """, key, label, order);
        jdbc.update("""
            UPDATE admin_boundary_layers
               SET folder_key = 'administrative_boundary', label = ?, sort_order = ?
             WHERE layer_key = ?
            """, label, order, key);
    }

    /* ===================== Folders ===================== */

    public List<Map<String, Object>> listFolders() {
        return listFolders(false);
    }

    public List<Map<String, Object>> listFolders(boolean includeHidden) {
        init();
        List<Map<String, Object>> folders = new ArrayList<>();
        String sql = includeHidden
                ? "SELECT folder_key, label, kind, sort_order, COALESCE(hidden,false) AS hidden FROM layer_folders ORDER BY sort_order, label"
                : "SELECT folder_key, label, kind, sort_order, COALESCE(hidden,false) AS hidden FROM layer_folders WHERE COALESCE(hidden,false) = false ORDER BY sort_order, label";
        jdbc.query(sql, rs -> {
            String fkey = rs.getString("folder_key");
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("key", fkey);
            f.put("label", rs.getString("label"));
            f.put("kind", rs.getString("kind"));
            f.put("sortOrder", rs.getInt("sort_order"));
            f.put("hidden", rs.getBoolean("hidden"));
            f.put("canHide", "boundary".equals(rs.getString("kind")));
            f.put("layers", listLayersInFolder(fkey, includeHidden));
            folders.add(f);
        });
        return folders;
    }

    public String folderKind(String folderKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT kind FROM layer_folders WHERE folder_key = ?", String.class, folderKey);
        } catch (Exception e) {
            return null;
        }
    }

    public Map<String, Object> createFolder(String key, String label, String kind) {
        init();
        Map<String, Object> r = new LinkedHashMap<>();
        key = normalizeKey(key);
        if (key == null) {
            r.put("status", "error");
            r.put("message", "Folder key must be lowercase letters/digits/underscore, start with a letter.");
            return r;
        }
        if (label == null || label.isBlank()) label = prettyLabel(key);
        else label = label.trim();
        if (kind == null || kind.isBlank()) kind = "boundary";
        kind = kind.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("boundary", "condition", "catalog").contains(kind)) {
            r.put("status", "error");
            r.put("message", "Folder kind must be boundary, condition, or catalog.");
            return r;
        }
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM layer_folders WHERE folder_key = ?", Integer.class, key);
        if (exists != null && exists > 0) {
            r.put("status", "error");
            r.put("message", "A folder named '" + key + "' already exists.");
            return r;
        }
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sort_order), 0) FROM layer_folders", Integer.class);
        jdbc.update(
                "INSERT INTO layer_folders (folder_key, label, kind, sort_order, hidden) VALUES (?,?,?,?,false)",
                key, label, kind, (max == null ? 0 : max) + 10);
        r.put("status", "ok");
        r.put("key", key);
        r.put("label", label);
        r.put("kind", kind);
        return r;
    }

    /** Soft-hide a folder. Only boundary folders may be hidden. */
    public Map<String, Object> hideFolder(String folderKey, boolean hide) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (!folderExists(folderKey)) {
            r.put("status", "error");
            r.put("message", "Unknown folder.");
            return r;
        }
        if (!"boundary".equals(folderKind(folderKey))) {
            r.put("status", "error");
            r.put("message", MANDATORY_MSG);
            return r;
        }
        jdbc.update("UPDATE layer_folders SET hidden = ? WHERE folder_key = ?", hide, folderKey);
        // Soft-hide layers under it as well when hiding the folder
        if (hide) {
            jdbc.update("UPDATE admin_boundary_layers SET hidden = true WHERE folder_key = ?", folderKey);
        }
        r.put("status", "ok");
        r.put("hidden", hide);
        return r;
    }

    /** @deprecated use {@link #hideFolder} — delete is not allowed (affects software). */
    public Map<String, Object> deleteFolder(String folderKey) {
        return hideFolder(folderKey, true);
    }

    /* ===================== Layers ===================== */

    public List<Map<String, Object>> listLayersInFolder(String folderKey) {
        return listLayersInFolder(folderKey, false);
    }

    public List<Map<String, Object>> listLayersInFolder(String folderKey, boolean includeHidden) {
        String kind = folderKind(folderKey);
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = """
                SELECT layer_key, label, folder_key, value_column, catalog_key, sort_order,
                       COALESCE(hidden,false) AS hidden, feature_type, import_format,
                       COALESCE(srid, 4326) AS srid
                  FROM admin_boundary_layers
                 WHERE folder_key = ?
                """ + (includeHidden ? "" : " AND COALESCE(hidden,false) = false ") + """
                 ORDER BY sort_order, label
                """;
        jdbc.query(sql, rs -> {
            String key = rs.getString("layer_key");
            String col = rs.getString("value_column");
            String catalogKey = rs.getString("catalog_key");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("label", rs.getString("label"));
            row.put("folderKey", rs.getString("folder_key"));
            row.put("folderKind", kind);
            row.put("valueColumn", col);
            row.put("catalogKey", catalogKey);
            row.put("sortOrder", rs.getInt("sort_order"));
            row.put("hidden", rs.getBoolean("hidden"));
            row.put("canHide", "boundary".equals(kind));
            row.put("classification", classificationFor(kind));
            row.put("featureType", rs.getString("feature_type"));
            row.put("importFormat", rs.getString("import_format"));
            row.put("srid", rs.getInt("srid"));
            fillCounts(row, kind, key, catalogKey);
            out.add(row);
        }, folderKey);
        return out;
    }

    /** Human label for layer kind — Condition layers are parameter (nested) layers on one shared table. */
    static String classificationFor(String kind) {
        if ("condition".equals(kind)) return "Parameter";
        if ("boundary".equals(kind)) return "Boundary";
        if ("catalog".equals(kind)) return "Inventory";
        if ("system".equals(kind)) return "System Generated";
        return kind == null ? "—" : kind;
    }

    private void fillCounts(Map<String, Object> row, String kind, String key, String catalogKey) {
        if ("condition".equals(kind)) {
            long n = conditionRowCount();
            row.put("hasData", n > 0);
            row.put("featureCount", n);
        } else if ("boundary".equals(kind)) {
            row.put("hasData", boundaryHasData(key));
            row.put("featureCount", boundaryFeatureCount(key));
        } else if (catalogKey != null) {
            LayerCatalog.get(catalogKey).ifPresentOrElse(d -> {
                long n = catalogFeatureCount(d);
                row.put("hasData", n > 0);
                row.put("featureCount", n);
            }, () -> {
                row.put("hasData", false);
                row.put("featureCount", 0);
            });
        } else {
            row.put("hasData", false);
            row.put("featureCount", 0);
        }
    }

    private long catalogFeatureCount(LayerCatalog.LayerDef d) {
        try {
            if (d.assetType() != null) {
                Long n = jdbc.queryForObject(
                        "SELECT count(*) FROM road_assets WHERE asset_type = ?",
                        Long.class, d.assetType());
                return n == null ? 0 : n;
            }
            Long n = jdbc.queryForObject("SELECT count(*) FROM " + d.table(), Long.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    public Map<String, Object> getManagedLayer(String layerKey) {
        init();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT l.layer_key, l.label, l.folder_key, l.value_column, l.catalog_key,
                       l.sort_order, COALESCE(l.hidden,false) AS hidden,
                       l.feature_type, l.import_format, COALESCE(l.srid, 4326) AS srid,
                       f.kind AS folder_kind, f.label AS folder_label
                  FROM admin_boundary_layers l
                  JOIN layer_folders f ON f.folder_key = l.folder_key
                 WHERE l.layer_key = ?
                """, layerKey);
        if (rows.isEmpty()) return null;
        Map<String, Object> raw = rows.get(0);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", raw.get("layer_key"));
        row.put("label", raw.get("label"));
        row.put("folderKey", raw.get("folder_key"));
        row.put("folderLabel", raw.get("folder_label"));
        row.put("folderKind", raw.get("folder_kind"));
        row.put("valueColumn", raw.get("value_column"));
        row.put("catalogKey", raw.get("catalog_key"));
        row.put("hidden", Boolean.TRUE.equals(raw.get("hidden")));
        row.put("canHide", "boundary".equals(raw.get("folder_kind")));
        row.put("classification", classificationFor(String.valueOf(raw.get("folder_kind"))));
        row.put("featureType", raw.get("feature_type"));
        row.put("importFormat", raw.get("import_format"));
        row.put("srid", raw.get("srid"));
        return row;
    }

    public List<Map<String, Object>> listBoundaries() {
        init();
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.query("""
                SELECT l.layer_key, l.label, l.folder_key, l.sort_order,
                       l.feature_type, l.import_format, COALESCE(l.srid, 4326) AS srid,
                       f.label AS folder_label
                  FROM admin_boundary_layers l
                  JOIN layer_folders f ON f.folder_key = l.folder_key
                 WHERE f.kind = 'boundary' AND COALESCE(l.hidden,false) = false
                   AND COALESCE(f.hidden,false) = false
                 ORDER BY f.sort_order, l.sort_order, l.label
                """, rs -> {
            String key = rs.getString("layer_key");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("label", rs.getString("label"));
            row.put("folderKey", rs.getString("folder_key"));
            row.put("folderLabel", rs.getString("folder_label"));
            row.put("sortOrder", rs.getInt("sort_order"));
            row.put("featureType", rs.getString("feature_type"));
            row.put("importFormat", rs.getString("import_format"));
            row.put("srid", rs.getInt("srid"));
            row.put("hasData", boundaryHasData(key));
            row.put("featureCount", boundaryFeatureCount(key));
            out.add(row);
        });
        return out;
    }

    public Map<String, Object> createLayerInFolder(String key, String label, String folderKey,
                                                   String valueColumn) {
        return createLayerInFolder(key, label, folderKey, valueColumn, null, null);
    }

    public Map<String, Object> createLayerInFolder(String key, String label, String folderKey,
                                                   String valueColumn, String featureType, String importFormat) {
        init();
        Map<String, Object> r = new LinkedHashMap<>();
        if (!folderExists(folderKey)) {
            r.put("status", "error");
            r.put("message", "Unknown folder.");
            return r;
        }
        String kind = folderKind(folderKey);
        if ("catalog".equals(kind) || "system".equals(kind)) {
            r.put("status", "error");
            r.put("message", "Cannot add layers under a mandatory " +
                    ("system".equals(kind) ? "system-generated" : "inventory") + " folder.");
            return r;
        }
        key = normalizeKey(key);
        if (key == null) {
            r.put("status", "error");
            r.put("message", "Layer key must be lowercase letters/digits/underscore, start with a letter.");
            return r;
        }
        if (label == null || label.isBlank()) label = prettyLabel(key);
        else label = label.trim();

        if ("condition".equals(kind)) {
            if (valueColumn == null || valueColumn.isBlank()) {
                r.put("status", "error");
                r.put("message", "Select one condition column for this layer.");
                return r;
            }
            valueColumn = valueColumn.trim().toLowerCase(Locale.ROOT);
            if (!CONDITION_VALUE_COLUMNS.contains(valueColumn)) {
                r.put("status", "error");
                r.put("message", "Invalid column. Allowed: " + String.join(", ", CONDITION_VALUE_COLUMNS));
                return r;
            }
            Integer dup = jdbc.queryForObject("""
                    SELECT count(*) FROM admin_boundary_layers
                     WHERE folder_key = ? AND value_column = ?
                    """, Integer.class, folderKey, valueColumn);
            if (dup != null && dup > 0) {
                r.put("status", "error");
                r.put("message", "A layer for column '" + valueColumn + "' already exists in this folder.");
                return r;
            }
        } else {
            valueColumn = null;
        }

        String finalFeatureType = null;
        String finalImportFormat = null;
        if ("boundary".equals(kind)) {
            finalFeatureType = (featureType == null || featureType.isBlank())
                    ? "Mixed / Other" : normalizeChoice(featureType, FEATURE_TYPES, "Mixed / Other");
            finalImportFormat = (importFormat == null || importFormat.isBlank())
                    ? "Shapefile ZIP or GeoJSON" : normalizeChoice(importFormat, IMPORT_FORMATS, "Shapefile ZIP or GeoJSON");
        }

        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM admin_boundary_layers WHERE layer_key = ?",
                Integer.class, key);
        if (exists != null && exists > 0) {
            r.put("status", "error");
            r.put("message", "A layer named '" + key + "' already exists.");
            return r;
        }
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sort_order), 0) FROM admin_boundary_layers WHERE folder_key = ?",
                Integer.class, folderKey);
        jdbc.update("""
                INSERT INTO admin_boundary_layers
                  (layer_key, label, folder_key, value_column, catalog_key, sort_order, hidden,
                   feature_type, import_format, srid)
                VALUES (?,?,?,?,NULL,?,false,?,?,4326)
                """, key, label, folderKey, valueColumn, (max == null ? 0 : max) + 10,
                finalFeatureType, finalImportFormat);
        r.put("status", "ok");
        r.put("key", key);
        r.put("label", label);
        r.put("folderKey", folderKey);
        r.put("valueColumn", valueColumn);
        r.put("featureType", finalFeatureType);
        r.put("importFormat", finalImportFormat);
        r.put("srid", 4326);
        return r;
    }

    /** Feature types offered when creating a custom (boundary-kind) layer. */
    static final List<String> FEATURE_TYPES = List.of(
            "Point", "MultiPoint", "LineString", "MultiLineString",
            "Polygon", "MultiPolygon", "Mixed / Other");

    /** Import formats offered when creating a custom (boundary-kind) layer — matches what the upload endpoint actually accepts. */
    static final List<String> IMPORT_FORMATS = List.of(
            "Shapefile ZIP or GeoJSON", "Shapefile ZIP", "GeoJSON");

    private static String normalizeChoice(String value, List<String> allowed, String fallback) {
        String v = value.trim();
        for (String a : allowed) {
            if (a.equalsIgnoreCase(v)) return a;
        }
        return fallback;
    }

    /** Soft-hide a layer. Only Administrative Boundary (kind=boundary) layers may be hidden. */
    public Map<String, Object> hideLayer(String key, boolean hide) {
        Map<String, Object> r = new LinkedHashMap<>();
        Map<String, Object> layer = getManagedLayer(key);
        if (layer == null) {
            r.put("status", "error");
            r.put("message", "Unknown layer.");
            return r;
        }
        if (!"boundary".equals(layer.get("folderKind"))) {
            r.put("status", "error");
            r.put("message", MANDATORY_MSG);
            return r;
        }
        jdbc.update("UPDATE admin_boundary_layers SET hidden = ? WHERE layer_key = ?", hide, key);
        r.put("status", "ok");
        r.put("hidden", hide);
        return r;
    }

    /** @deprecated use {@link #hideLayer} — hard delete is not allowed. */
    public Map<String, Object> deleteManagedLayer(String key) {
        return hideLayer(key, true);
    }

    public Map<String, Object> deleteBoundary(String key) {
        return hideLayer(key, true);
    }

    public boolean isManagedBoundary(String key) {
        Map<String, Object> layer = getManagedLayer(key);
        return layer != null && "boundary".equals(layer.get("folderKind"))
                && !Boolean.TRUE.equals(layer.get("hidden"));
    }

    public boolean isManagedLayer(String key) {
        return getManagedLayer(key) != null;
    }

    private boolean folderExists(String folderKey) {
        if (normalizeKey(folderKey) == null) return false;
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM layer_folders WHERE folder_key = ?",
                Integer.class, folderKey);
        return n != null && n > 0;
    }

    public boolean boundaryHasData(String key) {
        try {
            String g = jdbc.queryForObject(
                    "SELECT geojson FROM boundary WHERE type = ?", String.class, key);
            return g != null && g.contains("\"features\"") && !g.contains("\"features\":[]");
        } catch (Exception e) {
            return false;
        }
    }

    public long boundaryFeatureCount(String key) {
        try {
            String g = jdbc.queryForObject(
                    "SELECT geojson FROM boundary WHERE type = ?", String.class, key);
            if (g == null) return 0;
            int n = 0, idx = 0;
            while ((idx = g.indexOf("\"type\":\"Feature\"", idx)) >= 0) {
                n++;
                idx += 16;
            }
            if (n == 0) {
                idx = 0;
                while ((idx = g.indexOf("\"type\": \"Feature\"", idx)) >= 0) {
                    n++;
                    idx += 17;
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    private long conditionRowCount() {
        try {
            Long n = jdbc.queryForObject("SELECT count(*) FROM condition", Long.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    public List<Map<String, String>> conditionColumnChoices() {
        return LayerCatalog.CONDITION_PARAMS;
    }

    private static String normalizeKey(String key) {
        if (key == null) return null;
        key = key.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("^_+|_+$", "");
        return SAFE_KEY.matcher(key).matches() ? key : null;
    }

    private static String prettyLabel(String key) {
        String label = key.replace('_', ' ');
        return Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }
}
