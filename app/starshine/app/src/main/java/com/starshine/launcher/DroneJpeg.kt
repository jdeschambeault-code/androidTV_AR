package com.starshine.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * RTP/JPEG (RFC 2435), which the KY UFO camera sends. Each packet carries part of a JPEG's image data without the file
 * headers, so a complete JPEG is rebuilt for every frame: quantization tables from the first packet, standard Huffman tables.
 */
class RtpJpeg {
    private var data = ByteArray(512 * 1024)
    private var size = 0
    private var expected = 0
    private var ok = false
    private var qt: ByteArray? = null
    private var type = 0; private var w = 0; private var h = 0; private var dri = 0

    /** Takes one RTP payload; returns a complete JPEG when the frame's last packet arrives, else null. */
    fun add(b: ByteArray, start: Int, end: Int, marker: Boolean): ByteArray? {
        var o = start
        if (end - o < 8) return null
        val offset = ((b[o + 1].toInt() and 255) shl 16) or ((b[o + 2].toInt() and 255) shl 8) or (b[o + 3].toInt() and 255)
        val t = b[o + 4].toInt() and 255; val q = b[o + 5].toInt() and 255
        val width = (b[o + 6].toInt() and 255) * 8; val height = (b[o + 7].toInt() and 255) * 8
        o += 8
        if (t >= 64) { dri = ((b[o].toInt() and 255) shl 8) or (b[o + 1].toInt() and 255); o += 4 } else dri = 0
        if (offset == 0) {
            if (q >= 128) {
                val len = ((b[o + 2].toInt() and 255) shl 8) or (b[o + 3].toInt() and 255)
                o += 4
                if (len > 0 && o + len <= end) { qt = b.copyOfRange(o, o + len); o += len }
            }
            size = 0; expected = 0; ok = true; type = t and 63; w = width; h = height
        }
        if (offset != expected) ok = false     // a lost packet: skip this frame instead of showing a torn picture
        val n = end - o
        if (ok && n > 0 && offset + n <= data.size) { System.arraycopy(b, o, data, offset, n); size = offset + n; expected = size }
        if (!marker) return null
        val done = ok && qt != null && size > 0
        ok = false
        return if (done) build() else null
    }

    private fun build(): ByteArray {
        val out = ByteArrayOutputStream(size + 700)
        fun u8(v: Int) = out.write(v and 255)
        fun u16(v: Int) { u8(v shr 8); u8(v) }
        u8(0xFF); u8(0xD8)
        val tables = qt!!; val each = if (tables.size >= 128) 64 else tables.size
        u8(0xFF); u8(0xDB); u16(2 + 1 + each); u8(0); out.write(tables, 0, each)
        u8(0xFF); u8(0xDB); u16(2 + 1 + each); u8(1); out.write(tables, if (tables.size >= 128) 64 else 0, each)
        if (dri > 0) { u8(0xFF); u8(0xDD); u16(4); u16(dri) }
        u8(0xFF); u8(0xC0); u16(17); u8(8); u16(h); u16(w); u8(3)
        u8(1); u8(if (type == 0) 0x21 else 0x22); u8(0); u8(2); u8(0x11); u8(1); u8(3); u8(0x11); u8(1)
        fun dht(cls: Int, lens: IntArray, syms: IntArray) { u8(0xFF); u8(0xC4); u16(3 + 16 + syms.size); u8(cls); lens.forEach { u8(it) }; syms.forEach { u8(it) } }
        dht(0x00, LUM_DC, DC_SYMS); dht(0x10, LUM_AC, LUM_AC_SYMS); dht(0x01, CHM_DC, DC_SYMS); dht(0x11, CHM_AC, CHM_AC_SYMS)
        u8(0xFF); u8(0xDA); u16(12); u8(3); u8(1); u8(0x00); u8(2); u8(0x11); u8(3); u8(0x11); u8(0); u8(63); u8(0)
        out.write(data, 0, size)
        u8(0xFF); u8(0xD9)
        return out.toByteArray()
    }

    companion object {
        private val DC_SYMS = IntArray(12) { it }
        private val LUM_DC = intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
        private val CHM_DC = intArrayOf(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
        private val LUM_AC = intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d)
        private val CHM_AC = intArrayOf(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77)
        private fun hex(s: String) = s.trim().split(Regex("\\s+")).map { it.toInt(16) }.toIntArray()
        private val LUM_AC_SYMS = hex("""01 02 03 00 04 11 05 12 21 31 41 06 13 51 61 07 22 71 14 32 81 91 a1 08 23 42 b1 c1 15 52 d1 f0
            24 33 62 72 82 09 0a 16 17 18 19 1a 25 26 27 28 29 2a 34 35 36 37 38 39 3a 43 44 45 46 47 48 49 4a 53 54 55 56 57 58 59 5a
            63 64 65 66 67 68 69 6a 73 74 75 76 77 78 79 7a 83 84 85 86 87 88 89 8a 92 93 94 95 96 97 98 99 9a a2 a3 a4 a5 a6 a7 a8 a9 aa
            b2 b3 b4 b5 b6 b7 b8 b9 ba c2 c3 c4 c5 c6 c7 c8 c9 ca d2 d3 d4 d5 d6 d7 d8 d9 da e1 e2 e3 e4 e5 e6 e7 e8 e9 ea f1 f2 f3 f4 f5 f6 f7 f8 f9 fa""")
        private val CHM_AC_SYMS = hex("""00 01 02 03 11 04 05 21 31 06 12 41 51 07 61 71 13 22 32 81 08 14 42 91 a1 b1 c1 09 23 33 52 f0
            15 62 72 d1 0a 16 24 34 e1 25 f1 17 18 19 1a 26 27 28 29 2a 35 36 37 38 39 3a 43 44 45 46 47 48 49 4a 53 54 55 56 57 58 59 5a
            63 64 65 66 67 68 69 6a 73 74 75 76 77 78 79 7a 82 83 84 85 86 87 88 89 8a 92 93 94 95 96 97 98 99 9a a2 a3 a4 a5 a6 a7 a8 a9 aa
            b2 b3 b4 b5 b6 b7 b8 b9 ba c2 c3 c4 c5 c6 c7 c8 c9 ca d2 d3 d4 d5 d6 d7 d8 d9 da e2 e3 e4 e5 e6 e7 e8 e9 ea f2 f3 f4 f5 f6 f7 f8 f9 fa""")
    }
}

/** Shows JPEG frames on the screen surface and hands them to the recorder when one is running. */
class JpegScreen(private val surface: Surface) {
    private var reuse: Bitmap? = null
    private val smooth = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val opts = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    fun show(jpeg: ByteArray): Boolean {
        opts.inBitmap = reuse
        val bmp = runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) }.getOrNull() ?: run { opts.inBitmap = null; reuse = null; return false }
        reuse = bmp
        val c = runCatching { surface.lockHardwareCanvas() }.getOrNull() ?: runCatching { surface.lockCanvas(null) }.getOrNull() ?: return false
        if (Drone.vr) {
            // side by side: the same picture for each eye, keeping its shape, the eyes apart by the chosen distance
            c.drawColor(android.graphics.Color.BLACK)
            val half = c.width / 2
            val eyeH = (half * bmp.height / bmp.width.coerceAtLeast(1)).coerceAtMost(c.height)
            val top = (c.height - eyeH) / 2
            val shift = (half * Drone.vrGap / 2f).toInt()
            c.drawBitmap(bmp, null, Rect(shift, top, half + shift, top + eyeH), smooth)
            c.drawBitmap(bmp, null, Rect(half - shift, top, c.width - shift, top + eyeH), smooth)
        } else c.drawBitmap(bmp, null, Rect(0, 0, c.width, c.height), smooth)
        runCatching { surface.unlockCanvasAndPost(c) }
        DroneRecorder.frame(bmp)
        return true
    }
}

/** Records the drone picture to an MP4 and publishes it to Movies/Starshine. The drone's camera has no sound. */
object DroneRecorder {
    @Volatile var recording = false; private set
    private var codec: MediaCodec? = null; private var input: Surface? = null; private var muxer: MediaMuxer? = null
    private var track = -1; private var muxing = false; private var file: File? = null; private var want = false
    private val info = MediaCodec.BufferInfo()
    private var ctx: Context? = null

    @Synchronized fun start(c: Context) { ctx = c.applicationContext; want = true; recording = true }

    /** Stops and saves; calls back with where it went. */
    @Synchronized fun stop(onSaved: (String?) -> Unit) {
        want = false; recording = false
        val f = file; val m = muxer; val cd = codec
        if (cd == null || f == null) { onSaved(null); return }
        val saved = runCatching {
            cd.signalEndOfInputStream(); drain(true); cd.stop(); cd.release(); input?.release()
            if (muxing) m?.stop(); m?.release()
            PublicVideos.publish(ctx!!, f)
        }.getOrNull()
        codec = null; input = null; muxer = null; muxing = false; track = -1; file = null
        onSaved(saved)
    }

    @Synchronized fun frame(bmp: Bitmap) {
        if (!want) return
        runCatching {
            if (codec == null) open(bmp.width and 1.inv(), bmp.height and 1.inv())
            val c = input!!.lockHardwareCanvas()
            c.drawBitmap(bmp, 0f, 0f, null)
            input!!.unlockCanvasAndPost(c)
            drain(false)
        }.onFailure { Drone.log(ctx!!, "recording failed: $it"); want = false; recording = false }
    }

    private fun open(w: Int, h: Int) {
        val c = ctx!!
        val f = File(c.getExternalFilesDir("backgrounds"), "Drone_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".mp4")
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000); setInteger(MediaFormat.KEY_FRAME_RATE, 25); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val cd = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        cd.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        input = cd.createInputSurface(); cd.start()
        codec = cd; file = f; muxer = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        Drone.log(c, "recording ${w}x$h to ${f.name}")
    }

    private fun drain(end: Boolean) {
        val cd = codec ?: return; val m = muxer ?: return
        while (true) {
            val i = cd.dequeueOutputBuffer(info, if (end) 20_000 else 0)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = m.addTrack(cd.outputFormat); m.start(); muxing = true }
                i >= 0 -> {
                    val buf = cd.getOutputBuffer(i)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) { buf.position(info.offset); buf.limit(info.offset + info.size); m.writeSampleData(track, buf, info) }
                    cd.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
