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

  /**
   * Show a status line.
   *
   * `html` is opt-in and only ever passed a string this file composed itself —
   * server messages and layer names always go through the textContent path, so
   * a layer named with a tag cannot inject anything.
   */
  function msg(text, ok, html) {
    var el = document.getElementById('msg');
    el.className = 'msg ' + (ok ? 'ok' : 'err');
    if (html) el.innerHTML = text; else el.textContent = text;
    if (ok) setTimeout(function () { el.className = 'msg'; }, 6000);
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
    if (l.temporary) {
      chips.push('<span class="chip tmp">Temporary</span>');
    }
    if (l.frozen) {
      chips.push('<span class="chip frz">Frozen — data not in use</span>');
    }
    if (l.hidden) {
      chips.push('<span class="chip hid">Hidden from map</span>');
    }
    if (l.periodScoped) {
      chips.push('<span class="chip per">Survey period</span>');
    }
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

    // Attributes are viewable on every layer, including protected ones — seeing
    // what fields a layer carries is not an edit, and it is the main reason to
    // open this screen at all.
    /* This screen defines layers; it does not load them. Importing lives in the
       Data Console alongside every other dataset's import, so there is one place
       to go to put data in the system rather than two with different rules. */
    var acts = '<button class="btn ghost sm" onclick="AD.open(' + l.id + ')">Attributes</button>';
    // Offered on every layer: a name is a label, and the import panels in the
    // Data Console take their titles from it.
    if (l.renamable !== false) {
      acts += '<button class="btn ghost sm" data-requires="admin" onclick="LM.rename(' + l.id + ')">Rename</button>';
    }
    /* User layers are permanent. Hide takes it off the map; Freeze stops its
       data being used anywhere. Both are reversible, which is why neither is
       styled as a destructive action. */
    if (l.stateChangeable) {
      acts += '<button class="btn ghost sm" data-requires="admin" onclick="LM.hide(' + l.id + ',' +
        (!l.hidden) + ')">' + (l.hidden ? 'Show layer' : 'Hide layer') + '</button>';
      acts += '<button class="btn ghost sm" data-requires="admin" onclick="LM.freeze(' + l.id + ',' +
        (!l.frozen) + ')">' + (l.frozen ? 'Unfreeze data' : 'Freeze data') + '</button>';
    }
    if (l.deletable) {
      acts += '<button class="btn danger sm" data-requires="admin" onclick="LM.remove(' + l.id + ')">Discard</button>';
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
      (acts ? '<div class="lyr-act">' + acts + '</div>' : '') +
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
    // A core layer takes the name and nothing else — sending it a folder or a
    // format list would be asking to move things the server will refuse to move.
    post('/api/layers/' + id, l.editable
        ? { name: name, folderId: folderOf(id), uploadFormats: l.uploadFormats || [] }
        : { name: name }, 'PUT')
      .then(function () { msg('Layer renamed.', true); load(); })
      .catch(function (e) { msg(e.message); });
  }

  /** Take a layer off the map, or put it back. Its data is untouched. */
  function hide(id, on) {
    post('/api/layers/' + id + '/hidden', { hidden: on })
      .then(function () {
        msg(on ? 'Layer hidden from the map. Its data is still live and still counted.'
               : 'Layer is back on the map.', true);
        load();
      })
      .catch(function (e) { msg(e.message); });
  }

  /**
   * Freeze or thaw a layer's data.
   *
   * Freezing is the consequential one, so it asks — not because it destroys
   * anything, but because everything downstream stops seeing the data and that
   * is easy to forget you did.
   */
  function freeze(id, on) {
    var l = findLayer(id);
    if (!l) return;
    if (on && !confirm('Freeze "' + l.name + '"?\n\n' +
        'Its data stops being used anywhere — not drawn, not exported, not ' +
        'available to import into. Nothing is deleted, and you can unfreeze it ' +
        'at any time.')) return;

    post('/api/layers/' + id + '/frozen', { frozen: on })
      .then(function () {
        msg(on ? 'Data frozen. Nothing will use this layer until it is unfrozen.'
               : 'Data unfrozen and back in use.', true);
        load();
      })
      .catch(function (e) { msg(e.message); });
  }

  /**
   * Discard a temporary layer.
   *
   * Only reachable for temporary layers — permanent user layers have no delete
   * at all, and the server refuses one regardless. A temporary layer is scratch
   * by definition, so this drops its data with it rather than leaving an orphan
   * table nobody will ever look for.
   */
  function remove(id) {
    var l = findLayer(id);
    if (!l) return;
    if (!confirm('Discard the temporary layer "' + l.name + '"' +
        (l.features ? ' and its ' + Number(l.features).toLocaleString() + ' features' : '') +
        '?\n\nThis cannot be undone.')) return;

    post('/api/layers/' + id + '?purge=true', null, 'DELETE')
      .then(function () { msg('Temporary layer discarded.', true); load(); })
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
    document.getElementById('wPeriod').checked = false;
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
      periodScoped: document.getElementById('wPeriod').checked,
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
        load();
        // Data goes in from the Data Console, not from here, so the message
        // says where to go next rather than opening an importer on this screen.
        msg('Layer "' + esc(d.name) + '" created. Load its data from the ' +
            '<a href="/" style="color:inherit;text-decoration:underline">Data Console</a>' +
            ' → Import → User Layers.', true, true);
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

  window.LM = { rename: rename, remove: remove, hide: hide, freeze: freeze, reload: load };
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
