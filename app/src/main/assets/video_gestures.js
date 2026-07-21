(function () {
  if (window.__pbGestureInstalled) return;
  window.__pbGestureInstalled = true;

  var SEEK_SEC = 10;
  var SCRUB_THRESHOLD_PX = 20;
  var SWIPE_THRESHOLD_PX = 40;
  var SWIPE_TIME_LIMIT_MS = 800;
  var DOUBLE_TAP_MS = 300;
  var DOUBLE_TAP_MAX_MOVE = 30;

  // Disable the WebView's built-in double-tap-to-zoom on the whole document.
  // Pinch-zoom still works; we just don't want it stealing our double-tap.
  try {
    var css = document.createElement('style');
    css.id = '__pb_touch_css';
    css.textContent = 'html, body { touch-action: manipulation; }';
    (document.head || document.documentElement).appendChild(css);
  } catch (e) {}

  // Walk the main document plus every same-origin iframe we can reach.
  // Cross-origin iframes throw on contentDocument access and are skipped.
  function reachableDocuments() {
    var docs = [document];
    var i = 0;
    while (i < docs.length) {
      var d = docs[i++];
      var frames;
      try { frames = d.querySelectorAll('iframe, frame'); } catch (e) { continue; }
      for (var j = 0; j < frames.length; j++) {
        try {
          var cd = frames[j].contentDocument;
          if (cd && docs.indexOf(cd) === -1) docs.push(cd);
        } catch (e) { /* cross-origin — unreachable */ }
      }
    }
    return docs;
  }

  function allVideosRaw() {
    var out = [];
    var docs = reachableDocuments();
    for (var di = 0; di < docs.length; di++) {
      var nodes;
      try { nodes = docs[di].querySelectorAll('video'); } catch (e) { continue; }
      for (var i = 0; i < nodes.length; i++) out.push(nodes[i]);
    }
    return out;
  }

  function allVideos() {
    return allVideosRaw().filter(function (v) {
      var r = v.getBoundingClientRect();
      return r.width > 80 && r.height > 60;
    });
  }

  function fullscreenVideo() {
    var docs = reachableDocuments();
    for (var i = 0; i < docs.length; i++) {
      var fe = docs[i].fullscreenElement || docs[i].webkitFullscreenElement;
      if (fe && fe.tagName === 'VIDEO') return fe;
    }
    for (var k = 0; k < docs.length; k++) {
      var fe2 = docs[k].fullscreenElement || docs[k].webkitFullscreenElement;
      if (fe2 && fe2.querySelector) {
        var v = fe2.querySelector('video');
        if (v) return v;
      }
    }
    for (var m = 0; m < docs.length; m++) {
      var fe3 = docs[m].fullscreenElement || docs[m].webkitFullscreenElement;
      if (fe3 && (fe3.tagName === 'IFRAME' || fe3.tagName === 'FRAME')) {
        try {
          var inner = fe3.contentDocument && fe3.contentDocument.querySelector('video');
          if (inner) return inner;
        } catch (e) { /* cross-origin */ }
      }
    }
    return null;
  }

  function activeVideo() {
    var vids = allVideosRaw();
    for (var i = 0; i < vids.length; i++) {
      if (!vids[i].paused && vids[i].readyState >= 2) return vids[i];
    }
    return vids[0] || null;
  }

  function videoAtPoint(x, y) {
    var fs = fullscreenVideo();
    if (fs) return fs;
    var vids = allVideos();
    for (var i = 0; i < vids.length; i++) {
      var r = vids[i].getBoundingClientRect();
      if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return vids[i];
    }
    return activeVideo();
  }

  // Strict variant: true only when the point is actually inside a video's box
  // (no activeVideo fallback). Used to decide whether to hijack a tap — without
  // it, a tap anywhere on a page that merely *has* a video would toggle
  // play/pause and swallow the event, breaking links/buttons.
  function isPointOnVideo(x, y) {
    if (fullscreenVideo()) return true;
    var vids = allVideos();
    for (var i = 0; i < vids.length; i++) {
      var r = vids[i].getBoundingClientRect();
      if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return true;
    }
    return false;
  }

  // Topmost element at a point, piercing open shadow roots (custom video players
  // often render their chrome inside a shadow DOM).
  function deepElementFromPoint(x, y) {
    var el;
    try { el = document.elementFromPoint(x, y); } catch (e) { return null; }
    var guard = 0;
    while (el && el.shadowRoot && guard < 10) {
      var inner;
      try { inner = el.shadowRoot.elementFromPoint(x, y); } catch (e) { break; }
      if (!inner || inner === el) break;
      el = inner;
      guard++;
    }
    return el;
  }

  function isControlEl(el) {
    if (!el || el.nodeType !== 1) return false;
    var tag = el.tagName;
    if (tag === 'BUTTON' || tag === 'A' || tag === 'INPUT' || tag === 'SELECT' ||
        tag === 'TEXTAREA' || tag === 'LABEL' || tag === 'SUMMARY') return true;
    var role = el.getAttribute && el.getAttribute('role');
    if (role === 'button' || role === 'link' || role === 'menuitem' ||
        role === 'menuitemcheckbox' || role === 'checkbox' || role === 'switch' ||
        role === 'tab' || role === 'option') return true;
    if (el.hasAttribute && el.hasAttribute('onclick')) return true;
    return false;
  }

  // Broader "is this a clickable thing" test: real control elements OR anything
  // styled as clickable (cursor:pointer) — which is how virtually every custom
  // toolbar button (play/stop/⏪/⏩/expand rendered as <div>/<span>/<i>) signals
  // it's tappable, even without a button tag / role / onclick attribute.
  function looksInteractive(el) {
    if (isControlEl(el)) return true;
    try {
      var cs = window.getComputedStyle(el);
      if (cs && cs.cursor === 'pointer') return true;
    } catch (e) {}
    return false;
  }

  // True when the topmost element under (x,y) is a *small* interactive control
  // overlaid on the video (e.g. a close "×", a control-bar button) that should
  // receive the tap itself instead of our play/pause. A full-size click-catcher
  // (≥ half the video area) stays owned by us so play/pause keeps working on a
  // bare video tap — that's the Option A policy for the common play/pause overlay.
  function controlAtPoint(x, y, video) {
    var el = deepElementFromPoint(x, y);
    if (!el) return false;
    var vr = video ? video.getBoundingClientRect() : null;
    var videoArea = vr ? Math.max(1, vr.width * vr.height) : 0;
    var depth = 0;
    while (el && el !== video && depth < 6) {
      if (el === document.body || el === document.documentElement) break;
      if (el.tagName === 'VIDEO') break;
      if (looksInteractive(el)) {
        if (!videoArea) return true;
        var er = el.getBoundingClientRect();
        var area = er.width * er.height;
        return area > 0 && area < videoArea * 0.5;
      }
      el = el.parentElement || (el.getRootNode && el.getRootNode().host) || null;
      depth++;
    }
    return false;
  }

  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  function formatTime(sec) {
    sec = Math.max(0, Math.floor(sec || 0));
    var h = Math.floor(sec / 3600);
    var m = Math.floor((sec % 3600) / 60);
    var s = sec % 60;
    return (h > 0 ? h + ':' + pad2(m) : '' + m) + ':' + pad2(s);
  }

  function showToast(msg) {
    var el = document.getElementById('__pb_toast');
    if (!el) {
      el = document.createElement('div');
      el.id = '__pb_toast';
      el.style.cssText =
        'position:fixed;left:50%;top:18%;transform:translateX(-50%);' +
        'background:rgba(0,0,0,0.75);color:#fff;font:600 16px/1.2 sans-serif;' +
        'padding:10px 16px;border-radius:8px;z-index:2147483647;' +
        'pointer-events:none;transition:opacity .2s;opacity:0;';
      document.documentElement.appendChild(el);
    }
    el.textContent = msg;
    el.style.opacity = '1';
    clearTimeout(el.__t);
    el.__t = setTimeout(function () { el.style.opacity = '0'; }, 700);
  }

  function showScrub(currentSec, totalSec, deltaSec) {
    var el = document.getElementById('__pb_scrub');
    if (!el) {
      el = document.createElement('div');
      el.id = '__pb_scrub';
      el.style.cssText =
        'position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);' +
        'background:rgba(0,0,0,0.78);color:#fff;font:600 18px/1.3 sans-serif;' +
        'padding:12px 20px;border-radius:10px;z-index:2147483647;' +
        'pointer-events:none;text-align:center;';
      document.documentElement.appendChild(el);
    }
    var deltaTxt = '';
    if (typeof deltaSec === 'number' && isFinite(deltaSec)) {
      var sign = deltaSec >= 0 ? '+' : '−';
      deltaTxt = '<div style="font:500 13px/1.2 sans-serif;opacity:.8;margin-top:4px;">' +
        sign + formatTime(Math.abs(deltaSec)) + '</div>';
    }
    el.innerHTML = formatTime(currentSec) + ' / ' + formatTime(totalSec) + deltaTxt;
    el.style.display = 'block';
  }
  function hideScrub() {
    var el = document.getElementById('__pb_scrub');
    if (el) el.style.display = 'none';
  }

  function seekBy(video, delta) {
    try {
      var t = Math.max(0, Math.min((video.duration || 0) - 0.1, (video.currentTime || 0) + delta));
      video.currentTime = t;
      showToast((delta > 0 ? '+' : '') + delta + 's');
    } catch (e) {}
  }

  function togglePlay(video) {
    try {
      if (video.paused) { video.play(); showToast('재생'); }
      else { video.pause(); showToast('일시정지'); }
    } catch (e) {}
  }

  // Replay a tap as a synthetic pointer/mouse click on the given element so the
  // site's own handler runs. Untrusted (isTrusted === false), so our capture
  // click-suppressor lets it through. Returns false if dispatch threw.
  function dispatchSyntheticClick(el, x, y) {
    if (!el) return false;
    try {
      var base = {
        bubbles: true, cancelable: true, composed: true,
        clientX: x, clientY: y, view: window
      };
      if (window.PointerEvent) {
        var pbase = {};
        for (var k in base) pbase[k] = base[k];
        pbase.pointerId = 1; pbase.pointerType = 'touch'; pbase.isPrimary = true;
        el.dispatchEvent(new PointerEvent('pointerdown', pbase));
        el.dispatchEvent(new PointerEvent('pointerup', pbase));
      }
      el.dispatchEvent(new MouseEvent('mousedown', base));
      el.dispatchEvent(new MouseEvent('mouseup', base));
      el.dispatchEvent(new MouseEvent('click', base));
      return true;
    } catch (e) { return false; }
  }

  // Single-tap play/pause that keeps the site's UI in sync. If a layer sits on
  // top of the video (commonly the site's own play button / poster), forward
  // the tap to that element so the site toggles play AND hides its own overlay —
  // otherwise we'd play via the API while the button lingers on screen. Fall
  // back to the direct API if the site didn't actually change the play state
  // (e.g. a bare <video>, native controls, or a non-interactive overlay).
  function playPauseAtPoint(video, x, y) {
    var top = deepElementFromPoint(x, y);
    // Bare video surface (or no element): the API toggle is our job.
    if (!top || top === video || top.tagName === 'VIDEO') {
      if (video) togglePlay(video);
      return;
    }
    // A layer sits on top. If it looks clickable (site play button, toolbar
    // button…), forward the tap so the site does its own thing — play, expand,
    // ⏩, whatever — and updates/hides its own UI. Do NOT toggle play ourselves:
    // the button may do something unrelated (expand), and a blind fallback would
    // pause the video right after. Only fall back to our API toggle when the
    // overlay is non-interactive (a passive poster/gradient that swallows taps).
    var forwarded = dispatchSyntheticClick(top, x, y);
    if (video && (!forwarded || !looksInteractive(top))) togglePlay(video);
  }

  function switchVideo(direction) {
    var vids = allVideos();
    if (vids.length === 0) return;
    var current = -1;
    for (var i = 0; i < vids.length; i++) {
      if (!vids[i].paused) { current = i; break; }
    }
    if (current === -1) current = 0;
    var next = (current + (direction > 0 ? 1 : -1) + vids.length) % vids.length;
    try { vids[current].pause(); } catch (e) {}
    try {
      vids[next].scrollIntoView({ behavior: 'smooth', block: 'center' });
      vids[next].play();
      showToast(direction > 0 ? '다음 영상' : '이전 영상');
    } catch (e) {}
  }

  // Android-callable hooks. Used by the native fullscreen gesture overlay
  // since touch events on the CustomView never reach the WebView document.
  window.__pb = window.__pb || {};
  // Set by the native fullscreen overlay (GestureCapturingFrame). While true,
  // the in-document touch handlers below bail out so the Kotlin gesture frame
  // is the single source of seek/scrub/switch — otherwise a fullscreen
  // double-tap seeks twice (Kotlin + JS) and lands ~20-30s away.
  if (typeof window.__pb.fsActive !== 'boolean') window.__pb.fsActive = false;

  // Map a 0..1 tap position onto the VIDEO's box and run the same side-aware
  // double-tap as the in-document path: left third → −10s, right third → +10s,
  // middle → play/pause. Measuring against the video rect (not the screen) is
  // what makes ±10s land correctly when the video is letterboxed / portrait —
  // deciding the thirds from screen width left most taps in the middle zone.
  function applyDoubleTap(v, xRatio, yRatio) {
    var w = window.innerWidth || document.documentElement.clientWidth || 1;
    var h = window.innerHeight || document.documentElement.clientHeight || 1;
    var x = (xRatio || 0) * w, y = (yRatio || 0) * h;
    var r = v.getBoundingClientRect();
    var left = r.left, width = r.width;
    if (!width || width < 50) { left = 0; width = w; }
    var rel = (x - left) / width;
    if (rel < 0.35) { seekBy(v, -SEEK_SEC); return 'seek-'; }
    if (rel > 0.65) { seekBy(v, SEEK_SEC); return 'seek+'; }
    playPauseAtPoint(v, x, y);
    return 'pp';
  }

  // Relay a gesture to child frames. In native fullscreen the WebView only runs
  // evaluateJavascript in the TOP frame, but the actual <video> often lives in
  // a (possibly cross-origin) iframe player the top frame can't reach. Every
  // frame runs this same injected script and listens for the postMessage below,
  // so the frame that owns the video acts on it. Relay only fires when this
  // frame has no video of its own, so the video is never double-handled.
  function broadcastFsGesture(payload) {
    try {
      var frames = document.querySelectorAll('iframe, frame');
      for (var i = 0; i < frames.length; i++) {
        try { frames[i].contentWindow.postMessage({ __pbFs: payload }, '*'); } catch (e) {}
      }
    } catch (e) {}
  }

  // Single entry point for every discrete native-fullscreen gesture. Runs it on
  // this frame's video, or relays to child frames if the video isn't here.
  // Returns a short status string purely for Kotlin-side DebugLog diagnostics.
  function runFsGesture(p) {
    var v = fullscreenVideo() || activeVideo();
    if (!v) { broadcastFsGesture(p); return 'relay'; }
    switch (p.kind) {
      case 'seek': seekBy(v, p.delta); return 'seek';
      case 'doubletap': return applyDoubleTap(v, p.xRatio, p.yRatio);
      case 'tap': {
        // Single tap: show the site's control layer — do NOT toggle play (that's
        // the middle double-tap). The native-fullscreen overlay consumes raw
        // touch so the site never sees the tap; replay it as a synthetic click
        // on the element under the finger so the site's controls still toggle.
        var w = window.innerWidth || document.documentElement.clientWidth || 1;
        var h = window.innerHeight || document.documentElement.clientHeight || 1;
        var tx = (p.xRatio || 0) * w, ty = (p.yRatio || 0) * h;
        var topEl = deepElementFromPoint(tx, ty) || v;
        dispatchSyntheticClick(topEl, tx, ty);
        return 'tap';
      }
      case 'toggle': togglePlay(v); return 'toggle';
      case 'switch': switchVideo(p.dir); return 'switch';
    }
    return 'noop';
  }

  window.addEventListener('message', function (ev) {
    var d = ev && ev.data;
    if (!d || !d.__pbFs) return;
    runFsGesture(d.__pbFs);
  });

  // Android-callable hooks. Kotlin passes tap positions as 0..1 ratios (device-px
  // vs CSS-px independent). All route through runFsGesture so an iframe-hosted
  // player is handled the same as a top-frame <video>.
  window.__pb.seek = function (delta) { return runFsGesture({ kind: 'seek', delta: delta }); };
  window.__pb.togglePlay = function () { return runFsGesture({ kind: 'toggle' }); };
  window.__pb.fsTap = function (xRatio, yRatio) {
    return runFsGesture({ kind: 'tap', xRatio: xRatio, yRatio: yRatio });
  };
  window.__pb.fsDoubleTap = function (xRatio, yRatio) {
    return runFsGesture({ kind: 'doubletap', xRatio: xRatio, yRatio: yRatio });
  };
  window.__pb.switchVideo = function (dir) { return runFsGesture({ kind: 'switch', dir: dir }); };

  var nativeScrub = null;
  window.__pb.scrubStart = function (screenWidth) {
    var v = fullscreenVideo() || activeVideo();
    if (!v) { nativeScrub = null; return; }
    nativeScrub = {
      video: v,
      startTime: v.currentTime || 0,
      duration: v.duration || 0,
      screenWidth: Math.max(1, screenWidth || window.innerWidth || 1)
    };
  };
  window.__pb.scrubBy = function (deltaPx) {
    var s = nativeScrub;
    if (!s) return;
    var dur = s.duration || s.video.duration || 0;
    if (!isFinite(dur) || dur <= 0) return;
    var newT = Math.max(0, Math.min(dur - 0.1, s.startTime + (deltaPx / s.screenWidth) * dur));
    try {
      s.video.currentTime = newT;
      showScrub(newT, dur, newT - s.startTime);
    } catch (e) {}
  };
  window.__pb.scrubEnd = function () {
    nativeScrub = null;
    hideScrub();
  };

  // Vertical-drag overlay for system volume (right side) / brightness (left
  // side). Driven by GestureCapturingFrame during native fullscreen.
  function ensureVbOverlay() {
    var el = document.getElementById('__pb_vb');
    if (el) return el;
    el = document.createElement('div');
    el.id = '__pb_vb';
    el.style.cssText =
      'position:fixed;top:50%;transform:translateY(-50%);' +
      'background:rgba(0,0,0,0.78);color:#fff;font:600 13px/1.2 sans-serif;' +
      'padding:12px 14px;border-radius:10px;z-index:2147483647;' +
      'pointer-events:none;text-align:center;display:none;flex-direction:column;' +
      'align-items:center;gap:8px;transition:opacity .2s;opacity:0;';
    el.innerHTML =
      '<div id="__pb_vb_label" style="font-size:12px;opacity:.85;"></div>' +
      '<div style="width:8px;height:140px;background:rgba(255,255,255,0.22);' +
      'border-radius:4px;overflow:hidden;display:flex;align-items:flex-end;">' +
      '<div id="__pb_vb_fill" style="width:100%;background:#fff;"></div>' +
      '</div>' +
      '<div id="__pb_vb_pct" style="font-size:13px;"></div>';
    document.documentElement.appendChild(el);
    return el;
  }

  window.__pb.showVbOverlay = function (kind, ratio) {
    var el = ensureVbOverlay();
    var r = Math.max(0, Math.min(1, ratio || 0));
    if (kind === 'volume') {
      el.style.right = '24px';
      el.style.left = '';
    } else {
      el.style.left = '24px';
      el.style.right = '';
    }
    var label = document.getElementById('__pb_vb_label');
    var fill = document.getElementById('__pb_vb_fill');
    var pct = document.getElementById('__pb_vb_pct');
    if (label) label.textContent = kind === 'volume' ? '음량' : '밝기';
    if (fill) fill.style.height = (r * 100) + '%';
    if (pct) pct.textContent = Math.round(r * 100) + '%';
    el.style.display = 'flex';
    el.style.opacity = '1';
    clearTimeout(el.__t);
  };

  window.__pb.hideVbOverlay = function () {
    var el = document.getElementById('__pb_vb');
    if (!el) return;
    el.style.opacity = '0';
    clearTimeout(el.__t);
    el.__t = setTimeout(function () {
      if (el.style.opacity === '0') el.style.display = 'none';
    }, 220);
  };

  // ---- Suppress the site's own double-tap-to-fullscreen handlers ----
  //
  // Many sites attach dblclick / two-click handlers to the video element or
  // an overlay div and toggle fullscreen on double-tap. When we handle a
  // double-tap ourselves (for ±10s seek), we don't want the site's handler
  // to also fire and yank the user into fullscreen.
  //
  // Strategy: in capture phase (runs before any site listener) we kill:
  //   (a) every dblclick whose hit point is on a video, unconditionally;
  //   (b) the synthesized click that follows our recognized double-tap.

  var suppressClick = null; // { until, x, y }

  document.addEventListener('click', function (e) {
    // Let synthetic clicks through — including the one playPauseAtPoint() dispatches
    // to the site's own play button to keep its overlay in sync.
    if (e.isTrusted === false) return;
    var s = suppressClick;
    if (!s) return;
    if (Date.now() > s.until) { suppressClick = null; return; }
    if (Math.abs(e.clientX - s.x) < 40 && Math.abs(e.clientY - s.y) < 40) {
      e.preventDefault();
      e.stopPropagation();
      if (e.stopImmediatePropagation) e.stopImmediatePropagation();
    }
  }, { capture: true });

  document.addEventListener('dblclick', function (e) {
    if (!videoAtPoint(e.clientX, e.clientY)) return;
    e.preventDefault();
    e.stopPropagation();
    if (e.stopImmediatePropagation) e.stopImmediatePropagation();
  }, { capture: true });

  // ---- In-document (non-native-fullscreen) gesture handling ----

  var touchState = null;
  var lastTap = { t: 0, x: 0, y: 0, video: null };

  document.addEventListener('touchstart', function (e) {
    // In native fullscreen the Kotlin GestureCapturingFrame drives gestures via
    // window.__pb.* hooks; skip the in-document path to avoid double seeking.
    if (window.__pb && window.__pb.fsActive) { touchState = null; return; }
    if (!e.touches || e.touches.length === 0) return;
    var t0 = e.touches[0];
    var v = videoAtPoint(t0.clientX, t0.clientY);
    if (!v) { touchState = null; return; }
    touchState = {
      video: v,
      // Own the tap only if it's on the video surface AND not on a small control
      // overlaid on it (close button, control-bar button…). Those pass through
      // like off-video taps so the user can actually press them.
      onVideo: isPointOnVideo(t0.clientX, t0.clientY) &&
        !controlAtPoint(t0.clientX, t0.clientY, v),
      startX: t0.clientX,
      startY: t0.clientY,
      startT: Date.now(),
      startTime: v.currentTime || 0,
      pointers: e.touches.length,
      scrubbing: false,
      moved: false
    };
  }, { passive: true, capture: true });

  document.addEventListener('touchmove', function (e) {
    if (!touchState || !e.touches || e.touches.length === 0) return;
    if (e.touches.length > touchState.pointers) touchState.pointers = e.touches.length;
    var t0 = e.touches[0];
    var dx = t0.clientX - touchState.startX;
    var dy = t0.clientY - touchState.startY;
    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) touchState.moved = true;
    if (touchState.pointers >= 2) return;

    if (!touchState.scrubbing &&
        Math.abs(dx) > SCRUB_THRESHOLD_PX && Math.abs(dx) > Math.abs(dy)) {
      touchState.scrubbing = true;
    }
    if (touchState.scrubbing) {
      var dur = touchState.video.duration || 0;
      if (!isFinite(dur) || dur <= 0) return;
      var w = window.innerWidth || document.documentElement.clientWidth || 1;
      var newT = Math.max(0, Math.min(dur - 0.1, touchState.startTime + (dx / w) * dur));
      try {
        touchState.video.currentTime = newT;
        showScrub(newT, dur, newT - touchState.startTime);
      } catch (err) {}
    }
  }, { passive: true, capture: true });

  document.addEventListener('touchend', function (e) {
    if (!touchState) return;
    var s = touchState;
    touchState = null;
    var dt = Date.now() - s.startT;
    var et = (e.changedTouches && e.changedTouches[0]) || null;
    var dx = et ? et.clientX - s.startX : 0;
    var dy = et ? et.clientY - s.startY : 0;
    var ex = et ? et.clientX : s.startX;
    var ey = et ? et.clientY : s.startY;

    // Kill the synthesized click that can trail a gesture so it doesn't reach
    // the site / native <video> and toggle play or pop a control overlay that
    // then lingers. Only for gestures that were ours (on the video surface).
    function suppressTrailingClick() {
      suppressClick = { until: Date.now() + 700, x: ex, y: ey };
      e.stopPropagation();
      if (e.stopImmediatePropagation) e.stopImmediatePropagation();
    }

    // 2-finger horizontal swipe → switch video.
    if (s.pointers >= 2 && s.moved && dt <= SWIPE_TIME_LIMIT_MS &&
        Math.abs(dx) >= SWIPE_THRESHOLD_PX && Math.abs(dx) > Math.abs(dy)) {
      hideScrub();
      switchVideo(dx > 0 ? -1 : 1);
      if (s.onVideo) suppressTrailingClick();
      return;
    }

    // Scrubbing already updated currentTime live; just hide the overlay.
    if (s.scrubbing) {
      hideScrub();
      if (s.onVideo) suppressTrailingClick();
      return;
    }

    if (!et) return;
    // Only hijack taps that actually landed on a video. videoAtPoint() falls
    // back to the active video for off-video points (kept for scrub/swipe), but
    // taps elsewhere must pass through so page links/buttons still work.
    if (!s.onVideo) return;
    var x = et.clientX, y = et.clientY;
    var now = Date.now();
    // A drag that never became a recognized scrub/swipe (a small or vertical
    // move) is NOT a tap — never toggle play for it. Kill the trailing click
    // and bail so neither we nor the site act on it.
    if (s.moved) { suppressTrailingClick(); return; }
    if (now - lastTap.t < DOUBLE_TAP_MS && lastTap.video === s.video &&
        Math.abs(x - lastTap.x) < DOUBLE_TAP_MAX_MOVE &&
        Math.abs(y - lastTap.y) < DOUBLE_TAP_MAX_MOVE) {
      // Double-tap: this one is ours. Side-aware — left third → -10s, right
      // third → +10s, middle → play/pause. Kill the trailing click so the site
      // doesn't also act on it (e.g. toggle fullscreen).
      suppressClick = { until: now + 500, x: x, y: y };
      e.stopPropagation();
      if (e.stopImmediatePropagation) e.stopImmediatePropagation();
      var r = s.video.getBoundingClientRect();
      var left = r.left, width = r.width;
      if (!width || width < 50) { left = 0; width = window.innerWidth || 1; }
      var rel = (x - left) / width;
      if (rel < 0.35) seekBy(s.video, -SEEK_SEC);
      else if (rel > 0.65) seekBy(s.video, SEEK_SEC);
      else playPauseAtPoint(s.video, x, y);
      lastTap.t = 0;
    } else {
      // Single tap: let the site handle it so its own control layer shows/hides.
      // We deliberately do NOT toggle play (that's the middle double-tap) and do
      // NOT suppress the click — the site needs the real tap to reveal its
      // controls. Just record it so a following tap can be matched as a double.
      lastTap = { t: now, x: x, y: y, video: s.video };
    }
  }, { passive: true, capture: true });

  document.addEventListener('touchcancel', function () {
    if (touchState && touchState.scrubbing) hideScrub();
    touchState = null;
  }, { passive: true, capture: true });

  // ---- 외부 플레이어 연결 (long-press a video → open in external player) ----
  //
  // Long-pressing a <video> reads THAT element's source and asks Android to open
  // it in the built-in Media3 player, so a page with several videos can send
  // exactly the one under the finger (the single on-screen button couldn't say
  // which video it meant). The DOM src is used verbatim when it's a direct media
  // URL; blob:/MSE videos have no usable URL, so the native side falls back to
  // the sniffed network stream. Cross-origin iframe players can't see the
  // window.PBPlayer bridge, so the frame that owns the video relays the source
  // up to the top frame via postMessage (each level re-relays until it reaches
  // the frame that has the bridge) — same pattern as the fullscreen relay above.
  (function initExternalPlayer() {
    var LONG_PRESS_MS = 550;
    var MOVE_CANCEL_PX = 12;
    var lp = null; // { timer, x, y, video }

    function videoSrc(v) {
      if (!v) return '';
      try {
        if (v.currentSrc) return v.currentSrc;
        if (v.src) return v.src;
        var s = v.querySelector && v.querySelector('source[src]');
        if (s && s.src) return s.src;
      } catch (e) {}
      return '';
    }

    // Deliver the source to the Android bridge, relaying up through parent frames
    // when this frame has no bridge of its own (cross-origin iframe player).
    function requestExternal(src) {
      try {
        if (window.PBPlayer && window.PBPlayer.openVideo) {
          window.PBPlayer.openVideo(src || '');
          return;
        }
      } catch (e) {}
      try {
        if (window.parent && window.parent !== window) {
          window.parent.postMessage({ __pbOpenVideo: src || '' }, '*');
        }
      } catch (e) {}
    }

    function clearLp() {
      if (lp && lp.timer) clearTimeout(lp.timer);
      lp = null;
    }

    // NOTE: these listen on `window`, not `document`. The main gesture handler's
    // touchend listener (on `document`, registered earlier) calls
    // stopImmediatePropagation() on a recognized double-tap to kill the trailing
    // click — which would also block a later `document` listener like clearLp,
    // leaving the long-press timer from the 2nd tap alive so it fired the menu
    // ~550ms after a double-tap (bug). Capture-phase `window` listeners run
    // BEFORE any `document` listener, so our cancel always runs first and the
    // double-tap correctly cancels the pending long-press.
    window.addEventListener('touchstart', function (e) {
      if (!e.touches || e.touches.length !== 1) { clearLp(); return; }
      var t0 = e.touches[0];
      // Strict on-video test (no active-video fallback) so a long-press on page
      // chrome / links never hijacks into the player.
      if (!isPointOnVideo(t0.clientX, t0.clientY)) { clearLp(); return; }
      var v = videoAtPoint(t0.clientX, t0.clientY);
      if (!v) { clearLp(); return; }
      clearLp();
      lp = { x: t0.clientX, y: t0.clientY, video: v, timer: null };
      lp.timer = setTimeout(function () {
        var cur = lp;
        lp = null;
        // The native side now shows a menu (or a "no stream" toast), so don't
        // pre-announce "opening…" here — the long-press just requests the menu.
        if (cur) { requestExternal(videoSrc(cur.video)); }
      }, LONG_PRESS_MS);
    }, { passive: true, capture: true });

    window.addEventListener('touchmove', function (e) {
      if (!lp || !e.touches || e.touches.length === 0) return;
      var t0 = e.touches[0];
      if (Math.abs(t0.clientX - lp.x) > MOVE_CANCEL_PX ||
          Math.abs(t0.clientY - lp.y) > MOVE_CANCEL_PX) clearLp();
    }, { passive: true, capture: true });

    window.addEventListener('touchend', clearLp, { passive: true, capture: true });
    window.addEventListener('touchcancel', clearLp, { passive: true, capture: true });

    // A descendant frame long-pressed its video and relayed the source up.
    window.addEventListener('message', function (ev) {
      var d = ev && ev.data;
      if (!d || typeof d.__pbOpenVideo === 'undefined') return;
      requestExternal(d.__pbOpenVideo);
    });
  })();

  // ---- 이어보기 (resume playback) ----
  //
  // Records the active video's position via the Android PBResume bridge (keyed
  // by page URL on the native side) and, when the page is revisited, seeks back
  // to where the user left off. No-ops outside the app WebView (no bridge) or
  // when the user disabled it (bridge.load() returns -1).
  (function initResume() {
    var bridge = window.PBResume;
    if (!bridge) return;

    var MIN_RESUME_SEC = 10;   // ignore trivially-early positions
    var END_GUARD_SEC = 20;    // don't resume right at the end
    var MIN_DURATION_SEC = 90; // only real, long-ish media (skips short ad clips)

    function resumable(v) {
      return v && isFinite(v.duration) && v.duration > MIN_DURATION_SEC;
    }

    function reportSave(v) {
      try {
        if (!resumable(v)) return;
        bridge.save(v.currentTime || 0, v.duration || 0, document.title || '');
      } catch (e) {}
    }

    function tryResume(v) {
      try {
        if (!v || v.__pbResumed) return;
        if (!resumable(v)) return;
        v.__pbResumed = true; // attempt once per element
        var pos = bridge.load();
        if (pos > MIN_RESUME_SEC && pos < v.duration - END_GUARD_SEC) {
          v.currentTime = pos;
          showToast('이어보기 ' + formatTime(pos));
        }
      } catch (e) {}
    }

    function attach(v) {
      if (!v || v.__pbResumeAttached) return;
      v.__pbResumeAttached = true;
      v.addEventListener('loadedmetadata', function () { tryResume(v); });
      v.addEventListener('play', function () { tryResume(v); });
      v.addEventListener('pause', function () { reportSave(v); });
      v.addEventListener('seeked', function () { reportSave(v); });
      v.addEventListener('ended', function () {
        try { bridge.clear(); } catch (e) {}
      });
      if (v.readyState >= 1) tryResume(v); // metadata already present
    }

    // Periodically attach to any new <video> and checkpoint the active one.
    setInterval(function () {
      try {
        var vids = allVideosRaw();
        for (var i = 0; i < vids.length; i++) attach(vids[i]);
        var a = activeVideo();
        if (a && !a.paused) reportSave(a);
      } catch (e) {}
    }, 5000);

    // Flush the latest position when the page is backgrounded / navigated away.
    document.addEventListener('visibilitychange', function () {
      if (document.hidden) { var a = activeVideo(); if (a) reportSave(a); }
    });
    window.addEventListener('pagehide', function () {
      var a = activeVideo(); if (a) reportSave(a);
    });

    // Attach to whatever is already on the page right now.
    try {
      var existing = allVideosRaw();
      for (var k = 0; k < existing.length; k++) attach(existing[k]);
    } catch (e) {}
  })();
})();
