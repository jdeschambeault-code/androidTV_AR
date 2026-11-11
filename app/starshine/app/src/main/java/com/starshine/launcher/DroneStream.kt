package com.starshine.launcher

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Base64
import android.view.Surface
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * A small, forgiving RTSP/RTP client for the drone, whose replies are too unusual for ExoPlayer's strict parser.
 * Every reply is written to drone.log and the start of the stream to drone-raw.bin, so an unknown format can be identified.
 * Receives H.264 and decodes it straight to the screen with as little buffering as possible.
 */
class DroneStream(
    private val ctx: Context, private val url: String, private val host: String, private val port: Int,
    private val surface: Surface, private val tcp: Boolean, private val wakeFirst: Boolean,
    private val onSize: (Int, Int) -> Unit, private val onLive: () -> Unit, private val onFail: (String) -> Unit
) {
    @Volatile private var run = true
    private var sock: Socket? = null
    private var udp: DatagramSocket? = null
    private var codec: MediaCodec? = null
    private var cseq = 1
    private var session = ""
    private var liveSent = false
    private var raw: FileOutputStream? = null
    private var rawLeft = 512 * 1024
    private var hexLeft = 6
    private var payload = -1
    private val jpeg = RtpJpeg()
    private val screen = JpegScreen(surface)
    private var frames = 0

    /** Keeps trying until stopped, each attempt from a clean start. */
    fun start() {
        Thread({
            raw = runCatching { FileOutputStream(File(ctx.getExternalFilesDir(null), "drone-raw.bin")) }.getOrNull()
            var attempt = 0
            while (run) {
                attempt++
                Drone.log(ctx, "attempt $attempt " + if (tcp) "TCP" else "UDP")
                if (wakeFirst && attempt == 1) runCatching { DatagramSocket().use { it.send(DatagramPacket(byteArrayOf(8, 1), 2, InetAddress.getByName(host), 7099)) } }
                runCatching { stream() }.onFailure { if (run) { Drone.log(ctx, "stream failed: $it"); onFail(it.message ?: it.toString()) } }
                closeConnection()
                liveSent = false; cseq = 1; session = ""; hexLeft = 3
                var w = 0; while (run && w < 25) { Thread.sleep(100); w++ }
            }
            releaseAll()
        }, "starshine-drone").apply { isDaemon = true; start() }
    }
    fun stop() { run = false; runCatching { udp?.close() }; runCatching { sock?.close() } }

    private class Reply(val status: Int, val headers: Map<String, String>, val body: String)

    private fun send(method: String, target: String, extra: String = "") {
        val s = StringBuilder("$method $target RTSP/1.0\r\nCSeq: ${cseq++}\r\nUser-Agent: Starshine\r\n")
        if (session.isNotEmpty()) s.append("Session: $session\r\n")
        s.append(extra).append("\r\n")
        sock!!.getOutputStream().apply { write(s.toString().toByteArray()); flush() }
    }

    /** Reads up to a blank line, accepting bare \n, any header case and missing fields. */
    private fun read(input: InputStream, label: String): Reply {
        val head = ByteArrayOutputStream()
        var last = 0; var nl = 0
        while (true) {
            val b = input.read(); if (b < 0) break
            head.write(b)
            if (b == 10) { nl++; if (nl == 2) break } else if (b != 13) nl = 0
            last = b
            if (head.size() > 16384) break
        }
        val text = head.toString("ISO-8859-1")
        val headers = HashMap<String, String>()
        text.lines().drop(1).forEach { l -> val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim() }
        val len = headers["content-length"]?.trim()?.toIntOrNull() ?: 0
        val body = ByteArray(len.coerceIn(0, 65536))
        var got = 0
        while (got < body.size) { val n = input.read(body, got, body.size - got); if (n < 0) break; got += n }
        val bodyText = String(body, 0, got, Charsets.ISO_8859_1)
        Drone.log(ctx, "<< $label: " + (text + bodyText).replace("\r", "").replace("\n", " | ").take(1200))
        val status = Regex("""RTSP/\d\.\d\s+(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return Reply(status, headers, bodyText)
    }

    private fun stream() {
        val s = Socket(); sock = s
        s.connect(InetSocketAddress(host, port), 3000); s.soTimeout = 6000
        Drone.log(ctx, "connected from " + s.localAddress.hostAddress)
        val input = BufferedInputStream(s.getInputStream())
        send("OPTIONS", url); read(input, "OPTIONS")
        send("DESCRIBE", url, "Accept: application/sdp\r\n")
        val d = read(input, "DESCRIBE")

        // the video part of the description, whatever order it's in
        val lines = d.body.lines().map { it.trim() }
        val mi = lines.indexOfFirst { it.startsWith("m=video") }
        val section = if (mi >= 0) lines.drop(mi).takeWhile { it === lines[mi] || !it.startsWith("m=") } else lines
        val rtpmap = section.firstOrNull { it.startsWith("a=rtpmap") } ?: ""
        val codecName = Regex("""a=rtpmap:\d+\s+([A-Za-z0-9-]+)""").find(rtpmap)?.groupValues?.get(1)?.uppercase() ?: "?"
        val payloadType = if (mi >= 0) lines[mi].split(" ").getOrNull(3)?.toIntOrNull() ?: -1 else -1
        Drone.log(ctx, "codec=$codecName payload=$payloadType")
        payload = payloadType
        val control = section.firstOrNull { it.startsWith("a=control:") }?.removePrefix("a=control:")?.trim() ?: ""
        val base = (d.headers["content-base"] ?: url).trimEnd('/')
        val track = when { control.isEmpty() || control == "*" -> base; control.startsWith("rtsp://") -> control; else -> "$base/$control" }
        val sprop = section.firstOrNull { it.contains("sprop-parameter-sets") }?.substringAfter("sprop-parameter-sets=")?.substringBefore(";")?.trim() ?: ""
        val params = sprop.split(",").filter { it.isNotEmpty() }.mapNotNull { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
        params.firstOrNull { it.isNotEmpty() && it[0].toInt() and 31 == 7 }?.let { sps = it }
        params.firstOrNull { it.isNotEmpty() && it[0].toInt() and 31 == 8 }?.let { pps = it }

        var rtp: DatagramSocket? = null
        var serverPort = 0
        if (tcp) {
            send("SETUP", track, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n")
        } else {
            var p = 50000
            while (rtp == null && p < 50200) { rtp = runCatching { DatagramSocket(p) }.getOrNull(); if (rtp == null) p += 2 }
            udp = rtp ?: error("no free UDP port")
            send("SETUP", track, "Transport: RTP/AVP;unicast;client_port=$p-${p + 1}\r\n")
        }
        val setup = read(input, "SETUP")
        session = (setup.headers["session"] ?: "").substringBefore(";").trim()
        serverPort = Regex("""server_port=(\d+)""").find(setup.headers["transport"] ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0
        send("PLAY", url, "Range: npt=0.000-\r\n")
        if (read(input, "PLAY").status != 200) error("the drone didn't start the video")

        val addr = InetAddress.getByName(host)
        if (!tcp) {
            rtp!!.soTimeout = 4000
            // some cameras wait for a packet before sending to this port
            if (serverPort > 0) repeat(2) { rtp.send(DatagramPacket(byteArrayOf(0xCE.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte()), 4, addr, serverPort)) }
            val buf = ByteArray(65536); val pkt = DatagramPacket(buf, buf.size)
            var nextKeep = System.currentTimeMillis() + 15000
            while (run) {
                try { rtp.receive(pkt) } catch (e: SocketTimeoutException) { error("no video packets over UDP") }
                handle(buf, pkt.length)
                val now = System.currentTimeMillis()
                if (now > nextKeep) { nextKeep = now + 15000; runCatching { send("OPTIONS", url) } }
            }
        } else {
            val hdr = ByteArray(3); val buf = ByteArray(65536)
            while (run) {
                val b = input.read(); if (b < 0) error("drone closed the connection")
                if (b != '$'.code) continue          // a text reply between frames
                var g = 0; while (g < 3) { val n = input.read(hdr, g, 3 - g); if (n < 0) error("closed"); g += n }
                val len = ((hdr[1].toInt() and 255) shl 8) or (hdr[2].toInt() and 255)
                g = 0; while (g < len) { val n = input.read(buf, g, len - g); if (n < 0) error("closed"); g += n }
                if (hdr[0].toInt() == 0) handle(buf, len)
            }
        }
    }

    /* ---------- RTP -> H.264 ---------- */

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private val frame = ByteArrayOutputStream()
    private var keySeen = false
    private var lastTs = 0L

    private fun handle(b: ByteArray, n: Int) {
        if (rawLeft > 0) { runCatching { raw?.write(b, 0, minOf(n, rawLeft)) }; rawLeft -= n }
        if (hexLeft > 0) { hexLeft--; Drone.log(ctx, "packet $n bytes: " + (0 until minOf(n, 28)).joinToString(" ") { "%02x".format(b[it]) }) }
        if (n < 12 || (b[0].toInt() and 0xC0) != 0x80) return
        val cc = b[0].toInt() and 15
        var off = 12 + cc * 4
        if (b[0].toInt() and 0x10 != 0 && off + 4 <= n) off += 4 + 4 * (((b[off + 2].toInt() and 255) shl 8) or (b[off + 3].toInt() and 255))
        if (off >= n) return
        val marker = b[1].toInt() and 0x80 != 0
        if ((b[1].toInt() and 127) == 26 || payload == 26) {   // RTP/JPEG
            jpeg.add(b, off, n, marker)?.let { j ->
                if (screen.show(j)) {
                    if (frames++ == 0) Drone.log(ctx, "first JPEG frame, ${j.size} bytes")
                    if (!liveSent) { liveSent = true; onSize(640, 352); onLive() }
                }
            }
            return
        }
        val ts = ((b[4].toLong() and 255) shl 24) or ((b[5].toLong() and 255) shl 16) or ((b[6].toLong() and 255) shl 8) or (b[7].toLong() and 255)
        if (ts != lastTs && frame.size() > 0) flush()
        lastTs = ts
        val type = b[off].toInt() and 31
        when (type) {
            in 1..23 -> nal(b, off, n - off)
            24 -> { var p = off + 1; while (p + 2 <= n) { val l = ((b[p].toInt() and 255) shl 8) or (b[p + 1].toInt() and 255); p += 2; if (p + l > n) break; nal(b, p, l); p += l } }
            28 -> {
                val fu = b[off + 1].toInt()
                val t = fu and 31
                if (fu and 0x80 != 0) { val h = (b[off].toInt() and 0xE0) or t; frame.write(START); frame.write(h); note(t) }
                frame.write(b, off + 2, n - off - 2)
            }
        }
        if (marker) flush()
    }

    private val START = byteArrayOf(0, 0, 0, 1)
    private fun note(t: Int) { if (t == 5) keySeen = true }
    private fun nal(b: ByteArray, o: Int, l: Int) {
        val t = b[o].toInt() and 31
        if (t == 7) sps = b.copyOfRange(o, o + l)
        if (t == 8) pps = b.copyOfRange(o, o + l)
        note(t)
        frame.write(START); frame.write(b, o, l)
    }

    private fun flush() {
        val data = frame.toByteArray(); frame.reset()
        val c = codec ?: openCodec() ?: return
        if (!keySeen) return
        val i = c.dequeueInputBuffer(20_000)
        if (i >= 0) {
            val ib = c.getInputBuffer(i)!!; ib.clear()
            if (data.size <= ib.capacity()) { ib.put(data); c.queueInputBuffer(i, 0, data.size, System.nanoTime() / 1000, 0) } else c.queueInputBuffer(i, 0, 0, 0, 0)
        }
        val info = MediaCodec.BufferInfo()
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            when {
                o >= 0 -> {
                    c.releaseOutputBuffer(o, true)
                    if (!liveSent) { liveSent = true; onLive() }
                }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat; val w = f.getInteger(MediaFormat.KEY_WIDTH); val h = f.getInteger(MediaFormat.KEY_HEIGHT)
                    Drone.log(ctx, "video ${w}x$h"); onSize(w, h)
                }
                else -> return
            }
        }
    }

    private fun openCodec(): MediaCodec? {
        val s = sps ?: return null; val p = pps ?: return null
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
        f.setByteBuffer("csd-0", ByteBuffer.wrap(START + s)); f.setByteBuffer("csd-1", ByteBuffer.wrap(START + p))
        if (android.os.Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(f, surface, null, 0); c.start()
        Drone.log(ctx, "decoder started: " + c.name)
        codec = c; return c
    }

    private fun closeConnection() {
        runCatching { if (session.isNotEmpty()) send("TEARDOWN", url) }
        runCatching { udp?.close() }; runCatching { sock?.close() }; udp = null; sock = null
    }

    private fun releaseAll() {
        closeConnection()
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
        runCatching { raw?.close() }
    }
}
