package com.playerbrowser.app.cast

import android.webkit.WebResourceRequest
import com.playerbrowser.app.network.DebugLog
import java.util.concurrent.ConcurrentHashMap
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
 * Observes WebView subresource requests and remembers the most recent
 * castable video URL per main-frame host so the Chromecast button (and the
 * on-screen "play in player" affordance) can pick it up without the user
 * having to copy a URL manually.
 *
 * Prefers HLS over progressive MP4 when both are seen for the same host —
 * HLS adapts quality and resumes faster on the receiver. Falls back to
 * most-recent within the same priority tier.
 */
object VideoStreamSniffer {

    private const val TAG = "Cast"
    private val candidates = ConcurrentHashMap<String, StreamCandidate>()

    // Bumped whenever a candidate is stored. Compose observers collect this to
    // reactively show/refresh the on-screen player button the moment a stream
    // is captured (writes happen on the WebView's background thread, so a
    // thread-safe StateFlow rather than a plain snapshot state).
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

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
        val mime = detectMime(url) ?: return
        val candidate = StreamCandidate(url, mime, System.currentTimeMillis())
        val existing = candidates[mainFrameHost]
        val replace = when {
            existing == null -> true
            candidate.isHls && !existing.isHls -> true
            !candidate.isHls && existing.isHls -> false
            else -> true
        }
        if (replace) {
            candidates[mainFrameHost] = candidate
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

    fun current(host: String?): StreamCandidate? {
        if (host.isNullOrBlank()) return null
        return candidates[host]
    }

    fun clear(host: String?) {
        if (host.isNullOrBlank()) return
        candidates.remove(host)
    }
}
