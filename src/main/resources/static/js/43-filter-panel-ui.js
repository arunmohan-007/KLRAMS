/* ============================================================
   KLRAMS viewer · 43-filter-panel-ui.js
   Toolbar chrome for the Filter folder — live search across the (now
   fairly long) list of sections, collapse/expand all, and a single
   "Clear all filters" that reaches every filter type in the folder
   without needing to know how each one stores its own state.
   ============================================================ */
(function () {
  'use strict';

  /* ------------------------------------------------------------------
     Search — hides sections (and any group left with nothing visible)
     whose name doesn't match. Re-applied on every DOM change under
     #pane-filter, because the Soil/Core/boundary/user-layer sections are
     rebuilt wholesale by 37-layer-filters.js whenever a layer list changes,
     which would otherwise silently drop the filter someone was mid-search on.
     ------------------------------------------------------------------ */

  function sectionText(sec) {
    var sum = sec.querySelector(':scope > summary');
    return (sum ? sum.textContent : '').toLowerCase();
  }

  function applySearch() {
    var box = document.getElementById('fltSearchBox');
    var hint = document.getElementById('fltSearchHint');
    var clearBtn = document.getElementById('fltSearchClear');
    if (!box) return;
    var q = box.value.trim().toLowerCase();
    if (clearBtn) clearBtn.style.display = q ? '' : 'none';

    var groups = document.querySelectorAll('#pane-filter .flt-group');
    var shown = 0, total = 0;
    groups.forEach(function (g) {
      /* Not ":scope >" — the Soil/Core/boundary/user-layer sections mount one
         level deeper, inside #fsecAssets or #fsecExtra within the group, not
         as the group's direct children. Groups never nest, so a descendant
         selector can't cross into a different group by mistake. */
      var secs = g.querySelectorAll('details.flt-sec');
      var anyVisible = false;
      secs.forEach(function (sec) {
        total++;
        var match = !q || sectionText(sec).indexOf(q) >= 0;
        sec.classList.toggle('hidden-by-search', !match);
        if (match) { anyVisible = true; shown++; }
      });
      g.classList.toggle('hidden-by-search', secs.length > 0 && !anyVisible);
    });

    if (hint) hint.textContent = q ? (shown + ' of ' + total + ' match “' + box.value.trim() + '”') : '';
  }

  var searchTimer = null;
  function scheduleSearch() {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(applySearch, 40);
  }

  (function wireSearch() {
    var box = document.getElementById('fltSearchBox');
    var clearBtn = document.getElementById('fltSearchClear');
    if (!box) return;
    box.addEventListener('input', applySearch);
    if (clearBtn) clearBtn.addEventListener('click', function () {
      box.value = '';
      applySearch();
      box.focus();
    });
    /* The dynamic sections (Soil, Core, boundaries, user/temp layers) are
       torn down and rebuilt wholesale by 37-layer-filters.js — on load, on
       import, on a new user layer. Watching the panel rather than hooking
       each of those call sites keeps this file from needing to know they
       exist. */
    var panel = document.getElementById('pane-filter');
    if (panel && typeof MutationObserver !== 'undefined') {
      new MutationObserver(scheduleSearch).observe(panel, { childList: true, subtree: true });
    }
  })();

  /* ------------------------------------------------------------------
     Collapse all / Expand all
     ------------------------------------------------------------------ */

  function fltToggleCollapse() {
    var btn = document.getElementById('fltCollapseBtn');
    var secs = document.querySelectorAll('#pane-filter details.flt-sec');
    var collapsing = !btn || btn.dataset.mode !== 'expand';
    secs.forEach(function (sec) { sec.open = !collapsing; });
    if (btn) {
      btn.dataset.mode = collapsing ? 'expand' : 'collapse';
      btn.textContent = collapsing ? 'Expand all' : 'Collapse all';
    }
  }
  window.fltToggleCollapse = fltToggleCollapse;

  /* ------------------------------------------------------------------
     Clear all — every filter type in the folder keeps its own state and
     its own apply/clear pair (fixed sections: global clearXxxFilter()
     functions; the generic Soil/Core/boundary/user-layer sections: a
     [data-lf-clear] button per section). Rather than teach this file each
     one's internals, it calls what is already there for exactly this job —
     the same functions/buttons a person would use one at a time.
     ------------------------------------------------------------------ */

  var CLEAR_FNS = [
    'clearSelectRoadFilter', 'clearNetFilters', 'clearFilters',
    'clearTrafficFilter', 'clearFwdFilter', 'clearIri2kmFilter',
    'clearPciFilter', 'clearBridgeFilter', 'clearCulvertFilter'
  ];

  function fltClearAll() {
    CLEAR_FNS.forEach(function (name) {
      try { if (typeof window[name] === 'function') window[name](); } catch (e) { /* one bad clear should not block the rest */ }
    });
    document.querySelectorAll('#pane-filter [data-lf-clear]').forEach(function (b) {
      try { b.click(); } catch (e) {}
    });

    var btn = document.getElementById('fltClearAllBtn');
    if (btn) {
      var was = btn.textContent;
      btn.textContent = 'Cleared';
      setTimeout(function () { btn.textContent = was; }, 1100);
    }
  }
  window.fltClearAll = fltClearAll;
})();
