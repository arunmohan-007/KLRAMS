# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

KLRAMS (Kerala Road Asset Management System) is a GIS-based platform for the Public Works Department, Government of Kerala. It's operated by the RMMS Cell at the Kerala Highway Research Institute (KHRI). The system manages road inventory, pavement condition (PCI), traffic surveys, geotechnical (FWD) data, and Government Orders through an interactive map viewer.

**Tech Stack:** Spring Boot 4.0.6 (Java 17), PostgreSQL with PostGIS, Maven, MapLibre (frontend)

## Build and Run

```bash
# Build
mvn clean package

# Run (requires PostgreSQL with PostGIS)
mvn spring-boot:run

# Or run the JAR directly
java -jar target/rmms-backend-0.0.1-SNAPSHOT.jar

# Run tests
mvn test
```

The server runs on port 8090. The bootstrap admin account is configured via `app.admin.username` / `app.admin.password` in `application.properties` (never commit real credentials). Accounts and roles are then managed in the User Management module (`/users.html`).

## Architecture

### Backend Structure

This is a **JdbcTemplate-based application** — no JPA entities, no ORM. All database access is through direct SQL using `JdbcTemplate`. Tables are created programmatically via `@PostConstruct` or `ensureSchema()` methods.

**Package layout:**
- `*Controller.java` — REST endpoints (all under `/api/`)
- `*Service.java` — Business logic and transactions
- `SecurityConfig.java` — Spring Security config (form login, public vs authenticated routes)
- `WebConfig.java` — Serves stored videos from `/videos/**`

### Database Tables (PostgreSQL + PostGIS)

Core tables created on startup:
- `roads` — Road network centrelines (LineString/MultiLineString geometry)
- `condition` — Raw condition survey data (IRI, crack, pothole, etc.)
- `condition_segments` — Materialized view with linear-referenced condition segments
- `iri_2km_segments` — IRI rolled up into 2 km bins per section: length-weighted average per lane (`lane_avgs` jsonb, whichever of CC/CL1/CL2/CR1/CR2 the section carries) plus the worst of them (`IriSegmentService`, rebuilt with the condition segments); map layer served as MVT from `/api/iri-2km/tiles/{z}/{x}/{y}.mvt`
- `road_assets` — Bridges, culverts, road furniture, FWD (POINT/LINE via linear reference; FWD is a From..To line stretch with D0..Dn in attrs — lat/lng display-only); FWD map layer served as MVT from `/api/assets/fwd/tiles/{z}/{x}/{y}.mvt`
- `road_video` — NSV video catalog (videos stored on disk at `${app.video-dir}`)
- `traffic_stations`, `traffic_counts` — Traffic survey data persistence
- `go_folders`, `go_documents` — Government Orders repository
- `site_content` — Editable public portal content (About, Contact, FAQ)
- `boundary` — Administrative boundaries (district, constituency)
- `full_road_network` — Secondary road network by road name; map layer served as MVT from `/api/full-network/tiles/{z}/{x}/{y}.mvt`
- `drone_project`, `drone_dataset` — Drone survey flights and their rasters (one orthomosaic + one DEM each). Rasters live on disk at `${app.drone-dir}`, never in the database; the row holds the extracted metadata plus a PostGIS `footprint`. Tiles are **raster PNG**, not MVT: `/api/drone/datasets/{id}/tiles/{z}/{x}/{y}.png`

### Frontend Structure

Static HTML/JS modules in `src/main/resources/static/`:
- `map.html` — Main GIS viewer (login required)
- `drone.html` + `js/drone-console.js` — Drone Dashboard / Projects / Upload
- `drone-viewer.html` + `js/drone-viewer.js` — Drone Viewer: a standalone MapLibre page (NOT another panel in map.html, which loads 30+ modules and the whole condition stack on open). It reuses the road network MVT endpoint and the road index rather than duplicating any data. Its boot deliberately hangs off `styledata`, never the map's `load` event — `load` waits for the basemap's tiles, so on a network where the external basemap is blocked the panel would never render.
- `home.html` — Internal staff portal
- `welcome.html` — Public KHRI portal
- `js/01-config.js` through `js/24-fwd.js` — Numbered modules loaded in sequence
- `css/app.css` — All styling

See `js/README.md` for the complete module list.

## Key Patterns

### Linear Referencing

Condition segments and assets are placed on road centrelines using chainage-based linear reference:

```sql
-- Reference length priority: Rd_End_cha - Rd_Str_cha, then Measrd_Len, then geometry
-- NOTE: the column is "Measrd_Len" (10 chars — the DBF field-name limit a shapefile
-- import truncates to). Not "Measrd_Ln"; that column does not exist and the query
-- fails, which inside a transaction rolls back everything else in it.
COALESCE(
    NULLIF(r."Rd_End_cha"::double precision - r."Rd_Str_cha"::double precision, 0),
    NULLIF(r."Measrd_Len"::double precision, 0),
    ST_Length(r.geom::geography)
)

-- Line assets (bridges): ST_LineSubstring(geom, start_ch/len, end_ch/len)
-- Point assets (culverts): ST_LineInterpolatePoint(geom, ch/len)
```

### Vector tiles (map render)

Map **paint** layers with PostGIS geometry are served as Mapbox Vector Tiles under `/api/.../tiles/{z}/{x}/{y}.mvt`, gated by `TILES_ON` (default; `?tiles=0` keeps GeoJSON). Pair a `*TileService` + `*TileController`; reuse `app.tile.extent|buffer|max-zoom`. Keep `/geojson` for analysis, import, or documented exceptions (FWD stretch, traffic LRS, small boundaries). Client sources use `type:'vector'` + `source-layer` — do not preload the whole FeatureCollection just to draw.

### Drone rasters (the one non-vector map layer)

Every other map layer is PostGIS geometry served as MVT. Drone orthomosaics and DEMs are rasters on disk instead, so they have their own pipeline in `DroneRasterService`:

- **GeoTIFF only**, read with the JDK's own `ImageIO` TIFF plugin — no GDAL, no new dependency. `GeoTiffMeta` parses the georeferencing tags (33550 pixel scale / 33922 tiepoint / 34264 transform / 34735 GeoKeys) straight out of the first IFD; `DroneCrs` does the EPSG:4326 / 3857 / WGS84-UTM maths. BigTIFF is rejected at upload — the JDK reader cannot decode it.
- **Publish builds an XYZ PNG pyramid**, not a COG: a COG needs GDAL in the image, and a pyramid meets the same requirement (the browser never downloads the whole GeoTIFF). The deepest zoom is rendered from the source a tile at a time via `ImageReadParam.setSourceRegion`, so memory stays bounded whatever the file size; every zoom above it is averaged down from its four children. The original upload is kept untouched.
- The build is **async on a single thread** (`status` goes `UPLOADED → PROCESSING → PUBLISHED | FAILED`), which is why the console polls while anything is processing.
- `build_version` is bumped on every publish and carried in the tile URL (`?b=`), so a re-published dataset is a new URL rather than a 30-day-stale cache entry.
- **Do NOT use `reader.readRaster()`** on a DEM. The JDK TIFF plugin returns false from `canReadRaster()` and throws; take `reader.read(...).getRaster()` instead, which returns the same unscaled float samples.
- Writes need no new `SecurityConfig` rule — they fall under the blanket `POST/PUT/DELETE /api/**` ADMIN matcher, so view-only accounts can view drone data but not upload or publish it.
- A drone project's road reference is **Road / Location** (`drone_project.location`) — the stretch the flight covered, e.g. "Ch. 2/400 – 4/900, Vempayam → Thycad". It was called `crn` briefly; `DroneService.ensureSchema()` carries a guarded `ALTER … RENAME COLUMN`.

### Drone upload validation

`DroneRasterService.validate()` runs after the metadata and band statistics are read, before the row is written. It is aimed at files that upload perfectly, take minutes to publish, and then produce a black, grey or empty rectangle — every one of those is knowable at upload.

Rejected outright: a bit depth the reader cannot decode, a raster over 800 megapixels, and an orthomosaic whose every band is flat (a failed export). Recorded as `warnings` and shown in both info panels: a one-band orthomosaic (drawn grey), an orthomosaic with more than three colour bands (1-3 drawn, rest ignored), a multi-band DEM (band 1 used), an 8-bit DEM (usually a hillshade uploaded by mistake), and no nodata/alpha (collar only transparent where pure black).

Colour rendering needs **three or more non-alpha bands**, not exactly three; the first three become R, G, B in order. See `GeoTiffMeta.displayBands()`.

### Drone contours

Two sources, one table and one map layer:

- **Traced from a DEM** — marching squares in `DroneContourService`, one pass per level, segments chained into polylines. Two things that pure marching squares gets wrong and this guards against: a level coinciding exactly with a sample value makes several segment-ends meet at one grid node and the ring comes apart into arcs (fixed by nudging the level by a part in a billion), and the same coincidence emits zero-length slivers that count as lines but cannot be drawn (filtered by minimum length). `ContourTracingTest` pins both against a ramp, a cone and a nodata hole.
- **Imported from a survey file** — a `CONTOUR` dataset on the project, alongside its orthomosaic and DEM, so it reuses the whole card/toggle/info machinery. The file is parsed **in the browser** (shpjs for zipped shapefiles, `js/kml-reader.js` for KML/KMZ) and posted as GeoJSON, exactly as the Layer Management importer does it — no shapefile or KML parser on the server. The elevation attribute is auto-detected from the usual names (ELEV, ELEVATION, CONTOUR, LEVEL…) case-insensitively, and the index interval is inferred as the smallest gap between distinct heights.

Contours are **PostGIS LineStrings served as MVT**, not pixels in the DEM's pyramid: a contour is a value to label and query. Simplification is per-zoom in the tile query, not baked in at trace time. Every 5th level is drawn heavier and labelled.

**A symbol layer needs `glyphs` on the style** or MapLibre rejects it outright, and `text-font` must name a stack the glyph server actually serves — see the `FONTS` list in `js/34-layer-style.js` (`Noto Sans Regular/Bold`, `Open Sans Semibold`). Naming any other stack renders nothing at all. Also: only ONE zoom-driven `interpolate` is allowed per expression, so vary width by attribute with a `case` at each zoom stop, not a `case` wrapping two interpolates.

### Reading road attributes from a map click

A road vector tile carries **only four properties** — `road` (Section_La), `name`, `len` and `Road_Class` — not the other 25 columns, because the per-feature tag list is most of an MVT's weight (see the comment in `RoadTileService.sqlFor`). Anything else a popup wants (District, PWD section, start/end location) must be looked up in `/api/roads/index` by section label; that endpoint carries all columns without geometry and is already loaded once per page. Writing `feature.properties.District` straight off a tile silently yields `undefined` — the Drone Viewer's Identify popup was built that way at first and rendered nearly empty.

Note also that the roads table's own `CRN` column holds the literal string `"CRN"` on every row of the current import, so it is useless for search or display; `Rd_Str_Loc` / `Rd_End_Loc` hold real place names.

### Drone raster rendering: bands, types and display windows

Three rules, each learned from a real file that rendered wrongly:

1. **Never use `BufferedImage.getRGB()` for orthomosaic pixels.** It scales samples by the TYPE's maximum, so a 16-bit raster whose values reach 5 000 draws at 5000/65535 of full brightness — a correct image rendered almost black. It also throws `ArrayIndexOutOfBoundsException` outright on single-band float rasters. `DroneRasterService` reads raw samples via `readRaster` and maps bands itself. Only palette images (photometric 3) go through `getRGB`, because the colour table lives in the decoded image.

2. **Do not trust PhotometricInterpretation to decide colour.** Many real orthomosaics are written MINISBLACK with 3-4 bands because the exporter recorded band roles in its own metadata, not the TIFF tag. `GeoTiffMeta.displayBands()` maps bands 1-3 to R-G-B whenever there are three non-alpha bands, which is what GDAL and QGIS do; believing the tag renders a colour survey in grey from the red band alone. Likewise `ExtraSamples` value 0 means "unspecified", not "alpha" — but a 4th band on an RGB image *is* alpha by convention, while a 4th band on a MINISBLACK image is another measurement (NIR), not transparency.

3. **Anything not 8-bit needs a display window.** `RasterBandStats` measures each band's 2nd/98th percentile and the renderer stretches that across 0-255. Verified against QGIS's own "Stretch to MinMax" on the same file: QGIS reported band maxima 3967/5748/8316, this code computes 3948/5715/8255. 8-bit data is passed through untouched.

**`ImageReadParam.setSourceSubsampling` truncates float samples toward zero.** A DEM in metres only loses its decimals, so it went unnoticed; a reflectance ortho scaled 0..1 becomes uniformly zero. Sampling therefore decodes honest strips and thins them in Java (`DroneRasterService.sampleValues`). This had silently made every float DEM over ~1200 px fail upload with "no usable elevation values".

Re-publishing a dataset re-reads its band metadata, so files uploaded before a fix pick the correction up without being deleted and re-uploaded.

### Place-name search (`/api/geocode`)

`GeocodeController` proxies Nominatim server-side rather than letting the browser call it. Three reasons, in order of weight: it works on office networks that reach KLRAMS but not arbitrary third-party hosts; staff IPs and typed queries are not handed out one browser at a time; and Nominatim's policy (identifying User-Agent, max 1 req/s) is honourable by one server but not by thirty uncoordinated browsers. Results are cached and outbound calls throttled. Configurable via `app.geocode.*` — point `url` at a self-hosted Nominatim, or set `enabled=false`, and the viewer's search box degrades to coordinates only, which are parsed in the browser and need no service at all.

### Dual-Carriageway Handling

Dual roads are stored as two centrelines (trailing A/B in `Section_La`). Dashboard queries compute corrected length by averaging the A/B pair to avoid double-counting. See `DashboardController.java` for the `corr` CTE.

### GeoJSON Caching

Large GeoJSON responses (roads, segments, assets) are built once and cached in memory as `volatile String` fields. After uploads, call the `refresh` endpoint or restart to rebuild.

### Security

- **Public (no login):** `welcome.html`, `login.html`, `/img/**`, `/js/**`, `/css/**`, GET `/api/go/**`, GET `/api/site/content`
- **Authenticated (staff):** Everything else — GIS viewer, internal portal, Data Console, all uploads/edits
- Token-based CSRF is disabled (the static frontend has no place to carry a token); CSRF is defended instead by a `SameSite=Strict` session cookie (`application.properties`)
- Sign-in is rate-limited: 5 failed attempts per client IP triggers a 15-minute lockout (`LoginAttemptService` + `LoginAttemptFilter`)

## Configuration

`application.properties`:
- Database: `spring.datasource.url=jdbc:postgresql://localhost:5432/rmms`
- Server: `server.port=8090`
- Storage dirs: `app.video-dir`, `app.shapefile-dir`, `app.excel-dir`, `app.drone-dir`, etc.
- Admin credentials: `app.admin.username`, `app.admin.password`
- HTTP compression is enabled for GeoJSON (~5-10x reduction)

## Development Notes

- All CSV parsers handle double-quoted fields correctly (see `parseCsvLine()` implementations)
- Shapefile parsing happens in the browser (shpjs); backend receives GeoJSON
- KML/KMZ is read in the browser too (`js/kml-reader.js` — DOMParser for the XML, a zip walk + `DecompressionStream` for the KMZ, no library) and handed on as GeoJSON, so no upload format is stored for it: a layer accepting `GEOJSON` accepts KML. Altitude is dropped, because layer geom columns are 2D `geometry(TYPE,4326)`
- Government Orders are stored as `bytea` in PostgreSQL (not on disk)
- Videos are served from disk at `/videos/**` URL mapping
- PostGIS extension is enabled automatically via `CREATE EXTENSION IF NOT EXISTS postgis`
