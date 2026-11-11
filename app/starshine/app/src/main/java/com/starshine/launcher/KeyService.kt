package com.starshine.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Home button handling (works in every app, nothing is disabled; same approach as other TV launchers):
 *  - Home press      -> Starshine Home screen (when "Home button opens Starshine" is on; Google TV's launcher ignores the default-launcher setting)
 *  - Home long-press -> Starshine quick panel
 * Turn it off in Starshine Settings → System, or Exit Starshine (pauses it until Starshine is opened again).
 */
class KeyService : AccessibilityService() {
    private var longFired = false

    companion object {
        /** The web address read at the last long-press of Home. Kept in memory only, until the quick panel closes. */
        @Volatile var page: String? = null
        /** The app that long-press came from, when it wasn't Starshine. */
        @Volatile var readFrom: String? = null
        fun forget() { page = null; readFrom = null }
        private val FULL = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
        private val SITE = Regex("""^[\w-]+(\.[\w-]+)*\.[a-z]{2,}(:\d+)?(/\S*)?$""", RegexOption.IGNORE_CASE)
        private val URL_ID = Regex("url|address|location|omnibox", RegexOption.IGNORE_CASE)
    }

    /**
     * Looks for the address of the page in the app on screen, only when Home is long-pressed. It accepts an address field or
     * a browser's own page address, never links shown in the page, and skips password fields. The address itself isn't logged.
     */
    private fun readPage() {
        forget()
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg == packageName) return
        readFrom = pkg
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        queue.add(root)
        var nodes = 0; var edits = 0; var webs = 0; var found: String? = null
        while (queue.isNotEmpty() && nodes < 800 && found == null) {
            val n = queue.removeFirst(); nodes++
            val cls = n.className?.toString().orEmpty()
            val isEdit = cls.contains("EditText"); val isWeb = cls.contains("WebView")
            if (isEdit) edits++
            if (isWeb) webs++
            if (!n.isPassword) {
                val text = n.text?.toString().orEmpty().trim()
                val named = n.viewIdResourceName?.let { URL_ID.containsMatchIn(it) } == true
                if (isEdit || named) found = FULL.find(text)?.value ?: if (SITE.matches(text)) "https://$text" else null
                if (found == null && isWeb) n.extras?.let { x ->
                    for (k in x.keySet()) { if (found != null) break; if (k.contains("url", true)) found = runCatching { x.getCharSequence(k)?.toString() }.getOrNull()?.let { FULL.find(it)?.value } }
                }
            }
            for (i in 0 until n.childCount) runCatching { n.getChild(i) }.getOrNull()?.let { queue.add(it) }
        }
        page = found
        android.util.Log.i("StarshineInput", "page read from $pkg: nodes=$nodes fields=$edits webviews=$webs found=${found != null}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun override() = getSharedPreferences("starshine", Context.MODE_PRIVATE).getBoolean("homeOverride", true)

    private fun open(extra: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(extra, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        val takeHome = override()
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 1) {
                    longFired = true
                    // one call per node on screen, so not on this thread; the panel opens after, or it would be what gets read
                    Thread { runCatching { readPage() }; open("quick") }.start()
                }
                takeHome || event.repeatCount >= 1
            }
            KeyEvent.ACTION_UP -> when {
                longFired -> { longFired = false; true }
                takeHome -> { open("home"); true }
                else -> false
            }
            else -> takeHome
        }
    }
}
