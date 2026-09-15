// Webtoon image order fixer (v1.3.99). A bare function expression — ImageOrderFixer
// evaluates `(<this>)(mode, expectedHost)`.
//
// Some webtoon sites store a chapter's images in upload-completion order, not reading
// order. Their filenames are numeric timestamps (152126566223919.jpg — first 10 digits
// are unix seconds), so sorting by that number restores the order. The site's own
// viewer does no reordering: the HTML list itself is jumbled (blacktoon: 74 of 130
// images out of place, never more than 3 positions off).
//
// 'sort'    — reorder each run of numbered images by filename number.
// 'restore' — put them back in the order the page first had them.
// Returns {found, moved}, or null if this document is not the expected one.
//
// Existing nodes are moved, never cloned, so the page's lazy-loader handlers stay
// attached; a synthetic scroll afterwards makes scroll-driven loaders re-check.
(function (mode, expectedHost) {
  'use strict';
  try {
    if (expectedHost && location.hostname !== expectedHost) return null;

    var NUM = /^\d{10,20}$/;
    var ATTRS = ['o_src', 'data-original', 'data-src', 'data-lazy-src', 'src'];

    function keyOf(img) {
      for (var i = 0; i < ATTRS.length; i++) {
        var v = img.getAttribute(ATTRS[i]);
        if (!v || v.indexOf('data:') === 0) continue;
        var name = v.split(/[?#]/)[0];
        name = name.substring(name.lastIndexOf('/') + 1);
        var dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        if (NUM.test(name)) return name;
      }
      return null;
    }

    // Timestamps of different lengths: shorter is smaller; same length compares as text.
    function cmpKey(a, b) {
      if (a.length !== b.length) return a.length - b.length;
      return a < b ? -1 : a > b ? 1 : 0;
    }

    var state = window.__pbImgOrder;
    if (!state || !(state.orig instanceof WeakMap)) {
      state = { orig: new WeakMap(), n: 0 };
      window.__pbImgOrder = state;
    }

    // Units are what gets moved: the <img> itself, or its single-image wrapper
    // (<div><img></div>) when the images don't share a parent directly.
    function collect() {
      var imgs = document.images;
      var byParent = new Map();
      var entries = [];
      for (var i = 0; i < imgs.length; i++) {
        var img = imgs[i];
        var k = keyOf(img);
        if (!k || !img.parentNode) continue;
        var e = { img: img, key: k, unit: img };
        entries.push(e);
        var list = byParent.get(img.parentNode);
        if (!list) byParent.set(img.parentNode, (list = []));
        list.push(e);
      }
      var groups = [];
      var loose = [];
      byParent.forEach(function (list) {
        if (list.length >= 3) groups.push(list);
        else loose.push.apply(loose, list);
      });
      var byGrand = new Map();
      loose.forEach(function (e) {
        var wrap = e.img.parentElement;
        if (!wrap || wrap === document.body || wrap === document.documentElement) return;
        if (!wrap.parentNode || wrap.getElementsByTagName('img').length !== 1) return;
        e.unit = wrap;
        var list = byGrand.get(wrap.parentNode);
        if (!list) byGrand.set(wrap.parentNode, (list = []));
        list.push(e);
      });
      byGrand.forEach(function (list) {
        if (list.length >= 3) groups.push(list);
      });
      // First sighting fixes a unit's original position (document order).
      entries.forEach(function (e) {
        if (!state.orig.has(e.unit)) state.orig.set(e.unit, ++state.n);
      });
      return groups;
    }

    // Put desired[i] where current[i] is. Markers hold every slot, so a unit
    // leaving its old spot never shifts the slots still waiting to be filled.
    function arrange(current, desired) {
      var markers = current.map(function (u) {
        var m = document.createComment('');
        u.parentNode.insertBefore(m, u);
        return m;
      });
      var moved = 0;
      markers.forEach(function (m, i) {
        if (current[i] !== desired[i]) moved++;
        m.parentNode.replaceChild(desired[i], m);
      });
      return moved;
    }

    var groups = collect();
    var found = 0;
    var moved = 0;
    groups.forEach(function (list) {
      found += list.length;
      var current = list.map(function (e) { return e.unit; });
      var sorted = list.slice().sort(function (a, b) {
        if (mode === 'restore') return state.orig.get(a.unit) - state.orig.get(b.unit);
        return cmpKey(a.key, b.key) || state.orig.get(a.unit) - state.orig.get(b.unit);
      });
      var desired = sorted.map(function (e) { return e.unit; });
      var same = true;
      for (var i = 0; i < current.length; i++) {
        if (current[i] !== desired[i]) { same = false; break; }
      }
      if (!same) moved += arrange(current, desired);
    });
    if (moved > 0) {
      try { window.dispatchEvent(new Event('scroll')); } catch (e) {}
    }
    return { found: found, moved: moved };
  } catch (e) {
    return { found: 0, moved: 0, error: String(e && e.message || e) };
  }
})
