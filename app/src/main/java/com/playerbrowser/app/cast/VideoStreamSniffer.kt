package com.playerbrowser.app.cast

import android.webkit.WebResourceRequest
import com.playerbrowser.app.network.DebugLog
import java.util.concurrent.ConcurrentHashMap

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
 * castable video URL per main-frame host so the Chromecast button can pick
 * it up without the user having to copy a URL manually.
 *
 * Prefers HLS over progressive MP4 when both are seen for the same host —
 * HLS adapts quality and resumes faster on the receiver. Falls back to
 * most-recent within the same priority tier.
 */
object VideoStreamSniffer {

    private const val TAG = "Cast"
    private val candidates = ConcurrentHashMap<String, StreamCandidate>()

    fun observe(request: WebResourceRequest, mainFrameHost: String?) {
        if (mainFrameHost.isNullOrBlank()) return
        val method = request.method?.uppercase() ?: "GET"
        if (method != "GET") return
        val url = request.url?.toString() ?: return
        val lower = url.lowercase().substringBefore('?').substringBefore('#')
        val mime = when {
            lower.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".webm") -> "video/webm"
            else -> return
        }
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
            DebugLog.d(TAG, "captured $mime for $mainFrameHost -> $url")
        }
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
