/*
 * layer-import.js — the Data Import module for user and temporary layers.
 *
 * Three steps, in this order and no other:
 *   1. read the file    — shapefile (.zip) and GeoJSON are parsed to features,
 *                         CSV to rows; both end up as {columns, rows, geoms}
 *   2. confirm mapping  — the server SUGGESTS a column-to-attribute mapping and
 *                         the user corrects it; nothing is written until they do
 *   3. load             — rows go in, and the result reports what was skipped
 *
 * The mapping step is the reason this module exists. The asset importer guesses
 * from a fixed alias list and silently rejects every row when a file spells a
 * column differently; here the guess is only ever a default the user can see and
 * change before anything is stored.
 */
(function () {
  'use strict';

  var st = {
    layerId: null, layerName: '', dataset: 'default',
    columns: [], rows: [], geoms: [],
    mapping: null, placement: null, geometryType: null
  };

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function msg(text, ok) {
    var el = document.getElementById('impMsg');
    if (!el) return;
    el.className = 'msg ' + (ok ? 'ok' : 'err');
    el.innerHTML = text;
    if (ok) setTimeout(function () { el.className = 'msg'; }, 6000);
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
     Open
     ------------------------------------------------------------------ */

  function open(layerId, layerName) {
    st.layerId = layerId;
    st.layerName = layerName || '';
    st.columns = []; st.rows = []; st.geoms = []; st.mapping = null;
    document.getElementById('impVeil').classList.add('on');
    document.getElementById('impTitle').textContent = 'Import data — ' + st.layerName;
    step1();
  }

  function close() {
    document.getElementById('impVeil').classList.remove('on');
  }

  function step1() {
    document.getElementById('impStep').textContent = 'Step 1 of 3 · Choose a file';
    document.getElementById('impBody').innerHTML =
      '<div class="drop" id="impDrop">' +
        '<div class="drop-ic">⬆</div>' +
        '<div class="drop-t">Drop a file here, or choose one</div>' +
        '<div class="drop-d">Shapefile (.zip) · GeoJSON (.geojson, .json) · CSV (.csv)</div>' +
        '<input type="file" id="impFile" accept=".zip,.geojson,.json,.csv" style="display:none">' +
        '<button class="btn" onclick="document.getElementById(\'impFile\').click()">Choose file</button>' +
      '</div>' +
      '<div class="hint">A shapefile or GeoJSON brings its own geometry. A CSV is placed from its ' +
      'columns — by latitude/longitude or by section label and chainage, whichever this layer uses.</div>';

    var input = document.getElementById('impFile');
    input.addEventListener('change', function () { readFile(input.files[0]); });

    var drop = document.getElementById('impDrop');
    ['dragenter', 'dragover'].forEach(function (ev) {
      drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.add('over'); });
    });
    ['dragleave', 'drop'].forEach(function (ev) {
      drop.addEventListener(ev, function (e) { e.preventDefault(); drop.classList.remove('over'); });
    });
    drop.addEventListener('drop', function (e) {
      if (e.dataTransfer.files && e.dataTransfer.files[0]) readFile(e.dataTransfer.files[0]);
    });
  }

  /* ------------------------------------------------------------------
     Step 1 — read the file
     ------------------------------------------------------------------ */

  function readFile(file) {
    if (!file) return;
    var name = file.name.toLowerCase();
    msg('Reading ' + esc(file.name) + '…', true);

    if (name.endsWith('.zip')) {
      if (typeof shp === 'undefined') {
        return msg('Shapefile support is unavailable on this page — upload a GeoJSON instead.');
      }
      file.arrayBuffer()
        .then(function (buf) { return shp(buf); })
        .then(function (gj) { fromGeoJson(flatten(gj)); })
        .catch(function (e) { msg('Could not read the shapefile: ' + esc(e.message || e)); });

    } else if (name.endsWith('.geojson') || name.endsWith('.json')) {
      file.text()
        .then(function (t) { fromGeoJson(JSON.parse(t)); })
        .catch(function (e) { msg('Could not read the GeoJSON: ' + esc(e.message || e)); });

    } else if (name.endsWith('.csv')) {
      file.text()
        .then(function (t) { fromCsv(t); })
        .catch(function (e) { msg('Could not read the CSV: ' + esc(e.message || e)); });

    } else {
      msg('Unsupported file type. Use a shapefile (.zip), GeoJSON or CSV.');
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
    if (!feats.length) return msg('That file has no features in it.');

    var cols = [];
    feats.slice(0, 200).forEach(function (f) {
      Object.keys((f && f.properties) || {}).forEach(function (k) {
        if (cols.indexOf(k) < 0) cols.push(k);
      });
    });

    st.columns = cols;
    st.rows = feats.map(function (f) { return (f && f.properties) || {}; });
    st.geoms = feats.map(function (f) {
      return f && f.geometry ? JSON.stringify(f.geometry) : null;
    });
    step2();
  }

  function fromCsv(text) {
    var lines = text.split(/\r?\n/).filter(function (l) { return l.trim() !== ''; });
    if (lines.length < 2) return msg('That CSV has no data rows.');

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
    step2();
  }

  /** Quote-aware split — a value may legitimately contain commas. */
  function parseCsvLine(line) {
    var out = [], cur = '', q = false;
    for (var i = 0; i < line.length; i++) {
      var ch = line[i];
      if (q) {
        if (ch === '"') {
          if (line[i + 1] === '"') { cur += '"'; i++; } else q = false;
        } else cur += ch;
      } else if (ch === '"') q = true;
      else if (ch === ',') { out.push(cur.trim()); cur = ''; }
      else cur += ch;
    }
    out.push(cur.trim());
    return out;
  }

  /* ------------------------------------------------------------------
     Step 2 — confirm the mapping
     ------------------------------------------------------------------ */

  function step2() {
    api('/api/layer-data/' + st.layerId + '/preview',
        { dataset: st.dataset, columns: st.columns }, 'POST')
      .then(function (d) {
        st.mapping = d;
        renderMapping();
      })
      .catch(function (e) { msg(e.message); });
  }

  function renderMapping() {
    var d = st.mapping;
    document.getElementById('impStep').textContent =
      'Step 2 of 3 · Match columns · ' + st.rows.length.toLocaleString() + ' rows read';

    var opts = function (sel) {
      return '<option value="">— not mapped —</option>' + st.columns.map(function (c) {
        return '<option value="' + esc(c) + '"' + (c === sel ? ' selected' : '') + '>' + esc(c) + '</option>';
      }).join('');
    };

    var html = '<div class="tbl-wrap"><table class="atbl"><thead><tr>' +
      '<th>System attribute</th><th>Type</th><th>Required</th><th>File column</th><th>Sample</th>' +
      '</tr></thead><tbody>' +
      d.mapping.map(function (m) {
        var sample = '';
        if (m.fileColumn) {
          for (var i = 0; i < Math.min(st.rows.length, 5); i++) {
            var v = st.rows[i][m.fileColumn];
            if (v != null && String(v).trim() !== '') { sample = String(v); break; }
          }
        }
        return '<tr>' +
          '<td class="an">' + (m.role !== 'NONE' ? '<span class="pin">◆</span> ' : '') + esc(m.name) +
            '<span class="sk">' + esc(m.storageKey) + '</span></td>' +
          '<td>' + esc(m.dataType.charAt(0) + m.dataType.slice(1).toLowerCase()) + '</td>' +
          '<td>' + (m.mandatory ? '<span class="chip mn">Required</span>' : '<span class="opt">Optional</span>') + '</td>' +
          '<td><select class="mapsel' + (m.mandatory && !m.fileColumn ? ' bad' : '') +
            '" data-key="' + esc(m.storageKey) + '">' + opts(m.fileColumn) + '</select></td>' +
          '<td class="sample">' + esc(sample) + '</td>' +
        '</tr>';
      }).join('') +
      '</tbody></table></div>';

    if (d.unmappedFileColumns.length) {
      html += '<div class="hint"><b>' + d.unmappedFileColumns.length + '</b> column' +
        (d.unmappedFileColumns.length === 1 ? '' : 's') + ' in the file ' +
        (d.unmappedFileColumns.length === 1 ? 'has' : 'have') + ' no matching attribute and will be ignored: ' +
        d.unmappedFileColumns.map(esc).join(', ') +
        '. Add them in the Attribute Data module first if you need them kept.</div>';
    }

    html += '<label class="ck" style="margin-top:14px"><input type="checkbox" id="impReplace"> ' +
      'Replace everything already in this layer</label>';

    document.getElementById('impBody').innerHTML = html;
    document.getElementById('impFoot').innerHTML =
      '<button class="btn ghost" onclick="LI.back()">Back</button>' +
      '<div style="display:flex;gap:10px">' +
        '<button class="btn ghost" onclick="LI.close()">Cancel</button>' +
        '<button class="btn" id="impGo" onclick="LI.load()">Import</button>' +
      '</div>';

    document.querySelectorAll('.mapsel').forEach(function (s) {
      s.addEventListener('change', function () {
        s.classList.remove('bad');
        checkReady();
      });
    });
    checkReady();
  }

  /**
   * A mandatory attribute with no column is the one thing that must block the
   * import — an optional one may be left unmapped, and its values are simply
   * not stored.
   */
  function checkReady() {
    var missing = [];
    st.mapping.mapping.forEach(function (m) {
      if (!m.mandatory) return;
      var sel = document.querySelector('.mapsel[data-key="' + cssEsc(m.storageKey) + '"]');
      if (sel && !sel.value) missing.push(m.name);
    });
    var go = document.getElementById('impGo');
    if (go) {
      go.disabled = missing.length > 0;
      go.title = missing.length ? 'Still to match: ' + missing.join(', ') : '';
    }
    if (missing.length) {
      msg('Match a column for: <b>' + missing.map(esc).join('</b>, <b>') + '</b>');
    } else {
      var el = document.getElementById('impMsg');
      if (el) el.className = 'msg';
    }
  }

  function cssEsc(s) { return String(s).replace(/["\\]/g, '\\$&'); }

  function back() { step1(); }

  /* ------------------------------------------------------------------
     Step 3 — load
     ------------------------------------------------------------------ */

  function load() {
    var mapping = {};
    document.querySelectorAll('.mapsel').forEach(function (s) {
      if (s.value) mapping[s.getAttribute('data-key')] = s.value;
    });

    var btn = document.getElementById('impGo');
    btn.disabled = true;
    btn.textContent = 'Importing…';
    msg('Importing ' + st.rows.length.toLocaleString() + ' rows…', true);

    api('/api/layer-data/' + st.layerId + '/import', {
      dataset: st.dataset,
      mapping: mapping,
      rows: st.rows,
      geometries: st.geoms,
      replace: document.getElementById('impReplace').checked
    }, 'POST')
      .then(function (r) { done(r); })
      .catch(function (e) {
        btn.disabled = false;
        btn.textContent = 'Import';
        msg(e.message);
      });
  }

  function done(r) {
    document.getElementById('impStep').textContent = 'Step 3 of 3 · Done';

    var warn = '';
    if (r.skippedRows) {
      warn += '<div class="res-w"><b>' + r.skippedRows.toLocaleString() + '</b> row' +
        (r.skippedRows === 1 ? '' : 's') + ' skipped — a required value was missing or the wrong type.</div>';
    }
    if (r.skippedValues) {
      warn += '<div class="res-w"><b>' + r.skippedValues.toLocaleString() + '</b> optional value' +
        (r.skippedValues === 1 ? '' : 's') + ' did not fit their attribute type and were left empty.</div>';
    }
    if (r.unplaced) {
      warn += '<div class="res-w"><b>' + Number(r.unplaced).toLocaleString() + '</b> feature' +
        (r.unplaced === 1 ? '' : 's') + ' could not be placed on the map — usually a section label ' +
        'that is not in the road network, or a chainage beyond the section length.</div>';
    }

    document.getElementById('impBody').innerHTML =
      '<div class="res"><div class="res-n">' + Number(r.loaded).toLocaleString() + '</div>' +
      '<div class="res-l">feature' + (r.loaded === 1 ? '' : 's') + ' imported into ' +
      esc(r.layer) + '</div></div>' + warn +
      (r.problems && r.problems.length
        ? '<div class="probs"><div class="probs-t">First problems</div>' +
          r.problems.map(function (p) { return '<div class="prob">' + esc(p) + '</div>'; }).join('') +
          '</div>'
        : '');

    document.getElementById('impFoot').innerHTML =
      '<button class="btn ghost" onclick="LI.open(' + st.layerId + ',' +
        JSON.stringify(st.layerName) + ')">Import another file</button>' +
      '<button class="btn" onclick="LI.finish()">Done</button>';
  }

  /** Close and refresh the layer list, so the new feature count shows at once. */
  function finish() {
    close();
    if (window.LM && typeof window.LM.reload === 'function') window.LM.reload();
  }

  window.LI = {
    open: open, close: close, back: back, load: load, finish: finish
  };
})();
