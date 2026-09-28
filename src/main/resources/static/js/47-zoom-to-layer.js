/* ============================================================
   KLRAMS viewer · 47-zoom-to-layer.js
   "Zoom to layer" — an explicit button on every layer's row in the
   Layers panel (and on every row under Temporary Layers) that fits
   the map to that layer's own extent.

   Why a button, not automatic zoom-on-toggle
   -------------------------------------------
   The viewer's layers range from a handful of assets on one road to
   the whole road network covering all of Kerala. An automatic zoom
   the instant a checkbox is ticked would fight the user on the common
   case (switching several layers on in a row while already looking at
   the area they care about) just to help the rarer one. A button next
   to each row gives the same "jump to it" power on request instead.

   What "the layer's extent" means
   --------------------------------
   Always the data actually showing, not the whole uploaded dataset:
   - Road-linked layers (network, condition, PCI, IRI, assets, traffic)
     honour window.NET_SCOPE, the Road Network filter's live result set
     (see scopePropFor in 05-road-network.js for which property each
     layer scopes by).
   - The Road Condition attribute filter narrows further, via the same
     matchingFeatures() the filter panel itself uses to fit the map.
   - Boundaries, user layers and the free-form asset types (Sub-Grade
     Soil, Bituminous Core) honour whatever js/37-layer-filters.js has
     matched, via its exported matchIds().
   A filter that matches nothing correctly zooms nowhere — the button
   flashes red rather than guessing.

   Each lookup is intentionally independent of whatever the owning
   module has cached: it asks for the WHOLE-network answer on click
   (loadAssetData, ensureFullRoadsGeojson, Segs.ensure, the boundary/
   user-layer GeoJSON endpoints), which is a click-triggered cost, not
   a per-toggle one — the same lazy-analysis pattern 06-assets.js and
   07-data-loaders.js already use for the PCI report and CSV export.
   ============================================================ */
var KLAutoZoom = (function () {
  'use strict';

  var ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" ' +
    'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<circle cx="12" cy="12" r="7"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3"/></svg>';

  function icon() { return ICON; }

  /* ------------------------------------------------------------------
     Small shared helpers
     ------------------------------------------------------------------ */

  function bboxOf(feats) {
    if (!feats || !feats.length || typeof turf === 'undefined') return null;
    try {
      var bb = turf.bbox({ type: 'FeatureCollection', features: feats });
      if (![bb[0], bb[1], bb[2], bb[3]].every(isFinite)) return null;
      return [[bb[0], bb[1]], [bb[2], bb[3]]];
    } catch (e) { return null; }
  }

  /** Features whose `prop` is in the live Road Network filter result, or
   *  every feature when that filter is not active. */
  function scopeFeatures(feats, prop) {
    if (!window.NET_SCOPE || !feats) return feats;
    return feats.filter(function (f) {
      var p = (f && f.properties) || {};
      return NET_SCOPE.has(String(p[prop] != null ? p[prop] : ''));
    });
  }

  function klfEnsure(key) {
    return window.KLLayerFilters ? KLLayerFilters.ensureBags(key) : Promise.resolve();
  }
  function klfMatchIds(key) {
    return window.KLLayerFilters ? KLLayerFilters.matchIds(key) : null;
  }

  function fit(bounds) {
    if (!bounds) return;
    try {
      map.fitBounds(bounds, { padding: 70, maxZoom: 16, duration: 800 });
    } catch (e) { /* map not ready */ }
  }

  /* ------------------------------------------------------------------
     Per-family "what to fit to"
     ------------------------------------------------------------------ */

  function roadnetBounds() {
    /* ROADS is a top-level `let` in 01-config.js, so it is a shared global
       binding every classic script sees — but never a `window.ROADS`
       property, which is why this reads the bare name instead. */
    return Promise.resolve(typeof ensureFullRoadsGeojson === 'function' ? ensureFullRoadsGeojson() : null)
      .then(function () {
        var src = (typeof ROADS !== 'undefined') ? ROADS : {};
        var feats = Object.keys(src).map(function (k) { return src[k]; });
        return bboxOf(scopeFeatures(feats, 'road'));
      }).catch(function () { return null; });
  }

  function roadnet2Bounds() {
    /* Same story as ROADS: ROADS2_GJ is a top-level `let` in
       22-road-merged.js, not a window property. */
    if (typeof ROADS2_GJ !== 'undefined' && ROADS2_GJ && ROADS2_GJ.features && ROADS2_GJ.features.length) {
      return Promise.resolve(bboxOf(ROADS2_GJ.features));
    }
    return fetch('/api/full-network/geojson', { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (gj) { return bboxOf((gj && gj.features) || []); })
      .catch(function () { return null; });
  }

  function conditionBounds() {
    if (typeof Segs === 'undefined') return Promise.resolve(null);
    return Segs.ensure().then(function () {
      var fts = (typeof matchingFeatures === 'function') ? matchingFeatures() : null;
      if (!fts || !fts.length) fts = Segs.all();
      return bboxOf(scopeFeatures(fts, 'road'));
    }).catch(function () { return null; });
  }

  function iri2kmBounds() {
    return fetch('/api/iri-2km/geojson', { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (gj) { return bboxOf(scopeFeatures((gj && gj.features) || [], 'road')); })
      .catch(function () { return null; });
  }

  function trafficBounds() {
    return new Promise(function (resolve) {
      if (typeof loadTraffic !== 'function') return resolve(null);
      try {
        loadTraffic(function () {
          var fts = (typeof TRAFFIC_STN !== 'undefined' && TRAFFIC_STN.features) || [];
          resolve(bboxOf(scopeFeatures(fts, 'section')));
        });
      } catch (e) { resolve(null); }
    });
  }

  /* road_assets families: bridge/furn-line/culvert/furn-pt/soil/core/crust/fwd.
     `type` is the ASSETS[].type this key corresponds to — see KEY_TO_TYPE. */
  function assetBounds(type) {
    var a = (typeof ASSETS !== 'undefined' ? ASSETS : []).filter(function (x) { return x.type === type; })[0];
    if (!a || typeof loadAssetData !== 'function') return Promise.resolve(null);
    return loadAssetData(a).then(function (gj) {
      var feats = (gj && gj.features) || [];
      feats = scopeFeatures(feats, '__sec');
      var key = 'a_' + type;
      return klfEnsure(key).then(function () {
        var ids = klfMatchIds(key);
        if (ids) {
          feats = feats.filter(function (f) {
            var p = f.properties || {};
            var id = (p.__id != null) ? p.__id : p.asset_id;
            return ids.has(id);
          });
        }
        return bboxOf(feats);
      });
    }).catch(function () { return null; });
  }

  function boundaryBounds(type) {
    var key = 'b_' + type;
    return Promise.resolve(typeof ensureBoundary === 'function' ? ensureBoundary(type) : null)
      .then(function () { return klfEnsure(key); })
      .then(function () {
        var gj = (window.BOUNDARY_DATA || {})[type];
        var feats = ((gj || {}).features) || [];
        var ids = klfMatchIds(key);
        if (ids) feats = feats.filter(function (f, i) { return ids.has(i); });
        return bboxOf(feats);
      }).catch(function () { return null; });
  }

  /** A layer created in Layer Management, or a temporary one — never in
   *  KLLayers, so 33-user-layers.js calls this directly by layer id. */
  function userLayerBounds(id) {
    var key = 'u_' + id;
    return fetch('/api/layer-data/' + id + '/geojson', { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (gj) { return (typeof gj === 'string') ? JSON.parse(gj) : gj; })
      .catch(function () { return null; })
      .then(function (gj) {
        var feats = (gj && gj.features) || [];
        if (feats.length) {
          return klfEnsure(key).then(function () {
            var ids = klfMatchIds(key);
            if (!ids) return bboxOf(feats);
            var f2 = feats.filter(function (f) {
              var p = f.properties || {};
              var rid = (p.__id != null) ? p.__id : p.id;
              return ids.has(rid);
            });
            return bboxOf(f2.length ? f2 : feats);
          });
        }
        /* Not a vector layer (or nothing came back) — try it as a raster. */
        return fetch('/api/layer-data/' + id + '/raster/status', { credentials: 'same-origin' })
          .then(function (r) { return r.json(); })
          .then(function (d) {
            var raster = (d && d.raster) || {};
            if (raster.minX == null) return null;
            return [[raster.minX, raster.minY], [raster.maxX, raster.maxY]];
          }).catch(function () { return null; });
      });
  }

  /* ------------------------------------------------------------------
     Wiring: one provider per KLLayers family that has a toggle.
     ------------------------------------------------------------------ */

  var KEY_TO_ASSET_TYPE = {
    bridge: 'bridge', 'furn-line': 'furniture_line', culvert: 'culvert',
    'furn-pt': 'furniture_point', soil: 'subgrade', core: 'bituminous_core',
    crust: 'pavement_crust', fwd: 'fwd'
  };

  var PROVIDERS = {
    roadnet: roadnetBounds,
    roadnet2: roadnet2Bounds,
    condition: conditionBounds,
    'pci-avg': conditionBounds,
    'pci-worst': conditionBounds,
    iri2km: iri2kmBounds,
    traffic: trafficBounds,
    district: function () { return boundaryBounds('district'); },
    constituency: function () { return boundaryBounds('constituency'); }
  };
  Object.keys(KEY_TO_ASSET_TYPE).forEach(function (key) {
    var type = KEY_TO_ASSET_TYPE[key];
    PROVIDERS[key] = function () { return assetBounds(type); };
  });

  function makeButton() {
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'zoom-lyr-btn';
    btn.title = 'Zoom to layer extent';
    btn.setAttribute('aria-label', 'Zoom to layer extent');
    btn.innerHTML = ICON;
    return btn;
  }

  function flashEmpty(btn) {
    btn.classList.add('empty');
    setTimeout(function () { btn.classList.remove('empty'); }, 1200);
  }

  function run(btn, provider) {
    if (!btn || btn.__klBusy) return;
    btn.__klBusy = true;
    btn.classList.add('busy');
    Promise.resolve().then(provider).then(function (bounds) {
      btn.classList.remove('busy');
      btn.__klBusy = false;
      if (bounds) fit(bounds); else flashEmpty(btn);
    }, function () {
      btn.classList.remove('busy');
      btn.__klBusy = false;
      flashEmpty(btn);
    });
  }

  function zoomToUserLayer(id, btn) {
    run(btn, function () { return userLayerBounds(id); });
  }

  /** Every KLLayers family with a real toggle, deduped (district and
   *  district-label share one checkbox, and so on). */
  function injectButtons() {
    if (typeof KLLayers === 'undefined') return;
    var seen = {};
    KLLayers.all().forEach(function (spec) {
      if (!spec.toggle || seen[spec.toggle]) return;
      seen[spec.toggle] = true;
      var provider = PROVIDERS[spec.key];
      if (!provider) return;
      var chk = document.getElementById(spec.toggle);
      if (!chk || chk.__klZoomWired) return;
      chk.__klZoomWired = true;

      var wrap = document.createElement('span');
      wrap.className = 'switch-actions';
      var btn = makeButton();
      btn.disabled = !chk.checked;
      chk.parentNode.insertBefore(wrap, chk);
      wrap.appendChild(btn);
      wrap.appendChild(chk);

      btn.addEventListener('click', function () { run(btn, provider); });
      chk.addEventListener('change', function () { btn.disabled = !chk.checked; });
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', injectButtons);
  } else {
    injectButtons();
  }

  return { icon: icon, makeButton: makeButton, zoomToUserLayer: zoomToUserLayer };
})();
