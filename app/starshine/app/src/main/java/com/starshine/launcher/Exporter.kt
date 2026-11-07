package com.starshine.launcher

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLExt
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.io.File

/**
 * Renders a procedural background to an MP4 loop (720p30) in the app's backgrounds folder,
 * where it shows up as a regular video background (and can be adb-pulled to use anywhere).
 * Frames are posted in real time because surface-input timestamps follow wall-clock time.
 */
object Exporter {
    @Volatile var progress: Float = -1f   // -1 idle, 0..1 running
    @Volatile var lastFile: String = ""
    /** Where the last export can be found by other apps ("Movies/Starshine/x.mp4"), or "" if the public copy failed. */
    @Volatile var publicName: String = ""

    /** Renders the current Bg scene to a 720p MP4 and publishes it to Movies/Starshine. Sound reaction isn't recorded. */
    fun exportScene(ctx: Context, scene: BgScene, seconds: Int = 15, onDone: (File?) -> Unit) {
        if (progress >= 0f) return
        progress = 0f
        Thread {
            val out = File(ctx.getExternalFilesDir("backgrounds"), scene.name.replace(Regex("[^A-Za-z0-9]+"), "_") + "_loop.mp4")
            val r = runCatching { encodeScene(ctx, out, scene, seconds) }
            r.exceptionOrNull()?.let { android.util.Log.e("StarshineBg", "export failed", it) }
            val ok = r.isSuccess
            publicName = (if (ok) PublicVideos.publish(ctx, out) else null) ?: ""
            progress = -1f; lastFile = if (ok) out.absolutePath else ""
            onDone(if (ok) out else null)
        }.apply { isDaemon = true; start() }
    }

    private fun encodeScene(ctx: Context, out: File, scene: BgScene, seconds: Int) {
        val w = 1280; val h = 720; val fps = 30
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000); setInteger(MediaFormat.KEY_FRAME_RATE, fps); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface(); codec.start()
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1); val num = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142, 1, EGL14.EGL_NONE), 0, configs, 0, 1, num, 0)
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var muxing = false
        val info = MediaCodec.BufferInfo()
        fun drain(end: Boolean) {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxing) { buf.position(info.offset); buf.limit(info.offset + info.size); muxer.writeSampleData(track, buf, info) }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
        val renderer = Bg2Renderer(ctx, { scene }, { false })
        renderer.onSurfaceCreated(null, null)
        renderer.onSurfaceChanged(null, w, h)
        val frames = seconds * fps
        for (i in 0 until frames) {
            renderer.renderFrame(scene, 1f / fps, w, h)
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, i * 1_000_000_000L / fps)
            EGL14.eglSwapBuffers(display, eglSurface)
            drain(false); progress = i / frames.toFloat()
        }
        codec.signalEndOfInputStream(); drain(true)
        renderer.stopPlayers()
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface); EGL14.eglDestroyContext(display, context); EGL14.eglTerminate(display)
        runCatching { codec.stop() }; codec.release(); surface.release()
        if (muxing) muxer.stop(); muxer.release()
    }

    fun export(ctx: Context, name: String, style: BgStyle, seconds: Int = 15, onDone: (File?) -> Unit) {
        if (progress >= 0f) return
        progress = 0f
        Thread {
            val out = File(ctx.getExternalFilesDir("backgrounds"), "${name.replace(' ', '_')}_loop.mp4")
            val ok = runCatching { encode(out, name, style, seconds) }.isSuccess
            val shown = if (ok) PublicVideos.publish(ctx, out) else null   // public copy: VLC and other apps can play it
            progress = -1f; lastFile = if (ok) out.absolutePath else ""
            publicName = shown ?: ""
            onDone(if (ok) out else null)
        }.apply { isDaemon = true; start() }
    }

    private fun encode(out: File, name: String, style: BgStyle, seconds: Int) {
        val w = 1280; val h = 720; val fps = 30
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000); setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var muxing = false
        val info = MediaCodec.BufferInfo()
        val scope = CanvasDrawScope()
        fun drain(end: Boolean) {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxing) { buf.position(info.offset); buf.limit(info.offset + info.size); muxer.writeSampleData(track, buf, info) }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
        val frames = seconds * fps; val start = System.nanoTime()
        for (i in 0 until frames) {
            val c = surface.lockHardwareCanvas()
            scope.draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(c), Size(w.toFloat(), h.toFloat())) {
                drawProcedural(name, i / fps.toFloat(), style)
            }
            surface.unlockCanvasAndPost(c)
            drain(false)
            progress = i / frames.toFloat()
            val due = start + (i + 1) * 1_000_000_000L / fps
            val wait = (due - System.nanoTime()) / 1_000_000
            if (wait > 0) Thread.sleep(wait)
        }
        codec.signalEndOfInputStream(); drain(true)
        runCatching { codec.stop() }; codec.release(); surface.release()
        if (muxing) { muxer.stop() }; muxer.release()
    }
}
