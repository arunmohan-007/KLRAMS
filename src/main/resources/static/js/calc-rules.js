/*
 * calc-rules.js — the Calculation Rules module (/rules.html).
 *
 * Four rules, one tab each. Every tab is built the same way:
 *
 *   1. the BEFORE/AFTER strip — what the figure was without this correction and
 *      what it is with it, because a correction nobody can see the size of is a
 *      correction nobody can check;
 *   2. WHERE IT IS USED — the screens the rule reaches, so an edit is never made
 *      blind (the list comes from the server, next to the rule itself);
 *   3. the editor.
 *
 * The two grouping rules (carriageway, traffic station) share a workbench: the
 * ungrouped candidates on the left, the groups on the right. A section or
 * station may belong to at most ONE group — the server holds that as a primary
 * key, and the left column shows an already-grouped row greyed out with the
 * group holding it, so the rule is visible rather than only enforced.
 *
 * Reads are open to any signed-in user; the edit controls are hidden from
 * view-only accounts by role-gate.js (data-requires="admin"), and the server
 * enforces the same.
 */
(function () {
  'use strict';

  var D = null;              // the whole /api/calc-rules payload
  var tab = 'carriageway';
  var pick = {};             // tab -> Set of checked candidate keys
  var msgTimer = null;

  /* ---------- small helpers ---------- */

  function el(id) { return document.getElementById(id); }
  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }
  function num(v, dp) {
    if (v == null || v === '' || isNaN(+v)) return '–';
    return (+v).toLocaleString(undefined, { minimumFractionDigits: dp || 0, maximumFractionDigits: dp == null ? 2 : dp });
  }
  function msg(text, kind) {
    var m = el('msg');
    m.className = 'msg ' + (kind || 'ok');
    m.innerHTML = text;
    clearTimeout(msgTimer);
    if (kind !== 'err' && kind !== 'warn') msgTimer = setTimeout(function () { m.className = 'msg'; }, 6000);
    m.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }

  function api(method, path, body) {
    return fetch('/api/calc-rules' + path, {
      method: method,
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: body == null ? undefined : JSON.stringify(body)
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (j) {
        if (!r.ok || j.ok === false) throw new Error(j.error || 'The change could not be saved.');
        return j;
      });
    });
  }

  /** Reload everything and redraw, keeping the open tab. */
  function reload() {
    return fetch('/api/calc-rules', { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      .then(function (r) { return r.json(); })
      .then(function (d) {
        if (d && d.ok === false) throw new Error(d.error || 'The rules could not be loaded.');
        D = d;
        render();
      });
  }

  function fail(e) { msg(esc(e && e.message ? e.message : String(e)), 'err'); }

  /* ---------- the shared blocks at the top of every tab ---------- */

  /** The before/after strip for one rule's effect object. */
  function fxStrip(fx) {
    if (!fx) return '';
    var dp = fx.unit === 'km' ? 2 : (fx.unit === 'km²' ? 3 : 0);
    var d = fx.delta;
    var dTxt = d == null ? '–' : (d > 0 ? '+' : '') + num(d, dp);
    var counts = '';
    if (fx.before_count != null) {
      counts = '<div class="fx-c"><div class="fx-l">' + esc(fx.count_unit || 'rows') + '</div>' +
               '<div class="fx-v">' + num(fx.before_count, 0) + ' → ' + num(fx.after_count, 0) + '</div></div>';
    }
    return '<div class="fx">' +
      '<div class="fx-c before"><div class="fx-l">Before correction</div><div class="fx-v">' +
        num(fx.before, dp) + '<span class="fx-u">' + esc(fx.unit || '') + '</span></div></div>' +
      '<div class="fx-c after"><div class="fx-l">After correction</div><div class="fx-v">' +
        num(fx.after, dp) + '<span class="fx-u">' + esc(fx.unit || '') + '</span></div></div>' +
      '<div class="fx-c delta"><div class="fx-l">Difference</div><div class="fx-v">' + dTxt +
        '<span class="fx-u">' + esc(fx.unit || '') + '</span></div></div>' +
      counts +
      '<div class="fx-c wide"><div class="fx-l">' + esc(fx.metric || '') + '</div>' +
        '<div class="fx-n">' + esc(fx.note || '') + '</div></div>' +
    '</div>';
  }

  /** The "where this rule is used" panel. */
  function usePanel(list) {
    if (!list || !list.length) return '';
    return '<div class="use"><div class="use-t">Where this rule is used</div>' +
      list.map(function (u) {
        return '<div class="use-r"><span class="use-w">' + esc(u.where) + '</span>' +
               '<span class="use-d">' + esc(u.what) + '</span></div>';
      }).join('') + '</div>';
  }

  /* ---------- tabs ---------- */

  var TABS = [
    { key: 'carriageway', label: 'Carriageway correction', count: function () { return (D.carriageway.groups || []).length; } },
    { key: 'stations',    label: 'Traffic station groups', count: function () { return (D.stations.groups || []).length; } },
    { key: 'width',       label: 'Pavement width bands',   count: function () { return (D.width.bands || []).length; } },
    { key: 'pci',         label: 'PCI weights',            count: function () { return null; } }
  ];

  function renderTabs() {
    el('tabs').innerHTML = TABS.map(function (t) {
      var n = t.count();
      return '<button class="tab' + (t.key === tab ? ' on' : '') + '" data-tab="' + t.key + '">' +
        t.label + (n == null ? '' : '<span class="n">' + n + '</span>') + '</button>';
    }).join('');
  }

  function showTab(key) {
    tab = key;
    renderTabs();
    TABS.forEach(function (t) {
      var p = el('pane-' + t.key);
      if (p) p.classList.toggle('on', t.key === key);
    });
  }

  /* ==================================================================
     1 + 2. The grouping workbench (carriageway and traffic stations)
     ================================================================== */

  /**
   * One grouping tab. `cfg` names the two sides so the same workbench serves a
   * road section and a traffic station without either pretending to be the other.
   */
  function renderWorkbench(cfg) {
    var pane = el('pane-' + cfg.pane);
    var data = D[cfg.dataKey];
    var groups = data.groups || [];
    var cands = data.candidates || [];
    if (!pick[cfg.pane]) pick[cfg.pane] = {};

    var q = (cfg.q || '').toLowerCase();
    var dist = cfg.dist || '';
    var shown = cands.filter(function (c) {
      var name = String(c[cfg.idField] || '');
      if (dist && String(c.district || '') !== dist) return false;
      if (!q) return true;
      return (name + ' ' + (c.road_name || '') + ' ' + (c.district || '') + ' ' + (c.section || ''))
        .toLowerCase().indexOf(q) >= 0;
    });
    var free = shown.filter(function (c) { return !c.group_id; });
    var picked = Object.keys(pick[cfg.pane]).filter(function (k) { return pick[cfg.pane][k]; });

    var districts = [];
    cands.forEach(function (c) {
      if (c.district && districts.indexOf(c.district) < 0) districts.push(c.district);
    });
    districts.sort();

    pane.innerHTML =
      fxStrip(data.effect) +
      usePanel(data.used_by) +
      '<div class="sec-d">' + cfg.blurb + '</div>' +
      '<div class="work">' +
        '<div class="col">' +
          '<div class="col-h"><span class="col-t">' + cfg.leftTitle + '</span>' +
            '<span class="col-n">' + free.length + ' ungrouped · ' + shown.length + ' shown</span></div>' +
          '<div class="filt">' +
            '<input class="srch" id="' + cfg.pane + 'Q" placeholder="' + cfg.searchHint + '" value="' + esc(cfg.q || '') + '">' +
            (districts.length ? '<select class="srch" id="' + cfg.pane + 'D"><option value="">All districts</option>' +
              districts.map(function (d) {
                return '<option value="' + esc(d) + '"' + (d === dist ? ' selected' : '') + '>' + esc(d) + '</option>';
              }).join('') + '</select>' : '') +
          '</div>' +
          '<div class="col-b">' +
            (shown.length ? shown.map(function (c) { return candRow(cfg, c); }).join('')
                          : '<div class="empty">' + cfg.emptyLeft + '</div>') +
          '</div>' +
          '<div class="sel-bar" data-requires="admin">' +
            '<button class="btn" id="' + cfg.pane + 'Make"' + (picked.length < 2 ? ' disabled' : '') + '>' +
              'Group ' + (picked.length || 'the') + ' selected' + '</button>' +
            '<button class="btn ghost sm" id="' + cfg.pane + 'Clear"' + (picked.length ? '' : ' disabled') + '>Clear</button>' +
            (cfg.rescan ? '<button class="btn ghost sm" id="' + cfg.pane + 'Rescan">Scan for A/B pairs</button>' : '') +
            '<span class="col-n">' + (picked.length < 2 ? 'select two or more' : picked.length + ' selected') + '</span>' +
          '</div>' +
        '</div>' +
        '<div class="col">' +
          '<div class="col-h"><span class="col-t">Groups</span>' +
            '<span class="col-n">' + groups.length + ' group' + (groups.length === 1 ? '' : 's') + '</span></div>' +
          '<div class="col-b">' +
            (groups.length ? groups.map(function (g) { return groupRow(cfg, g); }).join('')
                           : '<div class="empty">No groups yet.</div>') +
          '</div>' +
        '</div>' +
      '</div>' +
      '<div class="note">' + cfg.footNote + '</div>';

    wireWorkbench(cfg);
  }

  function candRow(cfg, c) {
    var id = String(c[cfg.idField] || '');
    var taken = !!c.group_id;
    var checked = !taken && pick[cfg.pane][id];
    return '<label class="item' + (taken ? ' taken' : '') + '">' +
      (taken ? '<input type="checkbox" disabled>' :
               '<input type="checkbox" data-pick="' + esc(id) + '"' + (checked ? ' checked' : '') + '>') +
      '<span class="item-m"><span class="item-t">' + esc(id) + '</span>' +
        '<span class="item-s">' + esc(cfg.candSub(c)) +
        (taken ? ' · <b>in group:</b> ' + esc(c.group_name) : '') + '</span></span>' +
      '<span class="item-r">' + esc(cfg.candRight(c)) + '</span>' +
    '</label>';
  }

  function groupRow(cfg, g) {
    var members = g.members || [];
    return '<div class="grp">' +
      '<div class="grp-h">' +
        '<div style="flex:1;min-width:0">' +
          '<div class="grp-t">' + esc(g.name) + '</div>' +
          '<div class="grp-s">' + cfg.groupSub(g) + '</div>' +
        '</div>' +
        '<div class="grp-a" data-requires="admin">' +
          '<button class="ic" data-rename="' + g.id + '" title="Rename this group">✎</button>' +
          '<button class="ic del" data-del="' + g.id + '" title="Delete this group">✕</button>' +
        '</div>' +
      '</div>' +
      '<div class="grp-m">' + members.map(function (m) {
        var name = String(m[cfg.memberField] || '');
        var missing = cfg.memberMissing(m);
        return '<div class="mem' + (missing ? ' gone' : '') + '">' +
          '<span class="mem-n">' + esc(name) + '</span>' +
          '<span class="mem-l">' + esc(cfg.memberRight(m)) + '</span>' +
          '<button class="mem-x" data-drop="' + esc(name) + '" data-grp="' + g.id + '" ' +
            'data-requires="admin" title="Remove from this group">✕</button>' +
        '</div>';
      }).join('') + '</div>' +
    '</div>';
  }

  function wireWorkbench(cfg) {
    var pane = el('pane-' + cfg.pane);

    var qEl = el(cfg.pane + 'Q');
    if (qEl) qEl.addEventListener('input', function () {
      cfg.q = qEl.value;
      var at = qEl.selectionStart;
      renderWorkbench(cfg);
      var again = el(cfg.pane + 'Q');
      if (again) { again.focus(); again.setSelectionRange(at, at); }
    });
    var dEl = el(cfg.pane + 'D');
    if (dEl) dEl.addEventListener('change', function () { cfg.dist = dEl.value; renderWorkbench(cfg); });

    pane.querySelectorAll('[data-pick]').forEach(function (cb) {
      cb.addEventListener('change', function () {
        pick[cfg.pane][cb.getAttribute('data-pick')] = cb.checked;
        renderWorkbench(cfg);
      });
    });

    var clear = el(cfg.pane + 'Clear');
    if (clear) clear.addEventListener('click', function () { pick[cfg.pane] = {}; renderWorkbench(cfg); });

    var make = el(cfg.pane + 'Make');
    if (make) make.addEventListener('click', function () {
      var sel = Object.keys(pick[cfg.pane]).filter(function (k) { return pick[cfg.pane][k]; });
      if (sel.length < 2) return;
      var name = prompt(cfg.namePrompt, cfg.defaultName(sel));
      if (name === null) return;
      var body = { name: name };
      body[cfg.postField] = sel;
      api('POST', cfg.path, body).then(function () {
        pick[cfg.pane] = {};
        msg('Group created. ' + cfg.savedNote, 'ok');
        return reload();
      }).catch(fail);
    });

    var rescan = el(cfg.pane + 'Rescan');
    if (rescan) rescan.addEventListener('click', function () {
      api('POST', cfg.path + '/rescan', {}).then(function (r) {
        msg(r.created ? ('Created ' + r.created + ' group(s) from A/B station names.')
                      : 'No ungrouped A/B pairs were found — everything is already grouped.',
            r.created ? 'ok' : 'warn');
        return reload();
      }).catch(fail);
    });

    pane.querySelectorAll('[data-drop]').forEach(function (b) {
      b.addEventListener('click', function () {
        var name = b.getAttribute('data-drop'), gid = b.getAttribute('data-grp');
        api('DELETE', cfg.path + '/' + gid + '/members?' + cfg.dropParam + '=' + encodeURIComponent(name))
          .then(function () {
            msg(esc(name) + ' removed from the group. ' + cfg.savedNote, 'ok');
            return reload();
          }).catch(fail);
      });
    });

    pane.querySelectorAll('[data-del]').forEach(function (b) {
      b.addEventListener('click', function () {
        if (!confirm('Delete this group? Its members go back to being counted separately, '
                   + 'which changes the published figure.')) return;
        api('DELETE', cfg.path + '/' + b.getAttribute('data-del')).then(function () {
          msg('Group deleted. ' + cfg.savedNote, 'ok');
          return reload();
        }).catch(fail);
      });
    });

    pane.querySelectorAll('[data-rename]').forEach(function (b) {
      b.addEventListener('click', function () {
        var gid = b.getAttribute('data-rename');
        var g = (D[cfg.dataKey].groups || []).filter(function (x) { return String(x.id) === gid; })[0];
        var name = prompt('Name for this group', g ? g.name : '');
        if (name === null) return;
        api('PUT', cfg.path + '/' + gid, { name: name, note: g ? g.note : null })
          .then(function () { msg('Renamed.', 'ok'); return reload(); }).catch(fail);
      });
    });

    if (window.RoleGate && window.RoleGate.me) applyRole();
  }

  /* Re-hide the admin-only controls after each redraw — role-gate.js runs once
     on load, and everything above is built later. */
  function applyRole() {
    var me = (window.RoleGate && window.RoleGate.me) || {};
    var admin = me.role === 'ADMIN' || me.role === 'SUPER_ADMIN';
    if (admin) return;
    document.querySelectorAll('[data-requires="admin"]').forEach(function (n) { n.style.display = 'none'; });
  }

  var CW = {
    pane: 'carriageway', dataKey: 'carriageway', path: '/carriageway',
    idField: 'section_label', memberField: 'section_label', postField: 'sections', dropParam: 'section',
    leftTitle: 'Dual carriageway sections',
    searchHint: 'Search section label, road or district…',
    emptyLeft: 'No sections match.',
    savedNote: 'The road network length has been recalculated.',
    namePrompt: 'Name for this carriageway group (the road stretch it represents)',
    blurb: 'A dual road is drawn as two centrelines that <b>each carry the full length of the same stretch</b>, '
         + 'so adding them up counts the road twice. Group the centrelines that are one stretch and the group is '
         + 'counted once, at the average of its members’ measured lengths. Only sections whose Carriageway '
         + 'attribute is <b>Dual</b> are listed — if one is missing from this list, its attribute is wrong in '
         + 'the road data and that is where it should be fixed.',
    footNote: 'A section label may belong to <b>one</b> group only — an already-grouped section is greyed out '
            + 'with the group holding it, and the database refuses a second. Deleting a group does not touch the '
            + 'road data; the sections simply go back to being counted separately.',
    candSub: function (c) {
      return (c.road_name || '(unnamed)') + (c.district ? ' · ' + c.district : '')
           + (c.road_class ? ' · ' + c.road_class : '');
    },
    candRight: function (c) { return c.length_m == null ? '–' : num(c.length_m / 1000, 3) + ' km'; },
    groupSub: function (g) {
      return (g.members || []).length + ' sections · counted as <b>' + num((g.corrected_m || 0) / 1000, 3)
           + ' km</b> <span style="opacity:.6">(uncorrected ' + num((g.raw_m || 0) / 1000, 3) + ' km)</span>';
    },
    memberMissing: function (m) { return !!m.missing; },
    memberRight: function (m) {
      if (m.missing) return 'not in the road data';
      return (m.length_m == null ? '–' : num(m.length_m / 1000, 3) + ' km');
    },
    defaultName: function (sel) {
      // Offer the shared prefix of the picked labels — usually the stretch itself.
      var a = sel[0], n = a.length;
      sel.forEach(function (s) {
        var i = 0;
        while (i < n && i < s.length && s[i] === a[i]) i++;
        n = i;
      });
      return (a.slice(0, n).replace(/[\/\-_\s]+$/, '') || a);
    }
  };

  var STN = {
    pane: 'stations', dataKey: 'stations', path: '/stations',
    idField: 'station_name', memberField: 'station_name', postField: 'stations', dropParam: 'station',
    rescan: true,
    leftTitle: 'Traffic count stations',
    searchHint: 'Search station name or section…',
    emptyLeft: 'No stations match — or no traffic survey has been imported yet.',
    savedNote: 'The traffic station count has been recalculated.',
    namePrompt: 'Name for this station group (the physical station it represents)',
    blurb: 'The two carriageways of one physical count station are stored as two rows — TVM_STN_021A and '
         + 'TVM_STN_021B. Group them and every dashboard counts <b>one</b> station, merging both rows’ counts, '
         + 'class mix and hourly profile into it. A station in no group counts as itself, so a fresh import is '
         + 'never lost — use <b>Scan for A/B pairs</b> to fold new ones in.',
    footNote: 'A station name may belong to <b>one</b> group only. The group’s name is what the dashboards '
            + 'label the merged station with.',
    candSub: function (c) { return c.section ? 'section ' + c.section : '(no section)'; },
    candRight: function (c) { return c.rows_in_data + ' row' + (c.rows_in_data === 1 ? '' : 's'); },
    groupSub: function (g) { return (g.members || []).length + ' station rows → counted as <b>1 station</b>'; },
    memberMissing: function (m) { return !m.rows_in_data; },
    memberRight: function (m) {
      return m.rows_in_data ? (m.rows_in_data + ' rows') : 'not in the survey data';
    },
    defaultName: function (sel) { return sel[0].replace(/([0-9])[ABab]$/, '$1'); }
  };

  /* ==================================================================
     3. Pavement width bands
     ================================================================== */

  function renderWidth() {
    var w = D.width;
    var bands = w.bands || [];
    var def = w.default_bands || {};
    el('pane-width').innerHTML =
      fxStrip(w.effect) +
      usePanel(w.used_by) +
      '<div class="sec-d">The carriageway width, in metres, that each <b>Pavement_W</b> band code stands for. '
      + 'This is what a PCI ranking weights by — a road’s weight is its length × this width — so a '
      + 'wide long stretch pulls a road average more than a short narrow one. It is not used for network length.</div>' +
      '<div class="tbl-wrap"><table><thead><tr>' +
        '<th>Band code</th><th class="num">Width (m)</th><th class="num">Shipped default</th>'
        + '<th class="num">Road sections</th><th class="act" data-requires="admin"></th>' +
      '</tr></thead><tbody>' +
        bands.map(function (b) {
          var d = def[b.code];
          var changed = d != null && Math.abs(+b.width_m - +d) > 1e-9;
          return '<tr>' +
            '<td><b>' + esc(b.code) + '</b>' + (b.note ? ' <span class="dflt">' + esc(b.note) + '</span>' : '') + '</td>' +
            '<td class="num"><input class="cell" type="number" step="0.05" min="0.1" value="' + esc(b.width_m) +
              '" data-band="' + esc(b.code) + '" data-requires="admin">' +
              '<span class="wro" data-ro="' + esc(b.code) + '">' + num(b.width_m, 2) + '</span></td>' +
            '<td class="num ' + (changed ? 'edited' : 'dflt') + '">' + (d == null ? '–' : num(d, 2)) + '</td>' +
            '<td class="num dflt">–</td>' +
            '<td class="act" data-requires="admin">' +
              '<button class="ic del" data-delband="' + esc(b.code) + '" title="Delete this band">✕</button>' +
            '</td></tr>';
        }).join('') +
      '</tbody></table></div>' +
      '<div class="bar" data-requires="admin">' +
        '<button class="btn" id="wSave">Save widths</button>' +
        '<button class="btn ghost sm" id="wAdd">Add a band</button>' +
      '</div>' +
      '<div class="sec-h">Fallbacks</div>' +
      '<div class="sec-d">What to use when a section carries no band code, and how much of the banded width '
      + 'belongs to <b>one</b> centreline of a dual carriageway — the band describes the whole road, so each of '
      + 'its two centrelines takes this share of it (0.5 = half each).</div>' +
      '<div class="tbl-wrap"><table><tbody>' +
        '<tr><td>Width when the band code is missing</td>' +
          '<td class="num"><input class="cell" type="number" step="0.1" min="0.1" id="wDef" value="'
            + esc(w.default_m) + '" data-requires="admin"> m</td>' +
          '<td class="num dflt">default ' + num(w.factory_default_m, 2) + ' m</td></tr>' +
        '<tr><td>Share of the band width per dual carriageway centreline</td>' +
          '<td class="num"><input class="cell" type="number" step="0.05" min="0.01" max="1" id="wDual" value="'
            + esc(w.dual_factor) + '" data-requires="admin"></td>' +
          '<td class="num dflt">default ' + num(w.factory_dual_factor, 2) + '</td></tr>' +
      '</tbody></table></div>' +
      '<div class="bar" data-requires="admin"><button class="btn" id="wSaveScalars">Save fallbacks</button></div>';

    // Hide the read-only mirror of each editable cell for admins; show it for others.
    document.querySelectorAll('.wro').forEach(function (s) { s.style.display = 'none'; });

    el('wSave').addEventListener('click', function () {
      var inputs = Array.prototype.slice.call(document.querySelectorAll('[data-band]'));
      var chain = Promise.resolve();
      inputs.forEach(function (i) {
        chain = chain.then(function () {
          return api('POST', '/width/band', { code: i.getAttribute('data-band'), width_m: i.value });
        });
      });
      chain.then(function () { msg('Width bands saved. Reopen the PCI report to see the new weighting.', 'ok'); return reload(); })
           .catch(fail);
    });

    el('wAdd').addEventListener('click', function () {
      var code = prompt('Band code as it appears in the Pavement_W column (e.g. 6)');
      if (!code) return;
      var m = prompt('Carriageway width for band ' + code + ', in metres');
      if (!m) return;
      api('POST', '/width/band', { code: code, width_m: m })
        .then(function () { msg('Band added.', 'ok'); return reload(); }).catch(fail);
    });

    document.querySelectorAll('[data-delband]').forEach(function (b) {
      b.addEventListener('click', function () {
        var code = b.getAttribute('data-delband');
        if (!confirm('Delete band ' + code + '? Sections carrying that code fall back to the default width.')) return;
        api('DELETE', '/width/band?code=' + encodeURIComponent(code))
          .then(function () { msg('Band deleted.', 'ok'); return reload(); }).catch(fail);
      });
    });

    el('wSaveScalars').addEventListener('click', function () {
      api('POST', '/width/scalars', { default_m: el('wDef').value, dual_factor: el('wDual').value })
        .then(function () { msg('Fallbacks saved.', 'ok'); return reload(); }).catch(fail);
    });
  }

  /* ==================================================================
     4. PCI weights and thresholds
     ================================================================== */

  function renderPci() {
    var p = D.pci;
    var params = p.params || [];
    var sumOff = Math.abs((p.weight_sum || 0) - 1) > 0.005;

    el('pane-pci').innerHTML =
      usePanel(p.used_by) +
      '<div class="sec-d">The IRC:82&#8209;2023 numbers behind every PCI value: each distress’s <b>weight</b> in '
      + 'the index, and the <b>Good</b> and <b>Poor</b> thresholds that turn a raw measurement into a 0–100 '
      + 'score (100 down to 80 up to Good, 80 down to 40 up to Poor, 40 down to 0 up to twice Poor, then 0).</div>' +
      '<div class="warnbox"><b>Changing these makes the stored PCI stale.</b> The PCI held on every condition '
      + 'segment was computed with the previous numbers; the map and the report will not agree with it until the '
      + 'condition segments are rebuilt. Tick <i>rebuild now</i> below and the save does it for you.</div>' +
      '<div class="tbl-wrap"><table><thead><tr>' +
        '<th>Distress</th><th class="num">Weight</th><th class="num">Good below</th><th class="num">Poor above</th>'
        + '<th class="num">IRC default</th>' +
      '</tr></thead><tbody>' +
        params.map(function (r) {
          var chg = Math.abs(r.weight - r.default_weight) > 1e-9
                 || Math.abs(r.fair - r.default_fair) > 1e-9
                 || Math.abs(r.poor - r.default_poor) > 1e-9;
          return '<tr><td><b>' + esc(r.label) + '</b></td>' +
            '<td class="num"><input class="cell" type="number" step="0.01" min="0" max="1" value="' + esc(r.weight) +
              '" data-pci="' + esc(r.key) + '" data-f="weight" data-requires="admin"></td>' +
            '<td class="num"><input class="cell" type="number" step="0.05" min="0" value="' + esc(r.fair) +
              '" data-pci="' + esc(r.key) + '" data-f="fair" data-requires="admin"></td>' +
            '<td class="num"><input class="cell" type="number" step="0.05" min="0" value="' + esc(r.poor) +
              '" data-pci="' + esc(r.key) + '" data-f="poor" data-requires="admin"></td>' +
            '<td class="num ' + (chg ? 'edited' : 'dflt') + '">' + num(r.default_weight, 2) + ' · '
              + num(r.default_fair, 2) + ' · ' + num(r.default_poor, 2) + '</td></tr>';
        }).join('') +
      '</tbody></table></div>' +
      '<div class="bar">' +
        '<span class="' + (sumOff ? 'edited' : 'dflt') + '" id="pciSumTxt">Σ weights = <b>'
          + num(p.weight_sum, 2) + '</b>' + (sumOff ? ' — the weights do not add to 1' : '') + '</span>' +
      '</div>' +
      '<div class="bar" data-requires="admin">' +
        '<label class="dflt" style="display:flex;align-items:center;gap:7px;cursor:pointer">'
          + '<input type="checkbox" id="pciRebuild" checked style="accent-color:var(--teal)"> '
          + 'rebuild the condition segments after saving</label>' +
        '<button class="btn" id="pciSave">Save PCI numbers</button>' +
        '<button class="btn ghost sm" id="pciReset"' + (p.at_default ? ' disabled' : '') + '>Reset to IRC defaults</button>' +
      '</div>' +
      '<div class="note">The weights need not add to exactly 1 — PCI divides by the weights it actually used, '
      + 'so a distress missing from a survey does not drag the score down. A sum well away from 1 is usually a '
      + 'typing slip, which is why it is flagged.</div>';

    function collect() {
      var by = {};
      document.querySelectorAll('[data-pci]').forEach(function (i) {
        var k = i.getAttribute('data-pci');
        (by[k] = by[k] || { key: k })[i.getAttribute('data-f')] = i.value;
      });
      return Object.keys(by).map(function (k) { return by[k]; });
    }

    document.querySelectorAll('[data-pci][data-f="weight"]').forEach(function (i) {
      i.addEventListener('input', function () {
        var s = 0;
        document.querySelectorAll('[data-pci][data-f="weight"]').forEach(function (x) { s += +x.value || 0; });
        var off = Math.abs(s - 1) > 0.005;
        var t = el('pciSumTxt');
        t.className = off ? 'edited' : 'dflt';
        t.innerHTML = 'Σ weights = <b>' + num(s, 2) + '</b>' + (off ? ' — the weights do not add to 1' : '');
      });
    });

    el('pciSave').addEventListener('click', function () {
      api('POST', '/pci', { params: collect(), rebuild: el('pciRebuild').checked })
        .then(function (r) { msg(esc(r.message), r.rebuild_required ? 'warn' : 'ok'); return reload(); })
        .catch(fail);
    });
    el('pciReset').addEventListener('click', function () {
      if (!confirm('Put every weight and threshold back to the IRC:82-2023 defaults?')) return;
      api('POST', '/pci/reset', { rebuild: el('pciRebuild').checked })
        .then(function (r) { msg(esc(r.message), r.rebuild_required ? 'warn' : 'ok'); return reload(); })
        .catch(fail);
    });
  }

  /* ---------- draw everything ---------- */

  function render() {
    renderTabs();
    renderWorkbench(CW);
    renderWorkbench(STN);
    renderWidth();
    renderPci();
    showTab(tab);
    applyRole();
  }

  el('tabs').addEventListener('click', function (e) {
    var b = e.target.closest('[data-tab]');
    if (b) showTab(b.getAttribute('data-tab'));
  });

  if (window.RoleGate) window.RoleGate.onReady = applyRole;

  reload().catch(function (e) {
    el('pane-carriageway').classList.add('on');
    el('pane-carriageway').innerHTML = '<div class="empty">The calculation rules could not be loaded.</div>';
    fail(e);
  });
})();
