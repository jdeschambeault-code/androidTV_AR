package com.starshine.launcher

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

const val DOWNLOADED = "Downloaded"

private fun step(scene: String, audio: String, minutes: Float, blend: Float = 20f, transition: Int = 0) = JourneyStep(scene, audio, minutes, blend, transition)

/**
 * Ready-made journeys, made from the scenes and moods that come with Starshine. They loop by default. A step whose mood
 * isn't there (renamed or deleted) still has sound: the journey picks one that suits the scene.
 */
val BUILT_IN_JOURNEYS: List<Journey> = listOf(
    Journey("Five elements", listOf(step("Earth", "Outback dreaming", 4f), step("Water", "Ocean breath", 4f, 25f, 2), step("Air", "Zen garden", 4f, 20f, 4),
        step("Fire", "Fire circle", 4f, 20f, 5), step("Ether", "Sound bath", 5f, 30f, 2))),
    Journey("Deep sleep", listOf(step("Candle", "Rain sanctuary", 5f), step("Water", "Ocean breath", 6f, 40f, 1), step("Nebula orbs", "Delta sleep 2 Hz", 10f, 45f),
        step("Star temple", "Deep stillness", 10f, 60f, 1))),
    Journey("Morning light", listOf(step("Sun", "Alpha calm", 3f), step("Flower bloom", "528 Hz pure", 4f, 20f, 4), step("Temple of Light", "Beta focus 15 Hz", 4f, 20f, 3))),
    Journey("Cosmic voyage", listOf(step("Star sea", "Theta drift", 4f), step("Galaxy", "Deep stillness", 5f, 25f, 2), step("Solar system", "Theta dream 6 Hz", 5f, 25f),
        step("Ringed world", "Sound bath", 4f, 30f, 4))),
    Journey("Sacred geometry", listOf(step("Metatron", "Sound bath", 4f), step("Flower bloom", "Himalayan temple", 4f, 20f, 5), step("Floral mandala", "Tanpura evening", 4f),
        step("Sacred orbit", "432 Hz pure", 5f, 30f, 2))),
    Journey("Ocean calm", listOf(step("Water", "Ocean breath", 5f), step("Air", "Zen garden", 5f, 30f, 3), step("Nebula orbs", "Ocean", 5f, 30f, 1))),
    Journey("Focus flow", listOf(step("Hologram", "Beta focus 15 Hz", 5f), step("Inner eye", "Gamma 40 Hz", 4f, 15f, 4), step("Temple of Light", "Alpha relax 10 Hz", 5f, 20f))),
    Journey("Himalayan retreat", listOf(step("Temple of Light", "Himalayan temple", 5f), step("Earth", "Tanpura evening", 5f, 25f, 2), step("Candle", "Sound bath", 5f, 25f, 1))),
    Journey("World drums", listOf(step("Fire", "Fire circle", 3f), step("Earth", "Outback dreaming", 3f, 15f, 3), step("Warp gate", "Handpan journey", 3f, 15f, 5),
        step("Floral mandala", "Island gamelan", 3f, 15f, 4))),
    Journey("Inner eye", listOf(step("Inner eye", "Theta drift", 5f), step("Ether", "Deep stillness", 5f, 30f, 2), step("Star temple", "Schumann 7.83 Hz", 6f, 40f, 4)))
)

/**
 * Packs: more scenes, moods and journeys published on the public Starshine repository. The index lists them; a pack is
 * one JSON file holding any of "scenes", "moods" and "journeys" (in the same form Starshine saves them). Installed packs
 * are kept on the device and their contents appear under "Downloaded".
 */
object Packs {
    const val INDEX = "https://raw.githubusercontent.com/jdeschambeault-code/Rokit-TV-Toolkit/main/packs/index.json"

    class Info(val id: String, val name: String, val description: String, val version: Int, val url: String)

    var installed by mutableStateOf(mapOf<String, Int>()); private set
    var scenes by mutableStateOf(listOf<BgScene>()); private set
    var moods by mutableStateOf(listOf<AudioStack>()); private set
    var journeys by mutableStateOf(listOf<Journey>()); private set
    private var loaded = false
    private fun sp(ctx: Context) = ctx.getSharedPreferences("starshine_packs", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = sp(ctx)
        installed = p.all.keys.filter { it.startsWith("pack:") }.associate { k -> k.removePrefix("pack:") to (runCatching { JSONObject(p.getString(k, "{}")!!).optInt("version", 1) }.getOrDefault(1)) }
        rebuild(ctx)
    }

    /** Everything from every installed pack, under the Downloaded group. Names already taken by your own items are skipped. */
    private fun rebuild(ctx: Context) {
        val p = sp(ctx)
        val sc = ArrayList<BgScene>(); val md = ArrayList<AudioStack>(); val jr = ArrayList<Journey>()
        for (id in installed.keys) runCatching {
            val o = JSONObject(p.getString("pack:$id", "{}")!!)
            o.optJSONArray("scenes")?.let { a -> for (i in 0 until a.length()) runCatching { sc.add(BgScene.fromJson(a.getJSONObject(i)).copy(group = DOWNLOADED)) } }
            o.optJSONArray("moods")?.let { a -> for (i in 0 until a.length()) runCatching { md.add(AudioStack.fromJson(a.getJSONObject(i))) } }
            o.optJSONArray("journeys")?.let { a -> for (i in 0 until a.length()) runCatching { jr.add(Journey.fromJson(a.getJSONObject(i))) } }
        }
        scenes = sc.distinctBy { it.name }.filter { s -> Bg2.mine.none { it.name == s.name } && BUILT_IN_SCENES.none { it.name == s.name } }
        moods = md.distinctBy { it.name }
        journeys = jr.distinctBy { it.name }.filter { j -> BUILT_IN_JOURNEYS.none { it.name == j.name } }
        // downloaded moods join your moods list, so journeys and the Moods tab can use them like any other
        val add = moods.filter { m -> Med.stacks.none { it.name == m.name } }
        if (add.isNotEmpty()) { Med.stacks = Med.stacks + add; Med.saveStacks(ctx) }
    }

    fun fetchIndex(): List<Info> {
        val o = JSONObject(get(INDEX)); val a = o.getJSONArray("packs")
        return (0 until a.length()).map { i -> val p = a.getJSONObject(i); Info(p.getString("id"), p.optString("name", p.getString("id")), p.optString("description"), p.optInt("version", 1), p.getString("url")) }
    }

    /** Downloads and keeps a pack. Its contents are checked by reading them before anything is saved. */
    fun install(ctx: Context, info: Info): String {
        val raw = get(info.url)
        val o = JSONObject(raw)
        val n = listOf("scenes", "moods", "journeys").sumOf { o.optJSONArray(it)?.length() ?: 0 }
        o.optJSONArray("scenes")?.let { a -> for (i in 0 until a.length()) BgScene.fromJson(a.getJSONObject(i)) }
        o.optJSONArray("journeys")?.let { a -> for (i in 0 until a.length()) Journey.fromJson(a.getJSONObject(i)) }
        o.put("version", info.version)
        sp(ctx).edit().putString("pack:" + info.id, o.toString()).apply()
        installed = installed + (info.id to info.version)
        rebuild(ctx)
        return "${info.name}: $n items added"
    }

    fun remove(ctx: Context, id: String) { sp(ctx).edit().remove("pack:$id").apply(); installed = installed - id; rebuild(ctx) }

    private fun get(url: String): String {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000; c.setRequestProperty("User-Agent", "Starshine")
        if (c.responseCode !in 200..299) { c.disconnect(); throw java.io.IOException("HTTP ${c.responseCode}") }
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }
}

/** "Get more": the packs published for Starshine. OK installs or updates one; on an installed pack, OK twice removes it. */
@Composable
fun PackBrowser(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Packs.Info>?>(null) }
    var status by remember { mutableStateOf("Looking for packs…") }
    var confirm by remember { mutableStateOf("") }
    BackHandler { onClose() }
    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) { runCatching { Packs.fetchIndex() } }
        list = r.getOrNull()
        status = when { r.isFailure -> "Couldn't reach the pack list (offline, or not published yet)"; list.isNullOrEmpty() -> "No packs published yet"; else -> "" }
        delay(60); FocusMem.restoreSoon("row:pk:back")
    }
    Text("GET MORE", color = CYAN, fontSize = 11.sp, letterSpacing = 1.2.sp)
    Text("Scenes, moods and journeys from the Starshine repository · Back = done", color = DIM, fontSize = 9.sp, maxLines = 2, modifier = Modifier.padding(bottom = 6.dp))
    LoopColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { Row_("← Back to Meditation", false, id = "pk:back", onFocus = {}) { onClose() } }
        if (status.isNotEmpty()) item { Text(status, color = AMBER, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
        list?.forEach { p ->
            val have = Packs.installed[p.id]
            val label = when { have == null -> "⬇ " + p.name; have < p.version -> "⟳ Update " + p.name; confirm == p.id -> "🗑 OK again to remove " + p.name; else -> "✓ " + p.name + " · installed" }
            item(key = "pk:" + p.id) {
                Row_(label, have != null, id = "pk:" + p.id, onFocus = { if (confirm != p.id) confirm = "" }) {
                    if (have != null && have >= p.version) { if (confirm == p.id) { Packs.remove(ctx, p.id); confirm = ""; status = p.name + " removed" } else confirm = p.id }
                    else { status = "Downloading ${p.name}…"
                        scope.launch { status = withContext(Dispatchers.IO) { runCatching { Packs.install(ctx, p) }.getOrElse { "Couldn't install ${p.name}: ${it.message}" } } } }
                }
            }
            if (p.description.isNotEmpty()) item(key = "pkd:" + p.id) { Text(p.description, color = DIM, fontSize = 9.sp, modifier = Modifier.padding(start = 26.dp, end = 8.dp, bottom = 4.dp)) }
        }
    }
}
