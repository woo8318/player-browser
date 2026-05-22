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
  window.__pb.seek = function (delta) {
    var v = fullscreenVideo() || activeVideo();
    if (v) seekBy(v, delta);
  };
  window.__pb.togglePlay = function () {
    var v = fullscreenVideo() || activeVideo();
    if (v) togglePlay(v);
  };
  window.__pb.switchVideo = function (dir) { switchVideo(dir); };

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
    if (!e.touches || e.touches.length === 0) return;
    var t0 = e.touches[0];
    var v = videoAtPoint(t0.clientX, t0.clientY);
    if (!v) { touchState = null; return; }
    touchState = {
      video: v,
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

    // 2-finger horizontal swipe → switch video.
    if (s.pointers >= 2 && s.moved && dt <= SWIPE_TIME_LIMIT_MS &&
        Math.abs(dx) >= SWIPE_THRESHOLD_PX && Math.abs(dx) > Math.abs(dy)) {
      switchVideo(dx > 0 ? -1 : 1);
      return;
    }

    // Scrubbing already updated currentTime live; just hide the overlay.
    if (s.scrubbing) { hideScrub(); return; }

    if (!et) return;
    var x = et.clientX, y = et.clientY;
    var now = Date.now();
    if (now - lastTap.t < DOUBLE_TAP_MS && lastTap.video === s.video &&
        Math.abs(x - lastTap.x) < DOUBLE_TAP_MAX_MOVE &&
        Math.abs(y - lastTap.y) < DOUBLE_TAP_MAX_MOVE) {
      // Double-tap: side-aware. Left third → -10s, right third → +10s,
      // middle → play/pause.
      var r = s.video.getBoundingClientRect();
      var left = r.left, width = r.width;
      if (!width || width < 50) { left = 0; width = window.innerWidth || 1; }
      var rel = (x - left) / width;
      if (rel < 0.35) seekBy(s.video, -SEEK_SEC);
      else if (rel > 0.65) seekBy(s.video, SEEK_SEC);
      else togglePlay(s.video);
      lastTap.t = 0;
      // Block the site from also handling this tap (the synthesized click
      // and the upcoming dblclick are what trigger fullscreen on most
      // players).
      suppressClick = { until: Date.now() + 500, x: x, y: y };
      e.stopPropagation();
      if (e.stopImmediatePropagation) e.stopImmediatePropagation();
    } else {
      lastTap = { t: now, x: x, y: y, video: s.video };
    }
  }, { passive: true, capture: true });

  document.addEventListener('touchcancel', function () {
    if (touchState && touchState.scrubbing) hideScrub();
    touchState = null;
  }, { passive: true, capture: true });
})();
