package com.starshine.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One sound at a time (ambient OR frequency preset), with proper Android audio focus:
 * plays on the Home screen and in every app that is silent; pauses automatically when another app plays audio
 * (video, music…) and resumes when it stops.
 */
object Sound {
    var current by mutableStateOf("")          // what is selected ("" = nothing)
    var pausedByOther by mutableStateOf(false)
    private var kind = ""                       // "ambient" | "tone"
    private var ambientMode = "Off"
    private var tone: Tone? = null
    private var stack: AudioStack? = null
    private var stackBlend = 3f
    /** current tone for the visualizer (null when an ambient sound or nothing is playing) */
    val toneNow: Tone? get() = if (kind == "tone") tone else null
    @Volatile private var holding = false
    private var am: AudioManager? = null
    private var focusReq: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        AudioProbe.focus("audio focus changed: $change")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pauseEngines()
            AudioManager.AUDIOFOCUS_GAIN -> if (!holding) resumeEngines()
        }
    }

    /** every 3 s while paused: if nobody else is playing anymore, take focus back and resume */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!holding && pausedByOther && current.isNotEmpty() && am?.isMusicActive == false && requestFocus()) resumeEngines()
            if (current.isNotEmpty()) handler.postDelayed(this, 3000)
        }
    }

    fun init(ctx: Context) { if (am == null) am = ctx.applicationContext.getSystemService(AudioManager::class.java) }

    private fun requestFocus(): Boolean {
        val m = am ?: return true
        val req = focusReq ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(listener, handler).setWillPauseWhenDucked(true).build().also { focusReq = it }
        return m.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun pauseEngines() { AudioProbe.event("sound paused for another app"); pausedByOther = true; Ambient.stop(); Tones.stop(); AudioMix.stop() }

    private fun resumeEngines() {
        if (holding) return
        pausedByOther = false
        when (kind) { "ambient" -> Ambient.apply(ambientMode, Ambient.volume); "tone" -> tone?.let { Tones.play(it) }; "stack" -> stack?.let { AudioMix.set(it, stackBlend) } }
    }

    private fun start(label: String) {
        current = label; handler.removeCallbacks(watchdog); handler.postDelayed(watchdog, 3000)
        if (requestFocus()) resumeEngines() else pausedByOther = true
    }

    fun playAmbient(ctx: Context, mode: String, vol: Float = Ambient.volume) {
        init(ctx); Tones.stop(); AudioMix.stop(); Ambient.volume = vol
        if (mode == "Off" || mode !in Ambient.MODES) { stop(); return }   // also covers sounds removed since they were last chosen
        kind = "ambient"; ambientMode = mode; Ambient.stop(); start(mode)
    }

    /** Audio layers. If layers are already playing, this blends into the new stack instead of restarting. */
    fun playStack(ctx: Context, s: AudioStack, blendSec: Float) {
        init(ctx); Ambient.stop(); Tones.stop()
        val wasStack = kind == "stack" && !pausedByOther
        kind = "stack"; stack = s; stackBlend = blendSec
        if (wasStack) { current = s.name; AudioMix.set(s, blendSec) } else start(s.name)
    }

    fun playTone(ctx: Context, t: Tone) { init(ctx); Ambient.stop(); AudioMix.stop(); kind = "tone"; tone = t; Tones.stop(); start(t.name) }

    /** live parameter change while a tone is selected */
    fun updateTone(t: Tone) { tone = t; if (kind == "tone" && Tones.playing) Tones.update(t) }

    fun setAmbientVolume(v: Float) { Ambient.volume = v }

    /** Voice command: keep the sound silent for the whole listening session (the recognizer briefly owns the audio). */
    fun hold() { holding = true; if (current.isNotEmpty()) { Ambient.stop(); Tones.stop(); AudioMix.stop() } }

    /** Voice finished: resume shortly after, unless another app took over meanwhile. */
    fun release(delayMs: Long = 1000) {
        handler.postDelayed({
            holding = false
            if (current.isNotEmpty() && am?.isMusicActive == false && requestFocus()) resumeEngines() else if (current.isNotEmpty()) pausedByOther = true
        }, delayMs)
    }

    fun stop() {
        kind = ""; current = ""; pausedByOther = false; Ambient.stop(); Ambient.mode = "Off"; Tones.stop(); AudioMix.stop()
        handler.removeCallbacks(watchdog); focusReq?.let { am?.abandonAudioFocusRequest(it) }
    }
}
