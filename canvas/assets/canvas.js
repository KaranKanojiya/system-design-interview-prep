/* ============================================================
   Shared interactive layer for systemDesign study canvases.
   Vanilla JS, no dependencies. Each behaviour is opt-in: it only
   runs if the relevant elements/ids exist on the page.
   ============================================================ */
(function () {
  'use strict';
  var $  = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };
  var num = function (id) { var el = document.getElementById(id); return el ? (parseFloat(el.value) || 0) : 0; };
  var set = function (id, txt) { var el = document.getElementById(id); if (el) el.textContent = txt; };

  // ---- formatters ----
  function fmtCount(n) {
    if (!isFinite(n)) return '—';
    var a = Math.abs(n);
    if (a >= 1e12) return trim(n / 1e12) + 'T';
    if (a >= 1e9)  return trim(n / 1e9)  + 'B';
    if (a >= 1e6)  return trim(n / 1e6)  + 'M';
    if (a >= 1e3)  return trim(n / 1e3)  + 'K';
    return String(Math.round(n));
  }
  function trim(v) { return (v >= 100 ? Math.round(v) : Math.round(v * 10) / 10).toString(); }
  function fmtBytes(b) {
    if (!isFinite(b)) return '—';
    var u = ['B', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB'], i = 0, v = Math.abs(b);
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
    var s = i === 0 ? Math.round(v) : (v >= 100 ? v.toFixed(0) : v >= 10 ? v.toFixed(1) : v.toFixed(2));
    return s + ' ' + u[i];
  }

  // ---- tabs: .tabs > .tab[data-tab]  toggles  .tabpanel[data-panel] within [data-tabscope] ----
  function initTabs() {
    $$('.tabs').forEach(function (tabs) {
      var scope = tabs.closest('[data-tabscope]') || document;
      tabs.addEventListener('click', function (e) {
        var t = e.target.closest('.tab'); if (!t) return;
        var id = t.getAttribute('data-tab');
        $$('.tab', tabs).forEach(function (x) { x.classList.toggle('active', x === t); });
        $$('.tabpanel', scope).forEach(function (p) { p.classList.toggle('active', p.getAttribute('data-panel') === id); });
      });
    });
  }

  // ---- collapsibles: .collapsible > .c-head toggles .c-body ----
  function initCollapsibles() {
    $$('.collapsible > .c-head').forEach(function (h) {
      h.addEventListener('click', function () { h.parentElement.classList.toggle('open'); });
    });
  }

  // ---- clickable architecture diagram: [data-node][data-title][data-detail] -> .node-detail ----
  function initDiagram() {
    $$('.arch').forEach(function (arch) {
      var panel = $('.node-detail', arch.parentElement) || $('.node-detail');
      $$('[data-node]', arch).forEach(function (n) {
        n.addEventListener('click', function () {
          $$('[data-node]', arch).forEach(function (x) { x.classList.toggle('sel', x === n); });
          if (panel) {
            var t = panel.querySelector('.nd-title'), b = panel.querySelector('.nd-body');
            if (t) t.textContent = n.getAttribute('data-title') || n.textContent.trim();
            if (b) b.innerHTML = n.getAttribute('data-detail') || '';
          }
        });
      });
    });
  }

  // ---- request-flow stepper ----
  // Controls:  [data-flowctl] containing path buttons [data-flow="write"] and step buttons [data-flowstep="next|prev"].
  // Arrows:    .arrow with data-flow="write read" (space-separated paths it belongs to), a per-path order
  //            data-<path> (e.g. data-write="3"), and a per-path description data-desc-<path>.
  // A path's steps share the first hops but can carry different step numbers per path.
  function initFlow() {
    $$('[data-flowctl]').forEach(function (ctl) {
      var scope = ctl.closest('[data-flowscope]') || document;
      var label = $('[data-flowlabel]', scope);
      var st = { path: null, steps: [], idx: -1 };
      function clearAll() { $$('.arrow', scope).forEach(function (a) { a.classList.remove('hl', 'hl-step'); }); }
      function selectPath(path) {
        st.path = path;
        st.steps = $$('.arrow', scope).filter(function (a) {
          return (a.getAttribute('data-flow') || '').split(/\s+/).indexOf(path) !== -1 && a.getAttribute('data-' + path) != null;
        }).sort(function (x, y) {
          return (parseInt(x.getAttribute('data-' + path), 10) || 0) - (parseInt(y.getAttribute('data-' + path), 10) || 0);
        });
        st.idx = -1;
        clearAll();
        st.steps.forEach(function (a) { a.classList.add('hl'); });
        $$('[data-flow]', ctl).forEach(function (b) { b.classList.toggle('active', b.getAttribute('data-flow') === path); });
        if (label) label.textContent = st.steps.length
          ? (path + ' path — ' + st.steps.length + ' steps. Press “Next ▶” to walk through them.')
          : 'No steps defined for this path.';
      }
      function step(dir) {
        if (!st.steps.length) return;
        st.idx = Math.max(0, Math.min(st.steps.length - 1, st.idx + dir));
        st.steps.forEach(function (a, i) { a.classList.toggle('hl-step', i === st.idx); });
        var cur = st.steps[st.idx];
        var d = cur.getAttribute('data-desc-' + st.path) || cur.getAttribute('data-desc') || '';
        if (label) label.textContent = '(' + (st.idx + 1) + '/' + st.steps.length + ')  ' + d;
      }
      ctl.addEventListener('click', function (e) {
        var p = e.target.closest('[data-flow]'); if (p) { selectPath(p.getAttribute('data-flow')); return; }
        var s = e.target.closest('[data-flowstep]'); if (s) { step(s.getAttribute('data-flowstep') === 'next' ? 1 : -1); }
      });
    });
  }

  // ---- capacity estimator ----
  function initEstimator() {
    if (!document.getElementById('est-dau')) return;
    function calc() {
      var dau = num('est-dau'), acts = num('est-actions'), rw = num('est-rw'),
          payload = num('est-payload'), years = num('est-retention');
      var writesDay = dau * acts;
      var readsDay  = writesDay * rw;
      var wQPS = writesDay / 86400, rQPS = readsDay / 86400, tQPS = wQPS + rQPS, peak = tQPS * 3;
      var storeDay = writesDay * payload;
      var storeTotal = storeDay * 365 * years * 3;   // ×3 replication factor
      var bw = rQPS * payload;                        // read egress bytes/sec
      set('o-writesday', fmtCount(writesDay) + '/day');
      set('o-readsday',  fmtCount(readsDay) + '/day');
      set('o-wqps', fmtCount(wQPS) + '/s');
      set('o-rqps', fmtCount(rQPS) + '/s');
      set('o-tqps', fmtCount(tQPS) + '/s');
      set('o-peak', fmtCount(peak) + '/s');
      set('o-storeday', fmtBytes(storeDay) + '/day');
      set('o-storetotal', fmtBytes(storeTotal));
      set('o-bw', fmtBytes(bw) + '/s');
    }
    ['est-dau','est-actions','est-rw','est-payload','est-retention'].forEach(function (id) {
      var el = document.getElementById(id); if (el) el.addEventListener('input', calc);
    });
    calc();
  }

  // ---- byte converter (1024 vs 1000) ----
  function initConverter() {
    if (!document.getElementById('cv-val')) return;
    var base = 1024, units = ['B','KB','MB','GB','TB','PB'];
    function calc() {
      var v = num('cv-val'), unit = document.getElementById('cv-unit').value, idx = units.indexOf(unit);
      var bytes = v * Math.pow(base, idx);
      units.forEach(function (u, i) {
        var val = bytes / Math.pow(base, i);
        set('cv-' + u, val.toLocaleString(undefined, { maximumFractionDigits: 2 }));
      });
    }
    ['cv-val','cv-unit'].forEach(function (id) {
      var el = document.getElementById(id); if (el) el.addEventListener('input', calc);
    });
    $$('[data-base]').forEach(function (b) {
      b.addEventListener('click', function () {
        base = parseInt(b.getAttribute('data-base'), 10);
        $$('[data-base]').forEach(function (x) { x.classList.toggle('active', x === b); });
        calc();
      });
    });
    calc();
  }

  document.addEventListener('DOMContentLoaded', function () {
    initTabs(); initCollapsibles(); initDiagram(); initFlow(); initEstimator(); initConverter();
  });
})();
