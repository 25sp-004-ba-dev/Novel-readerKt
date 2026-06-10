# Wtr-Lab Novel Reader App Architecture Manual
## System-Wide Topology & Architectural Blueprint

This document provides system-wide topological details, structural boundaries, and interface definitions describing the Wtr-Lab Novel Reader browser. It is written to preserve extreme documentation, allowing any standard-level LLM or AI system to perfectly reconstruct or replicate the application's entire architecture.

---

## 🧭 Architectural Topology & Component Interlocking

The application is structured around a decentralized yet highly coordinated model utilizing **Jetpack Compose (UI)**, a **Foreground Service (Text-To-Speech background execution)**, **Android WebView pooling**, and an **SQLite local Room Database**.

The visual map below outlines how states, interfaces, bridges, and databases interlock:

```
                  +-----------------------------------+
                  |         MainActivity.kt           |--- Edge-to-Edge, permissions
                  +-----------------------------------+
                                    |
                                    v
                  +-----------------------------------+
                  |        BrowserViewModel           |--- Tabs state cache, History/Bookmarks Write
                  +-----------------------------------+
                                    |
                                    v
     +-------------------------------------------------------------+
     |                    BrowserAppScreen.kt                      | (Jetpack Compose View)
     |  - Multi-tab ViewPager                                       |
     |  - WebView Dynamic Injection Controller                      |
     |  - Bottom Audiobook Controller Shelf                        |
     +-------------------------------------------------------------+
         |                                                     |
         | (Evaluate JS Scraper / Highlight)                   | (Bridges event callbacks)
         v                                                     v
+------------------+     @JavascriptInterface       +--------------------------+
|  Active WebView  |===============================>|  WtrWebAppInterface.kt   |
|  - WebNovel.com  |                                |  - onPlaybackStateChanged|
|  - NovelHall.com |                                |  - onUrlSynced           |
+------------------+                                +--------------------------+
                                                                |
                                                                v
+------------------+                                +--------------------------+
|  WtrBrowserServ  |<===============================|  WtrAudioControlBridge   |
|  - Foreground TTS| (Observes state via StateFlow) |  (Global Event Bus /     |
|  - Media Session |                                |   State flows)           |
+------------------+                                +--------------------------+
         ^                                                     ^
         |                                                     |
         +------------------- [SQLite Room DB Cache] ----------+
```

---

## 📂 Codebase Modules & Directory Guide

To inspect specific subsets of the application, follow our architectural subsystem manuals:
* [🚀 Architecture Overview (This Document)](docs/ARCHITECTURE_OVERVIEW.md): System-wide topology and state flow synchronization rules.
* [📱 Core Engine Manual](docs/CORE_ENGINE.md): Detailed internals of background services, Javascript bridge, view models, and telemetry.
* [🎨 UI Subsystem Guide](docs/UI_LAYER.md): Compose layouts, views, settings, folders, and Web DOM selectors.
* [🗄️ Data Layer Schema](docs/DATA_LAYER.md): Room database models, queries, repositories, and Regex chapter parsing heuristics.
* [🛠️ Complete Fixes Log](docs/fixes.md): Extensive debugging history, memory leak remediations, custom Uri serializations, and anti-CAPTCHA bypasses.
* [🧭 Agent Onboarding (AGENTS.md)](AGENTS.md): Architectural lessons, defect diagnostics, and operational guidelines.

---

## 🔄 Concurrency, Thread Synchronization, and State Flow

State propagation acts entirely through type-safe, asynchronous reactive pipelines using Kotlin `StateFlow` coupled with Jetpack Compose lifecycle-aware collections (`collectAsStateWithLifecycle`).

### 1. The Global Audio State Bus (`WtrAudioControlBridge.kt`)
To decoupled the Compose visual rendering thread (UI thread) from the long-lived background service thread (worker thread run by Android's TextToSpeech engine scheduler), the global Singleton object `WtrAudioControlBridge` holds synchronous states and direct lambdas:
- `isPlaying: StateFlow<Boolean>` -> Controls lockscreen notification player state, visual indicators, play/pause toggles.
- `playTrackInputList: StateFlow<List<String>>` -> Holds the in-memory array of paragraph text blocks belonging to the active chapters spoken.
- `currentTrackIndex: StateFlow<Int>` -> Tracks active paragraph being read.
- `ttsSpeed: StateFlow<Float>`, `ttsPitch: StateFlow<Float>` -> Direct pitch and rate attributes bound locally across views.
- Callback closures (`playAction`, `pauseAction`, `nextAction`, `prevAction`, `nextChapterAction`, `playCustomParagraphAction`, `onCancelNative`) that target either the active Compose WebView's JS injection threads or Native TTS player routes.

### 2. Active Tab URL Synchronization & Background Hijacking Prevention
One of the most critical concurrency edge cases in Android WebView applications is prevention of **Background Tab Hijacking of the Address Bar**:
- **Defect Scenario**: A user opens target websites in multiple tabs. Tab A is active and being viewed. Tab B is in the background, but was loading. When Tab B finishes loading in the background, it triggers `onPageFinished`. If the browser simply binds of `onUrlSynced` or address updates globally, Tab B's loaded URL will hijack Tab A's address bar, causing unexpected page redirection, visual stutter, or web page freezes.
- **Topological Safeguard**: The `onUrlSynced` receiver inside `WtrWebAppInterface` is heavily guarded. It always dynamically resolves the *currently active tab* from the viewport (`viewModel.currentTab.value`) and checks if the WebView triggering the background event matches the active tab's unique ID:
```kotlin
val currentActive = viewModel.currentTab.value
val isWebUrl = syncedUrl.startsWith("http://") || syncedUrl.startsWith("https://")
if (isWebUrl && currentActive?.id == tab.id && currentActive.url != syncedUrl) {
    // Execute UI address updates only if active!
}
```

---

## 🔗 The JavaScript WebView Bridge Mechanics & Security

To bridge client-side Javascript scripts with Native Kotlin code, `WtrWebAppInterface` is mapped via WebView's custom injection `addJavascriptInterface`.

### 1. Injected Bridge Signatures
```javascript
window.WtrAndroidBridge = {
    postPlaybackState: function(isPlaying, novelName, chapterTitle, activeWebsite, speed, pitch) { ... },
    syncPollState: function(isPlaying, currentParagraphIndex, currentWordIndex) { ... },
    onUrlSynced: function(currentUrl) { ... }
};
```

### 2. JavaScript Bridge and Ad-Blocker/Security Interfacing (WARNING)
Many novel reading hosts (and specifically **Wtr-Lab companion servers** or companion analytics platforms) employ strict automated script-tracking loops to prevent headless browser ad-scraping and browser bots. By tracking elements such as the `window.speechSynthesis` API, mouse moves, and DOM mutations, hosts attempt to identify scrapers.
- **Automated Anti-Scraping Safeguard Warnings**: If the JavaScript bridge interactions (specifically for word tracking or WebSpeech mock integrations) are bypassed or turned off globally, host site tracking defenses detect an anomaly. They treat the browser as a hostile **"Ad-Blocker Active" scraper bot**, halting Chapter DOM rendering immediately or displaying infinite loops of `"Please stop your ad blocker to continue reading"`.
- **Architectural Binding Rule**: Native hooks must never disconnect or discard of JavaScript injections even when the player uses Native Speech fallback TTS (`playTrackInputList` parsing). State updates must echo back to the webpage DOM via `evaluateJavascript` to sync playback indicators cleanly, satisfying security challenges transparently.

---

## 🎧 Isolated Concurrent Tab TTS Segregation

When multiple tabs are opened with separate visual states, users expect consistent audio execution:
- **Rule of Isolation**: The audio stream belongs exclusively to the claimed tab ID (`WtrAudioControlBridge.activeTtsTabId`).
- Changing active browser viewing tabs (Tab A -> Tab B) must **never** disrupt the background playing TTS belonging to Tab A, unless the user starts a fresh TTS playback session elements inside Tab B.
- Initiating TTS inside Tab B claims tab-ownership, resetting Tab A's audio pipeline cleanly to prevent overlapping spoken audio voices from different tabs.

---

## 🗃️ Incremental Streaming Restoration & Database Verification

To support massive histories and bookmarks on low-end units:
- **Stream Processing**: The data layer transitions completely to incremental pull-parsing via `StreamingJsonParser.kt`, feeding tokens directly to SQL transactions without intermediate large allocations.
- **Verification Filters**: Checks standard columns in background routines to preserve database schema integrity (`validateDatabaseIntegrity()`), preventing blank URLs from entering repositories.
