package com.fist.rmms_backend;

/**
 * The coordinate transforms the Drone module needs, and nothing else.
 *
 * <p>KLRAMS has no projection library — every other layer is already WGS84 in
 * PostGIS, so PostGIS does the reprojecting. Drone rasters arrive on disk instead
 * of in the database, so the pixel-to-lon/lat conversion has to happen in Java.
 * Rather than pull in a full CRS stack for this one module, this covers exactly
 * the projections drone deliverables for Kerala actually come in:
 *
 * <ul>
 *   <li><b>EPSG:4326</b> — degrees, identity.</li>
 *   <li><b>EPSG:326xx / 327xx</b> — WGS84 UTM. Kerala is zone 43N (EPSG:32643),
 *       which is what Pix4D, Agisoft and ODM emit by default here.</li>
 *   <li><b>EPSG:3857</b> — Web Mercator, occasionally used for web deliverables.</li>
 * </ul>
 *
 * <p>Anything else is rejected at upload with a message naming the EPSG code,
 * which is far more useful than silently placing an image in the wrong country.
 * Formulas are Snyder's standard Transverse Mercator series (USGS PP 1395),
 * accurate to a few millimetres within a UTM zone — orders of magnitude finer
 * than drone ground sampling distance.
 */
final class DroneCrs {

    private static final double A = 6378137.0;                 // WGS84 semi-major axis
    private static final double F = 1.0 / 298.257223563;       // WGS84 flattening
    private static final double E2 = F * (2 - F);              // first eccentricity squared
    private static final double EP2 = E2 / (1 - E2);           // second eccentricity squared
    private static final double K0 = 0.9996;                   // UTM scale factor
    private static final double FALSE_EASTING = 500000.0;
    private static final double FALSE_NORTHING = 10000000.0;   // southern hemisphere only

    /** 4326, 3857, or a WGS84 UTM code. */
    private final int epsg;
    private final int utmZone;      // 0 when not UTM
    private final boolean south;

    private DroneCrs(int epsg, int utmZone, boolean south) {
        this.epsg = epsg;
        this.utmZone = utmZone;
        this.south = south;
    }

    /**
     * @throws IllegalArgumentException when the code is outside the supported set —
     *         the message is shown to the uploader verbatim.
     */
    static DroneCrs of(int epsg) {
        if (epsg == 4326 || epsg == 4327 || epsg == 4979) return new DroneCrs(4326, 0, false);
        if (epsg == 3857 || epsg == 900913 || epsg == 3785) return new DroneCrs(3857, 0, false);
        if (epsg >= 32601 && epsg <= 32660) return new DroneCrs(epsg, epsg - 32600, false);
        if (epsg >= 32701 && epsg <= 32760) return new DroneCrs(epsg, epsg - 32700, true);
        throw new IllegalArgumentException(
                "EPSG:" + epsg + " is not supported by the Drone module. Re-export the raster in "
              + "WGS84 geographic (EPSG:4326) or WGS84 / UTM (Kerala is EPSG:32643).");
    }

    int epsg() {
        return epsg;
    }

    String label() {
        if (epsg == 4326) return "EPSG:4326 — WGS 84 (geographic)";
        if (epsg == 3857) return "EPSG:3857 — WGS 84 / Pseudo-Mercator";
        return "EPSG:" + epsg + " — WGS 84 / UTM zone " + utmZone + (south ? "S" : "N");
    }

    /** True when a unit of this CRS is a degree, so a pixel size is not a metre count. */
    boolean isGeographic() {
        return epsg == 4326;
    }

    /* ------------------------------------------------------------------
       Projected <-> WGS84
       ------------------------------------------------------------------ */

    /** @return {@code {lon, lat}} in degrees for a coordinate in this CRS. */
    double[] toWgs84(double x, double y) {
        if (epsg == 4326) return new double[]{x, y};
        if (epsg == 3857) {
            double lon = Math.toDegrees(x / A);
            double lat = Math.toDegrees(2 * Math.atan(Math.exp(y / A)) - Math.PI / 2);
            return new double[]{lon, lat};
        }
        return utmToWgs84(x, y);
    }

    /** @return {@code {x, y}} in this CRS for a WGS84 degree pair. */
    double[] fromWgs84(double lon, double lat) {
        if (epsg == 4326) return new double[]{lon, lat};
        if (epsg == 3857) {
            double r = Math.toRadians(Math.max(-89.9, Math.min(89.9, lat)));
            return new double[]{A * Math.toRadians(lon), A * Math.log(Math.tan(Math.PI / 4 + r / 2))};
        }
        return wgs84ToUtm(lon, lat);
    }

    private double[] utmToWgs84(double easting, double northing) {
        double x = easting - FALSE_EASTING;
        double y = south ? northing - FALSE_NORTHING : northing;

        double m = y / K0;
        double mu = m / (A * (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256));
        double e1 = (1 - Math.sqrt(1 - E2)) / (1 + Math.sqrt(1 - E2));
        double e1_2 = e1 * e1, e1_3 = e1_2 * e1, e1_4 = e1_3 * e1;

        double phi1 = mu
                + (3 * e1 / 2 - 27 * e1_3 / 32) * Math.sin(2 * mu)
                + (21 * e1_2 / 16 - 55 * e1_4 / 32) * Math.sin(4 * mu)
                + (151 * e1_3 / 96) * Math.sin(6 * mu)
                + (1097 * e1_4 / 512) * Math.sin(8 * mu);

        double sinPhi1 = Math.sin(phi1), cosPhi1 = Math.cos(phi1), tanPhi1 = Math.tan(phi1);
        double n1 = A / Math.sqrt(1 - E2 * sinPhi1 * sinPhi1);
        double t1 = tanPhi1 * tanPhi1;
        double c1 = EP2 * cosPhi1 * cosPhi1;
        double r1 = A * (1 - E2) / Math.pow(1 - E2 * sinPhi1 * sinPhi1, 1.5);
        double d = x / (n1 * K0);
        double d2 = d * d, d3 = d2 * d, d4 = d3 * d, d5 = d4 * d, d6 = d5 * d;

        double lat = phi1 - (n1 * tanPhi1 / r1) * (d2 / 2
                - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * d4 / 24
                + (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) * d6 / 720);
        double lon = Math.toRadians(centralMeridian()) + (d
                - (1 + 2 * t1 + c1) * d3 / 6
                + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) * d5 / 120) / cosPhi1;

        return new double[]{Math.toDegrees(lon), Math.toDegrees(lat)};
    }

    private double[] wgs84ToUtm(double lonDeg, double latDeg) {
        double phi = Math.toRadians(latDeg);
        double lambda = Math.toRadians(lonDeg);
        double lambda0 = Math.toRadians(centralMeridian());

        double sinPhi = Math.sin(phi), cosPhi = Math.cos(phi), tanPhi = Math.tan(phi);
        double n = A / Math.sqrt(1 - E2 * sinPhi * sinPhi);
        double t = tanPhi * tanPhi;
        double c = EP2 * cosPhi * cosPhi;
        double a1 = cosPhi * (lambda - lambda0);
        double a2 = a1 * a1, a3 = a2 * a1, a4 = a3 * a1, a5 = a4 * a1, a6 = a5 * a1;

        double m = A * ((1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256) * phi
                - (3 * E2 / 8 + 3 * E2 * E2 / 32 + 45 * E2 * E2 * E2 / 1024) * Math.sin(2 * phi)
                + (15 * E2 * E2 / 256 + 45 * E2 * E2 * E2 / 1024) * Math.sin(4 * phi)
                - (35 * E2 * E2 * E2 / 3072) * Math.sin(6 * phi));

        double easting = K0 * n * (a1 + (1 - t + c) * a3 / 6
                + (5 - 18 * t + t * t + 72 * c - 58 * EP2) * a5 / 120) + FALSE_EASTING;
        double northing = K0 * (m + n * tanPhi * (a2 / 2
                + (5 - t + 9 * c + 4 * c * c) * a4 / 24
                + (61 - 58 * t + t * t + 600 * c - 330 * EP2) * a6 / 720));
        if (south) northing += FALSE_NORTHING;

        return new double[]{easting, northing};
    }

    private double centralMeridian() {
        return (utmZone - 1) * 6 - 180 + 3;
    }
}
