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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import kotlinx.coroutines.launch

/** Expanded folders of the Bg tab survive leaving and re-entering the tab. Everything starts closed. */
private val openSections = mutableStateMapOf<String, Boolean>()

/** The scene last shown by browsing, so coming back from the builder doesn't throw away what's in the preview. */
var lastBrowsedScene = ""

private fun KeyEvent.okKey() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/** Layout of the monitor, in dp of the 960x540 screen. Used by the GL renderer and by the labels on top of it. */
object Stage {
    private const val GAP = 6f
    private const val MARGIN = 8f
    /** The menu panel on screen, in the 960-wide units of this layout: left, top, right, bottom, and the UI scale. */
    private var panel by mutableStateOf(floatArrayOf(24f, 76f, 280f, 520f, 1f))
    fun place(next: FloatArray) { if (!next.contentEquals(panel)) panel = next }

    /** Moods and journeys show a fixed strip under the preview. Scenes show layer tiles, which shrink with it. */
    private val fixedStrip get() = Med.subTab == 1 || Med.planning != null
    private const val STRIP = 104f
    private const val TILE_LABEL = 20f
    val X get() = panel[2] + MARGIN
    val Y get() = panel[1]
    val W: Float get() {
        val contentRight = panel[0] - 24f * panel[4] + 960f * panel[4]
        val wide = minOf(contentRight, 960f - MARGIN) - X
        val tall = panel[3] - Y
        // height = preview + what's under it, so the width follows from whichever runs out first
        val fit = if (fixedStrip) (tall - STRIP - MARGIN) / (9f / 16f)
        else (tall - TILE_LABEL - MARGIN + GAP * 3f / 4f * 9f / 16f) / ((9f / 16f) * 1.25f)
        return minOf(wide, fit).coerceAtLeast(160f)
    }
    val H get() = W * 9f / 16f
    val TILE_Y get() = Y + H + MARGIN
    private fun slots(n: Int) = if (n <= 4) 4 else 8
    fun tileW(n: Int) = (W - GAP * (slots(n) - 1)) / slots(n)
    fun tileH(n: Int) = tileW(n) * 9f / 16f
    fun tileX(i: Int, n: Int) = X + i * (tileW(n) + GAP)
}

/** On the menu panel beside the preview: reports where it really is, since the interface can be scaled and moved. */
fun Modifier.stageBeside(): Modifier = onGloballyPositioned { c ->
    var root = c
    while (true) root = root.parentLayoutCoordinates ?: break
    val k = root.size.width / 960f
    if (k > 0f) {
        val b = c.boundsInWindow()
        Stage.place(floatArrayOf(b.left / k, b.top / k, b.right / k, b.bottom / k, b.width / k / 256f))
    }
}

/**
 * The Bg workspace: the monitor shows the scene being browsed or built, with its nodes as live tiles underneath, while
 * what's applied stays untouched. Browsing changes only the monitor. OK applies. Right arrow opens the builder.
 */
@Composable
fun BgTab(onApply: (BgScene) -> Unit, onBuild: () -> Unit, onNew: () -> Unit, onFiles: () -> Unit, onExport: () -> Unit, presetsVersion: Int,
          classics: List<String>,
          onStop: () -> Unit) {
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
        val open = openSections[id] ?: (id != "more")
        Row_((if (open) "▾ " else "▸ ") + label + "  ($count)", false, id = "sec:$id", category = true, onFocus = { Bg2.classicView = null }) { openSections[id] = !open }
    }
    var manage by remember { mutableStateOf<String?>(null) }
    var manageFrom by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(manage) { if (manage == null) manageFrom?.let { FocusMem.restoreSoon(it); manageFrom = null } }
    Panel(Modifier.padding(start = 24.dp, bottom = 8.dp).width(256.dp).fillMaxHeight().stageBeside()) {
        Text(browsing.takeIf { it.isNotEmpty() }?.removePrefix("file:")?.plus(" · OK = apply · hold OK = options") ?: "Choose a scene: OK = apply · hold OK = options",
            color = if (browsing.isNotEmpty()) FG else DIM, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp))
        val managed = manage
        if (managed != null) ItemMenu(managed, if (Bg2.mine.any { it.name == managed }) "Rename" else "Rename as my own copy", Bg2.mine.any { it.name == managed },
            onEdit = { manage = null; manageFrom = null; Bg2.pristine(managed)?.let { Bg2.preview = it }; lastBrowsedScene = managed; onBuild() },
            onRename = { n -> val ok = Bg2.rename(ctx, managed, n); if (ok) { lastBrowsedScene = n; browsing = n; manageFrom = "row:scene:$n"; manage = null }; ok },
            onDelete = { Bg2.delete(ctx, managed); manageFrom = "row:bgt:new"; manage = null },
            onClose = { manage = null },
            actions = listOf(
                "✓ Apply to background" to {
                    Bg2.pristine(managed)?.let { onApply(if (Bg2.preview.name == managed) Bg2.preview else it) }
                    manage = null
                },
                (if (Bg2.mine.any { it.name == managed }) "＋ Save a copy" else "＋ Save to My scenes") to {
                    Bg2.pristine(managed)?.let { s ->
                        val saved = Bg2.saveAsNew(ctx, if (Bg2.preview.name == managed) Bg2.preview else s)
                        android.widget.Toast.makeText(ctx, "Saved as \"${saved.name}\" in My scenes", android.widget.Toast.LENGTH_LONG).show()
                        lastBrowsedScene = saved.name; browsing = saved.name; manageFrom = "row:scene:${saved.name}"
                    }
                    manage = null
                }
            ))
        else LoopColumn(state = FocusMem.list("bg:tab"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // always first: apply what the preview shows
            item(key = "apply") {
                val shown = Bg2.preview.name
                Row_("✓ Apply: $shown", Bg2.applied && Bg2.current.name == shown, id = "bgt:apply", category = true, onFocus = { Bg2.classicView = null }) { onApply(Bg2.preview) }
            }
            Drafts.scene?.let { d ->
                item(key = "draft") { Row_("↩ Resume unsaved: " + d.name, false, id = "bgt:resume", onFocus = { Bg2.classicView = null }) { Bg2.preview = d; lastBrowsedScene = d.name; onBuild() } }
                item(key = "draftx") { Row_("🗑 Discard unsaved", false, id = "bgt:discard", onFocus = {}) { Drafts.discardScene(ctx); FocusMem.scope?.launch { FocusMem.restoreSoon("row:bgt:new") } } }
            }
            item { Row_("＋ New scene from scratch", false, id = "bgt:new", onFocus = { Bg2.classicView = null }) { onNew() } }
            item { Row_("◈ Build what's in the preview  ›", false, id = "bgt:build", onFocus = { Bg2.classicView = null }) { onBuild() } }
            SCENE_GROUPS.forEach { group ->
                val list = if (group == MY_SCENES) Bg2.mine else BUILT_IN_SCENES.filter { it.group == group }
                if (list.isNotEmpty()) {
                    item(key = "h:$group") { folder("g:$group", group, list.size) }
                    if (openSections["g:$group"] != false) items(list.map { it.name }.chunked(ICON_COLUMNS), key = { "s:$group:" + it.first() }) { row ->
                        IconRow(row, count = { n -> if (Bg2.applied && Bg2.current.name == n) 1 else 0 }, idOf = { "row:scene:$it" }, height = 40,
                            onHint = { n -> if (n.isNotEmpty()) { Bg2.classicView = null; browsing = n } },
                            onHold = { n -> manageFrom = "row:scene:$n"; manage = n },
                            onPress = { n -> onApply(if (Bg2.preview.name == n) Bg2.preview else (Bg2.pristine(n) ?: list.first { it.name == n })) }
                        ) { n, c, m -> SceneIcon(list.firstOrNull { it.name == n }, c, m) }
                    }
                }
            }
            item(key = "h:classic") { folder("classic", CLASSIC_GROUP, classics.size) }
            if (openSections["classic"] != false) items(classics.chunked(ICON_COLUMNS), key = { "cl:" + it.first() }) { row ->
                IconRow(row, count = { n -> if (Bg2.applied && Bg2.current.group == CLASSIC_GROUP && Bg2.current.name == n.removePrefix("file:")) 1 else 0 }, idOf = { "row:classic:$it" }, height = 40,
                    onHint = { n -> if (n.isNotEmpty()) browsing = n },
                    // classic backgrounds have no rename or delete: holding OK opens the builder
                    onHold = { n -> Bg2.pristine(n)?.let { Bg2.preview = it; lastBrowsedScene = n }; onBuild() },
                    onPress = { n -> Bg2.pristine(n)?.let { onApply(if (Bg2.preview.name == it.name) Bg2.preview else it) } }
                ) { n, c, m -> SceneIcon(Bg2.pristine(n), c, m) }
            }
            item(key = "h:more") { folder("more", "More", 4) }
            if (openSections["more"] == true) {
            item { Row_("■ Stop sound", false, id = "bgt:stop", onFocus = {}) { onStop() } }
            item { Row_("✦ React to sound: " + if (Bg2.reactOn) "on" else "off", false, id = "bgt:react", onFocus = {}) { Bg2.setReact(ctx, !Bg2.reactOn) } }
            item { Row_("📁 Files: your images and videos  ›", false, id = "bgt:files", onFocus = {}) { onFiles() } }
            item { Row_("🎞 Export preview as 15 s video", false, id = "bgt:export", onFocus = {}) { onExport() } }
            }
        }
    }
}

/* ---------------- builder: nodes, then one node's settings. Edits the preview only, until Apply ---------------- */

@Composable
fun BgEditor(onClose: () -> Unit, onApply: (BgScene) -> Unit, onToast: (String) -> Unit, onPickMedia: (Int) -> Unit) {
    val scene = Bg2.preview
    val ctx = LocalContext.current
    // a temporary save a moment after each change, and on the way out, however you leave
    LaunchedEffect(scene) { delay(600); Drafts.noteScene(ctx, scene) }
    DisposableEffect(Unit) { Drafts.builderOpened(true); onDispose { Drafts.noteScene(ctx, Bg2.preview) } }
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
    BackHandler(enabled = node == null && scene != base && !warned) { warned = true; onToast("Not saved: kept as a temporary save. Back again to leave") }
    LaunchedEffect(node == null) { if (node == null) { delay(90); runCatching { first.requestFocus() } } }
    // a preset opens on its first layer, so you start in the picture rather than in a menu
    LaunchedEffect(Unit) { delay(180); scene.layers.firstOrNull()?.let { toTile(it.id) } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.CenterStart).padding(start = 24.dp, top = 10.dp, bottom = 10.dp).width(256.dp).fillMaxHeight().stageBeside()
            .background(Color(0xFF060C14).copy(alpha = (Theme.panelAlpha + 0.3f).coerceAtMost(0.96f)), RoundedCornerShape(14.dp))
            .border(1.dp, CYAN.copy(alpha = 0.25f), RoundedCornerShape(14.dp)).padding(10.dp)
            .onFocusChanged { inSettings = it.hasFocus && node != null }
            .onPreviewKeyEvent { e ->
                if (node == null && e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && M5.editing == null && FocusMem.current?.let { it.startsWith("gen:") || it.startsWith("row:ask:") } != true) {
                    val id = node?.id ?: scene.layers.firstOrNull()?.id
                    if (id != null) { Bg2.adding = false; FocusMem.scope?.launch { FocusMem.restoreSoon("ltile:$id") }; true } else false
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
    var addOpen by Bg2.addingState
    DisposableEffect(Unit) { addOpen = scene.layers.isEmpty(); onDispose { addOpen = false } }
    var confirmDelete by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<Generator?>(null) }
    var hint by remember { mutableStateOf("") }
    var askedFrom by remember { mutableStateOf<String?>(null) }
    val panelList = androidx.compose.foundation.lazy.rememberLazyListState()
    // the box closed: back to the icon it was opened from
    LaunchedEffect(ask) { if (ask == null) askedFrom?.let { FocusMem.restoreSoon(it); askedFrom = null } }
    val isMine = Bg2.mine.any { it.name == scene.name }
    fun count(g: Generator) = scene.layers.count { it.type == g.name }
    fun shortName(n: String) = n.removePrefix(CLASSIC_PREFIX).removePrefix(LINES_PREFIX)
    fun removeLast(g: Generator) {
        val l = scene.layers.lastOrNull { it.type == g.name } ?: return
        val rest = scene.layers.filter { it.id != l.id }.map { o ->
            o.let { if (it["maskBy"].toInt() == l.id) it.set("maskBy", -1f) else it }.let { if (it["warpBy"].toInt() == l.id) it.set("warpBy", -1f) else it }
        }
        Bg2.soloId = -1; Bg2.preview = scene.copy(layers = rest)
        onToast("Removed ${shortName(g.name)}")
    }
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
    val hinted = GENERATORS.firstOrNull { it.name == hint }
    Text(when {
        hinted != null -> shortName(hinted.name) + if (count(hinted) > 0) " · added · OK = options" else " · OK = add"
        scene.layers.isEmpty() -> "Add a layer to begin"
        else -> "→ = go to the layers under the preview"
    }, color = if (hinted != null) FG else DIM, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp))
    val asked = ask
    if (asked != null) AddAgainBox(shortName(asked.name), count(asked), onRemove = { removeLast(asked); ask = null }, onAnother = { add(asked); ask = null }, onCancel = { ask = null })
    else LoopColumn(state = panelList, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { Box(Modifier.focusRequester(first)) { Row_("✓ Apply to background", Bg2.applied && Bg2.current == scene, id = "ed:apply", onFocus = {}) { onApply() } } }
        item { Row_("⛶ Full-screen preview", false, id = "ed:full", onFocus = {}) { Bg2.fullPreview = true; onToast("Any button returns") } }
        item(key = "addmenu") { Row_((if (addOpen) "▾ " else "▸ ") + "＋ Add layer", addOpen, id = "ed:addmenu", category = true, onFocus = {}) { addOpen = !addOpen } }
        if (addOpen) GEN_GROUPS.forEach { (title, belongs) ->
            val list = GENERATORS.filter(belongs).sortedBy { if (it.kind == GenKind.Solid) 1 else 0 }
            val open = openSections["add:$title"] == true
            item(key = "gh:$title") { Row_((if (open) "▾ " else "▸ ") + title + "  (${list.size})", false, id = "gh:$title", category = true, onFocus = {}) { openSections["add:$title"] = !open } }
            if (open) items(list.chunked(ICON_COLUMNS), key = { "gens:" + it.first().name }) { row ->
                IconRow(row.map { it.name }, count = { n -> scene.layers.count { it.type == n } }, idOf = { "gen:$it" }, onHint = { hint = it },
                    onPress = { n ->
                        val g = generator(n)
                        if (count(g) > 0) { askedFrom = "gen:$n"; ask = g } else add(g)
                    }) { n, c, m -> LayerIcon(n, c, m) }
            }
        }
        val saveOpen = openSections["ed:save"] == true
        item(key = "ed:save") { Row_((if (saveOpen) "▾ " else "▸ ") + "Save & reset", false, id = "ed:save", category = true, onFocus = {}) { openSections["ed:save"] = !saveOpen } }
        if (saveOpen) {
        item { Row_("＋ Save as new scene", false, id = "ed:new", onFocus = {}) {
            val s = Bg2.saveAsNew(ctx, scene); onSaved(s); onToast("Saved as \"${s.name}\"")
        } }
        if (isMine) item { Row_("⟳ Save changes", false, id = "ed:over", onFocus = {}) { Bg2.overwrite(ctx, scene); onSaved(scene); onToast("Saved ${scene.name}") } }
        item { Row_("↺ Reset to its saved state", false, id = "ed:reset", onFocus = {}) { Bg2.pristine(scene.name)?.let { Bg2.preview = it; onToast("Reset ${it.name}") } } }
        if (isMine) item { Row_(if (confirmDelete) "🗑 OK again to delete" else "🗑 Delete this scene", confirmDelete, id = "ed:del", onFocus = {}) {
            if (!confirmDelete) confirmDelete = true
            else { val n = scene.name; Bg2.delete(ctx, n); Drafts.discardScene(ctx); confirmDelete = false; onToast("Deleted $n"); onClose() }
        } }
        }
    }
}

private val GEN_GROUPS: List<Pair<String, (Generator) -> Boolean>> = listOf(
    "Classic backgrounds" to { g -> g.name.startsWith(CLASSIC_PREFIX) || g.kind == GenKind.Classic },
    "Line visualizers" to { g -> g.name.startsWith(LINES_PREFIX) },
    "Sacred geometry & solids" to { g -> g.kind == GenKind.Line || g.kind == GenKind.Solid }, "Particles" to { g -> g.kind == GenKind.Points },
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
        Adjust(p.label, p.fmt(v), { edit(l.set(p.key, Stepper.next(v, p.step, -1, p.min, p.max))) }, compact = true, fill = (v - p.min) / (p.max - p.min)) { edit(l.set(p.key, Stepper.next(v, p.step, 1, p.min, p.max))) }
    }
    @Composable fun toggle(label: String, key: String, yes: String = "on", no: String = "off") {
        val flip = { edit(l.set(key, if (l[key] > 0.5f) 0f else 1f)) }
        Adjust(label, if (l[key] > 0.5f) yes else no, { flip() }, compact = true, onOk = { flip() }) { flip() }
    }
    val typeIndex = GENERATORS.indexOfFirst { it.name == l.type }.coerceAtLeast(0)
    val settingsList = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // the list may be scrolled away from its first row, which then doesn't exist to be selected
    DisposableEffect(Unit) {
        Bg2.panelTop = { scope.launch { settingsList.scrollToItem(0); androidx.compose.runtime.withFrameNanos { }; runCatching { first.requestFocus() } } }
        onDispose { Bg2.panelTop = null }
    }
    LaunchedEffect(l.id) { settingsList.scrollToItem(0) }
    fun short(n: String) = n.removePrefix(CLASSIC_PREFIX).removePrefix(LINES_PREFIX)
    var picking by remember { mutableStateOf(false) }
    var confirmRemove by remember(l.id) { mutableStateOf(false) }
    if (picking) {
        fun done() { picking = false; scope.launch { FocusMem.restoreSoon("row:nd:gen") } }
        BackHandler { done() }
        val pickList = androidx.compose.foundation.lazy.rememberLazyListState(typeIndex)
        LaunchedEffect(Unit) { delay(80); FocusMem.restoreSoon("row:gp:" + l.type) }
        Text("CHOOSE A GENERATOR", color = CYAN, fontSize = 11.sp, letterSpacing = 1.2.sp, maxLines = 1)
        Text("OK = use it · Back = keep " + short(l.type), color = DIM, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp))
        LoopColumn(state = pickList, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            items(GENERATORS, key = { it.name }) { g ->
                Row_(short(g.name), g.name == l.type, id = "gp:" + g.name, small = true, onFocus = {}) { edit(l.copy(type = g.name)); done() }
            }
        }
        return
    }
    val panel = "layer"; val top = "Basics"
    fun position(i: Int) = scene.layers.indexOfFirst { it.id == i } + 1
    val placed = listOf("x", "y", "angle", "tiltX", "tiltY").any { l[it] != BgParams.DEFAULTS[it] }
    val linksNow = listOfNotNull(
        if (l["maskBy"].toInt() >= 0) "mask by " + position(l["maskBy"].toInt()) else null,
        if (l["warpBy"].toInt() >= 0) "warp by " + position(l["warpBy"].toInt()) else null,
        if (l["show"] < 0.5f) "hidden" else null
    ).joinToString(" · ").ifEmpty { "none" }
    fun react(n: Int) = if (l["r${n}a"] > 0f) BgParams.SOURCES[l["r${n}s"].toInt()] + " → " + BgParams.TARGETS[l["r${n}t"].toInt()] else null
    val reacts = listOfNotNull(react(1), react(2)).let { r -> if (r.isEmpty()) "off" else r[0] + if (r.size > 1) " +1" else "" }
    Text(short(l.type).uppercase(), color = CYAN, fontSize = 11.sp, letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text((if (M5.editLock) "OK = edit" else "← → change") + " · ↑↓ next · Back = layers", color = DIM, fontSize = 9.sp, modifier = Modifier.padding(bottom = 6.dp))
    LoopColumn(state = settingsList, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        item { Box(Modifier.focusRequester(first)) { toggle("Layer", "on", "On", "Off") } }
        item {
            val only = Bg2.soloId == l.id
            val flip = { Bg2.soloId = if (only) -1 else l.id }
            Adjust("Preview", if (only) "Only this" else "Whole scene", { flip() }, compact = true, onOk = { flip() }) { flip() }
        }
        section(panel, "Basics", top) {
            item { Row_("Generator: " + short(l.type) + "  ›", false, id = "nd:gen", onFocus = {}) { picking = true } }
            if (Solids.isHyper(l.type)) item {
                val d = Solids.dimOf(l.type, l["dim"])
                Adjust("Dimensions", "${d}D", { edit(l.set("dim", (d - 1).coerceAtLeast(4).toFloat())) }, compact = true) { edit(l.set("dim", (d + 1).coerceAtMost(Solids.maxDim(l.type)).toFloat())) }
            }
            if (l.kind == GenKind.Media) {
                item { Row_("📁 File: " + l.media.substringAfterLast('/').ifEmpty { "choose…" }, false, id = "nd:file", onFocus = {}) { onPickMedia() } }
                item {
                    val flipFit = { edit(l.set("fit", if (l["fit"] < 0.5f) 1f else 0f)) }
                    Adjust("Fit", if (l["fit"] < 0.5f) "Fill screen" else "Whole picture", { flipFit() }, compact = true, onOk = { flipFit() }) { flipFit() }
                }
            }
            items(listOf(BgParams.OPACITY, BgParams.SIZE, BgParams.HUE, BgParams.SPEED), key = { "ba:" + it.key }) { slider(it) }
        }
        section(panel, "Position", top, if (placed) "changed" else "default") {
            if (l.kind == GenKind.Media) item {
                val flat = l["persp"] < 0.5f
                val flipView = { edit(if (flat) l.set("persp", 1f).let { if (it["tiltX"] == 0f && it["tiltY"] == 0f) it.set("tiltX", -25f).set("tiltY", 20f) else it } else l.set("persp", 0f)) }
                Adjust("View", if (flat) "Flat" else "Perspective", { flipView() }, compact = true, onOk = { flipView() }) { flipView() }
            }
            val tilts = if (l.kind != GenKind.Media || l["persp"] > 0.5f) listOf(BgParams.TILTX, BgParams.TILTY) else emptyList()
            items(listOf(BgParams.X, BgParams.Y, BgParams.ANGLE, BgParams.SPIN) + tilts, key = { "po:" + it.key }) { slider(it) }
        }
        section(panel, "Color & light", top, "glow " + BgParams.GLOW.fmt(l["glow"])) {
            items(listOf(BgParams.SAT, BgParams.DRIFT, BgParams.GLOW, BgParams.DETAIL, BgParams.FADE, BgParams.TRAIL), key = { "co:" + it.key }) { slider(it) }
        }
        section(panel, "Mixing", top, BgParams.BLENDS[l["blend"].toInt()] + " · " + if (l["fold"].toInt() == 0) "no mirror" else BgParams.FOLDS[l["fold"].toInt()]) {
            item { Adjust("Blend", BgParams.BLENDS[l["blend"].toInt()], { cycle("blend", BgParams.BLENDS.size, -1) }, compact = true) { cycle("blend", BgParams.BLENDS.size, 1) } }
            item { Adjust("Mirror", BgParams.FOLDS[l["fold"].toInt()], { cycle("fold", BgParams.FOLDS.size, -1) }, compact = true) { cycle("fold", BgParams.FOLDS.size, 1) } }
        }
        section(panel, "Links to layers", top, linksNow) {
            item { toggle("Used as", "show", "Visible", "Hidden source") }
            item { Adjust("Mask by", linkName("maskBy"), { link("maskBy", -1) }, compact = true) { link("maskBy", 1) } }
            if (l["maskBy"].toInt() >= 0) item { slider(BgParams.MASK_AMT) }
            item { Adjust("Warp by", linkName("warpBy"), { link("warpBy", -1) }, compact = true) { link("warpBy", 1) } }
            if (l["warpBy"].toInt() >= 0) item { slider(BgParams.WARP_AMT) }
        }
        section(panel, "React to sound", top, reacts) {
            item { Adjust("React 1 from", BgParams.SOURCES[l["r1s"].toInt()], { cycle("r1s", BgParams.SOURCES.size, -1) }, compact = true) { cycle("r1s", BgParams.SOURCES.size, 1) } }
            item { Adjust("React 1 changes", BgParams.TARGETS[l["r1t"].toInt()], { cycle("r1t", BgParams.TARGETS.size, -1) }, compact = true) { cycle("r1t", BgParams.TARGETS.size, 1) } }
            item { slider(BgParams.R1A) }
            item { Adjust("React 2 from", BgParams.SOURCES[l["r2s"].toInt()], { cycle("r2s", BgParams.SOURCES.size, -1) }, compact = true) { cycle("r2s", BgParams.SOURCES.size, 1) } }
            item { Adjust("React 2 changes", BgParams.TARGETS[l["r2t"].toInt()], { cycle("r2t", BgParams.TARGETS.size, -1) }, compact = true) { cycle("r2t", BgParams.TARGETS.size, 1) } }
            item { slider(BgParams.R2A) }
        }
        section(panel, "Layer actions", top) {
            item { Row_("⧉ Duplicate", false, id = "nd:dup", onFocus = {}) {
                if (scene.layers.size < 8) Bg2.preview = scene.copy(layers = scene.layers + l.copy(id = scene.nextId))
            } }
            item { Row_("↺ Reset this layer", false, id = "nd:reset", onFocus = {}) { edit(Layer(l.id, l.type, media = l.media)) } }
            item { Row_(if (confirmRemove) "🗑 OK again to remove" else "🗑 Remove this layer", confirmRemove, id = "nd:rm", onFocus = {}) {
                if (!confirmRemove) confirmRemove = true
                else {
                    val rest = scene.layers.filter { it.id != l.id }.map { o ->
                        o.let { if (it["maskBy"].toInt() == l.id) it.set("maskBy", -1f) else it }.let { if (it["warpBy"].toInt() == l.id) it.set("warpBy", -1f) else it }
                    }
                    Bg2.soloId = -1; onDeleted(); Bg2.preview = scene.copy(layers = rest)
                }
            } }
        }
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
        val plan = Med.planning
        if (plan != null) { if (!Bg2.fullPreview) { StageLabels(tiles = false); JourneyTimeline(plan) } }
        else if (Med.subTab == 1) { if (!Bg2.fullPreview) { StageLabels(tiles = false); MoodStrip() } }
        else if (!Bg2.fullPreview) StageLabels()
    }
}

@Composable
private fun StageLabels(tiles: Boolean = true) {
    val scene = Bg2.preview
    val onScreen = Bg2.applied && Bg2.current == scene
    val n = scene.layers.size
    val soloName = scene.layers.firstOrNull { it.id == Bg2.soloId }?.type
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.offset(Stage.X.dp, Stage.Y.dp).size(Stage.W.dp, Stage.H.dp).border(1.dp, if (onScreen) GREEN.copy(alpha = 0.6f) else CYAN.copy(alpha = 0.35f), RoundedCornerShape(4.dp))) {
            Text(if (soloName != null) "Previewing only " + soloName.removePrefix(CLASSIC_PREFIX).removePrefix(LINES_PREFIX) + " · Apply still uses every layer"
                else scene.name + "   ·   " + if (onScreen) "ON SCREEN" else "PREVIEW, NOT APPLIED",
                color = if (onScreen && soloName == null) GREEN else AMBER, fontSize = 10.sp, maxLines = 1,
                modifier = Modifier.padding(6.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            if (n == 0) Text("Empty scene: add a node from the list", color = DIM, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
        }
        if (tiles) scene.layers.forEachIndexed { i, l -> key(l.id) { LayerTile(scene, l, i, n) } }
    }
}

/**
 * A layer under the monitor. Left is the back layer. In the builder it's a live control:
 * ← → next layer · OK or ↑ = its settings · hold OK = pick up, then ← → move, OK = drop.
 */
@Composable
private fun LayerTile(scene: BgScene, l: Layer, i: Int, n: Int) {
    val x = Stage.tileX(i, n); val w = Stage.tileW(n); val h = Stage.tileH(n)
    val selected = Bg2.building && Bg2.focusNode == l.id
    var focused by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val solo = Bg2.soloId == l.id
    val moving = Bg2.movingNode == l.id
    // a held arrow stops at the last tile; a new press loops round
    fun neighbour(d: Int, repeat: Int) { val n = scene.layers.size; if ((i + d) in 0 until n || repeat == 0) FocusMem.restore("ltile:${scene.layers[(i + d + n) % n].id}") }
    fun move(d: Int) {
        val j = i + d
        if (j in scene.layers.indices) Bg2.preview = scene.copy(layers = scene.layers.toMutableList().apply { add(j, removeAt(i)) })
    }
    val control = if (!Bg2.building) Modifier else Modifier
        .remembered("ltile:${l.id}")
        .focusProperties { canFocus = !Bg2.adding }
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
                    if (!down) { if (held) held = false else Bg2.panelTop?.invoke() }
                    true
                }
                !down -> false
                e.key == Key.DirectionLeft -> { neighbour(-1, e.nativeKeyEvent.repeatCount); true }
                e.key == Key.DirectionRight -> { neighbour(1, e.nativeKeyEvent.repeatCount); true }
                e.key == Key.DirectionUp || e.key == Key.DirectionDown -> { Bg2.panelTop?.invoke(); true }
                else -> false
            }
        }
        .focusable()
    val color = when { moving -> AMBER; solo -> GREEN; focused -> CYAN; selected -> CYAN.copy(alpha = 0.6f); l.on -> CYAN.copy(alpha = 0.3f); else -> DIM.copy(alpha = 0.2f) }
    Box(Modifier.offset(x.dp, Stage.TILE_Y.dp).size(w.dp, h.dp).then(control).background(Color(0xFF0A0F16), RoundedCornerShape(3.dp)).border(if (focused || solo || moving) 2.dp else 1.dp, color, RoundedCornerShape(3.dp))) {
        // a plain picture of what the layer is: the monitor above already shows it moving
        LayerIcon(l.type, if (solo) GREEN else if (focused) FG else if (l.on) CYAN.copy(alpha = 0.8f) else DIM.copy(alpha = 0.4f), Modifier.fillMaxSize().padding(if (n <= 4) 12.dp else 6.dp), l["dim"].toInt())
        if (moving) Text("‹ ›", color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp))
    }
    val links = listOfNotNull(
        scene.layers.firstOrNull { it.id == l["maskBy"].toInt() }?.let { "mask " + (scene.layers.indexOf(it) + 1) },
        scene.layers.firstOrNull { it.id == l["warpBy"].toInt() }?.let { "warp " + (scene.layers.indexOf(it) + 1) }
    ).joinToString(" ")
    Column(Modifier.offset(x.dp, (Stage.TILE_Y + h + 1f).dp).width(w.dp)) {
        Text("${i + 1} " + l.type.removePrefix(CLASSIC_PREFIX).removePrefix(LINES_PREFIX), color = if (solo) GREEN else if (moving) AMBER else if (focused) CYAN else if (l.on) FG else DIM, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (moving) "← → move · OK drop" else if (focused) "OK = settings" else if (!l.on) "off" else if (!l.show) "source only" else (BgParams.BLENDS[l["blend"].toInt()] + if (links.isEmpty()) "" else " · $links"), color = DIM, fontSize = 7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
