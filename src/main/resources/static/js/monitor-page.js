/* Monitoring & Health dashboard (monitor.html).
   No inline script, no inline handlers — every control is wired here via
   addEventListener, which is what lets script-src drop 'unsafe-inline'
   (see the CSP note in SecurityConfig.java). */
(function(){
  'use strict';

  var REFRESH_MS = 7000;
  var timer = null;

  window.RoleGate = window.RoleGate || {};
  window.RoleGate.onReady = function(me){
    var r = (me && me.role) || 'USER';
    document.getElementById('rolechip').textContent =
      r === 'SUPER_ADMIN' ? 'Super Admin' : r === 'ADMIN' ? 'Admin' : 'User';
  };

  function esc(s){ return (s==null?'':String(s)).replace(/[&<>"]/g, function(c){
    return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]; }); }

  function fmtTime(s){ if(!s) return '—'; try{
    return new Date(s).toLocaleString('en-IN',{day:'2-digit',month:'short',hour:'2-digit',minute:'2-digit',second:'2-digit'});
  }catch(e){ return String(s); } }

  function num(v, d){ return (v==null || v==='') ? d : Number(v); }
  function fmtMs(v){ v = num(v, null); return v==null ? '—' : (v>=1000 ? (v/1000).toFixed(1)+'s' : Math.round(v)+' ms'); }
  function fmtPct(v){ v = num(v, null); return v==null ? '—' : v.toFixed(1)+'%'; }

  /* ---------- CSV export (client-side, mirrors login-report-page.js) ---------- */
  function exportCsv(filename, head, rows, pick){
    var lines = [head.join(',')];
    rows.forEach(function(r){
      lines.push(pick(r).map(function(c){
        var v = c==null ? '' : String(c);
        return /[",\n]/.test(v) ? '"'+v.replace(/"/g,'""')+'"' : v;
      }).join(','));
    });
    var blob = new Blob([lines.join('\r\n')], {type:'text/csv;charset=utf-8'});
    var a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = filename;
    document.body.appendChild(a); a.click(); document.body.removeChild(a);
    setTimeout(function(){ URL.revokeObjectURL(a.href); }, 1000);
  }

  /* ---------- hand-rolled SVG line chart (no external chart library, per CSP) ---------- */
  var PALETTE = ['#3b86e6','#19b277','#d3aa3e','#8a68e6','#e65b5b','#3f9aa3'];

  function lineChart(svg, series, opts){
    opts = opts || {};
    var W = 600, H = 180, padL = 4, padR = 4, padT = 8, padB = 8;
    var all = [].concat.apply([], series.map(function(s){ return s.points; }));
    if(!all.length){ svg.innerHTML = '<text x="300" y="94" fill="#5f6e86" font-size="12" text-anchor="middle">No data yet</text>'; return; }

    var minT = Math.min.apply(null, all.map(function(p){ return p.t; }));
    var maxT = Math.max.apply(null, all.map(function(p){ return p.t; }));
    var minV = opts.minZero ? 0 : Math.min.apply(null, all.map(function(p){ return p.v; }));
    var maxV = Math.max.apply(null, all.map(function(p){ return p.v; }));
    if(maxV === minV) maxV = minV + 1;
    if(maxT === minT) maxT = minT + 1;

    function x(t){ return padL + (t - minT) / (maxT - minT) * (W - padL - padR); }
    function y(v){ return H - padB - (v - minV) / (maxV - minV) * (H - padT - padB); }

    var svgns = 'http://www.w3.org/2000/svg';
    var frag = document.createDocumentFragment();

    // gridlines
    for(var i=0;i<=3;i++){
      var gy = padT + (H-padT-padB)*i/3;
      var gl = document.createElementNS(svgns,'line');
      gl.setAttribute('x1',padL); gl.setAttribute('x2',W-padR); gl.setAttribute('y1',gy); gl.setAttribute('y2',gy);
      gl.setAttribute('stroke','#1a2740'); gl.setAttribute('stroke-width','1');
      frag.appendChild(gl);
    }

    series.forEach(function(s, si){
      if(!s.points.length) return;
      var d = s.points.map(function(p,i){ return (i===0?'M':'L') + x(p.t).toFixed(1) + ',' + y(p.v).toFixed(1); }).join(' ');
      var path = document.createElementNS(svgns,'path');
      path.setAttribute('d', d);
      path.setAttribute('fill','none');
      path.setAttribute('stroke', s.color || PALETTE[si % PALETTE.length]);
      path.setAttribute('stroke-width','2');
      path.setAttribute('stroke-linejoin','round');
      path.setAttribute('stroke-linecap','round');
      frag.appendChild(path);
    });

    svg.innerHTML = '';
    svg.appendChild(frag);
  }

  function barChart(svg, points, opts){
    opts = opts || {};
    var W = 600, H = 180, padL = 4, padR = 4, padT = 8, padB = 8;
    if(!points.length){ svg.innerHTML = '<text x="300" y="94" fill="#5f6e86" font-size="12" text-anchor="middle">No data yet</text>'; return; }
    var maxV = Math.max.apply(null, points.map(function(p){ return p.v; }), 1);
    var n = points.length;
    var bw = Math.max(1, (W - padL - padR) / n - 1.5);
    var svgns = 'http://www.w3.org/2000/svg';
    var frag = document.createDocumentFragment();
    points.forEach(function(p, i){
      var h = (p.v / maxV) * (H - padT - padB);
      var bx = padL + i * (W - padL - padR) / n;
      var by = H - padB - h;
      var r = document.createElementNS(svgns,'rect');
      r.setAttribute('x', bx.toFixed(1)); r.setAttribute('y', by.toFixed(1));
      r.setAttribute('width', bw.toFixed(1)); r.setAttribute('height', Math.max(0,h).toFixed(1));
      r.setAttribute('fill', opts.color || '#3b86e6');
      r.setAttribute('rx','1');
      frag.appendChild(r);
    });
    svg.innerHTML = '';
    svg.appendChild(frag);
  }

  /* ---------- fetch helpers ---------- */
  function getJson(url){
    return fetch(url, {credentials:'same-origin'}).then(function(r){
      if(!r.ok) throw new Error('HTTP '+r.status);
      return r.json();
    });
  }

  /* ---------- overview cards + alerts ---------- */
  function renderOverview(o){
    var sysUp = o.systemStatus === 'OK';
    setPip('cSystem', sysUp, sysUp ? 'All systems normal' : 'Degraded');
    var dbUp = o.health && o.health.DATABASE === 'UP';
    setPip('cDb', dbUp, dbUp ? 'Connected' : 'Down');

    document.getElementById('cUsers').innerHTML = (o.activeUsers5m!=null?o.activeUsers5m:'—') +
      '<small>'+(o.activeUsers15m!=null?o.activeUsers15m:'—')+' in 15 min</small>';

    var api = o.api || {};
    document.getElementById('cApi').textContent = fmtMs(api.avg_ms) +
      (api.error_rate_pct!=null ? '' : '');

    var sys = o.system || {};
    setGauge('cCpu','cCpuBar', sys.cpu_pct);
    setGauge('cMem','cMemBar', sys.mem_pct, fmtGb(sys.mem_used_mb/1024, sys.mem_total_mb/1024));
    setGauge('cDisk','cDiskBar', sys.disk_pct, fmtGb(sys.disk_used_gb, sys.disk_total_gb));
    sysMemUsedMb = num(sys.mem_used_mb, null);
    sysMemTotalMb = num(sys.mem_total_mb, null);
    updateProcHeader();

    var box = document.getElementById('alerts');
    var alerts = o.alerts || [];
    if(!alerts.length){ box.innerHTML = ''; return; }
    box.innerHTML = alerts.map(function(a){
      return '<div class="alert '+(a.level==='error'?'error':'warn')+'"><span class="dot"></span>'+esc(a.message)+'</div>';
    }).join('');
  }

  function setPip(elId, ok, label){
    document.getElementById(elId).innerHTML = '<span class="pip '+(ok?'ok':'bad')+'"></span>'+esc(label);
  }

  function fmtGb(used, total){
    used = num(used, null); total = num(total, null);
    if(used == null || total == null) return null;
    return used.toFixed(1) + ' / ' + total.toFixed(1) + ' GB';
  }

  function setGauge(valId, barId, pct, detail){
    pct = num(pct, null);
    document.getElementById(valId).innerHTML = (pct==null ? '—' : pct.toFixed(1)+'%') +
      (detail ? '<small>'+esc(detail)+'</small>' : '');
    var bar = document.getElementById(barId);
    bar.classList.toggle('hot', pct!=null && pct > 80);
    bar.querySelector('i').style.width = (pct==null?0:Math.min(100,pct)) + '%';
  }

  /* ---------- APIs section ---------- */
  var lastApiRows = [];
  function renderApis(d){
    var rows = d.topSlow || [];
    lastApiRows = rows;
    var tbody = document.getElementById('apiRows');
    if(!rows.length){ tbody.innerHTML = '<tr><td colspan="6" class="empty">No API calls recorded yet.</td></tr>'; }
    else {
      tbody.innerHTML = rows.map(function(r){
        var slow = num(r.avg_ms,0) > 2000;
        return '<tr><td class="mono">'+esc(r.endpoint)+'</td><td>'+esc(r.method)+'</td><td>'+esc(r.calls)+'</td>'+
          '<td>'+(slow?'<span class="pill bad">':'')+fmtMs(r.avg_ms)+(slow?'</span>':'')+'</td>'+
          '<td>'+fmtMs(r.max_ms)+'</td><td>'+(num(r.errors,0)>0?'<span class="pill bad">'+r.errors+'</span>':'0')+'</td></tr>';
      }).join('');
    }

    var series = (d.series || []).map(function(r){ return {t: new Date(r.bucket).getTime(), v: num(r.avg_ms,0)}; });
    barChart(document.getElementById('apiChart'), series.map(function(p){ return {v:p.v}; }), {color:'#3b86e6'});
  }

  /* ---------- Layers section ---------- */
  var lastLayerRows = [];
  function renderLayers(d){
    var rows = d.avgByLayer || [];
    lastLayerRows = rows;
    var tbody = document.getElementById('layerRows');
    if(!rows.length){ tbody.innerHTML = '<tr><td colspan="5" class="empty">No layer loads recorded yet.</td></tr>'; }
    else {
      tbody.innerHTML = rows.map(function(r){
        var slow = num(r.avg_ms,0) > 3000;
        return '<tr><td>'+esc(r.layer_name)+'</td><td>'+esc(r.loads)+'</td>'+
          '<td>'+(slow?'<span class="pill bad">':'')+fmtMs(r.avg_ms)+(slow?'</span>':'')+'</td>'+
          '<td>'+fmtMs(r.max_ms)+'</td><td>'+(num(r.failures,0)>0?'<span class="pill bad">'+r.failures+'</span>':'0')+'</td></tr>';
      }).join('');
    }

    var failed = d.failedLast24h || [];
    var frows = document.getElementById('failedRows');
    frows.innerHTML = failed.length ? failed.map(function(f){
      return '<tr><td>'+esc(f.layer_name)+'</td><td>'+fmtMs(f.duration_ms)+'</td><td>'+esc(f.username||'—')+'</td><td>'+esc(fmtTime(f.created_at))+'</td></tr>';
    }).join('') : '<tr><td colspan="4" class="empty">No failed layer loads in the last 24h.</td></tr>';

    // group series by layer into per-layer point arrays
    var byLayer = {};
    (d.series || []).forEach(function(r){
      var key = r.layer_name;
      (byLayer[key] = byLayer[key] || []).push({t: new Date(r.bucket).getTime(), v: num(r.avg_ms,0)});
    });
    var names = Object.keys(byLayer);
    var chartSeries = names.map(function(n, i){ return {label:n, color:PALETTE[i%PALETTE.length], points: byLayer[n]}; });
    lineChart(document.getElementById('layerChart'), chartSeries, {minZero:true});
    document.getElementById('layerLegend').innerHTML = names.map(function(n,i){
      return '<span><i style="background:'+PALETTE[i%PALETTE.length]+'"></i>'+esc(n)+'</span>';
    }).join('');
  }

  /* ---------- System + DB charts ---------- */
  function renderSystem(d){
    var series = d.series || [];
    var cpu = series.map(function(r){ return {t:new Date(r.created_at).getTime(), v:num(r.cpu_pct,0)}; });
    var mem = series.map(function(r){ return {t:new Date(r.created_at).getTime(), v:num(r.mem_pct,0)}; });
    lineChart(document.getElementById('sysChart'), [
      {label:'CPU', color:'#3b86e6', points:cpu},
      {label:'Memory', color:'#19b277', points:mem}
    ], {minZero:true});
  }

  function renderDb(d){
    var series = d.series || [];
    var active = series.map(function(r){ return {t:new Date(r.created_at).getTime(), v:num(r.active_connections,0)}; });
    lineChart(document.getElementById('dbChart'), [{label:'Active', color:'#d3aa3e', points:active}], {minZero:true});
  }

  /* ---------- Memory by process ---------- */
  function fmtMb(v){ v = num(v, null); return v==null ? '—' : (v>=1024 ? (v/1024).toFixed(2)+' GB' : v.toFixed(1)+' MB'); }

  var sysMemTotalMb = null, sysMemUsedMb = null;
  function updateProcHeader(){
    var el = document.getElementById('procMemTotal');
    if(sysMemTotalMb == null){ el.textContent = 'live, RSS'; return; }
    el.textContent = fmtMb(sysMemUsedMb) + ' / ' + fmtMb(sysMemTotalMb) + ' RAM in use';
  }

  function renderProcesses(d){
    var tbody = document.getElementById('procRows');
    if(d.supported === false){
      tbody.innerHTML = '<tr><td colspan="3" class="empty">Per-process breakdown needs Linux (ps) — not available on this box.</td></tr>';
      return;
    }
    var rows = d.processes || [];
    if(!rows.length){ tbody.innerHTML = '<tr><td colspan="3" class="empty">No process data yet.</td></tr>'; return; }
    var maxMb = Math.max.apply(null, rows.map(function(r){ return num(r.memMb,0); }), 1);
    tbody.innerHTML = rows.map(function(r){
      var barPct = Math.min(100, num(r.memMb,0) / maxMb * 100);
      var ofTotal = sysMemTotalMb ? (num(r.memMb,0) / sysMemTotalMb * 100) : null;
      return '<tr><td class="mono">'+esc(r.name)+'</td><td>'+fmtMb(r.memMb)+'</td>' +
        '<td><div class="bar" style="min-width:80px"><i style="width:'+barPct.toFixed(0)+'%"></i></div>' +
        (ofTotal!=null ? '<small>'+ofTotal.toFixed(1)+'%</small>' : '') + '</td></tr>';
    }).join('');
  }

  /* ---------- Active sessions ---------- */
  function roleLabel(r){ return r==='SUPER_ADMIN'?'Super Admin':r==='ADMIN'?'Admin':r==='USER'?'User':'—'; }
  function shortUA(ua){
    if(!ua) return '—';
    var browser = /Edg\//.test(ua)?'Edge': /OPR\//.test(ua)?'Opera':
                  /Chrome\//.test(ua)?'Chrome': /Firefox\//.test(ua)?'Firefox':
                  /Safari\//.test(ua)?'Safari':'Browser';
    return browser;
  }
  function renderUsers(d){
    var rows = d.openSessions || [];
    var tbody = document.getElementById('sessionRows');
    tbody.innerHTML = rows.length ? rows.map(function(s){
      return '<tr><td>'+esc(s.username)+'</td><td>'+esc(roleLabel(s.role))+'</td><td>'+esc(fmtTime(s.login_at))+'</td>'+
        '<td class="mono">'+esc(s.ip||'—')+'</td><td>'+esc(shortUA(s.user_agent))+'</td></tr>';
    }).join('') : '<tr><td colspan="5" class="empty">No open sessions.</td></tr>';
  }

  /* ---------- refresh loop ---------- */
  function load(){
    document.getElementById('lastUpd').textContent = 'Refreshing…';
    Promise.all([
      getJson('/api/monitor/overview').then(renderOverview),
      getJson('/api/monitor/apis?hours=24').then(renderApis),
      getJson('/api/monitor/layers?hours=6').then(renderLayers),
      getJson('/api/monitor/system?minutes=180').then(renderSystem),
      getJson('/api/monitor/db?minutes=180').then(renderDb),
      getJson('/api/monitor/processes').then(renderProcesses),
      getJson('/api/monitor/users').then(renderUsers)
    ]).then(function(){
      document.getElementById('lastUpd').textContent = 'Updated ' + new Date().toLocaleTimeString('en-IN');
    }).catch(function(e){
      document.getElementById('lastUpd').textContent = 'Refresh failed: ' + e.message;
    });
  }

  function scheduleAutoRefresh(){
    if(timer) clearInterval(timer);
    timer = document.getElementById('autoRefresh').checked ? setInterval(load, REFRESH_MS) : null;
  }

  document.getElementById('refresh').addEventListener('click', load);
  document.getElementById('autoRefresh').addEventListener('change', scheduleAutoRefresh);
  document.getElementById('exportApis').addEventListener('click', function(){
    exportCsv('klrams-api-metrics-'+new Date().toISOString().slice(0,10)+'.csv',
      ['Endpoint','Method','Calls','Avg ms','Max ms','Errors'], lastApiRows,
      function(r){ return [r.endpoint, r.method, r.calls, r.avg_ms, r.max_ms, r.errors]; });
  });
  document.getElementById('exportLayers').addEventListener('click', function(){
    exportCsv('klrams-layer-metrics-'+new Date().toISOString().slice(0,10)+'.csv',
      ['Layer','Loads','Avg ms','Max ms','Failures'], lastLayerRows,
      function(r){ return [r.layer_name, r.loads, r.avg_ms, r.max_ms, r.failures]; });
  });

  load();
  scheduleAutoRefresh();
})();
