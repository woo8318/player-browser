package com.playerbrowser.app.network

/**
 * Cookie / GDPR consent banner auto-rejecter.
 *
 * Most EU sites (and many global ones) gate content behind a cookie
 * banner. The banners ship from a handful of consent-management platforms
 * (OneTrust, Cookiebot, Quantcast/TCF, Didomi, TrustArc, Sourcepoint),
 * each with a recognisable button id/class. This injects a JS snippet
 * that:
 *
 *   1. Polls a few times (banners often render after pageload)
 *   2. Clicks the "reject all" button if it can find one — preferable to
 *      "accept all" because we don't want tracking cookies.
 *   3. As a fallback, hides any obvious banner container so it doesn't
 *      block content even if the click didn't take.
 *
 * Why JS-side (not network-level): the banners are first-party JS / DOM,
 * not third-party requests we could 204 away. Network-level blocks would
 * either miss them or break the host site's own JS.
 *
 * Why prefer reject-click over just hiding: many sites add `overflow:hidden`
 * to body or set a backdrop with `pointer-events: all` — hiding the banner
 * alone leaves the page un-scrollable. Clicking reject dismisses both.
 */
object CookieBannerKiller {

    /**
     * JS snippet. Idempotent via a window flag. Safe to inject every
     * `onPageFinished`; only the first call wires the polling loop.
     */
    const val SCRIPT: String = """
        (function () {
          if (window.__pbCookieKill) return;
          window.__pbCookieKill = true;

          var REJECT_SELECTORS = [
            // OneTrust
            '#onetrust-reject-all-handler',
            'button.ot-pc-refuse-all-handler',
            // Cookiebot
            '#CybotCookiebotDialogBodyButtonDecline',
            '#CybotCookiebotDialogBodyLevelButtonCustomize + button',
            // Quantcast / IAB TCF
            'button.qc-cmp2-summary-buttons[mode="secondary"]',
            'button[mode="secondary"][aria-label*="Reject"]',
            'button[mode="secondary"][aria-label*="reject"]',
            // Didomi
            '#didomi-notice-disagree-button',
            'button.didomi-button-standard.didomi-button-color-reverse',
            // Sourcepoint
            'button.sp_choice_type_REJECT_ALL',
            'button[title="Reject All"]',
            // TrustArc
            'a.call[onclick*="reject"]',
            // Common Google consent
            'button[aria-label="Reject all"]',
            'button[aria-label="모두 거부"]',
            // Generic data-attr variants
            '[data-testid="uc-deny-all-button"]',
            '[data-cy="cookie-banner-reject"]',
            '[data-cookieconsent="deny"]'
          ];

          var HIDE_SELECTORS = [
            '#onetrust-consent-sdk',
            '#onetrust-banner-sdk',
            '#CybotCookiebotDialog',
            '#CybotCookiebotDialogBodyUnderlay',
            '.qc-cmp2-container',
            '#didomi-host',
            '.didomi-popup-container',
            '.sp_message_container_*',
            '#truste-consent-track',
            '.truste_box_overlay',
            '#cookie-banner',
            '.cookie-banner',
            '.cookie-consent',
            '#cookieConsent',
            '[id*="cookie-notice"]',
            '[class*="cookie-notice"]',
            '[id*="gdpr-banner"]',
            '[class*="gdpr-banner"]'
          ];

          function tryClickReject() {
            for (var i = 0; i < REJECT_SELECTORS.length; i++) {
              try {
                var btn = document.querySelector(REJECT_SELECTORS[i]);
                if (btn && btn.offsetParent !== null) {
                  btn.click();
                  return true;
                }
              } catch (e) { /* selector with `*` etc. may fail – skip */ }
            }
            return false;
          }

          function applyHideCss() {
            if (document.getElementById('__pb-cookie-hide')) return;
            var sty = document.createElement('style');
            sty.id = '__pb-cookie-hide';
            sty.textContent =
              HIDE_SELECTORS.join(',') +
              '{display:none!important;visibility:hidden!important;' +
              'pointer-events:none!important;}' +
              'html,body{overflow:auto!important;}';
            (document.head || document.documentElement).appendChild(sty);
          }

          applyHideCss();

          var attempts = 0;
          var timer = setInterval(function () {
            attempts++;
            var clicked = tryClickReject();
            if (clicked || attempts >= 10) {
              clearInterval(timer);
            }
          }, 400);
        })();
    """
}
