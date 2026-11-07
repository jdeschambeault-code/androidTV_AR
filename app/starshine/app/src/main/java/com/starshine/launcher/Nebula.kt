package com.starshine.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * Nebula backdrop for the Starfield: a built-in procedural nebula (generated once on the pod, no downloads)
 * or the user's own JPG/PNG from the Starshine backgrounds folder. The starfield zooms it toward the viewer.
 */
object Nebula {
    val PRESETS = listOf("Off", "Violet", "Emerald", "Crimson", "Azure", "Golden")
    @Volatile var image: ImageBitmap? = null
    @Volatile var alpha = 0.6f
    private val cache = HashMap<String, ImageBitmap>()

    fun load(ctx: Context, name: String): ImageBitmap? = synchronized(cache) {
        if (name == "Off" || name.isEmpty()) return null
        cache[name]?.let { return it }
        val bmp = when {
            name.startsWith("neb:") -> decode(File(ctx.getExternalFilesDir("nebulae"), name.removePrefix("neb:")))
            name.startsWith("file:") -> decode(File(ctx.getExternalFilesDir("backgrounds"), name.removePrefix("file:")))
            else -> generate(name)
        }
        bmp?.asImageBitmap()?.also { cache[name] = it }
    }

    private fun decode(f: File): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.absolutePath, o)
        var s = 1; while (o.outWidth / s > 1600) s *= 2
        BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
    }.getOrNull()

    private fun hash(x: Int, y: Int, seed: Int): Float { var h = x * 374761393 + y * 668265263 + seed * 982451653; h = (h xor (h ushr 13)) * 1274126177; return ((h xor (h ushr 16)) and 0xffff) / 65535f }
    private fun noise(x: Float, y: Float, seed: Int): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt(); val xf = x - xi; val yf = y - yi
        val u = xf * xf * (3 - 2 * xf); val v = yf * yf * (3 - 2 * yf)
        val a = hash(xi, yi, seed); val b = hash(xi + 1, yi, seed); val c = hash(xi, yi + 1, seed); val d = hash(xi + 1, yi + 1, seed)
        return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v
    }
    private fun fbm(x: Float, y: Float, seed: Int): Float { var s = 0f; var a = 0.5f; var px = x; var py = y; repeat(6) { s += a * noise(px, py, seed); px *= 2.03f; py *= 2.03f; a *= 0.5f }; return s }

    /** Procedural nebula: domain-warped fbm clouds in two colours, dark dust lanes, bright core. */
    private fun generate(name: String): Bitmap {
        val (c1, c2, seed) = when (name) {
            "Emerald" -> Triple(0xFF0F5C4A.toInt(), 0xFF7CFFC4.toInt(), 11)
            "Crimson" -> Triple(0xFF6B0F1A.toInt(), 0xFFFFB36B.toInt(), 23)
            "Azure" -> Triple(0xFF123A7A.toInt(), 0xFF7FE0FF.toInt(), 37)
            "Golden" -> Triple(0xFF5A3D0A.toInt(), 0xFFFFD27A.toInt(), 41)
            else -> Triple(0xFF4B1F7A.toInt(), 0xFFFF6EC7.toInt(), 7)
        }
        val w = 480; val h = 270; val px = IntArray(w * h)
        fun ch(c: Int, s: Int) = (c shr s) and 0xff
        for (y in 0 until h) for (x in 0 until w) {
            val nx = x / w.toFloat() * 3.2f; val ny = y / h.toFloat() * 1.8f
            val qx = fbm(nx, ny, seed); val qy = fbm(nx + 5.2f, ny + 1.3f, seed)
            val v = fbm(nx + 3f * qx, ny + 3f * qy, seed + 1)
            val dust = fbm(nx * 2f + 9f, ny * 2f, seed + 2)
            val dx = x / w.toFloat() - 0.5f; val dy = y / h.toFloat() - 0.5f
            val core = (1f - (dx * dx * 2.2f + dy * dy * 4f) * 3f).coerceIn(0f, 1f)
            val m = (v * 1.6f - 0.45f).coerceIn(0f, 1f).pow(1.5f) * (0.35f + 0.65f * core) * (1f - 0.7f * (dust - 0.45f).coerceIn(0f, 1f) * 2f).coerceIn(0f, 1f)
            val hi = (m * m * 1.4f + 0.25f * core * core).coerceIn(0f, 1f)
            val r = (ch(c1, 16) * m + ch(c2, 16) * hi).coerceAtMost(255f).toInt()
            val g = (ch(c1, 8) * m + ch(c2, 8) * hi).coerceAtMost(255f).toInt()
            val b = (ch(c1, 0) * m + ch(c2, 0) * hi).coerceAtMost(255f).toInt()
            px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** NASA/Webb/Hubble pictures (or any image) pushed into the app's "nebulae" folder by the PC toolkit. */
    fun photos(ctx: Context): List<String> = ctx.getExternalFilesDir("nebulae")?.listFiles()
        ?.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }?.sortedBy { it.name }?.map { "neb:" + it.name } ?: emptyList()

    @Suppress("unused") private fun touch() = sin(0f)
}
