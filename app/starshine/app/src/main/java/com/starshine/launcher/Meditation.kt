package com.starshine.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Visual Meditation: a journey is a timeline of steps, each a scene and an audio stack, blending into the next.
 * Audio stacks are layers like scenes are: tone, noise, didgeridoo, rain, ocean, each changing slowly over time.
 */

/* ---------------- audio layers ---------------- */

/** The classic frequency generator (wave shapes, isochronic pulses, sweeps, noise bed, didgeridoo) as one layer. */
const val GENERATOR_KIND = "Frequency generator"
/** A drum and bass beat, made live: for testing sound reactions, and for moods with a pulse. */
const val DNB_KIND = "DnB beat"
private val KICKS = intArrayOf(0, 10); private val SNARES = intArrayOf(4, 12); private val GHOSTS = intArrayOf(7, 14)
val INSTRUMENT_KINDS = listOf("Singing bowl", "Tibetan bells", "Xylophone", "Chimes", "Temple gong", "Tanpura", "Handpan", "Kalimba", "Frame drum")
val AUDIO_KINDS = listOf("Tone", GENERATOR_KIND, "Noise", "Didgeridoo") + INSTRUMENT_KINDS + listOf(DNB_KIND, "Rain", "Ocean")

private fun mood(name: String, vararg layers: Pair<String, Map<String, Float>>) =
    AudioStack(name, layers.mapIndexed { i, (k, v) -> AudioLayer(i + 1, k, v) })
private fun bowl(pitch: Float, every: Float, level: Float, pan: Float = 0f) = "Singing bowl" to mapOf("pitch" to pitch, "every" to every, "level" to level, "pan" to pan)

/** Ready-made layered moods. Starting points to listen to and rebuild: everything is synthesized live. */
val MEDITATIVE_MOODS = listOf(
    mood("Sound bath", bowl(146f, 14f, 0.6f, -0.35f), bowl(220f, 19f, 0.5f, 0.35f), bowl(330f, 23f, 0.35f), "Noise" to mapOf("level" to 0.05f)),
    mood("Deep stillness", "Tone" to mapOf("carrier" to 136.1f, "beat" to 4f, "level" to 0.3f, "wander" to 0.5f), bowl(110f, 30f, 0.55f), "Noise" to mapOf("level" to 0.07f)),
    mood("Theta drift", "Tone" to mapOf("carrier" to 180f, "carrierTo" to 174f, "beat" to 6f, "beatTo" to 4f, "minutes" to 20f, "level" to 0.3f), "Ocean" to mapOf("level" to 0.5f)),
    mood("Rain sanctuary", "Rain" to mapOf("level" to 0.6f), bowl(196f, 25f, 0.45f), "Chimes" to mapOf("pitch" to 196f, "every" to 40f, "level" to 0.22f)),
    mood("Ocean breath", "Ocean" to mapOf("level" to 0.75f), "Tone" to mapOf("carrier" to 110f, "beat" to 0f, "level" to 0.14f, "wander" to 0.6f), "Temple gong" to mapOf("pitch" to 73f, "every" to 45f, "level" to 0.4f)),
    mood("Alpha calm", "Tone" to mapOf("carrier" to 200f, "beat" to 10f, "level" to 0.28f), "Rain" to mapOf("level" to 0.28f), "Chimes" to mapOf("pitch" to 220f, "every" to 35f, "level" to 0.2f))
)
val WORLD_MOODS = listOf(
    mood("Himalayan temple", "Temple gong" to mapOf("pitch" to 65f, "every" to 38f, "level" to 0.5f), bowl(174f, 17f, 0.55f), "Tibetan bells" to mapOf("pitch" to 220f, "every" to 27f, "level" to 0.3f), "Tone" to mapOf("carrier" to 87f, "beat" to 0f, "level" to 0.16f)),
    mood("Outback dreaming", "Didgeridoo" to mapOf("note" to 65f, "level" to 0.6f, "mouth" to 0.6f, "breath" to 0.45f), "Frame drum" to mapOf("pitch" to 82f, "every" to 2.4f, "level" to 0.28f)),
    mood("Tanpura evening", "Tanpura" to mapOf("pitch" to 138f, "every" to 1.6f, "level" to 0.7f), "Noise" to mapOf("level" to 0.03f)),
    mood("Thumb piano village", "Kalimba" to mapOf("pitch" to 220f, "every" to 1.5f, "level" to 0.55f), "Frame drum" to mapOf("pitch" to 95f, "every" to 2f, "level" to 0.3f), "Rain" to mapOf("level" to 0.1f)),
    mood("Zen garden", "Chimes" to mapOf("pitch" to 196f, "every" to 16f, "level" to 0.35f), bowl(261f, 28f, 0.35f), "Ocean" to mapOf("level" to 0.14f)),
    mood("Island gamelan", "Xylophone" to mapOf("pitch" to 196f, "every" to 1.4f, "level" to 0.5f), "Temple gong" to mapOf("pitch" to 98f, "every" to 18f, "level" to 0.4f), "Kalimba" to mapOf("pitch" to 294f, "every" to 3.3f, "level" to 0.24f)),
    mood("Handpan journey", "Handpan" to mapOf("pitch" to 147f, "every" to 2.2f, "level" to 0.6f), "Frame drum" to mapOf("pitch" to 73f, "every" to 3.4f, "level" to 0.22f), "Noise" to mapOf("level" to 0.04f)),
    mood("Fire circle", "Frame drum" to mapOf("pitch" to 78f, "every" to 1f, "level" to 0.55f), "Didgeridoo" to mapOf("note" to 58f, "level" to 0.3f, "breath" to 0.4f))
)

data class AudioLayer(val id: Int, val kind: String, val v: Map<String, Float> = emptyMap()) {
    operator fun get(k: String): Float = v[k] ?: DEFAULTS[k] ?: 0f
    fun set(k: String, x: Float) = copy(v = v + (k to x))
    val on get() = this["on"] > 0.5f
    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("v", JSONObject().also { o -> v.forEach { (k, x) -> o.put(k, x.toDouble()) } })
    companion object {
        val DEFAULTS = mapOf(
            "on" to 1f, "level" to 0.5f, "pan" to 0f, "wander" to 0.3f, "minutes" to 5f,
            "carrier" to 200f, "carrierTo" to -1f, "beat" to 10f, "beatTo" to -1f, "harmonic" to 0f,
            "follow" to 0f, "pitch" to 220f, "every" to 8f, "note" to 65f, "mouth" to 0.5f, "mouthRate" to 0.35f, "breath" to 0.35f,
            // frequency generator
            "beatEnd" to -1f, "sweep" to 0f, "wave" to 0f, "iso" to 0f, "isoDepth" to 0.8f, "noise" to 0f, "didge" to 0f, "didgeLevel" to 0.6f, "preset" to -1f, "toneOff" to 0f,
            // instruments: how they're played, beyond pitch and how often
            "octave" to 0f, "sustain" to 1f, "bright" to 1f, "space" to 1f, "loose" to 0.5f, "vary" to 0.4f, "spread" to 1f, "detune" to 0f
        )
        fun fromJson(o: JSONObject): AudioLayer {
            val vo = o.optJSONObject("v") ?: JSONObject(); val m = HashMap<String, Float>()
            vo.keys().forEach { m[it] = vo.getDouble(it).toFloat() }
            return AudioLayer(o.getInt("id"), o.getString("kind"), m)
        }
    }
}

data class AudioStack(val name: String, val layers: List<AudioLayer>) {
    val nextId get() = (layers.maxOfOrNull { it.id } ?: 0) + 1
    fun toJson(): JSONObject = JSONObject().put("name", name).put("layers", JSONArray().also { a -> layers.forEach { a.put(it.toJson()) } })
    companion object {
        fun fromJson(o: JSONObject): AudioStack { val a = o.getJSONArray("layers"); return AudioStack(o.getString("name"), (0 until a.length()).map { AudioLayer.fromJson(a.getJSONObject(it)) }) }
        /** A frequency preset as layers, so existing presets carry over. */
        fun fromTone(t: Tone): AudioStack {
            val l = ArrayList<AudioLayer>()
            if (!t.toneOff) l.add(AudioLayer(1, "Tone", mapOf("carrier" to t.carrier, "beat" to t.beat, "beatTo" to (if (t.sweepMin > 0f) t.beatEnd else -1f),
                "minutes" to (if (t.sweepMin > 0f) t.sweepMin else 5f), "harmonic" to t.harmonic, "level" to (t.volume * 2f).coerceAtMost(1f))))
            if (t.noise > 0f) l.add(AudioLayer(2, "Noise", mapOf("level" to t.noise)))
            if (t.didge) l.add(AudioLayer(3, "Didgeridoo", mapOf("note" to t.didgePitch, "level" to t.didgeLevel, "mouth" to t.didgeMouth, "mouthRate" to t.didgeRate, "breath" to t.didgeBreath)))
            return AudioStack(t.name, l)
        }
    }
}

/** Mixes the layers of a stack, cross-fading when the stack changes. All layers are generated live. */
object AudioMix {
    private const val RATE = 44100
    private class Voice(val key: String, @Volatile var layer: AudioLayer) {
        var gain = 0f; @Volatile var target = 1f; var clock = 0.0
        var phaseL = 0; var phaseR = 0; var walk = 0f; var walkV = 0f
        var didge: Didgeridoo? = null; var synth: AmbientSynth? = null
        var b0 = 0f; var b1 = 0f; var b2 = 0f; var c0 = 0f; var c1 = 0f; var c2 = 0f
        var gl = 0.0; var gr = 0.0; var gIso = 0.0; var gOn = 1f
        var kp = 0.0; var bp = 0.0; var nPrev = 0f
    }

    /** The generator's wave shapes, read from the sine table for the sine. */
    private fun shape(type: Int, phase: Double): Float {
        val p = phase - kotlin.math.floor(phase)
        return when (type) {
            1 -> (4 * kotlin.math.abs(p - 0.5) - 1).toFloat()
            2 -> if (p < 0.5) 0.8f else -0.8f
            3 -> (2 * p - 1).toFloat() * 0.8f
            else -> { val x = p * 4096.0; val i = x.toInt(); SINE[i] + (SINE[i + 1] - SINE[i]) * (x - i).toFloat() }
        }
    }
    private val voices = ArrayList<Voice>()
    @Volatile private var fadeSec = 3f
    @Volatile private var run = false
    private var thread: Thread? = null
    private var seed = 90817
    /** One sine cycle, read with whole-number phases that wrap by themselves. Far cheaper than calculating a sine per sample. */
    private val SINE = FloatArray(4097) { sin(2.0 * PI * it / 4096.0).toFloat() }
    private const val FRAC = 1f / 1048576f
    private fun phaseStep(hz: Float) = (hz.toDouble() / RATE * 4294967296.0).toLong().toInt()

    /** Starts or changes the sound. Layers that stay in the stack update live. */
    @Synchronized fun set(stack: AudioStack, blendSec: Float) {
        fadeSec = blendSec.coerceAtLeast(0.2f)
        AudioProbe.event("sound changes to ${stack.name}: ${stack.layers.size} layers, ${blendSec} s blend, ${voices.size} voices running")
        synchronized(voices) {
            voices.forEach { v -> v.target = 0f }
            for (l in stack.layers) {
                val key = stack.name + "/" + l.id
                val v = voices.firstOrNull { it.key == key }
                if (v != null) { v.layer = l; v.target = if (l.on) 1f else 0f } else if (l.on) voices.add(Voice(key, l))
            }
        }
        if (!run) start()
    }

    @Synchronized fun stop() { if (run) AudioProbe.event("sound engine stopped"); AudioBands.toneBeat = 0f; AudioBands.toneCarrier = 0f; run = false; thread?.join(700); thread = null; synchronized(voices) { voices.clear() } }

    private fun noise(): Float { seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5); return seed * 4.656613e-10f }

    private fun start() {
        AudioProbe.engineStart()
        run = true
        thread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(min, 35280)).build()   // 0.2 s in hand: enough for a busy moment, short enough that changes are heard at once (0.5 s made every change lag)
            track.play()
            val n = 1024; val l = FloatArray(n); val r = FloatArray(n); val vl = FloatArray(n); val vr = FloatArray(n); val pcm = ShortArray(n * 2)
            var master = 0f
            var written = 0L
            while (true) {
                val leaving = !run
                l.fill(0f); r.fill(0f)
                val t0 = System.nanoTime()
                val list = synchronized(voices) { voices.removeAll { it.target == 0f && it.gain < 0.001f }; voices.toList() }
                for (v in list) {
                    render(v, vl, vr, n)
                    val step = 1f / (RATE * fadeSec)
                    val pan = v.layer["pan"]; val pl = if (pan > 0f) 1f - pan else 1f; val pr = if (pan < 0f) 1f + pan else 1f
                    val level = v.layer["level"]
                    val target = v.target; var gain = v.gain
                    val gl = level * pl; val gr = level * pr
                    for (i in 0 until n) {
                        if (gain < target) { gain += step; if (gain > target) gain = target } else if (gain > target) { gain -= step; if (gain < target) gain = target }
                        l[i] += vl[i] * gain * gl; r[i] += vr[i] * gain * gr
                    }
                    v.gain = gain
                }
                val ms = if (leaving) -1f / (RATE * 0.25f) else 1f / (RATE * 0.5f)
                for (i in 0 until n) {
                    master = (master + ms).coerceIn(0f, 1f)
                    pcm[i * 2] = (AmbientSynth.limit(l[i]) * master * 32767f).toInt().toShort()
                    pcm[i * 2 + 1] = (AmbientSynth.limit(r[i]) * master * 32767f).toInt().toShort()
                }
                val t1 = System.nanoTime()
                track.setVolume(Sound.gain); track.write(pcm, 0, pcm.size)
                written += n
                if (AudioProbe.active) AudioProbe.block(t1 - t0, System.nanoTime() - t1, list.size, pcm, track.underrunCount, (written - track.playbackHeadPosition) * 1000f / RATE)
                if (leaving && master <= 0f) break
            }
            runCatching { track.stop(); track.release() }
        }.apply { isDaemon = true; start() }
    }

    private fun render(v: Voice, l: FloatArray, r: FloatArray, n: Int) {
        val a = v.layer
        val dt = 1.0 / RATE
        when (a.kind) {
            "Tone" -> {
                // the settings change far slower than the sound, so they're worked out once per block
                val wander = a["wander"]
                val span = (a["minutes"] * 60f).coerceAtLeast(1f)
                val h = a["harmonic"]
                val k = (v.clock / span).coerceIn(0.0, 1.0).toFloat()
                val toC = a["carrierTo"]; val toB = a["beatTo"]
                val carrier = a["carrier"] + (if (toC >= 0f) (toC - a["carrier"]) * k else 0f)
                val beat = a["beat"] + (if (toB >= 0f) (toB - a["beat"]) * k else 0f)
                // the carrier wanders with no repeating pattern; both ears move together, so the beat stays exact
                repeat(4) { v.walkV = (v.walkV + noise() * 0.002f) * 0.998f; v.walk = (v.walk + v.walkV).coerceIn(-1f, 1f); if (kotlin.math.abs(v.walk) >= 1f) v.walkV = -v.walkV }
                // follows the picture: brighter scenes raise the carrier, and the beat stays exact
                val c = carrier + v.walk * wander * 1.5f + a["follow"] * (Bg2.sceneLight - 0.25f) * 40f
                AudioBands.toneBeat = beat; AudioBands.toneCarrier = c
                // rates share no common period, so the level never settles into a noticeable cycle
                val t = v.clock
                val swell = (1f - wander * 0.22f * (0.5f + 0.25f * sin(2 * PI * 0.031 * t).toFloat() + 0.25f * sin(2 * PI * 0.0473 * t + 1.0).toFloat())) * 0.5f
                val hl = h * (0.7f + 0.3f * sin(2 * PI * 0.0191 * t).toFloat())
                v.clock += n * dt
                val stepL = phaseStep((c - beat / 2f).coerceAtLeast(1f)); val stepR = phaseStep((c + beat / 2f).coerceAtLeast(1f))
                var pl = v.phaseL; var pr = v.phaseR
                val tab = SINE
                for (i in 0 until n) {
                    pl += stepL; pr += stepR
                    var x = pl ushr 20; var y = tab[x] + (tab[x + 1] - tab[x]) * ((pl and 0xFFFFF) * FRAC)
                    var z = pr ushr 20; var w = tab[z] + (tab[z + 1] - tab[z]) * ((pr and 0xFFFFF) * FRAC)
                    if (hl != 0f) {
                        val p2 = pl shl 1; val q2 = pr shl 1
                        x = p2 ushr 20; y += hl * (tab[x] + (tab[x + 1] - tab[x]) * ((p2 and 0xFFFFF) * FRAC))
                        z = q2 ushr 20; w += hl * (tab[z] + (tab[z + 1] - tab[z]) * ((q2 and 0xFFFFF) * FRAC))
                    }
                    l[i] = y * swell; r[i] = w * swell
                }
                v.phaseL = pl; v.phaseR = pr
            }
            "Noise" -> for (i in 0 until n) {
                val w = noise(); val w2 = noise()
                v.b0 = 0.99765f * v.b0 + w * 0.0990460f; v.b1 = 0.96300f * v.b1 + w * 0.2965164f; v.b2 = 0.57000f * v.b2 + w * 1.0526913f
                v.c0 = 0.99765f * v.c0 + w2 * 0.0990460f; v.c1 = 0.96300f * v.c1 + w2 * 0.2965164f; v.c2 = 0.57000f * v.c2 + w2 * 1.0526913f
                l[i] = (v.b0 + v.b1 + v.b2 + w * 0.1848f) * 0.2f; r[i] = (v.c0 + v.c1 + v.c2 + w2 * 0.1848f) * 0.2f
            }
            "Didgeridoo" -> {
                val d = v.didge ?: Didgeridoo(RATE).also { v.didge = it }
                val t = Tone(didge = true, didgePitch = a["note"], didgeMouth = a["mouth"], didgeRate = a["mouthRate"], didgeBreath = a["breath"])
                for (i in 0 until n) { d.next(t); l[i] = d.l * 1.5f; r[i] = d.r * 1.5f }
            }
            DNB_KIND -> {
                // drum and bass at 174 BPM, made live: a kick on 1 and the "and" of 3, snares on 2 and 4 with ghost notes,
                // hats on every sixteenth, and a sub bass that ducks under the kick. One bar is 16 sixteenths.
                val bpm = (174f * a["speed"].coerceIn(0.25f, 2f)).toDouble()
                val stepLen = 60.0 / bpm / 4.0
                for (i in 0 until n) {
                    val t = v.clock + i * dt
                    val pos = t / stepLen; val s = Math.floorMod(pos.toLong(), 16L).toInt(); val ts = (pos - Math.floor(pos)) * stepLen
                    fun since(steps: IntArray): Double { val last = steps.lastOrNull { it <= s } ?: (steps.last() - 16); return ts + (s - last) * stepLen }
                    val tk = since(KICKS); val tn = since(SNARES); val tg = since(GHOSTS)
                    // kick: a sine that drops from about 170 Hz to 50 Hz
                    v.kp += (50.0 + 120.0 * Math.exp(-tk * 30.0)) / RATE
                    val kick = (Math.sin(2 * PI * v.kp) * Math.exp(-tk * 7.0)).toFloat() * 0.9f
                    // snare: a noise burst and a short tone; ghost notes are the same, much softer
                    val w = noise()
                    val snare = (w * Math.exp(-tn * 18.0) * 0.5 + Math.sin(2 * PI * 190.0 * tn) * Math.exp(-tn * 25.0) * 0.3).toFloat() +
                        (w * Math.exp(-tg * 22.0) * 0.14).toFloat()
                    // hats: the bright part of noise, ticking on each sixteenth, louder on the even ones
                    val hiss = w - v.nPrev; v.nPrev = w
                    val hat = hiss * Math.exp(-ts * 60.0).toFloat() * (if (s % 2 == 0) 0.22f else 0.1f)
                    // sub bass: A for the first half of the bar, G for the second, pushed down by the kick
                    v.bp += (if (s < 8) 55.0 else 49.0) / RATE
                    val bass = (Math.sin(2 * PI * v.bp) * 0.8 + Math.sin(2 * PI * v.bp * 1.005) * 0.2).toFloat() * 0.3f * (1f - Math.exp(-tk * 12.0).toFloat())
                    val mono = kick + snare + bass
                    l[i] = (mono + hat * 0.7f) * 0.7f; r[i] = (mono + hat * 1.3f) * 0.7f
                }
                v.kp -= Math.floor(v.kp); v.bp -= Math.floor(v.bp)
                v.clock += n * dt
                AudioBands.toneBeat = (bpm / 60.0).toFloat()
            }
            GENERATOR_KIND -> {
                // the classic generator, sample by sample: L = carrier - beat/2, R = carrier + beat/2, a beat that can sweep,
                // an isochronic pulse, a 2nd harmonic, a pink-noise bed and an optional didgeridoo
                val car = a["carrier"]; val b0 = a["beat"]; val be = a["beatEnd"]; val sw = a["sweep"]
                val type = a["wave"].toInt(); val iso = a["iso"]; val depth = a["isoDepth"]; val h = a["harmonic"]; val nz = a["noise"]
                val didgeOn = a["didge"] > 0.5f; val dl = a["didgeLevel"]
                val tone = if (didgeOn) Tone(didge = true, didgePitch = a["note"], didgeMouth = a["mouth"], didgeRate = a["mouthRate"], didgeBreath = a["breath"]) else null
                val d = if (didgeOn) (v.didge ?: Didgeridoo(RATE).also { v.didge = it }) else null
                val nyq = RATE / 2f - 100f
                val norm = 0.5f / (1f + h)
                val beatNow = if (sw > 0f && be >= 0f) b0 + (be - b0) * (v.clock / (sw * 60.0)).coerceIn(0.0, 1.0).toFloat() else b0
                AudioBands.toneBeat = beatNow; AudioBands.toneCarrier = car
                val fl = (car - beatNow / 2f).coerceIn(1f, nyq) / RATE; val fr = (car + beatNow / 2f).coerceIn(1f, nyq) / RATE
                val on = if (a["toneOff"] > 0.5f) 0f else 1f   // silence the carrier, keep the noise and didgeridoo
                for (i in 0 until n) {
                    v.gl += fl; v.gr += fr
                    v.gOn += (on - v.gOn) * 0.0005f
                    var x = (shape(type, v.gl) + h * shape(type, v.gl * 2)) * v.gOn
                    var y = (shape(type, v.gr) + h * shape(type, v.gr * 2)) * v.gOn
                    if (iso > 0f) { v.gIso += iso / RATE; val g = 1f - depth * (0.5f - 0.5f * shape(0, v.gIso)); x *= g; y *= g }
                    if (nz > 0f) {
                        val w = noise()
                        v.b0 = 0.99765f * v.b0 + w * 0.0990460f; v.b1 = 0.96300f * v.b1 + w * 0.2965164f; v.b2 = 0.57000f * v.b2 + w * 1.0526913f
                        val pink = (v.b0 + v.b1 + v.b2 + w * 0.1848f) * 0.18f * nz * 2f
                        x += pink; y += pink
                    }
                    if (d != null && tone != null) { d.next(tone); x += d.l * dl * 2f; y += d.r * dl * 2f }
                    l[i] = x * norm; r[i] = y * norm
                }
                // phases stay small, so they keep their precision over hours
                v.gl -= kotlin.math.floor(v.gl); v.gr -= kotlin.math.floor(v.gr); v.gIso -= kotlin.math.floor(v.gIso)
                v.clock += n * dt
            }
            else -> (v.synth ?: AmbientSynth(a.kind, RATE, v.key.hashCode()).also { v.synth = it }).also {
                it.pitch = a["pitch"]; it.every = a["every"]
                it.octave = a["octave"]; it.sustain = a["sustain"]; it.bright = a["bright"]; it.space = a["space"]
                it.loose = a["loose"]; it.vary = a["vary"]; it.spread = a["spread"]; it.detune = a["detune"]
            }.fill(l, r, n)
        }
    }
}

/* ---------------- journeys ---------------- */

data class JourneyStep(val scene: String, val audio: String = "", val minutes: Float = 3f, val blendSec: Float = 10f,
                       val transition: Int = 0, val lead: Int = -1, val offset: Float = 0f) {
    fun toJson(): JSONObject = JSONObject().put("scene", scene).put("audio", audio).put("minutes", minutes.toDouble()).put("blend", blendSec.toDouble())
        .put("transition", transition).put("lead", lead).put("offset", offset.toDouble())
    companion object { fun fromJson(o: JSONObject) = JourneyStep(o.getString("scene"), o.optString("audio", ""), o.optDouble("minutes", 3.0).toFloat(), o.optDouble("blend", 10.0).toFloat(),
        o.optInt("transition", 0), o.optInt("lead", -1), o.optDouble("offset", 0.0).toFloat()) }
}
/** [loop]: after the last step it blends back into the first, using the last step's transition. On unless switched off. */
data class Journey(val name: String, val steps: List<JourneyStep>, val loop: Boolean = true) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("loop", loop).put("steps", JSONArray().also { a -> steps.forEach { a.put(it.toJson()) } })
    companion object { fun fromJson(o: JSONObject): Journey { val a = o.getJSONArray("steps"); return Journey(o.getString("name"), (0 until a.length()).map { JourneyStep.fromJson(a.getJSONObject(it)) }, o.optBoolean("loop", true)) } }
}

object Med {
    var journeys by mutableStateOf(listOf<Journey>())
    var stacks by mutableStateOf(listOf<AudioStack>())
    var playing by mutableStateOf<Journey?>(null)
    var paused by mutableStateOf(false)
    var stepIndex by mutableIntStateOf(0)
    var skip by mutableIntStateOf(0)
    var remaining by mutableFloatStateOf(0f)
    /** Journey shown on the timeline while the planner is open. */
    var planning by mutableStateOf<Journey?>(null)
    var planStep by mutableIntStateOf(-1)
    /** Generator sub-tab: 0 = Backgrounds, 1 = Moods. */
    var subTab by mutableIntStateOf(0)
    /** The mood being browsed or built. It's only saved when you save it. */
    var draft by mutableStateOf<AudioStack?>(null)
    /** Add layer is open: the layer tiles can't take the selection, so neither the pointer nor a lost focus can open their settings. */
    val moodAddingState = androidx.compose.runtime.mutableStateOf(false)
    var moodAdding by moodAddingState
    /** Takes the selection to the top of the open settings list, whatever it's scrolled to. */
    var panelTop: (() -> Unit)? = null
    var moodBuilding by mutableStateOf(false)
    var moodFirst: FocusRequester? = null
    var focusLayer by mutableIntStateOf(-1)
    var soloLayer by mutableIntStateOf(-1)
    var movingLayer by mutableIntStateOf(-1)
    /** Step highlighted in the planner, shown in detail under the preview. */
    var focusStep by mutableIntStateOf(0)
    /** The journey picked in the tab. Kept here so it's still selected after leaving the tab and coming back. */
    val journeyPick = mutableStateOf("")
    /** Meditation tab layout: split (journey presets in their own column on the right) or classic (the earlier single list, kept to go back to). */
    var classicLayout by mutableStateOf(false); private set
    fun setClassic(ctx: Context, v: Boolean) { classicLayout = v; sp(ctx).edit().putBoolean("classicLayout", v).apply() }
    private var loaded = false
    private fun sp(ctx: Context) = ctx.getSharedPreferences("starshine_med", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        classicLayout = sp(ctx).getBoolean("classicLayout", false)
        MoodIcons.load(ctx)
        if (loaded) return
        loaded = true
        val p = sp(ctx)
        runCatching { p.getString("journeys", null)?.let { s -> val a = JSONArray(s); journeys = (0 until a.length()).map { Journey.fromJson(a.getJSONObject(it)) } } }
        runCatching { p.getString("stacks", null)?.let { s -> val a = JSONArray(s); stacks = (0 until a.length()).map { AudioStack.fromJson(a.getJSONObject(it)) } } }
        if (stacks.isEmpty()) stacks = Tones.loadPresets(ctx).map { AudioStack.fromTone(it) } + AudioStack("Rain", listOf(AudioLayer(1, "Rain"))) + AudioStack("Ocean", listOf(AudioLayer(1, "Ocean")))
        val instruments = listOf("Singing bowl", "Tibetan bells", "Xylophone", "Chimes", "Temple gong", "Tanpura", "Handpan", "Kalimba", "Frame drum").filter { n -> stacks.none { it.name == n } }
        stacks = stacks + instruments.map { AudioStack(it, listOf(AudioLayer(1, it))) }
        stacks = stacks + (MEDITATIVE_MOODS + WORLD_MOODS).filter { m -> stacks.none { it.name == m.name } }
        draft = stacks.firstOrNull()
        if (journeys.isEmpty()) journeys = listOf(Journey("First journey", listOf(
            JourneyStep("Temple of Light", "Rain", 3f, 15f), JourneyStep("Water", "Ocean", 3f, 15f), JourneyStep("Inner eye", "Theta dream 6 Hz", 3f, 15f))))
    }
    fun saveJourneys(ctx: Context) = sp(ctx).edit().putString("journeys", JSONArray().also { a -> journeys.forEach { a.put(it.toJson()) } }.toString()).apply()
    fun saveStacks(ctx: Context) = sp(ctx).edit().putString("stacks", JSONArray().also { a -> stacks.forEach { a.put(it.toJson()) } }.toString()).apply()
    fun putJourney(ctx: Context, j: Journey) { journeys = if (journeys.any { it.name == j.name }) journeys.map { if (it.name == j.name) j else it } else journeys + j; saveJourneys(ctx) }
    fun renameStack(ctx: Context, old: String, new: String): Boolean {
        if (stacks.any { it.name == new }) return false
        stacks = stacks.map { if (it.name == old) it.copy(name = new) else it }; saveStacks(ctx)
        journeys = journeys.map { j -> j.copy(steps = j.steps.map { if (it.audio == old) it.copy(audio = new) else it }) }; saveJourneys(ctx)
        draft?.let { if (it.name == old) draft = it.copy(name = new) }
        if (Sound.current == old) Sound.current = new
        Drafts.renamed(ctx, old, new); MoodIcons.renamed(ctx, old, new)
        return true
    }
    fun renameSceneInJourneys(ctx: Context, old: String, new: String) {
        journeys = journeys.map { j -> j.copy(steps = j.steps.map { if (it.scene == old) it.copy(scene = new) else it }) }; saveJourneys(ctx)
    }
    fun putStack(ctx: Context, s: AudioStack) { stacks = if (stacks.any { it.name == s.name }) stacks.map { if (it.name == s.name) s else it } else stacks + s; saveStacks(ctx) }
    fun newName(base: String, taken: (String) -> Boolean): String { var i = 1; while (taken("$base $i")) i++; return "$base $i" }

    /**
     * A mood in the same vibe as a scene, for when a journey has no sound of its own: its name, then its group, then a calm
     * default. Only moods that exist are chosen.
     */
    fun vibeFor(sceneName: String): AudioStack? {
        val scene = Bg2.pristine(sceneName)
        val words = (sceneName + " " + (scene?.group ?: "") + " " + scene?.layers.orEmpty().joinToString(" ") { it.type }).lowercase()
        val wishes = listOf(
            listOf("water", "ocean", "sea", "bioluminescent") to listOf("Ocean breath", "Ocean"),
            listOf("rain", "cloud", "air") to listOf("Rain sanctuary", "Zen garden", "Rain"),
            listOf("fire", "candle", "sun", "flare") to listOf("Fire circle", "Alpha calm"),
            listOf("earth", "globe", "mountain") to listOf("Outback dreaming", "Himalayan temple"),
            listOf("temple", "metatron", "flower", "seed", "sacred", "mandala", "geometry") to listOf("Sound bath", "Himalayan temple"),
            listOf("star", "galaxy", "nebula", "space", "warp", "planet", "3d scenes", "solar") to listOf("Theta drift", "Deep stillness"),
            listOf("light", "eye", "iris", "hologram") to listOf("Alpha calm", "Deep stillness")
        )
        val wanted = wishes.filter { (keys, _) -> keys.any { words.contains(it) } }.flatMap { it.second } + listOf("Deep stillness", "Sound bath")
        return wanted.firstNotNullOfOrNull { n -> stacks.firstOrNull { it.name == n } } ?: stacks.firstOrNull { it.layers.isNotEmpty() }
    }

    private var idBase = 100
    private fun fresh(scene: BgScene): BgScene {
        idBase += 100
        val base = idBase
        return scene.copy(layers = scene.layers.map { l ->
            Layer(l.id + base, l.type, l.v + listOfNotNull(if (l["maskBy"] >= 0f) "maskBy" to l["maskBy"] + base else null, if (l["warpBy"] >= 0f) "warpBy" to l["warpBy"] + base else null), l.media)
        })
    }

    /**
     * Plays until the end or until [playing] is cleared. A transition is centred on the cut: with a 15 s blend it starts 7.5 s
     * before the clip's end, and both clips keep running throughout. [show] puts a scene on the real background.
     */
    suspend fun run(ctx: Context, j: Journey, show: (BgScene) -> Unit) {
        Sound.userOff = false
        var i = 0
        var round = 0
        // the sound never stops during a journey: a step without its own keeps the last one, and with nothing at all
        // playing, a mood that suits the scene starts
        var sound: AudioStack? = null
        fun keepSound(st: JourneyStep, blend: Float) {
            if (paused || Sound.userOff || Sound.current.isNotEmpty()) return
            val a = sound ?: vibeFor(st.scene) ?: return
            sound = a; AudioProbe.event("no sound playing: ${a.name} starts"); Sound.playStack(ctx, a, blend, explicit = false)
        }
        // the next step's scene, prepared a few seconds ahead (fresh ids included) so its transition starts with nothing left to build
        var ready: Pair<Int, BgScene>? = null
        fun prepareNext() {
            val ni = if (i + 1 < j.steps.size) i + 1 else if (j.loop) 0 else return
            if (ready?.first == ni) return
            Bg2.pristine(j.steps[ni].scene)?.let { b -> val f = fresh(b); ready = ni to f; Bg2.warm = f; AudioProbe.event("step ${ni + 1}: ${f.name} prepared ahead") }
        }
        suspend fun waitFor(seconds: Float, st: JourneyStep) {
            val skipAt = skip
            var left = seconds
            while (left > 0f && playing === j && skip == skipAt) {
                delay(250)
                if (!paused) { left -= 0.25f; remaining = left; keepSound(st, 3f) }
                if (left <= PREPARE_SEC) prepareNext()
            }
        }
        while (i < j.steps.size && playing === j) {
            val s = j.steps[i]; stepIndex = i
            // a step's transition is the way OUT of it, into the next step. The journey itself starts straight on its first
            // scene, whatever was on screen before; when it loops, the last step's transition leads back into the first.
            val into = j.steps.getOrNull(i - 1) ?: if (round > 0) j.steps.last() else null
            val blendIn = into?.blendSec ?: 0f
            val blendOut = if (i < j.steps.lastIndex || j.loop) s.blendSec else 0f
            Bg2.pristine(s.scene)?.let { base ->
                // the copy prepared ahead when there is one: the renderer has already built it
                val scene = ready?.takeIf { it.first == i }?.second ?: fresh(base)
                ready = null
                Bg2.blendMode = (into?.transition ?: 0).coerceIn(0, TRANSITIONS.lastIndex)
                Bg2.blendLead = into?.let { scene.layers.getOrNull(it.lead)?.id } ?: -1
                Bg2.blendOffset = into?.offset ?: 0f
                Bg2.blendMix = 0f
                Bg2.blendFrom = if (into != null && Bg2.applied && blendIn > 0f) Bg2.current else null
                AudioProbe.event("step ${i + 1}: ${s.scene}, ${scene.layers.size} layers, transition of $blendIn s starts" + if (Bg2.blendFrom != null) ", from ${Bg2.blendFrom?.layers?.size} layers" else "")
                show(scene)
                val own = stacks.firstOrNull { it.name == s.audio }
                if (own != null && Sound.userOff) sound = own
                else if (own != null) { sound = own; Sound.playStack(ctx, own, if (into == null) 1f else blendIn, explicit = false) } else keepSound(s, if (into == null) 1f else blendIn.coerceAtLeast(1f))
                val total = (blendIn * 1000).toLong().coerceAtLeast(1)
                var done = 0L; var last = System.currentTimeMillis()
                while (done < total && playing === j) {
                    delay(33)
                    val now = System.currentTimeMillis()
                    if (!paused) done += now - last
                    last = now
                    Bg2.blendMix = (done.toFloat() / total).coerceIn(0f, 1f)
                }
                Bg2.blendFrom = null; Bg2.blendMix = 1f; Bg2.warm = null
                AudioProbe.event("step ${i + 1}: transition ended")
            }
            // half of this step's transition and half of the next one fall inside this step's time
            waitFor((s.minutes * 60f - blendIn / 2f - blendOut / 2f).coerceAtLeast(0f), s)
            i++
            if (i == j.steps.size && j.loop && playing === j) { i = 0; round++; AudioProbe.event("journey loops: round ${round + 1}") }
        }
        AudioProbe.event("journey ended on ${Bg2.current.name}")
        android.util.Log.i("StarshineMed", "journey ${j.name} ended on ${Bg2.current.name}")
        Bg2.warm = null
        if (playing === j) { playing = null; Sound.stop() }
    }

    /** Seconds before a step ends that the next step's scene is prepared. */
    private const val PREPARE_SEC = 4f
}

/* ---------------- planner ---------------- */

private fun fmt(v: Float, d: Int = 1) = String.format(Locale.US, "%.${d}f", v)
private fun <T> cycle(l: List<T>, cur: T, d: Int): T { val i = l.indexOf(cur).coerceAtLeast(0); return l[(i + d + l.size) % l.size] }

/** Every scene (or every mood) as icons with their titles, for a journey step. OK = use it · Back = keep the current one. */
@Composable
private fun StepPicker(scenes: Boolean, current: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    BackHandler { onClose() }
    val ctx = LocalContext.current
    val prefix = if (scenes) "md:ps:" else "md:pa:"
    LaunchedEffect(Unit) { delay(80); FocusMem.restoreSoon(if (current.isEmpty() && !scenes) "row:md:pa:keep" else prefix + current) }
    var hint by remember { mutableStateOf("") }
    Text(if (scenes) "CHOOSE A SCENE" else "CHOOSE A MOOD", color = CYAN, fontSize = 11.sp, letterSpacing = 1.2.sp, maxLines = 1)
    Text(hint.ifEmpty { "OK = use it · Back = keep " + current.ifEmpty { "the same sound" } }, color = DIM, fontSize = 9.sp, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
    LoopColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (scenes) (SCENE_GROUPS + SPACE_GROUP + DOWNLOADED).forEach { g ->
            val list = when (g) { MY_SCENES -> Bg2.mine; DOWNLOADED -> Packs.scenes; else -> BUILT_IN_SCENES.filter { it.group == g } }
            if (list.isNotEmpty()) {
                item(key = "psh:$g") { Header(g) }
                items(list.map { it.name }.chunked(ICON_COLUMNS).size) { k ->
                    val row = list.map { it.name }.chunked(ICON_COLUMNS)[k]
                    IconRow(row, count = { n -> if (n == current) 1 else 0 }, idOf = { prefix + it }, height = 36, label = { it },
                        onHint = { n -> hint = n; if (n.isNotEmpty()) Bg2.pristine(n)?.let { Bg2.preview = it; lastBrowsedScene = it.name } },
                        onPress = { onPick(it) }) { n, c, m -> SceneIcon(list.firstOrNull { it.name == n }, c, m) }
                }
            }
        } else {
            item { Row_("Keep the sound already playing", current.isEmpty(), id = "md:pa:keep", onFocus = { hint = "" }) { onPick("") } }
            items(Med.stacks.map { it.name }.chunked(ICON_COLUMNS).size) { k ->
                val row = Med.stacks.map { it.name }.chunked(ICON_COLUMNS)[k]
                IconRow(row, count = { n -> if (n == current) 1 else 0 }, idOf = { prefix + it }, height = 36, label = { it },
                    onHint = { n -> hint = n; if (n.isNotEmpty() && Med.playing == null) Med.stacks.firstOrNull { it.name == n }?.let { if (Sound.current != it.name) Sound.playStack(ctx, it, 0.4f, explicit = false) } },
                    onPress = { onPick(it) }) { n, c, m -> MoodIcon(Med.stacks.firstOrNull { it.name == n }, c, m) }
            }
        }
    }
}

@Composable
fun MeditationTab(onPlay: (Journey) -> Unit, onStop: () -> Unit, onAudio: () -> Unit) =
    MeditationLayout(onPlay, onStop, onAudio, split = !Med.classicLayout)

/**
 * The Meditation tab. Split (default): building and managing the selected journey on the left, every journey preset in a
 * column on the right (OK = select, hold OK = play, loop, audio test, manage, rename, delete). Classic: the earlier single
 * list, preset grids included, kept as it was so it can be switched back to (Layout row).
 */
@Composable
private fun MeditationLayout(onPlay: (Journey) -> Unit, onStop: () -> Unit, onAudio: () -> Unit, split: Boolean) {
    val ctx = LocalContext.current
    var name by Med.journeyPick
    var stepNo by remember { mutableIntStateOf(-1) }
    var picker by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    // your journeys first, then the ready-made and downloaded ones: editing one of those keeps your own copy
    val j = (Med.journeys + BUILT_IN_JOURNEYS + Packs.journeys).firstOrNull { it.name == name } ?: Med.journeys.firstOrNull() ?: BUILT_IN_JOURNEYS.first()
    val isMine = Med.journeys.any { it.name == j.name }
    var getMore by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    Med.planning = j
    Med.planStep = stepNo
    if (stepNo >= 0) Med.focusStep = stepNo
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Med.planning = null } }
    val scenes = remember(Bg2.mine) { Bg2.mine.map { it.name } + BUILT_IN_SCENES.map { it.name } }
    // browsing a step shows its scene and plays its sound (not while a journey is playing: that one keeps its own)
    fun hear(audio: String) { if (Med.playing == null) Med.stacks.firstOrNull { it.name == audio }?.let { if (Sound.current != it.name) Sound.playStack(ctx, it, 0.6f, explicit = false) } }
    fun preview(s: JourneyStep?) { s?.let { st -> Bg2.pristine(st.scene)?.let { Bg2.preview = it; lastBrowsedScene = it.name }; hear(st.audio) } }
    fun update(steps: List<JourneyStep>, loop: Boolean? = null) { j?.let { Med.putJourney(ctx, it.copy(steps = steps, loop = loop ?: it.loop)) } }
    // the monitor follows the selected journey: its first step, or an empty picture if it has no steps yet
    LaunchedEffect(j?.name) {
        delay(50)   // after the tab's own reset of the preview when entering it
        Med.focusStep = 0
        if (Med.playing == null && j != null) {
            if (j.steps.isEmpty()) { Bg2.preview = BgScene(j.name, "", emptyList()); lastBrowsedScene = j.name } else preview(j.steps.first())
        }
    }
    var backTo by remember { mutableStateOf("") }
    BackHandler(enabled = stepNo >= 0) { backTo = "row:md:st:$stepNo"; stepNo = -1 }
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(stepNo >= 0) {
        // opening the tab leaves the selection on its icon, as on the other tabs
        if (!opened) { opened = true; return@LaunchedEffect }
        delay(80)
        if (stepNo < 0 && backTo.isNotEmpty()) { FocusMem.restoreSoon(backTo); backTo = "" } else runCatching { first.requestFocus() }
    }
    if (getMore) { Panel(Modifier.padding(start = 24.dp, bottom = 8.dp).width(256.dp).fillMaxHeight().stageBeside()) { PackBrowser { getMore = false; FocusMem.scope?.launch { delay(80); FocusMem.restoreSoon("row:md:more") } } }; return }
    // split: the presets' column keeps the right edge, so the preview under the monitor shrinks to fit between the two
    // (the column reports where it really starts, see stageRight; it's forgotten when the column goes)
    if (split) androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Stage.rightEdge = 0f } }
    var menuFor by remember { mutableStateOf<String?>(null) }
    // the long-press menu shows its journey in the preview window (first step: scene and sound); closing it goes back to
    // the selected journey. Not while a journey is playing: that one keeps the screen
    var menuShown by remember { mutableStateOf(false) }
    LaunchedEffect(menuFor) {
        if (Med.playing != null) return@LaunchedEffect
        val t = menuFor?.let { n -> (Med.journeys + BUILT_IN_JOURNEYS + Packs.journeys).firstOrNull { it.name == n } }
        if (t != null) { menuShown = true; t.steps.firstOrNull()?.let { preview(it) } ?: run { Bg2.preview = BgScene(t.name, "", emptyList()); lastBrowsedScene = t.name } }
        else if (menuShown) { menuShown = false; j.steps.firstOrNull()?.let { preview(it) } }
    }
    Box(Modifier.fillMaxSize()) {
    // → past the left menu goes to the selected journey in the column (rows that change a value with ← → keep their
    // arrows: they use the key before focus moves), ← from the column comes back to Play
    Panel(Modifier.padding(start = 24.dp, bottom = 8.dp).width(256.dp).fillMaxHeight().stageBeside()
        // (a key handler, not focusProperties: the rows cancel sideways moves themselves)
        .onPreviewKeyEvent { e ->
            if (split && menuFor == null && M5.editing == null && e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight &&
                FocusMem.current?.startsWith("adj:") != true) { FocusMem.restore("md:j:${j.name}"); true } else false
        }) {
        val st = j?.steps?.getOrNull(stepNo)
        if (j != null && st != null) {
            Text("STEP ${stepNo + 1} · ${j.name}".uppercase(), color = CYAN, fontSize = 11.sp, letterSpacing = 1.2.sp, maxLines = 1)
            Text("OK = edit · ↑↓ next · Back = steps", color = DIM, fontSize = 9.sp, modifier = Modifier.padding(bottom = 4.dp))
            val i = stepNo
            fun set(n: JourneyStep) = update(j.steps.toMutableList().also { it[i] = n })
            val audios = listOf("") + Med.stacks.map { it.name }
            val pick = picker
            if (pick != null) StepPicker(pick == "scene", if (pick == "scene") st.scene else st.audio,
                onPick = { v -> val n = if (pick == "scene") st.copy(scene = v) else st.copy(audio = v); set(n); preview(n); picker = null; FocusMem.scope?.launch { delay(80); FocusMem.restoreSoon(if (pick == "scene") "adj:Scene" else "adj:Audio") } },
                onClose = { preview(st); picker = null; FocusMem.scope?.launch { delay(80); FocusMem.restoreSoon(if (pick == "scene") "adj:Scene" else "adj:Audio") } })
            else LoopColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // ← → step through them one by one; a long press on OK opens all of them as icons
                item { Box(Modifier.focusRequester(first)) { Adjust("Scene", st.scene, { val n = st.copy(scene = cycle(scenes, st.scene, -1)); set(n); preview(n) }, compact = true, onOk = { picker = "scene" }) { val n = st.copy(scene = cycle(scenes, st.scene, 1)); set(n); preview(n) } } }
                item { Adjust("Audio", st.audio.ifEmpty { "keep playing" }, { val n = st.copy(audio = cycle(audios, st.audio, -1)); set(n); hear(n.audio) }, compact = true, onOk = { picker = "audio" }) { val n = st.copy(audio = cycle(audios, st.audio, 1)); set(n); hear(n.audio) } }
                item { Text((if (M5.editLock) "Hold OK" else "OK") + " on Scene or Audio shows them all", color = DIM, fontSize = 9.sp, modifier = Modifier.padding(start = 12.dp)) }
                item { Adjust("Time", fmt(st.minutes) + " min", { set(st.copy(minutes = ((st.minutes - 0.1f) * 10f).let { Math.round(it) / 10f }.coerceAtLeast(0.1f))) }, compact = true) { set(st.copy(minutes = ((st.minutes + 0.1f) * 10f).let { Math.round(it) / 10f })) } }
                // looping, the last step leads back into the first
                val next = j.steps.getOrNull(i + 1) ?: if (j.loop && j.steps.size > 1) j.steps.first() else null
                if (next == null) item { Header("Last step: the journey ends here (loop is off)") }
                else {
                    item { Header("Transition into step ${if (i + 1 < j.steps.size) i + 2 else 1}: ${next.scene}") }
                    item { Adjust("Type", TRANSITIONS[st.transition.coerceIn(0, TRANSITIONS.lastIndex)], { set(st.copy(transition = (st.transition - 1 + TRANSITIONS.size) % TRANSITIONS.size)) }, compact = true) { set(st.copy(transition = (st.transition + 1) % TRANSITIONS.size)) } }
                    item { Adjust("Length", "${st.blendSec.toInt()} s", { set(st.copy(blendSec = (st.blendSec - 1f).coerceAtLeast(0f))) }, compact = true) { set(st.copy(blendSec = st.blendSec + 1f)) } }
                    item {
                        // the layer that leads belongs to the scene that's arriving
                        val layers = Bg2.pristine(next.scene)?.layers ?: emptyList()
                        val names = listOf("none") + layers.map { it.type.removePrefix(CLASSIC_PREFIX).removePrefix(LINES_PREFIX) }
                        val at = (st.lead + 1).coerceIn(0, names.lastIndex)
                        Adjust("Leads with layer", names[at], { set(st.copy(lead = (at - 1 + names.size) % names.size - 1)) }, compact = true) { set(st.copy(lead = (at + 1) % names.size - 1)) }
                    }
                    item { Adjust("Layer offset", "${(st.offset * 100).toInt()}%", { set(st.copy(offset = (st.offset - 0.1f).coerceAtLeast(0f))) }, compact = true) { set(st.copy(offset = (st.offset + 0.1f).coerceAtMost(1f))) } }
                    item { Text("Mixes out of this step into the next one. Half of it plays before this step ends." +
                        if (i == 0) " The journey starts straight on this step's scene." else "",
                        color = DIM, fontSize = 9.sp, modifier = Modifier.padding(6.dp)) }
                }
                item { Header("Step") }
                item { Row_("↑ Move earlier", false, id = "md:up", onFocus = {}) { if (i > 0) { update(j.steps.toMutableList().apply { add(i - 1, removeAt(i)) }); stepNo = i - 1 } } }
                item { Row_("↓ Move later", false, id = "md:down", onFocus = {}) { if (i < j.steps.lastIndex) { update(j.steps.toMutableList().apply { add(i + 1, removeAt(i)) }); stepNo = i + 1 } } }
                item { Row_("🗑 Remove this step", false, id = "md:rm", onFocus = {}) { update(j.steps.filterIndexed { k, _ -> k != i }); stepNo = -1 } }
            }
        } else {
            Text("VISUAL MEDITATION", color = CYAN, fontSize = 12.sp, letterSpacing = 1.5.sp)
            val now = Med.playing
            Text(if (now != null) "Playing ${now.name}, step ${Med.stepIndex + 1}" + if (Med.paused) " (paused)" else "" else "Plan a journey of scenes and sound", color = if (now != null) GREEN else DIM, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
            LoopColumn(state = FocusMem.list("med:list"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item { Box(Modifier.focusRequester(first)) {
                    if (now != null) Row_("■ Stop", false, id = "md:stop", onFocus = {}) { onStop() }
                    else Row_("▶ Play: " + (j?.name ?: "choose a journey"), false, id = "md:play", category = true, onFocus = {}) { if (j != null && j.steps.isNotEmpty()) onPlay(j) }
                } }
                // journeys as icons with their titles, like scenes and moods: OK selects one
                if (j != null) item { Adjust("Loop", if (j.loop) "on: starts again after the last step" else "off: ends after the last step", { update(j.steps, !j.loop) }, compact = true, onOk = { update(j.steps, !j.loop) }) { update(j.steps, !j.loop) } }
                if (!split) item { Header("My journeys") }
                if (!split) items(Med.journeys.map { it.name }.chunked(ICON_COLUMNS).size) { k ->
                    val row = Med.journeys.map { it.name }.chunked(ICON_COLUMNS)[k]
                    IconRow(row, count = { n -> if (n == j?.name) 1 else 0 }, idOf = { "md:j:$it" }, height = 36, label = { it }, onHint = {},
                        onPress = { n -> name = n }) { n, c, m -> SceneIcon(Med.journeys.firstOrNull { it.name == n }?.steps?.firstOrNull()?.let { Bg2.pristine(it.scene) }, c, m) }
                }
                if (!split && j != null && now == null) item { Row_("🔬 Play it with the audio test", false, id = "md:test", onFocus = {}) { if (j.steps.isNotEmpty()) { AudioProbe.start(ctx, j.name); onPlay(j) } } }
                if (AudioProbe.summary.isNotEmpty()) item { Text("Audio test: " + AudioProbe.summary, color = AMBER, fontSize = 9.sp, modifier = Modifier.padding(6.dp)) }
                if (j != null) {
                    item { Header("Steps · ${fmt(j.steps.sumOf { it.minutes.toDouble() }.toFloat())} min") }
                    j.steps.forEachIndexed { i, s ->
                        item(key = "st:$i:${s.scene}") { Row_("${i + 1}  ${s.scene} · ${s.audio.ifEmpty { "same sound" }} · ${fmt(s.minutes)} min", false, id = "md:st:$i", small = true, onFocus = { Med.focusStep = i; preview(s) }) { stepNo = i } }
                    }
                    item { Row_("＋ Add step", false, id = "md:add", onFocus = {}) { update(j.steps + JourneyStep(BUILT_IN_SCENES[0].name, Med.stacks.firstOrNull()?.name ?: "")); stepNo = j.steps.size } }
                }
                item { Header("Manage") }
                item { Row_("🎚 Build moods  ›", false, id = "md:audio", onFocus = {}) { onAudio() } }
                item { Row_("＋ New journey", false, id = "md:new", onFocus = {}) { val n = Med.newName("Journey") { x -> Med.journeys.any { it.name == x } }; Med.putJourney(ctx, Journey(n, emptyList())); name = n } }
                if (isMine) item { Row_(if (confirm) "🗑 OK again to delete" else "🗑 Delete this journey", confirm, id = "md:del", onFocus = {}) {
                    if (!confirm) confirm = true else { Med.journeys = Med.journeys.filter { it !== j }; Med.saveJourneys(ctx); confirm = false; name = Med.journeys.firstOrNull()?.name ?: "" }
                } }
                fun grid(title: String, list: List<Journey>, prefix: String) {
                    if (list.isEmpty()) return
                    item(key = "jh:$prefix") { Header(title) }
                    items(list.map { it.name }.chunked(ICON_COLUMNS).size, key = { "jg:$prefix:$it" }) { k ->
                        val row = list.map { it.name }.chunked(ICON_COLUMNS)[k]
                        IconRow(row, count = { n -> if (n == j.name) 1 else 0 }, idOf = { "md:$prefix:$it" }, height = 36, label = { it }, onHint = {},
                            onPress = { n -> name = n }) { n, c, m -> SceneIcon(list.firstOrNull { it.name == n }?.steps?.firstOrNull()?.let { Bg2.pristine(it.scene) }, c, m) }
                    }
                }
                if (!split) {
                    grid("Ready-made journeys", BUILT_IN_JOURNEYS.filter { b -> Med.journeys.none { it.name == b.name } }, "bj")
                    grid("Downloaded journeys", Packs.journeys.filter { b -> Med.journeys.none { it.name == b.name } }, "dj")
                    item { Row_("⬇ Get more journeys, scenes and moods  ›", false, id = "md:more", onFocus = {}) { getMore = true } }
                }
                // switch between this layout and the earlier one, any time
                // switching rebuilds the list, so the selection is put back on this row (it fell to the HOME tab before)
                item {
                    fun flip() { Med.setClassic(ctx, split); FocusMem.scope?.launch { delay(150); FocusMem.restoreSoon("adj:Layout") } }
                    Adjust("Layout", if (split) "presets on the right" else "classic (one list)", { flip() }, compact = true, onOk = { flip() }) { flip() }
                }
            }
        }
    }

    // ---- split layout: every journey preset in a column of its own on the right
    if (split) Panel(Modifier.align(Alignment.TopEnd).padding(end = 16.dp, bottom = 8.dp).width(PRESET_COLUMN.dp).fillMaxHeight().stageRight()
        // ← on the first icon of a row goes back to the left menu (Play, or Stop while one plays)
        .onPreviewKeyEvent { e ->
            val cur = FocusMem.current
            if (menuFor == null && e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft && cur != null && cur.startsWith("md:j:")) {
                val n = cur.removePrefix("md:j:")
                val lists = listOf(Med.journeys, BUILT_IN_JOURNEYS.filter { b -> Med.journeys.none { it.name == b.name } },
                    Packs.journeys.filter { b -> Med.journeys.none { it.name == b.name } })
                val first = lists.any { l -> l.indexOfFirst { it.name == n }.let { it >= 0 && it % 3 == 0 } }
                if (first) { FocusMem.restore(if (Med.playing != null) "row:md:stop" else "row:md:play"); true } else false
            } else if (cur == "row:md:more" && e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { FocusMem.restore("row:md:play"); true } else false
        }) {
        val target = menuFor?.let { n -> (Med.journeys + BUILT_IN_JOURNEYS + Packs.journeys).firstOrNull { it.name == n } }
        if (target != null) {
            val mine = Med.journeys.any { it.name == target.name }
            fun close(focus: String = "md:j:${target.name}") { menuFor = null; FocusMem.scope?.launch { delay(80); FocusMem.restoreSoon(focus) } }
            ItemMenu(target.name, if (mine) "Rename" else "Rename as my own copy", mine,
                // Manage: the journey is selected and the selection moves to its steps, on the left
                onEdit = { name = target.name; menuFor = null; FocusMem.scope?.launch { delay(120); FocusMem.restoreSoon(if (target.steps.isEmpty()) "row:md:add" else "row:md:st:0") } },
                onRename = { n ->
                    val t = n.trim()
                    if (t.isEmpty() || (Med.journeys + BUILT_IN_JOURNEYS + Packs.journeys).any { it.name == t }) false
                    else { if (mine) { Med.journeys = Med.journeys.map { if (it.name == target.name) it.copy(name = t) else it }; Med.saveJourneys(ctx) } else Med.putJourney(ctx, target.copy(name = t)); name = t; close("md:j:$t"); true }
                },
                onDelete = { Med.journeys = Med.journeys.filter { it.name != target.name }; Med.saveJourneys(ctx); if (name == target.name) name = Med.journeys.firstOrNull()?.name ?: ""; close("row:md:play") },
                onClose = { close() },
                actions = listOf(
                    "▶ Play" to { name = target.name; menuFor = null; if (target.steps.isNotEmpty()) onPlay(target) },
                    ("🔁 Loop: " + if (target.loop) "on (turn off)" else "off (turn on)") to { Med.putJourney(ctx, target.copy(loop = !target.loop)); name = target.name; close() },
                    "🔬 Play it with the audio test" to { name = target.name; menuFor = null; if (target.steps.isNotEmpty()) { AudioProbe.start(ctx, target.name); onPlay(target) } }
                ),
                editLabel = "⚙ Manage: steps, timing, transitions")
        } else {
            Text("JOURNEYS", color = CYAN, fontSize = 11.sp, letterSpacing = 1.2.sp)
            Text("OK = select · hold OK = play, loop, manage", color = DIM, fontSize = 9.sp, maxLines = 2, modifier = Modifier.padding(bottom = 4.dp))
            LoopColumn(state = FocusMem.list("med:presets"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                fun group(title: String, list: List<Journey>, prefix: String) {
                    if (list.isEmpty()) return
                    item(key = "ph:$prefix") { Header(title) }
                    items(list.map { it.name }.chunked(3).size, key = { "pg:$prefix:$it" }) { k ->
                        val row = list.map { it.name }.chunked(3)[k]
                        IconRow(row, count = { n -> if (n == j.name) 1 else 0 }, idOf = { "md:j:$it" }, height = 36, label = { it }, onHint = {},
                            onHold = { n -> menuFor = n },
                            onPress = { n -> name = n }) { n, c, m -> SceneIcon(list.firstOrNull { it.name == n }?.steps?.firstOrNull()?.let { Bg2.pristine(it.scene) }, c, m) }
                    }
                }
                group("My journeys", Med.journeys, "mj")
                group("Ready-made", BUILT_IN_JOURNEYS.filter { b -> Med.journeys.none { it.name == b.name } }, "bj")
                group("Downloaded", Packs.journeys.filter { b -> Med.journeys.none { it.name == b.name } }, "dj")
                item { Row_("⬇ Get more  ›", false, id = "md:more", onFocus = {}) { getMore = true } }
            }
        }
    }
    }
}

/** Width of the split layout's preset column, in the interface's dp. */
private const val PRESET_COLUMN = 200f

/** Under the monitor: the journey plotted in time, then the selected step's video layers, then its audio layers. */
@Composable
fun JourneyTimeline(j: Journey) {
    val total = j.steps.sumOf { it.minutes.toDouble() }.toFloat().coerceAtLeast(0.1f)
    val full = Stage.W
    val selected = j.steps.getOrNull(Med.focusStep) ?: j.steps.firstOrNull()
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(5.dp)
    androidx.compose.foundation.layout.Box(Modifier.offset(Stage.X.dp, (Stage.TILE_Y - 4f).dp).width(full.dp).height(104.dp).background(androidx.compose.ui.graphics.Color.Black)) {
        if (j.steps.isEmpty()) Text(j.name + " has no steps yet. Use ＋ Add step in the list.", color = DIM, fontSize = 10.sp, modifier = Modifier.padding(6.dp))
        var x = 0f
        j.steps.forEachIndexed { i, s ->
            val w = full * s.minutes / total
            val on = Med.playing === j && Med.stepIndex == i
            val focused = j.steps.getOrNull(Med.focusStep) === s
            val c = if (on) GREEN else if (focused) AMBER else CYAN.copy(alpha = 0.45f)
            androidx.compose.foundation.layout.Column(Modifier.offset(x.dp, 0.dp).width((w - 2f).coerceAtLeast(10f).dp).height(32.dp)
                .background(c.copy(alpha = 0.14f), shape).border(1.dp, c, shape).padding(horizontal = 4.dp, vertical = 2.dp)) {
                Text("${i + 1} " + s.scene, color = FG, fontSize = 8.sp, maxLines = 1)
                Text(fmt(s.minutes) + " min", color = DIM, fontSize = 7.sp, maxLines = 1)
            }
            // the blend into this step was set on the step before it
            val blendW = if (i > 0) full * (j.steps[i - 1].blendSec / 60f) / total else 0f
            if (blendW > 1f) androidx.compose.foundation.layout.Box(Modifier.offset((x - blendW / 2f).dp, 0.dp).width(blendW.dp).height(32.dp).background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, c.copy(alpha = 0.55f), androidx.compose.ui.graphics.Color.Transparent))))
            x += w
        }
        val scene = selected?.let { Bg2.pristine(it.scene) }
        androidx.compose.foundation.layout.Row(Modifier.offset(0.dp, 36.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("VIDEO", color = DIM, fontSize = 7.sp, modifier = Modifier.width(32.dp).padding(top = 5.dp))
            scene?.layers?.take(8)?.forEach { l ->
                Text(l.type.removePrefix(CLASSIC_PREFIX).removePrefix(LINES_PREFIX), color = FG, fontSize = 8.sp, maxLines = 1, modifier = Modifier.width(66.dp).border(1.dp, CYAN.copy(alpha = 0.3f), shape).padding(horizontal = 4.dp, vertical = 3.dp))
            }
        }
        val stack = selected?.let { st -> Med.stacks.firstOrNull { it.name == st.audio } }
        androidx.compose.foundation.layout.Row(Modifier.offset(0.dp, 60.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("AUDIO", color = DIM, fontSize = 7.sp, modifier = Modifier.width(32.dp).padding(top = 10.dp))
            if (stack == null) Text(if (selected?.audio.isNullOrEmpty()) "Keeps the sound already playing" else "Sound not found", color = DIM, fontSize = 8.sp, modifier = Modifier.padding(top = 9.dp))
            else stack.layers.take(8).forEach { a ->
                val c = if (a.on) FG.copy(alpha = 0.8f) else DIM.copy(alpha = 0.5f)
                androidx.compose.foundation.layout.Column(Modifier.width(66.dp).border(1.dp, CYAN.copy(alpha = 0.3f), shape).padding(horizontal = 4.dp, vertical = 2.dp)) {
                    WaveIcon(a.kind, c, Modifier.width(58.dp).height(16.dp))
                    Text(a.kind, color = c, fontSize = 7.sp, maxLines = 1)
                }
            }
        }
    }
}

/** A small picture of the sound. */
@Composable
fun WaveIcon(kind: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width; val h = size.height; val mid = h / 2
        val path = androidx.compose.ui.graphics.Path()
        val seedPoints = 48
        for (i in 0..seedPoints) {
            val x = i.toFloat() / seedPoints
            val y = when (kind) {
                "Tone" -> sin(2 * PI * x * 3).toFloat() * 0.8f
                GENERATOR_KIND -> (if (((x * 4f) % 1f) < 0.5f) 0.6f else -0.6f) * (0.6f + 0.4f * sin(2 * PI * x).toFloat())
                "Noise" -> sin(x * 91f) * sin(x * 37f + 1f) * 0.8f
                "Didgeridoo" -> (sin(2 * PI * x * 2).toFloat() * 0.6f + sin(2 * PI * x * 6).toFloat() * 0.25f)
                "Rain" -> if (i % 7 == 0) -0.85f else sin(x * 57f) * 0.2f
                "Ocean" -> sin(2 * PI * x * 1.2).toFloat() * 0.7f + sin(x * 67f) * 0.12f
                "Singing bowl" -> sin(2 * PI * x * 5).toFloat() * (1f - x * 0.75f) * 0.85f
                "Tibetan bells" -> sin(2 * PI * x * 11).toFloat() * kotlin.math.exp(-x * 3f) * 0.9f
                "Xylophone" -> sin(2 * PI * x * 9).toFloat() * kotlin.math.exp(-((x * 3f) % 1f) * 3.5f) * 0.9f
                "Temple gong" -> (sin(2 * PI * x * 4).toFloat() * 0.6f + sin(2 * PI * x * 9.3).toFloat() * 0.35f) * kotlin.math.exp(-x * 1.4f)
                "Tanpura" -> sin(2 * PI * x * 6).toFloat() * (0.35f + 0.5f * kotlin.math.exp(-((x * 4f) % 1f) * 2f))
                "Handpan" -> sin(2 * PI * x * 8).toFloat() * sin(PI * ((x * 2f) % 1f)).toFloat() * 0.9f
                "Kalimba" -> sin(2 * PI * x * 12).toFloat() * kotlin.math.exp(-((x * 4f) % 1f) * 5f) * 0.9f
                "Frame drum" -> sin(2 * PI * x * 5).toFloat() * kotlin.math.exp(-((x * 2f) % 1f) * 7f) * 0.95f
                else -> sin(2 * PI * x * 13).toFloat() * kotlin.math.exp(-((x * 2f) % 1f) * 2.5f) * 0.8f
            }
            val px = x * w; val py = mid + y * mid * 0.9f
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
    }
}
