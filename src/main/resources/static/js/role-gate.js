/*
 * role-gate.js — shared front-end role gating for KLRAMS staff pages.
 *
 * Fetches /api/me once, tags <body> with the current role, and hides any
 * element that the role is not allowed to use:
 *
 *   data-requires="admin"  → visible to ADMIN and SUPER_ADMIN (import/edit)
 *   data-requires="super"  → visible to SUPER_ADMIN only (Site Control, Users)
 *
 * This is UX only — the server (SecurityConfig) is the real enforcement. It
 * also redirects to the forced first-login password change when required.
 *
 * Usage: include <script src="/js/role-gate.js" defer></script> on a page.
 * Optionally set window.RoleGate.onReady = fn(me) before it runs.
 */
(function () {
  var RANK = { USER: 1, ADMIN: 2, SUPER_ADMIN: 3 };
  var NEED = { admin: 2, super: 3 };

  var RoleGate = window.RoleGate || (window.RoleGate = {});
  RoleGate.me = null;

  function apply(me) {
    RoleGate.me = me;
    var role = (me && me.role) || 'USER';
    var rank = RANK[role] || 0;
    var body = document.body;

    body.classList.remove('role-user', 'role-admin', 'role-super');
    body.classList.add(role === 'SUPER_ADMIN' ? 'role-super' : role === 'ADMIN' ? 'role-admin' : 'role-user');

    document.querySelectorAll('[data-requires]').forEach(function (el) {
      var need = NEED[(el.getAttribute('data-requires') || '').toLowerCase()] || 0;
      if (rank < need) el.style.display = 'none';
    });

    if (typeof RoleGate.onReady === 'function') {
      try { RoleGate.onReady(me); } catch (e) { /* ignore */ }
    }
  }

  function run() {
    fetch('/api/me', { headers: { 'Accept': 'application/json' }, credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (me) {
        if (!me || !me.authenticated) { apply({ role: 'USER' }); return; }
        // force first-login password change (not while already on that page)
        if (me.mustChangePassword && !/change-password\.html$/.test(location.pathname)) {
          location.replace('/change-password.html');
          return;
        }
        apply(me);
      })
      .catch(function () { apply({ role: 'USER' }); });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', run);
  else run();
})();
