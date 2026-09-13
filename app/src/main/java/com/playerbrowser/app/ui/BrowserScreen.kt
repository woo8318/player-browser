package com.playerbrowser.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import androidx.activity.compose.BackHandler
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.Toast
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.playerbrowser.app.cast.CastResult
import com.playerbrowser.app.cast.CastSessionBridge
import com.playerbrowser.app.cast.StreamCandidate
import com.playerbrowser.app.cast.VideoStreamSniffer
import com.playerbrowser.app.data.TabWebStateStore
import androidx.media3.common.util.UnstableApi
import com.playerbrowser.app.network.ChallengeCookies
import com.playerbrowser.app.network.DebugLog
import com.playerbrowser.app.network.InlinePlayerSwitch
import com.playerbrowser.app.network.ChallengeDetector
import com.playerbrowser.app.network.CookieFlusher
import com.playerbrowser.app.network.UrlRecovery
import com.playerbrowser.app.player.DownloadCenter
import com.playerbrowser.app.player.VideoPlayerActivity
import com.playerbrowser.app.web.InlinePlayerCommands
import com.playerbrowser.app.web.InlineRect
import com.playerbrowser.app.web.UrlUtils
import com.playerbrowser.app.web.VisitedLinkMarker

@OptIn(UnstableApi::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    webStates: SnapshotStateMap<String, BrowserWebViewState>,
    thumbnails: TabThumbnailStore,
    tabWebStates: TabWebStateStore,
    inlinePlayer: InlinePlayerController,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit
) {
    val context = LocalContext.current
    val tabs by viewModel.tabs.collectAsState()
    val activeTabId by viewModel.activeTabId.collectAsState()
    val activeTabState by viewModel.activeTab.collectAsState()
    val state by viewModel.state.collectAsState()
    val isBookmarked by viewModel.isCurrentBookmarked.collectAsState()
    val pendingUrl by viewModel.pendingLoadUrl.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val groups by viewModel.groups.collectAsState()

    // Video long-press (PBPlayer bridge) → DOM source URL; a LaunchedEffect
    // below resolves it to a stream and offers the menu. Lives on
    // inlinePlayer (owned by RootNavigation) rather than a local remember:
    // the WebView callbacks below are created once per tab and outlive this
    // composable, so a local MutableState would be orphaned after
    // Settings → back and later long-presses would go nowhere.

    // Garbage-collect WebViews for tabs that no longer exist, and prune their
    // gallery thumbnails in lock-step.
    LaunchedEffect(tabs) {
        val liveIds = tabs.map { it.id }.toSet()
        val stale = webStates.keys.filterNot { liveIds.contains(it) }
        stale.forEach { id ->
            webStates.remove(id)?.webView?.let { runCatching { it.destroy() } }
        }
        thumbnails.retain(liveIds)
        tabWebStates.retain(liveIds)
    }

    val tabIdForCreation: String = activeTabId
    val activeWebState = webStates.getOrPut(tabIdForCreation) {
        // Capture the tab id by value so this WebView's callbacks always
        // attribute events to its own tab, even after the user switches away.
        val ownerId = tabIdForCreation
        val initialUrl = tabs.firstOrNull { it.id == ownerId }?.currentUrl
            ?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: BrowserUiState.HOME_URL
        buildBrowserWebView(context, object : WebViewCallbacks {
            override fun onStarted(url: String) {
                // New document: the taken <video> is gone with the old page, so
                // drop the overlay without a JS release (nothing to hand back).
                inlinePlayer.dropTab(ownerId)
                viewModel.onPageStarted(ownerId, url)
            }
            override fun onFinished(
                url: String,
                title: String,
                canGoBack: Boolean,
                canGoForward: Boolean
            ) = viewModel.onPageFinished(ownerId, url, title, canGoBack, canGoForward)
            override fun onOpenAppSettings() = onOpenSettings()
            // Inherit ownerId as parentTabId so a back gesture from the popup
            // returns the user to the tab that opened it (Opera-style).
            override fun onOpenInNewTab(url: String) {
                viewModel.newTab(url, parentTabId = ownerId)
            }
            // Link long-press "백그라운드 탭": create the child tab but keep the
            // user on the current page (activate = false).
            override fun onOpenInBackgroundTab(url: String) {
                viewModel.newTab(url, parentTabId = ownerId, activate = false)
            }
            // Video long-press → open THAT video in the external player. Only the
            // active tab is interactable, so resolving against the active page's
            // context below is correct. Marshalled onto the main thread already.
            override fun onPlayVideoExternally(domSrc: String) {
                inlinePlayer.externalPlayRequest = domSrc
            }
            // In-place player (v1.3.89): the page reports a playing <video> and
            // its box; the effect below decides (setting / sniffed stream).
            override fun onInlineVideoPlay(
                id: String, domSrc: String, positionSec: Double, rect: InlineRect, manual: Boolean
            ) {
                if (ownerId == viewModel.activeTabId.value) {
                    inlinePlayer.reportPlay(ownerId, id, domSrc, positionSec, rect, manual)
                }
            }
            override fun onInlineVideoRect(id: String, rect: InlineRect) =
                inlinePlayer.reportRect(ownerId, id, rect)
            override fun onInlineVideoGone(id: String) = inlinePlayer.reportGone(ownerId, id)
        }).also { state ->
            // Restore this tab's saved back/forward history if we have it —
            // restoreState reloads the current entry itself, so only fall back
            // to a plain load when there's no (or an unusable) saved history.
            if (!tabWebStates.restore(ownerId, state.webView)) state.load(initialUrl)
        }
    }

    LaunchedEffect(pendingUrl) {
        pendingUrl?.let {
            activeWebState.load(it)
            viewModel.consumePendingLoad()
        }
    }

    val hasParent = activeTabState.parentTabId?.let { pid -> tabs.any { it.id == pid } } == true
    BackHandler(enabled = state.canGoBack || hasParent) {
        if (state.canGoBack) {
            activeWebState.goBack()
        } else {
            viewModel.tryReturnToParent()
        }
    }

    var urlInput by remember { mutableStateOf(state.currentUrl) }
    LaunchedEffect(state.currentUrl) { urlInput = state.currentUrl }

    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    var tabSwitcherOpen by remember { mutableStateOf(false) }

    // Direction of the next content transition: +1 next, -1 previous, 0 none.
    // Armed only by a swipe so tab close / switcher-select swap instantly
    // (animating a just-closed tab could reference a GC'd WebView).
    var switchDirection by remember { mutableStateOf(0) }
    LaunchedEffect(activeTabId) {
        switchDirection = 0
        // The overlay only ever covers the active tab; give the video back to
        // the tab we left (position unknown → JS keeps its own currentTime).
        // Only when the session really belongs to another tab — this effect
        // also runs on first composition (Settings → back), and ending the
        // session there would drop the overlay for nothing.
        inlinePlayer.session?.let { s ->
            if (s.tabId != activeTabId) {
                inlinePlayer.end()
                runCatching {
                    webStates[s.tabId]?.webView?.evaluateJavascript(
                        InlinePlayerCommands.release(s.videoId, -1.0), null
                    )
                }
            }
        }
        // Coming back from a child/background tab fires no onPageFinished here,
        // yet the page it opened is now in history — refresh the visited marks.
        val webView = activeWebState.webView
        VisitedLinkMarker.apply(webView, webView.url)
        webView.focusPageUnlessTyping()
    }
    // Cold start: the restored tab can finish loading before the history index
    // exists, so its page got nothing — mark it once the index lands.
    val visitedReady by VisitedLinkMarker.ready.collectAsState()
    LaunchedEffect(visitedReady) {
        if (visitedReady) activeWebState.webView.let { VisitedLinkMarker.apply(it, it.url) }
    }

    // Snapshot the tab the user is currently looking at into the gallery cache.
    // Only the active tab's WebView is attached & laid out, so this is the one
    // moment we can reliably capture it — call before navigating away.
    val captureActive: () -> Unit = {
        thumbnails.capture(activeTabId, activeWebState.webView)
    }

    // "No stream" used to be the end of the conversation: the toast says our
    // matcher missed, never what it missed, so every site that failed became a
    // guessing game about URL shapes. Dump the page's unrecognised requests to
    // the debug log first, so the next version can match what is actually there.
    val reportNoStream: (String) -> Unit = { message ->
        VideoStreamSniffer.dumpMisses(
            runCatching { Uri.parse(state.currentUrl).host }.getOrNull()
        )
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    // Launch the built-in Media3 player with a specific stream, injecting the
    // active page's Referer/Cookie/UA so protected CDNs receive the same context
    // as the WebView. Toasts when there's nothing to play.
    val playCandidate: (StreamCandidate?) -> Unit = { candidate ->
        if (candidate == null) {
            reportNoStream("재생할 영상 스트림을 못 찾았어요 (영상을 잠깐 재생 후 다시 시도 · 설정→디버그 로그 확인)")
        } else {
            val pageUrl = state.currentUrl
            val ua = runCatching { activeWebState.webView.settings.userAgentString }.getOrNull()
            val cookie = runCatching {
                CookieManager.getInstance().getCookie(candidate.url)
            }.getOrNull()
            VideoPlayerActivity.start(
                context = context,
                url = candidate.url,
                referer = pageUrl,
                cookie = cookie,
                userAgent = ua,
                mime = candidate.mime,
                title = state.currentTitle
            )
        }
    }

    // Same resolution as playCandidate, but hands the stream to DownloadCenter
    // instead of the player. Downloading is the real fix for "it keeps stalling":
    // once the file is on disk, playback never touches the network again.
    //
    // watchNow starts the player as soon as the download is queued rather than
    // making the user wait for 100%: the player reads through the same cache the
    // download writes, so received segments come off disk and the rest streams.
    // It launches from the callback because that is the point at which the
    // request exists - launching earlier would cost an HLS stream its stream keys.
    val downloadCandidate: (StreamCandidate?, Boolean) -> Unit = { candidate, watchNow ->
        if (candidate == null) {
            reportNoStream("다운로드할 영상 스트림을 못 찾았어요 (영상을 잠깐 재생 후 다시 시도 · 설정→디버그 로그 확인)")
        } else {
            requestNotificationPermission(context)
            val ua = runCatching { activeWebState.webView.settings.userAgentString }.getOrNull()
            DownloadCenter.enqueue(
                context = context,
                url = candidate.url,
                mime = candidate.mime,
                title = state.currentTitle,
                pageUrl = state.currentUrl,
                userAgent = ua
            ) { _, message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                // Watch it either way. A download that could not be queued (or is
                // already queued) is no reason to refuse to play the stream.
                if (watchNow) playCandidate(candidate)
            }
        }
    }

    // Hand the stream to a connected Chromecast. Unlike the player and the
    // downloader, we send an *address*, not the bytes: the receiver fetches it
    // over its own connection with none of our cookies, Referer or UA. That is
    // the structural gap against Opera, which mirrors the rendered tab instead
    // and therefore never needs a URL at all.
    val castCandidate: (StreamCandidate?) -> Unit = { candidate ->
        if (candidate == null) {
            reportNoStream("캐스트할 영상 스트림을 못 찾았어요 (영상을 잠깐 재생 후 다시 시도 · 설정→디버그 로그 확인)")
        } else {
            val message = when (
                CastSessionBridge.castNow(context, candidate, state.currentTitle)
            ) {
                CastResult.LOADED -> "Chromecast로 보냈어요"
                CastResult.NO_SESSION -> "먼저 상단 Cast 버튼으로 기기를 연결해 주세요"
                CastResult.FAILED -> "Chromecast 전송에 실패했어요 (설정→디버그 로그 확인)"
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // ⋮ menu + on-screen FAB: single best pick (HLS preferred) for the page.
    val launchPlayer: () -> Unit = {
        val host = runCatching { Uri.parse(state.currentUrl).host }.getOrNull()
        playCandidate(VideoStreamSniffer.current(host))
    }

    // Video long-press: resolve the long-pressed element's DOM source to a
    // playable stream, then offer a menu rather than auto-launching (a bare
    // long-press used to fire an unwanted — and often broken — player). A direct
    // media URL plays that exact video; a blob:/MSE src (no usable URL) falls
    // back to the page's sniffed network stream. When nothing is resolvable we
    // just toast — long-pressing an unplayable video shouldn't pop a useless menu.
    LaunchedEffect(inlinePlayer.externalPlayRequest) {
        val src = inlinePlayer.externalPlayRequest ?: return@LaunchedEffect
        inlinePlayer.externalPlayRequest = null
        val host = runCatching { Uri.parse(state.currentUrl).host }.getOrNull()
        val candidate = VideoStreamSniffer.matching(host, src)
            ?: VideoStreamSniffer.current(host)
        if (candidate == null) {
            reportNoStream("재생할 영상 스트림을 못 찾았어요 (영상을 잠깐 재생 후 다시 시도 · 설정→디버그 로그 확인)")
        } else {
            showVideoContextMenu(
                context = context,
                candidate = candidate,
                onPlay = { playCandidate(candidate) },
                onInline = {
                    // Ask the page to re-report the long-pressed video as a
                    // manual take; the inline effect below does the rest.
                    activeWebState.webView.evaluateJavascript(InlinePlayerCommands.pressed(), null)
                },
                onDownload = { downloadCandidate(candidate, false) },
                onDownloadAndPlay = { downloadCandidate(candidate, true) },
                onCast = { castCandidate(candidate) }
            )
        }
    }

    // Toolbar / nav-bar swipe → switch to the adjacent tab. A short haptic
    // only fires when the swap actually happens (i.e. not at the strip's edge).
    val switchAdjacentTab: (Boolean) -> Unit = { forward ->
        captureActive()
        if (viewModel.selectAdjacentTab(forward)) {
            switchDirection = if (forward) 1 else -1
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    LaunchedEffect(Unit) { viewModel.checkForUpdates(silent = true) }

    // Reactively surface an on-screen "play in player" button the moment the
    // sniffer captures a stream for the page we're on. Collecting the revision
    // recomputes the lookup whenever a new stream is seen or the page changes.
    val streamRevision by VideoStreamSniffer.revision.collectAsState()
    val hasPlayableStream = remember(streamRevision, state.currentUrl) {
        val host = runCatching { Uri.parse(state.currentUrl).host }.getOrNull()
        VideoStreamSniffer.current(host) != null
    }
    // Let the user hide the button; re-show it on every navigation so a fresh
    // page with a fresh stream gets the affordance again.
    var playerButtonDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(state.currentUrl) { playerButtonDismissed = false }

    // In-place player (v1.3.89). Hand the site's <video> back: clear our
    // overlay and, if asked, tell the page to unmute + seek to where we
    // stopped (-1 = leave its currentTime alone). Stays paused by design.
    val endInline: (Boolean, Double) -> Unit = { release, positionSec ->
        inlinePlayer.end()?.let { s ->
            if (release) {
                runCatching {
                    webStates[s.tabId]?.webView?.evaluateJavascript(
                        InlinePlayerCommands.release(s.videoId, positionSec), null
                    )
                }
            }
        }
    }
    // The controller ends a session by itself when the video box collapses
    // (display:none, 0-height container); it needs a way to release the site
    // video. Re-set every composition so it never points at a stale webStates.
    SideEffect {
        inlinePlayer.releaseHandler = { s ->
            runCatching {
                webStates[s.tabId]?.webView?.evaluateJavascript(
                    InlinePlayerCommands.release(s.videoId, -1.0), null
                )
            }
        }
    }
    // Resolve a "video started playing" report to a sniffed stream and take
    // the video over. Re-runs when a new stream lands (streamRevision) so an
    // auto request reported before the .m3u8 was seen still gets picked up.
    // Auto reports need the setting on; manual (long-press menu) ignores it
    // and toasts when nothing is resolvable instead of waiting silently.
    val inlineRequest = inlinePlayer.request
    LaunchedEffect(inlineRequest, streamRevision) {
        val req = inlineRequest ?: return@LaunchedEffect
        if (req.tabId != activeTabId) { inlinePlayer.consumeRequest(); return@LaunchedEffect }
        if (!req.manual && !InlinePlayerSwitch.enabled) { inlinePlayer.consumeRequest(); return@LaunchedEffect }
        // An automatic request waits for a stream to be sniffed, but not
        // forever: a video that started long ago must not get taken over
        // because some unrelated media request showed up later.
        if (!req.manual && SystemClock.uptimeMillis() - req.createdAt > INLINE_AUTO_DEADLINE_MS) {
            inlinePlayer.consumeRequest(); return@LaunchedEffect
        }
        val host = runCatching { Uri.parse(state.currentUrl).host }.getOrNull()
        val candidate = VideoStreamSniffer.matching(host, req.domSrc)
            ?: VideoStreamSniffer.current(host)
        if (candidate == null) {
            if (req.manual) {
                inlinePlayer.consumeRequest()
                reportNoStream("우리 플레이어로 바꿀 스트림을 못 찾았어요 (영상을 잠깐 재생 후 다시 시도 · 설정→디버그 로그 확인)")
            }
            // Auto: keep the request pending; the next sniffed stream re-runs this.
            return@LaunchedEffect
        }
        if (!req.rect.isUsable) {
            // Too small to host a player (thumbnail / hidden). A manual pick
            // still deserves an answer; auto stays quiet.
            inlinePlayer.consumeRequest()
            if (req.manual) Toast.makeText(context, "영상 영역이 너무 작아 우리 플레이어를 놓을 수 없어요", Toast.LENGTH_SHORT).show()
            return@LaunchedEffect
        }
        // Replacing another taken video on the same page: give that one back first.
        endInline(true, -1.0)
        val ua = runCatching { activeWebState.webView.settings.userAgentString }.getOrNull()
        val cookie = runCatching { CookieManager.getInstance().getCookie(candidate.url) }.getOrNull()
        inlinePlayer.start(
            InlineSession(
                tabId = req.tabId,
                videoId = req.videoId,
                candidate = candidate,
                startPositionSec = req.positionSec,
                pageUrl = state.currentUrl,
                referer = state.currentUrl,
                cookie = cookie,
                userAgent = ua,
                title = state.currentTitle
            ),
            req.rect
        )
        activeWebState.webView.evaluateJavascript(InlinePlayerCommands.take(req.videoId), null)
        DebugLog.d(
            "InlinePlayer",
            "사이트 영상 교체: ${candidate.url.take(80)} pos=${"%.1f".format(req.positionSec)}s " +
                "rect=${req.rect.width}x${req.rect.height}@${req.rect.left},${req.rect.top} manual=${req.manual}"
        )
    }
    val inlineSession = inlinePlayer.session
    val inlineActive = inlineSession != null && inlineSession.tabId == activeTabId

    Column(modifier = Modifier.fillMaxSize()) {
        // Top: URL bar + quick actions (bookmark, cast, menu).
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.tabSwitchSwipe(
                onPrevious = { switchAdjacentTab(false) },
                onNext = { switchAdjacentTab(true) }
            )
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                            // 주소창 입력 중엔 페이지가 포커스를 뺏지 않게 (focusPageUnlessTyping, v1.3.84)
                            .onFocusChanged { AddressBarFocus.typing = it.isFocused },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            val normalized = UrlUtils.normalize(urlInput)
                            urlInput = normalized
                            activeWebState.load(normalized)
                            focusManager.clearFocus()
                            // 주소창 → 페이지로 View 포커스를 넘긴다 (document.hasFocus, v1.3.84).
                            AddressBarFocus.typing = false
                            activeWebState.webView.focusPageUnlessTyping()
                        })
                    )
                    IconButton(onClick = { viewModel.toggleBookmark() }) {
                        Icon(
                            imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            contentDescription = "bookmark"
                        )
                    }
                    CastButton()
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "menu")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("새 탭") },
                                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                                onClick = { menuOpen = false; viewModel.newTab() }
                            )
                            DropdownMenuItem(
                                text = { Text("즐겨찾기") },
                                onClick = { menuOpen = false; onOpenBookmarks() }
                            )
                            DropdownMenuItem(
                                text = { Text("방문 기록") },
                                onClick = { menuOpen = false; onOpenHistory() }
                            )
                            DropdownMenuItem(
                                text = { Text("외부 브라우저로 열기") },
                                leadingIcon = { Icon(Icons.Filled.OpenInBrowser, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    val url = state.currentUrl
                                    if (url.startsWith("http", ignoreCase = true)) {
                                        runCatching {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(intent)
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("순정 WebView로 열기 (진단)") },
                                leadingIcon = { Icon(Icons.Filled.Science, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    val url = state.currentUrl
                                    if (url.startsWith("http", ignoreCase = true)) {
                                        // 앱의 WebViewClient/주입/브리지/원격 디버깅이 전부 없는
                                        // WebView 로 같은 주소를 연다 — 캡차 루프가 우리 코드
                                        // 때문인지 WebView 엔진 때문인지 가르는 측정 (v1.3.85).
                                        runCatching { BareWebViewActivity.start(context, url) }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("주소 복구 (URL 찾기)") },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    val url = state.currentUrl
                                    val wv = activeWebState.webView
                                    if (url.startsWith("http", ignoreCase = true)) {
                                        Toast.makeText(context, "주소 찾는 중…", Toast.LENGTH_SHORT).show()
                                        // 콜백은 백그라운드 스레드 → WebView.post 로 UI 스레드 복귀.
                                        UrlRecovery.findAlternative(url) { found ->
                                            runCatching {
                                                wv.post {
                                                    if (found != null) {
                                                        Toast.makeText(context, "이동: $found", Toast.LENGTH_SHORT).show()
                                                        activeWebState.load(found)
                                                    } else {
                                                        Toast.makeText(
                                                            context,
                                                            "살아있는 주소를 못 찾았어요",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Toast.makeText(context, "복구할 주소가 없어요", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("이 사이트 데이터 지우고 새로고침") },
                                leadingIcon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    val host = runCatching { Uri.parse(state.currentUrl).host }.getOrNull()
                                    if (host.isNullOrBlank()) {
                                        Toast.makeText(context, "지울 사이트가 없어요", Toast.LENGTH_SHORT).show()
                                    } else {
                                        // 쿠키(로그인 세션 포함) + 캐시 + 우리가 그 호스트에
                                        // 대해 기억하던 챌린지 상태를 통째로 지우고 다시 연다.
                                        // 캡차가 무한 반복될 때 "완전히 깨끗한 상태로 한 번"을
                                        // 시도하는 유일한 길 (v1.3.72).
                                        ChallengeCookies.resetSite(host)
                                        ChallengeDetector.forgetHost(host)
                                        CookieFlusher.flushNow()
                                        runCatching { activeWebState.webView.clearCache(true) }
                                        Toast.makeText(context, "$host 데이터 삭제 — 다시 여는 중", Toast.LENGTH_SHORT).show()
                                        activeWebState.load(state.currentUrl)
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("플레이어로 재생") },
                                leadingIcon = { Icon(Icons.Filled.PlayCircle, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    launchPlayer()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Chromecast로 재생") },
                                leadingIcon = { Icon(Icons.Filled.Cast, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    val host =
                                        runCatching { Uri.parse(state.currentUrl).host }.getOrNull()
                                    castCandidate(VideoStreamSniffer.current(host))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("영상 다운로드") },
                                leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    val host = runCatching { Uri.parse(state.currentUrl).host }.getOrNull()
                                    downloadCandidate(VideoStreamSniffer.current(host), false)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("다운로드 목록") },
                                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                                onClick = { menuOpen = false; onOpenDownloads() }
                            )
                            DropdownMenuItem(
                                text = { Text("설정") },
                                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                onClick = { menuOpen = false; onOpenSettings() }
                            )
                            DropdownMenuItem(
                                text = { Text("업데이트 확인") },
                                leadingIcon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null) },
                                onClick = { menuOpen = false; viewModel.checkForUpdates(silent = false) }
                            )
                        }
                    }
                }
                if (state.loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                }
            }
        }
        // Middle: web content fills remaining space between the two bars.
        // Wrapped in a Box so the on-screen player button can float over it.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // AnimatedContent slides the outgoing/incoming tab horizontally when
            // a swipe switched tabs; close/select keep switchDirection == 0 → instant.
            AnimatedContent(
                targetState = activeTabId,
                transitionSpec = {
                    when {
                        switchDirection > 0 ->
                            slideInHorizontally(tween(260)) { it } togetherWith
                                slideOutHorizontally(tween(260)) { -it }
                        switchDirection < 0 ->
                            slideInHorizontally(tween(260)) { -it } togetherWith
                                slideOutHorizontally(tween(260)) { it }
                        else -> EnterTransition.None togetherWith ExitTransition.None
                    }
                },
                modifier = Modifier.fillMaxSize(),
                label = "tab-switch"
            ) { tabId ->
                // Target tab uses the freshly-resolved active state; the outgoing tab
                // is looked up without creating, so a closed (GC'd) tab renders blank
                // instead of being resurrected.
                val hostState = if (tabId == activeTabId) activeWebState else webStates[tabId]
                if (hostState != null) {
                    BrowserWebViewHost(
                        state = hostState,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize())
                }
            }
            // Our player laid exactly over the site's <video> box (v1.3.89).
            // Clipped to the WebView area (the overlay uses a TextureView so
            // the clip actually applies) so a video scrolled half out of view
            // doesn't paint over the toolbars. Keyed on the session so a
            // different video gets a fresh ExoPlayer.
            if (inlineActive && inlineSession != null) {
                Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                    key(inlineSession.tabId, inlineSession.videoId, inlineSession.candidate.url) {
                        InlinePlayerOverlay(
                            session = inlineSession,
                            // Lambda, not a value: the rect is read inside the
                            // overlay's layout phase so a scrolling page does
                            // not recompose this whole screen per frame.
                            rect = { inlinePlayer.rect ?: InlineRect(0, 0, 0, 0) },
                            onClose = { pos -> endInline(true, pos) },
                            onFullscreen = { pos ->
                                VideoPlayerActivity.start(
                                    context = context,
                                    url = inlineSession.candidate.url,
                                    referer = inlineSession.referer,
                                    cookie = inlineSession.cookie,
                                    userAgent = inlineSession.userAgent,
                                    mime = inlineSession.candidate.mime,
                                    title = inlineSession.title,
                                    startPositionSec = pos
                                )
                                endInline(true, pos)
                            },
                            onError = { e ->
                                DebugLog.w("InlinePlayer", "우리 플레이어 재생 실패 — 사이트 플레이어로 복귀: ${e.errorCodeName}", e)
                                Toast.makeText(context, "우리 플레이어로 재생 실패 — 사이트 플레이어로 돌아갑니다", Toast.LENGTH_SHORT).show()
                                endInline(true, -1.0)
                            }
                        )
                    }
                }
            }
            // Floating "play in player" pill — appears the moment a stream is
            // sniffed for this page so the user can jump to the native player
            // without opening the ⋮ menu. Dismissible so it never blocks content.
            // Hidden while our in-place player is showing (it has its own ⛶).
            androidx.compose.animation.AnimatedVisibility(
                visible = hasPlayableStream && !playerButtonDismissed && !inlineActive,
                enter = androidx.compose.animation.fadeIn() +
                    androidx.compose.animation.scaleIn(initialScale = 0.8f),
                exit = androidx.compose.animation.fadeOut() +
                    androidx.compose.animation.scaleOut(targetScale = 0.8f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
            ) {
                PlayerFab(
                    onPlay = launchPlayer,
                    onDismiss = { playerButtonDismissed = true }
                )
            }
        }
        // Bottom: Opera-style navigation bar (back / forward / reload / home / tabs).
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .tabSwitchSwipe(
                        onPrevious = { switchAdjacentTab(false) },
                        onNext = { switchAdjacentTab(true) }
                    )
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (state.canGoBack) activeWebState.goBack()
                        else viewModel.tryReturnToParent()
                    },
                    enabled = state.canGoBack || hasParent
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "back")
                }
                IconButton(
                    onClick = { activeWebState.goForward() },
                    enabled = state.canGoForward
                ) {
                    Icon(Icons.Filled.ArrowForward, contentDescription = "forward")
                }
                IconButton(onClick = { activeWebState.reload() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "reload")
                }
                IconButton(onClick = { activeWebState.load(BrowserUiState.HOME_URL) }) {
                    Icon(Icons.Filled.Home, contentDescription = "home")
                }
                TabCountButton(
                    count = tabs.size,
                    onClick = {
                        // Capture the page being viewed so its card shows a live
                        // thumbnail the instant the gallery opens.
                        captureActive()
                        tabSwitcherOpen = true
                    }
                )
            }
        }
    }

    if (tabSwitcherOpen) {
        TabSwitcherOverlay(
            tabs = tabs,
            groups = groups,
            activeTabId = activeTabId,
            thumbnails = thumbnails,
            onSelect = {
                viewModel.selectTab(it)
                tabSwitcherOpen = false
            },
            onClose = { viewModel.closeTab(it) },
            onCloseMany = { viewModel.closeTabs(it) },
            onMoveToGroup = { tabIds, groupId -> viewModel.setTabsGroup(tabIds, groupId) },
            onMoveTab = { tabId, anchor, after, groupId ->
                viewModel.moveTab(tabId, anchor, after, groupId)
            },
            onAddGroup = { name, color -> viewModel.addGroup(name, color) },
            onRenameGroup = { id, name -> viewModel.renameGroup(id, name) },
            onDeleteGroup = { viewModel.deleteGroup(it) },
            onMoveGroup = { id, up -> viewModel.moveGroup(id, up) },
            onReorderGroup = { id, anchor, after -> viewModel.reorderGroup(id, anchor, after) },
            onNewTab = { groupId ->
                viewModel.newTab(groupId = groupId)
                tabSwitcherOpen = false
            },
            onDismiss = { tabSwitcherOpen = false }
        )
    }

    UpdateDialog(
        state = updateState,
        onDownload = { viewModel.startDownload() },
        onInstall = { viewModel.launchInstall() },
        onCancelDownload = { viewModel.cancelDownload() },
        onDismiss = { viewModel.dismissUpdate() }
    )
}

@Composable
private fun PlayerFab(onPlay: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        onClick = onPlay,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Filled.PlayCircle, contentDescription = null, modifier = Modifier.size(22.dp))
            Text("플레이어로 재생", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "닫기",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun CastButton() {
    // MediaRouteButton needs an AppCompat theme; rather than retrofit the
    // whole app, wrap just this view's context. Setup is wrapped in
    // runCatching so a missing-GMS device still renders the rest of the bar.
    AndroidView(
        modifier = Modifier.size(40.dp),
        factory = { ctx ->
            val themed = ContextThemeWrapper(ctx, androidx.appcompat.R.style.Theme_AppCompat_NoActionBar)
            MediaRouteButton(themed).also { btn ->
                runCatching { CastButtonFactory.setUpMediaRouteButton(themed, btn) }
            }
        }
    )
}

/**
 * Ask for POST_NOTIFICATIONS the first time a download starts (Android 13+).
 * Best-effort and fire-and-forget: the download runs either way, the permission
 * only decides whether its progress notification is visible. Asking here rather
 * than at launch keeps the prompt tied to the feature that needs it.
 */
private fun requestNotificationPermission(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val granted = ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
    if (granted) return
    val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>()
        .firstOrNull() ?: return
    runCatching {
        ActivityCompat.requestPermissions(
            activity, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0
        )
    }
}

/**
 * Long-press-on-video menu. Shown once a stream has been resolved, so the user
 * confirms the external player instead of it launching on a bare long-press.
 * "영상 주소 복사" is a useful escape hatch when the resolved stream won't play.
 */
private fun showVideoContextMenu(
    context: Context,
    candidate: StreamCandidate,
    onPlay: () -> Unit,
    onInline: () -> Unit,
    onDownload: () -> Unit,
    onDownloadAndPlay: () -> Unit,
    onCast: () -> Unit
) {
    val items = arrayOf(
        "외부 플레이어로 재생",
        "여기서 우리 플레이어로 재생",
        "다운로드 (다 받고 보기)",
        "받으면서 바로 보기",
        "Chromecast로 재생",
        "영상 주소 복사"
    )
    androidx.appcompat.app.AlertDialog.Builder(context)
        .setTitle("영상")
        .setItems(items) { _, which ->
            when (which) {
                0 -> onPlay()
                1 -> onInline()
                2 -> onDownload()
                3 -> onDownloadAndPlay()
                4 -> onCast()
                5 -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText("video", candidate.url)
                    )
                    Toast.makeText(context, "영상 주소를 복사했어요", Toast.LENGTH_SHORT).show()
                }
            }
        }
        .setNegativeButton("취소", null)
        .show()
}

@Composable
private fun TabCountButton(count: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (count > 99) "99" else count.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// How long an automatic in-place request may wait for a stream to be sniffed.
private const val INLINE_AUTO_DEADLINE_MS = 30_000L
