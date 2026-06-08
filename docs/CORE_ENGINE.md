# Wtr-Lab Core Engine & Controller Manual
## Native Android Subsystems, Services, and Bridges

This document details the mechanics of Wtr-Lab's core JVM subsystems, detailing initialization scopes, foreground states, audio stream channels, and bridge interfaces.

---

## 📱 Detailed Component and Class Directory

Below is the exhaustive architectural documentation of every core native class inside the `com.example` namespace.

---

### 1. Main Entry & Bootloader: `MainActivity.kt`
* **Purpose**: Performs primary boot initialization, constructs the Jetpack Compose Material 3 UI container, controls runtime permissions, and acts as the lifecycle owner.
* **Namespace**: `com.example`
* **Inheritance**: `androidx.activity.ComponentActivity`
* **Key Fields & States**:
  * `permissionsGranted`: Compose mutable state flag indicating if system-level notifications are authorized.
  * `activeWebViewsPool` (Static Companion): Static memory cache to persist WebViews globally across layout transformations to eliminate WebKit context memory leaks.
* **Child Components & Associated Closures**:
  * `MainActivity.Companion.activeWebViewsPool`: `MutableMap<Long, WebView>`. In-memory pool binding WebView instances directly to the `applicationContext` of the device. This ensures background tabs can load pages without leaking Activity contexts.
  * `permissionRequestLauncher`: ActivityResultLauncher handling notifications permission checks.

---

### 2. Tab States & Central Logic Broker: `BrowserViewModel.kt`
* **Purpose**: Coordinates application states, tab collections, bookmarks, history entries, custom URL sanitizations, and JSON backup/restore flows.
* **Namespace**: `com.example`
* **Inheritance**: `androidx.lifecycle.AndroidViewModel` (initialized using `Application` contexts to persist state safely across activity destructions)
* **Key Fields & States**:
  * `_currentTab`: `MutableStateFlow<TabEntry?>`. Source of truth for the active viewport tab.
  * `allTabs`: `StateFlow<List<TabEntry>>`. Live feed of all opened tabs.
  * `allHistory`: `StateFlow<List<HistoryEntry>>`. Active browsing history stream.
  * `allBookmarks`: `StateFlow<List<BookmarkEntry>>`. Active bookmark lists.
  * `currentSection`: `MutableStateFlow<BrowserSection>`. The active panel view.
* **Key Methods**:
  * `addNewTab(url)`: Spawns a new tab, persisting it to SQLite via `BrowserRepository`. Writes custom logs.
  * `switchToTab(tab)` / `closeTab(tabId)`: Shifts focusing or destroys instances.
  * `groupTabs(tabIds, groupName)`: Groups tabs together in custom visual folders.
  * `cleanInputUrl(input)`: Sanitizes browser search strings, translating terms into query engines or domain paths.
  * `exportBackup(uri, onSuccess, onError)` / `importBackup(uri, onSuccess, onError)`: Backup logic executing on `Dispatchers.IO` to export/restore browser databases securely. Automatically encrypts exported database values in AES standard CBC mode via `BackupEncryption.kt` (Version 2 backups) and automatically decrypts them back on import with a plain JSON fallback.
  * `restoreSessionWithValidation()`: Recovers past tabs and validates startup URLs to prevent malformed/stale redirect loops.

---

### 3. Background Audio Playback Engine: `WtrBrowserService.kt`
* **Purpose**: An Android Foreground Service that hosts the TextToSpeech engine, coordinates MediaSession buttons, controls lockscreen integrations, and holds hardware CPU wake locks.
* **Namespace**: `com.example`
* **Inheritance**: `android.app.Service`
* **Key Fields & States**:
  * `ttsEngine`: `TextToSpeech?`. System-level voice synthesis engine.
  * `mediaSession`: `MediaSessionCompat?`. Active MediaSession pipeline transmitting audio states and buttons to lockscreen, notification pull-downs, and Bluetooth controllers.
  * `wakeLock` & `wifiLock`: CPU and network hardware locks ensuring continuous playback when screens are deactivated.
  * `serviceScope`: `CoroutineScope`. Lifecycle-bound scope utilizing a `SupervisorJob` on `Dispatchers.Main` to ensure clean, leak-free stream consumption.
  * `notificationThrottleHandler` & `webviewSpeechTimeoutHandler`: Timers managing notification intervals to protect IPC binders.
* **Key Methods / Recoveries**:
  * `initTtsEngine(onComplete)`: Modular self-healing initiator builder for standard text to speech setup.
  * `speakText(...)`: Triggers voice synthesizers safely. If TTS is not ready or has crashed, triggers self-healing lazy init recovery builders on the fly.
* **Inner Structures & Listeners**:
  * `WtrUtteranceListener`: Custom `UtteranceProgressListener` wrapper monitoring voice synthetics states:
    * `onStart(utteranceId)`: Signals start of a paragraph segment.
    * `onDone(utteranceId)`: Advances playlist `currentTrackIndex` by 1.
    * `onRangeStart(utteranceId, start, end, frame)`: Updates real-time CSS font highlighting coordinates back to the active WebKit instance.
    * `onError(utteranceId)`: Logs failure logs inside `WtrLogManager` and attempts auto-recovery steps.
  * `WtrMediaButtonReceiver`: Custom callbacks listening to lockscreen/earphone buttons (`onPlay`, `onPause`, `onSkipToNext`, `onSkipToPrevious`).

---

### 4. Direct Javascript-to-Native Bridge: `WtrWebAppInterface.kt`
* **Purpose**: Exposes secure JVM methods mapped directly into the browser viewport under the JS variable name `window.WtrBridge` or `window.WtrAndroidBridge`.
* **Namespace**: `com.example`
* **Key Annotation**: `@JavascriptInterface`
* **Key Methods**:
  * `onUrlSynced(syncedUrl)`: Relays current WebKit address paths. Highly guarded via active tab validations to prevent background tabs from hijacking address indicators.
  * `postPlaybackState(isPlaying, novelName, chapterTitle, activeWebsite, speed, pitch)`: Direct playback hook synced from custom on-page control frames.
  * `speakNative(text, rate, pitch, lang)`: Captures web audio commands and delegates them directly to the native Service queue.
  * `cancelNative()` / `pauseNative()` / `resumeNative()`: Synchronizes page click events with native voice synthesizer states.
  * `syncPollState(isPlaying, currentParagraphIndex, currentWordIndex)`: Syncs real-time Web DOM indices to correct Native audio controller bounds.

---

### 5. Unified Communication State Bus: `WtrAudioControlBridge.kt`
* **Purpose**: Global Singleton state bus mediating event exchanges between Compose layouts, the active WebView, and the Foreground Service.
* **Namespace**: `com.example`
* **Type**: `object` (Kotlin Utility Singleton)
* **Reactive State Flows**:
  * `isPlaying`: `MutableStateFlow<Boolean>`. Signals system play states.
  * `novelName` / `chapterTitle`: `MutableStateFlow<String>`. Media session metadata labels.
  * `playTrackInputList`: `MutableStateFlow<List<String>>`. Live memory array of paragraphs currently actively being spoken. Truncated at 300 elements to guard heap footprints.
  * `currentTrackIndex`: `MutableStateFlow<Int>`. Tracks current paragraph block.
  * `ttsSpeed` / `ttsPitch`: `MutableStateFlow<Float>`. Real-time user sliders inputs.
  * `activeTtsTabId`: `MutableStateFlow<Long?>`. Scopes active playback to one tab, preventing voice overlapping during concurrent multi-tab browsing sessions.
* **Direct Event Actions**:
  * `playAction` / `pauseAction` / `nextAction` / `prevAction`: Lambdas mapping buttons down to Web HTML elements or Service loops.
  * `nextChapterAction`: Delegate executing the autoscroll / next chapter click.

---

### 6. Thread-Safe Telemetry Log: `WtrLogManager.kt`
* **Purpose**: Records in-app events (page finished, bridge synced, errors, audio state changes), storing them in a thread-safe ring buffer.
* **Namespace**: `com.example`
* **Type**: `object` (Singleton Ring Buffer)
* **Key Components & Fields**:
  * `_logs`: `Collections.synchronizedList(LinkedList<String>())`. Thread-safe collection holding up to 100 entries.
  * `loggerScope`: `CoroutineScope` initialized on the background with `Dispatchers.IO` and a `SupervisorJob`.
* **Operations**:
  * `log(context, event)`: Prepend time tracking logs, and saves them to SharedPreferences asynchronously using the Coroutine background thread `loggerScope` if logging is enabled.
  * `clear(context)`: Wipes logs.
  * `getSavedLogs(context)`: Deserializes logs from SharedPreferences.

---

### 7. Active View Mode Enumeration: `BrowserSection.kt`
* **Purpose**: Type-safe indicators mapping current visual layout layers inside the main application screen.
* **Namespace**: `com.example`
* **Enum Value Map**:
  * `WEB`: WebView container active.
  * `TABS`: Tab manager grid active.
  * `BOOKMARKS`: Novels and website library view active.
  * `HISTORY`: Browse histories panel active.
  * `SETTINGS`: Main settings dialog / features control hub active.

---

### 8. System Utilities and Safety Engines
* **BackupEncryption.kt**: Offers AES/CBC/PKCS7Padding encryption utilizing a secure AES key generated from Android KeyStore Provider. Secures backups under Version 2 scheme.
* **CrashReportManager.kt**: Standard uncaught JVM exception handler capturing production crash logs in private storage for easy Diagnostics debugging logs exports.
* **PerformanceMonitor.kt**: Lightweight thread analyzer validating available JVM heap memory limits, throwing warning logs if heap consumption exceeds 80%, and safely force-triggering global garbage collections at 95% threshold capacity.
* **NetworkErrorHandler.kt**: Built-in exponential retry handler ensuring network scrapes or API operations smoothly retry against network packet losses.
* **Wtr-Lab Asset Cache Proxy**: A custom intercepting engine inside `shouldInterceptRequest`. Transparantly caches static resources (CSS, JS, Fonts, Images) from companion websites inside the private local disk cache directory (`cacheDir/wtr_static_cache`) and resolves them inline to eliminate network round-trips and maximize page loading speeds.
* **Customizable Anti-CAPTCHA Auto-Next Delay**: Configured via a user preference toggle option `"anti_captcha_delay"`. When enabled, pauses auto-advancement on translated chapters for 4.5s to bypass Google Translate's bot-detection heuristics, avoiding CAPTCHA challenge screens. Default is false (instant transitions).

---

## 🔁 Complete System-Wide Concurrency Flow

The application coordinates threads to balance reading fluidities with user interfaces:

```
[Compose Thread (UI)]               [WebKit Thread (JS)]             [Background Service (TTS)]
        |                                   |                                    |
        |---> User Clicks Speak             |                                    |
        |     (Requests paragraphs)         |                                    |
        |                                   |                                    |
        |---------------------------------->| Evaluate DOM JavaScript            |
        |                                   | (Get paragraphs array)             |
        |                                   |                                    |
        |<----------------------------------| Return Paragraphs JSON String      |
        |                                   |                                    |
        |                                   |                                    |
        |----------------------------------------------------------------------->| Start Audio Stream
        |                                   |                                    | (Set CPU & Wifi Lock)
        |                                   |                                    |
        |<-----------------------------------------------------------------------| Emit progress metrics
        |  Highlight active paragraph       |                                    | (Continuous updates)
        |  Glides viewport scroll bar       |                                    |
```

---

## 🌉 The Javascript Interface & Ad-Blocker/Security Interfacing (CRITICAL)

To prevent anti-scraping loops, the JavaScript bridge interacts seamlessly with host domains. Bypassing or disabling WebSpeech mock APIs triggers standard website protection routines, mistaking the reader as a bot and throwing `"Please turn off your ad blocker"` prompts. Keep standard bindings active across all WebKit initializations!
