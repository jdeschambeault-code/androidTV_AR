package com.starshine.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * 10 calm procedural backgrounds (v3). Every position is a pure function of time t, so there is no simulation state.
 * All attributes are open-ended (no artificial caps): speed (negative = reverse), density, size, glow,
 * twinkle, brightness, saturation, vignette, color shift. Drawn on true black (see-through in Rokid optics).
 */
val PROCEDURAL = listOf(
    "Starfield", "Constellations", "Nebula", "Aurora", "Fireflies",
    "Bioluminescent sea", "Soft rain", "Snowfall", "Breathing orbs", "Slow galaxy"
)

data class BgStyle(
    val speed: Float = 1f, val density: Float = 0.75f, val glow: Float = 1f, val hue: Float = 0f,
    val size: Float = 1f, val brightness: Float = 1f, val saturation: Float = 1f,
    val twinkle: Float = 1f, val vignette: Float = 1f,
    /** motion direction: X = horizontal (+ right), Y = vertical (+ down), Z = depth (+ toward you, - away) */
    val dirX: Float = 0f, val dirY: Float = 0f, val dirZ: Float = 1f
)

// per-frame style knobs used by the scene functions (set at the start of each draw)
private var SZ = 1f
private var TW = 1f
private var VIG = 1f
private var DX = 0f
private var DY = 0f
private var DZ = 1f

private fun wrap(v: Float, m: Float) = if (m <= 0f) 0f else ((v % m) + m) % m
private fun hsl(h: Float, s: Float, l: Float, a: Float) = Color.hsl(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f), a.coerceIn(0f, 1f))
private fun hash(i: Int, salt: Int): Float = Random(i * 92821 + salt * 68917).nextFloat()
/** twinkle shaping: TW=0 steady, 1 normal, >1 stronger */
private fun tw(x: Float) = (1f - TW * (1f - x)).coerceIn(0.05f, 1f)

private class Seeds(n: Int, k: Int, seed: Int) {
    val v: Array<FloatArray> = Array(n) { val r = Random(seed * 7919 + it); FloatArray(k) { r.nextFloat() } }
    operator fun get(i: Int): FloatArray = v[((i % v.size) + v.size) % v.size]
}

private val SEED_CACHE = HashMap<String, Seeds>()
private fun seedsFor(name: String) = synchronized(SEED_CACHE) { SEED_CACHE.getOrPut(name) { Seeds(3000, 6, name.hashCode()) } }

@Composable
fun ProceduralBackground(name: String, t: Float, style: BgStyle, modifier: Modifier = Modifier) {
    remember(name) { seedsFor(name) }
    Canvas(modifier) { drawProcedural(name, t, style) }
}

/**
 * The classic backgrounds drawn on their own thread into a SurfaceView, so menus, focus changes and app loading
 * on the main thread can't stutter them (and they can't stutter the menus).
 */
private class BackgroundSurface(ctx: android.content.Context) : android.view.SurfaceView(ctx), android.view.SurfaceHolder.Callback {
    @Volatile var scene = PROCEDURAL[0]
    @Volatile var style = BgStyle()
    @Volatile var fps30 = true
    @Volatile var reactive = false
    @Volatile private var thread: Thread? = null
    init { holder.addCallback(this); isFocusable = false }
    override fun surfaceCreated(h: android.view.SurfaceHolder) {
        thread = Thread({
            val draw = androidx.compose.ui.graphics.drawscope.CanvasDrawScope()
            var last = System.nanoTime(); var clock = 0f
            while (thread === Thread.currentThread()) {
                val frame = h.surfaceFrame
                val c = runCatching { h.lockHardwareCanvas() }.getOrNull() ?: break
                val now = System.nanoTime(); clock += (now - last) / 1e9f; last = now
                val st = if (reactive) soundStyleAt(style, clock) else style
                runCatching {
                    draw.draw(androidx.compose.ui.unit.Density(1f), androidx.compose.ui.unit.LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(c),
                        Size(frame.width().toFloat(), frame.height().toFloat())) { drawProcedural(scene, clock, st) }
                }
                runCatching { h.unlockCanvasAndPost(c) }
                if (fps30) { val wait = 33L - (System.nanoTime() - now) / 1_000_000L; if (wait > 0) Thread.sleep(wait) }
            }
        }, "starshine-bg").apply { isDaemon = true; start() }
    }
    override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, ht: Int) {}
    override fun surfaceDestroyed(h: android.view.SurfaceHolder) { val t = thread; thread = null; runCatching { t?.join(500) } }
}

@Composable
fun ThreadedBackground(name: String, style: BgStyle, fps30: Boolean, reactive: Boolean, modifier: Modifier = Modifier) {
    remember(name) { seedsFor(name) }
    androidx.compose.ui.viewinterop.AndroidView(factory = { BackgroundSurface(it) }, modifier = modifier, update = {
        it.scene = name; it.style = style; it.fps30 = fps30; it.reactive = reactive
    })
}

/** Non-composable entry point (used by the live background, the screensaver and the MP4 exporter). */
fun DrawScope.drawProcedural(name: String, t: Float, style: BgStyle) {
    val seeds = seedsFor(name)
    run {
        drawRect(Color.Black)
        SZ = style.size.coerceAtLeast(0.05f); TW = style.twinkle.coerceAtLeast(0f); VIG = style.vignette.coerceAtLeast(0f)
        DX = style.dirX; DY = style.dirY; DZ = style.dirZ
        val ts = t * style.speed
        val q = style.density.coerceAtLeast(0f)
        val g = style.glow.coerceAtLeast(0f)
        val hue = style.hue
        val layer = style.brightness != 1f || style.saturation != 1f
        if (layer) drawIntoCanvas { c ->
            val b = style.brightness.coerceAtLeast(0f)
            val m = ColorMatrix().apply { setToSaturation(style.saturation.coerceAtLeast(0f)) }
            m.timesAssign(ColorMatrix(floatArrayOf(b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
            c.saveLayer(Rect(Offset.Zero, size), Paint().apply { colorFilter = ColorFilter.colorMatrix(m) })
        }
        when (name) {
            "Starfield" -> starfield(seeds, ts, t, q, g, hue)
            "Constellations" -> constellations(seeds, ts, t, q, g, hue)
            "Nebula" -> nebula(seeds, ts, t, q, g, hue)
            "Aurora" -> aurora(seeds, ts, t, q, g, hue)
            "Fireflies" -> fireflies(seeds, ts, t, q, g, hue)
            "Bioluminescent sea" -> sea(seeds, ts, t, q, g, hue)
            "Soft rain" -> rain(seeds, ts, t, q, g, hue)
            "Snowfall" -> snow(seeds, ts, t, q, g, hue)
            "Breathing orbs" -> orbs(ts, t, q, g, hue)
            "Slow galaxy" -> galaxy(seeds, ts, t, q, g, hue)
        }
        if (layer) drawIntoCanvas { it.restore() }
    }
}

/* ---------- shared layers ---------- */

private fun DrawScope.dust(s: Seeds, t: Float, q: Float, count: Int, alpha: Float, from: Int = 2400) {
    val n = (count * q).toInt()
    val pts = ArrayList<Offset>(n); val bright = ArrayList<Offset>(n / 4 + 1)
    for (i in 0 until n) {
        val v = s[from + (i % 600)]
        val o = Offset(wrap(v[0] * size.width + i * 7.31f, size.width), wrap(v[1] * size.height + i * 3.17f, size.height))
        if (sin(t * (0.6f + v[3]) + v[2] * 40f) > 1f - 0.7f * TW.coerceAtMost(1.4f)) bright.add(o) else pts.add(o)
    }
    drawPoints(pts, PointMode.Points, Color(0xFFCFE3FF).copy(alpha = alpha * 0.5f), 1.6f * SZ, StrokeCap.Round)
    drawPoints(bright, PointMode.Points, Color.White.copy(alpha = alpha), 2.2f * SZ, StrokeCap.Round)
}

private fun DrawScope.halo(c: Offset, r: Float, color: Color, plus: Boolean = true) {
    if (r <= 0f || color.alpha <= 0.001f) return
    drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = color.alpha * 0.35f), Color.Transparent), c, r), r, c,
        blendMode = if (plus) BlendMode.Plus else BlendMode.SrcOver)
}

private fun DrawScope.vignette() {
    if (VIG <= 0f) return
    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = (0.55f * VIG).coerceAtMost(1f))),
        Offset(size.width / 2, size.height / 2), maxOf(size.width, size.height) * 0.75f))
}

/* ---------- 1. Starfield: true 3D flight along (X, Y, Z), halos, colour temperatures, shooting stars ---------- */

private fun DrawScope.starfield(s: Seeds, ts: Float, t: Float, q: Float, g: Float, hue: Float) {
    val w = size.width; val h = size.height; val cx = w / 2; val cy = h / 2; val f = minOf(w, h) * 0.6f
    drawRect(Brush.linearGradient(listOf(Color.Transparent, hsl(225f + hue, 0.6f, 0.35f, 0.10f * g), Color.Transparent),
        Offset(0f, h * 0.15f), Offset(w, h * 0.85f)))
    // nebula backdrop zooming toward the viewer (two crossfading layers = endless approach), shifted by X/Y
    Nebula.image?.let { img ->
        val layers = if (abs(DZ) < 0.01f) listOf(0.3f) else listOf(wrap(ts * DZ * 0.012f, 1f), wrap(ts * DZ * 0.012f + 0.5f, 1f))
        for (p in layers) {
            val sc = 1.05f + p * 1.3f
            val a = (Nebula.alpha * (if (layers.size == 1) 1f else sin(p * PI.toFloat()))).coerceIn(0f, 1f)
            val aspect = img.width.toFloat() / img.height
            var dw = w * sc; var dh = dw / aspect
            if (dh < h * sc) { dh = h * sc; dw = dh * aspect }
            val ox = cx - dw / 2 - DX * p * w * 0.15f; val oy = cy - dh / 2 - DY * p * h * 0.15f
            drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(ox.toInt(), oy.toInt()), IntSize(dw.toInt(), dh.toInt()),
                alpha = a, filterQuality = FilterQuality.Low)
        }
    }
    val temps = arrayOf(Color(0xFFBFD8FF), Color(0xFFEAF2FF), Color(0xFFFFF4E0), Color(0xFFFFE2C4))
    val n = (650 * q).toInt()
    val streak = abs(DZ) > 2.5f
    for (i in 0 until n) {
        val v = s[i]
        // world position in a [-1,1]^2 x (0,1] box, moving with the direction vector; wraps around
        val z = wrap(v[2] - ts * DZ * 0.06f, 1f) * 0.98f + 0.02f
        val x = wrap(v[0] * 2f - 1f + ts * DX * 0.04f + 1f, 2f) - 1f
        val y = wrap(v[1] * 2f - 1f + ts * DY * 0.04f + 1f, 2f) - 1f
        val sx = cx + x / z * f * 0.35f; val sy = cy + y / z * f * 0.35f
        if (sx < -20f || sx > w + 20f || sy < -20f || sy > h + 20f) continue
        val near = 1f - z
        val k = tw(0.45f + 0.55f * abs(sin(t * (0.8f + v[3] * 1.6f) + v[5] * 30f)))
        val c = temps[(v[4] * 4).toInt().coerceAtMost(3)].copy(alpha = (k * (0.25f + 0.75f * near)).coerceIn(0f, 1f))
        val r = (0.6f + near * 2.6f) * SZ
        if (streak) {   // fast flight: motion streaks toward the vanishing point
            val z2 = (z + 0.02f * (if (DZ > 0f) 1f else -1f)).coerceIn(0.02f, 1f)
            drawLine(c, Offset(cx + x / z2 * f * 0.35f, cy + y / z2 * f * 0.35f), Offset(sx, sy), r, StrokeCap.Round)
        } else drawCircle(c, r, Offset(sx, sy))
        if (near > 0.8f && v[5] > 0.5f) halo(Offset(sx, sy), (10f + 14f * near) * SZ, c.copy(alpha = (0.25f * k * g).coerceAtMost(1f)))
    }
    val slot = floor(t / 9f).toInt(); val ph = (t / 9f) - slot
    if (ph < 0.12f) {
        val p = ph / 0.12f
        val sxx = hash(slot, 1) * w * 0.7f + w * 0.2f; val syy = hash(slot, 2) * h * 0.35f
        val head = Offset(sxx - p * w * 0.25f, syy + p * h * 0.18f)
        val tail = Offset(head.x + w * 0.06f, head.y - h * 0.045f)
        val al = sin(p * PI.toFloat())
        drawLine(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.9f * al), Color.Transparent), head, tail), head, tail, 2.4f * SZ, StrokeCap.Round)
        halo(head, 10f * SZ, Color.White.copy(alpha = (0.5f * al * g).coerceAtMost(1f)))
    }
    vignette()
}

/* ---------- 2. Constellations ---------- */

private fun DrawScope.constellations(s: Seeds, ts: Float, t: Float, q: Float, g: Float, hue: Float) {
    val w = size.width; val h = size.height
    dust(s, t, q, 260, 0.45f)
    for (layer in 0..1) {
        val n = ((if (layer == 0) 46 else 30) * q).toInt().coerceIn(2, 140)
        val depth = if (layer == 0) 0.6f else 1f
        val pts = Array(n) { i ->
            val v = s[layer * 200 + i]
            Offset(wrap(v[0] * w + ((v[2] - 0.5f) * 22f + DX * 30f) * ts * depth, w), wrap(v[1] * h + ((v[3] - 0.5f) * 22f + DY * 30f) * ts * depth, h))
        }
        val link = 200f * depth * SZ.coerceIn(0.5f, 2f)
        val col = hsl(190f + hue + 25f * sin(t * 0.05f + layer), 0.85f, 0.65f, 1f)
        for (i in 0 until n) for (j in i + 1 until n) {
            val d = hypot(pts[i].x - pts[j].x, pts[i].y - pts[j].y)
            if (d < link) { val k = 1 - d / link; drawLine(col.copy(alpha = (k * k * 0.45f * depth * g.coerceAtMost(2f)).coerceAtMost(1f)), pts[i], pts[j], (0.8f + k * 1.2f * depth) * SZ) }
        }
        for ((i, p) in pts.withIndex()) {
            val pulse = tw(0.6f + 0.4f * sin(t * 1.3f + i))
            if (layer == 1) halo(p, 12f * pulse * SZ, col.copy(alpha = (0.35f * g).coerceAtMost(1f)))
            drawCircle(Color(0xFFE6FBFF).copy(alpha = 0.6f + 0.4f * depth), (1.6f + depth * 1.2f) * SZ, p)
        }
    }
    vignette()
}

/* ---------- 3. Nebula ---------- */

private fun DrawScope.nebula(s: Seeds, ts: Float, t: Float, q: Float, g: Float, hue: Float) {
    val w = size.width; val h = size.height; val m = maxOf(w, h)
    val hues = floatArrayOf(195f, 265f, 305f, 215f, 285f, 175f, 330f, 240f)
    val clouds = (hues.size * (0.6f + 0.4f * q)).toInt().coerceIn(1, hues.size)
    for (i in 0 until clouds) {
        val ph = i * 1.7f
        val cx = w * (0.15f + 0.7f * ((i * 0.37f) % 1f)) + sin(ts * 0.045f + ph) * w * 0.07f
        val cy = h * (0.2f + 0.6f * ((i * 0.61f) % 1f)) + cos(ts * 0.035f + ph) * h * 0.07f
        val r = m * (0.22f + 0.05f * (i % 4)) * SZ.coerceIn(0.3f, 3f)
        for (k in 0..2) {
            val o = Offset(cx + cos(ph + k * 2.1f + ts * 0.02f) * r * 0.25f, cy + sin(ph + k * 2.1f) * r * 0.2f)
            halo(o, r * (0.75f + k * 0.15f), hsl(hues[i] + hue + k * 12f, 0.9f, 0.55f, 0.075f * g))
        }
    }
    drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f), Color.Transparent),
        Offset(w * 0.1f, h * (0.55f + 0.05f * sin(ts * 0.03f))), Offset(w * 0.9f, h * (0.35f + 0.05f * cos(ts * 0.03f)))))
    dust(s, t, q, 420, 0.8f)
    vignette()
}

/* ---------- 4. Aurora ---------- */

private fun DrawScope.aurora(s: Seeds, ts: Float, t: Float, q: Float, g: Float, hue: Float) {
    val w = size.width; val h = size.height
    dust(s, t, q, 300, 0.6f)
    val step = (if (q >= 0.75f) 6f else 10f) * SZ.coerceIn(0.5f, 3f)
    for (k in 0 until 3) {
        val baseH = 150f + k * 30f + hue
        var x = 0f
        while (x <= w) {
            val y = h * (0.28f + k * 0.06f) + sin(x * 0.0019f + ts * 0.32f + k) * 55f + sin(x * 0.0006f - ts * 0.21f + k * 2) * 85f
            val shimmer = tw(0.5f + 0.5f * sin(x * 0.03f + ts * 1.7f + k * 3f))
            val len = (110f + 90f * sin(x * 0.004f + ts * 0.5f + k)) * (0.7f + 0.3f * shimmer) * SZ.coerceIn(0.5f, 2.5f)
            val a = (0.05f + 0.07f * shimmer) * g
            drawLine(Brush.verticalGradient(listOf(Color.Transparent, hsl(baseH + 60f, 0.8f, 0.6f, a * 0.6f), hsl(baseH, 0.9f, 0.6f, a), Color.Transparent), y - len, y + 25f),
                Offset(x, y - len), Offset(x, y + 25f), step * 0.9f, blendMode = BlendMode.Plus)
            x += step
        }
    }
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, hsl(160f + hue, 0.7f, 0.4f, 0.10f * g)), h * 0.6f, h * 0.85f))
    val ridge = Path().apply {
        moveTo(0f, h); var x = 0f
        while (x <= w) { lineTo(x, h * 0.86f + sin(x * 0.004f) * 18f + sin(x * 0.013f) * 7f); x += 20f }
        lineTo(w, h); close()
    }
    drawPath(ridge, Color.Black)
}

/* ---------- 5. Fireflies ---------- */

private fun DrawScope.fireflies(s: Seeds, ts: Float, t: Float, q: Float, g: Float, hue: Float) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, hsl(120f + hue, 0.5f, 0.12f, 0.6f)), h * 0.5f, h))
    for (i in 0 until (80 * q).toInt()) {
        val v = s[i]; val depth = 0.4f + v[4] * 0.6f
        fun pos(tt: Float) = Offset(
            wrap(v[0] * w + sin(tt * 0.22f + v[2] * 9f) * 110f * depth + tt * 5f * (v[3] - 0.5f), w),
            wrap(v[1] * h * 0.85f + h * 0.1f + cos(tt * 0.29f + v[3] * 9f) * 70f * depth, h))
        val c = pos(ts)
        val blink = tw((0.5f + 0.5f * sin(t * (1.2f + v[5]) + v[2] * 20f)).pow(2))
        val col = hsl(52f + hue - v[5] * 15f, 1f, 0.68f, 1f)
        if (q >= 0.7f) for (k in 1..3) drawCircle(col.copy(alpha = blink * 0.12f / k), 2f * depth * SZ, pos(ts - k * 0.25f))
        halo(c, (10f + 18f * (1 - depth)) * (0.7f + 0.6f * blink) * SZ, col.copy(alpha = (blink * (0.5f + 0.3f * depth) * g).coerceAtMost(1f)))
        drawCircle(Color(0xFFFFFBE0).copy(alpha = blink), (1.4f + depth) * SZ, c)
    }
}

/* ---------- 6. Bioluminescent sea ---------- */

private fun DrawScope.sea(s: Seeds, ts: Float, t: Float, q: Float, g: Float, hue: Float) {
    val w = size.width; val h = size.height
    dust(s, t, q, 200, 0.5f)
    val moon = Offset(w * 0.78f, h * 0.18f)
    halo(moon, 120f * SZ, Color(0xFFDDEBFF).copy(alpha = (0.18f * g).coerceAtMost(1f)))
    drawCircle(Color(0xFFEFF5FF).copy(alpha = 0.9f), 22f * SZ, moon)
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, hsl(200f + hue, 0.8f, 0.5f, 0.08f * g)), h * 0.45f, h))
    val waves = (9 * (0.6f + 0.4f * q)).toInt().coerceIn(2, 16)
    for (k in 0 until waves) {
        val path = Path(); val base = h * (0.5f + k * 0.055f); var x = 0f
        val amp = 5f + k * 3.2f; val fr = 0.0035f + k * 0.0004f
        while (x <= w) {
            val y = base + sin(x * fr + ts * 0.55f * (1 + k * 0.12f)) * amp + sin(x * fr * 2.3f - ts * 0.4f) * amp * 0.35f
            if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y); x += 8f
        }
        val a = (0.10f + 0.06f * sin(t * 1.1f + k)) * (0.6f + k * 0.06f)
        val col = hsl(188f + hue + k * 2f, 0.95f, 0.6f, 1f)
        drawPath(path, col.copy(alpha = (a * 0.45f * g).coerceAtMost(1f)), style = Stroke(12f * SZ), blendMode = BlendMode.Plus)
        drawPath(path, col.copy(alpha = (a + 0.12f).coerceAtMost(1f)), style = Stroke(1.6f * SZ))
    }
    for (i in 0 until (140 * q).toInt()) {
        val v = s[300 + i]
        val x = wrap(v[0] * w + ts * 6f * (v[2] - 0.5f), w)
        val k = (v[1] * waves).toInt()
        val y = h * (0.5f + k * 0.055f) + sin(x * (0.0035f + k * 0.0004f) + ts * 0.55f * (1 + k * 0.12f)) * (5f + k * 3.2f)
        val a = tw((0.5f + 0.5f * sin(t * 3f + v[3] * 50f)).pow(3))
        drawCircle(Color(0xFF8FF6FF).copy(alpha = a), 1.6f * SZ, Offset(x, y))
    }
    vignette()
}

/* ---------- 7. Soft rain ---------- */

private fun DrawScope.rain(s: Seeds, ts: Float, t: Float, q: Float, g: Float, hue: Float) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(hsl(220f + hue, 0.4f, 0.15f, 0.35f), Color.Transparent), 0f, h * 0.6f))
    for (layer in 0..2) {
        val n = ((140 - layer * 40) * q).toInt(); val depth = 0.5f + layer * 0.25f
        for (i in 0 until n) {
            val v = s[layer * 400 + i]; val len = (12f + v[2] * 22f) * depth * 1.4f * SZ
            val y = wrap(v[1] * h + ts * (160f + v[3] * 160f) * depth * (1f + DY), h + len) - len
            val x = wrap(v[0] * w + y * (0.06f + DX * 0.4f) + ts * DX * 80f * depth, w)
            drawLine(hsl(215f + hue, 0.6f, 0.8f, (0.10f + 0.12f * depth) * g.coerceAtMost(3f)), Offset(x, y), Offset(x + len * 0.06f, y + len), (0.8f + depth * 0.8f) * SZ, StrokeCap.Round)
        }
    }
    for (i in 0 until (22 * q).toInt()) {
        val v = s[1300 + i]; val period = 2.2f + v[2] * 1.5f
        val ph = wrap(t + v[3] * period, period) / period
        val c = Offset(v[0] * w, h * (0.86f + v[1] * 0.12f))
        drawOval(hsl(210f + hue, 0.5f, 0.8f, (1 - ph) * 0.25f), topLeft = Offset(c.x - ph * 40f * SZ, c.y - ph * 8f * SZ),
            size = Size(ph * 80f * SZ, ph * 16f * SZ), style = Stroke(1.2f * SZ))
    }
}

/* ---------- 8. Snowfall ---------- */

private fun DrawScope.snow(s: Seeds, ts: Float, t: Float, q: Float, g: Float, hue: Float) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(hsl(215f + hue, 0.35f, 0.18f, 0.35f), Color.Transparent), 0f, h * 0.7f))
    val gust = sin(t * 0.15f) * 40f
    for (layer in 0..2) {
        val n = ((200 - layer * 60) * q).toInt(); val depth = 0.45f + layer * 0.3f
        for (i in 0 until n) {
            val v = s[layer * 500 + i]; val r = (0.8f + v[2] * 1.6f) * depth * 1.5f * SZ
            val y = wrap(v[1] * h + ts * (14f + v[3] * 18f) * depth * 1.6f * (1f + DY), h + 20f) - 10f
            val x = wrap(v[0] * w + sin(t * (0.4f + v[4] * 0.5f) + v[3] * 7f) * 20f * depth + gust * depth + ts * DX * 50f * depth, w)
            drawCircle(Color.White.copy(alpha = 0.35f + 0.45f * depth), r, Offset(x, y))
        }
    }
    for (i in 0 until (10 * q).toInt()) {
        val v = s[1700 + i]
        val y = wrap(v[1] * h + ts * 42f, h + 80f) - 40f; val x = wrap(v[0] * w + sin(t * 0.3f + i) * 50f + gust * 1.8f, w)
        halo(Offset(x, y), (16f + v[2] * 14f) * SZ, Color.White.copy(alpha = (0.10f * g).coerceAtMost(1f)), plus = false)
    }
}

/* ---------- 9. Breathing orbs ---------- */

private fun DrawScope.orbs(ts: Float, t: Float, q: Float, g: Float, hue: Float) {
    val w = size.width; val h = size.height; val m = minOf(w, h)
    val hues = floatArrayOf(190f, 265f, 320f, 160f, 220f, 290f, 175f)
    val pos = arrayOf(0.18f to 0.35f, 0.42f to 0.62f, 0.68f to 0.3f, 0.86f to 0.66f, 0.33f to 0.22f, 0.58f to 0.78f, 0.1f to 0.75f)
    val breath = 0.5f + 0.5f * sin(ts * 2 * PI.toFloat() / 10f)
    for (i in 0 until (hues.size * (0.6f + 0.4f * q)).toInt().coerceIn(1, hues.size)) {
        val r = (0.10f + 0.05f * breath + 0.02f * sin(ts * 0.4f + i * 1.3f)) * m * SZ
        val c = Offset(pos[i].first * w + sin(ts * 0.1f + i) * 50f, pos[i].second * h + cos(ts * 0.09f + i) * 35f)
        val hh = hues[i] + hue + 20f * sin(t * 0.03f + i)
        halo(c, r * 1.6f, hsl(hh, 0.9f, 0.55f, 0.16f * g))
        halo(c, r, hsl(hh, 0.9f, 0.62f, 0.30f * g))
        halo(c, r * 0.35f, hsl(hh + 15f, 0.8f, 0.8f, 0.30f * g))
    }
}

/* ---------- 10. Slow galaxy ---------- */

private fun DrawScope.galaxy(s: Seeds, ts: Float, t: Float, q: Float, g: Float, hue: Float) {
    val c = Offset(size.width / 2, size.height / 2); val m = minOf(size.width, size.height) * 0.48f * SZ.coerceIn(0.3f, 2.5f)
    dust(s, t, q, 260, 0.55f)
    halo(c, m * 0.9f, hsl(250f + hue, 0.6f, 0.4f, 0.10f * g))
    halo(c, m * 0.35f, hsl(40f + hue, 0.7f, 0.75f, 0.30f * g))
    val n = (1500 * q).toInt()
    val bands = Array(4) { ArrayList<Offset>(n / 3 + 1) }
    for (i in 0 until n) {
        val v = s[i]
        val d = v[0].pow(0.65f); val arm = (v[1] * 3).toInt()
        val spread = (v[2] - 0.5f) * (0.25f + 0.6f * d) * exp(-d * 0.5f)
        val a = arm * (2 * PI.toFloat() / 3) + d * 5.2f + spread + ts * 0.034f / (d + 0.22f)
        val rr = d * m
        bands[minOf(3, (d * 4).toInt())].add(Offset(c.x + cos(a) * rr * 1.45f, c.y + sin(a) * rr * 0.55f))
    }
    val colors = listOf(Color(0xFFFFF1D6), Color(0xFFE6EEFF), hsl(215f + hue, 0.8f, 0.75f, 0.85f), hsl(270f + hue, 0.7f, 0.7f, 0.6f))
    for (b in 0 until 4) drawPoints(bands[b], PointMode.Points, colors[b], (2.4f - b * 0.35f) * SZ.coerceIn(0.3f, 3f), StrokeCap.Round)
    halo(c, 18f * SZ, Color.White.copy(alpha = 0.7f))
    vignette()
}
