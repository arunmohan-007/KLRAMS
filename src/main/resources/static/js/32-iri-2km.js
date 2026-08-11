/* ============================================================
   KLRAMS viewer · 32-iri-2km.js   (build 2)
   "Avg IRI (2 km · worst lane)" layer.

   The condition survey is collected in ~100 m stretches per lane, which is far
   finer than the 2 km unit roughness is reported on. The backend
   (IriSegmentService) rolls the raw IRI up into fixed 2 km bins per road
   section and stores the result in the iri_2km_segments table:

     • lane_avgs — length-weighted average IRI inside the bin for EVERY lane the
       section carries (CC / CL1 / CL2 / CR1 / CR2; a 200 m row counts twice as
       much as a 100 m one)
     • worst_iri / worst_lane — the highest of those lane averages, and which
       lane it came from. Which lanes exist varies by section: some are surveyed
       as CC alone, and a dual carriageway's two centrelines each carry only
       their own side (…A → CL1/CL2, …B → CR1/CR2).

   Rendering: when TILES_ON (default), the layer is a vector-tile source at
   /api/iri-2km/tiles/{z}/{x}/{y}.mvt — MapLibre fetches only the viewport, so
   there is no whole-network download on load. Opt out with ?tiles=0 to keep the
   old /api/iri-2km/geojson path. Coloured by worst_iri against the same
   Good/Fair/Poor IRI thresholds the condition layer uses (IRC:82-2023 —
   Good < 2.55, Poor > 3.30 m/km, editable under Road Condition). Clicking a
   bin lists every lane's average, worst marked. The Filters-folder section
   (worst-lane IRI range + which lane is the worst) applies a MapLibre filter;
   the "N of M match" line scans the GeoJSON in ?tiles=0 mode and calls
   /api/iri-2km/match when tiles are on.

   Loaded as an ordered classic script from map.html; all modules share one
   global scope, so load order is preserved exactly.
   ============================================================ */
(function(){
  var LAYER='iri2km', SRC='iri2km';
  var TOGGLE='showIri2km';
  /* MVT layer name inside the tile, as IriTileService names it. Every layer
     bound to a vector source must declare it or MapLibre silently renders
     nothing. */
  var TILE_LAYER='iri2km';

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

  /* Thresholds are user-editable, so print them on a fixed 2 decimals — the
     ranges then line up as a column under font-variant-numeric:tabular-nums. */
  function band(v){var n=+v;return isNaN(n)?'–':n.toFixed(2);}

  function renderLegend(){
    var el=document.getElementById('iri2kmLegend');if(!el)return;
    var t=thresholds();
    var rows=[[(typeof GOOD!=='undefined')?GOOD:'#2ba66a','Good','&lt; '+band(t.fair)],
              [(typeof FAIR!=='undefined')?FAIR:'#FFC400','Fair',band(t.fair)+' – '+band(t.poor)],
              [(typeof POOR!=='undefined')?POOR:'#da4b43','Poor','&gt; '+band(t.poor)]];
    el.innerHTML='<div class="fl-hd"><span class="fl-t">Avg IRI · 2 km</span><span class="fl-u">m/km</span></div>'+
      '<div class="fl-sub">Worst lane of the section</div>'+
      rows.map(function(r){
        return '<div class="fl-r"><span class="sw" style="background:'+r[0]+'"></span>'+
               '<span class="fl-l">'+r[1]+'</span><span class="fl-v">'+r[2]+'</span></div>';
      }).join('');
  }

  function num(v,d){
    if(v==null||v==='')return '–';
    var n=+v;return isNaN(n)?'–':n.toFixed(d==null?2:d);
  }
  function esc(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}

  /* Cross-section order as it sits on the ground, left to right — the same
     order the condition layer draws its lanes in (LANE_SLOTS, 07-data-loaders).
     Anything unrecognised is listed after these, alphabetically. */
  var LANE_ORDER=['CL2','CL1','CC','CR1','CR2'];
  function laneRank(x){var i=LANE_ORDER.indexOf(x);return i<0?LANE_ORDER.length:i;}

  /* lane_avgs arrives as a jsonb object on GeoJSON, or as text from the MVT
     (ST_AsMVT stringifies jsonb). Accept either. */
  function laneAvgs(p){
    var la=p.lane_avgs;
    if(typeof la==='string'){try{la=JSON.parse(la);}catch(e){la=null;}}
    return (la&&typeof la==='object')?la:{};
  }

  function popup(lngLat,p){
    var worst=+p.worst_iri;
    var t=thresholds();
    /* tc = chip text colour: the Fair band is a light amber, so white on it is
       unreadable — that one gets dark text. */
    var band=isNaN(worst)?null:(worst<t.fair?{l:'Good',c:(typeof GOOD!=='undefined')?GOOD:'#2ba66a',tc:'#fff'}
                               :(worst<t.poor?{l:'Fair',c:(typeof FAIR!=='undefined')?FAIR:'#FFC400',tc:'#3a2b00'}
                                             :{l:'Poor',c:(typeof POOR!=='undefined')?POOR:'#da4b43',tc:'#fff'}));
    var wl=p.worst_lane||'';
    var la=laneAvgs(p);
    var lanes=Object.keys(la).sort(function(a,b){var d=laneRank(a)-laneRank(b);return d||(a<b?-1:1);});
    var rows=lanes.map(function(lane){
      var isWorst=(wl===lane);
      return '<tr><td class="k">'+esc(lane)+(isWorst?' <span class="iri-worst">▲ worst</span>':'')+'</td>'+
             '<td class="v">'+num(la[lane])+' m/km</td></tr>';
    }).join('');
    var head=band
      ? '<div class="iri-big">'+worst.toFixed(2)+'<span class="iri-unit"> m/km</span>'+
        '<span class="iri-band" style="background:'+band.c+';color:'+band.tc+'">'+band.l+'</span></div>'+
        '<div class="iri-note">Worst-lane average over this 2 km'+(wl?(' · lane '+esc(wl)):'')+'</div>'
      : '<div class="iri-note">No IRI recorded in this 2 km</div>';
    var from=+p.from_ch,to=+p.to_ch;
    var range=(isNaN(from)||isNaN(to))?'':((from/1000).toFixed(2)+' – '+(to/1000).toFixed(2)+' km');
    var foot=(p.surveyed_len!=null&&p.surveyed_len!=='')
      ? '<div class="iri-foot">Surveyed: <b>'+num(p.surveyed_len,0)+' m</b> over '+
        (lanes.length===1?'1 lane':(lanes.length+' lanes'))+
        (p.n_rows?(' · '+p.n_rows+' survey rows'):'')+'</div>'
      : '';
    new maplibregl.Popup({maxWidth:'300px'}).setLngLat(lngLat)
      .setHTML('<div class="pop iri-pop"><div class="sec">Avg IRI (2 km) · '+esc(p.road||'')+(range?(' · '+range):'')+'</div>'+
               head+'<table>'+rows+'</table>'+foot+'</div>')
      .addTo(map);
  }

  var _inflight=null, _loaded=false, _data=null, _wired=false;

  function tilesOn(){return typeof TILES_ON!=='undefined'&&TILES_ON;}
  function vis(){var t=document.getElementById(TOGGLE);return (t&&t.checked)?'visible':'none';}

  function wireHandlers(){
    if(_wired)return;_wired=true;
    map.on('click',LAYER,function(e){if(e.features.length)popup(e.lngLat,e.features[0].properties);});
    map.on('mouseenter',LAYER,function(){map.getCanvas().style.cursor='pointer';});
    map.on('mouseleave',LAYER,function(){map.getCanvas().style.cursor='';});
  }

  function addLayerPaint(){
    if(map.getLayer(LAYER)){
      map.setPaintProperty(LAYER,'line-color',colorExpr());
      map.setLayoutProperty(LAYER,'visibility',vis());
    }else{
      var spec={id:LAYER,type:'line',source:SRC,
        layout:{'line-cap':'round','line-join':'round','visibility':vis()},
        paint:{'line-color':colorExpr(),
               'line-width':['interpolate',['linear'],['zoom'],10,4.5,16,10],
               'line-offset':['interpolate',['linear'],['zoom'],10,-3.5,16,-8]}};
      if(tilesOn())spec['source-layer']=TILE_LAYER;
      map.addLayer(spec);
      wireHandlers();
    }
    /* A filter set before the layer existed (the Filters folder is reachable
       while the layer is still preloading) has to be re-applied to the new
       layer, or it would silently show everything. */
    applyIri2kmFilter(true);
  }

  /* Tile mode: register a vector source template — MapLibre fetches only the
     viewport. No download here; that is the whole point of the migration. */
  function ensureTileSource(){
    if(map.getSource(SRC)){addLayerPaint();return true;}
    map.addSource(SRC,{type:'vector',promoteId:'seg_id',
      tiles:[location.origin+'/api/iri-2km/tiles/{z}/{x}/{y}.mvt'],
      minzoom:0,maxzoom:16});
    addLayerPaint();
    return true;
  }

  function addGeoJsonLayer(gj){
    if(map.getSource(SRC)){map.getSource(SRC).setData(gj);}
    else{
      /* tolerance:0 — same reason as the condition segments: the default
         simplification collapses short bins at low zoom. */
      map.addSource(SRC,{type:'geojson',data:gj,tolerance:0});
    }
    addLayerPaint();
  }

  /* ---------- filter: worst-lane IRI range + which lane is the worst ----------
     Mirrors the FWD / PCI sections of the Filters folder (18-filters.js). */
  function filterInputs(){
    var mn=parseFloat((document.getElementById('iriMin')||{}).value);
    var mx=parseFloat((document.getElementById('iriMax')||{}).value);
    var lane=((document.getElementById('iriLane')||{}).value||'').trim();
    return {mn:mn,mx:mx,lane:lane,
            any:(!isNaN(mn)||!isNaN(mx)||!!lane)};
  }

  function matchCountLocal(f){
    var el=document.getElementById('iriMatchInfo');if(!el)return;
    if(!_data||!_data.features){el.textContent='';return;}
    if(!f.any){el.textContent='';return;}
    var n=0;
    _data.features.forEach(function(ft){
      var p=ft.properties||{},v=+p.worst_iri;
      if(isNaN(v))return;
      if(!isNaN(f.mn)&&v<f.mn)return;
      if(!isNaN(f.mx)&&v>f.mx)return;
      if(f.lane&&p.worst_lane!==f.lane)return;
      n++;
    });
    el.textContent=n+' of '+_data.features.length+' bins match';
  }

  /* Tile mode has no FeatureCollection to scan — ask the server. */
  function matchCountRemote(f){
    var el=document.getElementById('iriMatchInfo');if(!el)return;
    if(!f.any){el.textContent='';return;}
    var qs=[];
    if(!isNaN(f.mn))qs.push('min='+encodeURIComponent(f.mn));
    if(!isNaN(f.mx))qs.push('max='+encodeURIComponent(f.mx));
    if(f.lane)qs.push('lane='+encodeURIComponent(f.lane));
    fetch('/api/iri-2km/match?'+qs.join('&'),{credentials:'same-origin'})
      .then(function(r){return r.ok?r.json():null;})
      .then(function(j){
        if(!j){el.textContent='';return;}
        el.textContent=(j.matched||0)+' of '+(j.total||0)+' bins match';
      })
      .catch(function(){el.textContent='';});
  }

  function matchCount(f){
    if(tilesOn())matchCountRemote(f);
    else matchCountLocal(f);
  }

  /* quiet=true is the internal re-apply after a (re)load — it must not report
     "layer not loaded" when nothing is filtered. */
  function applyIri2kmFilter(quiet){
    var f=filterInputs();
    if(!map.getLayer(LAYER)){
      if(!quiet&&f.any){
        var st=document.getElementById('status');
        if(st)st.textContent='Avg IRI layer is still loading — the filter applies as soon as it is on the map.';
      }
      return;
    }
    var conds=['all'];
    if(!isNaN(f.mn))conds.push(['>=',['to-number',['get','worst_iri']],f.mn]);
    if(!isNaN(f.mx))conds.push(['<=',['to-number',['get','worst_iri']],f.mx]);
    if(f.lane)conds.push(['==',['get','worst_lane'],f.lane]);
    map.setFilter(LAYER, conds.length>1?conds:null);
    matchCount(f);
  }

  function clearIri2kmFilter(){
    ['iriMin','iriMax'].forEach(function(id){var e=document.getElementById(id);if(e)e.value='';});
    var l=document.getElementById('iriLane');if(l)l.value='';
    if(map.getLayer(LAYER))map.setFilter(LAYER,null);
    var el=document.getElementById('iriMatchInfo');if(el)el.textContent='';
  }

  window.applyIri2kmFilter=applyIri2kmFilter;
  window.clearIri2kmFilter=clearIri2kmFilter;

  /* Load once and cache; silent=true is the background preload from 15-main.js
     (layer created hidden, toggling is then an instant visibility flip).
     In tile mode there is nothing to download — just register the source. */
  function loadIri2km(silent){
    if(_inflight)return _inflight;
    if(_loaded&&map.getLayer(LAYER)){map.setLayoutProperty(LAYER,'visibility',vis());return Promise.resolve();}
    var st=document.getElementById('status');
    if(tilesOn()){
      ensureTileSource();
      _loaded=true;
      renderLegend();
      if(!silent&&st)st.textContent='✓ Avg IRI (2 km) ready.';
      return Promise.resolve();
    }
    if(!silent&&st)st.textContent='Loading 2 km IRI…';
    var _fetch=(typeof fetchJsonRetry==='function')
      ? fetchJsonRetry('/api/iri-2km/geojson',3)
      : fetch('/api/iri-2km/geojson',{credentials:'same-origin'}).then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json();});
    var p=_fetch.then(function(gj){
      if(!gj||!gj.features||!gj.features.length){
        if(!silent&&st)st.textContent='No 2 km IRI data yet — build it in the Data Console (Condition Data → Build Avg IRI 2 km).';
        return;
      }
      _data=gj;              /* kept for the filter's match count */
      addGeoJsonLayer(gj);
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

  /* toggle: first tick fetches / registers, later ticks flip visibility */
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
