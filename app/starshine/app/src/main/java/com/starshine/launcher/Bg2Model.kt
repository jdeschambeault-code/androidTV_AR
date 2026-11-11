package com.starshine.launcher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Bg": layered scenes built as a small node graph. Each node (layer) has a generator, a look, a place in the blend stack,
 * optional links to other nodes (mask by, warp by) and sound-reaction slots. Numeric settings live in a map so
 * saving, the editor rows and the renderer all share one table ([BgParams.ALL]).
 */
const val BG2_ID = "@layers"

enum class GenKind { Line, Solid, Points, Screen, Classic, Canvas, Media }

const val CLASSIC_PREFIX = "Classic: "
const val LINES_PREFIX = "Lines: "

data class Generator(val name: String, val kind: GenKind)

val GENERATORS: List<Generator> = listOf(
    Generator("Flower of Life", GenKind.Line), Generator("Seed of Life", GenKind.Line), Generator("Metatron's Cube", GenKind.Line),
    Generator("Floral mandala", GenKind.Line), Generator("Golden spiral", GenKind.Line), Generator("Hex lattice", GenKind.Line),
    Generator("Tetrahedron", GenKind.Solid), Generator("Cube", GenKind.Solid), Generator("Octahedron", GenKind.Solid),
    Generator("Icosahedron", GenKind.Solid), Generator("Dodecahedron", GenKind.Solid), Generator("Star tetrahedron", GenKind.Solid),
    Generator("Nested solids", GenKind.Solid), Generator("Torus", GenKind.Solid),
    Generator("Starfield", GenKind.Points), Generator("Orbs", GenKind.Points), Generator("Dust", GenKind.Points),
    Generator("Clouds", GenKind.Screen), Generator("Fire", GenKind.Screen), Generator("Water", GenKind.Screen), Generator("Nebula", GenKind.Screen),
    Generator("Iris", GenKind.Screen), Generator("Sun flare", GenKind.Screen), Generator("Candle", GenKind.Screen),
    Generator("Globe", GenKind.Screen), Generator("Honeycomb", GenKind.Screen), Generator("Aether", GenKind.Screen),
    Generator(FRACTAL, GenKind.Screen)
) + Solids.LINES.map { Generator(it, GenKind.Line) } + Solids.SOLIDS.map { Generator(it, GenKind.Solid) } +
    PROCEDURAL.map { Generator(CLASSIC_PREFIX + it, GenKind.Canvas) } + SHADERS.map { Generator(it, GenKind.Classic) } +
    VISUAL_MODES.map { Generator(LINES_PREFIX + it, GenKind.Canvas) } + Generator("Media file", GenKind.Media)

fun generator(name: String) = GENERATORS.firstOrNull { it.name == name } ?: GENERATORS[0]

object BgParams {
    val BLENDS = listOf("Add", "Screen", "Normal", "Multiply")
    val FOLDS = listOf("Off", "Mirror ←→", "Mirror 4-way", "Diamond", "Kaleido 6", "Kaleido 8", "Kaleido 12")
    /** Voice follows speech/chant syllables (300 Hz-3 kHz), Swell is the slow average: the two that suit meditative audio. */
    val SOURCES = listOf("Voice", "Level", "Bass", "Mid", "High", "Beat", "Swell", "Tone beat", "Tone pitch")
    val TARGETS = listOf("Glow", "Opacity", "Spin", "Size", "Color", "Warp", "Detail", "Fade")

    class P(val key: String, val label: String, val def: Float, val step: Float, val min: Float, val max: Float, val fmt: (Float) -> String = { pctf(it) })

    private fun pctf(v: Float) = "${Math.round(v * 100)}%"
    private fun deg(v: Float) = "${Math.round(v)}°"
    private fun num(v: Float) = String.format(java.util.Locale.US, "%.2f", v)

    val OPACITY = P("opacity", "Opacity", 1f, 0.05f, 0f, 1f)
    val SIZE = P("scale", "Size", 1f, 0.05f, 0.05f, 6f)
    val SPEED = P("speed", "Speed", 1f, 0.1f, -5f, 10f, { num(it) + "x" })
    val SPIN = P("spin", "Rotation speed", 0.05f, 0.01f, -2f, 2f, { num(it) })
    val ANGLE = P("angle", "Rotate Z", 0f, 5f, -180f, 180f, ::deg)
    val TILTX = P("tiltX", "Rotate X", 0f, 5f, -180f, 180f, ::deg)
    val TILTY = P("tiltY", "Rotate Y", 0f, 5f, -180f, 180f, ::deg)
    val HUE = P("hue", "Color", 0f, 10f, 0f, 350f, ::deg)
    val DRIFT = P("hueDrift", "Color drift", 0f, 1f, -60f, 60f, { "${Math.round(it)}°/s" })
    val SAT = P("sat", "Saturation", 1f, 0.1f, 0f, 2f)
    val GLOW = P("glow", "Glow", 1f, 0.1f, 0f, 5f)
    val DETAIL = P("detail", "Detail / line", 0.5f, 0.05f, 0f, 1f)
    val FADE = P("fade", "Edge fade", 0f, 0.1f, 0f, 1f)
    val TRAIL = P("trail", "Trails", 0f, 0.05f, 0f, 0.98f)
    val X = P("x", "Position X", 0f, 0.02f, -1f, 1f, { num(it) })
    val Y = P("y", "Position Y", 0f, 0.02f, -1f, 1f, { num(it) })
    /** Depth: 0 = on the screen, higher = further away, lower = closer. Works on every kind of layer. */
    val Z = P("z", "Position Z", 0f, 0.05f, -2f, 3f, { num(it) })
    val STX = P("stx", "Scale X", 1f, 0.05f, 0.05f, 6f)
    val STY = P("sty", "Scale Y", 1f, 0.05f, 0.05f, 6f)
    val STZ = P("stz", "Scale Z", 1f, 0.05f, 0.05f, 6f)
    val MASK_AMT = P("maskAmt", "Mask amount", 1f, 0.1f, -1f, 1f)
    val WARP_AMT = P("warpAmt", "Warp amount", 0.1f, 0.02f, 0f, 1f)
    val R1A = P("r1a", "React 1 amount", 0f, 0.1f, 0f, 3f)
    val R2A = P("r2a", "React 2 amount", 0f, 0.1f, 0f, 3f)

    /** Slider rows, in editor order. */
    val LOOK = listOf(OPACITY, SIZE, SPEED, SPIN, ANGLE, TILTX, TILTY, HUE, DRIFT, SAT, GLOW, DETAIL, FADE, TRAIL, X, Y, Z, STX, STY, STZ)
    val ALL = LOOK + listOf(MASK_AMT, WARP_AMT, R1A, R2A) + Fractal.PARAMS
    val DEFAULTS: Map<String, Float> = ALL.associate { it.key to it.def } + mapOf(
        "on" to 1f, "show" to 1f, "blend" to 0f, "fold" to 0f, "maskBy" to -1f, "warpBy" to -1f,
        "r1s" to 0f, "r1t" to 0f, "r2s" to 6f, "r2t" to 1f, "persp" to 0f, "fit" to 0f, "fset" to 1f, "pal" to 0f
    )
}

data class Layer(val id: Int, val type: String, val v: Map<String, Float> = emptyMap(), val media: String = "") {
    /** Your own pictures and videos sit still unless you ask for rotation. */
    operator fun get(k: String): Float = v[k] ?: (if (k == "spin" && kind == GenKind.Media) 0f else BgParams.DEFAULTS[k] ?: 0f)
    fun set(k: String, x: Float) = copy(v = v + (k to x))
    val on get() = this["on"] > 0.5f
    val show get() = this["show"] > 0.5f
    val kind get() = generator(type).kind
    fun toJson(): JSONObject = JSONObject().put("id", id).put("type", type).put("media", media)
        .put("v", JSONObject().also { o -> v.forEach { (k, x) -> o.put(k, x.toDouble()) } })
    companion object {
        fun fromJson(o: JSONObject): Layer {
            val vo = o.optJSONObject("v") ?: JSONObject()
            val m = HashMap<String, Float>(); vo.keys().forEach { m[it] = vo.getDouble(it).toFloat() }
            return Layer(o.getInt("id"), o.getString("type"), m, o.optString("media", ""))
        }
    }
}

/**
 * A scene = layers (the background and its look) + an optional sound. [group] places it in the browser.
 * [fx] holds the camera and render effects applied to the finished picture ([RenderFx]).
 */
data class BgScene(val name: String, val group: String, val layers: List<Layer>, val sound: String = "", val fx: Map<String, Float> = emptyMap()) {
    val nextId get() = (layers.maxOfOrNull { it.id } ?: 0) + 1
    fun fx(k: String): Float = fx[k] ?: RenderFx.DEFAULTS[k] ?: 0f
    fun toJson(): JSONObject = JSONObject().put("name", name).put("group", group).put("sound", sound)
        .put("layers", JSONArray().also { a -> layers.forEach { a.put(it.toJson()) } })
        .also { if (fx.isNotEmpty()) it.put("fx", JSONObject().also { o -> fx.forEach { (k, x) -> o.put(k, x.toDouble()) } }) }
    companion object {
        fun fromJson(o: JSONObject): BgScene {
            val a = o.getJSONArray("layers")
            val fo = o.optJSONObject("fx"); val fx = HashMap<String, Float>(); fo?.keys()?.forEach { fx[it] = fo.getDouble(it).toFloat() }
            return BgScene(o.getString("name"), o.optString("group", MY_SCENES), (0 until a.length()).map { Layer.fromJson(a.getJSONObject(it)) }, o.optString("sound", ""), fx)
        }
    }
}

val TRANSITIONS = listOf("Cross dissolve", "Dip to black", "Luma dissolve", "Wipe", "Iris", "Spiral")
const val MY_SCENES = "My scenes"
const val CLASSIC_GROUP = "Classic backgrounds"

/** Sound-only moods: they never touch the background. */
data class AudioMood(val name: String, val ambient: String)
val AUDIO_MOODS = listOf(AudioMood("Movies · silence", "Off"), AudioMood("Sleep · Rain", "Rain"), AudioMood("Calm · Ocean", "Ocean"))

private fun L(id: Int, type: String, vararg p: Pair<String, Float>) = Layer(id, type, mapOf(*p))
/** Voice-driven glow plus a slow swell on opacity: the default reaction of geometry layers. */
private val VOICE = arrayOf("r1s" to 0f, "r1t" to 0f, "r1a" to 1.2f, "r2s" to 6f, "r2t" to 2f, "r2a" to 0.6f)
private val SWELL = arrayOf("r1s" to 6f, "r1t" to 7f, "r1a" to 0.5f)

val BUILT_IN_SCENES: List<BgScene> = listOf(
    // first in the list: what a new install shows
    BgScene("Default", "Elements", listOf(
        L(1, "Sun flare", "warpBy" to 2f, "warpAmt" to 0.32f, "r1s" to 6f, "r1t" to 5f, "r1a" to 0.4f, "blend" to 0f, "scale" to 4.7f, "speed" to 1.3f, "fold" to 4f, "fade" to 0.5f, "hue" to 0f, "opacity" to 1f),
        L(2, "Dodecahedron", "scale" to 0.7f, "spin" to 0.05f, "hue" to 190f, "sat" to 0.25f, "detail" to 0.75f, "glow" to 0.9f, *VOICE)
    )),
    BgScene("Temple of Light", "Sacred geometry", listOf(
        L(1, "Starfield", "detail" to 0.35f, "speed" to 0.5f, "opacity" to 0.8f),
        L(2, "Orbs", "hue" to 180f, "opacity" to 0.5f, "detail" to 0.4f),
        L(3, "Flower of Life", "scale" to 1.4f, "spin" to 0.015f, "hue" to 40f, "sat" to 0.6f, "opacity" to 0.7f, "fade" to 1f, "detail" to 0.25f, *SWELL),
        L(4, "Dodecahedron", "scale" to 0.7f, "spin" to 0.08f, "hue" to 30f, "sat" to 0.35f, "glow" to 1.4f, *VOICE)
    )),
    BgScene("Flower bloom", "Sacred geometry", listOf(
        L(1, "Aether", "hue" to 250f, "opacity" to 0.6f),
        L(2, "Flower of Life", "scale" to 1.1f, "spin" to 0.03f, "hue" to 200f, "hueDrift" to 3f, "detail" to 0.3f, "trail" to 0.4f, *VOICE),
        L(3, "Seed of Life", "scale" to 0.55f, "spin" to -0.06f, "hue" to 40f, "sat" to 0.5f, "r1s" to 2f, "r1t" to 3f, "r1a" to 0.4f)
    )),
    BgScene("Metatron", "Sacred geometry", listOf(
        L(1, "Nebula", "hue" to 200f, "opacity" to 0.55f, "fade" to 0.8f),
        L(2, "Metatron's Cube", "scale" to 1.2f, "spin" to 0.02f, "hue" to 180f, "sat" to 0.5f, "detail" to 0.22f, *VOICE),
        L(3, "Star tetrahedron", "scale" to 0.45f, "spin" to 0.09f, "hue" to 45f, "sat" to 0.7f, "glow" to 1.3f, "r1s" to 5f, "r1t" to 2f, "r1a" to 0.8f)
    )),
    BgScene("Floral mandala", "Sacred geometry", listOf(
        L(1, "Floral mandala", "scale" to 1.4f, "spin" to 0.02f, "hue" to 300f, "hueDrift" to 2f, "detail" to 0.25f, "fold" to 0f, "trail" to 0.55f, *VOICE),
        L(2, "Floral mandala", "scale" to 0.7f, "spin" to -0.05f, "hue" to 40f, "detail" to 0.2f, "r1s" to 1f, "r1t" to 0f, "r1a" to 1f),
        L(3, "Sun flare", "scale" to 0.8f, "hue" to 0f, "glow" to 0.7f, "r1s" to 6f, "r1t" to 0f, "r1a" to 0.7f)
    )),
    BgScene("Water", "Elements", listOf(
        L(1, "Water", "fold" to 2f, "hue" to 0f, "fade" to 0.5f, "opacity" to 0.9f, "r1s" to 6f, "r1t" to 5f, "r1a" to 0.4f),
        L(2, "Dodecahedron", "scale" to 0.95f, "spin" to 0.05f, "hue" to 190f, "sat" to 0.25f, "detail" to 0.75f, "glow" to 0.9f, *VOICE)
    )),
    BgScene("Air", "Elements", listOf(
        L(1, "Clouds", "fold" to 3f, "detail" to 0.25f, "speed" to 1.3f),
        L(2, "Octahedron", "blend" to 1f, "scale" to 1.1f, "spin" to 0.04f, "hue" to 210f, "sat" to 0.2f, "detail" to 0.7f, "glow" to 1.6f, *VOICE),
        L(3, "Octahedron", "blend" to 1f, "scale" to 0.5f, "spin" to -0.07f, "sat" to 0.1f, "detail" to 0.4f, "glow" to 1.3f)
    )),
    BgScene("Fire", "Elements", listOf(
        L(1, "Fire", "fold" to 1f, "fade" to 0.9f, "r1s" to 5f, "r1t" to 0f, "r1a" to 0.5f),
        L(2, "Sun flare", "scale" to 1.2f, "opacity" to 0.5f, "hue" to 350f),
        L(3, "Star tetrahedron", "scale" to 0.75f, "spin" to 0.07f, "hue" to 225f, "sat" to 0.4f, "detail" to 0.35f, "glow" to 1.4f, *VOICE)
    )),
    BgScene("Earth", "Elements", listOf(
        L(1, "Fire", "fold" to 3f, "hue" to 20f, "sat" to 0.5f, "opacity" to 0.5f, "speed" to 0.15f),
        L(2, "Flower of Life", "scale" to 1.7f, "spin" to 0.01f, "hue" to 42f, "sat" to 0.35f, "glow" to 1.3f, "detail" to 0.3f, *SWELL),
        L(3, "Cube", "scale" to 0.65f, "spin" to 0.03f, "hue" to 45f, "sat" to 0.2f, "detail" to 0.5f, *VOICE),
        L(4, "Globe", "blend" to 2f, "scale" to 0.85f, "speed" to 0.6f)
    )),
    BgScene("Ether", "Elements", listOf(
        L(1, "Honeycomb", "show" to 0f, "detail" to 0.35f, "scale" to 1f),
        L(2, "Aether", "hue" to 300f, "scale" to 1.4f, "maskBy" to 1f, "maskAmt" to 0.85f, "r1s" to 0f, "r1t" to 4f, "r1a" to 0.5f),
        L(3, "Nested solids", "scale" to 1f, "spin" to 0.06f, "sat" to 0.35f, "glow" to 1.5f, "detail" to 0.45f, "hueDrift" to 4f, *VOICE)
    )),
    BgScene("Star temple", "Space", listOf(
        L(1, "Starfield", "detail" to 0.65f, "speed" to 1.2f),
        L(2, "Metatron's Cube", "scale" to 1.5f, "spin" to 0.01f, "hue" to 200f, "sat" to 0.4f, "opacity" to 0.45f, "detail" to 0.2f, "fade" to 0.8f, *SWELL)
    )),
    BgScene("Nebula orbs", "Space", listOf(
        L(1, "Nebula", "hue" to 0f, "opacity" to 0.8f),
        L(2, "Orbs", "detail" to 0.6f, "hue" to 180f, "glow" to 1.2f, *VOICE),
        L(3, "Dust", "detail" to 0.5f, "opacity" to 0.7f)
    )),
    BgScene("Warp gate", "Space", listOf(
        L(1, "Warp speed", "speed" to 0.7f, "opacity" to 0.9f),
        L(2, "Torus", "scale" to 1f, "spin" to 0.05f, "hue" to 170f, "detail" to 0.25f, "trail" to 0.3f, "r1s" to 2f, "r1t" to 2f, "r1a" to 0.8f)
    )),
    BgScene("Inner eye", "Light", listOf(
        L(1, "Iris", "detail" to 0.6f, "scale" to 1f, "r1s" to 0f, "r1t" to 0f, "r1a" to 0.8f),
        L(2, "Seed of Life", "scale" to 0.17f, "spin" to 0.1f, "sat" to 0.3f, "detail" to 0.1f)
    )),
    BgScene("Sun", "Light", listOf(
        L(1, "Sun flare", "detail" to 0.4f, "glow" to 1.1f, "r1s" to 6f, "r1t" to 0f, "r1a" to 0.8f),
        L(2, "Dust", "detail" to 0.3f, "hue" to 40f, "opacity" to 0.5f)
    )),
    BgScene("Candle", "Light", listOf(
        L(1, "Candle", "scale" to 1f, "r1s" to 6f, "r1t" to 0f, "r1a" to 0.4f),
        L(2, "Flower of Life", "scale" to 1.5f, "spin" to 0.005f, "hue" to 40f, "sat" to 0.5f, "opacity" to 0.16f, "detail" to 0.15f, "fade" to 1f)
    )),
    BgScene("Hologram", "Light", listOf(
        L(1, "Honeycomb", "detail" to 0.25f, "scale" to 1f, "fade" to 1f, "opacity" to 0.55f, "hueDrift" to 6f),
        L(2, "Nested solids", "scale" to 1.1f, "spin" to 0.07f, "sat" to 0.5f, "detail" to 0.6f, "glow" to 1.8f, "hueDrift" to 8f, *VOICE)
    ))
)

val SCENE_GROUPS = listOf(MY_SCENES, "Sacred geometry", "Elements", "Space", "Light")

/** Current scene, saved scenes and the master switches. Compose state, read directly by the render thread. */
object Bg2 {
    var current by mutableStateOf(BUILT_IN_SCENES[0])
    /** What the Bg workspace shows and edits. It reaches the real background only through Apply. */
    var preview by mutableStateOf(BUILT_IN_SCENES[0])
    /** True while the layered engine is the chosen background. */
    var applied by mutableStateOf(false)
    var fullPreview by mutableStateOf(false)
    /** Monitor: show just this node (-1 = whole scene). */
    var soloId by mutableIntStateOf(-1)
    /** A journey blending between scenes: the old scene and how far along the blend is (0..1). */
    @Volatile var blendFrom: BgScene? = null
    @Volatile var blendMix = 1f
    /** Index into [TRANSITIONS]. */
    @Volatile var blendMode = 0
    /** Layer of the new scene that leads the transition (-1 = none). */
    @Volatile var blendLead = -1
    /** 0 = all layers together, higher = they arrive one after another. */
    @Volatile var blendOffset = 0f
    /** How bright the picture is right now (0..1), measured by the renderer. Tone layers can follow it. */
    @Volatile var sceneLight = 0f
    /** The builder is open: the layer tiles under the monitor are live controls. */
    /** Add layer is open: the layer tiles can't take the selection, so neither the pointer nor a lost focus can open their settings. */
    val addingState = androidx.compose.runtime.mutableStateOf(false)
    var adding by addingState
    /** Takes the selection to the top of the open settings list, whatever it's scrolled to. */
    var panelTop: (() -> Unit)? = null
    var building by mutableStateOf(false)
    /** Layer tile picked up for reordering, or -1. */
    var movingNode by mutableIntStateOf(-1)
    /** First row of the builder panel, so a layer tile can send focus into its settings. */
    var panelFirst: androidx.compose.ui.focus.FocusRequester? = null
    /** A classic background being browsed: shown full screen behind the list, not applied. */
    var classicView by mutableStateOf<String?>(null)
    /** Node highlighted on the tile strip. */
    var focusNode by mutableIntStateOf(-1)
    /** Graphics quality, global: 0 = 540p, 1 = 720p, 2 = 1080p. */
    var quality by mutableIntStateOf(0)
    val QUALITY = listOf("540p · smoothest", "720p · sharper", "1080p · sharpest, may drop frames")
    val scale: Float get() = when (quality) { 1 -> 4f / 3f; 2 -> 2f; else -> 1f }
    fun setQuality(ctx: Context, q: Int) { quality = q; sp(ctx).edit().putInt("quality", q).apply() }
    var mine by mutableStateOf(listOf<BgScene>())
    /** Name of the last applied scene; edits keep it until saved under a new one. */
    var reactOn by mutableStateOf(true)
    /** Set when Save ran: lets the renderer stay unaware of persistence. */
    private var loaded = false

    private fun sp(ctx: Context) = ctx.getSharedPreferences("starshine_bg2", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        filesDir = ctx.getExternalFilesDir("backgrounds")
        Fractal.load(ctx)
        if (loaded) return
        loaded = true
        val p = sp(ctx)
        runCatching { p.getString("current", null)?.let { current = BgScene.fromJson(JSONObject(it)) } }
        runCatching { p.getString("mine", null)?.let { s -> val a = JSONArray(s); mine = (0 until a.length()).map { BgScene.fromJson(a.getJSONObject(it)) } } }
        reactOn = p.getBoolean("react", true)
        quality = p.getInt("quality", 0)
        preview = current
    }

    /** Once: the scene that became Default leaves My scenes, and Default becomes the background. */
    fun adoptDefault(ctx: Context) {
        val p = sp(ctx)
        if (p.getBoolean("defaultAdopted", false)) return
        val d = BUILT_IN_SCENES[0]
        mine.firstOrNull { it.name == "Water 2" && it.layers.map { l -> l.type } == d.layers.map { l -> l.type } }?.let {
            mine = mine - it; saveMine(ctx); Med.renameSceneInJourneys(ctx, it.name, d.name)
        }
        setCurrent(ctx, d); preview = d
        ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE).edit().putString("bg", BG2_ID).apply()
        p.edit().putBoolean("defaultAdopted", true).apply()
    }

    fun setCurrent(ctx: Context, s: BgScene) { current = s; sp(ctx).edit().putString("current", s.toJson().toString()).apply() }
    fun setReact(ctx: Context, on: Boolean) { reactOn = on; sp(ctx).edit().putBoolean("react", on).apply() }

    private fun saveMine(ctx: Context) = sp(ctx).edit().putString("mine", JSONArray().also { a -> mine.forEach { a.put(it.toJson()) } }.toString()).apply()

    private fun taken(n: String) = mine.any { it.name == n } || BUILT_IN_SCENES.any { it.name == n }

    /** An empty scene for building from scratch. */
    fun blank(): BgScene {
        var i = 1
        while (taken("New scene $i")) i++
        return BgScene("New scene $i", MY_SCENES, emptyList())
    }

    /** Saves a scene as a new preset ("<name> 2", "<name> 3"...). It doesn't change what's on screen. */
    fun saveAsNew(ctx: Context, scene: BgScene): BgScene {
        if (!taken(scene.name)) { val s = scene.copy(group = MY_SCENES); mine = mine + s; saveMine(ctx); return s }
        val base = scene.name.replace(Regex("\\s+\\d+$"), "")
        var i = 2
        var n = "$base $i"
        while (taken(n)) { i++; n = "$base $i" }
        val s = scene.copy(name = n, group = MY_SCENES)
        mine = mine + s; saveMine(ctx)
        return s
    }

    /** Overwrites a saved preset; false if the scene isn't one of yours. */
    fun overwrite(ctx: Context, scene: BgScene): Boolean {
        if (mine.none { it.name == scene.name }) return false
        mine = mine.map { if (it.name == scene.name) scene.copy(group = MY_SCENES) else it }; saveMine(ctx); return true
    }

    /** A scene of your own changes name everywhere it's used. A built-in one stays and you get a renamed copy. */
    fun rename(ctx: Context, old: String, new: String): Boolean {
        if (taken(new)) return false
        val s = pristine(old) ?: return false
        if (mine.any { it.name == old }) {
            mine = mine.map { if (it.name == old) it.copy(name = new) else it }
            Med.renameSceneInJourneys(ctx, old, new)
            if (current.name == old) setCurrent(ctx, current.copy(name = new))
        } else mine = mine + s.copy(name = new, group = MY_SCENES)
        saveMine(ctx)
        if (preview.name == old) preview = preview.copy(name = new)
        Drafts.renamed(ctx, old, new)
        return true
    }
    fun delete(ctx: Context, name: String) { mine = mine.filter { it.name != name }; saveMine(ctx) }

    private var filesDir: java.io.File? = null

    /** A classic background or a file as a scene of one layer, consistent with every other scene. */
    private fun classic(name: String): BgScene? = when {
        name in PROCEDURAL -> BgScene(name, CLASSIC_GROUP, listOf(Layer(1, CLASSIC_PREFIX + name)))
        name in SHADERS -> BgScene(name, CLASSIC_GROUP, listOf(Layer(1, name)))
        name.startsWith("file:") -> filesDir?.let { d -> BgScene(name.removePrefix("file:"), CLASSIC_GROUP, listOf(Layer(1, "Media file", mapOf("blend" to 2f), java.io.File(d, name.removePrefix("file:")).absolutePath))) }
        else -> null
    }

    /** The clean definition of a scene: built-ins always come back at their defaults, saved scenes as you saved them. */
    fun pristine(name: String) = mine.firstOrNull { it.name == name } ?: BUILT_IN_SCENES.firstOrNull { it.name == name } ?: classic(name)
}
