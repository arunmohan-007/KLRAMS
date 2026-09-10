/* Forced / self-service password change (change-password.html).
   Lifted out of an inline <script> so the page carries no inline script,
   which is what lets script-src drop 'unsafe-inline'. */
(function(){
    var form=document.getElementById('form'), msg=document.getElementById('msg'), sub=document.getElementById('sub');
    // If the user isn't being forced, soften the copy.
    fetch('/api/me',{credentials:'same-origin'}).then(function(r){return r.ok?r.json():null;}).then(function(me){
      if(me && me.authenticated && !me.mustChangePassword)
        sub.textContent='Update the password for '+(me.username||'your account')+'.';
    }).catch(function(){});

    function show(ok,text){ msg.className='msg '+(ok?'ok':'err'); msg.textContent=text; }

    form.addEventListener('submit', function(e){
      e.preventDefault();
      var cur=document.getElementById('current').value;
      var next=document.getElementById('next').value;
      var conf=document.getElementById('confirm').value;
      if(next.length<6){ show(false,'New password must be at least 6 characters.'); return; }
      if(next!==conf){ show(false,'The new passwords do not match.'); return; }
      fetch('/api/account/password',{method:'POST',headers:{'Content-Type':'application/json'},credentials:'same-origin',body:JSON.stringify({current:cur,new:next})})
        .then(function(r){ return r.json().then(function(d){ return {ok:r.ok,d:d}; }); })
        .then(function(res){
          if(res.ok && res.d.ok){ show(true,'Password updated. Redirecting…'); setTimeout(function(){ location.href='/home.html'; }, 900); }
          else show(false, res.d.error || 'Could not update password.');
        })
        .catch(function(){ show(false,'Network error.'); });
    });
  })();
