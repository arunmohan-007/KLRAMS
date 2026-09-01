package com.fist.rmms_backend;

import java.util.Map;

/**
 * Names for the GeoTIFF GeoKey codes worth showing a surveyor.
 *
 * <h2>Why a table and not a lookup</h2>
 * These are EPSG codes, and the authoritative answer lives in the EPSG registry —
 * tens of thousands of entries, a database in its own right, normally reached
 * through PROJ. This module deliberately has neither (see {@link DroneCrs}), so
 * instead of pretending to resolve everything it names the codes a drone survey
 * actually produces and shows the raw code for anything else. "EPSG:6326" is a
 * worse answer than "WGS 84" but a much better one than silence, and a wrong
 * guess would be worse than both.
 *
 * <p>The file's own citation strings are carried through untouched alongside these,
 * because an exporter usually writes the datum and geoid there in words — that is
 * where "WGS 84 / UTM zone 43N + EGM96 geoid" comes from when it appears.
 */
final class GeoKeyNames {

    private GeoKeyNames() {
    }

    /** GeogGeodeticDatumGeoKey (2050). */
    private static final Map<Integer, String> DATUMS = Map.ofEntries(
            Map.entry(6326, "WGS 84"),
            Map.entry(6322, "WGS 72"),
            Map.entry(6269, "NAD83"),
            Map.entry(6267, "NAD27"),
            Map.entry(6258, "ETRS89"),
            Map.entry(6277, "OSGB 1936"),
            Map.entry(6015, "Everest 1830 (1937 Adjustment)"),
            Map.entry(6042, "Everest 1830 Modified"),
            Map.entry(6239, "Indian 1954"),
            Map.entry(6240, "Indian 1975"),
            Map.entry(6301, "Tokyo"),
            Map.entry(6202, "Australian Geodetic Datum 1966"),
            Map.entry(6283, "GDA94"));

    /** GeogEllipsoidGeoKey (2056). */
    private static final Map<Integer, String> ELLIPSOIDS = Map.ofEntries(
            Map.entry(7030, "WGS 84"),
            Map.entry(7043, "WGS 72"),
            Map.entry(7019, "GRS 1980"),
            Map.entry(7008, "Clarke 1866"),
            Map.entry(7022, "International 1924"),
            Map.entry(7001, "Airy 1830"),
            Map.entry(7015, "Everest 1830 (1937 Adjustment)"),
            Map.entry(7044, "Everest 1830 (1962 Definition)"),
            Map.entry(7059, "Everest 1830 (1975 Definition)"));

    /** ProjCoordTransGeoKey (3075) — the projection method itself. */
    private static final Map<Integer, String> PROJECTIONS = Map.ofEntries(
            Map.entry(1, "Transverse Mercator"),
            Map.entry(2, "Transverse Mercator (Modified Alaska)"),
            Map.entry(3, "Oblique Mercator"),
            Map.entry(7, "Mercator"),
            Map.entry(8, "Lambert Conformal Conic (2SP)"),
            Map.entry(9, "Lambert Conformal Conic (1SP)"),
            Map.entry(10, "Lambert Azimuthal Equal Area"),
            Map.entry(11, "Albers Equal Area"),
            Map.entry(12, "Azimuthal Equidistant"),
            Map.entry(14, "Stereographic"),
            Map.entry(15, "Polar Stereographic"),
            Map.entry(16, "Oblique Stereographic"),
            Map.entry(17, "Equirectangular"),
            Map.entry(18, "Cassini-Soldner"),
            Map.entry(22, "Polyconic"),
            Map.entry(24, "Sinusoidal"),
            Map.entry(27, "Transverse Mercator (South Oriented)"));

    /** VerticalCSTypeGeoKey (4096) — this is where a geoid model shows up. */
    private static final Map<Integer, String> VERTICAL_CRS = Map.ofEntries(
            Map.entry(5773, "EGM96 geoid height"),
            Map.entry(3855, "EGM2008 geoid height"),
            Map.entry(5703, "NAVD88 height"),
            Map.entry(5714, "Mean sea level height"),
            Map.entry(5715, "Mean sea level depth"),
            Map.entry(5701, "Ordnance Datum Newlyn height"),
            Map.entry(4979, "WGS 84 ellipsoidal height"));

    /** VerticalDatumGeoKey (4098). */
    private static final Map<Integer, String> VERTICAL_DATUMS = Map.ofEntries(
            Map.entry(5100, "Mean Sea Level"),
            Map.entry(5103, "NAVD88"),
            Map.entry(5171, "EGM96 geoid"),
            Map.entry(5215, "EVRF2000"));

    private static final Map<Integer, String> LINEAR_UNITS = Map.of(
            9001, "metre",
            9002, "foot",
            9003, "US survey foot",
            9005, "Clarke's foot",
            9014, "fathom");

    private static final Map<Integer, String> ANGULAR_UNITS = Map.of(
            9101, "radian",
            9102, "degree",
            9105, "grad",
            9104, "arc-second");

    /** TIFF Compression (259). */
    private static final Map<Integer, String> COMPRESSION = Map.ofEntries(
            Map.entry(1, "none"),
            Map.entry(5, "LZW"),
            Map.entry(6, "JPEG (old style)"),
            Map.entry(7, "JPEG"),
            Map.entry(8, "Deflate"),
            Map.entry(32773, "PackBits"),
            Map.entry(32946, "Deflate"),
            Map.entry(34712, "JPEG 2000"),
            Map.entry(34887, "LERC"),
            Map.entry(50000, "Zstandard"),
            Map.entry(50001, "WebP"));

    static String datum(int code) {
        return name(DATUMS, code);
    }

    static String ellipsoid(int code) {
        return name(ELLIPSOIDS, code);
    }

    static String projection(int code) {
        return name(PROJECTIONS, code);
    }

    static String verticalCrs(int code) {
        return name(VERTICAL_CRS, code);
    }

    static String verticalDatum(int code) {
        return name(VERTICAL_DATUMS, code);
    }

    static String linearUnit(int code) {
        return name(LINEAR_UNITS, code);
    }

    static String angularUnit(int code) {
        return name(ANGULAR_UNITS, code);
    }

    static String compression(int code) {
        String s = COMPRESSION.get(code);
        return s != null ? s : "code " + code;
    }

    /**
     * A code's name, or the code itself when it is not in the table.
     *
     * <p>32767 is GeoTIFF's "user-defined", which means the file carries the
     * definition inline rather than by reference — worth saying so, because it is
     * the usual reason a CRS will not resolve elsewhere.
     */
    private static String name(Map<Integer, String> table, int code) {
        if (code == 0) return null;
        if (code == 32767) return "user-defined";
        String s = table.get(code);
        return s != null ? s : "EPSG:" + code;
    }
}
