/* Government Orders portal (go.html).
   Lifted out of an inline <script> so the page carries no inline script,
   which is what lets script-src drop 'unsafe-inline'. */
const API='/api/go';
let FOLDERS=[], DOCS=[];

function esc(s){return String(s==null?'':s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));}
function fmtSize(b){b=+b||0;if(b<1024)return b+' B';if(b<1048576)return (b/1024).toFixed(0)+' KB';return (b/1048576).toFixed(1)+' MB';}
function fmtDate(s){if(!s)return '';const d=new Date(s);if(isNaN(d))return '';return d.toLocaleDateString('en-GB',{day:'2-digit',month:'short',year:'numeric'});}
function topOf(n){const i=n.indexOf('/');return i<0?n:n.slice(0,i);}

async function jget(u){const r=await fetch(u);if(!r.ok)throw new Error(r.status);return r.json();}

async function loadAll(){
  try{FOLDERS=await jget(API+'/folders');}catch(e){FOLDERS=[];}
  try{DOCS=await jget(API+'/docs');}catch(e){DOCS=[];}
  renderHome(); fillSelects(); renderFolderList();
}

function goItem(d,opts){
  opts=opts||{};
  const sub=[];
  if(opts.showFolder) sub.push('<span class="chip">'+esc(d.folder)+'</span>');
  if(d.go_number) sub.push('No. '+esc(d.go_number));
  sub.push(fmtDate(d.uploaded_at));
  if(d.size_bytes) sub.push(fmtSize(d.size_bytes));
  const del=opts.del?'<button class="go-del" title="Delete" data-act="delDoc" data-args="['+d.id+']">&times;</button>':'';
  return '<div class="go-item" data-act="openGo" data-args="['+d.id+']">'
    +'<span class="go-ic">PDF</span>'
    +'<span class="go-meta"><div class="go-name">'+esc(d.go_name)+'</div><div class="go-sub">'+sub.join(' · ')+'</div></span>'
    +'<span class="go-open">Open &rsaquo;</span>'+del+'</div>';
}

function renderHome(){
  const q=(document.getElementById('goSearch').value||'').trim().toLowerCase();
  const box=document.getElementById('goTree');

  if(q){
    const hits=DOCS.filter(d=>((d.go_name||'')+' '+(d.go_number||'')+' '+(d.orig_name||'')).toLowerCase().includes(q));
    if(!hits.length){box.innerHTML=emptyBox('No GOs match “'+esc(q)+'”.');return;}
    box.innerHTML='<div class="folder"><div class="folder-head">'+folderSvg()+'Search results<span class="badge">'+hits.length+'</span></div><div class="go-list">'
      +hits.map(d=>goItem(d,{showFolder:true,del:true})).join('')+'</div></div>';
    return;
  }

  // build set of all folder paths (declared + used by docs)
  const all=new Set(FOLDERS); DOCS.forEach(d=>all.add(d.folder));
  const paths=[...all];
  const tops=[...new Set(paths.map(topOf))].sort((a,b)=>a.localeCompare(b));
  if(!DOCS.length && !FOLDERS.length){box.innerHTML=emptyBox('No Government Orders yet. Add one using the form above.');return;}

  let html='';
  tops.forEach(top=>{
    const direct=DOCS.filter(d=>d.folder===top);
    const childPaths=[...new Set(paths.filter(p=>p!==top && p.indexOf(top+'/')===0))].sort();
    const count=DOCS.filter(d=>d.folder===top||d.folder.indexOf(top+'/')===0).length;
    html+='<div class="folder"><div class="folder-head">'+folderSvg()+esc(top)+'<span class="badge">'+count+'</span></div>';
    if(direct.length) html+='<div class="go-list">'+direct.map(d=>goItem(d,{del:true})).join('')+'</div>';
    childPaths.forEach(cp=>{
      const cds=DOCS.filter(d=>d.folder===cp);
      html+='<div class="sub-head">'+esc(cp.slice(top.length+1))+'</div>';
      html+='<div class="go-list">'+(cds.length?cds.map(d=>goItem(d,{del:true})).join(''):'<div class="hint" style="padding:4px 12px">Empty</div>')+'</div>';
    });
    if(!direct.length && !childPaths.length) html+='<div class="go-list"><div class="hint" style="padding:4px 12px">No GOs in this folder yet.</div></div>';
    html+='</div>';
  });
  box.innerHTML=html;
}
function emptyBox(msg){return '<div class="empty"><svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M3 7h6l2 2h10v10H3z"/></svg><div style="font-size:14px">'+msg+'</div></div>';}
function folderSvg(){return '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M3 7h6l2 2h10v10H3z"/></svg>';}

function fillSelects(){
  const up=document.getElementById('upFolder'), par=document.getElementById('newParent');
  up.innerHTML=FOLDERS.map(f=>'<option>'+esc(f)+'</option>').join('')||'<option>RMMS Cell</option>';
  par.innerHTML='<option value="">(top level)</option>'+FOLDERS.map(f=>'<option>'+esc(f)+'</option>').join('');
}
/* Folder names are free text an ADMIN can set to anything (GoController does no
   character filtering) and this list is seen by every signed-in role. This used
   to build data-act="delFolder" data-args='["..."]' and needed the name escaped for TWO nested
   contexts — the JS string and the HTML attribute around it — which is exactly
   the pair that got out of step and allowed an attribute breakout. The name now
   travels as data (a JSON argument), never as code, so the only escaping left is
   the ordinary attribute one that esc() already does. */
function renderFolderList(){
  const el=document.getElementById('folderList');
  el.innerHTML=FOLDERS.map(f=>'<span class="ftag">'+folderSvg()+esc(f)+'<button title="Delete folder" data-act="delFolder" data-args="'+esc(JSON.stringify([f]))+'">&times;</button></span>').join('')||'<span class="hint">No folders.</span>';
}

function openGo(id){window.open(API+'/file/'+id,'_blank');}

async function doUpload(){
  const out=document.getElementById('upOut');
  const f=document.getElementById('upFile').files[0];
  const name=document.getElementById('upName').value.trim();
  const folder=document.getElementById('upFolder').value;
  const num=document.getElementById('upNumber').value.trim();
  if(!f){out.className='out err';out.textContent='Choose a file first.';return;}
  if(!name){out.className='out err';out.textContent='Enter the GO name.';return;}
  out.className='out';out.textContent='Uploading…';
  const fd=new FormData();fd.append('file',f);fd.append('go_name',name);fd.append('go_number',num);fd.append('folder',folder);
  try{
    const r=await fetch(API+'/upload',{method:'POST',body:fd});const j=await r.json();
    if(j.ok){out.className='out ok';out.textContent='✓ Uploaded “'+name+'” to '+folder+'.';
      document.getElementById('upName').value='';document.getElementById('upNumber').value='';document.getElementById('upFile').value='';
      await loadAll();
    } else {out.className='out err';out.textContent='Error: '+(j.error||'upload failed');}
  }catch(e){out.className='out err';out.textContent='Request failed: '+e.message+' (is the file under the upload size limit?)';}
}

async function doCreateFolder(){
  const out=document.getElementById('folderOut');
  let name=document.getElementById('newFolder').value.trim();
  const parent=document.getElementById('newParent').value;
  if(!name){out.className='out err';out.textContent='Enter a folder name.';return;}
  if(parent) name=parent+'/'+name;
  out.className='out';out.textContent='Creating…';
  try{
    const r=await fetch(API+'/folders',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name})});
    const j=await r.json();
    if(j.ok){out.className='out ok';out.textContent='✓ Folder “'+name+'” ready.';document.getElementById('newFolder').value='';await loadAll();}
    else {out.className='out err';out.textContent='Error: '+(j.error||'failed');}
  }catch(e){out.className='out err';out.textContent='Request failed: '+e.message;}
}

async function delFolder(name){
  if(!confirm('Delete folder “'+name+'”? (only works if it has no GOs)'))return;
  try{const r=await fetch(API+'/folders?name='+encodeURIComponent(name),{method:'DELETE'});const j=await r.json();
    if(!j.ok)alert(j.error||'Could not delete.');await loadAll();
  }catch(e){alert('Failed: '+e.message);}
}
async function delDoc(id){
  if(!confirm('Delete this Government Order?'))return;
  try{await fetch(API+'/docs/'+id,{method:'DELETE'});await loadAll();}catch(e){alert('Failed: '+e.message);}
}

document.getElementById('goSearch').addEventListener('input',renderHome);
loadAll();
