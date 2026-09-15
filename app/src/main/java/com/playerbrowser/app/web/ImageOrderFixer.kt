package com.playerbrowser.app.web

import android.content.Context
import android.webkit.WebView
import com.playerbrowser.app.network.ChallengeDetector
import com.playerbrowser.app.network.DebugLog
import org.json.JSONObject

/**
 * Puts jumbled webtoon images back in reading order (v1.3.99).
 *
 * Some sites list a chapter's images in upload-completion order; the filenames are
 * numeric timestamps, so `image_order.js` sorts each run of them by that number.
 * Opt-in per site from the ⋮ menu, then remembered by [VisitedLinkKeys.siteKey]
 * so it survives domain-number hops (blacktoon422 → blacktoon423).
 */
object ImageOrderFixer {

    data class Result(val found: Int, val moved: Int)

    private const val PREFS = "image_order_sites"
    private const val KEY_SITES = "sites"

    /** Re-runs after load: lazy loaders and late `show_content_img()` calls add images. */
    private val RETRY_DELAYS_MS = longArrayOf(1500L, 4000L)

    @Volatile private var sites: Set<String>? = null
    @Volatile private var scriptPrefix: String? = null

    private fun siteOf(url: String?): String? {
        val host = url?.let(VisitedLinkKeys::hostOf) ?: return null
        return VisitedLinkKeys.siteKey(host) ?: host.lowercase().removePrefix("www.")
    }

    private fun load(context: Context): Set<String> = sites ?: synchronized(this) {
        sites ?: context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SITES, emptySet())
            .orEmpty().toSet()
            .also { sites = it }
    }

    private fun save(context: Context, next: Set<String>) = synchronized(this) {
        sites = next
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_SITES, next).apply()
    }

    fun isRemembered(context: Context, url: String?): Boolean {
        val site = siteOf(url) ?: return false
        return site in load(context)
    }

    fun remember(context: Context, url: String?) {
        val site = siteOf(url) ?: return
        val current = load(context)
        if (site !in current) save(context, current + site)
    }

    fun forget(context: Context, url: String?) {
        val site = siteOf(url) ?: return
        val current = load(context)
        if (site in current) save(context, current - site)
    }

    /** Main thread. [callback] gets null when the page has moved on or the script failed. */
    fun sort(view: WebView, callback: (Result?) -> Unit) = evaluate(view, "sort", callback)

    fun restore(view: WebView, callback: (Result?) -> Unit) = evaluate(view, "restore", callback)

    /**
     * Called from `onPageFinished` (inside the `!onChallenge` block — never on a
     * challenge page). No-op unless the user turned sorting on for this site.
     */
    fun applyIfRemembered(view: WebView, url: String?) {
        if (url == null || !isRemembered(view.context, url)) return
        val host = VisitedLinkKeys.hostOf(url) ?: return
        val pass = { attempt: Int ->
            evaluate(view, "sort") { r ->
                if (r != null && r.moved > 0) {
                    DebugLog.d("ImageOrder", "$host #$attempt: ${r.moved}/${r.found} 이미지 순서 정렬")
                }
            }
        }
        pass(0)
        RETRY_DELAYS_MS.forEachIndexed { i, delay ->
            view.postDelayed({
                // Same document still on screen, still switched on (the user may have
                // restored in the meantime), and it has not turned into a challenge.
                if (view.url != url || !isRemembered(view.context, url)) return@postDelayed
                if (ChallengeDetector.isChallengeActive(host) ||
                    ChallengeDetector.isChallengeTitle(view.title)
                ) return@postDelayed
                pass(i + 1)
            }, delay)
        }
    }

    private fun evaluate(view: WebView, mode: String, callback: (Result?) -> Unit) {
        val host = view.url?.let(VisitedLinkKeys::hostOf)
        if (host == null) {
            callback(null)
            return
        }
        // The expected host lets the script bail if getUrl() ran ahead of the document.
        val js = prefix(view) + JSONObject.quote(mode) + "," + JSONObject.quote(host) + ");"
        view.evaluateJavascript(js) { raw -> callback(parse(raw)) }
    }

    private fun parse(raw: String?): Result? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching {
            val o = JSONObject(raw)
            o.optString("error").takeIf { it.isNotEmpty() }?.let {
                DebugLog.d("ImageOrder", "스크립트 오류: $it")
            }
            Result(o.optInt("found"), o.optInt("moved"))
        }.getOrNull()
    }

    private fun prefix(view: WebView): String = scriptPrefix
        ?: ("(" + WebAssetLoader.imageOrderScript(view.context).trim().removeSuffix(";") + ")(")
            .also { scriptPrefix = it }
}
