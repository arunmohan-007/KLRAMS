/* ---------------------------------------------------------------------------
   KLAct — the replacement for inline on* handlers.

   WHY THIS EXISTS. Every page used to wire its buttons with onclick="fn('x')".
   That blocks a real Content-Security-Policy: a nonce cannot cover inline event
   handler attributes, and the moment a nonce appears in script-src the browser
   ignores 'unsafe-inline' and every one of those handlers stops firing at once.
   So the handlers had to go before script-src could be tightened.

   It also retires a bug we hit three times. Building onclick="fn('...')" by
   string concatenation means escaping the value for TWO nested contexts — the
   JS string and the HTML attribute around it — and escaping only one of them
   let a bare double-quote break out of the attribute and inject markup. Values
   now travel as data (data-args, JSON) instead of as code, so a value can no
   longer become script however it is escaped.

   HOW TO USE IT.
       <button data-act="save">Save</button>
       <button data-act="del" data-args='["KPWD/SH/1"]'>Delete</button>
       <select data-change="onPick" data-args='[3]'>
       <input  data-input="filter">
       <input  data-enter="search">          <!-- keydown, Enter only -->

   data-args is a JSON array of the arguments. A bare string that is not valid
   JSON is passed through as a single string argument, so data-args="fwd" works
   as shorthand for data-args='["fwd"]'.

   Listeners are attached once, on document, so markup rebuilt with innerHTML
   keeps working without re-wiring — which is exactly what the dashboards do.

   A NOTE ON THE LOOKUP. Actions resolve to functions on window, filtered by a
   strict identifier pattern. An explicit registry would be marginally tighter,
   but it would mean hand-maintaining ~300 names that silently rot as the app
   changes. Reaching a function still requires injecting markup AND getting a
   user to click it, and the callable set is limited to this app's own globals —
   strictly narrower than the arbitrary code an onclick attribute used to allow.
--------------------------------------------------------------------------- */
(function () {
  'use strict';

  var NAME_RE = /^[A-Za-z_$][A-Za-z0-9_$]*$/;

  /** The event and element currently being dispatched, for handlers that need them. */
  var current = null;
  var currentEl = null;

  /**
   * Resolve "save" or "SM.closeTemplates" to {fn, self}.
   *
   * `self` is what the function is called on, and it differs by shape on purpose:
   * a dotted path is a METHOD, so it must keep its owner as `this` (SM.close()
   * would break otherwise), while a bare name is a plain function and is called
   * with the element as `this` — matching what onclick="..." used to give it.
   */
  function resolve(path) {
    if (!path) return null;
    var parts = String(path).split('.');
    for (var i = 0; i < parts.length; i++) {
      if (!NAME_RE.test(parts[i])) return null;
    }
    var self = window;
    var obj = window;
    for (var j = 0; j < parts.length; j++) {
      if (obj == null) return null;
      self = obj;
      obj = obj[parts[j]];
    }
    if (typeof obj !== 'function') return null;
    return { fn: obj, self: parts.length > 1 ? self : null };
  }

  /** data-args as an argument list. JSON array, JSON scalar, or a bare string. */
  function argsOf(el) {
    if (!el.hasAttribute('data-args')) return [];
    var raw = el.getAttribute('data-args');
    if (raw === '') return [];
    try {
      var v = JSON.parse(raw);
      return Array.isArray(v) ? v : [v];
    } catch (e) {
      return [raw];                      // plain unquoted string — the common shorthand
    }
  }

  function fire(el, attr, ev) {
    var name = el.getAttribute(attr);
    var found = resolve(name);
    if (!found) {
      /* Loud on purpose: a typo here used to be a silently dead button. */
      console.warn('[KLAct] no such action "' + name + '" for ' + attr);
      return;
    }
    current = ev;
    currentEl = el;
    try {
      found.fn.apply(found.self == null ? el : found.self, argsOf(el));
    } finally {
      current = null;
      currentEl = null;
    }
  }

  /** Nearest ancestor (or self) carrying the attribute, so icons inside a button work. */
  function owner(target, attr) {
    return target && target.closest ? target.closest('[' + attr + ']') : null;
  }

  function delegate(eventName, attr, filter) {
    document.addEventListener(eventName, function (ev) {
      var el = owner(ev.target, attr);
      if (!el) return;
      if (el.disabled) return;
      if (filter && !filter(ev)) return;
      /* Several controls are <a href="#"> styled as links, which used to end
         their handler with "return false" purely to stop the page jumping to
         the top. That cannot travel in data-act, so it is done here instead. */
      if (el.tagName === 'A' && (el.getAttribute('href') || '') === '#') ev.preventDefault();
      fire(el, attr, ev);
    });
  }

  delegate('click',  'data-act');
  delegate('change', 'data-change');
  delegate('input',  'data-input');
  delegate('keydown', 'data-enter', function (ev) { return ev.key === 'Enter'; });

  /* A broken <img> replacing onerror="this.style.display='none'".
     Resource error events do NOT bubble, so this has to listen in the capture
     phase — and the page must load this file in <head>, before the images, or
     a fast failure fires before the listener exists.

     data-hide-on-error="sibling" also reveals the next element, which is how the
     login and welcome pages fall back from the emblem image to a text crest. */
  document.addEventListener('error', function (ev) {
    var el = ev.target;
    if (!el || el.nodeType !== 1 || !el.hasAttribute || !el.hasAttribute('data-hide-on-error')) return;
    el.style.display = 'none';
    if (el.getAttribute('data-hide-on-error') === 'sibling' && el.nextElementSibling) {
      el.nextElementSibling.style.display = 'flex';
    }
  }, true);

  /* ---- built-in actions, for inline expressions that were not function calls ----
     These were things like onclick="this.classList.remove('open')" — too small to
     deserve a named function in a page script, too common to leave inline. */

  /** onclick="this.classList.remove('open')" */
  window.klRemoveClass = function (cls) {
    var el = currentEl;
    if (el) el.classList.remove(cls || 'open');
  };

  /** onclick="document.getElementById('banner').style.display='none'" */
  window.klHide = function (id) {
    var el = id ? document.getElementById(id) : currentEl;
    if (el) el.style.display = 'none';
  };

  /* Filter and search controls used to read the control inline, as
     onchange + "setThing(this.value)" written straight into the attribute.
     Rather than a near-identical adapter for each of the ~20 of them, these two
     forward the control's own value to the named function:
         <select data-change="klValue" data-args="regSetClass">
         <input type="checkbox" data-change="klChecked" data-args="nsvSetGaps"> */
  function forward(prop) {
    return function (fnName) {
      var found = resolve(fnName);
      if (!found) { console.warn('[KLAct] no such action "' + fnName + '"'); return; }
      found.fn.call(found.self == null ? currentEl : found.self, currentEl[prop]);
    };
  }
  window.klValue = forward('value');
  window.klChecked = forward('checked');

  /** onclick="event.stopPropagation()" — a click that must not reach the row behind it. */
  window.klStop = function () {
    var ev = current;
    if (ev) ev.stopPropagation();
  };

  /** Exposed for handlers that need the event (stopPropagation, key checks). */
  window.KLAct = {
    event: function () { return current; },
    /** The element that carries the data-act being dispatched. */
    el: function () { return currentEl; },
    /** Escape a value for safe use inside a double-quoted HTML attribute. */
    attr: function (v) {
      return String(v == null ? '' : v)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;')
        .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    },
    /** Build a data-args attribute from arguments: KLAct.args('a', 1) */
    args: function () {
      return 'data-args="' + window.KLAct.attr(JSON.stringify([].slice.call(arguments))) + '"';
    }
  };
})();
