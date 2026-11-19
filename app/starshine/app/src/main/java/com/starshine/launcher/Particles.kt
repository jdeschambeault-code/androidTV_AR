package com.starshine.launcher

/**
 * Universal particles, after Maya's particle systems: an EMITTER (where particles are born, how many, in which direction,
 * how fast) and the PARTICLES it emits (how long they live, how they look as they age), moved by FIELDS (gravity, drag,
 * swirl, turbulence).
 *
 * Everything runs on the GPU and nothing is stored between frames: particle i is born at a regular time set by the rate,
 * and from its age and its own random numbers its position, size, colour and opacity are worked out directly. That keeps
 * thousands of particles cheap enough for the pod, and any setting can change or be animated at any moment.
 * The system lives in the element's own space: moving, turning or scaling the element moves the whole system.
 */
object Particles {
    const val TYPE = "Particle system"
    /** Particles one system can show at once. */
    const val MAX = 12000

    private fun num(v: Float) = String.format(java.util.Locale.US, "%.2f", v)
    private fun pct(v: Float) = "${Math.round(v * 100)}%"
    private fun names(list: List<String>): (Float) -> String = { list[Math.round(it).coerceIn(0, list.lastIndex)] }

    val SHAPES = listOf("Point", "Line", "Circle", "Disc", "Grid", "Box", "Sphere (inside)", "Sphere (surface)", "Torus", "Spiral")
    val LOOKS = listOf("Soft dot", "Solid dot", "Star", "Ring", "Bokeh", "Fractal")

    /* ---------- emitter ---------- */
    val SHAPE = BgParams.P("peShape", "Shape", 7f, 1f, 0f, (SHAPES.size - 1).toFloat(), names(SHAPES))
    val RATE = BgParams.P("peRate", "Rate (per second)", 300f, 10f, 1f, 3000f, { "${Math.round(it)}/s" })
    val LIFE = BgParams.P("peLife", "Lifespan", 3f, 0.1f, 0.1f, 30f, { String.format(java.util.Locale.US, "%.1f s", it) })
    val LIFEVAR = BgParams.P("peLifeVar", "Lifespan variety", 0.3f, 0.05f, 0f, 1f, ::pct)
    val DIRX = BgParams.P("peDirX", "Direction X", 0f, 0.1f, -1f, 1f, ::num)
    val DIRY = BgParams.P("peDirY", "Direction Y", 1f, 0.1f, -1f, 1f, ::num)
    val DIRZ = BgParams.P("peDirZ", "Direction Z", 0f, 0.1f, -1f, 1f, ::num)
    val SPREAD = BgParams.P("peSpread", "Spread", 25f, 5f, 0f, 180f, { "${Math.round(it)}°" })
    val OUTWARD = BgParams.P("peOut", "Outward from shape", 0f, 0.1f, 0f, 1f, ::pct)
    /** Along the shape, round its vertical axis: sparks thrown off a spinning ring. Negative = the other way round. */
    val TANGENT = BgParams.P("peTan", "Along the shape (spin-off)", 0f, 0.1f, -1f, 1f, ::pct)
    val SPEED = BgParams.P("peSpeed", "Speed", 1.5f, 0.1f, 0f, 30f, { String.format(java.util.Locale.US, "%.1f m/s", it) })
    val SPEEDVAR = BgParams.P("peSpeedVar", "Speed variety", 0.3f, 0.05f, 0f, 1f, ::pct)

    /* ---------- fields ---------- */
    val GRAVITY = BgParams.P("peGrav", "Gravity", 0.5f, 0.1f, -10f, 10f, { String.format(java.util.Locale.US, "%.1f", it) })
    val DRAG = BgParams.P("peDrag", "Drag (air)", 0.2f, 0.05f, 0f, 5f, ::num)
    val SWIRL = BgParams.P("peSwirl", "Swirl", 0f, 5f, -360f, 360f, { "${Math.round(it)}°/s" })
    val TURB = BgParams.P("peTurb", "Turbulence", 0.2f, 0.05f, 0f, 5f, ::num)
    val TURBF = BgParams.P("peTurbF", "Turbulence scale", 1.5f, 0.1f, 0.1f, 10f, ::num)

    /* ---------- particles ---------- */
    val COUNT = BgParams.P("peMax", "Max particles", 2000f, 100f, 50f, MAX.toFloat(), { "${Math.round(it)}" })
    val LOOK = BgParams.P("peLook", "Look", 0f, 1f, 0f, (LOOKS.size - 1).toFloat(), names(LOOKS))
    val SIZE0 = BgParams.P("peSize", "Size at birth", 0.08f, 0.01f, 0.005f, 2f, ::num)
    val SIZE1 = BgParams.P("peSizeEnd", "Size at death (× birth)", 0.4f, 0.05f, 0f, 5f, ::pct)
    val HUE0 = BgParams.P("peHue", "Colour at birth", 190f, 5f, 0f, 360f, { "${Math.round(it)}°" })
    val HUE1 = BgParams.P("peHueEnd", "Colour at death", 280f, 5f, 0f, 360f, { "${Math.round(it)}°" })
    val HUEVAR = BgParams.P("peHueVar", "Colour variety", 20f, 5f, 0f, 180f, { "±${Math.round(it)}°" })
    val SAT = BgParams.P("peSat", "Saturation", 0.7f, 0.05f, 0f, 1f, ::pct)
    val GLOW = BgParams.P("peGlow", "Glow", 1.2f, 0.1f, 0f, 5f, ::pct)
    val FADEIN = BgParams.P("peFadeIn", "Fade in", 0.1f, 0.05f, 0f, 1f, ::pct)
    val FADEOUT = BgParams.P("peFadeOut", "Fade out", 0.5f, 0.05f, 0f, 1f, ::pct)
    val JULIA = BgParams.P("peJulia", "Fractal shape", 0.5f, 0.05f, 0f, 1f, ::pct)

    val EMITTER = listOf(SHAPE, RATE, LIFE, LIFEVAR, DIRX, DIRY, DIRZ, SPREAD, OUTWARD, TANGENT, SPEED, SPEEDVAR)
    val FIELDS = listOf(GRAVITY, DRAG, SWIRL, TURB, TURBF)
    val LOOKING = listOf(COUNT, LOOK, SIZE0, SIZE1, HUE0, HUE1, HUEVAR, SAT, GLOW, FADEIN, FADEOUT, JULIA, BgParams.OPACITY)
    val PARAMS = EMITTER + FIELDS + LOOKING - BgParams.OPACITY

    /** Ready-made settings, the starting point most of the time. */
    val PRESETS: List<Pair<String, Map<String, Float>>> = listOf(
        "Fountain" to mapOf("peShape" to 3f, "peRate" to 400f, "peLife" to 2.5f, "peDirY" to 1f, "peSpread" to 12f, "peSpeed" to 4f, "peGrav" to 3.5f,
            "peDrag" to 0.1f, "peTurb" to 0.05f, "peSize" to 0.05f, "peSizeEnd" to 0.6f, "peHue" to 195f, "peHueEnd" to 220f, "peSat" to 0.5f),
        "Fireflies" to mapOf("peShape" to 6f, "peRate" to 40f, "peLife" to 8f, "peSpeed" to 0.1f, "peSpread" to 180f, "peGrav" to 0f, "peTurb" to 0.6f,
            "peTurbF" to 0.6f, "peSize" to 0.06f, "peSizeEnd" to 1f, "peHue" to 55f, "peHueEnd" to 90f, "peFadeIn" to 0.4f, "peFadeOut" to 0.4f, "peGlow" to 2f),
        "Vortex" to mapOf("peShape" to 2f, "peRate" to 600f, "peLife" to 6f, "peDirY" to 1f, "peSpeed" to 0.4f, "peSpread" to 10f, "peOut" to 0.3f,
            "peGrav" to 0f, "peSwirl" to 90f, "peDrag" to 0f, "peTurb" to 0.1f, "peSize" to 0.04f, "peHue" to 260f, "peHueEnd" to 320f),
        "Embers" to mapOf("peShape" to 3f, "peRate" to 120f, "peLife" to 4f, "peDirY" to 1f, "peSpread" to 30f, "peSpeed" to 0.8f, "peGrav" to -0.4f,
            "peTurb" to 0.5f, "peSize" to 0.04f, "peSizeEnd" to 0.2f, "peHue" to 30f, "peHueEnd" to 0f, "peSat" to 0.9f, "peGlow" to 1.8f),
        "Snow" to mapOf("peShape" to 4f, "peRate" to 200f, "peLife" to 10f, "peDirY" to -1f, "peSpread" to 10f, "peSpeed" to 0.4f, "peGrav" to 0f,
            "peTurb" to 0.3f, "peTurbF" to 0.8f, "peSize" to 0.04f, "peSizeEnd" to 1f, "peSat" to 0f, "peFadeOut" to 0.2f),
        "Starburst" to mapOf("peShape" to 0f, "peRate" to 800f, "peLife" to 2f, "peOut" to 0f, "peSpread" to 180f, "peSpeed" to 3f, "peGrav" to 0f,
            "peDrag" to 1f, "peLook" to 2f, "peSize" to 0.12f, "peSizeEnd" to 0.1f, "peHue" to 40f, "peHueEnd" to 330f),
        "Fractal snow" to mapOf("peShape" to 5f, "peRate" to 25f, "peLife" to 9f, "peDirY" to -1f, "peSpeed" to 0.25f, "peSpread" to 20f, "peGrav" to 0f,
            "peTurb" to 0.25f, "peLook" to 5f, "peSize" to 0.14f, "peSizeEnd" to 1f, "peHue" to 200f, "peHueEnd" to 300f, "peJulia" to 0.35f, "peGlow" to 0.8f),
        "Spark ring" to PORTAL_RING, "Flung sparks" to PORTAL_SPARKS
    )

    /** The two halves of the spark portal: a fast spinning ring, and the sparks it flings off (they fall in the world). */
    val PORTAL_RING: Map<String, Float> get() = mapOf("peShape" to 2f, "peRate" to 1600f, "peLife" to 0.5f, "peLifeVar" to 0.5f, "peTan" to 1f, "peOut" to 0.1f, "peSpread" to 12f,
        "peSpeed" to 1.4f, "peSpeedVar" to 0.4f, "peDrag" to 1.5f, "peGrav" to 0f, "peSwirl" to 140f, "peTurb" to 0.05f, "peMax" to 3000f, "peLook" to 0f,
        "peSize" to 0.035f, "peSizeEnd" to 0.3f, "peHue" to 38f, "peHueEnd" to 12f, "peHueVar" to 8f, "peSat" to 0.95f, "peGlow" to 2.2f, "peFadeIn" to 0f, "peFadeOut" to 0.6f)
    val PORTAL_SPARKS: Map<String, Float> get() = mapOf("peShape" to 2f, "peRate" to 260f, "peLife" to 1.3f, "peLifeVar" to 0.5f, "peTan" to 0.85f, "peOut" to 0.35f, "peSpread" to 20f,
        "peSpeed" to 3.5f, "peSpeedVar" to 0.5f, "peDrag" to 0.3f, "peGrav" to 3.5f, "peSwirl" to 0f, "peTurb" to 0f, "peMax" to 600f, "peLook" to 2f,
        "peSize" to 0.06f, "peSizeEnd" to 0.15f, "peHue" to 42f, "peHueEnd" to 18f, "peHueVar" to 6f, "peSat" to 0.9f, "peGlow" to 1.8f, "peFadeIn" to 0f, "peFadeOut" to 0.5f)

    /** A new system starts as a fountain of soft blue light, 5 m ahead. */
    val DEFAULTS: Map<String, Float> = mapOf("scale" to 1f) + PRESETS.first().second

    /** Particles drawn this frame: as many as are alive at once (rate × lifespan), within the cap. */
    fun count(l: Layer) = (l[RATE.key] * l[LIFE.key]).coerceIn(1f, minOf(l[COUNT.key], MAX.toFloat())).toInt()

    /** Each particle: three random numbers and its place in the birth order (0..1). */
    fun seeds(): java.nio.FloatBuffer {
        val r = java.util.Random(9157L)
        val fb = java.nio.ByteBuffer.allocateDirect(MAX * 16).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until MAX) { fb.put(r.nextFloat()); fb.put(r.nextFloat()); fb.put(r.nextFloat()); fb.put(i.toFloat()) }
        fb.position(0); return fb
    }

    const val VS = """
attribute vec4 P;
uniform mat4 MVP; uniform float T; uniform float PX; uniform float N;
uniform float SHAPE; uniform float RATE; uniform float LIFE; uniform float LIFEVAR; uniform vec3 DIR; uniform float SPREAD; uniform float OUTW;
uniform float SPEED; uniform float SPEEDVAR; uniform float GRAV; uniform vec3 GDIR; uniform float TAN; uniform float DRAG; uniform float SWIRL; uniform float TURB; uniform float TURBF;
uniform float SIZE0; uniform float SIZE1; uniform float FADEIN; uniform float FADEOUT;
varying float vA; varying float vK; varying float vR;
const float TAU = 6.2831853;
vec3 sphereDir(float a, float b) { float u = a * 2.0 - 1.0; float t = b * TAU; float s = sqrt(1.0 - u * u); return vec3(s * cos(t), u, s * sin(t)); }
vec3 emit(float s, vec3 r) {
  if (s < 0.5) return vec3(0.0);
  if (s < 1.5) return vec3(r.x * 2.0 - 1.0, 0.0, 0.0);
  float a = r.x * TAU;
  if (s < 2.5) return vec3(cos(a), 0.0, sin(a));
  if (s < 3.5) return vec3(cos(a), 0.0, sin(a)) * sqrt(r.y);
  if (s < 4.5) return vec3(floor(r.x * 10.0) / 9.0 * 2.0 - 1.0, 0.0, floor(r.y * 10.0) / 9.0 * 2.0 - 1.0);
  if (s < 5.5) return r * 2.0 - 1.0;
  if (s < 6.5) return sphereDir(r.x, r.y) * pow(r.z, 0.3333);
  if (s < 7.5) return sphereDir(r.x, r.y);
  if (s < 8.5) { float b = r.y * TAU; return vec3(cos(a) * (1.0 + 0.3 * cos(b)), 0.3 * sin(b), sin(a) * (1.0 + 0.3 * cos(b))); }
  float k = r.x; float sa = k * 2.0 * TAU; return vec3(cos(sa) * k, k - 0.5, sin(sa) * k);
}
void main() {
  vec3 r = P.xyz; float i = P.w;
  // birth order: one particle every 1/RATE seconds, the N slots taking turns
  float cycle = N / RATE;
  float age = mod(T - i / RATE, cycle);
  float r4 = fract(sin(i * 12.9898 + 78.233) * 43758.5453);
  float life = LIFE * (1.0 - LIFEVAR * r4);
  float k = age / life;
  if (k >= 1.0 || i >= N) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); gl_PointSize = 0.0; vA = 0.0; vK = 0.0; vR = 0.0; return; }
  // each particle is born where its emission started: a new random draw every round of the slots
  float round = floor((T - i / RATE) / cycle);
  vec3 rr = fract(r + vec3(0.137, 0.271, 0.419) * round);
  vec3 e = emit(SHAPE, rr);
  // direction: the main one, bent outward from the shape, then scattered within the spread cone
  vec3 d = length(DIR) > 0.001 ? normalize(DIR) : vec3(0.0, 1.0, 0.0);
  vec3 o = length(e) > 0.001 ? normalize(e) : d;
  d = normalize(mix(d, o, OUTW) + 0.0001);
  vec3 tg = length(e.xz) > 0.001 ? normalize(vec3(-e.z, 0.0, e.x)) : d;
  d = normalize(mix(d, tg * sign(TAN + 0.0001), abs(TAN)) + 0.0001);
  vec3 rnd = sphereDir(fract(rr.y * 7.31 + rr.z), fract(rr.x * 3.17 + rr.z * 1.7));
  float spread = SPREAD / 180.0;
  vec3 v = normalize(mix(d, rnd, spread) + d * 0.0001);
  float sp = SPEED * (1.0 - SPEEDVAR * fract(r4 * 7.0));
  // fields: drag slows the launch, gravity pulls down, swirl turns round the vertical axis, turbulence wanders
  float travel = DRAG > 0.001 ? (1.0 - exp(-DRAG * age)) / DRAG : age;
  vec3 p = e + v * sp * travel;
  float sw = radians(SWIRL) * age; float cs = cos(sw); float sn = sin(sw);
  p = vec3(p.x * cs - p.z * sn, p.y, p.x * sn + p.z * cs);
  p += 0.5 * GRAV * age * age * GDIR;   // GDIR = down in the world, in the element's own space
  vec3 q = p * TURBF + rr * 6.0;
  p += TURB * age * 0.35 * vec3(sin(q.y + T * 0.7), sin(q.z + T * 0.9), sin(q.x + T * 0.8));
  vec4 c = MVP * vec4(p, 1.0);
  gl_Position = c;
  float size = SIZE0 * mix(1.0, SIZE1, k);
  float px = size * PX / max(c.w, 0.05);
  gl_PointSize = clamp(px, 1.0, 128.0);
  float fade = smoothstep(0.0, max(FADEIN, 0.001), k) * (1.0 - smoothstep(1.0 - max(FADEOUT, 0.001), 1.0, k));
  vA = fade * clamp(px, 0.0, 1.0) * step(0.0, c.w);
  vK = k; vR = fract(r4 * 13.0 + rr.x);
}"""

    const val FS = """
precision mediump float;
varying float vA; varying float vK; varying float vR;
uniform float HUE0; uniform float HUE1; uniform float HUEVAR; uniform float SAT; uniform float GLOW; uniform float OP; uniform float LOOK; uniform float JULIA;
vec3 hsv(float h, float s, float v) { vec3 k = clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0); return v * mix(vec3(1.0), k, s); }
void main() {
  vec2 c = gl_PointCoord * 2.0 - 1.0; float d = length(c);
  float a;
  if (LOOK < 0.5) a = exp(-d * d * 4.0) * step(d, 1.0);
  else if (LOOK < 1.5) a = smoothstep(1.0, 0.8, d);
  else if (LOOK < 2.5) a = exp(-d * d * 10.0) + 0.8 * exp(-abs(c.x) * 12.0) * exp(-abs(c.y) * 1.8) + 0.8 * exp(-abs(c.y) * 12.0) * exp(-abs(c.x) * 1.8);
  else if (LOOK < 3.5) a = exp(-pow((d - 0.7) * 6.0, 2.0));
  else if (LOOK < 4.5) a = smoothstep(1.0, 0.9, d) * (0.35 + 0.65 * smoothstep(0.6, 0.95, d));
  else {
    // a tiny Julia set: each particle its own, from its random number and the shape setting
    vec2 z = c * 1.4; float an = vR * 6.2831 + JULIA * 3.0; vec2 k = vec2(-0.8 + 0.25 * cos(an), 0.156 + 0.25 * sin(an));
    float n = 0.0;
    for (int it = 0; it < 18; it++) { z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + k; if (dot(z, z) > 4.0) break; n += 1.0; }
    a = (n / 18.0) * smoothstep(1.0, 0.7, d);
  }
  float h = mix(HUE0, HUE1, vK) / 360.0 + (vR - 0.5) * 2.0 * HUEVAR / 360.0;
  vec3 col = hsv(fract(h), SAT, 1.0) * GLOW;
  gl_FragColor = vec4(col * a * vA * OP, 1.0);
}"""
}
