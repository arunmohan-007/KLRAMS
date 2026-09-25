/*
 * console-user-layers-raster.js — the Data Console's importer for raster
 * temporary layers: GeoTIFF, or a JPG/PNG with a matching world file
 * (.jgw/.pgw/.wld).
 *
 * Deliberately a separate module from console-user-layers.js rather than a
 * branch inside it: every function over there (read(), fromGeoJson(),
 * fromCsv(), createTemp(), the column-mapping step) is shaped around a file
 * parsed client-side into GeoJSON, which a raster never is — it goes to the
 * server as real bytes and comes back as a tile pyramid built asynchronously.
 * Folding that in would mean an `if (raster)` in most of that file's
 * functions; this stays small and self-contained instead.
 */
(function () {
  'use strict';

  var st = {
    layerId: null,
    kind: null,       // 'geotiff' | 'worldfile'
    polling: null
  };

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

  function out(html, ok) {
    var el = document.getElementById('urOut');
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
        if (!r.ok || d.ok === false) throw new Error((d && d.error) || 'Request failed');
        return d;
      });
    });
  }

  /**
   * Upload with a progress percentage.
   *
   * fetch() carries no upload-progress event at all — only XMLHttpRequest
   * exposes `upload.onprogress` — which is why this isn't just another
   * fetch() call like the rest of the module's requests. A large GeoTIFF can
   * take long enough on a slow connection that a static "Uploading…" reads as
   * a hang, so onProgress is called with 0-100 as bytes go out.
   */
  function upload(url, formData, onProgress) {
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest();
      xhr.open('POST', url, true);
      xhr.withCredentials = true;
      if (onProgress && xhr.upload) {
        xhr.upload.onprogress = function (e) {
          if (e.lengthComputable) onProgress(Math.round((e.loaded / e.total) * 100));
        };
      }
      xhr.onload = function () {
        var d = {};
        try { d = JSON.parse(xhr.responseText || '{}'); } catch (e) { /* non-JSON error body */ }
        if (xhr.status >= 200 && xhr.status < 300 && d.ok !== false) resolve(d);
        else reject(new Error(d.error || 'Upload failed'));
      };
      xhr.onerror = function () { reject(new Error('Upload failed — connection lost.')); };
      xhr.send(formData);
    });
  }

  /* ------------------------------------------------------------------
     Panel
     ------------------------------------------------------------------ */

  function panel() {
    return '' +
      '<div class="ip-title">Raster layer from a file</div>' +
      '<p class="ip-sub">GeoTIFF with its coordinate system embedded, or a plain JPG/PNG placed ' +
      'with a matching world file (<code>.jgw</code>/<code>.pgw</code>/<code>.wld</code>). The ' +
      'layer is created, the file uploaded and a web tile pyramid built — this can take a while ' +
      'for a large file.</p>' +
      '<div class="ip-field"><label class="ip-label">Layer name</label>' +
      '<input type="text" id="urName" placeholder="e.g. Site survey scan — March"></div>' +
      '<div class="ip-field"><label class="ip-label">File type</label>' +
      '<select id="urKind" data-change="ULR.pickKind">' +
      '<option value="geotiff">GeoTIFF (.tif / .tiff)</option>' +
      '<option value="worldfile">JPG/PNG + world file</option>' +
      '</select></div>' +
      '<div id="urFiles"></div>' +
      '<div class="ip-field"><button class="btn" data-act="ULR.run">Upload &amp; publish</button></div>' +
      '<div id="urStatus"></div>' +
      '<div class="out" id="urOut"></div>' +
      '<p class="hint">A raster temporary layer is visible only to you unless shared, appears in ' +
      'the map viewer under <b>My layers</b>, and can be discarded in one action. Clicking it on ' +
      'the map shows the pixel value(s) at that point.</p>';
  }

  function filesBlock() {
    var el = document.getElementById('urFiles');
    if (!el) return;
    if (st.kind === 'worldfile') {
      el.innerHTML =
        '<div class="ip-field"><label class="ip-label">Image (JPG or PNG)</label>' +
        '<input type="file" id="urImage" accept=".jpg,.jpeg,.png"></div>' +
        '<div class="ip-field"><label class="ip-label">World file</label>' +
        '<input type="file" id="urWorld" accept=".jgw,.pgw,.wld"></div>';
    } else {
      el.innerHTML =
        '<div class="ip-field"><label class="ip-label">GeoTIFF file</label>' +
        '<input type="file" id="urFile" accept=".tif,.tiff"></div>' +
        '<div class="ip-field"><label><input type="checkbox" id="urRampOn" data-change="ULR.toggleRamp"> ' +
        'Colour by value</label>' +
        '<div class="hint">Tick this for single-band continuous data — elevation, rainfall, population ' +
        'density, and the like — to draw it with a colour ramp instead of grey/RGB. Leave it off for a ' +
        'photo, scan or classification raster.</div></div>' +
        '<div id="urRampFields"></div>';
    }
  }

  function rampFields() {
    var el = document.getElementById('urRampFields');
    if (!el) return;
    var on = document.getElementById('urRampOn').checked;
    if (!on) { el.innerHTML = ''; return; }
    el.innerHTML =
      '<div class="ip-field"><label class="ip-label">Colour ramp</label>' +
      '<select id="urRamp">' +
      '<option value="ELEVATION">Elevation — green to red-brown</option>' +
      '<option value="BLUE">Rainfall / water — pale to deep blue</option>' +
      '<option value="HEAT">Density (population, traffic, etc.) — pale yellow to dark red</option>' +
      '</select></div>' +
      '<div class="ip-field"><label class="ip-label">Value label</label>' +
      '<input type="text" id="urValueLabel" placeholder="e.g. Elevation (m), Rainfall (mm), ' +
      'Population density (people/km²)"></div>' +
      '<div class="hint">Shown in the click popup on the map, in place of a generic "Value".</div>';
  }

  function toggleRamp() {
    rampFields();
  }

  function pickKind() {
    st.kind = document.getElementById('urKind').value;
    filesBlock();
  }

  /* ------------------------------------------------------------------
     Upload + publish + poll
     ------------------------------------------------------------------ */

  function run() {
    var name = (document.getElementById('urName').value || '').trim();
    if (!name) return out('Give the layer a name.', false);
    st.kind = document.getElementById('urKind').value;

    var fd = new FormData();
    var uploadUrlSuffix, files;
    if (st.kind === 'worldfile') {
      var imageEl = document.getElementById('urImage'), worldEl = document.getElementById('urWorld');
      var image = imageEl && imageEl.files[0], world = worldEl && worldEl.files[0];
      if (!image || !world) return out('Choose both the image and its world file.', false);
      fd.append('image', image);
      fd.append('worldfile', world);
      uploadUrlSuffix = 'worldfile';
    } else {
      var fileEl = document.getElementById('urFile');
      var file = fileEl && fileEl.files[0];
      if (!file) return out('Choose a GeoTIFF file.', false);
      fd.append('file', file);
      var rampOn = (document.getElementById('urRampOn') || {}).checked;
      if (rampOn) {
        fd.append('colourRamp', (document.getElementById('urRamp') || {}).value || 'ELEVATION');
        fd.append('valueLabel', (document.getElementById('urValueLabel') || {}).value || '');
      }
      uploadUrlSuffix = 'geotiff';
    }

    out('Creating layer…');
    resolveNetworkFolderId()
      .then(function (folderId) {
        return api('/api/layers', {
          name: name, folderId: folderId, geometryType: 'RASTER', temporary: true
        }, 'POST');
      })
      .then(function (d) {
        st.layerId = d.id;
        out('Uploading raster… 0%');
        return upload('/api/layer-data/' + st.layerId + '/raster/' + uploadUrlSuffix, fd,
          function (pct) { out('Uploading raster… ' + pct + '%'); });
      })
      .then(function (d) {
        if (d.raster && d.raster.warnings) out('Uploaded. ' + esc(d.raster.warnings) + ' Publishing…');
        else out('Uploaded. Building tiles…');
        return api('/api/layer-data/' + st.layerId + '/raster/publish', {}, 'POST');
      })
      .then(function () { poll(); })
      .catch(function (e) { out(esc(e.message), false); });
  }

  function poll() {
    if (st.polling) clearTimeout(st.polling);
    api('/api/layer-data/' + st.layerId + '/raster/status')
      .then(function (d) {
        var r = d.raster || {};
        var box = document.getElementById('urStatus');
        if (box) box.innerHTML = '<div class="hint">Status: ' + esc(r.status || '?') + '</div>';
        if (r.status === 'PUBLISHED') {
          out('&#10003; The raster layer is published. Open the map and switch it on under ' +
              '<b>My layers</b>.', true);
          if (window.KLUserLayers) KLUserLayers.refresh();
          return;
        }
        if (r.status === 'FAILED') {
          out('Tile build failed: ' + esc(r.statusMessage || 'unknown error'), false);
          return;
        }
        st.polling = setTimeout(poll, 2000);
      })
      .catch(function (e) { out(esc(e.message), false); });
  }

  /* ------------------------------------------------------------------
     Hook into the hub
     ------------------------------------------------------------------ */

  function show() {
    st.layerId = null; st.kind = 'geotiff';
    if (st.polling) { clearTimeout(st.polling); st.polling = null; }
    var body = document.getElementById('paramBody');
    body.innerHTML = panel();
    filesBlock();
  }

  window.ULR = { show: show, pickKind: pickKind, run: run, toggleRamp: toggleRamp };
})();
