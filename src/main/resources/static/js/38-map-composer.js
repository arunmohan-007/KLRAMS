/* ============================================================
   KLRAMS viewer · 38-map-composer.js
   Map Composer — the engine.

   Turns "what the map is showing" into a print-quality sheet:
   a template, an automatically computed extent and zoom, a legend
   generated from the layers' own symbology, and PDF / PNG export.

   WHAT THIS MODULE IS NOT
   -----------------------
   It is not a second map, a second layer manager or a second filter
   system. Everything it draws is read out of the live viewer:

     · layers        KLLayers (02b-layer-registry) + KLUserLayers (33)
                     + published drone datasets (/api/drone/published)
     · symbology     map.getStyle() — the paint the viewer is using
                     right now, including anything saved in Style &
                     Label Management
     · road filter   window.NET_SCOPE and the saved filters in
                     /api/saved-filters (kind=network), applied through
                     05-road-network.js's own loadNetSaved() path
     · extent        POST /api/composer/extent — PostGIS ST_Extent, so a
                     33 000-section network costs four numbers, not 12 MB

   THE ONE THING IT DOES BUILD is a SECOND MapLibre INSTANCE, offscreen.
   That is deliberate and unavoidable:
     1. the viewer's map is created without preserveDrawingBuffer, so its
        canvas cannot be read back at all (02-map-core.js);
     2. a composed sheet needs the map at a page's pixel size and aspect
        ratio, which is not the shape of anyone's browser window;
     3. rendering into the live map would mean resizing it, moving its
        camera and toggling its layers under the user — precisely the
        "don't disturb the current map" line this feature must not cross.
   The offscreen instance is built FROM the live style, so it inherits
   every colour, width and filter without copying any of that logic.

   Draw order, symbology and per-layer filters therefore need no
   translation layer: what the viewer draws is what the sheet draws, with
   only the template's emphasis multipliers applied on top.

   Loaded after 33/34/37 so the layer, style and filter modules exist.
   The UI lives in 38b-composer-ui.js.
   ============================================================ */
var KLComposer = (function () {
  'use strict';

  /* ==================================================================
     PAGE GEOMETRY

     Every measurement in this module is in MILLIMETRES. Pixels appear
     only at the moment of rasterising, multiplied by one scale factor
     (px per mm). That is what makes the on-screen preview and a 200 dpi
     PDF the same drawing rather than two drawings that have to be kept
     in agreement by hand.
     ================================================================== */
  var PAGES = {
    A4:     { w: 210, h: 297, label: 'A4' },
    A3:     { w: 297, h: 420, label: 'A3' },
    A2:     { w: 420, h: 594, label: 'A2' },
    Letter: { w: 215.9, h: 279.4, label: 'Letter' },
    Screen: { w: 338.7, h: 190.5, label: 'Screen 16:9' }   /* 1280×720 at 96 dpi */
  };

  /** Page rectangle in mm for a size + orientation. Screen is 16:9 by
   *  definition, so "portrait" would mean something no projector wants;
   *  it is left landscape whatever the toggle says. */
  function pageRect(size, orientation, custom) {
    var p = PAGES[size];
    if (size === 'Custom' && custom && custom.w > 20 && custom.h > 20) {
      p = { w: +custom.w, h: +custom.h };
    }
    if (!p) p = PAGES.A4;
    var portrait = (orientation === 'portrait') && size !== 'Screen';
    return portrait ? { w: Math.min(p.w, p.h), h: Math.max(p.w, p.h) }
                    : { w: Math.max(p.w, p.h), h: Math.min(p.w, p.h) };
  }

  /* Render scales. The preview is deliberately coarser than the export —
     it is redrawn on every option change, and a 200 dpi A3 takes a
     noticeable second. Both go through the same draw() call. */
  var DPI_PREVIEW = 96, DPI_PNG = 150, DPI_PDF = 200;
  function pxPerMm(dpi) { return dpi / 25.4; }

  /* WebGL will not allocate an unbounded drawing buffer, and a browser
     that refuses one renders nothing at all rather than something small.
     4096 is the floor MAX_TEXTURE_SIZE guaranteed by any GPU that runs
     the viewer at all; the map frame is clamped to it and upscaled on
     paste, which costs sharpness on an A2 and costs the whole sheet
     otherwise. */
  var MAX_GL_PX = 4000;

  /* ==================================================================
     STATE

     One object, owned here, read by the UI. Deliberately NOT a copy of
     the viewer's state — it holds only the composer's own choices, and
     asks the viewer for everything else at render time, so a layer
     toggled on the map after the Composer was opened is picked up.
     ================================================================== */
  var state = {
    templateId: 'pwd-professional',
    pageSize: 'A4',
    orientation: 'auto',        /* auto | landscape | portrait */
    custom: { w: 297, h: 210 },
    extentMode: 'filter',       /* filter | network | selected | view | layers */
    basemap: 'template',        /* template | same | osm | sat | topo | light | dark | none */
    layers: {},                 /* composer layer id -> true/false; unset = template/viewer default */
    show: {},                   /* per-sheet overrides of the template's show* flags */
    info: {},                   /* title, subtitle, filterText, district, date, source, preparedBy, notes */
    infoTouched: {},            /* fields the user has typed into — never auto-overwritten again */
    /* Legend editing. Keyed by the composer layer id for a whole block, and
       by "<layerId>|<signature>" for one entry, so a choice survives the
       legend being rebuilt — entry ORDER changes when a layer is restyled or
       recoloured, but an entry's own colour+label does not. */
    legendHide: {},
    legendLabel: {},
    /* User-added text boxes — free-standing labels the layout engine
       knows nothing about (a north-facing arrow note, a "DRAFT" stamp, a
       revision mark). Positioned as a fraction of the page (0..1) rather
       than millimetres so a box stays put, proportionally, if the page
       size or orientation changes after it was placed. */
    textItems: []
  };

  function get() { return state; }
  function set(patch) { Object.assign(state, patch || {}); return state; }

  /* ==================================================================
     TEMPLATES

     A template is presentation only. It is loaded from /templates/*.json
     — adding one means dropping a file in and naming it in index.json,
     with no change here. Imported and locally-saved templates live in
     localStorage under the same shape, so they are indistinguishable
     from built-ins once loaded.
     ================================================================== */
  var Templates = (function () {
    var LS_KEY = 'klComposerTemplates';
    var builtin = [], custom = [], loaded = null;
    /* Same rule as the ?v= on every script in map.html: static files here are
       served with the default long cache, so an edited template would go on
       being read from disk cache for days. Bump this when a /templates/ file
       changes. */
    var TPL_V = '2';

    function defaults() {
      return {
        pageSize: 'A4', orientation: 'landscape', margin: 9, basemap: 'light',
        theme: {
          paper: '#ffffff', ink: '#10233d', muted: '#5b6e8a', accent: '#0d5c9e',
          band: '#0d3b66', bandInk: '#ffffff', rule: '#c7d4e4', frame: '#10233d',
          panel: '#f5f8fc', font: '"Segoe UI", Inter, system-ui, sans-serif',
          titleWeight: '700', titleTracking: 0.3, titleCase: 'none'
        },
        header: { show: true, style: 'band', height: 18, align: 'left' },
        logo: { show: true, position: 'header-left', size: 12 },
        legend: { show: true, position: 'right', width: 52, style: 'card', title: 'Legend' },
        northArrow: { show: true, position: 'map-top-right', style: 'classic', size: 14 },
        scaleBar: { show: true, position: 'map-bottom-left', style: 'bar', width: 44 },
        metadata: { show: true, position: 'bottom', height: 14, style: 'strip', columns: 4 },
        grid: { show: false, style: 'ticks', labels: false },
        neatline: { show: true, width: 0.7, double: false, gap: 1.2 },
        mapEmphasis: { road: 1.2, asset: 1.2, boundary: 1, opacity: 1 },
        labels: { show: true }
      };
    }

    /** Deep-merge a loaded template over the defaults, so a partial file
     *  (or a hand-written import) is still a complete template. */
    function normalise(t) {
      var d = defaults(), out = {};
      Object.keys(d).forEach(function (k) {
        out[k] = (d[k] && typeof d[k] === 'object' && !Array.isArray(d[k]))
          ? Object.assign({}, d[k], t[k] || {})
          : (t[k] !== undefined ? t[k] : d[k]);
      });
      out.id = t.id || ('tpl-' + Math.random().toString(36).slice(2, 8));
      out.name = t.name || out.id;
      out.tagline = t.tagline || '';
      out.recommended = !!t.recommended;
      out.custom = !!t.custom;
      return out;
    }

    function readCustom() {
      try {
        var raw = localStorage.getItem(LS_KEY);
        var list = raw ? JSON.parse(raw) : [];
        return Array.isArray(list) ? list.map(function (t) {
          t.custom = true; return normalise(t);
        }) : [];
      } catch (e) { return []; }
    }

    function writeCustom() {
      try { localStorage.setItem(LS_KEY, JSON.stringify(custom)); } catch (e) { /* private mode */ }
    }

    /**
     * Load the library once.
     *
     * A template that 404s or fails to parse is skipped with a console
     * note rather than failing the load: one broken file in the folder
     * must not cost the user the other five.
     */
    function load() {
      if (loaded) return loaded;
      loaded = fetch('templates/index.json?v=' + TPL_V, { credentials: 'same-origin' })
        .then(function (r) { return r.ok ? r.json() : { templates: [] }; })
        .then(function (idx) {
          var ids = (idx && idx.templates) || [];
          return Promise.all(ids.map(function (id) {
            return fetch('templates/' + id + '.json?v=' + TPL_V, { credentials: 'same-origin' })
              .then(function (r) { return r.ok ? r.json() : null; })
              .catch(function () { return null; });
          }));
        })
        .then(function (list) {
          builtin = list.filter(Boolean).map(normalise);
          custom = readCustom();
          if (!builtin.length) builtin = [normalise({ id: 'fallback', name: 'Standard', recommended: true })];
          return all();
        })
        .catch(function () {
          builtin = [normalise({ id: 'fallback', name: 'Standard', recommended: true })];
          custom = readCustom();
          return all();
        });
      return loaded;
    }

    function all() { return builtin.concat(custom); }
    function byId(id) {
      var l = all();
      for (var i = 0; i < l.length; i++) if (l[i].id === id) return l[i];
      return l[0] || normalise({});
    }

    /** Import a template from a JSON file the user picked. Rejected if it
     *  carries anything that is data rather than presentation — a
     *  template with a baked-in filter or feature list would silently
     *  override the user's own choices on every sheet. */
    function importJson(text) {
      var t = JSON.parse(text);
      var banned = ['sections', 'features', 'filter', 'filterId', 'roads', 'data', 'layers'];
      for (var i = 0; i < banned.length; i++) {
        if (t[banned[i]] !== undefined) {
          throw new Error('This file carries map DATA (' + banned[i] + '). A template may only describe layout.');
        }
      }
      t.custom = true;
      var n = normalise(t);
      /* An imported id that collides with a built-in would shadow it. */
      if (builtin.some(function (b) { return b.id === n.id; })) n.id = n.id + '-imported';
      custom = custom.filter(function (c) { return c.id !== n.id; }).concat([n]);
      writeCustom();
      return n;
    }

    /** Save the sheet's current layout choices as a new template. */
    function saveCurrent(name, base, overrides) {
      var t = JSON.parse(JSON.stringify(base));
      Object.assign(t, overrides || {});
      t.id = 'user-' + String(name).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
             + '-' + Math.random().toString(36).slice(2, 5);
      t.name = name;
      t.tagline = 'Saved from the Map Composer';
      t.recommended = false;
      t.custom = true;
      var n = normalise(t);
      custom = custom.concat([n]);
      writeCustom();
      return n;
    }

    function remove(id) {
      custom = custom.filter(function (c) { return c.id !== id; });
      writeCustom();
    }

    function exportJson(id) {
      var t = JSON.parse(JSON.stringify(byId(id)));
      delete t.custom;
      return JSON.stringify(t, null, 2);
    }

    return { load: load, all: all, byId: byId, importJson: importJson,
             saveCurrent: saveCurrent, remove: remove, exportJson: exportJson };
  })();

  /* ==================================================================
     LAYER DISCOVERY

     Nothing here is a hard-coded list of today's layers. Three sources
     are asked what exists, and each of them is the same source the
     viewer itself uses:

       KLLayers      every permanent family, with its label, group, its
                     render layer ids and its loader
       KLUserLayers  Layer Management + temporary layers, by definition
                     not knowable at build time
       /api/drone/published   published orthomosaics, DEMs and contour
                     sets, which live outside map.html entirely

     A layer nobody has switched on yet has no render layers on the map,
     so its family's own ensure() is called before composing — and its
     visibility is put straight back to whatever its checkbox says, so
     including a layer in a SHEET never switches it on in the VIEWER.
     ================================================================== */
  var Layers = (function () {
    var droneCache = null;

    /** Families that are machinery, not map content. */
    var HIDDEN = { 'roadnet-pick': 1, selection: 1, measure: 1 };

    var GROUP_LABEL = {
      network: 'Road network', pavement: 'Pavement condition', assets: 'Road assets',
      traffic: 'Traffic', analysis: 'Analysis', boundaries: 'Administrative boundaries',
      user: 'Temporary & user layers', drone: 'Drone & survey rasters'
    };
    var GROUP_ORDER = ['network', 'pavement', 'assets', 'traffic', 'analysis',
                       'boundaries', 'user', 'drone'];

    function permanent() {
      if (typeof KLLayers === 'undefined') return [];
      return KLLayers.all().filter(function (s) {
        return !HIDDEN[s.key] && s.toggle;
      }).map(function (s) {
        return {
          id: 'fam:' + s.key, kind: 'permanent', key: s.key,
          label: s.label, group: s.group || 'network',
          z: s.z, spec: s,
          on: KLLayers.toggled(s),
          built: KLLayers.ready(s)
        };
      });
    }

    function user() {
      if (!window.KLUserLayers) return [];
      return KLUserLayers.list().map(function (l, i) {
        return {
          id: 'ul:' + l.id, kind: 'user', key: l.key, layerRow: l, index: i,
          label: l.name + (l.temporary ? ' (temporary)' : ''),
          group: 'user',
          z: (typeof KLLayers !== 'undefined' ? KLLayers.Z.SELECTION - 2 : 68),
          on: !!(document.getElementById('showUL' + l.id) || {}).checked,
          built: !!(typeof map !== 'undefined' && map.getSource && map.getSource('ul-' + l.id))
        };
      });
    }

    function drone() {
      return (droneCache || []).map(function (d) {
        return {
          id: 'dr:' + d.id, kind: 'drone', dataset: d,
          label: (d.project_name ? d.project_name + ' · ' : '') + (d.name || ('Dataset ' + d.id)),
          group: 'drone',
          z: (typeof KLLayers !== 'undefined' ? KLLayers.Z.BOUNDARY_FILL - 2 : 8),
          on: false, built: true
        };
      });
    }

    /** Published drone datasets. Fetched once; a viewer with no drone
     *  module or no published data simply gets an empty group. */
    function loadDrone() {
      if (droneCache) return Promise.resolve(droneCache);
      return fetch('/api/drone/published', { credentials: 'same-origin' })
        .then(function (r) { return r.ok ? r.json() : []; })
        .then(function (l) { droneCache = Array.isArray(l) ? l : []; return droneCache; })
        .catch(function () { droneCache = []; return droneCache; });
    }

    /**
     * The Heat Map Analysis surface, when one has been run.
     *
     * Unlike every other entry this is NOT a registered layer family, and it
     * deliberately does not become one: it exists only after the user presses
     * Run, and it is rebuilt from scratch on the next run. Offering it as a
     * permanent tickbox would promise a layer that is usually not there. So it
     * appears in the picker exactly when there is a surface to print, and
     * disappears again when the analysis is cleared.
     *
     * Pre-selected (`on: true`) for the same reason: a user who has just built
     * a heat map and then opens the Composer wants it on the sheet.
     */
    function heat() {
      if (!window.KLHeatmap || !KLHeatmap.active()) return [];
      var info = KLHeatmap.info();
      return [{
        id: 'heat:1', kind: 'heat', info: info,
        label: 'Heat map — ' + (info ? info.datasetLabel : 'analysis'),
        group: 'analysis',
        /* Above the roads and condition it is read against, below the point
           layers, which is where the viewer puts it too — a surface drawn over
           its own stations would hide the evidence for it. */
        z: (typeof KLLayers !== 'undefined' ? KLLayers.Z.ASSET_POINT - 3 : 52),
        on: true, built: true
      }];
    }

    function all() {
      return permanent().concat(user(), heat(), drone());
    }

    /** All layers, grouped and ordered for the picker. */
    function grouped() {
      var by = {};
      all().forEach(function (it) { (by[it.group] = by[it.group] || []).push(it); });
      var out = [];
      GROUP_ORDER.forEach(function (g) {
        if (by[g] && by[g].length) out.push({ group: g, label: GROUP_LABEL[g] || g, items: by[g] });
        delete by[g];
      });
      Object.keys(by).forEach(function (g) {
        out.push({ group: g, label: GROUP_LABEL[g] || g, items: by[g] });
      });
      return out;
    }

    /** Is this layer in the sheet? An explicit choice wins; otherwise the
     *  viewer's own toggle decides, which is why the Composer opens
     *  already showing what the map shows. */
    function selected(it) {
      return (it.id in state.layers) ? !!state.layers[it.id] : !!it.on;
    }

    function selectedItems() { return all().filter(selected); }

    /**
     * Make sure every selected layer actually has render layers on the
     * map, then put visibility back exactly as it was.
     *
     * The restore is the important half. KLLayers.ensure() runs the
     * viewer's real loader, and those loaders add their layers visible —
     * so without the second pass, ticking "Bridges" in the Composer would
     * make bridges appear on the operational map behind it.
     */
    function ensureSelected() {
      var items = selectedItems();
      return Promise.all(items.map(function (it) {
        if (it.kind === 'permanent' && typeof KLLayers !== 'undefined') {
          if (KLLayers.ready(it.spec)) return null;
          return KLLayers.ensure(it.spec)
            .then(function () { KLLayers.setVisible(it.spec, KLLayers.toggled(it.spec)); })
            .catch(function () { /* a layer that will not load is reported by legend()/render() */ });
        }
        if (it.kind === 'user' && window.KLUserLayers) {
          /* KLUserLayers.ensure builds its layers with visibility:'none'
             already (33-user-layers.js addPaintLayers), so there is
             nothing to restore here. */
          return KLUserLayers.ensure(it.layerRow, it.index).catch(function () {});
        }
        return null;
      }).filter(Boolean));
    }

    /** The render layer ids a composer item contributes, in draw order. */
    function renderIds(it) {
      if (it.kind === 'permanent') return (it.spec.layers || []).slice();
      if (it.kind === 'user') return window.KLUserLayers ? KLUserLayers.ids(it.layerRow.id) : [];
      /* The surface plus whichever boundary frame is switched on — real style
         layers, so buildStyle copies them like any other. */
      if (it.kind === 'heat') return window.KLHeatmap ? KLHeatmap.layerIds() : [];
      return [];  /* drone layers are built directly into the composer style */
    }

    return { all: all, grouped: grouped, selected: selected, selectedItems: selectedItems,
             ensureSelected: ensureSelected, renderIds: renderIds, loadDrone: loadDrone,
             groupLabel: function (g) { return GROUP_LABEL[g] || g; } };
  })();

  /* ==================================================================
     EXTENT

     Five modes, one rule: the extent comes from the thing the user named
     and nothing else. In particular a layer with a huge extent — a state
     boundary, an all-Kerala raster — never widens a sheet the user asked
     to be about one filtered district, because in every mode except
     "selected layers" those layers are simply not part of the question.
     ================================================================== */
  var Extent = (function () {

    /** Section labels of the ACTIVE road network filter, or null when
     *  none is applied. This is window.NET_SCOPE — the viewer's own
     *  filter result, not a re-evaluation of it. */
    function scopeSections() {
      if (!window.NET_SCOPE) return null;
      return Array.from(window.NET_SCOPE);
    }

    function post(body) {
      return fetch('/api/composer/extent', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      }).then(function (r) {
        if (!r.ok) throw new Error('extent HTTP ' + r.status);
        return r.json();
      });
    }

    /** Client-side bbox of a GeoJSON FeatureCollection already in hand. */
    function bboxOf(fc) {
      try {
        if (!fc || !fc.features || !fc.features.length || typeof turf === 'undefined') return null;
        var b = turf.bbox(fc);
        return [b[0], b[1], b[2], b[3]].every(isFinite) ? b : null;
      } catch (e) { return null; }
    }

    /** The road(s) currently highlighted by the Road Inspection popup.
     *  Read out of the style rather than the source object so nothing
     *  here depends on a MapLibre private field. */
    function selectionFc() {
      try {
        var s = (map.getStyle().sources || {})['sel-road'];
        return (s && s.data && s.data.features) ? s.data : null;
      } catch (e) { return null; }
    }

    function hasSelection() {
      var fc = selectionFc();
      return !!(fc && fc.features && fc.features.length);
    }

    /**
     * Resolve the chosen extent mode to a bbox.
     *
     * Resolves to {bbox, source, note, empty}. An empty answer is not an
     * error — "this filter matches no roads" is a real thing for a user
     * to have done, and it is said in words rather than drawn as a map of
     * the Indian Ocean.
     */
    function compute(mode) {
      var sections = scopeSections();

      if (mode === 'view') {
        try {
          var b = map.getBounds();
          return Promise.resolve({
            bbox: [b.getWest(), b.getSouth(), b.getEast(), b.getNorth()],
            source: 'Current map view'
          });
        } catch (e) {
          return Promise.resolve({ bbox: null, empty: true, source: 'Current map view',
                                   note: 'The map has no view to copy yet.' });
        }
      }

      if (mode === 'selected') {
        var fc = selectionFc();
        var bb = bboxOf(fc);
        if (!bb) {
          return Promise.resolve({ bbox: null, empty: true, source: 'Selected features',
                                   note: 'Nothing is selected. Click a road on the map first, or choose another extent.' });
        }
        return Promise.resolve({ bbox: bb, source: 'Selected features',
                                 note: fc.features.length + ' selected feature(s)' });
      }

      if (mode === 'layers') {
        var req = { assets: [], userLayers: [], boundaries: [] };
        var droneBoxes = [], anyRoad = false;
        Layers.selectedItems().forEach(function (it) {
          if (it.kind === 'drone') {
            var d = it.dataset;
            if ([d.min_x, d.min_y, d.max_x, d.max_y].every(function (v) { return typeof v === 'number'; })) {
              droneBoxes.push([d.min_x, d.min_y, d.max_x, d.max_y]);
            }
            return;
          }
          if (it.kind === 'user') { req.userLayers.push(it.layerRow.id); return; }
          var k = it.key;
          if (k === 'roadnet' || k === 'condition' || k === 'pci-avg' || k === 'pci-worst' ||
              k === 'iri2km' || k === 'traffic') { anyRoad = true; return; }
          if (k === 'roadnet2') { req.fullNetwork = true; return; }
          if (k === 'district' || k === 'district-label') { req.boundaries.push('district'); return; }
          if (k === 'constituency' || k === 'constituency-label') { req.boundaries.push('constituency'); return; }
          /* Everything else registered under the assets group is a
             road_assets type; its family key is the type name. */
          if (it.group === 'assets') req.assets.push(assetTypeFor(k));
        });
        if (anyRoad) { req.roads = true; if (sections) req.sections = sections; }
        req.assets = req.assets.filter(Boolean);

        if (!req.roads && !req.fullNetwork && !req.assets.length && !req.userLayers.length &&
            !req.boundaries.length && !droneBoxes.length) {
          return Promise.resolve({ bbox: null, empty: true, source: 'Selected layers',
                                   note: 'None of the selected layers can supply an extent.' });
        }
        return post(req).then(function (r) {
          var bb = r.bbox || null;
          droneBoxes.forEach(function (d) { bb = union(bb, d); });
          return { bbox: bb, empty: !bb, source: 'Selected layers',
                   note: bb ? null : 'The selected layers hold no geometry yet.',
                   problems: r.problems };
        });
      }

      /* mode 'filter' and 'network' are the same query with and without
         the scope; keeping them one call means the "no filter is active"
         case cannot drift away from the filtered one. */
      var useScope = (mode === 'filter') && !!sections;
      var body = { roads: true };
      if (useScope) body.sections = sections;
      return post(body).then(function (r) {
        var label = useScope ? 'Filtered road network' : 'Entire road network';
        if (useScope && sections.length === 0) {
          return { bbox: null, empty: true, source: label,
                   note: 'The active Road Network filter matches no roads, so there is nothing to map.' };
        }
        return {
          bbox: r.bbox || null, empty: !r.bbox, source: label,
          note: r.bbox ? (useScope ? sections.length.toLocaleString() + ' road sections in the filter' : null)
                       : 'No road geometry found.',
          problems: r.problems
        };
      });
    }

    /** road_assets type for an asset family key. The registry's keys are
     *  short display keys ('soil', 'core'); the table's asset_type values
     *  are the longer names 06-assets.js uploads under. */
    var ASSET_TYPE = {
      bridge: 'bridge', culvert: 'culvert', fwd: 'fwd',
      soil: 'subgrade', core: 'bituminous_core', crust: 'pavement_crust',
      'furn-line': 'furniture_line', 'furn-pt': 'furniture_point'
    };
    function assetTypeFor(key) {
      if (ASSET_TYPE[key]) return ASSET_TYPE[key];
      /* Fall back to the ASSETS table the viewer itself keeps, so an
         asset type added later needs no edit here. */
      try {
        var spec = KLLayers.get(key);
        var lyr = spec && spec.assetLayer;
        for (var i = 0; typeof ASSETS !== 'undefined' && i < ASSETS.length; i++) {
          if (ASSETS[i].layer === lyr) return ASSETS[i].type;
        }
      } catch (e) { /* no ASSETS in scope */ }
      return null;
    }

    function union(a, b) {
      if (!b) return a;
      if (!a) return b.slice();
      return [Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])];
    }

    /**
     * Make a bbox safe to fit.
     *
     * Three real failure modes, all seen with real KLRAMS data:
     *   · a single point asset, or one very short road, has zero width
     *     or height — fitBounds on that zooms to the maximum the style
     *     allows, which is a grey square;
     *   · a road that runs almost exactly N–S has a width of a few metres
     *     and a height of kilometres, so a raw fit leaves the sheet 98%
     *     empty either side;
     *   · a bad row can put a corner outside Kerala (the server drops the
     *     worst of these, this catches the rest).
     * The minimum span is ~550 m, and the aspect ratio is nudged toward
     * the page's — not all the way, which would misrepresent how narrow
     * the corridor really is, but enough that it reads as a map.
     */
    function normalise(bbox, pageAspect) {
      if (!bbox) return null;
      var minX = Math.min(bbox[0], bbox[2]), maxX = Math.max(bbox[0], bbox[2]);
      var minY = Math.min(bbox[1], bbox[3]), maxY = Math.max(bbox[1], bbox[3]);
      var cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
      var MIN_DEG = 0.005;                       /* ≈ 550 m */
      var w = Math.max(maxX - minX, MIN_DEG);
      var h = Math.max(maxY - minY, MIN_DEG);

      /* Compare in projected-ish units: a degree of longitude is shorter
         than a degree of latitude everywhere but the equator, and Kerala
         is far enough north for that to matter to an aspect test. */
      var kx = Math.cos(cy * Math.PI / 180) || 1;
      var aspect = (w * kx) / h;
      if (pageAspect && isFinite(pageAspect) && aspect > 0) {
        /* Only ever GROW the short side — never crop the data away. */
        if (aspect < pageAspect * 0.42) w = (pageAspect * 0.42) * h / kx;
        else if (aspect > pageAspect * 2.4) h = (w * kx) / (pageAspect * 2.4);
      }
      return [cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2];
    }

    return { compute: compute, normalise: normalise, scopeSections: scopeSections,
             hasSelection: hasSelection, union: union };
  })();

  /* ==================================================================
     SAVED ROAD NETWORK FILTERS

     There is exactly one filter system in KLRAMS and this is not it.
     Selecting a saved filter here drives 05-road-network.js's own state
     (netFilters / netMode / applyNetFilter), which is the same code path
     the Network panel's "Load" button uses — so NET_SCOPE, the on-map
     scope card and every scoped layer stay in agreement with the sheet.

     Consequence, stated plainly in the UI: choosing a filter in the
     Composer also changes the map's filter. The alternative — a private
     copy of the filter evaluated only for the sheet — is the "second
     independent filtering system" this feature is required not to build.
     ================================================================== */
  var Filters = (function () {

    function list() {
      return fetch('/api/saved-filters?kind=network', {
        credentials: 'same-origin', headers: { Accept: 'application/json' }
      }).then(function (r) { return r.ok ? r.json() : []; })
        .then(function (l) { return Array.isArray(l) ? l : []; })
        .catch(function () { return []; });
    }

    /** Human summary of a saved filter's conditions, for the picker. */
    function describe(f) {
      var p = (f && f.payload) || {};
      var rows = Array.isArray(p.rows) ? p.rows : [];
      if (!rows.length) return 'no conditions';
      return rows.map(function (r) { return r.attr + ' ' + (r.op || '=') + ' ' + r.val; })
                 .join(p.mode === 'any' ? '  OR  ' : '  AND  ');
    }

    /** What the map is filtered by right now, as a sentence. */
    function activeText() {
      try {
        if (typeof netFilters === 'undefined') return '';
        var rows = netFilters.filter(function (f) { return f.attr && f.val !== ''; });
        if (!rows.length) return '';
        return rows.map(function (r) { return r.attr + ' ' + r.op + ' ' + r.val; })
                   .join(netMode === 'any' ? ' or ' : ' and ');
      } catch (e) { return ''; }
    }

    function isActive() { return !!activeText(); }

    /** Apply a saved filter through the viewer's own loader. */
    function apply(saved) {
      var sel = document.getElementById('netSavedSel');
      if (!sel || typeof loadNetSaved !== 'function') {
        return Promise.reject(new Error('The Road Network panel is not available on this page.'));
      }
      sel.value = String(saved.id);
      if (typeof onNetSavedPick === 'function') onNetSavedPick();
      loadNetSaved();
      /* applyNetFilter() schedules a fit; NET_SCOPE itself is set
         synchronously, which is all the Composer reads. */
      return Promise.resolve();
    }

    function clear() {
      if (typeof clearNetFilters === 'function') clearNetFilters();
      var sel = document.getElementById('netSavedSel');
      if (sel) sel.value = '';
      return Promise.resolve();
    }

    return { list: list, describe: describe, activeText: activeText, isActive: isActive,
             apply: apply, clear: clear };
  })();

  /* ==================================================================
     LEGEND GENERATION

     Read out of the paint the map is actually using, not out of a table
     of what the layers are believed to look like. That is the only way a
     legend can stay right through Style & Label Management, a colour-by
     change on the road network, or a user layer whose colour depends on
     its position in the list.

     Only SELECTED layers contribute, and only layers that have a colour
     to show: a label-only symbol layer and an invisible click target are
     both correctly absent.
     ================================================================== */
  var Legend = (function () {

    var MAX_PER_LAYER = 14;

    function paintOf(def, prop) {
      try { return (def.paint || {})[prop]; } catch (e) { return undefined; }
    }

    function symbolKind(def) {
      if (def.type === 'line') return 'line';
      if (def.type === 'fill') return 'fill';
      if (def.type === 'circle') return 'point';
      if (def.type === 'fill-extrusion') return 'fill';
      if (def.type === 'raster') return 'raster';
      return null;
    }

    function colorProp(def) {
      if (def.type === 'line') return 'line-color';
      if (def.type === 'fill') return 'fill-color';
      if (def.type === 'circle') return 'circle-color';
      return null;
    }

    /** Is this a colour a canvas can fill with? MapLibre accepts several
     *  spellings; anything else is treated as "expression". */
    function isColorLiteral(v) {
      return typeof v === 'string' && /^(#|rgb|hsl|[a-z]+$)/i.test(v);
    }

    /**
     * Turn one paint value into legend entries.
     *
     * Handles the four expression shapes KLRAMS actually paints with:
     * a literal colour, `match` (categorical — road class, colour-by, an
     * asset's own type), `step` (banded — condition and PCI classes) and
     * `interpolate` (a continuous ramp, drawn as a gradient with its end
     * labels). `case` is unwrapped to the colours it can produce, which
     * is the best a legend can say about a conditional.
     */
    function entriesFrom(value, kind) {
      if (value == null) return [];
      if (isColorLiteral(value)) return [{ kind: kind, color: value, label: null }];
      if (!Array.isArray(value)) return [];

      var op = value[0], out = [], i;

      if (op === 'match') {
        for (i = 2; i + 1 < value.length; i += 2) {
          var label = value[i];
          if (Array.isArray(label)) label = label.join(', ');
          out.push({ kind: kind, color: value[i + 1], label: String(label) });
        }
        var dflt = value[value.length - 1];
        if (isColorLiteral(dflt)) out.push({ kind: kind, color: dflt, label: 'Other' });
        return out;
      }

      if (op === 'step') {
        out.push({ kind: kind, color: value[2], label: null, _pendingUpper: true });
        for (i = 3; i + 1 < value.length; i += 2) {
          out.push({ kind: kind, color: value[i + 1], label: '≥ ' + fmtNum(value[i]) });
        }
        /* The first band has no lower bound of its own — it is
           "everything below the first stop", which only makes sense once
           the second stop is known. */
        if (out.length > 1 && out[0]._pendingUpper) {
          out[0].label = '< ' + fmtNum(value[3]);
          delete out[0]._pendingUpper;
        }
        return out;
      }

      if (op === 'interpolate' || op === 'interpolate-hcl' || op === 'interpolate-lab') {
        var stops = [];
        for (i = 3; i + 1 < value.length; i += 2) {
          if (isColorLiteral(value[i + 1])) stops.push({ at: value[i], color: value[i + 1] });
        }
        if (stops.length >= 2) {
          /* lo/hi are kept as their own fields rather than baked into one
             "a → b" string: the ramp is drawn as a bar with its ends labelled
             underneath, so the two numbers have to be placed independently. */
          return [{
            kind: 'gradient', stops: stops, label: null,
            lo: fmtNum(stops[0].at), hi: fmtNum(stops[stops.length - 1].at)
          }];
        }
        return [];
      }

      /* `case` is where the pavement layers keep their real symbology, so
         reading only its LITERAL branches was silently wrong for four whole
         families at once. Condition, PCI, 2 km IRI and FWD are all shaped
         `['case', <no value?>, grey, <the step that does the work>]` — the
         step is a nested expression, not a colour, so the old loop skipped it
         and legended the entire layer as a single grey "no data" swatch. The
         Good / Fair / Poor and PCI bands never reached the sheet.

         So recurse into any branch that is not a literal.

         The grey branch is then labelled from its own CONDITION rather than
         left blank: `['!',['has',k]]` means exactly "this feature has no
         value", and a legend row saying "No data" beside the grey is the
         whole point of printing it. A `case` whose conditions are positive
         `has` tests labels its DEFAULT the same way, which is the shape FWD
         uses. Anything more complicated stays unlabelled rather than
         inventing a meaning for it. */
      if (op === 'case') {
        var sawHas = false;
        for (i = 2; i < value.length - 1; i += 2) {
          var t = hasTest(value[i - 1]);
          if (t === 1) sawHas = true;
          if (isColorLiteral(value[i])) {
            out.push({ kind: kind, color: value[i], label: t === -1 ? 'No data' : null });
          } else {
            entriesFrom(value[i], kind).forEach(function (e) { out.push(e); });
          }
        }
        var last = value[value.length - 1];
        if (isColorLiteral(last)) {
          out.push({ kind: kind, color: last, label: sawHas ? 'No data' : null });
        } else {
          entriesFrom(last, kind).forEach(function (e) { out.push(e); });
        }
        return out;
      }

      if (op === 'to-color' || op === 'coalesce' || op === 'literal') {
        for (i = 1; i < value.length; i++) {
          var got = entriesFrom(value[i], kind);
          if (got.length) return got;
        }
      }
      return [];
    }

    /** 1 for `['has',k]`, -1 for `['!',['has',k]]`, 0 for anything else. */
    function hasTest(c) {
      if (!Array.isArray(c)) return 0;
      if (c[0] === 'has') return 1;
      if (c[0] === '!' && Array.isArray(c[1]) && c[1][0] === 'has') return -1;
      return 0;
    }

    /** `decimals` caps how many fraction digits survive — defaults to 2, the
     *  same precision the sub-1000 branch always rounded to. Without a cap,
     *  `toLocaleString()` on a number ≥1000 keeps EVERY decimal the raw value
     *  carried (e.g. an ADT of 1071.153512 printed as "1,071.154"), which is
     *  how a traffic count ended up on a legend with a fraction of a
     *  vehicle. Pass 0 for counts (ADT, station totals) that have no
     *  fractional unit at all. */
    function fmtNum(n, decimals) {
      if (typeof n !== 'number') return String(n);
      var d = (decimals == null) ? 2 : decimals;
      var f = Math.pow(10, d);
      return Math.abs(n) >= 1000
        ? n.toLocaleString(undefined, { maximumFractionDigits: d })
        : (Math.round(n * f) / f).toString();
    }

    function widthOf(def) {
      var w = paintOf(def, 'line-width');
      if (typeof w === 'number') return w;
      if (Array.isArray(w)) {
        /* Take the widest stop — a legend swatch drawn at the zoom-8 end
           of an interpolate is a hairline nobody can see. */
        var best = 0;
        w.forEach(function (v) { if (typeof v === 'number') best = Math.max(best, v); });
        if (best) return Math.min(best, 8);
      }
      return def.type === 'line' ? 2 : 0;
    }

    /** One composer item's legend block. */
    function forItem(it, liveLayers) {
      var entries = [], seen = {};

      if (it.kind === 'drone') {
        var d = it.dataset;
        return {
          title: it.label,
          entries: [{ kind: 'raster', color: '#8899aa', label: droneKindLabel(d) }]
        };
      }

      /* The heat map legends itself from the run's own descriptor, NOT from
         its paint. heatmap-color interpolates over `heatmap-density`, which is
         always 0..1 whatever the data, so entriesFrom() would print a ramp
         labelled "0 → 1" — true of the renderer and useless on a sheet. What
         the reader needs is the measure and the range it spans, which is what
         the panel shows in the viewer and what is recorded at run time.

         The fully transparent first ramp stop is dropped: on screen it lets
         the basemap through, but as a legend swatch it would just be a strip
         of blank paper at the cold end. */
      if (it.kind === 'heat') {
        var hi = it.info || (window.KLHeatmap && KLHeatmap.info()) || null;
        if (!hi) return null;
        var stops = (hi.ramp || [])
          .filter(function (s) { return !/,\s*0\s*\)$/.test(String(s[1])); })
          .map(function (s) { return { at: s[0], color: s[1] }; });
        if (stops.length < 2) return null;
        /* Laid out the way the viewer's own panel lays it out, because that
           is the arrangement the reader has already learned:

             Heat map · Sub-Grade Soil      <- block title (the dataset)
             CBR (soaked) · %               <- the measure and its unit
             [======= ramp =======]
             4                  10.1        <- the range, at the ramp's ends

           Cramming all of that onto one heading is what made it unreadable:
           "Heat map · Sub-Grade Soil · CB…" told the reader neither the
           measure nor the range. Split into title, caption and end labels,
           each part is short enough to survive a 46 mm legend column. */
        var isDensity = (hi.mode === 'density');
        /* Vehicle counts (ADT, survey volume, peak-hour volume) are whole
           traffic — "1,071.15 vehicles/day" is not a real measurement, it is
           a rounding artefact of the raw stored value. Fuzzy attributes
           (CBR%, soil moisture...) keep their normal 2-decimal precision. */
        var countUnit = /^veh/i.test(hi.unit || '');
        return {
          key: it.id, group: it.group,
          title: 'Heat map · ' + hi.datasetLabel,
          entries: [{
            kind: 'gradient', stops: stops,
            label: hi.measureLabel + (hi.unit ? ' · ' + hi.unit : ''),
            lo: isDensity ? 'Sparse' : fmtNum(hi.lo, countUnit ? 0 : 2),
            hi: isDensity ? 'Clustered' : fmtNum(hi.hi, countUnit ? 0 : 2)
          }],
          more: 0, flat: false
        };
      }

      Layers.renderIds(it).forEach(function (id) {
        var def = liveLayers[id];
        if (!def) return;
        if ((def.layout || {}).visibility === 'none' && !it.built) { /* still describable */ }
        /* A casing is the halo under a line, not a class of its own. */
        if (/-casing$/.test(id) || /-glow$/.test(id)) return;

        /* Point assets are icons, not circles (06-assets.js builds them from
           inline SVG). Their legend swatch is the icon itself, which is both
           more useful and more honest than a coloured dot standing in for a
           symbol the reader has to match by eye. */
        var icon = (def.layout || {})['icon-image'];
        if (def.type === 'symbol' && typeof icon === 'string') {
          var sig0 = 'icon|' + icon;
          if (!seen[sig0]) { seen[sig0] = 1; entries.push({ kind: 'icon', icon: icon, label: null }); }
          return;
        }

        var kind = symbolKind(def);
        var prop = colorProp(def);
        if (!kind || !prop) return;

        var got = entriesFrom(paintOf(def, prop), kind);
        got.forEach(function (e) {
          e.width = widthOf(def);
          var sig = e.kind + '|' + (e.color || '') + '|' + (e.label || '');
          if (seen[sig]) return;
          seen[sig] = 1;
          entries.push(e);
        });
      });

      if (!entries.length) return null;

      /* PCI greys "no value" AND "negative" with the same colour, so recursing
         the case yields one grey row labelled "No data" and a second identical
         grey with no label at all. Drop an unlabelled entry whenever a labelled
         one already shows that exact swatch — it carries no information the
         labelled row does not, and prints as a blank line. */
      if (entries.some(function (e) { return e.label; })) {
        var labelled = {};
        entries.forEach(function (e) { if (e.label) labelled[e.kind + '|' + (e.color || '')] = 1; });
        entries = entries.filter(function (e) {
          return e.label || !labelled[e.kind + '|' + (e.color || '')];
        });
      }

      /* "No data" is a footnote to the bands, not the first thing to read. */
      entries.sort(function (a, b) {
        return (a.label === 'No data' ? 1 : 0) - (b.label === 'No data' ? 1 : 0);
      });

      /* A layer whose entries are ALL unlabelled has one symbol, not several.
         A polygon family contributes a fill layer and an outline layer, and
         both come back as anonymous swatches — printed as-is that reads
         "District boundary / District boundary / District boundary", a
         heading over two rows that say the same thing. Keep the one that
         carries the most meaning (the fill a reader actually sees, then the
         line, then a point or icon). Blocks whose entries ARE labelled — a
         class breakdown, a colour-by — are untouched. */
      if (entries.length > 1 && entries.every(function (e) { return !e.label; })) {
        var rank = { fill: 0, gradient: 1, line: 2, icon: 3, point: 4, raster: 5 };
        entries.sort(function (a, b) {
          return (rank[a.kind] == null ? 9 : rank[a.kind]) - (rank[b.kind] == null ? 9 : rank[b.kind]);
        });
        entries = entries.slice(0, 1);
      }

      var more = 0;
      if (entries.length > MAX_PER_LAYER) {
        more = entries.length - MAX_PER_LAYER;
        entries = entries.slice(0, MAX_PER_LAYER);
      }

      /* A layer with exactly one unlabelled swatch is its own label —
         "Bridge ▬" rather than a "Bridge" heading over a blank row. */
      var flat = (entries.length === 1 && !entries[0].label);
      return { key: it.id, title: it.label, entries: entries, more: more, flat: flat, group: it.group };
    }

    function droneKindLabel(d) {
      var t = String(d.dataset_type || d.type || '').toUpperCase();
      if (t.indexOf('CONTOUR') >= 0) return 'Contours';
      if (t.indexOf('DEM') >= 0) return 'Digital elevation model';
      if (t.indexOf('ORTHO') >= 0) return 'Orthomosaic';
      return 'Raster';
    }

    /**
     * The whole legend, for the sheet as composed.
     *
     * `liveLayers` is the id→definition index of the CURRENT style, so a
     * layer the user has restyled since the page loaded legends itself
     * correctly.
     */
    function build(items) {
      var liveLayers = {};
      try {
        (map.getStyle().layers || []).forEach(function (l) { liveLayers[l.id] = l; });
      } catch (e) { /* style not ready */ }

      var blocks = [];
      items.forEach(function (it) {
        var b = forItem(it, liveLayers);
        if (b) blocks.push(b);
      });

      /* Name the road network block after whatever it is coloured by, so
         a sheet coloured by District does not present its swatches under
         the bare heading "Road network". */
      try {
        var cb = document.getElementById('netColorBy');
        if (cb && cb.value && cb.value !== '__class__') {
          blocks.forEach(function (b) {
            if (b.title === 'Road network') b.title = 'Road network — by ' + cb.value;
          });
        }
      } catch (e) { /* no colour-by control */ }

      return blocks;
    }

    /**
     * A stable name for one legend entry.
     *
     * Its position in the list is NOT stable — recolouring the road network
     * by a different attribute rewrites the whole list — but its colour and
     * its label together are, so that is what a "hide NH" choice is recorded
     * against. A gradient has no single colour, so it is keyed by its ends.
     */
    function entryKey(e) {
      if (e.kind === 'gradient') {
        return 'grad:' + (e.stops[0] || {}).color + '>' + (e.stops[e.stops.length - 1] || {}).color;
      }
      return e.kind + ':' + (e.icon || e.color || '') + ':' + (e.label || '');
    }

    /**
     * The legend as the user has edited it: blocks they have hidden are
     * gone, entries they have unticked are gone, and anything they have
     * renamed carries the new wording.
     *
     * Applied at DRAW time, not at build time, so the editor in the sidebar
     * can still list everything the layers offer — including the parts that
     * are currently switched off.
     */
    function applyEdits(blocks) {
      var hide = state.legendHide || {}, label = state.legendLabel || {};
      var out = [];
      blocks.forEach(function (b) {
        if (hide[b.key]) return;
        var entries = b.entries.filter(function (e) { return !hide[b.key + '|' + entryKey(e)]; });
        if (!entries.length) return;
        entries = entries.map(function (e) {
          var lk = b.key + '|' + entryKey(e);
          return (lk in label) ? Object.assign({}, e, { label: label[lk] }) : e;
        });
        var flat = (entries.length === 1 && !entries[0].label);
        out.push(Object.assign({}, b, {
          entries: entries, flat: flat,
          title: (b.key in label) ? label[b.key] : b.title
        }));
      });
      return out;
    }

    return { build: build, entriesFrom: entriesFrom, entryKey: entryKey, applyEdits: applyEdits };
  })();

  /* ==================================================================
     COMPOSER STYLE

     The offscreen map's style, assembled from the live one. Copying the
     live definitions rather than re-declaring them is the whole reason
     the sheet matches the screen: filters (including the road-network
     scope, which the viewer AND-s only onto layers that have a genuine
     relationship to a road — see scopePropFor in 05-road-network.js),
     colour-by expressions and any saved style all come along untouched.
     ================================================================== */
  /** Resolve once the viewer's own style is readable. map.getStyle()
   *  returns undefined until the style has loaded, and the Composer can be
   *  opened from the launcher within a second of the page appearing — so
   *  without this, composing races the map and dies on `undefined.glyphs`. */
  function viewerStyleReady() {
    return new Promise(function (resolve) {
      var tries = 0;
      (function poll() {
        try { if (map.getStyle && map.getStyle()) return resolve(); } catch (e) { /* not yet */ }
        if (++tries > 100) return resolve();      /* 10 s, then let it fail loudly */
        setTimeout(poll, 100);
      })();
    });
  }

  function buildStyle(items, tpl, basemapChoice) {
    var live = map.getStyle();
    if (!live) throw new Error('The map is still loading. Try again in a moment.');
    var theme = tpl.theme || {};
    /* glyphs/sprite are only set when the live style HAS them. Copying an
       undefined sprite through is not harmless: the style then fails
       validation ("sprite: string expected, undefined found"), the map's
       `load` event never fires, and the compose sits waiting for a signal
       that is never coming until the hard timeout rescues it. */
    var style = { version: 8, sources: {}, layers: [] };
    if (live.glyphs) style.glyphs = live.glyphs;
    if (live.sprite) style.sprite = live.sprite;

    /* Paper under everything: with the basemap off this IS the map's
       background, and with a partly-transparent basemap it stops the
       page showing through as black. */
    style.layers.push({
      id: 'mc-paper', type: 'background',
      paint: { 'background-color': theme.paper || '#ffffff' }
    });

    /* ---- basemap ---- */
    var bm = basemapChoice === 'template' ? (tpl.basemap || 'light') : basemapChoice;
    if (bm === 'same') {
      bm = 'osm';
      try {
        (typeof BASEMAPS !== 'undefined' ? BASEMAPS : []).forEach(function (b) {
          if (map.getLayer(b) && map.getLayoutProperty(b, 'visibility') !== 'none') bm = b;
        });
      } catch (e) { /* keep osm */ }
    }
    if (bm && bm !== 'none' && live.sources[bm]) {
      style.sources[bm] = JSON.parse(JSON.stringify(live.sources[bm]));
      style.layers.push({ id: 'mc-base', type: 'raster', source: bm, paint: { 'raster-opacity': 1 } });
    }

    /* ---- drone rasters, under the vector layers ---- */
    items.filter(function (it) { return it.kind === 'drone'; }).forEach(function (it) {
      var d = it.dataset;
      if (!d || d.contour_count > 0 && !d.build_version) return;
      var sid = 'mc-dr-' + d.id;
      style.sources[sid] = {
        type: 'raster', tileSize: 256,
        tiles: [location.origin + '/api/drone/datasets/' + d.id + '/tiles/{z}/{x}/{y}.png?b=' + d.build_version],
        minzoom: d.min_zoom != null ? d.min_zoom : 0,
        maxzoom: d.max_zoom != null ? d.max_zoom : 22,
        bounds: [d.min_x, d.min_y, d.max_x, d.max_y]
      };
      style.layers.push({
        id: sid + '-lyr', type: 'raster', source: sid,
        paint: { 'raster-opacity': 1, 'raster-resampling': 'nearest' }
      });
    });

    /* ---- the selected viewer layers, in declared draw order ---- */
    var liveById = {};
    (live.layers || []).forEach(function (l) { liveById[l.id] = l; });

    var wanted = [];
    items.filter(function (it) { return it.kind !== 'drone'; }).forEach(function (it) {
      Layers.renderIds(it).forEach(function (id) {
        var def = liveById[id];
        if (!def) return;
        wanted.push({ z: it.z, item: it, def: def });
      });
    });
    /* Stable sort by declared z; within a family the registry's own order
       (casing before line, line before icon) is preserved because
       Array.prototype.sort is stable in every engine that runs this. */
    wanted.sort(function (a, b) { return a.z - b.z; });

    var emph = tpl.mapEmphasis || {};
    wanted.forEach(function (w) {
      var def = JSON.parse(JSON.stringify(w.def));
      def.layout = def.layout || {};
      def.layout.visibility = 'visible';
      if (!tpl.labels || tpl.labels.show === false) {
        if (def.type === 'symbol') return;   /* labels suppressed by the template */
      }
      if (style.sources[def.source] === undefined && live.sources[def.source]) {
        style.sources[def.source] = JSON.parse(JSON.stringify(live.sources[def.source]));
      }
      if (!style.sources[def.source]) return;   /* source vanished — skip rather than throw */

      applyEmphasis(def, w.item, emph);
      style.layers.push(def);
    });

    return style;
  }

  /**
   * Print emphasis.
   *
   * A line width tuned for a 96 dpi screen disappears on paper, and an
   * inspection sheet wants its culverts to read louder than its
   * carriageways. The template says by how much; this multiplies the
   * layer's own width or radius, whether that is a number or a
   * zoom-driven expression — `['*', <expr>, k]` is valid wherever the
   * expression it wraps returns a number, which every width expression
   * in the viewer does.
   */
  function applyEmphasis(def, item, emph) {
    var k = 1;
    if (item.group === 'assets' || item.group === 'traffic') k = emph.asset || 1;
    /* The heat item's only strokes ARE a boundary frame — the surface itself
       has no width to scale — so it takes the boundary multiplier, not the
       road one it would otherwise fall through to. */
    else if (item.group === 'boundaries' || item.group === 'analysis') k = emph.boundary || 1;
    else if (item.group === 'user' || item.group === 'drone') k = 1;
    else k = emph.road || 1;
    if (k === 1) return;

    def.paint = def.paint || {};
    ['line-width', 'circle-radius', 'circle-stroke-width', 'line-gap-width'].forEach(function (p) {
      if (def.paint[p] === undefined) {
        if (p === 'line-width' && def.type === 'line') def.paint[p] = 1 * k;
        return;
      }
      def.paint[p] = scaleNumeric(def.paint[p], k);
    });
    /* Icons scale by their layout size, not a paint property. */
    if (def.type === 'symbol' && def.layout && def.layout['icon-size'] !== undefined) {
      def.layout['icon-size'] = scaleNumeric(def.layout['icon-size'], k);
    }
  }

  /**
   * Multiply a paint value by k, whatever shape it is.
   *
   * The obvious implementation — `['*', <the expression>, k]` — is
   * INVALID for every width expression in this viewer, and MapLibre says
   * so out loud: "zoom expression may only be used as input to a
   * top-level step or interpolate". Wrapping an interpolate in a
   * multiply buries the zoom reference one level down, the layer is
   * rejected, and the road network silently does not paint.
   *
   * So the multiplication is pushed down onto the OUTPUTS of the
   * expression instead, leaving its shape (and its top-level zoom input)
   * exactly as MapLibre requires. Anything not recognised is returned
   * untouched — a layer at its screen width is a small cosmetic loss; a
   * layer rejected by the style validator is a missing layer.
   */
  function scaleNumeric(v, k) {
    if (typeof v === 'number') return v * k;
    if (!Array.isArray(v) || !v.length) return v;
    var op = v[0], out, i;

    if (op === 'interpolate' || op === 'interpolate-hcl' || op === 'interpolate-lab') {
      /* ['interpolate', <interpolation>, <input>, stop, out, stop, out…] */
      out = v.slice(0, 3);
      for (i = 3; i < v.length; i += 2) {
        out.push(v[i], scaleNumeric(v[i + 1], k));
      }
      return out;
    }
    if (op === 'step') {
      /* ['step', <input>, out0, stop, out, stop, out…] */
      out = [v[0], v[1], scaleNumeric(v[2], k)];
      for (i = 3; i < v.length; i += 2) {
        out.push(v[i], scaleNumeric(v[i + 1], k));
      }
      return out;
    }
    if (op === 'case') {
      out = [v[0]];
      for (i = 1; i < v.length - 1; i += 2) out.push(v[i], scaleNumeric(v[i + 1], k));
      out.push(scaleNumeric(v[v.length - 1], k));
      return out;
    }
    if (op === 'match') {
      out = [v[0], v[1]];
      for (i = 2; i < v.length - 1; i += 2) out.push(v[i], scaleNumeric(v[i + 1], k));
      out.push(scaleNumeric(v[v.length - 1], k));
      return out;
    }
    if (op === 'literal') return v;
    /* A plain arithmetic or property expression carries no zoom input, so
       a multiply around it is legal. */
    return ['*', v, k];
  }

  /* ==================================================================
     OFFSCREEN MAP

     One instance, kept alive between renders — building a MapLibre map
     costs a WebGL context, and browsers cap how many of those a page may
     hold. It is rebuilt only when the style changes, which it does on
     every compose, so in practice this is "remove the old one first,
     always", written so a failure to remove cannot leak a context.
     ================================================================== */
  var gl = null;

  function glHost(w, h) {
    var el = document.getElementById('mcGlHost');
    if (!el) {
      el = document.createElement('div');
      el.id = 'mcGlHost';
      /* Not display:none — a zero-size container gives a zero-size
         drawing buffer and the sheet comes out blank. Parked behind the
         page at zero opacity instead, which still renders. */
      el.style.cssText = 'position:fixed;left:0;top:0;z-index:-1;opacity:0;pointer-events:none;overflow:hidden';
      document.body.appendChild(el);
    }
    el.style.width = w + 'px';
    el.style.height = h + 'px';
    return el;
  }

  /**
   * Carry the viewer's icons across to the offscreen map.
   *
   * Every point asset — culvert, soil test, core, furniture — is drawn as a
   * SYMBOL layer with an `icon-image`, and those images are not part of any
   * sprite sheet: 06-assets.js builds them from inline SVG at runtime and
   * registers them with map.addImage(). They therefore exist only on the
   * live map. Copy the layer definition without the image and MapLibre
   * draws nothing at all — the composed sheet loses every point asset while
   * cheerfully reporting no error.
   */
  function copyImages() {
    if (!gl || typeof map === 'undefined' || !map.listImages) return;
    var ids;
    try { ids = map.listImages() || []; } catch (e) { return; }
    ids.forEach(function (id) {
      try {
        if (gl.hasImage(id)) return;
        var im = map.getImage(id);
        if (!im || !im.data) return;
        gl.addImage(id, im.data, { pixelRatio: im.pixelRatio || 1, sdf: !!im.sdf });
      } catch (e) { /* one icon short is not worth failing the sheet for */ }
    });
  }

  function disposeGl() {
    if (!gl) return;
    try { gl.remove(); } catch (e) { /* already gone */ }
    gl = null;
  }

  /**
   * Render the map frame offscreen and hand back the live map object.
   *
   * Resolves once MapLibre says `idle` — every tile for the view has
   * arrived and nothing is animating. On a network where the external
   * basemap is unreachable that event never comes, so there is a hard
   * timeout: a sheet with the data layers and no basemap under them is a
   * far better answer than a spinner that never stops. (Same reasoning as
   * the Drone Viewer booting off `styledata` rather than `load`.)
   */
  function renderFrame(style, bbox, wPx, hPx, padPx) {
    return new Promise(function (resolve, reject) {
      var host = glHost(wPx, hPx);
      disposeGl();

      var errors = [];
      try {
        gl = new maplibregl.Map({
          container: host,
          style: style,
          interactive: false,
          attributionControl: false,
          preserveDrawingBuffer: true,   /* the entire point — see the header */
          fadeDuration: 0,
          center: [76.95, 8.52],
          zoom: 8
        });
      } catch (e) {
        reject(new Error('The map could not be created for export: ' + e.message));
        return;
      }

      var settled = false;
      var hardStop = setTimeout(function () { finish('timeout'); }, 30000);

      gl.on('error', function (e) {
        var m = (e && e.error && e.error.message) || 'unknown';
        if (errors.indexOf(m) < 0 && errors.length < 6) errors.push(m);
      });

      gl.once('load', function () {
        copyImages();
        try {
          gl.resize();
          gl.fitBounds([[bbox[0], bbox[1]], [bbox[2], bbox[3]]], {
            padding: padPx, animate: false, duration: 0, maxZoom: 17
          });
        } catch (e) { /* fall through with the default camera */ }
        /* `idle` is the correct signal; the fallback catches a basemap
           host that never answers. */
        var soft = setTimeout(function () { finish('tiles'); }, 14000);
        gl.once('idle', function () { clearTimeout(soft); finish('idle'); });
      });

      function finish(why) {
        if (settled) return;
        settled = true;
        clearTimeout(hardStop);
        if (!gl) { reject(new Error('The map was closed before it finished drawing.')); return; }
        /* One more frame, so preserveDrawingBuffer is holding the
           finished picture rather than the one before the last tile.
           Waited for with rAF where rAF is running and a timer where it
           is not: a tab the browser has stopped compositing (backgrounded,
           or a hidden panel) never fires rAF at all, and hanging there
           forever is a worse answer than a sheet drawn from whatever the
           buffer already holds. */
        try { gl.triggerRepaint(); } catch (e) { /* ignore */ }
        var delivered = false;
        var deliver = function () {
          if (delivered) return;
          delivered = true;
          clearTimeout(settle);
          if (!gl) { reject(new Error('The map was closed before it finished drawing.')); return; }
          resolve({ map: gl, canvas: gl.getCanvas(), why: why, errors: errors });
        };
        var settle = setTimeout(deliver, 400);
        try {
          requestAnimationFrame(function () { requestAnimationFrame(deliver); });
        } catch (e) { /* the timer covers it */ }
      }
    });
  }

  /* ==================================================================
     LAYOUT ENGINE

     Works purely in millimetres against the page, and knows nothing
     about canvases. Its job is the one the user is promised they will
     not have to do by hand: decide how much room the legend needs, take
     it out of the map rather than putting it on top of something, shrink
     a long title instead of letting it run off the sheet, and keep the
     map above a usable share of the page whatever else is asked for.
     ================================================================== */
  var Layout = (function () {

    /* Text measurement happens at a fixed internal scale and is divided
       back down, so a layout computed for the preview is identical to the
       one computed for a 200 dpi export. */
    var S0 = 4;
    var mctx = null;
    function measure(text, mmSize, weight, font) {
      if (!mctx) mctx = document.createElement('canvas').getContext('2d');
      mctx.font = (weight || '400') + ' ' + (mmSize * S0) + 'px ' + (font || 'sans-serif');
      return mctx.measureText(String(text == null ? '' : text)).width / S0;
    }

    /** Greedy word wrap to a width in mm. Returns the lines. */
    function wrap(text, mmWidth, mmSize, weight, font, maxLines) {
      var words = String(text == null ? '' : text).split(/\s+/).filter(Boolean);
      var lines = [], cur = '';
      for (var i = 0; i < words.length; i++) {
        var next = cur ? cur + ' ' + words[i] : words[i];
        if (cur && measure(next, mmSize, weight, font) > mmWidth) {
          lines.push(cur); cur = words[i];
          if (maxLines && lines.length >= maxLines) break;
        } else cur = next;
      }
      if (cur && (!maxLines || lines.length < maxLines)) lines.push(cur);
      if (maxLines && lines.length >= maxLines) {
        var rest = words.slice(lines.join(' ').split(/\s+/).length).join(' ');
        if (rest) lines[lines.length - 1] = lines[lines.length - 1] + '…';
      }
      return lines.length ? lines : [''];
    }

    /**
     * Title sizing: shrink before wrapping, wrap before truncating.
     *
     * Starts at the size the template wants and steps down to a floor
     * that is still legibly a title. Only then does it allow a second
     * line, because a two-line title costs the map vertical space.
     */
    function fitTitle(text, mmWidth, startMm, floorMm, weight, font) {
      var size = startMm;
      while (size > floorMm && measure(text, size, weight, font) > mmWidth) size -= 0.4;
      var lines = wrap(text, mmWidth, size, weight, font, 3);
      return { size: size, lines: lines };
    }

    /**
     * How tall a legend of these blocks is at a given width and row size.
     *
     * Returns columns too: a legend that will not fit in one column is
     * flowed into two before anything is thrown away, which is what a
     * sheet with a colour-by on District (fourteen swatches) needs.
     */
    function legendMetrics(blocks, widthMm, rowMm, fontMm, font, maxHeightMm, allowColumns, hasHead) {
      var titleMm = fontMm * 1.12;
      var padY = 3.2, padX = 3.0, gap = 2.2;
      /* The card's own heading ("LEGEND") and the rule under it. Counted
         here because drawLegend() draws it before the first row: leaving it
         out made every card about 7 mm shorter than its contents, so the
         last row or two fell past the bottom and were reported as "+N more"
         on a sheet with plenty of room for them. */
      var headMm = hasHead ? (fontMm * 2.1 + 0.8) : 0;
      var chrome = padY * 2 + headMm;      /* everything that is not a row */

      /* Rows are built against the width of ONE COLUMN, because that is what
         the text has to fit into — and the column width depends on how many
         columns there are. So the row list is rebuilt per candidate column
         count rather than measured once at full width and reused; measuring a
         two-column legend against the full card width is what would let a
         wrapped title overrun its column. */
      var SW = 6.4;            /* swatch width, mirrored in drawLegend */
      var BAR = 2.9;           /* ramp bar height */
      function buildRows(cols) {
        var colTextW = Math.max(8, widthMm / cols - 2 * padX);
        var rows = [];
        blocks.forEach(function (b, bi) {
          if (bi) rows.push({ t: 'gap', h: gap });
          if (!b.flat) {
            /* Titles WRAP now instead of being cut off with an ellipsis. A
               legend heading that reads "Heat map · Sub-Grade Soil · CB…" has
               lost the very thing it exists to say. */
            var tl = wrap(b.title, colTextW, titleMm, '700', font, 4);
            rows.push({ t: 'title', h: tl.length * titleMm * 1.5, block: b, lines: tl });
          }
          b.entries.forEach(function (e) {
            if (e.kind === 'gradient') {
              /* A ramp is drawn across the full column width with its ends
                 labelled underneath — a 6.4 mm swatch cannot show a six-stop
                 gradient at all, which is why the colours "were not showing".*/
              var cap = e.label ? wrap(e.label, colTextW, fontMm, '600', font, 3) : [];
              var hasEnds = (e.lo != null || e.hi != null);
              rows.push({
                t: 'grad', entry: e, block: b, lines: cap,
                h: cap.length * fontMm * 1.4 + (cap.length ? 0.8 : 0) +
                   BAR + (hasEnds ? (0.9 + fontMm * 1.25) : 0) + 1.2
              });
              return;
            }
            var txt = e.label || (b.flat ? b.title : '');
            var lw = colTextW - (b.flat ? 0 : 1.4) - SW - 2.2;
            var ll = txt ? wrap(txt, Math.max(6, lw), fontMm, '400', font, 3) : [''];
            rows.push({
              t: 'entry', entry: e, block: b, flat: b.flat, lines: ll,
              h: Math.max(rowMm, ll.length * fontMm * 1.45 + 1.4)
            });
          });
          if (b.more) rows.push({ t: 'more', h: rowMm * 0.9, block: b });
        });
        return rows;
      }

      var cols = 1, rows = buildRows(1), rowsMm = 0;
      rows.forEach(function (r) { rowsMm += r.h; });
      var total = chrome + rowsMm;

      if (allowColumns && maxHeightMm && total > maxHeightMm) {
        for (var nc = 2; nc <= 3; nc++) {
          var trial = buildRows(nc), tm = 0;
          trial.forEach(function (r) { tm += r.h; });
          if (chrome + tm / nc <= maxHeightMm) { cols = nc; rows = trial; rowsMm = tm; break; }
          cols = nc; rows = trial; rowsMm = tm;
        }
      }
      var colH = chrome + rowsMm / cols;

      return {
        rows: rows, cols: cols, rowMm: rowMm, fontMm: fontMm, titleMm: titleMm,
        padX: padX, padY: padY, width: widthMm, headMm: headMm, swW: SW, barMm: BAR,
        height: Math.min(colH, maxHeightMm || colH),
        naturalHeight: chrome + rowsMm,
        overflows: !!(maxHeightMm && colH > maxHeightMm + 0.4)
      };
    }

    /**
     * Fit the legend into the space available, degrading in the order a
     * cartographer would: full size, then tighter rows, then a smaller
     * font, then two columns, and only then "+N more".
     */
    function fitLegend(blocks, widthMm, maxHeightMm, font, allowColumns, hasHead) {
      var attempts = [
        { row: 5.0, fs: 2.7 }, { row: 4.5, fs: 2.55 }, { row: 4.1, fs: 2.4 },
        { row: 3.8, fs: 2.25 }, { row: 3.5, fs: 2.1 }
      ];
      var m = null;
      for (var i = 0; i < attempts.length; i++) {
        m = legendMetrics(blocks, widthMm, attempts[i].row, attempts[i].fs, font, maxHeightMm, false, hasHead);
        if (!m.overflows) return m;
      }
      if (allowColumns) {
        m = legendMetrics(blocks, widthMm, 3.8, 2.25, font, maxHeightMm, true, hasHead);
        if (!m.overflows) return m;
      }
      /* Still too tall: drop entries, worst-represented block first, so
         no layer disappears from the legend entirely. */
      var trimmed = blocks.map(function (b) { return Object.assign({}, b, { entries: b.entries.slice() }); });
      var guard = 200;
      while (guard-- > 0) {
        m = legendMetrics(trimmed, widthMm, 3.5, 2.1, font, maxHeightMm, allowColumns, hasHead);
        if (!m.overflows) break;
        var biggest = null;
        trimmed.forEach(function (b) { if (!biggest || b.entries.length > biggest.entries.length) biggest = b; });
        if (!biggest || biggest.entries.length <= 1) break;
        biggest.entries.pop();
        biggest.more = (biggest.more || 0) + 1;
      }
      return m;
    }

    /**
     * The whole sheet.
     *
     * Order matters: bands come off the page first, then the legend
     * column, and the map is whatever is left. The map is never allowed
     * below 46% of the content width — beyond that the legend has stopped
     * being an aid to a map and become the point of the page.
     */
    function compose(tpl, opts, ctx) {
      var page = pageRect(opts.pageSize, opts.orientation, opts.custom);
      var th = tpl.theme || {};
      var font = th.font || 'sans-serif';
      var m = tpl.margin != null ? tpl.margin : 9;

      var L = { page: page, margin: m, font: font, theme: th, tpl: tpl };
      var x0 = m, y0 = m, x1 = page.w - m, y1 = page.h - m;

      /* ---- header ---- */
      var hdr = tpl.header || {};
      var overlayHeader = hdr.style === 'overlay';
      L.header = null;
      if (opts.showHeader !== false && hdr.show !== false && !overlayHeader) {
        var titleW = (x1 - x0) - (tpl.logo && tpl.logo.show ? (tpl.logo.size || 12) + 6 : 0) - 4;
        var t = fitTitle(ctx.title || 'Map', titleW, 6.4, 3.6, th.titleWeight || '700', font);
        var subLines = ctx.subtitle ? wrap(ctx.subtitle, titleW, 3.1, '500', font, 2) : [];
        var metaLines = ctx.filterText ? wrap(ctx.filterText, titleW, 2.7, '500', font, 2) : [];
        var need = 4.2 + t.lines.length * t.size * 1.22
                       + subLines.length * 3.1 * 1.35
                       + metaLines.length * 2.7 * 1.4 + 3.2;
        var h = Math.max(hdr.height || 18, need);
        L.header = { x: x0, y: y0, w: x1 - x0, h: h, style: hdr.style || 'band',
                     align: hdr.align || 'left', title: t, subLines: subLines, metaLines: metaLines };
        y0 += h + 3.5;
      } else if (overlayHeader) {
        L.overlayTitle = fitTitle(ctx.title || 'Map', page.w * 0.45, 5.4, 3.4, th.titleWeight || '600', font);
        L.overlaySub = ctx.subtitle ? wrap(ctx.subtitle, page.w * 0.45, 2.9, '500', font, 2) : [];
      }

      /* ---- metadata strip ---- */
      var md = tpl.metadata || {};
      L.meta = null;
      if (opts.showMetadata !== false && md.show !== false && md.position === 'bottom') {
        var mh = md.height || 13;
        L.meta = { x: x0, y: y1 - mh, w: x1 - x0, h: mh, style: md.style || 'strip',
                   columns: md.columns || 4 };
        y1 -= mh + 3;
      }

      /* ---- scale bar on its own line under the map ---- */
      var sb = tpl.scaleBar || {};
      var scaleBelow = (opts.showScale !== false && sb.show !== false && sb.position === 'below-map');
      if (scaleBelow) y1 -= 9;

      /* ---- legend column ---- */
      var lg = tpl.legend || {};
      var wantLegend = opts.showLegend !== false && lg.show !== false && ctx.legend && ctx.legend.length;
      var colSide = (lg.position === 'left' || lg.position === 'right') ? lg.position : null;
      L.legend = null;

      var mapX = x0, mapY = y0, mapW = x1 - x0, mapH = y1 - y0;

      var hasHead = !!(lg.title && String(lg.title).trim());

      if (wantLegend && colSide) {
        var want = lg.width || 52;
        var maxCol = (x1 - x0) * 0.42;                 /* the map keeps at least 58% */
        var colW = Math.min(want, maxCol);
        var met = fitLegend(ctx.legend, colW - 2 * 3.0, mapH, font, true, hasHead);
        /* A legend that fits in far less than the template asked for gets
           the space back to the map — a three-entry legend in a 54 mm
           column is a lot of white paper. */
        if (met.naturalHeight < mapH * 0.5 && met.cols === 1) {
          var needW = 0;
          ctx.legend.forEach(function (b) {
            needW = Math.max(needW, measure(b.title, met.fontMm * 1.12, '700', font) + 2 * 3.0);
            b.entries.forEach(function (e) {
              needW = Math.max(needW, measure(e.label || b.title, met.fontMm, '400', font) + 12);
            });
          });
          colW = Math.max(Math.min(colW, needW + 4), 30);
          met = fitLegend(ctx.legend, colW - 2 * 3.0, mapH, font, true, hasHead);
        }
        if (met.cols > 1) colW = Math.min(colW * met.cols, maxCol);

        mapW = (x1 - x0) - colW - 3.5;
        if (colSide === 'right') {
          L.legend = { x: x0 + mapW + 3.5, y: y0, w: colW, h: Math.min(met.height, mapH),
                       metrics: met, style: lg.style || 'card', title: lg.title, floating: false };
        } else {
          mapX = x0 + colW + 3.5;
          L.legend = { x: x0, y: y0, w: colW, h: Math.min(met.height, mapH),
                       metrics: met, style: lg.style || 'card', title: lg.title, floating: false };
        }
      }

      L.map = { x: mapX, y: mapY, w: mapW, h: mapH };
      if (scaleBelow) L.scaleBelow = { x: mapX, y: mapY + mapH + 2.5, w: mapW, h: 6.5 };

      /* ---- floating legend over the map ---- */
      if (wantLegend && !colSide) {
        var fw = Math.min(lg.width || 46, mapW * 0.36);
        var fmet = fitLegend(ctx.legend, fw - 2 * 3.0, mapH * 0.74, font, false, hasHead);
        L.legend = { w: fw, h: Math.min(fmet.height, mapH * 0.74), metrics: fmet,
                     style: lg.style || 'floating', title: lg.title, floating: true,
                     position: lg.position };
      }

      /* ---- map furniture ---- */
      L.north = (opts.showNorth !== false && (tpl.northArrow || {}).show !== false)
        ? { size: (tpl.northArrow || {}).size || 14, style: (tpl.northArrow || {}).style || 'classic',
            position: (tpl.northArrow || {}).position || 'map-top-right' }
        : null;
      L.scale = (opts.showScale !== false && sb.show !== false)
        ? { width: sb.width || 44, style: sb.style || 'bar',
            position: scaleBelow ? 'below-map' : (sb.position || 'map-bottom-left') }
        : null;
      L.grid = (opts.showGrid != null ? opts.showGrid : (tpl.grid || {}).show)
        ? { style: (tpl.grid || {}).style || 'ticks', labels: (tpl.grid || {}).labels !== false }
        : null;
      L.neatline = tpl.neatline || { show: true, width: 0.6 };
      L.logo = (opts.showLogo !== false && (tpl.logo || {}).show)
        ? { size: (tpl.logo || {}).size || 12, position: (tpl.logo || {}).position || 'header-left' }
        : null;
      L.textItems = ctx.textItems || [];

      return L;
    }

    return { compose: compose, measure: measure, wrap: wrap, fitLegend: fitLegend };
  })();

  /* ==================================================================
     CANVAS RENDERER

     Everything is drawn here, including the preview — there is no second
     HTML rendering of the sheet that could disagree with the export. The
     preview is this canvas at 96 dpi; the PNG is it at 150; the PDF
     embeds it at 200.
     ================================================================== */
  var logoImg = null, logoTried = false;

  function loadLogo() {
    if (logoTried) return Promise.resolve(logoImg);
    logoTried = true;
    return new Promise(function (resolve) {
      var im = new Image();
      im.onload = function () { logoImg = im; resolve(im); };
      im.onerror = function () { resolve(null); };
      im.src = '/img/kerala-emblem.png';   /* same origin, so the canvas stays readable */
    });
  }

  function roundRect(c, x, y, w, h, r) {
    r = Math.min(r, w / 2, h / 2);
    c.beginPath();
    c.moveTo(x + r, y);
    c.arcTo(x + w, y, x + w, y + h, r);
    c.arcTo(x + w, y + h, x, y + h, r);
    c.arcTo(x, y + h, x, y, r);
    c.arcTo(x, y, x + w, y, r);
    c.closePath();
  }

  /**
   * Which corner of the map has the least on it.
   *
   * Sampling the rendered frame is the only honest way to answer "do not
   * cover anything important" — the alternative is a fixed corner that is
   * right for one road and wrong for the next. Each candidate box is
   * sampled on a coarse grid and scored by how much of it differs from
   * the frame's most common colour, which for any basemap is the
   * background.
   */
  function quietestCorner(canvas, boxes) {
    var c, best = null;
    try {
      c = document.createElement('canvas');
      var SW = 120, SH = Math.max(1, Math.round(120 * canvas.height / canvas.width));
      c.width = SW; c.height = SH;
      var cx = c.getContext('2d', { willReadFrequently: true });
      cx.drawImage(canvas, 0, 0, SW, SH);
      var img = cx.getImageData(0, 0, SW, SH).data;

      /* Modal colour, bucketed coarsely — the paper or the basemap wash. */
      var hist = {}, modal = null, modalN = -1;
      for (var i = 0; i < img.length; i += 4) {
        var key = (img[i] >> 4) + ',' + (img[i + 1] >> 4) + ',' + (img[i + 2] >> 4);
        hist[key] = (hist[key] || 0) + 1;
        if (hist[key] > modalN) { modalN = hist[key]; modal = key; }
      }
      var mp = modal.split(',').map(Number);

      boxes.forEach(function (b) {
        var x0 = Math.max(0, Math.floor(b.rx * SW)), x1 = Math.min(SW, Math.ceil((b.rx + b.rw) * SW));
        var y0 = Math.max(0, Math.floor(b.ry * SH)), y1 = Math.min(SH, Math.ceil((b.ry + b.rh) * SH));
        var ink = 0, n = 0;
        for (var y = y0; y < y1; y++) {
          for (var x = x0; x < x1; x++) {
            var o = (y * SW + x) * 4;
            var d = Math.abs((img[o] >> 4) - mp[0]) + Math.abs((img[o + 1] >> 4) - mp[1]) + Math.abs((img[o + 2] >> 4) - mp[2]);
            if (d > 2) ink++;
            n++;
          }
        }
        var score = n ? ink / n : 1;
        /* Tie-break toward the bottom-left, the conventional home of a
           legend on an Indian PWD sheet. */
        score += (b.bias || 0);
        if (!best || score < best.score) best = { box: b, score: score };
      });
    } catch (e) {
      best = { box: boxes[0], score: 0 };   /* tainted or unreadable — fall back */
    }
    return best ? best.box : boxes[0];
  }

  /** A round number of metres that fills roughly the requested bar width. */
  function niceScale(targetM) {
    var pow = Math.pow(10, Math.floor(Math.log10(Math.max(1, targetM))));
    var cands = [1, 2, 2.5, 5, 10].map(function (k) { return k * pow; });
    var best = cands[0];
    cands.forEach(function (v) {
      if (Math.abs(v - targetM) < Math.abs(best - targetM)) best = v;
    });
    return best;
  }

  function fmtDistance(m) {
    if (m >= 1000) return (m / 1000 % 1 === 0 ? (m / 1000) : (m / 1000).toFixed(1)) + ' km';
    return Math.round(m) + ' m';
  }

  /**
   * Draw the whole sheet.
   *
   * `frame` carries the offscreen map (still alive, so its project() can
   * place graticule lines exactly) and its canvas.
   */
  function draw(canvas, L, ctx2, frame, S) {
    var c = canvas.getContext('2d');
    var th = L.theme, font = L.font;
    var mm = function (v) { return v * S; };
    function setFont(sizeMm, weight) { c.font = (weight || '400') + ' ' + mm(sizeMm) + 'px ' + font; }

    c.save();
    c.fillStyle = th.paper || '#fff';
    c.fillRect(0, 0, canvas.width, canvas.height);

    /* ---------------- header ---------------- */
    if (L.header) {
      var H = L.header;
      if (H.style === 'band') {
        var g = c.createLinearGradient(mm(H.x), mm(H.y), mm(H.x + H.w), mm(H.y));
        g.addColorStop(0, th.band || '#0d3b66');
        g.addColorStop(1, th.bandTo || th.band || '#0d3b66');
        c.fillStyle = g;
        roundRect(c, mm(H.x), mm(H.y), mm(H.w), mm(H.h), mm(1.6));
        c.fill();
      } else if (H.style === 'rule') {
        c.strokeStyle = th.frame || '#000';
        c.lineWidth = Math.max(1, mm(0.5));
        c.beginPath();
        c.moveTo(mm(H.x), mm(H.y + H.h)); c.lineTo(mm(H.x + H.w), mm(H.y + H.h));
        c.stroke();
      }

      var inkC = (H.style === 'band') ? (th.bandInk || '#fff') : (th.ink || '#111');
      var mutedC = (H.style === 'band') ? hexAlpha(th.bandInk || '#fff', 0.78) : (th.muted || '#667');
      var tx = H.x + 4, avail = H.w - 8;

      /* Logo inside the header band. */
      if (L.logo && /^header-/.test(L.logo.position) && logoImg) {
        var ls = L.logo.size;
        var ly = H.y + (H.h - ls) / 2;
        if (L.logo.position === 'header-left') {
          drawLogo(c, mm(H.x + 4), mm(ly), mm(ls), H.style === 'band');
          tx = H.x + 4 + ls + 4; avail = H.w - (ls + 12);
        } else {
          drawLogo(c, mm(H.x + H.w - 4 - ls), mm(ly), mm(ls), H.style === 'band');
          avail = H.w - (ls + 12);
        }
      }

      var cy = H.y + 4.6;
      c.textBaseline = 'alphabetic';
      c.textAlign = (H.align === 'center') ? 'center' : 'left';
      var ax = (H.align === 'center') ? (H.x + H.w / 2) : tx;

      c.fillStyle = inkC;
      H.title.lines.forEach(function (ln) {
        setFont(H.title.size, th.titleWeight || '700');
        var txt = (th.titleCase === 'upper') ? ln.toUpperCase() : ln;
        if (th.titleTracking) drawTracked(c, txt, mm(ax), mm(cy + H.title.size * 0.82), mm(th.titleTracking), H.align === 'center');
        else c.fillText(txt, mm(ax), mm(cy + H.title.size * 0.82));
        cy += H.title.size * 1.22;
      });
      cy += 0.6;
      c.fillStyle = mutedC;
      H.subLines.forEach(function (ln) {
        setFont(3.1, '500');
        c.fillText(ln, mm(ax), mm(cy + 2.4));
        cy += 3.1 * 1.35;
      });
      H.metaLines.forEach(function (ln) {
        setFont(2.7, '600');
        c.fillStyle = (H.style === 'band') ? hexAlpha(th.bandInk || '#fff', 0.92) : (th.accent || '#0d5c9e');
        c.fillText(ln, mm(ax), mm(cy + 2.2));
        cy += 2.7 * 1.4;
      });
      c.textAlign = 'left';
    }

    /* ---------------- map frame ---------------- */
    var M = L.map;
    c.save();
    roundRect(c, mm(M.x), mm(M.y), mm(M.w), mm(M.h), 0);
    c.clip();
    if (frame && frame.canvas) {
      try {
        c.drawImage(frame.canvas, mm(M.x), mm(M.y), mm(M.w), mm(M.h));
      } catch (e) {
        c.fillStyle = '#eef2f6';
        c.fillRect(mm(M.x), mm(M.y), mm(M.w), mm(M.h));
      }
    } else {
      c.fillStyle = '#eef2f6';
      c.fillRect(mm(M.x), mm(M.y), mm(M.w), mm(M.h));
    }

    /* graticule / ticks, projected through the offscreen map so the
       lines land where the Mercator projection actually puts them */
    if (L.grid && frame && frame.map) drawGrid(c, L, frame, S, mm);
    c.restore();

    /* neatline */
    if (L.neatline && L.neatline.show !== false) {
      c.strokeStyle = th.frame || '#111';
      c.lineWidth = Math.max(1, mm(L.neatline.width || 0.6));
      c.strokeRect(mm(M.x), mm(M.y), mm(M.w), mm(M.h));
      if (L.neatline.double) {
        var gp = L.neatline.gap || 1.2;
        c.lineWidth = Math.max(1, mm((L.neatline.width || 0.6) * 0.5));
        c.strokeRect(mm(M.x - gp), mm(M.y - gp), mm(M.w + 2 * gp), mm(M.h + 2 * gp));
      }
    }

    /* ---------------- overlay title (Minimal Modern) ---------------- */
    if (L.overlayTitle) {
      var oy = M.y + M.h - 6 - (L.overlaySub.length * 3.4);
      var ow = 0;
      L.overlayTitle.lines.forEach(function (ln) {
        ow = Math.max(ow, Layout.measure(ln, L.overlayTitle.size, '600', font));
      });
      L.overlaySub.forEach(function (ln) { ow = Math.max(ow, Layout.measure(ln, 2.9, '400', font)); });
      var oh = L.overlayTitle.lines.length * L.overlayTitle.size * 1.25 + L.overlaySub.length * 3.6 + 5;
      c.fillStyle = th.panel || 'rgba(255,255,255,.92)';
      roundRect(c, mm(M.x + 5), mm(oy - L.overlayTitle.lines.length * L.overlayTitle.size * 1.25 - 1),
                mm(ow + 8), mm(oh), mm(1.6));
      c.fill();
      var ty = oy - L.overlayTitle.lines.length * L.overlayTitle.size * 1.25 + 3;
      c.fillStyle = th.ink;
      L.overlayTitle.lines.forEach(function (ln) {
        setFont(L.overlayTitle.size, '600');
        c.fillText(ln, mm(M.x + 9), mm(ty + L.overlayTitle.size * 0.8));
        ty += L.overlayTitle.size * 1.25;
      });
      c.fillStyle = th.muted;
      L.overlaySub.forEach(function (ln) {
        setFont(2.9, '400');
        c.fillText(ln, mm(M.x + 9), mm(ty + 2.2));
        ty += 3.6;
      });
    }

    /* ---------------- floating legend placement ---------------- */
    if (L.legend && L.legend.floating) {
      var pad = 4;
      var lw = L.legend.w, lh = L.legend.h;
      var cands;
      if (L.legend.position === 'map-auto') {
        cands = [
          { name: 'bl', x: M.x + pad, y: M.y + M.h - lh - pad, bias: -0.04 },
          { name: 'br', x: M.x + M.w - lw - pad, y: M.y + M.h - lh - pad, bias: 0 },
          { name: 'tl', x: M.x + pad, y: M.y + pad, bias: 0.02 },
          { name: 'tr', x: M.x + M.w - lw - pad, y: M.y + pad, bias: 0.06 }
        ];
      } else {
        var right = /right$/.test(L.legend.position), bottom = /bottom/.test(L.legend.position);
        cands = [{ name: 'fixed',
                   x: right ? (M.x + M.w - lw - pad) : (M.x + pad),
                   y: bottom ? (M.y + M.h - lh - pad) : (M.y + pad) }];
      }
      cands.forEach(function (b) {
        b.rx = (b.x - M.x) / M.w; b.ry = (b.y - M.y) / M.h;
        b.rw = lw / M.w; b.rh = lh / M.h;
      });
      var pick = (cands.length > 1 && frame && frame.canvas)
        ? quietestCorner(frame.canvas, cands) : cands[0];
      L.legend.x = pick.x; L.legend.y = pick.y;
      L.legend._corner = pick.name;
    }

    if (L.legend) drawLegend(c, L, S, mm);

    /* ---------------- north arrow ---------------- */
    if (L.north) {
      var np = furniturePos(L, L.north.position, L.north.size, L.north.size * 1.28, frame);
      drawNorth(c, np.x, np.y, L.north.size, L.north.style, th, S, mm);
    }

    /* ---------------- scale bar ---------------- */
    if (L.scale && frame && frame.map) drawScale(c, L, frame, S, mm);

    /* ---------------- metadata ---------------- */
    if (L.meta) drawMeta(c, L, ctx2, S, mm);
    else if ((L.tpl.metadata || {}).position === 'overlay-bottom') drawMetaOverlay(c, L, ctx2, S, mm);

    /* ---------------- logo outside the header ---------------- */
    if (L.logo && /^map-/.test(L.logo.position) && logoImg) {
      var lp = furniturePos(L, L.logo.position, L.logo.size, L.logo.size, frame);
      drawLogo(c, mm(lp.x), mm(lp.y), mm(L.logo.size), false);
    }

    /* ---------------- user text boxes ---------------- */
    if (L.textItems && L.textItems.length) drawTextItems(c, L, S, mm);

    c.restore();
  }

  /**
   * User-added text — the one thing on the sheet with no layout logic at
   * all. Every other element is placed by the template; these are placed
   * by the person building the sheet, anywhere they like, so the draw is
   * just "put this string at this fraction of the page" with the four
   * properties a sheet author actually reaches for: size, colour, bold,
   * underline. Wrapped to whatever's left of the page at its x, the same
   * greedy wrap() the title and legend use, so a long note does not run
   * off the sheet unread.
   */
  function drawTextItems(c, L, S, mm) {
    var page = L.page, font = L.font;
    L.textItems.forEach(function (t) {
      if (!t.text) return;
      var sizeMm = t.size || 4;
      var weight = t.bold ? '700' : '400';
      var x = (t.x != null ? t.x : 0.1) * page.w;
      var y = (t.y != null ? t.y : 0.1) * page.h;
      var maxW = Math.max(20, page.w - x - L.margin);
      /* Wrap paragraph by paragraph, not the whole string at once — wrap()
         treats any run of whitespace as one space, so a blank line the user
         typed on purpose (separating a heading from a note) would otherwise
         collapse away. */
      var lines = [];
      String(t.text).split(/\n/).forEach(function (para) {
        if (!para) { lines.push(''); return; }
        Layout.wrap(para, maxW, sizeMm, weight, font, 20).forEach(function (ln) { lines.push(ln); });
      });
      c.save();
      c.font = weight + ' ' + mm(sizeMm) + 'px ' + font;
      c.fillStyle = t.color || '#111111';
      c.textAlign = t.align === 'center' ? 'center' : (t.align === 'right' ? 'right' : 'left');
      c.textBaseline = 'alphabetic';
      var lineH = sizeMm * 1.3;
      var tx = c.textAlign === 'center' ? x + maxW / 2 : (c.textAlign === 'right' ? x + maxW : x);
      lines.forEach(function (ln, i) {
        var ly = y + sizeMm * 0.85 + i * lineH;
        c.fillText(ln, mm(tx), mm(ly));
        if (t.underline) {
          var w = Layout.measure(ln, sizeMm, weight, font);
          var ux = c.textAlign === 'center' ? tx - w / 2 : (c.textAlign === 'right' ? tx - w : tx);
          c.strokeStyle = t.color || '#111111';
          c.lineWidth = Math.max(1, mm(sizeMm * 0.045));
          c.beginPath();
          c.moveTo(mm(ux), mm(ly + sizeMm * 0.14));
          c.lineTo(mm(ux + w), mm(ly + sizeMm * 0.14));
          c.stroke();
        }
      });
      c.restore();
    });
  }

  function drawLogo(c, x, y, size, onDark) {
    if (!logoImg) return;
    c.save();
    if (onDark) {
      c.fillStyle = 'rgba(255,255,255,.94)';
      roundRect(c, x - size * 0.09, y - size * 0.09, size * 1.18, size * 1.18, size * 0.16);
      c.fill();
    }
    var r = logoImg.width / logoImg.height;
    var w = r >= 1 ? size : size * r, h = r >= 1 ? size / r : size;
    c.drawImage(logoImg, x + (size - w) / 2, y + (size - h) / 2, w, h);
    c.restore();
  }

  /** Letter-spaced text, which canvas has no property for. */
  function drawTracked(c, text, x, y, track, centred) {
    var chars = String(text).split('');
    var total = 0;
    chars.forEach(function (ch) { total += c.measureText(ch).width + track; });
    total -= track;
    var cx = centred ? x - total / 2 : x;
    chars.forEach(function (ch) {
      c.fillText(ch, cx, y);
      cx += c.measureText(ch).width + track;
    });
  }

  /** Position a small furniture item against the map rect. */
  function furniturePos(L, position, w, h, frame) {
    var M = L.map, pad = 4.5;
    var top = /top/.test(position), left = /left/.test(position);
    return {
      x: left ? (M.x + pad) : (M.x + M.w - w - pad),
      y: top ? (M.y + pad) : (M.y + M.h - h - pad)
    };
  }

  function drawLegend(c, L, S, mm) {
    var lg = L.legend, met = lg.metrics, th = L.theme, font = L.font;
    var x = lg.x, y = lg.y, w = lg.w;
    var colW = w / met.cols;
    var colH = lg.h;

    /* card */
    if (lg.style !== 'bare') {
      c.save();
      if (lg.floating) {
        c.shadowColor = 'rgba(15,25,40,.22)';
        c.shadowBlur = mm(1.6);
        c.shadowOffsetY = mm(0.5);
      }
      c.fillStyle = th.panel || '#f5f8fc';
      roundRect(c, mm(x), mm(y), mm(w), mm(lg.h), mm(lg.floating ? 1.8 : 1.2));
      c.fill();
      c.restore();
      c.strokeStyle = th.rule || '#ccd7e4';
      c.lineWidth = Math.max(1, mm(0.28));
      roundRect(c, mm(x), mm(y), mm(w), mm(lg.h), mm(lg.floating ? 1.8 : 1.2));
      c.stroke();
    }

    var cx = x + met.padX, cy = y + met.padY, col = 0;
    /* The heading's height is met.headMm, measured by legendMetrics — drawing
       it by its own arithmetic here is what made the card and its contents
       disagree, and every legend lose its last rows to a "+N more". */
    if (met.headMm) {
      c.fillStyle = th.accent || '#0d5c9e';
      c.font = '700 ' + mm(met.fontMm * 1.05) + 'px ' + font;
      c.fillText(String(lg.title).toUpperCase(), mm(cx), mm(cy + met.fontMm));
      c.strokeStyle = th.rule || '#ccd7e4';
      c.lineWidth = Math.max(1, mm(0.22));
      c.beginPath();
      c.moveTo(mm(cx), mm(cy + met.headMm - 1.2));
      c.lineTo(mm(x + w - met.padX), mm(cy + met.headMm - 1.2));
      c.stroke();
      cy += met.headMm;
    }

    /* Clip to the card.
       fitLegend() shrinks, then columns, then trims entries — but the width
       it measured against can be clamped afterwards (a two-column legend is
       capped so the map keeps 58% of the sheet), and a row or two can still
       fall past the bottom. Clipping guarantees the failure mode is "the
       legend says +N more" rather than "swatches printed over the map". */
    c.save();
    roundRect(c, mm(x), mm(y), mm(w), mm(lg.h), mm(lg.floating ? 1.8 : 1.2));
    c.clip();

    var limit = y + colH - met.padY;
    var stopped = -1;
    met.rows.forEach(function (r, ri) {
      if (stopped >= 0) return;
      if (cy + r.h > limit && col < met.cols - 1) {
        col++; cx = x + met.padX + col * colW; cy = y + met.padY + met.headMm;
      }
      /* Out of columns and out of room: stop here and say how much was left,
         rather than drawing rows the clip would silently swallow. */
      if (cy + r.h > limit) { stopped = ri; return; }
      if (r.t === 'gap') { cy += r.h; return; }

      if (r.t === 'title') {
        c.fillStyle = th.ink || '#111';
        c.font = '700 ' + mm(met.titleMm) + 'px ' + font;
        (r.lines || [r.block.title]).forEach(function (ln, li) {
          c.fillText(ln, mm(cx), mm(cy + met.titleMm * (0.92 + li * 1.5)));
        });
        cy += r.h;
        return;
      }

      /* A colour ramp, drawn the width of the column with its ends labelled
         underneath — the arrangement the viewer's own heat-map legend uses. */
      if (r.t === 'grad') {
        var e0 = r.entry, tw = colW - 2 * met.padX, gy = cy;
        if (r.lines && r.lines.length) {
          c.fillStyle = th.ink || '#111';
          c.font = '600 ' + mm(met.fontMm) + 'px ' + font;
          r.lines.forEach(function (ln, li) {
            c.fillText(ln, mm(cx), mm(gy + met.fontMm * (0.95 + li * 1.4)));
          });
          gy += r.lines.length * met.fontMm * 1.4 + 0.8;
        }
        var gr = c.createLinearGradient(mm(cx), 0, mm(cx + tw), 0);
        var n0 = e0.stops.length - 1;
        e0.stops.forEach(function (s, i) { gr.addColorStop(n0 ? i / n0 : 0, s.color); });
        c.fillStyle = gr;
        roundRect(c, mm(cx), mm(gy), mm(tw), mm(met.barMm), mm(0.5));
        c.fill();
        c.strokeStyle = th.rule || '#ccd7e4';
        c.lineWidth = Math.max(1, mm(0.2));
        roundRect(c, mm(cx), mm(gy), mm(tw), mm(met.barMm), mm(0.5));
        c.stroke();
        gy += met.barMm;
        if (e0.lo != null || e0.hi != null) {
          c.fillStyle = th.muted || '#667';
          c.font = '600 ' + mm(met.fontMm * 0.95) + 'px ' + font;
          var by = mm(gy + 0.9 + met.fontMm * 0.95);
          if (e0.lo != null) { c.textAlign = 'left';  c.fillText(String(e0.lo), mm(cx), by); }
          if (e0.hi != null) { c.textAlign = 'right'; c.fillText(String(e0.hi), mm(cx + tw), by); }
          c.textAlign = 'left';
        }
        cy += r.h;
        return;
      }
      if (r.t === 'more') {
        c.fillStyle = th.muted || '#667';
        c.font = 'italic 400 ' + mm(met.fontMm * 0.92) + 'px ' + font;
        c.fillText('+' + r.block.more + ' more', mm(cx + 7), mm(cy + met.fontMm * 0.9));
        cy += r.h;
        return;
      }

      var e = r.entry;
      var swX = cx + (r.flat ? 0 : 1.4), swW = met.swW, lines = r.lines || [''];
      /* One line centres in its row, as before. A label that WRAPS aligns its
         swatch to the first line instead — centring a swatch on a three-line
         row floats it beside the middle line, reading as if it belonged to
         nothing. */
      var lineH = met.fontMm * 1.45;
      var firstMid = (lines.length <= 1) ? (cy + r.h / 2) : (cy + 0.7 + lineH / 2);
      drawSwatch(c, e, swX, firstMid, swW, S, mm, th);
      c.fillStyle = th.ink || '#111';
      c.font = '400 ' + mm(met.fontMm) + 'px ' + font;
      lines.forEach(function (ln, li) {
        c.fillText(ln, mm(swX + swW + 2.2), mm(firstMid + met.fontMm * 0.36 + li * lineH));
      });
      cy += r.h;
    });

    if (stopped >= 0) {
      var left = met.rows.slice(stopped).filter(function (r) { return r.t === 'entry'; }).length;
      if (left) {
        c.fillStyle = th.muted || '#667';
        c.font = 'italic 600 ' + mm(met.fontMm * 0.92) + 'px ' + font;
        /* Painted over the last drawn row's space, inside the clip. */
        c.fillText('+' + left + ' more', mm(x + met.padX + col * colW),
                   mm(Math.min(cy, y + lg.h - 1.4)));
      }
    }
    c.restore();
  }

  function drawSwatch(c, e, x, midY, w, S, mm, th) {
    c.save();
    if (e.kind === 'gradient') {
      var g = c.createLinearGradient(mm(x), 0, mm(x + w), 0);
      var n = e.stops.length - 1;
      e.stops.forEach(function (s, i) { g.addColorStop(n ? i / n : 0, s.color); });
      c.fillStyle = g;
      roundRect(c, mm(x), mm(midY - 1.2), mm(w), mm(2.4), mm(0.5));
      c.fill();
    } else if (e.kind === 'line') {
      c.strokeStyle = e.color || '#888';
      c.lineWidth = Math.max(1.2, mm(Math.min(2.2, (e.width || 2) * 0.34)));
      c.lineCap = 'round';
      c.beginPath(); c.moveTo(mm(x), mm(midY)); c.lineTo(mm(x + w), mm(midY)); c.stroke();
    } else if (e.kind === 'fill') {
      c.fillStyle = e.color || '#888';
      c.globalAlpha = 0.55;
      roundRect(c, mm(x), mm(midY - 1.6), mm(w), mm(3.2), mm(0.4));
      c.fill();
      c.globalAlpha = 1;
      c.strokeStyle = e.color || '#888';
      c.lineWidth = Math.max(1, mm(0.25));
      c.stroke();
    } else if (e.kind === 'icon') {
      var im = iconCanvas(e.icon);
      if (im) {
        var side = mm(4.2);
        c.drawImage(im, mm(x + w / 2) - side / 2, mm(midY) - side / 2, side, side);
      } else {
        c.fillStyle = '#8899aa';
        c.beginPath(); c.arc(mm(x + w / 2), mm(midY), mm(1.4), 0, Math.PI * 2); c.fill();
      }
    } else if (e.kind === 'raster') {
      var gg = c.createLinearGradient(mm(x), mm(midY - 1.6), mm(x + w), mm(midY + 1.6));
      gg.addColorStop(0, '#7f8c99'); gg.addColorStop(0.5, '#b9c4ce'); gg.addColorStop(1, '#6f7d8b');
      c.fillStyle = gg;
      roundRect(c, mm(x), mm(midY - 1.6), mm(w), mm(3.2), mm(0.4));
      c.fill();
    } else {
      c.fillStyle = e.color || '#888';
      c.beginPath();
      c.arc(mm(x + w / 2), mm(midY), mm(1.5), 0, Math.PI * 2);
      c.fill();
      c.strokeStyle = 'rgba(0,0,0,.35)';
      c.lineWidth = Math.max(1, mm(0.2));
      c.stroke();
    }
    c.restore();
  }

  /**
   * A map icon as something canvas can draw.
   *
   * map.getImage() hands back raw RGBA samples, not an <img>, so they are
   * put into an ImageData and painted onto a scratch canvas once per icon.
   * Cached: a legend redraws on every preview and an asset sheet can carry
   * half a dozen of these.
   */
  var iconCache = {};
  function iconCanvas(id) {
    if (id in iconCache) return iconCache[id];
    iconCache[id] = null;
    try {
      var im = map.getImage(id);
      if (!im || !im.data || !im.data.width) return null;
      var cv = document.createElement('canvas');
      cv.width = im.data.width; cv.height = im.data.height;
      var cx = cv.getContext('2d');
      var idata = cx.createImageData(cv.width, cv.height);
      idata.data.set(new Uint8ClampedArray(im.data.data.buffer || im.data.data));
      cx.putImageData(idata, 0, 0);
      iconCache[id] = cv;
    } catch (e) { iconCache[id] = null; }
    return iconCache[id];
  }

  function clip(c, text, maxPx) {
    var s = String(text == null ? '' : text);
    if (c.measureText(s).width <= maxPx) return s;
    while (s.length > 1 && c.measureText(s + '…').width > maxPx) s = s.slice(0, -1);
    return s + '…';
  }

  function drawNorth(c, x, y, size, style, th, S, mm) {
    c.save();
    c.translate(mm(x + size / 2), mm(y + size / 2));
    var r = mm(size / 2);
    var ink = th.ink || '#111';

    if (style === 'minimal') {
      c.strokeStyle = ink; c.lineWidth = Math.max(1.2, mm(0.5)); c.lineCap = 'round';
      c.beginPath(); c.moveTo(0, r * 0.75); c.lineTo(0, -r * 0.7); c.stroke();
      c.beginPath(); c.moveTo(-r * 0.3, -r * 0.34); c.lineTo(0, -r * 0.78); c.lineTo(r * 0.3, -r * 0.34);
      c.stroke();
      c.fillStyle = ink; c.textAlign = 'center';
      c.font = '700 ' + mm(size * 0.3) + 'px ' + (th.font || 'sans-serif');
      c.fillText('N', 0, r * 1.12);
    } else if (style === 'needle') {
      c.fillStyle = th.accent || '#0d5c9e';
      c.beginPath(); c.moveTo(0, -r * 0.86); c.lineTo(r * 0.34, r * 0.5); c.lineTo(0, r * 0.22); c.closePath(); c.fill();
      c.fillStyle = 'rgba(20,30,45,.42)';
      c.beginPath(); c.moveTo(0, -r * 0.86); c.lineTo(-r * 0.34, r * 0.5); c.lineTo(0, r * 0.22); c.closePath(); c.fill();
      c.fillStyle = ink; c.textAlign = 'center';
      c.font = '700 ' + mm(size * 0.26) + 'px ' + (th.font || 'sans-serif');
      c.fillText('N', 0, r * 0.96);
    } else if (style === 'engineer') {
      c.strokeStyle = ink; c.lineWidth = Math.max(1, mm(0.3));
      c.beginPath(); c.arc(0, 0, r * 0.82, 0, Math.PI * 2); c.stroke();
      for (var i = 0; i < 8; i++) {
        var a = i * Math.PI / 4;
        var l = (i % 2 === 0) ? 0.82 : 0.5;
        c.beginPath();
        c.moveTo(Math.sin(a) * r * 0.2, -Math.cos(a) * r * 0.2);
        c.lineTo(Math.sin(a) * r * l, -Math.cos(a) * r * l);
        c.stroke();
      }
      c.fillStyle = ink;
      c.beginPath(); c.moveTo(0, -r * 0.86); c.lineTo(r * 0.19, -r * 0.16); c.lineTo(-r * 0.19, -r * 0.16); c.closePath(); c.fill();
      c.textAlign = 'center';
      c.font = '700 ' + mm(size * 0.24) + 'px ' + (th.font || 'sans-serif');
      c.fillText('N', 0, r * 1.2);
    } else {   /* classic */
      c.fillStyle = ink;
      c.beginPath(); c.moveTo(0, -r * 0.82); c.lineTo(r * 0.3, r * 0.62); c.lineTo(0, r * 0.3); c.closePath(); c.fill();
      c.fillStyle = '#ffffff';
      c.strokeStyle = ink; c.lineWidth = Math.max(1, mm(0.28));
      c.beginPath(); c.moveTo(0, -r * 0.82); c.lineTo(-r * 0.3, r * 0.62); c.lineTo(0, r * 0.3); c.closePath();
      c.fill(); c.stroke();
      c.fillStyle = ink; c.textAlign = 'center';
      c.font = '700 ' + mm(size * 0.27) + 'px ' + (th.font || 'sans-serif');
      c.fillText('N', 0, r * 1.16);
    }
    c.restore();
  }

  /**
   * Scale bar.
   *
   * Ground distance comes from the offscreen map's OWN bounds and pixel
   * width, not from a zoom-to-metres formula: the map may have been
   * fitted with padding, so its bounds are the truth about what is on the
   * page and the zoom is only half the story.
   */
  function drawScale(c, L, frame, S, mm) {
    var th = L.theme, M = L.map, sc = L.scale;
    var gm = frame.map;
    var b = gm.getBounds();
    var lat = (b.getNorth() + b.getSouth()) / 2;
    var spanM = (b.getEast() - b.getWest()) * 111320 * Math.cos(lat * Math.PI / 180);
    if (!isFinite(spanM) || spanM <= 0) return;
    var mmPerM = M.w / spanM;                       /* page mm per ground metre */
    var target = niceScale(sc.width / mmPerM);
    var barMm = target * mmPerM;
    if (!isFinite(barMm) || barMm <= 1) return;
    /* Never let a rounded-up bar run off the sheet. */
    while (barMm > sc.width * 1.35) { target /= 2; barMm = target * mmPerM; }

    var x, y, onMap = sc.position !== 'below-map';
    if (onMap) {
      var p = furniturePos(L, sc.position, barMm + 8, 9, frame);
      x = p.x + 3; y = p.y + 4.6;
      c.save();
      c.fillStyle = th.panel || 'rgba(255,255,255,.9)';
      roundRect(c, mm(p.x), mm(p.y), mm(barMm + 8), mm(9.4), mm(1.2));
      c.fill();
      c.strokeStyle = th.rule || '#ccd7e4';
      c.lineWidth = Math.max(1, mm(0.25));
      c.stroke();
      c.restore();
    } else {
      x = L.scaleBelow.x + 1; y = L.scaleBelow.y + 3.2;
    }

    var ink = th.ink || '#111';
    c.save();
    if (sc.style === 'line') {
      c.strokeStyle = ink; c.lineWidth = Math.max(1.2, mm(0.5)); c.lineCap = 'butt';
      c.beginPath();
      c.moveTo(mm(x), mm(y)); c.lineTo(mm(x + barMm), mm(y));
      c.moveTo(mm(x), mm(y - 1.1)); c.lineTo(mm(x), mm(y + 1.1));
      c.moveTo(mm(x + barMm), mm(y - 1.1)); c.lineTo(mm(x + barMm), mm(y + 1.1));
      c.stroke();
    } else if (sc.style === 'checker') {
      var seg = barMm / 4;
      for (var i = 0; i < 4; i++) {
        c.fillStyle = (i % 2 === 0) ? ink : '#ffffff';
        c.fillRect(mm(x + i * seg), mm(y - 1.1), mm(seg), mm(2.2));
        c.strokeStyle = ink; c.lineWidth = Math.max(1, mm(0.22));
        c.strokeRect(mm(x + i * seg), mm(y - 1.1), mm(seg), mm(2.2));
      }
    } else {
      c.fillStyle = ink;
      c.fillRect(mm(x), mm(y - 1), mm(barMm), mm(2));
      c.fillStyle = '#ffffff';
      c.fillRect(mm(x + barMm / 2), mm(y - 1), mm(barMm / 2), mm(2));
      c.strokeStyle = ink; c.lineWidth = Math.max(1, mm(0.22));
      c.strokeRect(mm(x), mm(y - 1), mm(barMm), mm(2));
    }

    c.fillStyle = ink;
    c.font = '600 ' + mm(2.5) + 'px ' + L.font;
    c.textAlign = 'left';
    c.fillText('0', mm(x), mm(y + 4.2));
    c.textAlign = 'right';
    c.fillText(fmtDistance(target), mm(x + barMm), mm(y + 4.2));
    /* Representative fraction — what a printed sheet is actually read at. */
    c.textAlign = 'left';
    c.fillStyle = th.muted || '#667';
    c.font = '500 ' + mm(2.2) + 'px ' + L.font;
    var rf = Math.round(1000 / mmPerM);
    c.fillText('1 : ' + rf.toLocaleString(), mm(x), mm(y + 7.2));
    c.textAlign = 'left';
    c.restore();
  }

  /** Graticule or edge ticks, projected through the offscreen map. */
  function drawGrid(c, L, frame, S, mm) {
    var M = L.map, th = L.theme, gm = frame.map;
    var b = gm.getBounds();
    var stepsDeg = [5, 2, 1, 0.5, 0.25, 0.1, 0.05, 0.025, 0.01, 0.005, 0.002, 0.001];
    var spanX = b.getEast() - b.getWest(), spanY = b.getNorth() - b.getSouth();
    var step = stepsDeg[stepsDeg.length - 1];
    for (var i = 0; i < stepsDeg.length; i++) {
      if (spanX / stepsDeg[i] >= 3 || spanY / stepsDeg[i] >= 3) { step = stepsDeg[i]; break; }
    }
    /* Offscreen CSS-pixel size → page mm. */
    var cw = gm.getCanvas().clientWidth || gm.getCanvas().width;
    var chh = gm.getCanvas().clientHeight || gm.getCanvas().height;
    var kx = M.w / cw, ky = M.h / chh;

    c.save();
    c.strokeStyle = hexAlpha(th.frame || '#111', L.grid.style === 'graticule' ? 0.22 : 0.75);
    c.lineWidth = Math.max(1, mm(0.22));
    c.font = '500 ' + mm(2.1) + 'px ' + L.font;
    c.fillStyle = hexAlpha(th.ink || '#111', 0.8);

    var startLng = Math.ceil(b.getWest() / step) * step;
    for (var lng = startLng; lng <= b.getEast(); lng += step) {
      var p = gm.project([lng, (b.getNorth() + b.getSouth()) / 2]);
      var px = M.x + p.x * kx;
      if (px < M.x || px > M.x + M.w) continue;
      c.beginPath();
      if (L.grid.style === 'graticule') { c.moveTo(mm(px), mm(M.y)); c.lineTo(mm(px), mm(M.y + M.h)); }
      else {
        c.moveTo(mm(px), mm(M.y)); c.lineTo(mm(px), mm(M.y + 2));
        c.moveTo(mm(px), mm(M.y + M.h)); c.lineTo(mm(px), mm(M.y + M.h - 2));
      }
      c.stroke();
      if (L.grid.labels) {
        c.textAlign = 'center';
        c.fillText(lng.toFixed(step < 0.01 ? 3 : 2) + '°E', mm(px), mm(M.y + M.h + 3.2));
      }
    }

    var startLat = Math.ceil(b.getSouth() / step) * step;
    for (var lat = startLat; lat <= b.getNorth(); lat += step) {
      var q = gm.project([(b.getEast() + b.getWest()) / 2, lat]);
      var py = M.y + q.y * ky;
      if (py < M.y || py > M.y + M.h) continue;
      c.beginPath();
      if (L.grid.style === 'graticule') { c.moveTo(mm(M.x), mm(py)); c.lineTo(mm(M.x + M.w), mm(py)); }
      else {
        c.moveTo(mm(M.x), mm(py)); c.lineTo(mm(M.x + 2), mm(py));
        c.moveTo(mm(M.x + M.w), mm(py)); c.lineTo(mm(M.x + M.w - 2), mm(py));
      }
      c.stroke();
      if (L.grid.labels) {
        c.save();
        c.translate(mm(M.x - 1.4), mm(py));
        c.rotate(-Math.PI / 2);
        c.textAlign = 'center';
        c.fillText(lat.toFixed(step < 0.01 ? 3 : 2) + '°N', 0, 0);
        c.restore();
      }
    }
    c.textAlign = 'left';
    c.restore();
  }

  function drawMeta(c, L, ctx2, S, mm) {
    var Mt = L.meta, th = L.theme;
    var fields = ctx2.metaFields || [];
    if (Mt.style === 'titleblock' || Mt.style === 'grid') {
      c.strokeStyle = th.frame || '#111';
      c.lineWidth = Math.max(1, mm(0.35));
      c.strokeRect(mm(Mt.x), mm(Mt.y), mm(Mt.w), mm(Mt.h));
    } else if (Mt.style === 'strip') {
      c.fillStyle = th.panel || '#f5f8fc';
      roundRect(c, mm(Mt.x), mm(Mt.y), mm(Mt.w), mm(Mt.h), mm(1.1));
      c.fill();
      c.strokeStyle = th.rule || '#ccd7e4';
      c.lineWidth = Math.max(1, mm(0.25));
      c.stroke();
    } else {
      c.strokeStyle = th.rule || '#ccd7e4';
      c.lineWidth = Math.max(1, mm(0.25));
      c.beginPath(); c.moveTo(mm(Mt.x), mm(Mt.y)); c.lineTo(mm(Mt.x + Mt.w), mm(Mt.y)); c.stroke();
    }

    var cols = Math.max(1, Mt.columns);
    var cw = Mt.w / cols;
    var perCol = Math.ceil(fields.length / cols);
    fields.forEach(function (f, i) {
      var col = Math.floor(i / perCol), row = i % perCol;
      var x = Mt.x + col * cw + 3;
      var y = Mt.y + 3.6 + row * 4.1;
      if (y > Mt.y + Mt.h - 0.6) return;
      c.fillStyle = th.muted || '#667';
      c.font = '700 ' + mm(1.95) + 'px ' + L.font;
      c.fillText(String(f[0]).toUpperCase(), mm(x), mm(y));
      c.fillStyle = th.ink || '#111';
      c.font = '500 ' + mm(2.5) + 'px ' + L.font;
      c.fillText(clip(c, f[1], mm(cw - 6)), mm(x), mm(y + 3));
    });
  }

  function drawMetaOverlay(c, L, ctx2, S, mm) {
    var M = L.map, th = L.theme;
    var line = (ctx2.metaFields || []).slice(0, 4)
      .map(function (f) { return f[0] + ': ' + f[1]; }).join('   ·   ');
    if (!line) return;
    c.font = '500 ' + mm(2.2) + 'px ' + L.font;
    var w = c.measureText(line).width / S;
    var x = M.x + M.w - w - 7, y = M.y + M.h - 3.2;
    c.fillStyle = th.panel || 'rgba(255,255,255,.9)';
    roundRect(c, mm(x - 2.5), mm(y - 3.2), mm(w + 5), mm(4.6), mm(1));
    c.fill();
    c.fillStyle = th.muted || '#667';
    c.fillText(line, mm(x), mm(y));
  }

  function hexAlpha(hex, a) {
    var h = String(hex || '#000').replace('#', '');
    if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
    if (h.length !== 6) return 'rgba(0,0,0,' + a + ')';
    return 'rgba(' + parseInt(h.slice(0, 2), 16) + ',' + parseInt(h.slice(2, 4), 16) + ','
                   + parseInt(h.slice(4, 6), 16) + ',' + a + ')';
  }

  /* ==================================================================
     METADATA

     Every field defaults from something KLRAMS already knows. The user is
     never asked to type a district the filter has already named.
     ================================================================== */
  var Meta = (function () {

    function today() {
      var d = new Date();
      var months = ['January', 'February', 'March', 'April', 'May', 'June', 'July',
                    'August', 'September', 'October', 'November', 'December'];
      return String(d.getDate()).padStart(2, '0') + ' ' + months[d.getMonth()] + ' ' + d.getFullYear();
    }

    /** The district(s) the active filter resolves to, when it resolves to
     *  few enough to name. Read from the road index, which every page
     *  already holds — no request. */
    function districtsInScope() {
      try {
        if (!window.NET_SCOPE || typeof RoadsIndex === 'undefined') return '';
        var rows = RoadsIndex.all();
        if (!rows.length) return '';
        var key = Object.keys(rows[0]).find(function (k) { return /^district$/i.test(k); })
               || Object.keys(rows[0]).find(function (k) { return /district/i.test(k); });
        if (!key) return '';
        var set = {};
        rows.forEach(function (p) {
          if (!window.NET_SCOPE.has(String(p.road))) return;
          var v = p[key];
          if (v != null && String(v).trim() !== '') set[String(v).trim()] = 1;
        });
        var list = Object.keys(set).sort();
        if (!list.length || list.length > 3) return list.length ? list.length + ' districts' : '';
        return list.join(', ');
      } catch (e) { return ''; }
    }

    function signedInUser() {
      try {
        var n = document.getElementById('ucName');
        return n ? (n.textContent || '').trim() : '';
      } catch (e) { return ''; }
    }

    /** Defaults for the Map Information step, recomputed whenever the
     *  filter or extent changes — but never over a field the user has
     *  typed into (state.infoTouched). */
    function defaults(extent) {
      var filterText = Filters.activeText();
      var dist = districtsInScope();
      var title = 'Road Network Map';
      if (Layers.selectedItems().some(function (i) { return i.group === 'assets'; })) {
        title = 'Road Network & Asset Map';
      }
      if (dist && dist.indexOf('districts') < 0) title = dist + ' — ' + title;

      return {
        title: title,
        subtitle: dist ? (dist + ' District, Kerala') : 'Kerala Public Works Department',
        filterText: filterText ? ('Filter: ' + filterText) : 'Filter: none — entire road network',
        district: dist || '—',
        extentSource: (extent && extent.source) || '—',
        date: today(),
        source: 'KLRAMS · Kerala PWD (RMMS Cell, KHRI)',
        preparedBy: signedInUser() || 'KLRAMS',
        notes: ''
      };
    }

    function merged(extent) {
      var d = defaults(extent);
      var out = {};
      Object.keys(d).forEach(function (k) {
        out[k] = state.infoTouched[k] && state.info[k] != null ? state.info[k] : d[k];
      });
      Object.keys(state.info).forEach(function (k) {
        if (state.infoTouched[k]) out[k] = state.info[k];
      });
      return out;
    }

    /** The footer table. Empty values are dropped rather than printed as
     *  blanks under a heading. */
    function fields(info, extent, sheet) {
      var f = [
        ['Prepared by', info.preparedBy],
        ['Data source', info.source],
        ['Date', info.date],
        ['Extent', info.extentSource],
        ['Filter', (info.filterText || '').replace(/^Filter:\s*/i, '')],
        ['District', info.district],
        ['Sheet', sheet],
        ['Notes', info.notes]
      ];
      return f.filter(function (r) { return r[1] != null && String(r[1]).trim() !== '' && r[1] !== '—'; });
    }

    return { defaults: defaults, merged: merged, fields: fields, today: today };
  })();

  /* ==================================================================
     COMPOSE

     The one pipeline both the preview and the export go through.
     ================================================================== */
  var lastCompose = null;
  var composeSeq = 0;          /* which compose is the newest */
  var composeChain = Promise.resolve();

  /**
   * Run composes one at a time, newest wins.
   *
   * Two bugs this closes, both of which showed up as the sidebar and the
   * sheet disagreeing about the legend. `gl` is a single offscreen map, so
   * two overlapping composes tore each other's WebGL context down mid-render;
   * and `lastCompose` — which the legend editor reads — was written by
   * whichever compose finished last, not by the newest one, so a slow older
   * render could overwrite the current answer after the fact.
   */
  function compose(opts) {
    var mySeq = ++composeSeq;
    var run = function () { return composeOnce(opts, mySeq); };
    var p = composeChain.then(run, run);
    composeChain = p.catch(function () { /* keep the chain alive */ });
    return p;
  }

  /**
   * Build a sheet.
   *
   * `opts.dpi` picks the raster resolution; everything else about the
   * drawing is identical between a preview and an export, which is what
   * makes "what you see is what you get" true here rather than aspirational.
   */
  function composeOnce(opts, mySeq) {
    opts = opts || {};
    var dpi = opts.dpi || DPI_PREVIEW;
    var tpl = Templates.byId(state.templateId);
    var onProgress = opts.onProgress || function () {};

    var pageSize = state.pageSize || tpl.pageSize || 'A4';
    var page0 = pageRect(pageSize, state.orientation === 'auto' ? (tpl.orientation || 'landscape') : state.orientation, state.custom);

    onProgress('Reading layers…');

    return viewerStyleReady()
      .then(function () { return Layers.ensureSelected(); })
      .then(function () {
        onProgress('Calculating extent…');
        return Extent.compute(state.extentMode);
      })
      .then(function (ext) {
        if (!ext.bbox) {
          var e = new Error(ext.note || 'There is nothing to map for this extent.');
          e.friendly = true;
          throw e;
        }

        /* Orientation: "auto" lets the data choose, which is the single
           most useful piece of automation on this screen — a long
           east–west corridor on a portrait sheet wastes half the page. */
        var orient = state.orientation;
        if (orient === 'auto') {
          var bw = (ext.bbox[2] - ext.bbox[0]) * Math.cos(((ext.bbox[1] + ext.bbox[3]) / 2) * Math.PI / 180);
          var bh = (ext.bbox[3] - ext.bbox[1]);
          orient = (bw >= bh * 0.92) ? 'landscape' : 'portrait';
          if (pageSize === 'Screen') orient = 'landscape';
        }
        var page = pageRect(pageSize, orient, state.custom);

        var items = Layers.selectedItems();
        var legendAll = Legend.build(items);          /* everything the layers offer */
        var legend = Legend.applyEdits(legendAll);    /* …minus what the user dropped */
        var info = Meta.merged(ext);
        /* "Show filter" off means off everywhere the filter line could print —
           the header's meta line AND the footer's "Filter" row both read
           info.filterText, so clearing it here once is enough for both. */
        if (state.show.filter === false) info = Object.assign({}, info, { filterText: '' });

        /* First layout pass to learn the map frame's aspect ratio, which
           the extent normaliser needs before the frame is rendered. */
        /* Same resolution as page0 above — this pass only exists to learn the
           map frame's aspect, and an aspect measured on the wrong page shape
           would feed a wrongly normalised bbox into the render. */
        var L0 = Layout.compose(tpl, Object.assign(sheetOpts(), {
          orientation: state.orientation === 'auto' ? (tpl.orientation || 'landscape') : state.orientation
        }), {
          title: info.title, subtitle: info.subtitle, filterText: info.filterText,
          legend: legend
        });
        var bbox = Extent.normalise(ext.bbox, L0.map.w / L0.map.h);

        var S = pxPerMm(dpi);
        var frameW = Math.min(MAX_GL_PX, Math.round(L0.map.w * S));
        var frameH = Math.min(MAX_GL_PX, Math.round(L0.map.h * S));
        var pad = Math.max(14, Math.round(Math.min(frameW, frameH) * 0.055));

        onProgress('Drawing the map…');
        var style = buildStyle(items, tpl, state.basemap);

        return renderFrame(style, bbox, frameW, frameH, pad).then(function (frame) {
          onProgress('Composing the sheet…');
          return loadLogo().then(function () {
            var canvas = document.createElement('canvas');
            canvas.width = Math.round(page.w * S);
            canvas.height = Math.round(page.h * S);

            /* The RESOLVED orientation, not state's raw value.
               `state.orientation` is routinely 'auto', which pageRect treats as
               landscape — so with auto resolving to portrait (which Kerala's
               tall outline does), the CANVAS was sized 210x297 while the LAYOUT
               was composed for 297x210. Everything laid out against the right
               edge, the legend first, was then drawn past the end of the canvas
               and simply did not appear on the sheet. */
            var L = Layout.compose(tpl, Object.assign(sheetOpts(), { orientation: orient }), {
              title: info.title, subtitle: info.subtitle, filterText: info.filterText,
              legend: legend, textItems: state.textItems
            });
            var sheetLabel = (PAGES[pageSize] ? PAGES[pageSize].label : pageSize) + ' ' +
                             (orient === 'portrait' ? 'Portrait' : 'Landscape');
            var ctx2 = { metaFields: Meta.fields(info, ext, sheetLabel), info: info };

            draw(canvas, L, ctx2, frame, S);

            var result = {
              canvas: canvas, page: page, layout: L, info: info, extent: ext,
              template: tpl, dpi: dpi, legend: legend, legendAll: legendAll, items: items,
              orientation: orient, pageSize: pageSize, frame: frame,
              warnings: warningsFor(ext, frame, items)
            };
            /* Only the newest compose may become "the sheet on screen" — an
               export started while a preview was still running must not
               leave the legend editor describing the export's page size. */
            if (mySeq === undefined || mySeq === composeSeq) lastCompose = result;
            return result;
          });
        });
      });
  }

  /** Per-sheet show/hide overrides on top of the template's own flags. */
  function sheetOpts() {
    return {
      showLegend: state.show.legend, showNorth: state.show.north,
      showScale: state.show.scale, showGrid: state.show.grid,
      showMetadata: state.show.metadata, showLogo: state.show.logo,
      showHeader: state.show.header,
      pageSize: state.pageSize, orientation: state.orientation, custom: state.custom
    };
  }

  /**
   * Re-paint the current sheet onto its existing canvas, without redoing
   * any of the expensive work — reading the layers, resolving the extent,
   * or rendering the offscreen map. Those three are exactly what compose()
   * spends its time on, and none of them change while a user is dragging a
   * text box around: the map frame is still the same picture, the legend
   * is still the same blocks. Only the LAYOUT pass (pure arithmetic, no
   * network, no WebGL) needs to run again, against the same `frame` and
   * the same canvas element already sitting in the DOM — so this is what
   * makes live text-dragging possible instead of a 1-2 second wait per
   * pixel moved.
   *
   * Returns the updated compose result, or null if nothing has been
   * composed yet (dragging is only offered once a sheet exists).
   */
  function redraw() {
    var last = lastCompose;
    if (!last || !last.frame) return null;
    var tpl = Templates.byId(state.templateId);
    var info = Meta.merged(last.extent);
    if (state.show.filter === false) info = Object.assign({}, info, { filterText: '' });
    var legend = Legend.applyEdits(Legend.build(last.items));
    var L = Layout.compose(tpl, Object.assign(sheetOpts(), { orientation: last.orientation }), {
      title: info.title, subtitle: info.subtitle, filterText: info.filterText,
      legend: legend, textItems: state.textItems
    });
    var sheetLabel = (PAGES[last.pageSize] ? PAGES[last.pageSize].label : last.pageSize) + ' ' +
                     (last.orientation === 'portrait' ? 'Portrait' : 'Landscape');
    var ctx2 = { metaFields: Meta.fields(info, last.extent, sheetLabel), info: info };
    draw(last.canvas, L, ctx2, last.frame, pxPerMm(last.dpi));
    last.layout = L; last.info = info; last.legend = legend;
    return last;
  }

  /** The mm bounding box a text item will occupy — the same wrap/measure
   *  the actual draw does, so a click either does or doesn't land on it by
   *  the same rule the paint used, never a rough approximation of it. */
  function textBoxMm(t, page, margin, font) {
    var sizeMm = t.size || 4;
    var weight = t.bold ? '700' : '400';
    var x = (t.x != null ? t.x : 0.1) * page.w;
    var y = (t.y != null ? t.y : 0.1) * page.h;
    var maxW = Math.max(20, page.w - x - margin);
    var lines = [];
    String(t.text || '').split(/\n/).forEach(function (para) {
      if (!para) { lines.push(''); return; }
      Layout.wrap(para, maxW, sizeMm, weight, font, 20).forEach(function (ln) { lines.push(ln); });
    });
    var w = 0;
    lines.forEach(function (ln) { w = Math.max(w, Layout.measure(ln, sizeMm, weight, font)); });
    var lineH = sizeMm * 1.3;
    return { x0: x, y0: y, x1: x + Math.max(w, 6), y1: y + Math.max(lineH, lines.length * lineH) };
  }

  /** Which text item (if any) a click at this page position lands on —
   *  topmost (last-added) first, so a click in an overlap grabs whichever
   *  box is drawn on top, matching what the eye sees. -1 for no hit. */
  function hitTestText(xMm, yMm) {
    if (!lastCompose) return -1;
    var page = lastCompose.page;
    var margin = (lastCompose.layout && lastCompose.layout.margin != null) ? lastCompose.layout.margin : 9;
    var font = (lastCompose.template && lastCompose.template.theme && lastCompose.template.theme.font) || 'sans-serif';
    var pad = 1.5; /* mm slack, so a short or empty line is still grabbable */
    for (var i = state.textItems.length - 1; i >= 0; i--) {
      var t = state.textItems[i];
      if (!t.text) continue;
      var b = textBoxMm(t, page, margin, font);
      if (xMm >= b.x0 - pad && xMm <= b.x1 + pad && yMm >= b.y0 - pad && yMm <= b.y1 + pad) return i;
    }
    return -1;
  }

  function warningsFor(ext, frame, items) {
    var w = [];
    if (ext.problems) ext.problems.forEach(function (p) { w.push(p); });
    if (frame && frame.why === 'timeout') w.push('The map took too long to finish drawing; some tiles may be missing.');
    else if (frame && frame.why === 'tiles') w.push('Some map tiles did not arrive in time — usually the external basemap. The data layers are complete.');
    if (frame && frame.errors && frame.errors.length) {
      w.push('MapLibre reported: ' + frame.errors.slice(0, 2).join('; '));
    }
    if (!items.length) w.push('No layers are selected, so the sheet shows the basemap only.');
    return w;
  }

  /* ==================================================================
     EXPORT
     ================================================================== */
  var Export = (function () {

    function safeName(info) {
      var base = (info && info.title) || 'klrams-map';
      return String(base).replace(/[^\w\d\- ]+/g, '').trim().replace(/\s+/g, '-').toLowerCase()
             || 'klrams-map';
    }

    function download(blob, filename) {
      var url = URL.createObjectURL(blob);
      var a = document.createElement('a');
      a.href = url; a.download = filename;
      document.body.appendChild(a);
      a.click();
      setTimeout(function () { document.body.removeChild(a); URL.revokeObjectURL(url); }, 400);
    }

    function png(onProgress) {
      return compose({ dpi: DPI_PNG, onProgress: onProgress }).then(function (r) {
        return new Promise(function (resolve, reject) {
          try {
            r.canvas.toBlob(function (b) {
              if (!b) { reject(new Error('The browser could not encode the image.')); return; }
              download(b, safeName(r.info) + '.png');
              resolve(r);
            }, 'image/png');
          } catch (e) {
            /* A tainted canvas is the one failure mode worth naming: it
               means a basemap host answered without CORS headers. */
            reject(new Error('The sheet could not be saved because the basemap did not allow it to be read. '
                             + 'Choose "No basemap (plain)" and try again.'));
          }
        });
      });
    }

    function pdf(onProgress) {
      return compose({ dpi: DPI_PDF, onProgress: onProgress }).then(function (r) {
        onProgress && onProgress('Writing the PDF…');
        return buildPdf(r).then(function (bytes) {
          download(new Blob([bytes], { type: 'application/pdf' }), safeName(r.info) + '.pdf');
          return r;
        });
      });
    }

    /* ---------------- a minimal, dependency-free PDF writer ----------------
       KLRAMS ships no PDF library and 29-export.js set the precedent of
       writing the container by hand rather than adding one (its ZIP and
       shapefile writers). A one-page PDF holding one image is a handful of
       objects, so the same trade applies: no new dependency, no CDN on an
       office network that may not reach one, and the page size is exactly
       the millimetres the layout worked in — so "A3 landscape" prints as
       A3 landscape rather than as whatever a viewer decides to scale to.

       The image goes in as Flate-compressed RGB where the browser has
       CompressionStream (lossless, so hairlines and 2 mm type stay crisp),
       and as JPEG where it does not. */

    function latin1(str) {
      var out = new Uint8Array(str.length);
      for (var i = 0; i < str.length; i++) out[i] = str.charCodeAt(i) & 0xff;
      return out;
    }

    function rgbBytes(canvas) {
      var c = canvas.getContext('2d', { willReadFrequently: true });
      var d = c.getImageData(0, 0, canvas.width, canvas.height).data;
      var out = new Uint8Array(canvas.width * canvas.height * 3);
      for (var i = 0, o = 0; i < d.length; i += 4) {
        /* Composited over white: the sheet has no transparency to keep,
           and PDF's /DeviceRGB has no alpha channel to put it in. */
        var a = d[i + 3] / 255;
        out[o++] = Math.round(d[i] * a + 255 * (1 - a));
        out[o++] = Math.round(d[i + 1] * a + 255 * (1 - a));
        out[o++] = Math.round(d[i + 2] * a + 255 * (1 - a));
      }
      return out;
    }

    function deflate(bytes) {
      if (typeof CompressionStream === 'undefined') return Promise.resolve(null);
      try {
        var cs = new CompressionStream('deflate');   /* zlib wrapper = PDF /FlateDecode */
        var stream = new Blob([bytes]).stream().pipeThrough(cs);
        return new Response(stream).arrayBuffer().then(function (buf) { return new Uint8Array(buf); });
      } catch (e) { return Promise.resolve(null); }
    }

    function jpegBytes(canvas) {
      return new Promise(function (resolve, reject) {
        canvas.toBlob(function (b) {
          if (!b) { reject(new Error('The browser could not encode the page image.')); return; }
          b.arrayBuffer().then(function (buf) { resolve(new Uint8Array(buf)); });
        }, 'image/jpeg', 0.95);
      });
    }

    function buildPdf(r) {
      var canvas = r.canvas;
      /* PDF user space is 1/72 inch. */
      var wPt = r.page.w / 25.4 * 72, hPt = r.page.h / 25.4 * 72;

      return deflate(rgbBytes(canvas))
        .then(function (flate) {
          if (flate && flate.length < canvas.width * canvas.height * 3) {
            return { data: flate, filter: '/FlateDecode', cs: '/DeviceRGB', bpc: 8 };
          }
          return jpegBytes(canvas).then(function (j) {
            return { data: j, filter: '/DCTDecode', cs: '/DeviceRGB', bpc: 8 };
          });
        })
        .then(function (img) {
          var parts = [], offsets = [], len = 0;
          function push(u8) { parts.push(u8); len += u8.length; }
          function pushStr(s) { push(latin1(s)); }
          function obj(n, body, stream) {
            offsets[n] = len;
            pushStr(n + ' 0 obj\n' + body + '\n');
            if (stream) {
              pushStr('stream\n');
              push(stream);
              pushStr('\nendstream\n');
            }
            pushStr('endobj\n');
          }

          var title = pdfString((r.info && r.info.title) || 'KLRAMS map');
          var author = pdfString((r.info && r.info.preparedBy) || 'KLRAMS');

          pushStr('%PDF-1.4\n%\xE2\xE3\xCF\xD3\n');

          obj(1, '<< /Type /Catalog /Pages 2 0 R >>');
          obj(2, '<< /Type /Pages /Kids [3 0 R] /Count 1 >>');
          obj(3, '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ' + wPt.toFixed(2) + ' ' + hPt.toFixed(2) + ']'
                + ' /Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>');
          obj(4, '<< /Type /XObject /Subtype /Image /Width ' + canvas.width + ' /Height ' + canvas.height
                + ' /ColorSpace ' + img.cs + ' /BitsPerComponent ' + img.bpc
                + ' /Filter ' + img.filter + ' /Length ' + img.data.length + ' >>', img.data);
          var content = latin1('q\n' + wPt.toFixed(2) + ' 0 0 ' + hPt.toFixed(2) + ' 0 0 cm\n/Im0 Do\nQ\n');
          obj(5, '<< /Length ' + content.length + ' >>', content);
          obj(6, '<< /Title ' + title + ' /Author ' + author
                + ' /Creator (KLRAMS Map Composer) /Producer (KLRAMS) >>');

          var xref = len;
          var n = 7;
          var s = 'xref\n0 ' + n + '\n0000000000 65535 f \n';
          for (var i = 1; i < n; i++) {
            s += String(offsets[i]).padStart(10, '0') + ' 00000 n \n';
          }
          s += 'trailer\n<< /Size ' + n + ' /Root 1 0 R /Info 6 0 R >>\nstartxref\n' + xref + '\n%%EOF\n';
          pushStr(s);

          var out = new Uint8Array(len), pos = 0;
          parts.forEach(function (p) { out.set(p, pos); pos += p.length; });
          return out;
        });
    }

    /** A PDF literal string: parentheses and backslashes must be escaped
     *  or the object is unparseable, and a map title routinely contains
     *  brackets. */
    function pdfString(s) {
      return '(' + String(s).replace(/[\\()]/g, '\\$&').replace(/[\r\n]/g, ' ') + ')';
    }

    return { png: png, pdf: pdf, download: download, safeName: safeName };
  })();

  /* ==================================================================
     PUBLIC API
     ================================================================== */
  return {
    PAGES: PAGES,
    pageRect: pageRect,
    state: get, set: set,
    Templates: Templates,
    Layers: Layers,
    Extent: Extent,
    Filters: Filters,
    Legend: Legend,
    Layout: Layout,
    Meta: Meta,
    Export: Export,
    compose: compose,
    redraw: redraw,
    hitTestText: hitTestText,
    last: function () { return lastCompose; },
    dispose: disposeGl
  };
})();
