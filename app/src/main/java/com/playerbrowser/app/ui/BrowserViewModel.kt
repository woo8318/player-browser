package com.playerbrowser.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playerbrowser.app.PlayerBrowserApp
import com.playerbrowser.app.data.Bookmark
import com.playerbrowser.app.data.BrowserRepository
import com.playerbrowser.app.data.HistoryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BrowserUiState(
    val currentUrl: String = HOME_URL,
    val currentTitle: String = "",
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false
) {
    companion object {
        const val HOME_URL = "https://www.google.com"
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BrowserViewModel(app: Application) : AndroidViewModel(app) {
    private val repository: BrowserRepository = (app as PlayerBrowserApp).repository

    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    private val _pendingLoadUrl = MutableStateFlow<String?>(null)
    val pendingLoadUrl: StateFlow<String?> = _pendingLoadUrl.asStateFlow()

    val bookmarks: StateFlow<List<Bookmark>> =
        repository.bookmarks().stateInOnViewModel(emptyList())

    val history: StateFlow<List<HistoryEntry>> =
        repository.history().stateInOnViewModel(emptyList())

    val visitedUrls: StateFlow<Set<String>> =
        repository.visitedUrls().map { it.toSet() }.stateInOnViewModel(emptySet())

    val isCurrentBookmarked: StateFlow<Boolean> =
        _state.map { it.currentUrl }
            .flatMapLatest { url -> repository.isBookmarked(url) }
            .stateInOnViewModel(false)

    private fun <T> Flow<T>.stateInOnViewModel(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    fun requestLoad(url: String) { _pendingLoadUrl.value = url }
    fun consumePendingLoad() { _pendingLoadUrl.value = null }

    fun onPageStarted(url: String) {
        _state.value = _state.value.copy(currentUrl = url, loading = true)
    }

    fun onPageFinished(url: String, title: String, canGoBack: Boolean, canGoForward: Boolean) {
        _state.value = _state.value.copy(
            currentUrl = url,
            currentTitle = title,
            loading = false,
            canGoBack = canGoBack,
            canGoForward = canGoForward
        )
        if (url.startsWith("http", ignoreCase = true)) {
            viewModelScope.launch { repository.recordVisit(url, title.ifBlank { url }) }
        }
    }

    fun toggleBookmark() {
        val s = _state.value
        if (!s.currentUrl.startsWith("http", ignoreCase = true)) return
        viewModelScope.launch {
            if (isCurrentBookmarked.value) repository.removeBookmark(s.currentUrl)
            else repository.addBookmark(s.currentUrl, s.currentTitle.ifBlank { s.currentUrl })
        }
    }

    fun deleteBookmark(url: String) = viewModelScope.launch { repository.removeBookmark(url) }
    fun deleteHistory(url: String) = viewModelScope.launch { repository.removeHistory(url) }
    fun clearHistory() = viewModelScope.launch { repository.clearHistory() }
}
