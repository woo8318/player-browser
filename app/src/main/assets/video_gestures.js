(function () {
  if (window.__pbGestureInstalled) return;
  window.__pbGestureInstalled = true;

  var SEEK_SEC = 10;
  var SWIPE_THRESHOLD = 40;        // px
  var SWIPE_TIME_LIMIT_MS = 800;

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
    // Direct: a <video> is the fullscreen element.
    for (var i = 0; i < docs.length; i++) {
      var fe = docs[i].fullscreenElement || docs[i].webkitFullscreenElement;
      if (fe && fe.tagName === 'VIDEO') return fe;
    }
    // Container fullscreen: find any <video> inside the fullscreen element.
    for (var k = 0; k < docs.length; k++) {
      var fe2 = docs[k].fullscreenElement || docs[k].webkitFullscreenElement;
      if (fe2 && fe2.querySelector) {
        var v = fe2.querySelector('video');
        if (v) return v;
      }
    }
    // Iframe-as-fullscreen: parent doc shows the iframe as fullscreen element;
    // the actual <video> lives inside that iframe's document.
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
    // In native fullscreen the video surface is composited outside the DOM;
    // hit-testing by rect fails, so prefer the fullscreen element when present.
    var fs = fullscreenVideo();
    if (fs) return fs;
    var vids = allVideos();
    for (var i = 0; i < vids.length; i++) {
      var r = vids[i].getBoundingClientRect();
      if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return vids[i];
    }
    return activeVideo();
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

  var touchState = null;

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
      pointers: e.touches.length,
      moved: false
    };
  }, { passive: true, capture: true });

  document.addEventListener('touchmove', function (e) {
    if (!touchState || !e.touches || e.touches.length === 0) return;
    if (e.touches.length > touchState.pointers) touchState.pointers = e.touches.length;
    var t0 = e.touches[0];
    if (Math.abs(t0.clientX - touchState.startX) > 8 ||
        Math.abs(t0.clientY - touchState.startY) > 8) {
      touchState.moved = true;
    }
  }, { passive: true, capture: true });

  document.addEventListener('touchend', function (e) {
    if (!touchState) return;
    var s = touchState;
    touchState = null;
    if (!s.moved) return;
    if (Date.now() - s.startT > SWIPE_TIME_LIMIT_MS) return;

    var endTouch = (e.changedTouches && e.changedTouches[0]) || null;
    if (!endTouch) return;
    var dx = endTouch.clientX - s.startX;
    var dy = endTouch.clientY - s.startY;
    if (Math.abs(dx) < SWIPE_THRESHOLD) return;
    if (Math.abs(dx) < Math.abs(dy)) return;

    if (s.pointers >= 2) {
      switchVideo(dx > 0 ? -1 : 1);
    } else {
      seekBy(s.video, dx > 0 ? SEEK_SEC : -SEEK_SEC);
    }
  }, { passive: true, capture: true });

  // Double-tap to toggle play/pause.
  var lastTap = { t: 0, x: 0, y: 0 };
  document.addEventListener('touchend', function (e) {
    if (!e.changedTouches || e.changedTouches.length === 0) return;
    var t = e.changedTouches[0];
    var v = videoAtPoint(t.clientX, t.clientY);
    if (!v) { lastTap.t = 0; return; }
    var now = Date.now();
    if (now - lastTap.t < 300 &&
        Math.abs(t.clientX - lastTap.x) < 30 &&
        Math.abs(t.clientY - lastTap.y) < 30) {
      togglePlay(v);
      lastTap.t = 0;
    } else {
      lastTap = { t: now, x: t.clientX, y: t.clientY };
    }
  }, { passive: true, capture: true });
})();
