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
          (l.temporary ? ' <span class="r2-hint">' + (l.shared ? 'temporary · shared' : 'temporary') + '</span>' : '') +
        '</span>' +
        '<input type="checkbox" id="showUL' + l.id + '">' +
        /* Discard right from the map, not just Layer Management — the point of
           a temporary layer is to look at it once and throw it away, and going
           to a separate admin screen to do that is the friction that leaves
           scratch layers piling up. Only offered for a layer this list already
           says is "mine" (see LayerDataService.viewerLayers); the server is the
           real guard either way — see LayerRegistryService.deleteLayer. */
        (l.temporary && l.mine
          ? '<button class="ul-del" title="Discard this temporary layer" aria-label="Discard">&times;</button>'
          : '');
      grp.appendChild(row);

      row.querySelector('input').addEventListener('change', function (e) {
        toggle(l, i, e.target.checked);
      });

      var del = row.querySelector('.ul-del');
      if (del) del.addEventListener('click', function (e) { e.preventDefault(); discard(l); });

      /* Rasters get an opacity slider right under their row — a vector layer's
         "appearance" (colour, label, popup) is set in Style & Label Management
         instead, but that screen has no notion of a raster's pixels, and a
         raster otherwise has no visual control here at all beyond on/off. */
      if (l.geometryType === 'RASTER') grp.appendChild(opacityRow(l));
    });

    if (note) {
      pane.insertBefore(title, note);
      pane.insertBefore(grp, note);
    } else {
      pane.appendChild(title);
      pane.appendChild(grp);
    }

    /* Build the matching Filter-panel sections only now. Each one locks itself
       to its layer's switch, and those switches are the checkboxes created a
       few lines above — asked for any earlier, 37-layer-filters.js would find
       none of them and every section would sit permanently locked. */
    if (window.KLLayerFilters) KLLayerFilters.refresh();
  }

  /**
   * A raster layer's opacity slider, shown right under its switch row.
   *
   * Reads its starting value from `viewer-layers` (see
   * LayerDataService.viewerLayers's `opacity` field) so it shows the saved
   * value even before the layer has ever been switched on — ensureRaster()
   * applies that same value as the paint layer's initial `raster-opacity`. If
   * the layer is already built (switched on earlier this session), dragging
   * the slider updates the map immediately; saving to the server is
   * debounced so dragging does not fire a request per pixel of travel.
   */
  function opacityRow(l) {
    var row = document.createElement('div');
    row.className = 'switch ul-opacity-row';
    var pct = Math.round((l.opacity == null ? 0.85 : l.opacity) * 100);
    row.innerHTML =
      '<span class="lname r2-hint">Opacity</span>' +
      '<input type="range" min="0" max="100" value="' + pct + '" class="ul-opacity">' +
      '<span class="r2-hint ul-opacity-pct">' + pct + '%</span>';

    var slider = row.querySelector('.ul-opacity');
    var pctLabel = row.querySelector('.ul-opacity-pct');
    var saveTimer = null;

    slider.addEventListener('input', function () {
      var v = Number(slider.value) / 100;
      pctLabel.textContent = slider.value + '%';
      var renderId = 'ul-' + l.id + '-raster';
      try { if (map.getLayer(renderId)) map.setPaintProperty(renderId, 'raster-opacity', v); } catch (e) {}

      clearTimeout(saveTimer);
      saveTimer = setTimeout(function () {
        fetch('/api/layer-data/' + l.id + '/raster/opacity', {
          method: 'PUT', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ opacity: v })
        }).catch(function () { /* the map already shows the new value either way */ });
      }, 400);
    });

    return row;
  }

  /**
   * Discard a temporary layer straight from the map.
   *
   * Same endpoint Layer Management's "Discard" button calls, with the same
   * server-side guard: only the layer's own creator (or a super admin, from
   * Layer Management) can actually delete it — see
   * LayerRegistryService.deleteLayer. purge=true so the table goes with it,
   * matching what "temporary" promises.
   */
  function discard(layer) {
    if (!confirm('Discard the temporary layer "' + layer.name + '"?\n\nThis cannot be undone.')) return;
    fetch('/api/layers/' + layer.id + '?purge=true', { method: 'DELETE', credentials: 'same-origin' })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (d) {
        if (!r.ok) throw new Error((d && d.error) || 'Could not discard the layer.');
      }); })
      .then(function () {
        setVis(layer.id, 'none');
        ids(layer.id).forEach(function (id) { try { if (map.getLayer(id)) map.removeLayer(id); } catch (e) {} });
        try { if (map.getSource('ul-' + layer.id)) map.removeSource('ul-' + layer.id); } catch (e) {}
        delete LOADED[layer.id];
        refresh();
      })
      .catch(function (e) { alert(e.message); });
  }

  /* ------------------------------------------------------------------
     Map layers
     ------------------------------------------------------------------ */

  function toggle(layer, i, on) {
    if (!on) { setVis(layer.id, 'none'); return; }
    ensure(layer, i).then(function () { setVis(layer.id, 'visible'); });
  }

  function ids(layerId) {
    return ['ul-' + layerId + '-fill', 'ul-' + layerId + '-line', 'ul-' + layerId + '-pt',
            'ul-' + layerId + '-raster'];
  }

  function setVis(layerId, v) {
    ids(layerId).forEach(function (id) {
      try { if (map.getLayer(id)) map.setLayoutProperty(id, 'visibility', v); } catch (e) { /* not built */ }
    });
  }

  function tilesOn() { return typeof TILES_ON !== 'undefined' && TILES_ON; }

  /**
   * Build the layers, once.
   *
   * Default render path is the vector tile at
   * /api/layer-data/{id}/tiles/{z}/{x}/{y}.mvt, the same as every other
   * paint layer in the viewer; ?tiles=0 falls back to the GeoJSON
   * endpoint, which is also what export and analysis still use. In tile
   * mode nothing is preloaded — MapLibre asks for the tiles it needs.
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
    /* Tell the style module which registry key this source belongs to,
       BEFORE its layers are built. A user layer's map ids (`ul-<id>-*`)
       and its registry key (a slug of whatever it was named) are two
       different things and neither can be derived from the other, so
       34-layer-style.js has no way to pair them on its own — and it has
       to have the pairing in hand by the time the layers appear, or a
       saved style would not reach the first one added. */
    if (window.KLStyle) KLStyle.registerUserLayer('ul-' + layer.id, layer.key);
    if (layer.geometryType === 'RASTER') return ensureRaster(layer, i);
    return tilesOn() ? ensureTiles(layer, i) : ensureGeoJson(layer, i);
  }

  /**
   * A raster temporary layer: PNG tiles from its own tile pyramid, not the MVT
   * fill/line/pt trio every other user layer gets. Status is fetched once to
   * pick up the zoom range the pyramid was actually built to and the
   * build_version to cache-bust with, the same convention the Drone viewer's
   * tile URLs use.
   */
  function ensureRaster(layer, i) {
    var src = 'ul-' + layer.id;
    if (map.getSource(src)) return Promise.resolve();
    return fetch('/api/layer-data/' + layer.id + '/raster/status', { credentials: 'same-origin' })
      .then(function (r) { return r.json(); })
      .then(function (d) {
        var raster = (d && d.raster) || {};
        if (raster.status !== 'PUBLISHED') {
          setStatus(layer, raster.status === 'FAILED' ? 'tile build failed' : 'not published yet');
          LOADED[layer.id] = false;
          return;
        }
        map.addSource(src, {
          type: 'raster',
          tiles: [location.origin + '/api/layer-data/' + layer.id + '/raster/tiles/{z}/{x}/{y}.png?v=' +
                  (raster.buildVersion || 0)],
          tileSize: 256,
          minzoom: raster.minZoom || 0,
          maxzoom: raster.maxZoom || 22
        });
        var before = (typeof KLLayers !== 'undefined' && KLLayers.beforeId)
          ? KLLayers.beforeId(KLLayers.Z.SELECTION - 1) : undefined;
        map.addLayer({
          id: src + '-raster', type: 'raster', source: src,
          layout: { visibility: 'none' },
          paint: { 'raster-opacity': raster.defaultOpacity != null ? raster.defaultOpacity : 0.85 }
        }, before);

        /* Active Map Layers (02e-active-layer.js) only discovers a layer by
           watching map.on('click'|'mouseenter', layerId, fn) — a raster layer
           can't actually be feature-queried for a real click (MapLibre returns
           no features for a raster source), which is why the pixel-value popup
           below is a map-wide handler with its own bounds check instead. This
           registration never fires; it exists purely so the raster layer is
           listed — and therefore reorderable via KLActive's move() — the same
           as every vector user layer. */
        if (window.KLActive) {
          map.on('click', src + '-raster', function () {});
          KLActive.label('ul-' + layer.id, layer.name + (layer.temporary ? ' · temporary' : ''));
        }
        bindRasterClick(layer, raster);
        setStatus(layer, 'raster tiles');
      })
      .catch(function () {
        LOADED[layer.id] = false;
        setStatus(layer, 'could not load');
      });
  }

  /**
   * Click-for-pixel-value, modeled on the Drone Viewer's DEM elevation popup
   * (drone-viewer.js identify()/sampleElevation()): a raster layer has no
   * discrete features to bind a feature-click to, so this is a map-level click
   * handler scoped to whether the layer is on and the click falls inside its
   * bounds.
   */
  function bindRasterClick(layer, raster) {
    var handler = function (e) {
      if (map.getLayoutProperty(layer._rasterLayerId, 'visibility') === 'none') return;
      // Same "which layer answers a click" rule every other layer's popup
      // obeys — see 02e-active-layer.js. This handler is map-wide rather than
      // layer-scoped (MapLibre cannot feature-query a raster layer), so it has
      // to check the gate itself instead of getting it for free the way a
      // map.on('click', layerId, fn) registration does.
      if (window.KLActive && !KLActive.isAuto() && KLActive.get() !== 'ul-' + layer.id) return;
      if (raster.minX == null) return;
      if (e.lngLat.lng < raster.minX || e.lngLat.lng > raster.maxX ||
          e.lngLat.lat < raster.minY || e.lngLat.lat > raster.maxY) return;

      var rampOn = !!raster.colourRamp;
      var label = rampOn ? (raster.valueLabel || 'Value') : 'Value';
      var h = '<div class="klpop"><div class="kp-head"><div class="kp-name">' + esc(layer.name) +
        '</div><div class="kp-meta">' +
        (layer.temporary ? '<span class="kp-chip">Temporary</span>' : '') +
        '</div></div><div class="kp-block"><div class="kp-attrs">' +
        '<div class="kp-attr"><span class="kp-k">' + esc(label) + '</span><span class="kp-v" id="ul-px-' + layer.id +
        '">reading…</span></div></div></div></div>';
      if (typeof klPopup === 'function') klPopup(e.lngLat, h);
      else new maplibregl.Popup({ maxWidth: '280px' }).setLngLat(e.lngLat).setHTML(h).addTo(map);

      fetch('/api/layer-data/' + layer.id + '/raster/pixel?lng=' + e.lngLat.lng + '&lat=' + e.lngLat.lat,
            { credentials: 'same-origin' })
        .then(function (r) { return r.json(); })
        .then(function (j) {
          var val = document.getElementById('ul-px-' + layer.id);
          if (!val) return;
          if (j.ok === false || !j.bands || !j.bands.length) { val.textContent = 'no data here'; return; }
          if (rampOn) {
            var v = j.bands[0];
            // The value label already carries its own unit (e.g. "Rainfall
            // (mm)"), so no unit is appended here the way the Drone DEM popup
            // appends " m" — that popup is elevation-only and can assume metres.
            val.textContent = v == null ? 'no data here' : Number(v).toFixed(2);
            return;
          }
          val.textContent = j.bands.map(function (v) { return v == null ? '—' : v; }).join(' / ');
        })
        .catch(function () {
          var val = document.getElementById('ul-px-' + layer.id);
          if (val) val.textContent = 'unavailable';
        });
    };
    layer._rasterLayerId = 'ul-' + layer.id + '-raster';
    map.on('click', handler);
  }

  function ensureTiles(layer, i) {
    var src = 'ul-' + layer.id;
    if (map.getSource(src)) return Promise.resolve();
    map.addSource(src, {
      type: 'vector',
      tiles: [location.origin + '/api/layer-data/' + layer.id + '/tiles/{z}/{x}/{y}.mvt'],
      minzoom: 0,
      maxzoom: 20
    });
    addPaintLayers(layer, i, src, UserLayerTileLayer);
    setStatus(layer, 'vector tiles');
    return Promise.resolve();
  }

  function ensureGeoJson(layer, i) {
    var src = 'ul-' + layer.id;
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
        addPaintLayers(layer, i, src, null);
        setStatus(layer, gj.features.length.toLocaleString() + ' features');
      })
      .catch(function () {
        LOADED[layer.id] = false;
        setStatus(layer, 'could not load');
      });
  }

  /** The MVT layer name the tile service writes; null for a GeoJSON source. */
  var UserLayerTileLayer = 'features';

  function addPaintLayers(layer, i, src, sourceLayer) {
    var col = colorFor(i);
    var before = (typeof KLLayers !== 'undefined' && KLLayers.beforeId)
      ? KLLayers.beforeId(KLLayers.Z.SELECTION - 1) : undefined;

    function spec(o) {
      o.source = src;
      if (sourceLayer) o['source-layer'] = sourceLayer;
      return o;
    }

    map.addLayer(spec({
      id: src + '-fill', type: 'fill',
      filter: ['match', ['geometry-type'], ['Polygon', 'MultiPolygon'], true, false],
      layout: { visibility: 'none' },
      paint: { 'fill-color': col, 'fill-opacity': 0.28, 'fill-outline-color': col }
    }), before);

    map.addLayer(spec({
      id: src + '-line', type: 'line',
      filter: ['match', ['geometry-type'], ['LineString', 'MultiLineString'], true, false],
      layout: { visibility: 'none', 'line-cap': 'round', 'line-join': 'round' },
      paint: {
        'line-color': col,
        'line-width': ['interpolate', ['linear'], ['zoom'], 8, 2, 16, 6]
      }
    }), before);

    map.addLayer(spec({
      id: src + '-pt', type: 'circle',
      filter: ['match', ['geometry-type'], ['Point', 'MultiPoint'], true, false],
      layout: { visibility: 'none' },
      paint: {
        'circle-radius': ['interpolate', ['linear'], ['zoom'], 8, 3.5, 16, 8],
        'circle-color': col,
        'circle-stroke-width': 1.4,
        'circle-stroke-color': '#0b1322'
      }
    }), before);

    ids(layer.id).forEach(function (id) { bindPopup(id, layer); });
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
   * What a click on this layer shows, as its style document says.
   *
   * Read at click time rather than captured when the layer is built: the
   * styling screen can save a change while the map is open, and KLStyle
   * repaints without rebuilding the layer. Null for a layer nobody has
   * styled, and null for a style saved before the popup section existed —
   * both meaning ALL, which is what this file did before there was a
   * choice to make.
   */
  function popupCfg(layer) {
    var s = (window.KLStyle && KLStyle.styleFor(layer.key)) || null;
    return (s && s.popup) || null;
  }

  function norm(s) {
    return String(s == null ? '' : s).toLowerCase().replace(/[^a-z0-9]/g, '');
  }

  /** Every spelling one catalogue attribute answers to, widest first. */
  function namesOf(a, fallback) {
    if (!a) return [fallback];
    return [a.key, a.name].concat(String(a.aliases || '').split(','));
  }

  /**
   * The rows this popup lists, in order, as {key, label, unit, value}.
   *
   * The LAYER decides the list, not the clicked feature. Layer Management
   * is the description of what this layer holds, so a popup built from
   * Object.keys(properties) disagrees with it twice: it drops any attribute
   * blank on this particular feature, which reads as "missing" rather than
   * "empty here", and it prints storage keys, so an attribute renamed in
   * Layer Management keeps showing its old slug on the map. Both are fixed
   * by asking the catalogue — the same source every asset and dashboard
   * card already labels itself from.
   *
   * Values are resolved through the attribute's aliases, so a row imported
   * under an older spelling of a column still fills that column's row.
   *
   * The catalogue is fetched once at page load, so a layer created after
   * the map was opened is not in it. That case falls through to the
   * feature's own keys below and behaves exactly as this did before.
   */
  function popupFields(layer, cfg, p) {
    var byNorm = {};
    Object.keys(p).forEach(function (k) {
      var n = norm(k);
      if (!(n in byNorm)) byNorm[n] = p[k];
    });

    var used = {}, out = [];
    var value = function (names) {
      for (var i = 0; i < names.length; i++) {
        var n = norm(names[i]);
        if (n && n in byNorm) { used[n] = true; return byNorm[n]; }
      }
      return null;
    };
    var push = function (key, label, unit, v) {
      out.push({ key: key, label: label || key, unit: unit || '', value: v });
    };

    /* A style that names its fields is an explicit answer to "what should a
       click show", so every field it names gets a row — including one this
       feature leaves blank, which is the difference between "no value" and
       "the style does not ask for it". */
    if (cfg.mode === 'FIELDS' && (cfg.fields || []).length) {
      cfg.fields.forEach(function (k) {
        var a = window.AttrCatalog ? AttrCatalog.attr(layer.key, k) : null;
        push(k, a && a.name, a && a.unit, value(namesOf(a, k)));
      });
      return out;
    }

    var cat = (window.AttrCatalog && AttrCatalog.fields(layer.key)) || [];
    cat.forEach(function (a) {
      var v = value(namesOf(a, a.key));
      /* A retired attribute earns a row only where the feature still carries
         what it recorded — it is no longer part of the layer's description,
         so an empty one is not a gap anybody is looking for. */
      if (a.active === false && (v == null || String(v).trim() === '')) return;
      push(a.key, a.name, a.unit, v);
    });

    /* Whatever the feature carries that the catalogue does not describe: a
       column adopted before the layer was described, or the whole feature
       when the catalogue never loaded. Appended rather than dropped — this
       is what keeps the popup working with no catalogue at all. */
    Object.keys(p).forEach(function (k) {
      var n = norm(k);
      if (used[n]) return;
      var v = p[k];
      if (v == null || String(v).trim() === '') return;
      used[n] = true;
      push(k, k, '', v);
    });
    return out;
  }

  /**
   * One attribute row.
   *
   * An empty value is shown as a dash rather than omitted, so the popup and
   * Layer Management list the same fields in the same order. A coded value
   * is expanded to what the code stands for, the same as every other card.
   */
  function attrRow(layer, r) {
    var blank = (r.value == null || String(r.value).trim() === '');
    var v = blank ? '—'
      : (window.AttrCatalog ? AttrCatalog.expand(layer.key, r.key, r.value) : r.value);
    return '<div class="kp-attr"><span class="kp-k">' + esc(r.label) + '</span>' +
           '<span class="kp-v' + (blank ? ' kp-empty' : '') + '">' + esc(v) +
           (r.unit && !blank ? ' <span class="kp-u">' + esc(r.unit) + '</span>' : '') +
           '</span></div>';
  }

  /**
   * Popup listing what the layer holds.
   *
   * Built on the shared .klpop card the asset and traffic popups already
   * use, so it inherits the dark theme, the scroll behaviour and the tip
   * styling rather than needing its own CSS in two stylesheets.
   *
   * The rows come from popupFields() — the layer's described attributes,
   * under the names Layer Management gives them. EVERY one of them is
   * reachable: the first 24 are listed outright and any remainder sits
   * behind a "show the rest" disclosure, because an imported shapefile can
   * carry a hundred columns and a popup that tall is unusable — but a
   * 28-column KML silently losing its last four attributes is worse. The
   * card already scrolls (.klpop.asset-klpop), so expanding costs nothing.
   * Where the layer's style names the fields it wants, those are shown
   * instead and in the order it lists them: a layer loaded to answer one
   * question should answer it without scrolling.
   */
  function bindPopup(layerId, layer) {
    /* Named for the active-layer chip before the handler registers, so the
       user picks it by the name they gave it rather than by its map id.
       A temporary layer is flagged there too — it is the one kind of layer
       someone may have several near-identical copies of. */
    if (window.KLActive) {
      KLActive.label('ul-' + layer.id, layer.name + (layer.temporary ? ' · temporary' : ''));
    }
    map.on('click', layerId, function (e) {
      var f = e.features && e.features[0];
      if (!f) return;
      var cfg = popupCfg(layer) || {};
      if (cfg.mode === 'NONE') return;

      var p = props(f);
      var has = function (k) { return p[k] != null && String(p[k]).trim() !== ''; };
      var chosen = popupFields(layer, cfg, p);
      var keys = chosen.slice(0, 24);
      var rest = chosen.slice(24);

      /* The heading names the FEATURE when the style says which field
         identifies it, and the layer name moves down to the chip line:
         "TVM_STN_021A" tells you what you clicked, while the layer name is
         already on the switch you turned it on with. */
      var head = (cfg.title && has(cfg.title)) ? String(p[cfg.title]) : null;

      var h = '<div class="klpop asset-klpop">' +
        '<div class="kp-head"><div class="kp-name">' + esc(head || layer.name) + '</div>' +
        '<div class="kp-meta">' +
          (head ? '<span class="kp-chip">' + esc(layer.name) + '</span>' : '') +
          (layer.temporary ? '<span class="kp-chip">Temporary</span>' : '') +
        '</div></div>';

      var rows = function (list) {
        return list.map(function (r) { return attrRow(layer, r); }).join('');
      };

      if (keys.length) {
        h += '<div class="kp-block"><div class="kp-eyebrow">Attributes</div><div class="kp-attrs">' +
          rows(keys) + '</div>' +
          (rest.length
            ? '<details class="trf-allcls"><summary>' + rest.length +
              ' more attribute' + (rest.length === 1 ? '' : 's') + '</summary>' +
              '<div class="kp-attrs">' + rows(rest) + '</div></details>'
            : '') +
          '</div>';
      } else {
        h += '<div class="kp-block"><div class="kp-eyebrow">Attributes</div>' +
             '<div class="kp-attrs"><div class="kp-attr"><span class="kp-k">' +
             'No attribute values on this feature</span></div></div></div>';
      }
      h += '</div>';

      if (typeof klPopup === 'function') klPopup(e.lngLat, h);
      else new maplibregl.Popup({ maxWidth: '340px' }).setLngLat(e.lngLat).setHTML(h).addTo(map);
    });
    map.on('mouseenter', layerId, function () { map.getCanvas().style.cursor = 'pointer'; });
    map.on('mouseleave', layerId, function () { map.getCanvas().style.cursor = ''; });
  }

  /**
   * A feature's attributes, whichever source it came from.
   *
   * MVT properties must be flat scalars, so the tile ships the whole
   * attribute bag as one `attrs` JSON string and it is expanded here. A
   * GeoJSON source already has the keys flattened. Returning the same
   * shape from both means the popup has one code path.
   */
  function props(f) {
    var p = (f && f.properties) || {};
    if (typeof p.attrs !== 'string') return p;
    try {
      var parsed = JSON.parse(p.attrs);
      return (parsed && typeof parsed === 'object') ? parsed : p;
    } catch (e) {
      return p;
    }
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

  /* colorFor is published so the export menu can draw the same swatch this
     panel drew for the layer — the colour is assigned by position in the list,
     which nothing outside this file could work out on its own.

     list/ensure/ids are published for the Map Composer (38-map-composer.js),
     which has to be able to put a user layer on a SHEET without switching it
     on in the VIEWER. That works because ensure() builds these layers with
     visibility:'none' (see addPaintLayers) — the Composer copies the finished
     layer definitions out of the style and makes them visible in its own
     offscreen style, leaving the operational map exactly as it found it.
     Re-implementing any of this in the Composer would mean a second colour
     assignment, a second tile URL and a second popup binding, all of which
     would drift from this file the first time it changed. */
  window.KLUserLayers = {
    refresh: refresh,
    colorFor: colorFor,
    /** Every viewer layer this user can see, in panel order (the order
     *  colorFor() is indexed by, so callers must keep the index). */
    list: function () { return LIST.slice(); },
    /** Build one layer's sources/layers if they are not there yet. */
    ensure: function (layer, i) { return ensure(layer, i); },
    /** The three render layer ids a user layer owns. */
    ids: function (layerId) { return ids(layerId); }
  };

  if (typeof map !== 'undefined' && map.loaded && map.loaded()) boot();
  else if (typeof map !== 'undefined') map.on('load', boot);
  else document.addEventListener('DOMContentLoaded', boot);
})();
