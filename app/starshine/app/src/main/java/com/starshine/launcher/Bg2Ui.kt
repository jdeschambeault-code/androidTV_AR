package com.starshine.launcher

import android.view.ViewGroup
import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/** Expanded folders of the Bg tab survive leaving and re-entering the tab. Everything starts closed. */
private val openSections = mutableStateMapOf<String, Boolean>()

/** The scene last shown by browsing, so coming back from the builder doesn't throw away what's in the preview. */
var lastBrowsedScene = ""

private fun KeyEvent.okKey() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/** Layout of the monitor, in dp of the 960x540 screen. Used by the GL renderer and by the labels on top of it. */
object Stage {
    const val X = 300f; const val Y = 76f; const val W = 624f; const val H = 351f
    const val TILE_Y = 436f; private const val GAP = 6f
    fun tileW(n: Int) = if (n <= 4) 140f else 73f
    fun tileH(n: Int) = tileW(n) * 9f / 16f
    fun tileX(i: Int, n: Int) = X + i * (tileW(n) + GAP)
}

/**
 * The Bg workspace: the monitor shows the scene being browsed or built, with its nodes as live tiles underneath, while
 * what's applied stays untouched. Browsing changes only the monitor. OK applies. Right arrow opens the builder.
 */
@Composable
fun BgTab(onApply: (BgScene) -> Unit, onBuild: () -> Unit, onNew: () -> Unit, onFiles: () -> Unit, onExport: () -> Unit, presetsVersion: Int,
          classics: List<String>, currentBg: String, onClassicApply: (String) -> Unit, onClassicEdit: (String) -> Unit,
          onAudioMood: (AudioMood) -> Unit, onAmbient: (String) -> Unit, onTone: (Tone) -> Unit, onStop: () -> Unit, onGenerator: () -> Unit) {
    val ctx = LocalContext.current
    val presets = remember(presetsVersion) { Tones.loadPresets(ctx) }
    val playing = Sound.current
    var browsing by remember { mutableStateOf("") }
    LaunchedEffect(browsing) {
        if (browsing.isEmpty() || browsing == lastBrowsedScene) return@LaunchedEffect
        delay(220)   // holding an arrow through the list doesn't load every scene on the way
        lastBrowsedScene = browsing
        Bg2.pristine(browsing)?.let { Bg2.preview = it }
    }
    @Composable fun folder(id: String, label: String, count: Int) {
        val open = openSections[id] == true
        Row_((if (open) "▾ " else "▸ ") + label + "  ($count)", false, id = "sec:$id", category = true, onFocus = { Bg2.classicView = null }) { openSections[id] = !open }
    }
    Panel(Modifier.padding(start = 24.dp, bottom = 8.dp).width(256.dp).fillMaxHeight()) {
        Text("OK = apply · → = build", color = DIM, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { Row_("＋ New scene from scratch", false, id = "bgt:new", onFocus = { Bg2.classicView = null }) { onNew() } }
            item { Row_("◈ Build what's in the preview  ›", false, id = "bgt:build", onFocus = { Bg2.classicView = null }) { onBuild() } }
            SCENE_GROUPS.forEach { group ->
                val list = if (group == MY_SCENES) Bg2.mine else BUILT_IN_SCENES.filter { it.group == group }
                if (group != MY_SCENES || list.isNotEmpty()) {
                    item(key = "h:$group") { folder("g:$group", group, list.size) }
                    if (openSections["g:$group"] == true) items(list, key = { "s:${group}:${it.name}" }) { s ->
                        val on = Bg2.applied && Bg2.current.name == s.name
                        Box(Modifier.onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                                if (Bg2.preview.name != s.name) Bg2.pristine(s.name)?.let { Bg2.preview = it }
                                lastBrowsedScene = s.name; onBuild(); true
                            } else false
                        }) {
                            Row_((if (on) "✓ " else "    ") + s.name, on, id = "scene:${s.name}", small = true, onFocus = { Bg2.classicView = null; browsing = s.name }) {
                                onApply(if (Bg2.preview.name == s.name) Bg2.preview else (Bg2.pristine(s.name) ?: s))
                            }
                        }
                    }
                }
            }
            item(key = "h:classic") { folder("classic", "Classic backgrounds", classics.size) }
            if (openSections["classic"] == true) items(classics, key = { "cl:$it" }) { n ->
                val on = currentBg == n
                Box(Modifier.onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { onClassicEdit(n); true } else false
                }) {
                    Row_((if (on) "✓ " else "    ") + n.removePrefix("file:").substringAfterLast('/'), on, id = "classic:$n", small = true, onFocus = { Bg2.classicView = n }) { onClassicApply(n) }
                }
            }
            item { Header("Sound · never changes the background") }
            item(key = "h:moods") { folder("moods", "Audio moods", AUDIO_MOODS.size) }
            if (openSections["moods"] == true) items(AUDIO_MOODS, key = { "am:" + it.name }) { m ->
                val on = playing == m.ambient
                Row_((if (on) "▶ " else "    ") + m.name, on, id = "am:${m.name}", small = true, onFocus = {}) { onAudioMood(m) }
            }
            item(key = "h:freq") { folder("freq", "Frequencies", presets.size) }
            if (openSections["freq"] == true) {
                items(presets, key = { "fq:" + it.name }) { t ->
                    Row_((if (playing == t.name) "▶ " else "    ") + t.name, playing == t.name, id = "fq:${t.name}", small = true, onFocus = {}) { onTone(t) }
                }
                item(key = "fq:gen") { Row_("    〰 Frequency generator…", false, id = "fq:gen", small = true, onFocus = {}) { onGenerator() } }
            }
            item { Row_("■ Stop sound", false, id = "bgt:stop", onFocus = {}) { onStop() } }
            item { Row_("✦ React to sound: " + if (Bg2.reactOn) "on" else "off", false, id = "bgt:react", onFocus = {}) { Bg2.setReact(ctx, !Bg2.reactOn) } }
            item { Row_("📁 Files: your images and videos  ›", false, id = "bgt:files", onFocus = {}) { onFiles() } }
            item { Row_("🎞 Export preview as 15 s video", false, id = "bgt:export", onFocus = {}) { onExport() } }
        }
    }
}

/* ---------------- builder: nodes, then one node's settings. Edits the preview only, until Apply ---------------- */

@Composable
fun BgEditor(onClose: () -> Unit, onApply: (BgScene) -> Unit, onToast: (String) -> Unit, onPickMedia: (Int) -> Unit) {
    val scene = Bg2.preview
    val node = scene.layers.firstOrNull { it.id == Bg2.focusNode }
    val first = remember { FocusRequester() }
    Bg2.panelFirst = first
    var base by remember { mutableStateOf(scene) }      // last applied or saved state, to know if anything would be lost
    var warned by remember { mutableStateOf(false) }
    var inSettings by remember { mutableStateOf(false) }
    fun edit(l: Layer) { Bg2.preview = scene.copy(layers = scene.layers.map { if (it.id == l.id) l else it }) }
    fun toTile(id: Int) { FocusMem.restore("ltile:$id") }
    DisposableEffect(Unit) { Bg2.building = true; onDispose { Bg2.building = false; Bg2.soloId = -1; Bg2.focusNode = -1; Bg2.movingNode = -1; Bg2.panelFirst = null } }
    // settings -> its layer tile -> the scene rows -> leave
    BackHandler(enabled = node != null) {
        if (inSettings) toTile(node!!.id) else { Bg2.focusNode = -1 }
    }
    BackHandler(enabled = node == null && scene != base && !warned) { warned = true; onToast("Not applied or saved. Back again to leave, keeping it in the preview") }
    LaunchedEffect(node == null) { if (node == null) { delay(90); runCatching { first.requestFocus() } } }
    // a preset opens on its first layer, so you start in the picture rather than in a menu
    LaunchedEffect(Unit) { delay(180); scene.layers.firstOrNull()?.let { toTile(it.id) } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.CenterStart).padding(start = 24.dp, top = 10.dp, bottom = 10.dp).width(256.dp).fillMaxHeight()
            .background(Color(0xFF060C14).copy(alpha = (Theme.panelAlpha + 0.3f).coerceAtMost(0.96f)), RoundedCornerShape(14.dp))
            .border(1.dp, CYAN.copy(alpha = 0.25f), RoundedCornerShape(14.dp)).padding(10.dp)
            .onFocusChanged { inSettings = it.hasFocus && node != null }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && M5.editing == null) {
                    val id = node?.id ?: scene.layers.firstOrNull()?.id
                    if (id != null) { toTile(id); true } else false
                } else false
            }) {
            if (node == null) ScenePanel(scene, first, onToast = onToast, onPickMedia = onPickMedia,
                onApply = { onApply(scene); base = scene; warned = false; onToast("On screen: ${scene.name}") },
                onSaved = { s -> Bg2.preview = s; lastBrowsedScene = s.name; base = s; warned = false },
                onClose = onClose)
            else NodeSettings(scene, node, first, edit = { edit(it) }, onPickMedia = { onPickMedia(node.id) }, onDeleted = { Bg2.focusNode = -1 })
        }
    }
}

@Composable
private fun ScenePanel(scene: BgScene, first: FocusRequester, onToast: (String) -> Unit, onPickMedia: (Int) -> Unit,
                       onApply: () -> Unit, onSaved: (BgScene) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var addOpen by remember { mutableStateOf(scene.layers.isEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val isMine = Bg2.mine.any { it.name == scene.name }
    fun add(g: Generator) {
        if (scene.layers.size >= 8) { onToast("8 nodes is the limit for this device"); return }
        val l = Layer(scene.nextId, g.name, if (g.kind == GenKind.Media) mapOf("blend" to 2f) else emptyMap())
        // a whole background belongs at the back; it can still be moved
        val base = g.name.startsWith(CLASSIC_PREFIX) || g.kind == GenKind.Classic
        Bg2.preview = scene.copy(layers = if (base) listOf(l) + scene.layers else scene.layers + l)
        onToast("Added ${g.name.removePrefix(CLASSIC_PREFIX)}" + if (base) " as the first layer" else "")
        if (g.kind == GenKind.Media) onPickMedia(l.id)
    }
    BackHandler(enabled = addOpen) { addOpen = false; FocusMem.restore("row:ed:addmenu") }
    Text(scene.name.uppercase(), color = CYAN, fontSize = 11.sp, letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(if (scene.layers.isEmpty()) "Add a layer to begin" else "→ = go to the layers under the preview", color = DIM, fontSize = 9.sp, modifier = Modifier.padding(bottom = 6.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { Box(Modifier.focusRequester(first)) { Row_("✓ Apply to background", Bg2.applied && Bg2.current == scene, id = "ed:apply", onFocus = {}) { onApply() } } }
        item { Row_("⛶ Full-screen preview", false, id = "ed:full", onFocus = {}) { Bg2.fullPreview = true; onToast("Any button returns") } }
        item(key = "addmenu") { Row_((if (addOpen) "▾ " else "▸ ") + "＋ Add layer", addOpen, id = "ed:addmenu", category = true, onFocus = {}) { addOpen = !addOpen } }
        if (addOpen) GEN_GROUPS.forEach { (title, belongs) ->
            item(key = "gh:$title") { Text(title, color = CYAN, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)) }
            items(GENERATORS.filter(belongs), key = { "gen:" + it.name }) { g ->
                Row_("＋ " + g.name.removePrefix(CLASSIC_PREFIX).removePrefix(LINES_PREFIX), false, id = "gen:${g.name}", small = true, onFocus = {}) { add(g) }
            }
        }
        item { Header("Scene") }
        item { Row_("＋ Save as new scene", false, id = "ed:new", onFocus = {}) {
            val s = Bg2.saveAsNew(ctx, scene); onSaved(s); onToast("Saved as \"${s.name}\"")
        } }
        if (isMine) item { Row_("⟳ Save changes", false, id = "ed:over", onFocus = {}) { Bg2.overwrite(ctx, scene); onSaved(scene); onToast("Saved ${scene.name}") } }
        item { Row_("↺ Reset to its saved state", false, id = "ed:reset", onFocus = {}) { Bg2.pristine(scene.name)?.let { Bg2.preview = it; onToast("Reset ${it.name}") } } }
        if (isMine) item { Row_(if (confirmDelete) "🗑 OK again to delete" else "🗑 Delete this scene", confirmDelete, id = "ed:del", onFocus = {}) {
            if (!confirmDelete) confirmDelete = true
            else { val n = scene.name; Bg2.delete(ctx, n); confirmDelete = false; onToast("Deleted $n"); onClose() }
        } }
    }
}

private val GEN_GROUPS: List<Pair<String, (Generator) -> Boolean>> = listOf(
    "Classic backgrounds" to { g -> g.name.startsWith(CLASSIC_PREFIX) || g.kind == GenKind.Classic },
    "Line visualizers" to { g -> g.name.startsWith(LINES_PREFIX) },
    "Sacred geometry" to { g -> g.kind == GenKind.Line }, "3D solids" to { g -> g.kind == GenKind.Solid }, "Particles" to { g -> g.kind == GenKind.Points },
    "Fields & light" to { g -> g.kind == GenKind.Screen }, "Your files" to { g -> g.kind == GenKind.Media }
)

@Composable
private fun NodeSettings(scene: BgScene, l: Layer, first: FocusRequester, edit: (Layer) -> Unit, onPickMedia: () -> Unit, onDeleted: () -> Unit) {
    val others = scene.layers.filter { it.id != l.id }
    fun cycle(key: String, size: Int, d: Int) = edit(l.set(key, (((l[key].toInt() + d) % size + size) % size).toFloat()))
    fun link(key: String, d: Int) {
        val ids = listOf(-1) + others.map { it.id }
        val i = ids.indexOf(l[key].toInt()).coerceAtLeast(0)
        edit(l.set(key, ids[(i + d + ids.size) % ids.size].toFloat()))
    }
    fun linkName(key: String) = others.firstOrNull { it.id == l[key].toInt() }?.type ?: "none"
    @Composable fun slider(p: BgParams.P) {
        val v = l[p.key]
        Adjust(p.label, p.fmt(v), { edit(l.set(p.key, (v - p.step).coerceAtLeast(p.min))) }, compact = true) { edit(l.set(p.key, (v + p.step).coerceAtMost(p.max))) }
    }
    @Composable fun toggle(label: String, key: String, yes: String = "on", no: String = "off") {
        val flip = { edit(l.set(key, if (l[key] > 0.5f) 0f else 1f)) }
        Adjust(label, if (l[key] > 0.5f) yes else no, { flip() }, compact = true) { flip() }
    }
    val typeIndex = GENERATORS.indexOfFirst { it.name == l.type }.coerceAtLeast(0)
    Text(l.type.uppercase(), color = CYAN, fontSize = 11.sp, letterSpacing = 1.2.sp, maxLines = 1)
    Text("OK = edit · ↑↓ next · → or Back = layers", color = DIM, fontSize = 9.sp, modifier = Modifier.padding(bottom = 6.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        item { Box(Modifier.focusRequester(first)) { toggle("Enabled", "on") } }
        item { toggle("In the picture", "show", "yes", "source only") }
        item { Adjust("Generator", l.type, { edit(l.copy(type = GENERATORS[(typeIndex - 1 + GENERATORS.size) % GENERATORS.size].name)) }, compact = true) { edit(l.copy(type = GENERATORS[(typeIndex + 1) % GENERATORS.size].name)) } }
        if (l.kind == GenKind.Media) {
            item { Row_("📁 File: " + l.media.substringAfterLast('/').ifEmpty { "choose…" }, false, id = "nd:file", onFocus = {}) { onPickMedia() } }
            item {
                val flat = l["persp"] < 0.5f
                val flip = { edit(if (flat) l.set("persp", 1f).let { if (it["tiltX"] == 0f && it["tiltY"] == 0f) it.set("tiltX", -25f).set("tiltY", 20f) else it } else l.set("persp", 0f)) }
                Adjust("View", if (flat) "Flat" else "Perspective", { flip() }, compact = true) { flip() }
            }
            item {
                val flip = { edit(l.set("fit", if (l["fit"] < 0.5f) 1f else 0f)) }
                Adjust("Fit", if (l["fit"] < 0.5f) "Fill screen" else "Whole picture", { flip() }, compact = true) { flip() }
            }
            item { Header("Placement") }
            items(listOf(BgParams.X, BgParams.Y, BgParams.SIZE, BgParams.ANGLE, BgParams.SPIN) + if (l["persp"] > 0.5f) listOf(BgParams.TILTX, BgParams.TILTY) else emptyList(), key = { "pl:" + it.key }) { slider(it) }
        }
        item { Adjust("Blend", BgParams.BLENDS[l["blend"].toInt()], { cycle("blend", BgParams.BLENDS.size, -1) }, compact = true) { cycle("blend", BgParams.BLENDS.size, 1) } }
        item { Adjust("Mirror", BgParams.FOLDS[l["fold"].toInt()], { cycle("fold", BgParams.FOLDS.size, -1) }, compact = true) { cycle("fold", BgParams.FOLDS.size, 1) } }
        item { Header("Layer interaction") }
        item { Adjust("Mask by", linkName("maskBy"), { link("maskBy", -1) }, compact = true) { link("maskBy", 1) } }
        item { slider(BgParams.MASK_AMT) }
        item { Adjust("Warp by", linkName("warpBy"), { link("warpBy", -1) }, compact = true) { link("warpBy", 1) } }
        item { slider(BgParams.WARP_AMT) }
        item { Header("Look") }
        val placed = setOf("x", "y", "scale", "angle", "spin", "tiltX", "tiltY")
        items(if (l.kind == GenKind.Media) BgParams.LOOK.filter { it.key !in placed } else BgParams.LOOK, key = { it.key }) { slider(it) }
        item { Header("React to sound") }
        item { Adjust("React 1 from", BgParams.SOURCES[l["r1s"].toInt()], { cycle("r1s", BgParams.SOURCES.size, -1) }, compact = true) { cycle("r1s", BgParams.SOURCES.size, 1) } }
        item { Adjust("React 1 changes", BgParams.TARGETS[l["r1t"].toInt()], { cycle("r1t", BgParams.TARGETS.size, -1) }, compact = true) { cycle("r1t", BgParams.TARGETS.size, 1) } }
        item { slider(BgParams.R1A) }
        item { Adjust("React 2 from", BgParams.SOURCES[l["r2s"].toInt()], { cycle("r2s", BgParams.SOURCES.size, -1) }, compact = true) { cycle("r2s", BgParams.SOURCES.size, 1) } }
        item { Adjust("React 2 changes", BgParams.TARGETS[l["r2t"].toInt()], { cycle("r2t", BgParams.TARGETS.size, -1) }, compact = true) { cycle("r2t", BgParams.TARGETS.size, 1) } }
        item { slider(BgParams.R2A) }
        item { Header("Node") }
        item { Row_("⧉ Duplicate", false, id = "nd:dup", onFocus = {}) {
            if (scene.layers.size < 8) Bg2.preview = scene.copy(layers = scene.layers + l.copy(id = scene.nextId))
        } }
        item { Row_("↺ Reset this node", false, id = "nd:reset", onFocus = {}) { edit(Layer(l.id, l.type, media = l.media)) } }
        item { Row_("🗑 Remove this node", false, id = "nd:rm", onFocus = {}) {
            val rest = scene.layers.filter { it.id != l.id }.map { o ->
                o.let { if (it["maskBy"].toInt() == l.id) it.set("maskBy", -1f) else it }.let { if (it["warpBy"].toInt() == l.id) it.set("warpBy", -1f) else it }
            }
            Bg2.soloId = -1; onDeleted(); Bg2.preview = scene.copy(layers = rest)
        } }
    }
}

/* ---------------- monitor: GL surface + labels ---------------- */

@Composable
fun Bg2Stage(fps30: Boolean) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) { AudioBands.start(); onDispose { AudioBands.stop() } }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { c ->
                GLSurfaceView(c).apply {
                    setEGLContextClientVersion(2)
                    val r = Bg2Renderer(c.applicationContext, sceneOf = { Bg2.preview }, monitor = true); r.fps30 = fps30
                    tag = r
                    setRenderer(r)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    isFocusable = false
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            update = { v ->
                (v.tag as? Bg2Renderer)?.fps30 = fps30
                val s = (Bg2.scale * 4f / 3f).coerceAtMost(2f); v.holder.setFixedSize((960 * s).toInt(), (540 * s).toInt())
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { (it.tag as? Bg2Renderer)?.stopPlayers(); it.onPause() }
        )
        if (!Bg2.fullPreview) StageLabels()
    }
}

@Composable
private fun StageLabels() {
    val scene = Bg2.preview
    val onScreen = Bg2.applied && Bg2.current == scene
    val n = scene.layers.size
    val soloName = scene.layers.firstOrNull { it.id == Bg2.soloId }?.type
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.offset(Stage.X.dp, Stage.Y.dp).size(Stage.W.dp, Stage.H.dp).border(1.dp, if (onScreen) GREEN.copy(alpha = 0.6f) else CYAN.copy(alpha = 0.35f), RoundedCornerShape(4.dp))) {
            Text(scene.name + (if (soloName != null) " · $soloName only" else "") + "   ·   " + if (onScreen) "ON SCREEN" else "PREVIEW, NOT APPLIED",
                color = if (onScreen) GREEN else AMBER, fontSize = 10.sp, maxLines = 1,
                modifier = Modifier.padding(6.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            if (n == 0) Text("Empty scene: add a node from the list", color = DIM, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
        }
        scene.layers.forEachIndexed { i, l -> key(l.id) { LayerTile(scene, l, i, n) } }
    }
}

/**
 * A layer under the monitor. Left is the back layer. In the builder it's a live control:
 * ← → next layer · OK = show only this layer · hold OK = pick up, then ← → move, OK = drop · ↑ = its settings.
 */
@Composable
private fun LayerTile(scene: BgScene, l: Layer, i: Int, n: Int) {
    val x = Stage.tileX(i, n); val w = Stage.tileW(n); val h = Stage.tileH(n)
    val selected = Bg2.building && Bg2.focusNode == l.id
    var focused by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val solo = Bg2.soloId == l.id
    val moving = Bg2.movingNode == l.id
    fun neighbour(d: Int) { scene.layers.getOrNull(i + d)?.let { FocusMem.restore("ltile:${it.id}") } }
    fun move(d: Int) {
        val j = i + d
        if (j in scene.layers.indices) Bg2.preview = scene.copy(layers = scene.layers.toMutableList().apply { add(j, removeAt(i)) })
    }
    val control = if (!Bg2.building) Modifier else Modifier
        .remembered("ltile:${l.id}")
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) Bg2.focusNode = l.id else if (Bg2.movingNode == l.id) Bg2.movingNode = -1 }
        .focusOnHover()
        .onPreviewKeyEvent { e ->
            val down = e.type == KeyEventType.KeyDown
            val ok = e.okKey()
            when {
                Bg2.movingNode == l.id -> {
                    if (down) when {
                        e.key == Key.DirectionLeft -> move(-1)
                        e.key == Key.DirectionRight -> move(1)
                        ok && e.nativeKeyEvent.repeatCount == 0 && !held -> Bg2.movingNode = -1
                        e.key == Key.Back -> Bg2.movingNode = -1
                    }
                    if (!down && ok) held = false
                    true
                }
                ok -> {
                    if (down && e.nativeKeyEvent.repeatCount == 1) { held = true; Bg2.movingNode = l.id }
                    if (!down) { if (held) held = false else Bg2.soloId = if (solo) -1 else l.id }
                    true
                }
                !down -> false
                e.key == Key.DirectionLeft -> { neighbour(-1); true }
                e.key == Key.DirectionRight -> { neighbour(1); true }
                e.key == Key.DirectionUp -> { runCatching { Bg2.panelFirst?.requestFocus() }; true }
                e.key == Key.DirectionDown -> true
                else -> false
            }
        }
        .focusable()
    val color = when { moving -> AMBER; solo -> GREEN; focused -> CYAN; selected -> CYAN.copy(alpha = 0.6f); l.on -> CYAN.copy(alpha = 0.3f); else -> DIM.copy(alpha = 0.2f) }
    Box(Modifier.offset(x.dp, Stage.TILE_Y.dp).size(w.dp, h.dp).then(control).border(if (focused || solo || moving) 2.dp else 1.dp, color, RoundedCornerShape(3.dp))) {
        if (moving) Text("‹ ›", color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp))
    }
    val links = listOfNotNull(
        scene.layers.firstOrNull { it.id == l["maskBy"].toInt() }?.let { "mask " + (scene.layers.indexOf(it) + 1) },
        scene.layers.firstOrNull { it.id == l["warpBy"].toInt() }?.let { "warp " + (scene.layers.indexOf(it) + 1) }
    ).joinToString(" ")
    Column(Modifier.offset(x.dp, (Stage.TILE_Y + h + 1f).dp).width(w.dp)) {
        Text("${i + 1} " + l.type.removePrefix(CLASSIC_PREFIX).removePrefix(LINES_PREFIX), color = if (solo) GREEN else if (moving) AMBER else if (focused) CYAN else if (l.on) FG else DIM, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (moving) "← → move · OK drop" else if (focused) (if (solo) "OK = whole scene" else "OK = only this") else if (!l.on) "off" else if (!l.show) "source only" else (BgParams.BLENDS[l["blend"].toInt()] + if (links.isEmpty()) "" else " · $links"), color = DIM, fontSize = 7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
