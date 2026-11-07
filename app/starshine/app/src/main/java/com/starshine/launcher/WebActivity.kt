package com.starshine.launcher

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback

/**
 * Built-in web viewer for web tiles (ZimaOS, Pinokio, Songsterr…). Works with the M5 air mouse.
 * LAN sites on your own private domain (starshine.privateDomain in local.properties, none by default) may use a private certificate that Android doesn't trust;
 * ONLY for hosts on that domain the certificate warning is accepted. Every other site keeps normal TLS checks.
 * Back = previous page (or close), Menu = reload.
 */
class WebActivity : ComponentActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    val host = Uri.parse(error.url).host ?: ""
                    if (BuildConfig.PRIVATE_DOMAIN.isNotEmpty() && host.endsWith(BuildConfig.PRIVATE_DOMAIN)) handler.proceed() else handler.cancel()
                }
            }
        }
        setContentView(web)
        // follow Starshine's screen position / scale (glasses alignment)
        val p = Prefs(this)
        web.post {
            web.translationX = p.offX * web.width; web.translationY = p.offY * web.height
            val sc = if (p.glasses) p.uiScale * 0.85f else p.uiScale
            web.scaleX = sc; web.scaleY = sc
        }
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(intent.dataString ?: "about:blank")
        onBackPressedDispatcher.addCallback(this) { if (web.canGoBack()) web.goBack() else finish() }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { web.reload(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }
    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
