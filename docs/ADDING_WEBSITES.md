# Adding Support for New Websites to Wtr-Browser

Wtr-Browser features a powerful, adaptive extraction system that separates raw text from layout bloat, user commentaries, and intrusive advertisement trackers. To keep the Text-To-Speech (TTS) voice flow seamless, the browser employs a centralized site registry, CSS-based filtering, `<br>` tag reconstruction, and JavaScript-based safeguards.

This guide explains how to add support for a new novel website, how the extraction architecture works under the hood (including our safe nesting & de-duplication rules), and provides a step-by-step implementation blueprint.

---

## 🧭 Core Architectural Principles

When adding support for a new website, the extraction engine operates according to these core guarantees:

1. **Non-Overlapping Containers (Anti-Nesting)**:
   If a website matches multiple containers in your `containerSelectors` (e.g., `#content` and `.show_txt`), the javascript scraper automatically filters out nested containers. It preserves only top-level unique containing components to prevent executing child extraction routines twice.
2. **Seen-Element Tracking (De-duplication)**:
   A strict, memory-insulated `Set` tracking processed HTML element references is maintained in-flight inside the WebView run. Even if selectors overlap, an element is processed **at most once**, guaranteeing that paragraphs are never double-read (e.g., preventing chapters from being extracted as `186` total paragraphs instead of `93`).
3. **Robust Sanitization (Junk Mitigation)**:
   Interactive dialog triggers, ad-blocker detection warnings, navigation buttons, and external discord promotion links are filtered dynamically at the DOM extraction boundary using global exclusion rules and site-specific blocklists.

---

## 🏗️ Step-by-Step Implementation

### Step 1: Analyze the Site Layout
Prior to writing code, open your desktop browser developer console (F12) on the target chapter URL and inspect:
- **Outer Container**: Identify the CSS class (e.g., `.chapter-body`, `#chapter-contents`) wrapping all the paragraphs.
- **Paragraph Elements**: See if paragraphs are natively styled using standard `<p>` nodes, or if they are segmented using individual `<br>` elements inside a text block.
- **Excluded Content**: Note classes of ad banners, share widgets, navigation links, or user commentaries (e.g., `#author-note-wrapper`, `.social-share`).

---

### Step 2: Create a Site Support Definition
Add your site implementation in `/app/src/main/java/com/example/sites/WebsiteSupportImpls.kt`. Your class must implement the `WebsiteSupport` interface:

```kotlin
class MyCustomSiteSupport : WebsiteSupport {
    override val siteId = "mycustomsite"
    override val domains = listOf("mycustomsite.com", "mycustomsite.org")
    override val keywords = listOf("customsite", "custom")
    override val requiresAutoTranslate = false // Set true for non-English sources requiring proxy translation

    // Chapter containers (placed in priority order)
    override val containerSelectors = listOf("#chapter-contents", ".chapter-inner", ".article-content")

    // Paragraph selector (defaults to standard p elements and custom wtr line segments)
    // Always prefer using CommonSelectors.STANDARD_PARAGRAPH to align HTML spans correctly
    override val paragraphSelector = CommonSelectors.STANDARD_PARAGRAPH

    // Exclusion patterns to drop before parsing text
    // Combine CommonSelectors.COMMON_EXCLUDE with any site-specific elements if necessary
    override val excludeSelectors = CommonSelectors.COMMON_EXCLUDE

    // Hardcoded target site-specific promos and signatures to strip
    override val siteSpecificJunkKeywords = listOf(
        "read at mycustomsite.com",
        "please join our discord server",
        "chapter transition"
    )

    // Set to true if text is separated by floating <br> tags rather than styled paragraphs
    override val requiresBrPreparation = false

    override val adBlockKeywords = emptyList<String>()
    override val titleSuffixes = listOf(" - MyCustomSite", " | WriteNovel")
}
```

### Reusing Commmon Selectors, Junks & Regex Patterns

For standardization and clarity, reuse the parameters predefined in `/app/src/main/java/com/example/sites/commons/Commons.kt`:

*   **`CommonSelectors.STANDARD_PARAGRAPH`**: Resolves standard paragraphs `p` and custom line wrapper tags `.wtr-line-segment`.
*   **`CommonSelectors.COMMON_EXCLUDE`**: Broad exclusion list containing standard sidebars, footers, ad banners, script/style components, and user comment cards.
*   **`CommonJunkKeywords.GENERIC_PROMO`**: Global multi-language dictionary containing promotional phrases (e.g., Discord invitation links, Patreon requests, and Chinese proxy transitions like "本章未完", "点击下一页") which gets automatically filtered out.
*   **`CommonPatterns.TITLE_PATTERNS` / `URL_PATTERNS`**: Preconfigured, robust regex definitions to extract precise chapter counts and IDs for bookmarks progress caching and history tracking indexes.

---

### Step 3: Register in the WebsiteSupportRegistry

All custom implementations must be added to the registry list inside `/app/src/main/java/com/example/sites/WebsiteSupportRegistry.kt`. We instantiate your class there:

```kotlin
object WebsiteSupportRegistry {
    private val supportedSites = listOf(
        WtrLabSupport(),
        WebNovelSupport(),
        NovelBinSupport(),
        TimoTxtSupport(),
        MyCustomSiteSupport() // <--- Register your class here!
    )
    
    // ... rest of registry lookup hooks ...
}
```

---

## 🛠️ Advanced Extraction Mechanics

Inside `BrowserAppScreen.kt`, the engine processes registered support rules using high-speed evaluated JavaScript. Understanding this mechanics is vital for addressing complex page behaviors:

### 1. Br Preparation (`requiresBrPreparation = true`)
For legacy domains where the paragraph structure is a massive text block separated by standalone `<br>` tags, the engine dynamically injects spans:
```javascript
function prepareBrParagraphs(contentEl) {
    if (!contentEl) return;
    // Bypassed if already wrapped by our highlight segments
    if (contentEl.querySelector('.wtr-line-segment') || contentEl.querySelector('.wtr-focus-highlight')) return;
    
    let children = Array.from(contentEl.childNodes);
    let newHtml = "";
    let currentGroup = "";

    children.forEach(node => {
        if (node.nodeType === 3) { // Text Node
            currentGroup += node.textContent;
        } else if (node.nodeType === 1) { // Element Node
            if (node.tagName.toLowerCase() === 'br') {
                if (currentGroup.trim().length > 0) {
                    newHtml += '<span class="wtr-line-segment">' + currentGroup.trim() + '</span><br>';
                    currentGroup = "";
                }
            } else if (node.tagName.toLowerCase() === 'p') {
                 if (currentGroup.trim().length > 0) {
                    newHtml += '<span class="wtr-line-segment">' + currentGroup.trim() + '</span>';
                    currentGroup = "";
                }
                newHtml += node.outerHTML;
            } else {
                currentGroup += node.outerHTML;
            }
        }
    });
    
    if (currentGroup.trim().length > 0) {
        newHtml += '<span class="wtr-line-segment">' + currentGroup.trim() + '</span>';
    }
    
    if (newHtml.length > 10) {
        contentEl.innerHTML = newHtml;
    }
}
```

### 2. Multi-Container Nesting Resolvers
To handle nested matching where query selectors match both a parent and details block, the browser pre-filters containers as follows:
```javascript
let rawContainers = Array.from(document.querySelectorAll(containerSelector));
// Filters out any container 'c' that is enclosed inside a larger container 'other'
containers = rawContainers.filter(c => !rawContainers.some(other => other !== c && other.contains(c)));
```

### 3. DOM Element De-duplication Set
During paragraph extraction, even if multiple selectors match, the scraper executes an in-memory double-read guard:
```javascript
let seenPTags = new Set();
pTags.forEach(p => {
    if (!p.closest(excludeClass)) {
        if (!seenPTags.has(p)) {
            seenPTags.add(p);
            let text = p.innerText.trim();
            if (text.length > 5 && !isJunk(text)) {
                paragraphs.push(text);
                elements.push(p);
            }
        }
    }
});
```

---

## 🚦 Troubleshooting Checklist

- **Issue: Paragraphs are read twice.**
  *Cause*: Multiple selectors are matching overlapping elements. 
  *Fix*: Verify that `containerSelectors` do not list parent/children selectors without checking nesting. (The new `seenPTags` Set and `rawContainers` filters have been globally integrated into `BrowserAppScreen.kt` to resolve this automatically).
- **Issue: The TTS skips text elements, or fails to find anything.**
  *Cause*: No tags matching the generic selectors exist under the matching container, or the content loads dynamically via slow AJAX scripts.
  *Fix*: Adjust `paragraphSelector` to cover custom container wrappers. Ensure a larger CSS polling delay or trigger extraction using exponential fallback loops.
- **Issue: Chinese text or untranslated characters are captured instead of final English outputs in Audiobook mode.**
  *Cause*: Extraction triggers too quickly before Google Translate or Gemini translation proxies have completed rewriting the DOM nodes.
  *Fix*: Enable exponential delays or validation gates inside the scraper loop to let translated content settle completely.
