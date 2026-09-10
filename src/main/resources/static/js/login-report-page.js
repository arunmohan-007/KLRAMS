/* Login Activity report (login-report.html).
   Lifted out of an inline <script> so the page carries no inline script,
   which is what lets script-src drop 'unsafe-inline'. */
(function(){
    var rows = document.getElementById('rows');
    var listMsg = document.getElementById('listMsg');
    var q = document.getElementById('q');
    var limitSel = document.getElementById('limit');
    var fromInp = document.getElementById('from');
    var toInp = document.getElementById('to');
    var data = [];

    window.RoleGate = window.RoleGate || {};
    window.RoleGate.onReady = function(me){
      var r = (me && me.role) || 'USER';
      document.getElementById('rolechip').textContent =
        r === 'SUPER_ADMIN' ? 'Super Admin' : r === 'ADMIN' ? 'Admin' : 'User';
    };

    function esc(s){ return (s==null?'':String(s)).replace(/[&<>"]/g, function(c){
      return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]; }); }

    function fmtTime(s){ if(!s) return null; try{
      return new Date(s).toLocaleString('en-IN',{day:'2-digit',month:'short',year:'numeric',hour:'2-digit',minute:'2-digit',second:'2-digit'});
    }catch(e){ return String(s); } }

    // "session extend" — human-readable elapsed time between login and logout.
    function fmtDuration(sec){
      if(sec == null) return null;
      sec = Number(sec); if(!isFinite(sec) || sec < 0) return null;
      var d = Math.floor(sec/86400), h = Math.floor((sec%86400)/3600),
          m = Math.floor((sec%3600)/60), s = Math.floor(sec%60);
      if(d) return d+'d '+h+'h '+m+'m';
      if(h) return h+'h '+m+'m';
      if(m) return m+'m '+s+'s';
      return s+'s';
    }

    // Trim a user-agent to a readable browser + OS hint.
    function shortUA(ua){
      if(!ua) return '—';
      var browser = /Edg\//.test(ua)?'Edge': /OPR\//.test(ua)?'Opera':
                    /Chrome\//.test(ua)?'Chrome': /Firefox\//.test(ua)?'Firefox':
                    /Safari\//.test(ua)?'Safari':'Browser';
      var os = /Windows NT 10/.test(ua)?'Windows': /Windows/.test(ua)?'Windows':
               /Android/.test(ua)?'Android': /iPhone|iPad|iOS/.test(ua)?'iOS':
               /Mac OS X/.test(ua)?'macOS': /Linux/.test(ua)?'Linux':'';
      return os ? browser+' · '+os : browser;
    }

    function roleLabel(r){ return r==='SUPER_ADMIN'?'Super Admin':r==='ADMIN'?'Admin':r==='USER'?'User':'—'; }
    function roleCls(r){ return r==='SUPER_ADMIN'?'super':r==='ADMIN'?'admin':'user'; }

    function load(){
      rows.innerHTML = '<tr><td colspan="8" class="empty">Loading…</td></tr>';
      var qs = 'limit=' + encodeURIComponent(limitSel.value);
      if(fromInp.value) qs += '&from=' + encodeURIComponent(fromInp.value);
      if(toInp.value)   qs += '&to='   + encodeURIComponent(toInp.value);
      fetch('/api/reports/logins?' + qs, {credentials:'same-origin'})
        .then(function(r){ if(!r.ok) throw new Error('You are not allowed to view this report ('+r.status+').'); return r.json(); })
        .then(function(list){ data = list || []; render(); })
        .catch(function(e){ rows.innerHTML = '<tr><td colspan="8" class="empty">'+esc(e.message)+'</td></tr>'; });
    }

    function filtered(){
      var term = q.value.trim().toLowerCase();
      if(!term) return data;
      return data.filter(function(e){
        return [e.username, e.full_name, e.role, e.ip, e.user_agent]
          .some(function(v){ return v && String(v).toLowerCase().indexOf(term) >= 0; });
      });
    }

    function render(){
      var list = filtered();

      // stats over the full (unfiltered) dataset
      var users = {}, active = 0;
      data.forEach(function(e){ if(e.username) users[e.username]=1; if(!e.logout_at) active++; });
      document.getElementById('stTotal').textContent = data.length;
      document.getElementById('stUsers').textContent = Object.keys(users).length;
      document.getElementById('stActive').textContent = active;

      if(!list.length){ rows.innerHTML = '<tr><td colspan="8" class="empty">No login activity'+(q.value?' matches your filter':' recorded yet')+'.</td></tr>'; return; }

      rows.innerHTML = list.map(function(e){
        var dur = fmtDuration(e.session_seconds);
        var loggedOut = fmtTime(e.logout_at);
        return '<tr>'+
          '<td class="uname">'+esc(e.username)+'</td>'+
          '<td>'+esc(e.full_name||'—')+'</td>'+
          '<td><span class="pill '+roleCls(e.role)+'">'+esc(roleLabel(e.role))+'</span></td>'+
          '<td>'+esc(fmtTime(e.login_at)||'—')+'</td>'+
          '<td>'+(loggedOut ? esc(loggedOut) : '<span class="pill active">Active</span>')+'</td>'+
          '<td>'+(dur ? esc(dur) : '<span class="stat">in progress</span>')+'</td>'+
          '<td class="ip">'+esc(e.ip||'—')+'</td>'+
          '<td class="ua" title="'+esc(e.user_agent||'')+'">'+esc(shortUA(e.user_agent))+'</td>'+
        '</tr>';
      }).join('');
    }

    function exportCsv(){
      var list = filtered();
      var head = ['Username','Full name','Role','Login time','Logout time','Session seconds','IP','User agent'];
      var lines = [head.join(',')];
      list.forEach(function(e){
        var cells = [e.username, e.full_name, e.role, e.login_at, e.logout_at, e.session_seconds, e.ip, e.user_agent];
        lines.push(cells.map(function(c){
          var v = c==null ? '' : String(c);
          return /[",\n]/.test(v) ? '"'+v.replace(/"/g,'""')+'"' : v;
        }).join(','));
      });
      var blob = new Blob([lines.join('\r\n')], {type:'text/csv;charset=utf-8'});
      var a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = 'klrams-login-activity-' + new Date().toISOString().slice(0,10) + '.csv';
      document.body.appendChild(a); a.click(); document.body.removeChild(a);
      setTimeout(function(){ URL.revokeObjectURL(a.href); }, 1000);
    }

    q.addEventListener('input', render);
    limitSel.addEventListener('change', load);
    fromInp.addEventListener('change', load);
    toInp.addEventListener('change', load);
    document.getElementById('clearDates').addEventListener('click', function(){
      if(!fromInp.value && !toInp.value) return;
      fromInp.value = ''; toInp.value = ''; load();
    });
    document.getElementById('refresh').addEventListener('click', load);
    document.getElementById('export').addEventListener('click', exportCsv);

    load();
  })();
