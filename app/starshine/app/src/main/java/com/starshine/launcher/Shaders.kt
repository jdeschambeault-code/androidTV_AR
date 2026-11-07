package com.starshine.launcher

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GPU "psychedelic" backgrounds (OpenGL ES 2 fragment shaders on the Mali-G31).
 * Rendered at a reduced internal resolution and scaled up by the display, so they stay smooth.
 * Background settings map to uniforms: speed, density (iterations/detail), particle size (zoom), glow,
 * colour shift, saturation, brightness.
 */
val SHADERS = listOf("Hyperspace jump", "Warp speed", "Wormhole gate", "Cosmic portal",
    "Mandelbrot dive", "Julia bloom", "Liquid plasma", "Infinite tunnel", "Kaleidoscope", "Hypno spiral", "Dream clouds")

const val SHADER_HEAD = """
precision highp float;
uniform vec2 R; uniform float T; uniform float HUE; uniform float GLOW; uniform float SAT; uniform float BRI; uniform float ZOOM; uniform float DEN; uniform vec3 DIR;
float hash1(float n) { return fract(sin(n) * 43758.5453); }
vec3 hsv(float h, float s, float v) { vec3 k = clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0); return v * mix(vec3(1.0), k, s); }
vec3 post(vec3 c) { float l = dot(c, vec3(0.299, 0.587, 0.114)); return mix(vec3(l), c, SAT) * BRI; }
vec2 uvc() { return (gl_FragCoord.xy - 0.5 * R) / R.y / ZOOM; }
"""

private val SRC = mapOf(
    // star streaks rushing out of a vanishing point (X/Y move the point, Z = speed and direction)
    "Hyperspace jump" to """
void main() {
  vec2 p = uvc() - DIR.xy * 0.3;
  float a = atan(p.y, p.x); float r = length(p);
  float lanes = 260.0 + 300.0 * DEN;
  float sc = (a + 3.14159) / 6.28318 * lanes;
  float sec = floor(sc); float thin = 1.0 - abs(fract(sc) - 0.5) * 2.0;
  float sp = 0.4 + hash1(sec) * 1.4;
  float pos = fract(hash1(sec * 7.13) + T * DIR.z * sp * 0.22);
  float head = pos * 1.8; float len = 0.03 + 0.55 * pos * pos;
  float d = smoothstep(head - len, head, r) * smoothstep(head + 0.004, head, r);
  float streak = d * pow(thin, 6.0) * (0.4 + pos);
  vec3 col = mix(hsv(HUE + 0.6, 0.55, 1.0), vec3(1.0), pos) * streak * 1.8 * GLOW;
  col += hsv(HUE + 0.6, 0.6, 1.0) * 0.012 / (r + 0.02) * GLOW;
  gl_FragColor = vec4(post(col), 1.0);
}""",
    // 3D star layers flying past (X/Y steer, Z = speed; negative = backwards)
    "Warp speed" to """
void main() {
  vec2 uv = uvc() - DIR.xy * 0.3; vec3 col = vec3(0.0);
  for (int i = 0; i < 5; i++) {
    float fi = float(i);
    float depth = fract(fi * 0.2 + T * DIR.z * 0.12);
    float scale = mix(10.0, 0.4, depth);
    float fade = depth * smoothstep(1.0, 0.85, depth);
    vec2 g = uv * scale + fi * 13.7; vec2 id = floor(g); vec2 f = fract(g) - 0.5;
    float h = fract(sin(dot(id, vec2(12.9898, 78.233))) * 43758.5453);
    vec2 off = vec2(h, fract(h * 7.7)) - 0.5;
    vec2 dv = f - off * 0.8;
    vec2 rad = normalize(uv + 0.0001);
    float along = dot(dv, rad); float across = dot(dv, vec2(-rad.y, rad.x));
    float stretch = 1.0 + depth * 6.0 * abs(DIR.z);
    float d = length(vec2(along / stretch, across));
    col += mix(hsv(HUE + 0.58 + h * 0.15, 0.4, 1.0), vec3(1.0), depth) * smoothstep(0.045, 0.0, d) * fade * (0.4 + h);
  }
  gl_FragColor = vec4(post(col * GLOW), 1.0);
}""",
    // rippling event horizon in a ring with nine chevrons; a surge of energy every ~15 s
    "Wormhole gate" to """
void main() {
  vec2 p = uvc() - DIR.xy * 0.2; float r = length(p); float a = atan(p.y, p.x); float t = T;
  float R0 = 0.36;
  float rip = sin(r * 42.0 - t * 3.0 * DIR.z + sin(a * 5.0 + t) * 0.6) * 0.5 + 0.5;
  float n = sin(p.x * 21.0 + t * 1.3) * sin(p.y * 24.0 - t * 1.1);
  vec3 water = hsv(HUE + 0.56, 0.65, 0.3 + 0.5 * rip * (0.75 + 0.25 * n)) * smoothstep(R0, R0 - 0.01, r);
  float ring = smoothstep(0.025, 0.0, abs(r - R0 - 0.035)) + 0.6 * smoothstep(0.05, 0.0, abs(r - R0 - 0.07));
  float chev = smoothstep(0.985, 1.0, cos(a * 9.0 + 1.5708)) * smoothstep(0.035, 0.0, abs(r - R0 - 0.07));
  float ph = fract(t / 15.0); float surge = smoothstep(0.0, 0.04, ph) * smoothstep(0.25, 0.05, ph);
  float jet = surge * smoothstep(R0 * 1.7, 0.0, r) * (0.6 + 0.4 * sin(r * 30.0 - t * 18.0));
  vec3 col = water * GLOW + vec3(0.55, 0.6, 0.7) * ring * 0.7 + hsv(HUE + 0.06, 0.9, 1.0) * chev * 1.5 + hsv(HUE + 0.55, 0.4, 1.0) * jet;
  gl_FragColor = vec4(post(col), 1.0);
}""",
    // spinning ring of sparks around a dim swirling core
    "Cosmic portal" to """
void main() {
  vec2 p = uvc() - DIR.xy * 0.2; float r = length(p); float a = atan(p.y, p.x); float t = T * DIR.z;
  float R0 = 0.34;
  float band = exp(-pow((r - R0) * 16.0, 2.0));
  float sw = sin(a * 24.0 - t * 8.0 + r * 30.0) * 0.5 + 0.5;
  float sk = floor(a * 90.0 + t * 25.0);
  float sparks = pow(hash1(sk), 18.0) * smoothstep(0.1, 0.0, abs(r - R0 - 0.02 * sin(sk)));
  float fly = pow(hash1(floor(a * 40.0) + floor(t * 6.0)), 30.0) * smoothstep(0.25, 0.0, abs(r - R0 - fract(t * 0.8) * 0.25));
  vec3 fire = hsv(HUE + 0.07, 0.95, 1.0);
  vec3 inner = hsv(HUE + 0.62, 0.6, 0.22) * smoothstep(R0, 0.0, r) * (0.6 + 0.4 * sin(t + r * 12.0 + a * 3.0));
  vec3 col = fire * band * (0.35 + 0.9 * sw) * GLOW + fire * (sparks + fly) * 1.8 + inner;
  gl_FragColor = vec4(post(col), 1.0);
}""",
    "Mandelbrot dive" to """
void main() {
  vec2 uv = uvc();
  float cyc = fract(T * 0.035);
  float z = pow(0.5, cyc * 15.0);
  vec2 c = vec2(-0.743643887, 0.131825904) + uv * z * 2.6;
  vec2 p = vec2(0.0); float i = 0.0; float maxI = 60.0 + 140.0 * DEN;
  for (int k = 0; k < 260; k++) { if (float(k) >= maxI) break; p = vec2(p.x * p.x - p.y * p.y, 2.0 * p.x * p.y) + c; if (dot(p, p) > 64.0) break; i += 1.0; }
  vec3 col = vec3(0.0);
  if (i < maxI) { float sm = i - log2(log2(dot(p, p))) + 4.0; col = hsv(HUE + sm * 0.018 + T * 0.02, 0.85, 0.95) * (0.55 + 0.45 * sin(sm * 0.3)) * GLOW; }
  col *= smoothstep(0.0, 0.06, cyc) * smoothstep(1.0, 0.94, cyc);
  gl_FragColor = vec4(post(col), 1.0);
}""",
    "Julia bloom" to """
void main() {
  vec2 p = uvc() * 2.4;
  vec2 c = 0.7885 * vec2(cos(T * 0.13), sin(T * 0.17));
  float i = 0.0; float maxI = 40.0 + 100.0 * DEN;
  for (int k = 0; k < 160; k++) { if (float(k) >= maxI) break; p = vec2(p.x * p.x - p.y * p.y, 2.0 * p.x * p.y) + c; if (dot(p, p) > 16.0) break; i += 1.0; }
  float sm = i - log2(log2(max(dot(p, p), 1.0001))) + 4.0;
  vec3 col = i >= maxI ? vec3(0.0) : hsv(HUE + 0.55 + sm * 0.025, 0.8, 1.0) * pow(sm / maxI, 0.6) * 1.6 * GLOW;
  gl_FragColor = vec4(post(col), 1.0);
}""",
    "Liquid plasma" to """
void main() {
  vec2 p = uvc() * 3.0; float t = T * 0.4;
  float v = sin(p.x + t) + sin((p.y + t) * 0.7) + sin((p.x + p.y + t) * 0.6) + sin(length(p + vec2(sin(t * 0.5), cos(t * 0.3))) * 1.5 + t);
  v += sin(length(p) * DEN * 2.0 - t);
  vec3 col = hsv(HUE + v * 0.12 + t * 0.05, 0.75, 0.55 + 0.45 * sin(v * 3.14159)) * GLOW * 0.8;
  gl_FragColor = vec4(post(col), 1.0);
}""",
    "Infinite tunnel" to """
void main() {
  vec2 p = uvc(); float a = atan(p.y, p.x); float r = length(p) + 0.0001;
  float t = T * 0.5;
  float u = 0.3 / r + t; float v = a / 3.14159;
  float stripes = sin(u * 6.0 * (0.5 + DEN)) * sin(v * 8.0 + sin(u + t) * 2.0);
  vec3 col = hsv(HUE + u * 0.05 + v * 0.2, 0.8, 0.5 + 0.5 * stripes) * smoothstep(0.0, 0.6, r) * GLOW;
  col += hsv(HUE + 0.6, 0.5, 1.0) * 0.02 / r;
  gl_FragColor = vec4(post(col), 1.0);
}""",
    "Kaleidoscope" to """
void main() {
  vec2 p = uvc(); float t = T * 0.25;
  float n = floor(6.0 + 6.0 * DEN); float a = atan(p.y, p.x); float r = length(p);
  float seg = 6.28318 / n; a = mod(a, seg); a = abs(a - seg * 0.5);
  vec2 q = vec2(cos(a), sin(a)) * r;
  for (int k = 0; k < 4; k++) { q = abs(q) / dot(q, q) - vec2(0.9 + 0.1 * sin(t), 0.6 + 0.1 * cos(t * 0.7)); }
  float v = length(q);
  vec3 col = hsv(HUE + v * 0.08 + t * 0.1, 0.8, clamp(0.25 * v, 0.0, 1.0)) * smoothstep(1.6, 0.2, r) * GLOW;
  gl_FragColor = vec4(post(col), 1.0);
}""",
    "Hypno spiral" to """
void main() {
  vec2 p = uvc(); float r = length(p); float a = atan(p.y, p.x);
  float t = T * 0.6;
  float s = sin(a * (3.0 + floor(DEN * 4.0)) + log(r + 0.001) * 8.0 - t * 2.0);
  float rings = sin(r * 18.0 - t * 3.0);
  vec3 col = hsv(HUE + r * 0.4 - t * 0.05, 0.85, smoothstep(-0.2, 0.9, s * rings)) * smoothstep(1.4, 0.1, r) * GLOW;
  gl_FragColor = vec4(post(col), 1.0);
}""",
    "Dream clouds" to """
float h(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float n(vec2 p) { vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
  return mix(mix(h(i), h(i + vec2(1.0, 0.0)), f.x), mix(h(i + vec2(0.0, 1.0)), h(i + vec2(1.0, 1.0)), f.x), f.y); }
float fbm(vec2 p) { float v = 0.0; float a = 0.5; for (int k = 0; k < 5; k++) { v += a * n(p); p *= 2.02; a *= 0.5; } return v; }
void main() {
  vec2 p = uvc() * 2.5; float t = T * 0.08;
  vec2 q = vec2(fbm(p + t), fbm(p + vec2(5.2, 1.3) - t));
  vec2 r = vec2(fbm(p + 4.0 * q + vec2(1.7, 9.2) + t * 1.5), fbm(p + 4.0 * q + vec2(8.3, 2.8) - t));
  float v = fbm(p + 4.0 * r * (0.5 + DEN));
  vec3 col = hsv(HUE + 0.6 + v * 0.5 + length(q) * 0.2, 0.7, v * v * 1.8) * GLOW;
  gl_FragColor = vec4(post(col), 1.0);
}"""
)

fun shaderSource(name: String): String = SRC[name] ?: SRC.values.first()

class ShaderRenderer(private val name: String) : GLSurfaceView.Renderer {
    @Volatile var style = BgStyle()
    @Volatile var reactive = false
    private var prog = 0
    private var w = 1f; private var h = 1f
    private var t = 0.0; private var last = 0L; private var clock = 0f
    private val quad = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)); position(0) }

    private fun shader(type: Int, src: String): Int = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val vs = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 P; void main(){ gl_Position = vec4(P, 0.0, 1.0); }")
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, SHADER_HEAD + shaderSource(name))
        prog = GLES20.glCreateProgram().also { GLES20.glAttachShader(it, vs); GLES20.glAttachShader(it, fs); GLES20.glBindAttribLocation(it, 0, "P"); GLES20.glLinkProgram(it) }
        last = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { GLES20.glViewport(0, 0, width, height); w = width.toFloat(); h = height.toFloat() }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime(); clock += (now - last) / 1e9f
        val style = if (reactive) soundStyleAt(this.style, clock) else this.style
        t += (now - last) / 1e9 * style.speed; last = now
        GLES20.glUseProgram(prog)
        fun u(n: String, v: Float) = GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, n), v)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "R"), w, h)
        u("T", (t % 100000.0).toFloat()); u("HUE", style.hue / 360f); u("GLOW", style.glow); u("SAT", style.saturation)
        u("BRI", style.brightness); u("ZOOM", style.size.coerceAtLeast(0.01f)); u("DEN", style.density)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "DIR"), style.dirX, style.dirY, style.dirZ)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
    }
}

@Composable
fun ShaderBackground(name: String, style: BgStyle, modifier: Modifier = Modifier, reactive: Boolean = false) {
    key(name) {
        val renderer = remember { ShaderRenderer(name) }
        renderer.style = style; renderer.reactive = reactive
        // fractals are heavier: render them at a lower internal resolution
        val q = Bg2.scale
        val (rw, rh) = if (name == "Mandelbrot dive" || name == "Julia bloom" || name == "Dream clouds") (640 * q).toInt() to (360 * q).toInt() else (960 * q).toInt() to (540 * q).toInt()
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    holder.setFixedSize(rw, rh)
                    isFocusable = false
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            update = { it.holder.setFixedSize(rw, rh) },
            modifier = modifier,
            onRelease = { it.onPause() }
        )
    }
}
