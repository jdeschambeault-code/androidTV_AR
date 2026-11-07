package com.starshine.launcher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The audio test: follows one journey and tells apart what can make sound jerk or cut.
 *  - sound delivered late (underruns, a shrinking margin), and what the system was doing at that second: CPU, memory, GC, frame rate
 *  - the sound engine restarting or losing audio focus
 *  - clicks and gaps already present in what was generated (also kept as a WAV to look at and listen to)
 * Writes report.txt and sound.wav to Android/data/com.starshine.launcher/files/audiotest. Only costs anything while a test runs.
 */
object AudioProbe {
    private const val RATE = 44100
    private const val BLOCK_MS = 1024f * 1000f / RATE
    private const val MAX_WAV_SECONDS = 300
    private const val CLICK = 6500          // a jump between two samples of 20% of full scale is a click at these frequencies

    @Volatile var active = false; private set
    /** One-line result of the last test, shown in the Meditation tab. */
    var summary by mutableStateOf("")

    private class Row(val sec: Int, val renderMax: Float, val renderAvg: Float, val writeMax: Float, val voices: Int, val underruns: Int, val marginMin: Float, val clicks: Int, val gaps: Int, val clipped: Int)

    private var startNs = 0L
    private var label = ""
    private val events = ArrayList<String>()
    private val queue = ArrayBlockingQueue<Any>(400)
    private var writer: Thread? = null
    private var dir: File? = null
    private val frames = AtomicInteger(0)
    private var dropped = 0

    // audio thread only
    private var sec = 0; private var blocks = 0; private var renderMax = 0L; private var renderSum = 0L; private var writeMax = 0L
    private var marginMin = Float.MAX_VALUE; private var underrunStart = -1; private var clicks = 0; private var gaps = 0; private var clipped = 0; private var voicesMax = 0
    private var lastL = 0; private var lastR = 0; private var hasLast = false

    // totals, for the verdict
    @Volatile private var totalUnderruns = 0; @Volatile private var totalClicks = 0; @Volatile private var totalGaps = 0; @Volatile private var totalClipped = 0
    @Volatile private var lateBlocks = 0; @Volatile private var restarts = 0; @Volatile private var focusLost = 0; @Volatile private var worstMargin = Float.MAX_VALUE

    private fun now() = (System.nanoTime() - startNs) / 1e9f

    fun start(ctx: Context, name: String) {
        if (active) return
        val d = File(ctx.getExternalFilesDir(null), "audiotest").apply { mkdirs() }
        dir = d; label = name; summary = "Audio test running…"
        synchronized(events) { events.clear() }
        queue.clear(); frames.set(0); dropped = 0
        sec = 0; blocks = 0; renderMax = 0; renderSum = 0; writeMax = 0; marginMin = Float.MAX_VALUE; underrunStart = -1; clicks = 0; gaps = 0; clipped = 0; voicesMax = 0; hasLast = false
        totalUnderruns = 0; totalClicks = 0; totalGaps = 0; totalClipped = 0; lateBlocks = 0; restarts = 0; focusLost = 0; worstMargin = Float.MAX_VALUE
        engineStarts = 0
        startNs = System.nanoTime()
        active = true
        writer = Thread({ writeLoop(d) }, "AudioProbe").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
        event("test started: $name")
    }

    fun event(s: String) {
        if (!active) return
        val line = String.format(java.util.Locale.US, "%7.2f  %s", now(), s)
        synchronized(events) { events.add(line) }
        android.util.Log.i("StarshineAudio", line)
    }
    private var engineStarts = 0
    /** The first start is normal; any later one means the sound was cut and started again. */
    fun engineStart() { if (active) { engineStarts++; if (engineStarts > 1) restarts++; event("sound engine started") } }
    fun focus(s: String) { if (active) { focusLost++; event(s) } }
    fun frame() { if (active) frames.incrementAndGet() }

    /** Called by the audio thread after each block. */
    fun block(renderNs: Long, writeNs: Long, voices: Int, pcm: ShortArray, underrunCount: Int, marginMs: Float) {
        if (!active) return
        if (underrunStart < 0) underrunStart = underrunCount
        blocks++
        if (renderNs > renderMax) renderMax = renderNs
        renderSum += renderNs
        if (writeNs > writeMax) writeMax = writeNs
        if (renderNs / 1e6f > BLOCK_MS) lateBlocks++
        if (voices > voicesMax) voicesMax = voices
        if (marginMs < marginMin) marginMin = marginMs
        if (marginMs < worstMargin) worstMargin = marginMs
        var sum = 0.0; var i = 0; val n = pcm.size
        var clickHere = false
        while (i < n) {
            val l = pcm[i].toInt(); val r = pcm[i + 1].toInt()
            if (hasLast && (kotlin.math.abs(l - lastL) > CLICK || kotlin.math.abs(r - lastR) > CLICK)) clickHere = true
            if (l >= 32000 || l <= -32000 || r >= 32000 || r <= -32000) clipped++
            sum += (l * l + r * r).toDouble()
            lastL = l; lastR = r; hasLast = true; i += 2
        }
        if (clickHere) { clicks++; totalClicks++ }
        if (voices > 0 && sum / n < 4.0) { gaps++; totalGaps++ }
        if (!queue.offer(pcm.copyOf())) dropped++
        val s = ((System.nanoTime() - startNs) / 1_000_000_000L).toInt()
        if (s != sec) {
            val under = underrunCount - underrunStart; underrunStart = underrunCount; totalUnderruns += under; totalClipped += clipped
            queue.offer(Row(sec, renderMax / 1e6f, renderSum / 1e6f / blocks.coerceAtLeast(1), writeMax / 1e6f, voicesMax, under, marginMin, clicks, gaps, clipped))
            sec = s; blocks = 0; renderMax = 0; renderSum = 0; writeMax = 0; marginMin = Float.MAX_VALUE; clicks = 0; gaps = 0; clipped = 0; voicesMax = voices
        }
    }

    private fun writeLoop(d: File) {
        val wavFile = File(d, "sound.wav")
        val csv = File(d, "seconds.csv").printWriter()
        csv.println("second,renderMaxMs,renderAvgMs,writeMaxMs,voices,underruns,marginMinMs,clicks,gaps,clippedSamples,fps,cpuPct,memAvailMb,gcCount,gcBlockMs")
        var bytes = 0L
        val max = MAX_WAV_SECONDS * RATE * 4L
        var lastCpu = cpuTicks(); var lastT = System.nanoTime()
        FileOutputStream(wavFile).use { raw ->
            raw.write(ByteArray(44))
            val out = BufferedOutputStream(raw, 65536)
            var buf = ByteArray(4096)
            while (active || queue.isNotEmpty()) {
                when (val x = queue.poll(200, TimeUnit.MILLISECONDS)) {
                    is ShortArray -> if (bytes < max) {
                        if (buf.size < x.size * 2) buf = ByteArray(x.size * 2)
                        var j = 0
                        for (v in x) { buf[j++] = v.toByte(); buf[j++] = (v.toInt() shr 8).toByte() }
                        out.write(buf, 0, j); bytes += j
                    }
                    is Row -> {
                        val cpu = cpuTicks(); val t = System.nanoTime()
                        // clock ticks are 10 ms each; 100% is one full core
                        val pct = if (t > lastT) (cpu - lastCpu) * 10_000_000.0 / (t - lastT) * 100.0 else 0.0
                        lastCpu = cpu; lastT = t
                        val gcCount = runCatching { android.os.Debug.getRuntimeStat("art.gc.gc-count") }.getOrNull() ?: ""
                        val gcBlock = runCatching { android.os.Debug.getRuntimeStat("art.gc.blocking-gc-time") }.getOrNull() ?: ""
                        csv.println(String.format(java.util.Locale.US, "%d,%.1f,%.1f,%.1f,%d,%d,%.0f,%d,%d,%d,%d,%.0f,%d,%s,%s",
                            x.sec, x.renderMax, x.renderAvg, x.writeMax, x.voices, x.underruns, x.marginMin, x.clicks, x.gaps, x.clipped, frames.getAndSet(0), pct, memAvail(), gcCount, gcBlock))
                    }
                }
            }
            out.flush()
        }
        csv.close()
        RandomAccessFile(wavFile, "rw").use { f ->
            f.seek(0)
            val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt((36 + bytes).toInt()).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(RATE).putInt(RATE * 4).putShort(4).putShort(16).put("data".toByteArray()).putInt(bytes.toInt())
            f.write(h.array())
        }
    }

    private fun cpuTicks(): Long = runCatching {
        val s = File("/proc/self/stat").readText(); val p = s.substring(s.lastIndexOf(')') + 2).split(' ')
        p[11].toLong() + p[12].toLong()
    }.getOrDefault(0L)
    private fun memAvail(): Long = runCatching {
        File("/proc/meminfo").useLines { l -> l.first { it.startsWith("MemAvailable") }.filter { it.isDigit() }.toLong() / 1024 }
    }.getOrDefault(0L)

    /** Ends the test and returns its one-line result. */
    fun finish(): String {
        if (!active) return summary
        event("test ended")
        active = false
        runCatching { writer?.join(4000) }
        val verdict = ArrayList<String>()
        if (totalUnderruns > 0) verdict.add("$totalUnderruns underruns: sound reached the output too late")
        if (restarts > 0) verdict.add("$restarts engine restarts")
        if (focusLost > 0) verdict.add("$focusLost audio focus changes")
        if (totalClicks > 0) verdict.add("$totalClicks blocks with clicks in the generated sound")
        if (totalGaps > 0) verdict.add("$totalGaps silent blocks while layers were playing")
        if (totalClipped > 0) verdict.add("$totalClipped clipped samples")
        if (lateBlocks > 0) verdict.add("$lateBlocks blocks took longer to generate than they last")
        val result = if (verdict.isEmpty()) "No problem found in the generated sound or its delivery" else verdict.joinToString(" · ")
        runCatching {
            File(dir, "report.txt").printWriter().use { w ->
                w.println("Audio test: $label"); w.println(java.util.Date().toString()); w.println("App ${BuildConfig.VERSION_NAME}, ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}"); w.println()
                w.println("RESULT: $result")
                w.println(String.format(java.util.Locale.US, "Smallest margin before the output ran dry: %.0f ms", if (worstMargin == Float.MAX_VALUE) 0f else worstMargin))
                if (dropped > 0) w.println("The test itself fell behind on $dropped blocks: those are missing from sound.wav")
                w.println(); w.println("EVENTS (seconds since the start)")
                synchronized(events) { events.forEach { w.println(it) } }
                w.println(); w.println("Second by second: seconds.csv. What was generated: sound.wav")
            }
        }
        summary = result
        return result
    }
}
