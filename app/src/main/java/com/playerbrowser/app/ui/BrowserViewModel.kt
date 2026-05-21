package com.playerbrowser.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playerbrowser.app.BuildConfig
import com.playerbrowser.app.PlayerBrowserApp
import com.playerbrowser.app.data.Bookmark
import com.playerbrowser.app.data.BrowserRepository
import com.playerbrowser.app.data.HistoryEntry
import com.playerbrowser.app.data.PersistedSession
import com.playerbrowser.app.data.PersistedTab
import com.playerbrowser.app.data.TabPersistence
import com.playerbrowser.app.update.DownloadStep
import com.playerbrowser.app.update.UpdateClient
import com.playerbrowser.app.update.UpdateInstaller
import com.playerbrowser.app.update.UpdateState
import com.playerbrowser.app.update.Version
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TabState(
    val id: String,
    val currentUrl: String = BrowserUiState.HOME_URL,
    val currentTitle: String = "",
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false
) {
    companion object {
        fun blank(initialUrl: String = BrowserUiState.HOME_URL): TabState =
            TabState(id = UUID.randomUUID().toString(), currentUrl = initialUrl)
    }
}

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
    private val tabPersistence: TabPersistence = TabPersistence(app)

    private val restored: PersistedSession? = tabPersistence.load()
    private val restoredTabs: List<TabState> = restored?.tabs
        ?.map { p ->
            TabState(
                id = p.id,
                currentUrl = p.url.ifBlank { BrowserUiState.HOME_URL },
                currentTitle = p.title
            )
        }
        ?.takeIf { it.isNotEmpty() }
        ?: listOf(TabState.blank())

    private val _tabs = MutableStateFlow(restoredTabs)
    val tabs: StateFlow<List<TabState>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow(
        restored?.activeTabId?.takeIf { id -> restoredTabs.any { it.id == id } }
            ?: restoredTabs.first().id
    )
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    val activeTab: StateFlow<TabState> =
        combine(_tabs, _activeTabId) { tabs, id -> tabs.firstOrNull { it.id == id } ?: tabs.first() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, restoredTabs.first())

    init {
        viewModelScope.launch {
            combine(_tabs, _activeTabId) { tabs, id -> snapshotForPersist(tabs, id) }
                .distinctUntilChanged()
                .debounce(500)
                .onEach { session ->
                    withContext(Dispatchers.IO) { tabPersistence.save(session) }
                }
                .collect {}
        }
    }

    private fun snapshotForPersist(tabs: List<TabState>, activeId: String): PersistedSession =
        PersistedSession(
            tabs = tabs.map { PersistedTab(id = it.id, url = it.currentUrl, title = it.currentTitle) },
            activeTabId = activeId
        )

    val state: StateFlow<BrowserUiState> = activeTab.map {
        BrowserUiState(
            currentUrl = it.currentUrl,
            currentTitle = it.currentTitle,
            loading = it.loading,
            canGoBack = it.canGoBack,
            canGoForward = it.canGoForward
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BrowserUiState())

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
        activeTab.map { it.currentUrl }
            .flatMapLatest { url -> repository.isBookmarked(url) }
            .stateInOnViewModel(false)

    private fun <T> Flow<T>.stateInOnViewModel(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    fun requestLoad(url: String) { _pendingLoadUrl.value = url }
    fun consumePendingLoad() { _pendingLoadUrl.value = null }

    // ----- Tab management -----

    fun newTab(initialUrl: String = BrowserUiState.HOME_URL): String {
        val tab = TabState.blank(initialUrl)
        _tabs.value = _tabs.value + tab
        _activeTabId.value = tab.id
        _pendingLoadUrl.value = initialUrl
        return tab.id
    }

    fun selectTab(id: String) {
        if (_tabs.value.any { it.id == id }) _activeTabId.value = id
    }

    fun closeTab(id: String) {
        val current = _tabs.value
        if (current.size <= 1) {
            // Keep at least one tab — reset it to a blank home tab instead.
            val replacement = TabState.blank()
            _tabs.value = listOf(replacement)
            _activeTabId.value = replacement.id
            _pendingLoadUrl.value = BrowserUiState.HOME_URL
            return
        }
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return
        val next = current.toMutableList().also { it.removeAt(index) }
        _tabs.value = next
        if (_activeTabId.value == id) {
            val newActive = next[index.coerceAtMost(next.lastIndex)]
            _activeTabId.value = newActive.id
        }
    }

    private fun updateTab(id: String, transform: (TabState) -> TabState) {
        _tabs.value = _tabs.value.map { if (it.id == id) transform(it) else it }
    }

    // ----- Page lifecycle (per tab) -----

    fun onPageStarted(tabId: String, url: String) {
        updateTab(tabId) { it.copy(currentUrl = url, loading = true) }
    }

    fun onPageFinished(
        tabId: String,
        url: String,
        title: String,
        canGoBack: Boolean,
        canGoForward: Boolean
    ) {
        updateTab(tabId) {
            it.copy(
                currentUrl = url,
                currentTitle = title,
                loading = false,
                canGoBack = canGoBack,
                canGoForward = canGoForward
            )
        }
        if (url.startsWith("http", ignoreCase = true)) {
            viewModelScope.launch { repository.recordVisit(url, title.ifBlank { url }) }
        }
    }

    fun toggleBookmark() {
        val s = activeTab.value
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
