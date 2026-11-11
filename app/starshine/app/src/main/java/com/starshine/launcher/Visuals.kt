package com.starshine.launcher

import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * Line visualizers: a category of Bg layers, nothing else draws them.
 * Driven by the sound's own parameters (no microphone): carrier & beat for tones, a slow default otherwise.
 * Photosensitivity-safe: on-screen modulation is capped at 2 Hz even for fast beats (e.g. 40 Hz gamma).
 */
val VISUAL_MODES = listOf("Sine waves", "Lissajous", "Kaleidoscope", "Spiral", "Flow")

private const val TAU = (2 * PI).toFloat()

/** Drawn on black into the layer's texture; follows the playing sound if there is one. */
fun DrawScope.drawVisualLayer(mode: String, t: Float, intensity: Float, hue: Float) {
    val tone = Sound.toneNow
    val c1 = Color.hsl(((190f + hue) % 360f + 360f) % 360f, 0.9f, 0.65f)
    val c2 = Color.hsl(((275f + hue) % 360f + 360f) % 360f, 0.85f, 0.7f)
    when (mode) {
        "Sine waves" -> sines(t, tone, intensity, c1, c2)
        "Lissajous" -> lissajous(t, tone, intensity, c1, c2)
        "Kaleidoscope" -> kaleido(t, tone, intensity, c1, c2)
        "Spiral" -> spiral(t, tone, intensity, c1, c2)
        else -> flow(t, intensity, c1, c2)
    }
}

private fun visBeat(tone: Tone?) = (tone?.beat ?: 0.3f).coerceIn(0.05f, 2f)
private fun cycles(tone: Tone?) = if (tone == null) 3f else (ln(tone.carrier.coerceAtLeast(20f)) - 2.5f).coerceIn(1f, 8f)

private fun DrawScope.glowPath(p: Path, c: Color, a: Float, w: Float) {
    drawPath(p, c.copy(alpha = (0.18f * a).coerceAtMost(1f)), style = Stroke(w * 6f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
    drawPath(p, c.copy(alpha = (0.85f * a).coerceAtMost(1f)), style = Stroke(w, cap = StrokeCap.Round))
}

private fun DrawScope.sines(t: Float, tone: Tone?, a: Float, c1: Color, c2: Color) {
    val w = size.width; val h = size.height; val cyc = cycles(tone); val env = 0.6f + 0.4f * sin(TAU * visBeat(tone) * t)
    for (k in 0 until 3) {
        val p = Path(); var x = 0f
        while (x <= w) {
            val y = h * 0.5f + sin(TAU * (x / w * cyc + k * 0.15f) + t * (1.2f + k * 0.4f)) * h * (0.12f - k * 0.03f) * env
            if (x == 0f) p.moveTo(x, y) else p.lineTo(x, y); x += 6f
        }
        glowPath(p, if (k == 1) c2 else c1, a * (1f - k * 0.25f), 2.5f - k * 0.5f)
    }
}

private fun DrawScope.lissajous(t: Float, tone: Tone?, a: Float, c1: Color, c2: Color) {
    val cx = size.width / 2; val cy = size.height / 2; val r = size.minDimension * 0.32f
    // ratio from the left/right ear frequencies, simplified so the figure stays readable
    val fa = 3f; val fb = 3f + (if (tone != null && tone.carrier > 0f) (tone.beat / tone.carrier * 20f).coerceIn(0f, 2f) else 1f)
    val phase = t * visBeat(tone) * 0.5f
    val p = Path()
    for (i in 0..720) {
        val th = i / 720f * TAU
        val x = cx + r * 1.4f * sin(fa * th + phase); val y = cy + r * sin(fb * th)
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    glowPath(p, c1, a, 2f)
    rotate(t * 6f, Offset(cx, cy)) { scale(0.6f, Offset(cx, cy)) { glowPath(p, c2, a * 0.6f, 1.5f) } }
}

private fun DrawScope.kaleido(t: Float, tone: Tone?, a: Float, c1: Color, c2: Color) {
    val c = Offset(size.width / 2, size.height / 2); val r = size.minDimension * 0.42f
    val petals = 12; val m = cycles(tone).toInt().coerceAtLeast(2); val env = 0.7f + 0.3f * sin(TAU * visBeat(tone) * t)
    val petal = Path()
    for (i in 0..120) {
        val th = i / 120f * (TAU / petals)
        val rr = r * env * (0.45f + 0.55f * sin(m * th * petals / 2f + t * 0.8f).let { it * it })
        val x = c.x + rr * cos(th); val y = c.y + rr * sin(th)
        if (i == 0) petal.moveTo(c.x, c.y)
        petal.lineTo(x, y)
    }
    for (k in 0 until petals) {
        rotate(k * 360f / petals + t * 8f, c) {
            glowPath(petal, if (k % 2 == 0) c1 else c2, a * 0.7f, 1.6f)
            scale(1f, -1f, c) { glowPath(petal, c2, a * 0.35f, 1f) }
        }
    }
}

private fun DrawScope.spiral(t: Float, tone: Tone?, a: Float, c1: Color, c2: Color) {
    val c = Offset(size.width / 2, size.height / 2); val maxR = size.minDimension * 0.5f; val arms = cycles(tone).toInt().coerceIn(2, 6)
    for (k in 0 until arms) {
        val p = Path()
        for (i in 0..300) {
            val th = i / 300f * TAU * 3f
            val rr = maxR * (i / 300f)
            val ang = th + k * TAU / arms + t * 0.6f
            val x = c.x + rr * cos(ang); val y = c.y + rr * sin(ang)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        glowPath(p, if (k % 2 == 0) c1 else c2, a * 0.6f, 1.6f)
    }
}

private fun DrawScope.flow(t: Float, a: Float, c1: Color, c2: Color) {
    val w = size.width; val h = size.height
    for (k in 0 until 5) {
        val p = Path(); var x = 0f; val base = h * (0.35f + k * 0.08f)
        while (x <= w) {
            val y = base + sin(x * 0.004f + t * (0.5f + k * 0.15f) + k) * 30f + sin(x * 0.011f - t * 0.3f) * 12f
            if (x == 0f) p.moveTo(x, y) else p.lineTo(x, y); x += 8f
        }
        glowPath(p, if (k % 2 == 0) c1 else c2, a * (0.7f - k * 0.1f), 1.8f)
    }
}
