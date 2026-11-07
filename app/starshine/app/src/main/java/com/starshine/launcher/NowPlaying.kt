package com.starshine.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService

/** Empty listener: its only job is to let Starshine read active media sessions (granted by build.js). */
class MediaListener : NotificationListenerService()

data class Playing(val pkg: String, val title: String, val artist: String, val art: Bitmap?, val playing: Boolean, val controller: MediaController)

object NowPlaying {
    fun controllers(ctx: Context): List<MediaController> = runCatching {
        ctx.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(ctx, MediaListener::class.java))
    }.getOrDefault(emptyList())

    fun current(ctx: Context): Playing? {
        val list = controllers(ctx)
        val c = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull() ?: return null
        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        return Playing(c.packageName, title, artist, art, c.playbackState?.state == PlaybackState.STATE_PLAYING, c)
    }

    fun toggle(p: Playing) = runCatching { if (p.playing) p.controller.transportControls.pause() else p.controller.transportControls.play() }
    fun next(p: Playing) = runCatching { p.controller.transportControls.skipToNext() }
    fun pauseAll(ctx: Context) = controllers(ctx).forEach { runCatching { it.transportControls.pause() } }
}
