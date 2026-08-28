/*
 * console-user-layers.js — the Data Console's importer for user layers.
 *
 * Two panels in the Import Hub:
 *   ul-import  load a file into a layer that Layer Management already defines
 *   ul-temp    make a temporary layer from a file in one step — name it, pick
 *              the coordinate columns, done
 *
 * Both read the file in the browser (shpjs for a shapefile zip, kml-reader.js
 * for KML/KMZ, JSON.parse for GeoJSON, a quote-aware split for CSV). Where they
 * part company is what happens next.
 *
 *   ul-import  the server proposes how the file's columns map onto the layer's
 *              attributes, the user confirms, and only then are rows written.
 *              The mapping step is the point: the layer's attribute list was
 *              defined by someone else, months ago, and guessing silently is how
 *              an import rejects every row and says nothing.
 *
 *   ul-temp    no mapping step at all. There is nothing to map ONTO — a
 *              temporary layer is created by this same action, so the file's own
 *              header IS the attribute list (see adoptFileColumns), every column
 *              matches itself, and asking the user to confirm that a column
 *              called SPEED_FLOW maps to the attribute called SPEED_FLOW is a
 *              question with one answer. What IS worth asking, and is asked
 *              afterwards while the layer is on screen rather than never, is how
 *              it should look and what a click on it should show.
 */
(function () {
  'use strict';

  var st = {
    mode: null,          // 'import' | 'temp'
    layerId: null,
    layerKey: null,      // the registry key a style is saved under
    layerName: '',
    columns: [],
    rows: [],
    geoms: [],
    hasGeometry: false,
    mapping: null,
    stored: []           // [{key, label}] the attributes the import actually filled
  };

  // The folder a temporary layer files under. Resolved by key (not a hard-coded
  // id) so it survives folder re-seeding or reordering; cached after the first
  // lookup since the tree rarely changes within a session.
  var networkFolderId = null;
  function resolveNetworkFolderId() {
    if (networkFolderId) return Promise.resolve(networkFolderId);
    return api('/api/layers/tree').then(function (d) {
      var f = (d.folders || []).find(function (x) { return x.key === 'network'; });
      if (!f) throw new Error('Road Network folder not found.');
      networkFolderId = f.id;
      return networkFolderId;
    });
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  /**
   * Status line.
   *
   * `.out` is display:none until it carries ok/err — the same contract the
   * console's own show() relies on — so a neutral "working…" message still
   * needs one of the two classes to be visible at all. Progress uses `ok`.
   */
  function out(html, ok) {
    var el = document.getElementById('ulOut');
    if (!el) return;
    el.className = 'out ' + (ok === false ? 'err' : 'ok');
    el.innerHTML = html;
  }

  function api(url, body, method) {
    return fetch(url, {
      method: method || 'GET',
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      credentials: 'same-origin',
      body: body ? JSON.stringify(body) : undefined
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (d) {
        if (!r.ok) throw new Error((d && d.error) || 'Request failed');
        return d;
      });
    });
  }

  /* ------------------------------------------------------------------
     Panels
     ------------------------------------------------------------------ */

  function panelImport() {
    return '' +
      '<div class="ip-title">Import into a layer</div>' +
      '<p class="ip-sub">Load data into a layer defined in ' +
      '<a class="viewer" href="/layers.html">Layer Management</a>. The file\'s columns are ' +
      'matched to the layer\'s attributes before anything is written.</p>' +
      '<div class="ip-field"><label class="ip-label">Target layer</label>' +
      '<select id="ulTarget" onchange="ULC.pickTarget()"><option value="">Loading…</option></select>' +
      '<div id="ulTargetNote" class="hint"></div></div>' +
      '<div id="ulPeriod"></div>' +
      '<div class="ip-field"><label class="ip-label">Data file</label>' +
      '<input type="file" id="ulFile" accept=".zip,.geojson,.json,.csv,.kml,.kmz" onchange="ULC.read(this)"></div>' +
      '<div id="ulStep"></div>' +
      '<div class="out" id="ulOut"></div>' +
      '<p class="hint">Shapefile zip needs <code>.shp</code>, <code>.shx</code>, <code>.dbf</code> ' +
      '(and <code>.prj</code>). KML and KMZ come straight from Google Earth. A CSV carries no ' +
      'geometry, so the layer places it — by lat/long or by section label and chainage, ' +
      'whichever it was defined with.</p>';
  }

  function panelTemp() {
    return '' +
      '<div class="ip-title">Temporary layer from a file</div>' +
      '<p class="ip-sub">Name it, pick a file, and — for a CSV — say which columns hold the ' +
      'coordinates. The layer is created and loaded in one step, with no column mapping: ' +
      'every column in the file is kept under the name the file gives it.</p>' +
      '<div class="ip-field"><label class="ip-label">Layer name</label>' +
      '<input type="text" id="ulTempName" placeholder="e.g. Contractor survey — March"></div>' +
      '<div class="ip-field"><label class="ip-label">Data file</label>' +
      '<input type="file" id="ulFile" accept=".zip,.geojson,.json,.csv,.kml,.kmz" onchange="ULC.read(this)"></div>' +
      '<div class="hint">Shapefile zip, KML, KMZ, GeoJSON or CSV.</div>' +
      '<div id="ulTempGeo"></div>' +
      '<div id="ulStep"></div>' +
      '<div class="out" id="ulOut"></div>' +
      '<p class="hint">A temporary layer is visible only to you and is meant for one-off ' +
      'analysis. It appears in the map viewer under <b>My layers</b>, and can be discarded in ' +
      'one action from Layer Management when you are done. Once it is loaded you can set its ' +
      'colour, its label and what a click on it shows — all of it optional, and all of it ' +
      'changeable later in <a class="viewer" href="/style.html">Style &amp; Label Management</a>.</p>';
  }

  /* ------------------------------------------------------------------
     Target list
     ------------------------------------------------------------------ */

  function loadTargets(preselect) {
    var sel = document.getElementById('ulTarget');
    if (!sel) return;
    api('/api/layer-data/import-targets')
      .then(function (d) {
        var live = (d.layers || []);
        if (!live.length) {
          sel.innerHTML = '<option value="">No user layers defined yet</option>';
          document.getElementById('ulTargetNote').innerHTML =
            'Define one in <a href="/layers.html">Layer Management</a> first.';
          return;
        }
        sel.innerHTML = '<option value="">Choose a layer…</option>' + live.map(function (l) {
          return '<option value="' + l.id + '"' + (l.frozen ? ' disabled' : '') + '>' +
            esc(l.name) + ' — ' + esc(l.folder) +
            (l.temporary ? ' (temporary)' : '') +
            (l.frozen ? ' — frozen' : '') + '</option>';
        }).join('');
        ULC._targets = live;
        // Preselected from the hub: apply it and describe the layer straight
        // away, so the panel opens in the state a chooser would have left it.
        if (preselect) {
          sel.value = String(preselect);
          if (sel.value === String(preselect)) pickTarget();
        }
      })
      .catch(function () {
        sel.innerHTML = '<option value="">Could not load layers</option>';
      });
  }

  /**
   * Explain what the chosen layer expects.
   *
   * Said before the file is picked, because "this layer places rows by section
   * label and chainage" is the difference between choosing the right file and
   * finding out after the upload that every row was rejected.
   */
  function pickTarget() {
    var sel = document.getElementById('ulTarget');
    var note = document.getElementById('ulTargetNote');
    st.layerId = sel.value ? Number(sel.value) : null;
    var l = (ULC._targets || []).filter(function (x) { return x.id === st.layerId; })[0];
    st.layerName = l ? l.name : '';
    if (!l) { note.textContent = ''; return; }
    note.innerHTML =
      '<b>' + esc(l.geometryType) + '</b> · ' +
      (l.placement === 'LINEAR_REFERENCE'
        ? 'placed by section label + chainage'
        : l.placement === 'LATLNG' ? 'placed by latitude / longitude'
        : 'geometry comes from the file') +
      (l.periodScoped ? ' · stored per survey period' : '') +
      (l.hidden ? ' · <i>currently hidden from the map</i>' : '') +
      /* Asked for before the file is chosen, not after the import runs: a
         layer that arrives on the map in the generic fallback colour is the
         moment nobody comes back to fix it. Choosing colours, an outline and
         a label needs no data on disk yet — a single colour, a category list
         typed by hand, or a numeric range all stand on their own — so nothing
         about styling first is blocked on the import that follows it. Not a
         hard gate: the import still runs if this is skipped. */
      (l.styled ? '' : ' · <a href="/style.html" target="_blank" style="color:var(--amber,#ffa630)">' +
        'Not styled yet — set its colour and label &rarr;</a>');
    renderPeriod(l);

    /* Either order has to work. The mapping needs BOTH a target layer and a
       file, and whichever the user supplies second is the one that should
       trigger it — without this, picking the file first left the panel stuck on
       "choose a target layer" even after they chose one. */
    if (st.rows.length) preview();
  }

  /**
   * The survey-period picker, shown only for a layer that was defined as
   * period-scoped.
   *
   * Asking for a period on a standing inventory would be a meaningless
   * question, which is why the layer decides rather than the import screen.
   */
  function renderPeriod(l) {
    var host = document.getElementById('ulPeriod');
    if (!host) return;
    if (!l || !l.periodScoped) { host.innerHTML = ''; st.periodNeeded = false; return; }
    st.periodNeeded = true;
    host.innerHTML = '<div class="ip-field"><label class="ip-label">Survey period</label>' +
      '<select id="ulPeriodSel"><option value="">Loading…</option></select>' +
      '<div class="hint">Every import is stored against this period, so a new cycle never ' +
      'overwrites the last. <b>Replace</b> clears only this period.</div></div>';
    // /api/survey-periods returns a bare array, newest first, with is_active.
    fetch('/api/survey-periods', { credentials: 'same-origin' })
      .then(function (r) { return r.json(); })
      .then(function (list) {
        var sel = document.getElementById('ulPeriodSel');
        if (!sel) return;
        sel.innerHTML = (list || []).map(function (p) {
          return '<option value="' + p.id + '"' + (p.is_active ? ' selected' : '') + '>' +
            esc(p.name) + (p.is_active ? ' (active)' : '') + '</option>';
        }).join('') || '<option value="">No periods defined</option>';
      })
      .catch(function () {
        var sel = document.getElementById('ulPeriodSel');
        if (sel) sel.innerHTML = '<option value="">Could not load periods</option>';
      });
  }

  /* ------------------------------------------------------------------
     Reading the file
     ------------------------------------------------------------------ */

  function read(input) {
    var file = input.files && input.files[0];
    if (!file) return;
    var name = file.name.toLowerCase();
    st.columns = []; st.rows = []; st.geoms = []; st.hasGeometry = false; st.mapping = null;
    st.geomKind = null; st.geomCounts = null;
    document.getElementById('ulStep').innerHTML = '';
    out('Reading ' + esc(file.name) + '…');

    if (name.endsWith('.zip')) {
      if (typeof shp === 'undefined') {
        return out('Shapefile support is unavailable here — upload a GeoJSON instead.', false);
      }
      file.arrayBuffer()
        .then(function (b) { return shp(b); })
        .then(function (gj) { fromGeoJson(flatten(gj)); })
        .catch(function (e) { out('Could not read the shapefile: ' + esc(e.message || e), false); });
    } else if (name.endsWith('.kml') || name.endsWith('.kmz')) {
      if (typeof KLKml === 'undefined') {
        return out('KML support is unavailable here — upload a GeoJSON instead.', false);
      }
      KLKml.read(file)
        .then(function (gj) { fromGeoJson(gj); })
        .catch(function (e) { out('Could not read the KML: ' + esc(e.message || e), false); });
    } else if (name.endsWith('.geojson') || name.endsWith('.json')) {
      file.text().then(function (t) { fromGeoJson(JSON.parse(t)); })
        .catch(function (e) { out('Could not read the GeoJSON: ' + esc(e.message || e), false); });
    } else if (name.endsWith('.csv')) {
      file.text().then(fromCsv)
        .catch(function (e) { out('Could not read the CSV: ' + esc(e.message || e), false); });
    } else {
      out('Unsupported file type. Use a shapefile (.zip), KML, KMZ, GeoJSON or CSV.', false);
    }
  }

  /** shpjs returns an array when the zip holds several layers. */
  function flatten(gj) {
    if (!Array.isArray(gj)) return gj;
    var feats = [];
    gj.forEach(function (g) { ((g && g.features) || []).forEach(function (f) { feats.push(f); }); });
    return { type: 'FeatureCollection', features: feats };
  }

  function fromGeoJson(gj) {
    var feats = (gj && gj.features) || [];
    if (!feats.length) return out('That file has no features in it.', false);
    // wgs84Bad() lives in index.html's inline script (shared by the road /
    // full-network / boundary importers) — catches a shapefile with no .prj,
    // or one exported in a projected CRS like UTM metres, before it is
    // stored as if it were already lat/lng.
    if (typeof wgs84Bad === 'function' && wgs84Bad(gj)) {
      return out('This file is not in latitude/longitude (WGS84) — re-export as EPSG:4326.', false);
    }
    var cols = [];
    feats.slice(0, 200).forEach(function (f) {
      Object.keys((f && f.properties) || {}).forEach(function (k) {
        if (cols.indexOf(k) < 0) cols.push(k);
      });
    });
    st.columns = cols;
    st.rows = feats.map(function (f) { return (f && f.properties) || {}; });
    st.geoms = feats.map(function (f) { return f && f.geometry ? JSON.stringify(f.geometry) : null; });
    st.hasGeometry = true;
    afterRead(feats.length, geometryKindOf(feats));
  }

  /**
   * The geometry kind to give the layer, and what else is in the file.
   *
   * A layer holds one geometry type, so a file carrying more than one has to
   * pick — and the majority is the useful answer, not whichever feature happens
   * to be first. This matters most for KML: a Google Earth file with a hundred
   * paths and two stray pins would otherwise become a point layer and drop
   * every path on import. The runner-up counts are handed back so the reader
   * can say so rather than let it be discovered afterwards.
   */
  function geometryKindOf(feats) {
    var counts = {};
    feats.forEach(function (f) {
      var g = f && f.geometry;
      if (!g || !g.type) return;
      var t = g.type.toUpperCase();
      if (t === 'GEOMETRYCOLLECTION') return;   // cannot be a layer's own type
      // Counted as the layer type each geometry would need, not as its own
      // name, so "3 LineStrings and 2 MultiLineStrings" is not read as two
      // separate minorities when it is one majority of lines.
      t = normaliseGeom(t);
      counts[t] = (counts[t] || 0) + 1;
    });
    // A MULTILINESTRING layer takes plain LineStrings too (the import wraps
    // them in ST_Multi), so when a file has both, the multi type loads all of
    // them and the plain one would reject half.
    if (counts.LINESTRING && counts.MULTILINESTRING) {
      counts.MULTILINESTRING += counts.LINESTRING;
      delete counts.LINESTRING;
    }
    st.geomCounts = counts;
    var kinds = Object.keys(counts).sort(function (a, b) { return counts[b] - counts[a]; });
    return kinds[0] || 'POINT';
  }

  function fromCsv(text) {
    var lines = text.split(/\r?\n/).filter(function (l) { return l.trim() !== ''; });
    if (lines.length < 2) return out('That CSV has no data rows.', false);
    var head = parseCsvLine(lines[0]);
    var rows = [];
    for (var i = 1; i < lines.length; i++) {
      var cells = parseCsvLine(lines[i]);
      var row = {};
      for (var c = 0; c < head.length; c++) row[head[c]] = cells[c];
      rows.push(row);
    }
    st.columns = head;
    st.rows = rows;
    st.geoms = [];
    st.hasGeometry = false;
    afterRead(rows.length, null);
  }

  /** Quote-aware split — a value may legitimately contain commas. */
  function parseCsvLine(line) {
    var o = [], cur = '', q = false;
    for (var i = 0; i < line.length; i++) {
      var ch = line[i];
      if (q) {
        if (ch === '"') { if (line[i + 1] === '"') { cur += '"'; i++; } else q = false; }
        else cur += ch;
      } else if (ch === '"') q = true;
      else if (ch === ',') { o.push(cur.trim()); cur = ''; }
      else cur += ch;
    }
    o.push(cur.trim());
    return o;
  }

  function afterRead(n, geomKind) {
    st.geomKind = geomKind;
    out('Read <b>' + n.toLocaleString() + '</b> row' + (n === 1 ? '' : 's') +
        ' · <b>' + st.columns.length + '</b> column' + (st.columns.length === 1 ? '' : 's') + '.' +
        mixedGeomNote(), true);
    if (st.mode === 'temp') renderTempGeo();
    else if (!st.layerId) {
      out('Read <b>' + n.toLocaleString() + '</b> row' + (n === 1 ? '' : 's') +
          '. Now choose the target layer above and the columns will be matched.', true);
    } else {
      preview();
    }
  }

  /**
   * Said out loud when the file mixes geometry kinds — common in KML, where one
   * Google Earth document happily holds pins, paths and shapes together. Only
   * the majority kind can be loaded, and being told that before the import is
   * far better than counting the rows that went missing after it.
   */
  function mixedGeomNote() {
    var counts = st.geomCounts || {};
    var kinds = Object.keys(counts);
    if (kinds.length < 2) return '';
    var keep = normaliseGeom(st.geomKind);
    var dropped = kinds.reduce(function (sum, k) {
      return k === keep ? sum : sum + counts[k];
    }, 0);
    return ' This file mixes geometry kinds (' + esc(kinds.join(', ').toLowerCase()) +
      '); a layer holds one, so <b>' + dropped.toLocaleString() + '</b> feature' +
      (dropped === 1 ? '' : 's') + ' that are not <b>' + esc(keep.toLowerCase()) +
      '</b> will not load. Split the file if you need them.';
  }

  /* ------------------------------------------------------------------
     Temporary layer — name + coordinate columns
     ------------------------------------------------------------------ */

  /**
   * For a CSV the user must say which columns hold the coordinates; a shapefile
   * or GeoJSON already carries geometry, so the question is not asked at all.
   * The guesses below are only defaults — a file using "Y"/"X" or "Lat_deg"
   * should not need renaming before it can be loaded.
   */
  function renderTempGeo() {
    var host = document.getElementById('ulTempGeo');
    if (st.hasGeometry) {
      host.innerHTML = '<div class="hint">This file carries its own geometry (' +
        esc(st.geomKind || 'geometry') + '), so no coordinate columns are needed.</div>' +
        '<button class="btn" onclick="ULC.createTemp()">Create layer and import</button>';
      return;
    }
    var opts = function (g) {
      return '<option value="">— choose —</option>' + st.columns.map(function (c) {
        return '<option value="' + esc(c) + '"' + (c === g ? ' selected' : '') + '>' + esc(c) + '</option>';
      }).join('');
    };
    host.innerHTML =
      '<div class="ip-field"><label class="ip-label">Latitude column</label>' +
      '<select id="ulLat">' + opts(guess(['lat', 'latitude', 'y'])) + '</select></div>' +
      '<div class="ip-field"><label class="ip-label">Longitude column</label>' +
      '<select id="ulLng">' + opts(guess(['lng', 'lon', 'long', 'longitude', 'x'])) + '</select></div>' +
      '<button class="btn" onclick="ULC.createTemp()">Create layer and import</button>';
  }

  function guess(names) {
    for (var i = 0; i < st.columns.length; i++) {
      var n = st.columns[i].toLowerCase().replace(/[^a-z0-9]/g, '');
      if (names.indexOf(n) >= 0) return st.columns[i];
    }
    return '';
  }

  /**
   * The attribute type to give each of the file's columns.
   *
   * Decided from the VALUES, not from the header: a temporary layer's columns
   * are whatever someone's file happens to carry, and a name-based guess reads
   * "LENGTH" as numeric and "MT_AADT" as text with equal confidence. Sampling
   * the first 200 rows answers it properly.
   *
   * The type is not cosmetic. Only a numeric attribute can be given a colour
   * ramp or a banded range in Style Management, and only a numeric one is
   * stored as a JSON number rather than a quoted string — so getting it wrong
   * here is what makes a perfectly good column un-styleable later.
   *
   * Dates are deliberately left as text. A file's date format is anybody's
   * guess, KLRAMS accepts exactly one, and a date stored as the string it
   * arrived as is honest; a date silently misread is not.
   */
  function typeOf(col) {
    var seen = 0, ints = 0, nums = 0;
    for (var i = 0; i < st.rows.length && seen < 200; i++) {
      var v = st.rows[i][col];
      if (v == null) continue;
      var s = String(v).trim();
      if (s === '') continue;
      seen++;
      if (typeof v === 'number') { nums++; if (v === Math.floor(v)) ints++; continue; }
      // Same tolerance the importer's coerce() applies, so a column typed here
      // as a number is one whose values it will actually accept as numbers.
      if (!/^[-+]?[0-9,]*\.?[0-9]+(?:[eE][-+]?[0-9]+)?$/.test(s)) continue;
      var n = Number(s.replace(/,/g, ''));
      if (isNaN(n)) continue;
      nums++;
      if (n === Math.floor(n) && s.indexOf('.') < 0) ints++;
    }
    // Every value has to fit. One "N/A" in a column of numbers means the column
    // is text, and typing it numeric would drop that row's value on import.
    if (!seen || nums < seen) return 'STRING';
    return ints === seen ? 'INTEGER' : 'DECIMAL';
  }

  /** The file's header as the layer's attribute list. */
  function fileColumns() {
    return st.columns.map(function (c) { return { name: c, dataType: typeOf(c) }; });
  }

  /**
   * Create the temporary layer, then import into it.
   *
   * Two calls rather than one endpoint doing both: the layer is a real layer
   * with real attributes, so it goes through the same creation path as any
   * other and inherits the same validation. Nothing here is a special case.
   */
  function createTemp() {
    var name = (document.getElementById('ulTempName').value || '').trim();
    if (!name) return out('Give the layer a name.', false);
    if (!st.rows.length) return out('Choose a data file first.', false);

    var lat = null, lng = null;
    if (!st.hasGeometry) {
      lat = (document.getElementById('ulLat') || {}).value;
      lng = (document.getElementById('ulLng') || {}).value;
      if (!lat || !lng) return out('Choose which columns hold the latitude and longitude.', false);
    }

    var geometryType = st.hasGeometry ? normaliseGeom(st.geomKind) : 'POINT';
    out('Creating layer…');

    resolveNetworkFolderId().then(function (folderId) {
      return api('/api/layers', {
        name: name,
        folderId: folderId,
        geometryType: geometryType,
        placement: st.hasGeometry ? 'GEOMETRY' : 'LATLNG',
        uploadFormats: st.hasGeometry ? ['SHAPEFILE', 'GEOJSON'] : ['CSV'],
        attributeMapping: true,
        temporary: true,
        // What removes the mapping step: the layer is created already knowing
        // every column this file carries, so there is nothing left to match.
        fileColumns: fileColumns()
      }, 'POST');
    })
      .then(function (d) {
        st.layerId = d.id;
        st.layerName = d.name;
        st.layerKey = d.key;
        // The layer's own lat/lng attributes are named lat and lng; map the
        // user's chosen columns onto them.
        st.forcedMapping = st.hasGeometry ? {} : { lat: lat, lng: lng };
        return preview();
      })
      .catch(function (e) { out(esc(e.message), false); });
  }

  function normaliseGeom(kind) {
    if (!kind) return 'POINT';
    if (kind.indexOf('POLYGON') >= 0) return 'POLYGON';
    if (kind.indexOf('MULTILINE') >= 0) return 'MULTILINESTRING';
    if (kind.indexOf('LINE') >= 0) return 'LINESTRING';
    return 'POINT';
  }

  /* ------------------------------------------------------------------
     Mapping and import
     ------------------------------------------------------------------ */

  function preview() {
    return api('/api/layer-data/' + st.layerId + '/preview',
        { dataset: 'default', columns: st.columns }, 'POST')
      .then(function (d) {
        st.mapping = d;
        if (st.mode === 'temp') return autoImport();
        renderMapping();
      })
      .catch(function (e) { out(esc(e.message), false); });
  }

  /**
   * The temporary layer's import: no mapping table, no confirmation.
   *
   * Both of those exist to protect a layer that already holds data — the wrong
   * target picked, "Replace everything" left ticked from a previous run. This
   * layer was created by the click that led here and is empty, so there is
   * nothing to protect and nobody else's data to overwrite. The preview is
   * still asked for, because it is the server that says which attribute each
   * column landed on and this has no business deciding that for itself.
   */
  function autoImport() {
    var forced = st.forcedMapping || {};
    var claimed = {};
    Object.keys(forced).forEach(function (k) { if (forced[k]) claimed[forced[k]] = k; });

    var mapping = {}, stored = [], missing = [];
    (st.mapping.mapping || []).forEach(function (m) {
      var col = forced[m.storageKey] || m.fileColumn;
      // A column already stored as a coordinate is not stored a second time
      // under its own name: picking "Y" as the latitude should give the layer a
      // Latitude, not a Latitude and a Y holding the same number.
      if (col && claimed[col] && claimed[col] !== m.storageKey) col = null;
      if (!col) {
        if (m.mandatory) missing.push(m.name);
        return;
      }
      mapping[m.storageKey] = col;
      stored.push({ key: m.storageKey, label: m.name });
    });
    st.stored = stored;

    if (missing.length) {
      return out('Nothing in the file matches <b>' + missing.map(esc).join('</b>, <b>') +
                 '</b>, which this layer needs to place a feature.', false);
    }

    /* Normally empty — the layer was built from this very header. A column can
       still land here if two of them differ only in case or punctuation, since
       one attribute cannot hold both; said out loud rather than dropped
       quietly, because "every column is kept" has to be true or corrected. */
    st.notStored = st.mapping.unmappedFileColumns || [];
    return load(mapping, false, null);
  }

  function renderMapping() {
    var d = st.mapping;
    var opts = function (sel) {
      return '<option value="">— not mapped —</option>' + st.columns.map(function (c) {
        return '<option value="' + esc(c) + '"' + (c === sel ? ' selected' : '') + '>' + esc(c) + '</option>';
      }).join('');
    };

    var html = '<div class="ul-map"><div class="ul-map-h">Match the file\'s columns</div>' +
      '<table class="ul-tbl"><thead><tr><th>Attribute</th><th>Type</th><th>Required</th>' +
      '<th>File column</th><th>Sample</th></tr></thead><tbody>' +
      d.mapping.map(function (m) {
        var pre = (st.forcedMapping || {})[m.storageKey];
        var chosen = pre || m.fileColumn;
        var sample = '';
        if (chosen) {
          for (var i = 0; i < Math.min(st.rows.length, 5); i++) {
            var v = st.rows[i][chosen];
            if (v != null && String(v).trim() !== '') { sample = String(v); break; }
          }
        }
        return '<tr>' +
          '<td><b>' + esc(m.name) + '</b>' + (m.role !== 'NONE' ? ' <span class="ul-pin">places feature</span>' : '') + '</td>' +
          '<td>' + esc(m.dataType.charAt(0) + m.dataType.slice(1).toLowerCase()) + '</td>' +
          '<td>' + (m.mandatory ? '<span class="ul-req">Required</span>' : 'Optional') + '</td>' +
          '<td><select class="ul-sel" data-key="' + esc(m.storageKey) + '">' + opts(chosen) + '</select></td>' +
          '<td class="ul-sample">' + esc(sample) + '</td></tr>';
      }).join('') + '</tbody></table>';

    if (d.unmappedFileColumns.length) {
      html += '<div class="hint"><b>' + d.unmappedFileColumns.length + '</b> column' +
        (d.unmappedFileColumns.length === 1 ? '' : 's') + ' in the file ' +
        (d.unmappedFileColumns.length === 1 ? 'has' : 'have') + ' no matching attribute and ' +
        'will be ignored: ' + d.unmappedFileColumns.map(esc).join(', ') + '.</div>';
    }
    html += '<label class="ul-ck"><input type="checkbox" id="ulReplace"> Replace everything ' +
      'already in this layer</label>' +
      '<button class="btn" id="ulGo" onclick="ULC.requestPublish()">Import ' +
      st.rows.length.toLocaleString() + ' rows</button></div>';

    document.getElementById('ulStep').innerHTML = html;
    document.querySelectorAll('.ul-sel').forEach(function (s) {
      s.addEventListener('change', check);
    });
    check();
  }

  /** A mandatory attribute with no column is the only thing that blocks import. */
  function check() {
    var missing = [];
    st.mapping.mapping.forEach(function (m) {
      if (!m.mandatory) return;
      var sel = document.querySelector('.ul-sel[data-key="' + m.storageKey.replace(/["\\]/g, '\\$&') + '"]');
      if (sel && !sel.value) missing.push(m.name);
    });
    var go = document.getElementById('ulGo');
    if (go) go.disabled = missing.length > 0;
    if (missing.length) out('Still to match: <b>' + missing.map(esc).join('</b>, <b>') + '</b>', false);
    else out('Ready to import.', true);
  }

  /* ---------------------- Confirm before publishing ----------------------
     Import used to run the moment "Import N rows" was clicked. The mapping
     table confirms the file's columns matched; it does not say what is about
     to happen to the LAYER — the wrong target picked, or "Replace everything"
     left ticked from a previous run, was only discoverable after the write.
     This is the same gate the six built-in importers already have (see
     wizShowConfirm in index.html): a summary the user has to agree to before
     anything is written, with the mapping table one Cancel away underneath. */
  function requestPublish() {
    var per = document.getElementById('ulPeriodSel');
    if (st.periodNeeded && per && !per.value) {
      return out('Select the survey period this data belongs to.', false);
    }
    var mapping = {};
    document.querySelectorAll('.ul-sel').forEach(function (s) {
      if (s.value) mapping[s.getAttribute('data-key')] = s.value;
    });
    var replace = (document.getElementById('ulReplace') || {}).checked;
    var periodId = per && per.value ? Number(per.value) : null;
    var periodLabel = per && per.value ? per.options[per.selectedIndex].textContent.trim() : null;

    var d = st.mapping;
    var filled = Object.keys(mapping).length;
    var fileInput = document.getElementById('ulFile');
    var fileName = (fileInput && fileInput.files && fileInput.files[0]) ? fileInput.files[0].name : '';
    var step = document.getElementById('ulStep');
    var savedHtml = step.innerHTML;

    var html = '<div class="wiz-confirm"><div class="wc-h">Publish to ' + esc(st.layerName) + '?</div>' +
      '<ul class="wc-list">' +
      '<li><b>' + esc(fileName) + '</b> — ' + st.rows.length.toLocaleString() +
      ' row' + (st.rows.length === 1 ? '' : 's') + '</li>' +
      '<li><b>' + filled + '</b> of ' + d.mapping.length + ' attributes will receive data</li>' +
      (d.unmappedFileColumns.length ? ('<li>' + d.unmappedFileColumns.length + ' file column' +
        (d.unmappedFileColumns.length === 1 ? '' : 's') + ' not in the attribute list — <b>not stored</b>: ' +
        d.unmappedFileColumns.map(esc).join(', ') + '</li>') : '') +
      (periodLabel ? ('<li>Survey period: <b>' + esc(periodLabel) + '</b></li>') : '') +
      (replace
        ? '<li class="wc-warn">Everything already stored in this layer is replaced by this file.</li>'
        : '<li>Rows are added by their key; nothing already in the layer is removed.</li>') +
      '</ul>' +
      '<div class="wc-act">' +
      '<button class="btn sm" type="button" id="ulConfirmGo">Confirm &amp; publish</button>' +
      '<button class="btn sm ghost" type="button" id="ulConfirmCancel">Cancel</button>' +
      '</div></div>';

    step.innerHTML = html;
    document.getElementById('ulConfirmGo').onclick = function () { load(mapping, replace, periodId); };
    document.getElementById('ulConfirmCancel').onclick = function () {
      step.innerHTML = savedHtml;
      document.querySelectorAll('.ul-sel').forEach(function (s) { s.addEventListener('change', check); });
      check();
    };
  }

  function load(mapping, replace, periodId) {
    var step = document.getElementById('ulStep');
    var go = document.getElementById('ulConfirmGo');
    if (go) go.disabled = true;
    out('Importing ' + st.rows.length.toLocaleString() + ' rows…');

    api('/api/layer-data/' + st.layerId + '/import', {
      dataset: 'default',
      mapping: mapping,
      rows: st.rows,
      geometries: st.geoms,
      periodId: periodId,
      replace: replace
    }, 'POST')
      .then(function (r) {
        var h = '&#10003; Imported <b>' + Number(r.loaded).toLocaleString() + '</b> feature' +
          (r.loaded === 1 ? '' : 's') + ' into ' + esc(r.layer) + '.';
        if (r.skippedRows) {
          h += '<div style="margin-top:6px">' + r.skippedRows.toLocaleString() +
            ' row(s) skipped — a required value was missing or the wrong type.</div>';
        }
        if (r.skippedValues) {
          h += '<div style="margin-top:6px">' + r.skippedValues.toLocaleString() +
            ' optional value(s) did not fit their type and were left empty.</div>';
        }
        if (r.unplaced) {
          h += '<div style="margin-top:6px">' + Number(r.unplaced).toLocaleString() +
            ' feature(s) could not be placed — usually a section label that is not in the ' +
            'road network, or a chainage past the end of the section.</div>';
        }
        if (r.problems && r.problems.length) {
          h += '<div style="margin-top:8px;font-family:var(--mono,monospace);font-size:11px">' +
            r.problems.map(esc).join('<br>') + '</div>';
        }
        if ((st.notStored || []).length) {
          h += '<div style="margin-top:6px">' + st.notStored.length + ' column(s) could not be ' +
            'kept as their own field — another column of this file already has that name once ' +
            'case and punctuation are ignored: ' + st.notStored.map(esc).join(', ') + '.</div>';
        }
        h += '<div style="margin-top:8px">It is on the map under <b>My layers</b>.</div>';
        out(h, true);
        // The temporary layer keeps the panel: appearance is asked for here,
        // once, while the layer is in front of whoever made it.
        if (st.mode === 'temp' && st.layerKey) renderAppearance();
        else step.innerHTML = '';
        if (typeof logUpload === 'function') {
          logUpload('user-layer', st.layerName, true, 'Loaded ' + r.loaded + ' features');
        }
      })
      .catch(function (e) {
        // Absent on the temporary path, which imports without a confirm step.
        if (go) go.disabled = false;
        out(esc(e.message), false);
      });
  }

  /* ------------------------------------------------------------------
     Appearance and popup — the temporary layer's last step
     ------------------------------------------------------------------ */

  /* Deliberately the palette 33-user-layers.js draws user layers in, so the
     swatches offered here are the colours already on the map rather than a
     second set nobody chose. */
  var PALETTE = ['#e0529c', '#4dd4ac', '#f2a03d', '#7c8cf8', '#48c7e8', '#c77dff',
                 '#ef4444', '#22c55e', '#eab308', '#3887be'];

  /**
   * Offer a colour, a label and a popup, on the layer that was just loaded.
   *
   * Offered HERE rather than left to Style Management for one reason: this is
   * the only moment anybody has the file in mind. A temporary layer is made to
   * answer one question, and a layer that arrives in the generic fallback
   * colour with a popup listing all twenty-nine of a shapefile's columns is one
   * nobody comes back to fix — they scroll past the noise instead.
   *
   * Everything on it is optional and everything is changeable afterwards in
   * Style &amp; Label Management, which is why "Not now" is a real answer and
   * not a nag: with no style saved the layer keeps the built-in paint and the
   * popup lists whatever the feature carries, exactly as before.
   */
  function renderAppearance() {
    var step = document.getElementById('ulStep');
    if (!step) return;
    var geom = normaliseGeom(st.geomKind);
    var fields = st.stored || [];

    var opts = function (sel, none) {
      return '<option value="">' + none + '</option>' + fields.map(function (f) {
        return '<option value="' + esc(f.key) + '"' + (f.key === sel ? ' selected' : '') +
               '>' + esc(f.label) + '</option>';
      }).join('');
    };

    var sizeLabel = geom === 'POINT' ? 'Point size'
                  : geom === 'POLYGON' ? 'Fill opacity' : 'Line width';
    var sizeAttrs = geom === 'POINT' ? 'min="1" max="20" step="0.5" value="5"'
                  : geom === 'POLYGON' ? 'min="0.05" max="1" step="0.05" value="0.3"'
                  : 'min="0.5" max="16" step="0.5" value="3"';

    step.innerHTML =
      '<div class="ul-map"><div class="ul-map-h">Appearance and popup <span class="ul-opt">optional</span></div>' +

      '<div class="ip-field"><label class="ip-label">Colour</label>' +
      '<div class="ul-sw">' + PALETTE.map(function (c, i) {
        return '<button type="button" class="ul-swatch' + (i === 0 ? ' on' : '') +
               '" data-color="' + c + '" style="background:' + c + '" title="' + c + '"></button>';
      }).join('') +
      '<input type="color" id="ulStColor" value="' + PALETTE[0] + '" title="Any other colour">' +
      '</div></div>' +

      '<div class="ip-field"><label class="ip-label">' + sizeLabel + '</label>' +
      '<input type="number" id="ulStSize" ' + sizeAttrs + '></div>' +

      '<div class="ip-field"><label class="ip-label">Label features with</label>' +
      '<select id="ulStLabel">' + opts(null, '— no label —') + '</select>' +
      '<div class="hint" style="margin-top:6px">Written beside each feature on the map.</div></div>' +

      '<div class="ip-field"><label class="ip-label">On click, show</label>' +
      '<select id="ulStPopup">' +
        '<option value="ALL">Every column in the file</option>' +
        '<option value="FIELDS">Only the columns I choose</option>' +
        '<option value="NONE">Nothing — no popup</option>' +
      '</select></div>' +

      '<div class="ip-field" id="ulStFieldsBox" style="display:none">' +
      '<label class="ip-label">Columns in the popup</label>' +
      '<div class="ul-fields">' + fields.map(function (f) {
        return '<label class="ul-ck"><input type="checkbox" class="ul-pf" value="' +
               esc(f.key) + '"> ' + esc(f.label) + '</label>';
      }).join('') + '</div>' +
      '<div class="hint" style="margin-top:6px">Shown in the order they appear here.</div></div>' +

      '<div class="ip-field"><label class="ip-label">Popup heading</label>' +
      '<select id="ulStTitle">' + opts(null, 'The layer name') + '</select></div>' +

      '<div class="wc-act">' +
      '<button class="btn sm" type="button" id="ulStSave">Save appearance</button>' +
      '<button class="btn sm ghost" type="button" id="ulStSkip">Not now</button>' +
      '</div></div>';

    step.querySelectorAll('.ul-swatch').forEach(function (b) {
      b.addEventListener('click', function () {
        step.querySelectorAll('.ul-swatch').forEach(function (x) { x.classList.remove('on'); });
        b.classList.add('on');
        document.getElementById('ulStColor').value = b.getAttribute('data-color');
      });
    });
    // A colour typed into the picker is nobody's swatch any more, so the
    // selected one is cleared rather than left claiming a colour it is not.
    document.getElementById('ulStColor').addEventListener('input', function () {
      step.querySelectorAll('.ul-swatch').forEach(function (x) { x.classList.remove('on'); });
    });
    document.getElementById('ulStPopup').addEventListener('change', function (e) {
      document.getElementById('ulStFieldsBox').style.display =
        e.target.value === 'FIELDS' ? '' : 'none';
    });
    document.getElementById('ulStSave').onclick = saveAppearance;
    document.getElementById('ulStSkip').onclick = function () { step.innerHTML = ''; };
  }

  /**
   * Save the style, keyed by the layer's registry key.
   *
   * A partial document on purpose — the server's clean() fills and clamps every
   * field this does not mention, so the four things asked for here cannot leave
   * a style half-specified. Failing to save is reported and nothing more: the
   * data is already in and the layer is already on the map, so a style that did
   * not take is a cosmetic loss, not a failed import.
   */
  function saveAppearance() {
    var geom = normaliseGeom(st.geomKind);
    var size = Number((document.getElementById('ulStSize') || {}).value);
    var label = (document.getElementById('ulStLabel') || {}).value || null;
    var mode = (document.getElementById('ulStPopup') || {}).value || 'ALL';
    var title = (document.getElementById('ulStTitle') || {}).value || null;

    var fields = [];
    document.querySelectorAll('.ul-pf').forEach(function (c) {
      if (c.checked) fields.push(c.value);
    });
    if (mode === 'FIELDS' && !fields.length) {
      return out('Tick the columns the popup should show, or choose another option.', false);
    }

    var style = {
      color: { mode: 'SINGLE', value: document.getElementById('ulStColor').value },
      label: { on: !!label, attribute: label },
      popup: { mode: mode, title: title, fields: fields }
    };
    if (isNaN(size)) { /* left blank — clean() applies the default for the kind */ }
    else if (geom === 'POINT') style.point = { radius: size };
    else if (geom === 'POLYGON') style.fill = { opacity: size };
    else style.line = { width: size };

    var btn = document.getElementById('ulStSave');
    if (btn) btn.disabled = true;
    out('Saving appearance…');

    api('/api/layer-styles/' + encodeURIComponent(st.layerKey), { style: style }, 'PUT')
      .then(function () {
        document.getElementById('ulStep').innerHTML = '';
        out('&#10003; <b>' + esc(st.layerName) + '</b> is loaded and styled. Open the map and ' +
            'switch it on under <b>My layers</b>.', true);
      })
      .catch(function (e) {
        if (btn) btn.disabled = false;
        out('The data is loaded, but the appearance could not be saved: ' + esc(e.message) +
            ' You can set it in <a class="viewer" href="/style.html">Style &amp; Label ' +
            'Management</a>.', false);
      });
  }

  /* ------------------------------------------------------------------
     Hook into the hub
     ------------------------------------------------------------------ */

  /**
   * Open the import panel.
   *
   * @param typeId  'ul-import' or 'ul-temp'
   * @param layerId optional — preselect this layer and skip the chooser.
   *                The Data Console now lists every user layer as its own
   *                import entry, so by the time this runs the layer HAS been
   *                chosen; asking again on the panel would be asking twice.
   */
  function show(typeId, layerId) {
    st.mode = (typeId === 'ul-temp') ? 'temp' : 'import';
    st.layerId = null; st.layerKey = null; st.forcedMapping = null;
    st.columns = []; st.rows = []; st.geoms = []; st.stored = [];
    var body = document.getElementById('paramBody');
    body.innerHTML = (st.mode === 'temp') ? panelTemp() : panelImport();
    if (st.mode === 'import') loadTargets(layerId);
  }

  var ULC = {
    show: show, read: read, pickTarget: pickTarget,
    createTemp: createTemp, requestPublish: requestPublish, _targets: []
  };
  window.ULC = ULC;
})();
