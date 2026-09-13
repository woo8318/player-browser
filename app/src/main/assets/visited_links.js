// Marks links whose page was opened before under a sibling domain number
// (newtoki123 → newtoki124). Native :visited matches only the exact URL, so the
// marks vanish whenever the site hops domains. VisitedLinkMarker evaluates this
// expression as `(<this>)(hashes, siteKey)` on page finish and tab re-activation.
//
// Privacy: the page is handed 64-bit hashes of page keys, never history URLs,
// and they live only in this closure. Strict mode matters — a page-patched
// builtin called from sloppy code could reach our `arguments` via `.caller`.
// siteKey / keyOf / hash mirror VisitedLinkKeys.kt bit for bit.
(function (hashes, site) {
  'use strict';
  try {
    var CLS = '__pbv';
    var STYLE_ID = '__pbv-style';
    // Colour only — fading would grey out thumbnails and whole cards. Site CSS
    // often colours the title span itself, so descendants inherit the mark.
    var CSS = 'a.' + CLS + '{color:#8e44ad!important}' +
      'a.' + CLS + ' :not(img,video,picture,svg,canvas){color:inherit!important}';
    var THROTTLE_MS = 250;
    var SHARED = '__pbVisited';

    function siteKey(host) {
      var h = String(host || '').toLowerCase();
      if (h.indexOf('www.') === 0) h = h.slice(4);
      if (!h || h.indexOf(':') >= 0 || /^\d+(\.\d+){3}$/.test(h)) return null;
      var m = /\d+(?=\D*$)/.exec(h);
      if (!m) return null;
      var end = m.index + m[0].length;
      var labelStart = h.lastIndexOf('.', m.index) + 1;
      var labelEnd = h.indexOf('.', end - 1);
      if (labelEnd < 0) labelEnd = h.length;
      if (!/[a-z]/.test(h.slice(labelStart, labelEnd))) return null;
      return h.slice(0, m.index) + '#' + h.slice(end);
    }

    // Scheme and fragment are ignored; a trailing slash is not a different page.
    function keyOf(loc) {
      if (loc.protocol !== 'http:' && loc.protocol !== 'https:') return null;
      var site = siteKey(loc.hostname);
      if (!site) return null;
      var p = loc.pathname || '/';
      if (p.length > 1 && p.charAt(p.length - 1) === '/') p = p.slice(0, -1);
      return site + p + (loc.search || '');
    }

    function hex8(v) {
      return ('0000000' + (v >>> 0).toString(16)).slice(-8);
    }

    function hash(s) {
      var h1 = 0xdeadbeef | 0;
      var h2 = 0x41c6ce57;
      for (var i = 0; i < s.length; i++) {
        var c = s.charCodeAt(i);
        h1 = Math.imul(h1 ^ c, 2654435761);
        h2 = Math.imul(h2 ^ c, 1597334677);
      }
      h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507);
      h1 ^= Math.imul(h2 ^ (h2 >>> 13), 3266489909);
      h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507);
      h2 ^= Math.imul(h1 ^ (h1 >>> 13), 3266489909);
      return hex8(h2) + hex8(h1);
    }

    // Run in the document that was on screen when Kotlin picked the payload —
    // a tab switched mid-navigation would otherwise get the next site's set.
    if (typeof site === 'string' && siteKey(location.hostname) !== site) return;

    // The history hashes must never reach a function the page can replace —
    // page scripts run first, so Array.isArray / Object.create / push may be
    // patched to record their arguments. Literal syntax only: the null-proto
    // literal calls nothing and has no setter or prototype getter to hit.
    var visited = { __proto__: null };
    var list = hashes || [];
    for (var i = 0; i < list.length; i++) {
      if (typeof list[i] === 'string') visited[list[i]] = 1;
    }

    // Re-injection (tab re-activation) brings a fresher history. The previous
    // closure is retired by identity: it stops as soon as it sees `shared`
    // replaced. Only tapped-link hashes are handed over — the page saw the
    // tap anyway, so exposing them on window leaks nothing.
    var prev = window[SHARED];
    var clicked = [];
    if (prev && Array.isArray(prev.clicked)) {
      for (var j = 0; j < prev.clicked.length && j < 500; j++) {
        var c = prev.clicked[j];
        if (typeof c === 'string') {
          clicked.push(c);
          visited[c] = 1;
        }
      }
    }
    var shared = { clicked: clicked };
    window[SHARED] = shared;

    var seen = new WeakMap();
    var self = null;
    var timer = 0;
    var observer = null;
    var stopped = false;

    function live() {
      if (stopped) return false;
      if (window[SHARED] === shared) return true;
      stopped = true;
      if (observer) observer.disconnect();
      window.removeEventListener('click', onClick, true);
      if (timer) clearTimeout(timer);
      return false;
    }

    // Same-page links ('#top', the page itself) resolve to a visited URL but are
    // UI, not "a page you read" — marking them would recolour the site's own
    // buttons. The bare site root is always visited, so it is skipped too.
    function isVisited(a) {
      if ((a.getAttribute('href') || '').charAt(0) === '#') return false;
      var key = keyOf(a);
      if (!key || key === self) return false;
      if (a.pathname === '/' && !a.search) return false;
      return visited[hash(key)] === 1;
    }

    function scan() {
      timer = 0;
      if (!live()) return;
      var here = keyOf(location);
      if (here !== self) {
        self = here;
        seen = new WeakMap();
      }
      var links = document.querySelectorAll('a[href]');
      for (var i = 0; i < links.length; i++) {
        var a = links[i];
        if (!(a instanceof HTMLAnchorElement)) continue;
        var href = a.href;
        if (seen.get(a) === href) continue;
        seen.set(a, href);
        var on = isVisited(a);
        if (a.classList.contains(CLS) !== on) a.classList.toggle(CLS, on);
      }
    }

    function schedule() {
      if (!timer && !stopped) timer = setTimeout(scan, THROTTLE_MS);
    }

    // Only mutations that can bring or retarget a link warrant a rescan — a
    // ticking player or ad rotator would otherwise rescan the page 4×/s.
    function bringsLinks(records) {
      for (var i = 0; i < records.length; i++) {
        var r = records[i];
        if (r.type === 'attributes') return true;
        var added = r.addedNodes;
        for (var k = 0; k < added.length; k++) {
          var n = added[k];
          if (n.nodeType !== 1) continue;
          if (n.localName === 'a' || n.querySelector('a[href]')) return true;
        }
      }
      return false;
    }

    // A tapped link's page may never reach history under this URL (redirects,
    // a new tab not yet loaded), so mark it on tap.
    function onClick(e) {
      if (!live()) return;
      var t = e.target;
      var a = t && t.closest ? t.closest('a[href]') : null;
      if (!(a instanceof HTMLAnchorElement)) return;
      if ((a.getAttribute('href') || '').charAt(0) === '#') return;
      var key = keyOf(a);
      if (!key) return;
      var h = hash(key);
      if (visited[h] === 1) return;
      visited[h] = 1;
      clicked.push(h);
      seen = new WeakMap();
      schedule();
    }

    if (!document.getElementById(STYLE_ID)) {
      var s = document.createElement('style');
      s.id = STYLE_ID;
      s.textContent = CSS;
      (document.head || document.documentElement).appendChild(s);
    }

    // Infinite-scroll lists and SPA re-renders add links after page finish.
    observer = new MutationObserver(function (records) {
      if (live() && bringsLinks(records)) schedule();
    });
    observer.observe(document.documentElement, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['href']
    });
    window.addEventListener('click', onClick, true);
    scan();
  } catch (e) {}
})
