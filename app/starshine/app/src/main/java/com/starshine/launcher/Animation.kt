package com.starshine.launcher

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * Layer animation: up to [SLOTS] animators per layer, each moving one setting over time. They live in the layer's value
 * table ("a0t" target, "a0m" mode, "a0a" amount, "a0d" seconds, "a0e" easing, "a0p" start point), so they save, copy and
 * reset like every other setting. The renderer draws an animated copy each frame; the saved values never move.
 */
object LayerAnim {
    const val SLOTS = 4

    /** What can be animated: every number a layer has. */
    // new settings go at the end: saved animations refer to their place in this list
    val TARGETS: List<BgParams.P> = BgParams.LOOK + listOf(BgParams.MASK_AMT, BgParams.WARP_AMT) + Fractal.PARAMS + Space.PARAMS + BgParams.MASK_PARAMS + Space.ELEMENT_PARAMS + Particles.PARAMS

    val MODES = listOf("Off", "Keeps going", "Loop A → B", "There and back", "Wave", "Wander", "Pulse")
    val MODE_HELP = listOf("", "moves on forever, wrapping round its range", "from its value to value + amount, then starts over",
        "to value + amount and back", "swings ± amount around its value", "drifts ± amount, never repeating", "a quick rise, then a slow fall")
    val EASES = listOf("Linear", "Smooth", "Ease in", "Ease out")

    fun k(i: Int, s: String) = "a$i$s"
    fun mode(l: Layer, i: Int) = l[k(i, "m")].toInt().coerceIn(0, MODES.lastIndex)
    fun target(l: Layer, i: Int) = TARGETS[l[k(i, "t")].toInt().coerceIn(0, TARGETS.lastIndex)]
    fun count(l: Layer) = (0 until SLOTS).count { mode(l, it) != 0 }
    fun any(l: Layer) = (0 until SLOTS).any { mode(l, it) != 0 }

    private fun ease(e: Int, x: Float): Float = when (e) {
        1 -> x * x * (3f - 2f * x)
        2 -> x * x
        3 -> 1f - (1f - x) * (1f - x)
        else -> x
    }
    private fun hash(n: Int): Float { var h = n * 374761393 + 668265263; h = (h xor (h ushr 13)) * 1274126177; return ((h xor (h ushr 16)) and 0xFFFF) / 65535f * 2f - 1f }
    /** Smooth noise: random values at whole numbers, eased in between. */
    private fun noise(x: Float, seed: Int): Float { val i = floor(x).toInt(); val f = x - i; val s = f * f * (3f - 2f * f); return hash(i + seed * 7919) * (1 - s) + hash(i + 1 + seed * 7919) * s }

    /** The layer as it is [t] seconds into its animation. */
    fun apply(l: Layer, t: Float): Layer {
        if (!any(l)) return l
        val v = HashMap(l.v)
        for (i in 0 until SLOTS) {
            val m = mode(l, i); if (m == 0) continue
            val p = target(l, i)
            val base = v[p.key] ?: l[p.key]
            val amt = l[k(i, "a")]; val dur = l[k(i, "d")].coerceAtLeast(0.1f); val e = l[k(i, "e")].toInt()
            val u = t / dur + l[k(i, "p")]
            val frac = u - floor(u)
            var x = when (m) {
                1 -> base + amt * u
                2 -> base + amt * ease(e, frac)
                3 -> base + amt * ease(e, if (frac < 0.5f) frac * 2f else 2f - frac * 2f)
                4 -> base + amt * sin(2.0 * PI * u).toFloat()
                5 -> base + amt * noise(u * 2f, i + p.key.hashCode())
                else -> base + amt * (if (frac < 0.12f) ease(e, frac / 0.12f) else 1f - ease(e, (frac - 0.12f) / 0.88f))
            }
            // "keeps going" wraps round the setting's range, so depth keeps coming toward you and starts again far away
            x = if (m == 1) { val r = p.max - p.min; if (r > 0f) p.min + ((x - p.min) % r + r) % r else x } else x.coerceIn(p.min, p.max)
            v[p.key] = x
        }
        return l.copy(v = v)
    }

    /** Ready-made animations, added to the first free slot. Each: target key, mode, amount, seconds, easing. */
    class Preset(val name: String, val key: String, val mode: Int, val amount: Float, val seconds: Float, val ease: Int = 1)
    val PRESETS = listOf(
        Preset("Fly toward me, endlessly", "z", 2, -5f, 20f, 0),
        Preset("Drift far and come back", "z", 3, 2f, 30f),
        Preset("Slow full turn", "angle", 1, 360f, 60f, 0),
        Preset("Tumble (rotate X)", "tiltX", 1, 360f, 40f, 0),
        Preset("Breathe (size)", "scale", 4, 0.15f, 8f),
        Preset("Glow pulse", "glow", 6, 1.2f, 4f, 3),
        Preset("Color cycle", "hue", 1, 350f, 30f, 0),
        Preset("More rays / detail, and back", "detail", 3, 0.5f, 20f),
        Preset("Wander left and right", "x", 5, 0.3f, 12f),
        Preset("Wander up and down", "y", 5, 0.2f, 15f),
        Preset("Speed swells", "speed", 4, 0.8f, 16f),
        Preset("Fade in and out", "opacity", 3, -0.8f, 12f),
        Preset("Mask grows and shrinks", "mthr", 3, 0.8f, 10f),
        Preset("Mask turns", "mrot", 1, 360f, 30f, 0)
    )

    /** Adds [p] in the first free slot; null when all slots are in use. */
    fun add(l: Layer, p: Preset): Layer? {
        val i = (0 until SLOTS).firstOrNull { mode(l, it) == 0 } ?: return null
        val ti = TARGETS.indexOfFirst { it.key == p.key }.coerceAtLeast(0)
        return l.copy(v = l.v + mapOf(k(i, "t") to ti.toFloat(), k(i, "m") to p.mode.toFloat(), k(i, "a") to p.amount, k(i, "d") to p.seconds,
            k(i, "e") to p.ease.toFloat(), k(i, "p") to 0f))
    }
    fun remove(l: Layer, i: Int): Layer = l.copy(v = l.v - listOf("t", "m", "a", "d", "e", "p").map { k(i, it) }.toSet())

    fun summary(l: Layer): String = (0 until SLOTS).filter { mode(l, it) != 0 }.joinToString(", ") { target(l, it).label.lowercase() }.ifEmpty { "none" }
}
