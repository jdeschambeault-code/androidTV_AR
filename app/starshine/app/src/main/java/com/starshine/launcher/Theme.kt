package com.starshine.launcher

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import kotlin.random.Random

/**
 * UI theme: colors are hue + saturation, so a preset and the sliders are the same thing. The menus read these through
 * the CYAN / VIOLET / FG / DIM names used all over the app, which makes a change apply everywhere immediately.
 */
object Theme {
    class Preset(val name: String, val accent: Float, val ring: Float, val sat: Float, val panel: Float)
    val PRESETS = listOf(
        Preset("Starshine", 190f, 258f, 1f, 0.62f), Preset("Ember", 28f, 350f, 1f, 0.62f), Preset("Forest", 140f, 85f, 0.8f, 0.62f),
        Preset("Rose", 335f, 285f, 0.9f, 0.62f), Preset("Gold", 45f, 28f, 0.9f, 0.62f), Preset("Ocean", 205f, 170f, 0.9f, 0.62f),
        Preset("Moon", 215f, 215f, 0.15f, 0.5f), Preset("Glass", 190f, 258f, 1f, 0.25f)
    )
    val FONTS = listOf("Sans" to FontFamily.SansSerif, "Serif" to FontFamily.Serif, "Mono" to FontFamily.Monospace, "Script" to FontFamily.Cursive)

    var accentHue by mutableFloatStateOf(190f)
    var ringHue by mutableFloatStateOf(258f)
    var sat by mutableFloatStateOf(1f)
    var panelAlpha by mutableFloatStateOf(0.62f)
    var scrimAlpha by mutableFloatStateOf(0.55f)
    var font by mutableIntStateOf(0)
    var preset by mutableStateOf("Starshine")

    val accent get() = Color.hsl(accentHue, (sat * 0.98f).coerceIn(0f, 1f), 0.68f)
    val ring get() = Color.hsl(ringHue, (sat * 0.98f).coerceIn(0f, 1f), 0.77f)
    val text get() = Color.hsl(accentHue, (sat * 0.8f).coerceIn(0f, 1f), 0.95f)
    val dim get() = Color.hsl(accentHue, (sat * 0.2f).coerceIn(0f, 1f), 0.58f)
    val family get() = FONTS[font.coerceIn(0, FONTS.size - 1)].second

    private fun sp(ctx: Context) = ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = sp(ctx)
        accentHue = p.getFloat("thAccent", 190f); ringHue = p.getFloat("thRing", 258f); sat = p.getFloat("thSat", 1f)
        panelAlpha = p.getFloat("thPanel", 0.62f); scrimAlpha = p.getFloat("thScrim", 0.55f); font = p.getInt("thFont", 0); preset = p.getString("thPreset", "Starshine")!!
    }

    fun save(ctx: Context) {
        sp(ctx).edit().putFloat("thAccent", accentHue).putFloat("thRing", ringHue).putFloat("thSat", sat).putFloat("thPanel", panelAlpha)
            .putFloat("thScrim", scrimAlpha).putInt("thFont", font).putString("thPreset", preset).apply()
    }

    fun apply(ctx: Context, p: Preset) { accentHue = p.accent; ringHue = p.ring; sat = p.sat; panelAlpha = p.panel; preset = p.name; save(ctx) }

    /** A new harmonious pair: the ring color is a fixed musical-style interval away from the accent. */
    fun generate(ctx: Context) {
        val a = Random.nextInt(36) * 10f
        val interval = listOf(30f, 60f, 120f, 150f, 180f, 210f).random()
        accentHue = a; ringHue = (a + interval) % 360f; sat = 0.75f + Random.nextFloat() * 0.25f; preset = "Generated"; save(ctx)
    }
}

/** Settings → Theme rows. Everything shows live, and stays saved. */
@Composable
fun ThemeRows() {
    val ctx = LocalContext.current
    fun change(block: () -> Unit) { block(); Theme.preset = "Custom"; Theme.save(ctx) }
    fun hue(v: Float, d: Float) = ((v + d) % 360f + 360f) % 360f
    val pi = Theme.PRESETS.indexOfFirst { it.name == Theme.preset }
    fun pick(d: Int) { val n = Theme.PRESETS.size; Theme.apply(ctx, Theme.PRESETS[(pi.coerceAtLeast(0) + d + n) % n]) }
    Column {
        Adjust("Color preset", Theme.preset, { pick(-1) }) { pick(+1) }
        Row_("✨ Generate a new color pair", false, id = "th:gen", onFocus = {}) { Theme.generate(ctx) }
        Adjust("Accent color", "${Theme.accentHue.toInt()}°", { change { Theme.accentHue = hue(Theme.accentHue, -10f) } }) { change { Theme.accentHue = hue(Theme.accentHue, 10f) } }
        Adjust("Ring color (selection)", "${Theme.ringHue.toInt()}°", { change { Theme.ringHue = hue(Theme.ringHue, -10f) } }) { change { Theme.ringHue = hue(Theme.ringHue, 10f) } }
        Adjust("Color strength", "${(Theme.sat * 100).toInt()}%", { change { Theme.sat = (Theme.sat - 0.05f).coerceAtLeast(0f) } }) { change { Theme.sat = (Theme.sat + 0.05f).coerceAtMost(1f) } }
        Adjust("Panel background", "${(Theme.panelAlpha * 100).toInt()}% solid", { change { Theme.panelAlpha = (Theme.panelAlpha - 0.05f).coerceAtLeast(0f) } }) { change { Theme.panelAlpha = (Theme.panelAlpha + 0.05f).coerceAtMost(1f) } }
        Adjust("Dimming behind menus", "${(Theme.scrimAlpha * 100).toInt()}%", { change { Theme.scrimAlpha = (Theme.scrimAlpha - 0.05f).coerceAtLeast(0f) } }) { change { Theme.scrimAlpha = (Theme.scrimAlpha + 0.05f).coerceAtMost(1f) } }
        Adjust("Font", Theme.FONTS[Theme.font].first, { Theme.font = (Theme.font - 1 + Theme.FONTS.size) % Theme.FONTS.size; Theme.save(ctx) }) { Theme.font = (Theme.font + 1) % Theme.FONTS.size; Theme.save(ctx) }
        Row_("↺ Back to the Starshine theme", false, id = "th:reset", onFocus = {}) { Theme.apply(ctx, Theme.PRESETS[0]); Theme.scrimAlpha = 0.55f; Theme.font = 0; Theme.save(ctx) }
    }
}
