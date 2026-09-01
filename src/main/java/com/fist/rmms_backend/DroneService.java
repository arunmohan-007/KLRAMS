package com.fist.rmms_backend;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Drone module — projects and the datasets (orthomosaic / DEM) that hang off them.
 *
 * <p>A drone project is a survey flight over a stretch of road: it names the road,
 * the location along it and the PWD section, and carries at most one orthomosaic
 * and one DEM. That one-of-each rule is deliberate — a re-flight is a new project (with its own
 * survey date), not a second raster inside the old one, which is what keeps
 * "which image am I looking at" answerable from the project list alone.
 *
 * <p>Follows the schema convention the rest of KLRAMS uses: no JPA, no migration
 * tool, tables created idempotently on startup from {@link PostConstruct}.
 *
 * <p>The raster files themselves never enter PostgreSQL. They live under
 * {@code app.drone-dir} (mounted on the persistent {@code klrams-data} volume in
 * docker-compose, next to videos and shapefiles); the database holds only the
 * path and the extracted metadata. See {@link DroneRasterService} for the layout.
 */
@Service
public class DroneService {

    private static final Logger log = LoggerFactory.getLogger(DroneService.class);

    static final String ORTHO = "ORTHOMOSAIC";
    static final String DEM = "DEM";
    /** Contours imported from a survey file, as opposed to traced from a DEM. */
    static final String CONTOUR = "CONTOUR";

    /** A dataset's life: uploaded → (publish) processing → published, or failed. */
    static final String UPLOADED = "UPLOADED";
    static final String PROCESSING = "PROCESSING";
    static final String PUBLISHED = "PUBLISHED";
    static final String FAILED = "FAILED";

    private final JdbcTemplate jdbc;
    private final Path root;

    public DroneService(JdbcTemplate jdbc, @Value("${app.drone-dir:drone-store}") String droneDir) {
        this.jdbc = jdbc;
        this.root = Paths.get(droneDir).toAbsolutePath().normalize();
    }

    /** Root of the on-disk raster store; {@code <root>/<datasetId>/…}. */
    Path root() {
        return root;
    }

    @PostConstruct
    public void ensure() {
        try {
            Files.createDirectories(root);
            ensureSchema();
        } catch (Exception e) {
            // Same rule the layer registry follows: a Drone failure must never stop
            // the app booting — every existing module works without this one.
            log.error("Drone module init failed — the Drone module may be degraded, "
                    + "but the app will keep starting", e);
        }
    }

    private void ensureSchema() {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS postgis");

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS drone_project (
                id           serial PRIMARY KEY,
                project_code text NOT NULL,
                project_name text NOT NULL,
                survey_date  date,
                road_section text,
                location     text,
                pwd_section  text,
                description  text,
                created_by   text,
                created_at   timestamptz NOT NULL DEFAULT now(),
                updated_at   timestamptz NOT NULL DEFAULT now()
            )""");

        /* Case-insensitive, so "DRONE-001" cannot quietly become a second project
           next to "drone-001" — the code is what staff quote to each other. */
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS drone_project_code_idx "
                + "ON drone_project (lower(project_code))");

        /* The field started life as "crn" and became "location": a drone flight is
           identified by where on the road it was flown, and the road network's own
           CRN column was already carrying the road's number. Renamed rather than
           added-and-copied so there is only ever one column to read, and guarded so
           it is a no-op on a database created after the rename. */
        jdbc.execute("""
            DO $$
            BEGIN
                IF EXISTS (SELECT 1 FROM information_schema.columns
                            WHERE table_name = 'drone_project' AND column_name = 'crn')
                   AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                            WHERE table_name = 'drone_project' AND column_name = 'location')
                THEN
                    ALTER TABLE drone_project RENAME COLUMN crn TO location;
                END IF;
            END $$""");

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS drone_dataset (
                id             serial PRIMARY KEY,
                project_id     integer NOT NULL REFERENCES drone_project(id) ON DELETE CASCADE,
                dataset_name   text NOT NULL,
                dataset_type   text NOT NULL,
                file_name      text NOT NULL,
                file_path      text NOT NULL,
                file_size      bigint,
                format         text,
                epsg           integer,
                crs_name       text,
                res_x          double precision,
                res_y          double precision,
                raster_width   integer,
                raster_height  integer,
                min_x          double precision,
                min_y          double precision,
                max_x          double precision,
                max_y          double precision,
                elevation_min  double precision,
                elevation_max  double precision,
                footprint      geometry(Polygon,4326),
                status         text NOT NULL DEFAULT 'UPLOADED',
                status_message text,
                published      boolean NOT NULL DEFAULT false,
                min_zoom       integer,
                max_zoom       integer,
                build_version  integer NOT NULL DEFAULT 0,
                created_by     text,
                created_at     timestamptz NOT NULL DEFAULT now(),
                updated_at     timestamptz NOT NULL DEFAULT now()
            )""");

        /* One orthomosaic and one DEM per project (see the class note). Enforced in
           the schema rather than only in the service, so a second upload of the same
           kind fails loudly instead of leaving two rows the viewer cannot choose
           between. */
        /* Band-level description of the raster, so the viewer can answer "what is
           actually in this file" — band count, sample type, colour interpretation
           and the per-band value range. Added after a 16-bit orthomosaic rendered
           almost black and none of that was visible anywhere. */
        jdbc.execute("ALTER TABLE drone_dataset ADD COLUMN IF NOT EXISTS band_count integer");
        jdbc.execute("ALTER TABLE drone_dataset ADD COLUMN IF NOT EXISTS data_type text");
        jdbc.execute("ALTER TABLE drone_dataset ADD COLUMN IF NOT EXISTS colour_interp text");
        jdbc.execute("ALTER TABLE drone_dataset ADD COLUMN IF NOT EXISTS band_stats jsonb");
        jdbc.execute("ALTER TABLE drone_dataset ADD COLUMN IF NOT EXISTS no_data double precision");
        /* Things that are true of the upload and would otherwise only become
           apparent as a strange-looking map after a long publish. */
        jdbc.execute("ALTER TABLE drone_dataset ADD COLUMN IF NOT EXISTS warnings text");

        /* Contours traced from a DEM. Lines in PostGIS rather than pixels in the
           DEM's pyramid: a contour is a value to label and query, not a picture. */
        jdbc.execute("ALTER TABLE drone_dataset ADD COLUMN IF NOT EXISTS contour_interval double precision");
        jdbc.execute("ALTER TABLE drone_dataset ADD COLUMN IF NOT EXISTS contour_status text");
        jdbc.execute("ALTER TABLE drone_dataset ADD COLUMN IF NOT EXISTS contour_count integer NOT NULL DEFAULT 0");
        jdbc.execute("ALTER TABLE drone_dataset ADD COLUMN IF NOT EXISTS contour_message text");

        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS drone_contour (
                id         serial PRIMARY KEY,
                dataset_id integer NOT NULL REFERENCES drone_dataset(id) ON DELETE CASCADE,
                elevation  double precision NOT NULL,
                is_index   boolean NOT NULL DEFAULT false,
                geom       geometry(LineString,4326)
            )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS drone_contour_geom_idx ON drone_contour USING GIST (geom)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS drone_contour_dataset_idx ON drone_contour (dataset_id)");

        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS drone_dataset_project_type_idx "
                + "ON drone_dataset (project_id, dataset_type)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS drone_dataset_footprint_idx "
                + "ON drone_dataset USING GIST (footprint)");
    }

    /* ------------------------------------------------------------------
       Projects
       ------------------------------------------------------------------ */

    /* A survey date is a calendar day, not an instant. Handed back as a
       java.sql.Date it would be serialised as midnight UTC, which renders as the
       PREVIOUS day for anyone east of Greenwich — Kerala included. Casting to text
       in SQL keeps the day the user typed the day everyone reads. */
    private static final String SURVEY_DATE = "to_char(p.survey_date, 'YYYY-MM-DD') AS survey_date";

    /**
     * Every project, newest survey first, each carrying a compact summary of its two
     * possible datasets so the project list can render its Orthomosaic / DEM columns
     * without a second round trip per row.
     */
    List<Map<String, Object>> listProjects() {
        return jdbc.queryForList("""
            SELECT p.id, p.project_code, p.project_name, %s, p.road_section, p.location,
                   p.pwd_section, p.description, p.created_by, p.created_at, p.updated_at,
                   COALESCE((
                       SELECT json_agg(json_build_object(
                                  'id', d.id, 'type', d.dataset_type, 'name', d.dataset_name,
                                  'status', d.status, 'published', d.published)
                              ORDER BY d.dataset_type)
                       FROM drone_dataset d WHERE d.project_id = p.id
                   ), '[]'::json)::text AS datasets
            FROM drone_project p
            ORDER BY p.survey_date DESC NULLS LAST, p.id DESC
            """.formatted(SURVEY_DATE));
    }

    Map<String, Object> project(int id) {
        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT * FROM drone_project WHERE id = ?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Drone project " + id + " does not exist.");
        return rows.get(0);
    }

    int createProject(String code, String name, String surveyDate, String roadSection,
                      String location, String pwdSection, String description, String user) {
        String c = required(code, "Project ID");
        String n = required(name, "Project Name");
        if (jdbc.queryForObject("SELECT count(*) FROM drone_project WHERE lower(project_code) = lower(?)",
                Integer.class, c) > 0)
            throw new IllegalArgumentException("A drone project with the ID \"" + c + "\" already exists.");

        return jdbc.queryForObject("""
            INSERT INTO drone_project
                (project_code, project_name, survey_date, road_section, location, pwd_section, description, created_by)
            VALUES (?, ?, CAST(? AS date), ?, ?, ?, ?, ?)
            RETURNING id
            """, Integer.class,
                c, n, blankToNull(surveyDate), blankToNull(roadSection), blankToNull(location),
                blankToNull(pwdSection), blankToNull(description), user);
    }

    void updateProject(int id, String code, String name, String surveyDate, String roadSection,
                       String location, String pwdSection, String description) {
        String c = required(code, "Project ID");
        String n = required(name, "Project Name");
        if (jdbc.queryForObject("SELECT count(*) FROM drone_project WHERE lower(project_code) = lower(?) AND id <> ?",
                Integer.class, c, id) > 0)
            throw new IllegalArgumentException("A drone project with the ID \"" + c + "\" already exists.");

        int n2 = jdbc.update("""
            UPDATE drone_project
               SET project_code = ?, project_name = ?, survey_date = CAST(? AS date),
                   road_section = ?, location = ?, pwd_section = ?, description = ?, updated_at = now()
             WHERE id = ?
            """, c, n, blankToNull(surveyDate), blankToNull(roadSection), blankToNull(location),
                blankToNull(pwdSection), blankToNull(description), id);
        if (n2 == 0) throw new IllegalArgumentException("Drone project " + id + " does not exist.");
    }

    void deleteProject(int id) {
        if (jdbc.update("DELETE FROM drone_project WHERE id = ?", id) == 0)
            throw new IllegalArgumentException("Drone project " + id + " does not exist.");
    }

    /* ------------------------------------------------------------------
       Datasets (rows only — the files are DroneRasterService's job)
       ------------------------------------------------------------------ */

    /* Every dataset column EXCEPT footprint. The footprint is a PostGIS geometry
       and JDBC hands it back as a driver-specific object, which Jackson serialises
       as a wrapper around a WKB hex string — noise no caller wants. It exists for
       spatial queries in SQL; min_x/min_y/max_x/max_y are what the API and the
       viewer read. */
    private static final String DATASET_COLUMNS = """
            d.id, d.project_id, d.dataset_name, d.dataset_type, d.file_name, d.file_size,
            d.format, d.epsg, d.crs_name, d.res_x, d.res_y, d.raster_width, d.raster_height,
            d.min_x, d.min_y, d.max_x, d.max_y, d.elevation_min, d.elevation_max,
            d.status, d.status_message, d.published, d.min_zoom, d.max_zoom, d.build_version,
            d.band_count, d.data_type, d.colour_interp, d.band_stats::text AS band_stats, d.no_data,
            d.contour_interval, d.contour_status, d.contour_count, d.contour_message, d.warnings,
            d.created_by, d.created_at, d.updated_at
            """;

    List<Map<String, Object>> listDatasets(Integer projectId) {
        String sql = """
            SELECT %s, p.project_code, p.project_name, p.location, p.road_section, %s
            FROM drone_dataset d JOIN drone_project p ON p.id = d.project_id
            """.formatted(DATASET_COLUMNS, SURVEY_DATE);
        return projectId == null
                ? jdbc.queryForList(sql + " ORDER BY d.id DESC")
                : jdbc.queryForList(sql + " WHERE d.project_id = ? ORDER BY d.dataset_type", projectId);
    }

    Map<String, Object> dataset(int id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT %s, p.project_code, p.project_name, p.location, p.road_section, %s
            FROM drone_dataset d JOIN drone_project p ON p.id = d.project_id
            WHERE d.id = ?
            """.formatted(DATASET_COLUMNS, SURVEY_DATE), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Drone dataset " + id + " does not exist.");
        return rows.get(0);
    }

    /**
     * Where a dataset's original file lives on disk.
     *
     * <p>Its own lookup rather than a column in {@link #DATASET_COLUMNS}: an absolute
     * server path is of no use to a browser and telling every caller of the API where
     * files sit on the host is free reconnaissance. Only the raster pipeline needs it.
     */
    Path filePath(int datasetId) {
        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT file_path FROM drone_dataset WHERE id = ?", datasetId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Drone dataset " + datasetId + " does not exist.");
        return Path.of((String) rows.get(0).get("file_path"));
    }

    /** Every dataset the viewer may draw: published, with a built pyramid. */
    List<Map<String, Object>> publishedDatasets() {
        return jdbc.queryForList("""
            SELECT d.id, d.project_id, d.dataset_name, d.dataset_type, d.min_x, d.min_y, d.max_x, d.max_y,
                   d.min_zoom, d.max_zoom, d.build_version, d.elevation_min, d.elevation_max,
                   d.epsg, d.crs_name, d.res_x, d.res_y, d.raster_width, d.raster_height, d.file_size,
                   d.band_count, d.data_type, d.colour_interp, d.band_stats::text AS band_stats, d.no_data,
                   d.contour_interval, d.contour_status, d.contour_count, d.warnings,
                   p.project_code, p.project_name, p.location, p.road_section, p.pwd_section, %s
            FROM drone_dataset d JOIN drone_project p ON p.id = d.project_id
            WHERE d.published AND d.status = 'PUBLISHED'
            ORDER BY p.survey_date DESC NULLS LAST, p.project_code, d.dataset_type
            """.formatted(SURVEY_DATE));
    }

    /* ------------------------------------------------------------------
       Dashboard
       ------------------------------------------------------------------ */

    Map<String, Object> summary() {
        return jdbc.queryForMap("""
            SELECT (SELECT count(*) FROM drone_project)                                    AS projects,
                   count(*) FILTER (WHERE dataset_type = 'ORTHOMOSAIC')                    AS orthomosaics,
                   count(*) FILTER (WHERE dataset_type = 'DEM')                            AS dems,
                   count(*) FILTER (WHERE dataset_type = 'CONTOUR')                        AS contour_sets,
                   COALESCE(sum(contour_count), 0)                                         AS contour_lines,
                   count(*) FILTER (WHERE published AND status = 'PUBLISHED')              AS published,
                   count(*) FILTER (WHERE status = 'PROCESSING')                           AS processing,
                   count(*) FILTER (WHERE status = 'FAILED')                               AS failed,
                   count(*) FILTER (WHERE status = 'UPLOADED')                             AS uploaded,
                   COALESCE(sum(file_size), 0)                                             AS total_bytes
            FROM drone_dataset
            """);
    }

    /* ------------------------------------------------------------------
       Helpers
       ------------------------------------------------------------------ */

    static String required(String v, String label) {
        String s = v == null ? "" : v.trim();
        if (s.isEmpty()) throw new IllegalArgumentException(label + " is required.");
        return s;
    }

    static String blankToNull(String v) {
        String s = v == null ? "" : v.trim();
        return s.isEmpty() ? null : s;
    }
}
