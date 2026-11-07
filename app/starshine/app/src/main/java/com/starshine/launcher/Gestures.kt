package com.starshine.launcher

import android.content.Context
import androidx.compose.ui.geometry.Offset
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/**
 * M5 air-mouse gestures: a drawn path becomes a direction string in 8 directions:
 * R L U D, plus diagonals A = up-right, B = down-right, C = down-left, E = up-left (e.g. "DR" = down then right).
 * Each gesture maps to an action code:
 *   app:<package> · bg:+1 · bg:-1 · bg:<name> · mood:<name> · sound:stop · ui:quick · ui:home · ui:voice · ui:apps · ui:saver
 */
object Gestures {
    /** 6 example gestures */
    val DEFAULTS = linkedMapOf(
        "R" to "bg:+1", "L" to "bg:-1", "U" to "ui:quick", "D" to "ui:home",
        "DR" to "app:org.jellyfin.androidtv", "UD" to "sound:stop"
    )

    /** Clockwise from right, 45° apart, so neighbours in this ring are one sector away. */
    private const val RING = "RBDCLEUA"
    private const val SECTOR = (PI / 4).toFloat()

    private fun sector(d: Offset): Int {
        val a = atan2(d.y, d.x)     // screen y grows downward, so positive angles turn clockwise
        return ((Math.round(a / SECTOR) % 8) + 8) % 8
    }

    /**
     * Quantise a path into up to 6 strokes. Segments shorter than [minSeg] px are ignored, and strokes much shorter than
     * the longest one (hand tremor, rounded corners) are dropped so that a curved "down then right" still reads as ↓→.
     */
    fun recognize(pts: List<Offset>, minSeg: Float = 45f): String {
        if (pts.size < 2) return ""
        val dirs = ArrayList<Int>(); val lens = ArrayList<Float>(); var anchor = pts.first()
        for (p in pts) {
            val d = p - anchor
            val l = d.getDistance()
            if (l >= minSeg) {
                val s = sector(d)
                if (dirs.isNotEmpty() && dirs.last() == s) lens[lens.size - 1] += l else { dirs.add(s); lens.add(l) }
                anchor = p
            }
        }
        if (dirs.isEmpty()) return ""
        val keep = lens.max() * 0.25f
        val out = StringBuilder()
        for (i in dirs.indices) if (lens[i] >= keep && (out.isEmpty() || out.last() != RING[dirs[i]])) out.append(RING[dirs[i]])
        return out.toString().take(6)
    }

    /** Sectors between two direction letters (0..4). */
    private fun gap(a: Char, b: Char): Int { val d = abs(RING.indexOf(a) - RING.indexOf(b)); return minOf(d, 8 - d) }

    /**
     * The saved gesture a drawn pattern means: exact match first, otherwise the closest one with the same number of
     * strokes where each stroke is off by at most 45° (so older 4-direction gestures still work when drawn at a slant).
     */
    fun match(pattern: String, saved: Collection<String>): String? {
        if (pattern in saved) return pattern
        return saved.filter { s -> s.length == pattern.length && s.indices.all { gap(s[it], pattern[it]) <= 1 } }
            .minByOrNull { s -> s.indices.sumOf { gap(s[it], pattern[it]) } }
    }

    fun arrows(p: String) = p.map { when (it) { 'R' -> "→"; 'L' -> "←"; 'U' -> "↑"; 'D' -> "↓"; 'A' -> "↗"; 'B' -> "↘"; 'C' -> "↙"; 'E' -> "↖"; else -> "?" } }.joinToString("")

    fun load(ctx: Context): LinkedHashMap<String, String> {
        val raw = ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE).getString("gestures", null) ?: return LinkedHashMap(DEFAULTS)
        return runCatching { val o = JSONObject(raw); LinkedHashMap<String, String>().apply { o.keys().forEach { put(it, o.getString(it)) } } }.getOrDefault(LinkedHashMap(DEFAULTS))
    }

    fun save(ctx: Context, map: Map<String, String>) {
        val o = JSONObject(); map.forEach { (k, v) -> o.put(k, v) }
        ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE).edit().putString("gestures", o.toString()).apply()
    }

    /** Human label for an action code (apps resolved by the caller's label lookup). */
    fun describe(code: String, appLabel: (String) -> String?): String = when {
        code.startsWith("app:") -> "Open " + (appLabel(code.removePrefix("app:")) ?: code.removePrefix("app:"))
        code == "bg:+1" -> "Next background"
        code == "bg:-1" -> "Previous background"
        code.startsWith("bg:") -> "Background: " + code.removePrefix("bg:").removePrefix("file:")
        code.startsWith("mood:") -> "Mood: " + code.removePrefix("mood:")
        code == "sound:stop" -> "Stop sound"
        code == "ui:quick" -> "Quick panel"
        code == "ui:home" -> "Home"
        code == "ui:voice" -> "Voice command"
        code == "ui:apps" -> "Open apps"
        code == "ui:saver" -> "Screensaver"
        else -> code
    }
}
