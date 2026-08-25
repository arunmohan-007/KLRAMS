package com.fist.rmms_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lookup &amp; Short Code: what the codes in the data stand for.
 *
 * A survey return stores {@code FLX}, {@code CLY}, {@code NB}. This holds the
 * expansion — Flexible, Clay, Northbound — so every card, popup and report can
 * print the value a reader understands while the stored data stays the code the
 * surveyor wrote. Nothing is rewritten in the data; expansion happens on the way
 * out.
 *
 * <h2>Sets, and the attributes bound to them</h2>
 * The tables ({@code lookup_set}, {@code lookup_value}) were created by
 * {@link LayerAttributeService} when attributes first gained a {@code LOOKUP}
 * type, and stood empty because nothing filled them. This fills them from
 * {@link LookupCatalog} and binds each set to the attributes that read it, by
 * setting those attributes' {@code lookup_key} and typing them {@code LOOKUP}.
 *
 * A set is shared, not copied per layer: Soil Type decodes the same on sub-grade
 * and on pavement crust, so correcting a value corrects both. The screen still
 * presents it per layer per attribute, which is how the RMMS cell looks for it.
 *
 * <h2>Seeding never overwrites an edit</h2>
 * Same rule as the attribute catalogue: a value is inserted if absent and left
 * alone if present, and a set the user has edited keeps their labels. So the
 * sheet's codes ship as a starting point and become theirs the moment they touch
 * them.
 */
@Service
public class LookupService {

    private static final Logger log = LoggerFactory.getLogger(LookupService.class);

    private final JdbcTemplate jdbc;

    public LookupService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Deliberately NOT {@code @PostConstruct} — the same reason
     * {@link LayerAttributeService#ensure()} is not. Binding writes to
     * {@code layer_attribute}, so the layers and their attributes must already
     * be seeded. {@link LayerRegistryService#ensure()} calls this last.
     */
    public void ensure() {
        try {
            ensureSchema();
            seed();
            bind();
        } catch (Exception e) {
            log.error("Lookup init failed — the Lookup & Short Code module may be degraded, "
                    + "but the app will keep starting", e);
        }
    }

    private void ensureSchema() {
        // The two tables already exist (LayerAttributeService creates them so an
        // attribute can reference a set before this module was built). Only the
        // columns this module adds are ensured here.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS lookup_set (
                id         serial PRIMARY KEY,
                set_key    text UNIQUE NOT NULL,
                name       text NOT NULL,
                created_at timestamp NOT NULL DEFAULT now()
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS lookup_value (
                id         serial PRIMARY KEY,
                set_id     integer NOT NULL REFERENCES lookup_set(id) ON DELETE CASCADE,
                code       text NOT NULL,
                label      text NOT NULL,
                sort_order integer NOT NULL DEFAULT 100
            )""");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS lookup_value_ux ON lookup_value(set_id, code)");

        /* The label the seed last wrote, so a shipped correction can reach an
           existing database without taking back an edit — exactly the guard
           layer_attribute.seeded_name provides for attribute labels. */
        jdbc.execute("ALTER TABLE lookup_value ADD COLUMN IF NOT EXISTS seeded_label text");
        jdbc.execute("ALTER TABLE lookup_set ADD COLUMN IF NOT EXISTS builtin boolean NOT NULL DEFAULT false");

        /* Retiring a value rather than deleting it. A code that has been used is
           still sitting in stored rows, so removing it outright would turn every
           one of them back into a raw code with nothing to explain why. Inactive
           means: no longer offered or accepted on import, but still decoded for
           the rows that already carry it. */
        jdbc.execute("ALTER TABLE lookup_value ADD COLUMN IF NOT EXISTS active boolean NOT NULL DEFAULT true");

        /* Provision for conditional lookups — "Sub Base Type is limited by Base
           Type". Stored and edited now so the column list is settled; NOTHING
           reads it yet, and the filtering rule is deliberately undecided. It is
           a note against the value until that rule exists. */
        jdbc.execute("ALTER TABLE lookup_value ADD COLUMN IF NOT EXISTS depends_on text");

        /* "The RMMS cell turned this one off deliberately."
           Needed because off cannot otherwise be told apart from not-yet-on:
           both look like data_type <> 'LOOKUP', and the seed would helpfully
           switch it back on at the next restart. */
        jdbc.execute("ALTER TABLE layer_attribute ADD COLUMN IF NOT EXISTS lookup_off "
                + "boolean NOT NULL DEFAULT false");
    }

    /* ------------------------------------------------------------------
       Seeding
       ------------------------------------------------------------------ */

    private void seed() {
        for (LookupCatalog.Set s : LookupCatalog.sets()) {
            Integer id = setId(s.key());
            if (id == null) {
                id = jdbc.queryForObject(
                        "INSERT INTO lookup_set (set_key, name, builtin) VALUES (?,?,true) RETURNING id",
                        Integer.class, s.key(), s.name());
            } else {
                jdbc.update("UPDATE lookup_set SET builtin = true WHERE id = ?", id);
            }
            int sort = 10;
            for (LookupCatalog.Value v : s.values()) {
                jdbc.update("""
                    INSERT INTO lookup_value (set_id, code, label, sort_order, seeded_label)
                    VALUES (?,?,?,?,?)
                    ON CONFLICT (set_id, code) DO UPDATE
                       SET label = CASE WHEN lookup_value.seeded_label IS NULL
                                          OR lookup_value.seeded_label = lookup_value.label
                                        THEN EXCLUDED.label ELSE lookup_value.label END,
                           seeded_label = EXCLUDED.seeded_label,
                           sort_order = EXCLUDED.sort_order
                    """, id, v.code(), v.label(), sort, v.label());
                sort += 10;
            }
            retireDropped(id, s);
        }
    }

    /**
     * A value the sheet no longer lists is DEACTIVATED, not deleted.
     *
     * The R2 revision dropped Eastbound and Westbound from Direction. Deleting
     * them would leave any row already holding "EB" showing a raw code with
     * nothing on screen to say why. Inactive says it exactly: no longer offered,
     * no longer accepted on import, still decoded for the rows that have it —
     * and shown as a NO on the Lookup screen, so someone can delete it properly
     * once they know nothing uses it.
     *
     * Only touches values the RMMS cell has not edited. One they added or
     * renamed is theirs and is never withdrawn by a re-seed.
     */
    private void retireDropped(int setId, LookupCatalog.Set declared) {
        List<Object> args = new ArrayList<>();
        args.add(setId);
        for (LookupCatalog.Value v : declared.values()) args.add(v.code());
        if (args.size() == 1) return;
        String holes = String.join(",", java.util.Collections.nCopies(args.size() - 1, "?"));
        int n = jdbc.update(
                "UPDATE lookup_value SET active = false "
              + " WHERE set_id = ? AND active"
              + "   AND seeded_label IS NOT NULL AND seeded_label = label"
              + "   AND code NOT IN (" + holes + ")", args.toArray());
        if (n > 0) {
            log.info("Deactivated {} value(s) of \"{}\" — no longer in the lookup sheet",
                    n, declared.key());
        }
    }

    /**
     * Point each attribute at the set that decodes it, and type it LOOKUP.
     *
     * Skips anything the RMMS cell has turned off ({@code lookup_off}) — that
     * is the one state this must not undo.
     *
     * Otherwise it asserts BOTH halves every boot rather than only binding
     * unbound attributes. It has to: the attribute catalogue declares these
     * columns STRING, and its re-seed used to reset the type on an attribute
     * that was already bound, so bind() skipped it and every lookup in the
     * system went quietly dead on the first restart. The catalogue now leaves a
     * LOOKUP alone, and this repairs any left broken by that.
     */
    private void bind() {
        LookupCatalog.bindings().forEach((layerKey, byAttr) ->
            byAttr.forEach((attrName, setKey) -> {
                try {
                    int n = jdbc.update("""
                        UPDATE layer_attribute a
                           SET lookup_key = ?, data_type = 'LOOKUP'
                          FROM layer_definition d
                         WHERE d.id = a.layer_id
                           AND d.layer_key = ?
                           AND a.name = ?
                           AND a.role = 'NONE'
                           AND NOT a.lookup_off
                           AND (a.lookup_key IS DISTINCT FROM ? OR a.data_type <> 'LOOKUP')
                        """, setKey, layerKey, attrName, setKey);
                    if (n > 0) log.info("Bound {}.{} to lookup set \"{}\"", layerKey, attrName, setKey);
                } catch (Exception e) {
                    log.warn("Could not bind {}.{} to \"{}\": {}",
                            layerKey, attrName, setKey, e.toString());
                }
            }));
    }

    /* ------------------------------------------------------------------
       Reads
       ------------------------------------------------------------------ */

    /** Every set with its values and the attributes bound to it — the module's screen. */
    public List<Map<String, Object>> sets() {
        List<Map<String, Object>> sets = jdbc.query("""
            SELECT s.id, s.set_key, s.name, s.builtin,
                   (SELECT count(*) FROM lookup_value v WHERE v.set_id = s.id) AS n
              FROM lookup_set s ORDER BY s.name
            """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getInt("id"));
            m.put("key", rs.getString("set_key"));
            m.put("name", rs.getString("name"));
            m.put("builtin", rs.getBoolean("builtin"));
            m.put("count", rs.getLong("n"));
            return m;
        });
        for (Map<String, Object> s : sets) {
            s.put("values", valuesOf((Integer) s.get("id")));
            s.put("usedBy", usedBy(String.valueOf(s.get("key"))));
        }
        return sets;
    }

    private List<Map<String, Object>> valuesOf(int setId) {
        return jdbc.query("""
            SELECT id, code, label, active, depends_on FROM lookup_value
             WHERE set_id = ? ORDER BY sort_order, id
            """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getInt("id"));
            m.put("code", rs.getString("code"));
            m.put("label", rs.getString("label"));
            m.put("active", rs.getBoolean("active"));
            m.put("dependsOn", rs.getString("depends_on"));
            return m;
        }, setId);
    }

    /** Which layer/attribute pairs read this set. */
    private List<Map<String, Object>> usedBy(String setKey) {
        return jdbc.query("""
            SELECT d.layer_key, d.name AS layer_name, a.name AS attr_name, a.dataset_key
              FROM layer_attribute a
              JOIN layer_definition d ON d.id = a.layer_id
             WHERE a.lookup_key = ?
             ORDER BY d.name, a.sort_order
            """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("layerKey", rs.getString("layer_key"));
            m.put("layer", rs.getString("layer_name"));
            m.put("attribute", rs.getString("attr_name"));
            m.put("dataset", rs.getString("dataset_key"));
            return m;
        }, setKey);
    }

    /**
     * The whole decode table in one document: set key -> code -> label.
     *
     * Fetched once by the viewer alongside the attribute catalogue, so a card
     * can expand a code without a request per feature. Codes are keyed BOTH as
     * written and normalised — a return that spells it "flx" or "F.L.X." is the
     * same code, and the alternative is every caller normalising for itself.
     */
    public Map<String, Object> decodeTable() {
        Map<String, Object> out = new LinkedHashMap<>();
        jdbc.query("""
            SELECT s.set_key, v.code, v.label
              FROM lookup_value v JOIN lookup_set s ON s.id = v.set_id
             ORDER BY s.set_key, v.sort_order, v.id
            """, rs -> {
            @SuppressWarnings("unchecked")
            Map<String, String> set = (Map<String, String>) out.computeIfAbsent(
                    rs.getString("set_key"), k -> new LinkedHashMap<String, String>());
            String code = rs.getString("code"), label = rs.getString("label");
            /* Both directions map to the one display value: the short code and
               the value itself. The label is included because a return that
               already holds the full word must display identically to one
               holding the code — the same fact recorded two ways, and a card
               should not show them differently.

               Each is keyed raw AND normalised, so case, spaces and punctuation
               never have to agree ("flx", "F.L.X.", "flexible"). Nothing wider
               than that is accepted: a spelling that is not the code or the
               value would expand on the card while the dashboards, which group
               on the stored string, still counted it separately. */
            set.putIfAbsent(code, label);
            set.putIfAbsent(norm(code), label);
            set.putIfAbsent(label, label);
            set.putIfAbsent(norm(label), label);
        });
        return out;
    }

    /**
     * One attribute's whole lookup state — what the screen loads after a layer
     * and an attribute have been picked.
     *
     * Everything needed in one response: the attribute's data type (the module
     * does nothing until it is LOOKUP), the set bound to it if any, that set's
     * values, and the other attributes reading the same set. That last one
     * matters: the sets are shared, so editing Soil Type here changes it on the
     * pavement-crust layer too, and the person editing should be told before
     * they type rather than after.
     */
    public Map<String, Object> forAttribute(int attributeId) {
        Map<String, Object> a = jdbc.queryForMap("""
            SELECT a.id, a.name, a.dataset_key, a.data_type, a.lookup_key, a.role,
                   d.layer_key, d.name AS layer_name
              FROM layer_attribute a
              JOIN layer_definition d ON d.id = a.layer_id
             WHERE a.id = ?
            """, attributeId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attributeId", a.get("id"));
        out.put("attribute", a.get("name"));
        out.put("dataset", a.get("dataset_key"));
        out.put("dataType", a.get("data_type"));
        out.put("layer", a.get("layer_name"));
        out.put("layerKey", a.get("layer_key"));
        // A placement column can never be a coded value, so the screen greys out
        // rather than offering an action that would be refused.
        out.put("placement", !"NONE".equals(String.valueOf(a.get("role"))));
        out.put("enabled", "LOOKUP".equals(String.valueOf(a.get("data_type"))));

        Object key = a.get("lookup_key");
        if (key == null) {
            out.put("set", null);
            out.put("values", List.of());
            out.put("sharedWith", List.of());
            return out;
        }
        Map<String, Object> set = jdbc.queryForMap(
                "SELECT id, set_key, name, builtin FROM lookup_set WHERE set_key = ?", key);
        Map<String, Object> sm = new LinkedHashMap<>();
        sm.put("id", set.get("id"));
        sm.put("key", set.get("set_key"));
        sm.put("name", set.get("name"));
        sm.put("builtin", set.get("builtin"));
        out.put("set", sm);
        out.put("values", valuesOf((Integer) set.get("id")));
        out.put("sharedWith", usedBy(String.valueOf(key)).stream()
                .filter(u -> !String.valueOf(u.get("attribute")).equals(String.valueOf(a.get("name")))
                          || !String.valueOf(u.get("layerKey")).equals(String.valueOf(a.get("layer_key"))))
                .toList());
        return out;
    }

    /**
     * Turn the lookup on for an attribute, creating its code list if needed.
     *
     * One action because it is one intention. Switching the data type to LOOKUP
     * without a list to read leaves an attribute that permits nothing, and
     * making a list without pointing the attribute at it leaves codes nobody
     * uses — so the screen offers "turn this on" and both happen together.
     *
     * An existing set is REUSED when the caller names one. That is what keeps
     * Soil Type a single list across sub-grade and pavement crust rather than
     * two that drift apart.
     */
    @Transactional
    public Map<String, Object> enable(int attributeId, String existingSetKey) {
        Map<String, Object> a = jdbc.queryForMap("""
            SELECT a.name, a.role, a.lookup_key, d.layer_key FROM layer_attribute a
              JOIN layer_definition d ON d.id = a.layer_id WHERE a.id = ?
            """, attributeId);
        if (!"NONE".equals(String.valueOf(a.get("role")))) {
            throw new IllegalArgumentException(
                    "\"" + a.get("name") + "\" places the feature on the map, so it cannot "
                    + "be a coded value.");
        }

        String key;
        String held = (String) a.get("lookup_key");
        if (existingSetKey != null && !existingSetKey.isBlank()) {
            if (setId(existingSetKey) == null) throw new IllegalArgumentException("No such code list");
            key = existingSetKey;
        } else if (held != null && setId(held) != null) {
            /* Turning it back ON reuses the list it already had. disable() keeps
               the link precisely so this is possible — without this branch, off
               then on would swap a fully populated list for a brand-new empty
               one, which reads as "my values disappeared" and, worse, silently
               lifts the import restriction the list was enforcing. */
            key = held;
        } else {
            String name = String.valueOf(a.get("name"));
            key = slug(name);
            if (key.isEmpty()) key = "lookup_" + attributeId;
            // A new list for an attribute whose name collides with an existing
            // list gets its own, rather than silently joining that one.
            if (setId(key) != null) key = key + "_" + attributeId;
            jdbc.update("INSERT INTO lookup_set (set_key, name, builtin) VALUES (?,?,false)",
                    key, name);
        }
        jdbc.update("UPDATE layer_attribute SET lookup_key = ?, data_type = 'LOOKUP', lookup_off = false WHERE id = ?",
                key, attributeId);
        return Map.of("ok", true, "setKey", key);
    }

    /**
     * Turn the lookup off: the attribute goes back to free text.
     *
     * The code list is left in place, not deleted — another attribute may read
     * it, and even if none does, the values are the RMMS cell's work and are
     * theirs to point at something else later.
     */
    /**
     * Turn the lookup off: the attribute goes back to free text.
     *
     * KEEPS {@code lookup_key}, so the list stays attached and turning it back
     * on restores exactly what was there — the screen promises the values are
     * kept, and clearing the link would make that a half-truth. {@code
     * lookup_off} is what makes the decision survive a restart; without it the
     * seed would read "not LOOKUP" as "not set up yet" and switch it back on.
     */
    public void disable(int attributeId) {
        jdbc.update("UPDATE layer_attribute SET data_type = 'STRING', lookup_off = true "
                + "WHERE id = ? AND role = 'NONE'", attributeId);
    }

    /**
     * The values an attribute permits, for import validation.
     *
     * A LOOKUP attribute accepts its codes and its values and nothing else —
     * that is what "lookup on" MEANS, and without this the restriction would be
     * a claim the screen makes and the importer ignores. Inactive values are
     * excluded: they decode for rows that already hold them but may not arrive
     * in a new file.
     *
     * Returns null when the attribute is not a lookup, which the caller reads as
     * "no restriction" — distinct from an empty list, which would mean a lookup
     * that permits nothing.
     */
    public java.util.Set<String> permittedValues(String layerKey, String dataset, String attributeName) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT v.code, v.label
              FROM layer_attribute a
              JOIN layer_definition d ON d.id = a.layer_id
              JOIN lookup_set s ON s.set_key = a.lookup_key
              JOIN lookup_value v ON v.set_id = s.id AND v.active
             WHERE d.layer_key = ? AND a.dataset_key = ? AND a.name = ?
               AND a.data_type = 'LOOKUP'
            """, layerKey, dataset == null ? "default" : dataset, attributeName);
        if (rows.isEmpty()) return null;
        java.util.Set<String> ok = new java.util.HashSet<>();
        for (Map<String, Object> r : rows) {
            ok.add(norm(String.valueOf(r.get("code"))));
            ok.add(norm(String.valueOf(r.get("label"))));
        }
        return ok;
    }

    /* ------------------------------------------------------------------
       Writes
       ------------------------------------------------------------------ */

    @Transactional
    public Map<String, Object> createSet(String name) {
        String clean = require(name, "Give the code list a name");
        String key = slug(clean);
        if (key.isEmpty()) throw new IllegalArgumentException("The name must contain letters or digits");
        if (setId(key) != null) throw new IllegalArgumentException("A code list named \"" + clean + "\" already exists");
        Integer id = jdbc.queryForObject(
                "INSERT INTO lookup_set (set_key, name, builtin) VALUES (?,?,false) RETURNING id",
                Integer.class, key, clean);
        return Map.of("id", id, "key", key, "name", clean);
    }

    public void renameSet(int id, String name) {
        jdbc.update("UPDATE lookup_set SET name = ? WHERE id = ?",
                require(name, "Give the code list a name").trim(), id);
    }

    /**
     * Add or update one code.
     *
     * The code is the identity — it is what the data holds — so changing a
     * label is an update in place and never a delete-and-re-add, which would
     * lose the row's position in the list.
     */
    @Transactional
    public void putValue(int setId, String code, String label, String dependsOn, Boolean active) {
        String c = require(code, "A short code is required").trim();
        String l = require(label, "A lookup value is required").trim();
        String d = (dependsOn == null || dependsOn.isBlank()) ? null : dependsOn.trim();
        boolean a = active == null || active;
        jdbc.update("""
            INSERT INTO lookup_value (set_id, code, label, depends_on, active, sort_order)
            VALUES (?,?,?,?,?, COALESCE((SELECT max(sort_order) + 10 FROM lookup_value WHERE set_id = ?), 10))
            ON CONFLICT (set_id, code) DO UPDATE
               SET label = EXCLUDED.label, depends_on = EXCLUDED.depends_on,
                   active = EXCLUDED.active
            """, setId, c, l, d, a, setId);
    }

    public void deleteValue(int valueId) {
        jdbc.update("DELETE FROM lookup_value WHERE id = ?", valueId);
    }

    /**
     * Delete a code list.
     *
     * Refused while an attribute still reads it: the codes in that column would
     * stop expanding everywhere at once, with nothing on screen to explain why
     * "FLX" had suddenly replaced "Flexible".
     */
    @Transactional
    public void deleteSet(int id) {
        String key = jdbc.queryForObject("SELECT set_key FROM lookup_set WHERE id = ?", String.class, id);
        List<Map<String, Object>> used = usedBy(key);
        if (!used.isEmpty()) {
            throw new IllegalArgumentException(
                    "This code list is still read by " + used.size() + " attribute(s) — "
                    + used.get(0).get("layer") + " · " + used.get(0).get("attribute")
                    + (used.size() > 1 ? " and others" : "")
                    + ". Point them elsewhere first.");
        }
        jdbc.update("DELETE FROM lookup_set WHERE id = ?", id);
    }

    /** Point an attribute at a code list, or clear it. */
    @Transactional
    public void bindAttribute(int attributeId, String setKey) {
        if (setKey == null || setKey.isBlank()) {
            jdbc.update("UPDATE layer_attribute SET lookup_key = NULL, data_type = 'STRING' "
                    + "WHERE id = ? AND role = 'NONE'", attributeId);
            return;
        }
        if (setId(setKey) == null) throw new IllegalArgumentException("No such code list");
        int n = jdbc.update("UPDATE layer_attribute SET lookup_key = ?, data_type = 'LOOKUP' "
                + "WHERE id = ? AND role = 'NONE'", setKey, attributeId);
        if (n == 0) {
            throw new IllegalArgumentException(
                    "This attribute places the feature, so it cannot be a coded value.");
        }
    }

    /* ------------------------------------------------------------------
       Helpers
       ------------------------------------------------------------------ */

    private Integer setId(String key) {
        try {
            return jdbc.queryForObject("SELECT id FROM lookup_set WHERE set_key = ?", Integer.class, key);
        } catch (Exception e) {
            return null;
        }
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String slug(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private static String require(String s, String message) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException(message);
        return s;
    }

    /* The layers and attributes the Lookup screen offers to bind — every
       attribute that could sensibly hold a code, grouped the way the screen
       shows them. Placement columns are excluded: a section label or a chainage
       is never a coded value. */
    public List<Map<String, Object>> bindableAttributes() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT d.layer_key, d.name AS layer_name, d.sort_order AS layer_sort,
                   a.id, a.name, a.dataset_key, a.data_type, a.lookup_key
              FROM layer_attribute a
              JOIN layer_definition d ON d.id = a.layer_id
             WHERE a.role = 'NONE' AND a.status = 'ACTIVE'
               AND a.data_type IN ('STRING','LOOKUP','INTEGER')
             ORDER BY d.sort_order, d.name, a.sort_order, a.id
            """);
        Map<String, Map<String, Object>> byLayer = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String key = String.valueOf(r.get("layer_key"));
            Map<String, Object> layer = byLayer.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("layerKey", key);
                m.put("layer", r.get("layer_name"));
                m.put("attributes", new ArrayList<Map<String, Object>>());
                return m;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) layer.get("attributes");
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id", r.get("id"));
            a.put("name", r.get("name"));
            a.put("dataset", r.get("dataset_key"));
            a.put("dataType", r.get("data_type"));
            a.put("lookupKey", r.get("lookup_key"));
            list.add(a);
        }
        return new ArrayList<>(byLayer.values());
    }
}
