# KLRAMS viewer — JavaScript modules

The viewer logic was previously one inline script in `map.html`. It is now split into ordered, single-responsibility modules. They are plain classic scripts loaded in sequence by `map.html`, so they share one global scope and run in the same order as before — no behaviour changed.

| # | File | Responsibility |
|---|------|----------------|
| 00 | `js/00-attr-catalog.js` | The attribute label catalogue: one fetch of `/api/attributes/catalog` giving every layer's column list as Attribute Data has it (label, unit, type, accepted column names), so a rename on that screen reaches the map cards. Loads first and never blocks — a lookup returns null when it has not loaded and the caller keeps its own fallback. |
| 01 | `js/01-config.js` | Constants, shapefile decode tables, road-detail fields, and shared application state. |
| 02 | `js/02-map-core.js` | MapLibre map creation, navigation/scale controls, and the base-map switcher. |
| 02b | `js/02b-layer-registry.js` | One declared home for every map layer: source, owning toggle, lazy loader, and draw order. Currently **inert** — it describes layers and exposes an API, but every layer is still created and toggled by the module that owns it. |
| 02c | `js/02c-segment-data.js` | The one way to ask a question about the condition segments. Backed by the global `DATA` today; exists so the whole-network reads (PCI report, export, match count, network scope) can later be answered by server-side aggregates instead of a full download. |
| 03 | `js/03-condition-style-filter.js` | Condition layer: colour-by parameter, Good/Fair/Poor thresholds, attribute filters and display mode. |
| 04 | `js/04-geo-helpers-boundaries.js` | Shared geometry/name helpers and the administrative boundary layers (district, constituency). |
| 05 | `js/05-road-network.js` | Road-network attribute metadata with colour-by and filter-by controls. |
| 06 | `js/06-assets.js` | Structures & furniture layers (bridges, culverts, furniture) with map icons and popups. |
| 07 | `js/07-data-loaders.js` | Road & condition-segment loaders and the parallel-lane condition layers. |
| 08 | `js/08-condition-popup-nsv.js` | Road / condition click popup and the NSV video marker placement helpers. |
| 09 | `js/09-dashboard-core.js` | Dashboard open/close and tab switching. |
| 10 | `js/10-pci-report.js` | PCI report: per-section and per-chainage tables, distribution bar, CSV and PDF export. |
| 11 | `js/11-dashboard-charts.js` | Dashboard charts (donuts, ranked bars), shared HTML/number formatters, overview render and floating panes. |
| 12 | `js/12-nsv-video.js` | NSV video player: pick a road, sync playback to chainage, and follow mode. |
| 13 | `js/13-search.js` | Road search and base-map location search (place names + lat/long coordinates). |
| 14 | `js/14-pci-engine.js` | IRC:82-2023 PCI engine: per-parameter indices, editable weights, rating bands, PCI map layers and popup. |
| 15 | `js/15-main.js` | Application bootstrap — wires the search once the map style has loaded. |
| 16 | `js/16-traffic.js` | Traffic stations: load from the Data Console store, place by chainage, popup with ADT / PHT / direction. |
| 32 | `js/32-iri-2km.js` | Avg IRI (2 km · worst lane) layer — 2 km IRI roll-up coloured by the worst lane average in each bin. Default render path is `/api/iri-2km/tiles/{z}/{x}/{y}.mvt` (opt out with `?tiles=0` for `/api/iri-2km/geojson`); per-bin popup lists every lane the section carries; Filters-folder section covers IRI range + worst lane (match count via `/api/iri-2km/match` in tile mode). |
| 29 | `js/29-export.js` | Per-layer export (Shapefile zip / GeoJSON / KML / KMZ / CSV) honouring the active filters; includes the in-browser ZIP/SHP/DBF/KML writers and the export menu UI. DBF column names are abbreviated word-by-word to fit the format's 10-char limit, with the full names shipped in the zip as QGIS aliases (`.qml`) and a `_field_names.csv` lookup. |

Styling lives in `css/app.css`. The HTML shell and all on-screen markup remain in `map.html`.
