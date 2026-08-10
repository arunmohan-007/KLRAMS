/* ============================================================
   KLRAMS viewer · 02b-layer-registry.js
   One declared home for every map layer: what it is, what feeds it,
   which toggle owns it, what has to load before it can appear, and
   where it sits in the draw order.

   THIS MODULE IS INERT. It registers descriptions and exposes an API;
   it does not add, remove, show or hide anything. Every layer is still
   created and toggled by the module that owns it today. Moving those
   call sites onto this registry is the NEXT step, deliberately kept
   separate so this one cannot change how the map looks.

   Why it exists
   -------------
   Layers are currently created by 31 map.addLayer calls spread across
   10 files, and shown/hidden by 27 setLayoutProperty calls spread
   across the same 10. Three things follow from that, all of them bugs
   waiting to happen:

     1. Draw order is ACCIDENTAL. Only the boundary layers pass a
        beforeId; everything else lands wherever insertion happens to
        put it, which depends on which toggle the user clicked first.
        Whether PCI draws over condition today is a matter of timing.
     2. There is nowhere for reconciliation logic to live, so it ended
        up in the wrong place — syncLazyVis(), which squares 8 layers
        against 4 checkboxes, sits inside 10-pci-report.js.
     3. A toggle does more than flip visibility (see `ensure` below),
        and that knowledge is re-implemented per module.

   Loaded from map.html right after 02-map-core.js: it needs `map` to
   exist, and everything else needs it.
   ============================================================ */
var KLLayers = (function () {
  'use strict';

  /* ------------------------------------------------------------------
     PROVISIONAL DRAW ORDER — NOT YET CONFIRMED. DO NOT SHIP AS FINAL.

     Low number = drawn first = furthest back. Values are spaced by 5 so
     a layer can be slotted between two others without renumbering.

     These are a best reading of what the map *appears* to intend, not a
     decision anyone has signed off. Today's order is accidental, so
     applying a deterministic one WILL visibly change the map wherever
     two layers overlap — which is the point, but it needs confirming
     against how PWD actually reads the map before applyOrder() is ever
     called in anger.
     ------------------------------------------------------------------ */
  var Z = {
    BOUNDARY_FILL:  10,   // administrative wash, behind everything
    ROAD_NETWORK:   20,   // the centreline network
    ROAD_MERGED:    25,   // secondary "merged by road name" network
    CONDITION:      30,   // per-lane condition colouring
    IRI_2KM:        35,   // 2 km IRI roll-up
    PCI:            40,   // composite / worst-lane PCI
    ASSET_LINE:     50,   // bridges, line furniture — drawn over the carriageway
    ASSET_POINT:    55,   // culverts, cores, FWD points
    TRAFFIC:        60,   // survey stations
    ROAD_PICK:      65,   // invisible click target for the network
    SELECTION:      70,   // highlight of the picked road
    MEASURE:        80,   // measuring tool scratch layers
    BOUNDARY_LABEL: 90    // place names read best on top of everything
  };

  var SPECS = [];        // in registration order
  var BY_KEY = {};       // family key -> spec
  var BY_LAYER = {};     // render layer id -> spec

  /* ------------------------------------------------------------------
     Registration
     ------------------------------------------------------------------ */

  /**
   * Describe one layer family.
   *
   * A family is one entry that may expand to SEVERAL render layers —
   * the condition lanes are five layers off one source, an asset is a
   * line plus a point plus an icon. They share a source, a toggle, a
   * loader and a z, so they are described once.
   *
   * Fields:
   *   key     stable identifier for the family (not a MapLibre id)
   *   label   human name, for the Layers panel and diagnostics
   *   group   which section of the Layers panel it belongs to
   *   layers  render layer ids, in draw order WITHIN the family
   *   source  MapLibre source id the layers read
   *   toggle  id of the checkbox that owns it, or null for tool layers
   *   z       draw order across families (see Z above)
   *   ensure  see below — may be omitted for layers with nothing to load
   *
   * `ensure` is a THUNK, never a direct function reference, and that is
   * load-bearing. This file is loaded at position 02b, before
   * 07-data-loaders.js defines loadRoads/loadSegments, before
   * 06-assets.js defines loadAsset, before 32-iri-2km.js exports
   * loadIri2km. Writing `ensure: loadSegments` would capture undefined
   * at registration time; wrapping it defers the lookup to the moment
   * it is called, by which point every module has loaded.
   */
  function register(spec) {
    if (!spec || !spec.key) throw new Error('layer spec needs a key');
    if (BY_KEY[spec.key]) throw new Error('duplicate layer key: ' + spec.key);
    spec.layers = spec.layers || [];
    BY_KEY[spec.key] = spec;
    SPECS.push(spec);
    spec.layers.forEach(function (id) { BY_LAYER[id] = spec; });
    return spec;
  }

  function get(key) { return BY_KEY[key] || null; }
  function all() { return SPECS.slice(); }
  function specForLayer(id) { return BY_LAYER[id] || null; }

  /* ------------------------------------------------------------------
     Queries against the live map
     ------------------------------------------------------------------ */

  /** Render layers of a family that are actually on the map right now. */
  function present(spec) {
    if (!spec || typeof map === 'undefined') return [];
    return spec.layers.filter(function (id) {
      try { return !!map.getLayer(id); } catch (e) { return false; }
    });
  }

  /** True once a family has at least one render layer built. */
  function ready(key) {
    var spec = (typeof key === 'string') ? get(key) : key;
    return present(spec).length > 0;
  }

  /** Is the family's toggle currently ticked? Families without a toggle
   *  (the measure tool, the selection highlight) are driven by code, not
   *  by the user, and answer false. */
  function toggled(spec) {
    if (!spec || !spec.toggle) return false;
    var el = document.getElementById(spec.toggle);
    return !!(el && el.checked);
  }

  /* ------------------------------------------------------------------
     Ordering
     ------------------------------------------------------------------ */

  /**
   * The layer id a family with this z should be inserted BEFORE, or
   * undefined to add on top.
   *
   * Walks the map's real draw order bottom-to-top and returns the first
   * registered layer that belongs above the given z. Passing the result
   * to map.addLayer(spec, beforeId) puts the new layer in its declared
   * place regardless of what has or has not loaded yet — which is the
   * whole answer to "order depends on which toggle you clicked first".
   */
  function beforeId(z) {
    if (typeof map === 'undefined' || !map.getStyle) return undefined;
    var layers;
    try { layers = (map.getStyle() || {}).layers || []; } catch (e) { return undefined; }
    for (var i = 0; i < layers.length; i++) {
      var spec = BY_LAYER[layers[i].id];
      if (spec && spec.z > z) return layers[i].id;
    }
    return undefined;
  }

  /** beforeId for a family, by key. */
  function beforeIdFor(key) {
    var spec = get(key);
    return spec ? beforeId(spec.z) : undefined;
  }

  /**
   * Reorder everything already on the map to match the declared z.
   *
   * NOT called anywhere yet, and must not be until the Z table above is
   * confirmed — it is the one function here that changes what the user
   * sees. Layers the registry does not know about are left alone.
   */
  function applyOrder() {
    if (typeof map === 'undefined') return;
    SPECS.slice().sort(function (a, b) { return a.z - b.z; }).forEach(function (spec) {
      present(spec).forEach(function (id) {
        try { map.moveLayer(id); } catch (e) { /* layer vanished mid-pass */ }
      });
    });
  }

  /* ------------------------------------------------------------------
     Loading and visibility
     ------------------------------------------------------------------ */

  /**
   * Make sure a family's data is loaded, returning a promise.
   *
   * THE POINT OF THIS FUNCTION: a KLRAMS toggle is not a visibility
   * switch. Ticking "Road condition" calls loadSegments(); "Road
   * network" calls loadRoads(); "District" calls ensureBoundary().
   * A registry that only set 'visibility' would leave those layers
   * permanently blank, because nothing would ever fetch them. Every
   * family that fronts lazily-loaded data carries its loader here.
   */
  function ensure(key) {
    var spec = (typeof key === 'string') ? get(key) : key;
    if (!spec) return Promise.resolve();
    if (ready(spec)) return Promise.resolve();
    if (typeof spec.ensure !== 'function') return Promise.resolve();
    try {
      return Promise.resolve(spec.ensure());
    } catch (e) {
      return Promise.reject(e);
    }
  }

  /** Show or hide every render layer of a family that exists. Missing
   *  layers are skipped rather than throwing: an asset's icon layer is
   *  added asynchronously after its line layer, so "some of the family
   *  is here" is a normal state, not an error. */
  function setVisible(key, on) {
    var spec = (typeof key === 'string') ? get(key) : key;
    if (!spec || typeof map === 'undefined') return;
    var v = on ? 'visible' : 'none';
    present(spec).forEach(function (id) {
      try { map.setLayoutProperty(id, 'visibility', v); } catch (e) { /* ignore */ }
    });
  }

  /** Ensure the data is there, then show it — what a toggle turning ON
   *  actually means. */
  function show(key) {
    return ensure(key).then(function () { setVisible(key, true); });
  }

  /**
   * Square families' visibility against their toggles.
   *
   * The job syncLazyVis() used to do from inside 10-pci-report.js: after
   * a background preload the layers exist but their toggles may be
   * unticked, and something has to reconcile the two.
   *
   * Families without a toggle are always left alone — the measure and
   * selection layers are driven by code, and roadnet-hit is deliberately
   * visible at all times so Video-on-click keeps working with the
   * network layer switched off.
   *
   * `opts.sources` narrows the pass to families reading those sources.
   * Callers use it because reconciling EVERYTHING is not the same
   * operation: a preload of the road network has no business deciding
   * whether the FWD layer should be on. Omit it only when you really do
   * mean every layer.
   */
  function syncFromToggles(opts) {
    var sources = opts && opts.sources;
    SPECS.forEach(function (spec) {
      if (!spec.toggle) return;
      if (sources && sources.indexOf(spec.source) < 0) return;
      setVisible(spec, toggled(spec));
    });
  }

  /**
   * Reconcile the lazily-loaded map layers — the road network and
   * everything drawn from the condition segments.
   *
   * Called after a background preload finishes. Those loads build their
   * layers whether or not the user has ticked anything, so without this
   * a preload would flash condition colours onto a map whose Condition
   * toggle is off.
   *
   * Deliberately narrower than syncFromToggles() with no arguments:
   * assets, traffic, boundaries and the merged network load on their own
   * schedules and must not be swept up by a road-network preload.
   */
  function syncLazyLayers() {
    syncFromToggles({ sources: ['roadnet', 'segs'] });
  }

  /* ------------------------------------------------------------------
     Diagnostics
     ------------------------------------------------------------------ */

  /**
   * What the registry believes, versus what is actually on the map.
   *
   * Two things worth catching, both printed rather than thrown because
   * this is a console aid, not a guard:
   *   missing — a declared layer that is not currently built (usually
   *             just "not loaded yet", which is fine)
   *   orphan  — a layer on the map that NO spec claims, i.e. the
   *             registry has drifted behind the code
   */
  function describe() {
    var out = { families: SPECS.length, layers: 0, built: 0, orphans: [] };
    SPECS.forEach(function (spec) {
      out.layers += spec.layers.length;
      out.built += present(spec).length;
    });
    if (typeof map !== 'undefined' && map.getStyle) {
      var live = [];
      try { live = (map.getStyle() || {}).layers || []; } catch (e) { live = []; }
      live.forEach(function (l) {
        // Base-map layers come from the style, not from us — only flag
        // ids that look like ours.
        if (BY_LAYER[l.id]) return;
        if (/^(roadnet|seg-|pci-|as-|iri2km|trafficstn|district|cons-|measure-|sel-road)/.test(l.id)) {
          out.orphans.push(l.id);
        }
      });
    }
    return out;
  }

  return {
    Z: Z,
    register: register,
    get: get,
    all: all,
    specForLayer: specForLayer,
    present: present,
    ready: ready,
    toggled: toggled,
    beforeId: beforeId,
    beforeIdFor: beforeIdFor,
    applyOrder: applyOrder,
    ensure: ensure,
    setVisible: setVisible,
    show: show,
    syncFromToggles: syncFromToggles,
    syncLazyLayers: syncLazyLayers,
    describe: describe
  };
})();

/* ==================================================================
   THE REGISTRY

   Every layer the viewer draws, described once. Sources and ids are
   copied from the modules that create them today — this file does not
   invent any of them, and describe() reports an "orphan" if a module
   grows a layer that never got registered here.
   ================================================================== */
(function (L) {
  'use strict';
  var Z = L.Z;

  /* ---- Road network (05/07-data-loaders) ---- */
  L.register({
    key: 'roadnet', label: 'Road network', group: 'network',
    layers: ['roadnet-casing', 'roadnet'], source: 'roadnet',
    toggle: 'showRoads', z: Z.ROAD_NETWORK,
    ensure: function () { return loadRoads(true); }
  });

  /* The invisible click target. Its own family because it sits at a
     different depth from the lines it belongs to, and because it is
     deliberately ALWAYS visible — Video-on-click has to keep working
     with the network layer switched off. */
  L.register({
    key: 'roadnet-pick', label: 'Road network (click target)', group: 'network',
    layers: ['roadnet-hit'], source: 'roadnet',
    toggle: null, z: Z.ROAD_PICK,
    ensure: function () { return loadRoads(true); }
  });

  /* ---- Merged road network (22-road-merged) ----
     ensureRoads2Layer() builds the layers but returns early unless
     ROADS2_GJ is already populated, so the loader — not the builder —
     is what belongs here. */
  L.register({
    key: 'roadnet2', label: 'Merged road network', group: 'network',
    layers: ['roadnet2-casing', 'roadnet2', 'roadnet2-sel'], source: 'roadnet2',
    toggle: 'showRoads2', z: Z.ROAD_MERGED,
    ensure: function () { return loadRoads2FromServer(); }
  });

  /* ---- Pavement condition (07-data-loaders) ----
     One family, five render layers: the same source drawn once per
     carriageway lane, fanned apart with line-offset. This is the shape
     that per-parameter condition layers will reuse — one source, N
     layers, a different property key each. */
  L.register({
    key: 'condition', label: 'Road condition', group: 'pavement',
    layers: ['seg-CC', 'seg-CL1', 'seg-CL2', 'seg-CR1', 'seg-CR2'], source: 'segs',
    toggle: 'showCond', z: Z.CONDITION,
    ensure: function () { return loadSegments(); }
  });

  /* ---- PCI (14-pci-engine) ----
     Two families rather than one: they have separate toggles and are
     independently useful, even though both read the `segs` source and
     are built by the same call. */
  L.register({
    key: 'pci-avg', label: 'PCI (Composite)', group: 'pavement',
    layers: ['pci-avg'], source: 'segs',
    toggle: 'showPciAvg', z: Z.PCI,
    ensure: function () { return generatePCI(true); }
  });
  L.register({
    key: 'pci-worst', label: 'PCI (Worst-Lane)', group: 'pavement',
    layers: ['pci-worst'], source: 'segs',
    toggle: 'showPciWorst', z: Z.PCI,
    ensure: function () { return generatePCI(true); }
  });

  /* ---- 2 km IRI roll-up (32-iri-2km) ---- */
  L.register({
    key: 'iri2km', label: 'Avg IRI (2 km)', group: 'pavement',
    layers: ['iri2km'], source: 'iri2km',
    toggle: 'showIri2km', z: Z.IRI_2KM,
    ensure: function () { return loadIri2km(); }
  });

  /* ---- Assets (06-assets) ----
     Mirrors the ASSETS table in 06-assets.js. Each entry becomes a line
     layer, a point layer and an icon layer; which of the three actually
     get built depends on the data, so present() is what to trust rather
     than this list. describe() cross-checks the two — see below. */
  [
    { key: 'bridge',    label: 'Bridge',            layer: 'as-bridge',  toggle: 'showBridge',  z: Z.ASSET_LINE },
    { key: 'furn-line', label: 'Furniture (line)',  layer: 'as-furnl',   toggle: 'showFurnL',   z: Z.ASSET_LINE },
    { key: 'culvert',   label: 'Culvert',           layer: 'as-culvert', toggle: 'showCulvert', z: Z.ASSET_POINT },
    { key: 'furn-pt',   label: 'Furniture (point)', layer: 'as-furnp',   toggle: 'showFurnP',   z: Z.ASSET_POINT },
    { key: 'soil',      label: 'Sub-Grade Soil',    layer: 'as-soil',    toggle: 'showSoil',    z: Z.ASSET_POINT },
    { key: 'core',      label: 'Bituminous Core',   layer: 'as-core',    toggle: 'showCore',    z: Z.ASSET_POINT },
    { key: 'crust',     label: 'Pavement Crust',    layer: 'as-crust',   toggle: 'showCrust',   z: Z.ASSET_POINT },
    { key: 'fwd',       label: 'FWD',               layer: 'as-fwd',     toggle: 'showFwd',     z: Z.ASSET_POINT }
  ].forEach(function (a) {
    L.register({
      key: a.key, label: a.label, group: 'assets',
      layers: [a.layer, a.layer + '-pt', a.layer + '-icon'],
      source: a.layer,          // 06-assets uses one id for source and base layer
      toggle: a.toggle, z: a.z,
      assetLayer: a.layer,      // let describe() match us against ASSETS
      ensure: function () {
        // loadAsset takes the ASSETS entry, so find it rather than
        // duplicating its colour/width/kind fields here.
        if (typeof ASSETS === 'undefined' || typeof loadAsset !== 'function') return;
        for (var i = 0; i < ASSETS.length; i++) {
          if (ASSETS[i].layer === a.layer) return loadAsset(ASSETS[i]);
        }
      }
    });
  });

  /* ---- Traffic stations (16-traffic) ----
     loadTraffic takes a callback and returns nothing, unlike every other
     loader here. Adapted to a promise so ensure() has one contract:
     without this, show() would reveal the layer before the stations
     arrived. It fires the callback on both paths — already-loaded and
     freshly-fetched — so this never hangs. */
  L.register({
    key: 'traffic', label: 'Traffic stations', group: 'traffic',
    layers: ['trafficstn-lyr'], source: 'trafficstn',
    toggle: 'showTraffic', z: Z.TRAFFIC,
    ensure: function () {
      return new Promise(function (resolve) { loadTraffic(resolve); });
    }
  });

  /* ---- Administrative boundaries (04-geo-helpers-boundaries) ----
     Split fills/lines from labels: text belongs on top of the whole map,
     not buried under the road network with its own polygon. */
  L.register({
    key: 'district', label: 'District boundary', group: 'boundaries',
    layers: ['district-fill', 'district-casing', 'district-line'], source: 'district',
    toggle: 'showDist', z: Z.BOUNDARY_FILL,
    ensure: function () { return ensureBoundary('district'); }
  });
  L.register({
    key: 'district-label', label: 'District names', group: 'boundaries',
    layers: ['district-label'], source: 'district',
    toggle: 'showDist', z: Z.BOUNDARY_LABEL,
    ensure: function () { return ensureBoundary('district'); }
  });
  L.register({
    key: 'constituency', label: 'Constituency boundary', group: 'boundaries',
    layers: ['cons-fill', 'cons-line'], source: 'constituency',
    toggle: 'showCons', z: Z.BOUNDARY_FILL,
    ensure: function () { return ensureBoundary('constituency'); }
  });
  L.register({
    key: 'constituency-label', label: 'Constituency names', group: 'boundaries',
    layers: ['cons-label'], source: 'constituency',
    toggle: 'showCons', z: Z.BOUNDARY_LABEL,
    ensure: function () { return ensureBoundary('constituency'); }
  });

  /* ---- Tool layers (17-measure, 08-condition-popup-nsv) ----
     No toggle: these are driven by code, appear on demand and are torn
     down again. Registered so they have a declared depth and so
     describe() does not report them as orphans. */
  L.register({
    key: 'selection', label: 'Selected road', group: 'tools',
    layers: ['sel-road-glow', 'sel-road-line'], source: null,
    toggle: null, z: Z.SELECTION
  });
  L.register({
    key: 'measure', label: 'Measure tool', group: 'tools',
    layers: ['measure-fill', 'measure-line', 'measure-route', 'measure-pts'], source: null,
    toggle: null, z: Z.MEASURE
  });
})(KLLayers);
