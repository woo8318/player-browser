package com.playerbrowser.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playerbrowser.app.BuildConfig
import com.playerbrowser.app.PlayerBrowserApp
import com.playerbrowser.app.data.Bookmark
import com.playerbrowser.app.data.BrowserRepository
import com.playerbrowser.app.data.HistoryEntry
import com.playerbrowser.app.data.PersistedGroup
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
    val canGoForward: Boolean = false,
    val parentTabId: String? = null,
    val groupId: String? = null
) {
    companion object {
        fun blank(initialUrl: String = BrowserUiState.HOME_URL, parentTabId: String? = null): TabState =
            TabState(
                id = UUID.randomUUID().toString(),
                currentUrl = initialUrl,
                parentTabId = parentTabId
            )
    }
}

data class TabGroup(
    val id: String,
    val name: String,
    val color: Int
)

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
                currentTitle = p.title,
                parentTabId = p.parentTabId,
                groupId = p.groupId
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

    private val _groups = MutableStateFlow(
        restored?.groups?.map { TabGroup(it.id, it.name, it.color) } ?: emptyList()
    )
    val groups: StateFlow<List<TabGroup>> = _groups.asStateFlow()

    init {
        viewModelScope.launch {
            combine(_tabs, _activeTabId, _groups) { tabs, id, groups ->
                snapshotForPersist(tabs, id, groups)
            }
                .distinctUntilChanged()
                .debounce(500)
                .onEach { session ->
                    withContext(Dispatchers.IO) { tabPersistence.save(session) }
                }
                .collect {}
        }
    }

    private fun snapshotForPersist(
        tabs: List<TabState>,
        activeId: String,
        groups: List<TabGroup>
    ): PersistedSession =
        PersistedSession(
            tabs = tabs.map {
                PersistedTab(
                    id = it.id,
                    url = it.currentUrl,
                    title = it.currentTitle,
                    parentTabId = it.parentTabId,
                    groupId = it.groupId
                )
            },
            activeTabId = activeId,
            groups = groups.map { PersistedGroup(it.id, it.name, it.color) }
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

    fun newTab(
        initialUrl: String = BrowserUiState.HOME_URL,
        parentTabId: String? = null
    ): String {
        // Inherit the parent's group so popups land next to their opener instead
        // of falling into the default bucket and visually breaking the group.
        val inheritedGroupId = parentTabId?.let { pid ->
            _tabs.value.firstOrNull { it.id == pid }?.groupId
        }
        val tab = TabState.blank(initialUrl, parentTabId = parentTabId)
            .copy(groupId = inheritedGroupId)
        _tabs.value = _tabs.value + tab
        _activeTabId.value = tab.id
        _pendingLoadUrl.value = initialUrl
        return tab.id
    }

    fun selectTab(id: String) {
        if (_tabs.value.any { it.id == id }) _activeTabId.value = id
    }

    /**
     * Switches tabs for the toolbar / nav-bar swipe gesture and returns true only
     * when the active tab actually changed.
     *
     * A backward ("previous") swipe first tries the opener tab: if the current
     * tab was spawned as a new window (`window.open` / `target=_blank`) it returns
     * to the tab that opened it, mirroring the back-gesture behavior — so a popup
     * tab swipes back to its origin instead of a positional neighbor. When there
     * is no live parent, both directions fall back to the flat adjacent tab,
     * stopping at the ends (no wrap) so an edge swipe is a predictable no-op.
     */
    fun selectAdjacentTab(forward: Boolean): Boolean {
        val tabs = _tabs.value
        val index = tabs.indexOfFirst { it.id == _activeTabId.value }
        if (index < 0) return false
        if (!forward) {
            val parentId = tabs[index].parentTabId
            if (parentId != null && tabs.any { it.id == parentId }) {
                _activeTabId.value = parentId
                return true
            }
        }
        val target = index + if (forward) 1 else -1
        if (target < 0 || target > tabs.lastIndex) return false
        _activeTabId.value = tabs[target].id
        return true
    }

    fun closeTab(id: String) {
        closeTabs(listOf(id))
    }

    fun closeTabs(ids: List<String>) {
        if (ids.isEmpty()) return
        val current = _tabs.value
        val targets = ids.toSet()
        // Special case: closing everything → replace with a single blank tab.
        if (targets.containsAll(current.map { it.id })) {
            val replacement = TabState.blank()
            _tabs.value = listOf(replacement)
            _activeTabId.value = replacement.id
            _pendingLoadUrl.value = BrowserUiState.HOME_URL
            return
        }
        val keptOrdered = current.filterNot { it.id in targets }
        // Orphaned children (their parent was just closed) lose their back-to-parent link.
        val keptIds = keptOrdered.map { it.id }.toSet()
        val sanitized = keptOrdered.map {
            if (it.parentTabId != null && it.parentTabId !in keptIds) it.copy(parentTabId = null) else it
        }
        _tabs.value = sanitized
        if (_activeTabId.value in targets) {
            // Keep the activation position close to where it was so the
            // user's mental map of the tab strip isn't shuffled.
            val originalIndex = current.indexOfFirst { it.id == _activeTabId.value }
                .coerceAtLeast(0)
            val newActive = sanitized.getOrNull(originalIndex.coerceAtMost(sanitized.lastIndex))
                ?: sanitized.first()
            _activeTabId.value = newActive.id
        }
    }

    /**
     * Attempts to return to the opener tab and close the current one. Used
     * when a child tab has no more back-history of its own — matches the
     * Opera/Chrome popup gesture where "back from a popup" dismisses it.
     * Returns true if a parent was found and the swap happened.
     */
    fun tryReturnToParent(): Boolean {
        val current = activeTab.value
        val parentId = current.parentTabId ?: return false
        if (_tabs.value.none { it.id == parentId }) return false
        _activeTabId.value = parentId
        closeTabs(listOf(current.id))
        return true
    }

    private fun updateTab(id: String, transform: (TabState) -> TabState) {
        _tabs.value = _tabs.value.map { if (it.id == id) transform(it) else it }
    }

    // ----- Tab grouping -----

    fun addGroup(name: String, color: Int): String {
        val group = TabGroup(id = UUID.randomUUID().toString(), name = name, color = color)
        _groups.value = _groups.value + group
        return group.id
    }

    fun renameGroup(id: String, name: String) {
        _groups.value = _groups.value.map { if (it.id == id) it.copy(name = name) else it }
    }

    fun deleteGroup(id: String) {
        // Removing a group does NOT close its tabs — they fall back to the
        // ungrouped section so the user doesn't lose work by mistake.
        _groups.value = _groups.value.filterNot { it.id == id }
        _tabs.value = _tabs.value.map { if (it.groupId == id) it.copy(groupId = null) else it }
    }

    fun setTabGroup(tabId: String, groupId: String?) {
        updateTab(tabId) { it.copy(groupId = groupId) }
    }

    fun setTabsGroup(tabIds: List<String>, groupId: String?) {
        if (tabIds.isEmpty()) return
        val targets = tabIds.toSet()
        _tabs.value = _tabs.value.map {
            if (it.id in targets) it.copy(groupId = groupId) else it
        }
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
