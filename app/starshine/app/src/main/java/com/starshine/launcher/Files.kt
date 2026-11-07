package com.starshine.launcher

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Public video folder: every video app (VLC included) finds what's here, and it survives uninstalling Starshine. */
object PublicVideos {
    const val FOLDER = "Movies/Starshine"
    const val VLC = "org.videolan.vlc"
    fun dir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Starshine")

    /** Copies a video into Movies/Starshine through MediaStore, so it's indexed immediately. Returns the shown name, or null. */
    fun publish(ctx: Context, f: File): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, f.name); put(MediaStore.Video.Media.MIME_TYPE, mime(f))
                put(MediaStore.Video.Media.RELATIVE_PATH, "$FOLDER/")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return@runCatching null
            ctx.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
        } else {
            dir().mkdirs(); f.copyTo(File(dir(), f.name), overwrite = true)
        }
        "$FOLDER/${f.name}"
    }.getOrNull()

    fun mime(f: File) = when (f.extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; "mov" -> "video/quicktime"; "avi" -> "video/x-msvideo"
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"
        "mp3" -> "audio/mpeg"; "flac" -> "audio/flac"; "ogg" -> "audio/ogg"; "wav" -> "audio/wav"; "m4a" -> "audio/mp4"
        "pdf" -> "application/pdf"; "txt", "log" -> "text/plain"; "apk" -> "application/vnd.android.package-archive"
        else -> "*/*"
    }
}

private val VIDEO = setOf("mp4", "m4v", "webm", "mkv", "mov", "avi")
private val BG_USABLE = setOf("mp4", "webm", "mkv", "mov", "gif", "webp", "png", "jpg", "jpeg")

private fun human(n: Long) = when { n > 1 shl 30 -> "%.1f GB".format(n / 1073741824f); n > 1 shl 20 -> "%.1f MB".format(n / 1048576f); n > 1024 -> "${n / 1024} KB"; else -> "$n B" }

/**
 * File explorer. pick != null means "choose a file for this" (a Bg media node) and OK returns it at once.
 * Otherwise OK on a file opens its actions: play (VLC or any app), use as background, copy to Movies/Starshine, delete.
 */
@Composable
fun FileExplorer(first: FocusRequester, pick: ((File) -> Unit)? = null, onUseAsBackground: (File) -> Unit, onAddToBgLayers: (File) -> Unit, onToast: (String) -> Unit) {
    val ctx = LocalContext.current
    var dir by remember { mutableStateOf<File?>(null) }
    var selected by remember { mutableStateOf<File?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(0) }
    var entries by remember { mutableStateOf<List<File>?>(emptyList()) }
    val hasAll = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
    LaunchedEffect(dir, version) {
        entries = withContext(Dispatchers.IO) {
            val d = dir
            when {
                d == null -> emptyList()
                d == ALL_VIDEOS -> allVideos(ctx)
                else -> { if (d == PublicVideos.dir()) d.mkdirs(); (d.listFiles() ?: if (d.exists()) null else emptyArray<File>())?.filter { !it.name.startsWith(".") }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) }
            }
        }
    }
    LaunchedEffect(dir, selected, entries == null) { delay(80); runCatching { first.requestFocus() } }
    BackHandler(enabled = selected != null || dir != null) {
        when {
            selected != null -> { selected = null; confirm = false }
            else -> {
                val d = dir!!; val root = Environment.getExternalStorageDirectory()
                val isShortcut = d == ALL_VIDEOS || roots(ctx).any { it.second == d } || d == root
                dir = if (isShortcut) null else d.parentFile
            }
        }
    }
    @Composable fun rowFirst(i: Int, content: @Composable () -> Unit) { if (i == 0) Box(Modifier.focusRequester(first)) { content() } else content() }
    val sel = selected
    when {
        sel != null -> LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            item { Text(sel.name + "  ·  " + human(sel.length()), color = FG, fontSize = 12.sp, modifier = Modifier.padding(4.dp)) }
            val ext = sel.extension.lowercase()
            val hasVlc = ctx.packageManager.getLaunchIntentForPackage(PublicVideos.VLC) != null
            val actions = buildList<Pair<String, () -> Unit>> {
                if (ext in VIDEO && hasVlc) add("▶ Play in VLC" to { openWith(ctx, sel, PublicVideos.VLC, onToast) })
                add((if (ext in VIDEO) "▶ Play in another app" else "↗ Open") to { openWith(ctx, sel, null, onToast) })
                if (ext in BG_USABLE) {
                    add("🌌 Use as background (full screen)" to { onUseAsBackground(sel) })
                    add("◈ Add as a layer in the current Bg scene" to { onAddToBgLayers(sel) })
                }
                if (ext in VIDEO && !sel.absolutePath.contains(PublicVideos.FOLDER)) add("⇪ Copy to ${PublicVideos.FOLDER} (for VLC)" to {
                    onToast("Copying…")
                    Thread { val r = PublicVideos.publish(ctx, sel); onToast(if (r != null) "Saved to $r" else "Copy failed") }.start()
                })
                add((if (confirm) "🗑 OK again to delete for good" else "🗑 Delete") to {
                    if (!confirm) confirm = true
                    else { val ok = sel.delete(); onToast(if (ok) "Deleted ${sel.name}" else "Couldn't delete ${sel.name}"); confirm = false; selected = null; version++ }
                })
            }
            actions.forEachIndexed { i, (label, act) -> item { rowFirst(i) { Row_(label, false, id = "fa:$i", onFocus = {}) { act() } } } }
        }
        dir == null -> LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val rs = roots(ctx)
            rs.forEachIndexed { i, (label, f) -> item { rowFirst(i) { Row_((if (f == ALL_VIDEOS) "🎞 " else "📁 ") + label, false, id = "fr:$label", onFocus = {}) { dir = f } } } }
            if (!hasAll) item {
                Row_("Allow access to all files  ›", false, id = "fr:allow", onFocus = {}) {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { onToast("Run Build & Install from the PC toolkit to grant it") }
                }
            }
        }
        else -> Column {
            Text(dir!!.absolutePath, color = DIM, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(4.dp))
            val list = entries
            when {
                list == null -> Box(Modifier.focusRequester(first)) { Row_("Android blocks this folder · OK = go back", false, id = "fe:deny", onFocus = {}) { dir = null } }
                list.isEmpty() -> Column {
                    Box(Modifier.focusRequester(first)) { Row_("Nothing here yet · OK = go back", false, id = "fe:empty", onFocus = {}) { dir = null } }
                    Text("To add videos: PC toolkit → Send Video to Pod, or a USB drive. Exports from Bg also land in Starshine videos.", color = DIM, fontSize = 10.sp, modifier = Modifier.padding(4.dp))
                }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexedFiles(list) { i, f ->
                        rowFirst(i) {
                            Row_((if (f.isDirectory) "📁 " else if (f.extension.lowercase() in VIDEO) "🎞 " else "📄 ") + f.name + if (f.isDirectory) "" else "   ·  " + human(f.length()),
                                false, id = "fe:${f.absolutePath}", onFocus = {}) {
                                when {
                                    f.isDirectory -> dir = f
                                    pick != null -> pick(f)
                                    else -> selected = f
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedFiles(list: List<File>, content: @Composable (Int, File) -> Unit) {
    items(list.size, key = { list[it].absolutePath }) { i -> content(i, list[i]) }
}


private val ALL_VIDEOS = File("/@all-videos")

/** Every video Starshine can see: shared storage, USB drives and its own folder, newest first. */
private fun allVideos(ctx: Context): List<File> {
    val out = ArrayList<File>()
    fun walk(d: File, depth: Int) {
        if (depth > 6 || out.size > 400) return
        for (f in d.listFiles() ?: return) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) { if (!(f.name == "Android" && depth <= 1)) walk(f, depth + 1) }
            else if (f.extension.lowercase() in VIDEO) out.add(f)
        }
    }
    walk(Environment.getExternalStorageDirectory(), 0)
    File("/storage").listFiles()?.filter { it.name != "emulated" && it.name != "self" }?.forEach { walk(it, 0) }
    ctx.getExternalFilesDir("backgrounds")?.let { walk(it, 5) }
    return out.distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }
}

private fun roots(ctx: Context): List<Pair<String, File>> {
    val pub = { d: String -> Environment.getExternalStoragePublicDirectory(d) }
    val usb = File("/storage").listFiles()?.filter { it.name != "emulated" && it.name != "self" }?.map { "USB / card " + it.name to it } ?: emptyList()
    return listOf("All videos on this pod" to ALL_VIDEOS, "Starshine videos" to PublicVideos.dir(), "Starshine backgrounds" to (ctx.getExternalFilesDir("backgrounds") ?: ctx.filesDir)) + usb + listOf(
        "Movies" to pub(Environment.DIRECTORY_MOVIES), "Downloads" to pub(Environment.DIRECTORY_DOWNLOADS),
        "Pictures" to pub(Environment.DIRECTORY_PICTURES), "Music" to pub(Environment.DIRECTORY_MUSIC),
        "All storage" to Environment.getExternalStorageDirectory()
    )
}

private fun openWith(ctx: Context, f: File, pkg: String?, onToast: (String) -> Unit) {
    runCatching {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, PublicVideos.mime(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) i.setPackage(pkg)
        ctx.startActivity(i)
    }.onFailure { onToast("No app can open ${f.name}") }
}
