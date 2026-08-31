/*
 * drone-console.js — Drone Dashboard, Drone Projects and Upload Drone Data.
 *
 * Rows are built with createElement/textContent and listeners are attached in
 * JavaScript. Nothing user-supplied — a project name, a file name, a status
 * message from a failed build — is ever concatenated into innerHTML or into an
 * onclick attribute, which is the rule the rest of the console pages follow.
 */
(function () {
  'use strict';

  var msgEl = document.getElementById('msg');
  var projects = [];
  var pollTimer = null;

  /* ---------------- small helpers ---------------- */

  function say(text, kind) {
    msgEl.className = 'msg ' + (kind || 'ok');
    msgEl.textContent = text;
    if (kind !== 'err') setTimeout(function () { if (msgEl.textContent === text) msgEl.className = 'msg'; }, 6000);
  }

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  function api(url, opts) {
    return fetch(url, Object.assign({ credentials: 'same-origin' }, opts || {}))
      .then(function (r) {
        if (r.status === 403) throw new Error('Your account is not allowed to do that.');
        return r.json();
      })
      .then(function (j) {
        if (j && j.ok === false) throw new Error(j.error || 'The request failed.');
        return j;
      });
  }

  function jsonPost(url, body, method) {
    return api(url, {
      method: method || 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
  }

  function fmtBytes(n) {
    if (n == null) return '—';
    var u = ['B', 'KB', 'MB', 'GB', 'TB'], i = 0, v = Number(n);
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
    return (i === 0 ? v : v.toFixed(v < 10 ? 2 : 1)) + ' ' + u[i];
  }

  function fmtDate(s) {
    if (!s) return '—';
    var d = new Date(s);
    if (isNaN(d)) return String(s).slice(0, 10);
    return String(d.getDate()).padStart(2, '0') + '-' +
           String(d.getMonth() + 1).padStart(2, '0') + '-' + d.getFullYear();
  }

  function num(v, digits) {
    return v == null ? '—' : Number(v).toFixed(digits == null ? 2 : digits);
  }

  /** A status pill. Class drives colour; text is the human word for the state. */
  function statusPill(status, published) {
    if (status === 'PUBLISHED' && published) return el('span', 'pill pub', 'Published');
    if (status === 'PROCESSING') return el('span', 'pill proc', 'Processing');
    if (status === 'FAILED') return el('span', 'pill fail', 'Failed');
    return el('span', 'pill up', 'Uploaded');
  }

  function isAdmin() {
    var me = window.RoleGate && window.RoleGate.me;
    return !!me && (me.role === 'ADMIN' || me.role === 'SUPER_ADMIN');
  }

  /* ---------------- tabs ---------------- */

  document.querySelectorAll('.tab').forEach(function (t) {
    t.addEventListener('click', function () {
      document.querySelectorAll('.tab').forEach(function (x) { x.classList.remove('on'); });
      document.querySelectorAll('.pane').forEach(function (x) { x.classList.remove('on'); });
      t.classList.add('on');
      document.getElementById('pane-' + t.dataset.pane).classList.add('on');
    });
  });

  /* ---------------- dashboard ---------------- */

  var STATS = [
    ['projects', 'Drone Projects', 'vio', 'Survey flights recorded.'],
    ['orthomosaics', 'Orthomosaics', 'info', 'Drone images uploaded.'],
    ['dems', 'DEMs', 'info', 'Elevation models uploaded.'],
    ['published', 'Published', 'ok', 'Drawn on the Drone Viewer.'],
    ['processing', 'Processing', 'warn', 'Tile build running or queued.'],
    ['failed', 'Failed', 'bad', 'Tile build did not complete.']
  ];

  function loadDashboard() {
    return api('/api/drone/summary').then(function (s) {
      var box = document.getElementById('stats');
      box.textContent = '';
      STATS.forEach(function (def) {
        var card = el('div', 'stat ' + def[2]);
        card.appendChild(el('div', 'l', def[1]));
        card.appendChild(el('div', 'v', String(s[def[0]] != null ? s[def[0]] : 0)));
        card.appendChild(el('div', 'd', def[3]));
        box.appendChild(card);
      });
      var storage = el('div', 'stat');
      storage.appendChild(el('div', 'l', 'Stored imagery'));
      storage.appendChild(el('div', 'v', fmtBytes(s.total_bytes)));
      storage.appendChild(el('div', 'd', 'Original GeoTIFFs on disk, tiles excluded.'));
      box.appendChild(storage);
    });
  }

  function loadRecent() {
    return api('/api/drone/datasets').then(function (rows) {
      var t = document.getElementById('recent');
      t.textContent = '';
      if (!rows.length) {
        t.appendChild(el('tbody')).appendChild(el('tr')).appendChild(el('td', 'empty',
          'No drone data uploaded yet.'));
        return;
      }
      var head = el('thead'), hr = el('tr');
      ['Dataset', 'Type', 'Project', 'Size', 'Resolution', 'CRS', 'Uploaded', 'Status'].forEach(function (h) {
        hr.appendChild(el('th', null, h));
      });
      head.appendChild(hr); t.appendChild(head);

      var body = el('tbody');
      rows.slice(0, 12).forEach(function (d) {
        var tr = el('tr');
        tr.appendChild(el('td', null, d.dataset_name));
        tr.appendChild(el('td', null, d.dataset_type === 'DEM' ? 'DEM' : 'Orthomosaic'));
        var pc = el('td'); pc.appendChild(el('span', 'code', d.project_code)); tr.appendChild(pc);
        tr.appendChild(el('td', null, fmtBytes(d.file_size)));
        tr.appendChild(el('td', null, d.res_x == null ? '—' : num(d.res_x, 3) + (d.epsg === 4326 ? '°' : ' m')));
        tr.appendChild(el('td', null, 'EPSG:' + (d.epsg == null ? '—' : d.epsg)));
        tr.appendChild(el('td', null, fmtDate(d.created_at)));
        var st = el('td'); st.appendChild(statusPill(d.status, d.published));
        if (d.status === 'FAILED' && d.status_message) st.title = d.status_message;
        tr.appendChild(st);
        body.appendChild(tr);
      });
      t.appendChild(body);
    });
  }

  /* ---------------- projects ---------------- */

  function loadProjects() {
    return api('/api/drone/projects').then(function (rows) {
      projects = rows;
      renderProjects();
      fillProjectPicker();
      schedulePoll();
    });
  }

  function datasetsOf(p) {
    try { return JSON.parse(p.datasets || '[]'); } catch (e) { return []; }
  }

  function renderProjects() {
    var t = document.getElementById('projects');
    t.textContent = '';
    if (!projects.length) {
      t.appendChild(el('tbody')).appendChild(el('tr')).appendChild(el('td', 'empty',
        'No drone projects yet. Create one, then upload its orthomosaic or DEM.'));
      return;
    }

    var head = el('thead'), hr = el('tr');
    ['Project', 'Name', 'Road / Location', 'Survey Date', 'Orthomosaic', 'DEM', 'Action'].forEach(function (h) {
      hr.appendChild(el('th', null, h));
    });
    head.appendChild(hr); t.appendChild(head);

    var body = el('tbody');
    projects.forEach(function (p) {
      var sets = datasetsOf(p);
      var ortho = sets.filter(function (d) { return d.type === 'ORTHOMOSAIC'; })[0];
      var dem = sets.filter(function (d) { return d.type === 'DEM'; })[0];

      var tr = el('tr');
      var c = el('td'); c.appendChild(el('span', 'code', p.project_code)); tr.appendChild(c);
      tr.appendChild(el('td', null, p.project_name));
      tr.appendChild(el('td', null, [p.road_section, p.location].filter(Boolean).join(' · ') || '—'));
      tr.appendChild(el('td', null, fmtDate(p.survey_date)));
      tr.appendChild(datasetCell(ortho));
      tr.appendChild(datasetCell(dem));
      tr.appendChild(projectActions(p, sets));
      body.appendChild(tr);
    });
    t.appendChild(body);
  }

  function datasetCell(d) {
    var td = el('td');
    if (!d) { td.appendChild(el('span', 'pill none', 'Not uploaded')); return td; }
    td.appendChild(statusPill(d.status, d.published));
    return td;
  }

  function projectActions(p, sets) {
    var td = el('td', 'acts');

    var view = el('button', 'btn ghost sm', 'View');
    view.addEventListener('click', function () {
      location.href = '/drone-viewer.html?project=' + encodeURIComponent(p.id);
    });
    td.appendChild(view);

    if (!isAdmin()) return td;

    var edit = el('button', 'btn ghost sm', 'Edit');
    edit.addEventListener('click', function () { openForm(p); });
    td.appendChild(edit);

    sets.forEach(function (d) {
      var label = d.type === 'DEM' ? 'DEM' : 'Ortho';
      if (d.published) {
        var un = el('button', 'btn ghost sm', 'Unpublish ' + label);
        un.addEventListener('click', function () { act(un, '/api/drone/datasets/' + d.id + '/unpublish'); });
        td.appendChild(un);
      } else if (d.status !== 'PROCESSING') {
        var pub = el('button', 'btn good sm', 'Publish ' + label);
        pub.addEventListener('click', function () { act(pub, '/api/drone/datasets/' + d.id + '/publish'); });
        td.appendChild(pub);
      }
      var del = el('button', 'btn danger sm', 'Delete ' + label);
      del.addEventListener('click', function () {
        if (!confirm('Delete the ' + label + ' "' + d.name + '"? The uploaded file and its tiles are removed.')) return;
        act(del, '/api/drone/datasets/' + d.id, 'DELETE');
      });
      td.appendChild(del);
    });

    var dp = el('button', 'btn danger sm', 'Delete project');
    dp.addEventListener('click', function () {
      if (!confirm('Delete project "' + p.project_code + '" and everything uploaded into it?')) return;
      act(dp, '/api/drone/projects/' + p.id, 'DELETE');
    });
    td.appendChild(dp);
    return td;
  }

  function act(button, url, method) {
    button.disabled = true;
    api(url, { method: method || 'POST' })
      .then(function () { return refreshAll(); })
      .then(function () { say('Done.', 'ok'); })
      .catch(function (e) { say(e.message, 'err'); })
      .finally(function () { button.disabled = false; });
  }

  /* ---------------- project form ---------------- */

  var form = document.getElementById('project-form');
  var F = {
    id: document.getElementById('p-id'), code: document.getElementById('p-code'),
    name: document.getElementById('p-name'), date: document.getElementById('p-date'),
    road: document.getElementById('p-road'), location: document.getElementById('p-location'),
    sec: document.getElementById('p-sec'), desc: document.getElementById('p-desc')
  };

  function openForm(p) {
    F.id.value = p ? p.id : '';
    F.code.value = p ? (p.project_code || '') : '';
    F.name.value = p ? (p.project_name || '') : '';
    F.date.value = p && p.survey_date ? String(p.survey_date).slice(0, 10) : '';
    F.road.value = p ? (p.road_section || '') : '';
    F.location.value = p ? (p.location || '') : '';
    F.sec.value = p ? (p.pwd_section || '') : '';
    F.desc.value = p ? (p.description || '') : '';
    form.style.display = '';
    F.code.focus();
  }

  document.getElementById('new-project').addEventListener('click', function () { openForm(null); });
  document.getElementById('p-cancel').addEventListener('click', function () { form.style.display = 'none'; });

  document.getElementById('p-save').addEventListener('click', function () {
    var body = {
      project_code: F.code.value, project_name: F.name.value, survey_date: F.date.value,
      road_section: F.road.value, location: F.location.value, pwd_section: F.sec.value,
      description: F.desc.value
    };
    var id = F.id.value;
    var btn = this;
    btn.disabled = true;
    jsonPost(id ? '/api/drone/projects/' + id : '/api/drone/projects', body, id ? 'PUT' : 'POST')
      .then(function () {
        form.style.display = 'none';
        say(id ? 'Project updated.' : 'Project created.', 'ok');
        return refreshAll();
      })
      .catch(function (e) { say(e.message, 'err'); })
      .finally(function () { btn.disabled = false; });
  });

  /* ---------------- upload ---------------- */

  function fillProjectPicker() {
    var sel = document.getElementById('u-project');
    var keep = sel.value;
    sel.textContent = '';
    if (!projects.length) {
      sel.appendChild(el('option', null, 'Create a project first'));
      sel.disabled = true;
      return;
    }
    sel.disabled = false;
    projects.forEach(function (p) {
      var o = el('option', null, p.project_code + ' — ' + p.project_name);
      o.value = p.id;
      sel.appendChild(o);
    });
    if (keep) sel.value = keep;
  }

  var lastUploadedId = null;

  document.getElementById('u-go').addEventListener('click', function () {
    var file = document.getElementById('u-file').files[0];
    var projectId = document.getElementById('u-project').value;
    var status = document.getElementById('u-status');
    if (!projectId) { say('Create a drone project before uploading.', 'err'); return; }
    if (!file) { say('Choose a GeoTIFF file.', 'err'); return; }

    var fd = new FormData();
    fd.append('project_id', projectId);
    fd.append('dataset_type', document.getElementById('u-type').value);
    fd.append('dataset_name', document.getElementById('u-name').value);
    fd.append('file', file);

    var btn = this;
    btn.disabled = true;
    status.textContent = 'Uploading ' + fmtBytes(file.size) + '…';
    document.getElementById('u-meta').classList.remove('on');

    api('/api/drone/datasets', { method: 'POST', body: fd })
      .then(function (res) {
        lastUploadedId = res.id;
        status.textContent = 'Uploaded.';
        showMeta(res.dataset);
        say('Upload accepted. Review the metadata, then publish it to the map.', 'ok');
        return refreshAll();
      })
      .catch(function (e) { status.textContent = ''; say(e.message, 'err'); })
      .finally(function () { btn.disabled = false; });
  });

  function showMeta(d) {
    var g = document.getElementById('u-meta-g');
    g.textContent = '';
    var degrees = d.epsg === 4326;
    var rows = [
      ['File name', d.file_name],
      ['File size', fmtBytes(d.file_size)],
      ['Dataset type', d.dataset_type === 'DEM' ? 'DEM' : 'Orthomosaic'],
      ['Coordinate system', d.crs_name],
      ['Raster size', d.raster_width + ' × ' + d.raster_height + ' px'],
      ['Resolution', num(d.res_x, degrees ? 8 : 3) + ' × ' + num(d.res_y, degrees ? 8 : 3) +
        (degrees ? '°' : ' m') + ' per pixel'],
      ['Pixel format', d.format],
      ['Bounding box', num(d.min_x, 6) + ', ' + num(d.min_y, 6) + '  →  ' +
        num(d.max_x, 6) + ', ' + num(d.max_y, 6)],
      ['Upload date', fmtDate(d.created_at)],
      ['Processing status', d.status === 'UPLOADED' ? 'Uploaded — not yet published' : d.status]
    ];
    if (d.dataset_type === 'DEM')
      rows.splice(8, 0, ['Elevation range', num(d.elevation_min, 2) + ' m — ' + num(d.elevation_max, 2) + ' m']);

    rows.forEach(function (r) {
      var box = el('div', 'meta-r');
      box.appendChild(el('div', 'k', r[0]));
      box.appendChild(el('div', 'v', r[1] == null ? '—' : String(r[1])));
      g.appendChild(box);
    });
    document.getElementById('u-meta').classList.add('on');
  }

  document.getElementById('u-publish').addEventListener('click', function () {
    if (!lastUploadedId) return;
    act(this, '/api/drone/datasets/' + lastUploadedId + '/publish');
  });

  /* ---------------- road suggestions ---------------- */

  function loadRoadNames() {
    // The road index is metadata only (no geometry) and already cached server-side,
    // so it is cheap enough to use purely as a spelling aid for the Road field.
    return fetch('/api/roads/index', { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (rows) {
        var list = document.getElementById('road-list');
        var seen = Object.create(null);
        rows.forEach(function (r) {
          var name = r.Road_Name || r.name;
          if (!name || seen[name]) return;
          seen[name] = 1;
          var o = document.createElement('option');
          o.value = name;
          list.appendChild(o);
        });
      })
      .catch(function () { /* the field still accepts free text */ });
  }

  /* ---------------- refresh + polling ---------------- */

  function refreshAll() {
    return Promise.all([loadDashboard(), loadRecent(), api('/api/drone/projects').then(function (rows) {
      projects = rows;
      renderProjects();
      fillProjectPicker();
    })]).then(schedulePoll);
  }

  /**
   * A tile build has no callback, so the page re-reads the list while anything is
   * PROCESSING and stops once nothing is. Polling only while there is a build in
   * flight keeps an idle console silent.
   */
  function schedulePoll() {
    if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
    var busy = projects.some(function (p) {
      return datasetsOf(p).some(function (d) { return d.status === 'PROCESSING'; });
    });
    if (busy) pollTimer = setTimeout(refreshAll, 4000);
  }

  window.RoleGate = window.RoleGate || {};
  window.RoleGate.onReady = function () { refreshAll(); };

  loadDashboard();
  loadRecent();
  loadProjects();
  loadRoadNames();
})();
