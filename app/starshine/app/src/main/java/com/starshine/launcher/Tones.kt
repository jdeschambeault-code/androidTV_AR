package com.starshine.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * Frequency generator (stereo): pure tones, binaural beats, isochronic pulses, sweeps, harmonics, noise bed.
 *  - Left ear  = carrier - beat/2, right ear = carrier + beat/2  (binaural beat = "beat" Hz; use headphones/glasses speakers)
 *  - Isochronic = volume pulsing at `iso` Hz with `isoDepth`
 *  - Sweep = beat glides from `beat` to `beatEnd` over `sweepMin` minutes (0 = off)
 * Keeps playing when you leave Starshine, until stopped.
 */
data class Tone(
    val name: String = "Custom",
    val carrier: Float = 200f,      // Hz
    val beat: Float = 10f,          // Hz (binaural difference L/R)
    val beatEnd: Float = 10f,       // Hz (sweep target)
    val sweepMin: Float = 0f,       // minutes, 0 = no sweep
    val wave: Int = 0,              // 0 sine, 1 triangle, 2 square, 3 saw
    val iso: Float = 0f,            // Hz isochronic pulse, 0 = off
    val isoDepth: Float = 0.8f,     // 0..1
    val harmonic: Float = 0f,       // 0..1 level of 2nd harmonic
    val noise: Float = 0f,          // 0..1 pink-noise bed
    val balance: Float = 0f,        // -1 left .. +1 right
    val volume: Float = 0.25f,      // 0..1
    val fadeSec: Float = 3f,
    val didge: Boolean = false,     // didgeridoo layer on/off
    val didgePitch: Float = 65f,    // Hz, the drone note
    val didgeLevel: Float = 0.6f,   // 0..1
    val didgeMouth: Float = 0.5f,   // 0..1 how far the mouth shape moves (the "wah")
    val didgeRate: Float = 0.35f,   // Hz, speed of that movement
    val didgeBreath: Float = 0.35f, // 0..1 breath noise and circular-breathing dips
    val toneOff: Boolean = false    // silence the carrier and hear only the noise and didgeridoo
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("carrier", carrier.toDouble()).put("beat", beat.toDouble())
        .put("beatEnd", beatEnd.toDouble()).put("sweepMin", sweepMin.toDouble()).put("wave", wave).put("iso", iso.toDouble())
        .put("isoDepth", isoDepth.toDouble()).put("harmonic", harmonic.toDouble()).put("noise", noise.toDouble())
        .put("balance", balance.toDouble()).put("volume", volume.toDouble()).put("fadeSec", fadeSec.toDouble())
        .put("didge", didge).put("didgePitch", didgePitch.toDouble()).put("didgeLevel", didgeLevel.toDouble()).put("didgeMouth", didgeMouth.toDouble())
        .put("didgeRate", didgeRate.toDouble()).put("didgeBreath", didgeBreath.toDouble()).put("toneOff", toneOff)

    companion object {
        val WAVES = listOf("Sine", "Triangle", "Square", "Saw")
        fun fromJson(o: JSONObject) = Tone(o.optString("name", "Custom"), o.optDouble("carrier", 200.0).toFloat(), o.optDouble("beat", 10.0).toFloat(),
            o.optDouble("beatEnd", o.optDouble("beat", 10.0)).toFloat(), o.optDouble("sweepMin", 0.0).toFloat(), o.optInt("wave", 0),
            o.optDouble("iso", 0.0).toFloat(), o.optDouble("isoDepth", 0.8).toFloat(), o.optDouble("harmonic", 0.0).toFloat(),
            o.optDouble("noise", 0.0).toFloat(), o.optDouble("balance", 0.0).toFloat(), o.optDouble("volume", 0.25).toFloat(), o.optDouble("fadeSec", 3.0).toFloat(),
            o.optBoolean("didge", false), o.optDouble("didgePitch", 65.0).toFloat(), o.optDouble("didgeLevel", 0.6).toFloat(), o.optDouble("didgeMouth", 0.5).toFloat(),
            o.optDouble("didgeRate", 0.35).toFloat(), o.optDouble("didgeBreath", 0.35).toFloat(), o.optBoolean("toneOff", false))

        val BUILT_IN = listOf(
            Tone("Schumann 7.83 Hz", 200f, 7.83f, 7.83f),
            Tone("Alpha relax 10 Hz", 200f, 10f, 10f, noise = 0.1f),
            Tone("Theta dream 6 Hz", 180f, 6f, 6f, noise = 0.15f),
            Tone("Delta sleep 2 Hz", 150f, 2f, 2f, noise = 0.2f, volume = 0.2f),
            Tone("Beta focus 15 Hz", 220f, 15f, 15f),
            Tone("Gamma 40 Hz", 240f, 40f, 40f, iso = 40f, isoDepth = 0.5f),
            Tone("Wind-down 14→4 Hz", 200f, 14f, 4f, sweepMin = 20f, noise = 0.12f),
            Tone("432 Hz pure", 432f, 0f, 0f, harmonic = 0.15f),
            Tone("528 Hz pure", 528f, 0f, 0f, harmonic = 0.15f),
            Tone("174 Hz deep", 174f, 0f, 0f, iso = 2f, isoDepth = 0.3f),
            Tone("Didgeridoo drone", 200f, 0f, 0f, didge = true, toneOff = true, volume = 0.4f)
        )
    }
}

/**
 * A small physical model: a buzzing-lip pulse feeds a pipe closed at the lips (so odd harmonics ring, as on a real didgeridoo),
 * then moving mouth resonances shape it. Circular breathing gives a dip and a sniff every several seconds.
 */
class Didgeridoo(private val rate: Int) {
    var l = 0f; private set
    var r = 0f; private set
    private val line = FloatArray(4096); private var pos = 0; private var loop = 0f
    private var phase = 0.0; private var wah = 0.0; private var clock = 0.0
    private var s1 = 0f; private var s2 = 0f; private var f1 = 0.1f
    private var t1 = 0f; private var t2 = 0f; private var f2 = 0.2f
    private var n1 = 0f; private var n2 = 0f
    private var count = 0; private var seed = 918273
    private var breathT = 3f; private var breathEvery = 7f
    private var mouth = 0f

    private fun noise(): Float { seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5); return seed * 4.656613e-10f }

    fun next(t: Tone) {
        val dt = 1.0 / rate
        clock += dt
        // a player never holds a perfectly steady note
        val hz = t.didgePitch.coerceIn(30f, 160f) * (1f + 0.006f * sin(2 * PI * 0.13 * clock).toFloat() + 0.003f * sin(2 * PI * 0.37 * clock).toFloat())
        phase += hz * dt; if (phase >= 1.0) phase -= 1.0
        val open = 0.42f
        val src = if (phase < open) { val s = sin(PI * phase / open).toFloat(); s * s * s - 0.18f } else -0.18f
        // the pipe: reflection after half a period at the note, inverted, which leaves the odd harmonics
        val delay = (rate / (2f * hz)).coerceIn(2f, 4000f)
        val rp = pos - delay; val i0 = Math.floorMod(rp.toInt() - (if (rp < 0f && rp != rp.toInt().toFloat()) 1 else 0), line.size)
        val frac = rp - kotlin.math.floor(rp)
        val delayed = line[i0] * (1f - frac) + line[(i0 + 1) % line.size] * frac
        loop += 0.55f * (delayed - loop)
        val y = src - 0.82f * loop
        line[pos] = y; pos = (pos + 1) % line.size
        // the mouth: two resonances that move together with the tongue
        wah += t.didgeRate.coerceIn(0.02f, 6f) * dt; if (wah >= 1.0) wah -= 1.0
        if (count++ and 31 == 0) {
            val w = 0.5f + 0.5f * sin(2 * PI * wah).toFloat()
            val slow = 0.5f + 0.5f * sin(2 * PI * 0.083 * clock).toFloat()
            mouth = (0.65f * w + 0.35f * slow) * t.didgeMouth.coerceIn(0f, 1f)
            f1 = 2f * sin(PI * (230f + 560f * mouth) / rate).toFloat()
            f2 = 2f * sin(PI * (1000f + 1100f * mouth) / rate).toFloat()
        }
        s2 += f1 * s1; val h1 = y - s2 - 0.33f * s1; s1 += f1 * h1
        t2 += f2 * t1; val h2 = y - t2 - 0.4f * t1; t1 += f2 * h2
        // circular breathing: the drone dips while air is snatched through the nose
        breathT -= dt.toFloat()
        if (breathT < -0.32f) { breathEvery = 5f + 4f * (noise() * 0.5f + 0.5f); breathT = breathEvery }
        val br = t.didgeBreath.coerceIn(0f, 1f)
        val inhale = if (breathT < 0f) sin(PI * (-breathT / 0.32f)).toFloat() else 0f
        val drive = 1f - 0.55f * br * inhale
        val w = noise()
        n1 += 0.16f * (w - n1); n2 += 0.03f * (n1 - n2)
        val airy = (n1 - n2) * (0.05f + 0.1f * mouth) * br * drive + (n1 - n2) * inhale * br * 0.55f
        val m = (y * 0.11f + s1 * 0.30f + t1 * 0.22f) * drive
        l = (m + t1 * 0.05f + airy) * 0.9f
        r = (m - t1 * 0.05f + airy * 0.8f) * 0.9f
    }
}

object Tones {
    @Volatile var params = Tone()
    @Volatile var playing = false
        private set
    private var thread: Thread? = null

    fun play(t: Tone) { params = t; if (!playing) start() }
    fun update(t: Tone) { params = t }
    fun stop() { playing = false; thread?.join(400); thread = null }

    private fun wave(type: Int, phase: Double): Float {
        val p = phase - floor(phase)   // 0..1
        return when (type) {
            1 -> (4 * abs(p - 0.5) - 1).toFloat()
            2 -> if (p < 0.5) 0.8f else -0.8f
            3 -> (2 * p - 1).toFloat() * 0.8f
            else -> sin(2 * PI * p).toFloat()
        }
    }

    private fun start() {
        playing = true
        thread = Thread {
            val rate = 44100
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(min, 16384)).build()
            track.play()
            val buf = ShortArray(2048)   // interleaved L,R
            var pl = 0.0; var pr = 0.0; var piso = 0.0; var n = 0L
            var b0 = 0f; var b1 = 0f; var b2 = 0f; val rnd = Random(3)
            var gain = 0f; val didge = Didgeridoo(rate); var carrierGain = 1f
            while (playing) {
                val t = params
                val nyq = rate / 2f - 100f
                for (i in 0 until buf.size / 2) {
                    val sec = n.toDouble() / rate
                    val beat = if (t.sweepMin > 0f) {
                        val k = (sec / (t.sweepMin * 60.0)).coerceIn(0.0, 1.0); (t.beat + (t.beatEnd - t.beat) * k).toFloat()
                    } else t.beat
                    val fl = (t.carrier - beat / 2f).coerceIn(1f, nyq); val fr = (t.carrier + beat / 2f).coerceIn(1f, nyq)
                    pl += fl / rate; pr += fr / rate
                    carrierGain += ((if (t.toneOff) 0f else 1f) - carrierGain) * 0.0005f   // no click when switching
                    var l = (wave(t.wave, pl) + t.harmonic * wave(t.wave, pl * 2)) * carrierGain
                    var r = (wave(t.wave, pr) + t.harmonic * wave(t.wave, pr * 2)) * carrierGain
                    if (t.iso > 0f) { piso += t.iso / rate; val g = 1f - t.isoDepth * (0.5f - 0.5f * sin(2 * PI * piso).toFloat()); l *= g; r *= g }
                    if (t.noise > 0f) {
                        val w = rnd.nextFloat() * 2f - 1f
                        b0 = 0.99765f * b0 + w * 0.0990460f; b1 = 0.96300f * b1 + w * 0.2965164f; b2 = 0.57000f * b2 + w * 1.0526913f
                        val pink = (b0 + b1 + b2 + w * 0.1848f) * 0.18f
                        l += pink * t.noise * 2f; r += pink * t.noise * 2f
                    }
                    if (t.didge) { didge.next(t); l += didge.l * t.didgeLevel * 2f; r += didge.r * t.didgeLevel * 2f }
                    val target = t.volume / (1f + t.harmonic)
                    gain += (target - gain) * (1f / (rate * t.fadeSec.coerceAtLeast(0.05f)))   // smooth fades + parameter changes
                    val lb = if (t.balance > 0f) 1f - t.balance else 1f
                    val rb = if (t.balance < 0f) 1f + t.balance else 1f
                    buf[2 * i] = (l * gain * lb * 32767f).coerceIn(-32767f, 32767f).toInt().toShort()
                    buf[2 * i + 1] = (r * gain * rb * 32767f).coerceIn(-32767f, 32767f).toInt().toShort()
                    n++
                }
                track.write(buf, 0, buf.size)
            }
            runCatching { track.stop(); track.release() }
        }.apply { isDaemon = true; start() }
    }

    /* ---- preset storage ---- */
    fun loadPresets(ctx: Context): List<Tone> {
        val raw = ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE).getString("tonePresets", null) ?: return Tone.BUILT_IN
        return runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { Tone.fromJson(a.getJSONObject(it)) } } }.getOrDefault(Tone.BUILT_IN)
    }
    fun savePresets(ctx: Context, list: List<Tone>) {
        val a = JSONArray(); list.forEach { a.put(it.toJson()) }
        ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE).edit().putString("tonePresets", a.toString()).apply()
    }
}
