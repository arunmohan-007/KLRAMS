/* ============================================================
   KLRAMS viewer · 05-road-network.js
   Road-network attribute metadata with colour-by and filter-by controls.
   Loaded as an ordered classic script from map.html; all modules
   share one global scope, so load order is preserved exactly.
   ============================================================ */
// ---- road network: attribute metadata, colour-by, filter-by ----
const SKIP_ATTRS=new Set(['road','name','len','id']);
const CAT_PALETTE=['#e0a33a','#3b6fa0','#2ba66a','#da4b43','#8a5cb8','#0fa3a3','#c2628e','#7a8b2f','#b06a2c','#5470c6','#9a6324','#46728e'];
let ATTRS={}, netMode='all', netFilters=[];
let _netFitT=null;
function buildAttrMeta(gj){
  ATTRS={};
  const keys=new Set();
  gj.features.forEach(f=>Object.keys(f.properties||{}).forEach(k=>keys.add(k)));
  keys.forEach(k=>{
    if(SKIP_ATTRS.has(k))return;
    let numeric=true; const distinct=new Set(); let min=Infinity,max=-Infinity; let seen=0;
    for(const f of gj.features){
      const v=f.properties[k]; if(v==null||v==='')continue; seen++;
      const n=+v;
      if(isNaN(n)) numeric=false; else {min=Math.min(min,n);max=Math.max(max,n);}
      if(distinct.size<=40) distinct.add(String(v));
    }
    if(!seen)return;
    ATTRS[k]={numeric:numeric&&min!==Infinity,min,max,values:[...distinct].sort()};
  });
  const sel=document.getElementById('netColorBy');
  sel.innerHTML='<option value="__class__">Default (SH / MDR)</option>';
  Object.keys(ATTRS).sort().forEach(k=>{const o=document.createElement('option');o.value=k;o.textContent=k+(ATTRS[k].numeric?' (numeric)':'');sel.appendChild(o);});
}
function netColorByExpr(attr){
  const m=ATTRS[attr];
  if(!m)return netColor();
  if(m.numeric){
    const lo=m.min,hi=m.max===m.min?m.min+1:m.max;
    return ['interpolate',['linear'],['to-number',['coalesce',['get',attr],lo]],lo,'#9ec97f',(lo+hi)/2,'#e4a13a',hi,'#c0392b'];
  }
  const e=['match',['to-string',['get',attr]]];
  m.values.slice(0,12).forEach((v,i)=>{e.push(v,CAT_PALETTE[i%CAT_PALETTE.length]);});
  e.push('#9aa7b5');
  return e;
}
function renderNetLegend(attr){
  const el=document.getElementById('netLegend'); el.innerHTML='';
  const m=ATTRS[attr];
  if(!m){el.innerHTML='<div class="lg"><span class="bar" style="background:#8a4d1f"></span><span class="lgt">SH</span></div><div class="lg"><span class="bar" style="background:#3b6fa0"></span><span class="lgt">MDR</span></div>';return;}
  if(m.numeric){el.innerHTML=`<div class="lg"><span class="bar" style="background:linear-gradient(90deg,#9ec97f,#e4a13a,#c0392b)"></span><span class="lgt">${m.min} → ${m.max}</span></div>`;return;}
  m.values.slice(0,12).forEach((v,i)=>{const lbl=dec(attr,v);el.innerHTML+=`<div class="lg"><span class="bar" style="background:${CAT_PALETTE[i%CAT_PALETTE.length]}"></span><span class="lgt" title="${lbl}">${lbl}</span></div>`;});
  if(m.values.length>12)el.innerHTML+='<div class="lg"><span class="bar" style="background:#9aa7b5"></span><span class="lgt">other</span></div>';
}
function setNetMode(m){netMode=m;document.getElementById('nAll').classList.toggle('on',m==='all');document.getElementById('nAny').classList.toggle('on',m==='any');applyNetFilter();}
function addNetFilter(){netFilters.push({attr:Object.keys(ATTRS).sort()[0]||'',op:'=',val:''});renderNetFilters();}
function clearNetFilters(){netFilters=[];renderNetFilters();applyNetFilter();}
/* Build 167 — multi-value conditions via a click-to-open picker, not typing.
   The value cell is a BUTTON (never a free-text field) showing the chosen
   values; clicking it opens a popup with its OWN search box, a scrollable
   checklist and All/Clear. Because the actual filter value is only ever set
   by ticking a checkbox, a typo in the search box just narrows the list —
   it can never corrupt the filter into matching nothing. */
function nfVals(f){return String(f.val==null?'':f.val).split(',').map(s=>s.trim()).filter(s=>s!=='');}
let _nfPopRow=-1,_nfPopQuery='';
function nfCloseValPop(){const p=document.getElementById('nfValPop');if(p)p.remove();_nfPopRow=-1;_nfPopQuery='';}
function nfRefreshRowButton(i,f,m){
  const rowEl=document.querySelectorAll('#netFilters .frow')[i];if(!rowEl)return;
  const btn=rowEl.querySelector('.valbtn');if(!btn)return;
  const sel=nfVals(f);
  btn.querySelector('.vb-txt').textContent=sel.length?sel.join(', '):(m.numeric?'Number(s)…':'Select value(s)…');
  btn.classList.toggle('has',sel.length>0);
  btn.title=sel.length?sel.join(', '):'Click to choose value(s)';
}
function nfPopList(f,m){
  const p=document.getElementById('nfValPop');if(!p)return;
  const esc=v=>String(v==null?'':v).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  const known=m.values.map(String);
  const sel=nfVals(f).filter(v=>known.indexOf(v)>=0);
  const q=_nfPopQuery.trim().toLowerCase();
  const items=known.filter(v=>!q||v.toLowerCase().indexOf(q)>=0);
  p.querySelector('#nvpCnt').textContent=sel.length+' selected · '+known.length+' value'+(known.length===1?'':'s');
  p.querySelector('#nvpList').innerHTML=items.length
    ?items.map(v=>'<label class="nvp-it"><span>'+esc(v)+'</span><input type="checkbox" value="'+esc(v)+'"'+(sel.indexOf(v)>=0?' checked':'')+'></label>').join('')
    :'<div class="nvp-empty">No values match “'+esc(_nfPopQuery)+'”.</div>';
  p.querySelectorAll('#nvpList input').forEach(cb=>{cb.onchange=()=>{
    let vals=nfVals(f).filter(v=>known.indexOf(v)>=0);
    if(cb.checked){if(vals.indexOf(cb.value)<0)vals.push(cb.value);}else{vals=vals.filter(v=>v!==cb.value);}
    f.val=vals.join(', ');
    applyNetFilter();nfRefreshRowButton(_nfPopRow,f,m);nfPopList(f,m);
  };});
}
function nfOpenValPop(i,f,m,anchor){
  nfCloseValPop();_nfPopRow=i;_nfPopQuery='';
  const p=document.createElement('div');p.className='nvp';p.id='nfValPop';
  p.innerHTML='<div class="nvp-top"><input type="text" class="nvp-q" placeholder="Search values…" autocomplete="off">'
    +'<button type="button" class="nvp-all">All</button><button type="button" class="nvp-clear">Clear</button></div>'
    +'<div class="nvp-list" id="nvpList"></div><div class="nvp-cnt" id="nvpCnt"></div>';
  document.body.appendChild(p);
  const r=anchor.getBoundingClientRect(),W=Math.max(240,Math.min(360,window.innerWidth-24));
  p.style.width=W+'px';
  p.style.left=Math.max(8,Math.min(r.left,window.innerWidth-W-8))+'px';
  p.style.top=(r.bottom+5)+'px';
  p.style.maxHeight=Math.max(160,Math.min(320,window.innerHeight-r.bottom-16))+'px';
  p.querySelector('.nvp-clear').onclick=()=>{f.val='';applyNetFilter();nfRefreshRowButton(i,f,m);nfPopList(f,m);};
  p.querySelector('.nvp-all').onclick=()=>{f.val=m.values.map(String).join(', ');applyNetFilter();nfRefreshRowButton(i,f,m);nfPopList(f,m);};
  const q=p.querySelector('.nvp-q');
  q.oninput=e=>{_nfPopQuery=e.target.value;nfPopList(f,m);};
  nfPopList(f,m);
  setTimeout(()=>{try{q.focus();}catch(e){}},0);
}
document.addEventListener('mousedown',function(e){
  if(_nfPopRow>=0&&!e.target.closest('#nfValPop')&&!e.target.closest('.valbtn'))nfCloseValPop();
},true);
document.addEventListener('scroll',function(e){
  const p=document.getElementById('nfValPop');
  if(p&&!(e.target&&p.contains&&e.target.nodeType===1&&p.contains(e.target)))nfCloseValPop();
},true);
window.addEventListener('resize',nfCloseValPop);
function renderNetFilters(){
  nfCloseValPop();
  const box=document.getElementById('netFilters');box.innerHTML='';
  netFilters.forEach((f,i)=>{
    const m=ATTRS[f.attr]||{numeric:false,values:[]};
    const row=document.createElement('div');row.className='frow';
    const vq=String(f.val==null?'':f.val).replace(/"/g,'&quot;');
    const as=Object.keys(ATTRS).sort().map(k=>`<option ${k===f.attr?'selected':''}>${k}</option>`).join('');
    const ops=m.numeric?['>','>=','=','<=','<']:['=','contains'];
    const os=ops.map(o=>`<option ${o===f.op?'selected':''}>${o}</option>`).join('');
    const listId='dl'+i;
    let valCell,isBtn=false;
    if(f.op==='='&&m.values.length){
      /* click-to-open picker — the filter value is only ever set by ticking a
         checkbox, so this can never be "misspelled" into matching nothing */
      isBtn=true;
      const sel=nfVals(f).filter(v=>m.values.map(String).indexOf(v)>=0);
      const txt=sel.length?sel.join(', '):(m.numeric?'Number(s)…':'Select value(s)…');
      valCell=`<button type="button" class="valbtn${sel.length?' has':''}" title="${sel.length?sel.join(', ').replace(/"/g,'&quot;'):'Click to choose value(s)'}"><span class="vb-txt">${txt.replace(/</g,'&lt;')}</span><i class="vb-arr">&#9662;</i></button>`;
    }else if(m.numeric&&f.op==='='){
      valCell=`<input type="text" value="${vq}" placeholder="2, 8" title="One or more numbers, separated by commas">`;
    }else if(m.numeric){
      valCell=`<input type="number" step="0.1" value="${vq}">`;
    }else{
      const dl=`<datalist id="${listId}">${m.values.map(v=>`<option value="${String(v).replace(/"/g,'&quot;')}">`).join('')}</datalist>`;
      valCell=`<input type="text" list="${listId}" value="${vq}">${dl}`;
    }
    row.innerHTML=`<select>${as}</select><select>${os}</select>${valCell}<span class="x">&times;</span>`;
    const sels=row.querySelectorAll('select');
    sels[0].onchange=e=>{f.attr=e.target.value;f.op='=';f.val='';renderNetFilters();applyNetFilter();};
    sels[1].onchange=e=>{f.op=e.target.value;f.val='';renderNetFilters();applyNetFilter();};
    if(isBtn){
      const btn=row.querySelector('.valbtn');
      btn.onclick=()=>{if(_nfPopRow===i){nfCloseValPop();}else{nfOpenValPop(i,f,m,btn);}};
    }else{
      const iv=row.querySelector('input');
      iv.oninput=e=>{f.val=e.target.value;applyNetFilter();};
    }
    row.querySelector('.x').onclick=()=>{netFilters.splice(i,1);renderNetFilters();applyNetFilter();};
    box.appendChild(row);
  });
}
function netFilterExpr(){
  const rows=netFilters.filter(f=>f.attr&&f.val!=='');
  if(!rows.length)return null;
  const parts=rows.map(f=>{
    const m=ATTRS[f.attr]||{};
    if(m.numeric){
      if(f.op==='='){const nums=nfVals(f).map(Number).filter(n=>!isNaN(n));return ['in',['to-number',['coalesce',['get',f.attr],-999999]],['literal',nums]];}
      const map_={'>':'>','>=':'>=','<=':'<=','<':'<'};
      return [map_[f.op]||'==',['to-number',['coalesce',['get',f.attr],-999999]],+f.val];
    }
    if(f.op==='contains')return ['in',f.val,['to-string',['coalesce',['get',f.attr],'']]];
    return ['in',['to-string',['coalesce',['get',f.attr],'']],['literal',nfVals(f)]];
  });
  return [netMode==='all'?'all':'any',...parts];
}
function applyNetFilter(){
  const ex=netFilterExpr();
  if(map.getLayer('roadnet'))map.setFilter('roadnet',ex);if(map.getLayer('roadnet-casing'))map.setFilter('roadnet-casing',ex);
  const rows=netFilters.filter(f=>f.attr&&f.val!=='');
  let info='',list=null;
  if(rows.length){
    list=Object.values(ROADS).filter(f=>{const p=f.properties;const t=rows.map(r=>{
      const m=ATTRS[r.attr]||{};const raw=p[r.attr];if(raw==null||raw==='')return false;
      if(m.numeric){
        const v=+raw;
        if(r.op==='=')return nfVals(r).map(Number).some(n=>!isNaN(n)&&v==n);
        const c=+r.val;switch(r.op){case'>':return v>c;case'>=':return v>=c;case'<':return v<c;case'<=':return v<=c;default:return v==c;}
      }
      const s=String(raw);
      if(r.op==='contains')return s.toLowerCase().includes(String(r.val).toLowerCase());
      return nfVals(r).indexOf(s)>=0;
    });return netMode==='all'?t.every(Boolean):t.some(Boolean);});
    info=list.length+' of '+Object.keys(ROADS).length+' roads match';
  }
  document.getElementById('netMatchInfo').textContent=info;
  /* Build 163 — scope every road-linked layer to the filtered roads */
  window.NET_SCOPE=(rows.length&&list)?new Set(list.map(f=>String(f.properties.road))):null;
  if(typeof applyNetScope==='function')applyNetScope();
  renderNetScopeCard(list,rows);
  if(_netFitT)clearTimeout(_netFitT);
  if(list&&list.length){const fl=list;_netFitT=setTimeout(()=>fitFeaturesBounds(fl),550);}
}

/* ============================================================
   Build 163 — on-map filter summary card (#netScopeCard).
   Shows the active Road Network filter criteria, live counts of
   everything in scope (sections, length, condition segments,
   bridges, culverts, FWD, traffic…) and the owners of the matched
   roads. Lazy layers refresh the card as they load. Close hides it
   until the filter criteria change again.
   ============================================================ */
let _nscState=null,_nscClosedSig=null,_nscLoadKicked=false;
function closeNetScopeCard(){const el=document.getElementById('netScopeCard');if(el)el.classList.remove('show');_nscClosedSig=_nscState?_nscState.sig:null;}
function toggleNetScopeCard(){
  const el=document.getElementById('netScopeCard'),b=document.getElementById('nscMin');if(!el)return;
  const min=!el.classList.contains('min');
  el.classList.toggle('min',min);
  if(b){b.innerHTML=min?'&plus;':'&minus;';b.title=min?'Expand':'Minimize';}
}
/* The card counts every road-linked dataset, so any that were never toggled on
   are fetched in the background the first time a filter activates. Their layers
   stay hidden (visibility follows the checkboxes); each loader refreshes the
   card via updateNetScopeCard() when it finishes. */
function ensureScopeDatasets(){
  if(_nscLoadKicked)return;_nscLoadKicked=true;
  const wanted={bridge:1,culvert:1,fwd:1,subgrade:1,bituminous_core:1}; /* only the datasets the card shows */
  try{if(typeof ASSETS!=='undefined'&&typeof loadAsset==='function')ASSETS.forEach(a=>{if(wanted[a.type]&&(typeof ASSET_DATA==='undefined'||!ASSET_DATA[a.type]))loadAsset(a);});}catch(e){}
  try{if((typeof DATA==='undefined'||!DATA||!DATA.features)&&typeof loadSegments==='function')loadSegments();}catch(e){}
  try{if(typeof TRAFFIC_LOADED!=='undefined'&&!TRAFFIC_LOADED&&typeof loadTraffic==='function')loadTraffic();}catch(e){}
}
function _nscCountIn(feats,prop){let n=0;(feats||[]).forEach(f=>{const p=(f&&f.properties)||{};if(window.NET_SCOPE.has(String(p[prop]!=null?p[prop]:'')))n++;});return n;}
function renderNetScopeCard(list,rows){
  const el=document.getElementById('netScopeCard');if(!el)return;
  if(!window.NET_SCOPE||!rows||!rows.length){el.classList.remove('show');_nscState=null;return;}
  const sig=JSON.stringify(rows.map(r=>[r.attr,r.op,r.val]))+'|'+netMode;
  _nscState={list:list||[],rows:rows,sig:sig};
  if(_nscClosedSig===sig)return;               /* user closed this exact filter's card */
  _nscClosedSig=null;
  /* criteria chips */
  const xe=t=>String(t==null?'':t).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  document.getElementById('nscCrit').innerHTML=rows.map(r=>'<span class="nc"><i>'+xe(r.attr)+' '+xe(r.op)+'</i><span>'+xe(r.val)+'</span></span>').join('')
    +(rows.length>1?('<span class="nc" style="color:#9fb2cd;border-color:rgba(120,160,215,.3);background:rgba(120,160,215,.08)"><i>match</i> '+(netMode==='all'?'ALL':'ANY')+'</span>'):'');
  /* stat tiles — only datasets that are actually loaded */
  const L=list||[];
  /* Network length = sum of the roads' MEASURED length attribute (Measrd_Len
     or similar); falls back to len when no measured-length column exists. */
  const num=v=>{const n=parseFloat(String(v==null?'':v).replace(/,/g,''));return isNaN(n)?0:n;};
  let lenM=0;
  L.forEach(f=>{const p=f.properties||{};const mk=Object.keys(p).find(k=>/meas/i.test(k)&&/len/i.test(k));lenM+=num(mk!=null?p[mk]:p.len)||num(p.len);});
  const tiles=[['#19b277',L.length,'Road section'+(L.length===1?'':'s')],['#3b86e6',(lenM/1000).toFixed(1)+' km','Length']];
  /* Chainage extent is shown ONLY when the filter is a single condition on
     Road Name or Road Number with a single value. Any additional condition
     (or a multi-value comma list) hides it. */
  if(rows.length===1&&/road.?(name|num)/i.test(rows[0].attr)&&nfVals(rows[0]).length===1){
    const endKeys=['Rd_End_cha','End_Chaina','end_chainage','Road_End_Chainage','Rd_End_Cha','End_Chainage'];
    let maxEnd=0;
    L.forEach(f=>{const p=f.properties||{};for(let i=0;i<endKeys.length;i++){if(p[endKeys[i]]!=null&&p[endKeys[i]]!==''){maxEnd=Math.max(maxEnd,num(p[endKeys[i]]));break;}}});
    tiles.push(['#e6c878','0 m','Road start chainage']);
    if(maxEnd>0)tiles.push(['#e6c878',Math.round(maxEnd).toLocaleString()+' m','Road end chainage']);
  }
  /* Condition data available = km of in-scope condition segments (to_ch - from_ch) */
  if(typeof DATA!=='undefined'&&DATA&&DATA.features){
    let condM=0;
    DATA.features.forEach(f=>{const p=(f&&f.properties)||{};if(!window.NET_SCOPE.has(String(p.road!=null?p.road:'')))return;condM+=Math.max(0,num(p.to_ch)-num(p.from_ch));});
    tiles.push(['#2ba66a',(condM/1000).toFixed(1)+' km','Condition data available']);
  }
  const AD=(typeof ASSET_DATA!=='undefined')?ASSET_DATA:{};
  [['bridge','#8a5cb8','Bridges'],['culvert','#e07b2a','Culverts'],['fwd','#7b1fa2','FWD points'],['subgrade','#8a4d1f','Soil tests'],['bituminous_core','#5c6470','Bituminous core test']]
    .forEach(t=>{const gj=AD[t[0]];if(gj&&gj.features)tiles.push([t[1],_nscCountIn(gj.features,'__sec'),t[2]]);});
  if(typeof TRAFFIC_STN!=='undefined'&&TRAFFIC_STN.features&&TRAFFIC_STN.features.length)tiles.push(['#1565c0',_nscCountIn(TRAFFIC_STN.features,'section'),'Traffic stations']);
  document.getElementById('nscStats').innerHTML=tiles.map(t=>'<span class="nsc-stat" style="--sc:'+t[0]+'"><span class="n">'+t[1]+'</span><span class="l">'+t[2]+'</span></span>').join('');
  /* owners of the matched roads — prefer the "Current owner" attribute
     (Current_Ow / Current_Owner…) over any other owner-ish column */
  const attrKeys=Object.keys(ATTRS);
  const propKeys=L.length?Object.keys(L[0].properties||{}):[];
  const findKey=re=>attrKeys.find(k=>re.test(k))||propKeys.find(k=>re.test(k));
  const ownKey=findKey(/current[ _]?ow/i)||findKey(/owner/i);
  let ownHtml='';
  if(ownKey){
    const set={};L.forEach(f=>{const v=f.properties[ownKey];if(v!=null&&v!=='')set[String(v).trim()]=1;});
    const dv=v=>(typeof decodeVal==='function')?decodeVal('Owner',v):v;
    const owners=Object.keys(set).map(dv).sort();
    if(owners.length)ownHtml='<span class="ol">Current owner</span>'+owners.slice(0,6).map(o=>'<span class="oc">'+xe(o)+'</span>').join('')+(owners.length>6?('<span class="oc">+'+(owners.length-6)+' more</span>'):'');
  }
  const ownEl=document.getElementById('nscOwn');ownEl.innerHTML=ownHtml;ownEl.style.display=ownHtml?'':'none';
  el.classList.add('show');
  ensureScopeDatasets();
}
/* lazy datasets call this as they finish loading, so counts stay live */
function updateNetScopeCard(){if(_nscState&&window.NET_SCOPE)renderNetScopeCard(_nscState.list,_nscState.rows);}

/* ============================================================
   Build 163 — network scope.
   When the Road Network filter is active, every road-linked data
   layer (condition seg-*, PCI, FWD / soil / core / crust / bridges /
   culverts / furniture as-*, traffic stations) is limited to the
   matching roads. Implemented by wrapping map.setFilter/addLayer so
   the scope survives each module re-setting its own filters: the
   caller's filter is remembered as the "base" and the scope
   membership test is AND-ed on top of it.
   ============================================================ */
window.NET_SCOPE=null;
const _scopeBase={};
function scopePropFor(id){
  if(id.indexOf('seg-')===0)return 'road';
  if(id==='pci-avg'||id==='pci-worst')return 'road';
  if(id==='trafficstn-lyr')return 'section';
  if(id==='roadnet-hit')return 'road';
  if(id.indexOf('as-')===0)return '__sec';
  return null;
}
function scopeExpr(prop){return ['in',['to-string',['coalesce',['get',prop],'']],['literal',Array.from(window.NET_SCOPE)]];}
function scopeCombine(id,f){const p=scopePropFor(id);if(!p||!window.NET_SCOPE)return f;return (f==null)?scopeExpr(p):['all',f,scopeExpr(p)];}
(function(){
  const _sf=map.setFilter.bind(map),_al=map.addLayer.bind(map);
  map.setFilter=function(id,f){_scopeBase[id]=(f==null)?null:f;return _sf(id,scopeCombine(id,(f==null)?null:f));};
  map.addLayer=function(def,before){
    const r=_al(def,before);
    try{if(def&&def.id&&scopePropFor(def.id)){_scopeBase[def.id]=def.filter||null;if(window.NET_SCOPE)_sf(def.id,scopeCombine(def.id,_scopeBase[def.id]));}}catch(e){}
    return r;
  };
  window.applyNetScope=function(){
    try{
      (map.getStyle().layers||[]).forEach(function(L){
        if(!scopePropFor(L.id))return;
        if(!(L.id in _scopeBase)){const cur=map.getFilter(L.id);_scopeBase[L.id]=(cur==null)?null:cur;}
        _sf(L.id,scopeCombine(L.id,_scopeBase[L.id]));
      });
    }catch(e){}
  };
})();