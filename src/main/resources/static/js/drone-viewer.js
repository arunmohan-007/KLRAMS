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

  function showDataset(d) {
    if (visible[d.id]) return;
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
  }

  function roadsOnTop() { return document.getElementById('roads-top').checked; }

  /** Re-stack every visible raster in panel order, then put the roads where asked. */
  function applyOrder() {
    datasets.forEach(function (d) {
      if (visible[d.id] && map.getLayer(layerId(d))) map.moveLayer(layerId(d));
    });
    if (map.getLayer(ROAD_CASING)) {
      if (roadsOnTop()) { map.moveLayer(ROAD_CASING); map.moveLayer(ROAD_LAYER); }
      else {
        var first = datasets.filter(function (d) { return visible[d.id]; })[0];
        if (first) { map.moveLayer(ROAD_CASING, layerId(first)); map.moveLayer(ROAD_LAYER, layerId(first)); }
      }
    }
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
      names.appendChild(el('div', 'ds-k',
        (d.dataset_type === 'DEM' ? 'DEM' : 'Orthomosaic') + ' · ' + d.project_code));
      head.appendChild(names);
      card.appendChild(head);

      var op = el('div', 'op');
      op.appendChild(el('label', null, 'Opacity'));
      var slider = el('input');
      slider.type = 'range'; slider.min = '0'; slider.max = '100'; slider.value = '100';
      var pct = el('span', 'pct', '100%');
      slider.addEventListener('input', function () {
        pct.textContent = slider.value + '%';
        if (map.getLayer(layerId(d)))
          map.setPaintProperty(layerId(d), 'raster-opacity', Number(slider.value) / 100);
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

      if (d.dataset_type === 'DEM') card.appendChild(elevationRamp(d));

      box.appendChild(card);
    });
  }

  function buildInfo(info, d) {
    var degrees = d.epsg === 4326;
    var rows = [
      ['Project', d.project_code + ' — ' + d.project_name],
      ['Road / Location', [d.road_section, d.location].filter(Boolean).join(' · ') || '—'],
      ['PWD section', d.pwd_section || '—'],
      ['Survey date', fmtDate(d.survey_date)],
      ['Coordinate system', d.crs_name],
      ['Raster size', d.raster_width + ' × ' + d.raster_height + ' px'],
      ['Resolution', num(d.res_x, degrees ? 8 : 3) + (degrees ? '°' : ' m') + ' per pixel'],
      ['File size', fmtBytes(d.file_size)],
      ['Tile zooms', d.min_zoom + ' – ' + d.max_zoom],
      ['Extent', num(d.min_x, 5) + ', ' + num(d.min_y, 5) + ' → ' + num(d.max_x, 5) + ', ' + num(d.max_y, 5)]
    ];
    if (d.dataset_type === 'DEM')
      rows.splice(8, 0, ['Elevation', num(d.elevation_min, 2) + ' – ' + num(d.elevation_max, 2) + ' m']);

    rows.forEach(function (r) {
      var row = el('div', 'r');
      row.appendChild(el('div', 'k', r[0]));
      row.appendChild(el('div', 'v', r[1] == null ? '—' : String(r[1])));
      info.appendChild(row);
    });
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
