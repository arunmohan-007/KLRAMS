<div align="center">

# KLRAMS — Kerala Road Asset Management System

## User Manual

**Government of Kerala · Public Works Department**
**Kerala Highway Research Institute (KHRI) — RMMS Cell**

*Version 1.0 · July 2026*

</div>

---

## Document Control

| Item | Detail |
|---|---|
| Document title | KLRAMS User Manual |
| System | Kerala Road Asset Management System (KLRAMS) |
| Operated by | RMMS Cell, Kerala Highway Research Institute (KHRI), PWD |
| Audience | PWD engineers, RMMS Cell staff, system administrators |
| Version | 1.0 |
| Status | Draft for review — screenshots to be inserted |

> **Note on figures:** every figure in this manual is a placeholder. Each placeholder names its image file (in `docs/user-manual/images/`) and describes exactly what the screenshot should show. Drop a PNG with the given filename into the `images/` folder and the figure will render — no edits to this document are needed. A complete figure checklist is in [Appendix D](#appendix-d--figure-checklist).

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Getting Started](#2-getting-started)
3. [The Portals — Public Site and Staff Home](#3-the-portals--public-site-and-staff-home)
4. [The GIS Viewer](#4-the-gis-viewer)
   - 4.1 [Screen layout](#41-screen-layout)
   - 4.2 [Map layers](#42-map-layers)
   - 4.3 [Road Inspector — click any road](#43-road-inspector--click-any-road)
   - 4.4 [Road Condition Data (NSV survey)](#44-road-condition-data-nsv-survey)
   - 4.5 [NSV Survey Video — synchronized playback](#45-nsv-survey-video--synchronized-playback)
   - 4.6 [Pavement Condition Index (PCI)](#46-pavement-condition-index-pci)
   - 4.7 [FWD — structural condition](#47-fwd--structural-condition)
   - 4.8 [Traffic stations](#48-traffic-stations)
   - 4.9 [Structures & road furniture](#49-structures--road-furniture)
   - 4.10 [Asset Register](#410-asset-register)
   - 4.11 [Network Dashboard](#411-network-dashboard)
   - 4.12 [Report Hub](#412-report-hub)
   - 4.13 [Search — roads and places](#413-search--roads-and-places)
   - 4.14 [Base maps](#414-base-maps)
   - 4.15 [Measure tools](#415-measure-tools)
   - 4.16 [Filters](#416-filters)
   - 4.17 [Sahaayi — the KLRAMS assistant](#417-sahaayi--the-klrams-assistant)
   - 4.18 [Lite map (no graphics acceleration)](#418-lite-map-no-graphics-acceleration)
5. [The Data Console](#5-the-data-console)
6. [GO Portal — Government Orders](#6-go-portal--government-orders)
7. [User Management & Security](#7-user-management--security)
8. [Site Control](#8-site-control)
9. [Troubleshooting & FAQ](#9-troubleshooting--faq)
- [Appendix A — Road data dictionary](#appendix-a--road-data-dictionary)
- [Appendix B — Condition parameters & thresholds](#appendix-b--condition-parameters--thresholds)
- [Appendix C — PCI method, weightages and rating bands](#appendix-c--pci-method-weightages-and-rating-bands)
- [Appendix D — Figure checklist](#appendix-d--figure-checklist)

---

# 1. Introduction

## 1.1 What is KLRAMS?

KLRAMS (Kerala Road Asset Management System) is a GIS-based road asset management platform for the Public Works Department, Government of Kerala. It is operated by the RMMS Cell at the Kerala Highway Research Institute (KHRI).

KLRAMS brings the department's road data together on one interactive map:

- **Road network** — State Highway and Major District Road centrelines with full inventory attributes (class, lane type, carriageway, surface, ownership, chainage, start/end locations).
- **Pavement condition** — Network Survey Vehicle (NSV) survey results (IRI roughness, cracking, potholes, rutting, texture, patching, ravelling) placed on the map lane-by-lane at every chainage.
- **NSV survey video** — the actual survey footage, played back in sync with the map: as the video plays, a vehicle marker drives along the road with live chainage and lane-wise condition read-out.
- **PCI** — a built-in IRC:82 Pavement Condition Index engine that converts raw distress data into a 0–100 index per stretch, with editable weightages, colour-coded map layers, and exportable reports.
- **FWD** — Falling Weight Deflectometer structural survey results (D0 deflection) as colour-coded segments along the network.
- **Traffic** — survey stations with ADT, peak-hour, direction split and vehicle-class breakdown.
- **Structures & furniture** — bridges, culverts and roadside furniture placed by chainage.
- **Government Orders** — a searchable GO repository with folder organisation.

## 1.2 Who uses it?

| Role | Typical user | What they can do |
|---|---|---|
| **USER** | Field engineers, section staff | View everything — map, dashboards, reports, videos, GOs |
| **ADMIN** | RMMS Cell data operators | Everything above **plus** import/edit data in the Data Console and GO Portal |
| **SUPER_ADMIN** | System administrator | Everything above **plus** User Management, Site Control and Login Activity |

Role gating is applied both in the interface (buttons hidden for lower roles) and enforced on the server.

## 1.3 System at a glance

| Component | Detail |
|---|---|
| Access | Web browser (Chrome / Edge / Firefox recommended), no installation |
| Map engine | MapLibre GL (WebGL). A **Lite map** is provided for machines without graphics acceleration |
| Backend | Spring Boot (Java), PostgreSQL + PostGIS |
| Default port | 8090 (e.g. `http://<server>:8090/`) |

---

# 2. Getting Started

## 2.1 Signing in

1. Open the KLRAMS address in your browser. The **public portal** loads without a login.
2. Click **Sign in** (or open `/login.html`).
3. Enter the username and password issued by the RMMS Cell.

![Figure 2.1 — Sign-in page](images/fig-02-01-login.png)
*Figure 2.1 — The KLRAMS Console sign-in page.*
<!-- SCREENSHOT: /login.html with the username/password form visible. -->

**First login:** new accounts are created with a temporary password and are forced to change it at first sign-in. You will be redirected to the **Change password** page automatically; choose a new password to continue.

![Figure 2.2 — Forced password change on first login](images/fig-02-02-change-password.png)
*Figure 2.2 — First-login password change.*
<!-- SCREENSHOT: /change-password.html -->

## 2.2 Where things are

| Page | URL | Login needed |
|---|---|---|
| Public portal (KHRI) | `/welcome.html` | No |
| GO Portal (viewing) | `/go.html` | No (viewing); upload needs ADMIN |
| Staff home | `/home.html` | Yes |
| GIS Viewer | `/map.html` | Yes |
| GIS Viewer (Lite) | `/map-lite.html` | Yes |
| Data Console | `/index.html` | Yes (imports need ADMIN) |
| User Management | `/users.html` | SUPER_ADMIN |
| Login Activity | `/login-report.html` | SUPER_ADMIN |
| Site Control | `/admin.html` | SUPER_ADMIN |

## 2.3 Browser requirements

The main GIS viewer uses WebGL. If your machine or remote-desktop session has no graphics acceleration, KLRAMS detects this and offers the **Lite map** (see § 4.18) which needs no WebGL.

---

# 3. The Portals — Public Site and Staff Home

## 3.1 Public portal

The public portal presents KHRI / RMMS Cell information — About, Contact and FAQ content (editable by administrators through **Site Control**, § 8) and public access to the **GO Portal**.

![Figure 3.1 — Public portal](images/fig-03-01-public-portal.png)
*Figure 3.1 — The public KHRI portal (no login required).*
<!-- SCREENSHOT: /welcome.html full page. -->

## 3.2 Staff home

After signing in you land on the **staff home portal** — a launcher for every module. Tiles that your role cannot use are hidden automatically.

![Figure 3.2 — Staff home portal](images/fig-03-02-home-portal.png)
*Figure 3.2 — Staff home with module tiles: GIS Viewer, Data Console, GO Portal, NSV Survey Video, Road Condition Data, Pavement Condition Index, Network Dashboard, Asset Register, Report Hub, User Management, Site Control, Login Activity.*
<!-- SCREENSHOT: /home.html after login as SUPER_ADMIN so every tile is visible. -->

---

# 4. The GIS Viewer

The GIS viewer (`/map.html`) is the heart of KLRAMS. Everything the system knows about a road is reachable from this one screen.

## 4.1 Screen layout

![Figure 4.1 — GIS viewer overview](images/fig-04-01-gis-overview.png)
*Figure 4.1 — GIS viewer: icon rail (left), layers panel, search bar (top), map canvas, legend (bottom-left), Sahaayi assistant (bottom-right).*
<!-- SCREENSHOT: map.html with Road network layer ON, layers panel open, zoomed to the network. -->

| Element | Where | What it does |
|---|---|---|
| **Icon rail** | far left | Switches the side panel: **Layers**, **Network**, **Filter**, **Base map**, **Measure**, and a shortcut back to the **Console** |
| **App launcher** (grid icon) | top-left | Opens the in-map launcher: Dashboard, Asset Register, PCI, Report Hub, NSV Videos and more |
| **Search bar** | top centre | Road search (name / number / section label) — see § 4.13 |
| **Legend** | bottom-left | Automatic legend for whichever layers are on |
| **Lite map / Sign out** | top-right | Switch to the WebGL-free viewer; end your session |
| **Ask Sahaayi** | bottom-right | The KLRAMS assistant (§ 4.17) |

![Figure 4.2 — In-map app launcher](images/fig-04-02-app-launcher.png)
*Figure 4.2 — The app launcher: one click to Dashboard, Asset Register, PCI, Report Hub, NSV Videos.*
<!-- SCREENSHOT: map.html with the grid launcher open. -->

## 4.2 Map layers

Open the **Layers** panel from the icon rail. Layers are grouped; click a group to expand it and use the toggles. Data loads on demand — the first time you switch a layer on it may take a moment.

| Group | Layers |
|---|---|
| **Road Network** | PWD road centrelines (SH / MDR); **Full Road Network** (secondary network, by road name) |
| **Road Condition Data & FWD** | NSV condition segments (lane-wise); FWD D0 segments |
| **Administrative boundary** | District boundary; Constituency boundary |
| **Structures & furniture** | Bridges; Culverts; Furniture (point); Furniture (line) |
| **PCI** | Composite PCI; Worst-Lane PCI (generate first — § 4.6) |
| **Traffic stations** | Survey stations with counts |
| **Sub-grade soil, Bituminous core & Pavement crust** | Geotechnical investigation layers |
| **Climate** | Flood susceptibility and related climate layers |

The **Network** panel adds display controls for the road network itself: **colour by attribute** (e.g. road class, surface type, ownership) and **filter by attribute**, with a live legend.

![Figure 4.3 — Network panel: colour-by and filter-by attribute](images/fig-04-03-network-panel.png)
*Figure 4.3 — Colouring the network by an attribute; the legend updates automatically.*
<!-- SCREENSHOT: Network panel open with "Colour by attribute" set (e.g. Road class) and the legend showing SH / MDR. -->

## 4.3 Road Inspector — click any road

Click any road on the map to open the **Road Inspector**. This is the single most useful action in the viewer — the inspector brings together everything KLRAMS knows at that exact spot:

- **Road identity** — road name, road number, class (SH / MDR / …), section label.
- **Geometry & referencing** — **start location** and **end location**, **road start / end chainage**, measured length, and the **chainage at the point you clicked**.
- **Inventory** — lane type, single/dual carriageway, pavement width band, construction type, surface type, current owner, PWD section, CRN, district.
- **Condition at that chainage** — for every condition layer you have on, the values of the 100 m block you clicked (lane-wise).
- **PCI cards** — the computed PCI at that stretch (once PCI has been generated).
- **NSV survey coverage** — a coverage bar showing how much of the road has survey video, with any gaps.
- **▶ Play footage** — launches the synchronized NSV video (§ 4.5). The button appears only when footage exists for this road.

![Figure 4.4 — Road Inspector](images/fig-04-04-road-inspector.png)
*Figure 4.4 — Road Inspector: identity, start/end locations and chainages, inventory attributes, condition at the clicked chainage, NSV coverage and the Play footage button.*
<!-- SCREENSHOT: click mid-way along a surveyed road with Condition layer ON, so the inspector shows attributes + condition values + Play footage. -->

## 4.4 Road Condition Data (NSV survey)

Switch on **Road Condition Data** in the Layers panel. The network is drawn as colour-coded segments — by default coloured by **IRI** (roughness) into **Good / Fair / Poor** bands.

**Lane-wise display.** Condition is stored per lane (XSP: `CC`, `CL1`, `CL2`, `CR1`, `CR2`). On the map each lane is drawn as its own parallel line offset from the centreline, so a dual-lane road shows its left and right survey lanes side by side.

**Colour by any parameter.** In the layer's style controls choose the parameter to colour by:

| Parameter | Unit | Good | Fair | Poor |
|---|---|---|---|---|
| IRI (roughness) | m/km | < 2.55 | 2.55 – 3.30 | > 3.30 |
| Cracking | % area | < 5 | 5 – 15 | > 15 |
| Pothole | count | < 1 | 1 – 3 | > 3 |
| Rutting | mm | < 5 | 5 – 10 | > 10 |
| Texture | — | < 1 | 1 – 3 | > 3 |
| Patch work | % | < 5 | 5 – 10 | > 10 |
| Ravelling | % | < 5 | 5 – 10 | > 10 |

The Good/Fair/Poor thresholds are pre-set to IRC guidance but **editable** in the panel (with a "Reset to IRC defaults" button), so you can explore stricter or looser bands without touching the data.

![Figure 4.5 — Condition layer coloured by IRI](images/fig-04-05-condition-iri.png)
*Figure 4.5 — NSV condition segments coloured Good/Fair/Poor by IRI; parallel lines are the survey lanes.*
<!-- SCREENSHOT: Condition layer ON, coloured by IRI, zoomed enough that the parallel lane lines are visible, legend showing Good/Fair/Poor. -->

![Figure 4.6 — Condition at a chainage](images/fig-04-06-condition-inspector.png)
*Figure 4.6 — Clicking a segment shows every distress value for that 100 m block, per lane.*
<!-- SCREENSHOT: Road Inspector open on a condition segment showing the condition table. -->

## 4.5 NSV Survey Video — synchronized playback

This is KLRAMS's signature feature: the Network Survey Vehicle footage plays **in sync with the map**.

**To start:** click a surveyed road → in the Road Inspector press **▶ Play footage**. (The button only shows on roads that have footage in the video catalogue.)

What happens:

1. A **video dock** opens at the bottom of the screen and the survey footage starts playing from the chainage you clicked.
2. A **vehicle marker (car icon)** appears on the road and drives along the centreline in step with the video.
3. The live **HUD** shows the current **chainage** (metres from the road start), the **direction of travel** (Forward = start→end, i.e. increasing chainage; Back = decreasing), and the **lane-wise IRI** at the vehicle's position.
4. A **condition read-out** for the current chainage block updates continuously as the vehicle moves — so you can watch the pavement in the video and read its measured condition at the same instant.

**Dock controls:**

| Control | What it does |
|---|---|
| **Speed** 0.25× / 0.5× / 1× / 1.5× / 2× | Playback speed |
| **Front / Back** | Direction of travel along the road |
| **Seek bar** | Jump to any point; the map marker jumps with it |
| **Follow** | Keeps the map centred on the moving vehicle (the map pans/zooms automatically) |
| **Fullscreen** | Expands the video |

**Chainage synchronisation.** The video is linearly referenced to the road: every video moment maps to a chainage, and every chainage maps to a location on the centreline. Clicking a different point on the same road while the dock is open **seeks the video to that chainage**. The start and end of the footage correspond to the road's start/end locations recorded in the inventory.

**Coverage and gaps.** The inspector's *NSV survey coverage* bar shows the percentage of the road covered by footage and marks any gaps; playback skips over gaps automatically.

![Figure 4.7 — NSV video synchronized with the map](images/fig-04-07-nsv-video-sync.png)
*Figure 4.7 — Survey footage in the dock; the car marker drives the map at the matching chainage with live IRI; speed and Front/Back controls on the dock.*
<!-- SCREENSHOT: video dock open mid-playback: car marker on the road, HUD showing chainage + IRI, dock controls visible. -->

![Figure 4.8 — Follow mode](images/fig-04-08-nsv-follow.png)
*Figure 4.8 — Follow mode keeps the vehicle in view as it drives.*
<!-- SCREENSHOT: same playback with Follow enabled, map zoomed to the car. -->

**NSV Videos catalogue.** The launcher's **NSV Videos** app lists every road with footage — with direction (forward/reverse) and coverage — and opens playback directly.

![Figure 4.9 — NSV video catalogue](images/fig-04-09-nsv-catalogue.png)
*Figure 4.9 — The NSV Videos screen: all surveyed roads, their footage direction and coverage.*
<!-- SCREENSHOT: launcher → NSV Videos screen. -->

## 4.6 Pavement Condition Index (PCI)

KLRAMS computes PCI from the NSV distress data using the **IRC:82** method — no external processing needed.

**Open:** launcher → **PCI** (or the PCI group in Layers).

### Weightages (editable)

Each distress parameter is converted to a 0–100 index and combined using weightages. The defaults follow IRC:82 and can be edited before generating; **Reset to IRC:82** restores them.

| Parameter | Default weight |
|---|---|
| IRI (roughness) | 0.40 |
| Cracking | 0.16 |
| Rut depth | 0.14 |
| Ravelling | 0.12 |
| Patch work | 0.10 |
| Pothole | 0.08 |

![Figure 4.10 — PCI weightages](images/fig-04-10-pci-weightages.png)
*Figure 4.10 — PCI screen, Weightages tab: editable weightage factors and the Generate PCI button.*
<!-- SCREENSHOT: PCI screen, Weightages tab, before/after pressing Generate. -->

### Generate and map

Press **Generate PCI**. Two map layers become available:

- **Composite PCI** — the carriageway-wide index (lane distresses pooled, one PCI).
- **Worst-Lane PCI** — the minimum of the per-lane PCIs (the weakest lane governs).

Segments are coloured by rating band (Appendix C). Clicking a stretch shows a PCI popup with the index, band, per-parameter sub-indices and the recommended intervention.

![Figure 4.11 — PCI map layer](images/fig-04-11-pci-map.png)
*Figure 4.11 — Composite PCI layer with the six-band legend.*
<!-- SCREENSHOT: PCI layer ON after generating, legend showing Excellent…Fail. -->

![Figure 4.12 — PCI popup](images/fig-04-12-pci-popup.png)
*Figure 4.12 — PCI at a stretch: index, band and per-parameter breakdown.*
<!-- SCREENSHOT: click a PCI segment. -->

### PCI Report

The **Report** tab produces the formal output:

- **Per-section** and **per-chainage** tables (every 100 m block with its distresses, sub-indices and PCI).
- A **band distribution bar** across the selected scope.
- **CSV export** and **print/PDF** output for circulation.

![Figure 4.13 — PCI report](images/fig-04-13-pci-report.png)
*Figure 4.13 — PCI Report: per-chainage table with distribution bar; CSV and PDF export.*
<!-- SCREENSHOT: PCI screen → Report tab with a table of chainage rows. -->

## 4.7 FWD — structural condition

The Falling Weight Deflectometer survey measures pavement deflection under a standard load; **D0** (centre deflection, microns) is the primary structural indicator.

Switch on **FWD (D0)** in the Layers panel. Deflection ranges (From–To chainage) are drawn as colour-coded segments along the centreline. Use the **D0 range (microns)** min/max filter in the panel to isolate weak stretches. Click a segment for the full deflection bowl (D0, D200, D300, D600, D900…), load and pavement temperature — every column from the survey CSV is kept and shown.

![Figure 4.14 — FWD D0 layer](images/fig-04-14-fwd-layer.png)
*Figure 4.14 — FWD segments coloured by D0 with the range filter.*
<!-- SCREENSHOT: FWD layer ON with legend, panel showing D0 range filter. -->

![Figure 4.15 — FWD popup](images/fig-04-15-fwd-popup.png)
*Figure 4.15 — FWD segment detail: chainage range and full deflection data.*
<!-- SCREENSHOT: click an FWD segment. -->

## 4.8 Traffic stations

Switch on **Traffic stations**. Stations are placed on their road **by chainage** (falling back to surveyed lat/long). Click a station for the full survey summary:

- **ADT** (average daily traffic) and total volume with survey dates,
- **Peak hour** — volume, time window and dominant direction,
- **Direction split** — totals and per-day volumes each way,
- **Vehicle-class breakdown** (car/jeep/van, two-wheeler, auto, bus, LCV, HCV/truck…),
- Hour-of-day profile.

A **Minimum ADT** filter in the panel hides low-volume stations when you're scanning for heavy corridors.

![Figure 4.16 — Traffic station popup](images/fig-04-16-traffic-station.png)
*Figure 4.16 — Traffic station: ADT, peak hour, direction split, class breakdown.*
<!-- SCREENSHOT: Traffic layer ON, one station popup open. -->

## 4.9 Structures & road furniture

Bridges (line features, From–To chainage), culverts (points at a chainage) and road furniture (points/lines) are placed on the centreline by linear reference. Each type has its own map icon; click for the structure's attributes (name, type, spans, size, condition…).

![Figure 4.17 — Structures on the map](images/fig-04-17-structures.png)
*Figure 4.17 — Bridges, culverts and furniture with a structure popup open.*
<!-- SCREENSHOT: Structures layers ON, zoomed to show icons, one popup open. -->

## 4.10 Asset Register

Launcher → **Asset Register** opens the tabular register with **Road / Bridge / Culvert** tabs — the same data as the map, in sortable table form for inventory review.

![Figure 4.18 — Asset Register](images/fig-04-18-asset-register.png)
*Figure 4.18 — Asset Register: road inventory tab.*
<!-- SCREENSHOT: Asset Register screen, Road tab; optionally a second capture of the Bridge tab. -->

## 4.11 Network Dashboard

Launcher → **Dashboard** opens the network analytics screen with four tabs:

- **Network Overview** — total network length (with the **dual-carriageway correction**: A/B carriageway pairs are averaged so dual roads are not double-counted), as-drawn length, SH/MDR split, network by road class (donut), network by current owner (ranked bars), and the longest SH / MDR roads with a district filter.
- **PCI Analysis** — network-wide PCI distribution and rankings (after generating PCI).
- **Culverts** and **Bridges** — structure inventories and condition summaries.

![Figure 4.19 — Network Overview dashboard](images/fig-04-19-dashboard-overview.png)
*Figure 4.19 — Network Overview: corrected network length, class split, ownership, longest roads.*
<!-- SCREENSHOT: Dashboard → Network Overview tab. -->

![Figure 4.20 — PCI Analysis dashboard](images/fig-04-20-dashboard-pci.png)
*Figure 4.20 — Dashboard → PCI Analysis tab.*
<!-- SCREENSHOT: Dashboard → PCI Analysis after generating PCI. -->

## 4.12 Report Hub

Launcher → **Report Hub** gathers the printable/exportable reports in one place, one tab per dataset: **Condition**, **FWD**, **Sub-Grade Soil**, **Bituminous Core**, **Pavement Crust**, **Traffic** (station & count report), and **Flood**.

![Figure 4.21 — Report Hub](images/fig-04-21-report-hub.png)
*Figure 4.21 — Report Hub with dataset tabs.*
<!-- SCREENSHOT: Report Hub open, e.g. the FWD tab. -->

## 4.13 Search — roads and places

- **Road search** (top search bar): type a road name, road number or section label; matches list instantly; selecting one zooms to the road.
- **Location search** (Base map panel → *Find a location*): search any place name, or type a coordinate like `8.5241, 76.9366` to jump straight there.

![Figure 4.22 — Road search](images/fig-04-22-road-search.png)
*Figure 4.22 — Road search suggestions.*
<!-- SCREENSHOT: search bar with a query typed and the suggestion list open. -->

## 4.14 Base maps

The **Base map** panel switches the background: **Streets (OSM)**, **Satellite (Esri)**, **Terrain (OpenTopo)**, **Light (Carto)**, **Dark — Night**. A background **opacity** slider lets the data stand out over any base.

![Figure 4.23 — Base map switcher](images/fig-04-23-basemaps.png)
*Figure 4.23 — Base map choices and opacity control.*
<!-- SCREENSHOT: Base map panel open; pick Satellite for visual interest. -->

## 4.15 Measure tools

The **Measure** panel offers **Length**, **Area** and **Route** modes. Click on the map to add points; use **Undo point** and **Clear** to correct. Values update live as you draw.

![Figure 4.24 — Measure tool](images/fig-04-24-measure.png)
*Figure 4.24 — Measuring a length along a road.*
<!-- SCREENSHOT: Measure panel with a drawn measurement on the map. -->

## 4.16 Filters

The **Filter** panel applies attribute filters per layer — road network, condition, PCI, FWD, traffic. Choose *show matching only*, combine multiple conditions with **Match all / any**, and clear with one click. (A section is locked until its layer is switched on — use the *Turn on layer* shortcut in the panel.)

![Figure 4.25 — Filters](images/fig-04-25-filters.png)
*Figure 4.25 — Attribute filters, e.g. condition IRI > 3.3 — show matching only.*
<!-- SCREENSHOT: Filter panel with one active filter and the map showing only matching segments. -->

## 4.17 Sahaayi — the KLRAMS assistant

**Ask Sahaayi** (bottom-right) opens the built-in assistant for quick questions about the data and how to use the viewer.

![Figure 4.26 — Sahaayi assistant](images/fig-04-26-sahaayi.png)
*Figure 4.26 — The Sahaayi chat panel.*
<!-- SCREENSHOT: Sahaayi panel open with a sample question/answer. -->

## 4.18 Lite map (no graphics acceleration)

On machines without WebGL (some VDI/remote desktops), use **Lite map** (top-right button, or `/map-lite.html`). It renders the network without graphics acceleration — reduced styling, same data. If the main viewer cannot start WebGL it directs you there automatically.

![Figure 4.27 — Lite map](images/fig-04-27-map-lite.png)
*Figure 4.27 — The Lite viewer.*
<!-- SCREENSHOT: /map-lite.html with the network visible. -->

---

# 5. The Data Console

The Data Console (`/index.html`) is where administrators load and manage every dataset. It has three tabs: **Data counts**, **Data Import Hub**, and **Upload log**. Viewing is open to all staff; **importing and removing data requires ADMIN**.

![Figure 5.1 — Data counts](images/fig-05-01-console-counts.png)
*Figure 5.1 — Data counts: every dataset with its stored row count, plus Refresh and Remove actions.*
<!-- SCREENSHOT: Data Console → Data counts tab with data loaded. -->

## 5.1 Data counts

One row per dataset (roads, condition, segments, FWD, assets, traffic, videos, boundaries…) showing what is currently stored. **Refresh counts** re-reads the database; **Remove current data** (per dataset) clears that dataset — use with care, this cannot be undone.

## 5.2 Data Import Hub

![Figure 5.2 — Data Import Hub](images/fig-05-02-console-import.png)
*Figure 5.2 — The Import Hub: choose the data category, format and file, then Import.*
<!-- SCREENSHOT: Data Console → Data Import Hub tab. -->

Pick the **Data Category**, the file, and any parameters, then press **Import**. The import formats:

### Road network (Shapefile ZIP / GeoJSON)

- A shapefile **.zip** must contain the `.shp`, `.dbf` and `.prj`; it is read in your browser and converted before upload. GeoJSON is accepted directly.
- Every feature **must carry a non-blank `Section_La`** (section label) — the key that all other datasets reference. Geometry must be LineString.
- **Modes:** *Merge* (default) updates existing sections by `Section_La` and adds new ones; *Replace* wipes and reloads the whole network. Validation runs first — a bad file changes nothing.
- Re-importing a road with the same `Section_La` refreshes that road.

### Condition survey (CSV)

Expected columns (header names exactly as below):

```
Survey_Type, Section_Label, XSP, IRI, CRACK, Pothole, Rutting, Texture,
Patch_Work, Ravelling, Start_Chainage, End_Chainage,
Start_Latitude, Start_Longitude, End_Latitude, End_Longitude
```

- `Section_Label` must match the road's `Section_La`. `XSP` is the lane code (`CC`, `CL1`, `CL2`, `CR1`, `CR2`).
- Import is **additive by section**: sections present in the file are replaced; others are kept.
- After importing, press **Build segments** — this cuts the condition rows into map segments along the centreline by chainage (linear referencing). The condition layer reads these segments.

### FWD survey (CSV)

```
Section_Label, Start_Chainage, End_Chainage, D0, D200, D300, D600, D900, ...
```

- Each row is a chainage **range** carrying the deflection bowl; every extra column is preserved and shown in the popup.
- After importing, press **Build FWD segments** to place the ranges on the network.

### Structures & furniture (CSV)

- **Bridges / line assets:** `Section_Label, Start_Chainage, End_Chainage, …attributes`
- **Culverts / point assets:** `Section_Label, Chainage, …attributes` (optional `Latitude`/`Longitude` override the chainage placement)
- All other columns are kept as attributes and shown in the popup.

### Traffic survey

Two files: **stations** (name, road, section, chainage, lat/long, lane) and **counts** (per-station volumes: totals, dates, direction split, class breakdown, hourly profile). Stations are placed by chainage on their section; counts attach by station name.

### Survey video files & catalogue

- **Videos:** upload a **.zip** of the footage files (large uploads resume automatically if interrupted). Videos are stored on the server disk and streamed at `/videos/…`.
- **Catalogue CSV:** links each road to its file:

```
section_label, video_file, direction
```

`direction` is `forward` (footage runs start→end / increasing chainage) or `reverse`. This is what powers the map-synchronized playback and the Front/Back control (§ 4.5).

### Boundaries & Full Road Network

- **District boundary** and **Constituency boundary**: GeoJSON polygon uploads.
- **Full road network (by Road Name)**: the secondary network layer.

> **After any road-network upload** the viewer's cached GeoJSON is refreshed automatically, so the next map load shows the new data. If a layer ever looks stale, reload the page.

## 5.3 Upload log

Every import is logged — who, when, what file, result. Use **Clear finished** to tidy the list.

![Figure 5.3 — Upload log](images/fig-05-03-console-log.png)
*Figure 5.3 — Upload log with the most recent imports.*
<!-- SCREENSHOT: Data Console → Upload log tab after a few imports. -->

---

# 6. GO Portal — Government Orders

The GO Portal (`/go.html`) is the department's searchable Government Order repository. **Viewing and downloading is public**; uploading and managing requires ADMIN.

- **Folders** organise GOs (nested names like `Maintenance Sanctions/2026` are supported).
- **Upload** a GO with its **GO name** and **GO number**; the document itself is stored securely in the database.
- **Search** across GO name, number and filename.
- Click a GO to **view or download** it.
- Admins can delete documents; a folder can be removed only when empty.

![Figure 6.1 — GO Portal](images/fig-06-01-go-portal.png)
*Figure 6.1 — GO Portal: folder tree, document list and search.*
<!-- SCREENSHOT: /go.html with a folder selected and at least one GO listed. -->

---

# 7. User Management & Security

## 7.1 Accounts and roles (SUPER_ADMIN)

**User Management** (`/users.html`) creates and manages staff accounts:

- **Create user** — username, full name, role (**USER / ADMIN / SUPER_ADMIN**) and a temporary password. New users must change the password at first login.
- **Edit** — change role or full name, **enable/disable** an account.
- **Reset password** — issues a new temporary password (forces a change at next login).
- The last enabled SUPER_ADMIN is protected — it cannot be demoted, disabled or deleted.

![Figure 7.1 — User Management](images/fig-07-01-users.png)
*Figure 7.1 — User Management: account list with roles and status; the create-user form.*
<!-- SCREENSHOT: /users.html with 2–3 accounts of different roles. -->

## 7.2 Changing your own password

Any signed-in user can change their password from the account menu (or `/change-password.html`).

## 7.3 Login Activity (SUPER_ADMIN)

`/login-report.html` lists sign-in events — who signed in, when, and from where — for audit.

![Figure 7.2 — Login Activity](images/fig-07-02-login-activity.png)
*Figure 7.2 — Login Activity report.*
<!-- SCREENSHOT: /login-report.html with a few events. -->

## 7.4 What is public and what is protected

| Public (no login) | Protected (login) |
|---|---|
| Public portal, sign-in page | GIS viewer & Lite map |
| GO Portal (viewing/downloading) | Staff home, Data Console (imports: ADMIN) |
| Public site content (About/Contact/FAQ) | User Management, Site Control, Login Activity (SUPER_ADMIN) |

---

# 8. Site Control

**Site Control** (`/admin.html`, SUPER_ADMIN) edits the public portal's content without touching code:

- **About** — the KHRI / RMMS Cell description shown on the public portal.
- **Contact** — address, phone, email block.
- **FAQ** — the public FAQ entries.

Edit and **Save** each section; the public portal updates immediately.

![Figure 8.1 — Site Control](images/fig-08-01-site-control.png)
*Figure 8.1 — Site Control: editing the public About / Contact / FAQ content.*
<!-- SCREENSHOT: /admin.html showing the three editable sections. -->

---

# 9. Troubleshooting & FAQ

**The map is blank / "Enable graphics to view the map".**
Your browser session has no WebGL. Use the **Lite map** button (or `/map-lite.html`), or enable hardware acceleration in the browser settings.

**I imported roads but the map still shows the old network.**
The viewer refreshes its road cache automatically after an upload; simply reload the page. If it persists, use the Data Console's refresh, then reload.

**Condition/FWD data imported but nothing shows on the map.**
Segments must be **built** after import — press *Build segments* (condition) or *Build FWD segments* in the Import Hub. Also confirm the CSV's `Section_Label` values exactly match the roads' `Section_La` — unmatched rows are dropped (the import result reports how many).

**The Play footage button doesn't appear on a road.**
That road has no entry in the video catalogue. Upload the footage zip and the catalogue CSV row linking `section_label` to `video_file`.

**A video plays but the marker/chainage looks wrong-way-round.**
Check the catalogue's `direction` for that road — `forward` means the footage runs from start chainage to end chainage; set `reverse` if it was filmed the other way.

**PCI layers are greyed out.**
PCI must be generated first: open PCI → (adjust weightages if needed) → **Generate PCI**.

**Upload says "roads table has no Section_La column" / features rejected.**
The shapefile/GeoJSON must carry `Section_La` on every feature; fix the attribute table and re-export.

**I can't see the Data Console import buttons.**
Imports require the ADMIN role — your account is USER (view-only). Contact the RMMS Cell.

**Large video zip upload was interrupted.**
Re-run the same upload — it resumes from where it stopped.

---

# Appendix A — Road data dictionary

Attributes carried by each road section (from the PWD road inventory shapefile):

| Field | Meaning | Values / unit |
|---|---|---|
| `Section_La` | **Section label — the unique key** every dataset references | text |
| `Road_Name` | Road name | text |
| `Road_Num` | Road number | e.g. SH 01 |
| `Road_Class` | Classification | `SH` State Highway · `MDR` Major District Road · `ODR` Other District Road · `NH` National Highway |
| `Road_Type` | Lane type | `SLR` Single · `ILR` Intermediate · `TLR` Two Lane · `WTL` Wide Two Lane · `FLR` Four Lane |
| `Single_Du` | Carriageway | `Single` / `Dual` (dual roads are stored as two centrelines, section label suffixed `A` / `B`) |
| `Rd_Str_Loc` / `Rd_End_Loc` | **Start / end location** (place names) | text |
| `Rd_Str_cha` / `Rd_End_cha` | **Start / end chainage** | metres |
| `Measrd_Len` | Measured length | metres |
| `Pavement_W` | Pavement width band | 1: ≥3.75 & <5.5 m · 2: >5.5 & <7 m · 3: ≥7 & <10.5 m · 4: ≥10.5 & ≤12.5 m · 5: >12.5 m |
| `Cons_Type` | Construction | `FLX` Flexible · `RGD` Rigid · `CMP` Composite · `WBM` · `GRV` Gravel · `ERT` Earthen · `PVB` Paver Block |
| `Surface_Ty` | Surface | `BT` Bituminous · `CC` Cement Concrete · `PVB` Paver Block · `WBM` · `GRV` Gravel · `ERT` Earthen |
| `Current_Ow` | Current owner | PWD Section / PWD Maintenance / KRFB / KRFB-PMU / KSTP / RICK / KMRL |
| `PWD_Sec` | PWD section office | text |
| `CRN` | Core road network number | text |
| `District` | District | text |

**Linear referencing rule** — a chainage `c` is placed on the centreline at fraction `c / L`, where the reference length `L` is, in priority order: `Rd_End_cha − Rd_Str_cha`, else `Measrd_Len`, else the geometric length.

# Appendix B — Condition parameters & thresholds

NSV condition is recorded per lane (`XSP`) for every 100 m block (`Start_Chainage`–`End_Chainage`), with start/end coordinates. Default Good/Fair/Poor bands (editable in the viewer):

| Parameter | Unit | Fair from | Poor from |
|---|---|---|---|
| IRI | m/km | 2.55 | 3.30 |
| Crack | % | 5 | 15 |
| Pothole | count | 1 | 3 |
| Rutting | mm | 5 | 10 |
| Texture | — | 1 | 3 |
| Patch work | % | 5 | 10 |
| Ravelling | % | 5 | 10 |

# Appendix C — PCI method, weightages and rating bands

**Method (IRC:82).** Each distress is converted to a 0–100 sub-index; the PCI is the weighted combination using the (editable) weightages — defaults: IRI 0.40, Cracking 0.16, Rut depth 0.14, Ravelling 0.12, Patch work 0.10, Pothole 0.08.

**Lane aggregation.**
- *Worst-Lane PCI* = the minimum of the per-lane PCIs.
- *Composite PCI* = one PCI computed from the lane distresses pooled across the carriageway.

**Rating bands and recommended action:**

| PCI | Rating | Recommended intervention |
|---|---|---|
| 90 – 100 | Excellent | Routine maintenance |
| 80 – 90 | Good | Preventive maintenance |
| 60 – 80 | Satisfactory | Resurfacing (structural check) |
| 40 – 60 | Fair | Minor rehabilitation |
| 20 – 40 | Poor | Major rehabilitation / overlay |
| 0 – 20 | Fail | Reconstruction |

# Appendix D — Figure checklist

Save each screenshot as a PNG with the exact filename below into `docs/user-manual/images/`. Recommended capture: a 1440×900 browser window, signed in as SUPER_ADMIN, with representative data loaded.

| # | Filename | What to capture |
|---|---|---|
| 2.1 | `fig-02-01-login.png` | Sign-in page |
| 2.2 | `fig-02-02-change-password.png` | Change-password page |
| 3.1 | `fig-03-01-public-portal.png` | Public portal (full page) |
| 3.2 | `fig-03-02-home-portal.png` | Staff home with all tiles |
| 4.1 | `fig-04-01-gis-overview.png` | Viewer with road network on, layers panel open |
| 4.2 | `fig-04-02-app-launcher.png` | In-map app launcher open |
| 4.3 | `fig-04-03-network-panel.png` | Network panel: colour-by attribute + legend |
| 4.4 | `fig-04-04-road-inspector.png` | Road Inspector with attributes, chainage, Play footage |
| 4.5 | `fig-04-05-condition-iri.png` | Condition layer coloured by IRI, lanes visible |
| 4.6 | `fig-04-06-condition-inspector.png` | Inspector showing condition values at a chainage |
| 4.7 | `fig-04-07-nsv-video-sync.png` | Video dock playing, car marker + chainage HUD |
| 4.8 | `fig-04-08-nsv-follow.png` | Follow mode zoomed on the vehicle |
| 4.9 | `fig-04-09-nsv-catalogue.png` | NSV Videos catalogue screen |
| 4.10 | `fig-04-10-pci-weightages.png` | PCI Weightages tab |
| 4.11 | `fig-04-11-pci-map.png` | PCI map layer + band legend |
| 4.12 | `fig-04-12-pci-popup.png` | PCI popup on a stretch |
| 4.13 | `fig-04-13-pci-report.png` | PCI Report tab (per-chainage table) |
| 4.14 | `fig-04-14-fwd-layer.png` | FWD D0 layer + range filter |
| 4.15 | `fig-04-15-fwd-popup.png` | FWD segment popup |
| 4.16 | `fig-04-16-traffic-station.png` | Traffic station popup (ADT, peak, split) |
| 4.17 | `fig-04-17-structures.png` | Bridges/culverts/furniture icons + popup |
| 4.18 | `fig-04-18-asset-register.png` | Asset Register screen |
| 4.19 | `fig-04-19-dashboard-overview.png` | Dashboard — Network Overview |
| 4.20 | `fig-04-20-dashboard-pci.png` | Dashboard — PCI Analysis |
| 4.21 | `fig-04-21-report-hub.png` | Report Hub |
| 4.22 | `fig-04-22-road-search.png` | Road search suggestions |
| 4.23 | `fig-04-23-basemaps.png` | Base map panel |
| 4.24 | `fig-04-24-measure.png` | Measure tool in use |
| 4.25 | `fig-04-25-filters.png` | Filter panel with an active filter |
| 4.26 | `fig-04-26-sahaayi.png` | Sahaayi assistant open |
| 4.27 | `fig-04-27-map-lite.png` | Lite map |
| 5.1 | `fig-05-01-console-counts.png` | Data Console — Data counts |
| 5.2 | `fig-05-02-console-import.png` | Data Console — Import Hub |
| 5.3 | `fig-05-03-console-log.png` | Data Console — Upload log |
| 6.1 | `fig-06-01-go-portal.png` | GO Portal |
| 7.1 | `fig-07-01-users.png` | User Management |
| 7.2 | `fig-07-02-login-activity.png` | Login Activity |
| 8.1 | `fig-08-01-site-control.png` | Site Control |

---

<div align="center">

*KLRAMS User Manual · RMMS Cell, Kerala Highway Research Institute · Public Works Department, Government of Kerala*

</div>
