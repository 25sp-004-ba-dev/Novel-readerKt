# Novel Reader: Premium Web Novel Browser & Foreground Speech Engine

Novel Reader is an advanced, high-performance web browser designed for native Android environments, built from the ground up utilizing **Jetpack Compose**, **Kotlin Coroutines / Flow**, and **Android WebKit**. It is specifically engineered to bridge the gap between static web-based content and rich, fluid media-player-like playback. It caters specifically to the unique visual, structural, and linguistic requirements of reading, auto-translating, and listening to web novels globally.

By establishing a bidirectional JavaScript-to-Native synchronization bridge, Novel Reader converts standard web chapter paragraphs into sequential audio nodes, supported by lockscreen controls, smart autoscroll pagination, secure automated translation routing, and an active resource-filtering ad-blocker.

---

## 📖 Technical Documentation Index
For deep architectural and implementation details, refer to the following guides:
- [🚀 Architecture Overview](docs/ARCHITECTURE_OVERVIEW.md): System-wide topology and state flow synchronization rules.
- [📱 Core Engine Manual](docs/CORE_ENGINE.md): Detailed internals of background services, bridges, and logging.
- [🎨 UI Subsystem Guide](docs/UI_LAYER.md): Compose layouts, injected styles, and scraper logic documentation.
- [🗄️ Data Layer Schema](docs/DATA_LAYER.md): Room database entities, serialization, and backup/restore procedures.
- [🛠️ Complete Fixes Log](docs/fixes.md): Extensive historical bug-fixes log, crash preventions, safe stream allocations, and Google Translate anti-challenge delays.
- [🧭 Agent Onboarding (AGENTS.md)](AGENTS.md): Critical rules, defect history, and operational memory for developers.

---

## 🚀 Supported Website Registry
The reader engine has specialized scraper logic and optimized playback flows for:
- **Wtr-Lab** (`wtr-lab.com`) - Native companion reader.
- **WebNovel** (`webnovel.com`) - Infinite scroll support.
- **NovelHall / FanMtl / NovelBin / FreeWebNovel** - Standard web scrapers.
- **TimoTxt / Novel543 / Twkan** - Auto-translation optimized.
- **NovelHub** (`novelhub.net`) - Enhanced English reader support.
- **NovelHubApp** (`novelhubapp.com`) - Optimized SPA reader with history tracking.

---

## 🎨 Visual Identity & System Architecture

```
                               ┌──────────────────────────────────┐
                               │       JETPACK COMPOSE UI        │
                               │  - Tabs Grid Grid/List Manager   │
                               │  - Reading Theme Overlay Sheets  │
                               │  - TTS Speed & Accent Sliders    │
                               └────────────────┬─────────────────┘
                                                │
                                                ▼ (Renders Layout)
                               ┌──────────────────────────────────┐
                               │        WEBVIEW CONTAINER         │
                               │  - Custom Resource Interceptor   │
                               │  - CSS Dark Custom Filters       │
                               │  - Text Zoom Engine (WebKit)     │
                               └────────────────┬─────────────────┘
                                                │
                                                ├─► JS-to-Native Web Interface [WtrWebAppInterface]
                                                │   - Captured Paragraph Streams
                                                │   - Media Poll Heartbeats
                                                │
                                                ▼ (Event Forwarding)
                               ┌──────────────────────────────────┐
                               │      WTR AUDIO CONTROL BRIDGE    │
                               │  - Playback State Flow Routing   │
                               │  - Active Segment Indices Tracker │
                               └────────────────┬─────────────────┘
                                                │
                                                ▼ (IPC / Callbacks)
                               ┌──────────────────────────────────┐
                               │  FOREGROUND BACKGROUND SERVICE   │
                               │  - Partial WakeLock & WifiLock   │
                               │  - Android MediaSession Metadata │
                               │  - Notification Rate Throttler   │
                               │  - Native TextToSpeech Loop KTX  │
                               └────────────────┬─────────────────┘
                                                │
                                                ▼ (Data Persistence)
                               ┌──────────────────────────────────┐
                               │      LOCAL SQLITE DATABASE       │
                               │  - Tab Entries & Nested Groups   │
                               │  - Bookmark Reading Positions    │
                               │  - Visited Site History Logs     │
                               └──────────────────────────────────┘
```

---

## 🚀 Complete Feature Catalog

### 1. Multi-Profile Visual Reader Themes & Full-Screen Settings
* **Dedicated Settings Panel**: Provides a comprehensive full-screen interface for granular controls over themes, text-zoom scaling, adblockers, and full JSON database backups without cluttering the reading view.
* **Distraction-Free Reading Profiles**: Novel Reader includes six custom-designed, eye-safe visual styles that automatically color-pair headings, background containers, and navigation items:
  1. **Slate Grey**: Crisp text contrasted against deep, charcoal backdrops.
  2. **Night Dark**: Ultra-low luminance black theme optimized for pure OLED panels.
  3. **Warm Sepia**: Comforting, warm paper coloration to minimize blue light fatigue.
  4. **Forest Green**: Pine-inspired forest theme, popular among e-ink reading readers.
  5. **Ocean Blue**: Deep marine wash for high-contrast nighttime readings.
  6. **Pristine Light**: Clean, standard light theme with high text resolution.
* **Force Dark CSS Injector**: For sites lacking native night modes, the browser overrides existing stylesheets by dynamically injecting custom global filters (`injectForceDarkCss`). It forces white backgrounds to `#121212` and text elements to high-clarity `#f1f1f1` without breaking the host page layouts.
* **Native WebKit Text Zoom**: Adjusts browser text sizing through native WebKit scaling variables (`textZoom = customTextZoom`), ranging from **Compact** ($95\%$) through **Default** ($115\%$), **Medium** ($130\%$), **Large** ($145\%$), and **Huge** ($160\%$), ensuring text reflows gracefully across folding screens and high-density mobile displays.

### 2. Bidirectional JS-to-Native TTS Engine
* **The WebSpeech API Mock Polyfill**: Novel Reader injects a custom-compiled JavaScript script (`injectTtsBridgeScript`) that overrides default browser APIs, mapping `window.speechSynthesis` and `SpeechSynthesisUtterance` definitions directly onto native Android code.
* **Back-to-Front Paragraph Extraction**: Scans layout elements to systematically compile clean paragraph lists (`<p>`), filtering out irrelevant UI noise, ads, footers, related chapter lists, and social widgets before sending.
* **Synchronized Paragraph Scrolling**: Automatically calculates DOM element vertical offsets as native speech loops progress, commanding the WebKit viewport to glide smoothly and center each active sentence.
* **Word Boundary Highlights**: Employs TTS token boundaries to wrap the reading node inside a custom highlighting style sheet, giving the reader immediate visual focus.
* **Visual Scroll-Position Reading Alignment**: Aligns speaking start index with the text segment the user is actively viewing on screen, by analyzing element scroll metrics within the active viewport.
* **Infinite Scroll Multi-Container Synthesis**: Adapts to scrolling chapters by extracting and indexing text blocks across multiple sequential containers dynamically.

### 3. Background Audio Service & Lockscreen MediaSession
* **Persistent Foreground Lifespan**: Wraps playback inside `WtrBrowserService`. Users can power off their screens, answer messages, or use external system apps without speech termination.
* **WakeLock & WifiLock Aggressiveness**: Utilizes dedicated Power & Wifi management locks, guaranteeing continuous background thread processing and network stream buffering during deep CPU sleep cycles.
* **Lockscreen Notification Throttler**: Incorporates a hardware state-change debouncer. While normal speech indices switch paragraphs at a rapid rate, updates to the native notification drawer are filtered through a $1.5\text{s}$ interval gate, eliminating the Android system `"Package enqueue rate is ... Shedding"` warning, while keeping lockscreen media labels synchronized.
* **System MediaSession Controls**: Full integration with native lockscreen, drawer, and Bluetooth accessory triggers (Play/Pause, Next Paragraph, Previous Paragraph, Speed Multiplier).

### 4. Smart Google Translate Integration & Redirection Shuffler
* **Auto-Translation Proxying**: Seamlessly redirects regional web domains (such as untamed Chinese raw-text sites, e.g. `timotxt.com` or `novelhall.com`) through secure, translated Google proxy pipelines with high rendering priority.
* **Infinite Redirection Loop Shield**: Tracks translation timestamps and retries. If the proxy fails to translate and redirects back with high frequency (exceeding 2 attempts within a 10-second gap on the same URL basis), translation is marked as looping and skipped, allowing raw content to load and prompting the viewer.
* **Mostly-Chinese Content Polling**: The background scraper checks the Unicode ranges (`\u4e00`..`\u9fa5`) of newly loaded text. It pauses speaking if it detects raw untranslated text, waiting up to $25$ automated extraction loops (each spaced by $650\text{ms}$) to let Google's cloud proxy finish text compilation to avoid speaking raw Chinese.

### 5. High-Performance Ad-Blocker & Resource Interceptor
* **Outbound Request Filter**: Intercepts on-page outbound calls inside `shouldInterceptRequest`. It runs a high-performance filtering check matching known ad networks, analytic trackers, redirect blocks, popunder scripts, and floating popups (like Google Adsense, DoubleClick, Taboola, Ezoic, and Outbrain) to protect bandwidth and maintain clean screen layouts.

### 6. Tab Grid Folder Manager
* **Grid and Nesting Support**: Manage countless open folders. Supports standalone tabs or custom stacked **Tab Groups / Folders** for organizing various light-novel catalogs.
* **Desktop Rendering Toggle**: Allows user-agents to simulate flat-desktop viewports on specific tabs to retrieve mobile-blocked novel chapters.
* **Local Room Database State Saving**: Tab states, ordering indices, open folder groupings, history logs, and chapter bookmarks are immediately saved into Room structures, preserving open pages even during system reboots.
* **Tab-Scoped Audio Sessions & Thread Isolation**: Native Text-To-Speech queues are tracked and bound strictly to their originating Tab ID. Switching tabs no longer halts background reading, allowing users to browse concurrent articles, run searches, or open parallel index groups without overlapping audio channels. Previous playback is safely released only when a new TTS sequence is explicitly started.

### 7. Integrated Diagnostics, Automated Translated Title Syncing, & Logs
* **Translated Bookmarks Synchronization**: Advanced bridging parses both DOM metadata (cover images, H1 titles) and Javascript `document.title` on standard intervals. Even if a user bookmarks a page before automatic translation completes, local databases seamlessly update behind-the-scenes using root host-matching to ensure English translations automatically display across the History and Bookmark grids. 
* **System Diagnostic Logs (`WtrLogManager`)**: An in-app diagnostic recorder captures critical web views lifecycle events (such as page starts, loaded status, synced JS bridges, and translation triggers). Operates in a thread-safe ring-buffer capping memory at the last 100 historical logs, with native options to view/clear logs dynamically via the main `DropdownMenu` ("View Diagnostic Logs") and a dedicated on/off toggle in Settings to protect user privacy.
* **Hijacking Prevention Shield**: Prevents background or inactive tabs from hijacking active navigation hooks. Injected JavaScript page synchronization bridges are strictly restricted, validating tab associations only if `currentActiveTab?.id == tab.id`, preventing unwanted redirects, URL freezing, or random blank pages.
* **Adaptive User-Agent Strings**: Handheld user-agent overrides utilize standard Android identifiers preventing modern servers from mistaking the internal WebKit as an unidentifiable automated robot client (eliminating infinite Cloudflare challenge loops).

### 8. Full State Backup & Restore (JSON Exporter)
* **Single-File Portability**: Users can export their complete browser configuration (SharedPreferences settings, all histories, all bookmarks, and open tabs with desktop-mode states) into a highly condensed JSON file.
* **Storage Access Framework (SAF)**: Utilizes native `ActivityResultContracts.CreateDocument` and `OpenDocument` to provide a secure dialog where users can select download Folders or upload backups.
* **Transactional Restores**: Database states are safely cleared and updated sequentially under `Dispatchers.IO`. Restored tabs and settings are immediately re-loaded into memory, auto-refreshing the screen back into the previous reading state upon upload.

---

## 📁 Class-by-Class Codebase Breakdown

### 1. Core Lifecycle & UI Coordinator: `MainActivity.kt`
* **Purpose**: Performs primary boot initialization, constructs the Compose Material 3 view layers, handles Android runtime permissions, orchestrates dynamic theme changes, registers WebKit client events, and initializes `WtrLogManager`.
* **Key Components**:
  * `BrowserAppScreen`: Houses the standard `Scaffold` incorporating top search bars, bottom media control controllers, slide-up drawers, setting modals, and the WebKit viewport.
  * `TabsPanel`: Configures tab viewports inside a dynamic two-column grid layouts with contextual menu folders.
  * `HistoryAndBookmarksDialog`: Custom sliding pop-up displaying past reading logs and togglable bookmarks.
  * `SettingsDialog`: Control room for AdBlock toggles, session logging preferences, autoscroll preferences, audio speeds, force-dark parameters, and cookies databases optimization.

### 2. Tab States & Search VM: `BrowserViewModel.kt`
* **Purpose**: Abstracted ViewModel operating on `viewModelScope`, acting as the single source of truth for navigation state, text search engine configurations, active tabs, and history logs.
* **Key Components**:
  * `allHistory`, `allBookmarks`, `allTabs`: `StateFlow` structures feeding the Compose UI asynchronously.
  * `addNewTab()`, `switchToTab()`, `closeTab()`: Core state machine tracking tab lifecycles (extensive diagnostics logging added).
  * `groupTabs()`, `removeFromGroup()`: Creates and destroys grouped folders.
  * `cleanInputUrl()`: Evaluates string inputs. It automatically matches short keywords (e.g. `nov` -> `novelhall.com`, `timo` -> `timotxt.com`, `wtr` -> `wtr-lab.com`) or runs query encoding algorithms to format inputs for preferred engines (Google, DuckDuckGo, Bing).

### 3. Low-Level Web Interface Bind: `WtrWebAppInterface.kt`
* **Purpose**: Registers JavaScript-to-Native channel hooks under the identifier `window.WtrBridge` inside the WebView browser scope.
* **Exposed Methods**:
  * `@JavascriptInterface postPlaybackState(isPlaying: Boolean, title: String, subtitle: String)`: Captures actual play/pause states directly from interactive on-page players.
  * `@JavascriptInterface speakNative(text: String, rate: Float, pitch: Float, lang: String)`: Intercepts standard javascript speaking streams to redirect them to the native speech engine.
  * `@JavascriptInterface cancelNative()`, `pauseNative()`, `resumeNative()`: Hooks native callbacks to DOM speech buttons.

### 4. Background Audio Service Controller: `WtrAudioControlBridge.kt`
* **Purpose**: Global, background-safe state router bridging the visual Compose threads, the multi-tab WebView container, and the background service context to prevent input/playback latency.
* **Exposed Controls**:
  * `playTrackInputList`: Live state flowing the currently captured list of paragraphs.
  * `currentTrackIndex`: Monitors the active paragraph number.
  * `isPlayerRunning`: Signals if the speech loop is active.
  * `onWebViewProgressTrigger`: Dispatches TTS lifecycle callbacks back to the WebView to support visual highlighting and scrolling.

### 5. MediaSession & TTS Execution: `WtrBrowserService.kt`
* **Purpose**: Android Foreground Service (`Service`) handling CPU locks, system MediaSession interfaces, locks, audio output focuses, and the physical TTS execution queue.
* **Key Components**:
  * `setupMediaSession()`: Registers lockscreen receiver actions (`onPlay`, `onPause`, `onSkipToNext`, `onSkipToPrevious`).
  * `setupTtsUtteranceListener()`: Monitors speech ranges to fire page element focuses on character milestones.
  * `speakText()`: Executes text blocks via standard `TextToSpeech` API using `QUEUE_FLUSH` flag for high responsiveness.
  * `acquireWakeLock()`, `acquireWifiLock()` : Holds hardware locks during background screen pauses.

### 6. Diagnostics Engine: `WtrLogManager.kt`
* **Purpose**: Collects system and navigation telemetry inside an thread-safe ring buffer list. Persists logs to `SharedPreferences` as an split-serialized string for continuous debugging across unexpected cold launches. Supports settings toggles and remote UI clearance operations.

---

## 🗄️ Database Architecture & Schemas (Room Entity Schemas)

Novel Reader employs **Android Room DB** to store application configurations, user assets, and navigation structures. Destructive schema migrations are bypassed using `fallbackToDestructiveMigration()` during developmental cycles since localized data are stateless.

### 1. `tabs` Table Schema
Maintains structural positions, user-agents, and folder grouping for all browsing tabs.

| Column Name | Data Type | Primary Key | Nullable | Default Value | Description |
| :--- | :--- | :---: | :---: | :--- | :--- |
| `id` | `INTEGER` | Yes | No | Auto-Generated | Unique ID for the tab. |
| `url` | `TEXT` | No | No | N/A | Current address loaded by this tab. |
| `title` | `TEXT` | No | No | N/A | Title of the active tab. |
| `isCurrent` | `INTEGER` | No | No | `0` (False) | Indicates if tab is visible in editor. |
| `isDesktopMode`| `INTEGER` | No| No | `0` (False) | Indicates if desktop Mode is active. |
| `groupId` | `INTEGER` | No | Yes | `NULL` | Foreign reference to tab group container. |
| `timestamp` | `INTEGER` | No | No | `System.currentTimeMillis()` | Ordering index for the tabs panel list. |

### 2. `bookmarks` Table Schema
Retains specific light novel pages and web indexes flagged by the user.

| Column Name | Data Type | Primary Key | Nullable | Default Value | Description |
| :--- | :--- | :---: | :---: | :--- | :--- |
| `id` | `INTEGER` | Yes | No | Auto-Generated | Unique ID for the bookmark. |
| `url` | `TEXT` | No | No | N/A | Address representing bookmarked page. |
| `title` | `TEXT` | No | No | N/A | Customized title of the page bookmark. |
| `timestamp` | `INTEGER` | No | No | `System.currentTimeMillis()` | Date bookmarks were created. |

### 3. `history` Table Schema
Maintains chronological reading history. It can be fully optimized and purged via settings.

| Column Name | Data Type | Primary Key | Nullable | Default Value | Description |
| :--- | :--- | :---: | :---: | :--- | :--- |
| `id` | `INTEGER` | Yes | No | Auto-Generated | Unique index ID. |
| `url` | `TEXT` | No | No | N/A | Visited page URL. |
| `title` | `TEXT` | No | No | N/A | Title parsed from page headers. |
| `timestamp` | `INTEGER` | No | No | `System.currentTimeMillis()` | Visit timestamp. |

---

## 🧬 Architectural Flows & Execution Pipelines

### 1. The TTS Paragraph Scraping, Queueing, & Focusing Pipeline

```
[Page finish / Nav] ──► Inject injectTtsBridgeScript ──► Register window.speechSynthesis
                                                                  │
                                                                  ▼
[Start TTS Command] ◄── WebView evaluate JavaScript ◄── Scan the DOM for paragraph text list (<p>)
         │
         ▼
[Convert array to JSON strings] ──► Forward via WtrBridge.speakNative()
                                              │
                                              ▼
[Populate WtrAudioControlBridge playTrackInputList] ──► Wake WtrBrowserService Foreground
                                                                  │
                                                                  ▼
[Play track at Current Index] ◄─────────────── Speak paragraph via android.speech.tts
             │                                                    │
             │ (On Range Boundary callback)                        │ (On Done callback)
             ▼                                                    ▼
Highlight reading words in black font     Seek current card index + 1 in playlist
Scroll WebView viewport into position    Load text segment / trigger speak loop
```

### 2. The Auto-Translation Polling, Redirection Guard, & Thread Safety Loop

```
               [URL Navigation Requested]
                           │
                           ▼
          [Does URL fit translation filters?]
             ├── NO ───────────────────────────────────► [Load Webpage Normatively]
             └── YES
                  │
                  ▼
          [Verify Loop Safeguards]
          Is the URL Base identical to a previous visit?
          AND did visit occur in the last 10 seconds?
             ├── YES (Count >= 2 Attempts) ────────────► [Skip Translation / Load Raw URL]
             └── NO (Increment Counts / Save Time)
                  │
                  ▼
          [Redirect through Google Translate Link]
                  │
                  ▼
          [Chapter Document Loaded]
          Cancel previous extraction coroutine tasks to prevent playback overlays.
                  │
                  ▼
          [Wait Polling Loop (Max 25 attempts, 650ms intervals)]
          Has text successfully rendered?
          Analyze Unicode densities: Count Chinese glyphs \u4e00..\u9fa5
             ├── High density remaining (Google Proxy still translating) ──► Re-loop / Delay
             └── Low density verified (English translated text detected!)
                  │
                  ▼
          [Execute TTS Queue Scraper & Play First Chunk]
```

### 3. Resource Interceptor Pipeline (Popups & Ad Blocker)

```
                     [Outbound Request (URL)]
                                │
                                ▼
                   [Is AdBlocker Toggle ON?]
                      ├── NO ──────────────────► Pass request to system layer
                      └── YES
                           │
                           ▼
             [Evaluate URL in low-latency loop]
             Does it match doubleclick, googleads, googlesyndication,
             popunder, ezoic, scorecards, or outbrain?
                      ├── YES ────────────────► Return empty ByteArray WebResourceResponse (0 bytes)
                      └── NO ─────────────────► Pass request to system layer
```

---

## 🛠️ Build Requirements & Setup Manual

To build and compile this application locally or via continuous integration, follow these instructions:

### System Prerequisites
* **Android Studio**: Koala (2024.1.1) or higher.
* **JDK**: Version 17 (Required by Gradle 8.4+).
* **Android Gradle Plugin (AGP)**: Version `8.4.0`+.
* **Kotlin Version**: Required version `1.9.0`+.
* **Min Native SDK**: Level API 26 (Android 8.0, matching modern background limits).
* **Compile / Target SDK**: Level API 34 (Android 14, requiring explicit media type configurations).

### 🤖 CI/CD GitHub Release Pipeline
We have configured a fully automated continuous integration workflow inside `.github/workflows/build-apk.yml`.
* **Execution Trigger**: Upon pushing code changes to the `main` branch, the pipeline executes.
* **Flow Stages**:
  1. Clones the full repository and sets up JDK 17 with caching capabilities enabled.
  2. Creates a clean temporary Android debug keystore on the runner workspace.
  3. Formulates a release candidate APK via the `gradle assembleDebug --no-daemon` compile task.
  4. Automatically determines a new SemVer release tag (patch version incremental bump).
  5. Uploads the final `.apk` file into the GitHub Releases tab and generates markdown release logs.

---

## 🛟 Developer Handover & Production Integration Manual

### 1. WebKit Process Management & Telemetry
Keep in-app log tracking active during diagnostic checks. Inspect logs inside **Dropdown -> View Diagnostic Logs** to isolate WebKit sandbox failures or empty resource blocks.

### 2. Thread Safety
All bridge interfaces running off Javascript interfaces (`WtrWebAppInterface` callback bindings) dispatch execution off a separate background thread. Any dynamic database writes or UI flow updates must utilize standard asynchronous Coroutine Scopes explicitly executing callbacks on `Dispatchers.Main` to avoid thread blockage, page stalling, or illegal state crashes.

### 3. Background Audio Service Restrictions (Android 14+)
`WtrBrowserService` requires explicit system declarations for `mediaPlayback` foreground modes. Remember to retain exact permissions (`POST_NOTIFICATIONS`) inside user scopes to draw controller elements continuously on screen lock states.

### 4. Smart Loop Prevention Strategy
Interceptions inside `shouldTranslateUrl(url)` keep tracking limits capped. If consecutive redirects happen within rapid succession, translation overrides are cancelled, releasing raw content render loops smoothly.
