/*
 * drone-viewer.js — the Drone Viewer map.
 *
 * A standalone MapLibre page rather than another panel inside map.html. The main
 * viewer loads 30-odd modules and the whole condition/PCI/FWD data stack on open;
 * a drone survey needs the road network and two raster layers, so it gets its own
 * light page and reuses the SAME sources the main viewer does — the road network
 * MVT endpoint and the road index — instead of duplicating any of that data.
 */
(function () {
  'use strict';

  var BASEMAPS = {
    osm:  { tiles: ['https://a.tile.openstreetmap.org/{z}/{x}/{y}.png',
                    'https://b.tile.openstreetmap.org/{z}/{x}/{y}.png',
                    'https://c.tile.openstreetmap.org/{z}/{x}/{y}.png'], attribution: '© OpenStreetMap' },
    sat:  { tiles: ['https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}'],
            attribution: '© Esri' },
    topo: { tiles: ['https://a.tile.opentopomap.org/{z}/{x}/{y}.png',
                    'https://b.tile.opentopomap.org/{z}/{x}/{y}.png'], attribution: '© OpenTopoMap' },
    light:{ tiles: ['https://a.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png',
                    'https://b.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png'], attribution: '© CARTO' },
    dark: { tiles: ['https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png',
                    'https://b.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png'], attribution: '© CARTO' }
  };

  var ROAD_SOURCE = 'klrams-roads';
  var ROAD_LAYER = 'klrams-roads-line';
  var ROAD_CASING = 'klrams-roads-casing';
  var MEASURE_SOURCE = 'measure';
  /** Every Nth contour is drawn heavier and labelled; matches DroneContourService. */
  var CONTOUR_INDEX_EVERY = 5;

  var datasets = [];
  var visible = Object.create(null);     // datasetId -> true when drawn
  var roadIndex = [];
  /** Section label -> that road's full record from /api/roads/index. */
  var roadBySection = Object.create(null);
  var mode = 'identify';
  var measurePoints = [];
  var popup = null;
  /** Pin dropped by the place / coordinate search; cleared by the Clear tool. */
  var placeMarker = null;

  var map = new maplibregl.Map({
    container: 'map',
    style: {
      version: 8,
      // Required before ANY symbol layer can render text, and shared with the main
      // viewer (js/02-map-core.js) rather than pointed somewhere new.
      glyphs: 'https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf',
      sources: baseSources(),
      layers: Object.keys(BASEMAPS).map(function (k) {
        return { id: 'bm-' + k, type: 'raster', source: 'bm-' + k,
                 layout: { visibility: k === 'sat' ? 'visible' : 'none' } };
      })
    },
    center: [76.95, 8.52],
    zoom: 8,
    maxZoom: 23
  });

  function baseSources() {
    var s = {};
    Object.keys(BASEMAPS).forEach(function (k) {
      s['bm-' + k] = { type: 'raster', tiles: BASEMAPS[k].tiles, tileSize: 256,
                       attribution: BASEMAPS[k].attribution };
    });
    return s;
  }

  map.addControl(new maplibregl.NavigationControl({ visualizePitch: false }), 'top-right');
  map.addControl(new maplibregl.ScaleControl({ maxWidth: 130, unit: 'metric' }), 'bottom-right');
  map.addControl(new maplibregl.FullscreenControl(), 'top-right');

  /* ---------------- helpers ---------------- */

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  function num(v, d) { return v == null ? '—' : Number(v).toFixed(d == null ? 2 : d); }

  function fmtBytes(n) {
    if (n == null) return '—';
    var u = ['B', 'KB', 'MB', 'GB'], i = 0, v = Number(n);
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
    return (i === 0 ? v : v.toFixed(1)) + ' ' + u[i];
  }

  function fmtDate(s) {
    if (!s) return '—';
    var d = new Date(s);
    if (isNaN(d)) return String(s).slice(0, 10);
    return String(d.getDate()).padStart(2, '0') + '-' +
           String(d.getMonth() + 1).padStart(2, '0') + '-' + d.getFullYear();
  }

  function readout(title, big, small) {
    var box = document.getElementById('readout');
    box.textContent = '';
    if (!title) { box.classList.remove('on'); return; }
    box.appendChild(el('div', 't', title));
    box.appendChild(el('div', 'big', big));
    if (small) box.appendChild(el('div', 'sm', small));
    box.classList.add('on');
  }

  /* ---------------- road network ---------------- */

  function addRoadNetwork() {
    map.addSource(ROAD_SOURCE, {
      type: 'vector',
      tiles: [location.origin + '/api/roads/tiles/{z}/{x}/{y}.mvt'],
      minzoom: 0,
      maxzoom: 14
    });
    map.addLayer({
      id: ROAD_CASING, type: 'line', source: ROAD_SOURCE, 'source-layer': 'roads',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: {
        'line-color': '#08121f',
        'line-width': ['interpolate', ['linear'], ['zoom'], 8, 3, 14, 8, 18, 14],
        'line-opacity': 0.85
      }
    });
    map.addLayer({
      id: ROAD_LAYER, type: 'line', source: ROAD_SOURCE, 'source-layer': 'roads',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: {
        'line-color': ['match', ['get', 'Road_Class'],
          'NH', '#e65b5b', 'SH', '#ffa630', 'MDR', '#3b86e6', 'ODR', '#2bb3c0', '#8a99ad'],
        'line-width': ['interpolate', ['linear'], ['zoom'], 8, 1.4, 14, 4, 18, 8]
      }
    });
  }

  function addMeasureLayers() {
    map.addSource(MEASURE_SOURCE, { type: 'geojson', data: emptyFc() });
    map.addLayer({ id: 'measure-fill', type: 'fill', source: MEASURE_SOURCE,
      filter: ['==', '$type', 'Polygon'],
      paint: { 'fill-color': '#8a68e6', 'fill-opacity': 0.22 } });
    map.addLayer({ id: 'measure-line', type: 'line', source: MEASURE_SOURCE,
      filter: ['!=', '$type', 'Point'],
      paint: { 'line-color': '#b39dfb', 'line-width': 2.5, 'line-dasharray': [2, 1.4] } });
    map.addLayer({ id: 'measure-pt', type: 'circle', source: MEASURE_SOURCE,
      filter: ['==', '$type', 'Point'],
      paint: { 'circle-radius': 4.5, 'circle-color': '#fff', 'circle-stroke-color': '#8a68e6',
               'circle-stroke-width': 2.5 } });
  }

  function emptyFc() { return { type: 'FeatureCollection', features: [] }; }

  /* ---------------- drone raster layers ---------------- */

  function sourceId(d) { return 'drone-src-' + d.id; }
  function layerId(d) { return 'drone-lyr-' + d.id; }

  /** True for a contour set imported from a survey file: lines, not pixels. */
  function isVector(d) { return d.dataset_type === 'CONTOUR'; }

  function showDataset(d) {
    if (visible[d.id]) return;
    // An imported contour set has no raster pyramid — it IS the vector layer, so it
    // goes through the same code that draws a DEM's traced contours.
    if (isVector(d)) { showContours(d); visible[d.id] = true; return; }
    map.addSource(sourceId(d), {
      type: 'raster',
      // build_version in the URL is what makes a re-published dataset a different
      // resource: the tiles are cached for 30 days, so without it a rebuild would
      // stay invisible until the cache expired.
      tiles: [location.origin + '/api/drone/datasets/' + d.id + '/tiles/{z}/{x}/{y}.png?b=' + d.build_version],
      tileSize: 256,
      minzoom: d.min_zoom == null ? 0 : d.min_zoom,
      maxzoom: d.max_zoom == null ? 22 : d.max_zoom,
      bounds: [d.min_x, d.min_y, d.max_x, d.max_y],
      attribution: 'Drone survey · ' + d.project_code
    });
    map.addLayer({
      id: layerId(d), type: 'raster', source: sourceId(d),
      paint: { 'raster-opacity': 1, 'raster-resampling': 'nearest' }
    }, roadsOnTop() ? ROAD_CASING : undefined);
    visible[d.id] = true;
  }

  function hideDataset(d) {
    if (!visible[d.id]) return;
    if (map.getLayer(layerId(d))) map.removeLayer(layerId(d));
    if (map.getSource(sourceId(d))) map.removeSource(sourceId(d));
    delete visible[d.id];
    // Contours belong to the DEM; leaving them behind would float lines over a
    // layer that is no longer shown.
    hideContours(d);
    if (d.__paintContours) d.__paintContours();
  }

  function roadsOnTop() { return document.getElementById('roads-top').checked; }

  /** Re-stack every visible raster in panel order, then put the roads where asked. */
  function applyOrder() {
    datasets.forEach(function (d) {
      if (visible[d.id] && !isVector(d) && map.getLayer(layerId(d))) map.moveLayer(layerId(d));
    });
    if (map.getLayer(ROAD_CASING)) {
      if (roadsOnTop()) { map.moveLayer(ROAD_CASING); map.moveLayer(ROAD_LAYER); }
      else {
        var first = datasets.filter(function (d) { return visible[d.id] && !isVector(d); })[0];
        if (first) { map.moveLayer(ROAD_CASING, layerId(first)); map.moveLayer(ROAD_LAYER, layerId(first)); }
      }
    }
    // Contours sit above every raster — a contour under its own hillshade is
    // invisible — but below the measuring overlay, which must stay on top.
    datasets.forEach(function (d) {
      if (!contourOn[d.id]) return;
      if (map.getLayer(contourLine(d))) map.moveLayer(contourLine(d));
      if (map.getLayer(contourLabel(d))) map.moveLayer(contourLabel(d));
    });
    ['measure-fill', 'measure-line', 'measure-pt'].forEach(function (id) {
      if (map.getLayer(id)) map.moveLayer(id);
    });
  }

  function zoomTo(d) {
    map.fitBounds([[d.min_x, d.min_y], [d.max_x, d.max_y]], { padding: 40, duration: 900 });
  }

  /* ---------------- dataset panel ---------------- */

  function renderDatasets() {
    var box = document.getElementById('datasets');
    box.textContent = '';
    document.getElementById('ds-count').textContent = datasets.length ? String(datasets.length) : '';

    if (!datasets.length) {
      var empty = el('div', 'empty');
      empty.appendChild(document.createTextNode('No published drone data yet. Upload an orthomosaic or DEM in the '));
      var a = el('a', null, 'Drone console');
      a.href = '/drone.html';
      empty.appendChild(a);
      empty.appendChild(document.createTextNode(' and publish it.'));
      box.appendChild(empty);
      return;
    }

    datasets.forEach(function (d) {
      var card = el('div', 'ds');

      var head = el('div', 'ds-h');
      var cb = el('input');
      cb.type = 'checkbox';
      cb.checked = !!visible[d.id];
      cb.addEventListener('change', function () {
        if (cb.checked) { showDataset(d); applyOrder(); } else { hideDataset(d); }
      });
      head.appendChild(cb);

      var names = el('div');
      names.style.flex = '1';
      names.style.minWidth = '0';
      names.appendChild(el('div', 'ds-n', d.dataset_name));
      names.appendChild(el('div', 'ds-k', kindLabel(d) + ' · ' + d.project_code));
      head.appendChild(names);
      card.appendChild(head);

      var op = el('div', 'op');
      op.appendChild(el('label', null, 'Opacity'));
      var slider = el('input');
      slider.type = 'range'; slider.min = '0'; slider.max = '100'; slider.value = '100';
      var pct = el('span', 'pct', '100%');
      slider.addEventListener('input', function () {
        pct.textContent = slider.value + '%';
        var v = Number(slider.value) / 100;
        // A contour set is lines, so opacity means the stroke's — including the
        // labels', which would otherwise stay solid over a faded layer.
        if (isVector(d)) {
          if (map.getLayer(contourLine(d))) map.setPaintProperty(contourLine(d), 'line-opacity', v);
          if (map.getLayer(contourLabel(d))) map.setPaintProperty(contourLabel(d), 'text-opacity', v);
        } else if (map.getLayer(layerId(d))) {
          map.setPaintProperty(layerId(d), 'raster-opacity', v);
        }
      });
      op.appendChild(slider);
      op.appendChild(pct);
      card.appendChild(op);

      var btns = el('div', 'ds-b');

      var zoom = el('button', 'mini', 'Zoom to');
      zoom.addEventListener('click', function () {
        if (!visible[d.id]) { cb.checked = true; showDataset(d); applyOrder(); }
        zoomTo(d);
      });
      btns.appendChild(zoom);

      var up = el('button', 'mini', 'Bring to front');
      up.addEventListener('click', function () {
        var i = datasets.indexOf(d);
        if (i < datasets.length - 1) {
          datasets.splice(i, 1);
          datasets.push(d);
          renderDatasets();
          applyOrder();
        }
      });
      btns.appendChild(up);

      var infoBtn = el('button', 'mini', 'Info');
      var info = el('div', 'info');
      infoBtn.addEventListener('click', function () {
        info.classList.toggle('on');
        infoBtn.classList.toggle('on');
      });
      btns.appendChild(infoBtn);
      card.appendChild(btns);

      buildInfo(info, d);
      card.appendChild(info);

      if (d.dataset_type === 'DEM') {
        card.appendChild(elevationRamp(d));
        card.appendChild(contourPanel(d));
      }

      box.appendChild(card);
    });
  }

  function buildInfo(info, d) {
    var degrees = d.epsg === 4326;

    /* An imported contour set has no pixels, so the raster rows would all read
       "—". It gets the handful of facts that do apply to it instead. */
    if (d.dataset_type === 'CONTOUR') {
      [['Project', d.project_code + ' — ' + d.project_name],
       ['Road / Location', [d.road_section, d.location].filter(Boolean).join(' · ') || '—'],
       ['Survey date', fmtDate(d.survey_date)],
       ['Source', d.format || 'Imported contour lines'],
       ['Lines', d.contour_count == null ? '—' : String(d.contour_count)],
       ['Interval', d.contour_interval == null ? 'irregular' : d.contour_interval + ' m'],
       ['Elevation', d.elevation_min == null ? '—'
            : num(d.elevation_min, 2) + ' – ' + num(d.elevation_max, 2) + ' m'],
       ['Extent', num(d.min_x, 5) + ', ' + num(d.min_y, 5) + ' → '
            + num(d.max_x, 5) + ', ' + num(d.max_y, 5)]
      ].forEach(function (r) {
        var row = el('div', 'r');
        row.appendChild(el('div', 'k', r[0]));
        row.appendChild(el('div', 'v', r[1] == null ? '—' : String(r[1])));
        info.appendChild(row);
      });
      return;
    }

    var rows = [
      ['Project', d.project_code + ' — ' + d.project_name],
      ['Road / Location', [d.road_section, d.location].filter(Boolean).join(' · ') || '—'],
      ['PWD section', d.pwd_section || '—'],
      ['Survey date', fmtDate(d.survey_date)],
      ['Coordinate system', d.crs_name],
      ['Raster size', d.raster_width + ' × ' + d.raster_height + ' px'],
      ['Resolution', num(d.res_x, degrees ? 8 : 3) + (degrees ? '°' : ' m') + ' per pixel'],
      ['File size', fmtBytes(d.file_size)],
      ['Bands', d.band_count == null ? null : d.band_count + (d.colour_interp ? ' · ' + d.colour_interp : '')],
      ['Data type', d.data_type],
      ['NoData', d.no_data == null ? 'none declared' : String(d.no_data)],
      ['Tile zooms', d.min_zoom + ' – ' + d.max_zoom],
      ['Extent', num(d.min_x, 5) + ', ' + num(d.min_y, 5) + ' → ' + num(d.max_x, 5) + ', ' + num(d.max_y, 5)]
    ];
    if (d.dataset_type === 'DEM')
      rows.splice(8, 0, ['Elevation', num(d.elevation_min, 2) + ' – ' + num(d.elevation_max, 2) + ' m']);
    if (d.warnings) rows.push(['Check', d.warnings]);

    rows.forEach(function (r) {
      var row = el('div', 'r');
      row.appendChild(el('div', 'k', r[0]));
      row.appendChild(el('div', 'v', r[1] == null ? '—' : String(r[1])));
      info.appendChild(row);
    });

    appendBandTable(info, d);
    appendGeoDetails(info, d);
  }

  /**
   * What the file says about its own coordinate reference and provenance.
   *
   * <p>Kept apart from the rows above because it answers a different question. The
   * rows say where the raster is and what it holds; this says what it is referenced
   * TO — which datum, which projection, and whether its heights are above a geoid or
   * above the ellipsoid. On a road survey that last one is the difference between a
   * level that matches a benchmark and one that is tens of metres out.
   */
  function appendGeoDetails(info, d) {
    var det;
    try { det = JSON.parse(d.geo_details || 'null'); } catch (e) { det = null; }
    if (!det) return;
    var keys = Object.keys(det);
    if (!keys.length) return;

    var wrap = el('div', 'geo');
    wrap.appendChild(el('div', 'geo-t', 'Coordinate reference'));
    keys.forEach(function (k) {
      var row = el('div', 'r');
      row.appendChild(el('div', 'k', k));
      row.appendChild(el('div', 'v', det[k]));
      wrap.appendChild(row);
    });

    /* Kept visually apart from the rows above: everything there is what the FILE
       declares, this is what a person recorded from the survey documentation. */
    if (d.geoid_model) {
      var row = el('div', 'r');
      row.appendChild(el('div', 'k', 'Geoid model'));
      var v = el('div', 'v');
      v.appendChild(document.createTextNode(d.geoid_model));
      v.appendChild(el('span', 'geo-src', ' recorded from documentation'));
      row.appendChild(v);
      wrap.appendChild(row);
    }
    info.appendChild(wrap);
  }

  /**
   * Per-band value ranges.
   *
   * <p>Shown because the range is what explains how the image is being drawn: a
   * 16-bit orthomosaic whose bands top out in the low thousands is not using its
   * type's full range, and the viewer is stretching it to make it visible. Without
   * this, "why does my image look like that" has no answer anywhere in the UI.
   */
  function appendBandTable(info, d) {
    var stats;
    try { stats = JSON.parse(d.band_stats || 'null'); } catch (e) { stats = null; }
    if (!stats || !stats.length) return;

    var wrap = el('div', 'bands');
    var head = el('div', 'band-h');
    ['Band', 'Min', 'Max', 'Displayed'].forEach(function (h) {
      head.appendChild(el('span', null, h));
    });
    wrap.appendChild(head);

    // 8-bit data is drawn as-is; anything else is mapped through the low/high window.
    var stretched = !!d.data_type && d.data_type.indexOf('8-bit') < 0;

    stats.forEach(function (b) {
      var row = el('div', 'band-r');
      row.appendChild(el('span', 'band-n', b.label || ('Band ' + b.band)));
      row.appendChild(el('span', null, fmtNum(b.min)));
      row.appendChild(el('span', null, fmtNum(b.max)));
      row.appendChild(el('span', null,
        stretched ? fmtNum(b.low) + ' – ' + fmtNum(b.high) : 'as-is'));
      wrap.appendChild(row);
    });

    if (stretched) {
      wrap.appendChild(el('div', 'band-note',
        'These bands are not 8-bit, so the “Displayed” range is stretched across '
        + '0–255 for drawing. The stored values are unchanged.'));
    }
    info.appendChild(wrap);
  }

  /** Compact number for the band table: integers plain, fractions to 3 places. */
  function fmtNum(v) {
    if (v == null) return '—';
    var n = Number(v);
    if (!isFinite(n)) return '—';
    return n === Math.round(n) ? String(n) : n.toFixed(3);
  }

  /* ---------------- contours ---------------- */

  var contourOn = Object.create(null);      // datasetId -> true when drawn
  var contourPoll = null;

  function contourSource(d) { return 'contour-src-' + d.id; }
  function contourLine(d) { return 'contour-lyr-' + d.id; }
  function contourLabel(d) { return 'contour-lbl-' + d.id; }

  /**
   * Draw a DEM's contours.
   *
   * <p>Two line layers off one source rather than one with a data-driven width:
   * index contours want a heavier stroke AND the labels, and MapLibre places labels
   * per layer. Splitting them is what lets every fifth line carry its height without
   * the others competing for the same label slots.
   */
  function showContours(d) {
    if (contourOn[d.id]) return;
    map.addSource(contourSource(d), {
      type: 'vector',
      tiles: [location.origin + '/api/drone/datasets/' + d.id + '/contours/tiles/{z}/{x}/{y}.mvt'],
      minzoom: 0,
      maxzoom: 22
    });

    map.addLayer({
      id: contourLine(d), type: 'line', source: contourSource(d), 'source-layer': 'contours',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: {
        'line-color': ['case', ['get', 'is_index'], '#f0d27a', '#e0b84a'],
        /* One zoom interpolation with a case at each stop, not a case wrapping two
           interpolations — the style spec allows only a single zoom-driven
           interpolate per expression and rejects the layer outright otherwise. */
        'line-width': ['interpolate', ['linear'], ['zoom'],
          14, ['case', ['get', 'is_index'], 1.1, 0.5],
          20, ['case', ['get', 'is_index'], 2.4, 1.1]],
        'line-opacity': ['interpolate', ['linear'], ['zoom'], 13, 0.35, 16, 0.9]
      }
    });

    map.addLayer({
      id: contourLabel(d), type: 'symbol', source: contourSource(d), 'source-layer': 'contours',
      filter: ['get', 'is_index'],
      layout: {
        'symbol-placement': 'line',
        'text-field': ['concat', ['to-string', ['round', ['get', 'elevation']]], ' m'],
        'text-size': 10,
        // Must name a stack the glyph server really has; anything else renders
        // nothing at all. See the FONTS list in js/34-layer-style.js.
        'text-font': ['Noto Sans Bold'],
        // Well apart, so a dense DEM does not turn into a wall of numbers.
        'symbol-spacing': 220,
        'text-max-angle': 25,
        'text-allow-overlap': false
      },
      paint: {
        'text-color': '#ffeaa8',
        'text-halo-color': 'rgba(8,18,31,0.9)',
        'text-halo-width': 1.4
      }
    });

    contourOn[d.id] = true;
    applyOrder();
  }

  function hideContours(d) {
    if (!contourOn[d.id]) return;
    [contourLabel(d), contourLine(d)].forEach(function (id) {
      if (map.getLayer(id)) map.removeLayer(id);
    });
    if (map.getSource(contourSource(d))) map.removeSource(contourSource(d));
    delete contourOn[d.id];
  }

  /** Redraw a dataset's contours from scratch — used after a re-trace. */
  function refreshContours(d) {
    if (!contourOn[d.id]) return;
    hideContours(d);
    showContours(d);
  }

  /**
   * The contour controls under a DEM: an interval, a trace button, a toggle.
   *
   * <p>The interval is asked for rather than guessed. A half-metre contour is right
   * for a road cross-section and absurd for a hillside, and only the person who
   * flew it knows which they have.
   */
  function contourPanel(d) {
    var wrap = el('div', 'contour');
    var head = el('div', 'contour-h');
    head.appendChild(el('span', null, 'Contours'));
    var count = el('span', 'contour-c');
    head.appendChild(count);
    wrap.appendChild(head);

    var row = el('div', 'contour-row');
    var input = el('input');
    input.type = 'number';
    input.min = '0.05';
    input.step = '0.5';
    input.value = d.contour_interval == null ? suggestInterval(d) : String(d.contour_interval);
    input.title = 'Contour interval in metres';
    row.appendChild(input);
    row.appendChild(el('span', 'contour-u', 'm'));

    var make = el('button', 'mini', 'Trace');
    var toggle = el('button', 'mini', 'Show');
    var drop = el('button', 'mini', 'Clear');
    row.appendChild(make);
    row.appendChild(toggle);
    row.appendChild(drop);
    wrap.appendChild(row);

    var note = el('div', 'contour-n');
    wrap.appendChild(note);

    function paint() {
      var status = d.contour_status;
      var n = d.contour_count || 0;
      count.textContent = n ? n + ' lines' : '';
      toggle.disabled = !(status === 'READY' && n > 0);
      drop.disabled = !status;
      toggle.classList.toggle('on', !!contourOn[d.id]);
      toggle.textContent = contourOn[d.id] ? 'Hide' : 'Show';
      make.disabled = status === 'PROCESSING';
      make.textContent = status === 'PROCESSING' ? 'Tracing…' : 'Trace';

      if (status === 'PROCESSING') note.textContent = 'Tracing contours — this can take a minute.';
      else if (status === 'FAILED') note.textContent = d.contour_message || 'Tracing failed.';
      else if (status === 'READY' && n > 0)
        note.textContent = 'Every ' + CONTOUR_INDEX_EVERY + 'th contour is drawn heavier and labelled.';
      else note.textContent = '';
      note.className = 'contour-n' + (status === 'FAILED' ? ' bad' : '');
    }

    make.addEventListener('click', function () {
      var interval = Number(input.value);
      if (!(interval > 0)) { note.className = 'contour-n bad'; note.textContent = 'Enter an interval above zero.'; return; }
      make.disabled = true;
      make.textContent = 'Tracing…';
      fetch('/api/drone/datasets/' + d.id + '/contours', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ interval: interval })
      }).then(function (r) { return r.json(); })
        .then(function (j) {
          if (j && j.ok === false) throw new Error(j.error || 'Tracing failed.');
          d.contour_status = 'PROCESSING';
          paint();
          pollContours();
        })
        .catch(function (e) {
          note.className = 'contour-n bad';
          note.textContent = e.message;
          make.disabled = false;
          make.textContent = 'Trace';
        });
    });

    toggle.addEventListener('click', function () {
      if (contourOn[d.id]) hideContours(d); else showContours(d);
      paint();
    });

    drop.addEventListener('click', function () {
      hideContours(d);
      fetch('/api/drone/datasets/' + d.id + '/contours', { method: 'DELETE', credentials: 'same-origin' })
        .then(function () {
          d.contour_status = null;
          d.contour_count = 0;
          paint();
        });
    });

    d.__paintContours = paint;
    paint();
    return wrap;
  }

  /** A starting interval that gives roughly 20 lines over the DEM's range. */
  function suggestInterval(d) {
    var lo = Number(d.elevation_min), hi = Number(d.elevation_max);
    if (!isFinite(lo) || !isFinite(hi) || hi <= lo) return '1';
    var raw = (hi - lo) / 20;
    // Round to a value a surveyor would actually use.
    var steps = [0.1, 0.25, 0.5, 1, 2, 2.5, 5, 10, 20, 25, 50, 100];
    for (var i = 0; i < steps.length; i++) if (raw <= steps[i]) return String(steps[i]);
    return String(steps[steps.length - 1]);
  }

  /** Poll while any DEM is tracing, then stop. Mirrors the console's publish poll. */
  function pollContours() {
    if (contourPoll) return;
    contourPoll = setInterval(function () {
      var tracing = datasets.filter(function (d) { return d.contour_status === 'PROCESSING'; });
      if (!tracing.length) { clearInterval(contourPoll); contourPoll = null; return; }

      fetch('/api/drone/published', { credentials: 'same-origin' })
        .then(function (r) { return r.json(); })
        .then(function (rows) {
          rows.forEach(function (row) {
            var d = datasets.filter(function (x) { return x.id === row.id; })[0];
            if (!d) return;
            var was = d.contour_status;
            d.contour_status = row.contour_status;
            d.contour_count = row.contour_count;
            d.contour_interval = row.contour_interval;
            if (d.__paintContours) d.__paintContours();
            if (was === 'PROCESSING' && row.contour_status === 'READY') refreshContours(d);
          });
        })
        .catch(function () { /* try again on the next tick */ });
    }, 3000);
  }

  /** What the card calls this dataset. */
  function kindLabel(d) {
    if (d.dataset_type === 'DEM') return 'DEM';
    if (d.dataset_type === 'CONTOUR') return 'Contours';
    return 'Orthomosaic';
  }

  function elevationRamp(d) {
    var box = el('div', 'ramp');
    box.appendChild(el('div', 'ramp-bar'));
    var labels = el('div', 'ramp-l');
    labels.appendChild(el('span', null, num(d.elevation_min, 1) + ' m'));
    labels.appendChild(el('span', null, 'Elevation'));
    labels.appendChild(el('span', null, num(d.elevation_max, 1) + ' m'));
    box.appendChild(labels);
    return box;
  }

  /* ---------------- identify ---------------- */

  /**
   * Half-width, in pixels, of the box an Identify click searches.
   *
   * <p>A road centreline renders about 4px wide at mid zoom, so a bare point query
   * demands pixel-perfect aim and mostly returns nothing — which reads as "identify
   * is broken" rather than "you missed". A small box is what makes a thin line
   * clickable, and it is small enough that it still resolves to the road under the
   * cursor rather than a neighbouring one.
   */
  var TAP_TOLERANCE = 6;

  function identify(e) {
    var tapBox = [
      [e.point.x - TAP_TOLERANCE, e.point.y - TAP_TOLERANCE],
      [e.point.x + TAP_TOLERANCE, e.point.y + TAP_TOLERANCE]
    ];
    var layers = roadLayersPresent();
    var feats = layers.length ? map.queryRenderedFeatures(tapBox, { layers: layers }) : [];
    var demOn = datasets.filter(function (d) {
      return visible[d.id] && d.dataset_type === 'DEM' && withinBounds(d, e.lngLat);
    });

    if (popup) { popup.remove(); popup = null; }

    var box = el('div', 'klpop');
    if (feats.length) {
      var tile = feats[0].properties || {};
      /* A road tile carries four properties — road / name / len / Road_Class — and
         deliberately not the other 25 columns, because the per-feature tag list is
         most of an MVT's weight (see RoadTileService). Everything else is looked up
         in the road index by section label, which is already loaded for search and
         is the same route the main viewer takes for whole-network questions. */
      var full = roadBySection[tile.road] || {};
      box.appendChild(el('div', 'h', full.Road_Name || tile.name || tile.road || 'Road'));
      [['Location', roadLocation(full)],
       ['Section', tile.road],
       ['Class', full.Road_Class || tile.Road_Class],
       ['District', full.District],
       ['PWD section', full.PWD_Sec],
       ['Length', tile.len == null ? null : Number(tile.len).toFixed(0) + ' m']
      ].forEach(function (r) {
        if (r[1] == null || r[1] === '') return;
        var row = el('div', 'r');
        row.appendChild(el('div', 'k', r[0]));
        row.appendChild(el('div', null, String(r[1])));
        box.appendChild(row);
      });
    } else if (!demOn.length) {
      readout(null);
      return;
    } else {
      box.appendChild(el('div', 'h', 'Drone DEM'));
    }

    // "Coordinates", not "Location": a road already has a Location row (its start
    // and end place names), and two rows with the same label reads as a bug.
    var coordRow = el('div', 'r');
    coordRow.appendChild(el('div', 'k', 'Coordinates'));
    coordRow.appendChild(el('div', null, e.lngLat.lat.toFixed(6) + ', ' + e.lngLat.lng.toFixed(6)));
    box.appendChild(coordRow);

    popup = new maplibregl.Popup({ closeButton: true, maxWidth: '300px' })
      .setLngLat(e.lngLat).setDOMContent(box).addTo(map);

    if (demOn.length) sampleElevation(demOn[0], e.lngLat, box);
    else readout(null);
  }

  /**
   * A road's location as "start to end".
   *
   * <p>Shown where the road's CRN used to be. The road network's own CRN column
   * holds the literal string "CRN" on every row of the current import, so it told
   * the user nothing; the start and end place names are what someone actually
   * recognises a stretch of road by.
   */
  function roadLocation(p) {
    var from = (p && p.Rd_Str_Loc) || '', to = (p && p.Rd_End_Loc) || '';
    if (from && to) return from + ' → ' + to;
    return from || to || '';
  }

  function withinBounds(d, ll) {
    return ll.lng >= d.min_x && ll.lng <= d.max_x && ll.lat >= d.min_y && ll.lat <= d.max_y;
  }

  function sampleElevation(d, lngLat, box) {
    var row = el('div', 'r');
    row.appendChild(el('div', 'k', 'Elevation'));
    var val = el('div', null, 'reading…');
    row.appendChild(val);
    box.appendChild(row);

    fetch('/api/drone/datasets/' + d.id + '/elevation?lng=' + lngLat.lng + '&lat=' + lngLat.lat,
          { credentials: 'same-origin' })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        if (j.ok === false) { val.textContent = 'unavailable'; return; }
        if (j.elevation == null) { val.textContent = 'no data here'; readout(null); return; }
        val.textContent = j.elevation.toFixed(2) + ' m';
        readout('Elevation · ' + d.dataset_name, j.elevation.toFixed(2) + ' m',
                lngLat.lat.toFixed(6) + ', ' + lngLat.lng.toFixed(6));
      })
      .catch(function () { val.textContent = 'unavailable'; });
  }

  function roadLayersPresent() {
    return map.getLayer(ROAD_LAYER) && map.getLayoutProperty(ROAD_LAYER, 'visibility') !== 'none'
      ? [ROAD_LAYER] : [];
  }

  /* ---------------- measure ---------------- */

  function addMeasurePoint(lngLat) {
    measurePoints.push([lngLat.lng, lngLat.lat]);
    drawMeasure();
  }

  function drawMeasure() {
    var feats = measurePoints.map(function (c) {
      return { type: 'Feature', geometry: { type: 'Point', coordinates: c }, properties: {} };
    });

    if (mode === 'dist' && measurePoints.length >= 2) {
      var line = turf.lineString(measurePoints);
      feats.push(line);
      var m = turf.length(line, { units: 'kilometers' }) * 1000;
      readout('Measured distance', m >= 1000 ? (m / 1000).toFixed(3) + ' km' : m.toFixed(2) + ' m',
              measurePoints.length + ' points · click to extend, Clear to reset');
    } else if (mode === 'area' && measurePoints.length >= 3) {
      var ring = measurePoints.concat([measurePoints[0]]);
      var poly = turf.polygon([ring]);
      feats.push(poly);
      var a = turf.area(poly);
      readout('Measured area',
              a >= 10000 ? (a / 10000).toFixed(4) + ' ha' : a.toFixed(2) + ' m²',
              (a >= 10000 ? a.toFixed(0) + ' m² · ' : '') + 'perimeter ' +
              (turf.length(turf.lineString(ring), { units: 'kilometers' }) * 1000).toFixed(1) + ' m');
    } else {
      readout(mode === 'area' ? 'Measure area' : 'Measure distance',
              measurePoints.length + ' point' + (measurePoints.length === 1 ? '' : 's'),
              mode === 'area' ? 'Click at least three points.' : 'Click a second point.');
    }

    map.getSource(MEASURE_SOURCE).setData({ type: 'FeatureCollection', features: feats });
  }

  /** Clears everything the map draws on top of the data: measurements, the
   *  identify popup and the place-search marker. One button, because from the
   *  user's side they are all "the thing I just put on the map". */
  function clearMeasure() {
    measurePoints = [];
    if (map.getSource(MEASURE_SOURCE)) map.getSource(MEASURE_SOURCE).setData(emptyFc());
    readout(null);
    if (popup) { popup.remove(); popup = null; }
    if (placeMarker) { placeMarker.remove(); placeMarker = null; }
  }

  function setMode(next) {
    mode = next;
    document.getElementById('t-identify').classList.toggle('on', mode === 'identify');
    document.getElementById('t-dist').classList.toggle('on', mode === 'dist');
    document.getElementById('t-area').classList.toggle('on', mode === 'area');
    map.getCanvas().style.cursor = mode === 'identify' ? '' : 'crosshair';
    clearMeasure();
  }

  document.getElementById('t-identify').addEventListener('click', function () { setMode('identify'); });
  document.getElementById('t-dist').addEventListener('click', function () { setMode('dist'); });
  document.getElementById('t-area').addEventListener('click', function () { setMode('area'); });
  document.getElementById('t-clear').addEventListener('click', clearMeasure);
  document.getElementById('t-full').addEventListener('click', function () {
    var box = document.querySelector('.map-box');
    if (document.fullscreenElement) document.exitFullscreen();
    else if (box.requestFullscreen) box.requestFullscreen();
  });
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape') clearMeasure(); });

  /* ---------------- basemap + road toggles ---------------- */

  document.getElementById('basemap').addEventListener('change', function () {
    var pick = this.value;
    Object.keys(BASEMAPS).forEach(function (k) {
      map.setLayoutProperty('bm-' + k, 'visibility', k === pick ? 'visible' : 'none');
    });
  });

  document.getElementById('roads-on').addEventListener('change', function () {
    var v = this.checked ? 'visible' : 'none';
    [ROAD_LAYER, ROAD_CASING].forEach(function (id) {
      if (map.getLayer(id)) map.setLayoutProperty(id, 'visibility', v);
    });
  });

  document.getElementById('roads-top').addEventListener('change', applyOrder);

  /* ---------------- search ---------------- */

  var qBox = document.getElementById('q');
  var resBox = document.getElementById('results');

  /**
   * How well a road matches the query, or 0 for no match. Higher is better.
   *
   * <p>Three fields are searchable — road name, section label and road number — and
   * they are scored rather than merely filtered, because they behave differently: a
   * road number is a short exact token where "1" must not be buried under every road
   * whose name happens to contain a 1, while a name is naturally a substring search.
   * Ranking is what keeps a one- or two-character query useful.
   */
  function scoreRoad(r, q) {
    var name = String(r.Road_Name || '').toLowerCase();
    var section = String(r.Section_La || '').toLowerCase();
    var num = r.Road_Num == null ? '' : String(r.Road_Num);

    if (num && /^[0-9]+$/.test(q)) {
      if (num === q) return 100;
      if (num.indexOf(q) === 0) return 80;
    }
    if (name.indexOf(q) === 0) return 60;
    if (section.indexOf(q) === 0) return 50;
    if (name.indexOf(q) >= 0) return 40;
    if (section.indexOf(q) >= 0) return 30;
    return 0;
  }

  qBox.addEventListener('input', function () {
    var raw = qBox.value.trim();
    var q = raw.toLowerCase();
    resBox.textContent = '';
    // One character is enough for a road number; free text needs two to be useful.
    var min = /^[0-9]+$/.test(q) ? 1 : 2;
    if (q.length < min) { resBox.classList.remove('on'); return; }

    var hits = [];
    roadIndex.forEach(function (r) {
      var s = scoreRoad(r, q);
      if (s > 0) hits.push({ road: r, score: s });
    });
    hits.sort(function (a, b) {
      if (b.score !== a.score) return b.score - a.score;
      return String(a.road.Road_Name || '').localeCompare(String(b.road.Road_Name || ''));
    });
    hits = hits.slice(0, 25);

    if (!hits.length) {
      resBox.appendChild(el('div', 'res', 'No road matches "' + raw + '".'));
      resBox.classList.add('on');
      return;
    }

    hits.forEach(function (h) {
      var r = h.road;
      var row = el('div', 'res');
      row.appendChild(el('div', 'n', r.Road_Name || r.Section_La));
      row.appendChild(el('div', 'm',
        [r.Section_La,
         r.Road_Num == null || r.Road_Num === '' ? null : 'Road no. ' + r.Road_Num,
         r.District].filter(Boolean).join(' · ')));
      row.addEventListener('click', function () {
        resBox.classList.remove('on');
        qBox.value = r.Road_Name || r.Section_La;
        flyToRoad(r);
      });
      resBox.appendChild(row);
    });
    resBox.classList.add('on');
  });

  /* Close whichever dropdown the click did not land inside — including when the
     click lands in the OTHER search box, so the two lists are never open at once. */
  document.addEventListener('click', function (e) {
    var inside = e.target.closest('.search');
    if (!inside || !inside.contains(resBox)) resBox.classList.remove('on');
    var pRes = document.getElementById('presults');
    if (pRes && (!inside || !inside.contains(pRes))) pRes.classList.remove('on');
  });

  function flyToRoad(r) {
    fetch('/api/roads/one/geojson?section=' + encodeURIComponent(r.Section_La),
          { credentials: 'same-origin' })
      .then(function (res) { return res.ok ? res.json() : null; })
      .then(function (fc) {
        if (!fc || !fc.features || !fc.features.length) return;
        var b = turf.bbox(fc);
        map.fitBounds([[b[0], b[1]], [b[2], b[3]]], { padding: 60, duration: 900 });
      })
      .catch(function () { /* leave the map where it is */ });
  }

  /* ---------------- place / coordinate search ---------------- */

  var pBox = document.getElementById('pq');
  var pResBox = document.getElementById('presults');
  var geocodeTimer = null;
  var geocodeSeq = 0;

  /**
   * Read a coordinate pair out of free text, or {@code null} if it isn't one.
   *
   * <p>Counts the numbers rather than matching one grand regex: two of them is a
   * decimal pair, four is degrees+minutes, six is degrees+minutes+seconds. That
   * accepts every separator people actually type — commas, spaces, degree symbols,
   * quotes — without a pattern per combination.
   */
  function parseCoords(text) {
    var s = String(text || '').trim();
    if (!s) return null;

    var upper = s.toUpperCase();
    /* Any letter other than a hemisphere marker means this is a place name, not a
       coordinate. The class is every letter EXCEPT N, S, E and W — written out as
       the four gaps around them, so E is not swallowed by a careless A-M range. */
    if (/[A-DF-MO-RT-VX-Z]/.test(upper)) return null;

    var nums = s.match(/-?\d+(?:\.\d+)?/g);
    if (!nums) return null;
    nums = nums.map(Number);

    function dms(d, m, sec) {
      var sign = d < 0 ? -1 : 1;
      return sign * (Math.abs(d) + (m || 0) / 60 + (sec || 0) / 3600);
    }

    var a, b;
    if (nums.length === 2) { a = nums[0]; b = nums[1]; }
    else if (nums.length === 4) { a = dms(nums[0], nums[1], 0); b = dms(nums[2], nums[3], 0); }
    else if (nums.length === 6) { a = dms(nums[0], nums[1], nums[2]); b = dms(nums[3], nums[4], nums[5]); }
    else return null;

    if (/S/.test(upper)) a = -Math.abs(a);
    else if (/N/.test(upper)) a = Math.abs(a);
    if (/W/.test(upper)) b = -Math.abs(b);
    else if (/E/.test(upper)) b = Math.abs(b);

    /* Written lng,lat instead of lat,lng. Kerala's latitude band (8-13) and
       longitude band (75-78) do not overlap, so the pair is unambiguous and worth
       fixing silently — but the resolved values are shown back to the user. */
    var swapped = false;
    if (Math.abs(a) > 60 && Math.abs(b) < 30) { var t = a; a = b; b = t; swapped = true; }

    if (!isFinite(a) || !isFinite(b)) return null;
    if (Math.abs(a) > 90 || Math.abs(b) > 180) return null;
    return { lat: a, lng: b, swapped: swapped };
  }

  function gotoPlace(lat, lng, label, bbox) {
    if (placeMarker) placeMarker.remove();
    placeMarker = new maplibregl.Marker({ color: '#8a68e6' }).setLngLat([lng, lat]).addTo(map);

    if (bbox && bbox.length === 4 && bbox[0] !== bbox[2]) {
      map.fitBounds([[bbox[0], bbox[1]], [bbox[2], bbox[3]]], { padding: 60, duration: 900 });
    } else {
      map.flyTo({ center: [lng, lat], zoom: Math.max(map.getZoom(), 15), duration: 900 });
    }
    readout(label || 'Location', lat.toFixed(6) + ', ' + lng.toFixed(6),
            'Search again, or Clear to remove the marker.');
  }

  function showPlaceRow(text, meta, onPick) {
    var row = el('div', 'res');
    row.appendChild(el('div', 'n', text));
    if (meta) row.appendChild(el('div', 'm', meta));
    if (onPick) row.addEventListener('click', function () {
      pResBox.classList.remove('on');
      onPick();
    });
    pResBox.appendChild(row);
    return row;
  }

  function runPlaceSearch() {
    var raw = pBox.value.trim();
    pResBox.textContent = '';
    if (!raw) { pResBox.classList.remove('on'); return; }

    var coords = parseCoords(raw);
    if (coords) {
      showPlaceRow(coords.lat.toFixed(6) + ', ' + coords.lng.toFixed(6),
                   coords.swapped ? 'read as longitude, latitude — go to this point'
                                  : 'go to this point',
                   function () { gotoPlace(coords.lat, coords.lng, 'Coordinates'); });
      pResBox.classList.add('on');
      return;
    }

    if (raw.length < 3) { pResBox.classList.remove('on'); return; }

    showPlaceRow('Searching…', null, null).className = 'res busy';
    pResBox.classList.add('on');

    var seq = ++geocodeSeq;
    fetch('/api/geocode?q=' + encodeURIComponent(raw), { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (rows) {
        if (seq !== geocodeSeq) return;          // a newer keystroke superseded this one
        pResBox.textContent = '';
        if (!rows.length) {
          showPlaceRow('No place matches "' + raw + '".', null, null);
        } else {
          rows.forEach(function (p) {
            var parts = String(p.name || '').split(',');
            var head = parts.shift().trim();
            showPlaceRow(head, parts.join(',').trim() || p.kind, function () {
              pBox.value = head;
              gotoPlace(p.lat, p.lng, head, p.bbox);
            });
          });
        }
        pResBox.classList.add('on');
      })
      .catch(function () {
        if (seq !== geocodeSeq) return;
        pResBox.textContent = '';
        showPlaceRow('Place search is unavailable.', 'Coordinates still work.', null);
        pResBox.classList.add('on');
      });
  }

  pBox.addEventListener('input', function () {
    if (geocodeTimer) clearTimeout(geocodeTimer);
    // Coordinates resolve locally, so they need no debounce; a place name does,
    // to keep one lookup per pause rather than one per keystroke.
    geocodeTimer = setTimeout(runPlaceSearch, parseCoords(pBox.value) ? 0 : 450);
  });

  pBox.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter') return;
    e.preventDefault();
    if (geocodeTimer) { clearTimeout(geocodeTimer); geocodeTimer = null; }
    var first = pResBox.querySelector('.res:not(.busy)');
    if (first) first.click(); else runPlaceSearch();
  });

  qBox.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter') return;
    e.preventDefault();
    var first = resBox.querySelector('.res');
    if (first) first.click();
  });

  /* ---------------- boot ---------------- */

  map.on('click', function (e) {
    if (mode === 'identify') identify(e);
    else addMeasurePoint(e.lngLat);
  });

  /**
   * Resolves once the style is parsed and layers may be added.
   *
   * <p>Deliberately NOT map's {@code load} event. That one waits for "the first
   * visually complete rendering", which includes the basemap's tiles — so when the
   * external basemap is slow or blocked, as it is on some PWD office networks, it
   * never fires and anything hung off it never runs. {@code styledata} fires as
   * soon as the style itself is ready, which is all adding a source needs.
   */
  var styleReady = new Promise(function (resolve) {
    if (map.isStyleLoaded()) resolve();
    else map.once('styledata', resolve);
  });

  var listed = fetch('/api/drone/published', { credentials: 'same-origin' })
    .then(function (r) { return r.json(); });

  // The panel is filled straight from the API without waiting for the map, so the
  // list of drone data is readable even if the basemap never arrives.
  listed.then(function (rows) {
    datasets = rows || [];
    renderDatasets();
  }).catch(function () {
    var box = document.getElementById('datasets');
    box.textContent = '';
    box.appendChild(el('div', 'empty', 'Drone data could not be loaded.'));
  });

  fetch('/api/roads/index', { credentials: 'same-origin' })
    .then(function (r) { return r.ok ? r.json() : []; })
    .then(function (rows) {
      roadIndex = rows || [];
      roadIndex.forEach(function (r) {
        if (r.Section_La) roadBySection[r.Section_La] = r;
      });
    })
    .catch(function () { /* search degrades to no suggestions, Identify to tile properties */ });

  Promise.all([styleReady, listed]).then(function () {
    addRoadNetwork();
    addMeasureLayers();

    // Everything published is switched on by default — a viewer that opens blank
    // makes the user hunt for data they just published.
    datasets.forEach(showDataset);
    applyOrder();
    renderDatasets();

    var wanted = new URLSearchParams(location.search).get('project');
    var focus = wanted
      ? datasets.filter(function (d) { return String(d.project_id) === String(wanted); })[0]
      : datasets[0];
    if (focus) zoomTo(focus);
  });
})();
