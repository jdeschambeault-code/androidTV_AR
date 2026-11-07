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
                if (event.repeatCount == 1) { longFired = true; open("quick") }
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
