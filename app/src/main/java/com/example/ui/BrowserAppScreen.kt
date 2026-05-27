package com.example.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.BrowserViewModel
import com.example.BrowserSection
import com.example.MainActivity
import com.example.getProxyTranslatedUrl
import com.example.WtrAudioControlBridge
import com.example.WtrWebAppInterface
import com.example.data.*
import kotlinx.coroutines.*
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume

fun isSameBaseOrTranslatedUrl(url1: String, url2: String): Boolean {
    fun clean(url: String): String {
        if (url.isEmpty()) return ""
        var cleanVal = url.lowercase()
            .replace("https://", "")
            .replace("http://", "")
            .replace("www.", "")

        if (cleanVal.contains(".translate.goog")) {
            try {
                val firstSlash = cleanVal.indexOf('/')
                val host = if (firstSlash >= 0) cleanVal.substring(0, firstSlash) else cleanVal
                val path = if (firstSlash >= 0) cleanVal.substring(firstSlash) else ""

                val hostWithoutTranslate = host.replace(".translate.goog", "")
                val decodedHost = hostWithoutTranslate
                    .replace("--", "_DASH_")
                    .replace("-", ".")
                    .replace("_DASH_", "-")

                cleanVal = decodedHost + path
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return cleanVal.split("?")[0].split("#")[0].trim('/')
    }
    return clean(url1) == clean(url2)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserAppScreen(webView: WebView, onThemeChanged: (String) -> Unit = {}) {
    val context = LocalContext.current
    val viewModel: BrowserViewModel = viewModel()
    
    val activeTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val urlInput by viewModel.currentUrlInput.collectAsStateWithLifecycle()
    val tabsList by viewModel.allTabs.collectAsStateWithLifecycle()
    val searchEngineUrl by viewModel.searchEngine.collectAsStateWithLifecycle()

    val isBookmarked by activeTab?.let { 
        viewModel.isUrlBookmarked(it.url).collectAsStateWithLifecycle(initialValue = false) 
    } ?: remember { mutableStateOf(false) }

    var currentSection by remember { mutableStateOf(BrowserSection.WEB) }
    var webProgress by remember { mutableIntStateOf(100) }
    var isWebLoading by remember { mutableStateOf(false) }

    var longPressedUrl by remember { mutableStateOf<String?>(null) }
    var isSearchFocused by remember { mutableStateOf(false) }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }

    val sharedPrefs = remember(context) { context.getSharedPreferences("wtr_browser_settings", android.content.Context.MODE_PRIVATE) }
    var enableWebTrackplayer by remember { mutableStateOf(sharedPrefs.getBoolean("enable_web_trackplayer", false)) }
    var forceDarkContent by remember { mutableStateOf(sharedPrefs.getBoolean("force_dark_content", false)) } 
    var autoFocusParagraphs by remember { mutableStateOf(sharedPrefs.getBoolean("auto_focus_paragraphs", true)) } 
    var autoTranslateEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("auto_translate_enabled", true)) }
    var autoTranslateDomains by remember { mutableStateOf(sharedPrefs.getString("auto_translate_domains", "timotxt.com, timotxt") ?: "timotxt.com, timotxt") }
    var adBlockerEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("ad_blocker_enabled", true)) }
    var customTextZoom by remember { mutableStateOf(sharedPrefs.getInt("custom_text_zoom", 115)) }
    var previousTabId by remember { mutableStateOf<Long?>(null) }
    var currentThemeName by remember { mutableStateOf(sharedPrefs.getString("app_theme", "Dark") ?: "Dark") }

    var urlText by remember { mutableStateOf("") }
    var extractedUrlOfActiveTracks by remember { mutableStateOf("") }

    LaunchedEffect(urlInput, isSearchFocused) {
        if (!isSearchFocused) {
            urlText = if (urlInput == "chrome//newtab" || urlInput == "chrome://newtab") "" else getCleanDisplayUrl(urlInput)
        } else {
            urlText = if (urlInput == "chrome//newtab" || urlInput == "chrome://newtab") "" else urlInput
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val activeTtsSpeed by WtrAudioControlBridge.ttsSpeed.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Maintain a map of dynamic WebViews keyed by Tab ID
    val webViewsMap = remember { mutableStateMapOf<Long, WebView>() }

    var runHtmlTextExtractionAndPlayRef by remember { mutableStateOf<(() -> Unit)?>(null) }

    val translationAttempts = remember { mutableStateMapOf<String, Int>() }
    val lastTranslationTime = remember { mutableStateOf(mutableMapOf<String, Long>()) }

    val isDomainMatchedForTranslation: (String?) -> Boolean = { url ->
        if (url == null || !autoTranslateEnabled) {
            false
        } else {
            val urlLower = url.lowercase()
            if (urlLower.contains("translate.goog") || urlLower.contains("translate.google")) {
                false
            } else {
                val domainsList = autoTranslateDomains.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                domainsList.any { domain ->
                    val cleanDomain = domain.replace("https://", "").replace("http://", "").replace("www.", "").trim('/')
                    cleanDomain.isNotEmpty() && urlLower.contains(cleanDomain)
                }
            }
        }
    }

    val shouldTranslateUrl: (String?) -> Boolean = { url ->
        if (!isDomainMatchedForTranslation(url)) {
            false
        } else {
            val urlLower = url!!.lowercase()
            val cleanUrl = urlLower.split("?")[0].split("#")[0].trim('/')
            val now = System.currentTimeMillis()
            val attempts = translationAttempts[cleanUrl] ?: 0
            val lastTime = lastTranslationTime.value[cleanUrl] ?: 0L

            if (now - lastTime < 10000) {
                if (attempts >= 2) {
                    android.util.Log.e("WtrBrowser", "Translation loop detected for $url! Skipping Google Translate redirection.")
                    false
                } else {
                    translationAttempts[cleanUrl] = attempts + 1
                    lastTranslationTime.value[cleanUrl] = now
                    true
                }
            } else {
                translationAttempts[cleanUrl] = 1
                lastTranslationTime.value[cleanUrl] = now
                true
            }
        }
    }

    // Resolve or build WebView content for the current selected tab
    val currentActiveWebView = activeTab?.let { tab ->
        webViewsMap.getOrPut(tab.id) {
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    mediaPlaybackRequiresUserGesture = false
                    textZoom = customTextZoom
                    
                    userAgentString = if (tab.isDesktopMode) {
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                    } else {
                        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    }
                    useWideViewPort = tab.isDesktopMode
                    loadWithOverviewMode = tab.isDesktopMode
                }
                
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        super.onProgressChanged(view, newProgress)
                        if (viewModel.currentTab.value?.id == tab.id) {
                            webProgress = newProgress
                            if (newProgress >= 100) {
                                isWebLoading = false
                            }
                        }
                        if (newProgress >= 10 && newProgress < 85) {
                            view?.let { injectTtsBridgeScript(it) }
                        }
                    }
                }

                webViewClient = object : WebViewClient() {
                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        if (url == null) return false
                        if (shouldTranslateUrl(url)) {
                            val translatedUrl = getProxyTranslatedUrl(url)
                            com.example.WtrLogManager.log(context, "shouldOverrideUrlLoading redirect tab=${tab.id} translation: $url -> $translatedUrl")
                            view?.loadUrl(translatedUrl)
                            return true
                        }
                        return false
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                        val url = request?.url?.toString() ?: return false
                        if (shouldTranslateUrl(url)) {
                            val translatedUrl = getProxyTranslatedUrl(url)
                            com.example.WtrLogManager.log(context, "shouldOverrideUrlLoading redirect tab=${tab.id} translation: $url -> $translatedUrl")
                            view?.loadUrl(translatedUrl)
                            return true
                        }
                        return false
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        if (url != null) {
                            com.example.WtrLogManager.log(context, "onPageStarted tab=${tab.id}: $url")
                        }
                        if (viewModel.currentTab.value?.id == tab.id) {
                            isWebLoading = true
                            webProgress = 10
                        }
                        view?.let { injectTtsBridgeScript(it) }

                        if (url != null && view != null && shouldTranslateUrl(url)) {
                            val translatedUrl = getProxyTranslatedUrl(url)
                            com.example.WtrLogManager.log(context, "onPageStarted redirect tab=${tab.id} auto-translation: $url -> $translatedUrl")
                            view.stopLoading()
                            view.loadUrl(translatedUrl)
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (url != null) {
                            com.example.WtrLogManager.log(context, "onPageFinished tab=${tab.id}: $url (title: ${view?.title})")
                        }
                        if (viewModel.currentTab.value?.id == tab.id) {
                            isWebLoading = false
                            webProgress = 100
                            if (url != null) {
                                viewModel.onPageLoaded(url, view?.title ?: "Wtr-Lab")
                            }
                        }
                        injectTtsBridgeScript(this@apply)
                        if (forceDarkContent) {
                            injectForceDarkCss(this@apply)
                        }
                        if (url != null && (url.contains("translate.goog") || url.contains("translate.google"))) {
                            injectTranslateCssCleanup(this@apply)
                        }
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                        val url = request?.url?.toString()
                        if (url != null && adBlockerEnabled) {
                            val urlLower = url.lowercase()
                            val adKeywords = listOf(
                                "googlesyndication.com", "googleads", "doubleclick.net", "adservice.google",
                                "adsystem", "popunder", "popads", "onclickads", "taboola", "outbrain",
                                "mgid.com", "scorecardresearch", "analytics.google"
                            )
                            if (adKeywords.any { urlLower.contains(it) }) {
                                return android.webkit.WebResourceResponse(
                                    "text/javascript", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0))
                                )
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                setOnLongClickListener { _ ->
                    val hr = hitTestResult
                    val type = hr.type
                    if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                        val url = hr.extra
                        if (url != null) {
                            longPressedUrl = url
                        }
                        true
                    } else {
                        false
                    }
                }

                addJavascriptInterface(WtrWebAppInterface(
                    onPlaybackStateChanged = { isPlaying, title, subtitle ->
                        WtrAudioControlBridge.updatePlaybackState(isPlaying, title, subtitle)
                    },
                    onUrlSynced = { syncedUrl, htmlTitle ->
                        val currentActive = viewModel.currentTab.value
                        val isWebUrl = syncedUrl.startsWith("http://") || syncedUrl.startsWith("https://")
                        if (isWebUrl && currentActive?.id == tab.id && currentActive.url != syncedUrl && currentActive.url != "chrome://newtab") {
                            com.example.WtrLogManager.log(context, "onUrlSynced matching tab ID=${tab.id} synchronized to: $syncedUrl (current: ${currentActive.url})")
                            coroutineScope.launch {
                                viewModel.onPageLoaded(syncedUrl, htmlTitle)
                            }
                        }
                    }
                ), "WtrBridge")

                if (tab.url != "chrome://newtab" && tab.url.isNotEmpty()) {
                    loadUrl(tab.url)
                }
                
                MainActivity.activeWebViewsPool.add(this)
            }
        }
    }

    BackHandler(enabled = true) {
        if (currentSection != BrowserSection.WEB) {
            currentSection = BrowserSection.WEB
        } else if (currentActiveWebView?.canGoBack() == true) {
            currentActiveWebView.goBack()
        } else {
            (context as? android.app.Activity)?.finish()
        }
    }

    // Safely prune and destroy closed WebViews
    LaunchedEffect(tabsList) {
        val tabIds = tabsList.map { it.id }.toSet()
        val iterator = webViewsMap.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!tabIds.contains(entry.key)) {
                val wv = entry.value
                try {
                    wv.stopLoading()
                    wv.removeAllViews()
                    wv.destroy()
                    MainActivity.activeWebViewsPool.remove(wv)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                iterator.remove()
            }
        }
    }

    // Update WebView agents dynamically when Desktop Mode toggle values shift
    LaunchedEffect(activeTab?.isDesktopMode) {
        val tab = activeTab
        val wv = currentActiveWebView
        if (tab != null && wv != null) {
            val targetUA = if (tab.isDesktopMode) {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            } else {
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }
            if (wv.settings.userAgentString != targetUA) {
                wv.settings.userAgentString = targetUA
                wv.settings.useWideViewPort = tab.isDesktopMode
                wv.settings.loadWithOverviewMode = tab.isDesktopMode
                wv.reload()
            }
        }
    }

    // LISTENER FLOW: Unified single-directional navigation receiver to avoid tab freeze loops.
    // Use rememberUpdatedState to prevent capturing stale closures of currentActiveWebView
    val activeWebViewState = rememberUpdatedState(currentActiveWebView)
    LaunchedEffect(Unit) {
        viewModel.userNavigateTrigger.collect { navUrl ->
            activeWebViewState.value?.loadUrl(navUrl)
        }
    }

    // Driven synchronously via AndroidView's factory and update methods to ensure reliable renders without race conditions.

    // Inject force dark CSS style blocks if preference changes
    LaunchedEffect(forceDarkContent, currentActiveWebView) {
        if (forceDarkContent && currentActiveWebView != null) {
            injectForceDarkCss(currentActiveWebView)
        }
    }

    // Apply custom text zoom dynamically if preference changes
    LaunchedEffect(customTextZoom, currentActiveWebView) {
        val wv = currentActiveWebView
        if (wv != null) {
            wv.settings.textZoom = customTextZoom
        }
    }

    // Configure system Media Sync actions to bind with the currently highlighted tab's WebView
    LaunchedEffect(currentActiveWebView) {
        val activeWV = currentActiveWebView
        if (activeWV != null) {
            WtrAudioControlBridge.onWebViewProgressTrigger = { event, charIndex ->
                activeWV.post {
                    activeWV.evaluateJavascript(
                        "if (typeof window.WtrTtsTriggerEvent === 'function') { window.WtrTtsTriggerEvent('$event', $charIndex); }",
                        null
                    )
                }
            }

            WtrAudioControlBridge.playAction = {
                activeWV.post {
                    activeWV.evaluateJavascript("""
                        (function() {
                            if (window.speechSynthesis && window.speechSynthesis.paused) {
                                window.speechSynthesis.resume();
                            }
                            let playBtn = document.querySelector('button[aria-label*="Play"], button[title*="Play"], button[class*="play"], .play-btn, .btn-play, .play-pause-btn, .audio-player-button');
                            if (playBtn) {
                                playBtn.click();
                            } else {
                                let buttons = Array.from(document.querySelectorAll('button, a, span'));
                                let target = buttons.find(b => b.innerText && (b.innerText.toLowerCase().includes('play') || b.innerText.toLowerCase().includes('listen') || b.innerText.toLowerCase().includes('tts')));
                                if (target) target.click();
                            }
                        })();
                    """.trimIndent(), null)
                }
            }

            WtrAudioControlBridge.pauseAction = {
                activeWV.post {
                    activeWV.evaluateJavascript("""
                        (function() {
                            if (window.speechSynthesis && window.speechSynthesis.speaking) {
                                window.speechSynthesis.pause();
                            }
                            let pauseBtn = document.querySelector('button[aria-label*="Pause"], button[title*="Pause"], button[class*="pause"], .pause-btn, .btn-pause, .play-pause-btn, .audio-player-button');
                            if (pauseBtn) {
                                pauseBtn.click();
                            } else {
                                let buttons = Array.from(document.querySelectorAll('button, a, span'));
                                let target = buttons.find(b => b.innerText && b.innerText.toLowerCase().includes('pause'));
                                if (target) target.click();
                            }
                        })();
                    """.trimIndent(), null)
                }
            }

            WtrAudioControlBridge.nextAction = {
                activeWV.post {
                    activeWV.evaluateJavascript("""
                        (function() {
                            let nextBtn = document.querySelector('.btn-next, .next, .next-chapter, a[class*="next"], button[class*="next"]');
                            if (nextBtn) {
                                nextBtn.click();
                            } else {
                                let links = Array.from(document.querySelectorAll('a, button'));
                                let target = links.find(l => l.innerText && (l.innerText.toLowerCase().includes('next') || l.innerText.toLowerCase().includes('next chapter')));
                                if (target) {
                                    target.click();
                                } else {
                                    let currentUrl = window.location.href;
                                    let match = currentUrl.match(/chapter-(\d+)/);
                                    if (match) {
                                        let nextNum = parseInt(match[1]) + 1;
                                        window.location.href = currentUrl.replace(/chapter-\d+/, 'chapter-' + nextNum);
                                    }
                                }
                            }
                        })();
                    """.trimIndent(), null)
                }
            }

            WtrAudioControlBridge.prevAction = {
                activeWV.post {
                    activeWV.evaluateJavascript("""
                        (function() {
                            let prevBtn = document.querySelector('.btn-prev, .prev, .prev-chapter, a[class*="prev"], button[class*="prev"]');
                            if (prevBtn) {
                                prevBtn.click();
                            } else {
                                let links = Array.from(document.querySelectorAll('a, button'));
                                let target = links.find(l => l.innerText && (l.innerText.toLowerCase().includes('prev') || l.innerText.toLowerCase().includes('previous') || l.innerText.toLowerCase().includes('previous chapter')));
                                if (target) {
                                    target.click();
                                } else {
                                    let currentUrl = window.location.href;
                                    let match = currentUrl.match(/chapter-(\d+)/);
                                    if (match) {
                                        let prevNum = parseInt(match[1]) - 1;
                                        if (prevNum > 0) {
                                            window.location.href = currentUrl.replace(/chapter-\d+/, 'chapter-' + prevNum);
                                        }
                                    }
                                }
                            }
                        })();
                    """.trimIndent(), null)
                }
            }
        }
    }

    // Custom TrackPlayer States for regular/different websites collected from service layer bridge
    val isAudiobookModeActive by WtrAudioControlBridge.isAudiobookModeActive.collectAsStateWithLifecycle()
    val playTrackInputList by WtrAudioControlBridge.playTrackInputList.collectAsStateWithLifecycle()
    val currentTrackIndex by WtrAudioControlBridge.currentTrackIndex.collectAsStateWithLifecycle()
    val currentlySpeakingText by WtrAudioControlBridge.currentlySpeakingText.collectAsStateWithLifecycle()
    val isPlayerRunning by WtrAudioControlBridge.isPlayerRunning.collectAsStateWithLifecycle()

    var isExtracting by remember { mutableStateOf(false) }

    // Unified play/pause custom track actions
    fun playCustomParagraph(index: Int) {
        val currentList = WtrAudioControlBridge.playTrackInputList.value
        if (currentList.isNotEmpty()) {
            val validIndex = index.coerceIn(0, currentList.size - 1)
            WtrAudioControlBridge.playCustomParagraphAction?.invoke(validIndex)
        }
    }

    fun stopCustomPlayback() {
        WtrAudioControlBridge.setPlayTrackInputList(emptyList())
        WtrAudioControlBridge.setCurrentTrackIndex(0)
        WtrAudioControlBridge.setIsPlayerRunning(false)
        WtrAudioControlBridge.setIsAudiobookModeActive(false)
        WtrAudioControlBridge.onCancelNative?.invoke()
    }

    fun pauseCustomVolume() {
        WtrAudioControlBridge.setIsPlayerRunning(false)
        WtrAudioControlBridge.onPauseNative?.invoke()
    }

    fun resumeCustomVolume() {
        WtrAudioControlBridge.setIsPlayerRunning(true)
        WtrAudioControlBridge.onResumeNative?.invoke()
    }

    // Reduced TTS latency and adaptive HTML readers extraction methods
    val runHtmlTextExtractionAndPlay: () -> Unit = {
        val webView = currentActiveWebView
        if (webView != null && !isExtracting) {
            isExtracting = true
            coroutineScope.launch(Dispatchers.Main) {
                try {
                    val currentUrlLower = (webView.url ?: "").lowercase()
                    
                    var attempts = 0
                    val maxAttempts = 80 // Poll up to 12-15 seconds for translation to finalize
                    var list = emptyList<String>()
                    var extractionSuccess = false
                    
                    while (attempts < maxAttempts) {
                        val resultString = suspendCoroutine<String?> { continuation ->
                            webView.post {
                                webView.evaluateJavascript(
                                    """
                                    (function() {
                                        let paragraphs = [];
                                        let host = window.location.hostname;
                                        
                                        function isJunk(text) {
                                            let t = text.toLowerCase().trim();
                                            if (t.length < 5) return true;
                                            if (t.includes(".com") || t.includes(".org") || t.includes(".net") || t.includes(".me") || t.includes(".xyz")  || t.includes("http://") || t.includes("https://")) {
                                                if (t.includes("novelbin") || t.includes("novelhall") || t.includes("freewebnovel") || t.includes("fanmtl") || t.includes("timotxt") || t.includes("webnovel")) {
                                                    return true;
                                                }
                                            }
                                            const promoKeywords = [
                                                "join our discord", "join discord", "patreon", "support me", "support the author",
                                                "rate this", "please review", "please rate", "author's note", "author note",
                                                "recommend", "translator", "translation", "editor's note", "editor note",
                                                "find any errors", "broken links", "report us", "if you find any", "novelbin",
                                                "novelhall", "freewebnovel", "fanmtl", "timotxt", "webnovel", "next chapter",
                                                "previous chapter", "table of contents", "read online free", "read online for free",
                                                "unlocked chapters", "bonus chapters", "sign up", "sign in", "subscribe to",
                                                "follow my page", "download our app", "read this novel", "other novel", "like this book"
                                            ];
                                            if (t.length < 300) {
                                                for (let keyword of promoKeywords) {
                                                    if (t.includes(keyword)) return true;
                                                }
                                            }
                                            return false;
                                        }
                                        
                                        // Helper to split <br> separated blocks into cleaner segmented spans
                                        function prepareBrParagraphs(contentEl) {
                                            if (!contentEl) return;
                                            if (contentEl.querySelector('.wtr-line-segment') || contentEl.querySelector('.wtr-focus-highlight')) return;
                                            let pTags = contentEl.querySelectorAll('p');
                                            if (pTags.length > 5) return; // Already has structural paragraphs
                                            
                                            let html = contentEl.innerHTML;
                                            let parts = html.split(/<br\s*\/?>/i);
                                            let newParts = parts.map(part => {
                                                let trimmed = part.replace(/<[^>]+>/g, '').trim();
                                                if (trimmed.length > 5) {
                                                    if (!part.trim().startsWith('<span class="wtr-line-segment"')) {
                                                        return '<span class="wtr-line-segment">' + part + '</span>';
                                                    }
                                                }
                                                return part;
                                            });
                                            contentEl.innerHTML = newParts.join('<br>');
                                        }

                                        let contentEl = null;
                                        let elements = [];

                                        if (host.includes("webnovel")) {
                                            let containers = document.querySelectorAll('.cha-content, .chapter-content, .cha-words, .chapter-inner');
                                            if (containers.length > 1) {
                                                let url = window.location.href;
                                                let match = url.match(/_(\d+)(?:\?|$)/) || url.match(/chapter-(\d+)/);
                                                if (match) {
                                                    let chId = match[1];
                                                    for (let c of containers) {
                                                        if (c.innerHTML.includes(chId) || (c.getAttribute('data-cid') && c.getAttribute('data-cid').includes(chId))) {
                                                            contentEl = c;
                                                            break;
                                                        }
                                                    }
                                                }
                                                if (!contentEl) {
                                                    let bestVisible = null;
                                                    let maxVisibleHeight = 0;
                                                    for (let c of containers) {
                                                        let rect = c.getBoundingClientRect();
                                                        let visibleHeight = Math.min(rect.bottom, window.innerHeight) - Math.max(rect.top, 0);
                                                        if (visibleHeight > maxVisibleHeight) {
                                                            maxVisibleHeight = visibleHeight;
                                                            bestVisible = c;
                                                        }
                                                    }
                                                    contentEl = bestVisible || containers[0];
                                                }
                                            } else {
                                                contentEl = containers[0];
                                            }
                                            if (contentEl) {
                                                let pTags = contentEl.querySelectorAll('p, .cha-paragraph, .pirate');
                                                pTags.forEach(p => {
                                                    if (!p.closest('.author-note, .gift-box, .recommend-box, .comment-area, .m-comment, .user-opinion, .review-item, .j_recommendation, .book-recommend, .cha-nav, .chapter-control')) {
                                                        let text = p.innerText.trim();
                                                        if (text.length > 5 && !isJunk(text)) paragraphs.push(text);
                                                    }
                                                });
                                            }
                                        } else if (host.includes("novelhall")) {
                                            contentEl = document.querySelector('#htmlContent') || document.querySelector('.entry-content') || document.querySelector('.active');
                                            if (contentEl) {
                                                prepareBrParagraphs(contentEl);
                                                let pTags = contentEl.querySelectorAll('p, .wtr-line-segment');
                                                pTags.forEach(p => {
                                                    if (!p.closest('.nav, .nav-btn, .next_chap, .prev_chap, .next-page, .prev-page')) {
                                                        let text = p.innerText.trim();
                                                        if (text.length > 5 && !isJunk(text)) paragraphs.push(text);
                                                    }
                                                });
                                            }
                                        } else if (host.includes("fanmtl")) {
                                            contentEl = document.querySelector('.chapter-content') || document.querySelector('.read-content') || document.querySelector('#chapter-content') || document.querySelector('.content-area');
                                            if (contentEl) {
                                                contentEl.querySelectorAll('p').forEach(p => {
                                                    if (!p.closest('.author-note, .next_chap, .prev_chap, .nav-links')) {
                                                        let text = p.innerText.trim();
                                                        if (text.length > 5 && !isJunk(text)) paragraphs.push(text);
                                                    }
                                                });
                                            }
                                        } else if (host.includes("novelbin")) {
                                            contentEl = document.querySelector('#chr-content') || document.querySelector('.chr-c') || document.querySelector('#chapter-content') || document.querySelector('.chapter-container');
                                            if (contentEl) {
                                                contentEl.querySelectorAll('p').forEach(p => {
                                                    if (!p.closest('#chr-nav, .chr-nav, .ads, .adsbygoogle, .btn-group, .custom-control, .category, .desc, .title-book')) {
                                                        let text = p.innerText.trim();
                                                        if (text.length > 5 && !isJunk(text)) paragraphs.push(text);
                                                    }
                                                });
                                            }
                                        } else if (host.includes("freewebnovel")) {
                                            contentEl = document.querySelector('.txt') || document.querySelector('#htmlContent') || document.querySelector('.chapter-content');
                                            if (contentEl) {
                                                contentEl.querySelectorAll('p').forEach(p => {
                                                    if (!p.closest('.ads, .adsbygoogle, .nav-links, .chapter-nav')) {
                                                        let text = p.innerText.trim();
                                                        if (text.length > 5 && !isJunk(text)) paragraphs.push(text);
                                                    }
                                                });
                                            }
                                        } else if (host.includes("timotxt")) {
                                            contentEl = document.querySelector('.read-content') || document.querySelector('#content') || document.querySelector('.show_txt');
                                            if (contentEl) {
                                                prepareBrParagraphs(contentEl);
                                                let pTags = contentEl.querySelectorAll('p, .wtr-line-segment');
                                                pTags.forEach(p => {
                                                    if (!p.closest('.nav, .ads, .menu, .chapter-nav')) {
                                                        let text = p.innerText.trim();
                                                        if (text.length > 5 && !isJunk(text)) paragraphs.push(text);
                                                    }
                                                });
                                            }
                                        }

                                        if (paragraphs.length === 0) {
                                            let bestContainer = null;
                                            let maxPLength = 0;
                                            document.querySelectorAll('div, article, section').forEach(el => {
                                                if (!el.closest('nav, footer, h1, fieldset, form, header, script, style, #comments, .comments, .nav, .footer, .sidebar, #sidebar')) {
                                                    let pList = el.querySelectorAll('p');
                                                    if (pList.length > maxPLength) {
                                                        maxPLength = pList.length;
                                                        bestContainer = el;
                                                    }
                                                }
                                            });

                                            if (bestContainer && maxPLength > 3) {
                                                bestContainer.querySelectorAll('p').forEach(p => {
                                                    let text = p.innerText.trim();
                                                    if (text.length > 5 && !isJunk(text)) {
                                                        paragraphs.push(text);
                                                    }
                                                });
                                            } else {
                                                let pTags = document.querySelectorAll('p, li, h1, h2, h3, [class*="paragraph"], [id*="paragraph"]');
                                                pTags.forEach(p => {
                                                    let t = p.innerText.trim();
                                                    let isChinese = /[\u4e00-\u9fa5]/.test(t);
                                                    let isValidLength = isChinese ? t.length > 5 : t.length > 15;
                                                    if (isValidLength && !p.closest('nav, footer, h1, fieldset, form, header, script, style, #comments, .comments, .nav, .footer, .sidebar, #sidebar, .menu, #menu')) {
                                                        if (!isJunk(t)) {
                                                            paragraphs.push(t);
                                                        }
                                                    }
                                                });
                                            }
                                        }
                                        
                                        return JSON.stringify(paragraphs);
                                    })();
                                    """.trimIndent()
                                ) { res ->
                                    continuation.resume(res)
                                }
                            }
                        }
                        
                        // Verify non-empty array returned
                        if (resultString != null && resultString != "null" && resultString != "[]" && resultString.isNotEmpty()) {
                            val cleanResult = try {
                                org.json.JSONTokener(resultString).nextValue() as String
                            } catch (e: Exception) {
                                resultString
                            }
                            val unescaped = org.json.JSONArray(cleanResult)
                            val temp = mutableListOf<String>()
                            for (i in 0 until unescaped.length()) {
                                val text = unescaped.getString(i).trim()
                                if (text.isNotEmpty()) {
                                    temp.add(text)
                                }
                            }
                            
                            val isProxyTranslation = currentUrlLower.contains("translate.goog") || currentUrlLower.contains("translate.google")
                            fun isPageMostlyTranslatingOrChinese(paragraphs: List<String>): Boolean {
                                if (paragraphs.isEmpty()) return false
                                var chineseCount = 0
                                var englishCount = 0
                                val sample = paragraphs.take(5)
                                for (p in sample) {
                                    for (c in p) {
                                        if (c in '\u4e00'..'\u9fa5') {
                                            chineseCount++
                                        } else if (c.isLetter() && c.code < 128) {
                                            englishCount++
                                        }
                                    }
                                }
                                return chineseCount > 0 && englishCount < chineseCount
                            }
                            val isChinesePresent = isPageMostlyTranslatingOrChinese(temp)
                            
                            if (isProxyTranslation && isChinesePresent && attempts < maxAttempts - 1) {
                                attempts++
                                delay(200) // Fast 200ms sleep wait
                            } else {
                                list = temp
                                extractionSuccess = true
                                break
                            }
                        } else {
                            attempts++
                            delay(150) // Fast fallback 150ms sleep
                        }
                    }
                    
                    if (extractionSuccess && list.isNotEmpty()) {
                        val tabTitle = activeTab?.title ?: "Web Chapter"
                        val tabUrl = activeTab?.url ?: ""
                        
                        val parsedInfo = extractNovelAndChapter(tabTitle, tabUrl)
                        WtrAudioControlBridge.setNovelAndChapter(parsedInfo.first, parsedInfo.second)
                        WtrAudioControlBridge.bookTitle = parsedInfo.first
                        
                        val webUri = try {
                            android.net.Uri.parse(tabUrl).host ?: ""
                        } catch (e: Exception) {
                            ""
                        }
                        val cleanHost = webUri.replace("www.", "").replace("translate.goog", "").trim('.')
                        WtrAudioControlBridge.setActiveWebsite(cleanHost)
                        
                        WtrAudioControlBridge.setPlayTrackInputList(list)
                        extractedUrlOfActiveTracks = tabUrl
                        
                        val savedProgressVal = getSavedParagraphIndex(context, tabUrl)
                        val startIdx = if (savedProgressVal in list.indices) savedProgressVal else 0
                        WtrAudioControlBridge.setCurrentTrackIndex(startIdx)
                        
                        android.widget.Toast.makeText(context, "Ready! Starting at Paragraph ${startIdx + 1}", android.widget.Toast.LENGTH_SHORT).show()
                        
                        playCustomParagraph(startIdx)
                    } else {
                        android.widget.Toast.makeText(context, "Ah, we couldn't segment paragraphs text here. Check settings.", android.widget.Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    isExtracting = false
                }
            }
        }
    }

    runHtmlTextExtractionAndPlayRef = runHtmlTextExtractionAndPlay

    val triggerNextChapterNavigation: () -> Unit = {
        val webView = currentActiveWebView
        if (webView != null) {
            webView.post {
                webView.evaluateJavascript(
                    """
                    (function() {
                        let nextBtn = document.querySelector('.btn-next, .next, .next-chapter, a[class*="next"], button[class*="next"]');
                        if (nextBtn) {
                            nextBtn.click();
                            return true;
                        } else {
                            let links = Array.from(document.querySelectorAll('a, button'));
                            let target = links.find(l => l.innerText && (l.innerText.toLowerCase().includes('next') || l.innerText.toLowerCase().includes('next chapter')));
                            if (target) {
                                target.click();
                                return true;
                            } else {
                                let currentUrl = window.location.href;
                                let match = currentUrl.match(/chapter-(\d+)/);
                                if (match) {
                                    let nextNum = parseInt(match[1]) + 1;
                                    window.location.href = currentUrl.replace(/chapter-\d+/, 'chapter-' + nextNum);
                                    return true;
                                }
                            }
                        }
                        return false;
                    })();
                    """.trimIndent()
                ) { result ->
                    val success = result == "true"
                    if (!success) {
                        android.widget.Toast.makeText(context, "Looking for next chapter...", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        android.widget.Toast.makeText(context, "Navigating to next chapter...", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Navigation and resets of active state segments on chapter changes
    var previousUrl by remember { mutableStateOf("") }
    LaunchedEffect(activeTab?.url) {
        val currentUrl = activeTab?.url ?: ""
        
        val urlChanged = currentUrl.isNotEmpty() && previousUrl.isNotEmpty() && !isSameBaseOrTranslatedUrl(previousUrl, currentUrl)
        
        if (urlChanged) {
            if (isAudiobookModeActive) {
                WtrAudioControlBridge.setPlayTrackInputList(emptyList())
                WtrAudioControlBridge.setCurrentTrackIndex(0)
                WtrAudioControlBridge.setIsPlayerRunning(false)
                WtrAudioControlBridge.onCancelNative?.invoke()
            } else {
                WtrAudioControlBridge.setPlayTrackInputList(emptyList())
                WtrAudioControlBridge.setCurrentTrackIndex(0)
                if (isPlayerRunning) {
                    WtrAudioControlBridge.setIsPlayerRunning(false)
                    WtrAudioControlBridge.onCancelNative?.invoke()
                }
            }
        }
        previousUrl = currentUrl
    }

    // Autosave TTS reading paragraph index when progress changes
    LaunchedEffect(currentTrackIndex) {
        val currentUrl = activeTab?.url ?: ""
        if (currentUrl.isNotEmpty() && currentUrl == extractedUrlOfActiveTracks && currentUrl != "chrome://newtab" && currentTrackIndex >= 0) {
            saveParagraphIndex(context, currentUrl, currentTrackIndex)
        }
    }

    val currentRunExtractionAndPlay by rememberUpdatedState(runHtmlTextExtractionAndPlay)
    val currentTriggerNextChapter by rememberUpdatedState(triggerNextChapterNavigation)

    // Detect page finished loading and auto-extract/resume in audiobook mode (observes URL updates to withstand translation redirects dynamically)
    LaunchedEffect(isWebLoading, isAudiobookModeActive, activeTab?.url) {
        if (!isWebLoading && isAudiobookModeActive) {
            val urlVal = activeTab?.url ?: ""
            
            // If the URL is scheduled for auto-translation, but Google Translate proxy is not yet loaded, skip this event and wait until the actual translated URL load completes
            if (autoTranslateEnabled && isDomainMatchedForTranslation(urlVal) && !urlVal.contains("translate.goog")) {
                return@LaunchedEffect
            }
            
            val isWtrLab = urlVal.contains("wtr-lab.com") || urlVal.startsWith("file://") || urlVal.isEmpty() || urlVal == "chrome://newtab"
            if (!isWtrLab) {
                delay(800) // Settle DOM delay
                currentRunExtractionAndPlay()
            } else {
                WtrAudioControlBridge.setIsAudiobookModeActive(false)
            }
        }
    }

    // Register decoupled background-safe callbacks
    LaunchedEffect(Unit) {
        WtrAudioControlBridge.nextChapterAction = {
            currentTriggerNextChapter()
        }
    }

    // Scroll active reading paragraph into view and highlight it (Auto-Focus mode)
    LaunchedEffect(currentTrackIndex, isPlayerRunning, autoFocusParagraphs, currentActiveWebView) {
        val webView = currentActiveWebView ?: return@LaunchedEffect
        if (isPlayerRunning && autoFocusParagraphs) {
            val jsCode = """
                (function() {
                    const targetIndex = $currentTrackIndex;
                    const host = window.location.hostname;
                    
                    function isJunk(text) {
                        let t = text.toLowerCase().trim();
                        if (t.length < 5) return true;
                        if (t.includes(".com") || t.includes(".org") || t.includes(".net") || t.includes(".me") || t.includes(".xyz") || t.includes("http://") || t.includes("https://")) {
                            if (t.includes("novelbin") || t.includes("novelhall") || t.includes("freewebnovel") || t.includes("fanmtl") || t.includes("timotxt") || t.includes("webnovel")) {
                                return true;
                            }
                        }
                        const promoKeywords = [
                            "join our discord", "join discord", "patreon", "support me", "support the author",
                            "rate this", "please review", "please rate", "author's note", "author note",
                            "recommend", "translator", "translation", "editor's note", "editor note",
                            "find any errors", "broken links", "report us", "if you find any", "novelbin",
                            "novelhall", "freewebnovel", "fanmtl", "timotxt", "webnovel", "next chapter",
                            "previous chapter", "table of contents", "read online free", "read online for free",
                            "unlocked chapters", "bonus chapters", "sign up", "sign in", "subscribe to",
                            "follow my page", "download our app", "read this novel", "other novel", "like this book"
                        ];
                        if (t.length < 300) {
                            for (let keyword of promoKeywords) {
                                if (t.includes(keyword)) return true;
                            }
                        }
                        return false;
                    }

                    function prepareBrParagraphs(contentEl) {
                        if (!contentEl) return;
                        if (contentEl.querySelector('.wtr-line-segment') || contentEl.querySelector('.wtr-focus-highlight')) return;
                        let pTags = contentEl.querySelectorAll('p');
                        if (pTags.length > 3) return; 
                        
                        let html = contentEl.innerHTML;
                        let parts = html.split(/<br\s*\/?>/i);
                        let newParts = parts.map(part => {
                            let trimmed = part.replace(/<[^>]+>/g, '').trim();
                            if (trimmed.length > 5) {
                                if (!part.trim().startsWith('<span class="wtr-line-segment"')) {
                                    return '<span class="wtr-line-segment">' + part + '</span>';
                                }
                            }
                            return part;
                        });
                        contentEl.innerHTML = newParts.join('<br>');
                    }

                    let elements = [];
                    
                    if (host.includes("webnovel")) {
                        let contentEl = null;
                        let containers = document.querySelectorAll('.cha-content, .chapter-content, .cha-words, .chapter-inner');
                        if (containers.length > 1) {
                            let url = window.location.href;
                            let match = url.match(/_(\d+)(?:\?|$)/) || url.match(/chapter-(\d+)/);
                            if (match) {
                                let chId = match[1];
                                for (let c of containers) {
                                    if (c.innerHTML.includes(chId) || (c.getAttribute('data-cid') && c.getAttribute('data-cid').includes(chId))) {
                                        contentEl = c;
                                        break;
                                    }
                                }
                            }
                            if (!contentEl) {
                                let bestVisible = null;
                                let maxVisibleHeight = 0;
                                for (let c of containers) {
                                    let rect = c.getBoundingClientRect();
                                    let visibleHeight = Math.min(rect.bottom, window.innerHeight) - Math.max(rect.top, 0);
                                    if (visibleHeight > maxVisibleHeight) {
                                        maxVisibleHeight = visibleHeight;
                                        bestVisible = c;
                                    }
                                }
                                contentEl = bestVisible || containers[0];
                            }
                        } else {
                            contentEl = containers[0];
                        }
                        if (contentEl) {
                            let pTags = contentEl.querySelectorAll('p, .cha-paragraph, .pirate');
                            pTags.forEach(p => {
                                if (!p.closest('.author-note, .gift-box, .recommend-box, .comment-area, .m-comment, .user-opinion, .review-item, .j_recommendation, .book-recommend, .cha-nav, .chapter-control')) {
                                    let text = p.innerText.trim();
                                    if (text.length > 5 && !isJunk(text)) elements.push(p);
                                }
                            });
                        }
                    } else if (host.includes("novelhall")) {
                        let contentEl = document.querySelector('#htmlContent') || document.querySelector('.entry-content') || document.querySelector('.active');
                        if (contentEl) {
                            prepareBrParagraphs(contentEl);
                            let pTags = contentEl.querySelectorAll('p, .wtr-line-segment');
                            pTags.forEach(p => {
                                if (!p.closest('.nav, .nav-btn, .next_chap, .prev_chap, .next-page, .prev-page')) {
                                    let text = p.innerText.trim();
                                    if (text.length > 5 && !isJunk(text)) elements.push(p);
                                }
                            });
                        }
                    } else if (host.includes("fanmtl")) {
                        let contentEl = document.querySelector('.chapter-content') || document.querySelector('.read-content') || document.querySelector('#chapter-content') || document.querySelector('.content-area');
                        if (contentEl) {
                            contentEl.querySelectorAll('p').forEach(p => {
                                if (!p.closest('.author-note, .next_chap, .prev_chap, .nav-links')) {
                                    let text = p.innerText.trim();
                                    if (text.length > 5 && !isJunk(text)) elements.push(p);
                                }
                            });
                        }
                    } else if (host.includes("novelbin")) {
                        let contentEl = document.querySelector('#chr-content') || document.querySelector('.chr-c') || document.querySelector('#chapter-content') || document.querySelector('.chapter-container');
                        if (contentEl) {
                            contentEl.querySelectorAll('p').forEach(p => {
                                if (!p.closest('#chr-nav, .chr-nav, .ads, .adsbygoogle, .btn-group, .custom-control, .category, .desc, .title-book')) {
                                    let text = p.innerText.trim();
                                    if (text.length > 5 && !isJunk(text)) elements.push(p);
                                }
                            });
                        }
                    } else if (host.includes("freewebnovel")) {
                        let contentEl = document.querySelector('.txt') || document.querySelector('#htmlContent') || document.querySelector('.chapter-content');
                        if (contentEl) {
                            contentEl.querySelectorAll('p').forEach(p => {
                                if (!p.closest('.ads, .adsbygoogle, .nav-links, .chapter-nav')) {
                                    let text = p.innerText.trim();
                                    if (text.length > 5 && !isJunk(text)) elements.push(p);
                                }
                            });
                        }
                    } else if (host.includes("timotxt")) {
                        let contentEl = document.querySelector('.read-content') || document.querySelector('#content') || document.querySelector('.show_txt');
                        if (contentEl) {
                            prepareBrParagraphs(contentEl);
                            let pTags = contentEl.querySelectorAll('p, .wtr-line-segment');
                            pTags.forEach(p => {
                                if (!p.closest('.nav, .ads, .menu, .chapter-nav')) {
                                    let text = p.innerText.trim();
                                    if (text.length > 5 && !isJunk(text)) elements.push(p);
                                }
                            });
                        }
                    }

                    if (elements.length === 0) {
                        let bestContainer = null;
                        let maxPLength = 0;
                        document.querySelectorAll('div, article, section').forEach(el => {
                            if (!el.closest('nav, footer, h1, fieldset, form, header, script, style, #comments, .comments, .nav, .footer, .sidebar, #sidebar')) {
                                let pList = el.querySelectorAll('p');
                                if (pList.length > maxPLength) {
                                    maxPLength = pList.length;
                                    bestContainer = el;
                                }
                            }
                        });

                        if (bestContainer && maxPLength > 3) {
                            bestContainer.querySelectorAll('p').forEach(p => {
                                let text = p.innerText.trim();
                                if (text.length > 5 && !isJunk(text)) {
                                    elements.push(p);
                                }
                            });
                        } else {
                            let elems = document.querySelectorAll('p, li, h1, h2, h3, [class*="paragraph"], [id*="paragraph"]');
                            elems.forEach(el => {
                                let t = el.innerText.trim();
                                let isChinese = /[\u4e00-\u9fa5]/.test(t);
                                let isValidLength = isChinese ? t.length > 5 : t.length > 15;
                                if (isValidLength && !el.closest('nav, footer, h1, fieldset, form, header, script, style, #comments, .comments, .nav, .footer, .sidebar, #sidebar, .menu, #menu')) {
                                    if (!isJunk(t)) {
                                        elements.push(el);
                                    }
                                }
                            });
                        }
                    }

                    document.querySelectorAll('.wtr-focus-highlight').forEach(el => {
                        el.classList.remove('wtr-focus-highlight');
                        el.style.backgroundColor = '';
                        el.style.borderRadius = '';
                        el.style.padding = '';
                        el.style.transition = '';
                    });

                    if (targetIndex >= 0 && targetIndex < elements.length) {
                        let targetEl = elements[targetIndex];
                        if (targetEl) {
                            targetEl.classList.add('wtr-focus-highlight');
                            targetEl.style.transition = 'background-color 0.4s ease-in-out';
                            targetEl.style.backgroundColor = 'rgba(255, 235, 59, 0.25)';
                            targetEl.style.borderRadius = '6px';
                            targetEl.style.padding = '4px 8px';
                            
                            targetEl.scrollIntoView({
                                behavior: 'smooth',
                                block: 'center'
                            });
                        }
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(jsCode, null)
        } else {
            val clearJs = """
                (function() {
                    document.querySelectorAll('.wtr-focus-highlight').forEach(el => {
                        el.classList.remove('wtr-focus-highlight');
                        el.style.backgroundColor = '';
                        el.style.borderRadius = '';
                        el.style.padding = '';
                        el.style.transition = '';
                    });
                })();
            """.trimIndent()
            webView.evaluateJavascript(clearJs, null)
        }
    }

    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Surface(
                tonalElevation = 6.dp,
                shadowElevation = 3.dp,
                modifier = Modifier.statusBarsPadding()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        // URL Search pill styled exactly like Google Chrome with zero text clipping
                        val isHttps = urlText.startsWith("https://") || urlInput.startsWith("https://")
                        BasicTextField(
                            value = urlText,
                            onValueChange = {
                                urlText = it
                                viewModel.setUrlInput(it)
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp
                            ),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Go,
                                keyboardType = KeyboardType.Uri
                            ),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    viewModel.loadUrl(urlText)
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                    currentSection = BrowserSection.WEB
                                }
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .onFocusChanged { isSearchFocused = it.isFocused }
                                .weight(1f)
                                .height(40.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    RoundedCornerShape(20.dp)
                                  )
                                .padding(horizontal = 12.dp),
                            decorationBox = { innerTextField ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxHeight()
                                ) {
                                    Icon(
                                        imageVector = if (isHttps) Icons.Default.Lock else Icons.Default.Search,
                                        contentDescription = "Security Status",
                                        tint = if (isHttps) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Box(modifier = Modifier.weight(1f)) {
                                        if (urlText.isEmpty()) {
                                            Text(
                                                text = "Search or type URL",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.outline,
                                                fontSize = 14.sp
                                            )
                                        }
                                        innerTextField()
                                    }
                                    if (urlText.isNotEmpty()) {
                                        IconButton(
                                            onClick = {
                                                urlText = ""
                                                viewModel.setUrlInput("")
                                            },
                                            modifier = Modifier.size(18.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Clear,
                                                contentDescription = "Clear Text",
                                                tint = MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        )

                        // Google Chrome-styled interactive tab switcher badge button
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(24.dp)
                                .border(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    shape = RoundedCornerShape(5.dp)
                                )
                                .clip(RoundedCornerShape(5.dp))
                                .clickable {
                                    currentSection = BrowserSection.TABS
                                }
                                .testTag("chrome_tab_badge_button")
                        ) {
                            Text(
                                text = tabsList.size.toString(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More Menu",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                // Top row of custom action buttons (Exactly like Google Chrome premium interface)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceAround
                                ) {
                                    // Back arrow button
                                    IconButton(
                                        enabled = currentActiveWebView?.canGoBack() == true,
                                        onClick = {
                                            currentActiveWebView?.goBack()
                                            showMenu = false
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Default.ArrowBack,
                                            contentDescription = "Back",
                                            tint = if (currentActiveWebView?.canGoBack() == true) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                        )
                                    }

                                    // Forward arrow button
                                    IconButton(
                                        enabled = currentActiveWebView?.canGoForward() == true,
                                        onClick = {
                                            currentActiveWebView?.goForward()
                                            showMenu = false
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Default.ArrowForward,
                                            contentDescription = "Forward",
                                            tint = if (currentActiveWebView?.canGoForward() == true) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                        )
                                    }

                                    // Star Bookmark button
                                    IconButton(
                                        onClick = {
                                            activeTab?.let { tab ->
                                                val wv = currentActiveWebView
                                                if (wv != null && !isBookmarked) {
                                                    wv.evaluateJavascript(
                                                        """
                                                        (function() {
                                                            let meta = document.querySelector('meta[property="og:image"]');
                                                            if (meta && meta.content) return meta.content;
                                                            
                                                            let twitter = document.querySelector('meta[name="twitter:image"]');
                                                            if (twitter && twitter.content) return twitter.content;

                                                            let linkSrc = document.querySelector('link[rel="image_src"]');
                                                            if (linkSrc && linkSrc.href) return linkSrc.href;
                                                            
                                                            let img = document.querySelector('.book-cover img, .cover img, .novel-cover img, img.cover, .cover-box img, .pic img, img.book-img');
                                                            if (img && img.src) return img.src;

                                                            let allImgs = Array.from(document.querySelectorAll('img'));
                                                            for (let im of allImgs) {
                                                                if (im.src && (im.src.includes('cover') || im.className.includes('cover') || im.id.includes('cover'))) {
                                                                    return im.src;
                                                                }
                                                            }
                                                            return '';
                                                        })()
                                                        """.trimIndent()
                                                    ) { res: String? ->
                                                        var coverUrl = res ?: ""
                                                        if (coverUrl.startsWith("\"") && coverUrl.endsWith("\"") && coverUrl.length >= 2) {
                                                             coverUrl = coverUrl.substring(1, coverUrl.length - 1)
                                                        }
                                                        val finalUrl = if (coverUrl.isEmpty() || coverUrl == "null") null else coverUrl
                                                        viewModel.toggleBookmark(tab.url, tab.title, finalUrl)
                                                    }
                                                } else {
                                                    viewModel.toggleBookmark(tab.url, tab.title)
                                                }
                                            }
                                            showMenu = false
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                                            contentDescription = if (isBookmarked) "Bookmarked" else "Add Bookmark",
                                            tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    // Reload button
                                    IconButton(
                                        onClick = {
                                            if (currentSection != BrowserSection.WEB) {
                                                currentSection = BrowserSection.WEB
                                            } else {
                                                currentActiveWebView?.reload()
                                            }
                                            showMenu = false
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Reload",
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    // Home page button
                                    IconButton(
                                        onClick = {
                                            viewModel.loadUrl("chrome://newtab")
                                            currentSection = BrowserSection.WEB
                                            showMenu = false
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Home,
                                            contentDescription = "Home",
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))

                                DropdownMenuItem(
                                    text = { Text("Open New Tab") },
                                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                    onClick = {
                                        viewModel.addNewTab()
                                        currentSection = BrowserSection.WEB
                                        showMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("All Tabs (${tabsList.size})") },
                                    leadingIcon = { Icon(Icons.Default.Tab, contentDescription = null) },
                                    onClick = {
                                        currentSection = BrowserSection.TABS
                                        showMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Bookmarks") },
                                    leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) },
                                    onClick = {
                                        currentSection = BrowserSection.BOOKMARKS
                                        showMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Navigation History") },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    onClick = {
                                        currentSection = BrowserSection.HISTORY
                                        showMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Desktop site") },
                                    leadingIcon = { Icon(Icons.Default.Laptop, contentDescription = null) },
                                    trailingIcon = {
                                        Checkbox(
                                            checked = activeTab?.isDesktopMode == true,
                                            onCheckedChange = { checked ->
                                                activeTab?.let { viewModel.toggleDesktopMode(it, checked) }
                                                showMenu = false
                                              }
                                        )
                                    },
                                    onClick = {
                                        activeTab?.let { viewModel.toggleDesktopMode(it, !(it.isDesktopMode)) }
                                        showMenu = false
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("Browser & Reader Settings") },
                                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    onClick = {
                                        showSettingsDialog = true
                                        showMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("View Diagnostic Logs") },
                                    leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                                    onClick = {
                                        showLogsDialog = true
                                        showMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Clear Cache & Storage") },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                    onClick = {
                                        currentActiveWebView?.clearCache(true)
                                        android.webkit.WebStorage.getInstance().deleteAllData()
                                        android.webkit.CookieManager.getInstance().removeAllCookies(null)
                                        android.widget.Toast.makeText(context, "Storage and cache cleared successfully!", android.widget.Toast.LENGTH_SHORT).show()
                                        showMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Translate Page") },
                                    leadingIcon = { Icon(Icons.Default.Translate, contentDescription = null) },
                                    onClick = {
                                        currentActiveWebView?.let { webView ->
                                            val currentUrl = webView.url
                                            if (currentUrl != null && !currentUrl.contains("translate.goog") && !currentUrl.contains("translate.google")) {
                                                val translatedUrl = getProxyTranslatedUrl(currentUrl)
                                                webView.loadUrl(translatedUrl)
                                            } else {
                                                android.widget.Toast.makeText(context, "Page is already translated or invalid", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        showMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // URL Suggestions like Chrome
                    val currentQuery = urlInput.trim().lowercase()
                    val builtInSuggestions = listOf(
                        "https://wtr-lab.com/en" to "Wtr-Lab (Main reader)",
                        "https://www.webnovel.com/" to "WebNovel",
                        "https://www.novelhall.com/" to "Novelhall",
                        "https://www.fanmtl.com/" to "Fanmtl",
                        "https://novelbin.me/" to "NovelBin",
                        "https://freewebnovel.com/index" to "Freewebnovel",
                        "https://www.timotxt.com/" to "TimoTxt"
                    )
                    
                    val suggestionsToDisplay = if (currentQuery.isNotEmpty() && currentQuery != "chrome://newtab") {
                        builtInSuggestions.filter { (url, title) ->
                            title.lowercase().contains(currentQuery) || 
                            url.lowercase().contains(currentQuery) ||
                            currentQuery.contains(title.lowercase()) ||
                            currentQuery.contains(url.lowercase().removePrefix("https://").removePrefix("www."))
                        }
                    } else {
                        emptyList()
                    }

                    if (isSearchFocused && suggestionsToDisplay.isNotEmpty()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "SUGGESTIONS",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                                suggestionsToDisplay.forEach { (url, title) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                viewModel.setUrlInput(url)
                                                viewModel.loadUrl(url)
                                                keyboardController?.hide()
                                                focusManager.clearFocus()
                                                currentSection = BrowserSection.WEB
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = title,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = url,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Default.ArrowForward,
                                            contentDescription = "Navigate",
                                            tint = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Loading progress indicator
                    if (isWebLoading && webProgress < 100) {
                        LinearProgressIndicator(
                            progress = { webProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Render our beautiful Chrome Home or the resolved dynamic WebView inside our view hierarchy
            if (activeTab != null && activeTab!!.url == "chrome://newtab") {
                ChromeNewTabPage(
                    onNavigate = { targetUrl ->
                        viewModel.loadUrl(targetUrl)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else if (activeTab != null && currentActiveWebView != null) {
                key(activeTab!!.id) {
                    AndroidView(
                        factory = { 
                            val wv = currentActiveWebView!!
                            if ((wv.url ?: "").isEmpty() && activeTab!!.url != "chrome://newtab") {
                                wv.loadUrl(activeTab!!.url)
                            }
                            wv
                        },
                        update = { wv ->
                            val targetUrl = activeTab?.url ?: ""
                            if (targetUrl.isNotEmpty() && targetUrl != "chrome://newtab") {
                                val currentUrl = wv.url ?: ""
                                if (currentUrl.isEmpty()) {
                                    wv.loadUrl(targetUrl)
                                } else if (!isSameBaseOrTranslatedUrl(currentUrl, targetUrl)) {
                                    wv.loadUrl(targetUrl)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Adjust webview visibility in parent bounds based on current section selection
            LaunchedEffect(currentSection, currentActiveWebView, activeTab?.url) {
                currentActiveWebView?.visibility = if (currentSection == BrowserSection.WEB && activeTab?.url != "chrome://newtab") {
                    android.view.View.VISIBLE
                } else {
                    android.view.View.INVISIBLE
                }
            }

            val urlVal = activeTab?.url ?: ""
            val isWtrLab = urlVal.contains("wtr-lab.com") || urlVal.startsWith("file://") || urlVal.isEmpty()

            // Custom TrackPlayer Bar for any websites besides Wtr Lab
            if (currentSection == BrowserSection.WEB && !isWtrLab && enableWebTrackplayer) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    if (playTrackInputList.isEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            shape = RoundedCornerShape(16.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Column {
                                        Text(
                                            text = "Listen to Webpage",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Text(
                                            text = "Let the TTS engine read the text content of this page aloud.",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Button(
                                    onClick = {
                                        WtrAudioControlBridge.setIsAudiobookModeActive(true)
                                        runHtmlTextExtractionAndPlay()
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    enabled = !isExtracting,
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    if (isExtracting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Text("Extract & Play", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    } else {
                        // Playback Tracker Panel Control
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            ),
                            shape = RoundedCornerShape(16.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = "Playing: ${activeTab?.title?.take(18) ?: "Web Article"}...",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        ) {
                                            Text(
                                                text = "${currentTrackIndex + 1}/${playTrackInputList.size}",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.padding(horizontal = 4.dp)
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = { stopCustomPlayback() },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Stop",
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                // Interactive Text Preview window showing the sentence live
                                if (currentlySpeakingText.isNotEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                            .padding(10.dp)
                                    ) {
                                        Text(
                                            text = currentlySpeakingText,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                // Interactive Controls: Skip Previous, Play/Pause, Skip Next, Cycle Speeds
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Button(
                                        onClick = {
                                            val nextSp = when (activeTtsSpeed) {
                                                1.0f -> 2.0f
                                                2.0f -> 3.0f
                                                3.0f -> 4.0f
                                                4.0f -> 5.0f
                                                else -> 1.0f
                                            }
                                            WtrAudioControlBridge.setTtsSpeed(nextSp)
                                        },
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        ),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text(
                                            text = "Speed: ${activeTtsSpeed.toInt()}x",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        FilledTonalIconButton(
                                            onClick = { playCustomParagraph(currentTrackIndex - 1) },
                                            enabled = currentTrackIndex > 0,
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Default.ArrowBack,
                                                contentDescription = "Previous Paragraph",
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                if (isPlayerRunning) {
                                                    pauseCustomVolume()
                                                } else {
                                                    resumeCustomVolume()
                                                }
                                            },
                                            modifier = Modifier
                                                .size(46.dp)
                                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(23.dp))
                                        ) {
                                            Icon(
                                                imageVector = if (isPlayerRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                contentDescription = if (isPlayerRunning) "Pause" else "Play",
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }

                                        FilledTonalIconButton(
                                            onClick = { playCustomParagraph(currentTrackIndex + 1) },
                                            enabled = currentTrackIndex < playTrackInputList.size - 1,
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Default.ArrowForward,
                                                contentDescription = "Next Paragraph",
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Secondary visual browser overlay screens (Tabs, Bookmarks, History Panels)
            AnimatedVisibility(
                visible = currentSection == BrowserSection.TABS,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
                modifier = Modifier.fillMaxSize()
            ) {
                TabsPanel(
                    viewModel = viewModel,
                    onTabSelected = { currentSection = BrowserSection.WEB }
                )
            }

            AnimatedVisibility(
                visible = currentSection == BrowserSection.BOOKMARKS,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
                modifier = Modifier.fillMaxSize()
            ) {
                BookmarksPanel(
                    viewModel = viewModel,
                    onUrlSelected = { url ->
                        val cleanUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            "https://$url"
                        } else {
                            url
                        }
                        viewModel.loadUrl(cleanUrl)
                        currentSection = BrowserSection.WEB
                    },
                    onDismiss = { currentSection = BrowserSection.WEB }
                )
            }

            AnimatedVisibility(
                visible = currentSection == BrowserSection.HISTORY,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
                modifier = Modifier.fillMaxSize()
            ) {
                HistoryPanel(
                    viewModel = viewModel,
                    onUrlSelected = { url ->
                        val cleanUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            "https://$url"
                        } else {
                            url
                        }
                        viewModel.loadUrl(cleanUrl)
                        currentSection = BrowserSection.WEB
                    },
                    onDismiss = { currentSection = BrowserSection.WEB }
                )
            }

            // Settings Overlays Trigger
            if (showSettingsDialog) {
                SettingsDialog(
                    onDismissRequest = { 
                        showSettingsDialog = false 
                        // Sync preference values immediately upon settings closing to update our active components state dynamically
                        enableWebTrackplayer = sharedPrefs.getBoolean("enable_web_trackplayer", false)
                        forceDarkContent = sharedPrefs.getBoolean("force_dark_content", false)
                        autoFocusParagraphs = sharedPrefs.getBoolean("auto_focus_paragraphs", true)
                        autoTranslateEnabled = sharedPrefs.getBoolean("auto_translate_enabled", true)
                        autoTranslateDomains = sharedPrefs.getString("auto_translate_domains", "timotxt.com, timotxt") ?: "timotxt.com, timotxt"
                        adBlockerEnabled = sharedPrefs.getBoolean("ad_blocker_enabled", true)
                        customTextZoom = sharedPrefs.getInt("custom_text_zoom", 115)
                        currentThemeName = sharedPrefs.getString("app_theme", "Dark") ?: "Dark"
                    },
                    viewModel = viewModel,
                    onThemeChanged = { onThemeChanged(it) },
                    webViewsMap = webViewsMap
                )
            }

            if (showLogsDialog) {
                AlertDialog(
                    onDismissRequest = { showLogsDialog = false },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = "System Diagnostic Logs",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    },
                    text = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                        ) {
                            Text(
                                text = "Showing last ${com.example.WtrLogManager.logs.size} operations. Perfect for troubleshooting novel loading issues.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            
                            val logs = com.example.WtrLogManager.logs
                            if (logs.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("No logs recorded yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                                }
                            } else {
                                androidx.compose.foundation.lazy.LazyColumn(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                        .padding(8.dp)
                                ) {
                                    items(logs.size) { idx ->
                                        val logText = logs[idx]
                                        Text(
                                            text = logText,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.06f))
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showLogsDialog = false }) {
                            Text("Close")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { 
                                com.example.WtrLogManager.clear(context)
                            }
                        ) {
                            Text("Clear Logs", color = MaterialTheme.colorScheme.error)
                        }
                    }
                )
            }

            // Chromelike Long Press Context Menu Overlay
            longPressedUrl?.let { url ->
                AlertDialog(
                    onDismissRequest = { longPressedUrl = null },
                    title = {
                        Text(
                            text = if (url.length > 55) url.take(52) + "..." else url,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    },
                    text = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            // Open in Current Tab
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.loadUrl(url)
                                        currentSection = BrowserSection.WEB
                                        longPressedUrl = null
                                    }
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowForward,
                                    contentDescription = "Open Link",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Open in current tab",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            
                            // Open in New Tab
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.addNewTab(url, "New Tab")
                                        currentSection = BrowserSection.WEB
                                        longPressedUrl = null
                                    }
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add New Tab",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Open in new tab",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            
                            // Copy Link
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val clip = android.content.ClipData.newPlainText("Link Address", url)
                                        clipboard.setPrimaryClip(clip)
                                        android.widget.Toast.makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                                        longPressedUrl = null
                                    }
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Copy Link",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Copy link address",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            
                            // Share Link
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_TEXT, url)
                                        }
                                        context.startActivity(android.content.Intent.createChooser(intent, "Share Link"))
                                        longPressedUrl = null
                                    }
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share Link",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Share link",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { longPressedUrl = null }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

// Unified context-safe preferences helper for smart paragraph-level tracking
private fun getSavedParagraphIndex(context: android.content.Context, url: String): Int {
    if (url.isEmpty() || url == "chrome://newtab") return 0
    val settingsPrefs = context.getSharedPreferences("wtr_browser_settings", android.content.Context.MODE_PRIVATE)
    val enabled = settingsPrefs.getBoolean("remember_paragraphs", true)
    if (!enabled) return 0

    val prefs = context.getSharedPreferences("wtr_tts_progress", android.content.Context.MODE_PRIVATE)
    val cleanUrl = cleanUrlForTts(url)
    return prefs.getInt(cleanUrl, 0)
}

private fun saveParagraphIndex(context: android.content.Context, url: String, index: Int) {
    if (url.isEmpty() || url == "chrome://newtab" || index < 0) return
    val settingsPrefs = context.getSharedPreferences("wtr_browser_settings", android.content.Context.MODE_PRIVATE)
    val enabled = settingsPrefs.getBoolean("remember_paragraphs", true)
    if (!enabled) return

    val prefs = context.getSharedPreferences("wtr_tts_progress", android.content.Context.MODE_PRIVATE)
    val cleanUrl = cleanUrlForTts(url)
    prefs.edit().putInt(cleanUrl, index).apply()
}

private fun cleanUrlForTts(url: String): String {
    if (url.isEmpty() || url == "chrome://newtab") return ""
    var clean = url
    if (clean.contains("translate.goog")) {
        try {
            val uri = android.net.Uri.parse(clean)
            val uParam = uri.getQueryParameter("u")
            if (!uParam.isNullOrEmpty()) {
                clean = uParam
            } else {
                val host = uri.host ?: ""
                if (host.isNotEmpty()) {
                    val cleanHost = host.replace(".translate.goog", "").replace("-", ".")
                    val scheme = if (url.startsWith("https")) "https" else "http"
                    clean = "$scheme://$cleanHost${uri.path ?: ""}"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    return clean.split("?")[0].split("#")[0]
}

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

private fun getCleanDisplayUrl(url: String): String {
    if (url.isEmpty() || url == "chrome://newtab") return ""
    if (url.contains("translate.goog") || url.contains("translate.google")) {
        try {
            val uri = android.net.Uri.parse(url)
            val uParam = uri.getQueryParameter("u")
            if (!uParam.isNullOrEmpty()) {
                return uParam
            }
            val host = uri.host ?: ""
            if (host.isNotEmpty()) {
                val cleanHost = host.replace(".translate.goog", "").replace("-", ".")
                val scheme = if (url.startsWith("https")) "https" else "http"
                return "$scheme://$cleanHost${uri.path ?: ""}"
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    return url
}

