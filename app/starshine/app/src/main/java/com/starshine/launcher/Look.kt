package com.starshine.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Appearance settings that the dock and top bar read directly, so changing one in Settings redraws at once. */
object Look {
    /** Dock tile styles, in the order ← → cycles through them. Round is the default, like Android TV's app row. */
    val STYLES = listOf("apple", "round", "roundnames", "icons", "banners")
    private val NAMES = mapOf("apple" to "glass shelf, round icons (Apple style)", "round" to "round icons", "roundnames" to "round icons + names", "icons" to "square icons", "banners" to "icons and names")

    var dockStyle by mutableStateOf("round")
    /** Space in dp between the top menu and the screen's top, left and right edges (full screen only; glasses mode has its own safe zone). */
    var edgePad by mutableStateOf(20)

    val round get() = dockStyle == "round" || dockStyle == "roundnames" || dockStyle == "apple"
    /** Apple-style dock: one frosted shelf, circle icons that grow and lift when selected. */
    val glass get() = dockStyle == "apple"
    val roundNames get() = dockStyle == "roundnames"

    /**
     * A square, edge-to-edge picture of an app icon, to be clipped to a circle (Google TV's round app row).
     * Adaptive icons: both layers drawn at 1.5x, so the circle shows the 72-of-108 visible area the system mask would.
     * Older icons: the circle is filled with the icon's own edge colour, and the icon drawn at 80% on top.
     */
    fun roundArt(d: Drawable, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (d is AdaptiveIconDrawable) {
            val o = -px / 4; val s = px * 3 / 2
            d.background?.let { it.setBounds(o, o, o + s, o + s); it.draw(c) }
            d.foreground?.let { it.setBounds(o, o, o + s, o + s); it.draw(c) }
        } else {
            val src = d.toBitmap(px, px)
            fun solid(x: Int, y: Int) = src.getPixel(x, y).takeIf { android.graphics.Color.alpha(it) > 200 }
            c.drawColor(solid(px / 2, 2) ?: solid(2, px / 2) ?: 0xFF16202C.toInt())
            val m = px / 10
            c.drawBitmap(src, null, Rect(m, m, px - m, px - m), null)
        }
        return bmp
    }
    val iconsOnly get() = dockStyle != "banners"
    val styleName get() = NAMES[dockStyle] ?: dockStyle

    private fun sp(ctx: Context) = ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val s = sp(ctx)
        dockStyle = s.getString("dockStyle", "apple")?.takeIf { it in STYLES } ?: "apple"
        // once: the Apple-style dock becomes the look (still changeable in Settings → Dock)
        if (!s.getBoolean("dockApple", false)) { dockStyle = "apple"; s.edit().putString("dockStyle", "apple").putBoolean("dockApple", true).apply() }
        edgePad = s.getInt("edgePad", 20)
    }

    fun cycleStyle(ctx: Context, d: Int) {
        dockStyle = STYLES[(STYLES.indexOf(dockStyle).coerceAtLeast(0) + d + STYLES.size) % STYLES.size]
        sp(ctx).edit().putString("dockStyle", dockStyle).apply()
    }

    fun changeEdge(ctx: Context, d: Int) {
        edgePad = (edgePad + d).coerceIn(0, 80)
        sp(ctx).edit().putInt("edgePad", edgePad).apply()
    }
}
