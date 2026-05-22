package com.playerbrowser.app.cast

import android.content.Context
import android.net.Uri
import android.widget.Toast
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.playerbrowser.app.network.DebugLog

/**
 * Bridges the Cast framework's session lifecycle to the sniffed stream URL
 * for the page currently visible in the WebView. When the user picks a
 * receiver from the MediaRouteButton dialog, [SessionManagerListener] fires
 * and we push whatever HLS/MP4 URL the sniffer last captured.
 *
 * Owns no UI — host activity calls [attach]/[detach] in onResume/onPause and
 * provides a callback that yields the current page's (url, title).
 */
class CastSessionBridge(
    private val context: Context,
    private val resolveActiveStream: () -> Pair<String?, String?>
) {
    private val tag = "Cast"
    private val castContext: CastContext? = runCatching {
        CastContext.getSharedInstance(context)
    }.getOrNull()

    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            DebugLog.d(tag, "session started")
            tryLoadCurrent(session)
        }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            DebugLog.d(tag, "session resumed")
            tryLoadCurrent(session)
        }
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            DebugLog.w(tag, "session start failed code=$error")
            Toast.makeText(context, "Cast 연결 실패 (코드 $error)", Toast.LENGTH_SHORT).show()
        }
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    fun attach() {
        castContext?.sessionManager
            ?.addSessionManagerListener(listener, CastSession::class.java)
    }

    fun detach() {
        castContext?.sessionManager
            ?.removeSessionManagerListener(listener, CastSession::class.java)
    }

    private fun tryLoadCurrent(session: CastSession) {
        val (url, title) = resolveActiveStream()
        val host = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
        val candidate = VideoStreamSniffer.current(host)
        if (candidate == null) {
            Toast.makeText(
                context,
                "재생 중인 캐스트 가능한 영상이 없습니다",
                Toast.LENGTH_LONG
            ).show()
            DebugLog.d(tag, "no candidate for host=$host")
            return
        }
        loadOnRemote(session, candidate, title.orEmpty())
    }

    private fun loadOnRemote(
        session: CastSession,
        candidate: StreamCandidate,
        title: String
    ) {
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            if (title.isNotBlank()) putString(MediaMetadata.KEY_TITLE, title)
        }
        val media = MediaInfo.Builder(candidate.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(candidate.mime)
            .setMetadata(metadata)
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(media)
            .setAutoplay(true)
            .build()
        session.remoteMediaClient?.load(request)
        DebugLog.d(tag, "loaded ${candidate.mime} on remote: ${candidate.url}")
    }
}
