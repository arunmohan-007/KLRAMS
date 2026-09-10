/* Site Control (admin.html) — About / Contact / FAQ editing.
   Lifted out of an inline <script> so the page needs no inline script at all:
   with script-src 'self' and no 'unsafe-inline', an external file is simply
   allowed, where an inline block would need a per-request nonce plumbed through
   a static file. Buttons dispatch through data-act (see js/00-actions.js). */
const SITE='/api/site';
let FAQ=[];
function escA(s){return String(s==null?'':s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));}

async function loadText(key,id){try{const j=await (await fetch(SITE+'/content?key='+key)).json();document.getElementById(id).value=j.value||'';}catch(e){}}
async function saveText(key,id,outId){
  const out=document.getElementById(outId);out.className='out';out.textContent='Saving…';
  try{const r=await fetch(SITE+'/content',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({key,value:document.getElementById(id).value})});
    const j=await r.json();
    if(j.ok){out.className='out ok';out.textContent='✓ Saved.';}else{out.className='out err';out.textContent='Error: '+(j.error||'failed');}
  }catch(e){out.className='out err';out.textContent='Failed: '+e.message;}
}

async function loadFaq(){
  try{const j=await (await fetch(SITE+'/content?key=faq')).json();FAQ=j.value?JSON.parse(j.value):[];}catch(e){FAQ=[];}
  if(!Array.isArray(FAQ))FAQ=[];
  renderFaq();
}
function faqRow(it,i){
  return '<div class="faq-row">'
    +'<div class="faq-row-head"><span class="faq-num">Q'+(i+1)+'</span><button class="xbtn" title="Remove" data-act="faqRemove" data-args="['+i+']">&times;</button></div>'
    +'<input class="inp faq-q" placeholder="Question" value="'+escA(it.q||'')+'">'
    +'<textarea class="faq-a" rows="3" placeholder="Answer">'+escA(it.a||'')+'</textarea>'
    +'</div>';
}
function renderFaq(){
  document.getElementById('faqRows').innerHTML=FAQ.length?FAQ.map(faqRow).join(''):'<div class="hint" style="margin-bottom:12px">No questions yet — add one below.</div>';
}
function collectFaq(){
  return [...document.querySelectorAll('.faq-row')].map(r=>({q:r.querySelector('.faq-q').value.trim(),a:r.querySelector('.faq-a').value.trim()})).filter(x=>x.q||x.a);
}
function faqAdd(){FAQ=collectFaq();FAQ.push({q:'',a:''});renderFaq();}
function faqRemove(i){FAQ=collectFaq();FAQ.splice(i,1);renderFaq();}
async function faqSave(){
  const data=collectFaq();const out=document.getElementById('faqOut');out.className='out';out.textContent='Saving…';
  try{const r=await fetch(SITE+'/content',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({key:'faq',value:JSON.stringify(data)})});
    const j=await r.json();
    if(j.ok){out.className='out ok';out.textContent='✓ FAQ saved ('+data.length+' question'+(data.length===1?'':'s')+').';FAQ=data;renderFaq();}
    else{out.className='out err';out.textContent='Error: '+(j.error||'failed');}
  }catch(e){out.className='out err';out.textContent='Failed: '+e.message;}
}

loadText('about','aAbout');
loadText('contact','aContact');
loadFaq();
