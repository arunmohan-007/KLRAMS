/* ============================================================
   KLRAMS viewer · 44-monitor-telemetry.js
   Reports GIS layer load performance to the Monitoring & Health dashboard
   (POST /api/monitor/layer-metric). SUPER_ADMIN sessions only — the
   endpoint itself is SUPER_ADMIN-gated in SecurityConfig, and this file
   never calls it for a USER/ADMIN session, so ordinary staff traffic is
   completely unaffected (see the note on map-tabs.js's 'kl-role-ready').

   Two sources of timing:
   - MapLibre source dataloading/data/error events cover every vector-tile
     and raster-tile layer (roadnet, condition segments, assets, IRI,
     traffic stations, full network, drone tiles, user layers…) without
     having to instrument each of the ~16 modules that load them.
   - A window.fetch wrapper covers the plain GeoJSON/JSON loaders (roads
     index, boundaries, etc.) that do not go through a MapLibre source.

   Sends are throttled per layer (min 4s apart) so a fast pan/zoom does not
   flood the endpoint with one row per tile.
   ============================================================ */
(function(){
  'use strict';

  var enabled = false;
  var lastSent = Object.create(null);
  var THROTTLE_MS = 4000;

  function post(layer, durationMs, status, featureCount){
    if(!enabled) return;
    var now = Date.now();
    var key = layer + ':' + status;
    if(lastSent[key] && now - lastSent[key] < THROTTLE_MS) return;
    lastSent[key] = now;
    var body = {layer: layer, durationMs: Math.max(0, Math.round(durationMs)), status: status};
    if(featureCount != null) body.featureCount = featureCount;
    fetch('/api/monitor/layer-metric', {
      method: 'POST', credentials: 'same-origin',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(body)
    }).catch(function(){ /* telemetry — never surface a failure to the user */ });
  }

  function wireMapSourceEvents(){
    if(typeof map === 'undefined' || !map || !map.on) return;
    var starts = Object.create(null);

    map.on('dataloading', function(e){
      if(!e.sourceId) return;
      starts[e.sourceId] = performance.now();
    });
    map.on('sourcedataloading', function(e){
      if(!e.sourceId) return;
      if(starts[e.sourceId] == null) starts[e.sourceId] = performance.now();
    });
    map.on('data', function(e){
      if(!e.sourceId || e.dataType !== 'source') return;
      if(!map.isSourceLoaded || !map.isSourceLoaded(e.sourceId)) return;
      var t0 = starts[e.sourceId];
      if(t0 == null) return;
      delete starts[e.sourceId];
      post(e.sourceId, performance.now() - t0, 'SUCCESS', null);
    });
    map.on('error', function(e){
      var sid = e && e.sourceId;
      if(!sid) return;
      var t0 = starts[sid];
      delete starts[sid];
      post(sid, t0 != null ? performance.now() - t0 : 0, 'FAILED', null);
    });
  }

  // GeoJSON/JSON loaders that bypass MapLibre sources entirely.
  var FETCH_LAYER_PATTERNS = [
    [/\/api\/roads(\?|$)/, 'roads-geojson'],
    [/\/api\/roads\/index/, 'roads-index'],
    [/\/api\/segments(\?|$)/, 'condition-segments'],
    [/\/api\/boundary/, 'boundary'],
    [/\/api\/assets(\?|$)/, 'assets-geojson'],
    [/\/api\/traffic\/stations/, 'traffic-stations'],
    [/\/api\/full-network(\?|$)/, 'full-network-geojson']
  ];

  function layerForUrl(url){
    for(var i=0;i<FETCH_LAYER_PATTERNS.length;i++){
      if(FETCH_LAYER_PATTERNS[i][0].test(url)) return FETCH_LAYER_PATTERNS[i][1];
    }
    return null;
  }

  function wireFetchWrapper(){
    var origFetch = window.fetch;
    window.fetch = function(input, init){
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      var layer = layerForUrl(url);
      if(!layer) return origFetch.apply(window, arguments);
      var t0 = performance.now();
      return origFetch.apply(window, arguments).then(function(resp){
        post(layer, performance.now() - t0, resp.ok ? 'SUCCESS' : 'FAILED', null);
        return resp;
      }, function(err){
        post(layer, performance.now() - t0, 'FAILED', null);
        throw err;
      });
    };
  }

  function activate(){
    if(enabled) return;
    enabled = true;
    wireMapSourceEvents();
    wireFetchWrapper();
  }

  if(window.__klRole === 'SUPER_ADMIN') activate();
  document.addEventListener('kl-role-ready', function(e){
    if(e.detail === 'SUPER_ADMIN') activate();
  });
})();
