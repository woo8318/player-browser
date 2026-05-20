(function () {
  if (window.__pbGestureInstalled) return;
  window.__pbGestureInstalled = true;

  var SEEK_SEC = 10;
  var SWIPE_THRESHOLD = 50;        // px
  var SWIPE_TIME_LIMIT_MS = 600;

  function allVideos() {
    var nodes = document.querySelectorAll('video');
    return Array.prototype.slice.call(nodes).filter(function (v) {
      var r = v.getBoundingClientRect();
      return r.width > 80 && r.height > 60;
    });
  }

  function videoAtPoint(x, y) {
    var vids = allVideos();
    for (var i = 0; i < vids.length; i++) {
      var r = vids[i].getBoundingClientRect();
      if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return vids[i];
    }
    return null;
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
      try {
        if (v.paused) { v.play(); showToast('재생'); }
        else { v.pause(); showToast('일시정지'); }
      } catch (e2) {}
      lastTap.t = 0;
    } else {
      lastTap = { t: now, x: t.clientX, y: t.clientY };
    }
  }, { passive: true, capture: true });
})();
