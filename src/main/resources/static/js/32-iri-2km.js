/* ============================================================
   KLRAMS viewer · 32-iri-2km.js   (build 1)
   "Avg IRI (2 km · worst lane)" layer.

   The condition survey is collected in ~100 m stretches per lane, which is far
   finer than the 2 km unit roughness is reported on. The backend
   (IriSegmentService) rolls the raw IRI up into fixed 2 km bins per road
   section and stores the result in the iri_2km_segments table:

     • avg_iri_cl1 / avg_iri_cr1 — length-weighted average IRI of lane CL1 / CR1
       inside the bin (a 200 m row counts twice as much as a 100 m row)
     • worst_iri / worst_lane    — the worse (higher) of those two lane averages,
       and which lane it came from

   This module fetches /api/iri-2km/geojson and draws ONE line per 2 km bin,
   coloured by worst_iri against the same Good/Fair/Poor IRI thresholds the
   condition layer uses (IRC:82-2023 — Good < 2.55, Poor > 3.30 m/km, editable
   under Road Condition). Clicking a bin shows both lane averages side by side.

   Loaded as an ordered classic script from map.html; all modules share one
   global scope, so load order is preserved exactly.
   ============================================================ */
(function(){
  var LAYER='iri2km', SRC='iri2km';
  var TOGGLE='showIri2km';

  /* Thresholds come from the Road Condition pane when the user is looking at
     IRI, so the two layers always agree; otherwise the IRC defaults apply. */
  function thresholds(){
    var def=(typeof PMAP!=='undefined'&&PMAP.iri)?PMAP.iri:{fair:2.55,poor:3.30};
    var fair=def.fair,poor=def.poor;
    try{
      if(typeof cb!=='undefined'&&cb&&cb.value==='iri'){
        var f=parseFloat((document.getElementById('fair')||{}).value);
        var p=parseFloat((document.getElementById('poor')||{}).value);
        if(!isNaN(f))fair=f;
        if(!isNaN(p))poor=p;
      }
    }catch(e){}
    return {fair:fair,poor:poor};
  }

  function colorExpr(){
    var t=thresholds();
    var good=(typeof GOOD!=='undefined')?GOOD:'#2ba66a';
    var fair=(typeof FAIR!=='undefined')?FAIR:'#FFC400';
    var poor=(typeof POOR!=='undefined')?POOR:'#da4b43';
    var none=(typeof NONE!=='undefined')?NONE:'#b9c2cc';
    return ['case',['!',['has','worst_iri']],none,
            ['step',['to-number',['get','worst_iri']],good,t.fair,fair,t.poor,poor]];
  }

  function renderLegend(){
    var el=document.getElementById('iri2kmLegend');if(!el)return;
    var t=thresholds();
    var rows=[[(typeof GOOD!=='undefined')?GOOD:'#2ba66a','Good &lt; '+t.fair],
              [(typeof FAIR!=='undefined')?FAIR:'#FFC400','Fair '+t.fair+' – '+t.poor],
              [(typeof POOR!=='undefined')?POOR:'#da4b43','Poor &gt; '+t.poor]];
    el.innerHTML='<div class="fl-t">Avg IRI per 2 km — worst of CL1 / CR1 (m/km)</div>'+
      rows.map(function(r){return '<div class="fl-r"><span class="sw" style="background:'+r[0]+'"></span>'+r[1]+'</div>';}).join('');
  }

  function num(v,d){
    if(v==null||v==='')return '–';
    var n=+v;return isNaN(n)?'–':n.toFixed(d==null?2:d);
  }
  function esc(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}

  function popup(lngLat,p){
    var worst=+p.worst_iri;
    var t=thresholds();
    var band=isNaN(worst)?null:(worst<t.fair?{l:'Good',c:(typeof GOOD!=='undefined')?GOOD:'#2ba66a'}
                               :(worst<t.poor?{l:'Fair',c:(typeof FAIR!=='undefined')?FAIR:'#FFC400'}
                                             :{l:'Poor',c:(typeof POOR!=='undefined')?POOR:'#da4b43'}));
    var wl=p.worst_lane||'';
    function laneRow(lane,val,n){
      var isWorst=(wl===lane);
      return '<tr><td class="k">'+lane+(isWorst?' <b style="color:#c2410c">▲ worst</b>':'')+'</td>'+
             '<td class="v">'+num(val)+' m/km'+(n?' <span style="color:#8193ac">('+n+' rows)</span>':'')+'</td></tr>';
    }
    var head=band
      ? '<div style="font-size:22px;font-weight:700;color:#0e2038">'+worst.toFixed(2)+
        '<span style="font-size:12px;color:#64718a;font-weight:500"> m/km</span> '+
        '<span style="background:'+band.c+';color:#fff;font-size:11px;font-weight:700;border-radius:20px;padding:2px 9px;margin-left:4px">'+band.l+'</span></div>'+
        '<div style="font-size:11.5px;color:#64718a;margin:3px 0 8px">Worst-lane average over this 2 km'+(wl?(' · lane '+wl):'')+'</div>'
      : '<div style="color:#64718a">No IRI recorded on CL1 / CR1 in this 2 km</div>';
    var from=+p.from_ch,to=+p.to_ch;
    var range=(isNaN(from)||isNaN(to))?'':((from/1000).toFixed(2)+' – '+(to/1000).toFixed(2)+' km');
    var cover=(p.surveyed_len!=null&&p.surveyed_len!=='')?('<div style="font-size:10.5px;color:#64718a;margin-top:6px;border-top:1px solid #eef1f5;padding-top:5px">Surveyed length in this bin: <b>'+num(p.surveyed_len,0)+' m</b> (both lanes)</div>'):'';
    new maplibregl.Popup({maxWidth:'300px'}).setLngLat(lngLat)
      .setHTML('<div class="pop"><div class="sec">Avg IRI (2 km) · '+esc(p.road||'')+(range?(' · '+range):'')+'</div>'+
               head+'<table>'+laneRow('CL1',p.avg_iri_cl1,p.n_cl1)+laneRow('CR1',p.avg_iri_cr1,p.n_cr1)+'</table>'+cover+'</div>')
      .addTo(map);
  }

  var _inflight=null, _loaded=false;

  function vis(){var t=document.getElementById(TOGGLE);return (t&&t.checked)?'visible':'none';}

  function addLayer(gj){
    if(map.getSource(SRC)){map.getSource(SRC).setData(gj);}
    else{
      /* tolerance:0 — same reason as the condition segments: the default
         simplification collapses short bins at low zoom. */
      map.addSource(SRC,{type:'geojson',data:gj,tolerance:0});
    }
    if(!map.getLayer(LAYER)){
      map.addLayer({id:LAYER,type:'line',source:SRC,
        layout:{'line-cap':'round','line-join':'round','visibility':vis()},
        paint:{'line-color':colorExpr(),
               'line-width':['interpolate',['linear'],['zoom'],10,4.5,16,10],
               'line-offset':['interpolate',['linear'],['zoom'],10,-3.5,16,-8]}});
      map.on('click',LAYER,function(e){if(e.features.length)popup(e.lngLat,e.features[0].properties);});
      map.on('mouseenter',LAYER,function(){map.getCanvas().style.cursor='pointer';});
      map.on('mouseleave',LAYER,function(){map.getCanvas().style.cursor='';});
    }else{
      map.setPaintProperty(LAYER,'line-color',colorExpr());
      map.setLayoutProperty(LAYER,'visibility',vis());
    }
  }

  /* Load once and cache; silent=true is the background preload from 15-main.js
     (layer created hidden, toggling is then an instant visibility flip). */
  function loadIri2km(silent){
    if(_inflight)return _inflight;
    if(_loaded&&map.getLayer(LAYER)){map.setLayoutProperty(LAYER,'visibility',vis());return Promise.resolve();}
    var st=document.getElementById('status');
    if(!silent&&st)st.textContent='Loading 2 km IRI…';
    var _fetch=(typeof fetchJsonRetry==='function')
      ? fetchJsonRetry('/api/iri-2km/geojson',3)
      : fetch('/api/iri-2km/geojson',{credentials:'same-origin'}).then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json();});
    var p=_fetch.then(function(gj){
      if(!gj||!gj.features||!gj.features.length){
        if(!silent&&st)st.textContent='No 2 km IRI data yet — build it in the Data Console (Condition Data → Build Avg IRI 2 km).';
        return;
      }
      addLayer(gj);
      _loaded=true;
      renderLegend();
      if(!silent&&st)st.textContent='✓ '+gj.features.length+' 2 km IRI bins loaded.';
    }).catch(function(err){
      if(!silent&&st)st.textContent='2 km IRI failed to load ('+err.message+').';
    });
    _inflight=p;
    p.then(function(){_inflight=null;},function(){_inflight=null;});
    return p;
  }
  window.loadIri2km=loadIri2km;

  /* toggle: first tick fetches, later ticks flip visibility */
  (function wire(){
    var t=document.getElementById(TOGGLE);if(!t)return;
    t.addEventListener('change',function(e){
      var lg=document.getElementById('iri2kmLegend');
      if(lg)lg.style.display=e.target.checked?'block':'none';
      if(e.target.checked&&!map.getLayer(LAYER)){renderLegend();loadIri2km();return;}
      if(map.getLayer(LAYER))map.setLayoutProperty(LAYER,'visibility',e.target.checked?'visible':'none');
    });
  })();

  /* Keep the colouring in step with the Good/Fair/Poor thresholds while the
     Road Condition pane is showing IRI — the same inputs drive both layers. */
  ['fair','poor'].forEach(function(id){
    var el=document.getElementById(id);if(!el)return;
    el.addEventListener('input',function(){if(map.getLayer(LAYER)){map.setPaintProperty(LAYER,'line-color',colorExpr());renderLegend();}});
  });
  try{
    if(typeof cb!=='undefined'&&cb)cb.addEventListener('change',function(){if(map.getLayer(LAYER)){map.setPaintProperty(LAYER,'line-color',colorExpr());renderLegend();}});
  }catch(e){}

  renderLegend();
})();
