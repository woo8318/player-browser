package com.playerbrowser.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playerbrowser.app.BuildConfig
import com.playerbrowser.app.PlayerBrowserApp
import com.playerbrowser.app.data.Bookmark
import com.playerbrowser.app.data.BrowserRepository
import com.playerbrowser.app.data.HistoryEntry
import com.playerbrowser.app.update.DownloadStep
import com.playerbrowser.app.update.UpdateClient
import com.playerbrowser.app.update.UpdateInstaller
import com.playerbrowser.app.update.UpdateState
import com.playerbrowser.app.update.Version
import kotlinx.coroutines.Job
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
    private val installer: UpdateInstaller = UpdateInstaller(app)

    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    private val _pendingLoadUrl = MutableStateFlow<String?>(null)
    val pendingLoadUrl: StateFlow<String?> = _pendingLoadUrl.asStateFlow()

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

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

    // ----- App update -----

    fun checkForUpdates(silent: Boolean = false) {
        if (_updateState.value is UpdateState.Checking ||
            _updateState.value is UpdateState.Downloading) return
        _updateState.value = UpdateState.Checking
        viewModelScope.launch {
            runCatching {
                UpdateClient.fetchLatestRelease(BuildConfig.GITHUB_OWNER, BuildConfig.GITHUB_REPO)
            }.onSuccess { info ->
                _updateState.value = if (Version.isNewer(info.versionName, BuildConfig.VERSION_NAME)) {
                    UpdateState.Available(info)
                } else {
                    if (silent) UpdateState.Idle else UpdateState.UpToDate
                }
            }.onFailure { e ->
                _updateState.value =
                    if (silent) UpdateState.Idle
                    else UpdateState.Error(e.message ?: "업데이트 확인 실패")
            }
        }
    }

    private var downloadJob: Job? = null

    fun startDownload() {
        val available = _updateState.value as? UpdateState.Available ?: return
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _updateState.value = UpdateState.Downloading(available.info, 0f, 0L)
            runCatching {
                installer.download(available.info).collect { step ->
                    when (step) {
                        is DownloadStep.Progress -> {
                            _updateState.value = UpdateState.Downloading(
                                info = available.info,
                                progress = step.fraction,
                                received = step.received
                            )
                        }
                        is DownloadStep.Done -> {
                            _updateState.value = UpdateState.ReadyToInstall(step.file, available.info)
                        }
                    }
                }
            }.onFailure { e ->
                _updateState.value = UpdateState.Error(e.message ?: "다운로드 실패")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        val current = _updateState.value
        if (current is UpdateState.Downloading) {
            _updateState.value = UpdateState.Available(current.info)
        }
    }

    fun launchInstall() {
        val ready = _updateState.value as? UpdateState.ReadyToInstall ?: return
        if (installer.canInstall()) {
            installer.launchInstaller(ready.apkFile)
        } else {
            installer.launchInstallSettings()
        }
    }

    fun dismissUpdate() { _updateState.value = UpdateState.Idle }
}
