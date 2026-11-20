package com.starshine.launcher

import android.view.ViewGroup
import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
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
    /** Room kept free on the right for another panel (the Meditation presets' column), in the 960-wide units. */
    var reserveRight by androidx.compose.runtime.mutableFloatStateOf(0f)
    /** Where a panel on the right (the Meditation presets' column) really starts, in the 960-wide units; 0 = none.
     *  Measured, not guessed: the screen-edge margin and the interface scale move it. */
    var rightEdge by androidx.compose.runtime.mutableFloatStateOf(0f)

    /** Moods and journeys show a fixed strip under the preview. Scenes show layer tiles, which shrink with it. */
    private val fixedStrip get() = Med.subTab == 1 || Med.planning != null
    private const val STRIP = 104f
    private const val TILE_LABEL = 20f
    val X get() = panel[2] + MARGIN
    val Y get() = panel[1]
    val W: Float get() {
        val contentRight = panel[0] - 24f * panel[4] + 960f * panel[4]
        val wide = minOf(contentRight, 960f - MARGIN, if (rightEdge > 0f) rightEdge - MARGIN else Float.MAX_VALUE) - X - reserveRight * panel[4]
        val tall = panel[3] - Y
        // height = preview + what's under it, so the width follows from whichever runs out first
        val fit = if (fixedStrip) (tall - STRIP - MARGIN) / (9f / 16f)
        else (tall - TILE_LABEL - MARGIN + GAP * 3f / 4f * 9f / 16f) / ((9f / 16f) * 1.25f)
        return minOf(wide, fit).coerceAtLeast(160f)
    }
    val H get() = W * 9f / 16f
    val TILE_Y get() = Y + H + MARGIN
    /** Up to 4 layers get big tiles; more get 8 per row, and the row scrolls to follow the selected one. */
    fun slots(n: Int) = if (n <= 4) 4 else 8
    /** The first layer shown in the row (it scrolls when the selection goes past either end). */
    private var first by androidx.compose.runtime.mutableIntStateOf(0)
    fun start(n: Int) = first.coerceIn(0, maxOf(0, n - slots(n)))
    fun visible(i: Int, n: Int) = (i - start(n)) in 0 until slots(n)
    fun follow(i: Int, n: Int) { val s = slots(n); val f = start(n); first = (if (i < f) i else if (i >= f + s) i - s + 1 else f).coerceIn(0, maxOf(0, n - s)) }
    fun tileW(n: Int) = (W - GAP * (slots(n) - 1)) / slots(n)
    fun tileH(n: Int) = tileW(n) * 9f / 16f
    /** Off-row tiles are parked far off screen: still there to take the selection, never under the cursor. */
    fun tileX(i: Int, n: Int) = if (visible(i, n)) X + (i - start(n)) * (tileW(n) + GAP) else -4000f
}

/** On a panel to the right of the preview: the preview stops before it. */
fun Modifier.stageRight(): Modifier = onGloballyPositioned { c ->
    var root = c
    while (true) root = root.parentLayoutCoordinates ?: break
    val k = root.size.width / 960f
    if (k > 0f) { val l = c.boundsInWindow().left / k; if (kotlin.math.abs(l - Stage.rightEdge) > 0.5f) Stage.rightEdge = l }
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
fun BgTab(onApply: (BgScene) -> Unit, onBuild: () -> Unit, onNew: (Boolean) -> Unit, onFiles: () -> Unit, onExport: () -> Unit, presetsVersion: Int,
          classics: List<String>,
          onStop: () -> Unit, space: Boolean = false) {
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
    var asking by remember { mutableStateOf(false) }   // "New scene": flat or space?
    LaunchedEffect(manage) { if (manage == null) manageFrom?.let { FocusMem.restoreSoon(it); manageFrom = null } }
    Panel(Modifier.padding(start = 24.dp, bottom = 8.dp).width(256.dp).fillMaxHeight().stageBeside()) {
        Text(browsing.takeIf { it.isNotEmpty() }?.removePrefix("file:")?.plus(" · OK = apply · hold OK = options") ?: "Choose a scene: OK = apply · hold OK = options or edit",
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
        else LoopColumn(state = FocusMem.list(if (space) "sp:tab" else "bg:tab"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // always first: apply what the preview shows
            item(key = "apply") {
                val shown = Bg2.preview.name
                // hold OK = edit the scene shown (the one last browsed)
                Row_("✓ Apply: $shown", Bg2.applied && Bg2.current.name == shown, id = "bgt:apply", category = true,
                    onHold = { Bg2.classicView = null; lastBrowsedScene = shown; onBuild() }, onFocus = { Bg2.classicView = null }) { onApply(Bg2.preview) }
            }
            // hold OK on a scene opens its options, Edit among them: there's no separate row for building what's shown
            // a new scene: one question, flat or space
            item { Row_((if (asking) "▾ " else "") + "＋ New scene", false, id = "bgt:new", onFocus = { Bg2.classicView = null }) { asking = !asking } }
            if (asking) {
                item { Row_("   ▭ Flat: lighter, for pictures and fields", false, id = "bgt:newflat", small = true, onFocus = {}) { asking = false; onNew(false) } }
                item { Row_("   ◈ Space: camera, depth, particles", false, id = "bgt:newspace", small = true, onFocus = {}) { asking = false; onNew(true) } }
            }
            // the 3D scenes tab lists 3D scenes only, the Backgrounds tab flat ones
            // one library for flat and 3D scenes; a 3D scene carries a small 3D badge on its icon
            (SCENE_GROUPS + SPACE_GROUP + DOWNLOADED).forEach { group ->
                val list = when (group) { MY_SCENES -> Bg2.mine; DOWNLOADED -> Packs.scenes; LANDSCAPES -> Landscapes.available(); else -> BUILT_IN_SCENES.filter { it.group == group } }
                if (list.isNotEmpty()) {
                    item(key = "h:$group") { folder("g:$group", group, list.size) }
                    if (openSections["g:$group"] != false) items(list.map { it.name }.chunked(ICON_COLUMNS), key = { "s:$group:" + it.first() }) { row ->
                        IconRow(row, count = { n -> if (Bg2.applied && Bg2.current.name == n) 1 else 0 }, idOf = { "row:scene:$it" }, height = 40, label = { it },
                            onHint = { n -> if (n.isNotEmpty()) { Bg2.classicView = null; browsing = n } },
                            onHold = { n -> manageFrom = "row:scene:$n"; manage = n },
                            onPress = { n -> onApply(if (Bg2.preview.name == n) Bg2.preview else (Bg2.pristine(n) ?: list.first { it.name == n })) }
                        ) { n, c, m -> BadgedSceneIcon(list.firstOrNull { it.name == n }, c, m) }
                    }
                }
            }
            item(key = "h:classic") { folder("classic", CLASSIC_GROUP, classics.size) }
            if (openSections["classic"] != false) items(classics.chunked(ICON_COLUMNS), key = { "cl:" + it.first() }) { row ->
                IconRow(row, count = { n -> if (Bg2.applied && Bg2.current.group == CLASSIC_GROUP && Bg2.current.name == n.removePrefix("file:")) 1 else 0 }, idOf = { "row:classic:$it" }, height = 40,
                    label = { it.removePrefix("file:") },
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
            // unsaved work sits at the end of the list, out of the way of browsing
            Drafts.scene?.let { d ->
                item(key = "draft") { Row_("↩ Resume unsaved: " + d.name, false, id = "bgt:resume", onFocus = { Bg2.classicView = null }) { Bg2.preview = d; lastBrowsedScene = d.name; onBuild() } }
                item(key = "draftx") { Row_("🗑 Discard unsaved", false, id = "bgt:discard", onFocus = {}) { Drafts.discardScene(ctx); FocusMem.scope?.launch { FocusMem.restoreSoon("row:bgt:new") } } }
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Bg2.panelFirst = first
    var base by remember { mutableStateOf(scene) }      // last applied or saved state, to know if anything would be lost
    var warned by remember { mutableStateOf(false) }
    var inSettings by remember { mutableStateOf(false) }
    fun edit(l: Layer) { Bg2.preview = scene.copy(layers = scene.layers.map { if (it.id == l.id) l else it }) }
    fun toTile(id: Int) { FocusMem.restore("ltile:$id") }
    DisposableEffect(Unit) { Bg2.building = true; onDispose { Bg2.building = false; Bg2.soloId = -1; Bg2.focusNode = -1; Bg2.movingNode = -1; Bg2.panelFirst = null; Bg2.exprTarget = null; Bg2.subPanel = false } }
    // settings -> its layer tile -> the scene rows -> leave
    BackHandler(enabled = node != null) {
        if (inSettings) toTile(node!!.id) else { Bg2.focusNode = -1 }
    }
    // leaving with unsaved work asks what to do with it
    var leaving by remember { mutableStateOf(false) }
    val unsaved = scene.layers.isNotEmpty() && Bg2.pristine(scene.name) != scene
    BackHandler(enabled = node == null && unsaved && !leaving) { leaving = true }
    LaunchedEffect(node == null) { if (node == null) { delay(90); runCatching { first.requestFocus() } } }
    // a preset opens on its first layer, so you start in the picture rather than in a menu
    LaunchedEffect(Unit) { delay(180); scene.layers.firstOrNull()?.let { toTile(it.id) } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.CenterStart).padding(start = 24.dp, top = 10.dp, bottom = 10.dp).width(256.dp).fillMaxHeight().stageBeside()
            .background(Color(0xFF060C14).copy(alpha = (Theme.panelAlpha + 0.3f).coerceAtMost(0.96f)), RoundedCornerShape(14.dp))
            .border(1.dp, CYAN.copy(alpha = 0.25f), RoundedCornerShape(14.dp)).padding(10.dp)
            .onFocusChanged { inSettings = it.hasFocus && node != null }
            .onPreviewKeyEvent { e ->
                if (node == null && e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && M5.editing == null && FocusMem.current?.let { c -> listOf("gen:", "row:ask:", "row:lv:", "row:fx:", "adj:").any { c.startsWith(it) } } != true) {
                    val id = node?.id ?: scene.layers.firstOrNull()?.id
                    if (id != null) { Bg2.adding = false; FocusMem.scope?.launch { FocusMem.restoreSoon("ltile:$id") }; true } else false
                } else false
            }) {
            if (node == null && leaving) DisposableEffect(Unit) { Bg2.subPanel = true; onDispose { Bg2.subPanel = false } }
            if (node == null && leaving) LeaveBox(scene.name, Bg2.mine.any { it.name == scene.name },
                onSave = { Bg2.overwrite(ctx, scene); onToast("Saved ${scene.name}"); leaving = false; onClose() },
                onSaveNew = { val s = Bg2.saveAsNew(ctx, scene); Drafts.discardScene(ctx); Bg2.preview = s; lastBrowsedScene = s.name; onToast("Saved as \"${s.name}\""); leaving = false; onClose() },
                onDiscard = { Drafts.discardScene(ctx); Bg2.preview = Bg2.pristine(scene.name) ?: Bg2.current; lastBrowsedScene = Bg2.preview.name; onToast("Changes discarded"); leaving = false; onClose() },
                onStay = { leaving = false; scope.launch { delay(60); runCatching { first.requestFocus() } } })
            else if (node == null) ScenePanel(scene, first, onToast = onToast, onPickMedia = onPickMedia,
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
    fun shortName(n: String) = Space.short(n)
    fun removeLast(g: Generator) {
        val l = scene.layers.lastOrNull { it.type == g.name } ?: return
        val rest = scene.layers.filter { it.id != l.id }.map { o ->
            o.let { if (it["maskBy"].toInt() == l.id) it.set("maskBy", -1f) else it }.let { if (it["warpBy"].toInt() == l.id) it.set("warpBy", -1f) else it }
        }
        Bg2.soloId = -1; Bg2.preview = scene.copy(layers = rest)
        onToast("Removed ${shortName(g.name)}")
    }
    fun add(g: Generator) {
        // a 3D scene can hold more: most of its elements are cheap point clouds and lines
        val limit = Bg2.maxLayers(scene.space)
        if (scene.layers.size >= limit) { onToast("A scene can hold up to $limit"); return }
        val slow = scene.layers.size + 1 > Bg2.comfyLayers(scene.space)
        // in 3D, a new element appears where the camera looks (the centre of the world, 5 m ahead to start with)
        val cam = scene.layers.firstOrNull { it.type == CAMERA }
        val at = if (scene.space && cam != null && g.name != "Stars" && !Space.isPanel(g.name)) mapOf("x" to cam["tx"], "y" to cam["ty"], "z" to cam["tz"]) else emptyMap()
        val l = Layer(scene.nextId, g.name, if (g.kind == GenKind.Media) mapOf("blend" to 2f) else if (scene.space) Space.defaults(g.name) + at else emptyMap())
        // a whole background belongs at the back; it can still be moved
        val base = g.name.startsWith(CLASSIC_PREFIX) || g.kind == GenKind.Classic
        Bg2.preview = scene.copy(layers = if (base) listOf(l) + scene.layers else scene.layers + l)
        onToast("Added ${g.name.removePrefix(CLASSIC_PREFIX)}" + (if (base) " as the first layer" else "") + (if (slow) " · past ${Bg2.comfyLayers(scene.space)} the pod may slow down" else ""))
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
        // coming back from the layers, saving is the first question: applying waits until the scene list (✓ Apply there)
        val saveOpen = openSections["ed:save"] != false
        item(key = "ed:save") { Box(Modifier.focusRequester(first)) { Row_((if (saveOpen) "▾ " else "▸ ") + "Save & reset", false, id = "ed:save", category = true, onFocus = {}) { openSections["ed:save"] = !saveOpen } } }
        if (saveOpen) {
        if (isMine) item { Row_("⟳ Save changes", false, id = "ed:over", onFocus = {}) { Bg2.overwrite(ctx, scene); onSaved(scene); onToast("Saved ${scene.name}") } }
        item { Row_("＋ Save as new scene", false, id = "ed:new", onFocus = {}) {
            val s = Bg2.saveAsNew(ctx, scene); onSaved(s); onToast("Saved as \"${s.name}\"")
        } }
        item { Row_("↺ Reset to its saved state", false, id = "ed:reset", onFocus = {}) { Bg2.pristine(scene.name)?.let { Bg2.preview = it; onToast("Reset ${it.name}") } } }
        if (isMine) item { Row_(if (confirmDelete) "🗑 OK again to delete" else "🗑 Delete this scene", confirmDelete, id = "ed:del", onFocus = {}) {
            if (!confirmDelete) confirmDelete = true
            else { val n = scene.name; Bg2.delete(ctx, n); Drafts.discardScene(ctx); confirmDelete = false; onToast("Deleted $n"); onClose() }
        } }
        }
        item { Row_("⛶ Full-screen preview", false, id = "ed:full", onFocus = {}) { Bg2.fullPreview = true; onToast("Any button returns") } }
        item(key = "addmenu") { Row_((if (addOpen) "▾ " else "▸ ") + if (scene.space) "＋ Add element" else "＋ Add layer", addOpen, id = "ed:addmenu", category = true, onFocus = {}) { addOpen = !addOpen } }
        if (addOpen) (if (scene.space) Space.ADD_GROUPS else GEN_GROUPS).forEach { (title, belongs) ->
            val list = GENERATORS.filter(belongs).sortedBy { if (it.kind == GenKind.Solid || generator(Space.inner(it.name)).kind == GenKind.Solid) 1 else 0 }
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
    }
}

private val GEN_GROUPS: List<Pair<String, (Generator) -> Boolean>> = listOf(
    "Fractals" to { g -> g.name == FRACTAL },
    "Classic backgrounds" to { g -> g.name.startsWith(CLASSIC_PREFIX) || g.kind == GenKind.Classic },
    "Line visualizers" to { g -> g.name.startsWith(LINES_PREFIX) },
    "Sacred geometry & solids" to { g -> g.kind == GenKind.Line || g.kind == GenKind.Solid }, "Particles" to { g -> g.kind == GenKind.Points },
    "Fields & light" to { g -> g.kind == GenKind.Screen && g.name != FRACTAL }, "Your files" to { g -> g.kind == GenKind.Media }
)

/**
 * The Render tab: camera and render effects for every background, in the order a camera applies them. They're global:
 * scenes, journeys and the builder's preview all go through the same camera. X / Y pairs are grouped under one name.
 */
@Composable
fun RenderTab() {
    val ctx = LocalContext.current
    // "every scene" edits the global effects; "this scene" edits the look saved with the scene on the monitor, which then
    // replaces the global one while that scene plays. Antialiasing is a device setting and always stays global.
    val scene = Bg2.preview.name
    val own = Bg2.sceneLooks[scene]
    var forScene by remember(scene) { mutableStateOf(own != null) }
    val fx = if (forScene) Bg2.fxFor(scene) else Bg2.fx
    fun set(m: Map<String, Float>) {
        if (!forScene) { Bg2.setFx(ctx, m); return }
        Bg2.setSceneLook(ctx, scene, m)
        val dev = m.filterKeys { it in RenderFx.DEVICE_KEYS }
        if (dev.isNotEmpty() && dev != Bg2.fx.filterKeys { it in RenderFx.DEVICE_KEYS }) Bg2.setFx(ctx, Bg2.fx.filterKeys { it !in RenderFx.DEVICE_KEYS } + dev)
    }
    fun put(k: String, v: Float) = if (forScene && k in RenderFx.DEVICE_KEYS) Bg2.setFx(ctx, Bg2.fx + (k to v)) else set(fx + (k to v))
    @Composable fun slider(p: BgParams.P, mini: String? = null) {
        val v = RenderFx.get(fx, p.key)
        Adjust(p.label, p.fmt(v), { put(p.key, Stepper.next(v, p.step, -1, p.min, p.max)) }, compact = true, fill = (v - p.min) / (p.max - p.min), mini = mini) {
            put(p.key, Stepper.next(v, p.step, 1, p.min, p.max))
        }
    }
    Panel(Modifier.padding(start = 24.dp, bottom = 8.dp).width(256.dp).fillMaxHeight().stageBeside()) {
    Text((if (forScene) "This scene's own look: " else "Every scene: ") + RenderFx.summary(fx), color = DIM, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp))
    var confirmLook by remember { mutableStateOf("") }
    LoopColumn(state = FocusMem.list("render:tab"), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        // what the settings below change: the global effects, or a look saved with the scene on the monitor
        item {
            fun flip() {
                // a new scene look starts from the global one, so switching shows no jump
                if (!forScene && Bg2.sceneLooks[scene] == null) Bg2.setSceneLook(ctx, scene, Bg2.fx)
                forScene = !forScene
            }
            Adjust("Applies to", if (forScene) "this scene · $scene" else "every scene", { flip() }, compact = true, onOk = { flip() }) { flip() }
        }
        if (forScene) item { Row_("✕ Remove this scene's own look (use the global one)", false, id = "fx:unscene", onFocus = {}) {
            Bg2.setSceneLook(ctx, scene, null); forScene = false
        } }
        // looks: whole sets of effects, the three built-in ones then yours. OK uses one; the sliders below fine-tune it.
        item { Header("Looks") }
        val allLooks = listOf(RenderFx.Look("None", emptyMap())) + RenderFx.BUILT_IN_LOOKS + Bg2.looks
        items(allLooks, key = { "look:" + it.name }) { lk ->
            val mine = Bg2.looks.any { it.name == lk.name }
            Row_((if (RenderFx.same(fx, lk.fx)) "● " else "   ") + lk.name + if (mine && confirmLook == lk.name) "  · OK again to delete" else "",
                RenderFx.same(fx, lk.fx), id = "look:" + lk.name, small = true, onFocus = { if (confirmLook != lk.name) confirmLook = "" }) {
                // pressing the look already in use, if it's one of yours, asks to delete it
                when {
                    !RenderFx.same(fx, lk.fx) -> set(lk.fx)
                    mine && confirmLook == lk.name -> { Bg2.saveLooks(ctx, Bg2.looks.filter { it.name != lk.name }); confirmLook = "" }
                    mine -> confirmLook = lk.name
                }
            }
        }
        item { Row_("＋ Save these settings as a look", false, id = "look:save", onFocus = {}) {
            var n = 1; while (allLooks.any { it.name == "My look $n" }) n++
            Bg2.saveLooks(ctx, Bg2.looks + RenderFx.Look("My look $n", fx))
            android.widget.Toast.makeText(ctx, "Saved as \"My look $n\"", android.widget.Toast.LENGTH_SHORT).show()
        } }
        item { Header("Edges") }
        item { slider(RenderFx.AA) }
        item { Header("Light") }
        item { slider(RenderFx.BLOOM) }
        item { slider(RenderFx.THRESH) }
        item { slider(RenderFx.GLARE) }
        item { slider(RenderFx.FLARE) }
        item { AxisGroup("Flare light", "where the light sits") }
        item { slider(RenderFx.FLX, "X ←→") }
        item { slider(RenderFx.FLY, "Y ↕") }
        item { slider(RenderFx.GHOSTS) }
        item { Header("Lens") }
        item { slider(RenderFx.DOF) }
        item { AxisGroup("Focus point", "sharp around here") }
        item { slider(RenderFx.FOCX, "X ←→") }
        item { slider(RenderFx.FOCY, "Y ↕") }
        item { slider(RenderFx.FOCR, "Range") }
        item { slider(RenderFx.CHROMA) }
        item { slider(RenderFx.DISTORT) }
        item { Header("Shutter") }
        item { slider(RenderFx.MOTION) }
        item { Header("Color grade") }
        item { slider(RenderFx.EXPOSURE) }
        item { slider(RenderFx.CONTRAST) }
        item { slider(RenderFx.SAT) }
        item { slider(RenderFx.TEMP) }
        item { slider(RenderFx.GAMMA) }
        item { Header("Film") }
        item { slider(RenderFx.VIGNETTE) }
        item { slider(RenderFx.GRAIN) }
        item { slider(RenderFx.SHARPEN) }
        item { Row_("↺ Reset all effects", false, id = "fx:reset", onFocus = {}) { set(emptyMap()) } }
    }
    }
}

@Composable
private fun NodeSettings(scene: BgScene, l: Layer, first: FocusRequester, edit: (Layer) -> Unit, onPickMedia: () -> Unit, onDeleted: () -> Unit) {
    val sp = scene.space; val isCam = l.type == CAMERA
    val others = scene.layers.filter { it.id != l.id }
    fun cycle(key: String, size: Int, d: Int) = edit(l.set(key, (((l[key].toInt() + d) % size + size) % size).toFloat()))
    fun link(key: String, d: Int) {
        val ids = listOf(-1) + others.map { it.id }
        val i = ids.indexOf(l[key].toInt()).coerceAtLeast(0)
        edit(l.set(key, ids[(i + d + ids.size) % ids.size].toFloat()))
    }
    fun linkName(key: String) = others.firstOrNull { it.id == l[key].toInt() }?.type ?: "none"
    // hold OK on any setting: animate it with a formula (Expr)
    // (the expression being edited lives in Bg2.exprTarget, tied to this layer's id)
    @Composable fun slider(p: BgParams.P, mini: String? = null, step: Float = p.step) {
        val v = l[p.key]
        val s = if (step == p.step) BgParams.posStep(p, v) else step
        Adjust(p.label, p.fmt(v) + if (l.ex.containsKey(p.key)) " ƒ" else "", { edit(l.set(p.key, Stepper.next(v, s, -1, p.min, p.max))) }, compact = true, fill = (v - p.min) / (p.max - p.min), mini = mini,
            // the tiles are locked at once, before this row goes: otherwise the selection falls on the first tile
            onHold = { Bg2.subPanel = true; Bg2.exprTarget = l.id to p }) { edit(l.set(p.key, Stepper.next(v, s, 1, p.min, p.max))) }
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
    fun short(n: String) = Space.short(n)
    // the layer the generator list was opened for: tied to its id, so a selection that slips onto another tile can't retarget it
    var picking by remember(l.id) { mutableStateOf(false) }
    var confirmRemove by remember(l.id) { mutableStateOf(false) }
    if (picking) {
        // the tiles stay locked while the list is open (as for expressions), or the selection falls on the first tile
        DisposableEffect(Unit) { Bg2.subPanel = true; onDispose { Bg2.subPanel = false } }
        fun done() { picking = false; scope.launch { FocusMem.restoreSoon("row:nd:gen") } }
        BackHandler { done() }
        val pickList = androidx.compose.foundation.lazy.rememberLazyListState(typeIndex)
        LaunchedEffect(Unit) { delay(80); FocusMem.restoreSoon("row:gp:" + l.type) }
        Text("CHOOSE A GENERATOR", color = CYAN, fontSize = 11.sp, letterSpacing = 1.2.sp, maxLines = 1)
        Text("OK = use it · Back = keep " + short(l.type), color = DIM, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp))
        LoopColumn(state = pickList, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            items(GENERATORS.filter { (it.kind == GenKind.Space) == scene.space && it.name != CAMERA }, key = { it.name }) { g ->
                Row_(short(g.name), g.name == l.type, id = "gp:" + g.name, small = true, onFocus = {}) { edit(l.copy(type = g.name)); done() }
            }
        }
        return
    }
    Bg2.exprTarget?.let { (id, p) ->
        if (id == l.id) { ExprPanel(l, p, edit) { Bg2.exprTarget = null; scope.launch { delay(80); FocusMem.restoreSoon("adj:" + p.label) } }; return }
        // another layer got the selection while an expression is open: go back to the layer it belongs to
        androidx.compose.runtime.SideEffect { Bg2.focusNode = id }
    }
    var mapsOpen by remember(l.id) { mutableStateOf(false) }
    if (mapsOpen) {
        ColorMapEditor(l["pal"].toInt(), onPick = { edit(l.set("pal", it.toFloat())) }) { mapsOpen = false; scope.launch { delay(60); FocusMem.restoreSoon("row:nd:maps") } }
        return
    }
    // the camera opens on its own section, remembered apart from the layers' (which open on Basics)
    val panel = if (isCam) "camera" else if (sp && !Space.isPanel(l.type)) "element" else "layer"; val top = if (isCam) "Camera" else if (l.type == Particles.TYPE) "Emitter" else "Look"   // the same first section, flat or 3D
    fun position(i: Int) = scene.layers.indexOfFirst { it.id == i } + 1
    val placed = listOf("x", "y", "z", "angle", "tiltX", "tiltY", "stx", "sty", "stz").any { l[it] != BgParams.DEFAULTS[it] }
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
        item { Box(Modifier.focusRequester(first)) { if (isCam) Row_("🎥 Camera: position, aim, lens, orbit", false, id = "nd:cam", onFocus = {}) { Sections.set("camera", "Camera", true, "Camera") } else toggle(if (sp) "Element" else "Layer", "on", "On", "Off") } }
        item {
            val only = Bg2.soloId == l.id
            val flip = { Bg2.soloId = if (only) -1 else l.id }
            Adjust("Preview", if (only) "Only this" else "Whole scene", { flip() }, compact = true, onOk = { flip() }) { flip() }
        }
        if (isCam) section(panel, "Camera", top, if (l["free"] > 0.5f) "free look" else "looks at a point") {
            item { toggle("Looks", "free", "freely (uses Rotation)", "at a point") }
            if (l["free"] < 0.5f) {
                item(key = "cam:tg") { AxisGroup("Looks at", "the point the camera faces and orbits") }
                item(key = "cam:tx") { slider(Space.TX, "X ←→") }
                item(key = "cam:ty") { slider(Space.TY, "Y ↕") }
                item(key = "cam:tz") { slider(Space.TZ, "Z depth") }
            }
            item(key = "cam:fov") { slider(Space.FOV) }
            item(key = "cam:orbit") { slider(Space.ORBIT) }
            item(key = "cam:reset") { Row_("↺ Reset the camera (it can't be deleted)", false, id = "nd:camreset", onFocus = {}) { edit(Space.camera().copy(id = l.id)) } }
        }
        // 3D elements: their own settings, in place of the flat layers' Basics and Color & light
        val element = sp && !isCam && !Space.isPanel(l.type)
        // a particle system is set up like Maya's: where and how particles are emitted, the fields that move them, how they look
        if (element && l.type == Particles.TYPE) {
            section(panel, "Emitter", top, Particles.SHAPE.fmt(l["peShape"]) + " · " + Particles.RATE.fmt(l["peRate"])) {
                item(key = "pe:presets") { AxisGroup("Start from", "OK on one: its settings replace these (position and size stay)") }
                items(Particles.PRESETS.map { it.first }.chunked(2), key = { "pe:p:" + it.first() }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { n -> Box(Modifier.weight(1f)) { Row_("✦ $n", false, id = "pe:p:$n", small = true, onFocus = {}) {
                            Particles.PRESETS.firstOrNull { it.first == n }?.let { (_, v) -> edit(l.copy(v = l.v.filterKeys { !it.startsWith("pe") } + Particles.DEFAULTS.filterKeys { it.startsWith("pe") } + v)) }
                        } } }
                    }
                }
                item(key = "pe:emit") { AxisGroup("Emitter", "where particles are born, how many, which way, how fast") }
                items(Particles.EMITTER, key = { "el:" + it.key }) { p -> slider(p) }
            }
            section(panel, "Fields", top, "gravity " + Particles.GRAVITY.fmt(l["peGrav"])) {
                items(Particles.FIELDS, key = { "el:" + it.key }) { p -> slider(p) }
            }
            section(panel, "Particles", top, Particles.LOOK.fmt(l["peLook"]) + " · up to " + Particles.count(l)) {
                items(Particles.LOOKING, key = { "el:" + it.key }) { p -> slider(p) }
                item { Text("Fractal look: each particle is a tiny Julia set of its own; Fractal shape morphs them all. Hold OK on any setting to animate it.", color = DIM, fontSize = 9.sp, modifier = Modifier.padding(6.dp)) }
            }
        }
        else if (element) section(panel, "Look", top) {
            items(Space.attrs(l.type), key = { "el:" + it.key + it.label }) { p -> slider(p) }
            // (Lens flare is one of the sun's own settings above: it was also listed in a separate Sun section)
            if (l.type == "Sun") item { Text("The first sun lights every planet, tints the 3D objects and throws a lens flare when the camera sees it.", color = DIM, fontSize = 9.sp, modifier = Modifier.padding(6.dp)) }
        }
        if (!isCam && !element) section(panel, "Look", top) {
            item { Row_("Generator: " + short(l.type) + "  ›", false, id = "nd:gen", onFocus = {}) { Bg2.subPanel = true; picking = true } }
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
                // the point of the picture that stays put when it turns or grows: its centre, an edge or a corner
                item {
                    val i = l["pivot"].toInt().coerceIn(0, PIVOTS.lastIndex)
                    fun to(k: Int) = edit(l.set("pivot", ((k + PIVOTS.size) % PIVOTS.size).toFloat()))
                    Adjust("Pivot", PIVOTS[i], { to(i - 1) }, compact = true, onOk = { to(i + 1) }) { to(i + 1) }
                }
            }
            items(listOf(BgParams.OPACITY, BgParams.SIZE, BgParams.HUE, BgParams.SPEED), key = { "ba:" + it.key }) { slider(it) }
        }
        if (l.type == FRACTAL) section(panel, "Fractal", top, Fractal.SETS[l["fset"].toInt().coerceIn(0, Fractal.SETS.lastIndex)].name) {
            item {
                val i = l["fset"].toInt().coerceIn(0, Fractal.SETS.lastIndex)
                Adjust("Set", "${i + 1}/${Fractal.SETS.size} " + Fractal.SETS[i].name, { edit(Fractal.applySet(l, i - 1)) }, compact = true) { edit(Fractal.applySet(l, i + 1)) }
            }
            item {
                val maps = Fractal.maps; val i = l["pal"].toInt().coerceIn(0, maps.lastIndex)
                Adjust("Color map", maps[i].name, { edit(l.set("pal", ((i - 1 + maps.size) % maps.size).toFloat())) }, compact = true) { edit(l.set("pal", ((i + 1) % maps.size).toFloat())) }
            }
            item {
                val v = l["f3d"].toInt().coerceIn(0, Fractal.VIEWS.lastIndex)
                // the first time a 3D view is chosen, the mesh is tipped back so its relief can be seen
                fun pick(n: Int) = edit(l.set("f3d", n.toFloat()).let { if (n > 0 && v == 0 && it["tiltX"] == 0f && it["tiltY"] == 0f) it.set("tiltX", -55f) else it })
                Adjust("View", Fractal.VIEWS[v], { pick((v - 1 + Fractal.VIEWS.size) % Fractal.VIEWS.size) }, compact = true) { pick((v + 1) % Fractal.VIEWS.size) }
            }
            if (l["f3d"] > 0.5f) item { slider(Fractal.HEIGHT) }
            item { Row_("🎨 Create or edit color maps  ›", false, id = "nd:maps", onFocus = {}) { Bg2.subPanel = true; mapsOpen = true } }
            item { slider(Fractal.CDENS) }
            item { slider(Fractal.CCYCLE) }
            item { slider(BgParams.DETAIL, "Iterations (detail)") }
            item { AxisGroup("Start point", "where the flight begins") }
            item { slider(Fractal.F0X, "X ←→", Fractal.stepAt(l["f0z"])) }
            item { slider(Fractal.F0Y, "Y ↕", Fractal.stepAt(l["f0z"])) }
            item { slider(Fractal.F0Z, "Zoom") }
            item { AxisGroup("Lead point", "what the flight is guided to") }
            item { slider(Fractal.F1X, "X ←→", Fractal.stepAt(l["f1z"])) }
            item { slider(Fractal.F1Y, "Y ↕", Fractal.stepAt(l["f1z"])) }
            item { slider(Fractal.F1Z, "Zoom") }
            item { Row_("⇄ Swap start and lead", false, id = "nd:fswap", onFocus = {}) {
                edit(l.set("f0x", l["f1x"]).set("f0y", l["f1y"]).set("f0z", l["f1z"]).set("f1x", l["f0x"]).set("f1y", l["f0y"]).set("f1z", l["f0z"]))
            } }
            item { slider(Fractal.TRIP) }
            item { slider(Fractal.HOLD) }
        }
        section(panel, "Move · scale · rotate", top, if (placed) "changed" else "default") {
            if (l.kind == GenKind.Media) item {
                val flat = l["persp"] < 0.5f
                val flipView = { edit(if (flat) l.set("persp", 1f).let { if (it["tiltX"] == 0f && it["tiltY"] == 0f) it.set("tiltX", -25f).set("tiltY", 20f) else it } else l.set("persp", 0f)) }
                Adjust("View", if (flat) "Flat" else "Perspective", { flipView() }, compact = true, onOk = { flipView() }) { flipView() }
            }
            val geo = l.kind == GenKind.Line || l.kind == GenKind.Solid
            // a panel starts fitted to the camera's frame and moves with it; position is then relative to the camera
            if (sp && Space.isPanel(l.type)) item(key = "po:world") { toggle("Panel", "world", "stays in the world", "stays on the camera") }
            item(key = "ag:pos") { AxisGroup("Position", "X ←→ · Y ↕ · Z depth") }
            item(key = "po:x") { slider(BgParams.X, "X ←→") }
            item(key = "po:y") { slider(BgParams.Y, "Y ↕") }
            item(key = "po:z") { slider(BgParams.Z, "Z depth") }
            item(key = "ag:scale") { AxisGroup("Scale") }
            item(key = "po:size") { slider(BgParams.SIZE, "All axes") }
            item(key = "po:stx") { slider(BgParams.STX, "X ←→") }
            item(key = "po:sty") { slider(BgParams.STY, "Y ↕") }
            if (geo) item(key = "po:stz") { slider(BgParams.STZ, "Z depth") }
            item(key = "ag:rot") { AxisGroup("Rotation") }
            item(key = "po:rx") { slider(BgParams.TILTX, "X (tips forward)") }
            item(key = "po:ry") { slider(BgParams.TILTY, "Y (turns sideways)") }
            item(key = "po:rz") { slider(BgParams.ANGLE, "Z (turns flat)") }
            // turning is an expression now (hold OK on a rotation → Keeps turning); the old setting shows only where it's set
            if (l["spin"] != 0f) item(key = "po:spin") { slider(BgParams.SPIN, "Keeps turning (older way: set 0, use an expression)") }
            item(key = "po:reset") { Row_("↺ Back to the middle, flat", false, id = "nd:place", onFocus = {}) {
                edit(listOf("x", "y", "z", "angle", "tiltX", "tiltY", "stx", "sty", "stz").fold(l) { a, k -> a.set(k, BgParams.DEFAULTS[k] ?: 0f) })
            } }
        }
        if (!isCam && !element) section(panel, "Color & light", top, "glow " + BgParams.GLOW.fmt(l["glow"])) {
            items(listOf(BgParams.SAT, BgParams.DRIFT, BgParams.GLOW, BgParams.DETAIL, BgParams.FADE, BgParams.TRAIL), key = { "co:" + it.key }) { slider(it) }
        }
        if (!sp || Space.isPanel(l.type)) section(panel, "Mixing", top, BgParams.BLENDS[l["blend"].toInt()] + " · " + (if (l["fold"].toInt() == 0) "no mirror" else BgParams.FOLDS[l["fold"].toInt()]) + if (l["mshape"] > 0.5f) " · " + BgParams.MASKS[l["mshape"].toInt().coerceIn(0, BgParams.MASKS.lastIndex)] else "") {
            item { Adjust("Blend", BgParams.BLENDS[l["blend"].toInt()], { cycle("blend", BgParams.BLENDS.size, -1) }, compact = true) { cycle("blend", BgParams.BLENDS.size, 1) } }
            item { Adjust("Mirror", BgParams.FOLDS[l["fold"].toInt()], { cycle("fold", BgParams.FOLDS.size, -1) }, compact = true) { cycle("fold", BgParams.FOLDS.size, 1) } }
            // the shape mask cuts the layer: part of how it mixes into the scene
            item(key = "mx:mask") { AxisGroup("Shape mask", "cuts the layer by a shape") }
            item {
                val m = l["mshape"].toInt().coerceIn(0, BgParams.MASKS.lastIndex); val n = BgParams.MASKS.size
                Adjust("Mask shape", "${m}/${n - 1} " + BgParams.MASKS[m], { edit(l.set("mshape", ((m - 1 + n) % n).toFloat())) }, compact = true) { edit(l.set("mshape", ((m + 1) % n).toFloat())) }
            }
            if (l["mshape"] > 0.5f) {
                item { slider(BgParams.MTHR) }
                item { slider(BgParams.MSOFT) }
                item { toggle("Keep", "minv", "outside the shape", "inside the shape") }
                item { slider(BgParams.MSCALE) }
                item { slider(BgParams.MROT) }
                item { Text("Hold OK on the threshold to animate it: the shape opens and closes.", color = DIM, fontSize = 9.sp, modifier = Modifier.padding(6.dp)) }
            }
        }
        if (!sp) section(panel, "Links to layers", top, linksNow) {
            item { toggle("Used as", "show", "Visible", "Hidden source") }
            item { Adjust("Mask by", linkName("maskBy"), { link("maskBy", -1) }, compact = true) { link("maskBy", 1) } }
            if (l["maskBy"].toInt() >= 0) item { slider(BgParams.MASK_AMT) }
            item { Adjust("Warp by", linkName("warpBy"), { link("warpBy", -1) }, compact = true) { link("warpBy", 1) } }
            if (l["warpBy"].toInt() >= 0) item { slider(BgParams.WARP_AMT) }
        }
        // sound now connects through expressions (hold OK on a setting); this stays only for layers that already use it
        // every layer and 3D element: react to the music (shown even when off, with one-press starting points). For a
        // formula on any single setting, hold OK on it → Connect to sound
        if (!isCam) section(panel, "React to sound", top, reacts) {
            item(key = "rx:quick") {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.weight(1f)) { Row_("♪ Pulse", false, id = "rx:pulse", small = true, onFocus = {}) { edit(l.copy(v = l.v + mapOf("r1s" to 2f, "r1t" to 0f, "r1a" to 0.5f, "r2s" to 5f, "r2t" to 3f, "r2a" to 0.3f))) } }
                    Box(Modifier.weight(1f)) { Row_("♪ Dance", false, id = "rx:dance", small = true, onFocus = {}) { edit(l.copy(v = l.v + mapOf("r1s" to 5f, "r1t" to 2f, "r1a" to 0.6f, "r2s" to 3f, "r2t" to 4f, "r2a" to 0.4f))) } }
                    Box(Modifier.weight(1f)) { Row_("Off", false, id = "rx:off", small = true, onFocus = {}) { edit(l.copy(v = l.v + mapOf("r1a" to 0f, "r2a" to 0f))) } }
                }
            }
            if (!Bg2.reactOn) item { Text("React to sound is off for the whole screen: turn it on in Quick settings (✧) to see this.", color = AMBER, fontSize = 9.sp, modifier = Modifier.padding(6.dp)) }
            item { Adjust("React 1 from", BgParams.SOURCES[l["r1s"].toInt()], { cycle("r1s", BgParams.SOURCES.size, -1) }, compact = true) { cycle("r1s", BgParams.SOURCES.size, 1) } }
            item { Adjust("React 1 changes", BgParams.TARGETS[l["r1t"].toInt()], { cycle("r1t", BgParams.TARGETS.size, -1) }, compact = true) { cycle("r1t", BgParams.TARGETS.size, 1) } }
            item { slider(BgParams.R1A) }
            item { Adjust("React 2 from", BgParams.SOURCES[l["r2s"].toInt()], { cycle("r2s", BgParams.SOURCES.size, -1) }, compact = true) { cycle("r2s", BgParams.SOURCES.size, 1) } }
            item { Adjust("React 2 changes", BgParams.TARGETS[l["r2t"].toInt()], { cycle("r2t", BgParams.TARGETS.size, -1) }, compact = true) { cycle("r2t", BgParams.TARGETS.size, 1) } }
            item { slider(BgParams.R2A) }
        }
        section(panel, "Animation", top, LayerAnim.summary(l)) {
            for (i in 0 until LayerAnim.SLOTS) {
                val m = LayerAnim.mode(l, i)
                if (m == 0) continue
                val p = LayerAnim.target(l, i)
                fun put(s: String, x: Float) = edit(l.set(LayerAnim.k(i, s), x))
                item(key = "an:h:$i") { AxisGroup("${i + 1} · " + p.label, LayerAnim.MODE_HELP[m]) }
                item(key = "an:t:$i") {
                    val ti = LayerAnim.TARGETS.indexOf(p); val n = LayerAnim.TARGETS.size
                    // a new setting gets an amount that suits its range
                    fun to(j: Int) { val q = LayerAnim.TARGETS[(j + n) % n]; edit(l.set(LayerAnim.k(i, "t"), ((j + n) % n).toFloat()).set(LayerAnim.k(i, "a"), (q.max - q.min) * 0.25f)) }
                    Adjust("Animation ${i + 1} setting", p.label, { to(ti - 1) }, compact = true, mini = "Animates") { to(ti + 1) }
                }
                item(key = "an:m:$i") {
                    val n = LayerAnim.MODES.size
                    // never cycles to Off here: removing is its own row
                    Adjust("Animation ${i + 1} mode", LayerAnim.MODES[m], { put("m", (if (m <= 1) n - 1 else m - 1).toFloat()) }, compact = true, mini = "How") { put("m", (if (m >= n - 1) 1 else m + 1).toFloat()) }
                }
                item(key = "an:a:$i") {
                    val a = l[LayerAnim.k(i, "a")]; val span = p.max - p.min
                    Adjust("Animation ${i + 1} amount", (if (a >= 0f) "+" else "") + p.fmt(a),
                        { put("a", Stepper.next(a, p.step, -1, -span * 4f, span * 4f)) }, compact = true, mini = if (m == 1) "By, per cycle" else "By") { put("a", Stepper.next(a, p.step, 1, -span * 4f, span * 4f)) }
                }
                item(key = "an:d:$i") {
                    val d = l[LayerAnim.k(i, "d")].coerceAtLeast(0.1f)
                    val step = if (d < 10f) 0.5f else if (d < 60f) 1f else 5f
                    Adjust("Animation ${i + 1} time", String.format(java.util.Locale.US, if (d < 10f) "%.1f s" else "%.0f s", d), { put("d", (d - step).coerceAtLeast(0.5f)) }, compact = true, mini = "Over") { put("d", (d + step).coerceAtMost(3600f)) }
                }
                if (m == 2 || m == 3 || m == 6) item(key = "an:e:$i") {
                    val e = l[LayerAnim.k(i, "e")].toInt().coerceIn(0, LayerAnim.EASES.lastIndex); val n = LayerAnim.EASES.size
                    Adjust("Animation ${i + 1} easing", LayerAnim.EASES[e], { put("e", ((e - 1 + n) % n).toFloat()) }, compact = true, mini = "Easing") { put("e", ((e + 1) % n).toFloat()) }
                }
                item(key = "an:p:$i") {
                    val ph = l[LayerAnim.k(i, "p")]
                    Adjust("Animation ${i + 1} start", "${Math.round(ph * 100)}% in", { put("p", ((ph - 0.05f) + 1f) % 1f) }, compact = true, mini = "Starts") { put("p", (ph + 0.05f) % 1f) }
                }
                item(key = "an:x:$i") { Row_("   − Remove this animation", false, id = "an:x:$i", small = true, onFocus = {}) { edit(LayerAnim.remove(l, i)) } }
            }
            if (LayerAnim.count(l) < LayerAnim.SLOTS) {
                item(key = "an:add") { Header("Add an animation") }
                // only the presets that change something this element shows
                items(LayerAnim.PRESETS.filter { !sp || Space.isPanel(l.type) || it.key in Space.animKeys(l.type) }, key = { "an:pr:" + it.name }) { pr ->
                    Row_("＋ " + pr.name, false, id = "an:pr:" + pr.name, small = true, onFocus = {}) { LayerAnim.add(l, pr)?.let { edit(it) } }
                }
            } else item(key = "an:full") { Text("All ${LayerAnim.SLOTS} animations in use", color = DIM, fontSize = 9.sp, modifier = Modifier.padding(start = 12.dp)) }
        }
        if (!isCam) section(panel, "Layer actions", top) {
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
        // delete is always in reach, not only inside Layer actions: OK twice
        if (!isCam) item(key = "nd:delete") {
            Row_(if (confirmRemove) "🗑 OK again to delete this layer" else "🗑 Delete this layer", confirmRemove, id = "nd:delete", onFocus = {}) {
                if (!confirmRemove) confirmRemove = true
                else {
                    val rest = scene.layers.filter { it.id != l.id }.map { o ->
                        o.let { if (it["maskBy"].toInt() == l.id) it.set("maskBy", -1f) else it }.let { if (it["warpBy"].toInt() == l.id) it.set("warpBy", -1f) else it }
                    }
                    Bg2.soloId = -1; onDeleted(); Bg2.preview = scene.copy(layers = rest)
                }
            }
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
        else if (Med.subTab == 2) { if (!Bg2.fullPreview) StageLabels(tiles = false) }
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
        // more layers than the row shows: how many are off each end (the row follows the selection)
        if (tiles && n > Stage.slots(n)) {
            val s = Stage.start(n); val after = n - s - Stage.slots(n)
            val y = (Stage.TILE_Y + Stage.tileH(n) + 12f).dp
            if (s > 0) Text("‹ $s more", color = AMBER, fontSize = 8.sp, modifier = Modifier.offset((Stage.X).dp, y + 10.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp))
            if (after > 0) Text("$after more ›", color = AMBER, fontSize = 8.sp, modifier = Modifier.offset((Stage.X + Stage.W - 48f).dp, y + 10.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp))
        }
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
        .focusProperties { canFocus = !Bg2.adding && !Bg2.subPanel }
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) { Bg2.focusNode = l.id; Stage.follow(i, n) } else if (Bg2.movingNode == l.id) Bg2.movingNode = -1 }
        .focusOnHover()
        .onPreviewKeyEvent { e ->
            val down = e.type == KeyEventType.KeyDown
            val ok = e.okKey()
            when {
                Bg2.movingNode == l.id -> {
                    if (down) when {
                        e.key == Key.DirectionLeft -> { move(-1); Stage.follow(i - 1, n) }
                        e.key == Key.DirectionRight -> { move(1); Stage.follow(i + 1, n) }
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
    // a layer that's on shows its own picture here, drawn live by the renderer behind this see-through tile;
    // its icon stays small in the corner. A layer that's off shows just its icon.
    // only layers with their own picture are drawn live into the tile: in a 3D scene that is just the panels
    val live = l.on && (!scene.space || Space.isPanel(l.type))
    Box(Modifier.offset(x.dp, Stage.TILE_Y.dp).size(w.dp, h.dp).then(control).background(if (live) Color.Transparent else Color(0xFF0A0F16), RoundedCornerShape(3.dp)).border(if (focused || solo || moving) 2.dp else 1.dp, color, RoundedCornerShape(3.dp))) {
        val tint = if (solo) GREEN else if (focused) FG else if (l.on) CYAN.copy(alpha = 0.8f) else DIM.copy(alpha = 0.4f)
        if (live) LayerIcon(l.type, tint, Modifier.align(Alignment.TopStart).padding(3.dp).size(if (n <= 4) 16.dp else 11.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(3.dp)).padding(1.dp), l["dim"].toInt())
        else LayerIcon(l.type, tint, Modifier.fillMaxSize().padding(if (n <= 4) 12.dp else 6.dp), l["dim"].toInt())
        if (moving) Text("‹ ›", color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp))
    }
    val links = listOfNotNull(
        scene.layers.firstOrNull { it.id == l["maskBy"].toInt() }?.let { "mask " + (scene.layers.indexOf(it) + 1) },
        scene.layers.firstOrNull { it.id == l["warpBy"].toInt() }?.let { "warp " + (scene.layers.indexOf(it) + 1) }
    ).joinToString(" ")
    Column(Modifier.offset(x.dp, (Stage.TILE_Y + h + 1f).dp).width(w.dp)) {
        Text("${i + 1} " + Space.short(l.type), color = if (solo) GREEN else if (moving) AMBER else if (focused) CYAN else if (l.on) FG else DIM, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (moving) "← → move · OK drop" else if (focused) "OK = settings" else if (!l.on) "off" else if (!l.show) "source only" else (BgParams.BLENDS[l["blend"].toInt()] + if (links.isEmpty()) "" else " · $links"), color = DIM, fontSize = 7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A scene's icon in the one library: 3D scenes carry a small "3D" badge, so flat and space scenes can share every group. */
@Composable
fun BadgedSceneIcon(scene: BgScene?, color: Color, modifier: Modifier) {
    Box(modifier) {
        SceneIcon(scene, color, Modifier.fillMaxSize())
        if (scene?.space == true) Text("3D", color = Color.White, fontSize = 7.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomEnd).background(VIOLET.copy(alpha = 0.85f), RoundedCornerShape(3.dp)).padding(horizontal = 2.dp))
    }
}

/** A picture's pivot: where it turns and grows from (Bg2Renderer.PIVOT_AT has the matching points). */
val PIVOTS = listOf("Centre", "Top left", "Top", "Top right", "Right", "Bottom right", "Bottom", "Bottom left", "Left")
