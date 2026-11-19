package com.starshine.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen brightness that works on the pod. The Rokid glasses take no backlight commands over DisplayPort, so Android's
 * brightness setting changes nothing there. Instead a black shade is laid over the picture: by KeyService (an
 * accessibility overlay, so it covers every app) when that service is on, otherwise inside Starshine only.
 * On see-through glasses a darker picture is also a more transparent one.
 */
object Brightness {
    const val MIN = 0.1f
    var level by mutableFloatStateOf(1f); private set
    /** KeyService is running and draws the shade over every app. */
    var systemWide by mutableStateOf(false)
    fun load(ctx: Context) { level = ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE).getFloat("brightLevel", 1f).coerceIn(MIN, 1f) }
    fun set(ctx: Context, v: Float) {
        level = v.coerceIn(MIN, 1f)
        ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE).edit().putFloat("brightLevel", level).apply()
        KeyService.shade(level)
    }
    /** How dark the shade is for a brightness level. */
    fun alpha(l: Float = level) = ((1f - l) * 0.95f).coerceIn(0f, 0.95f)
}

/** The quick panel's editable tiles, in the order chosen. "q:edit" is always last and can't be removed. */
object QuickTiles {
    /** id, icon, name: everything that can be in the grid. */
    val ALL = listOf(
        Triple("q:bgon", "🌌", "Background"), Triple("q:react", "✧", "React to sound"), Triple("q:nextbg", "⏭", "Next scene"),
        Triple("q:saver", "✨", "Screensaver"), Triple("q:ram", "🧹", "Clean apps"), Triple("q:web", "🌐", "Browser"),
        Triple("q:gesture", "✍", "Draw gesture"), Triple("q:settings", "⚙", "Settings"), Triple("q:home", "⌂", "Home"),
        Triple("q:apps", "▦", "Open apps"), Triple("q:voice", "🎙", "Voice"), Triple("q:files", "📁", "Files"),
        Triple("q:stopsound", "■", "Stop sound"), Triple("q:tone", "∿", "Frequencies")
    )
    private val DEFAULT = listOf("q:voice", "q:bgon", "q:react", "q:saver", "q:ram", "q:web", "q:gesture", "q:settings")
    var order by mutableStateOf(DEFAULT)
    fun load(ctx: Context) {
        val raw = ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE).getString("quickTiles", null) ?: return
        order = raw.split(",").filter { id -> ALL.any { it.first == id } }.distinct()
        // once: Voice moved here from the top bar, so it joins saved layouts at the front
        val p = ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE)
        if (!p.getBoolean("quickVoice", false)) { if ("q:voice" !in order) order = listOf("q:voice") + order; p.edit().putBoolean("quickVoice", true).apply(); save(ctx) }
    }
    private fun save(ctx: Context) = ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE).edit().putString("quickTiles", order.joinToString(",")).apply()
    fun remove(ctx: Context, id: String) { order = order - id; save(ctx) }
    fun add(ctx: Context, id: String) { if (id !in order) { order = order + id; save(ctx) } }
    fun move(ctx: Context, id: String, by: Int) {
        val i = order.indexOf(id); val j = (i + by).coerceIn(0, order.lastIndex)
        if (i < 0 || i == j) return
        order = order.toMutableList().apply { removeAt(i); add(j, id) }; save(ctx)
    }
    fun reset(ctx: Context) { order = DEFAULT; save(ctx) }
    fun info(id: String) = ALL.first { it.first == id }
}

private fun openSettings(ctx: Context, i: Intent) = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

private val TILE_BG = Color.White.copy(alpha = 0.07f)
private val BLOCK_BG = Color.White.copy(alpha = 0.05f)

/**
 * A tile that reacts to OK (or an M5 click, which arrives as OK): a press runs [onPress], a hold runs [onHold].
 * Taps work too when "click = OK" is off (touch screens).
 */
@Composable
private fun Modifier.quickKeys(id: String, onFocus: (Boolean) -> Unit, onHold: (() -> Unit)? = null, onKey: ((Key) -> Boolean)? = null, onPress: () -> Unit): Modifier {
    var held by remember { mutableStateOf(false) }
    return this.remembered("chip:$id").onFocusChanged { onFocus(it.isFocused) }.focusOnHover()
        .onPreviewKeyEvent { e ->
            val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
            when {
                ok && e.type == KeyEventType.KeyDown -> { if (e.nativeKeyEvent.repeatCount == 1 && onHold != null) { held = true; onHold() }; true }
                ok && e.type == KeyEventType.KeyUp -> { if (held) held = false else onPress(); true }
                e.type == KeyEventType.KeyDown && onKey != null -> onKey(e.key)
                else -> false
            }
        }
        .pointerInput(Unit) { detectTapGestures { onPress() } }
        .focusable()
}

/**
 * Sizes, from the reference picture (a phone control centre, 1 : 2): four buttons across, each button [Q_GAP] apart and
 * 10 bigger than its icon. Blocks are 2 buttons wide; sliders are 1 wide and 2 tall.
 */
const val Q_ICON = 30
const val Q_TILE = Q_ICON + 10
const val Q_GAP = 8
const val Q_BLOCK = Q_TILE * 2 + Q_GAP
/** Panel width: four buttons, three gaps and the frame's padding (18 each side); its height is twice that. */
const val Q_WIDTH = Q_TILE * 4 + Q_GAP * 3 + 36

/** The line on top of the panel: the selected button's name and state (buttons carry no labels, as in the picture). */
object QuickHint { var text by mutableStateOf("") }

/** An emoji or symbol drawn at about [dp] high. */
@Composable
private fun Glyph(icon: String, dp: Int, color: Color = FG) = Text(icon, fontSize = (dp * 0.8f).sp, color = color, maxLines = 1)

/** A round button, lit when its setting is on (the connectivity block). */
@Composable
private fun RoundToggle(id: String, icon: String, label: String, state: String, on: Boolean, modifier: Modifier = Modifier, onPress: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.size(Q_TILE.dp).quickKeys(id, { focused = it; if (it) QuickHint.text = label + if (state.isNotBlank()) " · $state" else "" }, onPress = onPress)
        .clip(CircleShape).background(if (on) CYAN.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.12f))
        .border(if (focused) 2.dp else 0.dp, if (focused) FG else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
        Glyph(icon, Q_ICON - 6)
    }
}

/** A square button with an icon only. In edit mode it carries a − (remove) or ＋ (add) badge. */
@Composable
private fun SquareTile(id: String, icon: String, label: String, on: Boolean, modifier: Modifier = Modifier, badge: String = "", picked: Boolean = false,
                       state: String = "", size: Int = Q_TILE, onHold: (() -> Unit)? = null, onKey: ((Key) -> Boolean)? = null, onPress: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.size(size.dp)) {
        Box(Modifier.fillMaxSize().quickKeys(id, { focused = it; if (it && label.isNotEmpty()) QuickHint.text = label + if (state.isNotBlank()) " · $state" else "" }, onHold, onKey, onPress)
            .background(if (picked) AMBER.copy(alpha = 0.25f) else if (on) CYAN.copy(alpha = 0.32f) else TILE_BG, RoundedCornerShape((size / 4).dp))
            .border(if (focused || picked) 2.dp else 0.dp, if (picked) AMBER else if (focused) CYAN else Color.Transparent, RoundedCornerShape((size / 4).dp)),
            contentAlignment = Alignment.Center) {
            Glyph(icon, size - 10)
        }
        if (badge.isNotEmpty()) Box(Modifier.align(Alignment.TopEnd).offset(3.dp, (-3).dp).size(13.dp).clip(CircleShape)
            .background(if (badge == "−") Color(0xFFE5484D) else Color(0xFF30A46C)), contentAlignment = Alignment.Center) {
            Text(badge, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * A tall slider like a phone's control centre: ↑ ↓ change it (at either end the arrow moves on to the next item),
 * an M5 click sets it where the cursor is, OK without the cursor on it runs [onOk].
 */
@Composable
private fun VerticalSlider(id: String, icon: String, label: String, value: Float, step: Float, modifier: Modifier = Modifier, onOk: (() -> Unit)? = null, onChange: (Float) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val v = value.coerceIn(0f, 1f)
    Box(modifier.size(Q_TILE.dp, Q_BLOCK.dp).onGloballyPositioned { bounds = it.boundsInWindow() }
        .quickKeys(id, { focused = it; if (it) QuickHint.text = label }, onKey = { k ->
            when (k) {
                Key.DirectionUp -> if (v >= 1f) false else { onChange((v + step).coerceAtMost(1f)); true }
                Key.DirectionDown -> if (v <= 0f) false else { onChange((v - step).coerceAtLeast(0f)); true }
                else -> false
            }
        }) {
            val p = M5.pointer
            if (M5.hoverAt > M5.lastArrowAt && bounds.height > 0f && bounds.contains(p)) onChange(((bounds.bottom - p.y) / bounds.height).coerceIn(0f, 1f))
            else onOk?.invoke()
        }
        .clip(RoundedCornerShape(12.dp)).background(TILE_BG)
        .border(if (focused) 2.dp else 0.dp, if (focused) CYAN else Color.Transparent, RoundedCornerShape(12.dp))) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(v).background(FG.copy(alpha = if (focused) 0.85f else 0.7f)))
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)) { Glyph(icon, Q_ICON - 10, if (v > 0.18f) Color(0xFF0B1520) else FG) }
    }
}

/**
 * Quick edit, laid out like a phone's control centre and opened in the top-left corner: connectivity and what's playing,
 * the timer / mute / sound switches beside tall brightness and volume sliders, then a grid of tiles you can add, remove and
 * reorder (✎ Edit), then the Meditation, Moods and Scenes rows.
 */
@Composable
fun QuickPanel(
    first: FocusRequester, sleepAt: Long, glasses: Boolean,
    bgOn: Boolean, onBgOn: (Boolean) -> Unit, dimOn: Boolean, onDimOn: (Boolean) -> Unit, dim: Float, onDim: (Float) -> Unit, onSoundToggle: () -> Unit,
    onSleep: (Long) -> Unit, onGlasses: (Boolean) -> Unit, onRam: () -> Unit, onStop: () -> Unit, onScene: (BgScene) -> Unit, onSetting: (String) -> Unit,
    onGesture: () -> Unit, onAction: (String) -> Unit, allSettings: String
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { KeyService.forget() } }
    LaunchedEffect(Unit) { QuickTiles.load(ctx); if (KeyService.page != null) { delay(250); FocusMem.restoreSoon("chip:q:page") } }
    val wifi by produceState(Pair(0, ""), Unit) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        while (true) {
            @Suppress("DEPRECATION") val info = runCatching { wm.connectionInfo }.getOrNull()
            val rssi = info?.rssi ?: -127
            val bars = when { rssi > -55 -> 4; rssi > -65 -> 3; rssi > -75 -> 2; rssi > -90 -> 1; else -> 0 }
            val band = info?.frequency?.let { if (it > 4900) "5 GHz" else if (it > 0) "2.4 GHz" else "" } ?: ""
            value = bars to band; delay(10_000)
        }
    }
    val bt by produceState(SystemInfo.Bt(false, emptyList()), Unit) { while (true) { value = withContext(Dispatchers.IO) { SystemInfo.bluetooth(ctx) }; delay(15_000) } }
    val np by produceState<Playing?>(null) { while (true) { value = runCatching { NowPlaying.current(ctx) }.getOrNull(); delay(2_000) } }
    val sleepMin = if (sleepAt > 0) ((sleepAt - System.currentTimeMillis()) / 60000 + 1) else 0
    val playing = Sound.current
    var muted by remember { mutableStateOf(Sound.systemMuted(ctx)) }
    fun setMuted(m: Boolean) { Sound.setSystemMuted(ctx, m); muted = Sound.systemMuted(ctx) }
    var editing by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf("") }
    fun onOff(b: Boolean) = if (b) "On" else "Off"
    fun refocus(id: String) { scope.launch { delay(60); FocusMem.restoreSoon("chip:$id") } }

    /** What each grid tile shows and does. */
    fun tileState(id: String): Pair<Boolean, String> = when (id) {
        "q:bgon" -> bgOn to onOff(bgOn)
        "q:react" -> Bg2.reactOn to onOff(Bg2.reactOn)
        else -> false to ""
    }
    fun run(id: String) = when (id) {
        "q:bgon" -> onBgOn(!bgOn)
        "q:react" -> Bg2.setReact(ctx, !Bg2.reactOn)
        "q:nextbg" -> onAction("bg:+1")
        "q:saver" -> { SystemInfo.startScreensaver(ctx); Unit }
        "q:ram" -> onRam()
        "q:web" -> WebActivity.askAddress(ctx)
        "q:gesture" -> onGesture()
        "q:settings" -> onSetting(allSettings)
        "q:home" -> onAction("ui:home")
        "q:apps" -> onAction("ui:apps")
        "q:voice" -> onAction("ui:voice")
        "q:files" -> onSetting("Files")
        "q:stopsound" -> { Med.playing = null; onStop() }
        "q:tone" -> onAction("ui:tone")
        else -> {}
    }

    val list = FocusMem.list("quick")
    LaunchedEffect(Unit) { if (FocusMem.returnTo == null) list.scrollToItem(0) }
    Column {
        // a message from the last action, until the selection moves; otherwise the selected button's name and state
        LaunchedEffect(QuickHint.text) { hint = "" }
        Text(hint.ifEmpty { QuickHint.text.ifEmpty { if (editing) "OK = remove or add · hold OK = move" else " " } },
            color = DIM, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp))
        LoopColumn(state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val page = KeyService.page
            if (page != null) item {
                // read when Home was long-pressed in another app
                Chip("▶ Open this page in Starshine browser: " + (Uri.parse(page).host ?: page), id = "q:page") {
                    KeyService.forget(); ctx.startActivity(Intent(ctx, WebActivity::class.java).setData(Uri.parse(page)))
                }
            } else if (KeyService.readFrom != null) item {
                Text("No web address found in the app you came from. If it hides its address bar, show it first, then long-press Home again.", color = DIM, fontSize = 10.sp)
            }

            // ---- row 1: connectivity block | what's playing
            item(key = "q:row1") {
                Row(horizontalArrangement = Arrangement.spacedBy(Q_GAP.dp)) {
                    // four round buttons in a 2 x 2 block, as wide as two buttons
                    Column(Modifier.size(Q_BLOCK.dp).background(BLOCK_BG, RoundedCornerShape(14.dp)), verticalArrangement = Arrangement.spacedBy(Q_GAP.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Q_GAP.dp)) {
                            RoundToggle("q:wifi", "📶", "Wi-Fi", "▮".repeat(wifi.first) + "▯".repeat(4 - wifi.first) + " " + wifi.second, wifi.first > 0,
                                Modifier.focusRequester(first)) { openSettings(ctx, Intent(Settings.ACTION_WIFI_SETTINGS)) }
                            RoundToggle("q:bt", "ᛒ", "Bluetooth", if (!bt.on) "Off" else if (bt.connected.isEmpty()) "On" else bt.connected.joinToString(), bt.on) { openSettings(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Q_GAP.dp)) {
                            RoundToggle("q:glasses", "👓", "Glasses", onOff(glasses), glasses) { onGlasses(!glasses) }
                            RoundToggle("q:dimon", "◐", "AR dim", if (dimOn) "${(dim * 100).toInt()}%" else "Off", dimOn) { onDimOn(!dimOn) }
                        }
                    }
                    // the music player (Settings → Sound: on or off here, and which app): what plays, or the chosen player
                    if (MusicPlayer.inQuick) PlayerBlock(np, Modifier.size(Q_BLOCK.dp))
                }
            }

            // ---- row 2: sleep + mute over a wide sound switch | brightness and volume sliders
            item(key = "q:row2") {
                Row(horizontalArrangement = Arrangement.spacedBy(Q_GAP.dp)) {
                    Column(Modifier.width(Q_BLOCK.dp), verticalArrangement = Arrangement.spacedBy(Q_GAP.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Q_GAP.dp)) {
                            SquareTile("q:sleep", "🌙", "Sleep", sleepMin > 0, state = if (sleepMin > 0) "$sleepMin min" else "off") {
                                val steps = listOf(0L, 15L, 30L, 60L, 90L)
                                val next = steps[(steps.indexOfFirst { it >= sleepMin }.coerceAtLeast(0) + 1) % steps.size]
                                onSleep(if (next == 0L) 0L else System.currentTimeMillis() + next * 60_000)
                            }
                            SquareTile("q:mute", if (muted) "🔇" else "🔔", "Mute all", muted, state = onOff(muted)) { setMuted(!muted) }
                        }
                        // the wide switch under them (Do Not Disturb in the picture): Starshine's sound on or off
                        var focused by remember { mutableStateOf(false) }
                        Row(Modifier.size(Q_BLOCK.dp, Q_TILE.dp).quickKeys("q:soundon", { focused = it; if (it) QuickHint.text = "Sound · " + playing.ifEmpty { "off" } }) { onSoundToggle() }
                            .background(if (playing.isNotEmpty()) CYAN.copy(alpha = 0.32f) else TILE_BG, RoundedCornerShape(10.dp))
                            .border(if (focused) 2.dp else 0.dp, if (focused) CYAN else Color.Transparent, RoundedCornerShape(10.dp))
                            .padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size((Q_TILE - 10).dp).clip(CircleShape).background(Color.White.copy(alpha = if (playing.isNotEmpty()) 0.9f else 0.15f)), contentAlignment = Alignment.Center) {
                                Glyph("♪", Q_TILE - 20, if (playing.isNotEmpty()) Color(0xFF0B1520) else FG)
                            }
                            Column(Modifier.padding(start = 5.dp)) {
                                Text("Sound", color = FG, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(if (playing.isNotEmpty()) playing else "Off", color = DIM, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    VerticalSlider("q:bright", "☀", "Brightness · ${(Brightness.level * 100).toInt()}%", (Brightness.level - Brightness.MIN) / (1f - Brightness.MIN), 0.1f,
                        onOk = { Brightness.set(ctx, 1f) }) { Brightness.set(ctx, Brightness.MIN + it * (1f - Brightness.MIN)) }
                    VerticalSlider("q:vol", if (Sound.level <= 0f) "🔈" else "🔊", "Volume · ${(Sound.level * 100).toInt()}%", Sound.level, 0.1f,
                        onOk = { setMuted(!muted) }) { Sound.setLevel(ctx, it) }
                }
            }

            // ---- the editable grid
            val shown = QuickTiles.order + "q:edit"
            shown.chunked(4).forEach { row ->
                item(key = "qg:" + row.first()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Q_GAP.dp)) {
                        row.forEach { id ->
                            if (id == "q:edit") SquareTile(id, if (editing) "✓" else "✎", if (editing) "Done editing" else "Edit tiles", editing) {
                                editing = !editing; picked = null; hint = ""
                            } else {
                                val (_, icon, name) = QuickTiles.info(id)
                                val (on, state) = tileState(id)
                                SquareTile(id, icon, name, on && !editing, badge = if (editing) "−" else "", picked = picked == id, state = if (editing) "" else state,
                                    onHold = if (editing) ({ picked = id; hint = "Moving $name: ← → ↑ ↓, OK to drop" }) else null,
                                    onKey = if (picked == id) ({ k ->
                                        val by = when (k) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; Key.DirectionUp -> -4; Key.DirectionDown -> 4; else -> 0 }
                                        if (by != 0) { QuickTiles.move(ctx, id, by); refocus(id) }
                                        by != 0
                                    }) else null) {
                                    when {
                                        picked == id -> { picked = null; hint = "" }
                                        editing -> { QuickTiles.remove(ctx, id); hint = "Removed $name" }
                                        else -> run(id)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (editing) {
                val hidden = QuickTiles.ALL.map { it.first }.filter { it !in QuickTiles.order }
                item(key = "q:addlabel") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (hidden.isEmpty()) "EVERY TILE IS IN THE PANEL" else "ADD A TILE", color = DIM, fontSize = 9.sp, letterSpacing = 1.4.sp, modifier = Modifier.weight(1f))
                        Chip("↺ Reset tiles", id = "q:tilereset") { QuickTiles.reset(ctx); hint = "Tiles reset" }
                    }
                }
                hidden.chunked(4).forEach { row ->
                    item(key = "qa:" + row.first()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Q_GAP.dp)) {
                            row.forEach { id ->
                                val (_, icon, name) = QuickTiles.info(id)
                                SquareTile("add:$id", icon, "Add " + name, false, badge = "＋") { QuickTiles.add(ctx, id); hint = "Added $name" }
                            }
                        }
                    }
                }
            }

            if (!editing) {
                item { Adjust("◐ AR dim level", "${(dim * 100).toInt()}%", { onDim((dim - 0.05f).coerceAtLeast(0f)) }, compact = true, fill = dim) { onDim((dim + 0.05f).coerceAtMost(1f)) } }
                if (!Brightness.systemWide) item {
                    Text("Brightness dims Starshine only. Turn on Starshine's accessibility service (Build & Install does it) to dim every app.", color = DIM, fontSize = 9.sp)
                }
                section("quick", "Meditation", "Meditation", Med.playing?.let { "${it.name}, step ${Med.stepIndex + 1}" } ?: "") {
                    if (Med.playing != null) item {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip(if (Med.paused) "▶ Resume" else "❚❚ Pause", id = "q:medpause") { Med.paused = !Med.paused }
                            Chip("⏭ Next step", id = "q:medskip") { Med.skip++ }
                            Chip("■ Stop journey", id = "q:medstop") { Med.playing = null; onStop() }
                        }
                    } else items(Med.journeys.map { it.name }.chunked(ICON_COLUMNS), key = { "qj:" + it.first() }) { row ->
                        IconRow(row, count = { 0 }, idOf = { "row:q:med:$it" }, height = 40, label = { it }, onHint = { hint = it },
                            onPress = { n -> Med.journeys.firstOrNull { it.name == n }?.takeIf { it.steps.isNotEmpty() }?.let { Med.paused = false; Med.playing = it } }
                        ) { n, c, m -> SceneIcon(Med.journeys.firstOrNull { it.name == n }?.steps?.firstOrNull()?.let { Bg2.pristine(it.scene) }, c, m) }
                    }
                }
                section("quick", "Moods", "Meditation", playing) {
                    items(Med.stacks.map { it.name }.chunked(ICON_COLUMNS), key = { "qm:" + it.first() }) { row ->
                        IconRow(row, count = { n -> if (playing == n) 1 else 0 }, idOf = { "row:q:mood:$it" }, height = 40, label = { it }, onHint = { hint = it },
                            onPress = { n -> if (playing == n) onStop() else Med.stacks.firstOrNull { it.name == n }?.let { Sound.playStack(ctx, it, 1f) } }
                        ) { n, c, m -> MoodIcon(Med.stacks.firstOrNull { it.name == n }, c, m) }
                    }
                }
                section("quick", "Scenes", "Meditation", if (Bg2.applied) Bg2.current.name else "") {
                    val scenes = Bg2.mine + Landscapes.available() + BUILT_IN_SCENES
                    items(scenes.map { it.name }.chunked(ICON_COLUMNS), key = { "qs:" + it.first() }) { row ->
                        IconRow(row, count = { n -> if (Bg2.applied && Bg2.current.name == n) 1 else 0 }, idOf = { "row:q:scene:$it" }, height = 40, label = { it }, onHint = { hint = it },
                            onPress = { n -> scenes.firstOrNull { it.name == n }?.let { onScene(it) } }
                        ) { n, c, m -> SceneIcon(scenes.firstOrNull { it.name == n }, c, m) }
                    }
                }
            }
        }
    }
}

/**
 * The music player: what any app is playing (title, artist, ⏮ ▶ ⏭), or, with nothing playing, the chosen player
 * (YouTube Music by default) to start, with ⇄ to switch to another installed one. [vertical] = the tall card on the
 * left edge of the Home screen.
 */
@Composable
fun PlayerBlock(np: Playing?, modifier: Modifier = Modifier, vertical: Boolean = false, idp: String = "q") {
    val ctx = LocalContext.current
    // buttons keep the panel's rule: 10 bigger than their icon (26 around 16 in the small block, 40 around 30 on the edge card)
    val b = if (vertical) Q_TILE else 26
    val name = np?.let { it.artist.ifEmpty { MusicPlayer.name(it.pkg) } } ?: MusicPlayer.name()
    Column(modifier.background(BLOCK_BG, RoundedCornerShape(14.dp)).padding(if (vertical) 10.dp else 4.dp),
        verticalArrangement = Arrangement.spacedBy(if (vertical) 8.dp else 2.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        if (vertical && np?.art != null) androidx.compose.foundation.Image(np.art.asImageBitmap(), null,
            Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        Text(np?.title ?: "Not playing", color = FG, fontSize = if (vertical) 12.sp else 9.sp, fontWeight = FontWeight.SemiBold, maxLines = if (vertical) 2 else 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(name, color = DIM, fontSize = if (vertical) 10.sp else 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        @Composable fun buttons() {
            if (np != null) {
                SquareTile("$idp:mprev", "⏮", "Previous · ${np.title}", false, size = b) { NowPlaying.previous(np) }
                SquareTile("$idp:mplay", if (np.playing) "❚❚" else "▶", (if (np.playing) "Pause · " else "Play · ") + np.title, np.playing, size = b) { NowPlaying.toggle(np) }
                SquareTile("$idp:mnext", "⏭", "Next · ${np.title}", false, size = b) { NowPlaying.next(np) }
            } else {
                SquareTile("$idp:mplay", "▶", "Play · ${MusicPlayer.name()}", false, size = b) { MusicPlayer.play(ctx) }
                SquareTile("$idp:mswitch", "⇄", "Switch player · now ${MusicPlayer.name()}", false, size = b) { MusicPlayer.cycle(ctx) }
            }
        }
        if (vertical) Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) { buttons() }
        else Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) { buttons() }
    }
}

/** The player as a tall card docked on the left edge of the Home screen (Settings → Sound → Player card on the left edge). */
@Composable
fun PlayerEdgeCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val np by produceState<Playing?>(null) { while (true) { value = runCatching { NowPlaying.current(ctx) }.getOrNull(); delay(2_000) } }
    PlayerBlock(np, modifier.width(112.dp), vertical = true, idp = "edge")
}

/** Settings → Sound: the music player's place on screen and which app it starts. */
@Composable
fun PlayerSettingsRows() {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Adjust("Player in Quick settings", if (MusicPlayer.inQuick) "on" else "off", { MusicPlayer.setQuick(ctx, !MusicPlayer.inQuick) }) { MusicPlayer.setQuick(ctx, !MusicPlayer.inQuick) }
        Adjust("Player card on the left edge", if (MusicPlayer.edge) "on" else "off", { MusicPlayer.setEdge(ctx, !MusicPlayer.edge) }) { MusicPlayer.setEdge(ctx, !MusicPlayer.edge) }
        val inst = remember { MusicPlayer.installed(ctx) }
        Adjust("Music player", MusicPlayer.name() + if (inst.none { it.first == MusicPlayer.pkg }) " (not installed)" else "", { MusicPlayer.cycle(ctx, -1) }) { MusicPlayer.cycle(ctx, 1) }
        Text("The player shows whatever app is playing; with nothing playing, ▶ starts the music player chosen here (⇄ switches). Installed: " +
            inst.joinToString { it.second }.ifEmpty { "none of the known players" }, color = DIM, fontSize = 9.sp, modifier = Modifier.padding(8.dp))
    }
}
