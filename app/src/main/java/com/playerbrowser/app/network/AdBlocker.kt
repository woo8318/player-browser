package com.playerbrowser.app.network

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight network-layer ad blocker. Matches against a curated set of
 * known ad / tracker hostnames (suffix-matched) plus a handful of
 * fingerprint URL substrings. Subresource requests that match return a
 * synthetic 204 No Content response, which is silently accepted by all
 * common subresource consumers (images, scripts, iframes, XHR).
 *
 * Why network-level (and not just CSS hiding): blocking at the request
 * boundary stops the ad payload from being downloaded, saving bandwidth,
 * battery and rendering time. CSS hiding only conceals already-loaded ads.
 *
 * Why suffix match (not exact): "googlesyndication.com" should also catch
 * "pagead2.googlesyndication.com" and the long tail of regional shards.
 *
 * Main-frame navigations are NEVER blocked — if the user explicitly types
 * or taps a URL that happens to match, we let it through. Otherwise an
 * inadvertent click would leave them on a blank page with no way back.
 */
object AdBlocker {

    private val blockedCount = AtomicLong(0)
    fun blockedCount(): Long = blockedCount.get()
    fun resetCount() { blockedCount.set(0) }

    // Host-suffix list. Each entry matches "host == entry" OR
    // "host endsWith .entry" — never substring (so "ad.com" can't
    // accidentally swallow "broadcasting.com").
    private val BLOCKED_HOSTS: Set<String> = setOf(
        // Google ad / analytics
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googletagservices.com",
        "googletagmanager.com",
        "google-analytics.com",
        "adservice.google.com",
        // Facebook tracking
        "connect.facebook.net",
        // Major ad exchanges / SSPs
        "adnxs.com",
        "adsrvr.org",
        "scorecardresearch.com",
        "outbrain.com",
        "taboola.com",
        "criteo.com",
        "criteo.net",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "moatads.com",
        "amazon-adsystem.com",
        "advertising.com",
        "yieldmo.com",
        "smartadserver.com",
        // Pop / push / adult-network ads (extremely common on streaming sites)
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "exoclick.com",
        "exosrv.com",
        "trafficjunky.com",
        "trafficjunky.net",
        "juicyads.com",
        "adsterra.com",
        "hilltopads.net",
        "clickadu.com",
        "ad-maven.com",
        "tsyndicate.com",
        // Behavioural / product analytics
        "hotjar.com",
        "mixpanel.com",
        "amplitude.com",
        "fullstory.com",
        "mouseflow.com",
        "newrelic.com",
        "branch.io",
        "appsflyer.com",
        "adjust.com",
        // KR-specific ad networks
        "adop.cc",
        "adop.kr",
        "interworksmedia.co.kr",
        "realclick.co.kr",
        "tagmanagerkorea.com"
    )

    // URL-substring patterns. Catches ad subresources served from the same
    // host as content (e.g., /ads/banner.gif on a publisher's CDN). Kept
    // short and conservative to minimise false positives.
    private val URL_PATTERNS: List<String> = listOf(
        "/ads/",
        "/adserver/",
        "/adservices/",
        "/advert/",
        "googleads",
        "/pagead/",
        "adsbygoogle",
        "amp-ad",
        "doubleclick"
    )

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (!AdBlockSwitch.enabled) return null
        if (request.isForMainFrame) return null
        val url = request.url ?: return null
        val host = url.host?.lowercase() ?: return null

        if (matchesHost(host)) {
            blockedCount.incrementAndGet()
            DebugLog.d("AdBlock", "blocked host=$host")
            return EMPTY_204
        }

        val urlString = url.toString().lowercase()
        if (URL_PATTERNS.any { urlString.contains(it) }) {
            blockedCount.incrementAndGet()
            DebugLog.d("AdBlock", "blocked pattern=$urlString")
            return EMPTY_204
        }
        return null
    }

    private fun matchesHost(host: String): Boolean =
        BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }

    private val EMPTY_204: WebResourceResponse
        get() = WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "No Content",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )

    /**
     * JS snippet that injects a stylesheet hiding common ad containers. Used
     * to handle ads served from the same host as the content (where the
     * network-level matcher can't help). Idempotent via a window flag.
     */
    const val HIDE_CSS_JS: String = """
        (function() {
          if (window.__pbAdHide) return;
          window.__pbAdHide = true;
          var style = document.createElement('style');
          style.id = '__pb-ad-hide';
          style.textContent = 'iframe[src*="googlesyndication"], iframe[src*="doubleclick"],' +
            'iframe[src*="adnxs"], iframe[src*="taboola"], iframe[src*="outbrain"],' +
            'iframe[src*="adservice"], iframe[src*="/ads/"], iframe[src*="popads"],' +
            'iframe[src*="exoclick"], iframe[src*="trafficjunky"],' +
            'iframe[src*="juicyads"], iframe[src*="adsterra"],' +
            'ins.adsbygoogle, .adsbygoogle,' +
            '[id^="google_ads_"], [id^="div-gpt-ad"],' +
            '[class*="adsense"], [id*="adsense"],' +
            '.ad-banner, .banner-ad, .ads-container, .ad-container, .ad-wrapper,' +
            '[class*="-ad-banner"], [class*="-ad-wrapper"], [class*="ad-slot"],' +
            '[data-ad-slot], [data-ad-client]' +
            '{display:none!important;visibility:hidden!important;height:0!important;min-height:0!important;}';
          (document.head || document.documentElement).appendChild(style);
        })();
    """
}
