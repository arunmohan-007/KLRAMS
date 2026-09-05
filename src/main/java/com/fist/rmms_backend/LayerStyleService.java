package com.fist.rmms_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Style &amp; Label Management: how each map layer is PAINTED, as opposed to what
 * it is.
 *
 * <p>{@link LayerRegistryService} answers "where do this layer's features come
 * from and how are they placed". This one answers the question the viewer asks
 * next — what colour, what width, what symbol, and what text sits beside it.
 *
 * <h2>An unstyled layer keeps its built-in look</h2>
 * The single most important rule here, and the reason nothing on the map moves
 * the day this ships: a layer with no {@code layer_style} row is left completely
 * alone. Every module still paints it exactly as it does today. A row appears
 * only when someone deliberately saves a style, and deleting the row is the
 * "back to the built-in style" action — which is why the built-in look never had
 * to be transcribed into the database, where it could then drift out of step
 * with the code that actually draws it. FWD is the one deliberate exception
 * ({@link #seedFwdStyle}): its D0 scale is transcribed once, on first boot, so
 * the layer has a starting entry here instead of none at all — see that
 * method for why it is safe.
 *
 * <h2>Condition and PCI are deliberately absent</h2>
 * Both already have an exclusive styling screen in the viewer — colour-by
 * parameter with Good/Fair/Poor thresholds for condition, the IRC:82-2023 score
 * bands for PCI. A second place to set their colours would be two answers to one
 * question, so {@link #EXCLUDED} keeps them out of this module entirely and the
 * API refuses them even if a request names one.
 *
 * <h2>Why the style is one JSON document</h2>
 * A style is read as a whole (the viewer wants all of it, or none of it) and
 * written as a whole. Columns would mean a migration for every option added —
 * and the option list is exactly what grows: line dash today, symbol rotation
 * tomorrow. It is validated field by field on the way in ({@link #clean}), so
 * "free-form JSON" never means "whatever reaches the client".
 */
@Service
public class LayerStyleService {

    private static final Logger log = LoggerFactory.getLogger(LayerStyleService.class);

    /** The current shape of a style document. Stamped on every save so a future
     *  migration can tell an old document from a new one without guessing. */
    public static final int VERSION = 1;

    /**
     * Layers this module does not style, because something else already does.
     *
     * <p>Condition is coloured by the chosen parameter and its thresholds
     * (03-condition-style-filter.js); PCI by its score bands (14-pci-engine.js).
     * Both are analytical scales, not presentation choices — changing them from
     * a style screen would silently contradict the legend the viewer prints
     * beside them.
     *
     * <p>The 2 km IRI roll-up is here for the same reason and not an extra one:
     * it is painted Good/Fair/Poor off the very same threshold boxes as the
     * condition layer and repaints itself whenever they move, so a saved style
     * would be overwritten by the next keystroke in either of them.
     */
    static final Set<String> EXCLUDED = Set.of(
            "condition", "condition_segments", "pci_composite", "pci_worst", "iri_2km");

    /* Colours are written straight into MapLibre paint properties, so only the
       one notation the client builds expressions from is accepted. */
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private static final Set<String> COLOR_MODES = Set.of("SINGLE", "CATEGORY", "RANGE", "GRADIENT");
    private static final Set<String> DASHES = Set.of("SOLID", "DASH", "DOT", "DASH_DOT", "LONG_DASH", "RAIL");
    private static final Set<String> CAPS = Set.of("butt", "round", "square");
    private static final Set<String> JOINS = Set.of("bevel", "round", "miter");
    private static final Set<String> POINT_MODES = Set.of("CIRCLE", "ICON");
    /** ALL every attribute the feature carries · FIELDS a chosen list · NONE no popup. */
    private static final Set<String> POPUP_MODES = Set.of("ALL", "FIELDS", "NONE");
    private static final Set<String> FONTS = Set.of("REGULAR", "BOLD", "SEMIBOLD");
    private static final Set<String> TRANSFORMS = Set.of("none", "uppercase", "lowercase");
    private static final Set<String> PLACEMENTS = Set.of("AUTO", "POINT", "LINE", "LINE_CENTER");
    private static final Set<String> ANCHORS = Set.of(
            "center", "left", "right", "top", "bottom", "top-left", "top-right",
            "bottom-left", "bottom-right");
    private static final Set<String> FILL_PATTERNS = Set.of("NONE", "HATCH", "CROSS", "DOTS");
    private static final Set<String> TEMPLATE_SCOPES = Set.of("ANY", "LINE", "POINT", "POLYGON");

    /**
     * The symbols a point layer may be drawn with.
     *
     * <p>Named here as well as in the client because the SVG for each is
     * generated browser-side from this name plus the style's colour — so the
     * server validating the name is what stops an unknown symbol reaching a
     * layer and rendering as nothing at all.
     */
    static final List<String> ICONS = List.of(
            "circle", "square", "diamond", "triangle", "star", "hexagon", "pin",
            "cross", "plus", "ring", "target", "flag", "bridge", "culvert",
            "sign", "signal", "light", "camera", "tree", "water", "hazard",
            "wrench", "drop", "arrow", "chevron", "square-rounded", "pentagon",
            "bolt", "pit", "core");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public LayerStyleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Deliberately NOT {@code @PostConstruct}, for the same reason
     * {@link LayerAttributeService#ensure()} is not: {@code layer_style} carries
     * a foreign key to {@code layer_definition}, which the registry has to
     * create and seed first. {@link LayerRegistryService#ensure()} calls this
     * once it is done.
     */
    public void ensure() {
        try {
            ensureSchema();
            seedTemplates();
            seedFwdStyle();
        } catch (Exception e) {
            log.error("Style registry init failed — Style & Label Management may be degraded, "
                    + "but the app will keep starting", e);
        }
    }

    private void ensureSchema() {
        /* Keyed by layer_key rather than by the layer's id: layer_key is the
           identity every module, tile source and importer already selects on,
           and it is what the viewer has in hand when it asks for a style. */
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS layer_style (
                id          serial PRIMARY KEY,
                layer_key   text UNIQUE NOT NULL
                            REFERENCES layer_definition(layer_key) ON DELETE CASCADE,
                style       jsonb NOT NULL,
                template_key text,
                updated_at  timestamp NOT NULL DEFAULT now(),
                updated_by  text
            )""");

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS layer_style_template (
                id           serial PRIMARY KEY,
                template_key text UNIQUE NOT NULL,
                name         text NOT NULL,
                scope        text NOT NULL DEFAULT 'ANY',
                description  text,
                style        jsonb NOT NULL,
                built_in     boolean NOT NULL DEFAULT false,
                sort_order   integer NOT NULL DEFAULT 500,
                created_at   timestamp NOT NULL DEFAULT now(),
                created_by   text
            )""");
    }

    /* ------------------------------------------------------------------
       Built-in templates
       ------------------------------------------------------------------ */

    /**
     * Seed the template library.
     *
     * <p>The presets are the Mapbox cartographic palettes — Streets, Light,
     * Dark, Outdoors, Navigation, Satellite — expressed as this module's style
     * document. They are here because they are the reference most people
     * already have an eye for: "make it look like the blue Mapbox route line" is
     * a request with one obvious answer, and picking it from a list beats
     * matching a hex code by eye.
     *
     * <p>Re-run on every boot and upserted by key, so a corrected preset ships
     * with a restart. Only rows marked {@code built_in} are touched — a template
     * the RMMS cell saved is theirs and is never overwritten, even if it happens
     * to share a key.
     */
    private void seedTemplates() {
        int sort = 10;

        /* ---- Mapbox Streets ---- */
        tpl("mb_streets_road", "Mapbox Streets · Road", "LINE",
            "The white road casing of the Mapbox Streets basemap — a light fill inside a grey edge.",
            line("#ffffff", 4, "#cdcdcd", 1.3), sort += 10);
        tpl("mb_streets_motorway", "Mapbox Streets · Motorway", "LINE",
            "The amber motorway ribbon from Mapbox Streets.",
            line("#f7c948", 5, "#d9a300", 1.4), sort += 10);
        tpl("mb_streets_rail", "Mapbox Streets · Rail", "LINE",
            "A dark hatched line, the way Mapbox Streets draws railways.",
            dashed("#6b6b76", 3, "RAIL"), sort += 10);

        /* ---- Mapbox Light / Dark ---- */
        tpl("mb_light", "Mapbox Light", "LINE",
            "Muted grey on white — for a layer that should sit behind the data, not compete with it.",
            line("#a3aab5", 3, "#ffffff", 1.2), sort += 10);
        tpl("mb_dark", "Mapbox Dark", "LINE",
            "The slate line of the Mapbox Dark basemap.",
            line("#5a6472", 3, "#1b2029", 1.2), sort += 10);

        /* ---- Mapbox Outdoors / Navigation / Satellite ---- */
        tpl("mb_outdoors_trail", "Mapbox Outdoors · Trail", "LINE",
            "The dashed clay-coloured path of Mapbox Outdoors.",
            dashed("#c4682a", 3, "DASH"), sort += 10);
        tpl("mb_navigation_route", "Mapbox Navigation · Route", "LINE",
            "The heavy blue route line of the Mapbox navigation styles.",
            line("#3887be", 6, "#2b6ca3", 1.6), sort += 10);
        tpl("mb_satellite_overlay", "Mapbox Satellite · Overlay", "LINE",
            "High-contrast yellow with a dark edge — legible over imagery.",
            line("#ffdd57", 4, "#2a2a2a", 1.6), sort += 10);

        /* ---- Point presets ---- */
        tpl("mb_marker_blue", "Mapbox Marker · Blue", "POINT",
            "The classic Mapbox teardrop marker in its default blue.",
            icon("#3887be", "pin", 1.1), sort += 10);
        tpl("mb_marker_red", "Mapbox Marker · Red", "POINT",
            "The same marker in the Mapbox alert red.",
            icon("#e55e5e", "pin", 1.1), sort += 10);
        tpl("mb_circle_light", "Mapbox Light · Point", "POINT",
            "A small white-ringed dot, as Mapbox Light draws place points.",
            circle("#4264fb", 6, "#ffffff", 1.6), sort += 10);
        tpl("mb_circle_dark", "Mapbox Dark · Point", "POINT",
            "A pale dot ringed in slate, for use over the dark basemap.",
            circle("#8fa3c8", 6, "#1b2029", 1.6), sort += 10);

        /* ---- Polygon presets ---- */
        tpl("mb_water", "Mapbox Streets · Water", "POLYGON",
            "The Mapbox water blue, as a translucent wash with a matching edge.",
            fill("#a0c8f0", 0.55, "#75a9dd"), sort += 10);
        tpl("mb_park", "Mapbox Streets · Park", "POLYGON",
            "The Mapbox park green.",
            fill("#a8d5a2", 0.5, "#7bb877"), sort += 10);
        tpl("mb_landuse", "Mapbox Light · Land use", "POLYGON",
            "A barely-there grey wash for a background area layer.",
            fill("#e8eaed", 0.45, "#c9ced6"), sort += 10);

        /* ---- Data-driven ramps ----
           Scoped ANY: a ramp is a rule for turning a number into a colour, and
           that is the same rule whether it paints a line, a dot or an area. The
           attribute is left null on purpose — it is the one part of a ramp that
           cannot be preset, so applying one asks for it. */
        tpl("mb_ramp_traffic", "Ramp · Good to poor", "ANY",
            "Green through amber to red, in five bands. The reading most PWD data wants: "
          + "low is good, high is not.",
            ramp(new String[]{"#1a9850", "#91cf60", "#fee08b", "#fc8d59", "#d73027"}), sort += 10);
        tpl("mb_ramp_viridis", "Ramp · Viridis", "ANY",
            "The perceptually even Viridis ramp — equal steps in the data read as equal steps "
          + "on the map, and it survives greyscale printing.",
            ramp(new String[]{"#440154", "#3b528b", "#21918c", "#5ec962", "#fde725"}), sort += 10);
        tpl("mb_ramp_magma", "Ramp · Magma", "ANY",
            "Dark to bright, the Magma ramp. Reads well over satellite imagery.",
            ramp(new String[]{"#000004", "#51127c", "#b73779", "#fc8961", "#fcfdbf"}), sort += 10);
        tpl("mb_ramp_blues", "Ramp · Blues", "ANY",
            "A single-hue blue ramp for a quantity with no natural midpoint.",
            ramp(new String[]{"#eff3ff", "#bdd7e7", "#6baed6", "#3182bd", "#08519c"}), sort += 10);
        tpl("mb_ramp_diverging", "Ramp · Diverging (blue–red)", "ANY",
            "Cool through neutral to warm, for a value read against a middle rather than a floor.",
            ramp(new String[]{"#2166ac", "#92c5de", "#f7f7f7", "#f4a582", "#b2182b"}), sort += 10);
        tpl("mb_categorical", "Palette · Categorical", "ANY",
            "Eight distinguishable hues for a class or type column — assigned to whichever values "
          + "the layer actually holds when you apply it.",
            categorical(new String[]{"#4264fb", "#e55e5e", "#3bb2d0", "#f7c948", "#8a5cb8",
                                     "#1a9850", "#e07b2a", "#0fa3a3"}), sort += 10);
    }

    /**
     * Give FWD (Deflection) a starting entry in this module, matching the D0
     * colour scale the viewer has always drawn ({@code FWD_D0_STOPS} in
     * {@code js/06-assets.js}) rather than leaving it the one asset layer with
     * no entry here at all.
     *
     * <p>Unlike Condition and PCI, D0 never had its own analytical styling
     * screen — {@code fwdD0ColorExpr()} was simply a constant sitting inside
     * the generic asset loader, so there is no second answer to contradict.
     * {@link FwdTileService} keeps this safe to edit or reset from here: a
     * saved style keyed on {@code D0} is coloured from the same scale-corrected
     * value (mm surveys normalised to microns) the built-in paint already used,
     * not the raw attrs text.
     *
     * <p>Seeded once, on {@code INSERT ... WHERE NOT EXISTS}: a row someone
     * has since edited, or reset back to nothing, is never overwritten by a
     * later boot — the same one-way door {@link #tpl} uses for templates,
     * just without the {@code built_in} column to key it on.
     */
    private void seedFwdStyle() {
        try {
            jdbc.update("""
                INSERT INTO layer_style (layer_key, style, updated_by)
                SELECT 'fwd', ?::jsonb, 'system'
                 WHERE EXISTS (SELECT 1 FROM layer_definition WHERE layer_key = 'fwd')
                   AND NOT EXISTS (SELECT 1 FROM layer_style WHERE layer_key = 'fwd')
                """, write(clean(fwdD0Style())));
        } catch (Exception e) {
            log.error("Could not seed the FWD D0 style — Style & Label Management will show "
                    + "the layer unstyled until someone saves one by hand", e);
        }
    }

    /** The D0 deflection scale: six bands, same breaks and colours as the built-in legend. */
    private static Map<String, Object> fwdD0Style() {
        List<Map<String, Object>> ranges = new ArrayList<>();
        ranges.add(band(null, 100d, "#1a9850", "< 100 microns"));
        ranges.add(band(100d, 200d, "#91cf60", "100 – 200 microns"));
        ranges.add(band(200d, 350d, "#fee08b", "200 – 350 microns"));
        ranges.add(band(350d, 500d, "#fdae61", "350 – 500 microns"));
        ranges.add(band(500d, 700d, "#f46d43", "500 – 700 microns"));
        ranges.add(band(700d, null, "#b2182b", "> 700 microns"));
        Map<String, Object> color = new LinkedHashMap<>();
        color.put("mode", "RANGE");
        color.put("attribute", "D0");
        color.put("ranges", ranges);
        return Map.of("color", color, "line", Map.of("width", 5d));
    }

    private static Map<String, Object> band(Double from, Double to, String color, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (from != null) m.put("from", from);
        if (to != null) m.put("to", to);
        m.put("color", color);
        m.put("label", label);
        return m;
    }

    /** Upsert one built-in template. User-saved rows are never touched. */
    private void tpl(String key, String name, String scope, String description,
                     Map<String, Object> style, int sort) {
        try {
            jdbc.update("""
                INSERT INTO layer_style_template
                    (template_key, name, scope, description, style, built_in, sort_order, created_by)
                VALUES (?,?,?,?,?::jsonb,true,?,'system')
                ON CONFLICT (template_key) DO UPDATE
                   SET name = EXCLUDED.name,
                       scope = EXCLUDED.scope,
                       description = EXCLUDED.description,
                       style = EXCLUDED.style,
                       sort_order = EXCLUDED.sort_order
                 WHERE layer_style_template.built_in
                """, key, name, scope, description, write(clean(style)), sort);
        } catch (Exception e) {
            // Logged with the stack trace, not just the message. These are our
            // own presets built from constants, so a failure here is a bug in
            // this file rather than bad input — and a bare "NullPointerException"
            // with no frame is exactly the message that hides one.
            log.error("Could not seed style template {} — it will be missing from "
                    + "the template gallery", key, e);
        }
    }

    /* ---- Preset builders. Each returns a partial style document; clean()
       fills in every field they leave out, so a preset only has to say what
       makes it different. ---- */

    private static Map<String, Object> line(String color, double width, String outline, double outWidth) {
        return Map.of(
            "color", Map.of("mode", "SINGLE", "value", color),
            "line", Map.of("width", width, "cap", "round", "join", "round",
                    "outline", Map.of("on", true, "color", outline, "width", outWidth)));
    }

    private static Map<String, Object> dashed(String color, double width, String dash) {
        return Map.of(
            "color", Map.of("mode", "SINGLE", "value", color),
            "line", Map.of("width", width, "dash", dash, "cap", "butt", "join", "round",
                    "outline", Map.of("on", false)));
    }

    private static Map<String, Object> circle(String color, double radius, String stroke, double strokeWidth) {
        return Map.of(
            "color", Map.of("mode", "SINGLE", "value", color),
            "point", Map.of("mode", "CIRCLE", "radius", radius,
                    "stroke", Map.of("color", stroke, "width", strokeWidth)));
    }

    private static Map<String, Object> icon(String color, String symbol, double size) {
        return Map.of(
            "color", Map.of("mode", "SINGLE", "value", color),
            "point", Map.of("mode", "ICON", "icon", symbol, "iconSize", size,
                    "stroke", Map.of("color", "#ffffff", "width", 1.6)));
    }

    private static Map<String, Object> fill(String color, double opacity, String outline) {
        return Map.of(
            "color", Map.of("mode", "SINGLE", "value", color),
            "fill", Map.of("opacity", opacity,
                    "outline", Map.of("on", true, "color", outline, "width", 1.2)));
    }

    /**
     * A graduated ramp with the bands left open-ended.
     *
     * <p>The break points are 0-1 fractions rather than data values, because the
     * preset cannot know the range of a column it has never seen. Applying it
     * asks for the attribute and the min/max, and the client spreads the colours
     * across them — so the same ramp works on an IRI in m/km and a deflection in
     * microns without either being wrong.
     */
    private static Map<String, Object> ramp(String[] colors) {
        List<Map<String, Object>> stops = new ArrayList<>();
        for (int i = 0; i < colors.length; i++) {
            stops.add(Map.of("at", colors.length == 1 ? 0d : (double) i / (colors.length - 1),
                    "color", colors[i]));
        }
        return Map.of("color", Map.of(
                "mode", "GRADIENT",
                "value", colors[colors.length / 2],
                "gradient", Map.of("min", 0, "max", 100, "stops", stops)));
    }

    private static Map<String, Object> categorical(String[] colors) {
        List<Map<String, Object>> cats = new ArrayList<>();
        for (String c : colors) cats.add(Map.of("value", "", "color", c));
        return Map.of("color", Map.of(
                "mode", "CATEGORY",
                "value", colors[0],
                "fallback", "#9aa0a6",
                "categories", cats));
    }

    /* ------------------------------------------------------------------
       Reads
       ------------------------------------------------------------------ */

    /**
     * Every saved style, keyed by layer key — what the viewer fetches once on
     * load.
     *
     * <p>Only rows that exist are returned. A layer absent from this map is the
     * signal to leave its built-in paint alone, so an empty response means "the
     * map looks exactly as it always has", which is the correct behaviour on a
     * database where nobody has opened this module yet.
     */
    public Map<String, Object> allStyles() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            jdbc.query("""
                SELECT s.layer_key, s.style, d.hidden, d.frozen, d.geometry_type
                  FROM layer_style s JOIN layer_definition d ON d.layer_key = s.layer_key
                """, rs -> {
                String key = rs.getString("layer_key");
                if (EXCLUDED.contains(key)) return;
                // A hidden or frozen layer is not drawn at all, so shipping its
                // style would only be work the client throws away.
                if (rs.getBoolean("hidden") || rs.getBoolean("frozen")) return;
                Map<String, Object> style = read(rs.getString("style"));
                /* The layer's declared geometry, attached here rather than
                   stored in the document.

                   The viewer needs it to decide which SECTION of the style a
                   given render layer should read, and getting that wrong is not
                   subtle: a bridge is a line layer that also carries a little
                   bridge glyph, and painting that glyph from `point` — a
                   section the editor never shows for a line layer — replaces it
                   with a generic dot that nothing on screen can change back.

                   Derived on read, never persisted, so it cannot drift out of
                   step with the registry the way a stored copy would. */
                style.put("geometry", rs.getString("geometry_type"));
                out.put(key, style);
            });
        } catch (Exception e) {
            log.warn("Could not read layer styles — the viewer will use built-in paint: {}", e.toString());
        }
        return out;
    }

    /** One layer's saved style, or null if it still uses its built-in paint. */
    public Map<String, Object> styleOf(String layerKey) {
        List<String> rows = jdbc.queryForList(
                "SELECT style::text FROM layer_style WHERE layer_key = ?", String.class, layerKey);
        return rows.isEmpty() ? null : read(rows.get(0));
    }

    /**
     * The layers this module may style, with their attributes and current style.
     *
     * <p>One response rather than a request per layer: the screen needs all of
     * it before it can render a single row, and the attribute lists are what
     * make the "colour by" and "label with" pickers possible without a round
     * trip per selection.
     */
    public List<Map<String, Object>> stylableLayers() {
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.query("""
            SELECT d.id, d.layer_key, d.name, d.geometry_type, d.source_type, d.hidden,
                   d.frozen, d.temporary, f.name AS folder, f.folder_key, f.sort_order AS fsort,
                   d.sort_order AS lsort,
                   s.style::text AS style, s.template_key, s.updated_at, s.updated_by
              FROM layer_definition d
              JOIN layer_folder f ON f.id = d.folder_id
              LEFT JOIN layer_style s ON s.layer_key = d.layer_key
             ORDER BY f.sort_order, f.name, d.sort_order, d.name
            """, rs -> {
            String key = rs.getString("layer_key");
            if (EXCLUDED.contains(key)) return;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getInt("id"));
            m.put("key", key);
            m.put("name", rs.getString("name"));
            m.put("folder", rs.getString("folder"));
            m.put("folderKey", rs.getString("folder_key"));
            m.put("geometryType", rs.getString("geometry_type"));
            m.put("sourceType", rs.getString("source_type"));
            m.put("hidden", rs.getBoolean("hidden"));
            m.put("frozen", rs.getBoolean("frozen"));
            m.put("temporary", rs.getBoolean("temporary"));
            String style = rs.getString("style");
            m.put("style", style == null ? null : read(style));
            m.put("templateKey", rs.getString("template_key"));
            m.put("updatedAt", rs.getString("updated_at"));
            m.put("updatedBy", rs.getString("updated_by"));
            out.add(m);
        });
        for (Map<String, Object> l : out) {
            l.put("attributes", attributesOf((Integer) l.get("id")));
        }
        return out;
    }

    /**
     * The attributes a style may key on.
     *
     * <p>Retired ones are excluded: an attribute nobody imports into any more
     * would colour every feature by its fallback, which looks like a broken
     * style rather than a retired column.
     */
    private List<Map<String, Object>> attributesOf(int layerId) {
        try {
            /* A column holding a whole JSON document is not a value anything
               can be coloured or labelled by, so it is not offered.

               full_road_network.props is the one case in the schema today: the
               registry declares it because it IS a column, but the tile ships
               it under a different name as JSON text, so choosing it would
               produce a blank label with nothing on screen explaining why. An
               option that silently does nothing is worse than no option. */
            return jdbc.query("""
                SELECT a.name, a.storage_key, a.data_type, a.unit, a.lookup_key
                  FROM layer_attribute a
                  JOIN layer_definition d ON d.id = a.layer_id
                 WHERE a.layer_id = ? AND a.dataset_key = 'default' AND a.status = 'ACTIVE'
                   AND NOT EXISTS (
                       SELECT 1 FROM information_schema.columns c
                        WHERE c.table_name = d.source_table
                          AND c.column_name = a.storage_key
                          AND c.data_type IN ('jsonb', 'json', 'bytea'))
                 ORDER BY a.sort_order, a.id
                """, (rs, i) -> {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("name", rs.getString("name"));
                a.put("key", rs.getString("storage_key"));
                a.put("type", rs.getString("data_type"));
                a.put("unit", rs.getString("unit"));
                a.put("lookupKey", rs.getString("lookup_key"));
                // Only a number can be banded or ramped; a string can only be
                // matched. The screen greys out the modes that do not apply.
                a.put("numeric", Set.of("DECIMAL", "INTEGER").contains(rs.getString("data_type")));
                return a;
            }, layerId);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** The distinct values of one attribute, for filling a category list. */
    public List<String> valuesOf(String layerKey, String attribute, int limit) {
        Map<String, Object> d;
        try {
            d = jdbc.queryForMap("SELECT physical_table, source_table, layer_key, source_type "
                    + "FROM layer_definition WHERE layer_key = ?", layerKey);
        } catch (Exception e) {
            return List.of();
        }
        String table = str(d.get("physical_table"));
        try {
            if (table != null) {
                return jdbc.queryForList(
                        "SELECT DISTINCT attrs->>? AS v FROM " + safeTable(table)
                      + " WHERE attrs->>? IS NOT NULL AND attrs->>? <> '' ORDER BY 1 LIMIT " + cap(limit),
                        String.class, attribute, attribute, attribute);
            }
            if ("road_assets".equals(str(d.get("source_table")))) {
                return jdbc.queryForList(
                        "SELECT DISTINCT attrs->>? AS v FROM road_assets WHERE asset_type = ? "
                      + "AND attrs->>? IS NOT NULL AND attrs->>? <> '' ORDER BY 1 LIMIT " + cap(limit),
                        String.class, attribute, layerKey, attribute, attribute);
            }
            /* A boundary keeps a whole FeatureCollection in one text column, so
               its values are inside the document rather than down a column —
               the same reason its attributes have to be discovered rather than
               declared. Without this branch "Fill from data" answers nothing
               for the two layers whose one useful field is a name, which is
               precisely the case category colouring exists for. */
            if (layerKey != null && layerKey.startsWith("boundary_")) {
                return jdbc.queryForList("""
                    SELECT DISTINCT f->'properties'->>? AS v
                      FROM boundary b,
                           jsonb_array_elements((b.geojson::jsonb)->'features') f
                     WHERE b.type = ?
                       AND f->'properties'->>? IS NOT NULL
                       AND f->'properties'->>? <> ''
                     ORDER BY 1 LIMIT
                    """ + cap(limit),
                    String.class, attribute, layerKey.substring("boundary_".length()),
                    attribute, attribute);
            }

            String src = str(d.get("source_table"));
            if (src != null && COLUMN_BACKED.contains(src)) {
                // A real column, so the name has to be interpolated — checked
                // against the table's own catalogue first, never trusted from
                // the request. Same rule RoadColumns applies.
                if (!columnExists(src, attribute)) return List.of();
                return jdbc.queryForList(
                        "SELECT DISTINCT \"" + attribute.replace("\"", "") + "\"::text FROM " + src
                      + " WHERE \"" + attribute.replace("\"", "") + "\" IS NOT NULL ORDER BY 1 LIMIT " + cap(limit),
                        String.class);
            }
        } catch (Exception e) {
            log.debug("Could not list values of {}.{}: {}", layerKey, attribute, e.toString());
        }
        return List.of();
    }

    /** Tables whose attributes are real columns rather than jsonb keys. */
    private static final Set<String> COLUMN_BACKED =
            Set.of("roads", "full_road_network", "condition", "traffic_stations");

    private boolean columnExists(String table, String column) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                Integer.class, table, column);
        return n != null && n > 0;
    }

    private static int cap(int limit) {
        return Math.max(1, Math.min(limit, 500));
    }

    private static String safeTable(String t) {
        if (!Pattern.compile("^ul_[0-9]+_[a-z0-9_]{1,40}$").matcher(t).matches()) {
            throw new IllegalArgumentException("Not a layer table");
        }
        return t;
    }

    /* ------------------------------------------------------------------
       Tile support
       ------------------------------------------------------------------ */

    /**
     * The two attribute keys a layer's tiles must carry, or nulls.
     *
     * <p>MVT properties are flat scalars, so the layers whose attributes live in
     * a {@code jsonb} bag ship that bag as one JSON string — which a MapLibre
     * expression cannot look inside. Colouring or labelling by an attribute of
     * one of those layers therefore needs the value lifted out server-side.
     *
     * <p>Only these two keys are lifted, never the whole bag: a tile that
     * carried every attribute of every feature would undo the payload saving
     * that moving to tiles was for. They ride under the fixed names
     * {@code __style} and {@code __label} so the SQL stays parameterised —
     * no attribute name is ever concatenated into a tile query.
     *
     * @return {styleAttribute, labelAttribute}, either or both possibly null
     */
    public String[] tileKeys(String layerKey) {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT style::text FROM layer_style WHERE layer_key = ?", String.class, layerKey);
            if (rows.isEmpty()) return NO_KEYS;
            Map<String, Object> s = read(rows.get(0));
            Map<String, Object> color = mapOf(s.get("color"));
            Map<String, Object> label = mapOf(s.get("label"));
            String styleAttr = "SINGLE".equals(str(color.get("mode"))) ? null : str(color.get("attribute"));
            String labelAttr = Boolean.TRUE.equals(label.get("on")) ? str(label.get("attribute")) : null;
            return new String[]{styleAttr, labelAttr};
        } catch (Exception e) {
            return NO_KEYS;
        }
    }

    private static final String[] NO_KEYS = new String[]{null, null};

    /* ------------------------------------------------------------------
       Writes
       ------------------------------------------------------------------ */

    /** Save (or replace) one layer's style. */
    @Transactional
    public Map<String, Object> save(String layerKey, Map<String, Object> body, String user) {
        assertStylable(layerKey);
        Map<String, Object> style = clean(mapOf(body.get("style")));
        String templateKey = str(body.get("templateKey"));
        jdbc.update("""
            INSERT INTO layer_style (layer_key, style, template_key, updated_at, updated_by)
            VALUES (?, ?::jsonb, ?, now(), ?)
            ON CONFLICT (layer_key) DO UPDATE
               SET style = EXCLUDED.style,
                   template_key = EXCLUDED.template_key,
                   updated_at = now(),
                   updated_by = EXCLUDED.updated_by
            """, layerKey, write(style), templateKey, user);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("layerKey", layerKey);
        out.put("style", style);
        return out;
    }

    /**
     * Drop a layer's style, putting it back to the built-in look.
     *
     * <p>Deleting the row rather than storing a "use the default" style is what
     * keeps the built-in paint in one place — the module that draws the layer.
     * A stored copy of it would be a second version to keep in step, and the
     * first change to either would make the map disagree with itself.
     */
    public void reset(String layerKey) {
        assertStylable(layerKey);
        jdbc.update("DELETE FROM layer_style WHERE layer_key = ?", layerKey);
    }

    /**
     * Apply one template to many layers in a single action.
     *
     * <p>The "set them all at once" case is the reason this exists on the
     * server rather than as a loop in the browser: applying a palette to
     * fourteen layers is one decision, and it should either take or not.
     *
     * <p>A template is a partial document — {@link #merge} keeps whatever the
     * target already had wherever the template says nothing, so applying a
     * colour ramp does not silently wipe a label someone set up last week.
     */
    @Transactional
    public Map<String, Object> applyTemplate(String templateKey, List<String> layerKeys,
                                             Map<String, Object> overrides, String user) {
        Map<String, Object> tpl;
        try {
            tpl = jdbc.queryForMap(
                    "SELECT name, style::text AS style FROM layer_style_template WHERE template_key = ?",
                    templateKey);
        } catch (Exception e) {
            throw new IllegalArgumentException("No such style template");
        }
        Map<String, Object> base = read(String.valueOf(tpl.get("style")));
        if (overrides != null && !overrides.isEmpty()) base = merge(base, overrides);

        List<String> applied = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String key : layerKeys == null ? List.<String>of() : layerKeys) {
            if (key == null || EXCLUDED.contains(key)) { skipped.add(key); continue; }
            try {
                Map<String, Object> existing = styleOf(key);
                Map<String, Object> merged = clean(existing == null ? base : merge(existing, base));
                jdbc.update("""
                    INSERT INTO layer_style (layer_key, style, template_key, updated_at, updated_by)
                    VALUES (?, ?::jsonb, ?, now(), ?)
                    ON CONFLICT (layer_key) DO UPDATE
                       SET style = EXCLUDED.style, template_key = EXCLUDED.template_key,
                           updated_at = now(), updated_by = EXCLUDED.updated_by
                    """, key, write(merged), templateKey, user);
                applied.add(key);
            } catch (Exception e) {
                // One layer that will not take the template must not lose the
                // other thirteen — a layer_key with no definition row is the
                // ordinary cause, not a reason to fail the request.
                log.warn("Could not apply template {} to layer {}: {}", templateKey, key, e.toString());
                skipped.add(key);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("template", tpl.get("name"));
        out.put("applied", applied);
        out.put("skipped", skipped);
        return out;
    }

    /** Save the given style as a reusable template. */
    public Map<String, Object> saveTemplate(Map<String, Object> body, String user) {
        String name = require(str(body.get("name")), "Template name is required");
        String scope = oneOf(str(body.get("scope")), TEMPLATE_SCOPES, "ANY");
        Map<String, Object> style = clean(mapOf(body.get("style")));
        String key = uniqueTemplateKey(slug(name));
        jdbc.update("""
            INSERT INTO layer_style_template
                (template_key, name, scope, description, style, built_in, sort_order, created_by)
            VALUES (?,?,?,?,?::jsonb,false,900,?)
            """, key, name.trim(), scope, str(body.get("description")), write(style), user);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("key", key);
        out.put("name", name.trim());
        return out;
    }

    /** Delete a user-saved template. Built-in presets are refused. */
    public void deleteTemplate(String key) {
        Boolean builtIn = jdbc.queryForObject(
                "SELECT built_in FROM layer_style_template WHERE template_key = ?", Boolean.class, key);
        if (Boolean.TRUE.equals(builtIn)) {
            throw new IllegalArgumentException(
                    "This is a built-in preset and cannot be deleted. Save your own template instead.");
        }
        jdbc.update("DELETE FROM layer_style_template WHERE template_key = ?", key);
    }

    public List<Map<String, Object>> templates() {
        return jdbc.query("""
            SELECT template_key, name, scope, description, style::text AS style, built_in,
                   created_by
              FROM layer_style_template ORDER BY built_in DESC, sort_order, name
            """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", rs.getString("template_key"));
            m.put("name", rs.getString("name"));
            m.put("scope", rs.getString("scope"));
            m.put("description", rs.getString("description"));
            m.put("style", read(rs.getString("style")));
            m.put("builtIn", rs.getBoolean("built_in"));
            m.put("createdBy", rs.getString("created_by"));
            return m;
        });
    }

    private void assertStylable(String layerKey) {
        if (layerKey == null || layerKey.isBlank()) throw new IllegalArgumentException("No layer given");
        if (EXCLUDED.contains(layerKey)) {
            throw new IllegalArgumentException(
                    "Condition and PCI are coloured from their own screen in the viewer — "
                  + "by survey parameter and threshold, not by a saved style. Change them there.");
        }
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM layer_definition WHERE layer_key = ?", Integer.class, layerKey);
        if (n == null || n == 0) throw new IllegalArgumentException("No such layer");
    }

    private String uniqueTemplateKey(String base) {
        String candidate = base.isEmpty() ? "template" : base;
        for (int n = 2; templateExists(candidate); n++) candidate = base + "_" + n;
        return candidate;
    }

    private boolean templateExists(String key) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM layer_style_template WHERE template_key = ?", Integer.class, key);
        return n != null && n > 0;
    }

    /* ------------------------------------------------------------------
       Validation
       ------------------------------------------------------------------ */

    /**
     * Normalise an incoming style into the one shape the viewer knows how to
     * read.
     *
     * <p>Every field is defaulted, clamped or dropped — the returned document
     * always has every key, always in range, and never carries anything the
     * request invented. That is what lets the client apply it straight to a
     * MapLibre paint property without a second round of guarding, and what keeps
     * a hand-written request from putting an arbitrary string where a colour
     * belongs.
     *
     * <p>Unknown values are corrected rather than rejected. A style is a
     * presentation choice, and refusing to save someone's work over a dash
     * pattern this version does not recognise would lose the rest of it.
     */
    Map<String, Object> clean(Map<String, Object> raw) {
        Map<String, Object> in = raw == null ? Map.of() : raw;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", VERSION);

        /* ---- colour ---- */
        Map<String, Object> c = mapOf(in.get("color"));
        Map<String, Object> color = new LinkedHashMap<>();
        String mode = oneOf(str(c.get("mode")), COLOR_MODES, "SINGLE");
        color.put("mode", mode);
        color.put("value", hex(c.get("value"), "#3887be"));
        color.put("fallback", hex(c.get("fallback"), "#9aa0a6"));
        color.put("attribute", "SINGLE".equals(mode) ? null : str(c.get("attribute")));

        List<Map<String, Object>> cats = new ArrayList<>();
        for (Object o : listOf(c.get("categories"))) {
            Map<String, Object> m = mapOf(o);
            Map<String, Object> cat = new LinkedHashMap<>();
            cat.put("value", str(m.get("value")) == null ? "" : str(m.get("value")));
            cat.put("color", hex(m.get("color"), "#3887be"));
            cat.put("label", str(m.get("label")));
            cats.add(cat);
            if (cats.size() >= 60) break;   // a legend longer than this is unreadable anyway
        }
        color.put("categories", cats);

        List<Map<String, Object>> ranges = new ArrayList<>();
        for (Object o : listOf(c.get("ranges"))) {
            Map<String, Object> m = mapOf(o);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("from", num(m.get("from")));
            r.put("to", num(m.get("to")));
            r.put("color", hex(m.get("color"), "#3887be"));
            r.put("label", str(m.get("label")));
            ranges.add(r);
            if (ranges.size() >= 24) break;
        }
        color.put("ranges", ranges);

        Map<String, Object> g = mapOf(c.get("gradient"));
        Map<String, Object> grad = new LinkedHashMap<>();
        grad.put("min", num(g.get("min")) == null ? 0d : num(g.get("min")));
        grad.put("max", num(g.get("max")) == null ? 100d : num(g.get("max")));
        List<Map<String, Object>> stops = new ArrayList<>();
        for (Object o : listOf(g.get("stops"))) {
            Map<String, Object> m = mapOf(o);
            Double at = num(m.get("at"));
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("at", at == null ? 0d : clamp(at, 0, 1));
            s.put("color", hex(m.get("color"), "#3887be"));
            stops.add(s);
            if (stops.size() >= 12) break;
        }
        if (stops.isEmpty()) {
            stops.add(new LinkedHashMap<>(Map.of("at", 0d, "color", "#2166ac")));
            stops.add(new LinkedHashMap<>(Map.of("at", 1d, "color", "#b2182b")));
        }
        grad.put("stops", stops);
        color.put("gradient", grad);
        out.put("color", color);

        /* ---- line ---- */
        Map<String, Object> l = mapOf(in.get("line"));
        Map<String, Object> lineOut = new LinkedHashMap<>();
        lineOut.put("width", clamp(num(l.get("width")), 0.1, 40, 3));
        lineOut.put("opacity", clamp(num(l.get("opacity")), 0, 1, 1));
        lineOut.put("dash", oneOf(str(l.get("dash")), DASHES, "SOLID"));
        lineOut.put("cap", oneOf(str(l.get("cap")), CAPS, "round"));
        lineOut.put("join", oneOf(str(l.get("join")), JOINS, "round"));
        lineOut.put("blur", clamp(num(l.get("blur")), 0, 12, 0));
        /* Widths scale with zoom by default, because a fixed pixel width that
           reads well statewide is a hairline at street level. `zoomScale` off
           means "this many pixels, always" — which some overlays genuinely want. */
        lineOut.put("zoomScale", !Boolean.FALSE.equals(l.get("zoomScale")));
        lineOut.put("outline", outline(mapOf(l.get("outline")), "#0b1322", 1.2));
        out.put("line", lineOut);

        /* ---- point ---- */
        Map<String, Object> p = mapOf(in.get("point"));
        Map<String, Object> pointOut = new LinkedHashMap<>();
        pointOut.put("mode", oneOf(str(p.get("mode")), POINT_MODES, "CIRCLE"));
        pointOut.put("radius", clamp(num(p.get("radius")), 0.5, 40, 5));
        pointOut.put("opacity", clamp(num(p.get("opacity")), 0, 1, 1));
        pointOut.put("blur", clamp(num(p.get("blur")), 0, 4, 0));
        /* Null-checked BEFORE the lookup, and that is not belt-and-braces:
           ICONS is a List.of(), whose contains(null) throws rather than
           answering false. A style document that simply says nothing about
           points — every line, polygon and colour-ramp preset — would take
           the whole clean() call down with it. */
        String icon = str(p.get("icon"));
        pointOut.put("icon", (icon != null && ICONS.contains(icon)) ? icon : "circle");
        pointOut.put("iconSize", clamp(num(p.get("iconSize")), 0.2, 4, 1));
        pointOut.put("iconRotate", clamp(num(p.get("iconRotate")), -180, 180, 0));
        pointOut.put("allowOverlap", Boolean.TRUE.equals(p.get("allowOverlap")));
        pointOut.put("zoomScale", !Boolean.FALSE.equals(p.get("zoomScale")));
        Map<String, Object> stroke = mapOf(p.get("stroke"));
        Map<String, Object> strokeOut = new LinkedHashMap<>();
        strokeOut.put("color", hex(stroke.get("color"), "#ffffff"));
        strokeOut.put("width", clamp(num(stroke.get("width")), 0, 8, 1.4));
        pointOut.put("stroke", strokeOut);
        out.put("point", pointOut);

        /* ---- fill ---- */
        Map<String, Object> f = mapOf(in.get("fill"));
        Map<String, Object> fillOut = new LinkedHashMap<>();
        fillOut.put("opacity", clamp(num(f.get("opacity")), 0, 1, 0.3));
        fillOut.put("pattern", oneOf(str(f.get("pattern")), FILL_PATTERNS, "NONE"));
        fillOut.put("outline", outline(mapOf(f.get("outline")), "#ffffff", 1.2));
        out.put("fill", fillOut);

        /* ---- label ---- */
        Map<String, Object> lb = mapOf(in.get("label"));
        Map<String, Object> label = new LinkedHashMap<>();
        boolean on = Boolean.TRUE.equals(lb.get("on"));
        String labelAttr = str(lb.get("attribute"));
        // "Label on" with nothing to write is off, not an empty label on every
        // feature — MapLibre would still reserve the collision box for it.
        label.put("on", on && labelAttr != null && !labelAttr.isBlank());
        label.put("attribute", labelAttr);
        label.put("size", clamp(num(lb.get("size")), 6, 40, 12));
        label.put("color", hex(lb.get("color"), "#ffffff"));
        label.put("opacity", clamp(num(lb.get("opacity")), 0, 1, 1));
        label.put("font", oneOf(str(lb.get("font")), FONTS, "REGULAR"));
        label.put("transform", oneOf(str(lb.get("transform")), TRANSFORMS, "none"));
        label.put("placement", oneOf(str(lb.get("placement")), PLACEMENTS, "AUTO"));
        label.put("anchor", oneOf(str(lb.get("anchor")), ANCHORS, "center"));
        label.put("offsetX", clamp(num(lb.get("offsetX")), -6, 6, 0));
        label.put("offsetY", clamp(num(lb.get("offsetY")), -6, 6, 0));
        label.put("rotate", clamp(num(lb.get("rotate")), -180, 180, 0));
        label.put("letterSpacing", clamp(num(lb.get("letterSpacing")), -0.1, 1, 0));
        label.put("maxWidth", clamp(num(lb.get("maxWidth")), 2, 40, 10));
        label.put("minZoom", clamp(num(lb.get("minZoom")), 0, 22, 0));
        label.put("maxZoom", clamp(num(lb.get("maxZoom")), 1, 24, 24));
        label.put("allowOverlap", Boolean.TRUE.equals(lb.get("allowOverlap")));
        /* Deliberately NOT trimmed. A leading space is the whole point of a
           suffix — "128 m" rather than "128m" — so the surrounding whitespace
           is content here, not padding to be tidied away. */
        label.put("prefix", affix(lb.get("prefix")));
        label.put("suffix", affix(lb.get("suffix")));
        label.put("decimals", lb.get("decimals") == null ? null
                : (int) (double) clamp(num(lb.get("decimals")), 0, 6, 0));
        Map<String, Object> halo = mapOf(lb.get("halo"));
        Map<String, Object> haloOut = new LinkedHashMap<>();
        haloOut.put("color", hex(halo.get("color"), "#0b1322"));
        haloOut.put("width", clamp(num(halo.get("width")), 0, 6, 1.4));
        haloOut.put("blur", clamp(num(halo.get("blur")), 0, 6, 0));
        label.put("halo", haloOut);
        out.put("label", label);

        /* ---- popup ----
           What a click on the layer shows. Presentation, like everything else
           here, so it belongs in the same document rather than in a table of
           its own: the viewer already fetches this once and has it in hand by
           the time anybody clicks.

           ALL is the default and is what every layer did before this section
           existed, so a style saved without it keeps behaving exactly as it
           did. FIELDS is for the layer imported straight from a shapefile with
           forty columns, where the popup is only useful once it has been cut
           down to the four that were the point of loading it. */
        Map<String, Object> pu = mapOf(in.get("popup"));
        Map<String, Object> popup = new LinkedHashMap<>();
        popup.put("mode", oneOf(str(pu.get("mode")), POPUP_MODES, "ALL"));
        popup.put("title", str(pu.get("title")));
        List<String> fields = new ArrayList<>();
        for (Object o : listOf(pu.get("fields"))) {
            String field = str(o);
            if (field == null || field.isBlank() || fields.contains(field)) continue;
            fields.add(field);
            if (fields.size() >= 40) break;   // a popup taller than this is unreadable
        }
        popup.put("fields", fields);
        out.put("popup", popup);

        /* ---- whole-layer zoom window ---- */
        out.put("minZoom", clamp(num(in.get("minZoom")), 0, 22, 0));
        out.put("maxZoom", clamp(num(in.get("maxZoom")), 1, 24, 24));
        return out;
    }

    private static Map<String, Object> outline(Map<String, Object> o, String defColor, double defWidth) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("on", Boolean.TRUE.equals(o.get("on")));
        out.put("color", hex(o.get("color"), defColor));
        out.put("width", clamp(num(o.get("width")), 0, 12, defWidth));
        /* A polygon's outline IS its border, and a dashed administrative
           border is the normal cartographic convention — the district
           boundary ships dashed. Carried here so that look survives being
           styled, rather than being flattened to solid the first time
           anyone saves. */
        out.put("dash", oneOf(str(o.get("dash")), DASHES, "SOLID"));
        return out;
    }

    /**
     * Overlay {@code patch} onto {@code base}, one level deep per section.
     *
     * <p>Deep enough to matter and no deeper: a template that sets only
     * {@code color} must not wipe {@code label}, but within {@code color} a
     * partial merge would leave a CATEGORY list from the old style sitting
     * under a GRADIENT mode from the new one — a document that validates and
     * paints nothing anyone asked for.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> patch) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map && out.get(e.getKey()) instanceof Map) {
                Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) out.get(e.getKey()));
                merged.putAll((Map<String, Object>) v);
                out.put(e.getKey(), merged);
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    /* ------------------------------------------------------------------
       Small helpers
       ------------------------------------------------------------------ */

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : Map.of();
    }

    private static List<?> listOf(Object o) {
        return (o instanceof List<?> l) ? l : List.of();
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * A label prefix or suffix, with its spacing left intact.
     *
     * Everything else on a style is a token or a number where surrounding
     * whitespace is noise, so {@link #str} trims. These two are the exception:
     * the space in " m" is what separates the value from its unit, and trimming
     * it silently turns every label on the layer into "128m".
     *
     * Still capped, and still normalised to null when there is nothing but
     * emptiness — a suffix of pure spaces is a slip, not a unit.
     */
    private static String affix(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        if (s.isBlank()) return null;
        return s.length() <= 24 ? s : s.substring(0, 24);
    }

    private static Double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return null;
        try {
            return Double.valueOf(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String hex(Object o, String fallback) {
        String s = str(o);
        return (s != null && HEX.matcher(s).matches()) ? s.toLowerCase(Locale.ROOT) : fallback;
    }

    private static String oneOf(String s, Set<String> allowed, String fallback) {
        if (s == null) return fallback;
        if (allowed.contains(s)) return s;
        String up = s.toUpperCase(Locale.ROOT);
        if (allowed.contains(up)) return up;
        String low = s.toLowerCase(Locale.ROOT);
        return allowed.contains(low) ? low : fallback;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static Double clamp(Double v, double lo, double hi, double fallback) {
        if (v == null || v.isNaN() || v.isInfinite()) return fallback;
        return Math.max(lo, Math.min(hi, v));
    }

    private static String require(String s, String message) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException(message);
        return s;
    }

    private static String slug(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private String write(Map<String, Object> m) {
        try {
            return json.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalArgumentException("Style could not be stored");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String s) {
        try {
            return json.readValue(s, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
