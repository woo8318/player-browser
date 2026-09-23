// 요소 숨기기 — 영역 선택 피커 (v1.3.100).
//
// ElementPickerCommands 가 사용자가 ⋮ 메뉴를 눌렀을 때만 evaluateJavascript 로
// 평가한다(챌린지 페이지 제외). 떠 있는 동안 화면 전체를 덮는 오버레이와
// window 캡처 리스너가 모든 터치를 삼켜 페이지로는 아무것도 가지 않는다.
//
//   한 손가락 끌기  → 사각형 안에 (면적 85% 이상) 들어온 요소를 선택에 추가
//   한 손가락 탭    → 그 지점 요소(48px 미만이면 부모로)를 추가, 이미 선택된
//                     영역 안이면 그 선택을 해제
//   두 손가락       → 스크롤
//
// confirm() 은 고른 요소의 CSS 선택자만 돌려준다 — 저장/적용은 Kotlin
// (ElementHideStore / ElementHider) 몫이고, 페이지로 넘기는 데이터는 없다.
(function () {
  'use strict';
  if (window.__pbPicker) return;

  var MAX_SEL = 20;
  var MAX_SCAN = 20000;
  var MIN_TAP_PX = 48;
  var TAP_SLOP = 10;
  var COVER = 0.85;
  var MAX_SELECTOR = 480;
  var MAX_DEPTH = 20;

  var BLOCKED = [
    'touchstart', 'touchmove', 'touchend', 'touchcancel',
    'pointerdown', 'pointermove', 'pointerup', 'pointercancel',
    'mousedown', 'mousemove', 'mouseup', 'click', 'dblclick', 'auxclick', 'contextmenu'
  ];

  var I = '!important;';
  var ROOT_CSS =
    'position:fixed' + I + 'left:0' + I + 'top:0' + I + 'right:0' + I + 'bottom:0' + I +
    'width:auto' + I + 'height:auto' + I + 'margin:0' + I + 'padding:0' + I + 'border:0' + I +
    'display:block' + I + 'z-index:2147483647' + I + 'background:rgba(0,0,0,.12)' + I +
    'touch-action:none' + I + 'user-select:none' + I + '-webkit-user-select:none' + I +
    '-webkit-touch-callout:none' + I + 'pointer-events:auto' + I + 'opacity:1' + I +
    'transform:none' + I + 'overflow:hidden' + I + 'visibility:visible' + I;
  var BOX_CSS =
    'position:absolute' + I + 'display:block' + I + 'box-sizing:border-box' + I + 'margin:0' + I +
    'padding:0' + I + 'border:2px solid #e53935' + I + 'background:rgba(229,57,53,.28)' + I +
    'pointer-events:none' + I;
  var RECT_CSS =
    'position:absolute' + I + 'box-sizing:border-box' + I + 'margin:0' + I + 'padding:0' + I +
    'border:2px dashed #1e88e5' + I + 'background:rgba(30,136,229,.15)' + I +
    'pointer-events:none' + I + 'display:none' + I;
  var LABEL_CSS =
    'position:absolute' + I + 'display:block' + I + 'left:50%' + I + 'top:8px' + I +
    'transform:translateX(-50%)' + I + 'margin:0' + I + 'padding:4px 12px' + I + 'border:0' + I +
    'border-radius:12px' + I + 'background:rgba(0,0,0,.75)' + I + 'color:#fff' + I +
    'font:600 13px/1.4 sans-serif' + I + 'white-space:nowrap' + I + 'pointer-events:none' + I;

  var st = null;

  // ---- helpers ----

  function mk(tag, cssText) {
    var el = document.createElement(tag);
    el.style.cssText = cssText;
    return el;
  }

  function setBox(el, left, top, w, h) {
    el.style.setProperty('left', left + 'px', 'important');
    el.style.setProperty('top', top + 'px', 'important');
    el.style.setProperty('width', w + 'px', 'important');
    el.style.setProperty('height', h + 'px', 'important');
    el.style.setProperty('display', 'block', 'important');
  }

  function hide(el) { el.style.setProperty('display', 'none', 'important'); }

  function isOurs(el) { return !!st && (el === st.root || st.root.contains(el)); }

  function connected(el) { return !!el && el.isConnected !== false && document.documentElement.contains(el); }

  function isPageRoot(el) {
    return !el || el === document.body || el === document.documentElement;
  }

  function sameSize(a, b) {
    return Math.abs(a.width - b.width) <= 1 && Math.abs(a.height - b.height) <= 1;
  }

  function contains(list, el) {
    for (var i = 0; i < list.length; i++) if (list[i] === el) return true;
    return false;
  }

  /** Drops entries that sit inside another entry — hiding the outer one covers them. */
  function outermost(list) {
    var out = [];
    for (var i = 0; i < list.length; i++) {
      var el = list[i], inside = false;
      for (var j = 0; j < list.length; j++) {
        if (i !== j && list[j] !== el && list[j].contains(el)) { inside = true; break; }
      }
      if (!inside && !contains(out, el)) out.push(el);
    }
    return out.slice(0, MAX_SEL);
  }

  function hitAt(x, y) {
    var list = document.elementsFromPoint ? document.elementsFromPoint(x, y) : [];
    for (var i = 0; i < list.length; i++) {
      if (isOurs(list[i])) continue;
      return isPageRoot(list[i]) ? null : list[i];
    }
    return null;
  }

  // ---- selection ----

  function add(list) {
    var changed = false;
    for (var i = 0; i < list.length && st.sel.length < MAX_SEL; i++) {
      var el = list[i], covered = false;
      for (var j = 0; j < st.sel.length; j++) {
        if (st.sel[j] === el || st.sel[j].contains(el)) { covered = true; break; }
      }
      if (covered) continue;
      st.sel = st.sel.filter(function (s) { return !el.contains(s); }).concat([el]);
      changed = true;
    }
    if (changed) st.stack = [];
  }

  /** Grows a tiny hit (icon, text run) to something big enough to mean "that thing". */
  function grow(el) {
    var cur = el;
    for (var i = 0; i < 30; i++) {
      var r = cur.getBoundingClientRect();
      if (r.width >= MIN_TAP_PX && r.height >= MIN_TAP_PX) return cur;
      var p = cur.parentElement;
      if (isPageRoot(p)) return cur;
      cur = p;
    }
    return cur;
  }

  function tapAt(x, y) {
    var hit = hitAt(x, y);
    if (!hit) return;
    for (var i = 0; i < st.sel.length; i++) {
      if (st.sel[i] === hit || st.sel[i].contains(hit)) {
        var off = st.sel[i];
        st.sel = st.sel.filter(function (s) { return s !== off; });
        st.stack = [];
        return;
      }
    }
    add([grow(hit)]);
  }

  function invisible(el) {
    try {
      var cs = getComputedStyle(el);
      return cs.visibility === 'hidden' || cs.opacity === '0';
    } catch (e) { return false; }
  }

  /** Outermost elements with at least COVER of their area inside the rectangle. */
  function collect(l, t, r, b) {
    var out = [];
    var body = document.body;
    if (!body) return out;
    var scanned = 0, full = false;
    var filter = function (node) {
      if (full || ++scanned > MAX_SCAN) return NodeFilter.FILTER_REJECT;
      if (isOurs(node)) return NodeFilter.FILTER_REJECT;
      var br = node.getBoundingClientRect();
      var area = br.width * br.height;
      if (area < 4) return NodeFilter.FILTER_SKIP;
      var iw = Math.min(br.right, r) - Math.max(br.left, l);
      var ih = Math.min(br.bottom, b) - Math.max(br.top, t);
      // Children may overflow their parent's box, so a miss only skips this node.
      if (iw <= 0 || ih <= 0) return NodeFilter.FILTER_SKIP;
      if ((iw * ih) / area < COVER) return NodeFilter.FILTER_SKIP;
      if (invisible(node)) return NodeFilter.FILTER_REJECT;
      out.push(node);
      if (out.length >= MAX_SEL) full = true;
      return NodeFilter.FILTER_REJECT;
    };
    var walker = document.createTreeWalker(body, NodeFilter.SHOW_ELEMENT, { acceptNode: filter });
    while (walker.nextNode()) { /* the filter does the work */ }
    return out;
  }

  /** First ancestor that is visibly bigger (same-size wrappers are skipped). */
  function biggerParent(el) {
    var r0 = el.getBoundingClientRect();
    for (var p = el.parentElement; !isPageRoot(p); p = p.parentElement) {
      if (!sameSize(p.getBoundingClientRect(), r0)) return p;
    }
    return null;
  }

  /** Largest child that is visibly smaller (same-size wrappers are skipped). */
  function smallerChild(el) {
    var r0 = el.getBoundingClientRect();
    var cur = el;
    for (var guard = 0; guard < MAX_DEPTH; guard++) {
      var best = null, bestArea = 0;
      for (var c = cur.firstElementChild; c; c = c.nextElementSibling) {
        var r = c.getBoundingClientRect();
        var a = r.width * r.height;
        if (a > bestArea) { best = c; bestArea = a; }
      }
      if (!best) return null;
      if (!sameSize(best.getBoundingClientRect(), r0)) return best;
      cur = best;
    }
    return null;
  }

  // ---- selectors ----

  /** Ids/classes that look hand-written: no long digit runs (generated ids, hashes). */
  function stableToken(t) {
    return typeof t === 'string' && t.length > 0 && t.length <= 40 &&
      /^[A-Za-z_][A-Za-z0-9_-]*$/.test(t) && !/\d{4,}/.test(t) && t.indexOf('__pb') !== 0;
  }

  function esc(s) {
    return (window.CSS && CSS.escape) ? CSS.escape(s) : String(s).replace(/[^A-Za-z0-9_-]/g, '\\$&');
  }

  function matchesSel(node, sel) {
    try { return node.matches(sel); } catch (e) { return false; }
  }

  function uniqueTo(sel, el) {
    try {
      var found = document.querySelectorAll(sel);
      return found.length === 1 && found[0] === el;
    } catch (e) { return false; }
  }

  function typeIndex(el) {
    var i = 1;
    for (var s = el.previousElementSibling; s; s = s.previousElementSibling) {
      if (s.tagName === el.tagName) i++;
    }
    return i;
  }

  function segmentFor(el) {
    var seg;
    if (stableToken(el.id)) {
      seg = '#' + esc(el.id);
    } else {
      // localName keeps SVG camelCase (foreignObject) — case matters outside HTML.
      seg = esc(el.localName);
      var cls = el.classList, n = 0;
      for (var i = 0; cls && i < cls.length && n < 2; i++) {
        if (stableToken(cls[i])) { seg += '.' + esc(cls[i]); n++; }
      }
    }
    var p = el.parentElement;
    if (p) {
      for (var c = p.firstElementChild; c; c = c.nextElementSibling) {
        if (c !== el && matchesSel(c, seg)) { seg += ':nth-of-type(' + typeIndex(el) + ')'; break; }
      }
    }
    return seg;
  }

  /** Shortest `a > b > c` chain (bottom-up) that matches exactly [el]. */
  function selectorFor(el) {
    var segs = [];
    var cur = el;
    for (var depth = 0; cur && depth < MAX_DEPTH; depth++) {
      var tag = cur.localName || '';
      if (!tag || tag === 'html') break;
      segs.unshift(tag === 'body' ? 'body' : segmentFor(cur));
      var sel = segs.join(' > ');
      if (sel.length > MAX_SELECTOR) return null;
      if (uniqueTo(sel, el)) return sel;
      if (tag === 'body') break;
      cur = cur.parentElement;
    }
    return null;
  }

  function hostOf(url) {
    try { return new URL(url, location.href).hostname; } catch (e) { return ''; }
  }

  function fileOf(url) {
    var path = String(url || '').split(/[?#]/)[0];
    return path.substring(path.lastIndexOf('/') + 1);
  }

  function labelFor(el) {
    var tag = el.localName;
    var r = el.getBoundingClientRect();
    var text = '';
    if (tag === 'img') text = el.getAttribute('alt') || fileOf(el.currentSrc || el.src);
    else if (tag === 'iframe') text = hostOf(el.src);
    else if (tag === 'video') text = '영상';
    if (!text) text = el.innerText || el.textContent || '';
    text = String(text).replace(/\s+/g, ' ').trim();
    if (text.length > 40) text = text.slice(0, 40) + '…';
    if (!text) {
      for (var i = 0; el.classList && i < el.classList.length; i++) {
        if (stableToken(el.classList[i])) { text = '.' + el.classList[i]; break; }
      }
    }
    return tag + ' ' + Math.round(r.width) + '×' + Math.round(r.height) + (text ? ' · ' + text : '');
  }

  // ---- drawing ----

  function render() {
    if (!st) return;
    st.sel = st.sel.filter(connected);
    while (st.boxes.length < st.sel.length) {
      var box = mk('pb-pick-box', BOX_CSS);
      st.root.insertBefore(box, st.rect);
      st.boxes.push(box);
    }
    for (var i = 0; i < st.boxes.length; i++) {
      if (i < st.sel.length) {
        var r = st.sel[i].getBoundingClientRect();
        setBox(st.boxes[i], r.left, r.top, r.width, r.height);
      } else {
        hide(st.boxes[i]);
      }
    }
    st.label.textContent = st.sel.length
      ? st.sel.length + '개 선택됨'
      : '끌어서 영역 선택 · 탭해서 요소 선택 · 두 손가락으로 스크롤';
  }

  function scheduleRender() {
    if (!st || st.raf) return;
    st.raf = requestAnimationFrame(function () {
      if (!st) return;
      st.raf = 0;
      render();
    });
  }

  function drawRect(d) {
    setBox(st.rect, Math.min(d.x0, d.x1), Math.min(d.y0, d.y1),
      Math.abs(d.x1 - d.x0), Math.abs(d.y1 - d.y0));
  }

  // ---- two-finger scroll ----

  function pointerCount() { return Object.keys(st.ptrs).length; }

  function centroid() {
    var ids = Object.keys(st.ptrs), x = 0, y = 0;
    for (var i = 0; i < ids.length; i++) { x += st.ptrs[ids[i]].x; y += st.ptrs[ids[i]].y; }
    return { x: x / ids.length, y: y / ids.length };
  }

  function scrollerAt(x, y) {
    var hit = hitAt(x, y);
    for (var cur = hit; !isPageRoot(cur); cur = cur.parentElement) {
      if (cur.scrollHeight > cur.clientHeight + 1) {
        var oy = '';
        try { oy = getComputedStyle(cur).overflowY; } catch (e) { /* keep looking */ }
        if (oy === 'auto' || oy === 'scroll') return cur;
      }
    }
    return null;
  }

  function scrollBy(target, dx, dy) {
    if (target) { target.scrollTop += dy; target.scrollLeft += dx; } else window.scrollBy(dx, dy);
  }

  // ---- input ----

  function onDown(e) {
    st.ptrs[e.pointerId] = { x: e.clientX, y: e.clientY };
    if (pointerCount() >= 2) {
      st.drag = null;
      hide(st.rect);
      st.pan = centroid();
      st.panTarget = scrollerAt(st.pan.x, st.pan.y);
      st.panned = true;
      return;
    }
    if (st.panned) return;
    st.drag = { id: e.pointerId, x0: e.clientX, y0: e.clientY, x1: e.clientX, y1: e.clientY, moved: false };
  }

  function onMove(e) {
    var p = st.ptrs[e.pointerId];
    if (!p) return;
    p.x = e.clientX;
    p.y = e.clientY;
    if (st.pan) {
      var c = centroid();
      scrollBy(st.panTarget, st.pan.x - c.x, st.pan.y - c.y);
      st.pan = c;
      return;
    }
    var d = st.drag;
    if (!d || d.id !== e.pointerId) return;
    d.x1 = e.clientX;
    d.y1 = e.clientY;
    if (!d.moved && (Math.abs(d.x1 - d.x0) > TAP_SLOP || Math.abs(d.y1 - d.y0) > TAP_SLOP)) d.moved = true;
    if (d.moved) drawRect(d);
  }

  function onUp(e) {
    if (!st.ptrs[e.pointerId]) return;
    delete st.ptrs[e.pointerId];
    if (st.panned) {
      st.pan = null;
      if (pointerCount() === 0) st.panned = false;
      return;
    }
    var d = st.drag;
    st.drag = null;
    hide(st.rect);
    if (!d || d.id !== e.pointerId) return;
    if (d.moved) {
      add(collect(Math.min(d.x0, d.x1), Math.min(d.y0, d.y1), Math.max(d.x0, d.x1), Math.max(d.y0, d.y1)));
    } else {
      tapAt(d.x0, d.y0);
    }
    render();
  }

  function onCancel(e) {
    delete st.ptrs[e.pointerId];
    st.drag = null;
    hide(st.rect);
    if (pointerCount() === 0) { st.pan = null; st.panned = false; }
  }

  function onEvent(e) {
    if (!st) return;
    e.stopImmediatePropagation();
    if (e.cancelable) e.preventDefault();
    try {
      if (e.type === 'pointerdown') onDown(e);
      else if (e.type === 'pointermove') onMove(e);
      else if (e.type === 'pointerup') onUp(e);
      else if (e.type === 'pointercancel') onCancel(e);
    } catch (err) { /* a broken page must not wedge the picker */ }
  }

  // ---- commands (called by ElementPickerCommands) ----

  function start() {
    if (st) return 'already';
    var docEl = document.documentElement;
    if (!docEl) return 'nodoc';
    var root = mk('pb-pick', ROOT_CSS);
    var rect = mk('pb-pick-rect', RECT_CSS);
    var label = mk('pb-pick-label', LABEL_CSS);
    root.appendChild(rect);
    root.appendChild(label);
    // On <html>, not <body>: saved `body > …` chains can never reach the overlay.
    docEl.appendChild(root);
    st = {
      root: root, rect: rect, label: label, boxes: [], sel: [], stack: [],
      ptrs: {}, drag: null, pan: null, panTarget: null, panned: false, raf: 0
    };
    for (var i = 0; i < BLOCKED.length; i++) {
      window.addEventListener(BLOCKED[i], onEvent, { capture: true, passive: false });
    }
    window.addEventListener('scroll', scheduleRender, true);
    window.addEventListener('resize', scheduleRender, true);
    window.__pbPickerActive = true;
    render();
    return 'ok';
  }

  function widen() {
    if (!st) return -1;
    if (!st.sel.length) return 0;
    var next = [], grew = false;
    for (var i = 0; i < st.sel.length; i++) {
      var p = biggerParent(st.sel[i]);
      if (p) grew = true;
      next.push(p || st.sel[i]);
    }
    if (grew) {
      st.stack = st.stack.concat([st.sel]);
      st.sel = outermost(next);
      render();
    }
    return st.sel.length;
  }

  function narrow() {
    if (!st) return -1;
    if (!st.sel.length) return 0;
    if (st.stack.length) {
      st.sel = st.stack[st.stack.length - 1].filter(connected);
      st.stack = st.stack.slice(0, -1);
    } else {
      var next = [];
      for (var i = 0; i < st.sel.length; i++) next.push(smallerChild(st.sel[i]) || st.sel[i]);
      st.sel = outermost(next);
    }
    render();
    return st.sel.length;
  }

  /** Selectors + labels of the selection, then an empty selection (picker stays up). */
  function confirm() {
    if (!st) return null;
    var out = [];
    var sel = st.sel.filter(connected);
    for (var i = 0; i < sel.length; i++) {
      var s = selectorFor(sel[i]);
      // No id/class anywhere in the chain = pure position; if the site later
      // inserts a sibling it can hit other content, so the label says so.
      if (s) out.push({ s: s, l: (/[#.]/.test(s) ? '' : '(위치) ') + labelFor(sel[i]) });
    }
    st.sel = [];
    st.stack = [];
    render();
    return out;
  }

  function exit() {
    window.__pbPickerActive = false;
    if (!st) return 'idle';
    for (var i = 0; i < BLOCKED.length; i++) window.removeEventListener(BLOCKED[i], onEvent, true);
    window.removeEventListener('scroll', scheduleRender, true);
    window.removeEventListener('resize', scheduleRender, true);
    if (st.raf) cancelAnimationFrame(st.raf);
    if (st.root.parentNode) st.root.parentNode.removeChild(st.root);
    st = null;
    return 'ok';
  }

  /** start() then select the element under (x, y) in CSS px — link long-press "요소 숨기기". */
  function startAt(x, y) {
    var r = start();
    if (r !== 'ok' && r !== 'already') return r;
    try {
      x = +x; y = +y;
      if (isFinite(x) && isFinite(y)) { tapAt(x, y); render(); }
    } catch (e) {}
    return r;
  }

  var api = { start: start, startAt: startAt, widen: widen, narrow: narrow, confirm: confirm, exit: exit };
  try {
    Object.defineProperty(window, '__pbPicker', { value: api, configurable: true, enumerable: false, writable: false });
  } catch (e) {
    window.__pbPicker = api;
  }
})();
