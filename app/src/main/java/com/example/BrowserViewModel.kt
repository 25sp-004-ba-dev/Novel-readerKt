package com.example

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            if (current != null && (current.url != url || current.title != title)) {
                com.example.WtrLogManager.log(getApplication(), "onPageLoaded updates tab ID=${current.id} from ${current.url} to $url (title=$title)")
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
        if (lower == "n543" || lower == "novel543") return "https://www.novel543.com/"
        if (lower == "twkan" || lower == "tw") return "https://twkan.com/"
        if (lower == "nhub" || lower == "novelhub") return "https://novelhub.net/"
        if (lower == "nhubapp" || lower == "novelhubapp") return "https://novelhubapp.com/"

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
    fun updateNovelMetadata(url: String, novelTitle: String, chapterTitle: String, coverImage: String) {
        viewModelScope.launch {
            repository.updateNovelMetadata(url, novelTitle, chapterTitle, coverImage)
        }
    }

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

    fun exportBackup(outputStream: java.io.OutputStream, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = org.json.JSONObject()
                json.put("version", 1)
                json.put("timestamp", System.currentTimeMillis())

                // 1. Settings from SharedPreferences
                val context = getApplication<Application>()
                val sharedPrefs = context.getSharedPreferences("wtr_browser_settings", Context.MODE_PRIVATE)
                val settingsJson = org.json.JSONObject().apply {
                    put("app_theme", sharedPrefs.getString("app_theme", "Dark"))
                    put("custom_text_zoom", sharedPrefs.getInt("custom_text_zoom", 115))
                    put("force_dark_content", sharedPrefs.getBoolean("force_dark_content", false))
                    put("enable_web_trackplayer", sharedPrefs.getBoolean("enable_web_trackplayer", false))
                    put("auto_focus_paragraphs", sharedPrefs.getBoolean("auto_focus_paragraphs", true))
                    put("remember_paragraphs", sharedPrefs.getBoolean("remember_paragraphs", true))
                    put("auto_translate_enabled", sharedPrefs.getBoolean("auto_translate_enabled", true))
                    put("auto_translate_domains", sharedPrefs.getString("auto_translate_domains", "timotxt.com, timotxt, novel543.com, novel543, twkan.com, twkan"))
                    put("ad_blocker_enabled", sharedPrefs.getBoolean("ad_blocker_enabled", true))
                }
                json.put("settings", settingsJson)

                // 2. History
                val historyList = allHistory.value.ifEmpty { repository.allHistory.first() }
                val historyArray = org.json.JSONArray()
                historyList.forEach { entry ->
                    val obj = org.json.JSONObject().apply {
                        put("url", entry.url)
                        put("title", entry.title)
                        put("timestamp", entry.timestamp)
                    }
                    historyArray.put(obj)
                }
                json.put("history", historyArray)

                // 3. Bookmarks
                val bookmarksList = allBookmarks.value.ifEmpty { repository.allBookmarks.first() }
                val bookmarksArray = org.json.JSONArray()
                bookmarksList.forEach { entry ->
                    val obj = org.json.JSONObject().apply {
                        put("url", entry.url)
                        put("title", entry.title)
                        put("timestamp", entry.timestamp)
                        put("isNovel", entry.isNovel)
                        put("novelTitle", entry.novelTitle ?: org.json.JSONObject.NULL)
                        put("chapterTitle", entry.chapterTitle ?: org.json.JSONObject.NULL)
                        put("imageUrl", entry.imageUrl ?: org.json.JSONObject.NULL)
                        put("domain", entry.domain ?: org.json.JSONObject.NULL)
                        put("lastViewedChapterUrl", entry.lastViewedChapterUrl ?: org.json.JSONObject.NULL)
                        put("lastViewedChapterTitle", entry.lastViewedChapterTitle ?: org.json.JSONObject.NULL)
                    }
                    bookmarksArray.put(obj)
                }
                json.put("bookmarks", bookmarksArray)

                // 4. Tabs
                val tabsList = repository.getAllTabs()
                val tabsArray = org.json.JSONArray()
                tabsList.forEach { entry ->
                    val obj = org.json.JSONObject().apply {
                        put("url", entry.url)
                        put("title", entry.title)
                        put("isCurrent", entry.isCurrent)
                        put("isDesktopMode", entry.isDesktopMode)
                        put("groupId", entry.groupId ?: org.json.JSONObject.NULL)
                        put("timestamp", entry.timestamp)
                    }
                    tabsArray.put(obj)
                }
                json.put("tabs", tabsArray)

                // Write to output stream
                withContext(Dispatchers.IO) {
                    val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(outputStream, "UTF-8"))
                    writer.write(json.toString(2))
                    writer.flush()
                }
                withContext(Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            }
        }
    }

    fun importBackup(inputStream: java.io.InputStream, onSuccess: () -> Unit, onError: (Exception) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonString = withContext(Dispatchers.IO) {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(inputStream, "UTF-8"))
                    reader.use { it.readText() }
                }
                val json = org.json.JSONObject(jsonString)

                // 1. Restore SharedPreferences
                val context = getApplication<Application>()
                val sharedPrefs = context.getSharedPreferences("wtr_browser_settings", Context.MODE_PRIVATE)
                val editor = sharedPrefs.edit()

                if (json.has("settings")) {
                    val settingsJson = json.getJSONObject("settings")
                    if (settingsJson.has("app_theme")) editor.putString("app_theme", settingsJson.getString("app_theme"))
                    if (settingsJson.has("custom_text_zoom")) editor.putInt("custom_text_zoom", settingsJson.getInt("custom_text_zoom"))
                    if (settingsJson.has("force_dark_content")) editor.putBoolean("force_dark_content", settingsJson.getBoolean("force_dark_content"))
                    if (settingsJson.has("enable_web_trackplayer")) editor.putBoolean("enable_web_trackplayer", settingsJson.getBoolean("enable_web_trackplayer"))
                    if (settingsJson.has("auto_focus_paragraphs")) editor.putBoolean("auto_focus_paragraphs", settingsJson.getBoolean("auto_focus_paragraphs"))
                    if (settingsJson.has("remember_paragraphs")) editor.putBoolean("remember_paragraphs", settingsJson.getBoolean("remember_paragraphs"))
                    if (settingsJson.has("auto_translate_enabled")) editor.putBoolean("auto_translate_enabled", settingsJson.getBoolean("auto_translate_enabled"))
                    if (settingsJson.has("auto_translate_domains")) editor.putString("auto_translate_domains", settingsJson.getString("auto_translate_domains"))
                    if (settingsJson.has("ad_blocker_enabled")) editor.putBoolean("ad_blocker_enabled", settingsJson.getBoolean("ad_blocker_enabled"))
                    editor.apply()
                }

                // Get DB instances
                val db = AppDatabase.getDatabase(context)
                val dao = db.browserDao()

                // 2. Restore History
                if (json.has("history")) {
                    val historyArray = json.getJSONArray("history")
                    dao.clearHistory()
                    for (i in 0 until historyArray.length()) {
                        val obj = historyArray.getJSONObject(i)
                        val entry = HistoryEntry(
                            url = obj.getString("url"),
                            title = obj.getString("title"),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                        )
                        dao.insertHistory(entry)
                    }
                }

                // 3. Restore Bookmarks
                if (json.has("bookmarks")) {
                    val bookmarksArray = json.getJSONArray("bookmarks")
                    dao.clearBookmarks()
                    for (i in 0 until bookmarksArray.length()) {
                        val obj = bookmarksArray.getJSONObject(i)
                        val entry = BookmarkEntry(
                            url = obj.getString("url"),
                            title = obj.getString("title"),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            isNovel = obj.optBoolean("isNovel", false),
                            novelTitle = if (obj.isNull("novelTitle")) null else obj.optString("novelTitle"),
                            chapterTitle = if (obj.isNull("chapterTitle")) null else obj.optString("chapterTitle"),
                            imageUrl = if (obj.isNull("imageUrl")) null else obj.optString("imageUrl"),
                            domain = if (obj.isNull("domain")) null else obj.optString("domain"),
                            lastViewedChapterUrl = if (obj.isNull("lastViewedChapterUrl")) null else obj.optString("lastViewedChapterUrl"),
                            lastViewedChapterTitle = if (obj.isNull("lastViewedChapterTitle")) null else obj.optString("lastViewedChapterTitle")
                        )
                        dao.insertBookmark(entry)
                    }
                }

                // 4. Restore Tabs
                if (json.has("tabs")) {
                    val tabsArray = json.getJSONArray("tabs")
                    dao.clearTabs()
                    var currentTabToLoad: TabEntry? = null
                    for (i in 0 until tabsArray.length()) {
                        val obj = tabsArray.getJSONObject(i)
                        val entry = TabEntry(
                            url = obj.getString("url"),
                            title = obj.getString("title"),
                            isCurrent = obj.optBoolean("isCurrent", false),
                            isDesktopMode = obj.optBoolean("isDesktopMode", false),
                            groupId = if (obj.isNull("groupId")) null else obj.optLong("groupId"),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                        )
                        val newId = dao.insertTab(entry)
                        if (entry.isCurrent) {
                            currentTabToLoad = entry.copy(id = newId)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        if (currentTabToLoad != null) {
                            _currentTab.value = currentTabToLoad
                            _currentUrlInput.value = currentTabToLoad.url
                        } else {
                            val allRestored = dao.getAllTabs()
                            if (allRestored.isNotEmpty()) {
                                val target = allRestored.first()
                                _currentTab.value = target
                                _currentUrlInput.value = target.url
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            }
        }
    }
}

