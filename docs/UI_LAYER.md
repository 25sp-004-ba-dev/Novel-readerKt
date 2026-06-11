# Wtr-Lab Jetpack Compose UI Subsystem Manual

## UI Layouts, Interactive Components, and WebView Scraping Drivers

This document details the screens, sheets, dynamic Compose trees, and injected styles framing the Wtr-Lab web novel e-reading experience.

---

## 🎨 Design System, Colors, and Themes

The visual theme utilizes standard **Material Design 3 (M3)** schemas with custom dark options optimized for eye-safe night reading.

- **Edge-to-Edge Constraints**: Standard programmatic top and bottom margin offsets using `WindowInsets.systemBars` are injected straight into custom `Scaffold` elements to support edge-to-edge layouts natively.
- **Touch Targets**: All control surfaces (Play, Pause, Track Bar, Tabs grids) maintain standard **48dp minimum physical heights** to adhere strictly to Material accessibility recommendations.

---

## 📱 Detailed Compose view & Component Directory

Below is the technical specification of the Compose views, layouts, and panels making up the application's visual system.

---

### 1. Main Viewport & Scaffold Coordinator: `BrowserAppScreen.kt`

Houses the coordinate tree of the application, managing view switches, top search bars, bottom media controller overlays, and WebView bindings.

- **Component Type**: `@Composable fun BrowserAppScreen(...)`
- **Exposed Sub-Components & Functions**:
  - `TopAppBar`: Houses the URL/Search input field, desktop mode toggle, active tab counter, bookmarks star button, and log-view dropdown action menus.
  - `Bottom Audio Control Shelf`: Integrates playing novel title headers, paragraph seek sliders (e.g., _Paragraph 14 of 95_), skip chapter buttons, and play/pause trackers.
  - `Diagnostic Logs Dialog`: Slide-up alert dialog showing real-time `WtrLogManager` logs. Features options to "Clear Logs" or "Save as TXT" using standard SAF streams.
- **State Management**: Collects reactive variables from the shared `BrowserViewModel` using lifecycle-aware standard flows (`collectAsStateWithLifecycle`).

---

### 2. Novels & Website Library Panel: `BookmarksPanel.kt`

A premium dual-tab visual bookshelf managing standard web links and complex light novels catalogs.

- **Component Type**: `@Composable fun BookmarksPanel(...)`
- **Layout Structure & Inner Modules**:
  - **Dual-Tab Accent Switcher**: Clean selector button grouping inputs into **Websites** (standard bookmarks) and **Novels** (bookshelf).
  - **Bookshelf Books Grid Canvas**: For bookmarked novels, isNovel renders a premium visual card utilizing initials-based gradient hash graphics when cover images are missing, complete with dynamic spine crease lines. Shows novel name titles, domain labels, and the user's last-viewed reading chapter progress indicators.
  - **Standard Bookmark rows**: Lightweight visual rows displaying titles and URLs with deletion buttons.
  - **Library Search Field**: Interactive search bar with dynamic query filters.

---

### 3. Chronological Access Records: `HistoryPanel.kt`

Displays standard LazyColumn lists detailing past browsing URLs in reverse chronological order.

- **Component Type**: `@Composable fun HistoryPanel(...)`
- **Key Components**:
  - **History Item Row**: Renders webpage names alongside domain summaries, click redirects, and deletion buttons.
  - **Integrated Clean Engine**: "Delete All" alert dialog providing quick table wipes via the repository.

---

### 4. Interactive Configuration panel: `SettingsPanel.kt`

Coordinates application preferences, slider thresholds, and backup integrations.

- **Component Type**: `@Composable fun SettingsPanel(...)`
- **Key Modules**:
  - **TTS Adjusters**: Linear sliders tweaking speech playback frequencies (Pitch and Speed multipliers) ranging from $0.5\text{x}$ to $2.5\text{x}$.
  - **Preferences Sliders**: Force dark content injection toggles, Ad-Blocker interceptor switches, customizable Anti-CAPTCHA delay toggle switches, and diagnostics preferences.
  - **SAF backup launchers**: "Full State Backup" triggering the Android Storage Access Framework picker `ActivityResultContracts.CreateDocument` to export the database. "Restore Backup" launching `OpenDocument` to ingest `.json` data backups sequentially.

---

### 5. Multi-Tab Grid Manager: `TabsPanel.kt`

A flexible grid viewport enabling multi-window browser interactions, user-agent overrides, and folder stacking.

- **Component Type**: `@Composable fun TabsPanel(...)`
- **Internal Structures**:
  - **Double Column Grid Layout**: Renders open cards showing previews of active tabs with close buttons and Desktop/Mobile mode indicators.
  - **Nesting Tab Groups Folder Manager**: Combines multiple selected tab IDs into specialized stacked Folders to organize multiple novel series. Handles item removals and group splits gracefully.

---

### 6. Chrome Native New Tab View: `ChromeNewTabPage.kt`

Renders the default landing page state inside newly spawned tabs.

- **Component Type**: `@Composable fun ChromeNewTabPage(...)`
- **Key Layouts**:
  - **Hero Header Logo**: Beautiful centered branding display header.
  - **Navigation Grid Tiles**: Instant jump cards routing users directly to popular websites (Google, WebNovel, Royal Road, Scribble Hub).
  - **Dynamic Quick Search**: Address inputs forwarding searches to the user's favored search providers.
  - **Recent Visual History list**: Pulls top historic entries from the database for instant chapter resume lookups.

---

### 7. Custom Scripts Worksheets: `WebScripts.kt`

Utility housing raw compiled Javascript modules injected straight into WebKit rendering loops.

- **Variables Map**:
  - `WEB_SPEECH_POLYFILL`: Overrides default browser audio APIs (`window.speechSynthesis`).
  - `DOM_PARAGRAPH_SCRAPER`: The complete paragraph extractor.
  - `CSS_FORCE_DARK_STYLES`: Stylistic stylesheet rules forcing OLED background themes.
  - `CSS_WORD_HIGHLIGHTS`: Configures formatting rules to highlight and color-frame the currently active paragraph spoken on-screen.

---

## 📚 Dynamic Paragraph Web Scraper (`runHtmlTextExtractionAndPlay`)

One of the application's most critical subsystems is its **custom paragraph DOM extractor**, optimized extensively to handle infinite vertical scroll layouts like `webnovel.com`.

### 1. Unified Web Scraper Core Logic (JavaScript Implementation)

```javascript
(function () {
  let paragraphs = [];
  let elements = [];
  let host = window.location.hostname;

  // Junk filter keyword dictionary
  function isJunk(text) {
    let t = text.toLowerCase().trim();
    if (t.length < 5) return true;
    if (
      t.includes(".com") ||
      t.includes(".org") ||
      t.includes(".net") ||
      t.includes(".me") ||
      t.includes(".xyz") ||
      t.includes("http://") ||
      t.includes("https://")
    ) {
      if (
        t.includes("novelbin") ||
        t.includes("novelhall") ||
        t.includes("freewebnovel") ||
        t.includes("fanmtl") ||
        t.includes("timotxt") ||
        t.includes("novel543") ||
        t.includes("twkan") ||
        t.includes("novelhub") ||
        t.includes("novelhubapp") ||
        t.includes("webnovel")
      ) {
        return true;
      }
    }
    const promoKeywords = [
      "join our discord",
      "patreon",
      "support me",
      "recommend",
      "rate this",
      "novelbin",
      "novelhall",
      "freewebnovel",
      "fanmtl",
      "timotxt",
      "novel543",
      "twkan",
      "novelhub",
      "novelhubapp",
    ];
    return promoKeywords.some((keyword) => t.includes(keyword));
  }

  let rawContainers = Array.from(
    document.querySelectorAll(
      ".cha-content, .chapter-content, .cha-words, .chapter-inner",
    ),
  );

  // Anti-overlapping sibling filter logic:
  // Guarantees only top-level parents are selected; filters out nested duplicate components!
  let containers = rawContainers.filter(
    (c) => !rawContainers.some((other) => other !== c && other.contains(c)),
  );

  containers.forEach((contentEl) => {
    let pSelector = host.includes("webnovel")
      ? "p, .cha-paragraph, .pirate"
      : "p, .wtr-line-segment";
    let rawPTags = Array.from(contentEl.querySelectorAll(pSelector));
    let pTags = rawPTags.filter(
      (p) => !rawPTags.some((parent) => parent !== p && parent.contains(p)),
    );

    pTags.forEach((p) => {
      let excludeClass =
        ".author-note, .gift-box, .recommend-box, .comment-area, .m-comment, .user-opinion";
      if (!p.closest(excludeClass)) {
        let rect = p.getBoundingClientRect();
        let isVisible = rect.height > 0 || p.offsetHeight > 0;
        let text = p.innerText.trim();

        // Excludes invisible spam elements and advertising scripts
        if (text.length > 5 && isVisible && !isJunk(text)) {
          paragraphs.push(text);
        }
      }
    });
  });

  return JSON.stringify(paragraphs);
})();
```
