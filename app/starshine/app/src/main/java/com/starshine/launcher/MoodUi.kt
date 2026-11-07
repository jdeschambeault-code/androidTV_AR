package com.starshine.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** The two halves of the Bg/Moods Generator. */
@Composable
fun GeneratorTabs() {
    Row(Modifier.padding(start = 24.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Backgrounds", "Moods").forEachIndexed { i, t ->
            var focused by remember { mutableStateOf(false) }
            val on = Med.subTab == i
            Text(t, color = if (on || focused) FG else DIM, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier
                .remembered("sub:$i").onFocusChanged { focused = it.isFocused }.focusOnHover()
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)) { Med.subTab = i; true }
                    else if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                        // entering the list always starts on its first option: apply or play
                        val moods = Med.subTab == 1
                        val list = FocusMem.list(if (moods) "mood:tab" else "bg:tab")
                        FocusMem.scope?.launch {
                            list.scrollToItem(0); androidx.compose.runtime.withFrameNanos { }
                            FocusMem.restoreSoon(if (moods) "row:mt:apply" else "row:bgt:apply")
                        }
                        true
                    } else false
                }
                .focusable()
                .background(if (on) CYAN.copy(alpha = 0.16f) else Color.Transparent, RoundedCornerShape(8.dp))
                .border(1.dp, if (focused) CYAN else if (on) CYAN.copy(alpha = 0.4f) else Color.Transparent, RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 5.dp))
        }
    }
}

private val openMoods = mutableStateMapOf<String, Boolean>()
private val FREE_GROUPS = listOf("My moods", "Meditative", "World-inspired", "Frequencies", "Ambient and instruments")
private val AMBIENT_KINDS = setOf("Rain", "Ocean", "Singing bowl", "Tibetan bells", "Xylophone", "Chimes", "Temple gong", "Tanpura", "Handpan", "Kalimba", "Frame drum")

private fun groupOf(s: AudioStack, freq: Set<String>) = when {
    s.name in MEDITATIVE_MOODS.map { it.name } -> FREE_GROUPS[1]
    s.name in WORLD_MOODS.map { it.name } -> FREE_GROUPS[2]
    s.name in freq -> FREE_GROUPS[3]
    s.layers.size == 1 && s.name in AMBIENT_KINDS -> FREE_GROUPS[4]
    else -> FREE_GROUPS[0]
}

/** Moods, the same way as Backgrounds: browsing shows its layers, OK plays it and you stay in the list, → builds. */
@Composable
fun MoodTab(onBuild: () -> Unit, onStop: () -> Unit) {
    val ctx = LocalContext.current
    val playing = Sound.current
    val freq = remember { Tone.BUILT_IN.map { it.name }.toSet() + Tones.loadPresets(ctx).map { it.name } }
    @Composable fun folder(id: String, count: Int) {
        val open = openMoods[id] != false
        Row_((if (open) "▾ " else "▸ ") + id + "  ($count)", false, id = "msec:$id", category = true, onFocus = {}) { openMoods[id] = !open }
    }
    var manage by remember { mutableStateOf<String?>(null) }
    var manageFrom by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(manage) { if (manage == null) manageFrom?.let { FocusMem.restoreSoon(it); manageFrom = null } }
    var hinted by remember { mutableStateOf("") }
    Panel(Modifier.padding(start = 24.dp, bottom = 8.dp).width(256.dp).fillMaxHeight().stageBeside()) {
        Text(if (hinted.isNotEmpty()) "$hinted · OK = play · hold OK = options" else if (playing.isEmpty()) "Choose a mood: OK = play · hold OK = options" else "Now: $playing" + if (Sound.pausedByOther) " (paused)" else "",
            color = if (hinted.isNotEmpty()) FG else if (playing.isEmpty()) DIM else GREEN, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp))
        val managed = manage
        if (managed != null) ItemMenu(managed, "Rename", Med.stacks.size > 1,
            onEdit = { manage = null; manageFrom = null; Med.stacks.firstOrNull { it.name == managed }?.let { Med.draft = it }; onBuild() },
            onRename = { n -> val ok = Med.renameStack(ctx, managed, n); if (ok) { manageFrom = "row:mood:$n"; manage = null }; ok },
            onDelete = { Med.stacks = Med.stacks.filter { it.name != managed }; Med.saveStacks(ctx); if (Med.draft?.name == managed) Med.draft = Med.stacks.firstOrNull(); manageFrom = "row:mt:new"; manage = null },
            onClose = { manage = null })
        else LoopColumn(state = FocusMem.list("mood:tab"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // always first: play what's selected
            item(key = "apply") {
                val d = Med.draft
                val on = d != null && playing == d.name
                Row_((if (on) "■ Stop: " else "▶ Play: ") + (d?.name ?: "choose a mood below"), on, id = "mt:apply", category = true, onFocus = {}) {
                    if (on) onStop() else d?.let { Sound.playStack(ctx, it, 1f) }
                }
            }
            Drafts.mood?.let { d ->
                item(key = "draft") { Row_("↩ Resume unsaved: " + d.name, false, id = "mt:resume", onFocus = {}) { Med.draft = d; onBuild() } }
                item(key = "draftx") { Row_("🗑 Discard unsaved", false, id = "mt:discard", onFocus = {}) { Drafts.discardMood(ctx); FocusMem.scope?.launch { FocusMem.restoreSoon("row:mt:new") } } }
            }
            item { Row_("＋ New mood from scratch", false, id = "mt:new", onFocus = {}) {
                val n = Med.newName("My mood") { x -> Med.stacks.any { it.name == x } }
                Med.draft = AudioStack(n, emptyList()); onBuild()
            } }
            item { Row_("◈ Build what's selected  ›", false, id = "mt:build", onFocus = {}) { onBuild() } }
            FREE_GROUPS.forEach { g ->
                val list = Med.stacks.filter { groupOf(it, freq) == g }
                if (list.isNotEmpty()) {
                    item(key = "mh:$g") { folder(g, list.size) }
                    if (openMoods[g] != false) items(list.map { it.name }.chunked(ICON_COLUMNS), key = { "ms:" + it.first() }) { row ->
                        IconRow(row, count = { n -> if (playing == n) 1 else 0 }, idOf = { "row:mood:$it" }, height = 40,
                            onHint = { n -> hinted = n; if (n.isNotEmpty()) list.firstOrNull { it.name == n }?.let { Med.draft = it } },
                            onHold = { n -> manageFrom = "row:mood:$n"; manage = n },
                            onPress = { n -> if (playing == n) onStop() else list.firstOrNull { it.name == n }?.let { Sound.playStack(ctx, it, 1f) } }
                        ) { n, c, m -> MoodIcon(list.firstOrNull { it.name == n }, c, m) }
                    }
                }
            }
            item { Row_("■ Stop sound", false, id = "mt:stop", onFocus = {}) { onStop() } }
        }
    }
}

private fun heard(s: AudioStack): AudioStack = if (Med.soloLayer >= 0) s.copy(layers = s.layers.map { if (it.id == Med.soloLayer) it else it.set("on", 0f) }) else s

/** Builds a mood: the layers are tiles under the preview, exactly as for a scene. */
@Composable
fun MoodEditor(onClose: () -> Unit, onToast: (String) -> Unit) {
    val ctx = LocalContext.current
    val stack = Med.draft ?: AudioStack("My mood", emptyList()).also { Med.draft = it }
    val layer = stack.layers.firstOrNull { it.id == Med.focusLayer }
    val first = remember { FocusRequester() }
    LaunchedEffect(stack) { delay(600); Drafts.noteMood(ctx, stack) }
    DisposableEffect(Unit) { Drafts.builderOpened(false); onDispose { Med.draft?.let { Drafts.noteMood(ctx, it) } } }
    var inSettings by remember { mutableStateOf(false) }
    var base by remember { mutableStateOf(stack) }
    var warned by remember { mutableStateOf(false) }
    fun toTile(id: Int) { FocusMem.restore("mtile:$id") }
    DisposableEffect(Unit) {
        Med.moodBuilding = true; Med.moodFirst = first
        onDispose { Med.moodBuilding = false; Med.soloLayer = -1; Med.focusLayer = -1; Med.movingLayer = -1; Med.moodFirst = null }
    }
    BackHandler(enabled = layer != null) { if (inSettings) toTile(layer!!.id) else Med.focusLayer = -1 }
    BackHandler(enabled = layer == null && stack != base && !warned) { warned = true; onToast("Not saved: kept as a temporary save. Back again to leave") }
    LaunchedEffect(layer == null) { if (layer == null) { delay(90); runCatching { first.requestFocus() } } }
    LaunchedEffect(Unit) { delay(180); stack.layers.firstOrNull()?.let { toTile(it.id) } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.CenterStart).padding(start = 24.dp, top = 10.dp, bottom = 10.dp).width(256.dp).fillMaxHeight().stageBeside()
            .background(Color(0xFF060C14).copy(alpha = (Theme.panelAlpha + 0.3f).coerceAtMost(0.96f)), RoundedCornerShape(14.dp))
            .border(1.dp, CYAN.copy(alpha = 0.25f), RoundedCornerShape(14.dp)).padding(10.dp)
            .onFocusChanged { inSettings = it.hasFocus && layer != null }
            .onPreviewKeyEvent { e ->
                if (layer == null && e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && M5.editing == null && FocusMem.current?.let { it.startsWith("md2:k:") || it.startsWith("row:ask:") } != true) {
                    val id = layer?.id ?: stack.layers.firstOrNull()?.id
                    if (id != null) { Med.moodAdding = false; FocusMem.scope?.launch { FocusMem.restoreSoon("mtile:$id") }; true } else false
                } else false
            }) {
            if (layer == null) MoodPanel(stack, first, onToast, onSaved = { s -> Med.draft = s; base = s; warned = false }, onClose = onClose)
            else LayerSettings(stack, layer, first)
        }
    }
}

@Composable
private fun MoodPanel(stack: AudioStack, first: FocusRequester, onToast: (String) -> Unit, onSaved: (AudioStack) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var addOpen by Med.moodAddingState
    DisposableEffect(Unit) { addOpen = stack.layers.isEmpty(); onDispose { addOpen = false } }
    var confirm by remember { mutableStateOf(false) }
    val saved = Med.stacks.any { it.name == stack.name }
    var ask by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf("") }
    var askedFrom by remember { mutableStateOf<String?>(null) }
    val panelList = androidx.compose.foundation.lazy.rememberLazyListState()
    // the box closed: back to the icon it was opened from
    LaunchedEffect(ask) { if (ask == null) askedFrom?.let { FocusMem.restoreSoon(it); askedFrom = null } }
    val listening = Sound.current == stack.name
    fun count(k: String) = stack.layers.count { it.kind == k }
    fun put(n: AudioStack) { Med.draft = n; if (Sound.current == n.name) Sound.playStack(ctx, heard(n), 0.3f) }
    fun add(k: String) { put(stack.copy(layers = stack.layers + AudioLayer(stack.nextId, k))); onToast("Added $k") }
    fun removeLast(k: String) {
        val l = stack.layers.lastOrNull { it.kind == k } ?: return
        Med.soloLayer = -1; put(stack.copy(layers = stack.layers.filter { it.id != l.id })); onToast("Removed $k")
    }
    BackHandler(enabled = addOpen) { addOpen = false; FocusMem.restore("row:md2:add") }
    Text(stack.name.uppercase(), color = CYAN, fontSize = 11.sp, letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(when {
        hint.isNotEmpty() -> hint + if (count(hint) > 0) " · added · OK = options" else " · OK = add"
        stack.layers.isEmpty() -> "Add a layer to begin"
        else -> "→ = go to the layers under the preview"
    }, color = if (hint.isNotEmpty()) FG else DIM, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp))
    val asked = ask
    if (asked != null) AddAgainBox(asked, count(asked), onRemove = { removeLast(asked); ask = null }, onAnother = { add(asked); ask = null }, onCancel = { ask = null })
    else LoopColumn(state = panelList, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { Box(Modifier.focusRequester(first)) { Row_(if (listening) "■ Stop listening" else "▶ Listen", listening, id = "md2:play", onFocus = {}) { if (listening) Sound.stop() else Sound.playStack(ctx, heard(stack), 0.5f) } } }
        item(key = "add") { Row_((if (addOpen) "▾ " else "▸ ") + "＋ Add layer", addOpen, id = "md2:add", category = true, onFocus = {}) { addOpen = !addOpen } }
        if (addOpen) {
            listOf("Tones" to listOf("Tone", "Noise"), "Instruments" to listOf("Didgeridoo", "Singing bowl", "Tibetan bells", "Xylophone", "Chimes", "Temple gong", "Tanpura", "Handpan", "Kalimba", "Frame drum"), "Ambient" to listOf("Rain", "Ocean")).forEach { (title, kinds) ->
                val open = openMoods["add:$title"] == true
                item(key = "gh:$title") { Row_((if (open) "▾ " else "▸ ") + title + "  (${kinds.size})", false, id = "mgh:$title", category = true, onFocus = {}) { openMoods["add:$title"] = !open } }
                if (open) items(kinds.chunked(ICON_COLUMNS), key = { "ks:" + it.first() }) { row ->
                    IconRow(row, count = { k -> stack.layers.count { it.kind == k } }, idOf = { "md2:k:$it" }, onHint = { hint = it },
                        onPress = { k -> if (count(k) > 0) { askedFrom = "md2:k:$k"; ask = k } else add(k) }) { k, c, m -> WaveIcon(k, c, m) }
                }
            }
        }
        val saveOpen = openMoods["ed:save"] == true
        item(key = "ed:save") { Row_((if (saveOpen) "▾ " else "▸ ") + "Save & reset", false, id = "md2:save", category = true, onFocus = {}) { openMoods["ed:save"] = !saveOpen } }
        if (saveOpen) {
        item { Row_("＋ Save as new mood", false, id = "md2:new", onFocus = {}) {
            val n = if (Med.stacks.any { it.name == stack.name }) Med.newName(stack.name.replace(Regex("\\s+\\d+$"), "")) { x -> Med.stacks.any { it.name == x } } else stack.name
            val s = stack.copy(name = n); Med.putStack(ctx, s); onSaved(s); onToast("Saved as \"$n\"")
        } }
        if (saved) item { Row_("⟳ Save changes", false, id = "md2:over", onFocus = {}) { Med.putStack(ctx, stack); onSaved(stack); onToast("Saved ${stack.name}") } }
        if (saved) item { Row_("↺ Reset to its saved state", false, id = "md2:reset", onFocus = {}) { Med.stacks.firstOrNull { it.name == stack.name }?.let { Med.draft = it; onToast("Reset ${it.name}") } } }
        if (saved && Med.stacks.size > 1) item { Row_(if (confirm) "🗑 OK again to delete" else "🗑 Delete this mood", confirm, id = "md2:del", onFocus = {}) {
            if (!confirm) confirm = true
            else { Med.stacks = Med.stacks.filter { it.name != stack.name }; Med.saveStacks(ctx); Drafts.discardMood(ctx); Med.draft = Med.stacks.firstOrNull(); onToast("Deleted ${stack.name}"); onClose() }
        } }
        }
    }
}

private fun f(v: Float, d: Int) = String.format(Locale.US, "%.${d}f", v)

@Composable
private fun LayerSettings(stack: AudioStack, l: AudioLayer, first: FocusRequester) {
    val ctx = LocalContext.current
    fun put(n: AudioStack) { Med.draft = n; if (Sound.current == n.name) Sound.playStack(ctx, heard(n), 0.3f) }
    fun edit(n: AudioLayer) = put(stack.copy(layers = stack.layers.map { if (it.id == n.id) n else it }))
    @Composable fun num(key: String, label: String, step: Float, d: Int, min: Float, max: Float, unit: String = "") {
        val v = l[key]
        Adjust(label, f(v, d) + unit, { edit(l.set(key, Stepper.next(v, step, -1, min, max))) }, compact = true, fill = (v - min) / (max - min)) { edit(l.set(key, Stepper.next(v, step, 1, min, max))) }
    }
    @Composable fun toValue(key: String, from: String, label: String, step: Float) {
        val v = l[key]
        Adjust(label, if (v < 0f) "no change" else f(v, 2) + " Hz", { edit(l.set(key, if (v < 0f) l[from] else Stepper.next(v, step, -1, -1f, 22000f))) }, compact = true) { edit(l.set(key, if (v < 0f) l[from] else Stepper.next(v, step, 1, -1f, 22000f))) }
    }
    // one row for a precise value: OK changes how far each arrow press moves it
    val stepOf = remember { androidx.compose.runtime.mutableStateMapOf<String, Float>() }
    @Composable fun stepped(key: String, label: String, steps: List<Float>, max: Float, min: Float = 0f) {
        val v = l[key]
        val step = stepOf[key] ?: steps[0]
        Adjust(label, f(v, 3) + " Hz ±" + step.toString().removeSuffix(".0"), { edit(l.set(key, Stepper.next(v, step, -1, min, max))) }, compact = true,
            onOk = { stepOf[key] = steps[(steps.indexOf(step) + 1) % steps.size] }) { edit(l.set(key, Stepper.next(v, step, 1, min, max))) }
    }
    @Composable fun stepHint() = Text((if (M5.editLock) "Hold OK" else "OK") + " changes the step", color = DIM, fontSize = 9.sp, modifier = Modifier.padding(start = 12.dp, bottom = 3.dp))
    var confirmRemove by remember(l.id) { mutableStateOf(false) }
    val panel = "mood"; val top = "Sound"
    Text(l.kind.uppercase(), color = CYAN, fontSize = 11.sp, letterSpacing = 1.2.sp, maxLines = 1)
    Text((if (M5.editLock) "OK = edit" else "← → change") + " · ↑↓ next · Back = layers", color = DIM, fontSize = 9.sp, modifier = Modifier.padding(bottom = 6.dp))
    val settingsList = FocusMem.list("moodset:" + l.kind)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    DisposableEffect(Unit) {
        Med.panelTop = { scope.launch { settingsList.scrollToItem(0); androidx.compose.runtime.withFrameNanos { }; runCatching { first.requestFocus() } } }
        onDispose { Med.panelTop = null }
    }
    LaunchedEffect(l.id) { settingsList.scrollToItem(0) }
    LoopColumn(state = settingsList, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        item { Box(Modifier.focusRequester(first)) {
            val flip = { edit(l.set("on", if (l.on) 0f else 1f)) }
            Adjust("Layer", if (l.on) "On" else "Off", { flip() }, compact = true, onOk = { flip() }) { flip() }
        } }
        item {
            val only = Med.soloLayer == l.id
            val flip = {
                Med.soloLayer = if (only) -1 else l.id
                if (Sound.current == stack.name) Sound.playStack(ctx, heard(stack), 0.3f)
            }
            Adjust("Hear", if (only) "Only this" else "Whole mood", { flip() }, compact = true, onOk = { flip() }) { flip() }
        }
        section(panel, "Sound", top, "level " + f(l["level"], 2)) {
            item { num("level", "Level", 0.05f, 2, 0f, 1f) }
            item { num("pan", "Left / right", 0.05f, 2, -1f, 1f) }
        }
        when (l.kind) {
            "Tone" -> {
                section(panel, "Carrier", top, f(l["carrier"], 3) + " Hz") {
                    item { stepped("carrier", "Carrier", listOf(1f, 0.01f, 0.001f), 22000f, 1f) }
                    item { stepHint() }
                    item { toValue("carrierTo", "carrier", "Slowly moves to", 0.01f) }
                }
                section(panel, "Beat", top, f(l["beat"], 3) + " Hz") {
                    item { stepped("beat", "Beat", listOf(0.1f, 0.01f, 0.001f), 100f) }
                    item { stepHint() }
                    item { toValue("beatTo", "beat", "Beat moves to", 0.01f) }
                }
                section(panel, "Over time", top, f(l["minutes"], 1) + " min") {
                    item { num("minutes", "Change takes", 0.1f, 1, 0.1f, 240f, " min") }
                    item { num("wander", "Living motion", 0.05f, 2, 0f, 1f) }
                    item { num("follow", "Follows the visuals", 0.05f, 2, 0f, 1f) }
                    item { num("harmonic", "2nd harmonic", 0.05f, 2, 0f, 1f) }
                }
            }
            "Didgeridoo" -> section(panel, "Instrument", top, f(l["note"], 0) + " Hz") {
                item { num("note", "Drone note", 1f, 0, 30f, 160f, " Hz") }
                item { num("mouth", "Mouth movement", 0.05f, 2, 0f, 1f) }
                item { num("mouthRate", "Mouth speed", 0.05f, 2, 0.05f, 6f, " Hz") }
                item { num("breath", "Breath", 0.05f, 2, 0f, 1f) }
            }
            "Singing bowl", "Tibetan bells", "Xylophone", "Chimes", "Temple gong", "Tanpura", "Handpan", "Kalimba", "Frame drum" -> section(panel, "Instrument", top, f(l["pitch"], 0) + " Hz") {
                item { num("pitch", "Pitch", 1f, 0, 60f, 880f, " Hz") }
                item { num("every", "A strike about every", 0.5f, 1, 0.5f, 120f, " s") }
            }
        }
        section(panel, "Layer actions", top) {
            item { Row_("⧉ Duplicate", false, id = "ls:dup", onFocus = {}) { put(stack.copy(layers = stack.layers + l.copy(id = stack.nextId))) } }
            item { Row_("↺ Reset this layer", false, id = "ls:reset", onFocus = {}) { edit(AudioLayer(l.id, l.kind)) } }
            item { Row_(if (confirmRemove) "🗑 OK again to remove" else "🗑 Remove this layer", confirmRemove, id = "ls:rm", onFocus = {}) {
                if (!confirmRemove) confirmRemove = true
                else { Med.soloLayer = -1; Med.focusLayer = -1; put(stack.copy(layers = stack.layers.filter { it.id != l.id })) }
            } }
        }
    }
}

private fun openAudioLayerMenu(ctx: android.content.Context, stack: AudioStack, a: AudioLayer, i: Int) {
    fun put(n: AudioStack) { Med.draft = n; if (Sound.current == n.name) Sound.playStack(ctx, heard(n), 0.3f) }
    val next = stack.layers.getOrNull(i + 1) ?: stack.layers.getOrNull(i - 1)
    val solo = Med.soloLayer == a.id
    HoldMenu.open("${i + 1} " + a.kind, buildList {
        add(HoldMenu.Action("⚙ Settings", focusAfter = null) { Med.panelTop?.invoke() })
        if (stack.layers.size > 1) add(HoldMenu.Action("↔ Move") { Med.movingLayer = a.id })
        add(HoldMenu.Action(if (a.on) "○ Turn off" else "● Turn on") {
            put(stack.copy(layers = stack.layers.map { if (it.id == a.id) it.set("on", if (a.on) 0f else 1f) else it }))
        })
        add(HoldMenu.Action(if (solo) "◉ Hear the whole mood" else "◉ Hear only this") {
            Med.soloLayer = if (solo) -1 else a.id
            if (Sound.current == stack.name) Sound.playStack(ctx, heard(stack), 0.3f)
        })
        add(HoldMenu.Action("⧉ Duplicate") { put(stack.copy(layers = stack.layers + a.copy(id = stack.nextId))) })
        add(HoldMenu.Action("🗑 Remove", confirm = true, focusAfter = next?.let { "mtile:${it.id}" }) {
            Med.soloLayer = -1; put(stack.copy(layers = stack.layers.filter { it.id != a.id }))
        })
    })
}

/** The mood's layers under the preview, left to right. In the builder they're live controls, like visual layers. */
@Composable
fun MoodStrip() {
    val ctx = LocalContext.current
    val stack = Med.draft
    Box(Modifier.offset(Stage.X.dp, (Stage.TILE_Y - 4f).dp).size(Stage.W.dp, 104.dp).background(Color.Black)) {
        stack?.layers?.firstOrNull { it.id == Med.soloLayer }?.let {
            Text("Hearing only " + it.kind + " · Saving keeps every layer", color = AMBER, fontSize = 9.sp, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(4.dp))
        }
        if (stack == null || stack.layers.isEmpty()) Text("No layers yet", color = DIM, fontSize = 10.sp, modifier = Modifier.padding(6.dp))
        else stack.layers.forEachIndexed { i, a ->
            key(a.id) {
                val n = stack.layers.size
                val slots = maxOf(n, 4)
                val w = (Stage.W - 4f * (slots - 1)) / slots
                val focusedHere = Med.moodBuilding && Med.focusLayer == a.id
                var focused by remember { mutableStateOf(false) }
                var tapping by remember { mutableStateOf(false) }   // only a press that began here and wasn't held opens the settings
                val solo = Med.soloLayer == a.id; val moving = Med.movingLayer == a.id
                fun neighbour(d: Int, repeat: Int) { val n = stack.layers.size; if ((i + d) in 0 until n || repeat == 0) FocusMem.restore("mtile:${stack.layers[(i + d + n) % n].id}") }
                fun move(d: Int) { val j = i + d; if (j in stack.layers.indices) Med.draft = stack.copy(layers = stack.layers.toMutableList().apply { add(j, removeAt(i)) }) }
                val control = if (!Med.moodBuilding) Modifier else Modifier
                    .remembered("mtile:${a.id}")
                    .focusProperties { canFocus = !Med.moodAdding }
                    .onFocusChanged { focused = it.isFocused; if (it.isFocused) Med.focusLayer = a.id else if (Med.movingLayer == a.id) Med.movingLayer = -1 }
                    .focusOnHover()
                    .onPreviewKeyEvent { e ->
                        val down = e.type == KeyEventType.KeyDown
                        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                        when {
                            Med.movingLayer == a.id -> {
                                if (down) when {
                                    e.key == Key.DirectionLeft -> move(-1); e.key == Key.DirectionRight -> move(1)
                                    ok && e.nativeKeyEvent.repeatCount == 0 -> Med.movingLayer = -1
                                    e.key == Key.Back -> Med.movingLayer = -1
                                }
                                true
                            }
                            ok -> {
                                if (down && e.nativeKeyEvent.repeatCount == 0) tapping = true
                                if (down && e.nativeKeyEvent.repeatCount == 1) { tapping = false; openAudioLayerMenu(ctx, stack, a, i) }
                                if (!down && tapping) { tapping = false; Med.panelTop?.invoke() }
                                true
                            }
                            !down -> false
                            e.key == Key.DirectionLeft -> { neighbour(-1, e.nativeKeyEvent.repeatCount); true }
                            e.key == Key.DirectionRight -> { neighbour(1, e.nativeKeyEvent.repeatCount); true }
                            e.key == Key.DirectionUp || e.key == Key.DirectionDown -> { Med.panelTop?.invoke(); true }
                            else -> false
                        }
                    }
                    .focusable()
                val c = when { moving -> AMBER; solo -> GREEN; focused -> CYAN; focusedHere -> CYAN.copy(alpha = 0.6f); a.on -> FG.copy(alpha = 0.7f); else -> DIM.copy(alpha = 0.5f) }
                Column(Modifier.offset((i * (w + 4f)).dp, 4.dp).width(w.dp).then(control).border(if (focused || solo || moving) 2.dp else 1.dp, c, RoundedCornerShape(6.dp)).padding(5.dp)) {
                    WaveIcon(a.kind, c, Modifier.width((w - 10f).dp).height(34.dp))
                    Text(a.kind, color = c, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (moving) "← → move" else if (focused) "OK = settings" else "level " + f(a["level"], 2), color = DIM, fontSize = 7.sp, maxLines = 1)
                }
            }
        }
    }
}
