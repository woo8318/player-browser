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

/** Outcome of an explicit "send this stream to the receiver now" request. */
enum class CastResult { LOADED, NO_SESSION, FAILED }

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
            // Connecting is almost always the *first* thing the user does, before
            // the page has played anything - so the sniffer is usually empty right
            // here. Say what to do next instead of just reporting the emptiness.
            Toast.makeText(
                context,
                "아직 캐스트할 영상을 못 찾았어요 — 영상을 잠깐 재생한 뒤 ⋮ 메뉴 → Chromecast로 재생",
                Toast.LENGTH_LONG
            ).show()
            DebugLog.d(tag, "no candidate for host=$host")
            return
        }
        loadOnRemote(session, candidate, title.orEmpty())
    }

    companion object {
        private const val TAG = "Cast"

        /**
         * Push a stream to an already-connected receiver.
         *
         * Without this there is no verb at all: the session listener only fires
         * when a session *starts*, so the one castable instant was the moment of
         * connecting - before any video had played, when the sniffer holds
         * nothing. Play the video afterwards and nothing ever re-triggers a load,
         * which is exactly how cast came to look permanently broken.
         */
        fun castNow(
            context: Context,
            candidate: StreamCandidate,
            title: String
        ): CastResult {
            val session = runCatching {
                CastContext.getSharedInstance(context).sessionManager.currentCastSession
            }.getOrNull()
            if (session == null || !session.isConnected) {
                DebugLog.d(TAG, "cast requested but no connected session")
                return CastResult.NO_SESSION
            }
            return if (loadOnRemote(session, candidate, title)) {
                CastResult.LOADED
            } else {
                CastResult.FAILED
            }
        }

        private fun loadOnRemote(
            session: CastSession,
            candidate: StreamCandidate,
            title: String
        ): Boolean {
            val client = session.remoteMediaClient ?: run {
                DebugLog.w(TAG, "session has no remoteMediaClient")
                return false
            }
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
            client.load(request)
            // The receiver fetches this URL itself, from its own IP, with none of
            // our cookies, Referer or User-Agent. A token- or hotlink-protected
            // CDN will answer it with 403 even though the address is correct -
            // worth knowing when the TV shows an error but the phone plays fine.
            DebugLog.d(TAG, "loaded ${candidate.mime} on remote: ${candidate.url}")
            return true
        }
    }
}
