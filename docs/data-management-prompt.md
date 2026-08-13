# KLRAMS — Data Management (Data Console import) — Implementation Prompt

> **Purpose:** Spec for how the Data Console (`index.html`, the "Data Management" module)
> surfaces each layer's required attribute columns at import time, so an admin sees what
> the system expects before uploading a file instead of finding out from a rejected import.
> Companion to `docs/layer-management-prompt.md` — both modules share the same backing
> (`LayerSchemaService`, `ManagedLayerService`, and — for CSV datasets — `ImportTemplateController`).

---

## Product goal

1. Before/while importing data for a layer, show the admin the attribute columns the system
   actually has (or expects) for that layer — not a guess, not a hard-coded list.
2. For CSV datasets, let the admin see how their file's columns map onto the columns KLRAMS
   expects, get warned about anything required that's missing, and confirm the mapping —
   an AquaGrid-style column-mapping step — **before** the real import runs.
3. Do this without duplicating or rewriting the existing upload/import endpoints.

## What already existed (and is now wired up)

`ImportTemplateController` (`/api/templates/*`) was already fully built — CRUD for templates,
a sample-CSV download, and `POST /api/templates/validate` (fuzzy header matching, cell
validation) — but no page ever called it. This pass wires `/api/templates/validate` into the
Data Console; it does **not** change any upload endpoint (`AssetController`, `ConditionService`,
`RoadUploadController`, `BoundaryController`, traffic upload, video catalog) or the
`import_templates`/`import_template_columns` tables.

## Two different mechanisms, depending on the import type

### A. CSV datasets with a template — the column-mapping wizard

Applies to every panel whose dataset key exists in `ImportTemplateController.DATASETS`:
`condition`, `bridge`, `culvert`, `furniture_line`, `furniture_point`, `subgrade`,
`bituminous_core`, `pavement_crust`, `fwd`, `traffic_stations`, `traffic_counts`,
`video_catalog`.

Flow (implemented in `index.html`, functions prefixed `wiz*`):

1. User picks a file in the panel's existing `<input type=file>` — no new UI element, same
   input as before.
2. On `change`, the client reads just the header line and POSTs the whole file to
   `POST /api/templates/validate?dataset=<key>` (existing endpoint, unchanged).
3. Response `status`:
   - `no_template` — dataset has no enabled template; wizard box stays hidden, Upload works exactly as before.
   - `error` — validation itself failed (e.g. bad multipart); wizard shows a warning but does **not** block Upload (fail open — a broken check should never be a worse experience than no check).
   - `invalid` — `missing` (required fields not found) and/or `errors` (bad cell values in matched columns). **Upload is disabled** until every missing field is resolved (or confirmed absent) via a per-field `<select>` populated with the file's own headers, defaulting to a loose name-guess. Cell-value errors get an "Import anyway" override checkbox, mirroring the existing duplicate-row override pattern already used elsewhere in this file (`showDupPrompt`).
   - `ok` — `rename` (auto-matched columns whose header differs from the canonical field name) and `extra` (columns the template doesn't claim) are shown for information. `rename` is applied automatically (no extra click) since the match is already unambiguous.
4. On resolution, the client rewrites **only the CSV header line** (`rewriteCsvHeader`) —
   data rows are never touched — and swaps the corrected `File` into the original
   `<input>` via `DataTransfer`. The existing upload functions (`up()`, `upAsset()`,
   `upGeo()`, `upTraffic()`) read `input.files[0]` exactly as before and are **not modified**.
5. This also fixes a real latent bug for free: `ConditionService.loadCsv()` and the
   traffic-stations parser match headers case-sensitively with no aliasing, so a
   differently-cased source header previously failed silently. Pre-upload renaming via the
   template's already-computed `rename` map fixes that.

Per-dataset "extra columns" wording differs, because the importers don't all behave the same:
`bridge`, `culvert`, `furniture_line`, `furniture_point`, `subgrade`, `bituminous_core`,
`pavement_crust`, `fwd` are `AssetController`-backed and keep unmatched columns as free-form
`attrs`/`props` jsonb — the wizard says "kept as extra attributes". `condition`,
`traffic_stations`, `traffic_counts`, `video_catalog` parse a fixed column set and drop
anything else — the wizard says "ignored by this importer".

### B. Geometry imports without a template — read-only attribute reference

Applies to road network, full road network, and every Administrative Boundary / custom
`boundary`-kind layer (shapefile ZIP or GeoJSON). These aren't mapped to a template because
there's nothing to map: `RoadUploadController` matches feature properties 1:1 against real
`roads` columns (case-sensitive) and silently drops anything unrecognized;
`BoundaryController` stores the uploaded GeoJSON as-is. There's no per-column target list to
offer a mapping UI against.

Instead, `wireLayerAttrPanels()` calls the **same** `/api/layers/{key}/attributes` endpoint
Layer Management uses (`LayerSchemaService`, live `information_schema` + PostGIS
introspection) and shows a read-only reference table (column name, type, sample value)
above the file picker, with a link to Layer Management for full detail. Boundary-kind
layers with no PostGIS geometry column get the existing "Boundary layers store GeoJSON
text" message instead of a column list — same message `layers.html` already shows.

---

## Non-goals (this pass)

- **No template-editor UI.** `import_templates` CRUD (`POST/PUT/DELETE /api/templates`) stays
  API-only — no admin screen to add/rename fields or change what's required. The wizard only
  *reads* templates via `/validate`.
- **No reprojection** — everything is EPSG:4326, same as Layer Management.
- **No change to any upload endpoint's parsing/validation logic.** The wizard only rewrites
  the CSV header text on the client before the file reaches the unmodified endpoint.
- **No mapping wizard for geometry imports** — read-only reference only, per §B above; there
  is no per-column target list to map shapefile/GeoJSON properties against.

## Acceptance checks

- [ ] Selecting a CSV panel with a template and a matching file shows "✓ Every column matches" and Upload is enabled immediately
- [ ] A file missing a required column blocks Upload until mapped or explicitly confirmed absent
- [ ] A file with a differently-cased/renamed header still imports correctly (header gets rewritten before the existing endpoint sees it)
- [ ] Cell-value errors block Upload unless "Import anyway" is checked
- [ ] Road network / full network / boundary panels show the live column reference, not a mapping wizard
- [ ] No existing upload endpoint's Java code changed as part of this — the wizard is entirely client-side plus the pre-existing `/api/templates/validate`
