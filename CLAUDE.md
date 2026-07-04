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
- `road_assets` — Bridges, culverts, road furniture (POINT/LINE via linear reference)
- `road_video` — NSV video catalog (videos stored on disk at `${app.video-dir}`)
- `traffic_stations`, `traffic_counts` — Traffic survey data persistence
- `go_folders`, `go_documents` — Government Orders repository
- `site_content` — Editable public portal content (About, Contact, FAQ)
- `boundary` — Administrative boundaries (district, constituency)
- `full_road_network` — Secondary road network by road name

### Frontend Structure

Static HTML/JS modules in `src/main/resources/static/`:
- `map.html` — Main GIS viewer (login required)
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
COALESCE(
    NULLIF(r."Rd_End_cha"::double precision - r."Rd_Str_cha"::double precision, 0),
    NULLIF(r."Measrd_Ln"::double precision, 0),
    ST_Length(r.geom::geography)
)

-- Line assets (bridges): ST_LineSubstring(geom, start_ch/len, end_ch/len)
-- Point assets (culverts): ST_LineInterpolatePoint(geom, ch/len)
```

### Dual-Carriageway Handling

Dual roads are stored as two centrelines (trailing A/B in `Section_La`). Dashboard queries compute corrected length by averaging the A/B pair to avoid double-counting. See `DashboardController.java` for the `corr` CTE.

### GeoJSON Caching

Large GeoJSON responses (roads, segments, assets) are built once and cached in memory as `volatile String` fields. After uploads, call the `refresh` endpoint or restart to rebuild.

### Security

- **Public (no login):** `welcome.html`, `login.html`, `/img/**`, `/js/**`, `/css/**`, GET `/api/go/**`, GET `/api/site/content`
- **Authenticated (staff):** Everything else — GIS viewer, internal portal, Data Console, all uploads/edits
- CSRF is disabled for the pilot (see `SecurityConfig.java`)

## Configuration

`application.properties`:
- Database: `spring.datasource.url=jdbc:postgresql://localhost:5432/rmms`
- Server: `server.port=8090`
- Storage dirs: `app.video-dir`, `app.shapefile-dir`, `app.excel-dir`, etc.
- Admin credentials: `app.admin.username`, `app.admin.password`
- HTTP compression is enabled for GeoJSON (~5-10x reduction)

## Development Notes

- All CSV parsers handle double-quoted fields correctly (see `parseCsvLine()` implementations)
- Shapefile parsing happens in the browser (shpjs); backend receives GeoJSON
- Government Orders are stored as `bytea` in PostgreSQL (not on disk)
- Videos are served from disk at `/videos/**` URL mapping
- PostGIS extension is enabled automatically via `CREATE EXTENSION IF NOT EXISTS postgis`
