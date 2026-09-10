/* In-app user manual (manual.html).
   Lifted out of an inline <script> so the page carries no inline
   script, which is what lets script-src drop 'unsafe-inline'. */
(function(){
  'use strict';
  var API = '/api/manual/images';
  var imgMap = {};          // name -> {uploaded_at, size_bytes}
  var roleRank = 1;         // 1 USER · 2 ADMIN · 3 SUPER_ADMIN
  var pendingName = null;   // figure being uploaded via the hidden file input
  var figs = Array.prototype.slice.call(document.querySelectorAll('.fig[data-img]'));

  function isAdmin(){ return roleRank >= 2; }
  function esc(s){ return String(s).replace(/[&<>"]/g, function(c){ return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]; }); }
  function toast(msg, err){
    var t = document.getElementById('toast');
    t.textContent = msg; t.classList.toggle('err', !!err); t.classList.add('show');
    clearTimeout(t._h); t._h = setTimeout(function(){ t.classList.remove('show'); }, 3200);
  }
  function figNo(fig){ var n = fig.querySelector('.fig-no'); return n ? n.textContent : ''; }
  function figCap(fig){
    var c = fig.querySelector('figcaption'); if(!c) return '';
    var t = c.cloneNode(true); var no = t.querySelector('.fig-no'); if(no) no.remove();
    return t.textContent.trim();
  }
  function imgUrl(name){ var m = imgMap[name]; return API + '/' + name + (m && m.uploaded_at ? '?v=' + m.uploaded_at : ''); }

  var CAM = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M4 8h3l2-3h6l2 3h3v12H4z"/><circle cx="12" cy="13.5" r="3.5"/></svg>';
  var UP  = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 17V4M6 10l6-6 6 6M4 21h16"/></svg>';
  var DEL = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M4 7h16M9 7V4h6v3M6 7l1 14h10l1-14"/></svg>';

  function renderFig(fig){
    var name = fig.getAttribute('data-img');
    var has = !!imgMap[name];
    var box = fig.querySelector('.fig-box');
    if(!box){
      box = document.createElement('div'); box.className = 'fig-box';
      fig.insertBefore(box, fig.firstChild);
    }
    var html = '';
    if(has){
      html += '<img src="' + imgUrl(name) + '" loading="lazy" alt="' + esc(figNo(fig)) + '">';
    } else {
      html += '<div class="fig-empty">' + CAM +
              '<span class="fe-t">Screenshot pending — ' + esc(name) + '</span>' +
              '<span class="fe-h">' + esc(fig.getAttribute('data-hint') || '') + '</span></div>';
    }
    if(isAdmin()){
      html += '<div class="fig-tools no-print">' +
              '<button class="fbtn" data-up="' + esc(name) + '">' + UP + (has ? 'Replace' : 'Upload PNG') + '</button>' +
              (has ? '<button class="fbtn del" data-del="' + esc(name) + '">' + DEL + 'Remove</button>' : '') +
              '</div>';
    }
    html += '<div class="fig-busy">Uploading…</div>';
    box.classList.toggle('nopic', !has);
    box.innerHTML = html;
    var im = box.querySelector('img');
    if(im) im.addEventListener('click', function(){
      document.getElementById('lbImg').src = imgUrl(name);
      document.getElementById('lbCap').textContent = figNo(fig) + ' — ' + figCap(fig);
      document.getElementById('lb').classList.add('open');
    });
  }

  function renderManager(){
    var tbl = document.getElementById('figManager');
    Array.prototype.slice.call(tbl.querySelectorAll('tr')).slice(1).forEach(function(r){ r.remove(); });
    figs.forEach(function(fig){
      var name = fig.getAttribute('data-img');
      var has = !!imgMap[name];
      var tr = document.createElement('tr');
      var kb = has && imgMap[name].size_bytes ? ' · ' + Math.round(imgMap[name].size_bytes/1024) + ' KB' : '';
      tr.innerHTML =
        '<td><a class="inl" href="#' + (fig.closest('[id]') ? '' : '') + '" data-jump>' + esc(figNo(fig)) + '</a><br><span style="color:var(--muted-2);font-size:11px">' + esc(figCap(fig)) + '</span></td>' +
        '<td><code>' + esc(name) + '</code></td>' +
        '<td>' + (has ? '<span class="pill good">Uploaded' + kb + '</span>' : '<span class="pill fair">Pending</span>') + '</td>' +
        (isAdmin() ? '<td class="no-print"><button class="fbtn" data-up="' + esc(name) + '">' + UP + (has ? 'Replace' : 'Upload') + '</button></td>' : '');
      tr.querySelector('[data-jump]').addEventListener('click', function(e){
        e.preventDefault(); fig.scrollIntoView({behavior:'smooth', block:'center'});
      });
      tbl.appendChild(tr);
    });
  }

  function renderProgress(){
    var done = figs.filter(function(f){ return !!imgMap[f.getAttribute('data-img')]; }).length;
    var el = document.getElementById('figProgress');
    if(el) el.innerHTML = '<i></i>Figures: ' + done + ' / ' + figs.length + ' uploaded';
  }

  function renderAll(){ figs.forEach(renderFig); renderManager(); renderProgress(); }

  function refresh(){
    fetch(API, {credentials:'same-origin'})
      .then(function(r){ return r.ok ? r.json() : []; })
      .then(function(list){
        imgMap = {};
        (list || []).forEach(function(m){ imgMap[m.name] = m; });
        renderAll();
      })
      .catch(function(){ renderAll(); });
  }

  /* one hidden file input shared by every Upload/Replace button (event delegation) */
  document.addEventListener('click', function(e){
    var up = e.target.closest('[data-up]');
    if(up){ pendingName = up.getAttribute('data-up'); document.getElementById('figFile').click(); return; }
    var del = e.target.closest('[data-del]');
    if(del){
      var name = del.getAttribute('data-del');
      if(!confirm('Remove the screenshot ' + name + ' from the manual?')) return;
      fetch(API + '/' + name, {method:'DELETE', credentials:'same-origin'})
        .then(function(r){ return r.json(); })
        .then(function(j){
          if(j && j.ok){ toast('Screenshot removed'); refresh(); }
          else toast((j && j.error) || 'Remove failed', true);
        })
        .catch(function(){ toast('Remove failed — check your connection', true); });
    }
  });

  document.getElementById('figFile').addEventListener('change', function(){
    var f = this.files[0]; this.value = '';
    if(!f || !pendingName) return;
    var name = pendingName; pendingName = null;
    var fig = figs.filter(function(x){ return x.getAttribute('data-img') === name; })[0];
    var box = fig && fig.querySelector('.fig-box');
    if(box) box.classList.add('busy');
    var fd = new FormData();
    fd.append('name', name); fd.append('file', f);
    fetch(API, {method:'POST', body:fd, credentials:'same-origin'})
      .then(function(r){ return r.json(); })
      .then(function(j){
        if(box) box.classList.remove('busy');
        if(j && j.ok){ toast('Screenshot saved — ' + name); refresh(); }
        else toast((j && j.error) || 'Upload failed', true);
      })
      .catch(function(){ if(box) box.classList.remove('busy'); toast('Upload failed — check your connection', true); });
  });

  /* role badge + admin controls appear once role-gate resolves /api/me */
  window.RoleGate = window.RoleGate || {};
  window.RoleGate.onReady = function(me){
    var r = (me && me.role) || 'USER';
    roleRank = r === 'SUPER_ADMIN' ? 3 : r === 'ADMIN' ? 2 : 1;
    var el = document.getElementById('roleBadge');
    if(el) el.textContent = r === 'SUPER_ADMIN' ? 'Super Admin' : r === 'ADMIN' ? 'Admin' : 'View only';
    renderAll();
  };

  /* TOC scroll-spy */
  var links = Array.prototype.slice.call(document.querySelectorAll('.toc a[href^="#"]'));
  var targets = links.map(function(a){ return document.getElementById(a.getAttribute('href').slice(1)); }).filter(Boolean);
  function spy(){
    var y = window.scrollY + 110, cur = targets[0];
    targets.forEach(function(t){ if(t.offsetTop <= y) cur = t; });
    links.forEach(function(a){ a.classList.toggle('on', cur && a.getAttribute('href') === '#' + cur.id); });
  }
  window.addEventListener('scroll', spy, {passive:true});

  renderAll();   /* placeholders immediately */
  refresh();     /* then real images */
  spy();
})();
