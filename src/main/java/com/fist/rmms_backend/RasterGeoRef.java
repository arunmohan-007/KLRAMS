package com.fist.rmms_backend;

/**
 * The georeferencing surface {@link UserRasterService}'s tile pyramid builder
 * needs, whichever way a raster's placement on Earth was declared.
 *
 * <p>{@link GeoTiffMeta} implements this from an embedded CRS and affine;
 * {@link WorldFileGeoRef} implements it from a {@code .jgw}/{@code .pgw}
 * sidecar with no embedded CRS at all, assuming WGS84. The pyramid builder
 * reads only this interface, so it does not need to know which kind of file
 * produced it.
 */
interface RasterGeoRef {

    int width();

    int height();

    int samplesPerPixel();

    /** TIFF PhotometricInterpretation-style code: 0/1 grayscale, 2 RGB, 3 palette. */
    int photometric();

    /** Which band carries alpha, or -1. */
    int alphaBand();

    /** NaN when there is no declared nodata value. */
    double noData();

    /** Model coordinate at the CENTRE of pixel (col,row). */
    double[] pixelToModel(double col, double row);

    /** Fractional pixel column/row holding the given model coordinate. */
    double[] modelToPixel(double x, double y);

    double resX();

    double resY();

    /** {minLon, minLat, maxLon, maxLat}. */
    double[] wgs84Bounds();

    /** Ground sample distance in metres. */
    double resolutionMetres();

    /** Model (x,y) for a WGS84 (lon,lat) pair. */
    double[] fromWgs84(double lon, double lat);

    /** WGS84 (lon,lat) for a model (x,y) pair. */
    double[] toWgs84(double x, double y);

    /** Source band indices to draw: three for colour, one for grayscale. */
    int[] displayBands();
}
