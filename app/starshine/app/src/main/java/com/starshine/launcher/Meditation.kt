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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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

val AUDIO_KINDS = listOf("Tone", "Noise", "Didgeridoo", "Singing bowl", "Tibetan bells", "Xylophone", "Chimes", "Temple gong", "Tanpura", "Handpan", "Kalimba", "Frame drum", "Rain", "Ocean")

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
            "follow" to 0f, "pitch" to 220f, "every" to 8f, "note" to 65f, "mouth" to 0.5f, "mouthRate" to 0.35f, "breath" to 0.35f
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
                .setBufferSizeInBytes(maxOf(min, 88200)).build()   // half a second in hand, so a busy moment can't interrupt the sound
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
                track.write(pcm, 0, pcm.size)
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
            else -> (v.synth ?: AmbientSynth(a.kind, RATE, v.key.hashCode()).also { v.synth = it }).also { it.pitch = a["pitch"]; it.every = a["every"] }.fill(l, r, n)
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
data class Journey(val name: String, val steps: List<JourneyStep>) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("steps", JSONArray().also { a -> steps.forEach { a.put(it.toJson()) } })
    companion object { fun fromJson(o: JSONObject): Journey { val a = o.getJSONArray("steps"); return Journey(o.getString("name"), (0 until a.length()).map { JourneyStep.fromJson(a.getJSONObject(it)) }) } }
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
    private var loaded = false
    private fun sp(ctx: Context) = ctx.getSharedPreferences("starshine_med", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
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
        Drafts.renamed(ctx, old, new)
        return true
    }
    fun renameSceneInJourneys(ctx: Context, old: String, new: String) {
        journeys = journeys.map { j -> j.copy(steps = j.steps.map { if (it.scene == old) it.copy(scene = new) else it }) }; saveJourneys(ctx)
    }
    fun putStack(ctx: Context, s: AudioStack) { stacks = if (stacks.any { it.name == s.name }) stacks.map { if (it.name == s.name) s else it } else stacks + s; saveStacks(ctx) }
    fun newName(base: String, taken: (String) -> Boolean): String { var i = 1; while (taken("$base $i")) i++; return "$base $i" }

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
        var i = 0
        suspend fun waitFor(seconds: Float) {
            val skipAt = skip
            var left = seconds
            while (left > 0f && playing === j && skip == skipAt) { delay(250); if (!paused) { left -= 0.25f; remaining = left } }
        }
        while (i < j.steps.size && playing === j) {
            val s = j.steps[i]; stepIndex = i
            val nextBlend = j.steps.getOrNull(i + 1)?.blendSec ?: 0f
            Bg2.pristine(s.scene)?.let { base ->
                val scene = fresh(base)
                Bg2.blendMode = s.transition.coerceIn(0, TRANSITIONS.lastIndex)
                Bg2.blendLead = scene.layers.getOrNull(s.lead)?.id ?: -1
                Bg2.blendOffset = s.offset
                Bg2.blendMix = 0f
                Bg2.blendFrom = if (Bg2.applied && s.blendSec > 0f) Bg2.current else null
                AudioProbe.event("step ${i + 1}: ${s.scene}, ${scene.layers.size} layers, transition of ${s.blendSec} s starts" + if (Bg2.blendFrom != null) ", from ${Bg2.blendFrom?.layers?.size} layers" else "")
                show(scene)
                stacks.firstOrNull { it.name == s.audio }?.let { Sound.playStack(ctx, it, s.blendSec) }
                val total = (s.blendSec * 1000).toLong().coerceAtLeast(1)
                var done = 0L; var last = System.currentTimeMillis()
                while (done < total && playing === j) {
                    delay(33)
                    val now = System.currentTimeMillis()
                    if (!paused) done += now - last
                    last = now
                    Bg2.blendMix = (done.toFloat() / total).coerceIn(0f, 1f)
                }
                Bg2.blendFrom = null; Bg2.blendMix = 1f
                AudioProbe.event("step ${i + 1}: transition ended")
            }
            // half of this step's transition and half of the next one fall inside this step's time
            waitFor((s.minutes * 60f - s.blendSec / 2f - nextBlend / 2f).coerceAtLeast(0f))
            i++
        }
        if (playing === j) { playing = null; Sound.stop() }
    }
}

/* ---------------- planner ---------------- */

private fun fmt(v: Float, d: Int = 1) = String.format(Locale.US, "%.${d}f", v)
private fun <T> cycle(l: List<T>, cur: T, d: Int): T { val i = l.indexOf(cur).coerceAtLeast(0); return l[(i + d + l.size) % l.size] }

@Composable
fun MeditationTab(onPlay: (Journey) -> Unit, onStop: () -> Unit, onAudio: () -> Unit) {
    val ctx = LocalContext.current
    var name by Med.journeyPick
    var stepNo by remember { mutableIntStateOf(-1) }
    var confirm by remember { mutableStateOf(false) }
    val j = Med.journeys.firstOrNull { it.name == name } ?: Med.journeys.firstOrNull()
    val first = remember { FocusRequester() }
    Med.planning = j
    Med.planStep = stepNo
    if (stepNo >= 0) Med.focusStep = stepNo
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Med.planning = null } }
    val scenes = remember(Bg2.mine) { Bg2.mine.map { it.name } + BUILT_IN_SCENES.map { it.name } }
    fun preview(s: JourneyStep?) { s?.let { st -> Bg2.pristine(st.scene)?.let { Bg2.preview = it; lastBrowsedScene = it.name } } }
    fun update(steps: List<JourneyStep>) { j?.let { Med.putJourney(ctx, it.copy(steps = steps)) } }
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
    Panel(Modifier.padding(start = 24.dp, bottom = 8.dp).width(256.dp).fillMaxHeight().stageBeside()) {
        val st = j?.steps?.getOrNull(stepNo)
        if (j != null && st != null) {
            Text("STEP ${stepNo + 1} · ${j.name}".uppercase(), color = CYAN, fontSize = 11.sp, letterSpacing = 1.2.sp, maxLines = 1)
            Text("OK = edit · ↑↓ next · Back = steps", color = DIM, fontSize = 9.sp, modifier = Modifier.padding(bottom = 4.dp))
            val i = stepNo
            fun set(n: JourneyStep) = update(j.steps.toMutableList().also { it[i] = n })
            val audios = listOf("") + Med.stacks.map { it.name }
            LoopColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item { Box(Modifier.focusRequester(first)) { Adjust("Scene", st.scene, { val n = st.copy(scene = cycle(scenes, st.scene, -1)); set(n); preview(n) }, compact = true) { val n = st.copy(scene = cycle(scenes, st.scene, 1)); set(n); preview(n) } } }
                item { Adjust("Audio", st.audio.ifEmpty { "keep playing" }, { set(st.copy(audio = cycle(audios, st.audio, -1))) }, compact = true) { set(st.copy(audio = cycle(audios, st.audio, 1))) } }
                item { Adjust("Time", fmt(st.minutes) + " min", { set(st.copy(minutes = ((st.minutes - 0.1f) * 10f).let { Math.round(it) / 10f }.coerceAtLeast(0.1f))) }, compact = true) { set(st.copy(minutes = ((st.minutes + 0.1f) * 10f).let { Math.round(it) / 10f })) } }
                item { Header(if (stepNo == 0) "Transition from your current background" else "Transition from the previous background") }
                item { Adjust("Type", TRANSITIONS[st.transition.coerceIn(0, TRANSITIONS.lastIndex)], { set(st.copy(transition = (st.transition - 1 + TRANSITIONS.size) % TRANSITIONS.size)) }, compact = true) { set(st.copy(transition = (st.transition + 1) % TRANSITIONS.size)) } }
                item { Adjust("Length", "${st.blendSec.toInt()} s", { set(st.copy(blendSec = (st.blendSec - 1f).coerceAtLeast(0f))) }, compact = true) { set(st.copy(blendSec = st.blendSec + 1f)) } }
                item {
                    val layers = Bg2.pristine(st.scene)?.layers ?: emptyList()
                    val names = listOf("none") + layers.map { it.type.removePrefix(CLASSIC_PREFIX).removePrefix(LINES_PREFIX) }
                    val at = (st.lead + 1).coerceIn(0, names.lastIndex)
                    Adjust("Leads with layer", names[at], { set(st.copy(lead = (at - 1 + names.size) % names.size - 1)) }, compact = true) { set(st.copy(lead = (at + 1) % names.size - 1)) }
                }
                item { Adjust("Layer offset", "${(st.offset * 100).toInt()}%", { set(st.copy(offset = (st.offset - 0.1f).coerceAtLeast(0f))) }, compact = true) { set(st.copy(offset = (st.offset + 0.1f).coerceAtMost(1f))) } }
                item { Text(
                    (if (stepNo == 0) "Mixes out of the background on screen when you press play, into this step. A classic background or a file cuts straight in. "
                    else "Mixes out of the previous background, into this step. Half of it plays before the previous step ends. ") +
                        "It doesn't affect the next step: set that on the next step.",
                    color = DIM, fontSize = 9.sp, modifier = Modifier.padding(6.dp)) }
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
                item { Box(Modifier.focusRequester(first)) { Adjust("Journey", j?.name ?: "none", { name = cycle(Med.journeys.map { it.name }, name, -1) }, compact = true) { name = cycle(Med.journeys.map { it.name }, name, 1) } } }
                if (j != null) item {
                    if (now != null) Row_("■ Stop", false, id = "md:stop", onFocus = {}) { onStop() }
                    else Row_("▶ Play this journey", false, id = "md:play", category = true, onFocus = {}) { if (j.steps.isNotEmpty()) onPlay(j) }
                }
                if (j != null && now == null) item { Row_("🔬 Play it with the audio test", false, id = "md:test", onFocus = {}) { if (j.steps.isNotEmpty()) { AudioProbe.start(ctx, j.name); onPlay(j) } } }
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
                if (j != null) item { Row_(if (confirm) "🗑 OK again to delete" else "🗑 Delete this journey", confirm, id = "md:del", onFocus = {}) {
                    if (!confirm) confirm = true else { Med.journeys = Med.journeys.filter { it !== j }; Med.saveJourneys(ctx); confirm = false; name = Med.journeys.firstOrNull()?.name ?: "" }
                } }
            }
        }
    }
}

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
            val blendW = if (i > 0) full * (s.blendSec / 60f) / total else 0f
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
