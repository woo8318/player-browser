package com.playerbrowser.app.ui

import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import androidx.activity.compose.BackHandler
import androidx.appcompat.view.ContextThemeWrapper
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.playerbrowser.app.cast.StreamCandidate
import com.playerbrowser.app.cast.VideoStreamSniffer
import com.playerbrowser.app.data.TabWebStateStore
import com.playerbrowser.app.network.UrlRecovery
import com.playerbrowser.app.player.VideoPlayerActivity
import com.playerbrowser.app.web.UrlUtils

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    webStates: SnapshotStateMap<String, BrowserWebViewState>,
    thumbnails: TabThumbnailStore,
    tabWebStates: TabWebStateStore,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit
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

    // Set by a long-press on a <video> (via the PBPlayer JS bridge) to the
    // element's DOM source URL; a LaunchedEffect below resolves it to a stream
    // and opens the external player. Reset to null after handling so a repeat
    // long-press on the same video re-triggers.
    var externalPlayRequest by remember { mutableStateOf<String?>(null) }

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
            override fun onStarted(url: String) = viewModel.onPageStarted(ownerId, url)
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
                externalPlayRequest = domSrc
            }
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
    LaunchedEffect(activeTabId) { switchDirection = 0 }

    // Snapshot the tab the user is currently looking at into the gallery cache.
    // Only the active tab's WebView is attached & laid out, so this is the one
    // moment we can reliably capture it — call before navigating away.
    val captureActive: () -> Unit = {
        thumbnails.capture(activeTabId, activeWebState.webView)
    }

    // Launch the built-in Media3 player with a specific stream, injecting the
    // active page's Referer/Cookie/UA so protected CDNs receive the same context
    // as the WebView. Toasts when there's nothing to play.
    val playCandidate: (StreamCandidate?) -> Unit = { candidate ->
        if (candidate == null) {
            Toast.makeText(
                context,
                "재생할 영상 스트림을 못 찾았어요 (영상을 잠깐 재생해 보세요)",
                Toast.LENGTH_SHORT
            ).show()
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

    // ⋮ menu + on-screen FAB: single best pick (HLS preferred) for the page.
    val launchPlayer: () -> Unit = {
        val host = runCatching { Uri.parse(state.currentUrl).host }.getOrNull()
        playCandidate(VideoStreamSniffer.current(host))
    }

    // Video long-press: resolve the long-pressed element's DOM source to a
    // playable stream. A direct media URL plays that exact video; a blob:/MSE
    // src (no usable URL) falls back to the page's sniffed network stream.
    LaunchedEffect(externalPlayRequest) {
        val src = externalPlayRequest ?: return@LaunchedEffect
        val host = runCatching { Uri.parse(state.currentUrl).host }.getOrNull()
        val candidate = VideoStreamSniffer.matching(host, src)
            ?: VideoStreamSniffer.current(host)
        playCandidate(candidate)
        externalPlayRequest = null
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
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            val normalized = UrlUtils.normalize(urlInput)
                            urlInput = normalized
                            activeWebState.load(normalized)
                            focusManager.clearFocus()
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
                                text = { Text("플레이어로 재생") },
                                leadingIcon = { Icon(Icons.Filled.PlayCircle, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    launchPlayer()
                                }
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
            // Floating "play in player" pill — appears the moment a stream is
            // sniffed for this page so the user can jump to the native player
            // without opening the ⋮ menu. Dismissible so it never blocks content.
            androidx.compose.animation.AnimatedVisibility(
                visible = hasPlayableStream && !playerButtonDismissed,
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
