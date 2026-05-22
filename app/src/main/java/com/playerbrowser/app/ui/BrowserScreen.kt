package com.playerbrowser.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
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
    val state by viewModel.state.collectAsState()
    val isBookmarked by viewModel.isCurrentBookmarked.collectAsState()
    val pendingUrl by viewModel.pendingLoadUrl.collectAsState()
    val updateState by viewModel.updateState.collectAsState()

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
        }).also { it.load(initialUrl) }
    }

    LaunchedEffect(pendingUrl) {
        pendingUrl?.let {
            activeWebState.load(it)
            viewModel.consumePendingLoad()
        }
    }

    BackHandler(enabled = state.canGoBack) { activeWebState.goBack() }

    var urlInput by remember { mutableStateOf(state.currentUrl) }
    LaunchedEffect(state.currentUrl) { urlInput = state.currentUrl }

    val focusManager = LocalFocusManager.current
    var menuOpen by remember { mutableStateOf(false) }
    var tabSwitcherOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.checkForUpdates(silent = true) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top: URL bar + quick actions (bookmark, cast, menu).
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
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
        BrowserWebViewHost(
            state = activeWebState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        // Bottom: Opera-style navigation bar (back / forward / reload / home / tabs).
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (!activeWebState.goBack()) Unit },
                    enabled = state.canGoBack
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
            activeTabId = activeTabId,
            onSelect = {
                viewModel.selectTab(it)
                tabSwitcherOpen = false
            },
            onClose = { viewModel.closeTab(it) },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabSwitcherOverlay(
    tabs: List<TabState>,
    activeTabId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(enabled = true) { onDismiss() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("탭 ${tabs.size}") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "close switcher")
                    }
                },
                actions = {
                    IconButton(onClick = onNewTab) {
                        Icon(Icons.Filled.Add, contentDescription = "new tab")
                    }
                }
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items = tabs, key = { it.id }) { tab ->
                TabCard(
                    tab = tab,
                    isActive = tab.id == activeTabId,
                    onSelect = { onSelect(tab.id) },
                    onClose = { onClose(tab.id) }
                )
            }
        }
    }
}

@Composable
private fun TabCard(
    tab: TabState,
    isActive: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    val border = if (isActive) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant
    Surface(
        shape = RoundedCornerShape(10.dp),
        tonalElevation = if (isActive) 4.dp else 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .border(width = if (isActive) 2.dp else 1.dp, color = border, shape = RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tab.currentTitle.ifBlank { "새 탭" },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "close tab",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                contentAlignment = Alignment.TopStart
            ) {
                Text(
                    text = tab.currentUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5
                )
            }
        }
    }
}
