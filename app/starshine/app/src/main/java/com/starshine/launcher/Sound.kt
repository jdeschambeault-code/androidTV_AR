package com.starshine.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One Starshine sound at a time (ambient, frequency preset or mood), as its own layer beside other apps' audio:
 *  - it never takes audio focus, so it can never pause another app's music (it used to grab focus when YouTube Music went
 *    quiet for a moment between songs, which paused the song);
 *  - when another app plays (any media session playing), it pauses; if that happens while you're in another app, it
 *    stays off until you're back on Starshine's Home;
 *  - on Home it comes back as soon as nothing else plays. Choosing a sound yourself pauses the other app's music.
 */
object Sound {
    var current by mutableStateOf("")          // what is selected ("" = nothing)
    var pausedByOther by mutableStateOf(false)
    private var kind = ""                       // "ambient" | "tone" | "stack"
    private var ambientMode = "Off"
    private var tone: Tone? = null
    private var stack: AudioStack? = null
    private var stackBlend = 3f
    /** current tone for the visualizer (null when an ambient sound or nothing is playing) */
    val toneNow: Tone? get() = if (kind == "tone") tone else null
    @Volatile private var holding = false
    /** Off = the sound stops while another app is in front, and comes back with Starshine. Some people want silence there. */
    var playOutside by mutableStateOf(true); private set
    /** Starshine is behind another app. */
    @Volatile private var away = false
    /** Another app played while Starshine was behind it: silent until Starshine is in front again. */
    @Volatile private var heldUntilHome = false
    private var appCtx: Context? = null
    fun setOutside(ctx: Context, on: Boolean) { playOutside = on; ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE).edit().putBoolean("soundOutside", on).apply() }

    /** Starshine went behind another app. */
    fun leftApp() {
        away = true
        if (!playOutside && current.isNotEmpty()) { AudioProbe.event("sound off while in another app"); stopEngines(); pausedByOther = true }
    }
    /** Starshine is in front again: the sound comes back unless another app is playing. */
    fun backInApp() {
        away = false; heldUntilHome = false
        if (current.isEmpty() || holding) return
        if (othersPlaying()) pausedByOther = true else resumeEngines()
    }
    private var am: AudioManager? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Another app's media is playing (read from its media session; Starshine's own sound has none). */
    fun othersPlaying(): Boolean {
        val c = appCtx ?: return false
        return NowPlaying.controllers(c).any { it.packageName != c.packageName &&
            (it.playbackState?.state == PlaybackState.STATE_PLAYING || it.playbackState?.state == PlaybackState.STATE_BUFFERING) }
    }

    /** every second while a sound is chosen: step aside for other apps, come back when they're done */
    private val watchdog = object : Runnable {
        override fun run() {
            if (current.isNotEmpty() && !holding) {
                val others = othersPlaying()
                if (others && !pausedByOther) pauseEngines()
                if (others && away) heldUntilHome = true
                if (!others && pausedByOther && !heldUntilHome && (!away || playOutside)) resumeEngines()
            }
            if (current.isNotEmpty()) handler.postDelayed(this, 1000)
        }
    }

    fun init(ctx: Context) {
        if (am != null) return
        appCtx = ctx.applicationContext
        am = ctx.applicationContext.getSystemService(AudioManager::class.java)
        val p = ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE)
        playOutside = p.getBoolean("soundOutside", true)
        level = p.getFloat("soundLevel", 1f); gain = level * level
    }

    /**
     * Starshine's own output level (0..1), applied to every engine's AudioTrack. The pod's HDMI / DisplayPort output plays at
     * full level whatever the system volume index says (only 0 = mute is heard), so this is what makes the volume slider audible.
     */
    @Volatile var gain = 1f; private set
    var level by mutableStateOf(1f); private set
    fun setLevel(ctx: Context, v: Float) {
        level = v.coerceIn(0f, 1f); gain = level * level   // a perceptual curve: halfway sounds like half
        ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE).edit().putFloat("soundLevel", level).apply()
    }

    /**
     * The quick panel's Sound switch is off. A running journey normally restarts a mood whenever nothing plays
     * ("the sound never stops during a journey"); this tells it the silence is wanted. Cleared by any new sound choice.
     */
    var userOff by mutableStateOf(false)


    /** Android's own mute of the media stream: unlike volume steps, the pod's HDMI output honours it, for every app. */
    fun systemMuted(ctx: Context) = ctx.getSystemService(AudioManager::class.java).isStreamMute(AudioManager.STREAM_MUSIC)
    fun setSystemMuted(ctx: Context, on: Boolean) = runCatching {
        ctx.getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_MUSIC, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
    }

    private fun stopEngines() { Ambient.stop(); Tones.stop(); AudioMix.stop() }
    private fun pauseEngines() { AudioProbe.event("sound paused: another app is playing"); pausedByOther = true; stopEngines() }

    private fun resumeEngines() {
        if (holding || (away && !playOutside) || heldUntilHome) return
        pausedByOther = false
        when (kind) { "ambient" -> Ambient.apply(ambientMode, Ambient.volume); "tone" -> tone?.let { Tones.play(it) }; "stack" -> stack?.let { AudioMix.set(it, stackBlend) } }
    }

    /**
     * [explicit] = you chose this sound: another app's music is paused for it. Otherwise (a journey, the sound restored at
     * start) it waits until nothing else plays.
     */
    private fun start(label: String, explicit: Boolean) {
        current = label; handler.removeCallbacks(watchdog); handler.postDelayed(watchdog, 1000)
        if (othersPlaying()) {
            if (explicit) { appCtx?.let { c -> NowPlaying.controllers(c).filter { it.packageName != c.packageName }.forEach { runCatching { it.transportControls.pause() } } }; heldUntilHome = false; resumeEngines() }
            else pausedByOther = true
        } else resumeEngines()
    }

    fun playAmbient(ctx: Context, mode: String, vol: Float = Ambient.volume, explicit: Boolean = true) {
        init(ctx); userOff = false; Tones.stop(); AudioMix.stop(); Ambient.volume = vol
        if (mode == "Off" || mode !in Ambient.MODES) { stop(); return }   // also covers sounds removed since they were last chosen
        kind = "ambient"; ambientMode = mode; Ambient.stop(); start(mode, explicit)
    }

    /** Audio layers. If layers are already playing, this blends into the new stack instead of restarting. */
    fun playStack(ctx: Context, s: AudioStack, blendSec: Float, explicit: Boolean = true) {
        init(ctx); userOff = false; Ambient.stop(); Tones.stop()
        val wasStack = kind == "stack" && !pausedByOther && !away
        kind = "stack"; stack = s; stackBlend = blendSec
        if (wasStack) { current = s.name; AudioMix.set(s, blendSec) } else start(s.name, explicit)
    }

    fun playTone(ctx: Context, t: Tone) { init(ctx); userOff = false; Ambient.stop(); AudioMix.stop(); kind = "tone"; tone = t; Tones.stop(); start(t.name, true) }

    /** live parameter change while a tone is selected */
    fun updateTone(t: Tone) { tone = t; if (kind == "tone" && Tones.playing) Tones.update(t) }

    fun setAmbientVolume(v: Float) { Ambient.volume = v }

    /** Voice command: keep the sound silent for the whole listening session (the recognizer briefly owns the audio). */
    fun hold() { holding = true; if (current.isNotEmpty()) stopEngines() }

    /** Voice finished: resume shortly after, unless another app took over meanwhile. */
    fun release(delayMs: Long = 1000) {
        handler.postDelayed({
            holding = false
            if (current.isNotEmpty()) { if (othersPlaying()) pausedByOther = true else resumeEngines() }
        }, delayMs)
    }

    fun stop() {
        kind = ""; current = ""; pausedByOther = false; Ambient.stop(); Ambient.mode = "Off"; stopEngines()
        handler.removeCallbacks(watchdog)
    }
}
