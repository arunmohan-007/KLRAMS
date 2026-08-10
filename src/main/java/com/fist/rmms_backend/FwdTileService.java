package com.fist.rmms_backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Builds Mapbox Vector Tiles for the FWD deflection survey.
 *
 * <p>Separate from {@link AssetTileService} because FWD is the one asset type whose stored geometry
 * is NOT what the map draws. {@code AssetController.upload()} files FWD under its point types, so
 * {@code road_assets.geom} is a single {@code ST_LineInterpolatePoint} at the START chainage — but
 * every FWD row is really a chainage RANGE carrying D0..Dn deflections, and the viewer draws it as a
 * STRETCH it re-derives client-side. Doing that in the browser is what forced the whole 4 MB road
 * network to be downloaded before FWD could appear; here the stretch is cut in SQL, so a tile
 * arrives ready to draw.
 *
 * <p>Not built on {@code fwd_segments} — {@link FwdSegmentService} materialises the same stretches,
 * but only after someone runs Build in the Data Console, and that table is empty on databases where
 * nobody has. Reading {@code road_assets} directly means FWD tiles work wherever the GeoJSON path
 * works today, with no build step and nothing new to keep in sync.
 *
 * <p>The linear reference is the project-standard one (CLAUDE.md): {@code Rd_End_cha - Rd_Str_cha},
 * falling back to {@code Measrd_Len}, then geodesic length — the same expression
 * {@link FwdSegmentService} and every asset placement uses.
 */
@Service
public class FwdTileService {

    /** Same MVT layer name the other asset tiles use, so the client binds one {@code source-layer}. */
    static final String LAYER_NAME = AssetTileService.LAYER_NAME;

    private final JdbcTemplate jdbc;
    private final SurveyPeriodService periods;

    private final int extent;
    private final int buffer;
    private final int maxZoom;

    public FwdTileService(JdbcTemplate jdbc,
                          SurveyPeriodService periods,
                          @Value("${app.tile.extent:4096}") int extent,
                          @Value("${app.tile.buffer:64}") int buffer,
                          @Value("${app.tile.max-zoom:20}") int maxZoom) {
        this.jdbc = jdbc;
        this.periods = periods;
        this.extent = extent;
        this.buffer = buffer;
        this.maxZoom = maxZoom;
    }

    int maxZoom() {
        return maxZoom;
    }

    /** One tile's worth of FWD, or {@code null} when there is nothing to draw. */
    byte[] tile(TileCoordinate t, Integer requestedPeriodId) {
        Boolean built = jdbc.queryForObject("SELECT to_regclass('road_assets') IS NOT NULL", Boolean.class);
        if (!Boolean.TRUE.equals(built)) return null;

        int periodId = periods.resolve(requestedPeriodId);
        byte[] tile = jdbc.queryForObject(TILE_SQL, byte[].class,
                t.z(), t.x(), t.y(), periodId, periodId, extent, buffer, extent);

        return (tile == null || tile.length == 0) ? null : tile;
    }

    /**
     * The tile query.
     *
     * <p>Three things here are load-bearing:
     *
     * <p>The bbox filter is on {@code r.geom} — the whole ROAD — not on the asset's stored point. A
     * stretch is always a subset of its road, so any stretch touching this tile belongs to a road
     * touching it too: filtering on the road is a correct superset, and it uses the roads GiST index.
     * Filtering on the stored point would instead MISS a long stretch whose start chainage happens to
     * fall outside the tile, which is the trap that kept FWD off tiles in the first place.
     *
     * <p>{@code __dscale} is a whole-network decision, so it cannot be computed per tile from the
     * rows a tile happens to hold. Some surveys record D0 in millimetres and some in microns; the
     * viewer's {@code fwdScale()} decides by looking at the largest D0 in the entire dataset and
     * multiplying by 1000 when that maximum is implausibly small. The scalar subquery below asks the
     * same question of every FWD row in the period, so it returns the same answer for every tile —
     * without that, neighbouring tiles could disagree and the colour scale would break at tile seams.
     *
     * <p>D0 is read out of the free-form {@code attrs} by normalising each key, matching
     * {@link FwdSegmentService} exactly (both {@code d0} and the commonly mistyped {@code do}).
     */
    private static final String TILE_SQL =
            """
            WITH bounds AS (
                SELECT merc, ST_Transform(merc, 4326) AS wgs
                FROM (SELECT ST_TileEnvelope(?, ?, ?) AS merc) e
            ),
            d0 AS (
                /* D0, and the chainage RANGE, both read out of the free-form attrs.
                   The range cannot come from road_assets.end_chainage: that column is NULL on
                   every FWD row in practice (the survey pre-dates the importer keeping it, which
                   is also why fwd_segments builds empty), yet the rows plainly are ranges -- the
                   From/To columns are sitting in attrs. The viewer has always read them from
                   there via FROM_KEYS/TO_KEYS in 04-geo-helpers-boundaries.js, so this matches
                   those key lists, normalised the same way (lowercased, non-alphanumerics
                   stripped). Keying off end_chainage instead drew 1095 dots where the map draws
                   1095 stretches. */
                SELECT a.id,
                       (SELECT (e.value)::double precision
                          FROM jsonb_each_text(a.attrs) e
                         WHERE regexp_replace(lower(e.key), '[^a-z0-9]', '', 'g') IN ('d0','do')
                           AND e.value ~ '^-?[0-9]+(\\.[0-9]+)?$'
                         LIMIT 1) AS v,
                       (SELECT (e.value)::double precision
                          FROM jsonb_each_text(a.attrs) e
                         WHERE regexp_replace(lower(e.key), '[^a-z0-9]', '', 'g') IN
                               ('fromch','fromchainage','startch','startchainage','chainagefrom',
                                'chfrom','frch','fromm','startm','from','start')
                           AND e.value ~ '^-?[0-9]+(\\.[0-9]+)?$'
                         LIMIT 1) AS att_from,
                       (SELECT (e.value)::double precision
                          FROM jsonb_each_text(a.attrs) e
                         WHERE regexp_replace(lower(e.key), '[^a-z0-9]', '', 'g') IN
                               ('toch','tochainage','endch','endchainage','chainageto','chto',
                                'tch','tom','endm','to','end')
                           AND e.value ~ '^-?[0-9]+(\\.[0-9]+)?$'
                         LIMIT 1) AS att_to
                FROM road_assets a
                WHERE a.asset_type = 'fwd' AND a.period_id = ?
            ),
            scale AS (
                /* whole-network, so every tile agrees -- see the javadoc */
                SELECT CASE WHEN max(abs(v)) > 0 AND max(abs(v)) < 10 THEN 1000 ELSE 1 END AS f
                FROM d0
            ),
            cand AS (
                /* lo/hi rather than from/to: a reversed range (To < From) occurs on 182 of the
                   1095 rows, and chainageStretch() swaps them rather than discarding the row,
                   so LEAST/GREATEST reproduces that. Both are clamped into the road, as the
                   client also does, so a chainage past the end cannot escape the centreline. */
                SELECT a.id, a.section_label, a.attrs,
                       a.geom AS pt_geom, r.geom AS road_geom, d0.v AS d0,
                       COALESCE(a.start_chainage, d0.att_from) AS from_ch,
                       COALESCE(a.end_chainage,   d0.att_to)   AS to_ch,
                       LEAST(COALESCE(a.start_chainage, d0.att_from),
                             COALESCE(a.end_chainage,   d0.att_to, a.start_chainage, d0.att_from)) AS lo,
                       GREATEST(COALESCE(a.start_chainage, d0.att_from),
                                COALESCE(a.end_chainage,   d0.att_to, a.start_chainage, d0.att_from)) AS hi,
                       COALESCE(
                           NULLIF(r."Rd_End_cha"::double precision - r."Rd_Str_cha"::double precision, 0),
                           NULLIF(r."Measrd_Len"::double precision, 0),
                           ST_Length(r.geom::geography)) AS measured_len
                FROM road_assets a
                JOIN roads r ON r."Section_La" = a.section_label AND r.geom IS NOT NULL
                LEFT JOIN d0 ON d0.id = a.id
                CROSS JOIN bounds b
                WHERE a.asset_type = 'fwd'
                  AND a.period_id = ?
                  AND r.geom && b.wgs
            ),
            src AS (
                SELECT
                    c.id            AS asset_id,
                    c.section_label AS road,
                    c.section_label AS __sec,
                    c.from_ch,
                    c.to_ch,
                    c.attrs::text   AS attrs_json,
                    s.f             AS __dscale,
                    round(c.d0 * s.f)::int AS __d0,
                    ST_AsMVTGeom(ST_Transform(
                        CASE
                            WHEN c.lo IS NOT NULL AND c.hi IS NOT NULL
                             AND c.measured_len > 0
                             AND c.hi - c.lo > 0.001
                            THEN ST_LineSubstring(ST_LineMerge(c.road_geom),
                                     GREATEST(LEAST(c.lo / c.measured_len, 1.0), 0.0),
                                     GREATEST(LEAST(c.hi / c.measured_len, 1.0), 0.0))
                            ELSE c.pt_geom
                        END, 3857), b.merc, ?, ?, true) AS geom
                FROM cand c, bounds b, scale s
            )
            """
            + "SELECT ST_AsMVT(src, '" + LAYER_NAME + "', ?, 'geom') FROM src WHERE geom IS NOT NULL";
}
