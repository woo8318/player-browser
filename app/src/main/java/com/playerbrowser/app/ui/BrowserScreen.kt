package com.playerbrowser.app.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
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
import com.playerbrowser.app.network.UrlRecovery
import com.playerbrowser.app.web.UrlUtils

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    webStates: SnapshotStateMap<String, BrowserWebViewState>,
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

    // Garbage-collect WebViews for tabs that no longer exist.
    LaunchedEffect(tabs) {
        val liveIds = tabs.map { it.id }.toSet()
        val stale = webStates.keys.filterNot { liveIds.contains(it) }
        stale.forEach { id ->
            webStates.remove(id)?.webView?.let { runCatching { it.destroy() } }
        }
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
        }).also { it.load(initialUrl) }
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

    // Toolbar / nav-bar swipe → switch to the adjacent tab. A short haptic
    // only fires when the swap actually happens (i.e. not at the strip's edge).
    val switchAdjacentTab: (Boolean) -> Unit = { forward ->
        if (viewModel.selectAdjacentTab(forward)) {
            switchDirection = if (forward) 1 else -1
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    LaunchedEffect(Unit) { viewModel.checkForUpdates(silent = true) }

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
        // AnimatedContent slides the outgoing/incoming tab horizontally when a
        // swipe switched tabs; close/select keep switchDirection == 0 → instant.
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
            modifier = Modifier.weight(1f).fillMaxWidth(),
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
                    onClick = { tabSwitcherOpen = true }
                )
            }
        }
    }

    if (tabSwitcherOpen) {
        TabSwitcherOverlay(
            tabs = tabs,
            groups = groups,
            activeTabId = activeTabId,
            onSelect = {
                viewModel.selectTab(it)
                tabSwitcherOpen = false
            },
            onClose = { viewModel.closeTab(it) },
            onCloseMany = { viewModel.closeTabs(it) },
            onMoveToGroup = { tabIds, groupId -> viewModel.setTabsGroup(tabIds, groupId) },
            onAddGroup = { name, color -> viewModel.addGroup(name, color) },
            onRenameGroup = { id, name -> viewModel.renameGroup(id, name) },
            onDeleteGroup = { viewModel.deleteGroup(it) },
            onMoveGroup = { id, up -> viewModel.moveGroup(id, up) },
            onNewTab = {
                viewModel.newTab()
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
