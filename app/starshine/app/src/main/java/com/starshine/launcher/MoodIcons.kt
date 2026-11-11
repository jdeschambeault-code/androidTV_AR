package com.starshine.launcher

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mood icons. By default a mood gets a symbol that fits its title ("Rain sanctuary" → rain, "Himalayan temple" → temple).
 * You can pick another symbol, use an icon found online (Iconify's free icon sets, fetched as vector paths), or your own picture.
 * Stored per mood name: "sym:<symbol>", "net:<json of paths>" or "file:<path>".
 */
object MoodIcons {
    var chosen by mutableStateOf(mapOf<String, String>()); private set
    private var loaded = false
    private fun sp(ctx: Context) = ctx.getSharedPreferences("starshine_med", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching { sp(ctx).getString("moodIcons", null)?.let { s -> val o = JSONObject(s); chosen = o.keys().asSequence().associateWith { o.getString(it) } } }
    }
    private fun save(ctx: Context) = sp(ctx).edit().putString("moodIcons", JSONObject(chosen).toString()).apply()
    fun set(ctx: Context, mood: String, icon: String?) { chosen = if (icon == null) chosen - mood else chosen + (mood to icon); save(ctx) }
    fun renamed(ctx: Context, old: String, new: String) { chosen[old]?.let { chosen = chosen - old + (new to it); save(ctx) } }

    val SYMBOLS = listOf("Rain", "Ocean", "Bowl", "Bell", "Temple", "Mountain", "Moon", "Sun", "Fire", "Drum", "Strings", "Keys", "Didgeridoo",
        "Zen", "Lotus", "Wave", "Eye", "Leaf", "Star", "Gong", "Chimes", "Heart", "Wind", "Crystal", "Handpan", "Noise", "Feather", "Spiral")

    private val WORDS = listOf(
        "Rain" to listOf("rain", "storm", "drizzle"), "Ocean" to listOf("ocean", "sea", "wave", "surf", "tide", "beach", "island"),
        "Fire" to listOf("fire", "flame", "candle", "ember"), "Bowl" to listOf("bowl", "bath"), "Bell" to listOf("bell"),
        "Temple" to listOf("temple", "himalaya", "monk", "pagoda", "shrine", "tibet"), "Mountain" to listOf("mountain", "peak", "everest", "valley"),
        "Moon" to listOf("sleep", "night", "moon", "delta", "dream", "lullaby"), "Sun" to listOf("sun", "morning", "dawn", "solar", "light"),
        "Drum" to listOf("drum", "beat of", "tribal", "shaman"), "Strings" to listOf("tanpura", "sitar", "string", "guitar", "harp", "evening"),
        "Keys" to listOf("kalimba", "thumb", "piano", "xylophone", "gamelan", "marimba", "village"), "Didgeridoo" to listOf("didgeridoo", "outback", "dreaming"),
        "Zen" to listOf("zen", "garden", "stone"), "Eye" to listOf("focus", "eye", "vision", "third", "insight"), "Leaf" to listOf("forest", "leaf", "tree", "nature", "green"),
        "Star" to listOf("star", "cosmic", "space", "galaxy", "astral"), "Gong" to listOf("gong"), "Chimes" to listOf("chime"),
        "Heart" to listOf("love", "heart", "kind", "compassion"), "Wind" to listOf("wind", "air", "breath", "breeze"), "Crystal" to listOf("crystal", "quartz", "clear"),
        "Handpan" to listOf("handpan", "hang", "steel"), "Noise" to listOf("noise", "static", "white", "pink", "brown"), "Feather" to listOf("calm", "soft", "gentle", "drift", "float"),
        "Lotus" to listOf("still", "peace", "relax", "lotus", "meditat", "sanctuary", "healing"),
        "Wave" to listOf("hz", "frequency", "tone", "beat", "alpha", "beta", "theta", "gamma", "schumann", "432", "528", "174", "binaural", "pure")
    )
    private val BY_KIND = mapOf("Rain" to "Rain", "Ocean" to "Ocean", "Singing bowl" to "Bowl", "Tibetan bells" to "Bell", "Xylophone" to "Keys", "Chimes" to "Chimes",
        "Temple gong" to "Gong", "Tanpura" to "Strings", "Handpan" to "Handpan", "Kalimba" to "Keys", "Frame drum" to "Drum", "Didgeridoo" to "Didgeridoo",
        "Tone" to "Wave", GENERATOR_KIND to "Wave", "Noise" to "Noise")

    /** Every symbol the title suggests, best first, then what its first sound is, then one picked from the name itself. */
    fun ideas(name: String, stack: AudioStack?): List<String> {
        val t = name.lowercase()
        val hits = WORDS.filter { (_, w) -> w.any { t.contains(it) } }.sortedBy { (_, w) -> w.mapNotNull { k -> t.indexOf(k).takeIf { it >= 0 } }.minOrNull() ?: 99 }.map { it.first }
        val kind = stack?.layers?.firstOrNull()?.kind?.let { BY_KIND[it] }
        return (hits + listOfNotNull(kind) + SYMBOLS[Math.floorMod(name.hashCode(), SYMBOLS.size)]).distinct()
    }
    fun iconOf(name: String, stack: AudioStack?): String = chosen[name] ?: ("sym:" + ideas(name, stack).first())

    /* ---------------- online: Iconify (free, open icon sets) ---------------- */

    class NetIcon(val id: String, val w: Float, val h: Float, val stroke: Boolean, val paths: List<String>) {
        fun encode(): String = JSONObject().put("id", id).put("w", w.toDouble()).put("h", h.toDouble()).put("s", stroke).put("d", JSONArray(paths)).toString()
        companion object {
            fun decode(s: String): NetIcon? = runCatching {
                val o = JSONObject(s); val a = o.getJSONArray("d")
                NetIcon(o.optString("id"), o.getDouble("w").toFloat(), o.getDouble("h").toFloat(), o.optBoolean("s"), (0 until a.length()).map { a.getString(it) })
            }.getOrNull()
        }
    }

    private fun get(url: String): String {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 8000; c.setRequestProperty("User-Agent", "Starshine")
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }

    /** Monochrome sets only, so every result can be tinted like the built-in symbols. */
    fun search(query: String): List<NetIcon> {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val found = JSONObject(get("https://api.iconify.design/search?query=$q&limit=32&prefixes=mdi,ph,tabler,game-icons,material-symbols")).optJSONArray("icons") ?: return emptyList()
        val ids = (0 until found.length()).map { found.getString(it) }
        val out = ArrayList<NetIcon>()
        val dRe = Regex("""\sd="([^"]+)"""")
        ids.groupBy { it.substringBefore(':') }.forEach { (prefix, list) ->
            runCatching {
                val o = JSONObject(get("https://api.iconify.design/$prefix.json?icons=" + list.joinToString(",") { it.substringAfter(':') }))
                val icons = o.getJSONObject("icons")
                val dw = o.optDouble("width", 16.0).toFloat(); val dh = o.optDouble("height", 16.0).toFloat()
                for (id in list) {
                    val ic = icons.optJSONObject(id.substringAfter(':')) ?: continue
                    val body = ic.getString("body")
                    val paths = dRe.findAll(body).map { it.groupValues[1] }.toList()
                    if (paths.isEmpty() || body.contains("<circle") || body.contains("<rect")) continue
                    out.add(NetIcon(id, ic.optDouble("width", dw.toDouble()).toFloat(), ic.optDouble("height", dh.toDouble()).toFloat(),
                        body.contains("stroke=\"currentColor\"") && body.contains("fill=\"none\""), paths))
                }
            }
        }
        return ids.mapNotNull { id -> out.firstOrNull { it.id == id } }.take(24)
    }
}

/** Draws an icon found online: its vector paths, tinted. */
@Composable
fun NetIconView(icon: MoodIcons.NetIcon, color: Color, modifier: Modifier) {
    val paths = remember(icon) { icon.paths.mapNotNull { d -> runCatching { PathParser().parsePathString(d).toPath() }.getOrNull() } }
    Canvas(modifier) {
        val k = minOf(size.width / icon.w, size.height / icon.h) * 0.9f
        translate((size.width - icon.w * k) / 2f, (size.height - icon.h * k) / 2f) {
            scale(k, k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                paths.forEach { if (icon.stroke) drawPath(it, color, style = Stroke(width = 1.8f)) else drawPath(it, color) }
            }
        }
    }
}

private val fileIcons = HashMap<String, ImageBitmap?>()

/** Any mood icon: a symbol, an icon from online, or a picture of yours. */
@Composable
fun MoodIconView(spec: String, color: Color, modifier: Modifier) {
    when {
        spec.startsWith("net:") -> { val n = remember(spec) { MoodIcons.NetIcon.decode(spec.removePrefix("net:")) }; if (n != null) NetIconView(n, color, modifier) }
        spec.startsWith("file:") -> {
            val path = spec.removePrefix("file:")
            val img by produceState(fileIcons[path], path) {
                if (!fileIcons.containsKey(path)) value = withContext(Dispatchers.IO) {
                    runCatching {
                        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }; android.graphics.BitmapFactory.decodeFile(path, o)
                        var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > 192) s *= 2
                        android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
                    }.getOrNull()
                }.also { fileIcons[path] = it }
            }
            img?.let { Image(it, null, modifier.padding(2.dp), contentScale = ContentScale.Fit) }
        }
        else -> SymbolIcon(spec.removePrefix("sym:"), color, modifier)
    }
}

/**
 * Choose a mood's icon: generated from its title (press again for another idea), any symbol, an icon found online,
 * or your own picture. Opened from a mood's options, and offered when a new mood is saved.
 */
@Composable
fun MoodIconPicker(name: String, onUpload: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val stack = Med.stacks.firstOrNull { it.name == name }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var idea by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf(name.replace(Regex("[^\\p{L}\\s]"), " ").trim().split(Regex("\\s+")).maxByOrNull { it.length } ?: name) }
    var results by remember { mutableStateOf<List<MoodIcons.NetIcon>?>(null) }
    var status by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    BackHandler { onClose() }
    LaunchedEffect(Unit) { delay(60); FocusMem.restoreSoon("row:mi:gen") }
    val current = MoodIcons.iconOf(name, stack)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
        Box(Modifier.size(40.dp).border(1.dp, CYAN.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(4.dp)) { MoodIconView(current, FG, Modifier.fillMaxSize()) }
        androidx.compose.foundation.layout.Column(Modifier.padding(start = 8.dp)) {
            Text("ICON FOR " + name.uppercase(), color = CYAN, fontSize = 11.sp, maxLines = 1)
            Text(hint.ifEmpty { "Back = done" }, color = DIM, fontSize = 9.sp, maxLines = 1)
        }
    }
    LoopColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { Row_("✦ Generate from the title" + if (idea > 0) " · another idea" else "", false, id = "mi:gen", onFocus = { hint = "Each press tries another idea" }) {
            val list = MoodIcons.ideas(name, stack)
            MoodIcons.set(ctx, name, "sym:" + list[idea % list.size]); idea++
        } }
        item { Row_("🌐 Find online: \"$query\"", false, id = "mi:net", onFocus = { hint = "Free icons from iconify.design · OK = search" }) {
            status = "Searching…"; results = null
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { MoodIcons.search(query) } }
                results = r.getOrNull(); status = if (r.isFailure) "Couldn't reach the icon library (offline?)" else if (results.isNullOrEmpty()) "Nothing found for \"$query\"" else ""
            }
        } }
        item { Row_("✎ Search for other words…", false, id = "mi:words", small = true, onFocus = { hint = "" }) {
            val box = android.widget.EditText(ctx).apply { setText(query); setSingleLine() }
            android.app.AlertDialog.Builder(ctx).setTitle("Search icons for").setView(box)
                .setPositiveButton("Search") { _, _ -> query = box.text.toString().trim().ifEmpty { query }; FocusMem.restore("row:mi:net") }
                .setNegativeButton("Cancel", null).show()
        } }
        if (status.isNotEmpty()) item { Text(status, color = AMBER, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
        results?.takeIf { it.isNotEmpty() }?.let { list ->
            items(list.chunked(ICON_COLUMNS), key = { "mn:" + it.first().id }) { row ->
                IconRow(row.map { it.id }, count = { id -> if (current == "net:" + list.first { it.id == id }.encode()) 1 else 0 }, idOf = { "mi:n:$it" }, height = 34,
                    onHint = { hint = it.substringAfter(':').replace('-', ' ') },
                    onPress = { id -> list.firstOrNull { it.id == id }?.let { MoodIcons.set(ctx, name, "net:" + it.encode()) } }
                ) { id, c, m -> list.firstOrNull { it.id == id }?.let { NetIconView(it, c, m) } }
            }
        }
        item { Row_("📁 Upload your own picture  ›", false, id = "mi:file", onFocus = { hint = "A PNG or JPG from your files" }) { onUpload() } }
        item { Header("Symbols") }
        items(MoodIcons.SYMBOLS.chunked(ICON_COLUMNS), key = { "ms:" + it.first() }) { row ->
            IconRow(row, count = { s -> if (current == "sym:$s") 1 else 0 }, idOf = { "mi:s:$it" }, height = 30, label = { it },
                onHint = { hint = it }, onPress = { s -> MoodIcons.set(ctx, name, "sym:$s") }) { s, c, m -> SymbolIcon(s, c, m) }
        }
        item { Row_("↺ Back to the automatic icon", false, id = "mi:auto", onFocus = { hint = "" }) { MoodIcons.set(ctx, name, null) } }
        item { Row_("✓ Done", false, id = "mi:done", onFocus = { hint = "" }) { onClose() } }
    }
}
