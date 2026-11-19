package com.starshine.launcher

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * Expressions: a small formula that moves one setting over time, written straight on the setting (hold OK on it).
 *   t = time in seconds, already multiplied by the expression's speed
 *   v = the value you set on the slider
 *   a = the expression's amount
 *   voice level bass mid high beat swell tonebeat tonepitch = the sound right now, 0..1 against its recent peak
 *   st = a sound clock: runs faster as it gets louder (× speed)
 * Operators + - * / % ^ and ( ), numbers, pi, and the functions
 *   sin cos tan abs sqrt floor fract min max clamp mix noise pulse tri saw step
 * A formula is read once and kept; an unreadable one leaves the setting as it is.
 */
object Expr {
    /** Ready-made formulas, the starting point most of the time. Each: name, formula, what it does. */
    class Template(val name: String, val text: String, val help: String)
    val TEMPLATES = listOf(
        Template("Keeps turning", "v + a*t", "turns on forever: put it on Rotation X, Y or Z"),
        Template("Wave", "v + a*sin(t)", "swings around your value"),
        Template("Keeps going", "v + a*t", "moves on forever"),
        Template("Loop", "v + a*fract(t/6.2832)", "from your value to value + amount, then again"),
        Template("There and back", "v + a*tri(t/6.2832)", "to value + amount and back"),
        Template("Pulse", "v + a*pulse(t/6.2832)", "a quick rise, then a slow fall"),
        Template("Wander", "v + a*(noise(t)*2-1)", "drifts, never repeating"),
        Template("Flicker", "v + a*(noise(t*8)*2-1)", "a fast, nervous shimmer"),
        Template("Steps", "v + a*floor(t/6.2832)", "jumps up one step at a time"),
        Template("Breathe", "v + a*(0.5-0.5*cos(t))", "rises and settles, like a breath")
    )
    /** Sound sources a formula can use, in the order of [AudioBands]. */
    val SOUNDS = listOf("voice", "level", "bass", "mid", "high", "beat", "swell", "tonebeat", "tonepitch")
    /**
     * Sound formulas. Each sound value is measured against its own recent peak, so it swings over the whole 0..1 whatever the
     * volume: amount is how far the setting moves at full sound. st is a clock that runs faster as it gets louder, so the
     * sound can drive a motion, not only push a value: speed then sets how fast that motion runs.
     */
    val SOUND_TEMPLATES = listOf(
        Template("Dances with the sound", "v + a*sin(st)", "swings, faster as it gets louder"),
        Template("Pushed by the bass", "v + a*bass", "moves out on the bass, back in the quiet"),
        Template("Kicks on the beat", "v + a*beat^3", "a sharp jump on each beat"),
        Template("Wave sized by the bass", "v + a*bass*sin(t)", "a steady wave that grows with the bass"),
        Template("Opens with the voice", "v + a*voice", "follows speech and chant"),
        Template("Shimmers on the highs", "v + a*high*(noise(t*8)*2-1)", "trembles with cymbals and bells"),
        Template("Breathes with the swell", "v + a*swell", "slow rise and fall of the whole sound"),
        Template("Spins with the sound", "v + a*st", "turns on, faster when louder: for rotations"),
        Template("Glows with the level", "v + a*level", "follows how loud it is")
    ) + SOUNDS.map { Template("Follows " + it, "v + a*$it", "the raw " + it + " value, 0..1") }

    /* sound values, scaled to their recent peak, and the sound clock: updated once a frame by the renderer */
    private val peaks = FloatArray(9) { 0.05f }
    private val norm = DoubleArray(9)
    @Volatile private var soundTime = 0.0
    fun tick(dt: Float) {
        // sound expressions always react: the React to sound switch only covers the older reaction settings
        for (i in 0 until 9) {
            val raw = AudioBands.band(i)
            peaks[i] = maxOf(raw, peaks[i] * (1f - dt * 0.15f), 0.02f)   // the peak falls slowly, so quiet music still swings fully
            norm[i] = (raw / peaks[i]).toDouble().coerceIn(0.0, 1.0)
        }
        soundTime += dt * (0.3 + norm[1] * 3.0)
    }
    /** Whether a formula moves with time, so its speed setting means something. */
    fun usesTime(text: String) = Regex("""\b(t|st)\b""").containsMatchIn(text)
    /** Whether a formula listens to the sound (any sound value, or the sound clock). */
    fun usesSound(text: String) = Regex("""\b(voice|level|bass|mid|high|beat|swell|tonebeat|tonepitch|st)\b""").containsMatchIn(text)

    /** Settings measured in degrees around a circle: an expression wraps them instead of stopping at the end. */
    val ANGLES = setOf("angle", "tiltX", "tiltY", "mrot", "hue", "hue2")

    private fun ek(key: String) = "es:$key"
    private fun ak(key: String) = "ea:$key"
    fun speed(l: Layer, key: String) = (l.v[ek(key)] ?: 1f).let { if (it == 0f) 1f else it }
    fun amount(l: Layer, key: String) = l.v[ak(key)] ?: 0f
    fun set(l: Layer, key: String, text: String, amount: Float, speed: Float = speed(l, key)) =
        l.copy(ex = l.ex + (key to text), v = l.v + mapOf(ek(key) to speed, ak(key) to amount))
    fun remove(l: Layer, key: String) = l.copy(ex = l.ex - key, v = l.v - setOf(ek(key), ak(key)))

    /** The layer at [t] seconds: every setting with an expression gets its value from it, kept within the setting's range. */
    fun apply(l: Layer, t: Float): Layer {
        if (l.ex.isEmpty()) return l
        val v = HashMap(l.v)
        for ((key, text) in l.ex) {
            val f = compile(text) ?: continue
            val p = LayerAnim.TARGETS.firstOrNull { it.key == key } ?: Space.attrs(l.type).firstOrNull { it.key == key }
            val vars = DoubleArray(4 + SOUNDS.size)
            vars[0] = (t * speed(l, key)).toDouble(); vars[1] = (l.v[key] ?: l[key]).toDouble(); vars[2] = amount(l, key).toDouble()
            for (i in SOUNDS.indices) vars[3 + i] = norm[i]
            vars[3 + SOUNDS.size] = soundTime * speed(l, key)
            val x = f(vars).toFloat()
            if (x.isNaN() || x.isInfinite()) continue
            // angles go round and round; everything else stops at the end of its range
            v[key] = when { p == null -> x; key in ANGLES -> { val r = p.max - p.min; p.min + ((x - p.min) % r + r) % r }; else -> x.coerceIn(p.min, p.max) }
        }
        return l.copy(v = v)
    }

    /* ---------------- the reader ---------------- */

    private val cache = HashMap<String, ((DoubleArray) -> Double)?>()
    /** null when the formula can't be read. */
    fun compile(text: String): ((DoubleArray) -> Double)? = synchronized(cache) { cache.getOrPut(text) { runCatching { Parser(text).parse() }.getOrNull() } }
    fun valid(text: String) = compile(text) != null

    private fun hash(n: Double): Double { val s = sin(n * 12.9898) * 43758.5453; return s - floor(s) }
    private fun noise(x: Double): Double { val i = floor(x); val f = x - i; val u = f * f * (3 - 2 * f); return hash(i) * (1 - u) + hash(i + 1) * u }
    private fun fract(x: Double) = x - floor(x)

    private class Parser(val s: String) {
        var i = 0
        fun parse(): (DoubleArray) -> Double { val e = sum(); skip(); require(i == s.length) { "unexpected ${s.substring(i)}" }; return e }
        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun eat(c: Char): Boolean { skip(); return if (i < s.length && s[i] == c) { i++; true } else false }
        fun sum(): (DoubleArray) -> Double {
            var e = product()
            while (true) {
                e = when { eat('+') -> { val a = e; val b = product(); { x -> a(x) + b(x) } }
                    eat('-') -> { val a = e; val b = product(); { x -> a(x) - b(x) } }
                    else -> return e }
            }
        }
        fun product(): (DoubleArray) -> Double {
            var e = power()
            while (true) {
                e = when { eat('*') -> { val a = e; val b = power(); { x -> a(x) * b(x) } }
                    eat('/') -> { val a = e; val b = power(); { x -> val d = b(x); if (d == 0.0) 0.0 else a(x) / d } }
                    eat('%') -> { val a = e; val b = power(); { x -> val d = b(x); if (d == 0.0) 0.0 else a(x) - d * floor(a(x) / d) } }
                    else -> return e }
            }
        }
        fun power(): (DoubleArray) -> Double { val a = unary(); return if (eat('^')) { val b = power(); { x -> Math.pow(a(x), b(x)) } } else a }
        fun unary(): (DoubleArray) -> Double = if (eat('-')) { val a = unary(); { x -> -a(x) } } else if (eat('+')) unary() else atom()
        fun atom(): (DoubleArray) -> Double {
            skip()
            if (eat('(')) { val e = sum(); require(eat(')')) { "missing )" }; return e }
            val start = i
            if (i < s.length && (s[i].isDigit() || s[i] == '.')) {
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                val n = s.substring(start, i).toDouble(); return { n }
            }
            while (i < s.length && s[i].isLetter()) i++
            val name = s.substring(start, i).lowercase()
            require(name.isNotEmpty()) { "expected a value at ${s.substring(start)}" }
            if (!eat('(')) return when (name) {
                "t" -> { x -> x[0] }; "v" -> { x -> x[1] }; "a" -> { x -> x[2] }; "pi" -> { _ -> PI }
                in SOUNDS -> { val k = 3 + SOUNDS.indexOf(name); { x -> x[k] } }
                "st" -> { val k = 3 + SOUNDS.size; { x -> x[k] } }
                else -> throw IllegalArgumentException("unknown $name")
            }
            val args = ArrayList<(DoubleArray) -> Double>()
            if (!eat(')')) { do args.add(sum()) while (eat(',')); require(eat(')')) { "missing )" } }
            fun a(k: Int) = args.getOrNull(k) ?: throw IllegalArgumentException("$name needs more values")
            return when (name) {
                "sin" -> { val f = a(0); { x -> Math.sin(f(x)) } }
                "cos" -> { val f = a(0); { x -> Math.cos(f(x)) } }
                "tan" -> { val f = a(0); { x -> Math.tan(f(x)) } }
                "abs" -> { val f = a(0); { x -> abs(f(x)) } }
                "sqrt" -> { val f = a(0); { x -> Math.sqrt(abs(f(x))) } }
                "floor" -> { val f = a(0); { x -> floor(f(x)) } }
                "fract", "saw" -> { val f = a(0); { x -> fract(f(x)) } }
                "tri" -> { val f = a(0); { x -> val p = fract(f(x)); if (p < 0.5) p * 2 else 2 - p * 2 } }
                "pulse" -> { val f = a(0); { x -> val p = fract(f(x)); if (p < 0.12) p / 0.12 else 1 - (p - 0.12) / 0.88 } }
                "noise" -> { val f = a(0); { x -> noise(f(x)) } }
                "step" -> { val e = a(0); val f = a(1); { x -> if (f(x) < e(x)) 0.0 else 1.0 } }
                "min" -> { val f = a(0); val g = a(1); { x -> minOf(f(x), g(x)) } }
                "max" -> { val f = a(0); val g = a(1); { x -> maxOf(f(x), g(x)) } }
                "mix" -> { val f = a(0); val g = a(1); val k = a(2); { x -> val m = k(x); f(x) * (1 - m) + g(x) * m } }
                "clamp" -> { val f = a(0); val lo = a(1); val hi = a(2); { x -> f(x).coerceIn(lo(x), maxOf(lo(x), hi(x))) } }
                else -> throw IllegalArgumentException("unknown function $name")
            }
        }
    }
}
