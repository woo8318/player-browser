package com.playerbrowser.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.playerbrowser.app.web.UrlUtils

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val isBookmarked by viewModel.isCurrentBookmarked.collectAsState()
    val pendingUrl by viewModel.pendingLoadUrl.collectAsState()
    val updateState by viewModel.updateState.collectAsState()

    val webState = remember {
        buildBrowserWebView(context, object : WebViewCallbacks {
            override fun onStarted(url: String) = viewModel.onPageStarted(url)
            override fun onFinished(url: String, title: String, canGoBack: Boolean, canGoForward: Boolean) =
                viewModel.onPageFinished(url, title, canGoBack, canGoForward)
        })
    }

    LaunchedEffect(Unit) {
        webState.load(BrowserUiState.HOME_URL)
        viewModel.checkForUpdates(silent = true)
    }
    LaunchedEffect(pendingUrl) {
        pendingUrl?.let {
            webState.load(it)
            viewModel.consumePendingLoad()
        }
    }

    BackHandler(enabled = state.canGoBack) { webState.goBack() }

    var urlInput by remember { mutableStateOf(state.currentUrl) }
    LaunchedEffect(state.currentUrl) { urlInput = state.currentUrl }

    val focusManager = LocalFocusManager.current
    var menuOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { if (!webState.goBack()) Unit }, enabled = state.canGoBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "back")
                    }
                    IconButton(onClick = { webState.goForward() }, enabled = state.canGoForward) {
                        Icon(Icons.Filled.ArrowForward, contentDescription = "forward")
                    }
                    IconButton(onClick = { webState.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "reload")
                    }
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            val normalized = UrlUtils.normalize(urlInput)
                            urlInput = normalized
                            webState.load(normalized)
                            focusManager.clearFocus()
                        })
                    )
                    IconButton(onClick = { viewModel.toggleBookmark() }) {
                        Icon(
                            imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            contentDescription = "bookmark"
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "menu")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("즐겨찾기") },
                                onClick = { menuOpen = false; onOpenBookmarks() }
                            )
                            DropdownMenuItem(
                                text = { Text("방문 기록") },
                                onClick = { menuOpen = false; onOpenHistory() }
                            )
                            DropdownMenuItem(
                                text = { Text("홈") },
                                leadingIcon = { Icon(Icons.Filled.Home, contentDescription = null) },
                                onClick = { menuOpen = false; webState.load(BrowserUiState.HOME_URL) }
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
        BrowserWebViewHost(state = webState, modifier = Modifier.fillMaxSize())
    }

    UpdateDialog(
        state = updateState,
        onDownload = { viewModel.startDownload() },
        onInstall = { viewModel.launchInstall() },
        onCancelDownload = { viewModel.cancelDownload() },
        onDismiss = { viewModel.dismissUpdate() }
    )
}
