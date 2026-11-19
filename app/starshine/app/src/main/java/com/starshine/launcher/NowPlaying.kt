package com.starshine.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Empty listener: its only job is to let Starshine read active media sessions (granted by build.js). */
class MediaListener : NotificationListenerService()

data class Playing(val pkg: String, val title: String, val artist: String, val art: Bitmap?, val playing: Boolean, val controller: MediaController)

object NowPlaying {
    fun controllers(ctx: Context): List<MediaController> = runCatching {
        ctx.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(ctx, MediaListener::class.java))
    }.getOrDefault(emptyList())

    /**
     * What is playing: a playing session first, otherwise the chosen player's own (paused) one. Not any other paused
     * session: that kept YouTube Music on the controls after switching to Spotify.
     */
    fun current(ctx: Context): Playing? {
        val list = controllers(ctx).filter { it.packageName != ctx.packageName }
        val c = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull { MusicPlayer.owns(MusicPlayer.pkg, it.packageName) } ?: return null
        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        return Playing(c.packageName, title, artist, art, c.playbackState?.state == PlaybackState.STATE_PLAYING, c)
    }

    fun toggle(p: Playing) = runCatching { if (p.playing) p.controller.transportControls.pause() else p.controller.transportControls.play() }
    fun next(p: Playing) = runCatching { p.controller.transportControls.skipToNext() }
    fun previous(p: Playing) = runCatching { p.controller.transportControls.skipToPrevious() }
    fun pauseAll(ctx: Context) = controllers(ctx).forEach { runCatching { it.transportControls.pause() } }
}

/**
 * The music player Starshine offers when nothing is playing: YouTube Music by default, or another installed one.
 * Whatever app actually plays is still shown and controlled (Android's media sessions, read through MediaListener).
 */
object MusicPlayer {
    /** Package, name. TV builds first: Android TV boxes get those from the Play Store. */
    val KNOWN = listOf(
        "com.google.android.apps.youtube.music" to "YouTube Music", "com.google.android.youtube.tvmusic" to "YouTube Music",   // phone build first: with Premium it plays in the background
        // SmartTube: a free YouTube client for TV that can keep playing behind other apps (its Settings → Player → Background playback)
        "org.smarttube.stable" to "SmartTube (YouTube, background)", "org.smarttube.beta" to "SmartTube (YouTube, background)",
        "com.teamsmart.videomanager.tv" to "SmartTube (YouTube, background)",
        "com.spotify.tv.android" to "Spotify", "com.spotify.music" to "Spotify",
        "deezer.android.tv" to "Deezer", "deezer.android.app" to "Deezer",
        "com.amazon.music.tv" to "Amazon Music", "com.amazon.mp3" to "Amazon Music",
        "com.aspiro.tidal" to "Tidal", "com.apple.android.music" to "Apple Music", "com.soundcloud.android" to "SoundCloud"
    )
    var pkg by androidx.compose.runtime.mutableStateOf(""); private set
    /** The player block in Quick settings. */
    var inQuick by androidx.compose.runtime.mutableStateOf(true); private set
    /** A tall player card on the left edge of the Home screen. */
    var edge by androidx.compose.runtime.mutableStateOf(false); private set

    private fun sp(ctx: Context) = ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE)
    private fun launchIntent(ctx: Context, p: String) = ctx.packageManager.getLeanbackLaunchIntentForPackage(p) ?: ctx.packageManager.getLaunchIntentForPackage(p)
    /** The known players installed here, one per name. */
    fun installed(ctx: Context) = KNOWN.filter { launchIntent(ctx, it.first) != null }.distinctBy { it.second }
    fun name(p: String = pkg) = KNOWN.firstOrNull { it.first == p }?.second ?: p.substringAfterLast('.')

    fun load(ctx: Context) {
        val s = sp(ctx); inQuick = s.getBoolean("playerQuick", true); edge = s.getBoolean("playerEdge", false)
        val inst = installed(ctx)
        pkg = s.getString("playerPkg", null)?.takeIf { p -> inst.any { it.first == p } } ?: inst.firstOrNull()?.first ?: KNOWN.first().first
    }
    /**
     * The app whose media session a player plays through. YouTube Music for TV (…youtube.tvmusic) is only a launcher: it
     * opens the YouTube TV app in music mode, so its session belongs to com.google.android.youtube.tv.
     */
    fun owns(player: String, session: String) = session == player ||
        (player == "com.google.android.youtube.tvmusic" && session == "com.google.android.youtube.tv")

    /** Test hook and Settings: use this player. */
    fun choose(ctx: Context, p: String) { pkg = p; sp(ctx).edit().putString("playerPkg", p).apply() }
    private fun log(s: String) = android.util.Log.i("StarshinePlayer", s)
    fun setQuick(ctx: Context, v: Boolean) { inQuick = v; sp(ctx).edit().putBoolean("playerQuick", v).apply() }
    fun setEdge(ctx: Context, v: Boolean) { edge = v; sp(ctx).edit().putBoolean("playerEdge", v).apply() }
    /** Next installed player (⇄). */
    fun cycle(ctx: Context, d: Int = 1) {
        val inst = installed(ctx); if (inst.isEmpty()) return
        val i = inst.indexOfFirst { it.first == pkg }
        pkg = inst[((i + d) % inst.size + inst.size) % inst.size].first; sp(ctx).edit().putString("playerPkg", pkg).apply()
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var browser: android.media.browse.MediaBrowser? = null
    private fun toast(ctx: Context, s: String) = android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_LONG).show()

    /**
     * ▶ with nothing playing, without taking over the screen:
     *  1. the player's own session, if it has one: play;
     *  2. otherwise its media browser service (what Android Auto and the Assistant use to start music in the background);
     *  3. only if neither answers: open the app, press play once its session appears, and bring Starshine back.
     */
    fun play(ctx: Context) {
        val p = pkg
        if (launchIntent(ctx, p) == null) { toast(ctx, "${name()} isn't installed (Apps → Install Recommended Apps)"); return }
        NowPlaying.controllers(ctx).firstOrNull { owns(p, it.packageName) }?.let { c -> log("play $p: its session"); runCatching { c.transportControls.play() }; return }
        val svc = ctx.packageManager.queryIntentServices(android.content.Intent("android.media.browse.MediaBrowserService").setPackage(p), 0)
            .firstOrNull()?.serviceInfo?.let { android.content.ComponentName(it.packageName, it.name) }
        log("play $p: browser service " + (svc?.className ?: "none"))
        if (svc == null) { openThenPlay(ctx, p); return }
        browser?.disconnect()
        var done = false
        val b = android.media.browse.MediaBrowser(ctx, svc, object : android.media.browse.MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                done = true; log("play $p: browser connected")
                val b = browser ?: return
                runCatching { android.media.session.MediaController(ctx, b.sessionToken).transportControls.play() }
                    .onFailure { openThenPlay(ctx, p) }
            }
            override fun onConnectionFailed() { log("play $p: browser refused"); if (!done) { done = true; openThenPlay(ctx, p) } }
            override fun onConnectionSuspended() {}
        }, null)
        browser = b
        runCatching { b.connect() }.onFailure { openThenPlay(ctx, p) }
        // some players only accept their own clients: no answer in 3 s means the fallback
        main.postDelayed({ if (!done) { done = true; b.disconnect(); openThenPlay(ctx, p) } }, 3000)
    }

    /**
     * Test: can Starshine read this player's library (playlists, mixes) itself? Connects to its media browser service and logs
     * the top level and one level under each folder (tag StarshinePlayer). Many players only answer approved apps.
     */
    fun probeBrowse(ctx: Context, p: String) {
        val svc = ctx.packageManager.queryIntentServices(android.content.Intent("android.media.browse.MediaBrowserService").setPackage(p), 0)
            .firstOrNull()?.serviceInfo?.let { android.content.ComponentName(it.packageName, it.name) }
        log("browse $p: service " + (svc?.className ?: "none")); if (svc == null) return
        var b: android.media.browse.MediaBrowser? = null
        fun list(id: String, depth: Int) {
            b?.subscribe(id, object : android.media.browse.MediaBrowser.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, children: MutableList<android.media.browse.MediaBrowser.MediaItem>) {
                    log("browse $p: '$parentId' has ${children.size} items")
                    children.take(12).forEach { c ->
                        log("browse $p:   " + "  ".repeat(depth) + (if (c.isBrowsable) "[folder] " else "") + (if (c.isPlayable) "[play] " else "") + c.description.title)
                        if (depth == 0 && c.isBrowsable) list(c.mediaId ?: return@forEach, 1)
                    }
                }
                override fun onError(parentId: String) { log("browse $p: '$parentId' refused") }
            })
        }
        b = android.media.browse.MediaBrowser(ctx, svc, object : android.media.browse.MediaBrowser.ConnectionCallback() {
            override fun onConnected() { log("browse $p: connected, root '${b?.root}'"); list(b?.root ?: return, 0) }
            override fun onConnectionFailed() { log("browse $p: connection refused") }
        }, null)
        runCatching { b.connect() }.onFailure { log("browse $p: " + it.message) }
        main.postDelayed({ log("browse $p: done"); runCatching { b.disconnect() } }, 15000)
    }

    /** The fallback: the app opens, play is pressed once its session exists, then Starshine comes back in front. */
    private fun openThenPlay(ctx: Context, p: String) {
        val i = launchIntent(ctx, p) ?: return
        log("play $p: opening the app from " + Throwable().stackTrace.drop(1).take(4).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }); toast(ctx, "Starting ${name(p)}…")
        runCatching { ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        var tries = 0
        val poll = object : Runnable {
            override fun run() {
                val c = NowPlaying.controllers(ctx).firstOrNull { owns(p, it.packageName) }
                if (c != null) {
                    log("play $p: session appeared, play, back to Starshine"); runCatching { c.transportControls.play() }
                    main.postDelayed({
                        ctx.startActivity(android.content.Intent(ctx, MainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    }, 800)
                } else if (++tries < 16) main.postDelayed(this, 500)
            }
        }
        main.postDelayed(poll, 1500)
    }
}
