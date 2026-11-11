package com.starshine.launcher

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.runtime.produceState
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
        else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
    }
}

private val VIDEO = setOf("mp4", "m4v", "webm", "mkv", "mov", "avi", "ts", "m2ts", "mpg", "mpeg", "wmv", "flv", "3gp", "ogv")
private val PICTURE = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
private val AUDIO = setOf("mp3", "flac", "ogg", "wav", "m4a", "aac", "opus")
private val SORTS = listOf("Name", "Date", "Size", "Type")

private fun symbol(f: File) = f.extension.lowercase().let { e ->
    when {
        f.isDirectory -> "📁"
        e in VIDEO -> "🎞"
        e in PICTURE -> "🖼"
        e in AUDIO -> "🎵"
        e == "apk" -> "📦"
        else -> "📄"
    }
}

/** Folders first, as in Finder. Newest and largest come first when sorting by date or size. */
private fun sorted(list: List<File>, sort: Int): List<File> {
    val by: Comparator<File> = when (sort) {
        1 -> compareByDescending { it.lastModified() }
        2 -> compareByDescending { if (it.isDirectory) 0L else it.length() }
        3 -> compareBy { it.extension.lowercase() }
        else -> compareBy { it.name.lowercase() }
    }
    return list.sortedWith(compareBy<File> { !it.isDirectory }.then(by).thenBy { it.name.lowercase() })
}

/** Names containing the text, in this folder and below. Stops at 300 results, so a search on a big drive stays quick. */
private fun search(root: File, text: String, hidden: Boolean): List<File> {
    val out = ArrayList<File>()
    fun walk(d: File, depth: Int) {
        if (depth > 8 || out.size >= 300) return
        for (f in d.listFiles() ?: return) {
            if (out.size >= 300) return
            if (!hidden && f.name.startsWith(".")) continue
            if (f.name.contains(text, ignoreCase = true)) out.add(f)
            if (f.isDirectory) walk(f, depth + 1)
        }
    }
    walk(root, 0)
    return out
}

/** A name that's free in this folder: a pasted item never replaces what's already there. */
private fun freeName(to: File, name: String, isFile: Boolean): File {
    val first = File(to, name)
    if (!first.exists()) return first
    val dot = if (isFile) name.lastIndexOf('.').takeIf { it > 0 } else null
    val base = if (dot != null) name.substring(0, dot) else name
    val ext = if (dot != null) name.substring(dot) else ""
    var n = 1
    while (true) {
        val f = File(to, base + (if (n == 1) " copy" else " copy $n") + ext)
        if (!f.exists()) return f
        n++
    }
}

private fun validName(n: String) = n.isNotEmpty() && !n.contains('/') && n != "." && n != ".."
private val BG_USABLE = setOf("mp4", "webm", "mkv", "mov", "gif", "webp", "png", "jpg", "jpeg")

private fun human(n: Long) = when { n > 1 shl 30 -> "%.1f GB".format(n / 1073741824f); n > 1 shl 20 -> "%.1f MB".format(n / 1048576f); n > 1024 -> "${n / 1024} KB"; else -> "$n B" }

/**
 * Finder-style file explorer: sidebar of places, the folder as a list or icons, and a preview of the selected file.
 * pick != null means "choose a file for this" (a layer) and OK returns it at once.
 * Otherwise OK on a file opens its actions: play, use as background, copy, move, rename, delete, select several.
 * "This folder" gives the same actions for the open folder. A paste never replaces an existing file: it adds "copy" to the name.
 */
@Composable
fun FileExplorer(first: FocusRequester, pick: ((File) -> Unit)? = null, onUseAsBackground: (File) -> Unit, onAddToBgLayers: (File) -> Unit, onToast: (String) -> Unit) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE) }
    val base = remember { roots(ctx) }
    var pins by remember { mutableStateOf(sp.getString("fePins", "").orEmpty().split("\n").filter { it.isNotEmpty() }.map { File(it) }.filter { it.isDirectory }) }
    val places = base + pins.map { it.name to it }
    fun savePins(next: List<File>) { pins = next; sp.edit().putString("fePins", next.joinToString("\n") { it.absolutePath }).apply() }
    var dir by remember { mutableStateOf<File?>(base.first().second) }
    var selected by remember { mutableStateOf<File?>(null) }
    var preview by remember { mutableStateOf<File?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(0) }
    var icons by remember { mutableStateOf(sp.getBoolean("feIcons", false)) }
    var sort by remember { mutableStateOf(sp.getInt("feSort", 0).coerceIn(SORTS.indices)) }
    var hidden by remember { mutableStateOf(sp.getBoolean("feHidden", false)) }
    var query by remember { mutableStateOf("") }
    var marked by remember { mutableStateOf(setOf<File>()) }
    var markDelete by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<File>?>(emptyList()) }
    var clip by remember { mutableStateOf<Pair<List<File>, Boolean>?>(null) }   // files, true = move
    LaunchedEffect(dir, version, sort, hidden, query) {
        entries = withContext(Dispatchers.IO) {
            val d = dir
            when {
                d == null -> emptyList()
                d == ALL_VIDEOS -> allVideos(ctx).let { if (sort == 0) it else sorted(it, sort) }
                query.isNotEmpty() -> sorted(search(d, query, hidden), sort)
                else -> { if (d == PublicVideos.dir()) d.mkdirs(); (d.listFiles() ?: if (d.exists()) null else emptyArray<File>())?.filter { hidden || !it.name.startsWith(".") }?.let { sorted(it, sort) } }
            }
        }
    }
    fun ask(title: String, value: String, done: (String) -> Unit) {
        val box = android.widget.EditText(ctx).apply { setText(value); setSingleLine() }
        android.app.AlertDialog.Builder(ctx).setTitle(title).setView(box).setPositiveButton("OK") { _, _ -> done(box.text.toString().trim()) }.setNegativeButton("Cancel", null).show()
    }
    fun paste() {
        val (items, move) = clip ?: return
        val to = dir ?: return
        clip = null
        val what = if (items.size == 1) items[0].name else "${items.size} items"
        onToast((if (move) "Moving " else "Copying ") + what + "…")
        Thread {
            var done = 0
            var problem = ""
            for (src in items) {
                // a folder can't go inside itself, and that copy would never end
                if (to.absolutePath == src.absolutePath || to.absolutePath.startsWith(src.absolutePath + "/")) { problem = "can't go inside itself"; continue }
                if (move && src.parentFile == to) { done++; continue }
                val dst = freeName(to, src.name, src.isFile)
                val ok = runCatching { if (move && src.renameTo(dst)) true else { val c = src.copyRecursively(dst); if (c && move) src.deleteRecursively(); c } }.getOrDefault(false)
                if (ok) done++
                else { runCatching { dst.deleteRecursively() }; problem = "not enough space, or Android blocks this folder" }
            }
            onToast(if (problem.isEmpty()) (if (move) "Moved " else "Copied ") + what else "Finished $done of ${items.size}: $problem")
            version++
        }.start()
    }
    var backTo by remember { mutableStateOf("") }
    val isPlace = dir == null || places.any { it.second == dir }
    val goUp = { backTo = "row:fe:" + dir!!.absolutePath; dir = dir!!.parentFile; query = "" }
    BackHandler(enabled = selected != null || !isPlace || query.isNotEmpty() || marked.isNotEmpty()) {
        when {
            selected != null -> { backTo = "row:fe:" + selected!!.absolutePath; selected = null; confirm = false }
            marked.isNotEmpty() -> { marked = emptySet(); markDelete = false }
            query.isNotEmpty() -> query = ""
            else -> goUp()
        }
    }
    LaunchedEffect(selected, dir, query, entries == null) { delay(100); if (backTo.isNotEmpty()) { FocusMem.restoreSoon(backTo); backTo = "" } else runCatching { first.requestFocus() } }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LoopColumn(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            item { Text("PLACES", color = DIM, fontSize = 9.sp, modifier = Modifier.padding(4.dp)) }
            places.forEach { (label, f) -> item { Row_((if (f == ALL_VIDEOS) "🎞 " else if (f in pins) "★ " else "📁 ") + label, dir == f, id = "fr:${f.absolutePath}", side = "R", small = true, onFocus = {}) { dir = f; selected = null; preview = null; query = "" } } }
        }
        Column(Modifier.weight(1f)) {
            val sel = selected
            if (sel != null) {
                val ext = sel.extension.lowercase()
                val hasVlc = ctx.packageManager.getLaunchIntentForPackage(PublicVideos.VLC) != null
                val isFolder = sel.isDirectory
                val actions = buildList<Pair<String, () -> Unit>> {
                    if (isFolder) add((if (sel in pins) "☆ Remove from Places" else "★ Add to Places") to {
                        savePins(if (sel in pins) pins - sel else pins + sel); selected = null
                    })
                    if (ext in VIDEO && hasVlc) add("▶ Play in VLC" to { openWith(ctx, sel, PublicVideos.VLC, onToast) })
                    if (ext in VIDEO && !hasVlc) add("▶ Play" to { PlayerActivity.open(ctx, android.net.Uri.fromFile(sel), sel.name) })
                    if (!isFolder) add((if (ext in VIDEO) "↗ Open in another app" else "↗ Open") to { openWith(ctx, sel, null, onToast) })
                    if (ext in BG_USABLE) {
                        add("🌌 Use as background" to { onUseAsBackground(sel) })
                        add("◈ Add as a layer" to { onAddToBgLayers(sel) })
                    }
                    if (!isFolder) add((if (sel in marked) "☐ Unselect" else "☑ Select, to copy, move or delete several") to {
                        marked = if (sel in marked) marked - sel else marked + sel; markDelete = false; selected = null
                    })
                    add("📋 Copy (then Paste in a folder)" to { clip = listOf(sel) to false; selected = null; onToast("Copied. Open a folder and choose Paste") })
                    add("✂ Move (then Paste in a folder)" to { clip = listOf(sel) to true; selected = null; onToast("Open a folder and choose Paste") })
                    add("✎ Rename" to { ask("Rename", sel.name) { n ->
                        if (validName(n) && n != sel.name) {
                            val target = File(sel.parentFile, n)
                            // Android's rename would silently replace a file that has this name
                            if (target.exists()) onToast("$n already exists")
                            else if (sel.renameTo(target)) {
                                onToast("Renamed")
                                if (sel in pins) savePins(pins - sel + target)
                                if (sel == dir) dir = target
                                selected = null; version++
                            } else onToast("Couldn't rename")
                        }
                    } })
                    if (ext in VIDEO && !sel.absolutePath.contains(PublicVideos.FOLDER)) add("⇪ Copy to ${PublicVideos.FOLDER} (for VLC)" to {
                        onToast("Copying…"); Thread { val r = PublicVideos.publish(ctx, sel); onToast(if (r != null) "Saved to $r" else "Copy failed") }.start()
                    })
                    add((if (confirm) "🗑 OK again to delete for good" else "🗑 Delete") to {
                        if (!confirm) confirm = true
                        else {
                            val ok = sel.deleteRecursively()
                            onToast(if (ok) "Deleted ${sel.name}" else "Couldn't delete ${sel.name}")
                            if (ok && sel in pins) savePins(pins - sel)
                            if (ok && sel == dir) dir = sel.parentFile
                            confirm = false; selected = null; version++
                        }
                    })
                }
                Text(sel.name, color = FG, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(4.dp))
                LoopColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    actions.forEachIndexed { i, (label, act) -> item { Box(if (i == 0) Modifier.focusRequester(first) else Modifier) { Row_(label, false, id = "fa:$i", side = "L", onFocus = {}) { act() } } } }
                }
            } else {
                val shown = when {
                    dir == ALL_VIDEOS -> "All videos on this device"
                    query.isNotEmpty() -> "\"$query\" in " + dir?.name
                    else -> dir?.absolutePath ?: ""
                }
                Text(shown, color = DIM, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp))
                val list = entries
                val free = remember(dir, version) { dir?.takeIf { it != ALL_VIDEOS }?.usableSpace ?: 0L }
                if (list != null) Text((if (list.size == 1) "1 item" else "${list.size} items") + if (free > 0) " · ${human(free)} free" else "", color = DIM, fontSize = 9.sp, modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp))
                LoopColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (list == null) item { Box(Modifier.focusRequester(first)) { Row_("Android blocks this folder", false, id = "fe:deny", side = "L", onFocus = {}) { dir = places.first().second } } }
                    else {
                        item {
                            Box(if (list.isEmpty()) Modifier.focusRequester(first) else Modifier) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (!isPlace) Chip("⬆ Up", id = "fe:up") { goUp() }
                                    Chip(if (icons) "☰ List" else "▦ Icons", id = "fe:view") { icons = !icons; sp.edit().putBoolean("feIcons", icons).apply() }
                                    Chip("Sort: " + SORTS[sort], id = "fe:sort") { sort = (sort + 1) % SORTS.size; sp.edit().putInt("feSort", sort).apply() }
                                    if (dir != ALL_VIDEOS) Chip("🔍 Search", id = "fe:find") { ask("Search in this folder and below", query) { query = it } }
                                }
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (dir != ALL_VIDEOS && pick == null) Chip("＋ New folder", id = "fe:mk") {
                                    ask("New folder", "") { n ->
                                        if (validName(n)) { onToast(if (File(dir, n).mkdirs()) "Created $n" else "Couldn't create $n"); version++ }
                                    }
                                }
                                if (!isPlace && pick == null) Chip("⋯ This folder", id = "fe:this") { selected = dir }
                                Chip("Hidden: " + if (hidden) "shown" else "off", id = "fe:hid") { hidden = !hidden; sp.edit().putBoolean("feHidden", hidden).apply() }
                            }
                        }
                        val held = clip
                        if (held != null && dir != ALL_VIDEOS) item {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Chip("📋 Paste " + if (held.first.size == 1) held.first[0].name.take(26) else "${held.first.size} items", id = "fe:paste") { paste() }
                                Chip("Cancel", id = "fe:unclip") { clip = null }
                            }
                        }
                        if (marked.isNotEmpty()) item {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Chip("Copy ${marked.size}", id = "fe:mcopy") { clip = marked.toList() to false; marked = emptySet(); markDelete = false; onToast("Open a folder and choose Paste") }
                                Chip("Move ${marked.size}", id = "fe:mmove") { clip = marked.toList() to true; marked = emptySet(); markDelete = false; onToast("Open a folder and choose Paste") }
                                Chip(if (markDelete) "OK again to delete for good" else "Delete ${marked.size}", id = "fe:mdel") {
                                    if (!markDelete) markDelete = true
                                    else {
                                        val items = marked.toList()
                                        val ok = items.count { it.deleteRecursively() }
                                        onToast("Deleted $ok of ${items.size}")
                                        marked = emptySet(); markDelete = false; version++
                                    }
                                }
                                Chip("Clear", id = "fe:mclear") { marked = emptySet(); markDelete = false }
                            }
                        }
                        if (list.isEmpty()) item { Text(if (query.isNotEmpty()) "Nothing found. Back returns to the folder." else "Nothing here yet. Use PC toolkit → Send Video to Pod, or a USB drive.", color = DIM, fontSize = 10.sp, modifier = Modifier.padding(6.dp)) }
                        val open = { f: File -> if (f.isDirectory) { dir = f; preview = null; query = "" } else if (pick != null) pick(f) else selected = f }
                        if (icons) items(list.chunked(3), key = { it.first().absolutePath }) { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { f ->
                                    var focused by remember { mutableStateOf(false) }
                                    Column(Modifier.width(112.dp).remembered("row:fe:" + f.absolutePath).onFocusChanged { focused = it.isFocused; if (it.isFocused) preview = f }.focusOnHover().clickable { open(f) }
                                        .background(if (focused) CYAN.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(4.dp)) {
                                        Thumb(f, Modifier.fillMaxWidth().height(58.dp))
                                        Text((if (f in marked) "☑ " else "") + f.name, color = if (focused) FG else DIM, fontSize = 9.sp, maxLines = 1)
                                    }
                                }
                            }
                        } else itemsIndexedFiles(list) { i, f ->
                            Box(if (i == 0) Modifier.focusRequester(first) else Modifier) {
                                Row_((if (f in marked) "☑ " else "") + symbol(f) + " " + f.name, false, id = "fe:${f.absolutePath}", side = "L", small = true, onFocus = { preview = f }) { open(f) }
                            }
                        }
                    }
                }
            }
        }
        val p = selected ?: preview
        Column(Modifier.width(150.dp)) {
            if (p != null) {
                Thumb(p, Modifier.fillMaxWidth().height(90.dp))
                Text(p.name, color = FG, fontSize = 10.sp, maxLines = 3, modifier = Modifier.padding(top = 6.dp))
                val inside by produceState<List<String>?>(null, p, hidden) {
                    value = if (!p.isDirectory) null else withContext(Dispatchers.IO) {
                        p.listFiles()?.filter { hidden || !it.name.startsWith(".") }?.let { kids -> sorted(kids, 0).map { symbol(it) + " " + it.name } }
                    }
                }
                val kids = inside
                Text((if (p.isDirectory) (if (kids != null) "Folder · ${kids.size} items" else "Folder") else human(p.length())) + "\n" + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(p.lastModified())), color = DIM, fontSize = 9.sp, modifier = Modifier.padding(top = 3.dp))
                if (query.isNotEmpty()) Text(p.parent.orEmpty(), color = DIM, fontSize = 8.sp, maxLines = 3, modifier = Modifier.padding(top = 3.dp))
                // Finder's next column: what's inside the folder, without opening it
                kids?.take(9)?.forEach { Text(it, color = DIM, fontSize = 9.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp)) }
                if (kids != null && kids.size > 9) Text("and ${kids.size - 9} more", color = DIM, fontSize = 8.sp, modifier = Modifier.padding(top = 2.dp))
            } else Text("Select a file to preview it", color = DIM, fontSize = 9.sp)
        }
    }
}

/** A picture or video frame, or a simple symbol for anything else. */
@Composable
private fun Thumb(f: File, modifier: Modifier) {
    val ctx = LocalContext.current
    val ext = f.extension.lowercase()
    val loader = remember { coil.ImageLoader.Builder(ctx).components { add(coil.decode.VideoFrameDecoder.Factory()) }.build() }
    Box(modifier.background(androidx.compose.ui.graphics.Color(0xFF0B1520), androidx.compose.foundation.shape.RoundedCornerShape(6.dp)), contentAlignment = androidx.compose.ui.Alignment.Center) {
        if (!f.isDirectory && (ext in VIDEO || ext in PICTURE)) {
            coil.compose.AsyncImage(f, null, imageLoader = loader, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)))
        } else Text(symbol(f), fontSize = 26.sp)
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
