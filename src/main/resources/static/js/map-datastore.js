/* map-datastore — lifted out of an inline <script> in map.html so the page carries
   no inline script, which is what lets script-src drop 'unsafe-inline'. */
/* ===== KLRAMS global data store (build 160) =====
     Captures EVERY layer once it loads — both /api/* fetches AND live MapLibre
     geojson sources (PCI, FWD, traffic, soil, core, condition) — indexed by road +
     chainage. Both the NSV HUD and the inspector read the same store, so a value,
     once generated/loaded, stays available for every later call (inspection card,
     NSV video, etc.) and is only replaced when that layer is generated again. */
  (function(){
    window.KL=window.KL||{byRoad:{},layers:{},_pciCache:{},_srcSig:{}};
    function roadKey(p){return p.road||p.Road_Name||p.road_name||p.ROAD_NAME||p.RoadName||p.section||p.Section_La||null;}
    function num(v){return (v==null||v==='')?NaN:+v;}
    function pickn(p,keys){for(var i=0;i<keys.length;i++){var v=p[keys[i]];if(v!=null&&v!=='')return +v;}return NaN;}
    var CH_FROM=['from_ch','Rd_Str_cha','chainage_from','from_chainage','start_ch','ch_from','From_Chain','Start_Chai'];
    var CH_TO  =['to_ch','Rd_End_cha','chainage_to','to_chainage','end_ch','ch_to','To_Chain','End_Chaina'];
    var CH_PT  =['chainage','chn','ch','chainage_m','Chainage','CHAINAGE','chain','chain_m','ch_m','lrp','LRP','location','Chainage_m','chainage_no','chainage_pt','Chn'];
    KL._clearLayer=function(layer){for(var r in KL.byRoad){KL.byRoad[r]=KL.byRoad[r].filter(function(e){return e.layer!==layer;});}};
    /* replace-by-layer, but only when the new data actually has features
       (so toggling a layer OFF / emptying a source never wipes captured values) */
    KL.ingest=function(layer,gj){
      try{
        if(!gj||!gj.features||!gj.features.length)return;
        layer=String(layer).toLowerCase();
        KL._clearLayer(layer); KL.layers[layer]=gj.features.length;
        for(var i=0;i<gj.features.length;i++){
          var f=gj.features[i];var pr=f.properties||{};var rk=roadKey(pr);if(!rk)continue;
          (KL.byRoad[rk]=KL.byRoad[rk]||[]).push({layer:layer,p:pr,
            from:pickn(pr,CH_FROM),to:pickn(pr,CH_TO),pt:pickn(pr,CH_PT)});
        }
      }catch(e){}
    };
    KL.ingestUrl=function(url,gj){var m=String(url).match(/\/api\/([a-z0-9_\-]+)/i);KL.ingest(m?m[1]:String(url),gj);};
    /* guaranteed capture of the already-parsed condition segments (worst PCI + condition) */
    KL.syncGlobals=function(){
      try{
        if(typeof segsByRoad!=='undefined'&&segsByRoad){
          var n=0,r;for(r in segsByRoad)n+=segsByRoad[r].length;
          if(KL._segN!==n){KL._segN=n;KL._clearLayer('segments');
            for(r in segsByRoad){for(var i=0;i<segsByRoad[r].length;i++){var f=segsByRoad[r][i],pr=f.properties||{},rk=pr.road||r;
              (KL.byRoad[rk]=KL.byRoad[rk]||[]).push({layer:'segments',p:pr,from:num(pr.from_ch!=null?pr.from_ch:pr.Rd_Str_cha),to:num(pr.to_ch!=null?pr.to_ch:pr.Rd_End_cha),pt:NaN});}}
          }
        }
      }catch(e){}
    };
    /* pull data straight out of live MapLibre geojson sources (where generated PCI etc. live) */
    KL.scanSources=function(){
      try{
        var m=window.map;if(!m||!m.getStyle)return;
        var srcs=m.getStyle().sources||{};
        for(var id in srcs){
          var src=m.getSource(id);if(!src)continue;
          var gj=src._data||(src.serialize&&src.serialize().data);
          if(!gj||typeof gj!=='object'||!gj.features||!gj.features.length){
            try{var ff=m.querySourceFeatures(id);if(ff&&ff.length)gj={type:'FeatureCollection',features:ff};}catch(e){}
          }
          if(!gj||typeof gj!=='object'||!gj.features||!gj.features.length)continue; /* persist on empty */
          var sig=id+':'+gj.features.length;
          if(KL._srcSig[id]===sig)continue; /* unchanged since last scan */
          KL._srcSig[id]=sig; KL.ingest(id,gj);
        }
      }catch(e){}
    };
    /* Throttle map-source scans, but NEVER skip a segsByRoad reindex — the
       inspector/HUD can load one road's segments while a recent sync is still
       inside the 1.2s window, and skipping left KL.pci returning null until a
       later refresh ("PCI not linked" flash). */
    KL.sync=function(force){
      var t=Date.now(),needSegs=false;
      try{if(typeof segsByRoad!=='undefined'&&segsByRoad){var n=0,r;for(r in segsByRoad)n+=segsByRoad[r].length;if(KL._segN!==n)needSegs=true;}}catch(e){}
      if(!force&&!needSegs&&KL._lastSync&&(t-KL._lastSync)<1200)return;
      KL._lastSync=t;KL.syncGlobals();KL.scanSources();
    };
    /* exact match only (no sticky nearest) \u2014 for chainage-varying readings (FWD etc.) */
    KL.atExact=function(road,ch){
      KL.sync();
      var arr=KL.byRoad[road];if(!arr)return {};
      var out={},k;
      for(var i=0;i<arr.length;i++){var e=arr[i],hit=false;
        if(!isNaN(e.from)&&!isNaN(e.to)){var lo=Math.min(e.from,e.to),hi=Math.max(e.from,e.to);hit=(ch>=lo&&ch<=hi);}
        else if(!isNaN(e.pt))hit=(Math.abs(ch-e.pt)<=30);
        else hit=false; /* no chainage info -> NOT a per-chainage hit */
        if(hit){for(k in e.p)out[k]=e.p[k];}
      }
      return out;
    };
    /* FWD resolver — finds D0..D8 by matching column NAMES with a regex, so the exact
       header (D0, FWD_D0, deflection, d0_micron, "D0 (mm)"...) no longer matters.
       Uses range matching (from_ch..to_ch) like condition data. */
    KL.fwdAt=function(road,ch){
      KL.sync();
      var arr=KL.byRoad[road];if(!arr)return null;
      var props=null;
      for(var i=0;i<arr.length;i++){var e=arr[i],hit=false;
        if(!isNaN(e.from)&&!isNaN(e.to)){var lo=Math.min(e.from,e.to),hi=Math.max(e.from,e.to);hit=(ch>=lo&&ch<=hi);}
        else if(!isNaN(e.pt))hit=(Math.abs(ch-e.pt)<=60);
        if(!hit)continue;
        var p=e.p,has=false,k;for(k in p){if(/(^|[_\s(\[])d0([_\s)\].]|$)|fwd.?d0|deflection|central.?defl/i.test(k)&&p[k]!=null&&p[k]!==''){has=true;break;}}
        if(has){props=props?props:{};for(k in p)props[k]=p[k];}
      }
      if(!props)return null;
      function valByRe(re){for(var k in props){if(re.test(k)){var v=+props[k];if(!isNaN(v))return v;}}return null;}
      var d0=valByRe(/(^|[_\s(\[])d0([_\s)\].]|$)|fwd.?d0|deflection|central.?defl/i);
      var ds=[];for(var n=1;n<=8;n++){var v=valByRe(new RegExp('(^|[_\\s(\\[])d'+n+'([_\\s)\\].]|$)|fwd.?d'+n,'i'));if(v!=null)ds.push(['D'+n,v]);}
      return {d0:d0,ds:ds};
    };
    KL.at=function(road,ch){
      KL.sync();
      var arr=KL.byRoad[road];if(!arr)return {};
      var hits=[],near={};
      for(var i=0;i<arr.length;i++){var e=arr[i],d=Infinity,hit=false;
        if(!isNaN(e.from)&&!isNaN(e.to)){var lo=Math.min(e.from,e.to),hi=Math.max(e.from,e.to);if(ch>=lo&&ch<=hi){hit=true;d=0;}else d=Math.min(Math.abs(ch-lo),Math.abs(ch-hi));}
        else if(!isNaN(e.pt)){d=Math.abs(ch-e.pt);hit=(d<=30);}
        else {hit=true;d=0;}
        if(hit)hits.push(e.p);
        if(!(e.layer in near)||d<near[e.layer].d)near[e.layer]={d:d,p:e.p};
      }
      var out={},k;
      for(var L in near){var np=near[L].p;for(k in np)out[k]=np[k];}
      for(var h=0;h<hits.length;h++){var hp=hits[h];for(k in hp)out[k]=hp[k];}
      return out;
    };
    /* nearest condition segment's full props (for the PCI engine) */
    KL.segAt=function(road,ch){
      var arr=KL.byRoad[road];if(!arr)return null;var best=null,bd=Infinity;
      for(var i=0;i<arr.length;i++){var e=arr[i];if(e.layer!=='segments'&&e.layer!=='segs')continue;var d=Infinity;
        if(!isNaN(e.from)&&!isNaN(e.to)){var lo=Math.min(e.from,e.to),hi=Math.max(e.from,e.to);d=(ch>=lo&&ch<=hi)?0:Math.min(Math.abs(ch-lo),Math.abs(ch-hi));}
        else d=0;
        if(d<bd){bd=d;best=e.p;}}
      return best;
    };
    /* PCI computed LIVE by the real IRC:82-2023 engine (segPCI): avg=Composite, worst=Worst-Lane.
       Works at any chainage without generating the layer; persists per road. */
    KL.pci=function(road,ch){
      KL.sync();
      var compKeys=['pci_def_avg','pci_avg','composite_pci','comp_pci','avg_pci','road_pci','pci_composite','pci'],worstKeys=['pci_def_worst','pci_worst','worst_pci','worst_lane_pci','lane_worst_pci'];
      function rd(p,keys){if(!p)return null;for(var i=0;i<keys.length;i++){var x=p[keys[i]];if(x!=null&&x!==''){var n=+x;if(!isNaN(n)&&n>=0)return n;}}return null;}
      function calc(p,basis){if(typeof segPCI==='function'){try{var v=segPCI(p,basis);if(v!=null&&!isNaN(v))return Math.round(v*10)/10;}catch(e){}}return null;}
      var sp=KL.segAt(road,ch),comp=null,worst=null;
      if(sp){comp=calc(sp,'avg');if(comp==null)comp=rd(sp,compKeys);worst=calc(sp,'worst');if(worst==null)worst=rd(sp,worstKeys);}
      var cache=KL._pciCache[road]||(KL._pciCache[road]={comp:null,worst:null});
      if(comp!=null)cache.comp=comp;if(worst!=null)cache.worst=worst;
      if(cache.comp==null||cache.worst==null){var arr=KL.byRoad[road]||[],aC=[],aW=[];
        for(var i=0;i<arr.length;i++){if(arr[i].layer!=='segments'&&arr[i].layer!=='segs')continue;var p=arr[i].p;
          var cc=calc(p,'avg');if(cc==null)cc=rd(p,compKeys);if(cc!=null)aC.push(cc);
          var ww=calc(p,'worst');if(ww==null)ww=rd(p,worstKeys);if(ww!=null)aW.push(ww);}
        if(cache.comp==null&&aC.length)cache.comp=Math.round((aC.reduce(function(x,y){return x+y;},0)/aC.length)*10)/10;
        if(cache.worst==null&&aW.length)cache.worst=Math.min.apply(null,aW);}
      return {comp:(comp!=null?comp:cache.comp),worst:(worst!=null?worst:cache.worst)};
    };
    if(window.fetch&&!window.__klFetchWrapped){window.__klFetchWrapped=true;var of=window.fetch;
      window.fetch=function(input){var url=(typeof input==='string')?input:(input&&input.url)||'';var pr=of.apply(this,arguments);
        if(/\/api\//i.test(url)&&!/\/segments\//i.test(url)){pr.then(function(res){try{res.clone().json().then(function(j){KL.ingestUrl(url,j);}).catch(function(){});}catch(e){}});}
        return pr;};}
    /* once the map exists, also capture sources as soon as they receive data */
    (function hook(){var m=window.map;if(m&&m.on){m.on('sourcedata',function(e){if(e&&e.isSourceLoaded)KL.sync();});}else setTimeout(hook,400);})();
  })();
