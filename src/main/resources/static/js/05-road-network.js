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
function renderNetFilters(){
  const box=document.getElementById('netFilters');box.innerHTML='';
  netFilters.forEach((f,i)=>{
    const m=ATTRS[f.attr]||{numeric:false,values:[]};
    const row=document.createElement('div');row.className='frow';
    const as=Object.keys(ATTRS).sort().map(k=>`<option ${k===f.attr?'selected':''}>${k}</option>`).join('');
    const ops=m.numeric?['>','>=','=','<=','<']:['=','contains'];
    const os=ops.map(o=>`<option ${o===f.op?'selected':''}>${o}</option>`).join('');
    const listId='dl'+i;
    const dl=!m.numeric?`<datalist id="${listId}">${m.values.map(v=>`<option value="${v}">`).join('')}</datalist>`:'';
    row.innerHTML=`<select>${as}</select><select>${os}</select><input ${m.numeric?'type="number" step="0.1"':'type="text" list="'+listId+'"'} value="${f.val}">${dl}<span class="x">&times;</span>`;
    const sels=row.querySelectorAll('select');const iv=row.querySelector('input');
    sels[0].onchange=e=>{f.attr=e.target.value;f.op='=';f.val='';renderNetFilters();applyNetFilter();};
    sels[1].onchange=e=>{f.op=e.target.value;applyNetFilter();};
    iv.oninput=e=>{f.val=e.target.value;applyNetFilter();};
    row.querySelector('.x').onclick=()=>{netFilters.splice(i,1);renderNetFilters();applyNetFilter();};
    box.appendChild(row);
  });
}
function netFilterExpr(){
  const rows=netFilters.filter(f=>f.attr&&f.val!=='');
  if(!rows.length)return null;
  const parts=rows.map(f=>{
    const m=ATTRS[f.attr]||{};
    if(m.numeric){const map_={'>':'>','>=':'>=','=':'==','<=':'<=','<':'<'};return [map_[f.op]||'==',['to-number',['coalesce',['get',f.attr],-999999]],+f.val];}
    if(f.op==='contains')return ['in',f.val,['to-string',['coalesce',['get',f.attr],'']]];
    return ['==',['to-string',['coalesce',['get',f.attr],'']],f.val];
  });
  return [netMode==='all'?'all':'any',...parts];
}
function applyNetFilter(){
  const ex=netFilterExpr();
  if(map.getLayer('roadnet'))map.setFilter('roadnet',ex);if(map.getLayer('roadnet-casing'))map.setFilter('roadnet-casing',ex);
  const rows=netFilters.filter(f=>f.attr&&f.val!=='');
  let info='',list=null;
  if(rows.length){
    list=Object.values(ROADS).filter(f=>{const p=f.properties;const t=rows.map(r=>{const m=ATTRS[r.attr]||{};const raw=p[r.attr];if(raw==null||raw==='')return false;if(m.numeric){const v=+raw,c=+r.val;switch(r.op){case'>':return v>c;case'>=':return v>=c;case'<':return v<c;case'<=':return v<=c;default:return v==c;}}const s=String(raw);return r.op==='contains'?s.toLowerCase().includes(String(r.val).toLowerCase()):s===String(r.val);});return netMode==='all'?t.every(Boolean):t.some(Boolean);});
    info=list.length+' of '+Object.keys(ROADS).length+' roads match';
  }
  document.getElementById('netMatchInfo').textContent=info;
  if(_netFitT)clearTimeout(_netFitT);
  if(list&&list.length){const fl=list;_netFitT=setTimeout(()=>fitFeaturesBounds(fl),550);}
}
