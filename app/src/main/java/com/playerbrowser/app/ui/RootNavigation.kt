package com.playerbrowser.app.ui

import androidx.compose.runtime.Composable
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
    NavHost(navController = navController, startDestination = Routes.BROWSER) {
        composable(Routes.BROWSER) {
            BrowserScreen(
                viewModel = viewModel,
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
