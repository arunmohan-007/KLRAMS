/*
 * console-user-layers.js — the Data Console's importer for user layers.
 *
 * Two panels in the Import Hub:
 *   ul-import  load a file into a layer that Layer Management already defines
 *   ul-temp    make a temporary layer from a file in one step — name it, pick
 *              the coordinate columns, done
 *
 * Both end in the same place: the file is read in the browser (shpjs for a
 * shapefile zip, JSON.parse for GeoJSON, a quote-aware split for CSV), the
 * server proposes how the file's columns map onto the layer's attributes, the
 * user confirms, and only then are rows written. The mapping step is the point:
 * guessing silently is how an import rejects every row and says nothing.
 */
(function () {
  'use strict';

  var st = {
    mode: null,          // 'import' | 'temp'
    layerId: null,
    layerName: '',
    columns: [],
    rows: [],
    geoms: [],
    hasGeometry: false,
    mapping: null
  };

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
      '<input type="file" id="ulFile" accept=".zip,.geojson,.json,.csv" onchange="ULC.read(this)"></div>' +
      '<div id="ulStep"></div>' +
      '<div class="out" id="ulOut"></div>' +
      '<p class="hint">Shapefile zip needs <code>.shp</code>, <code>.shx</code>, <code>.dbf</code> ' +
      '(and <code>.prj</code>). A CSV carries no geometry, so the layer places it — by ' +
      'lat/long or by section label and chainage, whichever it was defined with.</p>';
  }

  function panelTemp() {
    return '' +
      '<div class="ip-title">Temporary layer from a file</div>' +
      '<p class="ip-sub">Name it, pick a file, and — for a CSV — say which columns hold the ' +
      'coordinates. The layer is created and loaded in one step.</p>' +
      '<div class="ip-field"><label class="ip-label">Layer name</label>' +
      '<input type="text" id="ulTempName" placeholder="e.g. Contractor survey — March"></div>' +
      '<div class="ip-field"><label class="ip-label">Data file</label>' +
      '<input type="file" id="ulFile" accept=".zip,.geojson,.json,.csv" onchange="ULC.read(this)"></div>' +
      '<div id="ulTempGeo"></div>' +
      '<div id="ulStep"></div>' +
      '<div class="out" id="ulOut"></div>' +
      '<p class="hint">A temporary layer is visible only to you and is meant for one-off ' +
      'analysis. It appears in the map viewer under <b>My layers</b>, and can be discarded in ' +
      'one action from Layer Management when you are done.</p>';
  }

  /* ------------------------------------------------------------------
     Target list
     ------------------------------------------------------------------ */

  function loadTargets() {
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
      (l.hidden ? ' · <i>currently hidden from the map</i>' : '');
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
    } else if (name.endsWith('.geojson') || name.endsWith('.json')) {
      file.text().then(function (t) { fromGeoJson(JSON.parse(t)); })
        .catch(function (e) { out('Could not read the GeoJSON: ' + esc(e.message || e), false); });
    } else if (name.endsWith('.csv')) {
      file.text().then(fromCsv)
        .catch(function (e) { out('Could not read the CSV: ' + esc(e.message || e), false); });
    } else {
      out('Unsupported file type. Use a shapefile (.zip), GeoJSON or CSV.', false);
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

  function geometryKindOf(feats) {
    for (var i = 0; i < feats.length; i++) {
      var g = feats[i] && feats[i].geometry;
      if (g && g.type) return g.type.toUpperCase();
    }
    return 'POINT';
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
        ' · <b>' + st.columns.length + '</b> column' + (st.columns.length === 1 ? '' : 's') + '.', true);
    if (st.mode === 'temp') renderTempGeo();
    else if (!st.layerId) {
      out('Read <b>' + n.toLocaleString() + '</b> row' + (n === 1 ? '' : 's') +
          '. Now choose the target layer above and the columns will be matched.', true);
    } else {
      preview();
    }
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

    api('/api/layers', {
      name: name,
      folderId: 1,
      geometryType: geometryType,
      placement: st.hasGeometry ? 'GEOMETRY' : 'LATLNG',
      uploadFormats: st.hasGeometry ? ['SHAPEFILE', 'GEOJSON'] : ['CSV'],
      attributeMapping: true,
      temporary: true
    }, 'POST')
      .then(function (d) {
        st.layerId = d.id;
        st.layerName = d.name;
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
        renderMapping();
      })
      .catch(function (e) { out(esc(e.message), false); });
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
      '<button class="btn" id="ulGo" onclick="ULC.load()">Import ' +
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

  function load() {
    var mapping = {};
    document.querySelectorAll('.ul-sel').forEach(function (s) {
      if (s.value) mapping[s.getAttribute('data-key')] = s.value;
    });
    var go = document.getElementById('ulGo');
    go.disabled = true;
    out('Importing ' + st.rows.length.toLocaleString() + ' rows…');

    var per = document.getElementById('ulPeriodSel');
    if (st.periodNeeded && per && !per.value) {
      go.disabled = false;
      return out('Select the survey period this data belongs to.', false);
    }

    api('/api/layer-data/' + st.layerId + '/import', {
      dataset: 'default',
      mapping: mapping,
      rows: st.rows,
      geometries: st.geoms,
      periodId: per && per.value ? Number(per.value) : null,
      replace: (document.getElementById('ulReplace') || {}).checked
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
        h += '<div style="margin-top:8px">It is on the map under <b>My layers</b>.</div>';
        out(h, true);
        document.getElementById('ulStep').innerHTML = '';
        if (typeof logUpload === 'function') {
          logUpload('user-layer', st.layerName, true, 'Loaded ' + r.loaded + ' features');
        }
      })
      .catch(function (e) {
        go.disabled = false;
        out(esc(e.message), false);
      });
  }

  /* ------------------------------------------------------------------
     Hook into the hub
     ------------------------------------------------------------------ */

  function show(typeId) {
    st.mode = (typeId === 'ul-temp') ? 'temp' : 'import';
    st.layerId = null; st.forcedMapping = null;
    st.columns = []; st.rows = []; st.geoms = [];
    var body = document.getElementById('paramBody');
    body.innerHTML = (st.mode === 'temp') ? panelTemp() : panelImport();
    if (st.mode === 'import') loadTargets();
  }

  var ULC = {
    show: show, read: read, pickTarget: pickTarget,
    createTemp: createTemp, load: load, _targets: []
  };
  window.ULC = ULC;
})();
