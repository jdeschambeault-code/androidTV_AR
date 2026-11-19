package com.starshine.launcher

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * More 3D elements: worlds (planets with their own look), weather and elements made of particles, plants and crystals
 * drawn as glowing lines, and a tesseract turning in the fourth dimension.
 */
object SpaceShapes {
    /** Worlds: drawn like the planet, each with its own colours, bands and air. */
    val WORLDS = mapOf(
        "Earth" to mapOf("hue" to 210f, "hue2" to -90f, "sat" to 0.75f, "bands" to 3f, "atmo" to 1.6f),
        "Moon" to mapOf("hue" to 40f, "hue2" to 0f, "sat" to 0.06f, "bands" to 0f, "atmo" to 0f, "scale" to 0.6f),
        "Mars" to mapOf("hue" to 14f, "hue2" to 12f, "sat" to 0.65f, "bands" to 2f, "atmo" to 0.4f),
        "Gas giant" to mapOf("hue" to 32f, "hue2" to 18f, "sat" to 0.5f, "bands" to 16f, "atmo" to 0.8f, "scale" to 1.4f),
        "Ice world" to mapOf("hue" to 190f, "hue2" to 20f, "sat" to 0.25f, "bands" to 5f, "atmo" to 1.2f),
        "Lava world" to mapOf("hue" to 8f, "hue2" to 35f, "sat" to 0.9f, "bands" to 6f, "atmo" to 0.6f, "glow" to 1.5f)
    )
    /** Particles that move like an element: name to (mode in the point shader, colour). */
    val PARTICLES = mapOf(
        "Ocean waves" to mapOf("hue" to 195f, "sat" to 0.6f), "Wind" to mapOf("hue" to 200f, "sat" to 0.15f),
        "Embers" to mapOf("hue" to 20f, "sat" to 0.9f, "glow" to 1.4f), "Rain" to mapOf("hue" to 205f, "sat" to 0.3f),
        "Snow" to mapOf("hue" to 210f, "sat" to 0.05f), "Fireflies" to mapOf("hue" to 70f, "sat" to 0.8f),
        "Smoke" to mapOf("hue" to 230f, "sat" to 0.1f, "opacity" to 0.6f), "Fountain" to mapOf("hue" to 195f, "sat" to 0.5f)
    )
    /** Plants and crystals, as glowing lines. */
    val PLANTS = mapOf(
        "Lotus" to mapOf("hue" to 325f, "sat" to 0.6f, "detail" to 0.25f), "Tree" to mapOf("hue" to 100f, "sat" to 0.6f, "detail" to 0.2f),
        "Fern" to mapOf("hue" to 120f, "sat" to 0.7f, "detail" to 0.15f), "Crystal cluster" to mapOf("hue" to 190f, "sat" to 0.4f, "detail" to 0.25f)
    )
    const val TESSERACT = "Tesseract (4D)"

    fun isWorld(t: String) = t == "Planet" || t in WORLDS
    fun isLines(t: String) = t in PLANTS || t == TESSERACT

    /* ---------------- plants and crystals, as line segments (x0 y0 z0 x1 y1 z1 ...) ---------------- */

    private val cache = HashMap<String, FloatArray>()
    fun mesh(t: String): FloatArray = cache.getOrPut(t) {
        val s = ArrayList<Float>()
        fun seg(a: FloatArray, b: FloatArray) { s.add(a[0]); s.add(a[1]); s.add(a[2]); s.add(b[0]); s.add(b[1]); s.add(b[2]) }
        fun line(pts: List<FloatArray>) { for (i in 1 until pts.size) seg(pts[i - 1], pts[i]) }
        when (t) {
            "Lotus" -> {   // three rings of petals, opening outward, around a small seed pod
                for ((ring, n, len, up) in listOf(Quad(0, 6, 0.55f, 1.2f), Quad(1, 9, 0.8f, 0.75f), Quad(2, 12, 0.95f, 0.35f))) {
                    for (k in 0 until n) {
                        val th = 2 * PI * (k + ring * 0.5) / n
                        val side = { sgn: Float -> (0..14).map { i ->
                            val u = i / 14f; val w = sin(PI * u).toFloat() * 0.22f * len * sgn
                            val r = u * len * cos(up.toDouble()).toFloat(); val y = u * len * sin(up.toDouble()).toFloat() - 0.35f + 0.12f * u * u
                            floatArrayOf((r * cos(th) - w * sin(th)).toFloat(), y, (r * sin(th) + w * cos(th)).toFloat())
                        } }
                        line(side(1f)); line(side(-1f))
                    }
                }
                line((0..24).map { i -> val a = 2 * PI * i / 24; floatArrayOf(0.12f * cos(a).toFloat(), -0.25f, 0.12f * sin(a).toFloat()) })
            }
            "Tree" -> {    // a trunk that splits three ways, six times, leaning out as it goes
                fun branch(p: FloatArray, dir: FloatArray, len: Float, depth: Int, seed: Int) {
                    val q = floatArrayOf(p[0] + dir[0] * len, p[1] + dir[1] * len, p[2] + dir[2] * len); seg(p, q)
                    if (depth == 0) return
                    for (k in 0 until 3) {
                        val a = 2 * PI * (k / 3.0 + seed * 0.13); val tilt = 0.55
                        val d = floatArrayOf((dir[0] + sin(tilt) * cos(a)).toFloat(), dir[1] * 0.9f + 0.25f, (dir[2] + sin(tilt) * sin(a)).toFloat())
                        val n = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]); for (i in 0..2) d[i] /= n
                        branch(q, d, len * 0.68f, depth - 1, seed * 3 + k + 1)
                    }
                }
                branch(floatArrayOf(0f, -1f, 0f), floatArrayOf(0f, 1f, 0f), 0.55f, 6, 1)
            }
            "Fern" -> {    // five fronds arching out, each with leaflets that shrink toward the tip
                for (f in 0 until 5) {
                    val th = 2 * PI * f / 5
                    val stem = (0..20).map { i -> val u = i / 20f; floatArrayOf((u * 0.9f * cos(th)).toFloat(), -0.8f + 1.4f * u - 1.1f * u * u, (u * 0.9f * sin(th)).toFloat()) }
                    line(stem)
                    for (i in 2 until 20 step 2) {
                        val p = stem[i]; val l = 0.18f * (1f - i / 20f)
                        for (sg in listOf(1f, -1f)) seg(p, floatArrayOf(p[0] - (sin(th) * l * sg).toFloat(), p[1] + l * 0.4f, p[2] + (cos(th) * l * sg).toFloat()))
                    }
                }
            }
            else -> {      // crystal cluster: pointed hexagonal prisms leaning out from one base
                val r = java.util.Random(7)
                repeat(7) { c ->
                    val tilt = if (c == 0) 0.0 else 0.25 + r.nextDouble() * 0.5; val az = r.nextDouble() * 2 * PI
                    val h = 0.6f + r.nextFloat() * 0.8f; val w = 0.12f + r.nextFloat() * 0.1f
                    val axis = floatArrayOf((sin(tilt) * cos(az)).toFloat(), cos(tilt).toFloat(), (sin(tilt) * sin(az)).toFloat())
                    val ux = floatArrayOf(cos(az).toFloat() * cos(tilt).toFloat(), -sin(tilt).toFloat(), sin(az).toFloat() * cos(tilt).toFloat())
                    val uz = floatArrayOf(-sin(az).toFloat(), 0f, cos(az).toFloat())
                    fun at(k: Int, along: Float) = floatArrayOf(
                        axis[0] * along + (ux[0] * cos(k * PI / 3) + uz[0] * sin(k * PI / 3)).toFloat() * w,
                        axis[1] * along - 0.8f + (ux[1] * cos(k * PI / 3) + uz[1] * sin(k * PI / 3)).toFloat() * w,
                        axis[2] * along + (ux[2] * cos(k * PI / 3) + uz[2] * sin(k * PI / 3)).toFloat() * w)
                    val tip = floatArrayOf(axis[0] * (h + w * 1.6f), axis[1] * (h + w * 1.6f) - 0.8f, axis[2] * (h + w * 1.6f))
                    for (k in 0 until 6) { seg(at(k, 0f), at(k + 1, 0f)); seg(at(k, h), at(k + 1, h)); seg(at(k, 0f), at(k, h)); seg(at(k, h), tip) }
                }
            }
        }
        s.toFloatArray()
    }
    private data class Quad(val a: Int, val b: Int, val c: Float, val d: Float)

    /* ---------------- the tesseract: a 4D cube, turned in the fourth dimension, then seen in 3D ---------------- */

    private val V4 = Array(16) { i -> FloatArray(4) { k -> if ((i shr k) and 1 == 1) 1f else -1f } }
    private val E4 = (0 until 16).flatMap { i -> (0 until 4).map { k -> i to (i xor (1 shl k)) } }.filter { it.first < it.second }
    /** Its 32 edges at turn [a] (radians): it turns in the X-W and Z-W planes, so its inner cube passes through to the outside. */
    fun tesseract(a: Float): FloatArray {
        val c1 = cos(a); val s1 = sin(a); val c2 = cos(a * 0.7f); val s2 = sin(a * 0.7f)
        val p = Array(16) { i ->
            val v = V4[i]
            var x = v[0] * c1 - v[3] * s1; var w = v[0] * s1 + v[3] * c1
            val z = v[2] * c2 - w * s2; w = v[2] * s2 + w * c2
            val k = 1.6f / (3f - w)   // the fourth dimension shown as distance: the far cube looks smaller
            floatArrayOf(x * k * 0.5f, v[1] * k * 0.5f, z * k * 0.5f)
        }
        val out = FloatArray(E4.size * 6)
        E4.forEachIndexed { n, (i, j) -> for (k in 0..2) { out[n * 6 + k] = p[i][k]; out[n * 6 + 3 + k] = p[j][k] } }
        return out
    }
}
