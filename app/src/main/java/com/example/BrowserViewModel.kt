package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: BrowserRepository
    val allHistory: StateFlow<List<HistoryEntry>>
    val allBookmarks: StateFlow<List<BookmarkEntry>>
    val allTabs: StateFlow<List<TabEntry>>

    private val _currentTab = MutableStateFlow<TabEntry?>(null)
    val currentTab: StateFlow<TabEntry?> = _currentTab

    private val _currentUrlInput = MutableStateFlow("")
    val currentUrlInput: StateFlow<String> = _currentUrlInput

    private val _userNavigateTrigger = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 8)
    val userNavigateTrigger: SharedFlow<String> = _userNavigateTrigger.asSharedFlow()

    private val _searchEngine = MutableStateFlow("https://www.google.com/search?q=")
    val searchEngine: StateFlow<String> = _searchEngine

    private var lastHistoryUrl: String? = null

    init {
        val db = AppDatabase.getDatabase(application)
        repository = BrowserRepository(db.browserDao())

        allHistory = repository.allHistory.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )
        allBookmarks = repository.allBookmarks.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )
        allTabs = repository.allTabsFlow.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )

        // Initialize tabs once from DB; subsequently, we manage states in memory and update DB asynchronously
        viewModelScope.launch {
            val tabsList = repository.allTabsFlow.first()
            if (tabsList.isEmpty()) {
                // Create default tab
                val defaultTab = TabEntry(url = "chrome://newtab", title = "New Tab", isCurrent = true)
                val id = repository.insertTab(defaultTab)
                _currentTab.value = defaultTab.copy(id = id)
                _currentUrlInput.value = "chrome://newtab"
            } else {
                val current = tabsList.find { it.isCurrent } ?: tabsList.first()
                _currentTab.value = current
                _currentUrlInput.value = current.url
            }
        }
    }

    fun setUrlInput(url: String) {
        _currentUrlInput.value = url
    }

    fun setSearchEngine(engineQueryUrl: String) {
        _searchEngine.value = engineQueryUrl
    }

    fun addNewTab(url: String = "chrome://newtab", title: String = "New Tab", groupId: Long? = null) {
        viewModelScope.launch {
            com.example.WtrLogManager.log(getApplication(), "addNewTab requested: url=$url, title=$title")
            val tabsList = repository.getAllTabs()
            // Mark all current tabs as not current
            tabsList.forEach {
                if (it.isCurrent) {
                    repository.updateTab(it.copy(isCurrent = false))
                }
            }
            val newTab = TabEntry(url = url, title = title, isCurrent = true, groupId = groupId)
            val id = repository.insertTab(newTab)
            _currentTab.value = newTab.copy(id = id)
            _currentUrlInput.value = url
        }
    }

    fun switchToTab(tab: TabEntry) {
        viewModelScope.launch {
            val tabsList = repository.getAllTabs()
            tabsList.forEach {
                if (it.id == tab.id) {
                    val updated = it.copy(isCurrent = true)
                    repository.updateTab(updated)
                    _currentTab.value = updated
                    _currentUrlInput.value = updated.url
                } else if (it.isCurrent) {
                    repository.updateTab(it.copy(isCurrent = false))
                }
            }
        }
    }

    fun toggleDesktopMode(tab: TabEntry, enabled: Boolean) {
        viewModelScope.launch {
            val updated = tab.copy(isDesktopMode = enabled)
            repository.updateTab(updated)
            if (_currentTab.value?.id == tab.id) {
                _currentTab.value = updated
            }
        }
    }

    fun groupTabs(tabIds: List<Long>, targetGroupId: Long) {
        viewModelScope.launch {
            val tabsList = repository.getAllTabs()
            tabsList.forEach {
                if (tabIds.contains(it.id)) {
                    repository.updateTab(it.copy(groupId = targetGroupId))
                    if (_currentTab.value?.id == it.id) {
                        _currentTab.value = it.copy(groupId = targetGroupId)
                    }
                }
            }
        }
    }

    fun removeFromGroup(tab: TabEntry) {
        viewModelScope.launch {
            val updated = tab.copy(groupId = null)
            repository.updateTab(updated)
            if (_currentTab.value?.id == tab.id) {
                _currentTab.value = updated
            }
        }
    }

    fun closeTab(tab: TabEntry) {
        viewModelScope.launch {
            com.example.WtrLogManager.log(getApplication(), "closeTab requested: ID=${tab.id}, url=${tab.url}")
            val tabsList = repository.getAllTabs()
            if (tabsList.size <= 1) {
                // If closing single last tab, just reset it to home
                val updated = tab.copy(url = "chrome://newtab", title = "New Tab", isCurrent = true, groupId = null)
                repository.updateTab(updated)
                _currentTab.value = updated
                _currentUrlInput.value = "chrome://newtab"
                return@launch
            }

            repository.deleteTab(tab.id)

            // If we closed the active tab, switch to another tab
            if (tab.isCurrent) {
                val remaining = tabsList.filter { it.id != tab.id }
                if (remaining.isNotEmpty()) {
                    val target = remaining.first()
                    val updated = target.copy(isCurrent = true)
                    repository.updateTab(updated)
                    _currentTab.value = updated
                    _currentUrlInput.value = updated.url
                }
            }
        }
    }

    fun clearAllTabs() {
        viewModelScope.launch {
            repository.clearTabs()
            val defaultTab = TabEntry(url = "chrome://newtab", title = "New Tab", isCurrent = true)
            val id = repository.insertTab(defaultTab)
            _currentTab.value = defaultTab.copy(id = id)
            _currentUrlInput.value = "chrome://newtab"
        }
    }

    fun loadUrl(url: String) {
        viewModelScope.launch {
            val cleanUrl = cleanInputUrl(url, _searchEngine.value)
            com.example.WtrLogManager.log(getApplication(), "loadUrl: request=$url -> resolved=$cleanUrl")
            val current = _currentTab.value
            if (current != null) {
                val updated = current.copy(url = cleanUrl)
                repository.updateTab(updated)
                _currentTab.value = updated
                _currentUrlInput.value = cleanUrl
            }
            _userNavigateTrigger.emit(cleanUrl)
        }
    }

    fun onPageLoaded(url: String, title: String) {
        viewModelScope.launch {
            val current = _currentTab.value
            if (current != null && current.url != url) {
                com.example.WtrLogManager.log(getApplication(), "onPageLoaded updates tab ID=${current.id} from ${current.url} to $url")
                val updated = current.copy(url = url, title = title)
                repository.updateTab(updated)
                _currentTab.value = updated
                _currentUrlInput.value = url
            }
            if (lastHistoryUrl != url) {
                lastHistoryUrl = url
                repository.insertHistory(url, title)
            }
            try {
                repository.updateReadingProgress(url, title)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun cleanInputUrl(input: String, searchEngineUrl: String): String {
        val trimmed = input.trim()
        if (trimmed == "chrome://newtab") {
            return trimmed
        }
        val lower = trimmed.lowercase()
        if (lower == "wtr") return "https://wtr-lab.com/en"
        if (lower == "nov" || lower == "no" || lower == "novel") return "https://www.novelhall.com/"
        if (lower == "timo" || lower == "timotxt") return "https://www.timotxt.com/"

        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        val hasSpace = trimmed.contains(" ")
        val hasDot = trimmed.contains(".")
        val isProbablyUrl = !hasSpace && hasDot && trimmed.length > 3
        return if (isProbablyUrl) {
            "https://$trimmed"
        } else {
            searchEngineUrl + java.net.URLEncoder.encode(trimmed, "UTF-8")
        }
    }

    // Bookmarks
    fun toggleBookmark(url: String, title: String, imageUrl: String? = null) {
        viewModelScope.launch {
            val isBookmarkedFlow = repository.isBookmarked(url)
            val exists = isBookmarkedFlow.first()
            if (exists) {
                repository.deleteBookmarkByUrl(url)
            } else {
                repository.insertBookmark(url, title, imageUrl)
            }
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch {
            repository.deleteBookmark(id)
        }
    }

    fun deleteHistory(id: Long) {
        viewModelScope.launch {
            repository.deleteHistory(id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    fun isUrlBookmarked(url: String): Flow<Boolean> {
        return repository.isBookmarked(url)
    }
}
