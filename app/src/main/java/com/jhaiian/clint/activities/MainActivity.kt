package com.jhaiian.clint.activities

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.preference.PreferenceManager
import com.jhaiian.clint.R
import com.jhaiian.clint.bookmarks.Bookmark
import com.jhaiian.clint.bookmarks.BookmarkManager
import com.jhaiian.clint.crash.CrashHandler
import com.jhaiian.clint.databinding.ActivityMainBinding
import com.jhaiian.clint.downloads.ClintDownloadManager
import com.jhaiian.clint.network.DohManager
import com.jhaiian.clint.tabs.BrowserTab
import com.jhaiian.clint.tabs.TabManager
import com.jhaiian.clint.tabs.TabSwitcherSheet
import com.jhaiian.clint.update.UpdateChecker
import com.jhaiian.clint.webview.ClintSwipeRefreshLayout
import com.jhaiian.clint.webview.ClintWebChromeClient
import com.jhaiian.clint.webview.ClintWebViewClient

class MainActivity : ClintActivity(), TabSwitcherSheet.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val tabManager = TabManager()
    private var isDesktopMode = false
    private val desktopScriptHandlers = mutableMapOf<String, ScriptHandler>()

    companion object {
        private val DESKTOP_SCRIPT = """
(function(){
try{Object.defineProperty(navigator,'maxTouchPoints',{get:function(){return 0;},configurable:true});}catch(e){}
try{Object.defineProperty(navigator,'msMaxTouchPoints',{get:function(){return 0;},configurable:true});}catch(e){}
try{Object.defineProperty(navigator,'platform',{get:function(){return 'Win32';},configurable:true});}catch(e){}
try{Object.defineProperty(window,'ontouchstart',{value:undefined,configurable:true,writable:true});}catch(e){}
try{Object.defineProperty(window,'ontouchmove',{value:undefined,configurable:true,writable:true});}catch(e){}
try{Object.defineProperty(window,'ontouchend',{value:undefined,configurable:true,writable:true});}catch(e){}
var fixVp=function(){
  var m=document.querySelector('meta[name="viewport"]');
  if(m){if(m.content.indexOf('device-width')!==-1)m.content='width=1280';}
  else if(document.head){var n=document.createElement('meta');n.name='viewport';n.content='width=1280';document.head.insertBefore(n,document.head.firstChild);}
};
new MutationObserver(fixVp).observe(document.documentElement,{childList:true,subtree:true});
document.addEventListener('DOMContentLoaded',fixVp);
if(document.readyState!=='loading')fixVp();
})();
        """.trimIndent()
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    private var pendingFileChooserCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null
    private var pendingFileChooserParams: android.webkit.WebChromeClient.FileChooserParams? = null
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val cb = pendingFileChooserCallback
        val params = pendingFileChooserParams
        pendingFileChooserCallback = null
        pendingFileChooserParams = null
        if (granted && cb != null && params != null) {
            launchFileChooser(cb, params)
        } else if (cb != null) {
            cb.onReceiveValue(null)
        }
    }

    private var filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null
    private var cameraImageUri: android.net.Uri? = null
    private var cameraVideoUri: android.net.Uri? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            when {
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { clip.getItemAt(it).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                cameraImageUri != null -> arrayOf(cameraImageUri!!)
                else -> null
            }
        } else {
            cameraImageUri?.let { contentResolver.delete(it, null, null) }
            null
        }
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
        cameraImageUri = null
        cameraVideoUri = null
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "javascript_enabled" -> applyJavaScript()
            "block_third_party_cookies" -> applyCookiePolicy()
            "custom_user_agent" -> applyUserAgent()
            "block_trackers" -> reattachWebClients()
            "doh_mode", "doh_provider" -> { DohManager.invalidate(); reattachWebClients() }
            "force_dark_web" -> {
                tabManager.tabs.forEach { applyWebDarkMode(it.webView) }
                tabManager.activeTab?.webView?.reload()
            }
            "hide_bars_on_scroll" -> {
                if (!prefs.getBoolean("hide_bars_on_scroll", true)) animateBars(hide = false, animated = false)
            }
        }
    }

    private var topBarFullHeight = 0
    private var bottomBarFullHeight = 0
    private var statusBarInsetPx = 0
    private var cachedStatusBarInsetPx = 0
    private var barsHidden = false
    private var barAnimator: ValueAnimator? = null
    private var nestedScrollActive = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashHandler.install(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        applyStatusBarVisibility()
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbarTop) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            if (statusBars.top > 0) cachedStatusBarInsetPx = statusBars.top
            val effectivePadding = if (prefs.getBoolean("hide_status_bar", false)) 0 else statusBars.top
            statusBarInsetPx = effectivePadding
            v.setPadding(0, effectivePadding, 0, 0)
            v.post {
                if (topBarFullHeight == 0 && v.height > 0) {
                    topBarFullHeight = v.height
                    binding.swipeRefresh.setProgressViewOffset(false, v.height + 4, v.height + 72)
                }
            }
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomBar) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(0, 0, 0, navBars.bottom)
            v.post {
                if (bottomBarFullHeight == 0 && v.height > 0) bottomBarFullHeight = v.height
            }
            insets
        }
        ClintDownloadManager.createNotificationChannel(this)
        ClintDownloadManager.init(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (prefs.getBoolean("check_update_on_launch", true)) {
            val skipOnMetered = prefs.getBoolean("skip_update_on_metered", true)
            val isBeta = prefs.getBoolean("beta_channel", false)
            if (!skipOnMetered || !isNetworkMetered()) {
                UpdateChecker.check(this, isBeta, silent = true)
            }
        }
        setupSwipeRefresh()
        setupAddressBar()
        setupNavigationButtons()
        val intentUrl = intent?.data?.toString()
        if (!intentUrl.isNullOrEmpty()) {
            openNewTab(isIncognito = false, url = intentUrl)
        } else if (!restoreTabs()) {
            openNewTab(isIncognito = false, url = getSearchEngineHomeUrl())
        }
    }

    override fun onStop() {
        super.onStop()
        saveTabs()
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        tabManager.destroyAll()
        super.onDestroy()
    }

    private fun saveTabs() {
        val urls = tabManager.tabs
            .filter { !it.isIncognito }
            .mapNotNull { tab ->
                val url = tab.webView.url ?: tab.url
                url.takeIf { it.isNotEmpty() && it != "about:blank" }
            }
        val activeNonIncognito = tabManager.tabs
            .filter { !it.isIncognito }
            .indexOf(tabManager.activeTab)
            .coerceAtLeast(0)
        prefs.edit()
            .putString("saved_tab_urls", urls.joinToString("\n"))
            .putInt("saved_tab_active", activeNonIncognito)
            .apply()
    }

    private fun restoreTabs(): Boolean {
        val savedUrls = prefs.getString("saved_tab_urls", null)
            ?.split("\n")
            ?.filter { it.isNotEmpty() }
            ?: return false
        if (savedUrls.isEmpty()) return false
        val activeIdx = prefs.getInt("saved_tab_active", 0).coerceIn(0, savedUrls.lastIndex)
        savedUrls.forEach { url -> openNewTabSilent(url) }
        tabManager.switchTo(activeIdx)
        attachActiveWebView()
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openNewTabSilent(url: String) {
        val webView = createWebView(false)
        val tab = BrowserTab(url = url, webView = webView)
        tabManager.add(tab)
        if (isDesktopMode) addDesktopScript(tab)
        webView.webViewClient = ClintWebViewClient(prefs) { tabManager.activeTab?.id == tab.id }
        webView.webChromeClient = ClintWebChromeClient(
            isActive = { tabManager.activeTab?.id == tab.id },
            onTitleChanged = { title ->
                tab.title = title
                if (tabManager.activeTab?.id == tab.id) updateTabCount()
            },
            onFullscreenShow = { view, cb -> onShowCustomView(view, cb) },
            onFullscreenHide = { exitFullscreen() },
            onFileChooser = { callback, params -> onShowFileChooser(callback, params) },
            onNewWindowRequest = { url ->
                val uri = android.net.Uri.parse(url)
                val scheme = uri.scheme?.lowercase()
                val activeWebView = tabManager.activeTab?.webView
                val client = activeWebView?.webViewClient as? ClintWebViewClient
                if (scheme == "http" || scheme == "https") {
                    if (client == null || !client.tryOpenInApp(activeWebView, uri)) {
                        openNewTab(isIncognito = tab.isIncognito, url = url)
                    }
                } else {
                    openNewTab(isIncognito = tab.isIncognito, url = url)
                }
            }
        )
        webView.loadUrl(url)
    }

    private fun animateBars(hide: Boolean, animated: Boolean) {
        if (hide == barsHidden) return
        if (hide && !prefs.getBoolean("hide_bars_on_scroll", true)) return
        if (topBarFullHeight == 0 || bottomBarFullHeight == 0) return
        barAnimator?.cancel()
        barsHidden = hide
        if (hide) binding.swipeRefresh.isEnabled = false
        val topFrom = if (hide) topBarFullHeight else statusBarInsetPx
        val topTo   = if (hide) statusBarInsetPx   else topBarFullHeight
        val botFrom = if (hide) bottomBarFullHeight else 0
        val botTo   = if (hide) 0                  else bottomBarFullHeight
        if (!animated) {
            binding.toolbarTop.updateLayoutParams { height = topTo }
            binding.bottomBar.updateLayoutParams { height = botTo }
            if (!hide) binding.swipeRefresh.isEnabled = true
            return
        }
        barAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (hide) 250L else 200L
            interpolator = if (hide) AccelerateInterpolator() else DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                binding.toolbarTop.updateLayoutParams { height = (topFrom + (topTo - topFrom) * f).toInt() }
                binding.bottomBar.updateLayoutParams { height = (botFrom + (botTo - botFrom) * f).toInt() }
            }
            start()
        }
    }

    private fun attachScrollListener(webView: WebView) {
        val density = resources.displayMetrics.density
        val threshold = density * 40f
        var accumulated = 0f

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                accumulated += distanceY
                when {
                    accumulated > threshold -> {
                        animateBars(hide = true, animated = true)
                        accumulated = 0f
                    }
                    accumulated < -threshold -> {
                        animateBars(hide = false, animated = true)
                        accumulated = 0f
                    }
                }
                return false
            }
        })

        webView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> accumulated = 0f
                MotionEvent.ACTION_MOVE -> queryNestedScroll(webView)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!barsHidden) binding.swipeRefresh.isEnabled = true
                }
            }
            detector.onTouchEvent(event)
            false
        }
    }

    private val scrollTrackJs = """
        (function() {
            if (window.__clintTracked) return;
            window.__clintTracked = true;
            window.__clintNestedScrolled = false;
            document.addEventListener('scroll', function(e) {
                var t = e.target;
                var isRoot = !t || t === document || t === document.documentElement || t === document.body;
                if (!isRoot) {
                    window.__clintNestedScrolled = (t.scrollTop > 0 || t.scrollLeft > 0);
                } else {
                    window.__clintNestedScrolled = false;
                }
            }, true);
        })();
    """.trimIndent()

    private fun injectScrollTracker(webView: WebView) {
        webView.evaluateJavascript(scrollTrackJs, null)
    }

    private fun queryNestedScroll(webView: WebView) {
        if (isYouTubeShorts()) return
        webView.evaluateJavascript(
            "(typeof window.__clintNestedScrolled !== 'undefined' && window.__clintNestedScrolled).toString()"
        ) { result ->
            nestedScrollActive = result?.trim('"') == "true"
        }
    }

    private fun isYouTubeShorts(): Boolean {
        val url = tabManager.activeTab?.webView?.url ?: return false
        return url.contains("youtube.com/shorts", ignoreCase = true)
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.canChildScrollUpCallback = {
            barsHidden || isYouTubeShorts() || run {
                val wv = tabManager.activeTab?.webView
                wv != null && (wv.canScrollVertically(-1) || nestedScrollActive)
            }
        }
        binding.swipeRefresh.apply {
            setColorSchemeColors(
                ContextCompat.getColor(this@MainActivity, R.color.purple_300),
                ContextCompat.getColor(this@MainActivity, R.color.purple_200)
            )
            setProgressBackgroundColorSchemeColor(
                ContextCompat.getColor(this@MainActivity, R.color.toolbar_color)
            )
            setOnRefreshListener {
                nestedScrollActive = false
                tabManager.activeTab?.webView?.reload() ?: run { isRefreshing = false }
            }
        }
    }

    private fun applyJavaScript() {
        val enabled = prefs.getBoolean("javascript_enabled", true)
        tabManager.tabs.forEach { it.webView.settings.javaScriptEnabled = enabled }
        tabManager.activeTab?.webView?.reload()
    }

    private fun applyCookiePolicy() {
        val blockThirdParty = prefs.getBoolean("block_third_party_cookies", true)
        val cookieManager = CookieManager.getInstance()
        tabManager.tabs.forEach { tab ->
            if (!tab.isIncognito) cookieManager.setAcceptThirdPartyCookies(tab.webView, !blockThirdParty)
        }
        tabManager.activeTab?.webView?.reload()
    }

    private fun applyUserAgent() {
        val ua = buildUserAgent()
        tabManager.tabs.forEach { it.webView.settings.userAgentString = ua }
        tabManager.activeTab?.webView?.reload()
    }

    private val darkModeCss = """
        (function() {
            var id = '__clint_dark_mode';
            var existing = document.getElementById(id);
            if (existing) { existing.remove(); return; }
            var s = document.createElement('style');
            s.id = id;
            s.textContent = 'html { filter: invert(100%) hue-rotate(180deg) !important; background: #fff !important; } img, video, canvas, picture, svg, iframe { filter: invert(100%) hue-rotate(180deg) !important; }';
            (document.head || document.documentElement).appendChild(s);
        })();
    """.trimIndent()

    @Suppress("DEPRECATION")
    private fun applyWebDarkMode(webView: WebView) {
        val enabled = prefs.getBoolean("force_dark_web", false)
        val settings = webView.settings
        when {
            WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING) ->
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, enabled)
            WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK) ->
                WebSettingsCompat.setForceDark(
                    settings,
                    if (enabled) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
                )
            // API < 29: CSS injection handled in onPageFinished
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun reattachWebClients() {
        tabManager.tabs.forEach { tab ->
            tab.webView.webViewClient = ClintWebViewClient(prefs) { tabManager.activeTab?.id == tab.id }
        }
    }

    private fun getSearchEngineHomeUrl(): String {
        return when (prefs.getString("search_engine", "duckduckgo")) {
            "brave" -> "https://search.brave.com"
            "google" -> "https://www.google.com"
            else -> "https://duckduckgo.com"
        }
    }

    private fun getSearchQueryUrl(query: String): String {
        val encoded = Uri.encode(query)
        return when (prefs.getString("search_engine", "duckduckgo")) {
            "brave" -> "https://search.brave.com/search?q=$encoded"
            "google" -> "https://www.google.com/search?q=$encoded"
            else -> "https://duckduckgo.com/?q=$encoded"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(isIncognito: Boolean): WebView {
        val webView = WebView(this)
        val settings = webView.settings
        settings.javaScriptEnabled = prefs.getBoolean("javascript_enabled", true)
        settings.domStorageEnabled = !isIncognito
        settings.cacheMode = if (isIncognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        settings.setSupportZoom(true)
        settings.setSupportMultipleWindows(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.safeBrowsingEnabled = false
        settings.userAgentString = buildUserAgent()
        val cookieManager = CookieManager.getInstance()
        if (isIncognito) {
            cookieManager.setAcceptCookie(false)
        } else {
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, !prefs.getBoolean("block_third_party_cookies", true))
        }
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
            ClintDownloadManager.enqueue(this, url, filename, userAgent)
        }
        applyWebDarkMode(webView)
        return webView
    }

    private fun addDesktopScript(tab: BrowserTab) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        removeDesktopScript(tab)
        desktopScriptHandlers[tab.id] = WebViewCompat.addDocumentStartJavaScript(tab.webView, DESKTOP_SCRIPT, setOf("*"))
    }

    private fun removeDesktopScript(tab: BrowserTab) {
        desktopScriptHandlers.remove(tab.id)?.remove()
    }

    private fun buildUserAgent(): String {
        return when {
            isDesktopMode ->
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
            prefs.getBoolean("custom_user_agent", true) ->
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
            else ->
                WebSettings.getDefaultUserAgent(this)
        }
    }

    private fun openNewTab(isIncognito: Boolean, url: String = getSearchEngineHomeUrl()) {
        val webView = createWebView(isIncognito)
        val tab = BrowserTab(isIncognito = isIncognito, webView = webView)
        val index = tabManager.add(tab)
        if (isDesktopMode) addDesktopScript(tab)
        webView.webViewClient = ClintWebViewClient(prefs) { tabManager.activeTab?.id == tab.id }
        webView.webChromeClient = ClintWebChromeClient(
            isActive = { tabManager.activeTab?.id == tab.id },
            onTitleChanged = { title ->
                tab.title = title
                if (tabManager.activeTab?.id == tab.id) updateTabCount()
            },
            onFullscreenShow = { view, cb -> onShowCustomView(view, cb) },
            onFullscreenHide = { exitFullscreen() },
            onFileChooser = { callback, params -> onShowFileChooser(callback, params) },
            onNewWindowRequest = { url ->
                val uri = android.net.Uri.parse(url)
                val scheme = uri.scheme?.lowercase()
                val activeWebView = tabManager.activeTab?.webView
                val client = activeWebView?.webViewClient as? ClintWebViewClient
                if (scheme == "http" || scheme == "https") {
                    if (client == null || !client.tryOpenInApp(activeWebView, uri)) {
                        openNewTab(isIncognito = isIncognito, url = url)
                    }
                } else {
                    openNewTab(isIncognito = isIncognito, url = url)
                }
            }
        )
        tabManager.switchTo(index)
        attachActiveWebView()
        loadUrl(url)
    }

    private fun attachActiveWebView() {
        val tab = tabManager.activeTab ?: return
        binding.webContainer.removeAllViews()
        (tab.webView.parent as? android.view.ViewGroup)?.removeView(tab.webView)
        binding.webContainer.addView(tab.webView, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))
        updateIncognitoState(tab.isIncognito)
        updateSwipeRefreshColors(tab.isIncognito)
        updateTabCount()
        updateAddressBar(tab.webView.url ?: "")
        updateNavigationState()
        updateBookmarkIcon()
        val cookieManager = CookieManager.getInstance()
        if (tab.isIncognito) {
            cookieManager.setAcceptCookie(false)
        } else {
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(tab.webView, !prefs.getBoolean("block_third_party_cookies", true))
        }
        nestedScrollActive = false
        animateBars(hide = false, animated = false)
        attachScrollListener(tab.webView)
    }

    private fun updateIncognitoState(isIncognito: Boolean) {
        binding.incognitoIcon.visibility = if (isIncognito) View.VISIBLE else View.GONE
        val color = ContextCompat.getColor(this, if (isIncognito) R.color.incognito_toolbar_color else R.color.toolbar_color)
        binding.toolbarTop.setBackgroundColor(color)
        binding.bottomBar.setBackgroundColor(color)
    }

    private fun updateSwipeRefreshColors(isIncognito: Boolean) {
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, if (isIncognito) R.color.incognito_toolbar_color else R.color.toolbar_color)
        )
        if (isIncognito) {
            binding.swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.incognito_accent))
        } else {
            binding.swipeRefresh.setColorSchemeColors(
                ContextCompat.getColor(this, R.color.purple_300),
                ContextCompat.getColor(this, R.color.purple_200)
            )
        }
    }

    private fun updateTabCount() {
        val count = tabManager.count
        binding.btnTabCount.text = if (count > 99) ":D" else count.toString()
    }

    private fun setupAddressBar() {
        binding.addressBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                navigateToInput(); true
            } else false
        }
        binding.addressBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.addressBar.post { binding.addressBar.selectAll() }
            } else {
                updateAddressBar(tabManager.activeTab?.webView?.url ?: "")
            }
        }
    }

    private fun setupNavigationButtons() {
        binding.btnBack.setOnClickListener { tabManager.activeTab?.webView?.let { if (it.canGoBack()) it.goBack() } }
        binding.btnForward.setOnClickListener { tabManager.activeTab?.webView?.let { if (it.canGoForward()) it.goForward() } }
        binding.btnRefresh.setOnClickListener {
            tabManager.activeTab?.webView?.let { wv ->
                if (binding.progressBar.visibility == View.VISIBLE) {
                    wv.stopLoading(); onPageFinished(wv.url ?: "")
                } else { wv.reload() }
            }
        }
        binding.btnHome.setOnClickListener { loadUrl(getSearchEngineHomeUrl()) }
        binding.btnTabCount.setOnClickListener { showTabSwitcher() }
        binding.btnBookmark.setOnClickListener {
            val url = tabManager.activeTab?.webView?.url ?: return@setOnClickListener
            val title = tabManager.activeTab?.title ?: url
            if (BookmarkManager.isBookmarked(this, url)) {
                BookmarkManager.remove(this, url)
            } else {
                BookmarkManager.add(this, Bookmark(url = url, title = title))
            }
            updateBookmarkIcon()
        }
        binding.btnMenu.setOnClickListener { anchor ->
            val popupView = LayoutInflater.from(this).inflate(R.layout.popup_menu, null)
            val popup = PopupWindow(
                popupView,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            )
            popup.elevation = 12f
            popup.isOutsideTouchable = true

            val desktopCheck = popupView.findViewById<ImageView>(R.id.desktop_mode_check)
            desktopCheck.alpha = if (isDesktopMode) 1f else 0f

            val openInAppItem = popupView.findViewById<android.view.View>(R.id.menu_open_in_app)
            val openInAppText = popupView.findViewById<android.widget.TextView>(R.id.menu_open_in_app_text)
            val currentUrl = tabManager.activeTab?.webView?.url
            val currentUri = currentUrl?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
            val webClient = tabManager.activeTab?.webView?.webViewClient as? com.jhaiian.clint.webview.ClintWebViewClient
            val appMatches = if (currentUri != null && webClient != null &&
                (currentUri.scheme == "http" || currentUri.scheme == "https")) {
                webClient.resolveAppMatches(currentUri, this)
            } else emptyList()

            if (appMatches.isEmpty()) {
                openInAppItem.isEnabled = false
                openInAppItem.alpha = 0.38f
                openInAppText.text = getString(R.string.menu_open_in_app)
            } else if (appMatches.size == 1) {
                val appName = appMatches[0].loadLabel(packageManager).toString()
                openInAppText.text = getString(R.string.menu_open_in_named_app, appName)
                openInAppItem.setOnClickListener {
                    popup.dismiss()
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, currentUri)
                        .setPackage(appMatches[0].activityInfo.packageName)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { startActivity(intent) }
                }
            } else {
                openInAppText.text = getString(R.string.menu_open_in_app)
                openInAppItem.setOnClickListener {
                    popup.dismiss()
                    val wv = tabManager.activeTab?.webView ?: return@setOnClickListener
                    webClient?.tryOpenInApp(wv, currentUri!!)
                }
            }

            popupView.findViewById<View>(R.id.menu_new_tab).setOnClickListener {
                popup.dismiss(); openNewTab(false)
            }
            popupView.findViewById<View>(R.id.menu_incognito).setOnClickListener {
                popup.dismiss(); openNewTab(true)
            }
            popupView.findViewById<View>(R.id.menu_share).setOnClickListener {
                popup.dismiss()
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, tabManager.activeTab?.webView?.url)
                }
                startActivity(Intent.createChooser(i, getString(R.string.share_url)))
            }
            popupView.findViewById<View>(R.id.menu_downloads).setOnClickListener {
                popup.dismiss()
                startActivity(Intent(this, DownloadsActivity::class.java))
            }
            popupView.findViewById<View>(R.id.menu_bookmarks).setOnClickListener {
                popup.dismiss()
                startActivity(Intent(this, BookmarksActivity::class.java))
            }
            popupView.findViewById<View>(R.id.menu_desktop_mode).setOnClickListener {
                isDesktopMode = !isDesktopMode
                desktopCheck.alpha = if (isDesktopMode) 1f else 0f
                tabManager.tabs.forEach { tab ->
                    tab.webView.settings.userAgentString = buildUserAgent()
                    if (isDesktopMode) addDesktopScript(tab) else removeDesktopScript(tab)
                }
                tabManager.activeTab?.webView?.reload()
                popup.dismiss()
            }
            popupView.findViewById<View>(R.id.menu_settings).setOnClickListener {
                popup.dismiss(); startActivity(Intent(this, SettingsActivity::class.java))
            }

            popupView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
            )
            val popupWidth = popupView.measuredWidth
            popup.showAsDropDown(anchor, -popupWidth + anchor.width, 0, Gravity.TOP or Gravity.END)
        }
    }

    private fun showTabSwitcher() {
        val existing = supportFragmentManager.findFragmentByTag("tab_switcher") as? TabSwitcherSheet
        if (existing != null && existing.isAdded) return
        val sheet = TabSwitcherSheet()
        sheet.tabs = tabManager.previews().toMutableList()
        sheet.activeIndex = tabManager.activeIndex
        sheet.show(supportFragmentManager, "tab_switcher")
    }

    override fun onTabSelected(index: Int) { tabManager.switchTo(index); attachActiveWebView() }
    override fun onTabClosed(index: Int) {
        val tab = tabManager.tabs.getOrNull(index)
        tab?.let { removeDesktopScript(it) }
        val wasActive = index == tabManager.activeIndex
        tabManager.closeTab(index)
        if (tabManager.count == 0) openNewTab(false)
        else if (wasActive) attachActiveWebView()
        else updateTabCount()
    }
    override fun onNewTab() { openNewTab(false) }
    override fun onNewIncognitoTab() { openNewTab(true) }

    fun loadUrl(input: String) {
        tabManager.activeTab?.webView?.loadUrl(formatUrl(input))
        tabManager.activeTab?.url = formatUrl(input)
        hideKeyboard()
    }

    private fun navigateToInput() {
        val input = binding.addressBar.text?.toString()?.trim() ?: ""
        if (input.isNotEmpty()) loadUrl(input)
        hideKeyboard()
    }

    private fun formatUrl(input: String): String {
        val t = input.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> getSearchQueryUrl(t)
        }
    }

    fun updateAddressBar(url: String) {
        val display = url
        if (!binding.addressBar.isFocused) binding.addressBar.setText(display)
        binding.lockIcon.setImageResource(if (url.startsWith("https://")) R.drawable.ic_lock_24 else R.drawable.ic_lock_open_24)
    }

    fun onTabUrlUpdated(webView: WebView, url: String) {
        tabManager.tabs.find { it.webView === webView }?.url = url
        if (tabManager.activeTab?.webView === webView && !binding.addressBar.isFocused) updateAddressBar(url)
    }

    fun onPageStarted(url: String) {
        binding.swipeRefresh.isRefreshing = false
        updateAddressBar(url)
        binding.btnRefresh.setImageResource(R.drawable.ic_close_24)
        binding.progressBar.visibility = View.VISIBLE
        updateNavigationState()
    }

    fun onPageFinished(url: String) {
        binding.swipeRefresh.isRefreshing = false
        updateAddressBar(url)
        binding.progressBar.visibility = View.INVISIBLE
        binding.btnRefresh.setImageResource(R.drawable.ic_refresh_24)
        updateNavigationState()
        tabManager.activeTab?.webView?.let { wv ->
            injectScrollTracker(wv)
            if (prefs.getBoolean("force_dark_web", false)
                && !WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)
                && !WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)
            ) {
                wv.evaluateJavascript(darkModeCss, null)
            }
        }
        nestedScrollActive = false
        updateBookmarkIcon()
    }

    fun onProgressChanged(progress: Int) {
        binding.progressBar.progress = progress
        binding.progressBar.visibility = if (progress < 100) View.VISIBLE else View.INVISIBLE
    }

    private fun updateBookmarkIcon() {
        val url = tabManager.activeTab?.webView?.url ?: ""
        val isBookmarked = url.isNotEmpty() && BookmarkManager.isBookmarked(this, url)
        binding.btnBookmark.setImageResource(
            if (isBookmarked) R.drawable.ic_bookmark_filled_24 else R.drawable.ic_bookmark_24
        )
        binding.btnBookmark.alpha = if (url.isEmpty()) 0.38f else 1.0f
    }

    private fun updateNavigationState() {
        val wv = tabManager.activeTab?.webView
        binding.btnBack.alpha = if (wv?.canGoBack() == true) 1.0f else 0.38f
        binding.btnForward.alpha = if (wv?.canGoForward() == true) 1.0f else 0.38f
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.addressBar.windowToken, 0)
        binding.addressBar.clearFocus()
    }

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenCallback = callback
        fullscreenView = view
        binding.fullscreenContainer.addView(view, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))
        binding.fullscreenContainer.visibility = View.VISIBLE
        binding.toolbarTop.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
        val ctrl = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        ctrl.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        ctrl.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        tabManager.activeTab?.webView?.evaluateJavascript(
            "(function(){ var v = document.querySelector('video'); return v ? v.videoWidth + ',' + v.videoHeight : '0,0'; })()"
        ) { result ->
            val parts = result?.trim('"')?.split(",")
            val vw = parts?.getOrNull(0)?.toIntOrNull() ?: 0
            val vh = parts?.getOrNull(1)?.toIntOrNull() ?: 0
            requestedOrientation = when {
                vw > 0 && vh > 0 && vw >= vh ->
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                vw > 0 && vh > 0 && vh > vw ->
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else ->
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
    }

    fun exitFullscreen() {
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        fullscreenView?.let { binding.fullscreenContainer.removeView(it) }
        fullscreenView = null
        binding.fullscreenContainer.visibility = View.GONE
        barAnimator?.cancel()
        barsHidden = false
        nestedScrollActive = false
        if (topBarFullHeight > 0) binding.toolbarTop.updateLayoutParams { height = topBarFullHeight }
        if (bottomBarFullHeight > 0) binding.bottomBar.updateLayoutParams { height = bottomBarFullHeight }
        binding.toolbarTop.visibility = View.VISIBLE
        binding.bottomBar.visibility = View.VISIBLE
        binding.swipeRefresh.isEnabled = true
        val ctrl = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        ctrl.show(WindowInsetsCompat.Type.navigationBars())
        applyStatusBarVisibility()
        binding.root.post {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            topBarFullHeight = 0
            bottomBarFullHeight = 0
            ViewCompat.requestApplyInsets(binding.toolbarTop)
            ViewCompat.requestApplyInsets(binding.bottomBar)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (fullscreenView != null) {
                exitFullscreen()
                return true
            }
            val wv = tabManager.activeTab?.webView
            if (wv?.canGoBack() == true) { wv.goBack(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun onShowFileChooser(
        callback: android.webkit.ValueCallback<Array<android.net.Uri>>,
        params: android.webkit.WebChromeClient.FileChooserParams
    ): Boolean {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null

        val hasCameraPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            pendingFileChooserCallback = callback
            pendingFileChooserParams = params
            if (shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA)) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_ClintBrowser_Dialog)
                    .setTitle(getString(R.string.camera_permission_title))
                    .setMessage(getString(R.string.camera_permission_message))
                    .setCancelable(false)
                    .setNegativeButton(getString(R.string.action_not_now)) { _, _ ->
                        val cb = pendingFileChooserCallback
                        pendingFileChooserCallback = null
                        pendingFileChooserParams = null
                        cb?.onReceiveValue(null)
                    }
                    .setPositiveButton(getString(R.string.action_allow)) { _, _ ->
                        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                    }
                    .show()
            } else {
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
            return true
        }

        return launchFileChooser(callback, params)
    }

    private fun launchFileChooser(
        callback: android.webkit.ValueCallback<Array<android.net.Uri>>,
        params: android.webkit.WebChromeClient.FileChooserParams
    ): Boolean {
        filePathCallback = callback

        val accept = params.acceptTypes?.joinToString(",") ?: "*/*"
        val isImageOnly = accept.contains("image") && !accept.contains("video") && !accept.contains("audio") && !accept.contains("*/*")
        val isVideoOnly = accept.contains("video") && !accept.contains("image") && !accept.contains("*/*")
        val allowMultiple = params.mode == android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

        val extraIntents = mutableListOf<android.content.Intent>()

        if (!isVideoOnly) {
            try {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.TITLE, "clint_capture_${System.currentTimeMillis()}")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                }
                val uri = contentResolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    cameraImageUri = uri
                    extraIntents.add(
                        android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                        }
                    )
                }
            } catch (_: Exception) {}
        }

        if (!isImageOnly) {
            try {
                extraIntents.add(
                    android.content.Intent(android.provider.MediaStore.Audio.Media.RECORD_SOUND_ACTION)
                )
            } catch (_: Exception) {}
            try {
                extraIntents.add(
                    android.content.Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE)
                )
            } catch (_: Exception) {}
        }

        val mimeType = when {
            isImageOnly -> "image/*"
            isVideoOnly -> "video/*"
            accept.contains("audio") && !accept.contains("*/*") -> "audio/*"
            else -> "*/*"
        }

        val contentIntent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            type = mimeType
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            if (allowMultiple) putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
        }

        val chooser = android.content.Intent.createChooser(contentIntent, null).apply {
            if (extraIntents.isNotEmpty()) {
                putExtra(android.content.Intent.EXTRA_INITIAL_INTENTS, extraIntents.toTypedArray())
            }
        }

        try {
            fileChooserLauncher.launch(chooser)
        } catch (_: android.content.ActivityNotFoundException) {
            filePathCallback = null
            cameraImageUri = null
            cameraVideoUri = null
            return false
        }
        return true
    }

    private fun applyStatusBarVisibility() {
        val hide = prefs.getBoolean("hide_status_bar", false)
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (hide) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
            statusBarInsetPx = 0
            binding.toolbarTop.setPadding(0, 0, 0, 0)
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
            if (cachedStatusBarInsetPx > 0) {
                statusBarInsetPx = cachedStatusBarInsetPx
                binding.toolbarTop.setPadding(0, cachedStatusBarInsetPx, 0, 0)
            }
        }
    }

    private fun isNetworkMetered(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return cm.isActiveNetworkMetered
    }
}
