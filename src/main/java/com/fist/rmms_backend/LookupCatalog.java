package com.fist.rmms_backend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The declared short-code catalogue: what every coded attribute's values mean.
 *
 * Taken from the RMMS "Lookup sheet Master R2" (30-01-2026), which is the sheet
 * that settles, per survey template and parameter, the short code a surveyor
 * writes and the value it stands for — {@code SH} for State Highway, {@code CLY}
 * for Clay, {@code NB} for Northbound.
 *
 * <h2>Why the codes need declaring at all</h2>
 * The data arrives holding the CODE. Without this, a road card shows
 * "Cons_Type: FLX" and a soil popup shows "Soil Type: CLY" — accurate and
 * unreadable. The viewer used to paper over this with a hard-coded table in
 * {@code js/01-config.js} covering the seven road columns someone got round to,
 * so the road card expanded its codes and nothing else did. This is that table,
 * complete, on the server, and editable.
 *
 * <h2>Sets are shared; attributes point at them</h2>
 * Soil type means the same thing on the sub-grade layer and the pavement-crust
 * layer, and Owner means the same thing on four. So a set is declared ONCE and
 * bound to every attribute that uses it, rather than copied per layer: correct a
 * value and it is corrected everywhere it is read, which is the whole reason the
 * RMMS cell wants one place to edit these.
 *
 * <h2>Declared, then owned</h2>
 * Seeding is additive and never overwrites an edited value — the same rule
 * {@link LayerAttributeCatalog} follows. These are the sheet's codes as of
 * January 2026; from the moment someone edits one on the Lookup screen, theirs
 * wins and a later shipment of this file will not take it back.
 */
final class LookupCatalog {

    private LookupCatalog() {
    }

    /**
     * One code and the value it stands for.
     *
     * Both directions resolve: a row holding {@code FLX} and a row holding
     * {@code Flexible} both display as "Flexible", and matching is normalised so
     * {@code FLEXIBLE} and {@code flexible} do too.
     *
     * There is deliberately no third list of accepted spellings. One existed
     * briefly and was removed: it made a CARD show a single value while the
     * dashboards, which group on the stored string, still split that field into
     * one bucket per spelling. A display claiming a consistency the numbers
     * underneath do not have is worse than an unexpanded code. If a district's
     * spelling has to be supported, the honest fix is to normalise it on the way
     * IN, not to paper over it on the way out.
     */
    record Value(String code, String label) { }

    /**
     * One set of codes, and every attribute that reads them.
     *
     * @param bindings {@code layerKey -> attribute label}, the attributes whose
     *                 values this set decodes. The attribute is named by its
     *                 LABEL because that is what Attribute Data shows and what
     *                 the sheet's "Parameter" column says.
     */
    record Set(String key, String name, List<Value> values, List<String[]> bindings) { }

    private static final List<Set> SETS = new ArrayList<>();

    /** @param values each row is {@code {code, value}}. */
    private static void set(String key, String name, String[][] values, String[][] bindings) {
        List<Value> vs = new ArrayList<>();
        for (String[] v : values) vs.add(new Value(v[0], v[1]));
        SETS.add(new Set(key, name, List.copyOf(vs), List.of(bindings)));
    }

    /* The soil classification, and the owner list, are each used by several
       layers — declared once here and bound to all of them below. */
    private static final String[][] SOIL = {
        {"CLY", "Clay"}, {"SIL", "Silt"}, {"SND", "Sand"}, {"GRV", "Gravel"},
        {"LAT", "Laterite"}, {"BLK", "Black Cotton"}, {"RCK", "Rock"}
    };
    private static final String[][] SURFACE = {
        {"BT", "Bituminous"},
        {"CC", "Cement Concrete"},
        {"PVB", "Paver Block"},
        {"WBM", "WBM"},
        {"GRV", "Gravel"},
        {"ERT", "Earthen"}
    };

    static {
        /* ---- R1 · Road network (shapefile) ----
           Storage keys here are the DBF's 10-character truncations, so the
           bindings name the attribute LABEL the catalogue gives them. */
        set("road_class", "Road Class", new String[][]{
            {"SH", "State Highway"}, {"MDR", "Major District Road"},
            {"ODR", "Other District Road"}, {"NH", "National Highway"}
        }, new String[][]{{"roads", "Road Class"}});

        set("lane_type", "Lane Type", new String[][]{
            {"SLR", "Single Lane Road"},
            {"ILR", "Intermediate Road"},
            {"TLR", "Two Lane Road"},
            {"WTL", "Wide Two Lane Road"},
            {"FLR", "Four Lane Road"}
        }, new String[][]{{"roads", "Lane Type"}});

        set("pavement_width_band", "Pavement Width Band", new String[][]{
            {"1", ">=3.75m and <5.5m"}, {"2", ">5.5m and <7m"},
            {"3", ">=7m and <10.5m"}, {"4", ">=10.5m and <=12.5m"}, {"5", ">12.5 m"}
        }, new String[][]{{"roads", "Pavement Width"}});

        set("shoulder_width_band", "Shoulder Width Band", new String[][]{
            {"1", "No shoulder"}, {"2", "<1m"}, {"3", ">=1m and <=2m"}, {"4", ">2m"}
        }, new String[][]{{"roads", "Shoulder Width"}});

        set("environment", "Environment", new String[][]{
            {"URB", "Urban"},
            {"SUB", "Semi-Urban"},
            {"RUR", "Rural"},
            {"FOR", "Forest Area"},
            {"COA", "Coastal Area"},
            {"HIL", "Hilly Area"},
            {"IND", "Industrial Area"},
            {"RES", "Residential Area"},
            {"AGR", "Agricultural/Plantation Area"}
        }, new String[][]{{"roads", "Environment"}});

        set("construction_type", "Construction Type", new String[][]{
            {"FLX", "Flexible"},
            {"RGD", "Rigid"},
            {"CMP", "Composite"},
            {"WBM", "WBM"},
            {"GRV", "Gravel"},
            {"ERT", "Earthen"},
            {"PVB", "Paver Block"}
        }, new String[][]{{"roads", "Construction Type"}});

        set("surface_type", "Surface Type", SURFACE, new String[][]{
            {"roads", "Surface Type"},
            {"pavement_crust", "Surface Type"}
        });

        set("terrain", "Terrain", new String[][]{
            {"FLT", "Flat"},
            {"RLL", "Rolling"},
            {"HIL", "Hilly/Steep"}
        }, new String[][]{{"roads", "Terrain"}});

        set("region", "Region", new String[][]{
            {"NR", "North"}, {"CR", "Central"}, {"SR", "South"}
        }, new String[][]{{"roads", "Region"}});

        /* ---- Owner ----
           The sheet lists this under R8, but the same department codes appear on
           the road network and on every structure, so it is bound to all of them. */
        set("owner", "Owner", new String[][]{
            {"KMRL", "Kochi Metro Rail Limited"},
            {"KRFB", "Kerala Road Fund Board"},
            {"KRFB-PMU", "Kerala Road Fund Board — Project Management Unit"},
            {"KSTP", "Kerala State Transport Project"},
            {"PWD Maintenance", "PWD Maintenance"},
            {"PWD Section", "PWD Section"},
            {"RICK", "Road Infrastructure Company Kerala Limited"}
        }, new String[][]{
            {"roads", "Owner"}, {"roads", "Current Owner"},
            {"bridge", "Owner"}, {"culvert", "Owner"},
            {"furniture_line", "Owner"}, {"furniture_point", "Owner"}
        });

        /* ---- R13 / R8 · Soil classification ---- */
        set("soil_type", "Soil Type", SOIL, new String[][]{
            {"subgrade", "Soil Type"},
            {"pavement_crust", "Sub Grade Soil Type"}
        });

        /* ---- R8 · Pavement crust ---- */
        set("base_type", "Base Type", new String[][]{
            {"WMM", "Wet Mix Macadam"}, {"WBM", "Water Bound Macadam"},
            {"CTB", "Cement Treated Base"}, {"GBS", "Granular Base (General)"}
        }, new String[][]{{"pavement_crust", "Base Type"}});

        set("sub_base_type", "Sub Base Type", new String[][]{
            {"GSB", "Granular Subbase"}, {"CTSB", "Cement Treated Subbase"},
            {"EGB", "Earth / Gravel Subbase"}, {"SRL", "Selected Soil Layer"}
        }, new String[][]{{"pavement_crust", "Sub Base Type"}});

        /* ---- R14 · Bridges ---- */
        set("bridge_type", "Bridge Type", new String[][]{
            {"MAJ", "Major Bridge"},
            {"MIN", "Minor Bridge"}
        }, new String[][]{{"bridge", "Bridge Type"}});

        /* ---- R12 / R16 · Road furniture ---- */
        set("furniture_point_type", "Road Furniture Type (point)", new String[][]{
            {"SIG", "Signage / Road Signs"},
            {"SSP", "Street Light / Solar Poles"},
            {"GAB", "Gantry Board"}
        }, new String[][]{{"furniture_point", "Road_Furniture_Type"}});

        set("furniture_line_type", "Road Furniture Type (line)", new String[][]{
            {"MRK", "Road Markings"}, {"GDR", "Guardrail / Crash Barrier"},
            {"DRL", "Delineators"}, {"RFL", "Reflectors"}
        }, new String[][]{{"furniture_line", "Asset Name"}});

        /* Revised R2 sheet: two bearings only, and the "(increasing chainage)"
           qualifier dropped from the value. */
        /* The R2 sheet reduced this to the two chainage directions — the
           cardinal East/West pair it used to carry is gone, because a station's
           direction on a road IS its chainage direction. */
        set("direction", "Direction", new String[][]{
            {"NB", "Northbound"},
            {"SB", "Southbound"}
        }, new String[][]{{"traffic_stations", "Direction"}});

        /* ---- The lane (cross-section position) every survey return carries ----
           Not on the sheet, which covers the coded PARAMETERS rather than the
           position column, but XSP is a code on ten layers and reads as noise
           until it is expanded. Declared with the values the returns use. */
        set("xsp", "Cross-Section Position (XSP)", new String[][]{
            {"CC", "Carriageway Centre"}, {"CL1", "Centre Lane 1 (left)"},
            {"CL2", "Centre Lane 2 (left)"}, {"CR1", "Centre Lane 1 (right)"},
            {"CR2", "Centre Lane 2 (right)"}
        }, new String[][]{
            {"condition", "XSP"}, {"fwd", "XSP"}, {"bridge", "XSP"},
            {"culvert", "XSP"}, {"furniture_line", "XSP"}, {"furniture_point", "XSP"},
            {"subgrade", "Xsp"}, {"bituminous_core", "XSP"},
            {"pavement_crust", "XSP"}, {"traffic_stations", "XSP"}
        });
    }

    /*
     * Deliberately NOT declared, and why:
     *
     *   R3 Pavement Inventory (Pavement Type, Type of junction), R7 Axle Load
     *   (HDM_FieldID) and R15 Drain (Drain Type) — the sheet defines codes for
     *   layers KLRAMS does not have. Seeding a set nothing can reference would
     *   put dead rows on the Lookup screen.
     *
     *   R5 FWD "Survey VERSION" and the two "Survey Type" blocks — the sheet
     *   marks these "to be defined in Survey manager" and leaves the short code
     *   blank on most rows. A code column with no codes in it is not a lookup.
     */

    static List<Set> sets() {
        return List.copyOf(SETS);
    }

    /** layerKey -> attribute label -> set key, for binding after the seed. */
    static Map<String, Map<String, String>> bindings() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Set s : SETS) {
            for (String[] b : s.bindings()) {
                out.computeIfAbsent(b[0], k -> new LinkedHashMap<>()).put(b[1], s.key());
            }
        }
        return out;
    }
}
