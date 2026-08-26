package com.playerbrowser.app.cast

import android.webkit.WebResourceRequest
import com.playerbrowser.app.network.DebugLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class StreamCandidate(
    val url: String,
    val mime: String,
    val capturedAt: Long
) {
    val isHls: Boolean get() = mime.contains("mpegurl", ignoreCase = true) ||
        url.contains(".m3u8", ignoreCase = true)
}

/**
 * Observes WebView subresource requests and remembers the castable video URLs
 * seen per main-frame host so the Chromecast button, the ⋮ menu, and the
 * on-screen "play in player" affordance can pick them up without the user
 * having to copy a URL manually.
 *
 * Keeps *all* distinct streams seen for a host (bounded) so a page with several
 * videos can offer a per-stream picker. `current()` still returns a single best
 * pick (HLS preferred, else most recent) for Cast / one-tap launch; `all()`
 * exposes the full list for the picker dialog.
 */
object VideoStreamSniffer {

    private const val TAG = "Cast"
    private const val MAX_PER_HOST = 12
    private val candidates = ConcurrentHashMap<String, CopyOnWriteArrayList<StreamCandidate>>()

    // Bumped whenever a candidate is stored. Compose observers collect this to
    // reactively show/refresh the on-screen player button the moment a stream
    // is captured (writes happen on the WebView's background thread, so a
    // thread-safe StateFlow rather than a plain snapshot state).
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    // Subresource URLs we looked at and did not recognise as a stream, kept
    // only for the host being browsed right now. A "no stream" toast is a dead
    // end on its own - it says the matcher missed, not what it missed - and
    // guessing at URL shapes is exactly how we ended up here. So record the
    // misses and let the log say what the site actually serves.
    private const val MAX_MISSES = 60

    // How many of the earliest misses are never evicted. The request that
    // matters - the playlist - is fetched once, first, and everything after it
    // is a flood of segments. Evicting from the head would push the one URL we
    // are hunting for out of the list precisely when playback finally produces it.
    private const val KEEP_FIRST = 24
    @Volatile private var missHost: String? = null
    private val misses = CopyOnWriteArrayList<String>()

    // Page furniture that could never be a video. Skipping it keeps the dump
    // short enough to read; anything ambiguous is kept on purpose.
    private val IGNORED_EXTS = listOf(
        ".css", ".js", ".mjs", ".png", ".jpg", ".jpeg", ".gif", ".webp",
        ".avif", ".svg", ".ico", ".woff", ".woff2", ".ttf", ".otf", ".map"
    )

    private val VIDEO_EXTS = listOf(
        ".m3u8" to "application/vnd.apple.mpegurl",
        ".mp4" to "video/mp4",
        ".webm" to "video/webm"
    )

    fun observe(request: WebResourceRequest, mainFrameHost: String?) {
        if (mainFrameHost.isNullOrBlank()) return
        val method = request.method?.uppercase() ?: "GET"
        if (method != "GET") return
        val url = request.url?.toString() ?: return
        val mime = detectMime(url)
        if (mime == null) {
            noteMiss(mainFrameHost, url)
            return
        }
        val list = candidates.getOrPut(mainFrameHost) { CopyOnWriteArrayList() }
        // Keep every distinct stream (by URL) so a multi-video page can offer a
        // per-stream fallback; bound the list so a long session doesn't grow it
        // unbounded (drop the oldest).
        if (list.none { it.url == url }) {
            list.add(StreamCandidate(url, mime, System.currentTimeMillis()))
            while (list.size > MAX_PER_HOST) list.removeAt(0)
            _revision.update { it + 1 }
            DebugLog.d(TAG, "captured $mime for $mainFrameHost -> $url")
        }
    }

    /**
     * Detect a playable stream extension. Checks the clean path end first
     * (`master.m3u8?token=…` → still `.m3u8` after stripping the query), then
     * falls back to the extension appearing mid-URL or inside a query param
     * (`/play?file=video.mp4`), which extension-at-end matching would miss —
     * a common reason a video plays in the WebView yet reports "no stream".
     */
    private fun detectMime(url: String): String? {
        val lower = url.lowercase()
        val path = lower.substringBefore('?').substringBefore('#')
        VIDEO_EXTS.firstOrNull { path.endsWith(it.first) }?.let { return it.second }
        VIDEO_EXTS.firstOrNull { (ext, _) ->
            val i = lower.indexOf(ext)
            // Require a non-alphanumeric boundary (or end of string) after the
            // extension so ".mp4a"/".webmanifest" don't false-positive.
            i >= 0 && lower.getOrNull(i + ext.length)?.isLetterOrDigit() != true
        }?.let { return it.second }
        return null
    }

    private fun noteMiss(host: String, url: String) {
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return
        val path = lower.substringBefore('?').substringBefore('#')
        if (IGNORED_EXTS.any { path.endsWith(it) }) return
        // Navigating to another site starts a fresh list rather than growing a
        // map of every host ever visited - only the page in front of the user
        // can be the one they just failed to download.
        if (missHost != host) {
            missHost = host
            misses.clear()
        }
        if (misses.contains(url)) return
        // Drop from the middle, never the head: keep the page's opening moves
        // and the most recent activity, and let the repetitive middle go.
        while (misses.size >= MAX_MISSES) misses.removeAt(KEEP_FIRST)
        misses.add(url)
    }

    /**
     * Write what the page actually fetched to the debug log, for when the user
     * asked to play or download and we had nothing to give them. This is the
     * same measure-first move that finally ended the guessing in the captcha
     * work: the next version can match what is in this list instead of
     * inventing URL patterns and hoping.
     */
    fun dumpMisses(host: String?) {
        val h = host?.takeIf { it.isNotBlank() } ?: return
        val unknown = if (missHost == h) misses.toList() else emptyList()
        DebugLog.w(
            TAG,
            "스트림 못 찾음: $h — 인식된 후보 ${all(h).size}개, 못 알아본 요청 ${unknown.size}개"
        )
        unknown.forEachIndexed { i, u ->
            if (i == KEEP_FIRST && unknown.size >= MAX_MISSES - 1) {
                DebugLog.d(TAG, "  … (중간 생략 — 앞부분과 최근만 남김)")
            }
            DebugLog.d(TAG, "  미인식 요청: $u")
        }
    }

    /** Single best pick for Cast / one-tap launch: newest HLS, else newest overall. */
    fun current(host: String?): StreamCandidate? {
        if (host.isNullOrBlank()) return null
        val list = candidates[host] ?: return null
        return list.filter { it.isHls }.maxByOrNull { it.capturedAt }
            ?: list.maxByOrNull { it.capturedAt }
    }

    /** All distinct streams seen for the host, newest last (capture order). */
    fun all(host: String?): List<StreamCandidate> {
        if (host.isNullOrBlank()) return emptyList()
        return candidates[host]?.toList() ?: emptyList()
    }

    /**
     * Best stream matching a source URL read off a specific <video> element.
     * When the DOM source is a direct media URL we can play it verbatim; when
     * it's a blob:/MSE URL (no usable network URL) callers fall back to current().
     */
    fun matching(host: String?, domSrc: String?): StreamCandidate? {
        val src = domSrc?.trim().orEmpty()
        if (src.isEmpty() || src.startsWith("blob:", ignoreCase = true)) return null
        val scheme = src.substringBefore(':').lowercase()
        if (scheme != "http" && scheme != "https") return null
        // Prefer an already-sniffed candidate (carries the detected mime), else
        // synthesize one straight from the DOM URL.
        all(host).firstOrNull { it.url == src }?.let { return it }
        val mime = detectMime(src) ?: "video/mp4"
        return StreamCandidate(src, mime, System.currentTimeMillis())
    }

    fun clear(host: String?) {
        if (host.isNullOrBlank()) return
        candidates.remove(host)
    }
}
