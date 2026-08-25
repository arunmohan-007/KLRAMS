/*
 * attribute-data.js — the Attribute Data module, opened from Layer Management.
 *
 * Shows every attribute a layer carries: its type, length, unit, whether it is
 * mandatory, whether it drives a lookup, and — for a linearly-referenced layer —
 * which attribute places the feature.
 *
 * Traffic has TWO datasets (stations and counts), so the panel is built around
 * a dataset list rather than a single table; every other layer simply has one.
 *
 * Every attribute is editable, including the standard ones of a core layer:
 * the column list now comes from the RMMS Format-B returns and the RMMS cell is
 * who decides what each field should be called. Renaming is safe because the
 * label and the storage are separate — the name is what the map cards and the
 * dashboards print, while the storage key underneath it never moves.
 *
 * As in Layer Management, nothing here decides what is protected. `canDelete`
 * and each attribute's `placement` flag arrive from the server, and the API
 * refuses the call regardless of what this file renders.
 */
(function () {
  'use strict';

  var TYPES = ['STRING', 'INTEGER', 'DECIMAL', 'DATE', 'BOOLEAN', 'LOOKUP'];

  var ROLE_LABEL = {
    NONE: '',
    SECTION_LABEL: 'Section label',
    CHAINAGE: 'Chainage',
    START_CHAINAGE: 'From chainage',
    END_CHAINAGE: 'To chainage'
  };

  var state = { layerId: null, data: null, dataset: null };

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function msg(text, ok) {
    var el = document.getElementById('attrMsg');
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
     Open / render
     ------------------------------------------------------------------ */

  function open(layerId) {
    state.layerId = layerId;
    document.getElementById('attrVeil').classList.add('on');
    document.getElementById('attrBody').innerHTML = '<div class="empty">Loading…</div>';
    api('/api/attributes/layer/' + layerId)
      .then(function (d) {
        state.data = d;
        state.dataset = (d.datasets[0] || {}).key || 'default';
        render();
      })
      .catch(function (e) {
        document.getElementById('attrBody').innerHTML =
          '<div class="empty">' + esc(e.message) + '</div>';
      });
  }

  function close() {
    document.getElementById('attrVeil').classList.remove('on');
  }

  function current() {
    var ds = state.data.datasets;
    for (var i = 0; i < ds.length; i++) if (ds[i].key === state.dataset) return ds[i];
    return ds[0];
  }

  function render() {
    var d = state.data;
    document.getElementById('attrTitle').textContent = d.layerName;
    document.getElementById('attrSub').textContent =
      d.placement === 'LINEAR_REFERENCE'
        ? 'Linearly referenced · placed by section label and chainage'
        : (d.placement === 'LATLNG' ? 'Placed by latitude / longitude'
                                    : 'Geometry comes from the uploaded file');

    var html = '';

    // Only shown when a layer really has more than one dataset — traffic is the
    // only one today, and a lone tab would just be noise everywhere else.
    if (d.datasets.length > 1) {
      html += '<div class="dstabs">' + d.datasets.map(function (ds) {
        return '<button class="dstab' + (ds.key === state.dataset ? ' on' : '') +
          '" onclick="AD.tab(\'' + ds.key + '\')">' + esc(ds.label) +
          ' <span class="dscount">' + ds.attributes.length + '</span></button>';
      }).join('') + '</div>';
    }

    html += '<div class="attr-head">' +
      '<div class="attr-h-l">' + esc(current().label) + '</div>' +
      '<button class="btn sm" onclick="AD.addRow()">Add Custom Attribute</button>' +
      '</div>';

    html += '<div class="tbl-wrap"><table class="atbl"><thead><tr>' +
      '<th>Name</th><th>Type</th><th>Lookup</th><th>Length</th><th>Unit</th>' +
      '<th>Accepted column names</th>' +
      '<th>Placement</th><th>Mandatory</th><th>Attribute type</th><th>Status</th><th></th>' +
      '</tr></thead><tbody>' +
      (current().attributes.length
        ? current().attributes.map(rowHtml).join('')
        : '<tr><td colspan="11" class="empty">No attributes defined yet.</td></tr>') +
      '</tbody></table></div>';

    document.getElementById('attrBody').innerHTML = html;
  }

  /**
   * The header spellings an upload may use for this attribute, shown as chips.
   *
   * Capped at three with a "+n" tail: FWD alone carries four or five per field
   * and the full list would push every other column off the screen. The edit
   * form shows all of them.
   */
  function aliasChips(a) {
    var list = String(a.aliases || '').split(',').filter(function (s) { return s.trim(); });
    if (!list.length) return '';
    var shown = list.slice(0, 3).map(function (s) {
      return '<span class="chip al">' + esc(s.trim()) + '</span>';
    }).join('');
    return shown + (list.length > 3
      ? '<span class="opt" title="' + esc(list.join(', ')) + '">+' + (list.length - 3) + '</span>'
      : '');
  }

  function rowHtml(a) {
    // Every attribute may be edited and every non-placement one may be removed.
    // The only lock left is the attribute that places the feature: without it
    // the layer cannot put anything on the map at all.
    var retired = a.status !== 'ACTIVE';
    return '<tr' + (retired ? ' class="retired"' : '') + '>' +
      '<td class="an">' + (a.placement ? '<span class="pin" title="Places the feature">◆</span> ' : '') +
        esc(a.name) + '<span class="sk">' + esc(a.storageKey) + '</span></td>' +
      '<td>' + esc(a.dataType.charAt(0) + a.dataType.slice(1).toLowerCase()) +
        (a.dataType === 'DATE' ? '<span class="sk">' + esc(a.dateFormat || '') + '</span>' : '') + '</td>' +
      '<td>' + (a.lookupKey ? '<span class="chip lk">' + esc(a.lookupKey) + '</span>' : '') + '</td>' +
      '<td>' + (a.length == null ? '' : a.length) + '</td>' +
      '<td>' + esc(a.unit || '') + '</td>' +
      '<td class="alz">' + aliasChips(a) + '</td>' +
      '<td>' + (a.placement ? '<span class="chip pl">' + esc(ROLE_LABEL[a.role]) + '</span>' : '') + '</td>' +
      '<td>' + (a.mandatory ? '<span class="chip mn">Required</span>' : '<span class="opt">Optional</span>') + '</td>' +
      '<td>' + (a.attributeType === 'CUSTOM' ? '<span class="chip cu">Custom</span>' : 'Standard') + '</td>' +
      '<td><span class="badge ' + (a.status === 'ACTIVE' ? 'ok' : 'off') + '">' + esc(a.status) + '</span></td>' +
      '<td class="acts" data-requires="admin">' +
        '<button class="ic" title="Edit" onclick="AD.edit(' + a.id + ')">✎</button>' +
        (a.placement
          ? '<span class="ic lock" title="Places the feature — give the role to another ' +
            'attribute before removing it">🔒</span>'
          : '<button class="ic del" title="Remove" onclick="AD.remove(' + a.id + ')">🗑</button>') +
      '</td></tr>';
  }

  function tab(key) {
    state.dataset = key;
    render();
  }

  /* ------------------------------------------------------------------
     Edit / add
     ------------------------------------------------------------------ */

  function findAttr(id) {
    var list = current().attributes;
    for (var i = 0; i < list.length; i++) if (list[i].id === id) return list[i];
    return null;
  }

  function edit(id) {
    var a = findAttr(id);
    if (!a) return;
    showForm(a);
  }

  function addRow() {
    showForm(null);
  }

  /**
   * The edit form.
   *
   * Role and mandatory interact: an attribute that places the feature is
   * mandatory by definition, so the checkbox is forced on and disabled rather
   * than letting someone build a layer that cannot be imported.
   */
  function showForm(a) {
    var isNew = !a;
    var lr = state.data.placement === 'LINEAR_REFERENCE';
    var point = state.data.geometryType === 'POINT';

    var roles = ['NONE', 'SECTION_LABEL'];
    if (lr) roles = roles.concat(point ? ['CHAINAGE'] : ['START_CHAINAGE', 'END_CHAINAGE']);

    var html =
      '<div class="fld"><label>Attribute name</label>' +
        '<input type="text" id="afName" value="' + esc(a ? a.name : '') + '"></div>' +
      '<div class="frow">' +
        '<div class="fld"><label>Type</label><select id="afType">' +
          TYPES.map(function (t) {
            return '<option value="' + t + '"' + (a && a.dataType === t ? ' selected' : '') + '>' +
              t.charAt(0) + t.slice(1).toLowerCase() + '</option>';
          }).join('') + '</select></div>' +
        '<div class="fld"><label>Length</label>' +
          '<input type="text" id="afLen" value="' + (a && a.length != null ? a.length : '') + '"></div>' +
        '<div class="fld"><label>Unit</label>' +
          '<input type="text" id="afUnit" value="' + esc(a ? (a.unit || '') : '') + '"></div>' +
      '</div>' +
      (lr ? '<div class="fld"><label>Used for linear referencing</label><select id="afRole">' +
        roles.map(function (r) {
          return '<option value="' + r + '"' + (a && a.role === r ? ' selected' : '') + '>' +
            (r === 'NONE' ? '— not a placement column —' : ROLE_LABEL[r]) + '</option>';
        }).join('') + '</select>' +
        '<div class="hint">Marking an attribute here tells the importer which column carries the ' +
        'section label or chainage. Only one attribute can hold each role.</div></div>' : '') +
      '<div class="fld"><label class="ck"><input type="checkbox" id="afReq"' +
        (a && a.mandatory ? ' checked' : '') + '> Mandatory</label>' +
        '<div class="hint">A mandatory attribute must be matched to a column at import. ' +
        'An optional one may be left unmapped, and rows whose value does not fit the type are skipped.</div></div>' +
      '<div class="fld"><label>Accepted column names</label>' +
        '<input type="text" id="afAliases" value="' + esc(a ? (a.aliases || '') : '') + '"' +
        ' placeholder="Section_Label, Section Label, Label">' +
        '<div class="hint">Comma separated. Districts do not spell their headers the same way — ' +
        'list every spelling seen in a survey return here and they all resolve to this one ' +
        'attribute. Case, spaces and underscores are ignored when matching.</div></div>' +
      (a ? '<div class="fld"><label>Status</label><select id="afStatus">' +
        ['ACTIVE', 'RETIRED'].map(function (s) {
          return '<option value="' + s + '"' + (a.status === s ? ' selected' : '') + '>' +
            (s === 'ACTIVE' ? 'Active' : 'Retired') + '</option>';
        }).join('') + '</select>' +
        '<div class="hint">A retired attribute is no longer mapped at import and is greyed out ' +
        'on this screen. Stored values are left untouched, so retiring is reversible.</div></div>' : '') +
      '<div class="fld" id="afLookupWrap" style="display:none"><label>Lookup set</label>' +
        '<input type="text" id="afLookup" value="' + esc(a ? (a.lookupKey || '') : '') + '"' +
        ' placeholder="leave blank to create one named after this attribute">' +
        '<div class="hint">A Lookup attribute accepts only the values defined for its set, each with ' +
        'a short code. Manage the values in the Lookup module.</div></div>' +
      (a && a.dataType === 'DATE' || !a
        ? '<div class="fld" id="afDateWrap" style="display:none"><label>Date format</label>' +
          '<input type="text" value="' + esc(state.data.dateFormat) + '" disabled>' +
          '<div class="hint">KLRAMS stores every date in this format; there is no alternative.</div></div>'
        : '');

    document.getElementById('afTitle').textContent = isNew ? 'Add custom attribute' : 'Edit attribute';
    document.getElementById('afBody').innerHTML = html;
    document.getElementById('afSave').onclick = function () { save(a); };
    document.getElementById('afVeil').classList.add('on');

    var typeSel = document.getElementById('afType');
    var roleSel = document.getElementById('afRole');
    function sync() {
      var t = typeSel.value;
      var w = document.getElementById('afLookupWrap');
      if (w) w.style.display = t === 'LOOKUP' ? '' : 'none';
      var dw = document.getElementById('afDateWrap');
      if (dw) dw.style.display = t === 'DATE' ? '' : 'none';
      var req = document.getElementById('afReq');
      var placing = roleSel && roleSel.value !== 'NONE';
      if (placing) { req.checked = true; req.disabled = true; } else { req.disabled = false; }
    }
    typeSel.addEventListener('change', sync);
    if (roleSel) roleSel.addEventListener('change', sync);
    sync();
  }

  function closeForm() {
    document.getElementById('afVeil').classList.remove('on');
  }

  function save(a) {
    var roleSel = document.getElementById('afRole');
    var body = {
      dataset: state.dataset,
      name: document.getElementById('afName').value.trim(),
      dataType: document.getElementById('afType').value,
      length: document.getElementById('afLen').value.trim(),
      unit: document.getElementById('afUnit').value.trim(),
      role: roleSel ? roleSel.value : 'NONE',
      mandatory: document.getElementById('afReq').checked,
      lookupKey: (document.getElementById('afLookup') || {}).value || '',
      aliases: document.getElementById('afAliases').value.trim(),
      status: (document.getElementById('afStatus') || {}).value || 'ACTIVE'
    };
    if (!body.name) return msg('Give the attribute a name.');

    var p = a ? api('/api/attributes/' + a.id, body, 'PUT')
              : api('/api/attributes/layer/' + state.layerId, body, 'POST');
    p.then(function () {
      closeForm();
      msg(a ? 'Attribute updated.' : 'Attribute added.', true);
      return api('/api/attributes/layer/' + state.layerId);
    }).then(function (d) {
      if (!d) return;
      state.data = d;
      render();
    }).catch(function (e) { msg(e.message); });
  }

  /**
   * Remove an attribute.
   *
   * A standard attribute is retired rather than deleted — it is part of the
   * declared column list and the next restart would put it straight back — so
   * the prompt says which of the two is about to happen instead of promising a
   * delete that undoes itself.
   */
  function remove(id) {
    var a = findAttr(id);
    if (!a) return;
    var custom = a.attributeType === 'CUSTOM';
    if (!confirm(custom
        ? 'Delete the custom attribute "' + a.name + '"? Stored values for it are left untouched.'
        : '"' + a.name + '" is a standard column of this layer, so it will be retired rather ' +
          'than deleted: it stops being mapped at import and is greyed out here. Stored values ' +
          'are left untouched and you can set it back to Active at any time.')) return;
    api('/api/attributes/' + id, null, 'DELETE')
      .then(function () {
        msg(custom ? 'Attribute deleted.' : 'Attribute retired.', true);
        return api('/api/attributes/layer/' + state.layerId);
      })
      .then(function (d) { state.data = d; render(); })
      .catch(function (e) { msg(e.message); });
  }

  window.AD = {
    open: open, close: close, tab: tab, edit: edit,
    addRow: addRow, remove: remove, closeForm: closeForm
  };
})();
