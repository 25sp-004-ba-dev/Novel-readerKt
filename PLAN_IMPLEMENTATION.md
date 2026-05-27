# Browser & Novel Reader Plan Implementation

This document contains the step-by-step strategy for resolving navigation, URL, TTS, translation, and infinite-scroll conflicts within our novel reader web browser.

## 1. Identified Issues & Solutions

### A. URL Editing & Submission Failure
- **Root Cause:** The URL bar (`BasicTextField`) gets its text value by dynamically applying `getCleanDisplayUrl` on recompositions directly from the flow's value. When typing, this transformation causes text to jump, lock up, or ignore enter key/press events. Because the local changes are not committed instantly or are mangled, navigation attempts ignore input.
- **Solution:** Introduce a local string state (`urlText`) in the scope of the URL bar that handles text mutations gracefully. Synchronize this local state whenever `currentUrlInput` changes while unfocused. Let users edit the raw/cleaned string seamlessly and commit the final navigation request on pressing `Go` or clicking a suggestion.

### B. Missing "Open in New Tab" and Long-Press Functions
- **Root Cause:** A long-press listener is present (`longPressedUrl = url`), but the context menu UI is completely missing. It was erased or never integrated, meaning long-pressing any anchor/link produces no action.
- **Solution:** Implement an elegant Material 3 bottom sheet or modal dialog that automatically triggers when `longPressedUrl != null`. Display classic options:
  1. **Open in New Tab** (Opens a new tab in the background or foreground).
  2. **Open in Current Tab** (Standard navigation).
  3. **Copy Link Address** (Copies URL to clipboard).
  4. **Share Link** (Launches share sheet).

### C. Smart Paragraph Conflict with Auto-Next & Translation
- **Root Cause:** When the active URL changes (e.g. from Chapter 1 to Chapter 2), the general `urlChanged` trigger fires. However, because `currentTrackIndex` can take a split second to reset relative to the new URL, the progress-saving LaunchedEffect incorrectly maps Chapter 1's track index (e.g., 59) to Chapter 2's URL. When Chapter 2 finishes loading, the app tries to continue from paragraph 59, which exceeds Chapter 2's paragraph bounds, causing crash loops or skipping chapters entirely.
- **Solution:** Keep track of the *exact URL* from which the active track list was extracted (`extractedUrlOfActiveTracks`). Only auto-save `currentTrackIndex` if the active tab URL is exactly equal to the `extractedUrlOfActiveTracks`. This stops cross-contamination completely.

### D. WebNovel & Infinite-Scroll Pages Loading Issue
- **Root Cause:** For pages that append next-chapter content dynamically underneath without fully reloading (such as WebNovel), `onPageFinished` is never called, meaning `isWebLoading` doesn't toggle and the paragraphs-extraction system doesn't know Chapter 2 has started. Even if re-extracted, single element-focused query selectors like `document.querySelector` only return the first chapter block, failing to extract the appended chapters.
- **Solution:** 
  1. Synchronize SPA/Client-side pushState/replaceState updates back to Android by adding a `syncUrl(url, title)` JS-to-Kotlin interface on `WtrWebAppInterface`. This updates the active tab URL dynamically.
  2. Modify the injected extraction scripts to look for WebNovel's infinite scroll containers. When multiple matching containers exist (such as multiple `.cha-content` blocks), find the most active visible container in the viewport, or the one matching the chapter ID in the active URL. Extract paragraph content from that active container, enabling smooth seamlessly-continuous reading without requiring page reloads or manual skip/reset.

### E. Wtr-Lab / Translation Compatibility
- **Root Cause:** Stale User-Agent configurations (Chrome 115) trigger Cloudflare bot protection on websites like Wtr-Lab, preventing WebView from loading. Unstable translation loops detection intercepts and blocks valid navigation cycles.
- **Solution:** Upgrade User-Agent strings to modern standards (Chrome 124 on Android 14 / Windows 10) and simplify translation flow checks to avoid erroneous lockouts.

---

## 2. Implementation Schedule
- **Step 1:** Modify `WtrWebAppInterface` to support `syncUrl` and `syncPollState` enhancements.
- **Step 2:** Modify `WebScripts.kt` to call `syncUrl` and optimize multi-container infinite-scroll selectors.
- **Step 3:** Implement local URL text state inside `BrowserAppScreen.kt` for fluid editing.
- **Step 4:** Code the Chrome-style Long Press Link Context Menu dialog.
- **Step 5:** Protect paragraph saving via matching `extractedUrlOfActiveTracks` checks.
- **Step 6:** Run `compile_applet` to verify zero compiler regressions or dependency errors.
