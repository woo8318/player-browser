package com.playerbrowser.app.cast

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.playerbrowser.app.network.DebugLog
import java.lang.ref.WeakReference

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
        loadOnRemote(context, session, candidate, title.orEmpty())
    }

    companion object {
        private const val TAG = "Cast"

        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        // Every remote load gets a number. Each number is reported at most once
        // and only while it is still the latest load, so the load result and the
        // receiver's error message for one failure give one toast, not two.
        @Volatile private var loadSeq = 0
        @Volatile private var acceptedSeq = -1
        @Volatile private var reportedSeq = -1
        @Volatile private var watched: WeakReference<RemoteMediaClient>? = null

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
            return if (loadOnRemote(context, session, candidate, title)) {
                CastResult.LOADED
            } else {
                CastResult.FAILED
            }
        }

        private fun loadOnRemote(
            context: Context,
            session: CastSession,
            candidate: StreamCandidate,
            title: String
        ): Boolean {
            val client = session.remoteMediaClient ?: run {
                DebugLog.w(TAG, "session has no remoteMediaClient")
                return false
            }
            val app = context.applicationContext
            val seq = ++loadSeq
            watch(app, client)
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
            // The receiver fetches this URL itself, from its own IP, with none of
            // our cookies, Referer or User-Agent. A token- or hotlink-protected
            // CDN will answer it with 403 even though the address is correct -
            // worth knowing when the TV shows an error but the phone plays fine.
            client.load(request).setResultCallback { result ->
                val status = result.status
                when {
                    status.isSuccess -> {
                        acceptedSeq = seq
                        DebugLog.d(TAG, "receiver accepted load #$seq")
                    }
                    // A newer load took over; that one reports for itself.
                    status.statusCode == CastStatusCodes.REPLACED ->
                        DebugLog.d(TAG, "load #$seq replaced by a newer load")
                    else -> {
                        val detail = result.mediaError?.detailedErrorCode ?: -1
                        DebugLog.w(
                            TAG,
                            "receiver rejected load #$seq status=${status.statusCode} " +
                                "detailed=$detail ${status.statusMessage.orEmpty()}"
                        )
                        report(
                            app,
                            seq,
                            "TV 가 재생 요청을 거부했어요 [cast:load:${status.statusCode}:$detail] " +
                                hintOf(detail)
                        )
                    }
                }
            }
            DebugLog.d(TAG, "load #$seq ${candidate.mime} on remote: ${candidate.url}")
            return true
        }

        /**
         * Until v1.3.104 the load was fire-and-forget: whatever the receiver said
         * went nowhere, so "the TV shows nothing" came with no reason attached.
         * Its answer now comes back as a bracket code the user can read out -
         * they cannot send us logs.
         */
        private fun watch(app: Context, client: RemoteMediaClient) {
            if (watched?.get() === client) return
            watched = WeakReference(client)
            client.registerCallback(object : RemoteMediaClient.Callback() {
                override fun onMediaError(mediaError: MediaError) {
                    val code = mediaError.detailedErrorCode ?: -1
                    DebugLog.w(
                        TAG,
                        "receiver media error #$loadSeq type=${mediaError.type} " +
                            "detailed=$code reason=${mediaError.reason}"
                    )
                    // Before the latest load is accepted, an error is either that
                    // load failing - its result callback reports it - or the
                    // previous media being cut off by it (LOAD_INTERRUPTED 904 on
                    // a quick second cast), which must not be pinned on the new
                    // load nor use up its one toast.
                    if (acceptedSeq != loadSeq) return
                    report(app, loadSeq, "TV 에서 재생 실패 [cast:error:$code] " + hintOf(code))
                }

                override fun onStatusUpdated() {
                    // Until the receiver accepts the new load, an IDLE/ERROR status
                    // may still describe the previous media.
                    if (acceptedSeq != loadSeq) return
                    val status = client.mediaStatus ?: return
                    if (status.playerState == MediaStatus.PLAYER_STATE_IDLE &&
                        status.idleReason == MediaStatus.IDLE_REASON_ERROR
                    ) {
                        DebugLog.w(TAG, "receiver went idle with an error #$loadSeq")
                        report(app, loadSeq, "TV 에서 재생이 멈췄어요 [cast:idle:error]")
                    }
                }
            })
        }

        /**
         * Web Receiver detailed error codes, grouped by the stage that failed.
         * A few codes are named first because their range says the wrong thing:
         * 103/104 are what a 403 on a progressive file surfaces as, 316 is a
         * parse failure inside the network range, 905 is the generic load failure.
         */
        private fun hintOf(code: Int): String = when (code) {
            103, 104 -> "(TV 가 영상을 못 받아오거나 못 읽음)"
            316 -> "(영상 조각 해석 실패)"
            905 -> "(TV 가 영상을 불러오지 못함)"
            in 100..199 -> "(TV 가 영상 형식을 못 읽음)"
            in 200..299 -> "(DRM 보호 영상)"
            in 300..399 -> "(TV 가 영상 주소를 못 받아옴)"
            in 400..499 -> "(재생목록 해석 실패)"
            in 500..599 -> "(영상 조각 해석 실패)"
            else -> ""
        }

        private fun report(app: Context, seq: Int, message: String) {
            if (seq != loadSeq || seq == reportedSeq) return
            reportedSeq = seq
            mainHandler.post { Toast.makeText(app, message.trim(), Toast.LENGTH_LONG).show() }
        }
    }
}
