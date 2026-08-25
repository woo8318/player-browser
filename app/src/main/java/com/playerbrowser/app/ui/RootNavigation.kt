package com.playerbrowser.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.playerbrowser.app.data.TabWebStateStore
import com.playerbrowser.app.network.CookieFlusher

object Routes {
    const val BROWSER = "browser"
    const val BOOKMARKS = "bookmarks"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val DEBUG_LOG = "debug_log"
    const val DOWNLOADS = "downloads"
}

@Composable
fun RootNavigation(viewModel: BrowserViewModel) {
    val navController = rememberNavController()

    // Tab-keyed WebViews live here so they survive navigation to Bookmarks /
    // History / Settings and back. They are destroyed only when the entire
    // composition is disposed (Activity destroy).
    val webStates = remember { mutableStateMapOf<String, BrowserWebViewState>() }
    // Lives next to webStates so captured gallery thumbnails survive navigation
    // to Bookmarks / History / Settings and back.
    val thumbnails = remember { TabThumbnailStore() }
    // Persists each tab's back/forward history so a restart keeps in-tab
    // navigation (Android back steps through visited pages instead of closing
    // a freshly-reloaded tab).
    val context = LocalContext.current
    val tabWebStates = remember { TabWebStateStore(context) }

    // Save every open tab's history whenever the app is backgrounded — the
    // reliable "app is going away" hook before the system may kill the process.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                tabWebStates.saveAll(webStates.mapValues { it.value.webView })
                // Cookies our interceptors planted via CookieManager.setCookie
                // are memory-only until flushed — without this a captcha
                // clearance cookie (cf_clearance …) dies with the process.
                CookieFlusher.flushNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
            // Final flush before tearing the WebViews down.
            tabWebStates.saveAll(webStates.mapValues { it.value.webView })
            webStates.values.forEach { runCatching { it.webView.destroy() } }
            webStates.clear()
        }
    }

    NavHost(navController = navController, startDestination = Routes.BROWSER) {
        composable(Routes.BROWSER) {
            BrowserScreen(
                viewModel = viewModel,
                webStates = webStates,
                thumbnails = thumbnails,
                tabWebStates = tabWebStates,
                onOpenBookmarks = { navController.navigate(Routes.BOOKMARKS) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenDownloads = { navController.navigate(Routes.DOWNLOADS) }
            )
        }
        composable(Routes.BOOKMARKS) {
            BookmarksScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpen = { url ->
                    viewModel.requestLoad(url)
                    navController.popBackStack()
                }
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpen = { url ->
                    viewModel.requestLoad(url)
                    navController.popBackStack()
                }
            )
        }
        composable(Routes.SETTINGS) {
            val settingsViewModel: SettingsViewModel = viewModel()
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onOpenDebugLog = { navController.navigate(Routes.DEBUG_LOG) }
            )
        }
        composable(Routes.DEBUG_LOG) {
            DebugLogScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.DOWNLOADS) {
            DownloadsScreen(onBack = { navController.popBackStack() })
        }
    }
}
