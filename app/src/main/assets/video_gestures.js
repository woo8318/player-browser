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
    // A lone video has nothing to switch to — say so (the native fullscreen
    // HUD reports the result) instead of pausing and replaying the same one.
    if (vids.length < 2) return false;
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
    return true;
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
  // Returns a short status string. Kotlin logs it, and in native fullscreen the
  // HUD decides "did it" vs "nothing to act on" from it (v1.3.90) — so keep the
  // values honest: 'seek' / 'switch' only when a video in this frame was acted on.
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
      case 'switch': return switchVideo(p.dir) ? 'switch' : 'switch-none';
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
        if (cur) {
          try { if (window.__pbInline) window.__pbInline.markPressed(cur.video); } catch (e) {}
          // Ancestors may still hold an older press of their own; PBPlayer is
          // visible here too, so the __pbOpenVideo relay (which clears them)
          // does not run — tell them explicitly (v1.3.95).
          try {
            if (window.parent && window.parent !== window) {
              window.parent.postMessage({ __pbPressedAway: 1 }, '*');
            }
          } catch (e) {}
          requestExternal(videoSrc(cur.video));
        }
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
      // The pressed video lives in a child frame: our own candidate
      // (if any) is stale now, so the 'pressed' command broadcasts.
      try { if (window.__pbInline) window.__pbInline.clearPressed(); } catch (e) {}
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

  // ---- 풀스크린 영상 최대 크기 맞춤 (v1.3.94) ----
  //
  // In native fullscreen the fullscreen element fills the screen, but the
  // <video> inside it is sized by the SITE's CSS — a fixed-size box, a
  // max-height, or object-fit:cover can leave it small or cropped. Here we
  // measure the picture actually on screen against the largest size that fits
  // the video's aspect ratio (contain — never crop) and only step in when it
  // falls short. Sites that already get it right are left untouched.
  //
  // Styles come from a stylesheet keyed on an attribute, not inline styles:
  // author !important beats the site's inline style (which a player's resize
  // handler rewrites constantly), and exit is just removing the attribute.
  // Each frame running this script handles its own document (a cross-origin
  // iframe player gets its own fullscreenchange); same-origin frames are
  // reached from here through reachableDocuments().
  (function initFullscreenFit() {
    var ATTR = 'data-pbfit';
    var STYLE_ID = '__pb_fsfit_css';
    var GOOD = 0.97; // fraction of the max contain size that counts as full size
    // Two :not(#id) push specificity past typical `#player video {…!important}`.
    var SEL = 'video[' + ATTR + ']:not(#__pbfit_a):not(#__pbfit_b)';
    var CSS =
      SEL + '{object-fit:contain!important;width:100%!important;height:100%!important;' +
      'max-width:none!important;max-height:none!important;min-width:0!important;min-height:0!important}' +
      SEL.replace('[' + ATTR + ']', '[' + ATTR + '="2"]') +
      '{position:fixed!important;left:0!important;top:0!important;right:auto!important;' +
      'bottom:auto!important;margin:0!important;transform:none!important;' +
      'translate:none!important;scale:none!important}';

    var fitted = null;
    var timers = [];

    function fullscreenDocs() {
      var out = [];
      var docs = reachableDocuments();
      for (var i = 0; i < docs.length; i++) {
        var fe = docs[i].fullscreenElement || docs[i].webkitFullscreenElement;
        if (fe) out.push(fe);
      }
      return out;
    }

    // The main video of the fullscreen element(s): playing beats paused, then
    // bigger intrinsic size — so a seek-bar preview <video> never wins.
    function pickVideo(fsEls) {
      var best = null, bestKey = -1;
      for (var i = 0; i < fsEls.length; i++) {
        var fe = fsEls[i];
        var list;
        if (fe.tagName === 'VIDEO') list = [fe];
        else {
          try { list = fe.querySelectorAll('video'); } catch (e) { continue; }
        }
        for (var j = 0; j < list.length; j++) {
          var v = list[j];
          var area = (v.videoWidth || 0) * (v.videoHeight || 0);
          var key = (!v.paused && v.readyState >= 2 ? 1e12 : 0) + area;
          if (key > bestKey) { best = v; bestKey = key; }
        }
      }
      return best;
    }

    // Displayed picture size ÷ max uncropped size. -1 = cropped / stretched /
    // off-screen, null = can't tell yet (no metadata, hidden, no viewport).
    function fitScore(v) {
      var win = (v.ownerDocument && v.ownerDocument.defaultView) || window;
      var W = win.innerWidth, H = win.innerHeight;
      var vw = v.videoWidth, vh = v.videoHeight;
      if (!W || !H || !vw || !vh) return null;
      var r = v.getBoundingClientRect();
      if (r.width < 2 || r.height < 2) return null;
      var fit = 'contain';
      try { fit = win.getComputedStyle(v).objectFit || 'contain'; } catch (e) {}
      var sx = r.width / vw, sy = r.height / vh;
      var ps;
      if (fit === 'cover') ps = Math.max(sx, sy);
      else if (fit === 'none') ps = 1;
      else if (fit === 'scale-down') ps = Math.min(1, sx, sy);
      else ps = Math.min(sx, sy);
      if (fit === 'fill' && Math.abs(sx - sy) > 0.02 * Math.max(sx, sy)) return -1;
      var pw = ps * vw, ph = ps * vh;
      if (pw > r.width + 2 || ph > r.height + 2) return -1;
      var px = r.left + (r.width - pw) / 2, py = r.top + (r.height - ph) / 2;
      if (px < -2 || py < -2 || px + pw > W + 2 || py + ph > H + 2) return -1;
      return ps / Math.min(W / vw, H / vh);
    }

    function ensureStyle(doc) {
      try {
        if (doc.getElementById(STYLE_ID)) return;
        var s = doc.createElement('style');
        s.id = STYLE_ID;
        s.textContent = CSS;
        (doc.head || doc.documentElement).appendChild(s);
      } catch (e) {}
    }

    function unfit() {
      if (fitted) { try { fitted.removeAttribute(ATTR); } catch (e) {} }
      fitted = null;
    }

    function hook(v) {
      if (v.__pbFitHooked) return;
      v.__pbFitHooked = true;
      var again = function () { schedule(0); };
      v.addEventListener('loadedmetadata', again);
      v.addEventListener('resize', again); // intrinsic size changed
    }

    // Measure with the site's own layout first; only if that isn't full size
    // try level 1 (fill the parent, contain) then level 2 (pin to the
    // viewport). getBoundingClientRect forces layout synchronously, so every
    // trial is measured inside this one task — nothing paints in between.
    function refit() {
      var fsEls = fullscreenDocs();
      var v = fsEls.length ? pickVideo(fsEls) : null;
      if (fitted && fitted !== v) unfit();
      if (!v) return;
      hook(v);
      ensureStyle(v.ownerDocument);
      v.removeAttribute(ATTR);
      fitted = null;
      var s0 = fitScore(v);
      if (s0 === null || s0 >= GOOD) return;
      var best = 0, bestScore = s0;
      for (var lvl = 1; lvl <= 2; lvl++) {
        v.setAttribute(ATTR, String(lvl));
        var s = fitScore(v);
        if (s !== null && s > bestScore + 0.01) { best = lvl; bestScore = s; }
        if (s !== null && s >= GOOD) break;
      }
      if (best === 0) { v.removeAttribute(ATTR); return; }
      v.setAttribute(ATTR, String(best));
      fitted = v;
    }

    function schedule(delay) {
      timers.push(setTimeout(function () {
        try { refit(); } catch (e) {}
      }, delay));
    }

    function clearTimers() {
      for (var i = 0; i < timers.length; i++) clearTimeout(timers[i]);
      timers = [];
    }

    function onFullscreenChange() {
      clearTimers();
      if (!fullscreenDocs().length) { unfit(); return; }
      // The viewport only grows to the fullscreen view (and rotates) a little
      // after the event, so look again as it settles.
      try { refit(); } catch (e) {}
      schedule(250);
      schedule(800);
      schedule(2000);
    }

    document.addEventListener('fullscreenchange', onFullscreenChange);
    document.addEventListener('webkitfullscreenchange', onFullscreenChange);

    var resizeTimer = null;
    window.addEventListener('resize', function () {
      if (!fitted && !fullscreenDocs().length) return;
      clearTimeout(resizeTimer);
      resizeTimer = setTimeout(function () {
        try { refit(); } catch (e) {}
      }, 120);
    });

    // An ad finishing and the real video starting swaps which <video> plays.
    document.addEventListener('playing', function () {
      if (fullscreenDocs().length) schedule(0);
    }, true);
  })();

  // ---- 우리 플레이어로 자동 교체 (in-place native player, v1.3.89) ----
  //
  // Observes <video> playback and reports "started playing" + the video's box
  // to the Android PBInline bridge so the app can lay its own Media3 player
  // exactly over the site's player. JS never decides — Kotlin checks the
  // setting and whether a stream was sniffed, then answers with `take` (we
  // pause+mute the site video and stream its box every frame) or, later,
  // `release` (we hand it back seeked to where the native player stopped).
  //
  // Coordinates: only the TOP frame talks to the bridge, in device px relative
  // to the WebView (layout css px → visualViewport → devicePixelRatio). Videos
  // in same-origin iframes are walked directly (frameElement chain); a
  // cross-origin iframe player relays `{__pbInline}` up to its parent, which
  // adds the iframe's offset, caches the child payload and re-emits on its own
  // rAF loop (so the parent scrolling also moves the overlay). Commands go
  // down as `{__pbInlineCmd}` broadcasts; ids are unique per frame.
  (function initInlinePlayer() {
    var isTop = true;
    try { isTop = (window.parent === window); } catch (e) { isTop = true; }
    // No bridge in the top frame (challenge page — PBInline is exposed even
    // with the setting off, for the long-press manual pick): nothing to
    // report to, so don't hook play events or run the rAF loop at all.
    if (isTop && !window.PBInline) return;
    var idPrefix = 'pb' + Math.random().toString(36).slice(2, 8) + '-';
    var idCounter = 0;
    var REPORT_MIN_MS = 1500;
    var local = {};    // id -> { v, prevMuted, last:{l,t,w,h} }
    var relayed = Object.create(null);  // id -> { frame, p:{l,t,w,h}, last }
    var lastPressed = null;  // { v: video, t: Date.now() } from the long-press menu
    var PRESSED_TTL_MS = 15000;
    var ID_RE = /^[A-Za-z0-9_-]{1,64}$/;
    var rafId = 0;

    function idOf(v) {
      if (!v.__pbInlineId) v.__pbInlineId = idPrefix + (++idCounter);
      return v.__pbInlineId;
    }

    function videoSrc(v) {
      try {
        if (v.currentSrc) return v.currentSrc;
        if (v.src) return v.src;
        var s = v.querySelector && v.querySelector('source[src]');
        if (s && s.src) return s.src;
      } catch (e) {}
      return '';
    }

    // Box of `el` in THIS window's layout css px, folding in every same-origin
    // ancestor iframe between el's document and this window.
    function rectHere(el) {
      var r = el.getBoundingClientRect();
      var l = r.left, t = r.top, w = r.width, h = r.height;
      try {
        var win = el.ownerDocument && el.ownerDocument.defaultView;
        var guard = 0;
        while (win && win !== window && guard++ < 16) {
          var fe = win.frameElement;
          if (!fe) break;
          var fr = fe.getBoundingClientRect();
          l += fr.left + (fe.clientLeft || 0);
          t += fr.top + (fe.clientTop || 0);
          win = win.parent;
        }
      } catch (e) {}
      return { l: l, t: t, w: w, h: h };
    }

    // Top frame only: layout css px → device px relative to the WebView.
    function toDevice(p) {
      var vv = window.visualViewport;
      var scale = (vv && vv.scale) || 1;
      var ox = (vv && vv.offsetLeft) || 0;
      var oy = (vv && vv.offsetTop) || 0;
      var dpr = window.devicePixelRatio || 1;
      return {
        l: Math.round((p.l - ox) * scale * dpr),
        t: Math.round((p.t - oy) * scale * dpr),
        w: Math.round(p.w * scale * dpr),
        h: Math.round(p.h * scale * dpr)
      };
    }

    function roundRect(p) {
      return { l: Math.round(p.l), t: Math.round(p.t), w: Math.round(p.w), h: Math.round(p.h) };
    }

    function sameRect(a, b) {
      return !!a && !!b && a.l === b.l && a.t === b.t && a.w === b.w && a.h === b.h;
    }

    // Deliver a report: to the bridge (top frame) or up to the parent frame.
    // true = handed off (the parent may still drop it — that shows up as
    // the app's "no answer" timeout instead).
    function emit(msg) {
      if (isTop) {
        var b = window.PBInline;
        if (!b) return false;
        try {
          if (msg.type === 'play') {
            var d = toDevice(msg);
            b.onPlay(msg.id, msg.src || '', +msg.pos || 0, d.l, d.t, d.w, d.h, !!msg.manual);
          } else if (msg.type === 'rect') {
            var d2 = toDevice(msg);
            b.onRect(msg.id, d2.l, d2.t, d2.w, d2.h);
          } else if (msg.type === 'gone') {
            b.onGone(msg.id);
          }
        } catch (e) { return false; }
        return true;
      }
      try { window.parent.postMessage({ __pbInline: msg }, '*'); } catch (e) { return false; }
      return true;
    }

    // Returns what happened so the manual path (long-press menu) can tell the
    // app why nothing appeared (v1.3.95): 'sent' | 'tiny' | 'throttled' | 'emitfail'.
    function report(v, manual) {
      var now = Date.now();
      if (!manual && v.__pbInlineLastReport && now - v.__pbInlineLastReport < REPORT_MIN_MS) return 'throttled';
      var r = rectHere(v);
      // A momentarily tiny box (mid-layout) must not burn the throttle slot.
      // A manual pick still goes up: Kotlin checks the size and says so.
      if (!manual && (r.w < 40 || r.h < 40)) return 'tiny';
      v.__pbInlineLastReport = now;
      var id = idOf(v);
      var pos = 0;
      try { pos = v.currentTime || 0; } catch (e) {}
      return emit({ type: 'play', id: id, src: videoSrc(v), pos: pos, l: r.l, t: r.t, w: r.w, h: r.h, manual: !!manual })
        ? 'sent' : 'emitfail';
    }

    function ensureLoop() {
      if (rafId) return;
      rafId = requestAnimationFrame(tick);
    }

    function tick() {
      rafId = 0;
      var any = false;
      var id;
      for (id in local) {
        var s = local[id];
        if (!s) continue;
        any = true;
        var v = s.v;
        if (!v.isConnected) {
          emit({ type: 'gone', id: id });
          delete local[id];
          continue;
        }
        var r = rectHere(v);
        // Pinch-zoom / visual viewport moves change device px without changing
        // layout px — the top frame compares after conversion.
        var key = isTop ? toDevice(r) : roundRect(r);
        if (!sameRect(key, s.last)) {
          s.last = key;
          emit({ type: 'rect', id: id, l: r.l, t: r.t, w: r.w, h: r.h });
        }
      }
      for (id in relayed) {
        var rs = relayed[id];
        if (!rs) continue;
        any = true;
        if (!rs.frame.isConnected) {
          emit({ type: 'gone', id: id });
          delete relayed[id];
          continue;
        }
        var combined = combine(rs.frame, rs.p);
        var key2 = isTop ? toDevice(combined) : roundRect(combined);
        if (!sameRect(key2, rs.last)) {
          rs.last = key2;
          emit({ type: 'rect', id: id, l: combined.l, t: combined.t, w: combined.w, h: combined.h });
        }
      }
      if (any) rafId = requestAnimationFrame(tick);
    }

    // Child payload (child layout px) + the owning iframe's box here.
    function combine(frame, p) {
      var fr = rectHere(frame);
      var l = fr.l + (frame.clientLeft || 0) + p.l;
      var t = fr.t + (frame.clientTop || 0) + p.t;
      // Intersect with the iframe's box: a video scrolled partly out of its
      // frame is clipped by the frame, so the overlay must be too.
      var r = Math.min(l + p.w, fr.l + fr.w);
      var b = Math.min(t + p.h, fr.t + fr.h);
      l = Math.max(l, fr.l);
      t = Math.max(t, fr.t);
      return { l: l, t: t, w: Math.max(0, r - l), h: Math.max(0, b - t) };
    }

    function findLocal(id) {
      if (local[id]) return local[id].v;
      var vids = allVideosRaw();
      for (var i = 0; i < vids.length; i++) if (vids[i].__pbInlineId === id) return vids[i];
      return null;
    }

    function take(v) {
      var id = idOf(v);
      if (!local[id]) local[id] = { v: v, prevMuted: !!v.muted, last: null };
      v.__pbInlineTaken = true;
      try { v.muted = true; } catch (e) {}
      try { v.pause(); } catch (e) {}
      ensureLoop();
    }

    function release(v, pos) {
      var id = idOf(v);
      var s = local[id];
      delete local[id];
      v.__pbInlineTaken = false;
      try { v.muted = s ? s.prevMuted : false; } catch (e) {}
      if (typeof pos === 'number' && isFinite(pos) && pos >= 0) {
        try {
          var d = v.duration;
          if (!isFinite(d) || pos < d) v.currentTime = pos;
        } catch (e) {}
      }
      // Stay paused: the user closed our player, they did not ask the site's to resume.
    }

    // Returns how many frames the command was posted to.
    function broadcastCmd(c) {
      var sent = 0;
      try {
        var docs = reachableDocuments();
        for (var di = 0; di < docs.length; di++) {
          var frames;
          try { frames = docs[di].querySelectorAll('iframe, frame'); } catch (e) { continue; }
          for (var i = 0; i < frames.length; i++) {
            try { frames[i].contentWindow.postMessage({ __pbInlineCmd: c }, '*'); sent++; } catch (e) {}
          }
        }
      } catch (e) {}
      return sent;
    }

    // Returns a status string; the app reads it for 'pressed' only (v1.3.95).
    function cmd(c) {
      if (!c || !c.kind) return 'bad';
      if (c.kind === 'pressed') {
        var lp = lastPressed;
        lastPressed = null;
        // A descendant's long-press clears ours (__pbPressedAway), so a press
        // held here is the latest one: report its own failure instead of
        // broadcasting to ad/analytics iframes and timing out as [broadcast].
        if (lp && lp.v) {
          if (!lp.v.isConnected) return 'detached';
          if (Date.now() - lp.t >= PRESSED_TTL_MS) return 'stale';
          return report(lp.v, true);
        }
        // The pressed video lives in a child frame (or nowhere): ask them all.
        return broadcastCmd(c) > 0 ? 'broadcast' : 'none';
      }
      var v = c.id ? findLocal(c.id) : null;
      if (v) {
        if (c.kind === 'take') take(v);
        else if (c.kind === 'release') release(v, c.pos);
        return;
      }
      if (c.kind === 'release' && relayed[c.id]) delete relayed[c.id];
      broadcastCmd(c);
    }

    // The <iframe> that owns a child window (may sit inside a same-origin
    // nested iframe of ours).
    function frameOf(source) {
      var docs = reachableDocuments();
      for (var di = 0; di < docs.length; di++) {
        var frames;
        try { frames = docs[di].querySelectorAll('iframe, frame'); } catch (e) { continue; }
        for (var i = 0; i < frames.length; i++) {
          try { if (frames[i].contentWindow === source) return frames[i]; } catch (e) {}
        }
      }
      return null;
    }

    window.addEventListener('message', function (ev) {
      var d = ev && ev.data;
      if (!d) return;
      if (d.__pbInlineCmd) {
        // Commands come from the app (evaluateJavascript in the top frame)
        // and are relayed parent → child only; anything else is spoofed.
        if (isTop) return;
        var fromParent = false;
        try { fromParent = (ev.source === window.parent); } catch (e) {}
        if (!fromParent) return;
        cmd(d.__pbInlineCmd);
        return;
      }
      if (d.__pbPressedAway) {
        // A frame below us long-pressed its own video: our candidate is stale.
        // Only our own child frames may say so; pass it on up.
        if (!frameOf(ev.source)) return;
        lastPressed = null;
        if (!isTop) {
          try { window.parent.postMessage({ __pbPressedAway: 1 }, '*'); } catch (e) {}
        }
        return;
      }
      var m = d.__pbInline;
      // Ids come from another (possibly hostile) frame: same charset the
      // Kotlin bridge enforces, so "__proto__" & co. never reach the map.
      if (!m || typeof m.id !== 'string' || !ID_RE.test(m.id)) return;
      if (m.type === 'gone') {
        // Only the frame that reported the video may retract it.
        var owner = relayed[m.id];
        if (owner) {
          var ownerWin = null;
          try { ownerWin = owner.frame.contentWindow; } catch (e) {}
          if (ownerWin !== ev.source) return;
        } else if (!frameOf(ev.source)) {
          return;
        }
        delete relayed[m.id];
        emit({ type: 'gone', id: m.id });
        return;
      }
      var frame = (relayed[m.id] && relayed[m.id].frame) || frameOf(ev.source);
      if (!frame || !frame.isConnected) return;
      var p = { l: +m.l || 0, t: +m.t || 0, w: +m.w || 0, h: +m.h || 0 };
      var combined = combine(frame, p);
      if (m.type === 'play') {
        emit({ type: 'play', id: m.id, src: m.src || '', pos: +m.pos || 0, manual: !!m.manual,
               l: combined.l, t: combined.t, w: combined.w, h: combined.h });
        return;
      }
      if (m.type === 'rect') {
        var rs = relayed[m.id];
        if (!rs) { rs = relayed[m.id] = { frame: frame, p: p, last: null }; }
        else { rs.p = p; }
        // Emit right away with the fresh child box; the loop keeps it aligned
        // afterwards when only this frame moves.
        rs.last = isTop ? toDevice(combined) : roundRect(combined);
        emit({ type: 'rect', id: m.id, l: combined.l, t: combined.t, w: combined.w, h: combined.h });
        ensureLoop();
      }
    });

    // Play detection. Media events don't bubble but capture at the document
    // still sees them; same-origin iframe documents get hooked too.
    function onPlaying(e) {
      var v = e && e.target;
      if (!v || !v.tagName || v.tagName.toUpperCase() !== 'VIDEO') return;
      if (v.__pbInlineTaken) {
        // The site (autoplay retry / its own controls) tried to resume the
        // video we replaced — keep it silent and paused.
        try { v.muted = true; v.pause(); } catch (err) {}
        return;
      }
      report(v, false);
    }

    function hookDoc(d) {
      if (!d || d.__pbInlineHooked) return;
      d.__pbInlineHooked = true;
      try {
        d.addEventListener('playing', onPlaying, true);
        d.addEventListener('play', onPlaying, true);
      } catch (e) {}
    }

    function hookAll() {
      var docs = reachableDocuments();
      for (var i = 0; i < docs.length; i++) hookDoc(docs[i]);
    }
    hookAll();
    setInterval(hookAll, 2000);

    // Videos already rolling when the script lands (autoplay before onPageFinished).
    try {
      var existing = allVideosRaw();
      for (var k = 0; k < existing.length; k++) {
        var ev0 = existing[k];
        if (!ev0.paused && !ev0.ended && ev0.readyState >= 2) report(ev0, false);
      }
    } catch (e) {}

    window.__pbInline = {
      cmd: cmd,
      markPressed: function (v) { lastPressed = v ? { v: v, t: Date.now() } : null; },
      clearPressed: function () { lastPressed = null; }
    };
  })();
  // ---- Body sniff (v1.3.91): recognise media by response *content* ----
  // The Kotlin sniffer sees only URLs (and Content-Type on our OkHttp path).
  // A playlist served from `/v/e/<id>/c.html` or `/api/stream?id=` looks like
  // nothing from the outside, but its first bytes say what it is: `#EXTM3U`,
  // `ftyp`, EBML. So wrap fetch/XHR, peek at the head of each GET response,
  // and hand recognised URLs to PBPlayer.onStreamBody. Frames without the
  // bridge relay to their parent via postMessage like the other bridges.
  // Gate: every frame that can see PBPlayer asks bodySniffEnabled() (setting,
  // challenge page, quarantined site - all decided in Kotlin); a frame that
  // can't see it gets window.__pbBodySniffOff from IframeScriptInjector when
  // the setting is off. This script itself only runs from the !onChallenge
  // block, and IframeScriptInjector skips challenge/quarantined documents.
  (function initBodySniff() {
    if (window.__pbSniff || window.__pbBodySniffOff) return;
    var isTop = true;
    try { isTop = (window.parent === window); } catch (e) { isTop = true; }
    var br = null;
    try { br = window.PBPlayer || null; } catch (e) { br = null; }
    if (br && typeof br.bodySniffEnabled === 'function') {
      try { if (!br.bodySniffEnabled()) return; } catch (e) { return; }
    } else if (isTop) {
      return;   // top frame without the bridge: challenge page or no app
    }
    if (typeof WeakMap !== 'function' || typeof Proxy !== 'function') return;
    window.__pbSniff = true;

    var HLS = 'application/vnd.apple.mpegurl';
    var MP4 = 'video/mp4';
    var WEBM = 'video/webm';
    var HEAD = 512;            // bytes of body we look at
    var MAX_REPORTED = 16;     // per document (Kotlin caps per page too)
    var MAX_URL = 4096;
    var MIN_FILE = 256 * 1024; // smaller mp4/webm = init segment, preview, sound
    var seen = Object.create(null);
    var seenCount = 0;

    function absUrl(u) {
      try {
        var a = new URL(String(u), location.href);
        if (a.protocol !== 'http:' && a.protocol !== 'https:') return null;
        return a.href.length > MAX_URL ? null : a.href;
      } catch (e) { return null; }
    }

    // Paths the Kotlin side already recognises (.m3u8/.mp4/.webm), segments,
    // subtitles and static assets: no point cloning their bodies.
    var SKIP_EXT = /\.(m3u8|mp4|webm|ts|m4s|m4a|m4v|aac|mp3|vtt|jpe?g|png|gif|webp|avif|svg|ico|css|m?js|json|woff2?|ttf|otf)$/i;
    function skipUrl(url) {
      return SKIP_EXT.test(String(url).split(/[?#]/)[0]);
    }

    function deliver(url, mime) {
      if (!url || seen[url] || seenCount >= MAX_REPORTED) return;
      seen[url] = true;
      seenCount++;
      try {
        if (br && typeof br.onStreamBody === 'function') {
          br.onStreamBody(url, mime);
          return;
        }
      } catch (e) {}
      try {
        if (!isTop) window.parent.postMessage({ __pbStreamBody: { url: url, mime: mime } }, '*');
      } catch (e) {}
    }

    // ftyp major brands that are never a standalone playable video: DASH/CMAF
    // pieces, still images (HEIF/AVIF) and audio-only files.
    var NOT_VIDEO_BRANDS = {
      'dash': 1, 'msdh': 1, 'msix': 1, 'cmfc': 1, 'cmf2': 1,
      'avif': 1, 'avis': 1, 'heic': 1, 'heix': 1, 'mif1': 1, 'msf1': 1,
      'M4A ': 1, 'M4B ': 1, 'M4P ': 1
    };

    // First bytes of a body -> media type, or null. Segments are deliberately
    // null: a playing video fetches hundreds and each would push the playlist
    // out of the bounded candidate list.
    function classifyBytes(b) {
      if (!b || b.length < 8) return null;
      if (b[0] === 0x47 && (b.length < 189 || b[188] === 0x47)) return null;   // MPEG-TS
      if (b[0] === 0x1A && b[1] === 0x45 && b[2] === 0xDF && b[3] === 0xA3) return WEBM;
      var box = String.fromCharCode(b[4], b[5], b[6], b[7]);
      if (box === 'ftyp') {
        if (b.length < 12) return null;
        var brand = String.fromCharCode(b[8], b[9], b[10], b[11]);
        return NOT_VIDEO_BRANDS[brand] ? null : MP4;
      }
      if (box === 'moof' || box === 'styp' || box === 'sidx') return null;     // fMP4 segment
      var i = 0;
      if (b[0] === 0xEF && b[1] === 0xBB && b[2] === 0xBF) i = 3;              // UTF-8 BOM
      while (i < b.length && (b[i] === 0x20 || b[i] === 0x09 || b[i] === 0x0D || b[i] === 0x0A)) i++;
      // '#EXTM3U'
      if (b.length - i >= 7 && b[i] === 0x23 && b[i + 1] === 0x45 && b[i + 2] === 0x58 &&
          b[i + 3] === 0x54 && b[i + 4] === 0x4D && b[i + 5] === 0x33 && b[i + 6] === 0x55) return HLS;
      return null;
    }

    function classifyText(t) {
      if (typeof t !== 'string') return null;
      var s = t.slice(0, HEAD).replace(/^﻿/, '').replace(/^\s+/, '');
      return s.indexOf('#EXTM3U') === 0 ? HLS : null;
    }

    // Content-Type that already names the thing: no need to read the body.
    function mimeFromType(ct) {
      ct = String(ct || '').toLowerCase();
      if (ct.indexOf('mpegurl') >= 0) return HLS;
      if (ct.indexOf('video/mp4') === 0) return MP4;
      if (ct.indexOf('video/webm') === 0) return WEBM;
      return null;
    }

    function skipType(ct) {
      ct = String(ct || '').toLowerCase();
      if (ct.indexOf('mpegurl') >= 0) return false;
      return /^(image\/|font\/|audio\/|text\/css|text\/vtt|application\/(x-)?javascript|text\/javascript|application\/json|video\/mp2t|video\/iso\.segment|audio\/iso\.segment)/.test(ct);
    }

    // Known total size of the resource, or -1. 206: the total after '/' in
    // Content-Range (when the page may read it); 200: Content-Length.
    function totalSize(status, contentLength, contentRange) {
      if (status === 206) {
        var m = /\/(\d+)\s*$/.exec(String(contentRange || ''));
        return m ? parseInt(m[1], 10) : -1;
      }
      var n = parseInt(contentLength, 10);
      return isNaN(n) ? -1 : n;
    }

    function acceptable(mime, size) {
      if (!mime) return false;
      if (mime === HLS) return true;
      return size < 0 || size >= MIN_FILE;
    }

    function sniffResponse(fallbackUrl, res) {
      try {
        if (!res || !res.ok || res.type === 'opaque') return;
        var h = res.headers;
        var get = function (n) { try { return h && h.get ? h.get(n) : null; } catch (e) { return null; } };
        var ct = get('content-type');
        if (skipType(ct)) return;
        var url = absUrl(res.url) || fallbackUrl;
        if (!url || seen[url] || skipUrl(url)) return;
        var size = totalSize(res.status, get('content-length'), get('content-range'));
        var byType = mimeFromType(ct);
        if (byType) {
          if (acceptable(byType, size)) deliver(url, byType);
          return;
        }
        var c = res.clone();
        if (c.body && c.body.getReader) {
          var reader = c.body.getReader();
          reader.read().then(function (r) {
            try { var cp = reader.cancel(); if (cp && cp.catch) cp.catch(function () {}); } catch (e) {}
            try {
              if (r && r.value) {
                var m = classifyBytes(r.value);
                if (acceptable(m, size)) deliver(url, m);
              }
            } catch (e) {}
          }, function () {});
        }
      } catch (e) {}
    }

    // Wrappers are Proxies so name/length/toString stay the native ones, and
    // the original always runs first with the caller's own this/arguments.
    try {
      var origFetch = window.fetch;
      if (typeof origFetch === 'function') {
        window.fetch = new Proxy(origFetch, {
          apply: function (target, thisArg, args) {
            var p = Reflect.apply(target, thisArg == null ? window : thisArg, args);
            try {
              var input = args[0];
              var init = args[1];
              var isReq = input && typeof input === 'object' && typeof input.url === 'string';
              var method = (init && init.method) || (isReq && input.method) || 'GET';
              var url = absUrl(isReq ? input.url : input);
              if (url && !skipUrl(url) && String(method).toUpperCase() === 'GET' && p && p.then) {
                p.then(function (res) { sniffResponse(url, res); }, function () {});
              }
            } catch (e) {}
            return p;
          }
        });
      }
    } catch (e) {}

    // XMLHttpRequest. Per-request state lives in WeakMaps, not on the object.
    var xhrUrl = new WeakMap();    // xhr -> absolute URL of its current GET, or null
    var xhrHooked = new WeakMap(); // xhr -> true once our load listener is on
    function sniffXhr(x) {
      try {
        var reqUrl = xhrUrl.get(x);
        if (!reqUrl) return;       // current request isn't a GET we looked at
        if (x.status < 200 || x.status >= 300) return;
        var ct = x.getResponseHeader ? x.getResponseHeader('content-type') : null;
        if (skipType(ct)) return;
        var url = absUrl(x.responseURL) || reqUrl;
        if (!url || seen[url] || skipUrl(url)) return;
        var rt = x.responseType || '';
        // Content-Range isn't CORS-safelisted; asking for it cross-origin only
        // logs a console error, so only ask when it matters.
        var hdrSize = totalSize(x.status, x.getResponseHeader('content-length'),
          x.status === 206 ? x.getResponseHeader('content-range') : null);
        var byType = mimeFromType(ct);
        if (byType) {
          if (acceptable(byType, hdrSize)) deliver(url, byType);
          return;
        }
        if (rt === '' || rt === 'text') {
          var mt = classifyText(x.responseText);
          if (mt) deliver(url, mt);
        } else if (rt === 'arraybuffer' && x.response && x.response.byteLength) {
          var ab = x.response;
          var ma = classifyBytes(new Uint8Array(ab, 0, Math.min(HEAD, ab.byteLength)));
          if (acceptable(ma, x.status === 206 ? hdrSize : ab.byteLength)) deliver(url, ma);
        } else if (rt === 'blob' && x.response && x.response.slice) {
          var bl = x.response;
          var blSize = x.status === 206 ? hdrSize : bl.size;
          var fr = new FileReader();
          fr.onload = function () {
            try {
              var mb = classifyBytes(new Uint8Array(fr.result));
              if (acceptable(mb, blSize)) deliver(url, mb);
            } catch (e) {}
          };
          fr.readAsArrayBuffer(bl.slice(0, HEAD));
        }
      } catch (e) {}
    }
    try {
      var XP = window.XMLHttpRequest && window.XMLHttpRequest.prototype;
      if (XP && typeof XP.open === 'function' && typeof XP.send === 'function') {
        XP.open = new Proxy(XP.open, {
          apply: function (target, thisArg, args) {
            try {
              var u = (String(args[0]).toUpperCase() === 'GET') ? absUrl(args[1]) : null;
              if (thisArg && typeof thisArg === 'object') xhrUrl.set(thisArg, (u && !skipUrl(u)) ? u : null);
            } catch (e) {}
            return Reflect.apply(target, thisArg, args);
          }
        });
        XP.send = new Proxy(XP.send, {
          apply: function (target, thisArg, args) {
            try {
              var x = thisArg;
              if (x && typeof x === 'object' && xhrUrl.get(x) && !xhrHooked.get(x)) {
                xhrHooked.set(x, true);
                x.addEventListener('load', function () { sniffXhr(x); });
              }
            } catch (e) {}
            return Reflect.apply(target, thisArg, args);
          }
        });
      }
    } catch (e) {}

    // Cross-frame relay: child -> parent -> ... -> top (which owns the bridge).
    // Only direct child frames are listened to, and everything in the message
    // is page input: shape, scheme and mime are checked here, Kotlin checks again.
    function fromChildFrame(src) {
      try {
        for (var i = 0; i < window.frames.length; i++) {
          if (window.frames[i] === src) return true;
        }
      } catch (e) {}
      return false;
    }
    window.addEventListener('message', function (ev) {
      var d = ev && ev.data;
      if (!d || typeof d !== 'object') return;
      var m = d.__pbStreamBody;
      if (!m || typeof m !== 'object') return;
      if (!fromChildFrame(ev.source)) return;
      if (typeof m.url !== 'string' || typeof m.mime !== 'string') return;
      if (m.mime !== HLS && m.mime !== MP4 && m.mime !== WEBM) return;
      if (!/^https?:\/\//i.test(m.url) || m.url.length > MAX_URL) return;
      deliver(m.url, m.mime);
    }, false);
  })();
})();
