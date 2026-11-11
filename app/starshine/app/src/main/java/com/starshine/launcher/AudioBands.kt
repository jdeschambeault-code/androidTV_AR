package com.starshine.launcher

import android.media.audiofx.Visualizer
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * What the sound is doing, split into bands for the Bg layers: Voice, Level, Bass, Mid, High, Beat, Swell (all 0..1).
 * Reads the device's mixed output (so it follows Starshine's own moods, and music or video from other apps where the
 * system allows it). If that capture isn't available it falls back to the parameters of the playing mood or frequency.
 * Beat is limited to 2 per second (photosensitivity-safe, same rule as the line visualizers).
 */
object AudioBands {
    private const val N = 9
    /** Set by the audio engine while a tone plays: 0 = nothing. */
    @Volatile var toneBeat = 0f
    @Volatile var toneCarrier = 0f
    private val raw = FloatArray(N)
    private val smooth = FloatArray(N)
    private val peak = FloatArray(N) { 0.05f }
    private var viz: Visualizer? = null
    private var users = 0
    @Volatile private var lastCapture = 0L
    private var lastBeat = 0L
    private var avg = 0f
    private var clock = 0f
    @Volatile private var capturedLevel = 0f

    @Synchronized fun start() {
        users++
        if (viz != null) return
        AudioProbe.event("sound analyser starting")
        viz = runCatching {
            Visualizer(0).apply {
                captureSize = 1024.coerceAtMost(Visualizer.getCaptureSizeRange()[1])
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, rate: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) { if (fft != null) analyse(fft, rate) }
                }, Visualizer.getMaxCaptureRate(), false, true)
                enabled = true
            }
        }.onFailure { android.util.Log.w("StarshineBg", "audio capture unavailable: ${it.message}") }.getOrNull()
    }

    @Synchronized fun stop() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) { if (viz != null) AudioProbe.event("sound analyser stopping"); runCatching { viz?.enabled = false; viz?.release() }; viz = null }
    }

    private fun analyse(fft: ByteArray, rateMilli: Int) {
        val bins = fft.size / 2
        val hzPerBin = rateMilli / 1000f / fft.size
        var bass = 0f; var mid = 0f; var voice = 0f; var high = 0f; var total = 0f
        for (i in 1 until bins) {
            val m = hypot(fft[2 * i].toFloat(), fft[2 * i + 1].toFloat()) / 128f
            val hz = i * hzPerBin
            total += m
            if (hz < 250f) bass += m
            if (hz in 250f..2000f) mid += m
            if (hz in 300f..3000f) voice += m
            if (hz > 3000f) high += m
        }
        capturedLevel = total
        if (total > 0.02f) lastCapture = SystemClock.uptimeMillis()
        synchronized(raw) { raw[0] = voice; raw[1] = total; raw[2] = bass; raw[3] = mid; raw[4] = high }
    }

    private fun synthetic() {
        val tone = Sound.toneNow
        if (Sound.current.isEmpty() || Sound.pausedByOther) { synchronized(raw) { raw.fill(0f) }; return }
        val rate = if (tone != null) (if (tone.beat > 0f) tone.beat else 1f).coerceIn(0.1f, 2f) else 0.17f
        val p = 0.5f + 0.5f * sin(2f * PI.toFloat() * rate * clock)
        val q = 0.5f + 0.5f * sin(2f * PI.toFloat() * rate * 0.37f * clock + 1f)
        synchronized(raw) { raw[0] = p * 0.6f + q * 0.4f; raw[1] = p; raw[2] = q; raw[3] = p * q; raw[4] = 1f - p }
    }

    /** Call once per frame from the render thread. */
    fun tick(dt: Float) {
        clock += dt
        if (SystemClock.uptimeMillis() - lastCapture > 1500L) synthetic()
        val attack = (dt * 18f).coerceAtMost(1f); val release = (dt * 3.5f).coerceAtMost(1f)
        synchronized(raw) {
            for (i in 0..4) {
                // automatic gain: each band is measured against its own recent peak, so quiet and loud material react alike
                peak[i] = maxOf(peak[i] * (1f - dt * 0.12f).coerceAtLeast(0.5f), raw[i], 0.05f)
                val x = (raw[i] / peak[i]).coerceIn(0f, 1f)
                smooth[i] += (x - smooth[i]) * if (x > smooth[i]) attack else release
            }
        }
        val level = smooth[1]
        avg += (level - avg) * (dt * 0.6f).coerceAtMost(1f)
        val now = SystemClock.uptimeMillis()
        if (level > avg * 1.25f + 0.08f && now - lastBeat > 500L) { lastBeat = now; smooth[5] = 1f }
        smooth[5] = (smooth[5] - dt * 2.8f).coerceAtLeast(0f)
        smooth[6] += (level - smooth[6]) * (dt * 0.5f).coerceAtMost(1f)
        // frequency drives the picture directly. On-screen pulsing stays at 2 per second or less, even for a 40 Hz beat
        val tone = Sound.toneNow
        val beat = if (toneBeat > 0f) toneBeat else tone?.beat ?: 0f
        val carrier = if (toneCarrier > 0f) toneCarrier else tone?.carrier ?: 0f
        val active = Sound.current.isNotEmpty() && !Sound.pausedByOther
        smooth[7] = if (active && beat > 0f) 0.5f + 0.5f * sin(2f * PI.toFloat() * beat.coerceIn(0.05f, 2f) * clock) else 0f
        smooth[8] = if (active && carrier > 0f) ((kotlin.math.ln(carrier.coerceIn(30f, 3000f)) - 3.4f) / 4.6f).coerceIn(0f, 1f) else 0f
    }

    fun band(i: Int) = smooth[i.coerceIn(0, N - 1)]
}
