package com.starshine.launcher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

const val FRACTAL = "Mandelbrot v2"

/**
 * Mandelbrot v2: one fractal layer that can show 50 sets (Mandelbrot places, multibrots, Julias, burning ships…), coloured
 * through a colour map, and flown from a start point to a lead point and back. Points are (x, y) in the complex plane plus a
 * zoom level in doublings, so "10" is 1024 times closer than "0".
 */
object Fractal {
    /** formula: 0 Mandelbrot, 1 burning ship, 2 tricorn, 3 celtic, 4 buffalo, 5 perpendicular ship, 6 phoenix. */
    class Set(val name: String, val formula: Int, val power: Int = 2, val julia: Boolean = false, val cx: Float = 0f, val cy: Float = 0f,
              val start: FloatArray, val lead: FloatArray)

    private fun m(n: String, sx: Float, sy: Float, sz: Float, lx: Float, ly: Float, lz: Float, f: Int = 0, p: Int = 2) =
        Set(n, f, p, false, 0f, 0f, floatArrayOf(sx, sy, sz), floatArrayOf(lx, ly, lz))
    private fun j(n: String, cx: Float, cy: Float, f: Int = 0, p: Int = 2, lz: Float = 3f, lx: Float = 0f, ly: Float = 0f) =
        Set(n, f, p, true, cx, cy, floatArrayOf(0f, 0f, -0.3f), floatArrayOf(lx, ly, lz))

    val SETS: List<Set> = listOf(
        m("Mandelbrot · whole set", -0.5f, 0f, -0.2f, -0.5f, 0f, 0.6f),
        m("Mandelbrot · seahorse valley", -0.5f, 0f, 0f, -0.7436447f, 0.1318252f, 12f),
        m("Mandelbrot · elephant valley", -0.5f, 0f, 0f, 0.2850f, 0.0110f, 9f),
        m("Mandelbrot · triple spiral", -0.5f, 0f, 0f, -0.0880f, 0.6540f, 9f),
        m("Mandelbrot · mini brot", -0.5f, 0f, 0f, -1.7499f, 0f, 11f),
        m("Mandelbrot · scepter valley", -0.5f, 0f, 0f, -1.3600f, 0.0050f, 8f),
        m("Mandelbrot · double spiral", -0.5f, 0f, 0f, -0.7453f, 0.1127f, 11f),
        m("Mandelbrot · antenna", -0.5f, 0f, 0f, -1.9400f, 0f, 9f),
        m("Mandelbrot · lightning", -0.5f, 0f, 0f, -0.1700f, 1.0400f, 7f),
        m("Mandelbrot · starfish", -0.5f, 0f, 0f, -0.3740f, 0.6600f, 9f),
        m("Multibrot 3", 0f, 0f, -0.2f, 0f, 0.6f, 5f, 0, 3),
        m("Multibrot 4", 0f, 0f, -0.2f, 0.3f, 0.5f, 5f, 0, 4),
        m("Multibrot 5", 0f, 0f, -0.2f, 0.45f, 0.45f, 5f, 0, 5),
        m("Multibrot 6", 0f, 0f, -0.2f, 0f, 0.8f, 5f, 0, 6),
        m("Multibrot 8", 0f, 0f, -0.2f, 0.62f, 0.42f, 5f, 0, 8),
        j("Julia · dendrite", 0f, 1f),
        j("Julia · Douady rabbit", -0.123f, 0.745f),
        j("Julia · San Marco", -0.75f, 0f),
        j("Julia · Siegel disk", -0.391f, -0.587f),
        j("Julia · dragon", -0.8f, 0.156f),
        j("Julia · galaxy", -0.7269f, 0.1889f),
        j("Julia · spiral", 0.285f, 0.01f),
        j("Julia · snowflake", -0.4f, 0.6f),
        j("Julia · lightning", -0.70176f, -0.3842f),
        j("Julia · cauliflower", 0.25f, 0f),
        j("Julia · airplane", -1.755f, 0f),
        j("Julia · starfish", -0.54f, 0.54f),
        j("Julia · ice", 0.355f, 0.355f),
        j("Julia · fern", -0.75f, 0.11f),
        j("Julia · swirl", 0.285f, 0.535f),
        j("Julia · coral", -0.1f, 0.651f),
        j("Julia 3 · triskelion", 0.4f, 0f, 0, 3),
        j("Julia 4 · cross", 0.484f, 0f, 0, 4),
        j("Julia 5 · flower", 0.544f, 0f, 0, 5),
        m("Burning ship · whole", -0.4f, -0.5f, -0.3f, -1.762f, -0.028f, 6f, 1),
        m("Burning ship · armada", -0.4f, -0.5f, -0.3f, -1.755f, -0.035f, 9f, 1),
        m("Burning ship · mast", -0.4f, -0.5f, -0.3f, -1.861f, -0.003f, 8f, 1),
        m("Tricorn · whole", -0.3f, 0f, -0.2f, -0.3f, 0f, 0.5f, 2),
        m("Tricorn · tentacles", -0.3f, 0f, 0f, -1.3f, 0.12f, 6f, 2),
        m("Celtic · whole", -0.5f, 0f, -0.2f, -0.5f, 0f, 0.5f, 3),
        m("Celtic · knot", -0.5f, 0f, 0f, -1.29f, 0.07f, 7f, 3),
        m("Buffalo · whole", -0.5f, -0.4f, -0.2f, -0.5f, -0.4f, 0.5f, 4),
        m("Buffalo · horns", -0.5f, -0.4f, 0f, -1.53f, -0.03f, 6f, 4),
        m("Perpendicular ship", -0.5f, 0f, -0.2f, -1.67f, 0f, 7f, 5),
        j("Ship Julia · sails", -1.4f, -0.05f, 1),
        j("Ship Julia · flames", -0.6f, -1.1f, 1),
        j("Tricorn Julia · crown", -0.3f, 0.6f, 2),
        j("Tricorn Julia · lace", 0.2f, -0.55f, 2),
        // phoenix: z' = z² + c + p·(previous z), with c and p real (stored as the x and y of the Julia point)
        j("Phoenix · feathers", 0.5667f, -0.5f, 6),
        j("Phoenix · wings", 0.56f, -0.45f, 6)
    )

    /** Layer settings of the fractal, sharing the layer's value table. */
    private fun num(v: Float) = String.format(java.util.Locale.US, "%.4f", v)
    val F0X = BgParams.P("f0x", "Start X", -0.5f, 0.05f, -3f, 3f, ::num)
    val F0Y = BgParams.P("f0y", "Start Y", 0f, 0.05f, -3f, 3f, ::num)
    val F0Z = BgParams.P("f0z", "Start zoom", 0f, 0.25f, -2f, 16f, { String.format(java.util.Locale.US, "×%.0f", Math.pow(2.0, it.toDouble())) })
    val F1X = BgParams.P("f1x", "Lead X", -0.7436447f, 0.05f, -3f, 3f, ::num)
    val F1Y = BgParams.P("f1y", "Lead Y", 0.1318252f, 0.05f, -3f, 3f, ::num)
    val F1Z = BgParams.P("f1z", "Lead zoom", 12f, 0.25f, -2f, 16f, { String.format(java.util.Locale.US, "×%.0f", Math.pow(2.0, it.toDouble())) })
    val TRIP = BgParams.P("trip", "Trip time", 60f, 5f, 5f, 900f, { "${Math.round(it)} s" })
    val HOLD = BgParams.P("fhold", "Stay at each end", 0.15f, 0.05f, 0f, 0.8f)
    val CDENS = BgParams.P("cdens", "Color bands", 0.35f, 0.05f, 0.02f, 3f)
    val CCYCLE = BgParams.P("ccycle", "Color flow", 0.05f, 0.01f, -1f, 1f, { String.format(java.util.Locale.US, "%.2f", it) })
    val PARAMS = listOf(F0X, F0Y, F0Z, F1X, F1Y, F1Z, TRIP, HOLD, CDENS, CCYCLE)

    /** Points are moved in steps that shrink as you zoom, so the arrows stay useful deep inside. */
    fun stepAt(zoom: Float) = (0.1f / Math.pow(2.0, zoom.toDouble()).toFloat()).coerceAtLeast(1e-6f)

    fun applySet(l: Layer, i: Int): Layer {
        val s = SETS[(i % SETS.size + SETS.size) % SETS.size]
        return l.set("fset", ((i % SETS.size + SETS.size) % SETS.size).toFloat())
            .set("f0x", s.start[0]).set("f0y", s.start[1]).set("f0z", s.start[2])
            .set("f1x", s.lead[0]).set("f1y", s.lead[1]).set("f1z", s.lead[2])
    }

    /* ---------------- colour maps ---------------- */

    /** Five colour stops, looping. Colours as 0xRRGGBB. */
    data class ColorMap(val name: String, val stops: List<Int>) {
        fun toJson(): JSONObject = JSONObject().put("name", name).put("stops", JSONArray().also { a -> stops.forEach { a.put(it) } })
        companion object { fun fromJson(o: JSONObject) = ColorMap(o.getString("name"), o.getJSONArray("stops").let { a -> (0 until a.length()).map { a.getInt(it) } }) }
    }
    val BUILT_IN_MAPS = listOf(
        ColorMap("Classic", listOf(0x000764, 0x206BCB, 0xEDFFFF, 0xFFAA00, 0x300200)),
        ColorMap("Fire", listOf(0x000000, 0x5A0A00, 0xE03C00, 0xFFC020, 0xFFFFD0)),
        ColorMap("Ocean", listOf(0x00101E, 0x003C5A, 0x00A0B4, 0xA0F0E6, 0x0A2850)),
        ColorMap("Aurora", listOf(0x050014, 0x14A064, 0x3CF0B4, 0x8C3CDC, 0x0A0A32)),
        ColorMap("Sunset", listOf(0x14002D, 0x8C1450, 0xF0643C, 0xFFD278, 0x3C145A)),
        ColorMap("Psychedelic", listOf(0xFF0080, 0xFFE600, 0x00FF8C, 0x0078FF, 0xB400FF)),
        ColorMap("Gold", listOf(0x0A0600, 0x5A3C0A, 0xD2A032, 0xFFF0B4, 0x6E4614)),
        ColorMap("Ice", listOf(0x000814, 0x1E3C78, 0x8CC8FF, 0xFFFFFF, 0x3C6EB4)),
        ColorMap("Forest", listOf(0x020A02, 0x145A1E, 0x5AAA3C, 0xDCE68C, 0x283C14)),
        ColorMap("Moonlight", listOf(0x000000, 0x282832, 0x8C8CA0, 0xF0F0FF, 0x46465A))
    )
    var custom by mutableStateOf(listOf<ColorMap>())
    val maps: List<ColorMap> get() = BUILT_IN_MAPS + custom
    private var loaded = false
    private fun sp(ctx: Context) = ctx.getSharedPreferences("starshine_bg2", Context.MODE_PRIVATE)
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching { sp(ctx).getString("colorMaps", null)?.let { s -> val a = JSONArray(s); custom = (0 until a.length()).map { ColorMap.fromJson(a.getJSONObject(it)) } } }
    }
    fun saveCustom(ctx: Context, list: List<ColorMap>) {
        custom = list
        sp(ctx).edit().putString("colorMaps", JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()).apply()
    }
    fun map(i: Int): ColorMap = maps.getOrNull(i) ?: BUILT_IN_MAPS[0]

    /* ---------------- shader ---------------- */

    /** Field shader body. Uniforms: SETF (formula, power, julia, -), JC (julia c), CAM (x, y, zoom), PAL[5], CB (bands, flow). */
    const val SHADER = """
uniform vec4 SETF; uniform vec2 JC; uniform vec3 CAM; uniform vec3 PAL[5]; uniform vec2 CB;
vec2 cmul(vec2 a, vec2 b) { return vec2(a.x * b.x - a.y * b.y, a.x * b.y + a.y * b.x); }
vec2 cpow(vec2 z, int p) { vec2 r = z; for (int k = 1; k < 8; k++) { if (k >= p) break; r = cmul(r, z); } return r; }
vec3 pal(float t) {
  t = fract(t) * 5.0;
  if (t < 1.0) return mix(PAL[0], PAL[1], t);
  if (t < 2.0) return mix(PAL[1], PAL[2], t - 1.0);
  if (t < 3.0) return mix(PAL[2], PAL[3], t - 2.0);
  if (t < 4.0) return mix(PAL[3], PAL[4], t - 3.0);
  return mix(PAL[4], PAL[0], t - 4.0);
}
void main() {
  vec2 uv = uvr();
  float scale = 1.5 / exp2(CAM.z);
  vec2 c = CAM.xy + uv * scale;
  int f = int(SETF.x + 0.5); int p = int(SETF.y + 0.5); bool julia = SETF.z > 0.5;
  vec2 z = julia ? c : vec2(0.0);
  vec2 k = julia ? JC : c;
  vec2 prev = vec2(0.0);
  int maxi = int(48.0 + DEN * 352.0);
  float it = 0.0; float esc = 0.0;
  for (int i = 0; i < 400; i++) {
    if (i >= maxi) break;
    vec2 w = z;
    if (f == 1) w = abs(z);
    else if (f == 2) w = vec2(z.x, -z.y);
    else if (f == 5) w = vec2(z.x, -abs(z.y));
    vec2 n = cpow(w, p);
    if (f == 3) n.x = abs(n.x);
    else if (f == 4) n = abs(n);
    if (f == 6) { vec2 nz = n + vec2(k.x, 0.0) + k.y * prev; prev = z; z = nz; }
    else z = n + k;
    if (dot(z, z) > 256.0) { esc = 1.0; break; }
    it += 1.0;
  }
  vec3 col = vec3(0.0);
  if (esc > 0.5) {
    float mu = it + 1.0 - log(log(max(length(z), 1.0001))) / log(float(p));
    col = pal(mu * CB.x * 0.1 + T * CB.y * 0.1 + HUE);
  }
  gl_FragColor = vec4(post(col * GLOW), 1.0);
}"""
}

/**
 * Camera and render effects for a whole scene, applied to the finished picture in the order a camera would: lens (distortion,
 * chromatic aberration, depth of field), light (bloom, glare, lens flare), shutter (motion blur), then the grade and the film.
 */
object RenderFx {
    private fun pct(v: Float) = "${Math.round(v * 100)}%"
    private fun num(v: Float) = String.format(java.util.Locale.US, "%.2f", v)
    val BLOOM = BgParams.P("bloom", "Bloom", 0f, 0.1f, 0f, 3f, ::pct)
    val THRESH = BgParams.P("bloomT", "Bloom threshold", 0.55f, 0.05f, 0f, 0.95f, ::pct)
    val GLARE = BgParams.P("glare", "Glare streaks", 0f, 0.1f, 0f, 3f, ::pct)
    val FLARE = BgParams.P("flare", "Lens flare", 0f, 0.1f, 0f, 3f, ::pct)
    val FLX = BgParams.P("flareX", "Flare light X", 0.45f, 0.05f, -1.5f, 1.5f, ::num)
    val FLY = BgParams.P("flareY", "Flare light Y", 0.35f, 0.05f, -1.5f, 1.5f, ::num)
    val GHOSTS = BgParams.P("ghosts", "Flare ghosts", 0.6f, 0.1f, 0f, 2f, ::pct)
    val MOTION = BgParams.P("motion", "Motion blur", 0f, 0.05f, 0f, 0.95f, ::pct)
    val DOF = BgParams.P("dof", "Depth of field", 0f, 0.1f, 0f, 1f, ::pct)
    val FOCX = BgParams.P("focusX", "Focus X", 0f, 0.05f, -1f, 1f, ::num)
    val FOCY = BgParams.P("focusY", "Focus Y", 0f, 0.05f, -1f, 1f, ::num)
    val FOCR = BgParams.P("focusR", "Focus range", 0.35f, 0.05f, 0.02f, 2f, ::num)
    val CHROMA = BgParams.P("chroma", "Chromatic aberration", 0f, 0.1f, 0f, 2f, ::pct)
    val DISTORT = BgParams.P("distort", "Lens distortion", 0f, 0.05f, -1f, 1f, ::num)
    val VIGNETTE = BgParams.P("vignette", "Vignette", 0f, 0.1f, 0f, 1.5f, ::pct)
    val GRAIN = BgParams.P("grain", "Film grain", 0f, 0.05f, 0f, 1f, ::pct)
    val SHARPEN = BgParams.P("sharpen", "Sharpen", 0f, 0.1f, 0f, 2f, ::pct)
    val EXPOSURE = BgParams.P("exposure", "Exposure", 0f, 0.1f, -3f, 3f, { String.format(java.util.Locale.US, "%+.1f EV", it) })
    val CONTRAST = BgParams.P("contrast", "Contrast", 1f, 0.05f, 0.3f, 2.5f, ::pct)
    val SAT = BgParams.P("fxSat", "Saturation", 1f, 0.05f, 0f, 2.5f, ::pct)
    val TEMP = BgParams.P("temp", "Temperature", 0f, 0.05f, -1f, 1f, { if (it > 0.01f) "warm " + pct(it) else if (it < -0.01f) "cool " + pct(-it) else "neutral" })
    val GAMMA = BgParams.P("gamma", "Gamma", 1f, 0.05f, 0.4f, 2.5f, ::num)
    val ALL = listOf(BLOOM, THRESH, GLARE, FLARE, FLX, FLY, GHOSTS, MOTION, DOF, FOCX, FOCY, FOCR, CHROMA, DISTORT, VIGNETTE, GRAIN, SHARPEN, EXPOSURE, CONTRAST, SAT, TEMP, GAMMA)
    val DEFAULTS: Map<String, Float> = ALL.associate { it.key to it.def }
    /** Any effect that changes the picture: when none is set, the scene draws straight to the screen as before. */
    private val EFFECTS = listOf(BLOOM, GLARE, FLARE, MOTION, DOF, CHROMA, DISTORT, VIGNETTE, GRAIN, SHARPEN, EXPOSURE, CONTRAST, SAT, TEMP, GAMMA)
    fun active(s: BgScene) = EFFECTS.any { kotlin.math.abs(s.fx(it.key) - it.def) > 0.001f }
    fun summary(s: BgScene): String = EFFECTS.filter { kotlin.math.abs(s.fx(it.key) - it.def) > 0.001f }.joinToString(", ") { it.label.lowercase() }.ifEmpty { "none" }
}
