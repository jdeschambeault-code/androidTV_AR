package com.starshine.launcher

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private class SavedPage(val url: String, val title: String)
private class BrowserRow(val text: String, val hold: (() -> Unit)? = null, val click: () -> Unit)

/** Bookmarks and history stay on this device: this folder is left out of Android's backups. */
private class PageList(ctx: Context, name: String, private val limit: Int) {
    private val file = File(ctx.noBackupFilesDir, "browser_$name.json")
    var pages: List<SavedPage> = runCatching {
        val a = JSONArray(file.readText())
        List(a.length()) { a.getJSONObject(it).let { o -> SavedPage(o.getString("u"), o.optString("t")) } }
    }.getOrDefault(emptyList())
        private set

    private fun save() {
        val a = JSONArray()
        pages.forEach { a.put(JSONObject().put("u", it.url).put("t", it.title)) }
        runCatching { file.writeText(a.toString()) }
    }
    fun has(url: String) = pages.any { it.url == url }
    fun add(url: String, title: String) { pages = (listOf(SavedPage(url, title)) + pages.filter { it.url != url }).take(limit); save() }
    fun remove(url: String) { pages = pages.filter { it.url != url }; save() }
    fun clear() { pages = emptyList(); runCatching { file.delete() } }
}

/**
 * Starshine's browser, for web tiles and any web address. Works with the M5 air mouse and with the remote.
 * Top bar: back, forward, reload, address, bookmark, menu. The menu has bookmarks, history, downloads and page settings.
 * When a real video starts on a page and its address can be played directly, VLC takes over (it can be turned off in Settings).
 * Other browsers can pass a page here with Share or "open with". Android doesn't let an app see the video inside another app.
 * Protected streams, and players that build the video in the page (YouTube, Netflix), have no address to pass, and stay here.
 * LAN sites on your own private domain (starshine.privateDomain in local.properties, none by default) may use a private certificate that Android doesn't trust;
 * ONLY for hosts on that domain the certificate warning is accepted. Every other site keeps normal TLS checks.
 * Back = close the menu, leave full screen, leave the top bar, previous page, or close. Menu = play the found video in VLC, or open the menu. Hold Menu = type an address.
 */
class WebActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var banner: TextView
    private lateinit var root: FrameLayout
    private lateinit var page: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var address: TextView
    private lateinit var backBtn: TextView
    private lateinit var forwardBtn: TextView
    private lateinit var reloadBtn: TextView
    private lateinit var starBtn: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var bookmarks: PageList
    private lateinit var history: PageList
    private var panel: View? = null
    private var backdrop: View? = null
    private var fullView: View? = null
    private var fullDone: WebChromeClient.CustomViewCallback? = null
    /** The best video address seen on this page. Protected streams never appear here. */
    @Volatile private var found: String? = null
    /** Addresses already handed over: pressing play again after coming back from VLC stays in the page. */
    private val sent = HashSet<String>()
    private val protectedOk = HashSet<String>()
    private var menuHeld = false
    private var loading = false
    private var failed = false
    private var uploadDone: ValueCallback<Array<Uri>>? = null
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        uploadDone?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        uploadDone = null
    }

    private val sp by lazy { getSharedPreferences("starshine", MODE_PRIVATE) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun manifest(u: String) = MANIFEST.containsMatchIn(u)
    private fun offer(url: String?) {
        if (url == null || !url.startsWith("http") || url == found) return
        // a stream's master list is what a player needs; a clip found later on the page doesn't replace it
        if (found?.let { manifest(it) } == true && !manifest(url)) return
        found = url
        runOnUiThread { showBanner("▶ Video found. Press Menu to play it in VLC") }
    }
    private fun showBanner(s: String) { banner.text = s; banner.visibility = View.VISIBLE }
    private fun toVlc(url: String) {
        sent.add(url)
        pageMedia()
        PlayerActivity.play(this, Uri.parse(url), web.title)
    }
    private fun pageMedia() { runCatching { web.evaluateJavascript(PAUSE_JS, null) } }
    private val scan = object : Runnable {
        override fun run() {
            // a video that's really being watched: playing, with sound, of a real size, and not a short clip
            runCatching { web.evaluateJavascript(SCAN_JS) { r ->
                val s = r?.trim('"').orEmpty()
                val src = s.removePrefix("playing:")
                if (src.startsWith("http")) offer(src)
                if (s.startsWith("playing:") && autoVlc()) {
                    val target = if (src.startsWith("http")) src else found
                    if (target != null && target !in sent) toVlc(target)
                }
            } }
            web.postDelayed(this, 1500)
        }
    }
    private fun autoVlc() = sp.getBoolean(AUTO_VLC, true)

    /** A remote or air-mouse target: lights up when focused or under the pointer. */
    private fun pill(label: String, click: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
        isFocusable = true; isClickable = true
        setPadding(dp(12), dp(5), dp(12), dp(5))
        val on = (Theme.accent.toArgb() and 0xFFFFFF) or 0x66000000
        fun round(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(8).toFloat() }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), round(on))
            addState(intArrayOf(android.R.attr.state_hovered), round(on))
            addState(intArrayOf(), round(0x14FFFFFF))
        }
        setOnClickListener { click() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookmarks = PageList(this, "bookmarks", 500)
        history = PageList(this, "history", 300)
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            // this activity accepts addresses from other apps, so pages get no way into local files
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setGeolocationEnabled(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.textZoom = sp.getInt(TEXT_SIZE, 100)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    fullView?.let { root.removeView(it) }
                    fullView = view; fullDone = callback
                    root.addView(view, FrameLayout.LayoutParams(-1, -1))
                    page.visibility = View.GONE; banner.visibility = View.GONE
                }
                override fun onHideCustomView() { leaveFull(false) }
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
                }
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    uploadDone?.onReceiveValue(null)
                    uploadDone = callback
                    runCatching { picker.launch(params.createIntent()) }.onFailure {
                        callback.onReceiveValue(null); uploadDone = null
                        toast("This device has no file picker for uploads")
                    }
                    return true
                }
                // only protected video is ever offered: camera, microphone and the rest stay refused
                override fun onPermissionRequest(request: PermissionRequest) {
                    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID !in request.resources) { request.deny(); return }
                    val grant = arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
                    val origin = request.origin.toString()
                    if (origin in protectedOk) { request.grant(grant); return }
                    android.app.AlertDialog.Builder(this@WebActivity).setTitle("Play protected video?")
                        .setMessage("${request.origin.host} wants to use this device's protected video support.")
                        .setPositiveButton("Allow") { _, _ -> protectedOk.add(origin); request.grant(grant) }
                        .setNegativeButton("Block") { _, _ -> request.deny() }
                        .setOnCancelListener { request.deny() }.show()
                }
            }
            webViewClient = object : WebViewClient() {
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    val host = Uri.parse(error.url).host ?: ""
                    // your own servers: the private domain, and the hosts of your web tiles (e.g. Proxmox on its LAN address,
                    // which only has a self-signed certificate). Every other site with a bad certificate stays blocked.
                    val ownTile = BuildConfig.WEB_TILES.split(";").mapNotNull { t -> t.substringAfter('|', "").takeIf { it.isNotEmpty() }?.let { Uri.parse(it).host } }.contains(host)
                    if ((BuildConfig.PRIVATE_DOMAIN.isNotEmpty() && host.endsWith(BuildConfig.PRIVATE_DOMAIN)) || ownTile) handler.proceed()
                    else {
                        handler.cancel()
                        if (error.url == view.url) { failed = true; showBanner("Blocked: this site's security certificate can't be trusted") }
                    }
                }
                override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                    val u = request.url.toString()
                    if (VIDEO_URL.containsMatchIn(u)) offer(u)
                    return null
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) =
                    leavesBrowser(request.url.toString(), request.hasGesture())
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    found = null; failed = false; loading = true; banner.visibility = View.GONE; refreshBar()
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    loading = false
                    if (!failed && url != null && url.startsWith("http")) history.add(url, view.title.orEmpty())
                    refreshBar()
                }
                override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) failed = true
                }
                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { refreshBar() }
                // the page's renderer crashing must not take the launcher down with it
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    view.removeCallbacks(scan)
                    toast("The page crashed, so the browser closed")
                    finish()
                    return true
                }
            }
            setDownloadListener { url, agent, disposition, mime, _ ->
                // the page stays as it was, and no finished event comes for a link that turned out to be a file
                loading = false; progressBar.visibility = View.INVISIBLE; refreshBar()
                offerDownload(url, agent, disposition, mime)
            }
        }
        applyDesktop()
        backBtn = pill("◀") { if (web.canGoBack()) web.goBack() }
        forwardBtn = pill("▶") { if (web.canGoForward()) web.goForward() }
        reloadBtn = pill("↻") { if (loading) web.stopLoading() else web.reload() }
        address = pill("") { askAddress(this, web.url) }.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL; textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        starBtn = pill("☆") { toggleBookmark() }
        val menuBtn = pill("≡") { showMenu() }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF0A121C.toInt()); setPadding(dp(6), dp(4), dp(6), dp(4))
            fun lp(w: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, -2, weight).apply { marginStart = dp(2); marginEnd = dp(2) }
            addView(backBtn, lp(-2)); addView(forwardBtn, lp(-2)); addView(reloadBtn, lp(-2))
            addView(address, lp(0, 1f))
            addView(starBtn, lp(-2)); addView(menuBtn, lp(-2))
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.INVISIBLE
            progressTintList = ColorStateList.valueOf(Theme.accent.toArgb())
        }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(-1, -2))
            addView(progressBar, LinearLayout.LayoutParams(-1, dp(3)))
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        banner = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; setBackgroundColor(0xCC060C14.toInt()); setPadding(28, 14, 28, 14); visibility = View.GONE
        }
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(page)
            addView(banner, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 40 })
        }
        setContentView(root)
        // follow Starshine's screen position / scale (glasses alignment), full screen video included
        val p = Prefs(this)
        root.post {
            root.translationX = p.offX * root.width; root.translationY = p.offY * root.height
            val sc = if (p.glasses) p.uiScale * 0.85f else p.uiScale
            root.scaleX = sc; root.scaleY = sc
        }
        refreshBar()
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else addressFrom(intent)?.let { open(it) } ?: run { showBackdrop(p); root.post { start() } }
        onBackPressedDispatcher.addCallback(this) {
            when {
                panel != null -> closePanel()
                fullView != null -> leaveFull(true)
                bar.hasFocus() && web.url != null -> web.requestFocus()
                web.canGoBack() -> web.goBack()
                else -> finish()
            }
        }
    }

    /** With no page open, Starshine's own background shows behind the bar. It goes away with the first page, so it costs nothing while browsing. */
    private fun showBackdrop(p: Prefs) {
        if (!p.bgOn) return
        val view = ComposeView(this).apply {
            isFocusable = false
            setContent {
                Background(p.bg, p.style, p.fps30)
                if (p.dimOn) Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = p.dim)))
            }
        }
        root.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
        web.visibility = View.INVISIBLE
        backdrop = view
    }

    private fun open(url: String) {
        backdrop?.let { root.removeView(it) }
        backdrop = null
        web.visibility = View.VISIBLE
        web.loadUrl(url); web.requestFocus()
    }

    /** Opened with no page: saved pages when there are some, otherwise straight to typing an address. */
    private fun start() {
        if (bookmarks.pages.isNotEmpty()) showSaved("Bookmarks", bookmarks)
        else { address.requestFocus(); askAddress(this) }
    }

    private fun refreshBar() {
        val u = web.url
        address.text = when {
            u == null || !u.startsWith("http") -> "Type an address or search"
            u.startsWith("https://") -> u.removePrefix("https://")
            else -> "Not secure · " + u.removePrefix("http://")
        }
        backBtn.alpha = if (web.canGoBack()) 1f else 0.35f
        forwardBtn.alpha = if (web.canGoForward()) 1f else 0.35f
        reloadBtn.text = if (loading) "✕" else "↻"
        starBtn.text = if (u != null && bookmarks.has(u)) "★" else "☆"
    }

    private fun toggleBookmark() {
        val u = web.url?.takeIf { it.startsWith("http") } ?: return
        if (bookmarks.has(u)) bookmarks.remove(u) else bookmarks.add(u, web.title.orEmpty())
        refreshBar()
    }

    /** True when the link isn't a web page. Only a link you pressed may open another app, never one the page follows by itself. */
    private fun leavesBrowser(url: String, pressed: Boolean): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme in setOf("http", "https", "about", "data", "blob")) return false
        if (!pressed || scheme in setOf("file", "content", "javascript")) return true
        val outcome = runCatching {
            val i = if (scheme == "intent") Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            val fallback = i.getStringExtra("browser_fallback_url")
            i.component = null; i.selector = null
            i.addCategory(Intent.CATEGORY_BROWSABLE)
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            fallback to runCatching { startActivity(i) }.isSuccess
        }.getOrNull()
        if (outcome?.second != true) {
            val f = outcome?.first
            if (f != null && f.startsWith("http")) web.loadUrl(f) else toast("No app on this device can open that link")
        }
        return true
    }

    private fun offerDownload(url: String, agent: String?, disposition: String?, mime: String?) {
        val name = URLUtil.guessFileName(url, disposition, mime)
        val b = android.app.AlertDialog.Builder(this).setTitle("Save this file?").setMessage(name)
            .setPositiveButton("Download") { _, _ -> download(url, agent, mime, name) }
            .setNegativeButton("Cancel", null)
        if (mime?.startsWith("video/") == true || VIDEO_URL.containsMatchIn(url)) b.setNeutralButton("Play in VLC") { _, _ -> toVlc(url) }
        b.show()
    }

    private fun download(url: String, agent: String?, mime: String?, name: String) {
        runCatching {
            val r = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            if (!mime.isNullOrEmpty()) r.setMimeType(mime)
            if (!agent.isNullOrEmpty()) r.addRequestHeader("User-Agent", agent)
            CookieManager.getInstance().getCookie(url)?.let { r.addRequestHeader("Cookie", it) }
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(r)
        }.onSuccess { toast("Downloading to the Downloads folder. Follow it in ≡ → Downloads") }
            .onFailure { toast("This file can't be saved: it has no address of its own") }
    }

    private fun applyDesktop() {
        val mobile = WebSettings.getDefaultUserAgent(this)
        val chrome = Regex("""Chrome/[\d.]+""").find(mobile)?.value ?: "Chrome/120.0.0.0"
        web.settings.userAgentString = if (sp.getBoolean(DESKTOP, false)) "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36" else mobile
    }

    private fun showPanel(title: String, rows: List<BrowserRow>, focus: Int = 0) {
        panel?.let { root.removeView(it) }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)) }
        list.addView(TextView(this).apply { text = title; setTextColor(Color.WHITE); textSize = 16f; setPadding(dp(6), dp(2), dp(6), dp(8)) })
        var first: View? = null
        rows.forEachIndexed { i, r ->
            val v = pill(r.text) { r.click() }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL; textSize = 13f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10), dp(7), dp(10), dp(7))
            }
            r.hold?.let { h -> v.setOnLongClickListener { h(); true } }
            list.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            if (i == focus.coerceAtMost(rows.lastIndex)) first = v
        }
        val box = ScrollView(this).apply { setBackgroundColor(0xF2060C14.toInt()); addView(list) }
        // while the menu is open the remote stays inside it
        page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        panel = box
        val width = if (root.width > 0) minOf(dp(380), (root.width * 0.6f).toInt()) else dp(380)
        root.addView(box, FrameLayout.LayoutParams(width, -1, Gravity.END))
        first?.requestFocus()
    }

    private fun closePanel() {
        panel?.let { root.removeView(it) }
        panel = null
        page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        (fullView ?: if (web.url != null) web else address).requestFocus()
    }

    private fun showMenu(focus: Int = 0) {
        val rows = mutableListOf<BrowserRow>()
        found?.let { v -> rows += BrowserRow("▶ Play the video on this page in VLC") { closePanel(); toVlc(v) } }
        rows += BrowserRow("★ Bookmarks") { showSaved("Bookmarks", bookmarks) }
        rows += BrowserRow("🕘 History") { showSaved("History", history) }
        rows += BrowserRow("⬇ Downloads") { showDownloads() }
        run {
            val i = rows.size
            rows += BrowserRow("Desktop site: " + if (sp.getBoolean(DESKTOP, false)) "on" else "off") {
                sp.edit().putBoolean(DESKTOP, !sp.getBoolean(DESKTOP, false)).apply()
                applyDesktop(); web.reload(); showMenu(i)
            }
        }
        run {
            val i = rows.size
            val now = sp.getInt(TEXT_SIZE, 100)
            rows += BrowserRow("Text size: $now%") {
                val next = TEXT_SIZES[(TEXT_SIZES.indexOf(now) + 1) % TEXT_SIZES.size]
                sp.edit().putInt(TEXT_SIZE, next).apply()
                web.settings.textZoom = next; showMenu(i)
            }
        }
        run {
            val i = rows.size
            rows += BrowserRow("Video opens in VLC by itself: " + if (autoVlc()) "on" else "off") {
                sp.edit().putBoolean(AUTO_VLC, !autoVlc()).apply(); showMenu(i)
            }
        }
        rows += BrowserRow("Clear history, cookies and site data") { confirmClear() }
        rows += BrowserRow("Close the browser") { finish() }
        showPanel("Browser", rows, focus)
    }

    private fun showSaved(title: String, list: PageList, focus: Int = 0) {
        val rows = list.pages.mapIndexed { i, s ->
            val name = s.title.ifEmpty { Uri.parse(s.url).host ?: s.url }
            BrowserRow(name + "\n" + s.url, hold = { list.remove(s.url); refreshBar(); showSaved(title, list, i) }) {
                closePanel(); open(s.url)
            }
        }
        if (rows.isEmpty()) showPanel(title, listOf(BrowserRow(if (list === bookmarks) "Nothing saved yet. Press ☆ on a page to save it." else "Nothing here yet.") { closePanel() }))
        else showPanel("$title · hold OK to remove", rows, focus)
    }

    /** Downloads this browser started. The list shows only this app's own, and opening a finished one is Android's choice of app. */
    private fun showDownloads() {
        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val rows = mutableListOf<BrowserRow>()
        runCatching {
            dm.query(DownloadManager.Query()).use { c ->
                while (c.moveToNext() && rows.size < 30) {
                    val id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                    val name = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)).orEmpty()
                    val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val state = when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> "✓ "
                        DownloadManager.STATUS_FAILED -> "Failed: "
                        else -> if (total > 0) "${done * 100 / total}% " else "… "
                    }
                    rows += BrowserRow(state + name) {
                        if (status == DownloadManager.STATUS_SUCCESSFUL) runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(dm.getUriForDownloadedFile(id), dm.getMimeTypeForDownloadedFile(id))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure { toast("No app on this device can open this file. It's in the Downloads folder.") }
                        else showDownloads()
                    }
                }
            }
        }
        showPanel("Downloads", rows.ifEmpty { listOf(BrowserRow("Nothing downloaded yet.") { closePanel() }) })
    }

    private fun confirmClear() {
        android.app.AlertDialog.Builder(this).setTitle("Clear browsing data?")
            .setMessage("This removes history, cookies and site logins. Bookmarks and downloaded files stay.")
            .setPositiveButton("Clear") { _, _ ->
                history.clear(); web.clearHistory(); web.clearCache(true)
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                protectedOk.clear(); refreshBar(); toast("Browsing data cleared")
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun leaveFull(tellPage: Boolean) {
        fullView?.let { root.removeView(it) }
        fullView = null
        if (tellPage) fullDone?.onCustomViewHidden()
        fullDone = null
        page.visibility = View.VISIBLE
        web.requestFocus()
        if (found != null) banner.visibility = View.VISIBLE
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        addressFrom(intent)?.let {
            if (panel != null) closePanel()
            sent.clear(); open(it)
        }
    }

    // handled before the page sees it, so a site can't swallow the key
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_MENU) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) menuHeld = false
            if (event.repeatCount == 1) { menuHeld = true; askAddress(this, web.url) }
        } else if (event.action == KeyEvent.ACTION_UP && !menuHeld) {
            val v = found
            when {
                panel != null -> closePanel()
                v != null -> toVlc(v)
                else -> showMenu()
            }
        }
        return true
    }

    // leaving the browser stops its sound and its video checks, so nothing plays or opens from the background
    override fun onPause() {
        pageMedia()
        web.onPause()
        web.removeCallbacks(scan)
        CookieManager.getInstance().flush()
        super.onPause()
    }
    override fun onResume() {
        super.onResume()
        web.onResume()
        web.removeCallbacks(scan)
        web.postDelayed(scan, 1500)
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }
    override fun onDestroy() {
        uploadDone?.onReceiveValue(null); uploadDone = null
        web.removeCallbacks(scan); web.destroy(); super.onDestroy()
    }

    companion object {
        const val AUTO_VLC = "webToVlc"
        private const val DESKTOP = "webDesktop"
        private const val TEXT_SIZE = "webTextSize"
        private val TEXT_SIZES = listOf(100, 125, 150, 175, 200, 75)
        private val VIDEO_URL = Regex("""\.(mp4|m4v|webm|mkv|mov|ogv|avi|m3u8|mpd)(\?|$)""", RegexOption.IGNORE_CASE)
        private val MANIFEST = Regex("""\.(m3u8|mpd)(\?|$)""", RegexOption.IGNORE_CASE)
        private val LAN = Regex("""^(localhost|\d{1,3}(\.\d{1,3}){3}|[\w.-]+:\d+)(:\d+)?(/.*)?$""", RegexOption.IGNORE_CASE)
        private const val SCAN_JS = "(function(){var a=document.querySelectorAll('video'),first='';for(var i=0;i<a.length;i++){var v=a[i],s=v.currentSrc||v.src||'';" +
            "if(!first)first=s;var d=v.duration;if(!v.paused&&!v.muted&&v.volume>0&&v.clientWidth>300&&(isNaN(d)||d>=30))return 'playing:'+s;}return first;})()"
        private const val PAUSE_JS = "document.querySelectorAll('video,audio').forEach(function(v){v.pause()})"

        /** Only web pages can be opened here, whatever another app sends. */
        private fun web(u: String?) = u?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }

        private fun addressFrom(i: Intent?): String? {
            if (i == null) return null
            if (i.action == Intent.ACTION_SEND) return i.getStringExtra(Intent.EXTRA_TEXT)?.let { Regex("""https?://\S+""", RegexOption.IGNORE_CASE).find(it)?.value }
            return web(i.dataString)
        }

        /** What was typed as a web address: a full address, a device on the network, a site name, or else a search. */
        fun addressOf(typed: String): String? {
            val t = typed.trim()
            return when {
                t.isEmpty() -> null
                web(t) != null -> t
                t.contains(' ') -> "https://duckduckgo.com/?q=" + Uri.encode(t)
                LAN.matches(t) -> "http://$t"
                t.contains('.') -> "https://$t"
                else -> "https://duckduckgo.com/?q=" + Uri.encode(t)
            }
        }

        fun askAddress(ctx: Context, current: String? = null) {
            val box = android.widget.EditText(ctx).apply {
                hint = "Web address or search"; setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                imeOptions = EditorInfo.IME_ACTION_GO
                if (current != null && current.startsWith("http")) { setText(current); selectAll() }
            }
            fun go() {
                addressOf(box.text.toString())?.let { a -> ctx.startActivity(Intent(ctx, WebActivity::class.java).setData(Uri.parse(a)).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or if (ctx is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            val dialog = android.app.AlertDialog.Builder(ctx).setTitle("Open in Starshine browser").setView(box)
                .setPositiveButton("Open") { _, _ -> go() }
                .setNegativeButton("Cancel", null).create()
            box.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO) { go(); dialog.dismiss(); true } else false
            }
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            dialog.show()
        }
    }
}
