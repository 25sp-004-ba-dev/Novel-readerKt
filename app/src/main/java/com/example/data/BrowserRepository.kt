package com.example.data

import android.net.Uri
import kotlinx.coroutines.flow.Flow

class BrowserRepository(private val browserDao: BrowserDao) {
    val allHistory: Flow<List<HistoryEntry>> = browserDao.getAllHistory()
    val allBookmarks: Flow<List<BookmarkEntry>> = browserDao.getAllBookmarks()
    val allTabsFlow: Flow<List<TabEntry>> = browserDao.getAllTabsFlow()

    suspend fun insertHistory(url: String, title: String) {
        val existing = browserDao.getHistoryByUrl(url)
        if (existing != null) {
            browserDao.insertHistory(existing.copy(timestamp = System.currentTimeMillis(), title = title))
        } else {
            browserDao.insertHistory(HistoryEntry(url = url, title = title))
            // Auto prune history size to 500 rows for high-efficiency reading speeds
            browserDao.pruneHistory(500)
        }
    }

    suspend fun deleteHistory(id: Long) = browserDao.deleteHistory(id)
    suspend fun clearHistory() = browserDao.clearHistory()

    private fun extractNovelAndChapter(title: String, url: String): Pair<String, String> {
        if (title.isEmpty()) return Pair("Wtr-Lab Browser", "Web Chapter")
        
        var cleanTitle = title
            .replace(" - NovelHall", "", ignoreCase = true)
            .replace(" - Read Novel Free", "", ignoreCase = true)
            .replace(" - WebNovel", "", ignoreCase = true)
            .replace(" - NovelBin", "", ignoreCase = true)
            .replace(" - FreeWebNovel", "", ignoreCase = true)
            .replace(" - FanMTL", "", ignoreCase = true)
            .replace(" - timotxt", "", ignoreCase = true)
            .replace(" online free", "", ignoreCase = true)
            .replace(" read online", "", ignoreCase = true)
            .trim()

        val separators = listOf(" - ", " | ", " – ", " — ")
        for (sep in separators) {
            if (cleanTitle.contains(sep)) {
                val parts = cleanTitle.split(sep)
                if (parts.size >= 2) {
                    val hasChap0 = parts[0].contains("Chapter", ignoreCase = true) || parts[0].contains("Ch ", ignoreCase = true) || parts[0].any { it.isDigit() }
                    val hasChap1 = parts[1].contains("Chapter", ignoreCase = true) || parts[1].contains("Ch ", ignoreCase = true) || parts[1].any { it.isDigit() }
                    
                    return if (hasChap1 && !hasChap0) {
                        Pair(parts[0].trim(), parts[1].trim())
                    } else if (hasChap0 && !hasChap1) {
                        Pair(parts[1].trim(), parts[0].trim())
                    } else {
                        Pair(parts[0].trim(), parts.drop(1).joinToString(" - ").trim())
                    }
                }
            }
        }
        
        if (cleanTitle.contains("Chapter", ignoreCase = true)) {
            val idx = cleanTitle.indexOf("Chapter", ignoreCase = true)
            if (idx > 0) {
                return Pair(cleanTitle.substring(0, idx).trim(), cleanTitle.substring(idx).trim())
            }
        }
        
        return Pair(cleanTitle, "Web Novel")
    }

    suspend fun insertBookmark(url: String, title: String, imageUrl: String? = null) {
        val host = try { Uri.parse(url).host ?: "" } catch (e: Exception) { "" }.lowercase()
        val hasNovelHost = host.contains("novel") || host.contains("timotxt") || host.contains("fanmtl") || host.contains("translate.goog")
        val parsed = extractNovelAndChapter(title, url)
        val hasChapterInTitle = title.contains("Chapter", ignoreCase = true) || title.contains("Ch.", ignoreCase = true) || title.contains("Ch ", ignoreCase = true)
        val isNovel = hasNovelHost || hasChapterInTitle || (parsed.second != "Web Novel" && parsed.second != "Web Chapter")

        if (isNovel) {
            val cleanHost = host.replace("www.", "").replace("translate.goog", "").trim('.')
            browserDao.insertBookmark(
                BookmarkEntry(
                    url = url,
                    title = title,
                    isNovel = true,
                    novelTitle = parsed.first,
                    chapterTitle = parsed.second,
                    domain = cleanHost,
                    imageUrl = imageUrl,
                    lastViewedChapterUrl = url,
                    lastViewedChapterTitle = parsed.second
                )
            )
        } else {
            browserDao.insertBookmark(BookmarkEntry(url = url, title = title, isNovel = false))
        }
    }

    suspend fun updateReadingProgress(url: String, title: String) {
        val parsed = extractNovelAndChapter(title, url)
        val novelTitleVal = parsed.first
        if (novelTitleVal.isNotEmpty() && novelTitleVal != "Wtr-Lab Browser" && parsed.second != "Web Novel" && parsed.second != "Web Chapter") {
            val existingNovelBookmark = browserDao.getNovelBookmark(novelTitleVal)
            if (existingNovelBookmark != null) {
                val updated = existingNovelBookmark.copy(
                    lastViewedChapterUrl = url,
                    lastViewedChapterTitle = parsed.second
                )
                browserDao.updateBookmark(updated)
            }
        }
    }

    suspend fun deleteBookmark(id: Long) = browserDao.deleteBookmark(id)
    suspend fun deleteBookmarkByUrl(url: String) = browserDao.deleteBookmarkByUrl(url)
    fun isBookmarked(url: String): Flow<Boolean> = browserDao.isBookmarked(url)

    suspend fun getAllTabs(): List<TabEntry> = browserDao.getAllTabs()
    suspend fun insertTab(tab: TabEntry): Long = browserDao.insertTab(tab)
    suspend fun updateTab(tab: TabEntry) = browserDao.updateTab(tab)
    suspend fun deleteTab(id: Long) = browserDao.deleteTab(id)
    suspend fun clearTabs() = browserDao.clearTabs()
}
