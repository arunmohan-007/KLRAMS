/*
 * lookup-manager.js — the Lookup & Short Code module.
 *
 * One window, three steps, left to right: pick a LAYER, pick an ATTRIBUTE of
 * it, then enter the values that attribute may hold and the short code for
 * each. Nothing else to navigate — the layer and the attribute are the two
 * things someone already knows when they arrive, and everything after that is
 * one table.
 *
 * <h2>The data type is the switch</h2>
 * A lookup does nothing until the attribute's data type is LOOKUP. That is not
 * a separate setting to remember on another screen: this one shows the type,
 * and turning the lookup on here sets it and creates the code list in the same
 * action. Turning it off puts the attribute back to free text and KEEPS the
 * list, because the values are work and may be pointed at something else later.
 *
 * <h2>Lookup on means the values are the only ones permitted</h2>
 * Once on, the attribute accepts its short codes and its lookup values and
 * nothing else — the import screen refuses anything outside the list. So this
 * table is not decoration; it is the column's domain.
 *
 * Code lists are SHARED where the meaning is shared: soil type is one list read
 * by sub-grade and by pavement crust. Editing through either edits both, so the
 * screen says so above the table rather than letting it be discovered after.
 */
(function () {
  'use strict';

  var state = {
    layers: [],        // [{layerKey, layer, attributes:[…]}]
    layerKey: null,
    attributeId: null,
    detail: null,      // /api/lookups/attribute/{id}
    sets: []           // every code list, for the "share an existing list" picker
  };

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function msg(text, ok) {
    var el = document.getElementById('lkMsg');
    if (!el) return;
    el.className = 'msg ' + (ok ? 'ok' : 'err');
    el.textContent = text;
    if (ok) setTimeout(function () { el.className = 'msg'; }, 4000);
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
     Load
     ------------------------------------------------------------------ */

  function boot() {
    return Promise.all([api('/api/lookups/bindable'), api('/api/lookups')])
      .then(function (r) {
        state.layers = r[0].layers || [];
        state.sets = r[1].sets || [];
        if (state.layers.length) state.layerKey = state.layers[0].layerKey;
        render();
      })
      .catch(function (e) {
        document.getElementById('lkBody').innerHTML =
          '<div class="empty">' + esc(e.message) + '</div>';
      });
  }

  function currentLayer() {
    for (var i = 0; i < state.layers.length; i++) {
      if (state.layers[i].layerKey === state.layerKey) return state.layers[i];
    }
    return null;
  }

  function loadAttribute(id) {
    state.attributeId = id;
    state.detail = null;
    render();
    if (id == null) return;
    api('/api/lookups/attribute/' + id)
      .then(function (d) { state.detail = d; render(); })
      .catch(function (e) { msg(e.message); });
  }

  /* ------------------------------------------------------------------
     Render
     ------------------------------------------------------------------ */

  function render() {
    var layer = currentLayer();

    var h = '<div class="lk-bar">' +
      '<label class="lk-lbl">Layer</label>' +
      '<select class="lk-pick" onchange="LK.pickLayer(this.value)">' +
        state.layers.map(function (l) {
          return '<option value="' + esc(l.layerKey) + '"' +
            (l.layerKey === state.layerKey ? ' selected' : '') + '>' + esc(l.layer) + '</option>';
        }).join('') +
      '</select>' +
      '<label class="lk-lbl">Attribute</label>' +
      '<select class="lk-pick wide" onchange="LK.pickAttribute(this.value)">' +
        '<option value="">— choose an attribute —</option>' +
        ((layer && layer.attributes) || []).map(function (a) {
          // The tick tells you which attributes already carry a lookup without
          // having to open each one.
          return '<option value="' + a.id + '"' +
            (a.id === state.attributeId ? ' selected' : '') + '>' +
            (a.lookupKey ? '●  ' : '○  ') + esc(a.name) +
            (a.dataset !== 'default' ? ' (' + esc(a.dataset) + ')' : '') + '</option>';
        }).join('') +
      '</select>' +
      '</div>';

    h += '<div id="lkPanel">' + panelHtml() + '</div>';
    document.getElementById('lkBody').innerHTML = h;
  }

  function panelHtml() {
    if (state.attributeId == null) {
      return '<div class="empty">Choose an attribute to set up its lookup values ' +
        'and short codes.</div>';
    }
    var d = state.detail;
    if (!d) return '<div class="empty">Loading…</div>';

    if (d.placement) {
      return '<div class="empty">“' + esc(d.attribute) + '” places the feature on the map ' +
        '(section label or chainage), so it cannot be a coded value.</div>';
    }

    var h = '';

    /* The state line: what the data type is, and the switch. This is the whole
       "lookup only works when the type is LOOKUP" rule, said where it applies
       rather than left on another screen to be discovered. */
    h += '<div class="lk-state' + (d.enabled ? ' on' : '') + '">' +
      '<div class="lk-state-l">' +
        '<div class="lk-state-t">' + esc(d.layer) + ' · ' + esc(d.attribute) + '</div>' +
        '<div class="lk-state-s">Data type: <b>' +
          esc(d.dataType.charAt(0) + d.dataType.slice(1).toLowerCase()) + '</b>' +
          (d.enabled
            ? ' — the values below are the only ones this attribute accepts, ' +
              'by short code or in full.'
            : ' — free text. Turn the lookup on to restrict it to a list of values.') +
        '</div>' +
      '</div>' +
      '<div class="lk-state-r" data-requires="admin">' +
        (d.enabled
          ? '<button class="btn sm ghost" onclick="LK.disable()">Turn lookup off</button>'
          : '<button class="btn sm" onclick="LK.enable()">Turn lookup on</button>' +
            shareHtml()) +
      '</div></div>';

    if (!d.enabled) return h;

    if (d.sharedWith && d.sharedWith.length) {
      h += '<div class="lk-shared">This code list is shared — editing it here also changes ' +
        d.sharedWith.map(function (u) {
          return '<span class="lk-use">' + esc(u.layer) + ' · ' + esc(u.attribute) + '</span>';
        }).join('') + '</div>';
    }

    h += '<div class="attr-head"><div class="attr-h-l">Lookup Details</div>' +
      '<div class="lk-head-r" data-requires="admin">' +
      '<button class="btn sm" onclick="LK.addRow()">+ Add</button>' +
      '<button class="btn sm ghost" onclick="LK.exportCsv()">Export Data</button>' +
      '</div></div>';

    h += '<div class="tbl-wrap"><table class="atbl lk-tbl"><thead><tr>' +
      '<th>Lookup value</th><th style="width:150px">Short code</th>' +
      '<th style="width:190px">Depends on</th>' +
      '<th style="width:110px">Is active?</th>' +
      '<th style="width:90px">Action</th>' +
      '</tr></thead><tbody>';

    (d.values || []).forEach(function (v) {
      h += '<tr' + (v.active ? '' : ' class="retired"') + '>' +
        '<td class="an">' + esc(v.label) + '</td>' +
        '<td><code class="lk-code">' + esc(v.code) + '</code></td>' +
        '<td>' + (v.dependsOn
            ? '<span class="chip al">' + esc(v.dependsOn) + '</span>'
            : '<span class="opt">—</span>') + '</td>' +
        '<td><span class="badge ' + (v.active ? 'ok' : 'off') + '">' +
          (v.active ? 'YES' : 'NO') + '</span></td>' +
        '<td class="acts" data-requires="admin">' +
          '<button class="ic" title="Edit" onclick="LK.editRow(' + v.id + ')">✎</button>' +
          '<button class="ic del" title="Remove" onclick="LK.delRow(' + v.id + ')">🗑</button>' +
        '</td></tr>';
    });
    if (!(d.values || []).length) {
      h += '<tr><td colspan="5" class="empty">No values yet — use <b>+ Add</b> to enter ' +
        'the first lookup value and its short code.</td></tr>';
    }
    h += '</tbody></table></div>';

    h += '<div class="lk-note">“Depends on” is recorded but not yet acted on — the rule for ' +
      'conditional lookups is still to be decided, so the column is here and editable, and ' +
      'nothing reads it.</div>';
    return h;
  }

  /** Offer to reuse an existing list instead of making a new one. */
  function shareHtml() {
    if (!state.sets.length) return '';
    return '<select class="lk-pick" id="lkShare" title="Reuse an existing code list">' +
      '<option value="">…or reuse a list</option>' +
      state.sets.map(function (s) {
        return '<option value="' + esc(s.key) + '">' + esc(s.name) +
          ' (' + s.count + ')</option>';
      }).join('') + '</select>';
  }

  /* ------------------------------------------------------------------
     Actions
     ------------------------------------------------------------------ */

  function pickLayer(key) {
    state.layerKey = key;
    state.attributeId = null;
    state.detail = null;
    render();
  }

  function pickAttribute(id) {
    loadAttribute(id ? Number(id) : null);
  }

  function enable() {
    var sel = document.getElementById('lkShare');
    api('/api/lookups/attribute/' + state.attributeId + '/enable',
        { setKey: sel ? sel.value : '' })
      .then(function () {
        msg('Lookup turned on — data type is now Lookup.', true);
        return refresh();
      })
      .catch(function (e) { msg(e.message); });
  }

  function disable() {
    if (!confirm('Turn the lookup off for “' + state.detail.attribute + '”?\n\n' +
        'It goes back to free text and stops restricting what may be imported. ' +
        'The code list itself is kept.')) return;
    api('/api/lookups/attribute/' + state.attributeId + '/disable', {})
      .then(function () { msg('Lookup turned off.', true); return refresh(); })
      .catch(function (e) { msg(e.message); });
  }

  /** Reload the attribute AND the layer list, so the ● markers stay truthful. */
  function refresh() {
    return Promise.all([
      api('/api/lookups/bindable'),
      api('/api/lookups'),
      api('/api/lookups/attribute/' + state.attributeId)
    ]).then(function (r) {
      state.layers = r[0].layers || [];
      state.sets = r[1].sets || [];
      state.detail = r[2];
      render();
    });
  }

  function findRow(id) {
    var vs = (state.detail && state.detail.values) || [];
    for (var i = 0; i < vs.length; i++) if (vs[i].id === id) return vs[i];
    return null;
  }

  function addRow() {
    var label = prompt('Lookup value — what a reader should see (e.g. Flexible)');
    if (!label) return;
    var code = prompt('Short code as it appears in the data (e.g. FLX)', '');
    if (!code) return;
    var dep = prompt('Depends on — optional, and not acted on yet. Leave blank.', '');
    save(code, label, dep || '', true);
  }

  function editRow(id) {
    var v = findRow(id);
    if (!v) return;
    var label = prompt('Lookup value', v.label);
    if (!label) return;
    var dep = prompt('Depends on — optional, not acted on yet.', v.dependsOn || '');
    if (dep === null) dep = v.dependsOn || '';
    var act = confirm('Is this value active?\n\nOK = active (offered and accepted on import)\n' +
      'Cancel = inactive (still decoded for rows that hold it, but not accepted in new files)');
    // The code is the identity — it is what the stored rows carry — so it is not
    // editable. Changing it would orphan every row holding the old one.
    save(v.code, label, dep, act);
  }

  function save(code, label, dependsOn, active) {
    api('/api/lookups/' + state.detail.set.id + '/values',
        { code: code, label: label, dependsOn: dependsOn, active: active }, 'POST')
      .then(function () { msg('Saved.', true); return refresh(); })
      .catch(function (e) { msg(e.message); });
  }

  function delRow(id) {
    var v = findRow(id);
    if (!v) return;
    if (!confirm('Remove “' + v.label + '” (' + v.code + ')?\n\n' +
        'Rows already holding this code will show the raw code again. ' +
        'To stop it being accepted in new files while keeping it readable, ' +
        'edit it and set it inactive instead.')) return;
    api('/api/lookups/values/' + id, null, 'DELETE')
      .then(function () { msg('Value removed.', true); return refresh(); })
      .catch(function (e) { msg(e.message); });
  }

  /** The list as CSV — the same two columns the RMMS lookup sheet uses. */
  function exportCsv() {
    var d = state.detail;
    var cell = function (v) {
      v = String(v == null ? '' : v);
      return /[",\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
    };
    var rows = [['Layer', 'Attribute Column', 'Finalised Lookup value',
                 'Finalised Short Code', 'Depends On', 'Is Active']];
    (d.values || []).forEach(function (v) {
      rows.push([d.layer, d.attribute, v.label, v.code, v.dependsOn || '',
                 v.active ? 'YES' : 'NO']);
    });
    var csv = rows.map(function (r) { return r.map(cell).join(','); }).join('\r\n');
    var a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
    a.download = (d.layer + '_' + d.attribute).replace(/[^A-Za-z0-9]+/g, '_') + '_lookup.csv';
    a.click();
    URL.revokeObjectURL(a.href);
  }

  window.LK = {
    pickLayer: pickLayer, pickAttribute: pickAttribute,
    enable: enable, disable: disable,
    addRow: addRow, editRow: editRow, delRow: delRow,
    exportCsv: exportCsv
  };

  boot();
})();
