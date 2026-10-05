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

    /**
     * How long a just-forgotten hash is kept out of [rebuild]. The history
     * delete is asynchronous, so a snapshot taken before it lands may still be
     * on its way here and would bring the mark straight back.
     */
    private const val FORGET_HOLD_MS = 5_000L

    /** Site key → that family's page-key hashes, newest first. */
    @Volatile private var index: Map<String, Set<String>> = emptyMap()

    /** Site key → JSON array of [index]'s hashes, built off the main thread. */
    @Volatile private var payloads: Map<String, String> = emptyMap()

    /** Hash → time it was un-marked (see [FORGET_HOLD_MS]). Copy-on-write under [forgetLock]. */
    @Volatile private var forgotten: Map<String, Long> = emptyMap()

    /** [forget] (main) and [rebuild] (background) both rewrite [forgotten]. */
    private val forgetLock = Any()

    private val _ready = MutableStateFlow(false)

    /** True once the first history snapshot is indexed — pages finished before that got nothing. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    @Volatile private var scriptPrefix: String? = null

    /** [urls] newest first (see `HistoryDao.observeUrlsByRecency`). Background thread. */
    fun rebuild(urls: List<String>) {
        val now = System.currentTimeMillis()
        val held = forgotten.filterValues { now - it < FORGET_HOLD_MS }
        val stale = HashSet<String>()
        val bySite = HashMap<String, LinkedHashSet<String>>()
        for (url in urls) {
            val key = VisitedLinkKeys.pageKey(url) ?: continue
            val hash = VisitedLinkKeys.hash(key)
            if (hash in held) {
                stale.add(hash)
                continue
            }
            val bucket = bySite.getOrPut(VisitedLinkKeys.siteOfPageKey(key)) { LinkedHashSet() }
            if (bucket.size < MAX_KEYS_PER_SITE) bucket.add(hash)
        }
        index = bySite
        payloads = bySite.mapValues { (_, hashes) -> toPayload(hashes) }
        // A hold ends as soon as a snapshot no longer has the page (the delete
        // landed) — otherwise reopening it within the hold would stay unmarked.
        // Entries forget() added while this ran were not tested here and stay.
        synchronized(forgetLock) {
            forgotten = forgotten.filter { (h, t) ->
                now - t < FORGET_HOLD_MS && (h !in held || h in stale)
            }
        }
        _ready.value = true
    }

    /** True where our own mark (not the native `:visited`) can be on [url]'s link. */
    fun canForget(url: String): Boolean =
        VisitedLinkSwitch.enabled && VisitedLinkKeys.pageKey(url) != null

    /**
     * Link menu → "방문 표시 지우기" (v1.3.109). Drops [url]'s page from the index
     * right away (the caller deletes the history rows, which re-indexes a
     * moment later) and returns its hash for [apply], or null when [url] is
     * not a page we mark. Main thread.
     */
    fun forget(url: String): String? {
        val key = VisitedLinkKeys.pageKey(url) ?: return null
        val hash = VisitedLinkKeys.hash(key)
        val site = VisitedLinkKeys.siteOfPageKey(key)
        synchronized(forgetLock) { forgotten = forgotten + (hash to System.currentTimeMillis()) }
        val hashes = index[site]
        if (hashes != null && hash in hashes) {
            val rest = hashes - hash
            index = index + (site to rest)
            payloads = payloads + (site to toPayload(rest))
        }
        return hash
    }

    /**
     * Main thread. No-op off digit-numbered hosts, where native `:visited`
     * suffices. [forget] is a hash from [forget]: the page is re-marked even
     * when nothing else is left in its family, and that one mark is taken off.
     */
    fun apply(view: WebView, url: String?, forget: String? = null) {
        if (!VisitedLinkSwitch.enabled) return
        val host = url?.let(VisitedLinkKeys::hostOf) ?: return
        // No script on a challenge page — Kotlin signals only (v1.3.87).
        if (ChallengeDetector.isChallengeActive(host)) return
        if (ChallengeDetector.isChallengeTitle(view.title)) return
        val site = VisitedLinkKeys.siteKey(host) ?: return
        val payload = payloads[site] ?: if (forget != null) "[]" else return
        // The site key lets the script bail if the document on screen is not
        // the one `url` names (getUrl() runs ahead during a navigation).
        val tail = if (forget != null) ",[" + JSONObject.quote(forget) + "]" else ""
        view.evaluateJavascript(prefix(view) + payload + "," + JSONObject.quote(site) + tail + ");", null)
    }

    // Hashes are hex, so plain quoting is valid JSON.
    private fun toPayload(hashes: Collection<String>): String =
        hashes.joinToString(",", "[", "]") { "\"$it\"" }

    private fun prefix(view: WebView): String = scriptPrefix
        ?: ("(" + WebAssetLoader.visitedLinksScript(view.context).trim().removeSuffix(";") + ")(")
            .also { scriptPrefix = it }
}
