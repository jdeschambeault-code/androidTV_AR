package com.starshine.launcher

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
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
    private class Phase { var t = 0f; var spin = 0f; var hue = 0f }
    private class Mesh(val vbo: Int, val verts: Int)
    private class MediaTex(val tex: Int, val external: Boolean, val path: String, @Volatile var aspect: Float = 16f / 9f) {
        var surfaceTexture: SurfaceTexture? = null; var surface: Surface? = null; var player: ExoPlayer? = null
    }

    private val progs = HashMap<String, Prog?>()
    private val targets = HashMap<Int, Target>()
    private val phases = HashMap<Int, Phase>()
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
        clipA = null; clipB = null; fxScene = null; fxHistA = null; fxHistB = null; fxSmallA = null; fxSmallB = null; outFbo = 0; gridVbo = 0; depthRbs.clear(); progs.clear(); targets.clear(); meshes.clear(); releaseMedia(); canvases.values.forEach { it.stop() }; canvases.clear(); last = System.nanoTime()
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
    fun renderFrame(scene: BgScene, dt: Float, w: Int, h: Int) {
        AudioBands.tick(dt)
        // a journey's transition: both clips keep running. The nodes of the two scenes take turns, which keeps this chip's
        // frame rate and leaves time for the audio, while the mix itself updates every frame.
        val from = if (live && !monitor) Bg2.blendFrom else null
        val mix = Bg2.blendMix
        if (from == null) blendFrame = 0
        val active = scene.layers.filter { it.on } + (from?.layers?.filter { it.on } ?: emptyList())
        targets.keys.toList().filter { id -> active.none { it.id == id } }.forEach { id -> targets.remove(id)?.let { deleteTarget(it) } }
        // nodes removed from the scene give their media back
        media.keys.toList().filter { id -> active.none { it.id == id && it.kind == GenKind.Media } }.forEach { releaseMedia(it) }
        canvases.keys.toList().filter { id -> active.none { it.id == id && it.kind == GenKind.Canvas } }.forEach { releaseCanvas(it) }
        GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDisable(GLES20.GL_CULL_FACE)

        if (from == null) for (l in active) renderNode(l, dt)
        else {
            val turn = blendFrame % 2 == 0; val firstFrame = blendFrame == 0; blendFrame++
            for (l in from.layers) if (l.on && (firstFrame || turn)) renderNode(l, if (firstFrame) dt else dt * 2f)
            for (l in scene.layers) if (l.on && (firstFrame || !turn)) renderNode(l, if (firstFrame) dt else dt * 2f)
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val fx = RenderFx.active(scene)
        if (monitor && !Bg2.fullPreview) {
            // workspace: result in the monitor area, and each layer's own picture in its tile under it
            val k = w / 960f
            val vx = (Stage.X * k).toInt(); val vw = (Stage.W * k).toInt(); val vh = (Stage.H * k).toInt(); val vy = h - (Stage.Y * k).toInt() - vh
            val solo = Bg2.soloId
            if (fx && solo < 0) beginFx(vw, vh) else GLES20.glViewport(vx, vy, vw, vh)
            for (l in active) if (if (solo >= 0) l.id == solo else l.show) composite(l, scene)
            if (fx && solo < 0) endFx(scene, vx, vy, vw, vh, dt)
            drawTiles(scene, w, h)
        } else {
            if (fx) beginFx(w, h)
            if (from != null) drawTransition(from, scene, mix, w, h)
            else for (l in scene.layers) if (l.on && l.show) composite(l, scene)
            if (fx) endFx(scene, 0, 0, w, h, dt)
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
    private var fxSmallA: Target? = null; private var fxSmallB: Target? = null
    private var fxTime = 0f

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

    private fun endFx(scene: BgScene, x: Int, y: Int, w: Int, h: Int, dt: Float) {
        outFbo = 0
        GLES20.glDisable(GLES20.GL_BLEND)
        fxTime = (fxTime + dt) % 1000f
        var src = fxScene ?: return
        // shutter: each frame keeps some of the last, so movement leaves a trail
        val motion = scene.fx("motion")
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
        // a soft copy at a quarter of the size feeds bloom, glare, flare ghosts, depth of field and sharpening
        val needBlur = listOf("bloom", "glare", "flare", "dof", "sharpen").any { scene.fx(it) > 0.001f }
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
        fun u(n: String, k: String) = GLES20.glUniform1f(p.loc(n), scene.fx(k))
        u("BLOOM", "bloom"); u("THR", "bloomT"); u("GLARE", "glare"); u("FLARE", "flare"); u("GHOSTS", "ghosts")
        u("DOF", "dof"); u("FOCR", "focusR"); u("CHROMA", "chroma"); u("DISTORT", "distort"); u("VIG", "vignette"); u("GRAIN", "grain")
        u("SHARP", "sharpen"); u("EXPO", "exposure"); u("CON", "contrast"); u("SAT", "fxSat"); u("TEMP", "temp"); u("GAM", "gamma")
        GLES20.glUniform2f(p.loc("FL"), scene.fx("flareX"), scene.fx("flareY")); GLES20.glUniform2f(p.loc("FOC"), scene.fx("focusX"), scene.fx("focusY"))
        fullscreen()
    }

    /** The tiles under the monitor show each layer's own picture, as it renders, rather than an icon. */
    private fun drawTiles(scene: BgScene, w: Int, h: Int) {
        if (Med.subTab != 0 || Med.planning != null) return
        val p = program("blit", FULL_VS, BLIT_FS) ?: return
        GLES20.glUseProgram(p.id); GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glUniform1i(p.loc("TEX"), 0)
        val n = scene.layers.size; val k = w / 960f
        scene.layers.forEachIndexed { i, l ->
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

    private fun drawClip(t: Target, scene: BgScene, factor: (Int, Layer) -> Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, t.fbo)
        GLES20.glViewport(0, 0, t.w, t.h)
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        scene.layers.forEachIndexed { i, l -> if (l.on && l.show) composite(l, scene, factor(i, l)) }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Two clips crossing over. With a layer offset the new scene's layers arrive one after another, the leading layer first. */
    private fun drawTransition(from: BgScene, scene: BgScene, mix: Float, w: Int, h: Int) {
        val a = clip(clipA, w, h).also { clipA = it }; val b = clip(clipB, w, h).also { clipB = it }
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
        val q = Bg2.scale
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
        val ph = phases.getOrPut(l.id) { Phase() }
        ph.t += dt * l["speed"]
        // Spin target speeds the rotation up instead of jumping it, so reaction never looks like a zoom
        ph.spin += dt * l["spin"] * (1f + 3f * mod(l, 2)) * 6.2831855f
        ph.hue += dt * l["hueDrift"]
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

    private val GX = 160; private val GY = 90
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
        GLES20.glUniform1f(p.loc("MAXI"), 32f + detail * 160f); GLES20.glUniform2f(p.loc("OFF"), l["x"], l["y"])
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

    private fun uploadRibbons(segs: FloatArray): Mesh {
        // segs = x0 y0 z0 x1 y1 z1 per segment; each becomes a quad whose width is applied on screen by the vertex shader
        val n = segs.size / 6
        val fb = ByteBuffer.allocateDirect(n * 6 * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val corners = floatArrayOf(0f, -1f, 1f, -1f, 1f, 1f, 0f, -1f, 1f, 1f, 0f, 1f)
        for (i in 0 until n) for (c in 0 until 6) {
            fb.put(segs, i * 6, 6); fb.put(corners[c * 2]); fb.put(corners[c * 2 + 1])
        }
        fb.position(0)
        val b = IntArray(1); GLES20.glGenBuffers(1, b, 0)
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
        val bmp = BitmapFactory.decodeFile(l.media, BitmapFactory.Options().apply { inSampleSize = sample }) ?: run { GLES20.glDeleteTextures(1, tex, 0); return null }
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
        fullscreen()
    }

    private fun angleRad(l: Layer) = Math.toRadians(l["angle"].toDouble()).toFloat()

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
        GLES20.glUniform1f(p.loc("RIP"), 0.02f * mod(l, 5)); GLES20.glUniform1f(p.loc("TM"), phases[l.id]?.t ?: 0f)
        GLES20.glUniform1f(p.loc("FADE"), (l["fade"] + mod(l, 7) * 0.5f).coerceIn(0f, 1f)); GLES20.glUniform1f(p.loc("MUL"), if (blend == 3) 1f else 0f)
        GLES20.glUniform1f(p.loc("ASP"), viewW.toFloat() / viewH)
        // the layer as a plane in 3D: whatever a generator can't move, scale or turn by itself is done here, so every
        // kind of layer takes position X Y Z, scale X Y and rotation X Y Z the same way
        val geo = l.kind == GenKind.Line || l.kind == GenKind.Solid
        val nativeTilt = geo || (l.kind == GenKind.Media && l["persp"] > 0.5f) || (l.type == FRACTAL && l["f3d"] > 0.5f)
        val rx = if (nativeTilt) 0f else Math.toRadians(l["tiltX"].toDouble()).toFloat()
        val ry = if (nativeTilt) 0f else Math.toRadians(l["tiltY"].toDouble()).toFloat()
        val rz = if (l.kind == GenKind.Points) angleRad(l) + (phases[l.id]?.spin ?: 0f) else 0f
        val ox = if (l.kind == GenKind.Canvas) l["x"] else 0f; val oy = if (l.kind == GenKind.Canvas) l["y"] else 0f
        val sx = if (geo) 1f else l["stx"]; val sy = if (geo) 1f else l["sty"]
        val depth = Math.pow(2.0, l["z"].toDouble()).toFloat()
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
            "Sun flare" to "flare", "Candle" to "candle", "Globe" to "globe", "Honeycomb" to "honeycomb", "Aether" to "aether")

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
        private const val MEDIA_COMMON = """
varying vec2 v; uniform float HUE; uniform float SAT; uniform float GLOW; uniform float ZOOM; uniform float ROT; uniform vec2 OFF;
uniform float ASP; uniform float MA; uniform float FIT; uniform float PERSP; uniform vec4 TILT;
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
  float c = cos(ROT); float s = sin(ROT);
  p = vec2(c * p.x - s * p.y, s * p.x + c * p.y) / ZOOM;
  bool wide = MA > ASP;
  vec2 box = (FIT < 0.5) == wide ? vec2(MA, 1.0) : vec2(ASP, ASP / MA);
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
        private const val BLIT_FS = "precision mediump float; varying vec2 v; uniform sampler2D TEX; void main(){ gl_FragColor = vec4(texture2D(TEX, v).rgb, 1.0); }"
        private const val ACC_FS = "precision mediump float; varying vec2 v; uniform sampler2D CUR; uniform sampler2D OLD; uniform float K;" +
            "void main(){ gl_FragColor = vec4(mix(texture2D(CUR, v).rgb, texture2D(OLD, v).rgb, K), 1.0); }"
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
uniform float BLOOM; uniform float THR; uniform float GLARE; uniform float FLARE; uniform float GHOSTS; uniform vec2 FL;
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
    // ghosts on the line from the light through the middle of the lens
    for (int i = 0; i < 5; i++) {
      vec2 gp = lp * (-0.35 - float(i) * 0.38); float gr = 0.025 + 0.022 * float(i);
      vec3 tint = 0.5 + 0.5 * cos(vec3(0.0, 2.0, 4.0) + float(i) * 1.3);
      f += tint * smoothstep(gr, gr * 0.55, length(p - gp)) * 0.16 * GHOSTS;
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

        private const val COMP_FS = """
precision highp float;
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
