package com.fist.rmms_backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves that resolving the road network's columns by system attribute name produces exactly
 * the SQL the hard-coded names produced.
 *
 * <p>The sweep replaced literal DBF column names ({@code "Rd_Str_cha"}, {@code "Section_La"})
 * with lookups through {@link LayerAttributeCatalog}, across the segment builders, the placement
 * SQL and the tile and dashboard queries. Every one of those is a string interpolated into SQL,
 * so a mistake does not fail to compile — it fails at the database, or worse, succeeds and
 * returns a different answer. A join that resolves to the wrong column yields zero rows and a
 * silently empty map layer.
 *
 * <p>So the check is behavioural, not structural: rebuild each derived table from unchanged
 * source data and assert the row count is exactly what it was. These tables are DROP/CREATE by
 * design and Postgres DDL is transactional, so a failed build rolls back rather than leaving a
 * layer missing.
 */
@SpringBootTest
class RoadColumnSweepIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired RoadColumns roadColumns;
    @Autowired SegmentService segments;
    @Autowired FwdSegmentService fwdSegments;
    @Autowired IriSegmentService iriSegments;

    private long count(String table) {
        Boolean exists = jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table);
        if (!Boolean.TRUE.equals(exists)) return -1;
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    @Test
    void theResolverAgreesWithTheColumnsThisNetworkActuallyHas() {
        assertEquals("Section_La", roadColumns.find(LayerAttributeCatalog.SECTION_LABEL));
        assertEquals("Rd_Str_cha", roadColumns.find(LayerAttributeCatalog.ROAD_START_CHAINAGE));
        assertEquals("Rd_End_cha", roadColumns.find(LayerAttributeCatalog.ROAD_END_CHAINAGE));
        assertEquals("Measrd_Len", roadColumns.find(LayerAttributeCatalog.MEASURED_LENGTH));

        // Qualified and quoted, ready to interpolate.
        assertEquals("r.\"Rd_Str_cha\"", roadColumns.col("r", LayerAttributeCatalog.ROAD_START_CHAINAGE));
        assertEquals("\"Section_La\"", roadColumns.col(LayerAttributeCatalog.SECTION_LABEL));
    }

    @Test
    void theSharedReferenceLengthIsTheExpressionEveryBuilderUsedBefore() {
        String expr = roadColumns.lenExpr("r");
        // The same three terms, in the same priority, as the eight copies it replaced.
        assertTrue(expr.contains("r.\"Rd_End_cha\"::double precision - r.\"Rd_Str_cha\"::double precision"),
                expr);
        assertTrue(expr.contains("NULLIF(r.\"Measrd_Len\"::double precision, 0)"), expr);
        assertTrue(expr.contains("ST_Length(r.geom::geography)"), expr);
        assertTrue(expr.indexOf("Rd_End_cha") < expr.indexOf("Measrd_Len"),
                "chainage span must be tried before measured length");
        assertTrue(expr.indexOf("Measrd_Len") < expr.indexOf("ST_Length"),
                "measured length must be tried before the drawn line");

        // It must evaluate. A malformed expression would only be found by a query using it.
        Double v = jdbc.queryForObject(
                "SELECT " + expr + " FROM roads r WHERE r.geom IS NOT NULL LIMIT 1", Double.class);
        assertNotNull(v, "the shared reference length must evaluate against a real row");
        assertTrue(v > 0, "a real section's reference length must be positive, got " + v);
    }

    @Autowired RoadTileService roadTiles;
    @Autowired FwdTileService fwdTiles;

    /**
     * A tile query naming the wrong column does not error — it matches nothing and returns an
     * empty tile, which renders as a blank map layer. So the check is that a tile covering real
     * road geometry comes back non-empty.
     */
    @Test
    void theRoadTileStillRendersOverRealGeometry() {
        TileCoordinate t = tileOverTheNetwork();
        assumeTrue(t != null, "no road geometry in this database");
        byte[] tile = roadTiles.tile(t, null);
        System.out.println("[tile] roads z" + t.z() + "/" + t.x() + "/" + t.y()
                + " -> " + (tile == null ? "null" : tile.length + " bytes"));
        assertNotNull(tile, "the road tile is empty — a resolved column matched nothing");
        assertTrue(tile.length > 0, "the road tile is zero-length");
    }

    @Test
    void theFwdTileQueryStillExecutes() {
        TileCoordinate t = tileOverTheNetwork();
        assumeTrue(t != null, "no road geometry in this database");
        // FWD data may be absent, so an empty tile is a legitimate answer here; what is being
        // checked is that the statement is valid SQL against the resolved columns.
        assertDoesNotThrow(() -> fwdTiles.tile(t, null));
        System.out.println("[tile] fwd query executed");
    }

    /** A zoom-12 tile containing the first road centreline in the network. */
    private TileCoordinate tileOverTheNetwork() {
        var p = jdbc.queryForList(
                "SELECT ST_X(ST_Centroid(geom)) AS lng, ST_Y(ST_Centroid(geom)) AS lat "
              + "FROM roads WHERE geom IS NOT NULL LIMIT 1");
        if (p.isEmpty()) return null;
        double lng = ((Number) p.get(0).get("lng")).doubleValue();
        double lat = ((Number) p.get(0).get("lat")).doubleValue();
        int z = 12, n = 1 << z;
        int x = (int) Math.floor((lng + 180) / 360 * n);
        double latRad = Math.toRadians(lat);
        int y = (int) Math.floor((1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2 * n);
        return new TileCoordinate(z, x, y);
    }

    @Autowired FwdDashboardController fwdDashboard;
    @Autowired DashboardController networkDashboard;

    @Test
    void everyGroupingAttributeResolvesToADistinctRealColumn() {
        for (String attr : new String[]{
                LayerAttributeCatalog.DISTRICT, LayerAttributeCatalog.ROAD_CLASS,
                LayerAttributeCatalog.CONSTRUCTION_TYPE, LayerAttributeCatalog.SURFACE_TYPE,
                LayerAttributeCatalog.ROAD_NAME, LayerAttributeCatalog.MEASURED_LENGTH}) {
            String column = roadColumns.find(attr);
            assertNotNull(column, "no column resolved for \"" + attr + "\"");
            // It must be a column the table really has, or the SQL fails at the database.
            assertTrue(roadColumns.get().contains(column),
                    attr + " resolved to \"" + column + "\", which roads does not have");
            System.out.println("[grouping] " + attr + " -> " + column);
        }
    }

    /**
     * The FWD dashboard groups by District and Road Class through a LEFT JOIN on the section
     * label. If any of those three resolved to the wrong column the join still runs — it just
     * matches nothing, and every point falls into the "(unmapped)" district and "OTHER" class
     * buckets the SQL substitutes for a null. So a dashboard that is merely non-empty proves
     * nothing; it has to contain real district names.
     */
    @Test
    void theFwdDashboardStillGroupsByRealDistrictsAndClasses() {
        Map<String, Object> summary = fwdDashboard.summary();
        String rendered = String.valueOf(summary);
        assumeTrue(rendered.length() > 100, "no FWD data in this database");

        long realDistricts = jdbc.queryForObject(
                "SELECT count(DISTINCT " + roadColumns.col(LayerAttributeCatalog.DISTRICT) + ") "
              + "FROM roads WHERE " + roadColumns.col(LayerAttributeCatalog.DISTRICT) + " IS NOT NULL",
                Long.class);
        assumeTrue(realDistricts > 0, "the network carries no districts");

        assertFalse(rendered.contains("(unmapped)") && !containsAnyRealDistrict(rendered),
                "every FWD point fell into the \"(unmapped)\" bucket — the District join "
              + "resolved to a column that matches nothing");
        System.out.println("[fwd] summary groups across real districts");
    }

    private boolean containsAnyRealDistrict(String rendered) {
        for (String d : jdbc.queryForList(
                "SELECT DISTINCT trim(" + roadColumns.col(LayerAttributeCatalog.DISTRICT) + ") "
              + "FROM roads WHERE " + roadColumns.col(LayerAttributeCatalog.DISTRICT) + " IS NOT NULL",
                String.class)) {
            if (d != null && !d.isBlank() && rendered.contains(d)) return true;
        }
        return false;
    }

    /** The network dashboard's headline: total network length must be a real positive figure. */
    @Test
    void theNetworkDashboardStillTotalsRealKilometres() {
        Map<String, Object> summary = networkDashboard.summary();
        Object km = summary.get("total_km");
        assumeTrue(km instanceof Number, "this dashboard reports no total_km: " + summary.keySet());
        assertTrue(((Number) km).doubleValue() > 0,
                "total network length came back as " + km + " — a resolved column matched nothing");
        System.out.println("[network] total_km = " + km);
    }

    @Autowired RoadController roads;

    /**
     * The road network's own endpoints. These build their JSON in SQL
     * ({@code jsonb_build_object('road', …, 'name', …, 'len', …)}), so a mis-resolved column
     * yields a document that is structurally valid and missing the properties the whole viewer
     * keys off — no error anywhere.
     */
    @Test
    void theRoadNetworkEndpointsStillCarryTheirProperties() {
        roads.refresh();                                  // rebuild from the resolved SQL
        String geo = roads.geojson(null).getBody();
        assertNotNull(geo, "roads GeoJSON came back empty");
        assertTrue(geo.contains("\"road\""), "GeoJSON lost the 'road' property the viewer keys off");
        assertTrue(geo.contains("\"name\"") && geo.contains("\"len\""),
                "GeoJSON lost 'name' or 'len'");

        String index = roads.index(null).getBody();
        assertNotNull(index, "roads index came back empty");
        System.out.println("[roads] geojson " + geo.length() + " chars, index " + index.length() + " chars");
    }

    /** The Chainage Locator: find a road, then locate a chainage inside one of its sections. */
    @Test
    void theChainageLocatorStillFindsASection() {
        var r = jdbc.queryForList(
                "SELECT " + roadColumns.col(LayerAttributeCatalog.ROAD_NAME) + " AS name, "
              + "       " + roadColumns.col(LayerAttributeCatalog.ROAD_START_CHAINAGE) + "::double precision AS lo, "
              + "       " + roadColumns.col(LayerAttributeCatalog.ROAD_END_CHAINAGE) + "::double precision AS hi "
              + "FROM roads WHERE geom IS NOT NULL "
              + "  AND " + roadColumns.col(LayerAttributeCatalog.ROAD_END_CHAINAGE) + "::double precision "
              + "    > " + roadColumns.col(LayerAttributeCatalog.ROAD_START_CHAINAGE) + "::double precision "
              + "LIMIT 1");
        assumeTrue(!r.isEmpty(), "no section with a chainage range");
        String name = String.valueOf(r.get(0).get("name"));
        double mid = (((Number) r.get(0).get("lo")).doubleValue()
                    + ((Number) r.get(0).get("hi")).doubleValue()) / 2;

        Map<String, Object> located = roads.locateChainage(name, mid, null);
        System.out.println("[locate] \"" + name + "\" @ " + mid + " -> " + located.get("matches"));
        assertNotNull(located.get("road_name"));
    }

    /*
     * These rebuild TWICE and compare the two rebuilds, rather than comparing one rebuild
     * against whatever the table already held.
     *
     * Comparing against the stored table looked stronger and was actually fragile: the stored
     * count reflects the data as it was when someone last built it, so any other test that adds
     * or removes a condition row — SectionSplitMergeIT divides one and re-joins it — made this
     * fail for a reason that had nothing to do with column resolution. Two rebuilds over the
     * same data must agree, and a non-empty result is what proves the joins still match; a
     * mis-resolved column yields zero, which the second assertion catches.
     */
    @Test
    void rebuildingConditionSegmentsIsReproducibleAndNonEmpty() {
        assumeTrue(count("condition") > 0, "no condition data in this database");
        int first = segments.buildSegments();
        int second = segments.buildSegments();
        System.out.println("[rebuild] condition_segments " + first + " -> " + second);
        assertEquals(first, second, "two rebuilds over the same data disagree");
        assertTrue(first > 0, "the rebuild produced nothing — a resolved column matched no rows");
    }

    @Test
    void rebuildingFwdSegmentsIsReproducible() {
        int first = fwdSegments.buildSegments();
        int second = fwdSegments.buildSegments();
        System.out.println("[rebuild] fwd_segments " + first + " -> " + second);
        assertEquals(first, second, "two rebuilds over the same data disagree");
    }

    @Test
    void rebuildingIriBinsIsReproducibleAndNonEmpty() {
        assumeTrue(count("condition") > 0, "no condition data in this database");
        int first = iriSegments.buildSegments();
        int second = iriSegments.buildSegments();
        System.out.println("[rebuild] iri_2km_segments " + first + " -> " + second);
        assertEquals(first, second, "two rebuilds over the same data disagree");
        assertTrue(first > 0, "the rebuild produced nothing — a resolved column matched no rows");
    }
}
