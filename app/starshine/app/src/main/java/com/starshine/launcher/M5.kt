package com.starshine.launcher

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/**
 * AOW M5 air-mouse control layer (it shows up as "M5 Mouse" + "M5 Keyboard" + "M5 Consumer Control").
 *  - hover selects only after the pointer rests [dwellMs] and has moved more than [deadzone] px since the last hover-select
 *  - a mouse click acts as OK on the *selected* item (immune to hand drift while clicking); holding the button = hold-OK
 *  - setting rows lock while editing: ← → change only that row until Back / OK
 */
object M5 {
    var hoverSelect by mutableStateOf(false)   // off by default: 1 press = 1 move
    var dwellMs by mutableIntStateOf(280)
    var deadzone by mutableFloatStateOf(14f)
    var clickAsOk by mutableStateOf(true)
    var editLock by mutableStateOf(true)
    var rightClick by mutableStateOf("Back")        // Back · Quick panel · Voice · Open apps · Nothing
    var menuKey by mutableStateOf("Quick panel")    // Quick panel · Voice · Open apps
    val ACTIONS_RIGHT = listOf("Back", "Quick panel", "Voice", "Open apps", "Nothing")
    val ACTIONS_MENU = listOf("Quick panel", "Voice", "Open apps")

    /** Row currently being edited (locked), or null. */
    var editing by mutableStateOf<String?>(null)
    /** ↑ ↓ pressed while editing: the row that receives focus next starts in edit mode. */
    var carryEdit = false
    /** Item under the cursor (violet outline) and when the cursor entered it. A click uses it unless an arrow was pressed later. */
    var hoverTarget: androidx.compose.ui.focus.FocusRequester? = null
    var hoverAt = 0L
    var lastArrowAt = 0L

    // live monitor (for review / calibration)
    var pointer by mutableStateOf(Offset.Zero)
    var speed by mutableFloatStateOf(0f)           // px/s
    val keyLog = mutableStateListOf<String>()
    var lastHoverPos = Offset(-9999f, -9999f)

    fun logKey(s: String) { keyLog.add(0, s); while (keyLog.size > 8) keyLog.removeAt(keyLog.size - 1) }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = sp(ctx)
        hoverSelect = p.getBoolean("m5hover2", false); dwellMs = p.getInt("m5dwell", 280); deadzone = p.getFloat("m5dead", 14f)
        clickAsOk = p.getBoolean("m5click", true); editLock = p.getBoolean("m5lock", true)
        rightClick = p.getString("m5right", "Back")!!; menuKey = p.getString("m5menu", "Quick panel")!!
    }

    fun save(ctx: Context) {
        sp(ctx).edit().putBoolean("m5hover2", hoverSelect).putInt("m5dwell", dwellMs).putFloat("m5dead", deadzone)
            .putBoolean("m5click", clickAsOk).putBoolean("m5lock", editLock).putString("m5right", rightClick).putString("m5menu", menuKey).apply()
    }

    /** System cursor speed (-7..7). Reading/writing a private system setting may be refused on Android 12; then use the PC toolkit. */
    fun pointerSpeed(ctx: Context): Int? = runCatching { Settings.System.getInt(ctx.contentResolver, "pointer_speed") }.getOrNull()
    fun setPointerSpeed(ctx: Context, v: Int): Boolean = runCatching { Settings.System.putInt(ctx.contentResolver, "pointer_speed", v.coerceIn(-7, 7)) }.getOrDefault(false)
}
