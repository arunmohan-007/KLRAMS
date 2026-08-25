package com.fist.rmms_backend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The declared attribute catalogue: the canonical column list of every KLRAMS
 * layer, written down once here instead of being inferred from whatever data
 * happens to be in the database.
 *
 * <h2>Why a declaration and not discovery</h2>
 * {@link LayerAttributeService} used to describe a layer by reading it — the
 * jsonb keys present in {@code road_assets}, the columns present in
 * {@code condition}. That works only for a layer that already holds data, and
 * it inherits every spelling the surveyors happened to use. A district that has
 * not been uploaded yet shows an empty attribute list; a district uploaded with
 * {@code Section Label} and one uploaded with {@code Section_Label} become two
 * different attributes on the same layer.
 *
 * This file is the fixed answer to "what fields does this layer carry", taken
 * from the RMMS Format-B survey returns (R2, R5, R8–R16). Discovery still runs
 * afterwards, so a column nobody predicted is still picked up — the catalogue
 * raises the floor, it does not cap what a layer may hold.
 *
 * <h2>Three names, deliberately kept apart</h2>
 * <ul>
 *   <li><b>name</b> — the label. This is what the map popups, the inspection and
 *       summary cards, the dashboards and the import screen all show, and it is
 *       the one thing the RMMS cell may freely rename.</li>
 *   <li><b>storageKey</b> — where the value actually lives: a real column on
 *       {@code condition} / {@code roads} / {@code road_assets}, or a key inside
 *       the {@code attrs} jsonb bag. Renaming a label must never move this, or
 *       every module selecting the column breaks.</li>
 *   <li><b>aliases</b> — the header spellings seen in real uploads. The importer
 *       maps any of them onto the storage key, which is what lets Malappuram's
 *       {@code Section_Label} and Idukki's {@code Section Label} land in the same
 *       attribute.</li>
 * </ul>
 *
 * Storage keys for {@code road_assets} layers are the CSV header verbatim,
 * because that is exactly what {@code AssetController} writes into {@code attrs}
 * (it stores every column under its trimmed header). The catalogue therefore
 * picks ONE of the observed spellings as canonical and lists the rest as
 * aliases, rather than inventing a new key that no stored row would match.
 */
final class LayerAttributeCatalog {

    private LayerAttributeCatalog() {
    }

    /** One declared attribute. */
    record Attr(String name, String storageKey, String dataType, String unit,
                boolean mandatory, String role, List<String> aliases) {

        Attr {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    /* Shorthands. The argument order is (label, storage key, …) throughout so a
       row reads as "this is called X and is stored as Y". */

    private static Attr s(String name, String key, String... aliases) {
        return new Attr(name, key, "STRING", null, false, "NONE", List.of(aliases));
    }

    private static Attr d(String name, String key, String unit, String... aliases) {
        return new Attr(name, key, "DECIMAL", unit, false, "NONE", List.of(aliases));
    }

    private static Attr i(String name, String key, String unit, String... aliases) {
        return new Attr(name, key, "INTEGER", unit, false, "NONE", List.of(aliases));
    }

    private static Attr dt(String name, String key, String... aliases) {
        return new Attr(name, key, "DATE", null, false, "NONE", List.of(aliases));
    }

    /** The section label that places the feature. Always STRING, always mandatory. */
    private static Attr sec(String name, String key, String... aliases) {
        return new Attr(name, key, "STRING", null, true, "SECTION_LABEL", List.of(aliases));
    }

    /** A chainage that places the feature. Always numeric, always mandatory. */
    private static Attr ch(String name, String key, String role, String... aliases) {
        return new Attr(name, key, "DECIMAL", "m", true, role, List.of(aliases));
    }

    /* Alias sets that recur across nearly every return, kept in one place so a
       new district's spelling is fixed for all layers at once. */
    private static final String[] SECTION_ALIASES = {
        "Section_Label", "Section Label", "Section Label Code", "Section_Label_Code",
        "SectionLabel", "Section_La", "Label", "Section"
    };
    /* "Chiange" is not a typo in this file — it is the spelling several survey
       returns actually ship, and it is already in the database as its own
       attribute on some layers. Listing it lets those rows fold into the
       correctly-spelled attribute instead of standing beside it. */
    private static final String[] START_CH_ALIASES = {
        "Start_Chainage", "Start Chainage", "From", "From_Chainage", "From Chainage",
        "Chainage_From", "StartChainage", "Start Chiange", "Start_Chiange"
    };
    private static final String[] END_CH_ALIASES = {
        "End_Chainage", "End Chainage", "To", "To_Chainage", "To Chainage",
        "Chainage_To", "EndChainage", "End Chiange", "End_Chiange"
    };
    private static final String[] POINT_CH_ALIASES = {
        "Chainage", "Start_Chainage", "Start Chainage", "Chiange", "Start Chiange"
    };

    /* ------------------------------------------------------------------
       The catalogue
       ------------------------------------------------------------------ */

    /** layer key -> dataset key -> attributes, in the order the screens show them. */
    private static final Map<String, Map<String, List<Attr>>> BY_LAYER = new LinkedHashMap<>();

    private static void put(String layerKey, String dataset, List<Attr> attrs) {
        BY_LAYER.computeIfAbsent(layerKey, k -> new LinkedHashMap<>()).put(dataset, attrs);
    }

    static {
        /* ---- R2 · Road Condition Data ----
           Column-backed: `condition` has a real column per field, so the storage
           keys are those columns and the aliases are the CSV headers.

           The return also carries Survey_Start_Date, Survey_End_Date,
           Surveying_Company_Name, Survey_Version and Section_Start_Date. They are
           NOT listed: ConditionService.loadCsv reads none of them and the table
           has nowhere to put them, so declaring them would promise a field the
           system silently drops. */
        put("condition", "default", List.of(
            sec("Section_Label", "section_label", SECTION_ALIASES),
            ch("Start_Chainage", "start_chainage", "START_CHAINAGE", START_CH_ALIASES),
            ch("End_Chainage", "end_chainage", "END_CHAINAGE", END_CH_ALIASES),
            s("XSP", "xsp", "Xsp", "Lane", "XSP_Code"),
            s("Survey_Type", "survey_type", "Survey Type"),
            /* Labels and units here are the ones the viewer already prints for
               these seven (js/01-config.js PARAMS), not the survey return's
               header spellings — the header goes in the alias list. Keeping them
               identical is what lets the viewer adopt the catalogue without any
               card changing on the day it ships; from then on, renaming one here
               is what moves it everywhere. */
            d("IRI", "iri", "m/km", "IRI"),
            d("Crack", "crack", "%", "CRACK", "Cracking"),
            d("Pothole", "pothole", "nos/km", "Potholes", "POTHOLE"),
            d("Rutting", "rutting", "mm", "Rut", "RUTTING"),
            d("Texture", "texture", "mm", "TEXTURE"),
            d("Patch work", "patch_work", "sqm", "Patch_Work", "Patch Work", "Patchwork"),
            d("Ravelling", "ravelling", "%", "Raveling", "RAVELLING"),
            d("Start_Latitude", "start_lat", "deg", "Start Latitude", "Start_Lat"),
            d("Start_Longitude", "start_lng", "deg", "Start Longitude", "Start_Long"),
            d("End_Latitude", "end_lat", "deg", "End Latitude", "End_Lat", "End Lat"),
            d("End_Longitude", "end_lng", "deg", "End Longitude", "End_Long", "End Long")));

        /* ---- R5 · FWD Deflection ----
           jsonb-backed on road_assets. The return names the section column
           "Label" and the chainages "From"/"To", which is why the placement roles
           are declared rather than guessed from the name. D7–D9 are catalogued
           even though this return stops at D6: geophone counts vary by machine
           and an absent column simply stays empty. */
        List<Attr> fwd = new ArrayList<>(List.of(
            sec("Section_Label", "section_label", SECTION_ALIASES),
            ch("From", "start_chainage", "START_CHAINAGE", START_CH_ALIASES),
            ch("To", "end_chainage", "END_CHAINAGE", END_CH_ALIASES),
            s("XSP", "XSP", "Xsp", "Lane"),
            s("Survey Type", "Survey Type", "Survey_Type"),
            dt("Survey Start Date", "Survey Start Date", "Survey_Start_Date"),
            dt("Survey End Date", "Survey End Date", "Survey_End_Date"),
            s("Surveying Company Name", "Surveying Company Name",
              "Surveying_Company_Name", "Survey Company Name"),
            dt("Section Start Date", "Section Start Date", "Section_Start_Date"),
            s("Survey Version", "Survey Version", "Survey_Version")));
        for (int n = 0; n <= 9; n++) {
            fwd.add(d("D" + n, "D" + n, "microns", "D" + n + " ", "d" + n));
        }
        fwd.add(d("Pavement Temp", "Pavement Temp", "°C",
                  "Pavement_Temp", "Pavement Temperature", "Pav Temp"));
        fwd.add(d("Air Temp", "Air Temp", "°C", "Air_Temp", "Air Temperature"));
        fwd.add(d("Point Latitude", "Point Latitude", "deg", "Point_Latitude", "Latitude"));
        fwd.add(d("Point Longitude", "Point Longitude", "deg", "Point_Longitude", "Longitude"));
        put("fwd", "default", List.copyOf(fwd));

        /* ---- R14 · Bridges (line) ---- */
        put("bridge", "default", List.of(
            sec("Section Label", "section_label", SECTION_ALIASES),
            ch("Start Chainage", "start_chainage", "START_CHAINAGE", START_CH_ALIASES),
            ch("End Chainage", "end_chainage", "END_CHAINAGE", END_CH_ALIASES),
            s("Asset ID", "Asset ID", "Asset_ID", "AssetID"),
            s("Bridge Name", "Bridge Name", "Bridge_Name", "Name"),
            s("Bridge Type", "Bridge Type", "Bridge_Type", "Structure_Type", "Structure Type"),
            s("Road Name", "Road_Name", "Road Name"),
            s("XSP", "XSP", "Xsp", "Lane"),
            s("Owner", "Owner", "Owner Department"),
            d("Road Start Chainage", "Road Start Chainage", "m", "Road_Start_Chainage", "Road Start Chiange"),
            d("Road End Chainage", "Road End Chainage", "m", "Road_End_Chainage", "Road End Chiange"),
            d("Start Latitude", "Start Latitude", "deg", "Start_Latitude", "Start Lattitude"),
            d("Start Longitude", "Start Longitude", "deg", "Start_Longitude"),
            d("End Latitude", "End Lat", "deg", "End_Lat", "End Latitude", "End Lattitude"),
            d("End Longitude", "End Long", "deg", "End_Long", "End Longitude"),
            dt("Section Start Date", "Section_Start_Date", "Section Start Date"),
            s("Remarks", "Remarks", "Remark")));

        /* ---- R10 · Culverts (point) ---- */
        put("culvert", "default", List.of(
            sec("Section_Label", "section_label", SECTION_ALIASES),
            ch("Start_Chainage", "start_chainage", "CHAINAGE", POINT_CH_ALIASES),
            s("Asset_ID", "Asset_ID", "Asset ID", "AssetID"),
            s("Name", "Name", "Culvert Name", "Culvert_Name"),
            s("Culvert Type", "Culvert_Type", "Culvert Type", "Type"),
            s("Road_Name", "Road_Name", "Road Name"),
            s("Section_Code", "Section_Code", "Section Code", "Sectn_Code"),
            s("XSP", "XSP", "Xsp", "Lane"),
            s("Owner", "Owner", "Owner Department"),
            d("Road_Chainage", "Road_Chainage", "m", "Road Chainage", "Road Chiange"),
            d("Start_Latitude", "Start_Latitude", "deg", "Start Latitude", "Latitude"),
            d("Start_Longitude", "Start_Longitude", "deg", "Start Longitude", "Longitude"),
            dt("Section_Start_Date", "Section_Start_Date", "Section Start Date"),
            s("Remarks", "Remarks", "Remark")));

        /* ---- R12 · Road Furniture (point) ---- */
        put("furniture_point", "default", List.of(
            sec("Section_Label", "section_label", SECTION_ALIASES),
            ch("Chainage", "start_chainage", "CHAINAGE", POINT_CH_ALIASES),
            s("Asset_ID", "Asset_ID", "Asset ID", "AssetID"),
            s("Name", "Name", "Asset Name", "Asset_Name"),
            s("Road_Furniture_Type", "Road_Furniture_Type",
              "Road Furniture Type", "Furniture_Type", "Furniture Type"),
            s("Road_Name", "Road_Name", "Road Name"),
            s("Section_Code", "Section_Code", "Section Code"),
            s("XSP", "XSP", "Xsp", "Lane", "Side"),
            s("Owner", "Owner", "Owner Department"),
            d("Road Chainage", "Road Chainage", "m", "Road_Chainage", "Road Chiange"),
            d("Start_Latitude", "Start_Latitude", "deg", "Start Latitude", "Latitude"),
            d("Start_Longitude", "Start_Longitude", "deg", "Start Longitude", "Longitude"),
            dt("Section_Start_Date", "Section_Start_Date", "Section Start Date"),
            s("Remarks", "Remarks", "Remark")));

        /* ---- R16 · Road Furniture (line) ----
           "Lattitude" is the header the return actually ships, so it stays the
           storage key; the label spells it correctly. This is the case the
           name/storageKey split exists for. */
        put("furniture_line", "default", List.of(
            sec("Section Label", "section_label", SECTION_ALIASES),
            ch("Start Chainage", "start_chainage", "START_CHAINAGE", START_CH_ALIASES),
            ch("End Chainage", "end_chainage", "END_CHAINAGE", END_CH_ALIASES),
            s("Asset ID", "Asset ID", "Asset_ID", "AssetID"),
            s("Asset Name", "Asset Name", "Asset_Name", "Name"),
            s("Furniture Type", "Furniture_Type", "Furniture Type",
              "Road_Furniture_Type", "Road Furniture Type"),
            s("Road Name", "Road_Name", "Road Name"),
            s("XSP", "XSP", "Xsp", "Lane", "Side"),
            s("Owner", "Owner", "Owner Department"),
            d("Road Start Chainage", "Road Start Chainage", "m", "Road_Start_Chainage", "Road Start Chiange"),
            d("Road End Chainage", "Road End Chainage", "m", "Road_End_Chainage", "Road End Chiange"),
            d("Start Latitude", "Start Lattitude", "deg", "Start_Lattitude", "Start Latitude"),
            d("Start Longitude", "Start Longitude", "deg", "Start_Longitude"),
            d("End Latitude", "End Lattitude", "deg", "End_Lattitude", "End Latitude"),
            d("End Longitude", "End Longitude", "deg", "End_Longitude"),
            dt("Section Start Date", "Section Start Date", "Section_Start_Date"),
            s("Remarks", "Remarks", "Remark")));

        /* ---- R13 · Sub-Grade Soil ---- */
        put("subgrade", "default", List.of(
            sec("Section Label", "section_label", SECTION_ALIASES),
            ch("Chainage", "start_chainage", "CHAINAGE", POINT_CH_ALIASES),
            s("Xsp", "Xsp", "XSP", "Lane"),
            dt("Date", "Date", "Test Date", "Survey Date", "Date_of_Test"),
            s("Soil Type", "Soil Type", "Soil_Type"),
            d("CBR", "CBR", "%", "C.B.R", "Soaked CBR"),
            d("MDD", "MDD", "g/cc", "M.D.D", "Max Dry Density"),
            d("OMC", "OMC", "%", "O.M.C"),
            d("FDD", "FDD", "g/cc", "F.D.D", "Field Dry Density"),
            d("FMC", "FMC", "%", "F.M.C"),
            d("Doc", "Doc", "%", "DOC", "Degree of Compaction"),
            d("LL", "LL", "%", "Liquid Limit"),
            d("PL", "PL", "%", "Plastic Limit"),
            d("PI", "PI", "%", "Plasticity Index"),
            d("Gravel Content", "Gravel Content", "%", "Gravel_Content", "Gravel"),
            d("Sand Content", "Sand Content", "%", "Sand_Content", "Sand"),
            d("IS Sieve 20 mm", "Percentage_IS_Sieve_20mm", "%", "Percentage IS Sieve 20mm"),
            d("IS Sieve 10 mm", "Percentage_IS_Sieve_10mm", "%", "Percentage IS Sieve 10mm"),
            d("IS Sieve 4.75 mm", "Percentage_IS_Sieve_4.75mm", "%", "Percentage IS Sieve 4.75mm"),
            d("IS Sieve 2.36 mm", "Percentage_IS_Sieve_2.36mm", "%", "Percentage IS Sieve 2.36mm"),
            d("IS Sieve 0.425 mm", "Percentage_IS_Sieve_0.425mm", "%", "Percentage IS Sieve 0.425mm"),
            d("IS Sieve 0.075 mm", "Percentage_IS_Sieve_0.075mm", "%", "Percentage IS Sieve 0.075mm"),
            dt("Section Start Date", "Section Start Date", "Section_Start_Date"),
            s("Remarks", "Remarks", "Remark")));

        /* ---- R9 · Bituminous Core ----
           The return calls the section column "Section Label Code"; it is the
           same field as everywhere else, so it carries the shared alias set. */
        put("bituminous_core", "default", List.of(
            sec("Section Label Code", "section_label", SECTION_ALIASES),
            ch("Chainage", "start_chainage", "CHAINAGE", POINT_CH_ALIASES),
            s("XSP", "XSP", "Xsp", "Lane"),
            s("Core No", "Core No", "Core_No", "Core Number"),
            dt("Date", "Date", "Test Date", "Survey Date"),
            d("Observed Thickness of Wearing Course",
              "Observed Thickness of Wearing Course mm", "mm",
              "Observed_Thickness_of_Wearing_Course_mm", "Wearing Course Thickness"),
            d("Observed Thickness of Binder Course",
              "Observed Thickness of Binder Course mm", "mm",
              "Observed_Thickness_of_Binder_Course_mm", "Binder Course Thickness"),
            d("Total Observed Bituminous Layers Thickness",
              "Total Observed bituminous layers thickness mm", "mm",
              "Total_Observed_bituminous_layers_thickness_mm", "Total Bituminous Thickness"),
            d("Bulk Density of Wearing Course",
              "Bulk Density of Wearing Course gmcc", "g/cc",
              "Bulk_Density_of_Wearing_Course_gmcc"),
            d("Bulk Density of Binder Course",
              "Bulk Density of Binder Course gmcc", "g/cc",
              "Bulk_Density_of_Binder_Course_gmcc"),
            dt("Section Start Date", "Section Start Date", "Section_Start_Date"),
            s("Remarks", "Remarks", "Remark")));

        /* ---- R8 · Pavement Crust ---- */
        put("pavement_crust", "default", List.of(
            sec("Section Label", "section_label", SECTION_ALIASES),
            ch("Chainage", "start_chainage", "CHAINAGE", POINT_CH_ALIASES),
            s("XSP", "XSP", "Xsp", "Lane"),
            dt("Date", "Date", "Test Date", "Survey Date"),
            d("Surface Thickness", "Surface Thickness", "mm", "Surface_Thickness"),
            s("Surface Type", "Surface Type", "Surface_Type"),
            d("Base Thickness", "Base Thickness", "mm", "Base_Thickness"),
            s("Base Type", "Base Type", "Base_Type"),
            d("Sub Base Thickness", "Sub Base Thickness", "mm", "Sub_Base_Thickness"),
            s("Sub Base Type", "Sub Base Type", "Sub_Base_Type"),
            d("Sub Grade CBR", "Sub Grade CBR", "%", "Sub_Grade_CBR", "Subgrade CBR"),
            s("Sub Grade Soil Type", "Sub Grade Soil Type",
              "Sub_Grade_Soil_Type", "Subgrade Soil Type"),
            d("Embankment Height", "Embankment Height", "m", "Embankment_Height"),
            dt("Section Start Date", "Section Start Date", "Section_Start_Date"),
            s("Remarks", "Remarks", "Remark")));

        /* ---- Traffic stations ----
           Column-backed: these seven ARE the columns of traffic_stations, so
           they are listed under those names. The station's position is computed
           live from section + chainage — lat/lng are stored but never placed
           from — which is why the placement roles sit on the first two. */
        put("traffic_stations", "default", List.of(
            sec("Section Label", "section", SECTION_ALIASES),
            ch("Chainage", "chainage", "CHAINAGE", POINT_CH_ALIASES),
            s("Station Name", "name", "STATION_NAME", "Station_Name", "Station"),
            s("Road Name", "road", "Road_Name", "Road", "Description"),
            s("XSP", "xsp", "Xsp", "Xsp Code", "XSP_Code", "Lane"),
            d("Latitude", "lat", "deg", "Latitude", "Lat"),
            d("Longitude", "lng", "deg", "Longitude", "Long", "Lon")));

        /* ---- Traffic counts ----
           The one dataset here that describes an UPLOAD rather than storage:
           traffic_counts is (name, data jsonb) and the blob's shape is the
           viewer's, not a column list. What is worth declaring is the count
           return's columns, which is what the import screen maps onto — the same
           contract ImportTemplateController's traffic_counts template carries. */
        put("traffic_stations", "counts", List.of(
            new Attr("Station Name", "name", "STRING", null, true, "NONE",
                     List.of("STATION_NAME", "Station_Name", "Station")),
            dt("Date", "date", "DATE", "Survey Date", "Count Date"),
            s("Time", "time", "TIME", "Slot"),
            s("Direction", "direction", "DIRECTION", "Dir"),
            s("Vehicle Class", "vehicle_class", "VEHICLE_CLASS", "Vehicle Type", "Class"),
            i("Count", "count", "count", "COUNT", "Volume", "Nos")));

        /* ---- Boundaries ----
           One GeoJSON document per type, so there are exactly two fields. */
        for (String b : new String[]{"boundary_district", "boundary_constituency"}) {
            put(b, "default", List.of(
                new Attr("Boundary Type", "type", "STRING", null, true, "NONE", List.of()),
                s("Name", "name", "NAME", "District", "Constituency")));
        }
    }

    /* ------------------------------------------------------------------
       Road network labels
       ------------------------------------------------------------------ */

    /**
     * Display labels for the road shapefile's DBF fields.
     *
     * Not a full catalogue entry: {@code roads} and {@code full_road_network} are
     * whatever the last shapefile import created, and DBF truncates every field
     * name to 10 characters, so the column list must stay discovered. This only
     * replaces the mechanical de-prettified guess ("Rd Str Cha") with the field's
     * real meaning wherever the truncated name is recognised.
     */
    private static final Map<String, String> ROAD_LABELS = new LinkedHashMap<>();

    static {
        ROAD_LABELS.put("section_la", "Section Label");
        ROAD_LABELS.put("road_name", "Road Name");
        ROAD_LABELS.put("road_num", "Road Number");
        ROAD_LABELS.put("road_class", "Road Class");
        /* "Lane Type" and not "Road Type": the column holds SLR/TLR/FLR, which is
           how many lanes the road carries, not what kind of road it is (that is
           Road_Class). The viewer's road card has always labelled it this way and
           the catalogue keeps its meaning rather than echoing the column name. */
        ROAD_LABELS.put("road_type", "Lane Type");
        ROAD_LABELS.put("sectn_code", "Section Code");
        ROAD_LABELS.put("dig_l", "Digitised Length");
        ROAD_LABELS.put("measrd_len", "Measured Length");
        ROAD_LABELS.put("start_chai", "Start Chainage");
        ROAD_LABELS.put("end_chaina", "End Chainage");
        ROAD_LABELS.put("rd_str_cha", "Road Start Chainage");
        ROAD_LABELS.put("rd_end_cha", "Road End Chainage");
        ROAD_LABELS.put("rd_str_loc", "Road Start Location");
        ROAD_LABELS.put("rd_end_loc", "Road End Location");
        ROAD_LABELS.put("start_date", "Section Start Date");
        ROAD_LABELS.put("owner", "Owner");
        ROAD_LABELS.put("current_ow", "Current Owner");
        ROAD_LABELS.put("single_du", "Carriageway");
        ROAD_LABELS.put("environmen", "Environment");
        ROAD_LABELS.put("pwd_sec", "PWD Section");
        ROAD_LABELS.put("crn", "CRN");
        ROAD_LABELS.put("pavement_w", "Pavement Width");
        ROAD_LABELS.put("shoulder_w", "Shoulder Width");
        ROAD_LABELS.put("cons_type", "Construction Type");
        ROAD_LABELS.put("consistenc", "Consistency");
        ROAD_LABELS.put("surface_ty", "Surface Type");
        ROAD_LABELS.put("district", "District");
        ROAD_LABELS.put("terrain", "Terrain");
        ROAD_LABELS.put("region", "Region");
    }

    /** Units for the road fields that have one; everything else is unitless text. */
    private static final Map<String, String> ROAD_UNITS = Map.of(
        "dig_l", "m", "measrd_len", "m", "start_chai", "m", "end_chaina", "m",
        "rd_str_cha", "m", "rd_end_cha", "m", "pavement_w", "m", "shoulder_w", "m");

    /** The declared label for a road column, or null to keep the discovered one. */
    static String roadLabel(String column) {
        return column == null ? null : ROAD_LABELS.get(column.toLowerCase(Locale.ROOT));
    }

    static String roadUnit(String column) {
        return column == null ? null : ROAD_UNITS.get(column.toLowerCase(Locale.ROOT));
    }

    /* ------------------------------------------------------------------
       Lookup
       ------------------------------------------------------------------ */

    /** The declared attributes of one layer dataset, or an empty list. */
    static List<Attr> forLayer(String layerKey, String dataset) {
        Map<String, List<Attr>> byDataset = BY_LAYER.get(layerKey);
        if (byDataset == null) return List.of();
        return byDataset.getOrDefault(dataset == null ? "default" : dataset, List.of());
    }

    /** Every dataset key this layer declares, in order. */
    static List<String> datasetsOf(String layerKey) {
        Map<String, List<Attr>> byDataset = BY_LAYER.get(layerKey);
        return byDataset == null ? List.of() : List.copyOf(byDataset.keySet());
    }

    static boolean declares(String layerKey) {
        return BY_LAYER.containsKey(layerKey);
    }
}
