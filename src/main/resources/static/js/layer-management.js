/*
 * layer-management.js — the Layer Management screen (/layers.html).
 *
 * Renders the server-side layer registry (/api/layers/tree) as the same seven
 * folders the map's Layers panel shows, and drives the create-layer wizard.
 *
 * What is NOT decided here: whether a layer may be edited or deleted. Those
 * arrive on each layer as `editable` / `deletable`, derived server-side from
 * its source type, and the API refuses the call regardless of what this file
 * renders. Hiding a button is a courtesy, not the guard.
 */
(function () {
  'use strict';

  var TREE = [];        // folders, as returned
  var FOLDERS = [];     // flat {id,name} for the wizard's select

  /* ------------------------------------------------------------------
     Labels
     ------------------------------------------------------------------ */

  var PLACEMENT = {
    GEOMETRY: 'From file geometry',
    LATLNG: 'By lat / long',
    LINEAR_REFERENCE: 'By linear reference'
  };

  var GEOMETRY = {
    POINT: 'Point',
    LINESTRING: 'Line',
    MULTILINESTRING: 'Multi-line',
    POLYGON: 'Polygon'
  };

  var SOURCE = {
    BUILT_IN:          { cls: 'core', text: 'Protected' },
    SYSTEM_GENERATED:  { cls: 'sys',  text: 'System generated' },
    EDITABLE_BUILT_IN: { cls: 'edit', text: 'Core · rename only' },
    USER:              { cls: 'user', text: 'User layer' }
  };

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function msg(text, ok) {
    var el = document.getElementById('msg');
    el.className = 'msg ' + (ok ? 'ok' : 'err');
    el.textContent = text;
    if (ok) setTimeout(function () { el.className = 'msg'; }, 4000);
  }

  /* ------------------------------------------------------------------
     Render
     ------------------------------------------------------------------ */

  function load() {
    fetch('/api/layers/tree', { credentials: 'same-origin' })
      .then(function (r) { return r.json(); })
      .then(function (d) {
        TREE = (d && d.folders) || [];
        FOLDERS = TREE.map(function (f) { return { id: f.id, name: f.name }; });
        render();
      })
      .catch(function () { msg('Could not load the layer registry.'); });
  }

  function render() {
    var host = document.getElementById('tree');
    if (!TREE.length) { host.innerHTML = '<div class="empty">No layers registered.</div>'; return; }
    host.innerHTML = TREE.map(folderHtml).join('');
    // Open the first folder so the screen is never a wall of closed bars.
    var first = host.querySelector('.folder');
    if (first) first.classList.add('open');
    gate(host);
  }

  /**
   * Re-apply role gating to freshly rendered markup.
   *
   * role-gate.js sweeps [data-requires] once, on load — long before this list
   * exists. Without this pass the row actions would be visible to a USER, who
   * would then get a 403 from the API. The server is still the real guard;
   * this just stops the UI offering something it knows will be refused.
   */
  function gate(root) {
    var me = window.RoleGate && window.RoleGate.me;
    // /api/me has not answered yet. Leave the markup alone rather than guessing
    // USER and hiding an admin's buttons — gate() runs again from onReady below
    // the moment the real role is known.
    if (!me) return;
    var rank = { USER: 1, ADMIN: 2, SUPER_ADMIN: 3 }[me.role] || 1;
    root.querySelectorAll('[data-requires="admin"]').forEach(function (el) {
      if (rank < 2) el.style.display = 'none';
    });
  }

  function folderHtml(f) {
    var n = (f.layers || []).length;
    return '<div class="folder">' +
      '<div class="fhead" onclick="this.parentNode.classList.toggle(\'open\')">' +
        '<svg class="chev" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="m9 18 6-6-6-6"/></svg>' +
        '<span class="fname">' + esc(f.name) + '</span>' +
        '<span class="fcount">' + n + ' layer' + (n === 1 ? '' : 's') + '</span>' +
      '</div>' +
      '<div class="fbody">' +
        (n ? (f.layers || []).map(layerHtml).join('')
           : '<div class="empty">This folder has no layers yet.</div>') +
      '</div></div>';
  }

  function layerHtml(l) {
    var src = SOURCE[l.sourceType] || SOURCE.BUILT_IN;
    var chips = [];

    chips.push('<span class="chip geom">' + esc(GEOMETRY[l.geometryType] || l.geometryType) + '</span>');
    chips.push('<span class="chip place">' + esc(PLACEMENT[l.placement] || l.placement) + '</span>');

    /* A system-generated layer has no upload target, so showing an empty
       "accepts" list would read as a gap in the data rather than a property of
       the layer. Say what it is built from instead. */
    if (l.sourceType === 'SYSTEM_GENERATED') {
      chips.push('<span class="chip">Computed' + (l.derivedFrom ? ' from ' + esc(l.derivedFrom) : '') + '</span>');
    } else if (l.uploadFormats && l.uploadFormats.length) {
      l.uploadFormats.forEach(function (f) {
        chips.push('<span class="chip fmt">' + esc(f) + '</span>');
      });
    }
    if (l.attributeMapping) chips.push('<span class="chip attr">Attribute mapping</span>');

    var meta = [];
    if (l.features) meta.push(Number(l.features).toLocaleString() + ' features');
    if (l.physicalTable) meta.push(l.physicalTable);
    else if (l.sourceTable) meta.push(l.sourceTable);
    if (l.sectionField && l.placement === 'LINEAR_REFERENCE') {
      meta.push(l.sectionField + (l.chainageField ? ' · ' + l.chainageField : ''));
    }

    var acts = '';
    if (l.editable) {
      acts += '<button class="btn ghost sm" onclick="LM.rename(' + l.id + ')">Rename</button>';
    }
    if (l.deletable) {
      acts += '<button class="btn danger sm" onclick="LM.remove(' + l.id + ')">Delete</button>';
    }

    return '<div class="lyr">' +
      '<div class="lyr-main">' +
        '<div class="lyr-name">' +
          (l.editable ? '' : '<svg class="lock" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>') +
          esc(l.name) +
          '<span class="badge ' + src.cls + '">' + src.text + '</span>' +
        '</div>' +
        '<div class="chips">' + chips.join('') + '</div>' +
        (l.notes ? '<div class="lyr-note">' + esc(l.notes) + '</div>' : '') +
        (meta.length ? '<div class="lyr-meta">' + esc(meta.join('  ·  ')) + '</div>' : '') +
      '</div>' +
      (acts ? '<div class="lyr-act" data-requires="admin">' + acts + '</div>' : '') +
    '</div>';
  }

  /* ------------------------------------------------------------------
     Folder + layer mutations
     ------------------------------------------------------------------ */

  function post(url, body, method) {
    return fetch(url, {
      method: method || 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: body ? JSON.stringify(body) : undefined
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (d) {
        if (!r.ok) throw new Error((d && d.error) || 'Request failed');
        return d;
      });
    });
  }

  function newFolder() {
    var name = prompt('New folder name');
    if (!name) return;
    post('/api/layers/folders', { name: name })
      .then(function () { msg('Folder created.', true); load(); })
      .catch(function (e) { msg(e.message); });
  }

  function rename(id) {
    var l = findLayer(id);
    if (!l) return;
    var name = prompt('Rename layer', l.name);
    if (!name || name === l.name) return;
    post('/api/layers/' + id, {
      name: name,
      folderId: folderOf(id),
      uploadFormats: l.uploadFormats || []
    }, 'PUT')
      .then(function () { msg('Layer renamed.', true); load(); })
      .catch(function (e) { msg(e.message); });
  }

  /**
   * Delete is two decisions, asked separately.
   *
   * Retiring the definition is reversible-ish; dropping the table is not, so
   * the table is kept unless the user explicitly says otherwise. Anything
   * holding uploaded features asks the second question with the count in it —
   * "delete this layer" should never quietly mean "destroy 4,000 rows".
   */
  function remove(id) {
    var l = findLayer(id);
    if (!l) return;
    if (!confirm('Remove the layer "' + l.name + '" from the registry?')) return;

    var purge = false;
    if (l.features > 0) {
      purge = confirm('This layer holds ' + Number(l.features).toLocaleString() + ' features.\n\n' +
        'OK — also delete the stored data permanently.\n' +
        'Cancel — keep the data in the database (recoverable).');
    }
    post('/api/layers/' + id + (purge ? '?purge=true' : ''), null, 'DELETE')
      .then(function () { msg(purge ? 'Layer and its data deleted.' : 'Layer removed; its data was kept.', true); load(); })
      .catch(function (e) { msg(e.message); });
  }

  function findLayer(id) {
    for (var i = 0; i < TREE.length; i++) {
      var ls = TREE[i].layers || [];
      for (var j = 0; j < ls.length; j++) if (ls[j].id === id) return ls[j];
    }
    return null;
  }

  function folderOf(id) {
    for (var i = 0; i < TREE.length; i++) {
      var ls = TREE[i].layers || [];
      for (var j = 0; j < ls.length; j++) if (ls[j].id === id) return TREE[i].id;
    }
    return null;
  }

  /* ------------------------------------------------------------------
     Wizard
     ------------------------------------------------------------------ */

  var step = 1;

  function openWizard() {
    step = 1;
    document.getElementById('wName').value = '';
    document.getElementById('wLat').value = '';
    document.getElementById('wLng').value = '';
    document.getElementById('wSection').value = '';
    document.getElementById('wChainage').value = '';
    document.querySelectorAll('#veil input[type=radio],#veil input[type=checkbox]')
      .forEach(function (i) { i.checked = false; });
    document.querySelectorAll('#veil .opt').forEach(function (o) { o.classList.remove('sel'); });

    var sel = document.getElementById('wFolder');
    sel.innerHTML = FOLDERS.map(function (f) {
      return '<option value="' + f.id + '">' + esc(f.name) + '</option>';
    }).join('');

    document.getElementById('veil').classList.add('on');
    paint();
  }

  function closeWizard() {
    document.getElementById('veil').classList.remove('on');
  }

  var TITLES = ['Name and folder', 'Feature type', 'Upload and placement', 'Attribute data'];

  function paint() {
    document.querySelectorAll('.wpane').forEach(function (p) {
      p.style.display = (+p.dataset.pane === step) ? '' : 'none';
    });
    document.querySelectorAll('.stp').forEach(function (s) {
      s.classList.toggle('on', +s.dataset.s <= step);
    });
    document.getElementById('wStep').innerHTML =
      'Step ' + step + ' of 4 &middot; ' + TITLES[step - 1];
    document.getElementById('wBack').style.visibility = step === 1 ? 'hidden' : '';
    document.getElementById('wNext').textContent = step === 4 ? 'Create layer' : 'Next';
    if (step === 3) syncStep3();
    if (step === 4) summarise();
  }

  function val(name) {
    var el = document.querySelector('#veil input[name=' + name + ']:checked');
    return el ? el.value : null;
  }

  function formats() {
    return Array.prototype.slice
      .call(document.querySelectorAll('#wFmt input:checked'))
      .map(function (i) { return i.value; });
  }

  /**
   * Keep step 3 honest about what the chosen geometry can actually do.
   *
   * The placement question only exists for CSV (a shapefile brings its own
   * geometry), and lat/long can only place a point — so for a line or polygon
   * that option is disabled rather than offered and rejected on submit.
   */
  function syncStep3() {
    var geom = val('geom');
    var fmts = formats();
    var hasCsv = fmts.indexOf('CSV') >= 0;

    document.getElementById('wPlaceWrap').style.display = hasCsv ? '' : 'none';

    var latOpt = document.querySelector('#wPlace input[value=LATLNG]');
    var latLbl = latOpt.closest('.opt');
    var pointOnly = geom === 'POINT';
    latOpt.disabled = !pointOnly;
    latLbl.classList.toggle('dis', !pointOnly);
    if (!pointOnly && latOpt.checked) { latOpt.checked = false; latLbl.classList.remove('sel'); }

    // A multi-line feature has no single chainage axis — which part does
    // "chainage 4200" belong to? — so linear reference is unavailable for it,
    // the same way it is for polygons.
    var linOpt = document.querySelector('#wPlace input[value=LINEAR_REFERENCE]');
    var linLbl = linOpt.closest('.opt');
    var lineOk = geom !== 'POLYGON' && geom !== 'MULTILINESTRING';
    linOpt.disabled = !lineOk;
    linLbl.classList.toggle('dis', !lineOk);
    if (!lineOk && linOpt.checked) { linOpt.checked = false; linLbl.classList.remove('sel'); }

    var hint = document.getElementById('wPlaceHint');
    hint.textContent = !pointOnly && lineOk
      ? 'A line feature cannot be placed from a single coordinate pair, so lat/long is unavailable.'
      : (geom === 'POLYGON' ? 'A polygon cannot be placed by chainage — it needs a shapefile or GeoJSON.'
      : (geom === 'MULTILINESTRING' ? 'A multi-line feature has no single chainage axis, so it cannot be placed by linear reference — it needs a shapefile or GeoJSON.'
      : ''));

    var place = val('place');
    document.getElementById('wLatLng').style.display = (hasCsv && place === 'LATLNG') ? '' : 'none';
    document.getElementById('wLinear').style.display = (hasCsv && place === 'LINEAR_REFERENCE') ? '' : 'none';
  }

  function summarise() {
    var fmts = formats();
    var place = placementValue();
    var bits = [
      '<b>' + esc(document.getElementById('wName').value || 'Untitled') + '</b> — ' +
        esc(GEOMETRY[val('geom')] || '') + ' layer',
      'Placed: ' + esc(PLACEMENT[place] || place),
      'Accepts: ' + (fmts.length ? esc(fmts.join(', ')) : '—')
    ];
    if (val('attr') === 'yes') {
      bits.push('An entry will be created in the Attribute Data module for mapping its columns.');
    }
    document.getElementById('wSummary').innerHTML = bits.join('<br>');
  }

  /** GEOMETRY unless the layer takes a CSV, in which case the user chose. */
  function placementValue() {
    return formats().indexOf('CSV') >= 0 ? (val('place') || null) : 'GEOMETRY';
  }

  function wizBack() { if (step > 1) { step--; paint(); } }

  function wizNext() {
    if (step === 1) {
      if (!document.getElementById('wName').value.trim()) return msg('Give the layer a name.');
      step = 2; return paint();
    }
    if (step === 2) {
      if (!val('geom')) return msg('Choose a feature type.');
      step = 3; return paint();
    }
    if (step === 3) {
      var fmts = formats();
      if (!fmts.length) return msg('Choose at least one upload format.');
      if (fmts.indexOf('CSV') >= 0 && !val('place')) {
        return msg('Choose how CSV rows should be placed.');
      }
      step = 4; return paint();
    }
    submit();
  }

  function submit() {
    if (!val('attr')) return msg('Say whether this layer needs attribute data.');
    var body = {
      name: document.getElementById('wName').value.trim(),
      folderId: Number(document.getElementById('wFolder').value),
      geometryType: val('geom'),
      placement: placementValue(),
      uploadFormats: formats(),
      attributeMapping: val('attr') === 'yes',
      latField: document.getElementById('wLat').value.trim(),
      lngField: document.getElementById('wLng').value.trim(),
      sectionField: document.getElementById('wSection').value.trim(),
      chainageField: document.getElementById('wChainage').value.trim()
    };
    var btn = document.getElementById('wNext');
    btn.disabled = true;
    post('/api/layers', body)
      .then(function (d) {
        closeWizard();
        msg('Layer "' + d.name + '" created' +
            (d.attributeMapping ? ' — define its attributes in the Attribute Data module.' : '.'), true);
        load();
      })
      .catch(function (e) { msg(e.message); })
      .then(function () { btn.disabled = false; });
  }

  /* Selected-state styling for the radio/checkbox cards. */
  document.addEventListener('change', function (e) {
    var input = e.target;
    if (!input.matches || !input.matches('#veil .opt input')) return;
    var opt = input.closest('.opt');
    if (input.type === 'radio') {
      document.querySelectorAll('#veil .opt input[name="' + input.name + '"]').forEach(function (i) {
        i.closest('.opt').classList.toggle('sel', i.checked);
      });
    } else {
      opt.classList.toggle('sel', input.checked);
    }
    if (step === 3) syncStep3();
  });

  window.LM = { rename: rename, remove: remove };
  window.openWizard = openWizard;
  window.closeWizard = closeWizard;
  window.newFolder = newFolder;
  window.wizNext = wizNext;
  window.wizBack = wizBack;

  // Re-gate once the role is known; the tree usually renders first.
  var RG = window.RoleGate || (window.RoleGate = {});
  RG.onReady = function () { gate(document); };

  document.addEventListener('DOMContentLoaded', load);
})();
