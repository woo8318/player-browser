package com.playerbrowser.app.web

import android.webkit.WebView
import com.playerbrowser.app.network.ChallengeDetector
import com.playerbrowser.app.network.VisitedLinkSwitch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Marks links as visited across domain-number hops (newtoki123 → newtoki124).
 *
 * Chromium's `:visited` matches the exact URL, and neither JS nor
 * `WebChromeClient.getVisitedHistory` (called once per WebView) can feed it, so
 * every hop wipes the marks. We keep our own: the Room history is turned into
 * domain-number-agnostic page keys ([VisitedLinkKeys]) here, and
 * `visited_links.js` is handed the current family's key **hashes** to tag
 * matching anchors with a class. The page never sees a history URL.
 */
object VisitedLinkMarker {

    /** Newest first per site — bounds the payload evaluated on every page. */
    private const val MAX_KEYS_PER_SITE = 2000

    /** Site key → JSON array of that family's page-key hashes, built off the main thread. */
    @Volatile private var payloads: Map<String, String> = emptyMap()

    private val _ready = MutableStateFlow(false)

    /** True once the first history snapshot is indexed — pages finished before that got nothing. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    @Volatile private var scriptPrefix: String? = null

    /** [urls] newest first (see `HistoryDao.observeUrlsByRecency`). Background thread. */
    fun rebuild(urls: List<String>) {
        val bySite = HashMap<String, LinkedHashSet<String>>()
        for (url in urls) {
            val key = VisitedLinkKeys.pageKey(url) ?: continue
            val bucket = bySite.getOrPut(VisitedLinkKeys.siteOfPageKey(key)) { LinkedHashSet() }
            if (bucket.size < MAX_KEYS_PER_SITE) bucket.add(VisitedLinkKeys.hash(key))
        }
        // Hashes are hex, so plain quoting is valid JSON.
        payloads = bySite.mapValues { (_, hashes) ->
            hashes.joinToString(",", "[", "]") { "\"$it\"" }
        }
        _ready.value = true
    }

    /** Main thread. No-op off digit-numbered hosts, where native `:visited` suffices. */
    fun apply(view: WebView, url: String?) {
        if (!VisitedLinkSwitch.enabled) return
        val host = url?.let(VisitedLinkKeys::hostOf) ?: return
        if (ChallengeDetector.isChallengeActive(host)) return
        val site = VisitedLinkKeys.siteKey(host) ?: return
        val payload = payloads[site] ?: return
        // The site key lets the script bail if the document on screen is not
        // the one `url` names (getUrl() runs ahead during a navigation).
        view.evaluateJavascript(prefix(view) + payload + "," + JSONObject.quote(site) + ");", null)
    }

    private fun prefix(view: WebView): String = scriptPrefix
        ?: ("(" + WebAssetLoader.visitedLinksScript(view.context).trim().removeSuffix(";") + ")(")
            .also { scriptPrefix = it }
}
