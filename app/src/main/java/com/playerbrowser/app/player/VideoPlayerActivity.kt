package com.playerbrowser.app.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.GestureDetector
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.playerbrowser.app.data.WatchProgressStore
import com.playerbrowser.app.network.DebugLog
import com.playerbrowser.app.network.ResumeSwitch
import kotlin.math.abs

/**
 * In-app native video player (Media3 / ExoPlayer). The browser extracts a direct
 * stream URL ([com.playerbrowser.app.cast.VideoStreamSniffer]) and hands it here
 * with the page's Referer / Cookie / User-Agent so protected CDNs still serve it.
 * Playing the stream natively sidesteps the whole class of fullscreen gesture vs.
 * site-overlay conflicts the WebView player suffers from — the player owns the
 * surface, so play/pause, the scrubber, double-tap ±10s seek and brightness /
 * volume drags all behave predictably regardless of the originating site.
 *
 * Limitation: blob: / MSE / DRM / tokenized streams can't be extracted to a plain
 * URL — those keep playing in the WebView. The browser only offers this player
 * when a concrete .m3u8 / .mp4 / .webm was sniffed for the page.
 */
@UnstableApi
class VideoPlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var feedback: TextView

    private var resumeKey: String? = null
    private var videoTitle: String = ""
    private var appliedOrientation = false

    private val audioManager: AudioManager? by lazy {
        getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    private val maxVolume: Int by lazy {
        audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideFeedback = Runnable { feedback.visibility = View.GONE }
    private val vbThresholdPx: Float by lazy { 24f * resources.displayMetrics.density }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            DebugLog.e(TAG, "no stream URL — finishing")
            finish()
            return
        }
        val referer = intent.getStringExtra(EXTRA_REFERER)
        val cookie = intent.getStringExtra(EXTRA_COOKIE)
        val ua = intent.getStringExtra(EXTRA_UA)
        val mime = intent.getStringExtra(EXTRA_MIME)
        videoTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        resumeKey = referer?.takeIf { it.startsWith("http", ignoreCase = true) }

        enterImmersive()

        // Root: PlayerView (controls) + a centered feedback label on top.
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        playerView = PlayerView(this).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShowRewindButton(true)
            setShowFastForwardButton(true)
            setControllerShowTimeoutMs(3000)
            setBackgroundColor(Color.BLACK)
        }
        root.addView(playerView)

        feedback = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xB3000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        root.addView(
            feedback,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)
        )
        setContentView(root)

        attachGestures()
        buildPlayer(url, referer, cookie, ua, mime)
    }

    private fun buildPlayer(
        url: String,
        referer: String?,
        cookie: String?,
        ua: String?,
        mime: String?,
        attempt: Int = 0
    ) {
        // Attempt 0 is the real one. 1 and 2 are the rungs taken after a failure
        // (see onPlayerError), each dropping exactly one thing so that whichever
        // one plays names the culprit by itself.
        val useDownload = attempt == 0
        val useResume = attempt < 2
        if (attempt > 0) {
            DebugLog.d(
                TAG,
                "재생 시도 #$attempt — " +
                    (if (useResume) "스트리밍(캐시 우회)" else "스트리밍(캐시 우회, 처음부터)")
            )
        }

        val headers = HashMap<String, String>()
        referer?.let { headers["Referer"] = it }
        cookie?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)
        if (!ua.isNullOrBlank()) httpFactory.setUserAgent(ua)

        // Read through the download cache first: if this stream was downloaded
        // ahead of time, every byte comes off disk and the network never enters
        // the picture — which is the whole point of downloading it. Anything not
        // downloaded falls straight through to the network factory below.
        //
        // A retry must skip the cache entirely, not just drop the download's
        // MediaItem. CacheKeyFactory.DEFAULT keys by URI, so leaving the
        // CacheDataSource in the chain would serve the very same cached bytes
        // again and the retry would prove nothing at all.
        val networkFactory = DefaultDataSource.Factory(this, httpFactory)
        val dataSourceFactory: DataSource.Factory =
            if (useDownload) DownloadCenter.playbackDataSourceFactory(this, networkFactory)
            else networkFactory

        // A download that is still running is perfectly playable: the cache serves
        // whatever has landed and the rest streams as usual, so there is no reason
        // to make the user wait for 100%.
        val downloadItem = if (useDownload) DownloadCenter.mediaItemFor(this, url) else null
        if (downloadItem != null) {
            // The numbers matter more than the label here - see DownloadCenter.describe.
            DebugLog.d(TAG, "다운로드본 재생 — ${DownloadCenter.describe(this, url)}")
        } else {
            DebugLog.d(
                TAG,
                "캐시 우회 스트리밍 (mime=${mimeTypeFor(mime, url) ?: "-"}) — " +
                    DownloadCenter.describe(this, url)
            )
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        // Buffer far more aggressively than the Media3 default (50s cap, capped
        // again by a byte threshold). These streams stall on weak mobile links,
        // and holding a couple of minutes ahead rides out the dropouts. Time is
        // prioritized over the size threshold so a big time buffer isn't cut
        // short by the default target-buffer-bytes limit.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                BUFFER_MIN_MS,
                BUFFER_MAX_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_AFTER_REBUFFER_MS
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(SEEK_MS)
            .setSeekForwardIncrementMs(SEEK_MS)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()

        // Replay the download's own request when there is one: it carries the
        // stream keys, so HLS plays the rendition that was downloaded instead of
        // letting adaptive selection pick a bitrate whose segments aren't cached.
        val item = downloadItem ?: MediaItem.Builder()
            .setUri(url)
            .apply {
                mimeTypeFor(mime, url)?.let { setMimeType(it) }
            }
            .build()

        exo.setMediaItem(item)
        exo.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                applyOrientation(videoSize)
            }

            override fun onPlaybackStateChanged(state: Int) {
                // Logged because a player that opens but never starts is otherwise
                // completely silent: "still buffering" and "failed" look identical
                // from the outside, and they share no cause at all.
                val name = when (state) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> state.toString()
                }
                DebugLog.d(TAG, "재생 상태: $name")
                if (state == Player.STATE_ENDED) {
                    resumeKey?.let { WatchProgressStore.get(this@VideoPlayerActivity).delete(it) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Without this a failure is a black screen with working controls and
                // nothing in the log - which is exactly what "the player opens but
                // playback never starts" looks like from the outside.
                val chain = StringBuilder()
                var cause: Throwable? = error.cause
                var depth = 0
                while (cause != null && depth < 3) {
                    chain.append(" / ${cause.javaClass.simpleName}: ${cause.message}")
                    cause = cause.cause
                    depth++
                }
                DebugLog.e(
                    TAG,
                    "재생 실패 #$attempt [${error.errorCodeName}] ${error.message}$chain"
                )
                DebugLog.e(
                    TAG,
                    "재생 대상: " + DownloadCenter.describe(this@VideoPlayerActivity, url)
                )

                // Bisect instead of giving up. Each rung changes exactly one thing,
                // so whichever one plays names the culprit on its own:
                //   0 → 1  drops the downloaded bytes AND the cache      (bad cache?)
                //   1 → 2  drops the resume position                     (bad offset?)
                // The user gets their video out of it either way, which is the
                // only reason to run the ladder live rather than in a log.
                if (attempt < LAST_ATTEMPT) {
                    val next = attempt + 1
                    val note = if (next == 1) {
                        "다운로드본 재생 실패 — 스트리밍으로 다시 시도합니다"
                    } else {
                        "이어보기 위치에서 실패 — 처음부터 다시 시도합니다"
                    }
                    DebugLog.d(TAG, "$note (시도 #$next)")
                    showFeedback(note)
                    // Long enough to actually read, unlike the 600ms gesture hint.
                    mainHandler.postDelayed(hideFeedback, 3_000)
                    mainHandler.post {
                        playerView.player = null
                        player?.release()
                        player = null
                        buildPlayer(url, referer, cookie, ua, mime, next)
                    }
                    return
                }
                showFeedback(
                    "재생 실패 (${error.errorCodeName})\n설정 → 디버그 로그를 확인해 주세요"
                )
            }
        })

        // Resume from the same position the WebView "이어보기" recorded for this
        // page (shared key = page URL), so switching to the native player keeps
        // your spot. Honors the global resume toggle.
        val key = resumeKey
        if (useResume && ResumeSwitch.enabled && key != null) {
            val savedSec = WatchProgressStore.get(this).position(key)
            if (savedSec > 0) {
                DebugLog.d(TAG, "이어보기 ${savedSec}초부터 시작")
                exo.seekTo((savedSec * 1000).toLong())
            }
        }

        exo.playWhenReady = true
        exo.prepare()
        player = exo
        playerView.player = exo
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures() {
        val overlay = playerView.overlayFrameLayout ?: return
        val gestureView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        val detector = GestureDetector(this, GestureListener())
        var startBrightness = 0f
        var startVolume = 0
        var vbMode = 0 // 0 none, 1 brightness, 2 volume
        gestureView.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    vbMode = 0
                    // Capture the gesture start here so the MOVE math never depends
                    // on GestureListener.onDown having fired first.
                    gestureDownX = ev.x
                    gestureDownY = ev.y
                    startBrightness = currentBrightnessRatio()
                    startVolume = currentVolumeIndex()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (vbMode != 0) {
                        vbMode = 0
                        scheduleFeedbackHide()
                    }
                }
            }
            // Brightness / volume vertical drag is tracked here (GestureDetector
            // onScroll is fine too, but we keep the per-gesture start values).
            if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
                val dx = ev.x - gestureDownX
                val dy = ev.y - gestureDownY
                if (vbMode == 0 && abs(dy) > vbThresholdPx && abs(dy) > abs(dx)) {
                    vbMode = if (ev.x < v.width / 2f) 1 else 2
                }
                if (vbMode != 0) {
                    val ratio = -dy / v.height.coerceAtLeast(1)
                    if (vbMode == 1) {
                        val nb = (startBrightness + ratio).coerceIn(0f, 1f)
                        applyBrightness(nb)
                        showFeedback("밝기 ${(nb * 100).toInt()}%")
                    } else {
                        val mv = maxVolume
                        if (mv > 0) {
                            val nv = (startVolume + ratio * mv).coerceIn(0f, mv.toFloat())
                            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, nv.toInt(), 0)
                            showFeedback("볼륨 ${((nv / mv) * 100).toInt()}%")
                        }
                    }
                    return@setOnTouchListener true
                }
            }
            detector.onTouchEvent(ev)
            true
        }
        overlay.addView(gestureView)
    }

    private var gestureDownX = 0f
    private var gestureDownY = 0f

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            gestureDownX = e.x
            gestureDownY = e.y
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // Toggle the player controls (the controller intercepts touches when
            // visible, so this branch only runs while it's hidden).
            playerView.showController()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val p = player ?: return false
            val w = playerView.width.coerceAtLeast(1)
            val rel = e.x / w
            when {
                rel < 0.4f -> {
                    p.seekTo((p.currentPosition - SEEK_MS).coerceAtLeast(0))
                    showFeedback("⏪ 10초")
                    scheduleFeedbackHide()
                }
                rel > 0.6f -> {
                    p.seekTo(p.currentPosition + SEEK_MS)
                    showFeedback("⏩ 10초")
                    scheduleFeedbackHide()
                }
                else -> {
                    if (p.isPlaying) p.pause() else p.play()
                }
            }
            return true
        }
    }

    private fun mimeTypeFor(mime: String?, url: String): String? {
        val m = mime?.lowercase().orEmpty()
        val u = url.lowercase().substringBefore('?').substringBefore('#')
        return when {
            m.contains("mpegurl") || u.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            m.contains("mp4") || u.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
            m.contains("webm") || u.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            else -> null
        }
    }

    private fun applyOrientation(size: VideoSize) {
        if (appliedOrientation) return
        if (size.width <= 0 || size.height <= 0) return
        appliedOrientation = true
        requestedOrientation = if (size.width >= size.height) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    private fun showFeedback(text: String) {
        mainHandler.removeCallbacks(hideFeedback)
        feedback.text = text
        feedback.visibility = View.VISIBLE
    }

    private fun scheduleFeedbackHide() {
        mainHandler.removeCallbacks(hideFeedback)
        mainHandler.postDelayed(hideFeedback, 600)
    }

    private fun currentVolumeIndex(): Int =
        audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0

    private fun currentBrightnessRatio(): Float {
        val current = window.attributes.screenBrightness
        if (current in 0f..1f) return current
        return runCatching {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f).coerceIn(0f, 1f)
    }

    private fun applyBrightness(ratio: Float) {
        val lp = window.attributes
        lp.screenBrightness = ratio
        window.attributes = lp
    }

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun saveProgress() {
        val key = resumeKey ?: return
        val p = player ?: return
        if (!ResumeSwitch.enabled) return
        val pos = p.currentPosition / 1000.0
        val dur = if (p.duration > 0) p.duration / 1000.0 else 0.0
        WatchProgressStore.get(this).save(key, pos, dur, videoTitle)
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(hideFeedback)
        player?.release()
        player = null
    }

    companion object {
        private const val TAG = "VideoPlayer"
        private const val SEEK_MS = 10_000L

        // Rungs of the retry ladder in onPlayerError; see the comment there.
        private const val LAST_ATTEMPT = 2

        // Buffer targets, tuned well past the Media3 defaults (50s/50s/2.5s/5s)
        // to ride out mobile dropouts. minBufferMs must be >= the rebuffer
        // threshold and maxBufferMs >= minBufferMs, or the builder throws.
        private const val BUFFER_MIN_MS = 30_000
        private const val BUFFER_MAX_MS = 120_000
        private const val BUFFER_FOR_PLAYBACK_MS = 2_500
        private const val BUFFER_AFTER_REBUFFER_MS = 5_000
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private const val EXTRA_URL = "url"
        private const val EXTRA_REFERER = "referer"
        private const val EXTRA_COOKIE = "cookie"
        private const val EXTRA_UA = "ua"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_TITLE = "title"

        fun start(
            context: Context,
            url: String,
            referer: String?,
            cookie: String?,
            userAgent: String?,
            mime: String?,
            title: String?
        ) {
            val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_REFERER, referer)
                putExtra(EXTRA_COOKIE, cookie)
                putExtra(EXTRA_UA, userAgent)
                putExtra(EXTRA_MIME, mime)
                putExtra(EXTRA_TITLE, title)
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
