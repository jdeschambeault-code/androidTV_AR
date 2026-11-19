package com.starshine.launcher

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws a [BgScene] with OpenGL on its own thread:
 *   1. every enabled node renders into its own texture (geometry as glowing ribbons, fields as fragment shaders)
 *   2. the visible nodes are composited in order: fold (mirror/kaleido), warp by another node, mask by another node, blend
 * Node links read the texture of the other node from the same frame or, for a node later in the stack, the previous frame.
 * Budget for the Mali-G31: 960x540 output, soft fields at 480x270, 30 fps by default.
 */
class Bg2Renderer(private val ctx: Context, private val sceneOf: () -> BgScene = { Bg2.current }, private val reactive: () -> Boolean = { Bg2.reactOn }, private val monitor: Boolean = false, private val live: Boolean = true) : GLSurfaceView.Renderer {
    private class Prog(val id: Int) { val u = HashMap<String, Int>(); fun loc(n: String) = u.getOrPut(n) { GLES20.glGetUniformLocation(id, n) } }
    private class Target(val fbo: Int, val tex: Int, val w: Int, val h: Int)
    private class Phase(val type: String = "") { var t = 0f; var spin = 0f; var hue = 0f; var skip = false }
    /** A layer's running state (time, spin angle, hue drift), kept by layer number across scenes so a scene doesn't jump when
     *  edited; started afresh when another kind of layer takes that number (browsing from a spinning solid to a photo turned
     *  the photo by the solid's accumulated angle). */
    private fun phaseOf(l: Layer): Phase = phases.getOrPut("${l.id}:${l.type}") { Phase(l.type) }
    private class Mesh(val vbo: Int, val verts: Int)
    private class MediaTex(val tex: Int, val external: Boolean, val path: String, @Volatile var aspect: Float = 16f / 9f) {
        var surfaceTexture: SurfaceTexture? = null; var surface: Surface? = null; var player: ExoPlayer? = null
    }

    private val progs = HashMap<String, Prog?>()
    private val targets = HashMap<Int, Target>()
    private val phases = HashMap<String, Phase>()
    private val meshes = HashMap<String, Mesh>()
    private val media = HashMap<Int, MediaTex>()
    private val canvases = HashMap<Int, CanvasNode>()

    /** A classic background or line visual drawn by its own thread into a texture, using the same path as a video layer. */
    private inner class CanvasNode(val type: String) {
        val tex: Int; val st: SurfaceTexture; val surface: Surface
        @Volatile var look = BgStyle(); @Volatile var rate = 1f; @Volatile private var fresh = false; @Volatile private var run = true
        val w = (960 * Bg2.scale).toInt(); val h = (540 * Bg2.scale).toInt()
        init {
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex); clampLinear(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            st = SurfaceTexture(tex); st.setDefaultBufferSize(w, h); st.setOnFrameAvailableListener { fresh = true }
            surface = Surface(st)
            Thread({
                val draw = androidx.compose.ui.graphics.drawscope.CanvasDrawScope()
                var last = System.nanoTime(); var clock = 0f
                // these scenes are tuned in 1080p pixels, so draw at that size and scale to the texture
                val k = w / 1920f
                while (run) {
                    val c = runCatching { surface.lockHardwareCanvas() }.getOrNull() ?: break
                    val now = System.nanoTime(); clock += (now - last) / 1e9f * rate; last = now
                    val s = look
                    runCatching {
                        c.scale(k, k)
                        synchronized(CANVAS_LOCK) {
                            draw.draw(androidx.compose.ui.unit.Density(1f), androidx.compose.ui.unit.LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(c), androidx.compose.ui.geometry.Size(1920f, 1080f)) {
                                if (type.startsWith(LINES_PREFIX)) { drawRect(androidx.compose.ui.graphics.Color.Black); drawVisualLayer(type.removePrefix(LINES_PREFIX), clock, s.glow, s.hue) }
                                else drawProcedural(type.removePrefix(CLASSIC_PREFIX), clock, s)
                            }
                        }
                    }.onFailure { Log.e("StarshineBg", "canvas layer $type: $it") }
                    runCatching { surface.unlockCanvasAndPost(c) }
                    val wait = 33L - (System.nanoTime() - now) / 1_000_000L; if (wait > 0) Thread.sleep(wait)
                }
                runCatching { surface.release(); st.release() }
            }, "starshine-layer").apply { isDaemon = true; start() }
        }
        fun latch() { if (fresh) { fresh = false; runCatching { st.updateTexImage() } } }
        fun stop() { run = false }
    }

    private fun releaseCanvas(id: Int) { canvases.remove(id)?.let { it.stop(); GLES20.glDeleteTextures(1, intArrayOf(it.tex), 0) } }

    private fun drawCanvas(l: Layer, ph: Phase, hue: Float, glow: Float, size: Float, detail: Float, t: Target) {
        var n = canvases[l.id]
        if (n != null && n.type != l.type) { releaseCanvas(l.id); n = null }
        val node = n ?: CanvasNode(l.type).also { canvases[l.id] = it }
        node.rate = l["speed"]
        // position is applied when the layer is placed on screen, like every other layer
        node.look = BgStyle(speed = 1f, density = detail * 1.5f, glow = glow, hue = hue * 360f, size = size, saturation = l["sat"], twinkle = 1f)
        node.latch()
        val p = program("mediaOES", FULL_VS, MEDIA_OES_FS) ?: return
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(p.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, node.tex)
        GLES20.glUniform1i(p.loc("TEX"), 0)
        val asp = t.w.toFloat() / t.h
        GLES20.glUniform1f(p.loc("HUE"), 0f); GLES20.glUniform1f(p.loc("SAT"), 1f); GLES20.glUniform1f(p.loc("GLOW"), 1f); GLES20.glUniform1f(p.loc("ZOOM"), 1f)
        GLES20.glUniform1f(p.loc("ROT"), angleRad(l)); GLES20.glUniform2f(p.loc("OFF"), 0f, 0f); GLES20.glUniform1f(p.loc("ASP"), asp); GLES20.glUniform1f(p.loc("MA"), asp)
        GLES20.glUniform1f(p.loc("FIT"), 0f); GLES20.glUniform1f(p.loc("PERSP"), 0f); GLES20.glUniform4f(p.loc("TILT"), 0f, 0f, 1f, 1f)
        fullscreen()
    }
    private val main = Handler(Looper.getMainLooper())
    private var viewW = 960; private var viewH = 540
    private var lightTimer = 0f
    private val lightBuf = ByteBuffer.allocateDirect(16 * 16 * 4).order(ByteOrder.nativeOrder())
    private var last = 0L
    @Volatile var fps30 = true
    private val tri: FloatBuffer = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)); position(0) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // a new GL context invalidates every GL object
        clipA = null; clipB = null; warmClip = null; warmed = null; peVbo = 0; fxScene = null; fxHistA = null; fxHistB = null; fxSmallA = null; fxSmallB = null; fxAA = null; outFbo = 0; gridVbo = 0; tessMesh = null; depthRbs.clear(); progs.clear(); targets.clear(); meshes.clear(); releaseMedia(); canvases.values.forEach { it.stop() }; canvases.clear(); last = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { viewW = width; viewH = height }

    override fun onDrawFrame(gl: GL10?) {
        AudioProbe.frame()
        val now = System.nanoTime()
        val dt = ((now - last) / 1e9f).coerceIn(0.001f, 0.25f)
        if (fps30 && dt < 0.030f) { Thread.sleep(((0.032f - dt) * 1000).toLong().coerceAtLeast(1L)) }
        val n2 = System.nanoTime(); val step = ((n2 - last) / 1e9f).coerceIn(0.001f, 0.25f); last = n2
        renderFrame(sceneOf(), step, viewW, viewH)
    }

    /** One frame to the current framebuffer 0. Also used by the MP4 exporter. */
    /** Seconds each layer has been animating, by layer id. */
    private val animClock = HashMap<Int, Float>()
    /** Average time between frames, to tell whether the chip keeps up. */
    private var frameAvg = 1f / 60f

    /** The scene as drawn this frame: layers with animations get their moving values; the scene itself is never changed. */
    private fun animated(s: BgScene, dt: Float): BgScene {
        if (s.layers.none { LayerAnim.any(it) || it.ex.isNotEmpty() }) return s
        return s.copy(layers = s.layers.map { l ->
            if (!LayerAnim.any(l) && l.ex.isEmpty()) l else {
                val t = (animClock[l.id] ?: 0f) + dt; animClock[l.id] = t % 86400f
                Expr.apply(LayerAnim.apply(l, t), t)
            }
        })
    }

    fun renderFrame(sceneIn: BgScene, dt: Float, w: Int, h: Int) {
        AudioBands.tick(dt)
        Expr.tick(dt)
        val scene = animated(sceneIn, dt)
        frameAvg += (dt - frameAvg) * 0.1f
        // a journey's transition: both clips keep running. The nodes of the two scenes take turns, which keeps this chip's
        // frame rate and leaves time for the audio, while the mix itself updates every frame.
        val from = (if (live && !monitor) Bg2.blendFrom else null)?.let { animated(it, dt) }
        val mix = Bg2.blendMix
        if (from == null) blendFrame = 0
        // what has its own texture this frame: every layer of a flat scene; only the panels of a 3D scene
        // the outgoing scene renders its layers at half size: it is fading away (see targetFor)
        fading.clear(); from?.let { f -> nodes(f).forEach { fading.add(it.id) } }
        // a scene prepared ahead keeps what was built for it (programs, pictures, videos) until its transition uses it
        val warm = Bg2.warm?.takeIf { it !== sceneIn && live && !monitor }
        val active = nodes(scene) + (from?.let { nodes(it) } ?: emptyList())
        // drawn each frame: active. Kept from being freed: active and the prepared scene
        val keep = active + (warm?.let { nodes(it) } ?: emptyList())
        targets.keys.toList().filter { id -> keep.none { it.id == id } }.forEach { id -> targets.remove(id)?.let { deleteTarget(it) } }
        // nodes removed from the scene give their media back
        media.keys.toList().filter { id -> keep.none { it.id == id && it.kind == GenKind.Media } }.forEach { releaseMedia(it) }
        canvases.keys.toList().filter { id -> keep.none { it.id == id && it.kind == GenKind.Canvas } }.forEach { releaseCanvas(it) }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDisable(GLES20.GL_CULL_FACE)
        lastDt = dt
        if (warm != null && warmed !== warm) { warmUp(warm); warmed = warm }

        if (from == null) for (l in active) renderNode(l, dt)
        else {
            // both scenes move every frame while the chip keeps up (above ~40 fps); only below that do they take turns,
            // each moving at half rate, which keeps the picture and the sound fluid on a heavy pair of scenes
            val busy = frameAvg > (if (fps30) 1f / 26f else 1f / 40f)
            val turn = blendFrame % 2 == 0; val firstFrame = blendFrame == 0; blendFrame++
            val step = if (busy && !firstFrame) dt * 2f else dt
            for (l in nodes(from)) if (!busy || firstFrame || turn) renderNode(l, step)
            for (l in nodes(scene)) if (!busy || firstFrame || !turn) renderNode(l, step)
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val fxm = Bg2.fx; val fx = RenderFx.active(fxm) && !Bg2.noFx
        fxSpace = scene.space
        if (monitor && !Bg2.fullPreview) {
            // workspace: result in the monitor area, and each layer's own picture in its tile under it
            val k = w / 960f
            val vx = (Stage.X * k).toInt(); val vw = (Stage.W * k).toInt(); val vh = (Stage.H * k).toInt(); val vy = h - (Stage.Y * k).toInt() - vh
            val solo = Bg2.soloId
            if (fx && solo < 0) beginFx(vw, vh) else GLES20.glViewport(vx, vy, vw, vh)
            if (scene.space) draw3D(scene, vw, vh, dt, solo)
            else for (l in active) if (if (solo >= 0) l.id == solo else l.show) composite(l, scene)
            if (fx && solo < 0) endFx(fxm, vx, vy, vw, vh, dt)
            drawTiles(scene, w, h)
        } else {
            // with render effects the scene goes through a full-screen pass every frame, which halved the frame rate on this
            // chip (18-20 fps against 40 without). The scene is drawn smaller into the effects' input, at 3/4 size and at
            // half during a blend, and the last pass scales it up to the screen
            // never below 960 wide when still (720 during a blend): at Normal quality the surface is already 960x540, and
            // shrinking that again made every scene look soft. Only the higher qualities are reduced, to keep their speed
            val k = (if (!fx) 1f else if (from != null) maxOf(0.5f, 720f / w) else if (RenderFx.get(fxm, "aa") > 0.001f) 1f else maxOf(0.75f, 960f / w)).coerceAtMost(1f)
            val sw = (w * k).toInt(); val sh = (h * k).toInt()
            if (fx) beginFx(sw, sh)
            if (from != null) drawTransition(from, scene, mix, sw, sh)
            else if (scene.space) draw3D(scene, sw, sh, dt)
            else for (l in scene.layers) if (l.on && l.show) composite(l, scene)
            if (fx) endFx(fxm, 0, 0, w, h, dt)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
        lightTimer += dt
        if (lightTimer > 0.5f) {   // a small sample twice a second: enough to follow the picture, too little to slow it
            lightTimer = 0f
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            lightBuf.clear()
            GLES20.glReadPixels(w / 2 - 8, h / 2 - 8, 16, 16, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, lightBuf)
            var sum = 0
            for (i in 0 until 256) { sum += (lightBuf.get(i * 4).toInt() and 255) + (lightBuf.get(i * 4 + 1).toInt() and 255) + (lightBuf.get(i * 4 + 2).toInt() and 255) }
            Bg2.sceneLight += (sum / (256f * 765f) - Bg2.sceneLight) * 0.3f
        }
    }

    private var clipA: Target? = null
    private var clipB: Target? = null
    private var blendFrame = 0

    /* ---------- render effects: the scene goes to a texture, then through the camera ---------- */

    /** Where the scene's layers are drawn: the screen (0), or the effects' input while the scene has render effects. */
    private var outFbo = 0
    private var fxScene: Target? = null
    private var fxHistA: Target? = null; private var fxHistB: Target? = null
    private var fxAA: Target? = null
    private var fxSmallA: Target? = null; private var fxSmallB: Target? = null
    private var fxTime = 0f
    /** The scene being finished is 3D: the flare follows its sun. */
    private var fxSpace = false

    private fun beginFx(w: Int, h: Int) {
        val t = clip(fxScene, w, h).also { fxScene = it }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, t.fbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        outFbo = t.fbo
    }

    private fun pass(p: Prog, into: Target) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, into.fbo); GLES20.glViewport(0, 0, into.w, into.h)
        GLES20.glDisable(GLES20.GL_BLEND); fullscreen()
    }

    private fun endFx(fxm: Map<String, Float>, x: Int, y: Int, w: Int, h: Int, dt: Float) {
        outFbo = 0
        GLES20.glDisable(GLES20.GL_BLEND)
        fxTime = (fxTime + dt) % 1000f
        var src = fxScene ?: return
        // shutter: each frame keeps some of the last, so movement leaves a trail
        val motion = RenderFx.get(fxm, "motion")
        if (motion > 0.001f) {
            val old = clip(fxHistA, w, h); val into = clip(fxHistB, w, h)
            program("fxAcc", FULL_VS, ACC_FS)?.let { p ->
                GLES20.glUseProgram(p.id)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, old.tex)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src.tex)
                GLES20.glUniform1i(p.loc("CUR"), 0); GLES20.glUniform1i(p.loc("OLD"), 1); GLES20.glUniform1f(p.loc("K"), motion)
                pass(p, into)
            }
            fxHistA = into; fxHistB = old; src = into
        }
        // antialiasing (FXAA): finds edges by contrast in luminance and blends across them, before the other effects
        val aa = RenderFx.get(fxm, "aa")
        if (aa > 0.001f) {
            val into = clip(fxAA, src.w, src.h).also { fxAA = it }
            program("fxAA", FULL_VS, FXAA_FS)?.let { p ->
                GLES20.glUseProgram(p.id); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src.tex)
                GLES20.glUniform1i(p.loc("TEX"), 0); GLES20.glUniform2f(p.loc("PX"), 1f / src.w, 1f / src.h); GLES20.glUniform1f(p.loc("K"), aa)
                pass(p, into); src = into
            }
        }
        // a soft copy at a quarter of the size feeds bloom, glare, flare ghosts, depth of field and sharpening
        val needBlur = listOf("bloom", "glare", "flare", "dof", "sharpen").any { RenderFx.get(fxm, it) > 0.001f }
        var blur = src
        if (needBlur) {
            val sw = (w / 4).coerceAtLeast(8); val sh = (h / 4).coerceAtLeast(8)
            val a = clip(fxSmallA, sw, sh).also { fxSmallA = it }; val b = clip(fxSmallB, sw, sh).also { fxSmallB = it }
            program("fxBlur", FULL_VS, BLUR_FS)?.let { p ->
                GLES20.glUseProgram(p.id); GLES20.glUniform1i(p.loc("TEX"), 0); GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                fun step(from: Target, into: Target, dx: Float, dy: Float) { GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, from.tex); GLES20.glUniform2f(p.loc("D"), dx, dy); pass(p, into) }
                step(src, a, 1f / sw, 0f); step(a, b, 0f, 1f / sh); step(b, a, 2.5f / sw, 0f); step(a, b, 0f, 2.5f / sh)
            }
            blur = b
        }
        val p = program("fxPost", FULL_VS, POST_FS) ?: return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(x, y, w, h)
        GLES20.glUseProgram(p.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blur.tex)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src.tex)
        GLES20.glUniform1i(p.loc("SRC"), 0); GLES20.glUniform1i(p.loc("BLUR"), 1)
        GLES20.glUniform1f(p.loc("ASP"), w.toFloat() / h); GLES20.glUniform1f(p.loc("T"), fxTime)
        fun u(n: String, k: String) = GLES20.glUniform1f(p.loc(n), RenderFx.get(fxm, k))
        u("BLOOM", "bloom"); u("THR", "bloomT"); u("GLARE", "glare"); u("FLARE", "flare"); u("GHOSTS", "ghosts")
        u("DOF", "dof"); u("FOCR", "focusR"); u("CHROMA", "chroma"); u("DISTORT", "distort"); u("VIG", "vignette"); u("GRAIN", "grain")
        u("SHARP", "sharpen"); u("EXPO", "exposure"); u("CON", "contrast"); u("SAT", "fxSat"); u("TEMP", "temp"); u("GAM", "gamma")
        // in a 3D scene the flare sits on the sun and fades as it leaves the screen or goes behind a planet
        GLES20.glUniform1f(p.loc("LIGHT"), if (fxSpace) 1f else 0f)
        if (fxSpace) { GLES20.glUniform2f(p.loc("FL"), sunFlareX, sunFlareY); GLES20.glUniform1f(p.loc("FLARE"), RenderFx.get(fxm, "flare") * sunFlareK) }
        else GLES20.glUniform2f(p.loc("FL"), RenderFx.get(fxm, "flareX"), RenderFx.get(fxm, "flareY"))
        GLES20.glUniform2f(p.loc("FOC"), RenderFx.get(fxm, "focusX"), RenderFx.get(fxm, "focusY"))
        fullscreen()
    }

    /** The tiles under the monitor show each layer's own picture, as it renders, rather than an icon. */
    private fun drawTiles(scene: BgScene, w: Int, h: Int) {
        if (Med.subTab == 1 || Med.subTab == 2 || Med.planning != null) return
        val p = program("blit", FULL_VS, BLIT_FS) ?: return
        GLES20.glUseProgram(p.id); GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glUniform1i(p.loc("TEX"), 0)
        val n = scene.layers.size; val k = w / 960f
        scene.layers.forEachIndexed { i, l ->
            if (!Stage.visible(i, n)) return@forEachIndexed   // scrolled out of the row under the preview
            val t = targets[l.id] ?: return@forEachIndexed
            val tw = (Stage.tileW(n) * k).toInt(); val th = (Stage.tileH(n) * k).toInt()
            GLES20.glViewport((Stage.tileX(i, n) * k).toInt(), h - (Stage.TILE_Y * k).toInt() - th, tw, th)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.tex)
            fullscreen()
        }
        GLES20.glViewport(0, 0, w, h)
    }

    private fun clip(old: Target?, w: Int, h: Int): Target {
        if (old != null && old.w == w && old.h == h) return old
        old?.let { deleteTarget(it) }
        val tex = IntArray(1); val fbo = IntArray(1)
        GLES20.glGenTextures(1, tex, 0); GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        clampLinear(GLES20.GL_TEXTURE_2D)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[0], 0)
        return Target(fbo[0], tex[0], w, h)
    }

    /** Layers of the scene fading out in a transition: their pictures render at half size (targetFor). */
    private val fading = HashSet<Int>()
    private var warmed: BgScene? = null
    private var warmClip: Target? = null

    /**
     * Builds a scene once, off screen, a few seconds before a journey blends into it: its programs compile, its layer
     * pictures and videos are set up, so the transition's first frames have nothing to build (that was the jolt at its start).
     */
    private fun warmUp(s: BgScene) {
        val t0 = System.nanoTime()
        for (l in nodes(s)) renderNode(l, 0.001f)
        val t = clip(warmClip, 64, 36).also { warmClip = it }
        drawClip(t, s) { _, _ -> 1f }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        AudioProbe.event("renderer: ${s.name} built ahead in ${(System.nanoTime() - t0) / 1_000_000} ms")
    }

    /* ---------- universal particles (Particles.kt): one buffer of seeds, the whole simulation in the vertex shader ---------- */
    private var peVbo = 0
    private fun drawParticles(l: Layer, ph: Phase, op: Float, px: Float) {
        val p = program("pe3", Particles.VS, Particles.FS) ?: return
        if (peVbo == 0) {
            val b = IntArray(1); GLES20.glGenBuffers(1, b, 0); peVbo = b[0]
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, peVbo)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, Particles.MAX * 16, Particles.seeds(), GLES20.GL_STATIC_DRAW)
        }
        place(l, ph, 1f)
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        GLES20.glUseProgram(p.id)
        GLES20.glUniformMatrix4fv(p.loc("MVP"), 1, false, mMVP, 0)
        fun u(name: String, v: Float) = GLES20.glUniform1f(p.loc(name), v)
        // world down, seen from inside the element (it may be tilted): gravity always pulls toward the floor
        Matrix.invertM(mInv, 0, mModel, 0); v4[0] = 0f; v4[1] = -1f; v4[2] = 0f; v4[3] = 0f; Matrix.multiplyMV(o4, 0, mInv, 0, v4, 0)
        val gl = kotlin.math.sqrt(o4[0] * o4[0] + o4[1] * o4[1] + o4[2] * o4[2]).coerceAtLeast(0.0001f)
        GLES20.glUniform3f(p.loc("GDIR"), o4[0] / gl, o4[1] / gl, o4[2] / gl); u("TAN", l["peTan"])
        u("T", ph.t % 100000f); u("PX", px * l["scale"]); u("N", Particles.count(l).toFloat())
        u("SHAPE", l["peShape"]); u("RATE", l["peRate"].coerceAtLeast(1f)); u("LIFE", l["peLife"].coerceAtLeast(0.05f)); u("LIFEVAR", l["peLifeVar"])
        GLES20.glUniform3f(p.loc("DIR"), l["peDirX"], l["peDirY"], l["peDirZ"])
        u("SPREAD", l["peSpread"]); u("OUTW", l["peOut"]); u("SPEED", l["peSpeed"]); u("SPEEDVAR", l["peSpeedVar"])
        u("GRAV", l["peGrav"]); u("DRAG", l["peDrag"]); u("SWIRL", l["peSwirl"]); u("TURB", l["peTurb"]); u("TURBF", l["peTurbF"])
        u("SIZE0", l["peSize"] * (1f + 0.4f * mod(l, 3))); u("SIZE1", l["peSizeEnd"]);   // React to sound: Size u("FADEIN", l["peFadeIn"]); u("FADEOUT", l["peFadeOut"])
        u("HUE0", l["peHue"] + ph.hue); u("HUE1", l["peHueEnd"] + ph.hue); u("HUEVAR", l["peHueVar"]); u("SAT", l["peSat"]); u("GLOW", l["peGlow"] * (1f + mod(l, 0)))
        u("OP", op); u("LOOK", l["peLook"]); u("JULIA", l["peJulia"])
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, peVbo); GLES20.glEnableVertexAttribArray(0); GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, Particles.count(l))
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun drawClip(t: Target, scene: BgScene, factor: (Int, Layer) -> Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, t.fbo)
        GLES20.glViewport(0, 0, t.w, t.h)
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (scene.space) draw3D(scene, t.w, t.h, lastDt)
        else scene.layers.forEachIndexed { i, l -> if (l.on && l.show) composite(l, scene, factor(i, l)) }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /* ---------- 3D scenes: elements in one space, seen through the scene's camera ---------- */

    private var lastDt = 0f
    private var tessMesh: Mesh? = null
    /** The 3D sun on screen (x, y in -1..1) and how much of it shows: 0 when hidden or off screen. */
    private var sunFlareX = 0f; private var sunFlareY = 0f; private var sunFlareK = 0f
    private val PLACE_KEYS = setOf("x", "y", "z", "angle", "tiltX", "tiltY", "stx", "sty", "stz", "scale", "opacity", "fold", "maskBy", "warpBy", "spin")
    /** Layers that get their own texture: all of a flat scene's; a 3D scene's panels, as the flat layer they show. */
    private fun nodes(s: BgScene): List<Layer> =
        if (!s.space) s.layers.filter { it.on }
        // the picture on a panel stays upright: turning is the panel's own, set by the user
        else s.layers.filter { it.on && Space.isPanel(it.type) }.map { l -> Layer(l.id, Space.inner(l.type), l.v - PLACE_KEYS + ("spin" to 0f), l.media) }

    private val mView = FloatArray(16); private val mProj = FloatArray(16); private val mVP = FloatArray(16)
    private val mModel = FloatArray(16); private val mMVP = FloatArray(16); private val mTmp = FloatArray(16)
    private val v4 = FloatArray(4); private val o4 = FloatArray(4)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(12 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f)); position(0) }

    /** Point clouds: count, size in the world, spread (world units at size 1), hue spread, soft sprites, brightness. */
    private class Cloud(val max: Int, val world: Float, val spread: Float, val hueVar: Float, val soft: Boolean, val bright: Float)
    private val CLOUDS = mapOf(
        "Stars" to (0 to Cloud(3500, 0.55f, 100f, 0.25f, false, 1f)), "Glow orbs" to (1 to Cloud(28, 1.4f, 6f, 0.3f, true, 0.7f)),
        "Dust" to (2 to Cloud(1600, 0.05f, 8f, 0.15f, false, 0.8f)), "Galaxy" to (3 to Cloud(5000, 0.12f, 10f, 0.45f, false, 0.8f)),
        "Asteroid ring" to (4 to Cloud(3000, 0.07f, 4f, 0.12f, false, 0.9f)), "Nebula cloud" to (5 to Cloud(80, 6f, 8f, 0.25f, true, 0.22f)),
        "Ocean waves" to (6 to Cloud(3600, 0.05f, 3f, 0.1f, false, 0.8f)), "Wind" to (7 to Cloud(1500, 0.03f, 3f, 0.1f, false, 0.6f)),
        "Embers" to (8 to Cloud(600, 0.05f, 1.5f, 0.15f, false, 1f)), "Rain" to (9 to Cloud(2500, 0.02f, 3f, 0.05f, false, 0.6f)),
        "Snow" to (10 to Cloud(1500, 0.04f, 3f, 0.05f, false, 0.8f)), "Fireflies" to (11 to Cloud(60, 0.1f, 2f, 0.2f, true, 1f)),
        "Smoke" to (12 to Cloud(140, 0.9f, 1.2f, 0.1f, true, 0.25f)), "Fountain" to (13 to Cloud(1500, 0.035f, 1.2f, 0.1f, false, 0.9f)))

    private fun cloudMesh(mode: Int, max: Int): Mesh = meshes.getOrPut("sp:$mode") {
        val r = java.util.Random(4242L + mode)
        val fb = ByteBuffer.allocateDirect(max * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()
        repeat(max) { i ->
            var x: Float; var y: Float; var z: Float; var w = r.nextFloat()
            when (mode) {
                0 -> { val u = r.nextFloat() * 2f - 1f; val a = r.nextFloat() * 6.2831855f; val s = kotlin.math.sqrt(1f - u * u); val d = 0.85f + 0.15f * r.nextFloat()
                    x = s * cos(a) * d; y = u * d; z = s * sin(a) * d; w = w * w * w }
                1 -> { val d = Math.cbrt(r.nextDouble()).toFloat(); val u = r.nextFloat() * 2f - 1f; val a = r.nextFloat() * 6.2831855f; val s = kotlin.math.sqrt(1f - u * u)
                    x = s * cos(a) * d; y = u * d; z = s * sin(a) * d }
                2, in 7..13 -> { x = r.nextFloat(); y = r.nextFloat(); z = r.nextFloat() }
                6 -> { x = (i % 60) / 59f * 2f - 1f; z = (i / 60) / 59f * 2f - 1f; y = 0f }
                3 -> if (i % 7 == 0) { x = (r.nextGaussian() * 0.12).toFloat(); y = (r.nextGaussian() * 0.06).toFloat(); z = (r.nextGaussian() * 0.12).toFloat(); w *= 0.1f }
                    else { val d = 0.08f + 0.92f * Math.pow(r.nextDouble(), 0.8).toFloat(); val a = (i % 2) * 3.1415927f + d * 4f * 3.1415927f + (r.nextGaussian() * 0.3 / (d + 0.3)).toFloat()
                        x = d * cos(a); z = d * sin(a); y = (r.nextGaussian() * 0.03 * (1.2 - d)).toFloat(); w = 0.3f + 0.7f * d }
                4 -> { val d = 0.65f + 0.35f * r.nextFloat(); val a = r.nextFloat() * 6.2831855f; x = d * cos(a); z = d * sin(a); y = (r.nextGaussian() * 0.015).toFloat() }
                else -> { x = (r.nextGaussian() * 0.6).toFloat(); y = (r.nextGaussian() * 0.3).toFloat(); z = (r.nextGaussian() * 0.45).toFloat() }
            }
            fb.put(x); fb.put(y); fb.put(z); fb.put(w)
        }
        fb.position(0)
        val b = IntArray(1); GLES20.glGenBuffers(1, b, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, b[0]); GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, max * 16, fb, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        Mesh(b[0], max)
    }

    /** Places an element: position, then rotation (X, Y, Z), then its own spin, then its size. */
    /** How far each panel has turned: kept apart from the picture it shows, which has its own clock. */
    private val panelTurn = HashMap<Int, Float>()

    private val mInv = FloatArray(16)
    private var curFov = 60f

    /** onCam: placed on the camera instead of in the world, at the distance where a size-1 panel exactly fills its frame. */
    private fun place(l: Layer, ph: Phase, k: Float, at: FloatArray? = null, onCam: Boolean = false) {
        Matrix.setIdentityM(mModel, 0)
        if (onCam) {
            Matrix.invertM(mInv, 0, mView, 0); System.arraycopy(mInv, 0, mModel, 0, 16)
            val d = k / kotlin.math.tan(Math.toRadians(curFov / 2.0)).toFloat()
            Matrix.translateM(mModel, 0, l["x"], l["y"], l["z"] - d)
        } else if (at != null) Matrix.translateM(mModel, 0, at[0], at[1], at[2]) else Matrix.translateM(mModel, 0, l["x"], l["y"], l["z"])
        Matrix.rotateM(mModel, 0, l["tiltX"], 1f, 0f, 0f); Matrix.rotateM(mModel, 0, l["tiltY"], 0f, 1f, 0f); Matrix.rotateM(mModel, 0, l["angle"], 0f, 0f, 1f)
        val turn = if (Space.isPanel(l.type)) panelTurn[l.id] ?: 0f else ph.spin
        Matrix.rotateM(mModel, 0, Math.toDegrees(turn.toDouble()).toFloat(), 0f, 1f, 0f)
        val s = l["scale"] * k * (1f + 0.15f * mod(l, 3))
        Matrix.scaleM(mModel, 0, s * l["stx"], s * l["sty"], s * l["stz"])
        Matrix.multiplyMM(mMVP, 0, mVP, 0, mModel, 0)
    }

    /** Where a world point lands on screen: x, y in -1..1 once divided by w, its distance (0 or less = behind the camera). */
    private fun project(x: Float, y: Float, z: Float): FloatArray {
        v4[0] = x; v4[1] = y; v4[2] = z; v4[3] = 1f; Matrix.multiplyMV(o4, 0, mVP, 0, v4, 0); return o4.copyOf()
    }

    private fun draw3D(scene: BgScene, vw: Int, vh: Int, dt: Float, solo: Int = -1) {
        val cam = scene.layers.firstOrNull { it.type == CAMERA } ?: Space.camera()
        val cph = phases.getOrPut("cam:${cam.id}") { Phase() }; cph.t += dt
        // camera: it orbits the point it looks at, or looks freely with its own rotation
        val tx = cam["tx"]; val ty = cam["ty"]; val tz = cam["tz"]
        val orbitDeg = cph.t * cam["orbit"]; val oa = Math.toRadians(orbitDeg.toDouble())
        var ex = cam["x"]; val ey = cam["y"]; var ez = cam["z"]
        if (cam["orbit"] != 0f) { val dx = ex - tx; val dz = ez - tz; val c = cos(oa).toFloat(); val s = sin(oa).toFloat(); ex = tx + dx * c + dz * s; ez = tz - dx * s + dz * c }
        if (cam["free"] > 0.5f) {
            Matrix.setIdentityM(mView, 0)
            Matrix.rotateM(mView, 0, cam["angle"], 0f, 0f, 1f); Matrix.rotateM(mView, 0, -cam["tiltX"], 1f, 0f, 0f); Matrix.rotateM(mView, 0, -cam["tiltY"] - orbitDeg, 0f, 1f, 0f)
            Matrix.translateM(mView, 0, -ex, -ey, -ez)
        } else {
            val same = kotlin.math.abs(ex - tx) + kotlin.math.abs(ey - ty) + kotlin.math.abs(ez - tz) < 0.001f
            Matrix.setLookAtM(mView, 0, ex, ey, if (same) ez + 0.01f else ez, tx, ty, tz, 0f, 1f, 0f)
            if (cam["angle"] != 0f) { Matrix.setRotateM(mTmp, 0, cam["angle"], 0f, 0f, 1f); Matrix.multiplyMM(mModel, 0, mTmp, 0, mView, 0); System.arraycopy(mModel, 0, mView, 0, 16) }
        }
        // head tracking: the head's rotation on top of the camera's own view (HeadTrack)
        if (HeadTrack.on) {
            Matrix.setIdentityM(mTmp, 0)
            Matrix.rotateM(mTmp, 0, -HeadTrack.roll, 0f, 0f, 1f); Matrix.rotateM(mTmp, 0, HeadTrack.pitch, 1f, 0f, 0f); Matrix.rotateM(mTmp, 0, HeadTrack.yaw, 0f, 1f, 0f)
            Matrix.multiplyMM(mModel, 0, mTmp, 0, mView, 0); System.arraycopy(mModel, 0, mView, 0, 16)
        }
        curFov = cam["fov"].coerceIn(10f, 140f)
        Matrix.perspectiveM(mProj, 0, cam["fov"].coerceIn(10f, 140f), vw.toFloat() / vh.coerceAtLeast(1), 0.05f, 5000f)
        Matrix.multiplyMM(mVP, 0, mProj, 0, mView, 0)
        val right = floatArrayOf(mView[0], mView[4], mView[8]); val up = floatArrayOf(mView[1], mView[5], mView[9]); val back = floatArrayOf(mView[2], mView[6], mView[10])
        val px = mProj[5] * vh * 0.5f

        val all = scene.layers.filter { it.on && it.show && it.type != CAMERA && (solo < 0 || it.id == solo) }
        // a panel only turns if its own "Keeps turning" is set (the layers' slow default turn doesn't apply to it)
        for (l in all) if (Space.isPanel(l.type)) panelTurn[l.id] = ((panelTurn[l.id] ?: 0f) + dt * l["spin"] * 6.2831855f) % 6.2831855f
        for (l in all) if (!Space.isPanel(l.type)) {
            val ph = phaseOf(l)
            ph.t += dt * l["speed"]; ph.spin = if (l["spin"] == 0f) 0f else ph.spin + dt * l["spin"] * (1f + 3f * mod(l, 2)) * 6.2831855f; ph.hue += dt * l["hueDrift"]
        }
        val sun = all.firstOrNull { it.type == "Sun" }
        // farthest first: everything glows and adds up, and a planet covers what is behind it
        val order = all.sortedByDescending { l -> if (l.type == "Stars") Float.MAX_VALUE else { val dx = l["x"] - ex; val dy = l["y"] - ey; val dz = l["z"] - ez; dx * dx + dy * dy + dz * dz } }
        for (l in order) {
            val ph = phases["${l.id}:${l.type}"] ?: Phase()
            val hue = (l["hue"] + ph.hue + 80f * mod(l, 4)) / 360f
            val glow = l["glow"] * (1f + mod(l, 0)); val op = (l["opacity"] * (1f + mod(l, 1))).coerceIn(0f, 1f)
            val detail = (l["detail"] + 0.35f * mod(l, 6)).coerceIn(0f, 1f)
            val cloud = CLOUDS[l.type]
            when {
                l.type == Particles.TYPE -> drawParticles(l, ph, op, px)
                cloud != null -> {
                    val (mode, c) = cloud
                    val p = program("p3", P3_VS, P3_FS) ?: continue
                    place(l, ph, c.spread, if (mode == 0) floatArrayOf(ex, ey, ez) else null)
                    val m = cloudMesh(mode, c.max)
                    GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
                    GLES20.glUseProgram(p.id)
                    GLES20.glUniformMatrix4fv(p.loc("MVP"), 1, false, mMVP, 0)
                    GLES20.glUniform1f(p.loc("T"), ph.t % 100000f); GLES20.glUniform1f(p.loc("SZ"), c.world * l["scale"] * l["psize"]); GLES20.glUniform1f(p.loc("PX"), px); GLES20.glUniform1f(p.loc("TW"), l["twink"])
                    GLES20.glUniform1f(p.loc("MODE"), mode.toFloat()); GLES20.glUniform1f(p.loc("HUE"), hue); GLES20.glUniform1f(p.loc("SAT"), l["sat"].coerceIn(0f, 1f))
                    GLES20.glUniform1f(p.loc("HV"), c.hueVar * l["hvar"]); GLES20.glUniform1f(p.loc("GLOW"), glow); GLES20.glUniform1f(p.loc("OP"), op * c.bright); GLES20.glUniform1f(p.loc("SOFT"), if (c.soft) 1f else 0f)
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, m.vbo); GLES20.glEnableVertexAttribArray(0); GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, 0, 0)
                    GLES20.glDrawArrays(GLES20.GL_POINTS, 0, (c.max * (0.15f + 0.85f * detail)).toInt().coerceAtLeast(1))
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
                }
                l.type == "Sun" || SpaceShapes.isWorld(l.type) -> {
                    val isSun = l.type == "Sun"
                    val p = (if (isSun) program("sun3", BILL_VS, SUN_FS) else program("planet3", BILL_VS, PLANET_FS)) ?: continue
                    val radius = l["scale"] * (1f + 0.15f * mod(l, 3))
                    GLES20.glEnable(GLES20.GL_BLEND)
                    if (isSun) GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE) else GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                    GLES20.glUseProgram(p.id)
                    GLES20.glUniformMatrix4fv(p.loc("VP"), 1, false, mVP, 0)
                    GLES20.glUniform3f(p.loc("C"), l["x"], l["y"], l["z"]); GLES20.glUniform3fv(p.loc("RIGHT"), 1, right, 0); GLES20.glUniform3fv(p.loc("UP"), 1, up, 0)
                    GLES20.glUniform1f(p.loc("T"), ph.t % 100000f); GLES20.glUniform1f(p.loc("OP"), op); GLES20.glUniform1f(p.loc("GLOW"), glow)
                    if (isSun) {
                        // the quad grows to hold a large corona or long rays
                        val s = maxOf(1f, l["rayLen"], kotlin.math.sqrt(l["corona"]))
                        GLES20.glUniform1f(p.loc("R"), radius * 2f * s); GLES20.glUniform1f(p.loc("QS"), s)
                        GLES20.glUniform1f(p.loc("CORONA"), l["corona"]); GLES20.glUniform1f(p.loc("RAYLEN"), l["rayLen"])
                        GLES20.glUniform1f(p.loc("ROT"), angleRad(l) + ph.t * Math.toRadians(l["raySpin"].toDouble()).toFloat())
                        val c = hsv(hue, (l["sat"] * 0.6f).coerceIn(0f, 1f)); GLES20.glUniform3f(p.loc("COL"), c[0], c[1], c[2])
                        GLES20.glUniform1f(p.loc("RAYS"), l["rays"])
                    } else {
                        val qs = 1f + 0.15f * l["atmo"]
                        GLES20.glUniform1f(p.loc("R"), radius * qs); GLES20.glUniform1f(p.loc("QS"), qs)
                        GLES20.glUniform1f(p.loc("BANDS"), l["bands"]); GLES20.glUniform1f(p.loc("ATMO"), l["atmo"])
                        val c = hsv(hue, (l["sat"] * 0.7f).coerceIn(0f, 1f)); val c2 = hsv(hue + l["hue2"] / 360f, (l["sat"] * 0.5f).coerceIn(0f, 1f))
                        GLES20.glUniform3f(p.loc("COL"), c[0] * 0.9f, c[1] * 0.9f, c[2] * 0.9f); GLES20.glUniform3f(p.loc("COL2"), c2[0] * 0.45f, c2[1] * 0.45f, c2[2] * 0.45f)
                        // lit from where the sun is: its direction, in the planet's own picture (right, up, toward the camera)
                        var lx = 0.5f; var ly = 0.5f; var lz = 0.7f
                        if (sun != null) {
                            var dx = sun["x"] - l["x"]; var dy = sun["y"] - l["y"]; var dz = sun["z"] - l["z"]
                            val n = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.0001f); dx /= n; dy /= n; dz /= n
                            lx = dx * right[0] + dy * right[1] + dz * right[2]; ly = dx * up[0] + dy * up[1] + dz * up[2]; lz = dx * back[0] + dy * back[1] + dz * back[2]
                        }
                        GLES20.glUniform3f(p.loc("L"), lx, ly, lz); GLES20.glUniform1f(p.loc("AMB"), if (sun != null) 0.06f else 0.3f); GLES20.glUniform1f(p.loc("SPIN"), ph.spin + Math.toRadians(l["tiltY"].toDouble()).toFloat())
                    }
                    GLES20.glEnableVertexAttribArray(0); GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
                }
                Space.isObject(l.type) || SpaceShapes.isLines(l.type) -> {
                    val p = program("line3", LINE3_VS, LINE3_FS) ?: continue
                    val inner = Space.inner(l.type)
                    val dim = if (Solids.isHyper(inner)) Solids.dimOf(inner, l["dim"]) else 0
                    val m = when {
                        // the tesseract turns in 4D: its lines are worked out again each frame, into the same buffer
                        l.type == SpaceShapes.TESSERACT -> uploadRibbons(SpaceShapes.tesseract(ph.t * 0.5f), tessMesh?.vbo ?: 0).also { tessMesh = it }
                        l.type in SpaceShapes.PLANTS -> meshes.getOrPut("pl:" + l.type) { uploadRibbons(SpaceShapes.mesh(l.type)) }
                        else -> meshes.getOrPut(if (dim > 0) inner + dim else inner) { uploadRibbons(Geometry.of(inner, dim)) }
                    }
                    place(l, ph, 1f)
                    GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
                    GLES20.glUseProgram(p.id)
                    GLES20.glUniformMatrix4fv(p.loc("MVP"), 1, false, mMVP, 0)
                    GLES20.glUniform1f(p.loc("WID"), 0.004f + detail * 0.02f); GLES20.glUniform1f(p.loc("ASP"), vw.toFloat() / vh.coerceAtLeast(1))
                    // the sun's colour falls a little on everything in its light
                    var c = hsv(hue, (0.75f * l["sat"]).coerceIn(0f, 1f))
                    if (sun != null) { val s = hsv(sun["hue"] / 360f, (sun["sat"] * 0.6f).coerceIn(0f, 1f)); c = floatArrayOf(c[0] * 0.8f + s[0] * 0.2f, c[1] * 0.8f + s[1] * 0.2f, c[2] * 0.8f + s[2] * 0.2f) }
                    GLES20.glUniform3f(p.loc("COL"), c[0], c[1], c[2]); GLES20.glUniform1f(p.loc("GLOW"), glow * op)
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, m.vbo)
                    GLES20.glEnableVertexAttribArray(0); GLES20.glEnableVertexAttribArray(1); GLES20.glEnableVertexAttribArray(2)
                    GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 32, 0); GLES20.glVertexAttribPointer(1, 3, GLES20.GL_FLOAT, false, 32, 12); GLES20.glVertexAttribPointer(2, 2, GLES20.GL_FLOAT, false, 32, 24)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, m.verts)
                    GLES20.glDisableVertexAttribArray(1); GLES20.glDisableVertexAttribArray(2)
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
                }
                Space.isPanel(l.type) -> {
                    val t = targets[l.id] ?: continue
                    val p = program("panel3", PANEL_VS, PANEL_FS) ?: continue
                    place(l, ph, 1.5f, onCam = l["world"] < 0.5f)
                    if (l["blend"].toInt() == 2) GLES20.glDisable(GLES20.GL_BLEND) else { GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE) }
                    GLES20.glUseProgram(p.id)
                    GLES20.glUniformMatrix4fv(p.loc("MVP"), 1, false, mMVP, 0); GLES20.glUniform1f(p.loc("AR"), 16f / 9f); GLES20.glUniform1f(p.loc("OP"), op)
                    GLES20.glUniform1f(p.loc("PAR"), 16f / 9f); GLES20.glUniform1f(p.loc("FADE"), (l["fade"] + mod(l, 7) * 0.5f).coerceIn(0f, 1f)); maskUniforms(p, l)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.tex); GLES20.glUniform1i(p.loc("TEX"), 0)
                    GLES20.glEnableVertexAttribArray(0); GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
                }
            }
        }
        // the sun's lens flare, when the camera sees it and no planet stands in front of it
        // where the sun is on screen also steers the Render tab's lens flare, so it follows the light as the camera moves
        sunFlareK = 0f
        if (sun != null) {
            val s = project(sun["x"], sun["y"], sun["z"]); val sw = s[3]
            if (sw > 0.05f) {
                val sx = s[0] / sw; val sy = s[1] / sw
                val hidden = all.any { pl ->
                    if (!SpaceShapes.isWorld(pl.type)) false
                    else { val q = project(pl["x"], pl["y"], pl["z"]); val qw = q[3]
                        qw > 0.05f && qw < sw && kotlin.math.hypot((q[0] / qw - sx).toDouble(), (q[1] / qw - sy).toDouble()) < pl["scale"] * mProj[5] / qw }
                }
                val edge = (1.4f - maxOf(kotlin.math.abs(sx), kotlin.math.abs(sy))).coerceIn(0f, 0.4f) / 0.4f
                sunFlareX = sx; sunFlareY = sy; sunFlareK = if (hidden) 0f else edge * sun["opacity"]
                if (!hidden && edge > 0f && sun["flare"] > 0.01f) program("flare3", FULL_VS, FLARE3_FS)?.let { p ->
                    GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
                    GLES20.glUseProgram(p.id)
                    val c = hsv(sun["hue"] / 360f, (sun["sat"] * 0.6f).coerceIn(0f, 1f))
                    GLES20.glUniform2f(p.loc("S"), sx, sy); GLES20.glUniform1f(p.loc("ASP"), vw.toFloat() / vh.coerceAtLeast(1))
                    GLES20.glUniform1f(p.loc("K"), sun["flare"] * sun["opacity"] * edge); GLES20.glUniform3f(p.loc("COL"), c[0], c[1], c[2])
                    fullscreen()
                }
            }
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Two clips crossing over. With a layer offset the new scene's layers arrive one after another, the leading layer first. */
    private fun drawTransition(from: BgScene, scene: BgScene, mix: Float, w: Int, h: Int) {
        // while they blend the incoming scene is drawn at three-quarter size and the outgoing one at half: it is fading away
        val a = clip(clipA, w / 2, h / 2).also { clipA = it }; val b = clip(clipB, w * 3 / 4, h * 3 / 4).also { clipB = it }
        val lead = Bg2.blendLead; val offset = Bg2.blendOffset
        val order = scene.layers.sortedBy { if (it.id == lead) -1 else scene.layers.indexOf(it) }.map { it.id }
        val n = (order.size - 1).coerceAtLeast(1)
        drawClip(a, from) { _, _ -> 1f }
        drawClip(b, scene) { _, l -> if (offset <= 0f) 1f else (mix * (1f + offset) - offset * order.indexOf(l.id) / n).coerceIn(0f, 1f) }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outFbo)
        GLES20.glViewport(0, 0, w, h)
        val p = program("trans", FULL_VS, TRANS_FS) ?: return
        GLES20.glUseProgram(p.id); GLES20.glDisable(GLES20.GL_BLEND)
        val matte = targets[lead]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, matte?.tex ?: b.tex)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, b.tex)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, a.tex)
        GLES20.glUniform1i(p.loc("A"), 0); GLES20.glUniform1i(p.loc("B"), 1); GLES20.glUniform1i(p.loc("M"), 2)
        GLES20.glUniform1f(p.loc("T"), mix); GLES20.glUniform1f(p.loc("MODE"), Bg2.blendMode.toFloat())
        GLES20.glUniform1f(p.loc("LEAD"), if (matte != null) 1f else 0f); GLES20.glUniform1f(p.loc("ASP"), w.toFloat() / h)
        fullscreen()
    }

    /* ---------- modulation ---------- */

    private fun mod(l: Layer, target: Int): Float {
        if (!reactive()) return 0f
        var m = 0f
        if (l["r1a"] > 0f && l["r1t"].toInt() == target) m += l["r1a"] * AudioBands.band(l["r1s"].toInt())
        if (l["r2a"] > 0f && l["r2t"].toInt() == target) m += l["r2a"] * AudioBands.band(l["r2s"].toInt())
        return m
    }

    /* ---------- node textures ---------- */

    private fun targetFor(l: Layer): Target {
        val soft = l.kind == GenKind.Classic || l.type in setOf("Clouds", "Fire", "Water", "Nebula", "Globe", "Candle", "Aether") || (l.type == FRACTAL && l["f3d"] < 0.5f)
        // the flat fractal is the costliest field there is: it renders at a third of the screen and is smoothed when placed
        val q = Bg2.scale * (if (l.type == FRACTAL && l["f3d"] < 0.5f) 0.67f else 1f) * (if (l.id in fading) 0.5f else 1f)
        val w = ((if (soft) 480 else 960) * q).toInt(); val h = ((if (soft) 270 else 540) * q).toInt()
        targets[l.id]?.let { if (it.w == w) return it else { deleteTarget(it); targets.remove(l.id) } }
        val tex = IntArray(1); val fbo = IntArray(1)
        GLES20.glGenTextures(1, tex, 0); GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        clampLinear(GLES20.GL_TEXTURE_2D)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[0], 0)
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        return Target(fbo[0], tex[0], w, h).also { targets[l.id] = it }
    }

    private fun deleteTarget(t: Target) {
        depthRbs.remove(t.fbo)?.let { GLES20.glDeleteRenderbuffers(1, intArrayOf(it), 0) }
        GLES20.glDeleteTextures(1, intArrayOf(t.tex), 0); GLES20.glDeleteFramebuffers(1, intArrayOf(t.fbo), 0)
    }

    private fun clampLinear(kind: Int) {
        GLES20.glTexParameteri(kind, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR); GLES20.glTexParameteri(kind, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(kind, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE); GLES20.glTexParameteri(kind, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun renderNode(l: Layer, dt: Float) {
        val t = targetFor(l)
        val ph = phaseOf(l)
        ph.t += dt * l["speed"]
        // Spin target speeds the rotation up instead of jumping it, so reaction never looks like a zoom
        ph.spin = if (l["spin"] == 0f) 0f else ph.spin + dt * l["spin"] * (1f + 3f * mod(l, 2)) * 6.2831855f
        ph.hue += dt * l["hueDrift"]
        // fractals change slowly: drawn every other frame, which halves their cost while the rest of the scene stays smooth
        if (l.type == FRACTAL) { ph.skip = !ph.skip; if (ph.skip) return }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, t.fbo)
        GLES20.glViewport(0, 0, t.w, t.h)
        val trail = l["trail"]
        if (trail > 0.001f) {
            GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ZERO, GLES20.GL_SRC_ALPHA)
            program("solid", SOLID_VS, SOLID_FS)?.let { p -> GLES20.glUseProgram(p.id); GLES20.glUniform1f(p.loc("A"), trail); fullscreen() }
        } else {
            GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        val hue = (l["hue"] + ph.hue + 80f * mod(l, 4)) / 360f
        val glow = l["glow"] * (1f + mod(l, 0))
        val size = l["scale"] * (1f + 0.15f * mod(l, 3))
        val detail = (l["detail"] + 0.35f * mod(l, 6)).coerceIn(0f, 1f)
        when (l.kind) {
            GenKind.Line, GenKind.Solid -> drawLines(l, ph, hue, glow, size, detail, t)
            GenKind.Points -> drawPoints(l, ph, hue, glow, size, detail, t)
            GenKind.Screen, GenKind.Classic -> drawField(l, ph, hue, glow, size, detail, t)
            GenKind.Media -> drawMedia(l, ph, hue, glow, size, t)
            GenKind.Canvas -> drawCanvas(l, ph, hue, glow, size, detail, t)
            GenKind.Space -> {}   // 3D elements are drawn by draw3D, not as flat layers
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawField(l: Layer, ph: Phase, hue: Float, glow: Float, size: Float, detail: Float, t: Target) {
        if (l.type == FRACTAL && l["f3d"] > 0.5f) { drawFractalMesh(l, ph, hue, glow, size, detail, t); return }
        val p = when {
            l.type == FRACTAL -> program("fractal", FULL_VS, SHADER_HEAD + FIELD_EXT + Fractal.SHADER)
            l.kind == GenKind.Classic -> program("cl:" + l.type, FULL_VS, SHADER_HEAD + FIELD_EXT + shaderSource(l.type))
            else -> program("f:" + l.type, FULL_VS, SHADER_HEAD + FIELD_EXT + asset(FIELD_FILES[l.type] ?: "clouds"))
        }
        p ?: return
        if (l.type == FRACTAL) fractalUniforms(p, l, ph)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(p.id)
        GLES20.glUniform2f(p.loc("R"), t.w.toFloat(), t.h.toFloat())
        GLES20.glUniform1f(p.loc("T"), ph.t % 100000f); GLES20.glUniform1f(p.loc("HUE"), hue); GLES20.glUniform1f(p.loc("GLOW"), glow)
        GLES20.glUniform1f(p.loc("SAT"), l["sat"]); GLES20.glUniform1f(p.loc("BRI"), 1f); GLES20.glUniform1f(p.loc("ZOOM"), size.coerceAtLeast(0.01f))
        GLES20.glUniform1f(p.loc("DEN"), detail); GLES20.glUniform3f(p.loc("DIR"), 0f, 0f, 1f)
        GLES20.glUniform1f(p.loc("ROT"), ph.spin + angleRad(l)); GLES20.glUniform2f(p.loc("OFF"), l["x"], l["y"])
        fullscreen()
    }

    private val palBuf = FloatArray(15)

    /* ---------- Mandelbrot v2 in 3D: a grid displaced by the fractal ---------- */

    private val GX = 128; private val GY = 72
    private var gridVbo = 0; private var triIbo = 0; private var triCount = 0; private var lineIbo = 0; private var lineCount = 0
    /** Depth buffers added to the textures of 3D fractal layers, by framebuffer. */
    private val depthRbs = HashMap<Int, Int>()

    private fun ensureGrid() {
        if (gridVbo != 0) return
        val fb = ByteBuffer.allocateDirect(GX * GY * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (y in 0 until GY) for (x in 0 until GX) { fb.put(x * 2f / (GX - 1) - 1f); fb.put(y * 2f / (GY - 1) - 1f) }
        fb.position(0)
        val tri = ByteBuffer.allocateDirect((GX - 1) * (GY - 1) * 6 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (y in 0 until GY - 1) for (x in 0 until GX - 1) {
            val a = (y * GX + x).toShort(); val b = (y * GX + x + 1).toShort(); val c = ((y + 1) * GX + x).toShort(); val d = ((y + 1) * GX + x + 1).toShort()
            tri.put(a); tri.put(b); tri.put(c); tri.put(b); tri.put(d); tri.put(c)
        }
        tri.position(0); triCount = (GX - 1) * (GY - 1) * 6
        val segs = (GX - 1) * GY + GX * (GY - 1)
        val ln = ByteBuffer.allocateDirect(segs * 2 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (y in 0 until GY) for (x in 0 until GX - 1) { ln.put((y * GX + x).toShort()); ln.put((y * GX + x + 1).toShort()) }
        for (y in 0 until GY - 1) for (x in 0 until GX) { ln.put((y * GX + x).toShort()); ln.put(((y + 1) * GX + x).toShort()) }
        ln.position(0); lineCount = segs * 2
        val b = IntArray(3); GLES20.glGenBuffers(3, b, 0)
        gridVbo = b[0]; triIbo = b[1]; lineIbo = b[2]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gridVbo); GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, GX * GY * 8, fb, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, triIbo); GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, triCount * 2, tri, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, lineIbo); GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, lineCount * 2, ln, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0); GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    /** Terrain is drawn solid with a depth test; the wire mesh and point cloud glow and add up, like the sacred geometry layers. */
    private fun drawFractalMesh(l: Layer, ph: Phase, hue: Float, glow: Float, size: Float, detail: Float, t: Target) {
        val p = program("fractalMesh", Fractal.MESH_VS, Fractal.MESH_FS) ?: return
        ensureGrid()
        fractalUniforms(p, l, ph)
        val mode = l["f3d"].toInt()
        GLES20.glUniformMatrix3fv(p.loc("ROTM"), 1, false, rotation(Math.toRadians(l["tiltX"].toDouble()).toFloat(), Math.toRadians(l["tiltY"].toDouble()).toFloat(), ph.spin + angleRad(l)), 0)
        GLES20.glUniform1f(p.loc("SCL"), size); GLES20.glUniform1f(p.loc("ASP"), t.w.toFloat() / t.h); GLES20.glUniform1f(p.loc("HGT"), l["fhgt"])
        GLES20.glUniform1f(p.loc("MAXI"), 24f + detail * 104f); GLES20.glUniform2f(p.loc("OFF"), l["x"], l["y"])
        GLES20.glUniform1f(p.loc("T"), ph.t % 100000f); GLES20.glUniform1f(p.loc("HUE"), hue); GLES20.glUniform1f(p.loc("GLOW"), glow)
        GLES20.glUniform1f(p.loc("PTS"), 2.5f * t.h / 540f); GLES20.glUniform1f(p.loc("DIMS"), if (mode == 1) 1f else 0.55f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gridVbo)
        GLES20.glEnableVertexAttribArray(0); GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0)
        if (mode == 1) {
            val rb = depthRbs.getOrPut(t.fbo) {
                val r = IntArray(1); GLES20.glGenRenderbuffers(1, r, 0)
                GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, r[0]); GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, t.w, t.h)
                GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, r[0]); r[0]
            }
            if (rb != 0) { GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT); GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glDepthFunc(GLES20.GL_LESS) }
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, triIbo)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, triCount, GLES20.GL_UNSIGNED_SHORT, 0)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        } else {
            GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
            if (mode == 2) { GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, lineIbo); GLES20.glDrawElements(GLES20.GL_LINES, lineCount, GLES20.GL_UNSIGNED_SHORT, 0) }
            else GLES20.glDrawArrays(GLES20.GL_POINTS, 0, GX * GY)
        }
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0); GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /**
     * The fractal flies from its start point to its lead point and back. Zoom moves in doublings, so the dive feels steady,
     * and the position follows the zoom's scale, so the lead point stays centred as it grows.
     */
    private fun fractalUniforms(p: Prog, l: Layer, ph: Phase) {
        GLES20.glUseProgram(p.id)
        val s = Fractal.SETS[l["fset"].toInt().coerceIn(0, Fractal.SETS.lastIndex)]
        GLES20.glUniform4f(p.loc("SETF"), s.formula.toFloat(), s.power.toFloat(), if (s.julia) 1f else 0f, 0f)
        GLES20.glUniform2f(p.loc("JC"), s.cx, s.cy)
        val trip = l["trip"].coerceAtLeast(1f); val hold = l["fhold"].coerceIn(0f, 0.8f)
        val u = (ph.t / trip) % 2f
        var k = if (u < 1f) u else 2f - u
        k = ((k - hold / 2f) / (1f - hold)).coerceIn(0f, 1f); k = k * k * (3f - 2f * k)
        val z0 = l["f0z"]; val z1 = l["f1z"]; val zoom = z0 + (z1 - z0) * k
        val s0 = Math.pow(2.0, -z0.toDouble()); val s1 = Math.pow(2.0, -z1.toDouble()); val sc = Math.pow(2.0, -zoom.toDouble())
        val w = if (kotlin.math.abs(s0 - s1) > 1e-12) ((s0 - sc) / (s0 - s1)).toFloat() else k
        GLES20.glUniform3f(p.loc("CAM"), l["f0x"] + (l["f1x"] - l["f0x"]) * w, l["f0y"] + (l["f1y"] - l["f0y"]) * w, zoom)
        val map = Fractal.map(l["pal"].toInt())
        for (i in 0 until 5) { val c = map.stops.getOrElse(i) { 0 }; palBuf[i * 3] = ((c shr 16) and 255) / 255f; palBuf[i * 3 + 1] = ((c shr 8) and 255) / 255f; palBuf[i * 3 + 2] = (c and 255) / 255f }
        GLES20.glUniform3fv(p.loc("PAL"), 5, palBuf, 0)
        GLES20.glUniform2f(p.loc("CB"), l["cdens"], l["ccycle"])
    }

    private fun drawLines(l: Layer, ph: Phase, hue: Float, glow: Float, size: Float, detail: Float, t: Target) {
        val p = program("line", LINE_VS, LINE_FS) ?: return
        val dim = if (Solids.isHyper(l.type)) Solids.dimOf(l.type, l["dim"]) else 0
        val m = meshes.getOrPut(if (dim > 0) l.type + dim else l.type) { uploadRibbons(Geometry.of(l.type, dim)) }
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        GLES20.glUseProgram(p.id)
        val solid = l.kind == GenKind.Solid
        val ax = Math.toRadians(l["tiltX"].toDouble()).toFloat() + if (solid) ph.spin * 0.61f else 0f
        val ay = Math.toRadians(l["tiltY"].toDouble()).toFloat() + if (solid) ph.spin else 0f
        val az = (if (solid) ph.spin * 0.23f else ph.spin) + angleRad(l)
        GLES20.glUniformMatrix3fv(p.loc("ROTM"), 1, false, rotation(ax, ay, az), 0)
        GLES20.glUniform1f(p.loc("SCL"), size); GLES20.glUniform1f(p.loc("ASP"), t.w.toFloat() / t.h)
        GLES20.glUniform3f(p.loc("STR"), l["stx"], l["sty"], l["stz"])
        GLES20.glUniform1f(p.loc("WID"), 0.006f + detail * 0.045f); GLES20.glUniform2f(p.loc("OFF"), l["x"], l["y"])
        GLES20.glUniform1f(p.loc("PERSP"), if (solid) 0.35f else 0f)
        val c = hsv(hue, (0.75f * l["sat"]).coerceIn(0f, 1f))
        GLES20.glUniform3f(p.loc("COL"), c[0], c[1], c[2]); GLES20.glUniform1f(p.loc("GLOW"), glow)
        val stride = 8 * 4
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, m.vbo)
        GLES20.glEnableVertexAttribArray(0); GLES20.glEnableVertexAttribArray(1); GLES20.glEnableVertexAttribArray(2)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glVertexAttribPointer(1, 3, GLES20.GL_FLOAT, false, stride, 12)
        GLES20.glVertexAttribPointer(2, 2, GLES20.GL_FLOAT, false, stride, 24)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, m.verts)
        GLES20.glDisableVertexAttribArray(1); GLES20.glDisableVertexAttribArray(2)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /** [into]: refill this buffer instead of making a new one (the tesseract changes shape every frame). */
    private fun uploadRibbons(segs: FloatArray, into: Int = 0): Mesh {
        // segs = x0 y0 z0 x1 y1 z1 per segment; each becomes a quad whose width is applied on screen by the vertex shader
        val n = segs.size / 6
        val fb = ByteBuffer.allocateDirect(n * 6 * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val corners = floatArrayOf(0f, -1f, 1f, -1f, 1f, 1f, 0f, -1f, 1f, 1f, 0f, 1f)
        for (i in 0 until n) for (c in 0 until 6) {
            fb.put(segs, i * 6, 6); fb.put(corners[c * 2]); fb.put(corners[c * 2 + 1])
        }
        fb.position(0)
        val b = IntArray(1); if (into != 0) b[0] = into else GLES20.glGenBuffers(1, b, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, b[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, n * 6 * 8 * 4, fb, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        return Mesh(b[0], n * 6)
    }

    private fun drawPoints(l: Layer, ph: Phase, hue: Float, glow: Float, size: Float, detail: Float, t: Target) {
        val p = program("pts", POINT_VS, POINT_FS) ?: return
        val max = when (l.type) { "Orbs" -> 14; "Dust" -> 500; else -> 1400 }
        val m = meshes.getOrPut("pts:" + l.type) {
            val r = java.util.Random(l.type.hashCode().toLong())
            val fb = ByteBuffer.allocateDirect(max * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            repeat(max) { fb.put(r.nextFloat()); fb.put(r.nextFloat()); fb.put(r.nextFloat()); fb.put(r.nextFloat()) }
            fb.position(0)
            val b = IntArray(1); GLES20.glGenBuffers(1, b, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, b[0]); GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, max * 16, fb, GLES20.GL_STATIC_DRAW)
            Mesh(b[0], max)
        }
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        GLES20.glUseProgram(p.id)
        GLES20.glUniform1f(p.loc("T"), ph.t % 100000f); GLES20.glUniform1f(p.loc("SCL"), size); GLES20.glUniform1f(p.loc("ASP"), t.w.toFloat() / t.h)
        GLES20.glUniform1f(p.loc("MODE"), when (l.type) { "Orbs" -> 1f; "Dust" -> 2f; else -> 0f })
        GLES20.glUniform1f(p.loc("H"), t.h.toFloat()); GLES20.glUniform1f(p.loc("GLOW"), glow)
        GLES20.glUniform2f(p.loc("OFF"), l["x"], l["y"])
        val c = hsv(hue + if (l.type == "Starfield") 0.6f else 0f, (0.7f * l["sat"] * if (l.type == "Starfield") 0.3f else 1f).coerceIn(0f, 1f))
        GLES20.glUniform3f(p.loc("COL"), c[0], c[1], c[2])
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, m.vbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, (max * (0.1f + 0.9f * detail)).toInt().coerceAtLeast(1))
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /* ---------- media (image or video file as a node) ---------- */

    private fun releaseMedia(id: Int) {
        val mt = media.remove(id) ?: return
        val pl = mt.player; val s = mt.surface; val st = mt.surfaceTexture
        main.post { runCatching { pl?.release() }; runCatching { s?.release() }; runCatching { st?.release() } }
        GLES20.glDeleteTextures(1, intArrayOf(mt.tex), 0)
    }
    private fun releaseMedia() { media.keys.toList().forEach { releaseMedia(it) } }
    /** Called when the view goes away, with no GL context guaranteed: players must still be stopped. */
    fun stopPlayers() { canvases.values.forEach { it.stop() }; media.values.forEach { mt -> val pl = mt.player; main.post { runCatching { pl?.release() } }; mt.player = null } }

    private fun openMedia(l: Layer): MediaTex? {
        media[l.id]?.let { if (it.path == l.media) return it else releaseMedia(l.id) }
        val f = File(l.media)
        if (l.media.isEmpty() || !f.exists()) return null
        val isVideo = f.extension.lowercase() in setOf("mp4", "webm", "mkv", "mov")
        val tex = IntArray(1); GLES20.glGenTextures(1, tex, 0)
        if (isVideo) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]); clampLinear(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            val mt = MediaTex(tex[0], true, l.media)
            val st = SurfaceTexture(tex[0]); val s = Surface(st)
            mt.surfaceTexture = st; mt.surface = s
            main.post {
                mt.player = ExoPlayer.Builder(ctx).build().apply {
                    addListener(object : Player.Listener {
                        override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                            if (size.height > 0) mt.aspect = size.width * size.pixelWidthHeightRatio / size.height
                        }
                    })
                    setVideoSurface(s); setMediaItem(MediaItem.fromUri(Uri.fromFile(f))); repeatMode = Player.REPEAT_MODE_ALL; volume = 0f; prepare(); playWhenReady = true
                }
            }
            media[l.id] = mt; return mt
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(l.media, opts)
        var sample = 1; while (maxOf(opts.outWidth, opts.outHeight) / sample > 1024) sample *= 2
        val raw = BitmapFactory.decodeFile(l.media, BitmapFactory.Options().apply { inSampleSize = sample }) ?: run { GLES20.glDeleteTextures(1, tex, 0); return null }
        // phones store many photos sideways with a "turn me" tag (EXIF): the decoder ignores it, so the picture arrived
        // rotated and needed about 90° of Rotate Z. It is turned upright here, so every image starts the way it was taken
        val bmp = runCatching {
            val o = android.media.ExifInterface(l.media).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
            val m = android.graphics.Matrix()
            when (o) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                android.media.ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                android.media.ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            }
            if (m.isIdentity) raw else android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
        }.getOrDefault(raw)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]); clampLinear(GLES20.GL_TEXTURE_2D)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1); bmp.recycle()
        return MediaTex(tex[0], false, l.media, aspect).also { media[l.id] = it }
    }

    private fun drawMedia(l: Layer, ph: Phase, hue: Float, glow: Float, size: Float, t: Target) {
        val mt = openMedia(l) ?: return
        val p = (if (mt.external) program("mediaOES", FULL_VS, MEDIA_OES_FS) else program("media2D", FULL_VS, MEDIA_2D_FS)) ?: return
        if (mt.external) runCatching { mt.surfaceTexture?.updateTexImage() }
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(p.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(if (mt.external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, mt.tex)
        GLES20.glUniform1i(p.loc("TEX"), 0)
        GLES20.glUniform1f(p.loc("HUE"), hue); GLES20.glUniform1f(p.loc("SAT"), l["sat"]); GLES20.glUniform1f(p.loc("GLOW"), glow)
        GLES20.glUniform1f(p.loc("ZOOM"), size.coerceAtLeast(0.01f)); GLES20.glUniform1f(p.loc("ROT"), ph.spin + angleRad(l)); GLES20.glUniform2f(p.loc("OFF"), l["x"], l["y"])
        GLES20.glUniform1f(p.loc("ASP"), t.w.toFloat() / t.h); GLES20.glUniform1f(p.loc("MA"), mt.aspect); GLES20.glUniform1f(p.loc("FIT"), l["fit"]); GLES20.glUniform1f(p.loc("PERSP"), l["persp"])
        val tx = Math.toRadians(l["tiltX"].toDouble()).toFloat(); val ty = Math.toRadians(l["tiltY"].toDouble()).toFloat()
        GLES20.glUniform4f(p.loc("TILT"), sin(tx), sin(ty), cos(tx), cos(ty))
        val pv = PIVOT_AT[l["pivot"].toInt().coerceIn(0, PIVOT_AT.lastIndex)]
        GLES20.glUniform2f(p.loc("PIV"), pv[0], pv[1])
        fullscreen()
    }

    private fun angleRad(l: Layer) = Math.toRadians(l["angle"].toDouble()).toFloat()

    /** The layer's shape mask, for the composite and panel shaders. */
    private fun maskUniforms(p: Prog, l: Layer) {
        GLES20.glUniform1f(p.loc("MSHAPE"), l["mshape"]); GLES20.glUniform1f(p.loc("MTHR"), l["mthr"]); GLES20.glUniform1f(p.loc("MSOFT"), l["msoft"])
        GLES20.glUniform1f(p.loc("MSCALE"), l["mscale"]); GLES20.glUniform1f(p.loc("MROT"), Math.toRadians(l["mrot"].toDouble()).toFloat()); GLES20.glUniform1f(p.loc("MINV"), l["minv"])
    }

    /* ---------- composite ---------- */

    private fun composite(l: Layer, scene: BgScene, factor: Float = 1f) {
        val t = targets[l.id] ?: return
        val p = program("comp", FULL_VS, COMP_FS) ?: return
        GLES20.glUseProgram(p.id)
        val mask = scene.layers.firstOrNull { it.id == l["maskBy"].toInt() && it.on && it.id != l.id }?.let { targets[it.id] }
        val warp = scene.layers.firstOrNull { it.id == l["warpBy"].toInt() && it.on && it.id != l.id }?.let { targets[it.id] }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, warp?.tex ?: 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mask?.tex ?: 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t.tex)
        GLES20.glUniform1i(p.loc("TEX"), 0); GLES20.glUniform1i(p.loc("MASK"), 1); GLES20.glUniform1i(p.loc("WARP"), 2)
        val blend = l["blend"].toInt()
        GLES20.glEnable(GLES20.GL_BLEND)
        when (blend) {
            1 -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_COLOR)
            2 -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            3 -> GLES20.glBlendFunc(GLES20.GL_DST_COLOR, GLES20.GL_ZERO)
            else -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        }
        val wAmt = l["warpAmt"] * (1f + 2f * mod(l, 5))
        GLES20.glUniform1f(p.loc("OP"), (l["opacity"] * (1f + mod(l, 1))).coerceIn(0f, 1f) * factor)
        GLES20.glUniform1f(p.loc("FOLD"), l["fold"]); GLES20.glUniform1f(p.loc("MSK"), if (mask != null) l["maskAmt"] else 0f)
        // Warp reaction with no linked node ripples the texture by itself
        GLES20.glUniform1f(p.loc("WRP"), if (warp != null) wAmt else 0f)
        GLES20.glUniform1f(p.loc("RIP"), 0.02f * mod(l, 5)); GLES20.glUniform1f(p.loc("TM"), phases["${l.id}:${l.type}"]?.t ?: 0f)
        GLES20.glUniform1f(p.loc("FADE"), (l["fade"] + mod(l, 7) * 0.5f).coerceIn(0f, 1f)); GLES20.glUniform1f(p.loc("MUL"), if (blend == 3) 1f else 0f)
        GLES20.glUniform1f(p.loc("ASP"), viewW.toFloat() / viewH)
        // the layer as a plane in 3D: whatever a generator can't move, scale or turn by itself is done here, so every
        // kind of layer takes position X Y Z, scale X Y and rotation X Y Z the same way
        maskUniforms(p, l)
        val geo = l.kind == GenKind.Line || l.kind == GenKind.Solid
        val nativeTilt = geo || (l.kind == GenKind.Media && l["persp"] > 0.5f) || (l.type == FRACTAL && l["f3d"] > 0.5f)
        val rx = if (nativeTilt) 0f else Math.toRadians(l["tiltX"].toDouble()).toFloat()
        val ry = if (nativeTilt) 0f else Math.toRadians(l["tiltY"].toDouble()).toFloat()
        val rz = if (l.kind == GenKind.Points) angleRad(l) + (phases["${l.id}:${l.type}"]?.spin ?: 0f) else 0f
        val ox = if (l.kind == GenKind.Canvas) l["x"] else 0f; val oy = if (l.kind == GenKind.Canvas) l["y"] else 0f
        val sx = if (geo) 1f else l["stx"]; val sy = if (geo) 1f else l["sty"]
        val depth = Math.pow(2.0, l["z"].toDouble().coerceIn(-12.0, 30.0)).toFloat()
        val plane = rx != 0f || ry != 0f || rz != 0f || ox != 0f || oy != 0f || sx != 1f || sy != 1f || depth != 1f
        GLES20.glUniform1f(p.loc("PLANE"), if (plane) 1f else 0f)
        if (plane) {
            GLES20.glUniformMatrix3fv(p.loc("PM"), 1, false, rotation(rx, ry, rz), 0)
            GLES20.glUniform2f(p.loc("POFF"), ox, oy); GLES20.glUniform2f(p.loc("PSTR"), sx.coerceAtLeast(0.01f), sy.coerceAtLeast(0.01f)); GLES20.glUniform1f(p.loc("PZ"), depth)
        }
        fullscreen()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /* ---------- helpers ---------- */

    private fun fullscreen() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, tri)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
    }

    private fun asset(name: String): String = runCatching { ctx.assets.open("bg2/$name.frag").bufferedReader().use { it.readText() } }.getOrDefault("void main(){gl_FragColor=vec4(0.0,0.0,0.0,1.0);}")

    private fun program(key: String, vs: String, fs: String): Prog? {
        if (progs.containsKey(key)) return progs[key]
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) { Log.e("StarshineBg", "shader '$key' failed: " + GLES20.glGetShaderInfoLog(s)); GLES20.glDeleteShader(s); return 0 }
            return s
        }
        val v = compile(GLES20.GL_VERTEX_SHADER, vs); val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        if (v == 0 || f == 0) { progs[key] = null; return null }   // a broken node is skipped; the rest of the scene still draws
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, v); GLES20.glAttachShader(id, f)
        GLES20.glBindAttribLocation(id, 0, "P"); GLES20.glBindAttribLocation(id, 1, "B"); GLES20.glBindAttribLocation(id, 2, "ST")
        GLES20.glLinkProgram(id)
        val ok = IntArray(1); GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { Log.e("StarshineBg", "program '$key' failed: " + GLES20.glGetProgramInfoLog(id)); progs[key] = null; return null }
        return Prog(id).also { progs[key] = it }
    }

    private fun hsv(h: Float, s: Float): FloatArray {
        val c = FloatArray(3); val hh = ((h % 1f) + 1f) % 1f * 6f
        for (i in 0..2) {
            val k = (hh + floatArrayOf(0f, 4f, 2f)[i]) % 6f
            val x = (kotlin.math.abs(k - 3f) - 1f).coerceIn(0f, 1f)
            c[i] = 1f + s * (x - 1f)
        }
        return c
    }

    private fun rotation(ax: Float, ay: Float, az: Float): FloatArray {
        val cx = cos(ax); val sx = sin(ax); val cy = cos(ay); val sy = sin(ay); val cz = cos(az); val sz = sin(az)
        // column-major Rz * Rx * Ry
        return floatArrayOf(
            cz * cy - sz * sx * sy, sz * cy + cz * sx * sy, -cx * sy,
            -sz * cx, cz * cx, sx,
            cz * sy + sz * sx * cy, sz * sy - cz * sx * cy, cx * cy
        )
    }

    companion object {
        private val CANVAS_LOCK = Any()   // the classic scenes share per-frame settings, so two layers mustn't draw at the same moment
        val FIELD_FILES = mapOf("Clouds" to "clouds", "Fire" to "fire", "Water" to "water", "Nebula" to "nebula", "Iris" to "iris",
            "Sun flare" to "flare", "Candle" to "candle", "Globe" to "globe", "Honeycomb" to "honeycomb", "Aether" to "aether", "Light rays" to "lightrays")

        private const val FIELD_EXT = """
uniform float ROT; uniform vec2 OFF;
vec2 uvr() { vec2 p = uvc() - OFF; float c = cos(ROT); float s = sin(ROT); return vec2(c * p.x - s * p.y, s * p.x + c * p.y); }
"""
        private const val FULL_VS = "attribute vec2 P; varying vec2 v; void main(){ v = P * 0.5 + 0.5; gl_Position = vec4(P, 0.0, 1.0); }"
        private const val SOLID_VS = "attribute vec2 P; void main(){ gl_Position = vec4(P, 0.0, 1.0); }"
        private const val SOLID_FS = "precision mediump float; uniform float A; void main(){ gl_FragColor = vec4(0.0, 0.0, 0.0, A); }"

        private const val LINE_VS = """
attribute vec3 P; attribute vec3 B; attribute vec2 ST;
uniform mat3 ROTM; uniform float SCL; uniform float ASP; uniform float WID; uniform float PERSP; uniform vec2 OFF; uniform vec3 STR;
varying float vD; varying float vZ;
vec3 proj(vec3 q) { vec3 r = ROTM * (q * STR); float w = 1.0 - r.z * PERSP; return vec3(r.xy * SCL / w, r.z); }
void main() {
  vec3 a = proj(P); vec3 b = proj(B);
  vec2 d = b.xy - a.xy; float len = length(d); d = len > 0.00001 ? d / len : vec2(1.0, 0.0);
  vec2 n = vec2(-d.y, d.x);
  vec2 pos = mix(a.xy, b.xy, ST.x) + n * ST.y * WID + OFF;
  vD = ST.y; vZ = mix(a.z, b.z, ST.x);
  gl_Position = vec4(pos.x / ASP, pos.y, 0.0, 1.0);
}"""
        private const val LINE_FS = """
precision mediump float; uniform vec3 COL; uniform float GLOW; varying float vD; varying float vZ;
void main() {
  float d = abs(vD);
  float halo = exp(-d * d * 5.0) * 0.42;
  float core = smoothstep(0.17, 0.04, d);
  float depth = clamp(0.62 + vZ * 0.5, 0.18, 1.0);
  vec3 c = (COL * halo * GLOW + mix(COL, vec3(1.0), 0.6) * core * min(GLOW, 1.6) * 1.15) * depth;
  gl_FragColor = vec4(c, 1.0);
}"""
        private const val POINT_VS = """
attribute vec4 P; uniform float T; uniform float SCL; uniform float ASP; uniform float MODE; uniform float H; uniform vec2 OFF;
varying float vA;
void main() {
  vec2 pos; float size;
  if (MODE < 0.5) {
    float z = fract(P.z - T * 0.05) * 0.98 + 0.02;
    pos = (P.xy * 2.0 - 1.0) * 0.55 / z;
    vA = (1.0 - z) * (0.55 + 0.45 * sin(T * (0.8 + P.w * 2.0) + P.w * 30.0));
    size = (1.0 + (1.0 - z) * 5.0) * SCL * (H / 540.0);
  } else if (MODE < 1.5) {
    pos = (P.xy * 2.0 - 1.0) * vec2(ASP, 1.0) * 0.8 + vec2(sin(T * 0.09 + P.z * 40.0), cos(T * 0.07 + P.w * 40.0)) * 0.12;
    vA = 0.4 + 0.35 * sin(T * 0.6 * (0.4 + P.w) + P.z * 20.0);
    size = (70.0 + 170.0 * P.z) * SCL * (H / 540.0);
  } else {
    pos = (fract(P.xy + vec2(T * 0.006 * (P.z - 0.5), T * 0.01 * (P.w - 0.5))) * 2.0 - 1.0) * vec2(ASP, 1.0);
    vA = 0.2 + 0.8 * pow(0.5 + 0.5 * sin(T * (0.4 + P.w) + P.z * 40.0), 3.0);
    size = (1.5 + 3.0 * P.w) * SCL * (H / 540.0);
  }
  pos += OFF;
  gl_Position = vec4(pos.x / ASP, pos.y, 0.0, 1.0);
  gl_PointSize = size;
}"""
        private const val POINT_FS = """
precision mediump float; uniform vec3 COL; uniform float GLOW; varying float vA;
void main() {
  vec2 q = gl_PointCoord * 2.0 - 1.0; float d = dot(q, q);
  float g = exp(-d * 3.8) - 0.022; if (g <= 0.0) discard;
  gl_FragColor = vec4(COL * g * vA * GLOW, 1.0);
}"""
        /** Pivot points on the picture (x right, y up, ±0.5 = its edges), in the order of PIVOTS in Bg2Ui. */
        private val PIVOT_AT = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(-0.5f, 0.5f), floatArrayOf(0f, 0.5f), floatArrayOf(0.5f, 0.5f),
            floatArrayOf(0.5f, 0f), floatArrayOf(0.5f, -0.5f), floatArrayOf(0f, -0.5f), floatArrayOf(-0.5f, -0.5f), floatArrayOf(-0.5f, 0f))
        private const val MEDIA_COMMON = """
varying vec2 v; uniform float HUE; uniform float SAT; uniform float GLOW; uniform float ZOOM; uniform float ROT; uniform vec2 OFF;
uniform float ASP; uniform float MA; uniform float FIT; uniform float PERSP; uniform vec4 TILT; uniform vec2 PIV;
vec3 hueSat(vec3 c) {
  float a = HUE * 6.28318; float cs = cos(a); float sn = sin(a);
  vec3 k = vec3(0.57735);
  vec3 r = c * cs + cross(k, c) * sn + k * dot(k, c) * (1.0 - cs);
  float l = dot(r, vec3(0.299, 0.587, 0.114));
  return mix(vec3(l), r, SAT);
}
// screen point -> picture point, in units of screen height so that rotating never skews the picture
vec2 place() {
  vec2 p = (v - 0.5 - OFF * 0.5) * vec2(ASP, 1.0);
  if (PERSP > 0.5) {
    float w = 1.0 - (p.x * TILT.y + p.y * TILT.x) * 1.1;
    p = p / max(w, 0.05) / vec2(max(TILT.w, 0.2), max(TILT.z, 0.2));
  }
  bool wide = MA > ASP;
  vec2 box = (FIT < 0.5) == wide ? vec2(MA, 1.0) : vec2(ASP, ASP / MA);
  // turn and zoom around the pivot (a point of the picture: its centre, an edge or a corner), which stays where it is
  vec2 pv = PIV * box;
  float c = cos(ROT); float s = sin(ROT);
  p -= pv;
  p = vec2(c * p.x - s * p.y, s * p.x + c * p.y) / ZOOM + pv;
  return p / box + 0.5;
}
float inside(vec2 q) { return step(0.0, q.x) * step(q.x, 1.0) * step(0.0, q.y) * step(q.y, 1.0); }
"""
        private const val MEDIA_2D_FS = "precision mediump float; uniform sampler2D TEX;" + MEDIA_COMMON +
            "void main(){ vec2 q = place(); float m = inside(q); q.y = 1.0 - q.y; gl_FragColor = vec4(hueSat(texture2D(TEX, q).rgb) * GLOW * m, 1.0); }"
        private const val MEDIA_OES_FS = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES TEX;" + MEDIA_COMMON +
            "void main(){ vec2 q = place(); float m = inside(q); q.y = 1.0 - q.y; gl_FragColor = vec4(hueSat(texture2D(TEX, q).rgb) * GLOW * m, 1.0); }"

        private const val TRANS_FS = """
precision mediump float;
varying vec2 v; uniform sampler2D A; uniform sampler2D B; uniform sampler2D M;
uniform float T; uniform float MODE; uniform float LEAD; uniform float ASP;
float lum(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
void main() {
  vec3 a = texture2D(A, v).rgb; vec3 b = texture2D(B, v).rgb; vec3 c;
  vec2 p = (v - 0.5) * vec2(ASP, 1.0);
  float e = 0.18;
  float k = T;
  if (MODE > 0.5 && MODE < 1.5) {            // dip to black
    c = T < 0.5 ? a * (1.0 - 2.0 * T) : b * (2.0 * T - 1.0);
    gl_FragColor = vec4(c, 1.0); return;
  }
  if (LEAD > 0.5 || (MODE > 1.5 && MODE < 2.5)) {   // luma: bright parts arrive first, from the leading layer if one is chosen
    float l = lum(texture2D(M, v).rgb);
    k = smoothstep(1.0 - l - 0.3, 1.0 - l + 0.3, T * 1.6 - 0.3);
  } else if (MODE > 2.5 && MODE < 3.5) {     // wipe
    k = smoothstep(v.x - e, v.x + e, T * (1.0 + 2.0 * e) - e);
  } else if (MODE > 3.5 && MODE < 4.5) {     // iris
    float r = length(p) / 1.05;
    k = smoothstep(r - e, r + e, T * (1.0 + 2.0 * e) - e);
  } else if (MODE > 4.5) {                   // spiral
    float r = length(p) / 1.05; float ang = fract(atan(p.y, p.x) / 6.28318 + 0.5 + r);
    float d = r * 0.5 + ang * 0.5;
    k = smoothstep(d - e, d + e, T * (1.0 + 2.0 * e) - e);
  }
  gl_FragColor = vec4(mix(a, b, k), 1.0);
}"""

        /**
         * The 20 shape masks. Each gives a value for a point of the layer (its middle at 0, half its height = 0.5): above 0 is
         * inside the shape, rising toward its centre; the gradients run 0..1. The threshold cuts at a value, so moving it
         * grows or shrinks the shape, or wipes a gradient across.
         */
        private const val MASK_FN = """
uniform float MSHAPE; uniform float MTHR; uniform float MSOFT; uniform float MSCALE; uniform float MROT; uniform float MINV;
float mhash(vec2 q) { return fract(sin(dot(q, vec2(127.1, 311.7))) * 43758.5453); }
float mnoise(vec2 q) { vec2 i = floor(q); vec2 f = fract(q); f = f * f * (3.0 - 2.0 * f);
  return mix(mix(mhash(i), mhash(i + vec2(1.0, 0.0)), f.x), mix(mhash(i + vec2(0.0, 1.0)), mhash(i + vec2(1.0, 1.0)), f.x), f.y); }
float shapeValue(vec2 p, int s) {
  float r = length(p); float a = atan(p.y, p.x); vec2 q = abs(p);
  if (s == 1) return 1.0 - r / 0.45;
  if (s == 2) return 1.0 - abs(r - 0.3) / 0.12;
  if (s == 3) return 1.0 - max(q.x, q.y) / 0.42;
  if (s == 4) return 1.0 - (q.x + q.y) / 0.55;
  if (s == 5) return 1.0 - max(q.x * 0.866 + q.y * 0.5, q.y) / 0.42;
  if (s == 6) return 1.0 - max(q.x * 0.866 + p.y * 0.5, -p.y) / 0.25;
  if (s == 7) return 1.0 - r / (0.26 + 0.16 * cos(5.0 * a + 1.5708));
  if (s == 8) return 1.0 - r / (0.22 + 0.2 * abs(cos(3.0 * a)));
  if (s == 9) return 1.0 - max(max(q.x, q.y), (q.x + q.y) * 0.7071) / 0.42;
  if (s == 10) return min(1.0 - r / 0.45, length(p - vec2(0.18, 0.1)) / 0.4 - 1.0);
  if (s == 11) return p.x + 0.5;
  if (s == 12) return p.y + 0.5;
  if (s == 13) return 1.0 - r * 1.4;
  if (s == 14) return 0.5 + 0.5 * sin(p.x * 30.0);
  if (s == 15) return 0.5 + 0.5 * sin(p.x * 22.0) * sin(p.y * 22.0);
  if (s == 16) return fract(a / 6.28318 + r * 3.0);
  if (s == 17) return 1.0 - abs(p.y - 0.08 * sin(p.x * 12.0)) / 0.22;
  if (s == 18) return mnoise(p * 4.0) * 1.4 - 0.4;
  if (s == 19) return 1.0 - max(min(q.x, q.y) / 0.11, max(q.x, q.y) / 0.45);
  if (s == 20) return min(1.0 - length(p - vec2(0.0, -0.22)) / 0.48, 1.0 - length(p - vec2(0.0, 0.22)) / 0.48);
  return 1.0;
}
float shapeAlpha(vec2 p) {
  if (MSHAPE < 0.5) return 1.0;
  float cr = cos(MROT); float sr = sin(MROT);
  vec2 m = vec2(cr * p.x + sr * p.y, -sr * p.x + cr * p.y) / max(MSCALE, 0.05);
  float k = smoothstep(MTHR - MSOFT - 0.0005, MTHR + MSOFT + 0.0005, shapeValue(m, int(MSHAPE + 0.5)));
  return MINV > 0.5 ? 1.0 - k : k;
}
"""
        /* ---------- 3D scene shaders ---------- */
        private const val P3_VS = """
attribute vec4 P; uniform mat4 MVP; uniform float T; uniform float SZ; uniform float PX; uniform float MODE; uniform float TW;
varying float vA; varying float vS;
void main() {
  vec3 p = P.xyz;
  if (MODE > 1.5 && MODE < 2.5) p = fract(p + vec3(0.011, 0.006, 0.009) * T * (0.4 + P.w)) * 2.0 - 1.0;
  // elements and weather: each moves its particles its own way (af = brightness, sf = size)
  float af = 1.0; float sf = 1.0; float w = P.w;
  if (MODE > 5.5 && MODE < 6.5) { p.y = 0.12 * (sin(p.x * 5.0 + T * 1.3) + sin(p.z * 4.0 - T * 0.9) * 0.7 + sin((p.x + p.z) * 9.0 + T * 2.1) * 0.3); af = 0.6 + p.y * 3.0; }
  else if (MODE > 6.5 && MODE < 7.5) { float x = fract(p.x + T * 0.08 * (0.5 + w)); p = vec3(x * 2.0 - 1.0, (p.y * 2.0 - 1.0) * 0.6 + sin(x * 9.0 + T + w * 6.0) * 0.15, p.z * 2.0 - 1.0); af = sin(x * 3.14159); }
  else if (MODE > 7.5 && MODE < 8.5) { float k = fract(p.y + T * 0.07 * (0.5 + w)); p = vec3((p.x * 2.0 - 1.0) * 0.5 * (1.0 - k * 0.6) + sin(T * 1.3 + w * 20.0) * 0.06 * k, k * 2.0 - 1.0, (p.z * 2.0 - 1.0) * 0.5 * (1.0 - k * 0.6)); af = (1.0 - k) * min(k * 8.0, 1.0); }
  else if (MODE > 8.5 && MODE < 9.5) { p = vec3(p.x * 2.0 - 1.0, 1.0 - fract(p.y + T * 0.7 * (0.7 + w)) * 2.0, p.z * 2.0 - 1.0); }
  else if (MODE > 9.5 && MODE < 10.5) { float k = fract(p.y + T * 0.05 * (0.5 + w)); p = vec3(p.x * 2.0 - 1.0 + sin(T * 0.6 + w * 30.0) * 0.06, 1.0 - k * 2.0, p.z * 2.0 - 1.0 + cos(T * 0.5 + w * 20.0) * 0.06); }
  else if (MODE > 10.5 && MODE < 11.5) { p = (p * 2.0 - 1.0) * 0.8 + vec3(sin(T * 0.3 + w * 40.0), sin(T * 0.23 + w * 17.0), cos(T * 0.27 + w * 29.0)) * 0.2; af = pow(0.5 + 0.5 * sin(T * (1.0 + w * 2.0) + w * 60.0), 4.0); }
  else if (MODE > 11.5 && MODE < 12.5) { float k = fract(w + T * 0.04); float sp = 0.1 + k * 0.7; p = vec3((p.x * 2.0 - 1.0) * sp + sin(T * 0.2 + w * 9.0) * 0.1 * k, k * 2.0 - 1.0, (p.z * 2.0 - 1.0) * sp); sf = 0.4 + k * 2.0; af = k * (1.0 - k) * 4.0; }
  else if (MODE > 12.5) { float k = fract(w * 7.0 + T * 0.25); float a = p.x * 6.2832; float r = 0.15 + p.z * 0.35; p = vec3(cos(a) * r * k * 2.0, -1.0 + 6.0 * k * (1.0 - k) * (0.6 + p.y * 0.4), sin(a) * r * k * 2.0); af = 1.0 - k * 0.7; }
  vec4 c = MVP * vec4(p, 1.0);
  gl_Position = c;
  float s = SZ * (0.5 + P.w) * PX / max(c.w, 0.05) * sf;
  gl_PointSize = clamp(s, 1.0, 256.0);
  float tw = MODE < 0.5 ? 1.0 - TW * 0.4 * (0.5 - 0.5 * sin(T * (0.5 + P.w * 2.0) + P.w * 50.0)) : (MODE > 4.5 ? 1.0 : 1.0 - TW * 0.25 * (0.5 - 0.5 * sin(T * 0.7 + P.w * 30.0)));
  vA = tw * af * clamp(s, 0.0, 1.0) * step(0.0, c.w);
  vS = P.w;
}"""
        private const val P3_FS = """
precision mediump float; varying float vA; varying float vS;
uniform float HUE; uniform float SAT; uniform float HV; uniform float GLOW; uniform float OP; uniform float SOFT;
vec3 hsv(float h, float s) { vec3 k = clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0); return mix(vec3(1.0), k, s); }
void main() {
  vec2 q = gl_PointCoord * 2.0 - 1.0; float d = dot(q, q); if (d > 1.0) discard;
  float g = SOFT > 0.5 ? exp(-d * 3.0) * (1.0 - d) : exp(-d * 4.5);
  gl_FragColor = vec4(hsv(HUE + (vS - 0.5) * HV, SAT) * g * vA * GLOW * OP, 1.0);
}"""
        private const val BILL_VS = """
attribute vec2 P; uniform mat4 VP; uniform vec3 C; uniform vec3 RIGHT; uniform vec3 UP; uniform float R; uniform float QS;
varying vec2 q;
void main() { q = P * QS; gl_Position = VP * vec4(C + (RIGHT * P.x + UP * P.y) * R, 1.0); }"""
        private const val SUN_FS = """
precision mediump float; varying vec2 q; uniform vec3 COL; uniform float T; uniform float GLOW; uniform float RAYS; uniform float OP;
uniform float CORONA; uniform float RAYLEN; uniform float ROT; uniform highp float QS;   // highp: it is shared with the vertex shader, and Mali refuses to link a precision mismatch
void main() {
  float r = length(q); float a = atan(q.y, q.x);
  float core = smoothstep(0.2, 0.17, r);
  float k = 0.035 * CORONA; float glow = k / (r * r + k) * smoothstep(QS, 0.55 * QS, r);
  float rays = pow(abs(sin(a * RAYS * 0.5 + ROT)), 8.0) * 0.5 + pow(abs(sin(a * RAYS * 0.31 - ROT * 0.7)), 12.0) * 0.4;
  rays *= smoothstep(RAYLEN, 0.25 * RAYLEN, r) * smoothstep(0.12, 0.3, r) * step(0.5, RAYS);
  vec3 c = mix(COL, vec3(1.0), core * 0.9 + 0.3 * glow) * (core * 2.0 + glow + rays * 0.6) * GLOW * OP;
  gl_FragColor = vec4(c, 1.0);
}"""
        private const val PLANET_FS = """
precision mediump float; varying vec2 q; uniform vec3 COL; uniform vec3 COL2; uniform vec3 L; uniform float SPIN; uniform float OP; uniform float AMB;
uniform float BANDS; uniform float ATMO; uniform float GLOW;
void main() {
  float r2 = dot(q, q);
  vec3 l = normalize(L);
  if (r2 > 1.0) {   // a thin atmosphere around it, bright on the lit side
    float h = smoothstep(1.0 + 0.15 * ATMO, 1.0, sqrt(r2)) * min(ATMO, 1.5); if (h <= 0.0) discard;
    float lit = max(dot(normalize(vec3(q, 0.0)), l) * 0.5 + 0.5, 0.0);
    gl_FragColor = vec4(COL * 0.6 * h * lit * OP, h * 0.5 * lit * OP); return;
  }
  vec3 n = vec3(q, sqrt(1.0 - r2));
  float lon = atan(n.x, n.z) + SPIN; float lat = n.y;
  float bands = sin(lat * BANDS + sin(lon * 3.0) * 0.6) * 0.5 + 0.5;
  float spots = sin(lon * 7.0 + lat * 5.0) * sin(lat * 13.0 - lon * 2.0) * 0.5 + 0.5;
  vec3 surf = mix(COL, COL2, bands * 0.7 + spots * 0.3);
  float d = max(dot(n, l), 0.0);
  float rim = pow(1.0 - n.z, 3.0);
  gl_FragColor = vec4((surf * (AMB + d) + COL * rim * 0.5 * ATMO * (0.2 + d)) * GLOW * OP, OP);
}"""
        private const val LINE3_VS = """
attribute vec3 P; attribute vec3 B; attribute vec2 ST;
uniform mat4 MVP; uniform float WID; uniform float ASP;
varying float vD; varying float vZ;
void main() {
  vec4 a = MVP * vec4(P, 1.0); vec4 b = MVP * vec4(B, 1.0);
  if (a.w < 0.05 || b.w < 0.05) { gl_Position = vec4(0.0, 0.0, 2.0, 1.0); vD = 1.0; vZ = 0.0; return; }
  vec2 d = (b.xy / b.w - a.xy / a.w) * vec2(ASP, 1.0); float len = length(d); d = len > 0.00001 ? d / len : vec2(1.0, 0.0);
  vec2 n = vec2(-d.y, d.x) / vec2(ASP, 1.0);
  vec4 c = mix(a, b, ST.x);
  c.xy += n * ST.y * WID * c.w;
  vD = ST.y; vZ = clamp(6.0 / max(c.w, 0.1), 0.3, 1.0);
  gl_Position = c;
}"""
        private const val LINE3_FS = """
precision mediump float; uniform vec3 COL; uniform float GLOW; varying float vD; varying float vZ;
void main() {
  float d = abs(vD);
  vec3 c = (COL * exp(-d * d * 5.0) * 0.42 * GLOW + mix(COL, vec3(1.0), 0.6) * smoothstep(0.17, 0.04, d) * min(GLOW, 1.6) * 1.15) * vZ;
  gl_FragColor = vec4(c, 1.0);
}"""
        private const val PANEL_VS = """
attribute vec2 P; uniform mat4 MVP; uniform float AR; varying vec2 uv;
void main() { uv = P * 0.5 + 0.5; gl_Position = MVP * vec4(P.x * AR, P.y, 0.0, 1.0); }"""
        private const val PANEL_FS = "precision mediump float; " + MASK_FN + "varying vec2 uv; uniform sampler2D TEX; uniform float OP; uniform float PAR; uniform float FADE;" +
            // Edge fade, as on flat layers: a panel in a 3D scene (Clouds, Water...) dims toward its edges
            "void main(){ vec2 p = (uv - 0.5) * vec2(PAR, 1.0); float f = 1.0 - FADE * smoothstep(0.12, 0.62, length(p));" +
            " gl_FragColor = vec4(texture2D(TEX, uv).rgb * OP * f * shapeAlpha(p), 1.0); }"
        private const val FLARE3_FS = """
precision mediump float; varying vec2 v; uniform vec2 S; uniform float ASP; uniform float K; uniform vec3 COL;
void main() {
  vec2 p = (v - 0.5) * vec2(ASP, 1.0); vec2 s = S * 0.5 * vec2(ASP, 1.0); vec3 f = vec3(0.0);
  for (int i = 0; i < 6; i++) {
    vec2 g = s * (-0.25 - float(i) * 0.33); float gr = 0.018 + 0.02 * float(i);
    vec3 tint = 0.5 + 0.5 * cos(vec3(0.0, 2.0, 4.0) + float(i) * 1.3);
    f += tint * smoothstep(gr, gr * 0.4, length(p - g)) * 0.14;
  }
  // only the lens: ghosts and a streak. The sun's own glow is Glow and Corona, so a stronger flare no longer swells the sun
  float d = length(p - s); float rr = (d - 0.16) * 22.0; f += COL * exp(-rr * rr) * 0.12;
  f += COL * exp(-abs(p.y - s.y) * 70.0) * exp(-abs(p.x - s.x) * 1.2) * 0.3;
  gl_FragColor = vec4(f * K, 1.0);
}"""
        private const val BLIT_FS = "precision mediump float; varying vec2 v; uniform sampler2D TEX; void main(){ gl_FragColor = vec4(texture2D(TEX, v).rgb, 1.0); }"
        private const val ACC_FS = "precision mediump float; varying vec2 v; uniform sampler2D CUR; uniform sampler2D OLD; uniform float K;" +
            "void main(){ gl_FragColor = vec4(mix(texture2D(CUR, v).rgb, texture2D(OLD, v).rgb, K), 1.0); }"
        /** FXAA (simplified, after Timothy Lottes): edges found by luminance contrast are blended along their direction.
         *  K = strength. One pass, nine texture reads. */
        private const val FXAA_FS = """
precision mediump float; varying vec2 v; uniform sampler2D TEX; uniform vec2 PX; uniform float K;
float lum(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
void main() {
  vec3 m = texture2D(TEX, v).rgb;
  float lM = lum(m);
  float lNW = lum(texture2D(TEX, v + vec2(-1.0, -1.0) * PX).rgb), lNE = lum(texture2D(TEX, v + vec2(1.0, -1.0) * PX).rgb);
  float lSW = lum(texture2D(TEX, v + vec2(-1.0, 1.0) * PX).rgb), lSE = lum(texture2D(TEX, v + vec2(1.0, 1.0) * PX).rgb);
  float lMin = min(lM, min(min(lNW, lNE), min(lSW, lSE))), lMax = max(lM, max(max(lNW, lNE), max(lSW, lSE)));
  if (lMax - lMin < max(0.0312, lMax * 0.125)) { gl_FragColor = vec4(m, 1.0); return; }
  vec2 dir = vec2(-((lNW + lNE) - (lSW + lSE)), (lNW + lSW) - (lNE + lSE));
  float red = max((lNW + lNE + lSW + lSE) * 0.03125, 0.0078125);
  dir = clamp(dir / (min(abs(dir.x), abs(dir.y)) + red), -8.0, 8.0) * PX;
  vec3 a = 0.5 * (texture2D(TEX, v + dir * (1.0 / 3.0 - 0.5)).rgb + texture2D(TEX, v + dir * (2.0 / 3.0 - 0.5)).rgb);
  vec3 b = a * 0.5 + 0.25 * (texture2D(TEX, v - dir * 0.5).rgb + texture2D(TEX, v + dir * 0.5).rgb);
  float lB = lum(b);
  vec3 aa = (lB < lMin || lB > lMax) ? a : b;
  gl_FragColor = vec4(mix(m, aa, K), 1.0);
}"""
        private const val BLUR_FS = """
precision mediump float; varying vec2 v; uniform sampler2D TEX; uniform vec2 D;
void main() {
  vec3 c = texture2D(TEX, v).rgb * 0.227;
  c += (texture2D(TEX, v + D * 1.385).rgb + texture2D(TEX, v - D * 1.385).rgb) * 0.316;
  c += (texture2D(TEX, v + D * 3.231).rgb + texture2D(TEX, v - D * 3.231).rgb) * 0.070;
  gl_FragColor = vec4(c, 1.0);
}"""
        /** The camera: lens, light, then the grade and the film. Every effect at its neutral value leaves the picture as it was. */
        private const val POST_FS = """
precision highp float;
varying vec2 v; uniform sampler2D SRC; uniform sampler2D BLUR; uniform float ASP; uniform float T;
uniform float BLOOM; uniform float THR; uniform float GLARE; uniform float FLARE; uniform float GHOSTS; uniform vec2 FL; uniform float LIGHT;
uniform float DOF; uniform vec2 FOC; uniform float FOCR; uniform float CHROMA; uniform float DISTORT; uniform float VIG; uniform float GRAIN;
uniform float SHARP; uniform float EXPO; uniform float CON; uniform float SAT; uniform float TEMP; uniform float GAM;
vec3 bright(vec3 c) { return max(c - THR, 0.0) / max(1.0 - THR, 0.05); }
void main() {
  vec2 p = (v - 0.5) * vec2(ASP, 1.0);
  float r2 = dot(p, p);
  vec2 uv = v;
  if (DISTORT != 0.0) uv = p * (1.0 + DISTORT * 0.45 * r2) / vec2(ASP, 1.0) + 0.5;
  float inside = step(0.0, uv.x) * step(uv.x, 1.0) * step(0.0, uv.y) * step(uv.y, 1.0);
  vec3 c;
  if (CHROMA > 0.0) {
    vec2 d = (uv - 0.5) * CHROMA * 0.014 * (0.3 + r2 * 2.0);
    c = vec3(texture2D(SRC, uv + d).r, texture2D(SRC, uv).g, texture2D(SRC, uv - d).b);
  } else c = texture2D(SRC, uv).rgb;
  vec3 bl = texture2D(BLUR, uv).rgb;
  if (DOF > 0.0) {
    float f = smoothstep(FOCR * 0.25, FOCR, length(p - FOC * vec2(ASP, 1.0) * 0.5));
    c = mix(c, bl, f * DOF);
  }
  if (SHARP > 0.0) c += (c - bl) * SHARP;
  if (BLOOM > 0.0) c += bright(bl) * BLOOM;
  if (GLARE > 0.0) {
    vec3 g = vec3(0.0);
    for (int i = 1; i <= 6; i++) {
      float o = float(i) * 0.04; float w = 1.0 - float(i) / 7.0;
      g += (bright(texture2D(BLUR, uv + vec2(o, 0.0)).rgb) + bright(texture2D(BLUR, uv - vec2(o, 0.0)).rgb)) * w;
    }
    c += g * GLARE * 0.22 * vec3(0.75, 0.9, 1.25);
  }
  if (FLARE > 0.0) {
    // the light itself: a glow, a soft six-point star and a faint ring
    vec2 lp = FL * vec2(ASP, 1.0) * 0.5;
    vec2 d = p - lp; float r = length(d); float a = atan(d.y, d.x);
    vec3 f = vec3(1.0, 0.9, 0.75) * (0.05 / (r * r * 30.0 + 0.05) + pow(abs(cos(a * 3.0)), 60.0) * exp(-r * 5.0) * 0.7);
    float rr = (r - 0.3) * 16.0; f += vec3(0.45, 0.7, 1.0) * exp(-rr * rr) * 0.1;
    // LIGHT = 0 in flat scenes: there is no light source there, so no star of its own (it sat on every 2D background);
    // only the ghosts of the picture's own bright parts remain
    f *= LIGHT;
    // ghosts on the line from the light through the middle of the lens
    for (int i = 0; i < 5; i++) {
      vec2 gp = lp * (-0.35 - float(i) * 0.38); float gr = 0.025 + 0.022 * float(i);
      vec3 tint = 0.5 + 0.5 * cos(vec3(0.0, 2.0, 4.0) + float(i) * 1.3);
      f += tint * smoothstep(gr, gr * 0.55, length(p - gp)) * 0.16 * GHOSTS * LIGHT;
    }
    // and ghosts of the bright parts of the picture, mirrored through the middle
    vec2 m = 1.0 - uv;
    for (int i = 0; i < 3; i++) f += bright(texture2D(BLUR, m + (vec2(0.5) - m) * (0.45 * float(i))).rgb) * (0.5 - 0.12 * float(i)) * GHOSTS * 0.4 * vec3(0.7, 0.85, 1.1);
    c += f * FLARE;
  }
  c *= exp2(EXPO);
  c *= vec3(1.0 + TEMP * 0.2, 1.0 + TEMP * 0.03, 1.0 - TEMP * 0.2);
  c = (c - 0.5) * CON + 0.5;
  float l = dot(c, vec3(0.299, 0.587, 0.114)); c = mix(vec3(l), c, SAT);
  c = pow(max(c, 0.0), vec3(1.0 / GAM));
  if (VIG > 0.0) c *= 1.0 - VIG * 0.65 * smoothstep(0.35, 1.45, length((v - 0.5) * 2.0));
  if (GRAIN > 0.0) c += (fract(sin(dot(v * 917.0 + T * 13.7, vec2(12.9898, 78.233))) * 43758.5453) - 0.5) * GRAIN * 0.18;
  gl_FragColor = vec4(max(c, 0.0) * inside, 1.0);
}"""

        private const val COMP_FS = "precision highp float; " + MASK_FN + """
varying vec2 v; uniform sampler2D TEX; uniform sampler2D MASK; uniform sampler2D WARP;
uniform float OP; uniform float FOLD; uniform float MSK; uniform float WRP; uniform float RIP; uniform float TM; uniform float FADE; uniform float MUL; uniform float ASP;
uniform float PLANE; uniform mat3 PM; uniform vec2 POFF; uniform vec2 PSTR; uniform float PZ;
void main() {
  vec2 p = (v - 0.5) * vec2(ASP, 1.0);
  float outside = 0.0;
  if (PLANE > 0.5) {
    // a ray from the eye through this pixel meets the layer's plane: turned by PM, PZ times further away, moved by POFF
    vec3 n = PM * vec3(0.0, 0.0, -1.0);
    vec3 cen = vec3(POFF * PZ, 1.5 * PZ);
    vec3 d = vec3(p, 1.5);
    float dn = dot(d, n);
    float t = abs(dn) > 0.00001 ? dot(cen, n) / dn : -1.0;
    if (t <= 0.0) outside = 1.0;
    vec3 h = d * t - cen;
    p = vec2(dot(h, PM * vec3(1.0, 0.0, 0.0)), dot(h, PM * vec3(0.0, 1.0, 0.0))) / PSTR;
  }
  float r0 = length(p);
  float am = shapeAlpha(p);   // the shape mask moves, scales and turns with the layer
  if (FOLD > 0.5 && FOLD < 1.5) { p.x = abs(p.x); }
  else if (FOLD > 1.5 && FOLD < 2.5) { p = abs(p); }
  else if (FOLD > 2.5 && FOLD < 3.5) {
    p = abs(p);
    vec2 n = normalize(vec2(1.0 / ASP, 1.0));
    float d = dot(p, n) - 0.3;
    if (d > 0.0) p -= 2.0 * d * n;
  } else if (FOLD > 3.5) {
    float k = FOLD < 4.5 ? 6.0 : (FOLD < 5.5 ? 8.0 : 12.0);
    float a = atan(p.y, p.x); float r = length(p); float s = 6.28318 / k;
    a = mod(a, s); a = abs(a - s * 0.5);
    p = vec2(cos(a), sin(a)) * r;
  }
  if (WRP > 0.0) {
    vec3 w = texture2D(WARP, v).rgb; float l = dot(w, vec3(0.333));
    p += normalize(p + 0.0001) * l * WRP * 0.5 + (w.rg - w.b) * WRP * 0.15;
  }
  if (RIP > 0.0) p += normalize(p + 0.0001) * sin(length(p) * 40.0 - TM * 3.0) * RIP;
  vec2 q = p / vec2(ASP, 1.0) + 0.5;
  if (PLANE > 0.5 && (q.x < 0.0 || q.x > 1.0 || q.y < 0.0 || q.y > 1.0)) outside = 1.0;
  vec3 c = texture2D(TEX, q).rgb * (1.0 - outside);
  if (MSK != 0.0) {
    float m = dot(texture2D(MASK, v).rgb, vec3(0.333));
    m = clamp(m * 1.6, 0.0, 1.0);
    if (MSK < 0.0) m = 1.0 - m;
    c *= mix(1.0, m, abs(MSK));
  }
  if (FADE > 0.0) c *= 1.0 - FADE * smoothstep(0.12, 0.62, r0);
  c *= am;
  if (MUL > 0.5) { gl_FragColor = vec4(mix(vec3(1.0), c, OP), 1.0); return; }
  c *= OP;
  float al = clamp(max(c.r, max(c.g, c.b)) * 2.5, 0.0, 1.0);
  gl_FragColor = vec4(c, al);
}"""
    }
}

/** Geometry as line segments (6 floats each), all in roughly the -1..1 range. */
object Geometry {
    private const val TAU = 6.2831855f
    private val PHI = ((1 + Math.sqrt(5.0)) / 2).toFloat()

    fun of(name: String, dim: Int = 0): FloatArray = when (name) {
        "Flower of Life" -> flower(2, 0.21f)
        "Seed of Life" -> flower(1, 0.32f)
        "Metatron's Cube" -> metatron()
        "Floral mandala" -> mandala()
        "Golden spiral" -> spiral()
        "Hex lattice" -> hexLattice()
        "Tetrahedron" -> edges(TETRA, 0.8f)
        "Cube" -> edges(CUBE, 0.62f)
        "Octahedron" -> edges(OCTA, 0.95f)
        "Icosahedron" -> edges(ICOSA, 0.65f)
        "Dodecahedron" -> edges(dodeca(), 0.6f)
        "Star tetrahedron" -> edges(TETRA, 0.85f) + edges(TETRA.map { floatArrayOf(-it[0], -it[1], -it[2]) }.toTypedArray(), 0.85f)
        "Nested solids" -> edges(dodeca(), 0.72f) + edges(ICOSA, 0.46f) + edges(CUBE, 0.28f) + edges(TETRA, 0.3f)
        "Torus" -> torus()
        else -> Solids.of(name, dim) ?: flower(2, 0.21f)
    }

    private fun circle(out: ArrayList<Float>, cx: Float, cy: Float, r: Float, segs: Int = 56) {
        for (i in 0 until segs) {
            val a = i * TAU / segs; val b = (i + 1) * TAU / segs
            out.addAll(listOf(cx + r * cos(a), cy + r * sin(a), 0f, cx + r * cos(b), cy + r * sin(b), 0f))
        }
    }

    private fun line(out: ArrayList<Float>, x0: Float, y0: Float, x1: Float, y1: Float) { out.addAll(listOf(x0, y0, 0f, x1, y1, 0f)) }

    /** Overlapping circles on a hexagonal lattice, enclosed by an outer circle. */
    private fun flower(rings: Int, r: Float): FloatArray {
        val out = ArrayList<Float>(); val root3 = Math.sqrt(3.0).toFloat()
        for (q in -rings..rings) for (s in -rings..rings) {
            if (kotlin.math.abs(q + s) > rings) continue
            circle(out, r * (q + s * 0.5f), r * s * root3 / 2f, r)
        }
        circle(out, 0f, 0f, r * (rings + 1), 96)
        if (rings > 1) circle(out, 0f, 0f, r * (rings + 1) * 1.04f, 96)
        return out.toFloatArray()
    }

    private fun metatron(): FloatArray {
        val out = ArrayList<Float>(); val r = 0.15f; val pts = ArrayList<Pair<Float, Float>>()
        pts.add(0f to 0f)
        for (ring in 1..2) for (i in 0 until 6) { val a = i * TAU / 6 + TAU / 4; pts.add(ring * 2 * r * cos(a) to ring * 2 * r * sin(a)) }
        for ((x, y) in pts) circle(out, x, y, r, 40)
        for (i in pts.indices) for (j in i + 1 until pts.size) line(out, pts[i].first, pts[i].second, pts[j].first, pts[j].second)
        return out.toFloatArray()
    }

    private fun mandala(): FloatArray {
        val out = ArrayList<Float>()
        for ((k, base, amp) in listOf(Triple(12, 0.48f, 0.38f), Triple(8, 0.28f, 0.22f), Triple(24, 0.82f, 0.08f), Triple(6, 0.12f, 0.1f))) {
            val segs = k * 24
            for (i in 0 until segs) {
                val a = i * TAU / segs; val b = (i + 1) * TAU / segs
                val ra = base + amp * kotlin.math.abs(cos(a * k / 2)); val rb = base + amp * kotlin.math.abs(cos(b * k / 2))
                line(out, ra * cos(a), ra * sin(a), rb * cos(b), rb * sin(b))
            }
        }
        circle(out, 0f, 0f, 0.92f, 96); circle(out, 0f, 0f, 0.96f, 96)
        return out.toFloatArray()
    }

    private fun spiral(): FloatArray {
        val out = ArrayList<Float>(); val growth = Math.pow(PHI.toDouble(), 2.0 / Math.PI).toFloat()
        for (arm in 0 until 8) {
            val off = arm * TAU / 8; var a0 = 0f
            while (a0 < TAU * 2.2f) {
                val a1 = a0 + 0.12f
                val r0 = 0.03f * Math.pow(growth.toDouble(), a0.toDouble()).toFloat(); val r1 = 0.03f * Math.pow(growth.toDouble(), a1.toDouble()).toFloat()
                line(out, r0 * cos(a0 + off), r0 * sin(a0 + off), r1 * cos(a1 + off), r1 * sin(a1 + off))
                a0 = a1
            }
        }
        return out.toFloatArray()
    }

    private fun hexLattice(): FloatArray {
        val out = ArrayList<Float>(); val r = 0.12f; val w = Math.sqrt(3.0).toFloat() * r; val n = 8
        for (row in -n..n) for (col in -n..n) {
            val cx = col * w + if (row % 2 != 0) w / 2 else 0f; val cy = row * r * 1.5f
            for (i in 0 until 6) {
                val a = i * TAU / 6 + TAU / 12; val b = (i + 1) * TAU / 6 + TAU / 12
                line(out, cx + r * cos(a), cy + r * sin(a), cx + r * cos(b), cy + r * sin(b))
            }
        }
        return out.toFloatArray()
    }

    private fun torus(): FloatArray {
        val out = ArrayList<Float>(); val big = 0.62f; val small = 0.26f
        fun pt(u: Float, w: Float) = floatArrayOf((big + small * cos(w)) * cos(u), (big + small * cos(w)) * sin(u), small * sin(w))
        val rings = 24; val segs = 20
        for (i in 0 until rings) for (j in 0 until segs) {
            val u = i * TAU / rings; val w0 = j * TAU / segs; val w1 = (j + 1) * TAU / segs
            // twisted wires give the woven, flowing look
            val a = pt(u + w0 * 0.25f, w0); val b = pt(u + w1 * 0.25f, w1)
            out.addAll(a.toList()); out.addAll(b.toList())
        }
        return out.toFloatArray()
    }

    /** All vertex pairs at the solid's shortest edge length become edges. */
    private fun edges(v: Array<FloatArray>, scale: Float): FloatArray {
        fun d(a: FloatArray, b: FloatArray) = Math.sqrt(((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2])).toDouble()).toFloat()
        var min = Float.MAX_VALUE
        for (i in v.indices) for (j in i + 1 until v.size) min = minOf(min, d(v[i], v[j]))
        val out = ArrayList<Float>()
        for (i in v.indices) for (j in i + 1 until v.size) if (d(v[i], v[j]) < min * 1.01f) {
            for (k in 0..2) out.add(v[i][k] * scale)
            for (k in 0..2) out.add(v[j][k] * scale)
        }
        return out.toFloatArray()
    }

    private fun f(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
    private val TETRA = arrayOf(f(1f, 1f, 1f), f(-1f, -1f, 1f), f(-1f, 1f, -1f), f(1f, -1f, -1f)).map { v -> FloatArray(3) { v[it] * 0.8f } }.toTypedArray()
    private val CUBE = arrayOf(f(1f, 1f, 1f), f(1f, 1f, -1f), f(1f, -1f, 1f), f(1f, -1f, -1f), f(-1f, 1f, 1f), f(-1f, 1f, -1f), f(-1f, -1f, 1f), f(-1f, -1f, -1f))
    private val OCTA = arrayOf(f(1f, 0f, 0f), f(-1f, 0f, 0f), f(0f, 1f, 0f), f(0f, -1f, 0f), f(0f, 0f, 1f), f(0f, 0f, -1f))
    private val ICOSA = arrayOf(f(0f, 1f, PHI), f(0f, -1f, PHI), f(0f, 1f, -PHI), f(0f, -1f, -PHI), f(1f, PHI, 0f), f(-1f, PHI, 0f), f(1f, -PHI, 0f), f(-1f, -PHI, 0f), f(PHI, 0f, 1f), f(-PHI, 0f, 1f), f(PHI, 0f, -1f), f(-PHI, 0f, -1f))
    private fun dodeca(): Array<FloatArray> {
        val i = 1f / PHI; val list = ArrayList<FloatArray>(CUBE.toList())
        for (a in listOf(-1f, 1f)) for (b in listOf(-1f, 1f)) { list.add(f(0f, a * i, b * PHI)); list.add(f(a * i, b * PHI, 0f)); list.add(f(a * PHI, 0f, b * i)) }
        return list.toTypedArray()
    }
}

@Composable
fun Bg2Background(fps30: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) { Bg2.load(ctx); AudioBands.start(); onDispose { AudioBands.stop() } }
    AndroidView(
        factory = { c ->
            GLSurfaceView(c).apply {
                setEGLContextClientVersion(2)
                val r = Bg2Renderer(c.applicationContext); r.fps30 = fps30
                tag = r
                setRenderer(r)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                isFocusable = false
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        },
        update = { v -> (v.tag as? Bg2Renderer)?.fps30 = fps30; v.holder.setFixedSize((960 * Bg2.scale).toInt(), (540 * Bg2.scale).toInt()) },
        modifier = modifier,
        onRelease = { (it.tag as? Bg2Renderer)?.stopPlayers(); it.onPause() }
    )
}
