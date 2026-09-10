/* map-layer-controls — lifted out of an inline <script> in map.html so the page carries
   no inline script, which is what lets script-src drop 'unsafe-inline'. */
(function(){
  function init(){
    if(typeof map==='undefined'||!map||!map.getLayer){return setTimeout(init,300);}
    var LMAP={
      showRoads:{layers:['roadnet','roadnet-casing'],color:'#8a4d1f'},
      showRoads2:{layers:['roadnet2','roadnet2-casing','fullnet','fullnet-casing','roads2','roads2-line'],color:'#3a4ad6'},
      showCond:{layers:['seg-CC','seg-CL1','seg-CL2','seg-CR1','seg-CR2'],color:'#2ba66a',grad:1},
      showFwd:{layers:['as-fwd','as-fwd-pt','as-fwd-icon'],color:'#7b1fa2'},
      showIri2km:{layers:['iri2km'],color:'#c2410c',grad:1},
      showDist:{layers:['district-fill','district-casing','district-line','district-label'],color:'#0e2038'},
      showCons:{layers:['cons-fill','cons-line','cons-label'],color:'#0d7a51'},
      showBridge:{layers:['as-bridge','as-bridge-icon'],color:'#8a5cb8'},
      showCulvert:{layers:['as-culvert','as-culvert-icon'],color:'#e07b2a'},
      showFurnL:{layers:['as-furnl','as-furnl-icon'],color:'#0fa3a3'},
      showFurnP:{layers:['as-furnp','as-furnp-icon'],color:'#3b6fa0'},
      showPciAvg:{layers:['pci-avg'],color:'#157f3c',grad:1},
      showPciWorst:{layers:['pci-worst'],color:'#e8590c',grad:1},
      showTraffic:{layers:['trafficstn-lyr'],color:'#1565c0'},
      showSoil:{layers:['as-soil','as-soil-icon'],color:'#8a4d1f'},
      showCore:{layers:['as-core','as-core-icon'],color:'#2b2b2b'},
      showCrust:{layers:['as-crust','as-crust-icon'],color:'#b8860b'}
    };
    var NAMES={'#8a4d1f':'Brown','#3a4ad6':'Blue','#2ba66a':'Green','#7b1fa2':'Purple','#0e2038':'Navy','#0d7a51':'Green','#8a5cb8':'Violet','#e07b2a':'Orange','#0fa3a3':'Teal','#3b6fa0':'Steel','#157f3c':'Green','#e8590c':'Orange','#1565c0':'Blue','#2b2b2b':'Black','#b8860b':'Gold'};
    function setOpacity(layers,v){
      /* Road network keeps zoom-based opacity (thin + no dark casing when zoomed
         out). A flat setPaintProperty(line-opacity, 1) was wiping that curve and
         restoring the statewide dark blotch whenever the Layers card refreshed. */
      if(layers.indexOf('roadnet')>=0&&typeof applyNetUserOpacity==='function'){
        applyNetUserOpacity(v);
        return;
      }
      layers.forEach(function(id){if(!map.getLayer(id))return;var t=map.getLayer(id).type;try{
      if(t==='line')map.setPaintProperty(id,'line-opacity',v);
      else if(t==='circle')map.setPaintProperty(id,'circle-opacity',v);
      else if(t==='symbol'){try{map.setPaintProperty(id,'icon-opacity',v);}catch(e){}try{map.setPaintProperty(id,'text-opacity',v);}catch(e){}}
      else if(t==='fill')map.setPaintProperty(id,'fill-opacity',Math.min(0.6,v*0.45));
    }catch(e){}});}
    Object.keys(LMAP).forEach(function(id){
      var cb=document.getElementById(id);if(!cb)return;
      var sw=cb.closest?cb.closest('.switch'):null;if(!sw||sw.__lx)return;sw.__lx=1;
      var cfg=LMAP[id];
      var lx=document.createElement('div');lx.className='lx';
      var swatch;
      if(cfg.grad){
        /* PCI uses the IRC 6-band scale; condition / IRI use Good→Fair→Poor. */
        var g=(id==='showPciAvg'||id==='showPciWorst')
          ?'linear-gradient(90deg,#c92a2a,#e8590c,#f08c00,#f2c200,#7cb518,#157f3c)'
          :'linear-gradient(90deg,#2ba66a,#FFC400,#da4b43)';
        swatch='<span class="lx-grad" style="background:'+g+'"></span>Graded scale';
      }else{
        swatch='<i style="background:'+cfg.color+'"></i>'+(NAMES[cfg.color.toLowerCase()]||'');
      }
      lx.innerHTML='<div class="lx-div"></div>'+
        '<div class="lx-row"><span class="lx-k">Colour</span><span class="lx-col">'+swatch+'</span></div>'+
        '<div class="lx-row"><span class="lx-k">Opacity</span><input type="range" class="lx-op" min="15" max="100" value="100"><span class="lx-val">100%</span></div>';
      sw.parentNode.insertBefore(lx,sw.nextSibling);
      var rng=lx.querySelector('.lx-op'),val=lx.querySelector('.lx-val');
      rng.addEventListener('input',function(){val.textContent=rng.value+'%';setOpacity(cfg.layers,+rng.value/100);});
      function reflect(){lx.classList.toggle('on',cb.checked);if(cb.checked)[140,700,1700].forEach(function(d){setTimeout(function(){if(cb.checked)setOpacity(cfg.layers,+rng.value/100);},d);});}
      cb.addEventListener('change',reflect);reflect();
    });
  }
  if(document.readyState!=='loading')init();else document.addEventListener('DOMContentLoaded',init);
})();
