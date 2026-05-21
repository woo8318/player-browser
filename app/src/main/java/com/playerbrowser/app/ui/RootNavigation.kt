package com.playerbrowser.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

object Routes {
    const val BROWSER = "browser"
    const val BOOKMARKS = "bookmarks"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
}

@Composable
fun RootNavigation(viewModel: BrowserViewModel) {
    val navController = rememberNavController()

    // Tab-keyed WebViews live here so they survive navigation to Bookmarks /
    // History / Settings and back. They are destroyed only when the entire
    // composition is disposed (Activity destroy).
    val webStates = remember { mutableStateMapOf<String, BrowserWebViewState>() }
    DisposableEffect(Unit) {
        onDispose {
            webStates.values.forEach { runCatching { it.webView.destroy() } }
            webStates.clear()
        }
    }

    NavHost(navController = navController, startDestination = Routes.BROWSER) {
        composable(Routes.BROWSER) {
            BrowserScreen(
                viewModel = viewModel,
                webStates = webStates,
                onOpenBookmarks = { navController.navigate(Routes.BOOKMARKS) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
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
                onBack = { navController.popBackStack() }
            )
        }
    }
}
