# AI Agent Onboarding Checklist & Architectural Rules (AGENTS.md)

This file serves as the permanent context, directory roadmap, and operational memory for any future AI Coding Agent continuing development on the Novel Reader application. **Do not remove or modify this file unless explicitly instructed by the user.**

---

## 🧭 Project Coordinates & Tech Stack
- **Context**: A premium, native Android web browser optimized specifically for reading, listening (Text-To-Speech), and auto-translating web novels.
- **UI Architecture**: Jetpack Compose, Material Design 3.
- **Async Concurrency**: Kotlin Coroutines & Kotlin Flows (`StateFlow`, `collectAsStateWithLifecycle`).
- **Data Persistence**: Android Room SQLite Local Database (`tabs`, `history`, `bookmarks`).
- **Web Engine**: Android System WebKit WebViews managed in a global concurrent pool (`MainActivity.activeWebViewsPool`) to avoid context leaks.

---

## 🧭 Project Documentation Index
For deep architectural and implementation details, refer to:
- [🚀 Architecture Overview](docs/ARCHITECTURE_OVERVIEW.md): System-wide topology and state flow synchronization rules.
- [📱 Core Engine Manual](docs/CORE_ENGINE.md): Detailed internals of background services, bridges, view models, and telemetry.
- [🎨 UI Subsystem Guide](docs/UI_LAYER.md): Compose layouts, views, settings overlays, and JS scrapers.
- [🗄️ Data Layer Schema](docs/DATA_LAYER.md): Room database entities, DAO queries, repositories, and Regex parsing heuristics.
- [🛠️ Complete Fixes Log](docs/fixes.md): Exhaustive debugging history, memory limiters, safe streams, and anti-CAPTCHA implementations.

---

## ⚠️ Critical Lessons & Past Defect Diagnostics

Ensure you read this section before making any changes to WebView behaviors, lifecycle hooks, or navigation methods to prevent regressions:

### 1. Active Tab URL Synchronization & Background Hijacking (CRITICAL)
- **Defect**: Inactive background tabs finishing page loads or executing timers in the background would trigger standard `@JavascriptInterface` bridge callbacks (`onUrlSynced`). If left unchecked, this would overwrite the active tab's address bar, resulting in flashing, random redirects, or freezing the screen back to previous URLs.
- **Rule**: You **MUST** always safely resolve the *currently active tab* via `viewModel.currentTab.value` and verify that the triggering WebView belongs to the active tab:
  ```kotlin
  val currentActive = viewModel.currentTab.value
  val isWebUrl = syncedUrl.startsWith("http://") || syncedUrl.startsWith("https://")
  if (isWebUrl && currentActive?.id == tab.id && currentActive.url != syncedUrl) {
      // Execute UI address updates only if active!
  }
  ```
- **Constraint**: Do not use stale closures (`activeTab?.id`) where references can get mismatched during page swaps.

### 2. Modern Web Client User-Agents (UA)
- **Defect**: Truncated, malformed, or typo-ridden client user-agents (e.g. `Android 14; K; K`) trigger strict browser bot safeguards, causing host sites to dump into blank pages, display infinite loops of security challenge validations, or reject connections with 403 Forbidden screens.
- **Rule**: When setting user-agent, always apply complete, standard, representative mobile or desktop client headers:
  ```kotlin
  // Standard Mobile User-Agent:
  val mobileUA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
  // Standard Desktop User-Agent:
  val desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
  ```

### 3. Integrated Coroutine Telemetry (`com.example.WtrLogManager`)
- **Utility**: In-app diagnostic logging operates off an in-memory thread-safe state list capped at 100 historical records, displaying system actions, page loads, and audio bounds.
- **Rule**: Write logs proactively using `WtrLogManager.log(context, "message")`. Disk writing of serialized logs is delegated cleanly to a dedicated background Coroutine Scope `loggerScope` running on `Dispatchers.IO` to protect UI responsiveness.
- **Settings Toggle**: Logging is user-controlled via `enable_logs` in Shared Preferences. Respect this flag internally.
- **Exporting Logs**: Under active debugging, users can download logs as plain text files through the "Save as TXT" option in the Diagnostic popup modal, which launches standard Storage Access Framework loaders.

### 4. Smart Saving of Paragraphs, Translate, and TTS Auto-Next Coordination
- **Context**: The TTS polyfill replaces standard browser WebSpeech bindings. Reading position autosave (`remember_paragraphs`), auto-translation scraper, and next-chapter loading have overlapping timing cycles.
- **Rule**: Keep state clean. Prior to starting new speech sequences or navigating, cancel previous translation scraping coroutines and flush active speaker channels (`QUEUE_FLUSH`) inside the Foreground service context to prevent multi-voice overlays or database state conflicts.

### 5. Integrity of the JavaScript Bridge for Wtr-Lab (CRITICAL AD-BLOCKER DETECTION WARNING)
- **Context**: The JavaScript bridge (`WtrWebAppInterface` and the window injection bindings) must **NEVER** be removed, bypassed, or globally disabled for wtr-lab.com or companion reader engines. Wtr-Lab employs an extremely sensitive, proprietary script-monitoring system that tracks background TTS playback and WebSpeech api signals.
- **Rule**: Bypassing or deleting the bridge interactions causes the target website script tracking to fail, which triggers its automated security defenses, labeling the browser as having a hostile **ad-blocker** fully active. This will instantly halt webpage loading or lock the reader view. Always route speech play/pause/cancel events back through the bridge callbacks or let the background timer mechanism handle takeovers seamlessly.

### 6. Tab-scoped TTS Isolation and Concurrent Playback
- **Context**: Users expect to keep listening to TTS audio on tab A while switching to tab B to search or browse other chapters. 
- **Rule**: Standard player controls are scoped to the active TTS tab ID (`WtrAudioControlBridge.activeTtsTabId`). Playback state resets or transitions must never occur upon tab switching unless the user explicitly starts a new TTS session on the newly opened tab, in which case the previous tab's TTS is cleared and the new tab takes ownership.

### 7. Infinite Layout Chapters and Visual Scroll Alignment
- **Context**: Sites like `webnovel.com` load chapters dynamically inside sequential visual containers when the reading user scrolls vertically. 
- **Rule**: Scraper routines must extract paragraphs across multiple concurrent content containers, keeping track of elements in the DOM. To start reading naturally from the user's current reading point rather than always starting from paragraph 1, the script must calculate elements' positions in the viewport, selecting the paragraph closest to the top of the viewport (`rect.top - 100`). Additionally, junk filter arrays must explicitly purge any background-inserted ad-blocker detection warn texts.

### 8. Robust Backup & Restore Synchronization via SAF Uri
- **Context**: Users backing up their data need to migrate their browser configurations, tabs, bookmarks, and histories onto fresh installs.
- **Rule**: When serializing/deserializing backup blobs via JSON, always format nested fields safely. Database clearing instructions (`clearHistory`, `clearBookmarks`, `clearTabs`) MUST execute in sequence before inserting entries from the parsed JSON, and the ViewModel must successfully re-evaluate the currently active tab ID state (`_currentTab.value = restoredTab`) to prevent empty WebViews from initializing on startup or failing to load. Ensure all I/O streams are executed off the Main thread under `Dispatchers.IO` using a validated `android.net.Uri` input.

### 9. Google Translate CAPTCHA Anti-Looping and Interceptors
- **Context**: Rapid chapter-flipping on regional untrusted links translation proxy loops mimics automated search bots, which triggers aggressive Google CAPTCHA challenge screens.
- **Rule**: Include an explicit anti-CAPTCHA timing lock (4500ms Handler post delay and user notification Toasts) on translated chapter change requests when executing auto-advancing TTS routines.

---

## 📜 Complete Codebase Map

- `/.github/workflows/build-apk.yml`
  - *CI/CD pipeline: automates building debug APKs upon pushing to GitHub `main` branch, generating SemVer patches, and compiling Github releases with APK binaries.*
- `/app/src/main/java/com/example/MainActivity.kt`
  - *Main entry point, bootstrap, permission handlers, theme, and WebView pool listeners.*
- `/app/src/main/java/com/example/BrowserViewModel.kt`
  - *Core VM: tab operations, history logs, search inputs, query validation, and export/import JSON backup logic.*
- `/app/src/main/java/com/example/WtrLogManager.kt`
  - *Thread-safe ring-buffer list logging operations, persisted via split serialization inside SharedPreferences using background Coroutines.*
- `/app/src/main/java/com/example/WtrWebAppInterface.kt`
  - *Bridges Javascript string variables, paragraph indexes, and media play states into Android native JVM streams.*
- `/app/src/main/java/com/example/WtrBrowserService.kt`
  - *Foreground service handling CPU locks, lockscreen notifications throttled at 1.5s gates, and TextToSpeech queues.*
- `/app/src/main/java/com/example/BackupEncryption.kt`
  - *Secure backup utility generating hardware Store-backed AES keys for database file encryption and decryption routines.*
- `/app/src/main/java/com/example/CrashReportManager.kt`
  - *Thread boundary uncaught exception handler creating offline crash logs inside private application storage.*
- `/app/src/main/java/com/example/PerformanceMonitor.kt`
  - *Background thread loop validating system RAM consumption, triggering heap alerts, and GC requests.*
- `/app/src/main/java/com/example/NetworkErrorHandler.kt`
  - *Exponential backoff retry wrapper to automatically recover from slow or intermittent connection errors.*
- `/app/src/main/java/com/example/ui/`
  -  `BrowserAppScreen.kt`: *The core parent container rendering search bar, bottom audio shelf, and nested WebViews. Includes SAF txt logger savers.*
  -  `SettingsDialog.kt`: *Settings panel for speech parameters, force-dark css, ad-blocker, cookies, diagnostic options, and the interactive JSON Backup / Restore importer launcher using Uri streams.*
  -  `ChromeNewTabPage.kt`: *Default screen rendering shortcuts, recent history rows, and search inputs.*
  -  `TabsPanel.kt`: *Double-grid UI folders to manage standalone tabs or nested tab folders.*
- `/app/src/main/java/com/example/data/`
  -  *Room database configurations decoupling database tables (`BookmarkEntry`, `HistoryEntry`, `TabEntry`) with simple repository patterns, advanced RegEx chapter title parsers, and clean table-wipe queries.*

---

## 🌐 Supported Website Registry & Scraper Patterns
The reader engine has specialized scraper logic for the following domains:
- **Wtr-Lab** (`wtr-lab.com`): Primary target with deep JS bridge integration.
- **WebNovel** (`webnovel.com`): Dynamic container extraction with ad-blocker detection bypass.
- **NovelHall / FanMtl / NovelBin / FreeWebNovel**: standard CSS selector extraction.
- **TimoTxt / Novel543 / Twkan**: High-priority auto-translation domains with specialized junk filtering.
- **NovelHub** (`novelhub.net`): English-first domain with standard `#chr-content` or `.chapter-content` extraction.
- **NovelHubApp** (`novelhubapp.com`): Single-page reader app with dynamic navigation. Tracking implemented via client-side hash injection to ensure chapter uniqueness in history.
