package com.starshine.launcher

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ambient sounds synthesised live (no audio files, never repeating): Rain, Ocean.
 * Stereo 44.1 kHz. Natural sound comes from many small independent events and slow random change instead of one filtered noise.
 */
object Ambient {
    val MODES = listOf("Off", "Rain", "Ocean")
    private const val RATE = 44100
    @Volatile var mode = "Off"
    @Volatile var volume = 0.35f
    private var thread: Thread? = null
    @Volatile private var running = false

    fun apply(newMode: String, vol: Float) {
        volume = vol
        if (newMode == mode && (running || newMode == "Off")) return
        stop(); mode = newMode
        if (newMode != "Off") start()
    }

    fun resume() { if (mode != "Off" && !running) start() }

    fun stop() { running = false; thread?.join(600); thread = null }

    private fun start() {
        running = true
        val chosen = mode
        thread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(min, 32768)).build()
            track.play()
            val n = 1024
            val l = FloatArray(n); val r = FloatArray(n); val pcm = ShortArray(n * 2)
            val synth = AmbientSynth(chosen, RATE, System.nanoTime().toInt())
            var fade = 0f
            while (true) {
                val leaving = !running
                val step = if (leaving) -1f / (RATE * 0.25f) else 1f / (RATE * 2.5f)   // fade in over 2.5 s, out over 0.25 s
                synth.fill(l, r, n)
                for (i in 0 until n) {
                    fade = (fade + step).coerceIn(0f, 1f)
                    val g = volume * fade * fade
                    pcm[i * 2] = (AmbientSynth.limit(l[i]) * g * 32767f).toInt().toShort()
                    pcm[i * 2 + 1] = (AmbientSynth.limit(r[i]) * g * 32767f).toInt().toShort()
                }
                track.write(pcm, 0, pcm.size)
                if (leaving && fade <= 0f) break
            }
            runCatching { track.stop(); track.release() }
        }.apply { isDaemon = true; start() }
    }

    /** Test tool: renders each sound to a WAV (at full volume) and logs how much of one CPU core it needs. */
    fun renderTest(dir: File, seconds: Int = 40) {
        dir.mkdirs()
        for (m in MODES.drop(1) + listOf("Singing bowl", "Tibetan bells", "Xylophone", "Chimes", "Temple gong", "Tanpura", "Handpan", "Kalimba", "Frame drum")) {
            val synth = AmbientSynth(m, RATE, 1234)
            val n = 1024; val l = FloatArray(n); val r = FloatArray(n)
            val total = RATE * seconds / n
            val f = RandomAccessFile(File(dir, m.replace(' ', '_') + ".wav"), "rw"); f.setLength(0)
            val data = n * 4 * total
            val head = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            head.put("RIFF".toByteArray()); head.putInt(36 + data); head.put("WAVEfmt ".toByteArray()); head.putInt(16); head.putShort(1); head.putShort(2)
            head.putInt(RATE); head.putInt(RATE * 4); head.putShort(4); head.putShort(16); head.put("data".toByteArray()); head.putInt(data)
            f.write(head.array())
            val bb = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
            var spent = 0L
            repeat(total) {
                val t0 = System.nanoTime(); synth.fill(l, r, n); spent += System.nanoTime() - t0
                bb.clear()
                for (i in 0 until n) { bb.putShort((AmbientSynth.limit(l[i]) * 32767f).toInt().toShort()); bb.putShort((AmbientSynth.limit(r[i]) * 32767f).toInt().toShort()) }
                f.write(bb.array())
            }
            f.close()
            android.util.Log.i("StarshineSound", "$m: ${spent / 10_000_000 / seconds}% of one core")
        }
        renderDidge(dir, seconds)
        android.util.Log.i("StarshineSound", "render done")
    }
}

private fun wavHeader(frames: Int, rate: Int): ByteArray {
    val data = frames * 4
    val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    h.put("RIFF".toByteArray()); h.putInt(36 + data); h.put("WAVEfmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(2)
    h.putInt(rate); h.putInt(rate * 4); h.putShort(4); h.putShort(16); h.put("data".toByteArray()); h.putInt(data)
    return h.array()
}

private fun renderDidge(dir: File, seconds: Int) {
    val rate = 44100; val d = Didgeridoo(rate); val t = Tone(didge = true)
    val f = RandomAccessFile(File(dir, "Didgeridoo.wav"), "rw"); f.setLength(0)
    f.write(wavHeader(rate * seconds, rate))
    val bb = ByteBuffer.allocate(rate * 4).order(ByteOrder.LITTLE_ENDIAN)
    var spent = 0L
    repeat(seconds) {
        bb.clear()
        val t0 = System.nanoTime()
        repeat(rate) { d.next(t); bb.putShort((d.l.coerceIn(-1f, 1f) * 32767f).toInt().toShort()); bb.putShort((d.r.coerceIn(-1f, 1f) * 32767f).toInt().toShort()) }
        spent += System.nanoTime() - t0
        f.write(bb.array())
    }
    f.close()
    android.util.Log.i("StarshineSound", "Didgeridoo: ${spent / 10_000_000 / seconds}% of one core")
}

/** The generators. No Android dependencies. Output is roughly -1..1 before [limit]. */
class AmbientSynth(private val mode: String, private val rate: Int, seed: Int) {
    companion object {
        /** Gentle limiter: transparent at normal levels, rounds off rare peaks instead of clipping them. */
        fun limit(x: Float): Float { val a = abs(x); return if (a < 0.6f) x else (if (x > 0) 1f else -1f) * (0.6f + 0.4f * (1f - exp(-(a - 0.6f) / 0.4f))) }
        private const val TAB = 4096
        private val SINE = FloatArray(TAB + 1) { sin(2.0 * PI * it / TAB).toFloat() }
    }

    private var state = seed or 1
    private fun white(): Float { state = state xor (state shl 13); state = state xor (state ushr 17); state = state xor (state shl 5); return state * 4.656613e-10f }
    private fun uni() = white() * 0.5f + 0.5f
    private fun sine(ph: Double): Float { val p = (ph - Math.floor(ph)) * TAB; val i = p.toInt(); return SINE[i] + (SINE[i + 1] - SINE[i]) * (p - i).toFloat() }

    private inner class Pink { private var a = 0f; private var b = 0f; private var c = 0f
        fun next(): Float { val w = white(); a = 0.99765f * a + w * 0.0990460f; b = 0.96300f * b + w * 0.2965164f; c = 0.57000f * c + w * 1.0526913f; return (a + b + c + w * 0.1848f) * 0.22f } }
    private class Lp { var y = 0f; fun next(x: Float, k: Float): Float { y += k * (x - y); return y } }
    private fun coef(hz: Float) = (1f - exp(-2f * PI.toFloat() * hz / rate)).coerceIn(0f, 1f)

    /** Band-pass with settable centre. */
    private inner class Band(hz: Float, q: Float) { private var s1 = 0f; private var s2 = 0f; private var f = 0f; private var d = 1f / q
        init { set(hz) }
        fun set(hz: Float) { f = (2f * sin(PI.toFloat() * hz.coerceAtMost(rate * 0.2f) / rate)) }
        fun next(x: Float): Float { s2 += f * s1; val h = x - s2 - d * s1; s1 += f * h; return s1 } }

    /** Slowly wandering value in 0..1, new target every [sec] seconds. */
    private inner class Drift(private val sec: Float) { private var v = uni(); private var target = uni(); private var left = 0
        fun next(): Float { if (left-- <= 0) { target = uni(); left = (rate * sec * (0.5f + uni())).toInt() }; v += (target - v) * (1f / (rate * sec)); return v } }

    /** Small stereo room: parallel combs + allpass, different lengths per side. Mix is chosen by the caller. */
    private inner class Room(size: Float, private val feedback: Float, damp: Float) {
        private val lens = intArrayOf(1557, 1617, 1491, 1422, 1277, 1356, 1188, 1116)
        private val lines = Array(8) { FloatArray((lens[it] * size).toInt().coerceAtLeast(16)) }
        private val pos = IntArray(8); private val st = FloatArray(8); private val d = damp
        private val ap = Array(2) { FloatArray((if (it == 0) 556 else 579)) }; private val apPos = IntArray(2)
        var outL = 0f; var outR = 0f
        fun process(x: Float) {
            var a = 0f; var b = 0f
            for (i in 0 until 8) {
                val line = lines[i]; val o = line[pos[i]]
                st[i] += d * (o - st[i])
                line[pos[i]] = x + st[i] * feedback
                val next = pos[i] + 1; pos[i] = if (next == line.size) 0 else next
                if (i % 2 == 0) a += o else b += o
            }
            outL = all(0, a * 0.25f); outR = all(1, b * 0.25f)
        }
        private fun all(i: Int, x: Float): Float { val l = ap[i]; val o = l[apPos[i]]; val y = -x + o; l[apPos[i]] = x + o * 0.5f; val next = apPos[i] + 1; apPos[i] = if (next == l.size) 0 else next; return y }
    }

    fun fill(l: FloatArray, r: FloatArray, n: Int) {
        when (mode) {
            "Rain" -> rain(l, r, n)
            "Ocean" -> ocean(l, r, n)
            "Singing bowl", "Tibetan bells", "Xylophone", "Chimes", "Temple gong", "Tanpura", "Handpan", "Kalimba", "Frame drum" -> instrument(l, r, n)
            else -> { l.fill(0f, 0, n); r.fill(0f, 0, n) }
        }
    }

    /* ---------- Rain: a soft wash, plus thousands of separate drops at random places ---------- */

    private class Drop { var left = 0; var amp = 0f; var decay = 0f; var panL = 0f; var panR = 0f; var band = 0 }
    private val pinkA = Pink(); private val pinkB = Pink()
    private val washHpL = Lp(); private val washHpR = Lp(); private val washLpL = Lp(); private val washLpR = Lp(); private val roofL = Lp(); private val roofR = Lp()
    private val gust = Drift(9f); private val densityDrift = Drift(14f)
    private val drops = Array(32) { Drop() }
    private val dropBands by lazy { Array(32) { Band(1500f + 5500f * uni() * uni(), 1.6f) } }
    private val rainRoom by lazy { Room(0.55f, 0.72f, 0.45f) }

    private fun rain(l: FloatArray, r: FloatArray, n: Int) {
        val hp = coef(350f); val lp = coef(6500f); val lowK = coef(240f)
        val bands = dropBands; val room = rainRoom
        for (i in 0 until n) {
            val g = gust.next(); val density = 0.45f + 0.55f * densityDrift.next()
            val a = pinkA.next(); val b = pinkB.next()
            val wl = washLpL.next(a - washHpL.next(a, hp), lp); val wr = washLpR.next(b - washHpR.next(b, hp), lp)
            val level = 0.20f + 0.10f * g
            var sl = wl * level + roofL.next(a, lowK) * 0.16f
            var sr = wr * level + roofR.next(b, lowK) * 0.16f
            if (uni() < 95f * density / rate) {
                drops.firstOrNull { it.left <= 0 }?.let { d ->
                    val big = uni()
                    d.amp = 0.03f + 0.55f * big * big * big * big
                    val ms = 2.5f + 20f * uni() * uni()
                    d.left = (rate * ms * 0.006f).toInt(); d.decay = exp(-1f / (rate * ms * 0.001f))
                    val p = uni(); d.panL = sqrt(1f - p); d.panR = sqrt(p)
                    d.band = (uni() * 31.99f).toInt()
                }
            }
            var wet = 0f
            for (d in drops) if (d.left > 0) {
                d.left--; d.amp *= d.decay
                val s = bands[d.band].next(white()) * d.amp
                sl += s * d.panL; sr += s * d.panR; wet += s
            }
            room.process(wet * 0.5f)
            l[i] = (sl + room.outL * 0.35f) * 2.2f; r[i] = (sr + room.outR * 0.35f) * 2.2f
        }
    }

    /* ---------- Ocean: overlapping waves that build, break and wash back, each from its own place ---------- */

    private inner class Wave {
        var on = false; var k = 0f; var foam = 0f; var gainL = 0f; var gainR = 0f
        var t = 0f; var length = 8f; var size = 1f; var pan = 0.5f; var from = 0f; val lowL = Lp(); val lowR = Lp(); val hiL = Lp(); val hiR = Lp(); val nL = Pink(); val nR = Pink()
        fun restart(delay: Float) { t = -delay; length = 7f + 8f * uni(); size = 0.55f + 0.45f * uni(); from = uni(); pan = from; }
    }
    private val waves by lazy { Array(4) { i -> Wave().also { it.restart(i * 4.5f + uni() * 2f) } } }
    private val surfL = Lp(); private val surfR = Lp(); private val surfN = Pink(); private val surfM = Pink(); private val surfDrift = Drift(11f)

    private fun ocean(l: FloatArray, r: FloatArray, n: Int) {
        val dt = 32f / rate
        val surfK = coef(170f)
        val hiK = coef(2600f)
        val list = waves
        for (i in 0 until n) {
            var sl = surfL.next(surfN.next(), surfK) * (0.65f + 0.35f * surfDrift.next()) * 0.9f
            var sr = surfR.next(surfM.next(), surfK) * (0.65f + 0.35f * surfDrift.next()) * 0.9f
            // each wave's shape moves slowly: worked out every 32 samples, about every 0.7 ms, instead of on every sample
            if (i and 31 == 0) for (w in list) {
                w.t += dt
                w.on = false
                if (w.t <= 0f) continue
                val u = w.t / w.length
                if (u >= 1f) { w.restart(uni() * 3f); continue }
                // quick rise to the break at 30%, then a long fading wash
                val env = if (u < 0.3f) (u / 0.3f).pow(2.2f) else ((1f - u) / 0.7f).pow(1.6f)
                val bright = if (u < 0.3f) (u / 0.3f).pow(3f) else ((1f - u) / 0.7f).pow(2.5f)
                w.k = coef(220f + 3800f * bright * w.size)
                w.foam = bright * bright * 0.35f
                val p = w.from + (0.5f - w.from) * u   // each wave moves toward the middle as it washes in
                val g = env * w.size * 0.95f
                w.gainL = g * sqrt(1f - p); w.gainR = g * sqrt(p)
                w.on = true
            }
            for (j in list.indices) {
                val w = list[j]
                if (!w.on) continue
                val a = w.nL.next(); val b = w.nR.next()
                val bl = w.lowL.next(a, w.k); val br = w.lowR.next(b, w.k)
                val foamL = (a - w.hiL.next(a, hiK)) * w.foam
                val foamR = (b - w.hiR.next(b, hiK)) * w.foam
                sl += (bl + foamL) * w.gainL; sr += (br + foamR) * w.gainR
            }
            l[i] = sl * 0.75f; r[i] = sr * 0.75f
        }
    }

    /* ---------- Struck instruments: each strike rings a set of decaying resonators, in a small room ---------- */

    /** Set by the layer. */
    @Volatile var pitch = 220f
    @Volatile var every = 6f
    /** How it's played: octave shift, ring length, overtones, room, timing, strike strength, stereo width, detune. */
    @Volatile var octave = 0f
    @Volatile var sustain = 1f
    @Volatile var bright = 1f
    @Volatile var space = 1f
    @Volatile var loose = 0.5f
    @Volatile var vary = 0.4f
    @Volatile var spread = 1f
    @Volatile var detune = 0f
    /** The note of the strike in progress, so overtones (anything well above it) can be made brighter or darker. */
    private var strikeBase = 220f
    private class Res(w: Float, r: Float, amp: Float, val l: Float, val p: Float) {
        val a1 = 2f * r * kotlin.math.cos(w); val a2 = -r * r
        var y1 = 0f; var y2 = -amp * sin(w)
        var peak = amp
        fun next(): Float { val y = a1 * y1 + a2 * y2; y2 = y1; y1 = y; return y }
    }
    private val rings = ArrayList<Res>()
    private var nextStrike = 0f
    private val bellRoom by lazy { Room(1.0f, 0.74f, 0.35f) }
    private var tanpuraStep = 0
    private var drumStep = 0
    private val HANDPAN = floatArrayOf(1f, 1.2f, 1.333f, 1.5f, 1.8f, 2f, 2.4f)
    private val SCALE = floatArrayOf(1f, 1.125f, 1.25f, 1.5f, 1.667f, 2f, 2.25f, 2.5f)

    private fun ring(hz: Float, seconds: Float, amp: Float, pan: Float) {
        if (hz >= rate * 0.45f || rings.size > 160) return
        val a = if (hz > strikeBase * 1.6f) amp * bright else amp
        // decay to about -60 dB over the given time
        rings.add(Res(2f * PI.toFloat() * hz / rate, exp(-6.9f / ((seconds * sustain).coerceAtLeast(0.02f) * rate)), a, sqrt(1f - pan), sqrt(pan)))
    }

    private fun strike() {
        val pan = (0.5f + (uni() - 0.5f) * 0.6f * spread).coerceIn(0f, 1f); val hit = 1f - vary.coerceIn(0f, 1f) * uni()
        // octave and detune move every note of this strike together
        val pitch = this.pitch * Math.pow(2.0, octave.toDouble()).toFloat() * (1f + detune * 0.03f * (uni() - 0.5f))
        strikeBase = pitch
        when (mode) {
            "Singing bowl" -> {   // close pairs of partials beat slowly, which is what makes a bowl sing
                val f = pitch * (0.995f + 0.01f * uni())
                ring(f, 26f, 0.34f * hit, pan); ring(f + 0.35f, 24f, 0.30f * hit, pan)
                ring(f * 2.71f, 15f, 0.13f * hit, pan); ring(f * 2.71f + 0.6f, 14f, 0.10f * hit, pan)
                ring(f * 5.15f, 7f, 0.05f * hit, pan); ring(f * 8.2f, 3f, 0.02f * hit, pan)
            }
            "Tibetan bells" -> {  // two small cymbals: bright, slightly apart
                val f = pitch * 8f * (0.99f + 0.02f * uni())
                ring(f, 7f, 0.30f * hit, pan); ring(f * 1.006f, 7f, 0.26f * hit, pan)
                ring(f * 2.76f, 4f, 0.10f * hit, pan); ring(f * 5.4f, 2f, 0.05f * hit, pan)
            }
            "Xylophone" -> {      // wooden bars: a short note, a tuned overtone and a soft mallet tick
                val f = pitch * 2f * SCALE[(uni() * 7.99f).toInt()]
                ring(f, 0.9f, 0.60f * hit, pan); ring(f * 3f, 0.35f, 0.18f * hit, pan); ring(f * 6.2f, 0.08f, 0.10f * hit, pan)
            }
            "Temple gong" -> {    // large gong: many inharmonic partials, with the low ones lasting longest
                val f = pitch
                ring(f, 34f, 0.28f * hit, pan); ring(f * 1.003f, 30f, 0.2f * hit, pan); ring(f * 1.52f, 22f, 0.14f * hit, pan)
                ring(f * 2.27f, 16f, 0.11f * hit, pan); ring(f * 2.76f, 12f, 0.09f * hit, pan); ring(f * 3.53f, 8f, 0.06f * hit, pan); ring(f * 4.41f, 5f, 0.04f * hit, pan)
            }
            "Tanpura" -> {        // a drone made of plucked strings in the traditional cycle: a fifth, then the home note three times
                val note = when (tanpuraStep++ % 4) { 0 -> 0.75f; 3 -> 0.5f; else -> 1f }
                val f = pitch * note
                for (h in 1..12) ring(f * h + 0.05f * h, 7f - h * 0.35f, 0.16f / (1f + h * 0.32f), 0.35f + 0.3f * uni())
            }
            "Handpan" -> {        // steel notes in a minor pentatonic, with soft octave and compound-fifth overtones
                val f = pitch * HANDPAN[(uni() * 6.99f).toInt()]
                ring(f, 4.5f, 0.42f * hit, pan); ring(f + 0.4f, 4f, 0.14f * hit, pan); ring(f * 2f, 2.8f, 0.2f * hit, pan); ring(f * 3f, 1.5f, 0.09f * hit, pan)
            }
            "Kalimba" -> {        // plucked metal tines: a clear note with a brief high overtone
                val f = pitch * SCALE[(uni() * 7.99f).toInt()]
                ring(f, 1.7f, 0.55f * hit, pan); ring(f * 6.27f, 0.16f, 0.16f * hit, pan); ring(f * 2f, 0.6f, 0.08f * hit, pan)
            }
            "Frame drum" -> {     // a low skin hit, with an accent on the first of every four
                val accent = if (drumStep++ % 4 == 0) 1f else 0.62f
                val f = pitch * (0.97f + 0.06f * uni())
                ring(f, 0.55f, 0.75f * accent, pan); ring(f * 1.59f, 0.28f, 0.32f * accent, pan); ring(f * 2.14f, 0.16f, 0.16f * accent, pan); ring(f * 3.3f, 0.06f, 0.08f * accent, pan)
            }
            else -> {             // wind chimes: metal tubes in a loose scale
                val f = pitch * 4f * SCALE[(uni() * 7.99f).toInt()]
                ring(f, 5f, 0.33f * hit, pan); ring(f * 2.76f, 2.6f, 0.15f * hit, pan); ring(f * 5.4f, 1.2f, 0.06f * hit, pan)
            }
        }
    }

    private fun instrument(l: FloatArray, r: FloatArray, n: Int) {
        nextStrike -= n.toFloat() / rate
        // timing feel: 0 = like a metronome, 1 = free
        if (nextStrike <= 0f) { strike(); val k = loose.coerceIn(0f, 1f); nextStrike = every.coerceAtLeast(0.2f) * (1f - 0.8f * k + 1.6f * k * uni()) }
        val room = bellRoom; val count = rings.size
        val wet = 0.28f * space
        for (i in 0 until n) {
            var a = 0f; var b = 0f
            for (k in 0 until count) { val g = rings[k]; val y = g.next(); a += y * g.l; b += y * g.p }
            room.process((a + b) * 0.25f)
            l[i] = a + room.outL * wet; r[i] = b + room.outR * wet
        }
        if (rings.size > 8 || nextStrike < 0.1f) rings.removeAll { kotlin.math.abs(it.y1) + kotlin.math.abs(it.y2) < 0.00003f && it.peak > 0f }
    }
}
