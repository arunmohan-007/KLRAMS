/* map-tabs — lifted out of an inline <script> in map.html so the page carries
   no inline script, which is what lets script-src drop 'unsafe-inline'. */
/* Signed-in user profile chip — shows the role as the prominent label
     (the permissions level is what matters at a glance). The account's
     full name and username stay available in the hover tooltip.
     Roles: SUPER_ADMIN → "Super Admin", ADMIN → "Admin", USER → "User". */
  (function(){
    var chip=document.getElementById('userChip');
    function label(r){ return r==='SUPER_ADMIN'?'Super Admin':r==='ADMIN'?'Admin':'User'; }
    fetch('/api/me',{headers:{'Accept':'application/json'},credentials:'same-origin'})
      .then(function(r){ return r.ok?r.json():null; })
      .then(function(me){
        if(!me||!me.authenticated) return;
        var role=me.role||'USER';
        /* Exposed for other modules (e.g. 28-fwd-dashboard.js) that need to
           show/hide Super-Admin-only actions without their own /api/me call. */
        window.__klRole=role;
        document.dispatchEvent(new CustomEvent('kl-role-ready',{detail:role}));
        if(!chip) return;
        var avatar=document.getElementById('ucAvatar'),
            nameEl=document.getElementById('ucName'),
            roleEl=document.getElementById('ucRole');
        var display=me.fullName||me.username||'User';
        /* Single neat line: role only. The name span is hidden; the full
           name + username go into the tooltip so the chip stays compact. */
        nameEl.style.display='none';
        roleEl.textContent=label(role);
        avatar.textContent=(display.trim()[0]||'U');
        chip.title=display+' · '+label(role)+(me.username?(' ('+me.username+')'):'');
        chip.style.display='inline-flex';
      })
      .catch(function(){});
  })();
