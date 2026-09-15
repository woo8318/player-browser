@file:OptIn(UnstableApi::class)

package com.playerbrowser.app.ui

import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.playerbrowser.app.R
import com.playerbrowser.app.cast.StreamCandidate
import com.playerbrowser.app.cast.VideoStreamSniffer
import com.playerbrowser.app.data.WatchProgressStore
import com.playerbrowser.app.network.DebugLog
import com.playerbrowser.app.network.ResumeSwitch
import com.playerbrowser.app.player.DownloadCenter
import com.playerbrowser.app.web.InlineRect
import kotlinx.coroutines.delay

/**
 * One in-place replacement of a site's `<video>` by our Media3 player (v1.3.89).
 *
 * [videoId] is the id the page's `__pbInline` module assigned to the element;
 * [candidate] is the sniffed stream we play instead of it. Everything else is
 * the same request context the full-screen [com.playerbrowser.app.player.VideoPlayerActivity]
 * carries (the CDNs check Referer/Cookie/UA), captured once when the session
 * starts so a later navigation cannot swap it under a running player.
 */
data class InlineSession(
    val tabId: String,
    val videoId: String,
    val candidate: StreamCandidate,
    val startPositionSec: Double,
    val pageUrl: String,
    val referer: String?,
    val cookie: String?,
    val userAgent: String?,
    val title: String,
    /** The `Origin` the page's own player sent for this stream, if any (v1.3.98). */
    val origin: String? = null,
    /** 0 = first try with the captured headers, 1 = the one retry after a 401/403/410. */
    val attempt: Int = 0
)

/**
 * Our player drawn exactly over the site's video box. The parent must be a
 * `Box` covering the WebView (clipped), so [rect] — device px relative to the
 * WebView's top-left, as reported by the page — maps 1:1 onto our placement.
 *
 * [rect] is a lambda on purpose: the page streams box updates at frame rate
 * while it scrolls, and reading them in composition would recompose the whole
 * browser screen per frame. The box is read only inside layout lambdas (and
 * two `derivedStateOf`s that flip rarely), so a moving video costs a re-layout
 * of this overlay and nothing else.
 *
 * The control bar (−10s / play-pause / +10s / full screen / ×) sits *below*
 * the picture inside the box, never over it, unless the box is too short to
 * spare 48dp — then it floats translucent over the bottom edge. Buttons, not
 * gestures: the gesture set lives in the full-screen player, and here a
 * double-tap would fight the page underneath.
 *
 * The video surface is a TextureView (`res/layout/inline_player_view.xml`,
 * `surface_type="texture_view"`): a SurfaceView punches through the window
 * and ignores Compose clipping, so a video scrolled half under the toolbar
 * would paint over it. `surface_type` is XML-only on PlayerView, hence the
 * layout resource.
 */
@Composable
fun InlinePlayerOverlay(
    session: InlineSession,
    rect: () -> InlineRect,
    onClose: (positionSec: Double) -> Unit,
    onFullscreen: (positionSec: Double) -> Unit,
    onError: (error: PlaybackException, reachedReady: Boolean) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val player = remember(session) { buildInlinePlayer(context, session) }
    var isPlaying by remember(session) { mutableStateOf(false) }
    var ended by remember(session) { mutableStateOf(false) }
    var positionMs by remember(session) { mutableLongStateOf(0L) }
    var durationMs by remember(session) { mutableLongStateOf(0L) }
    // Once the stream has played, a later 403 is an expired token, not a
    // header mismatch — the caller must not retry from the old start point.
    var reachedReady by remember(session) { mutableStateOf(false) }

    // Progress is keyed by the page URL — the same key the WebView's resume
    // bridge and the full-screen player use, so the three stay in step.
    val saveProgress: () -> Unit = {
        if (ResumeSwitch.enabled) {
            val pos = player.currentPosition / 1000.0
            val dur = if (player.duration > 0) player.duration / 1000.0 else 0.0
            runCatching { WatchProgressStore.get(context).save(session.pageUrl, pos, dur, session.title) }
        }
    }

    DisposableEffect(session) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing) saveProgress()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    ended = true
                    // Watched to the end: nothing to resume next time.
                    runCatching { WatchProgressStore.get(context).delete(session.pageUrl) }
                } else if (state == Player.STATE_READY) {
                    ended = false
                    reachedReady = true
                    durationMs = player.duration.coerceAtLeast(0L)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                DebugLog.e(TAG, "인라인 재생 실패 [${error.errorCodeName}] ${error.message} — ${session.candidate.url}")
                onError(error, reachedReady)
            }
        }
        player.addListener(listener)
        // Backgrounding the app pauses; the site video stays paused+muted
        // underneath us regardless, so there is nothing to hand back here.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                saveProgress()
                player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            saveProgress()
            player.removeListener(listener)
            player.release()
        }
    }

    // Position readout + periodic progress save while playing.
    LaunchedEffect(session) {
        var ticks = 0
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            if (durationMs <= 0L && player.duration > 0) durationMs = player.duration
            if (player.isPlaying && ++ticks % SAVE_EVERY_TICKS == 0) saveProgress()
            delay(TICK_MS)
        }
    }

    val barHeightPx = with(density) { BAR_HEIGHT.roundToPx() }
    val timeMinWidthPx = with(density) { TIME_MIN_WIDTH.roundToPx() }
    // Below the picture when there is room; floating over its bottom when not.
    // Derived so composition happens only when the answer flips, not per px.
    val barOverlaps by remember(barHeightPx) {
        derivedStateOf { rect().height - barHeightPx < MIN_PICTURE_PX }
    }
    val showTime by remember(timeMinWidthPx) {
        derivedStateOf { rect().width >= timeMinWidthPx }
    }

    Box(
        modifier = Modifier
            .layout { measurable, _ ->
                // Read in the layout phase: a rect change re-lays out this node only.
                val r = rect()
                val w = r.width.coerceAtLeast(0)
                val h = r.height.coerceAtLeast(0)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(r.left, r.top) }
            }
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.inline_player_view, null) as PlayerView).apply {
                    useController = false
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    setKeepContentOnPlayerReset(true)
                }
            },
            update = { view -> view.player = player },
            onRelease = { view -> view.player = null },
            modifier = Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val r = rect()
                    val h = (if (barOverlaps) r.height else r.height - barHeightPx).coerceAtLeast(0)
                    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .align(Alignment.TopStart)
                // A tap on the picture toggles playback — the one thing every
                // player does; the rest stays on the buttons.
                .clickable { if (player.isPlaying) player.pause() else resumeOrRestart(player, ended) }
        )
        InlineControlBar(
            isPlaying = isPlaying,
            positionMs = positionMs,
            durationMs = durationMs,
            showTime = showTime,
            translucent = barOverlaps,
            onSeekBack = { player.seekBack() },
            onTogglePlay = { if (player.isPlaying) player.pause() else resumeOrRestart(player, ended) },
            onSeekForward = { player.seekForward() },
            onFullscreen = {
                saveProgress()
                onFullscreen(player.currentPosition / 1000.0)
            },
            onClose = {
                saveProgress()
                onClose(player.currentPosition / 1000.0)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT)
                .align(Alignment.BottomStart)
        )
    }
}

@Composable
private fun InlineControlBar(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    showTime: Boolean,
    translucent: Boolean,
    onSeekBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekForward: () -> Unit,
    onFullscreen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = Color.White
    Row(
        modifier = modifier.background(if (translucent) Color.Black.copy(alpha = 0.55f) else BAR_COLOR),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        IconButton(onClick = onSeekBack) {
            Icon(Icons.Filled.Replay10, contentDescription = "10초 뒤로", tint = tint)
        }
        IconButton(onClick = onTogglePlay) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "일시정지" else "재생",
                tint = tint
            )
        }
        IconButton(onClick = onSeekForward) {
            Icon(Icons.Filled.Forward10, contentDescription = "10초 앞으로", tint = tint)
        }
        if (showTime) {
            Text(
                text = "${formatClock(positionMs)} / ${formatClock(durationMs)}",
                color = tint,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
        IconButton(onClick = onFullscreen) {
            Icon(Icons.Filled.Fullscreen, contentDescription = "전체 화면", tint = tint)
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "사이트 플레이어로 되돌리기", tint = tint)
        }
    }
}

private fun resumeOrRestart(player: ExoPlayer, ended: Boolean) {
    if (ended) player.seekTo(0L)
    player.play()
}

private fun formatClock(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Same chain as `VideoPlayerActivity.buildPlayer` attempt #0: Referer/Cookie/UA
 * on the HTTP source, the download cache read-through in front of it (a
 * downloaded stream plays from disk here too), the download's own MediaItem
 * when one exists (stream keys — see the v1.3.75 memo), and the enlarged
 * buffer. Starts at the page's current position, or the stored resume point.
 */
private fun buildInlinePlayer(context: Context, s: InlineSession): ExoPlayer {
    val headers = HashMap<String, String>()
    s.referer?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
    s.cookie?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
    // Only ever the value the page itself sent — an invented Origin is a
    // stronger mismatch than none.
    s.origin?.takeIf { it.isNotBlank() }?.let { headers["Origin"] = it }
    val httpFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(headers)
    if (!s.userAgent.isNullOrBlank()) httpFactory.setUserAgent(s.userAgent)
    val networkFactory = DefaultDataSource.Factory(context, httpFactory)
    val dataSourceFactory = DownloadCenter.playbackDataSourceFactory(context, networkFactory)

    val url = s.candidate.url
    val item = DownloadCenter.mediaItemFor(context, url)
        ?: MediaItem.Builder().setUri(url).apply {
            mimeTypeFor(s.candidate.mime, url)?.let { setMimeType(it) }
        }.build()

    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(BUFFER_MIN_MS, BUFFER_MAX_MS, BUFFER_FOR_PLAYBACK_MS, BUFFER_AFTER_REBUFFER_MS)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    val startSec = when {
        s.startPositionSec > 0 -> s.startPositionSec
        ResumeSwitch.enabled -> runCatching { WatchProgressStore.get(context).position(s.pageUrl) }.getOrDefault(0.0)
        else -> 0.0
    }
    DebugLog.d(
        TAG,
        "인라인 재생 시작 #${s.attempt} — ${url.take(120)} (mime=${mimeTypeFor(s.candidate.mime, url) ?: "-"}, " +
            "${"%.1f".format(startSec)}초부터, referer=${VideoStreamSniffer.originOf(s.referer) ?: "-"}, origin=${s.origin ?: "-"})"
    )

    return ExoPlayer.Builder(context)
        .setSeekBackIncrementMs(SEEK_MS)
        .setSeekForwardIncrementMs(SEEK_MS)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
        .setLoadControl(loadControl)
        .build()
        .apply {
            setMediaItem(item)
            if (startSec > 0) seekTo((startSec * 1000).toLong())
            playWhenReady = true
            prepare()
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

private const val TAG = "InlinePlayer"
private val BAR_HEIGHT = 48.dp
private val TIME_MIN_WIDTH = 330.dp
private val BAR_COLOR = Color(0xFF1C1C1E)
private const val MIN_PICTURE_PX = 96
private const val TICK_MS = 500L
private const val SAVE_EVERY_TICKS = 10 // 5s
private const val SEEK_MS = 10_000L
private const val BUFFER_MIN_MS = 30_000
private const val BUFFER_MAX_MS = 120_000
private const val BUFFER_FOR_PLAYBACK_MS = 2_500
private const val BUFFER_AFTER_REBUFFER_MS = 5_000
