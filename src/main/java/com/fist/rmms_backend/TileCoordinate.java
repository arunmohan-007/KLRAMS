package com.fist.rmms_backend;

/**
 * A validated slippy-map tile address.
 *
 * <p>Exists so the tile endpoint never hands an unchecked z/x/y to PostGIS. {@code
 * ST_TileEnvelope} raises on a zoom outside 0–31 and silently produces a nonsense envelope for an
 * x/y outside the zoom's grid, so an unvalidated request is either a 500 with a Postgres message
 * in it or an empty tile that looks like missing data. Both are worse than a 400.
 *
 * <p>The grid at zoom {@code z} is {@code 2^z} tiles square, so the only valid indices are
 * {@code 0 .. 2^z - 1}. That is computed in {@code long} because {@code 1 << 31} overflows a
 * signed int — a detail that only bites at absurd zooms, which is exactly where a careless
 * check would let a bad value through.
 */
record TileCoordinate(int z, int x, int y) {

    /**
     * Validates a tile address against the configured maximum zoom.
     *
     * @throws IllegalArgumentException if the zoom is negative or beyond {@code maxZoom}, or if
     *                                  x/y fall outside the grid for that zoom
     */
    static TileCoordinate of(int z, int x, int y, int maxZoom) {
        if (z < 0 || z > maxZoom) {
            throw new IllegalArgumentException("zoom " + z + " outside 0.." + maxZoom);
        }
        long span = 1L << z;
        if (x < 0 || x >= span || y < 0 || y >= span) {
            throw new IllegalArgumentException(
                    "tile " + x + "/" + y + " outside the 0.." + (span - 1) + " grid at zoom " + z);
        }
        return new TileCoordinate(z, x, y);
    }
}
