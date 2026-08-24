/* ============================================================
   KLRAMS viewer · 33-user-layers.js
   The map's home for layers that did not exist when the viewer was
   written: the ones created in Layer Management, and the temporary
   ones someone drops in to look at once and throw away.

   Everything else in the Layers panel is markup in map.html with a
   matching module. These layers are not knowable at build time — they
   are rows in layer_definition — so this section builds itself from
   /api/layer-data/viewer-layers and creates its MapLibre layers on
   demand, the first time a toggle is ticked.

   Draw order: user layers sit above the network and condition colouring
   but below the tool layers, so a dropped file is visible against the
   basemap without hiding the selection highlight or the measure tool.
   ============================================================ */
(function () {
  'use strict';

  var LOADED = {};    // layerId -> true once its source is on the map
  var LIST = [];

  /* Palette for user layers. Deliberately distinct from every built-in
     colour in map.html so a user layer is never mistaken for road
     condition or an asset class. Assigned by position, stable per load. */
  var COLORS = ['#e0529c', '#4dd4ac', '#f2a03d', '#7c8cf8', '#48c7e8', '#c77dff'];

  function colorFor(i) { return COLORS[i % COLORS.length]; }

  /* ------------------------------------------------------------------
     Panel
     ------------------------------------------------------------------ */

  function boot() {
    fetch('/api/layer-data/viewer-layers', { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        LIST = (d && d.layers) || [];
        renderPanel();
      })
      .catch(function () { /* viewer works fine without them */ });
  }

  /**
   * Build the "My layers" group in the Layers pane.
   *
   * The group is only added when there is at least one layer to put in
   * it — an empty section with a "nothing here" note is noise in a panel
   * that is already long.
   */
  function renderPanel() {
    var pane = document.getElementById('pane-layers');
    if (!pane) return;

    var old = document.getElementById('ul-group');
    var oldTitle = document.getElementById('ul-group-title');
    if (old) old.remove();
    if (oldTitle) oldTitle.remove();
    if (!LIST.length) return;

    var note = pane.querySelector('.note');

    var title = document.createElement('div');
    title.className = 'grp-title';
    title.id = 'ul-group-title';
    title.textContent = 'My layers';

    var grp = document.createElement('div');
    grp.className = 'grp';
    grp.id = 'ul-group';

    LIST.forEach(function (l, i) {
      var row = document.createElement('div');
      row.className = 'switch';
      row.innerHTML =
        '<span class="lname">' +
          '<span class="ldot" style="background:' + colorFor(i) + '"></span>' +
          esc(l.name) +
          (l.temporary ? ' <span class="r2-hint">temporary</span>' : '') +
        '</span>' +
        '<input type="checkbox" id="showUL' + l.id + '">';
      grp.appendChild(row);

      row.querySelector('input').addEventListener('change', function (e) {
        toggle(l, i, e.target.checked);
      });
    });

    if (note) {
      pane.insertBefore(title, note);
      pane.insertBefore(grp, note);
    } else {
      pane.appendChild(title);
      pane.appendChild(grp);
    }
  }

  /* ------------------------------------------------------------------
     Map layers
     ------------------------------------------------------------------ */

  function toggle(layer, i, on) {
    if (!on) { setVis(layer.id, 'none'); return; }
    ensure(layer, i).then(function () { setVis(layer.id, 'visible'); });
  }

  function ids(layerId) {
    return ['ul-' + layerId + '-fill', 'ul-' + layerId + '-line', 'ul-' + layerId + '-pt'];
  }

  function setVis(layerId, v) {
    ids(layerId).forEach(function (id) {
      try { if (map.getLayer(id)) map.setLayoutProperty(id, 'visibility', v); } catch (e) { /* not built */ }
    });
  }

  /**
   * Fetch the features and build the layers, once.
   *
   * All three geometry kinds get a layer rather than only the one the
   * layer declares, because a "Point" layer whose CSV failed to place a
   * few rows still has points, and a shapefile layer may legitimately
   * mix line and polygon parts. MapLibre skips a layer whose filter
   * matches nothing, so the unused ones cost nothing.
   */
  function ensure(layer, i) {
    if (LOADED[layer.id]) return Promise.resolve();
    LOADED[layer.id] = true;

    var src = 'ul-' + layer.id;
    var col = colorFor(i);

    return fetch('/api/layer-data/' + layer.id + '/geojson', { credentials: 'same-origin' })
      .then(function (r) { return r.json(); })
      .then(function (gj) {
        if (typeof gj === 'string') gj = JSON.parse(gj);
        if (!gj || !gj.features || !gj.features.length) {
          setStatus(layer, 'no features yet');
          return;
        }
        if (map.getSource(src)) { map.getSource(src).setData(gj); return; }

        map.addSource(src, { type: 'geojson', data: gj, generateId: true });

        var before = (typeof KLLayers !== 'undefined' && KLLayers.beforeId)
          ? KLLayers.beforeId(KLLayers.Z.SELECTION - 1) : undefined;

        map.addLayer({
          id: src + '-fill', type: 'fill', source: src,
          filter: ['match', ['geometry-type'], ['Polygon', 'MultiPolygon'], true, false],
          layout: { visibility: 'none' },
          paint: { 'fill-color': col, 'fill-opacity': 0.28, 'fill-outline-color': col }
        }, before);

        map.addLayer({
          id: src + '-line', type: 'line', source: src,
          filter: ['match', ['geometry-type'], ['LineString', 'MultiLineString'], true, false],
          layout: { visibility: 'none', 'line-cap': 'round', 'line-join': 'round' },
          paint: {
            'line-color': col,
            'line-width': ['interpolate', ['linear'], ['zoom'], 8, 2, 16, 6]
          }
        }, before);

        map.addLayer({
          id: src + '-pt', type: 'circle', source: src,
          filter: ['match', ['geometry-type'], ['Point', 'MultiPoint'], true, false],
          layout: { visibility: 'none' },
          paint: {
            'circle-radius': ['interpolate', ['linear'], ['zoom'], 8, 3.5, 16, 8],
            'circle-color': col,
            'circle-stroke-width': 1.4,
            'circle-stroke-color': '#0b1322'
          }
        }, before);

        ids(layer.id).forEach(function (id) { bindPopup(id, layer); });
        setStatus(layer, gj.features.length.toLocaleString() + ' features');
      })
      .catch(function () {
        LOADED[layer.id] = false;
        setStatus(layer, 'could not load');
      });
  }

  function setStatus(layer, text) {
    var box = document.getElementById('showUL' + layer.id);
    if (!box) return;
    var row = box.closest('.switch');
    var hint = row && row.querySelector('.r2-hint');
    if (!hint) return;
    hint.textContent = (layer.temporary ? 'temporary · ' : '') + text;
  }

  /**
   * Popup listing whatever the feature carries.
   *
   * Built on the shared .klpop card the asset and traffic popups already
   * use, so it inherits the dark theme, the scroll behaviour and the tip
   * styling rather than needing its own CSS in two stylesheets.
   *
   * A user layer's fields are not known at build time, so the rows come
   * from the properties the feature actually has. Capped at 24: an
   * imported shapefile can carry a hundred columns, and a popup that tall
   * is unusable.
   */
  function bindPopup(layerId, layer) {
    map.on('click', layerId, function (e) {
      var f = e.features && e.features[0];
      if (!f) return;
      var p = f.properties || {};
      var all = Object.keys(p).filter(function (k) {
        return p[k] != null && String(p[k]).trim() !== '';
      });
      var keys = all.slice(0, 24);

      var h = '<div class="klpop asset-klpop">' +
        '<div class="kp-head"><div class="kp-name">' + esc(layer.name) + '</div>' +
        '<div class="kp-meta">' +
          (layer.temporary ? '<span class="kp-chip">Temporary</span>' : '') +
        '</div></div>';

      if (keys.length) {
        h += '<div class="kp-block"><div class="kp-eyebrow">Attributes</div><div class="kp-attrs">' +
          keys.map(function (k) {
            return '<div class="kp-attr"><span class="kp-k">' + esc(k) +
                   '</span><span class="kp-v">' + esc(p[k]) + '</span></div>';
          }).join('') +
          '</div></div>';
      } else {
        h += '<div class="kp-block"><div class="kp-eyebrow">Attributes</div>' +
             '<div class="kp-attrs"><div class="kp-attr"><span class="kp-k">' +
             'No attribute values on this feature</span></div></div></div>';
      }
      if (all.length > keys.length) {
        h += '<div class="kp-block"><div class="kp-eyebrow">' +
             (all.length - keys.length) + ' more not shown</div></div>';
      }
      h += '</div>';

      if (typeof klPopup === 'function') klPopup(e.lngLat, h);
      else new maplibregl.Popup({ maxWidth: '340px' }).setLngLat(e.lngLat).setHTML(h).addTo(map);
    });
    map.on('mouseenter', layerId, function () { map.getCanvas().style.cursor = 'pointer'; });
    map.on('mouseleave', layerId, function () { map.getCanvas().style.cursor = ''; });
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  /** Re-read the list — called after an import so a new layer appears. */
  function refresh() {
    LOADED = {};
    boot();
  }

  window.KLUserLayers = { refresh: refresh };

  if (typeof map !== 'undefined' && map.loaded && map.loaded()) boot();
  else if (typeof map !== 'undefined') map.on('load', boot);
  else document.addEventListener('DOMContentLoaded', boot);
})();
