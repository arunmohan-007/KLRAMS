package com.fist.rmms_backend;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads live PostgreSQL / PostGIS column metadata for Layer Management (Phase 1 — discover only).
 *
 * <p>Table names come only from {@link LayerCatalog}. Column names used in dynamic SQL are
 * taken from {@code information_schema} and re-checked before interpolation (reject {@code "},
 * require {@link #SAFE_IDENT}). Never trust request input as an identifier.
 */
@Service
public class LayerSchemaService {

    /** Same shape as {@link RoadColumns} — letters, digits, underscore; must start with a letter. */
    private static final String SAFE_IDENT = "[a-zA-Z][a-zA-Z0-9_]*";

    private static final Set<String> PROTECTED_COLS = Set.of("id", "geom", "seg_id");

    private final JdbcTemplate jdbc;
    private final ManagedLayerService managed;

    public LayerSchemaService(JdbcTemplate jdbc, ManagedLayerService managed) {
        this.jdbc = jdbc;
        this.managed = managed;
    }

    public List<Map<String, Object>> listLayers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LayerCatalog.LayerDef d : LayerCatalog.managed()) {
            out.add(summarize(d, false));
        }
        return out;
    }

    public Map<String, Object> layerDetail(String key) {
        Map<String, Object> managedLayer = managed.getManagedLayer(key);
        if (managedLayer != null) {
            String kind = String.valueOf(managedLayer.get("folderKind"));
            if ("condition".equals(kind)) {
                Map<String, Object> m = summarize(LayerCatalog.CONDITION_SOURCE, true);
                m.put("key", key);
                m.put("label", managedLayer.get("label"));
                m.put("valueColumn", managedLayer.get("valueColumn"));
                m.put("folderKey", managedLayer.get("folderKey"));
                m.put("folderLabel", managedLayer.get("folderLabel"));
                m.put("folderKind", "condition");
                m.put("classification", ManagedLayerService.classificationFor("condition"));
                m.put("editable", true);
                m.put("canHide", false);
                return m;
            }
            if (("catalog".equals(kind) || "system".equals(kind)) && managedLayer.get("catalogKey") != null) {
                String ck = String.valueOf(managedLayer.get("catalogKey"));
                LayerCatalog.LayerDef d = LayerCatalog.get(ck)
                        .orElseThrow(() -> new IllegalArgumentException("unknown layer: " + key));
                Map<String, Object> m = summarize(d, true);
                m.put("key", key);
                m.put("label", managedLayer.get("label"));
                m.put("folderKey", managedLayer.get("folderKey"));
                m.put("folderLabel", managedLayer.get("folderLabel"));
                m.put("folderKind", kind);
                m.put("catalogKey", ck);
                m.put("classification", ManagedLayerService.classificationFor(kind));
                m.put("canHide", false);
                return m;
            }
            if ("boundary".equals(kind)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", key);
                m.put("label", managedLayer.get("label"));
                m.put("folderKey", managedLayer.get("folderKey"));
                m.put("folderLabel", managedLayer.get("folderLabel"));
                m.put("folderKind", "boundary");
                m.put("classification", ManagedLayerService.classificationFor("boundary"));
                m.put("table", "boundary");
                m.put("editable", false);
                m.put("canHide", true);
                m.put("hasData", managed.boundaryHasData(key));
                m.put("featureCount", managed.boundaryFeatureCount(key));
                m.put("featureType", managedLayer.get("featureType"));
                m.put("importFormat", managedLayer.get("importFormat"));
                m.put("srid", managedLayer.get("srid"));
                return m;
            }
        }
        LayerCatalog.LayerDef d = resolve(key);
        return summarize(d, true);
    }

    public Map<String, Object> attributes(String key) {
        Map<String, Object> managedLayer = managed.getManagedLayer(key);
        LayerCatalog.LayerDef d;
        String highlightCol = null;
        if (managedLayer != null && "condition".equals(managedLayer.get("folderKind"))) {
            d = LayerCatalog.CONDITION_SOURCE;
            highlightCol = managedLayer.get("valueColumn") == null ? null
                    : String.valueOf(managedLayer.get("valueColumn"));
        } else if (managedLayer != null
                && ("catalog".equals(managedLayer.get("folderKind")) || "system".equals(managedLayer.get("folderKind")))
                && managedLayer.get("catalogKey") != null) {
            d = LayerCatalog.get(String.valueOf(managedLayer.get("catalogKey")))
                    .orElseThrow(() -> new IllegalArgumentException("unknown layer: " + key));
        } else if (managedLayer != null && "boundary".equals(managedLayer.get("folderKind"))) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("layerKey", key);
            out.put("label", managedLayer.get("label"));
            out.put("table", "boundary");
            out.put("columns", List.of());
            out.put("jsonbKeys", List.of());
            out.put("message", "Boundary layers store GeoJSON text — use Import to replace or add data.");
            return out;
        } else {
            d = resolve(key);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("layerKey", key);
        out.put("label", managedLayer != null ? managedLayer.get("label") : d.label());
        out.put("table", d.table());
        out.put("assetType", d.assetType());
        out.put("editable", d.editable());
        out.put("valueColumn", highlightCol);
        out.put("readOnly", !d.editable() || d.rebuildTable());
        out.put("readOnlyReason", readOnlyReason(d));
        out.put("mutable", false);

        if (!tableExists(d.table())) {
            out.put("columns", List.of());
            out.put("jsonbKeys", List.of());
            out.put("error", "Table does not exist yet: " + d.table());
            return out;
        }

        List<Map<String, Object>> cols = loadColumns(d);
        if (highlightCol != null) {
            for (Map<String, Object> c : cols) {
                c.put("highlight", highlightCol.equals(c.get("name")));
            }
        }
        out.put("columns", cols);

        String jsonbCol = jsonbColumn(d.table());
        out.put("jsonbKeys", jsonbCol == null ? List.of() : discoverJsonbKeys(d, jsonbCol));
        return out;
    }

    /** Whether Import should warn that data already exists (replace vs add). */
    public Map<String, Object> importStatus(String key) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("layerKey", key);
        if (managed.isManagedBoundary(key) || key.startsWith("bnd-")) {
            String bkey = key.startsWith("bnd-") ? key.substring(4) : key;
            boolean has = managed.boundaryHasData(bkey);
            out.put("kind", "boundary");
            out.put("hasData", has);
            out.put("featureCount", managed.boundaryFeatureCount(bkey));
            out.put("message", has
                    ? "Boundary data is already uploaded for this layer. Replace the whole layer, or cancel."
                    : "No boundary data yet — ready for first upload.");
            return out;
        }
        if ("condition".equals(key) || (managed.getManagedLayer(key) != null
                && "condition".equals(managed.getManagedLayer(key).get("folderKind")))) {
            LayerCatalog.LayerDef d = LayerCatalog.CONDITION_SOURCE;
            long n = featureCount(d);
            out.put("kind", "condition");
            out.put("hasData", n > 0);
            out.put("featureCount", n);
            out.put("message", n > 0
                    ? "Condition CSV data already exists (" + n + " rows). Choose Replace or Add when importing."
                    : "No condition data yet — ready for first upload.");
            return out;
        }
        LayerCatalog.LayerDef d = resolve(key);
        long n = featureCount(d);
        out.put("kind", d.table());
        out.put("hasData", n > 0);
        out.put("featureCount", n);
        out.put("message", n > 0
                ? "Data already exists (" + n + " rows/features). Choose Replace or Add when importing."
                : "No data yet — ready for first upload.");
        return out;
    }

    private LayerCatalog.LayerDef resolve(String key) {
        return LayerCatalog.get(key)
                .orElseThrow(() -> new IllegalArgumentException("unknown layer: " + key));
    }

    private Map<String, Object> summarize(LayerCatalog.LayerDef d, boolean detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", d.key());
        m.put("label", d.label());
        m.put("group", d.group());
        m.put("table", d.table());
        m.put("assetType", d.assetType());
        m.put("rebuildTable", d.rebuildTable());
        m.put("editable", d.editable());
        m.put("readOnlyReason", readOnlyReason(d));
        m.put("tableExists", tableExists(d.table()));
        m.put("featureCount", featureCount(d));
        m.put("hasData", featureCount(d) > 0);
        if (detail) {
            m.put("notes", d.notes());
            m.put("geometryColumn", d.geometryColumn());
            m.put("srid", srid(d));
            m.put("geometryType", geometryType(d));
            m.put("featureType", geometryType(d));
            m.put("schema", "public");
        }
        return m;
    }

    private String readOnlyReason(LayerCatalog.LayerDef d) {
        if (d.autoGenerated() || d.rebuildTable()) {
            return "Auto-generated / rebuilt layer — not edited here.";
        }
        if (d.editable()) {
            return null; // Condition CSV & inventory layers are editable targets
        }
        return "Schema editing is not available for this layer.";
    }

    private boolean tableExists(String table) {
        if (!LayerCatalog.isKnownTable(table) || !table.matches(SAFE_IDENT)) return false;
        try {
            Boolean ok = jdbc.queryForObject(
                    "SELECT to_regclass('public.' || ?) IS NOT NULL", Boolean.class, table);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            return false;
        }
    }

    private long featureCount(LayerCatalog.LayerDef d) {
        if (!tableExists(d.table())) return 0;
        try {
            if (d.assetType() != null) {
                Long n = jdbc.queryForObject(
                        "SELECT count(*) FROM road_assets WHERE asset_type = ?",
                        Long.class, d.assetType());
                return n == null ? 0 : n;
            }
            // table name is catalogue-controlled and SAFE_IDENT-checked in tableExists
            Long n = jdbc.queryForObject("SELECT count(*) FROM " + d.table(), Long.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    private Integer srid(LayerCatalog.LayerDef d) {
        if (d.geometryColumn() == null || !tableExists(d.table())) return null;
        String geom = d.geometryColumn();
        if (!geom.matches(SAFE_IDENT)) return null;
        try {
            Integer s = jdbc.queryForObject(
                    "SELECT Find_SRID('public', ?, ?)",
                    Integer.class, d.table(), geom);
            if (s != null && s != 0) return s;
            Integer sample = jdbc.queryForObject(
                    "SELECT ST_SRID(\"" + geom + "\") FROM " + d.table() +
                            " WHERE \"" + geom + "\" IS NOT NULL LIMIT 1",
                    Integer.class);
            return (sample != null && sample != 0) ? sample : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String geometryType(LayerCatalog.LayerDef d) {
        if (d.geometryColumn() == null) {
            if ("traffic_stations".equals(d.table())) return "Lat/Lng (no PostGIS geom)";
            if ("boundary".equals(d.table())) return "GeoJSON text";
            return null;
        }
        if (!tableExists(d.table())) return null;
        String geom = d.geometryColumn();
        if (!geom.matches(SAFE_IDENT)) return null;
        try {
            String sql = "SELECT ST_GeometryType(\"" + geom + "\") FROM " + d.table();
            if (d.assetType() != null) {
                sql += " WHERE asset_type = ? AND \"" + geom + "\" IS NOT NULL LIMIT 1";
                return friendlyGeom(jdbc.queryForObject(sql, String.class, d.assetType()));
            }
            sql += " WHERE \"" + geom + "\" IS NOT NULL LIMIT 1";
            return friendlyGeom(jdbc.queryForObject(sql, String.class));
        } catch (Exception e) {
            return null;
        }
    }

    private static String friendlyGeom(String stType) {
        if (stType == null || stType.isBlank()) return null;
        String s = stType.startsWith("ST_") ? stType.substring(3) : stType;
        // ST_MultiLineString → MultiLineString (already mixed); GeometryType upper → title
        if (s.equals(s.toUpperCase(Locale.ROOT))) {
            String lower = s.toLowerCase(Locale.ROOT);
            StringBuilder sb = new StringBuilder();
            boolean up = true;
            for (int i = 0; i < lower.length(); i++) {
                char c = lower.charAt(i);
                if (c == '_') { up = true; continue; }
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
            return sb.toString();
        }
        return s;
    }

    private List<Map<String, Object>> loadColumns(LayerCatalog.LayerDef d) {
        String table = d.table();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT column_name, data_type, udt_name,
                       numeric_precision, numeric_scale,
                       character_maximum_length, is_nullable, column_default,
                       ordinal_position
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = ?
                 ORDER BY ordinal_position
                """, table);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String name = String.valueOf(r.get("column_name"));
            if (!isSafeColumn(name)) continue;

            String dataType = str(r.get("data_type"));
            String udt = str(r.get("udt_name"));
            String display = displayType(dataType, udt);

            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", name);
            col.put("pgType", dataType);
            col.put("udtName", udt);
            col.put("displayType", display);
            col.put("precision", applicablePrecision(display, r.get("numeric_precision")));
            col.put("scale", applicableScale(display, r.get("numeric_scale")));
            col.put("stringLength", stringLength(display, dataType, r.get("character_maximum_length")));
            col.put("dateFormat", dateFormat(display));
            col.put("sampleValue", sampleValue(d, name, display));
            col.put("nullable", "YES".equalsIgnoreCase(str(r.get("is_nullable"))));
            Object def = r.get("column_default");
            col.put("defaultValue", def == null ? null : String.valueOf(def));
            col.put("protected", PROTECTED_COLS.contains(name.toLowerCase(Locale.ROOT))
                    || "geometry".equalsIgnoreCase(display));
            out.add(col);
        }
        return out;
    }

    private Object sampleValue(LayerCatalog.LayerDef d, String col, String displayType) {
        String table = d.table();
        try {
            if ("Geometry".equals(displayType)) {
                String sql = "SELECT ST_GeometryType(\"" + col + "\") FROM " + table +
                        " WHERE \"" + col + "\" IS NOT NULL";
                if (d.assetType() != null) {
                    sql += " AND asset_type = ? LIMIT 1";
                    return friendlyGeom(jdbc.queryForObject(sql, String.class, d.assetType()));
                }
                if ("boundary".equals(table) && ("district".equals(d.key()) || "constituency".equals(d.key()))) {
                    // no geom column on boundary
                    return null;
                }
                sql += " LIMIT 1";
                String g = jdbc.queryForObject(sql, String.class);
                return g == null ? "No value" : friendlyGeom(g);
            }

            String sql = "SELECT \"" + col + "\" FROM " + table + " WHERE \"" + col + "\" IS NOT NULL";
            Object val;
            if (d.assetType() != null) {
                sql += " AND asset_type = ? LIMIT 1";
                val = jdbc.queryForObject(sql, Object.class, d.assetType());
            } else if ("boundary".equals(table)) {
                String type = "district".equals(d.key()) ? "district" : "constituency";
                sql += " AND type = ? LIMIT 1";
                val = jdbc.queryForObject(sql, Object.class, type);
            } else {
                sql += " LIMIT 1";
                val = jdbc.queryForObject(sql, Object.class);
            }
            if (val == null) return "No value";
            if ("JSON".equals(displayType)) {
                String s = String.valueOf(val);
                return s.length() > 120 ? s.substring(0, 117) + "…" : s;
            }
            return String.valueOf(val);
        } catch (Exception e) {
            return "No value";
        }
    }

    private List<String> discoverJsonbKeys(LayerCatalog.LayerDef d, String jsonbCol) {
        if (!isSafeColumn(jsonbCol)) return List.of();
        try {
            String sql;
            List<String> keys;
            if (d.assetType() != null) {
                sql = "SELECT DISTINCT k FROM road_assets, LATERAL jsonb_object_keys(\"" + jsonbCol + "\") AS k " +
                        "WHERE asset_type = ? AND \"" + jsonbCol + "\" IS NOT NULL ORDER BY 1 LIMIT 200";
                keys = jdbc.queryForList(sql, String.class, d.assetType());
            } else {
                sql = "SELECT DISTINCT k FROM " + d.table() +
                        ", LATERAL jsonb_object_keys(\"" + jsonbCol + "\") AS k " +
                        "WHERE \"" + jsonbCol + "\" IS NOT NULL ORDER BY 1 LIMIT 200";
                keys = jdbc.queryForList(sql, String.class);
            }
            return keys == null ? List.of() : keys;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String jsonbColumn(String table) {
        return switch (table) {
            case "road_assets" -> "attrs";
            case "full_road_network" -> "props";
            case "iri_2km_segments" -> "lane_avgs";
            case "condition_segments" -> "lane_vals";
            default -> null;
        };
    }

    private static boolean isSafeColumn(String name) {
        return name != null && name.indexOf('"') < 0 && name.matches(SAFE_IDENT);
    }

    private static String displayType(String dataType, String udt) {
        if (dataType == null) return "Text";
        String dt = dataType.toLowerCase(Locale.ROOT);
        String u = udt == null ? "" : udt.toLowerCase(Locale.ROOT);
        if ("geometry".equals(u) || "geography".equals(u)) return "Geometry";
        if (dt.contains("timestamp")) return "DateTime";
        return switch (dt) {
            case "smallint", "integer" -> "Integer";
            case "bigint" -> "Big Integer";
            case "numeric", "decimal", "real", "double precision" -> "Decimal";
            case "boolean" -> "Boolean";
            case "date" -> "Date";
            case "json", "jsonb" -> "JSON";
            case "user-defined" -> "geometry".equals(u) ? "Geometry" : udt;
            case "text", "character varying", "character", "varchar", "char" -> "Text";
            default -> dataType;
        };
    }

    private static Object applicablePrecision(String display, Object prec) {
        if (!"Decimal".equals(display)) return null;
        return prec;
    }

    private static Object applicableScale(String display, Object scale) {
        if (!"Decimal".equals(display)) return null;
        return scale;
    }

    private static Object stringLength(String display, String dataType, Object maxLen) {
        if (!"Text".equals(display)) return null;
        if (maxLen == null) {
            if (dataType != null && dataType.equalsIgnoreCase("text")) return "Unlimited";
            return "Unlimited";
        }
        return maxLen;
    }

    private static String dateFormat(String display) {
        if ("Date".equals(display)) return "YYYY-MM-DD";
        if ("DateTime".equals(display)) return "YYYY-MM-DD HH:mm:ss";
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
