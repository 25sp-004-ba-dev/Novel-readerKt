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

### 3. Integrated Telemetry (`com.example.WtrLogManager`)
- **Utility**: In-app diagnostic logging operates off an in-memory thread-safe state list capped at 100 historical records, displaying system actions, web page loads, page redirections, and audio sync bounds.
- **Rule**: Write logs proactively of all operations, including navigation bounds, audio selections, paragraph tracking status, or file reads, using:
  ```kotlin
  com.example.WtrLogManager.log(context, "Descriptive log event")
  ```
- **Settings Toggle**: Logging is user-controlled via `enable_logs` in Shared Preferences. Respect this flag internally.

### 4. Smart Saving of Paragraphs, Translate, and TTS Auto-Next Coordination
- **Context**: The TTS polyfill replaces standard browser WebSpeech bindings. Reading position autosave (`remember_paragraphs`), auto-translation scraper, and next-chapter loading have overlapping timing cycles.
- **Rule**: Keep state clean. Prior to starting new speech sequences or navigating, cancel previous translation scraping coroutines and flush active speaker channels (`QUEUE_FLUSH`) inside the Foreground service context to prevent multi-voice overlays or database state conflicts.

---

## 📜 Complete Codebase Map

- `/app/src/main/java/com/example/MainActivity.kt`
  - *Main entry point, bootstrap, permission handlers, theme, and WebView pool listeners.*
- `/app/src/main/java/com/example/BrowserViewModel.kt`
  - *Core VM: tab operations, history logs, search inputs, query validation and redirection parsing.*
- `/app/src/main/java/com/example/WtrLogManager.kt`
  - *Thread-safe ring-buffer list logging operations, persisted via split serialization inside SharedPreferences.*
- `/app/src/main/java/com/example/WtrWebAppInterface.kt`
  - *Bridges Javascript string variables, paragraph indexes, and media play states into Android native JVM streams.*
- `/app/src/main/java/com/example/WtrBrowserService.kt`
  - *Foreground service handling CPU locks, lockscreen notifications throttled at 1.5s gates, and TextToSpeech queues.*
- `/app/src/main/java/com/example/ui/`
  -  `BrowserAppScreen.kt`: *The core parent container rendering search bar, bottom audio shelf, and nested WebViews.*
  -  `SettingsDialog.kt`: *Settings panel for speech parameters, force-dark css, ad-blocker, cookies, and Session Logging toggle.*
  -  `ChromeNewTabPage.kt`: *Default screen rendering shortcuts, recent history rows, and search inputs.*
  -  `TabsPanel.kt`: *Double-grid UI folders to manage standalone tabs or nested tab folders.*
- `/app/src/main/java/com/example/data/`
  -  *Room database configurations decoupling database tables (`BookmarkEntry`, `HistoryEntry`, `TabEntry`) with simple repository patterns.*
