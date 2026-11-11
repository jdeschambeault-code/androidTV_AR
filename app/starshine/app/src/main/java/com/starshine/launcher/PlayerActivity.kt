package com.starshine.launcher

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager
import android.app.Activity
import android.app.ActivityOptions
import android.graphics.Color as AndroidColor
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Starshine's video player: local files, network streams and "open with" from other apps.
 * OK = pause · ← → = 10 s · ↑ ↓ = 1 min · Menu = next subtitles · Search or * = next audio track · Back = leave.
 * Protected (DRM) video isn't supported.
 */
class PlayerActivity : ComponentActivity() {
    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val uri = intent?.data
        if (uri == null) { finish(); return }
        val title = intent.getStringExtra("title") ?: uri.lastPathSegment ?: uri.toString()
        val player = ExoPlayer.Builder(this).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
        player.setMediaItem(MediaItem.fromUri(uri)); player.prepare(); player.playWhenReady = true
        setContent {
            var pos by remember { mutableLongStateOf(0L) }
            var dur by remember { mutableLongStateOf(0L) }
            var note by remember { mutableStateOf("") }
            var shownUntil by remember { mutableLongStateOf(System.currentTimeMillis() + 4000) }
            var error by remember { mutableStateOf("") }
            val focus = remember { FocusRequester() }
            fun show(t: String = "") { if (t.isNotEmpty()) note = t; shownUntil = System.currentTimeMillis() + 3500 }
            fun nextTrack(type: Int, label: String) {
                val groups = player.currentTracks.groups.filter { it.type == type }
                val all = groups.flatMap { g -> (0 until g.length).map { g to it } }
                if (all.isEmpty()) { show("No $label"); return }
                val cur = all.indexOfFirst { (g, i) -> g.isTrackSelected(i) }
                val b = player.trackSelectionParameters.buildUpon()
                if (type == C.TRACK_TYPE_TEXT && cur == all.lastIndex) { b.setTrackTypeDisabled(type, true); show("$label off") }
                else {
                    val (g, i) = all[(cur + 1) % all.size]
                    b.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                    show(label + ": " + (g.getTrackFormat(i).language ?: g.getTrackFormat(i).label ?: "track ${cur + 2}"))
                }
                player.trackSelectionParameters = b.build()
            }
            DisposableEffect(Unit) {
                val l = object : Player.Listener {
                    override fun onPlayerError(e: PlaybackException) { error = "Can't play this: " + e.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase(Locale.US) }
                }
                player.addListener(l); onDispose { player.removeListener(l) }
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() }; while (true) { pos = player.currentPosition; dur = player.duration.coerceAtLeast(0L); delay(500) } }
            Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> { if (e.nativeKeyEvent.repeatCount == 0) { if (player.isPlaying) player.pause() else player.play(); show() }; true }
                    Key.DirectionLeft -> { player.seekBack(); show(); true }
                    Key.DirectionRight -> { player.seekForward(); show(); true }
                    Key.DirectionUp -> { player.seekTo(player.currentPosition + 60_000); show(); true }
                    Key.DirectionDown -> { player.seekTo((player.currentPosition - 60_000).coerceAtLeast(0)); show(); true }
                    Key.Menu -> { nextTrack(C.TRACK_TYPE_TEXT, "Subtitles"); true }
                    Key.Search, Key.VoiceAssist -> { nextTrack(C.TRACK_TYPE_AUDIO, "Audio"); true }
                    else -> false
                }
            }.focusable()) {
                AndroidView(factory = { c ->
                    PlayerView(c).apply {
                        useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; isFocusable = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS; setShutterBackgroundColor(AndroidColor.BLACK); this.player = player
                        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                }, modifier = Modifier.fillMaxSize())
                if (error.isNotEmpty()) Text(error, color = FG, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
                if (System.currentTimeMillis() < shownUntil || pos == 0L) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 30.dp, vertical = 10.dp)) {
                    Text(title, color = FG, fontSize = 13.sp, maxLines = 1)
                    Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.2f))) {
                        if (dur > 0) Box(Modifier.fillMaxWidth((pos.toFloat() / dur).coerceIn(0f, 1f)).height(3.dp).background(CYAN))
                    }
                    Text(clock(pos) + " / " + clock(dur) + "    OK pause · ←→ 10 s · ↑↓ 1 min · Menu subtitles · Search audio" + if (note.isNotEmpty()) "    $note" else "", color = DIM, fontSize = 10.sp, maxLines = 1)
                }
            }
            DisposableEffect(Unit) { onDispose { player.release() } }
        }
    }
    private fun clock(ms: Long): String { val s = ms / 1000; return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60) }

    companion object {
        private const val VLC = "org.videolan.vlc"

        /** Plays in VLC when it's installed; Starshine's own player is only the fallback. */
        fun play(ctx: Context, uri: Uri, title: String? = null) {
            if (ctx.packageManager.getLaunchIntentForPackage(VLC) != null) {
                val ok = runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setPackage(VLC).setDataAndType(uri, "video/*").putExtra("title", title)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }.isSuccess
                if (ok) return
            }
            open(ctx, uri, title)
        }

        fun open(ctx: Context, uri: Uri, title: String? = null) {
            runCatching { ctx.startActivity(Intent(ctx, PlayerActivity::class.java).setData(uri).putExtra("title", title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        }
    }
}

/**
 * Phone mode: when Starshine runs on an external display (glasses), a black window on the phone's own screen at lowest
 * brightness. Android doesn't let an app power the panel off, but on OLED phones black pixels are unlit. Any touch ends it.
 */
class BlackoutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 0.01f }
        window.decorView.setBackgroundColor(AndroidColor.BLACK)
        window.decorView.systemUiVisibility = 0x1006   // immersive, hide system bars
        window.decorView.setOnClickListener { finish() }
    }
    companion object {
        /** True if a second display exists and this window is on it, so the phone's own screen is free to black out. */
        fun canRun(a: Activity): Boolean {
            val dm = a.getSystemService(DisplayManager::class.java)
            val here = if (Build.VERSION.SDK_INT >= 30) a.display?.displayId else null
            return dm.displays.size > 1 && here != null && here != Display.DEFAULT_DISPLAY
        }
        fun start(a: Activity) {
            val o = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            runCatching { a.startActivity(Intent(a, BlackoutActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), o.toBundle()) }
        }
    }
}
