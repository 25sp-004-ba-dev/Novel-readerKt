# Wtr-Lab Data Persistence Layer Manual
## Room Database Schema, SQLite Indexes, and Title Parser Engine

This document outlines the SQLite schema, entity mappings, custom repository patterns, and string-parsing heuristics governing data storage across Wtr-Lab Novel Reader.

---

## 🏛️ Comprehensive Database Architecture & Classes

The data layer is built on **Jetpack Room**, which abstracts standard SQLite transactions using annotations, providing compile-time query validation and reactive flows (`Flow`).

### 1. Database Holder: `AppDatabase.kt`
The abstract boundary declaring the operational database and database connections.
* **Component Type**: `abstract class AppDatabase : RoomDatabase()`
* **Primary Scope**: Defines the SQLite file mapping, configuration schemas, and Dao access points. Matches `version = 4` with persistent single/composite table speed indexes.
* **Key Attributes & Inner Components**:
  * `INSTANCE`: Volatile thread-safe caching variable protecting dual-initialization.
  * `getDatabase(context)`: Standard synchronized thread-safe Singleton constructor. Invokes `Room.databaseBuilder(..., "wtr_browser_db")`.
  * `.fallbackToDestructiveMigration()`: Prevents schema version mismatch crashes by clearing old SQLite local caches in development mode.

---

## 🗄️ Database Schemas & Entities

The application manages three distinct tables within local storage. Below are the precise Kotlin property definitions, column constraints, and roles.

### 1. TabEntry (`tabs` Table)
Holds browser tab states, tab groups, and historical session markers to rebuild active browser states.
* **Property Schema**:
  * `id`: `Long` (Primary Key, auto-generated). Unique identifier.
  * `url`: `String`. The current web URL address loaded by this tab. Defaults to `chrome://newtab` for fresh instances.
  * `title`: `String`. The active webpage title string drawn in the URL tab picker.
  * `isCurrent`: `Boolean`. Flag marking if this tab is currently selected and visible in the WebKit viewport.
  * `isDesktopMode`: `Boolean`. Flag enabling Desktop User-Agent overrides specifically on this WebKit instance.
  * `groupId`: `Long?` (Nullable). Points to a custom index Folder for tab grouping drawers. Set to `null` if standalone.
  * `timestamp`: `Long`. Sorting index ensuring tab listings preserve chronological creation.

### 2. HistoryEntry (`history` Table)
Stores visited web pages to build the Quick Speed-Dial cards and recent navigation rows.
* **Database Indexes**:
  * `idx_history_url`: Index on `url`. Optimizes history presence query checks.
  * `idx_history_timestamp`: Index on `timestamp`. Accelerates chronological listing fetches.
* **Property Schema**:
  * `id`: `Long` (Primary Key, auto-generated). Unique historical index.
  * `url`: `String`. Visited webpage URL.
  * `title`: `String`. Webpage title (or novel chapter title parsed from DOM).
  * `timestamp`: `Long`. Chronological timestamp of the visit.

### 3. BookmarkEntry (`bookmarks` Table)
Permits the saving of novel chapters, websites, and custom web links.
* **Database Indexes**:
  * `idx_bookmark_url`: Index on `url`. High speed bookmark checker queries.
  * `idx_bookmark_domain`: Index on `domain`. Dominant filter lists optimizations.
  * `idx_bookmark_isnovel`: Index on `isNovel`. Bookshelf library visual queries optimizer.
* **Property Schema**:
  * `id`: `Long` (Primary Key, auto-generated). Unique bookmark index.
  * `url`: `String`. Saved page address.
  * `title`: `String`. Webpage title.
  * `timestamp`: `Long`. Date of bookmark creation.
  * `isNovel`: `Boolean`. Smart flag marking if this bookmark matches a recognized reading website (e.g. timotxt, novelhall, webnovel) or has chapter titles. Opens in the visual **Novels Bookshelf Grid** if `true`; otherwise open under the general **Websites** tab.
  * `novelTitle`: `String?` (Nullable). Extracted index title of the parent web novel (e.g. *Lord of the Mysteries*).
  * `chapterTitle`: `String?` (Nullable). The specific chapter subtitle (e.g. *Chapter 123*).
  * `imageUrl`: `String?` (Nullable). Direct cover image URL extracted from the page's HTML meta headers or image scopes.
  * `domain`: `String?` (Nullable). Host domain name (e.g. `wtr-lab.com`) for host-associated search updates.
  * `lastViewedChapterUrl`: `String?` (Nullable). Address pointing to the user's most recently viewed chapter, providing seamless resume capabilities.
  * `lastViewedChapterTitle`: `String?` (Nullable). Subtitle of the last viewed chapter.

---

## 🛰️ Room Data Access Object: `BrowserDao.kt`

The central Interface defining SQL mappings. Queries are written directly on functions, providing asynchronous execution.

* **Interface Type**: `interface BrowserDao`
* **Exposed SQL Implementations**:
  * `getAllHistory()`: `SELECT * FROM history ORDER BY timestamp DESC` returning reactive `Flow<List<HistoryEntry>>` streams.
  * `getHistoryByUrl(url)`: `SELECT * FROM history WHERE url = :url LIMIT 1` (suspended query).
  * `pruneHistory(limit)`: `DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY timestamp DESC LIMIT :limit)` (restricts database ballooning).
  * `insertHistory(entry)`: Suspended SQLite Insertion on conflict replacement.
  * `deleteHistory(id)`: Removes history items.
  * `clearHistory()`: Complete table wipe.
  * `getAllBookmarks()`: `SELECT * FROM bookmarks ORDER BY timestamp DESC` returning a `Flow`.
  * `insertBookmark(entry)` / `updateBookmark(entry)`: Basic database writers.
  * `getNovelBookmark(novelTitle)`: Retrieves a bookmarked novel by exact string name.
  * `getAllNovelBookmarks()`: Retrieves all bookmarks flagged as novels (`isNovel = 1`).
  * `deleteBookmark(id)` / `deleteBookmarkByUrl(url)`: Removes bookmarks.
  * `clearBookmarks()`: Wipes bookmarks.
  * `isBookmarked(url)`: `SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url LIMIT 1)` returning `Flow<Boolean>`.
  * `getAllTabsFlow()`: Emits active browser tab array lists.
  * `getAllTabs()`: Retrieves the static flat list of tabs for JSON backups.
  * `insertTab(tab)` / `updateTab(tab)` / `deleteTab(id)` / `clearTabs()`: Tab manipulation APIs.

---

## 🏛️ The Repository Pattern (`BrowserRepository.kt`)

`BrowserRepository` coordinates actions between the UI view layer and the background database handlers, executing disk I/O operations asynchronously off the Main UI thread.

### 1. Coordinate and Execution Methods
* `insertHistory(url, title)`: Saves a history row. Automatically calls `pruneHistory(500)` to maintain lightweight database size.
* `insertBookmark(url, title, imageUrl)`: Evaluates if the URL host matches novel signatures. Auto-extracts metadata and saves the bookmark with `isNovel = true` if recognized.
* `updateNovelMetadata(url, novelTitle, chapterTitle, coverImage)`: Updates active bookmark titles, covers, and last chapter indicators during active browsing.
* `updateReadingProgress(url, title)`: Automatically updates the user's reading bookmark progress. Tracks the user's active chapter URL and title so they can resume reading from the library.
* `JSON Backup Streams`: Facilitates backup serialization and transactional recovery routines under `Dispatchers.IO`.

---

## 🔍 The Advanced `extractNovelAndChapter` Pattern Matches

To support correct chapters and novel indicators inside notification bars, standard string parsing falls short. `BrowserRepository.kt` implements a deep **Regex Heuristic Engine** (`extractNovelAndChapter`) to parse titles.

### Heuristic Execution Pipeline
```
         [Input Title & URL]
                  |
                  v
       [Purge Website Suffixes]  (Strips tags like "novelhall", "timotxt")
                  v
   [Scan for English Chapter Regex]  ---> Found? ---> [Return parsed fields]
                  |
                Failed
                  v
   [Scan for Chinese Chapter Regex]  ---> Found? ---> [Return parsed fields]
                  |
                Failed
                  v
   [Scan for Roman Numeral Regex]   ---> Found? ---> [Return parsed fields]
                  |
                Failed
                  v
[Parse Chapter information from URL Path] ---------> [Verify & return finalized tokens]
```

### RegEx Matching In-Memory Core (Kotlin Implementation)

The RegEx matching parsing process strips hostnames, tags, and standard headers to split titles into exact Pairs representing a clean `Novel Title` and a well-formatted `Chapter Subtitle` (e.g. *Chapter 123*):

```kotlin
fun extractNovelAndChapter(title: String, url: String): Pair<String, String> {
    // Stage 1: Clean junk prefixes and suffixes
    var parsedTitle = title.trim()
        .replace(Regex("(?i)_novelhall\\.com"), "")
        .replace(Regex("(?i)_novelhall"), "")
        .replace(Regex("(?i)_timotxt"), "")
        .replace(Regex("(?i)_timotxt\\.com"), "")
        .replace(Regex("(?i)_timotxt", RegexOption.IGNORE_CASE), "")
        .replace(Regex("(?i)- Wtr-Lab(?i)"), "")

    // Stage 2: English Chapter Parsing (e.g., Chapter 123: The Beginning)
    val englishChapterRegex = Regex("(?i)Chapter\\s*(\\d+|\\d+\\.\\d+)\\s*(?:-|:)?\\s*(.*)")
    val engMatch = englishChapterRegex.find(parsedTitle)
    if (engMatch != null) {
        val chapterNum = engMatch.groupValues[1]
        val chapterName = engMatch.groupValues[2].trim()
        val novelPart = parsedTitle.substring(0, engMatch.range.first).trim()
        val formattedChap = "Chapter $chapterNum" + (if (chapterName.isNotEmpty()) " - $chapterName" else "")
        return Pair(if (novelPart.isNotEmpty()) novelPart else "Web Novel", formattedChap)
    }

    // Stage 3: Chinese Chapter Parsing (e.g., 第123章 章节名称)
    val chineseChapterRegex = Regex("(第\\s*\\d+\\s*[章节卷]\\s*)(.*)")
    val cnMatch = chineseChapterRegex.find(parsedTitle)
    if (cnMatch != null) {
        val chapterNum = cnMatch.groupValues[1].trim()
        val chapterName = cnMatch.groupValues[2].trim()
        val novelPart = parsedTitle.substring(0, cnMatch.range.first).trim()
        val formattedChap = "$chapterNum" + (if (chapterName.isNotEmpty()) " - $chapterName" else "")
        return Pair(if (novelPart.isNotEmpty()) novelPart else "Web Novel", formattedChap)
    }

    // Stage 4: Roman Numeral Chapters (e.g., Act II, Chapter III)
    val romanChapterRegex = Regex("(?i)Chapter\\s+([IVXLCDMivxlcdm]+)\\s*(?:-|:)?\\s*(.*)")
    val romanMatch = romanChapterRegex.find(parsedTitle)
    if (romanMatch != null) {
        val romanNum = romanMatch.groupValues[1].uppercase()
        val chapterName = romanMatch.groupValues[2].trim()
        val novelPart = parsedTitle.substring(0, romanMatch.range.first).trim()
        val formattedChap = "Chapter $romanNum" + (if (chapterName.isNotEmpty()) " - $chapterName" else "")
        return Pair(if (novelPart.isNotEmpty()) novelPart else "Web Novel", formattedChap)
    }

    // Stage 5: Fallback URL Path Parsing Heuristics
    val pathRegex = Regex("/(?:chapter|read|novel)/(\\d+|[ivxlcdm\\d-]+)/?")
    val pathMatch = pathRegex.find(url)
    if (pathMatch != null) {
        val rawChapterId = pathMatch.groupValues[1].replace("-", " ").capitalize()
        return Pair(parsedTitle, "Chapter $rawChapterId")
    }

    return Pair("Web Novel", parsedTitle)
}
```
This multi-layered Regex mechanism has proven highly effective at ensuring correct title indicators inside systems structures across all supported domains.
