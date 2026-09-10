/* User Management (users.html).
   Lifted out of an inline <script> so the page carries no inline script,
   which is what lets script-src drop 'unsafe-inline'. */
(function(){
    var rows = document.getElementById('rows');
    var addForm = document.getElementById('addForm');
    var addMsg = document.getElementById('addMsg');
    var listMsg = document.getElementById('listMsg');
    var pwModal = document.getElementById('pwModal');
    var pwNew = document.getElementById('pwNew');
    var pwWho = document.getElementById('pwWho');
    var pwTarget = null;

    window.RoleGate = window.RoleGate || {};
    window.RoleGate.onReady = function(me){
      var r = (me && me.role) || 'USER';
      document.getElementById('rolechip').textContent =
        r === 'SUPER_ADMIN' ? 'Super Admin' : r === 'ADMIN' ? 'Admin' : 'User';
    };

    function esc(s){ return (s==null?'':String(s)).replace(/[&<>"]/g, function(c){
      return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]; }); }
    function fmtDate(s){ if(!s) return '—'; try{ return new Date(s).toLocaleDateString('en-IN',{day:'2-digit',month:'short',year:'numeric'}); }catch(e){ return '—'; } }
    function roleLabel(r){ return r==='SUPER_ADMIN'?'Super Admin':r==='ADMIN'?'Admin':'User'; }
    function roleCls(r){ return r==='SUPER_ADMIN'?'super':r==='ADMIN'?'admin':'user'; }
    function flash(el, ok, text){ el.className = 'msg ' + (ok?'ok':'err'); el.textContent = text; setTimeout(function(){ if(el.textContent===text) el.className='msg'; }, ok?3500:6000); }

    function load(){
      fetch('/api/users', {credentials:'same-origin'})
        .then(function(r){ if(!r.ok) throw new Error('You are not allowed to view users ('+r.status+').'); return r.json(); })
        .then(render)
        .catch(function(e){ rows.innerHTML = '<tr><td colspan="6" class="empty">'+esc(e.message)+'</td></tr>'; });
    }

    function render(list){
      if(!list.length){ rows.innerHTML = '<tr><td colspan="6" class="empty">No users yet.</td></tr>'; return; }
      rows.innerHTML = list.map(function(u){
        var enabled = u.enabled;
        var isSuper = u.role === 'SUPER_ADMIN';
        var roleSel = ['USER','ADMIN','SUPER_ADMIN'].map(function(v){
          return '<option value="'+v+'"'+(u.role===v?' selected':'')+'>'+roleLabel(v)+'</option>'; }).join('');
        // Super Admins cannot be disabled/enabled — omit the toggle for those rows.
        var toggleBtn = isSuper ? '' :
          '<button class="btn sm ghost act-toggle">'+(enabled?'Disable':'Enable')+'</button>';
        return '<tr data-id="'+u.id+'">'+
          '<td class="uname">'+esc(u.username)+'</td>'+
          '<td>'+esc(u.full_name||'—')+'</td>'+
          '<td><select class="roleSel" style="max-width:150px">'+roleSel+'</select></td>'+
          '<td><span class="pill '+(enabled?'on':'off')+'">'+(enabled?'Active':'Disabled')+'</span></td>'+
          '<td>'+fmtDate(u.updated_at)+'</td>'+
          '<td><div class="actions">'+
            toggleBtn+
            '<button class="btn sm ghost act-pw">Reset password</button>'+
            '<button class="btn sm danger act-del">Delete</button>'+
          '</div></td>'+
        '</tr>';
      }).join('');
    }

    function idOf(el){ return el.closest('tr').getAttribute('data-id'); }

    // add user
    addForm.addEventListener('submit', function(e){
      e.preventDefault();
      var body = { username:addForm.username.value.trim(), fullName:addForm.fullName.value.trim(),
                   role:addForm.role.value, password:addForm.password.value };
      fetch('/api/users', {method:'POST', headers:{'Content-Type':'application/json'}, credentials:'same-origin', body:JSON.stringify(body)})
        .then(function(r){ return r.json().then(function(d){ return {ok:r.ok, d:d}; }); })
        .then(function(res){
          if(res.ok && res.d.ok){ flash(addMsg, true, 'User "'+body.username+'" created.'); addForm.reset(); load(); }
          else flash(addMsg, false, res.d.error || 'Could not create user.');
        })
        .catch(function(){ flash(addMsg, false, 'Network error.'); });
    });

    // row actions (event delegation)
    rows.addEventListener('click', function(e){
      var t = e.target;
      if(t.classList.contains('act-toggle')){
        var tr = t.closest('tr');
        var enabled = tr.querySelector('.pill').classList.contains('on');
        put(idOf(t), {enabled: !enabled});
      } else if(t.classList.contains('act-del')){
        var name = t.closest('tr').querySelector('.uname').textContent;
        if(confirm('Delete user "'+name+'"? This cannot be undone.')) del(idOf(t));
      } else if(t.classList.contains('act-pw')){
        pwTarget = idOf(t);
        pwWho.textContent = 'New password for "'+t.closest('tr').querySelector('.uname').textContent+'". They will change it at next sign-in.';
        pwNew.value=''; pwModal.classList.add('show'); pwNew.focus();
      }
    });
    rows.addEventListener('change', function(e){
      if(e.target.classList.contains('roleSel')) put(idOf(e.target), {role: e.target.value});
    });

    function put(id, body){
      fetch('/api/users/'+id, {method:'PUT', headers:{'Content-Type':'application/json'}, credentials:'same-origin', body:JSON.stringify(body)})
        .then(function(r){ return r.json().then(function(d){ return {ok:r.ok, d:d}; }); })
        .then(function(res){ if(res.ok && res.d.ok){ flash(listMsg,true,'Saved.'); } else flash(listMsg,false,res.d.error||'Update failed.'); load(); })
        .catch(function(){ flash(listMsg,false,'Network error.'); load(); });
    }
    function del(id){
      fetch('/api/users/'+id, {method:'DELETE', credentials:'same-origin'})
        .then(function(r){ return r.json().then(function(d){ return {ok:r.ok, d:d}; }); })
        .then(function(res){ if(res.ok && res.d.ok){ flash(listMsg,true,'User deleted.'); } else flash(listMsg,false,res.d.error||'Delete failed.'); load(); })
        .catch(function(){ flash(listMsg,false,'Network error.'); });
    }

    document.getElementById('pwCancel').addEventListener('click', function(){ pwModal.classList.remove('show'); });
    pwModal.addEventListener('click', function(e){ if(e.target===pwModal) pwModal.classList.remove('show'); });
    document.getElementById('pwSave').addEventListener('click', function(){
      if(!pwTarget) return;
      fetch('/api/users/'+pwTarget+'/password', {method:'POST', headers:{'Content-Type':'application/json'}, credentials:'same-origin', body:JSON.stringify({password:pwNew.value})})
        .then(function(r){ return r.json().then(function(d){ return {ok:r.ok, d:d}; }); })
        .then(function(res){ pwModal.classList.remove('show'); if(res.ok && res.d.ok) flash(listMsg,true,'Password reset.'); else flash(listMsg,false,res.d.error||'Reset failed.'); })
        .catch(function(){ pwModal.classList.remove('show'); flash(listMsg,false,'Network error.'); });
    });

    load();
  })();
