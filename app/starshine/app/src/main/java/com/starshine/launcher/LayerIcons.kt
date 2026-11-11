package com.starshine.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

const val ICON_COLUMNS = 4

/**
 * One row of the Add layer grid. An item already in the scene is lit. ← → stay in the row and loop around it, which keeps
 * the arrows from leaking out to the layer tiles.
 */
@Composable
fun IconRow(row: List<String>, count: (String) -> Int, idOf: (String) -> String, onHint: (String) -> Unit, onPress: (String) -> Unit,
            height: Int = 30, onHold: ((String) -> Unit)? = null, label: ((String) -> String)? = null,
            icon: @Composable (String, Color, Modifier) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        row.forEachIndexed { i, name ->
            var focused by remember { mutableStateOf(false) }
            val n = count(name)
            val tint = if (focused) FG else if (n > 0) GREEN else DIM
            Box(Modifier.weight(1f).height((if (label != null) height + 13 else height).dp)
                .remembered(idOf(name))
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onHint(name) else if (it.hasFocus.not()) onHint("") }
                .focusOnHover()
                .onPreviewKeyEvent { e ->
                    if (onHold != null && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount >= 1 &&
                        (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                        FocusMem.swallowOk = true; onHold(name); return@onPreviewKeyEvent true
                    }
                    if (e.type != KeyEventType.KeyDown || (e.key != Key.DirectionLeft && e.key != Key.DirectionRight)) return@onPreviewKeyEvent false
                    val j = i + if (e.key == Key.DirectionLeft) -1 else 1
                    // a held arrow stops at the end of the row; a new press loops
                    if (j in row.indices || e.nativeKeyEvent.repeatCount == 0) FocusMem.restore(idOf(row[(j + row.size) % row.size]))
                    true
                }
                .clickable { onPress(name) }
                .background(if (n > 0) GREEN.copy(alpha = 0.16f) else if (focused) CYAN.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(7.dp))
                .border(if (focused) 2.dp else 1.dp, if (focused) CYAN else if (n > 0) GREEN.copy(alpha = 0.8f) else DIM.copy(alpha = 0.25f), RoundedCornerShape(7.dp))
                .padding(3.dp), contentAlignment = Alignment.Center) {
                // with a label: the picture on top and its title under it, so a grid can be read without moving through it
                if (label != null) Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { icon(name, tint, Modifier.fillMaxSize()) }
                    Text(label(name), color = if (focused) FG else DIM, fontSize = 7.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                } else icon(name, tint, Modifier.fillMaxSize())
                if (n > 1) Text("×$n", color = GREEN, fontSize = 8.sp, modifier = Modifier.align(Alignment.TopEnd))
            }
        }
        repeat(ICON_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
    }
}

/** A scene's icon, made from its own layers so it stays true after edits: its main shape, with the next layer small in the corner. */
@Composable
fun SceneIcon(scene: BgScene?, color: Color, modifier: Modifier) {
    val all = scene?.layers.orEmpty()
    val seen = all.filter { it.on && it.show }.ifEmpty { all }
    val main = seen.lastOrNull { it.kind == GenKind.Line || it.kind == GenKind.Solid } ?: seen.lastOrNull()
    val other = seen.lastOrNull { it !== main }
    Box(modifier) {
        if (main != null) LayerIcon(main.type, color, Modifier.fillMaxSize(), main["dim"].toInt())
        if (other != null) LayerIcon(other.type, color.copy(alpha = 0.6f), Modifier.size(13.dp).align(Alignment.BottomEnd), other["dim"].toInt())
    }
}

/**
 * A mood's icon: the one chosen for it, or a symbol that fits its title. Frequency moods also show their carrier, since
 * several share the wave symbol.
 */
@Composable
fun MoodIcon(stack: AudioStack?, color: Color, modifier: Modifier) {
    if (stack == null) return
    val first = stack.layers.firstOrNull()
    val spec = MoodIcons.iconOf(stack.name, stack)
    Box(modifier) {
        MoodIconView(spec, color, Modifier.fillMaxSize())
        if (spec == "sym:Wave" && (first?.kind == "Tone" || first?.kind == GENERATOR_KIND))
            Text(Math.round(first["carrier"]).toString(), color = color, fontSize = 7.sp, maxLines = 1, modifier = Modifier.align(Alignment.BottomEnd))
    }
}

/** One of the mood symbols, drawn with the same pen as the layer icons. */
@Composable
fun SymbolIcon(name: String, color: Color, modifier: Modifier) = Canvas(modifier) { Pen(this, color).symbol(name) }

/** Shown when OK lands on a layer already in the scene: the undo is one press, and a second copy has to be asked for. */
@Composable
fun AddAgainBox(name: String, count: Int, onRemove: () -> Unit, onAnother: () -> Unit, onCancel: () -> Unit) {
    BackHandler { onCancel() }
    LaunchedEffect(Unit) { FocusMem.restoreSoon("row:ask:rm") }
    Column(Modifier.fillMaxWidth().padding(top = 40.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
        .border(1.dp, CYAN.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(name, color = CYAN, fontSize = 12.sp, maxLines = 2)
        Text(if (count > 1) "$count of this layer are in use" else "This layer is already in use", color = DIM, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
        Row_(if (count > 1) "− Remove the last one" else "− Remove it", false, id = "ask:rm", onFocus = {}) { onRemove() }
        Row_("＋ Add another", false, id = "ask:add", onFocus = {}) { onAnother() }
        Row_("Cancel", false, id = "ask:no", onFocus = {}) { onCancel() }
    }
}

/** A small line drawing of a visual layer. */
@Composable
fun LayerIcon(generator: String, color: Color, modifier: Modifier, dim: Int = 0) {
    val name = generator.removePrefix(CLASSIC_PREFIX).removePrefix(LINES_PREFIX)
    val kind = generator(generator).takeIf { it.name == generator }?.kind
    if (kind == GenKind.Line || kind == GenKind.Solid) {
        val segs = remember(generator, dim) { shapeOf(generator, dim) }
        val turned = kind == GenKind.Solid
        Canvas(modifier) {
            val u = minOf(size.width, size.height) / 2 * 0.96f
            val w = if (segs.size > 2400) 0.4.dp.toPx() else 0.8.dp.toPx()
            var i = 0
            while (i + 5 < segs.size) {
                drawLine(color, iconPoint(segs, i, turned, u, size.width / 2, size.height / 2), iconPoint(segs, i + 3, turned, u, size.width / 2, size.height / 2), w)
                i += 6
            }
        }
    } else Canvas(modifier) { Pen(this, color).draw(name) }
}

private val SHAPES = HashMap<String, FloatArray>()
/** The layer's real geometry, so the icon always matches it. Very dense shapes keep a sample of their lines. */
private fun shapeOf(name: String, dim: Int): FloatArray = SHAPES.getOrPut(name + dim) {
    val all = Geometry.of(name, dim); val segments = all.size / 6
    if (segments <= 500) all else { val keep = segments / 500 + 1; FloatArray((segments / keep) * 6) { all[(it / 6) * keep * 6 + it % 6] } }
}
/** Solids are seen turned a little, so they read as 3D. */
private fun iconPoint(s: FloatArray, i: Int, turned: Boolean, u: Float, cx: Float, cy: Float): Offset {
    val x = s[i]; val y = s[i + 1]; val z = s[i + 2]
    if (!turned) return Offset(cx + x * u, cy - y * u)
    val x1 = x * 0.82f + z * 0.57f; val z1 = -x * 0.57f + z * 0.82f
    val y1 = y * 0.9f - z1 * 0.43f
    return Offset(cx + x1 * u, cy - y1 * u)
}

private class Pen(val d: DrawScope, val color: Color) {
    val cx = d.size.width / 2; val cy = d.size.height / 2
    val u = minOf(d.size.width, d.size.height) / 2 * 0.94f
    val width = with(d) { 1.1.dp.toPx() }
    val line = Stroke(width)
    val TAU = (2 * PI).toFloat()

    fun p(x: Float, y: Float) = Offset(cx + x * u, cy + y * u)
    fun ring(x: Float, y: Float, r: Float, a: Float = 1f) = d.drawCircle(color.copy(alpha = a), r * u, p(x, y), style = line)
    fun dot(x: Float, y: Float, r: Float = 0.07f, a: Float = 1f) = d.drawCircle(color.copy(alpha = a), r * u, p(x, y))
    fun seg(x1: Float, y1: Float, x2: Float, y2: Float, a: Float = 1f) = d.drawLine(color.copy(alpha = a), p(x1, y1), p(x2, y2), width)
    fun oval(x: Float, y: Float, rx: Float, ry: Float) = d.drawOval(color, Offset(cx + (x - rx) * u, cy + (y - ry) * u), Size(2 * rx * u, 2 * ry * u), style = line)
    /** A curve from t = 0..1. */
    fun curve(steps: Int = 60, close: Boolean = false, f: (Float) -> Pair<Float, Float>) {
        val path = Path()
        for (i in 0..steps) { val (x, y) = f(i.toFloat() / steps); val o = p(x, y); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        if (close) path.close()
        d.drawPath(path, color, style = line)
    }
    fun poly(n: Int, r: Float, turn: Float = -PI.toFloat() / 2, x: Float = 0f, y: Float = 0f) =
        curve(n, true) { t -> x + r * cos(turn + TAU * t) to y + r * sin(turn + TAU * t) }
    fun corner(n: Int, i: Int, r: Float, turn: Float = -PI.toFloat() / 2) = r * cos(turn + TAU * i / n) to r * sin(turn + TAU * i / n)
    fun rays(n: Int, from: Float, to: Float, a: Float = 1f) { for (i in 0 until n) { val (x1, y1) = corner(n, i, from); val (x2, y2) = corner(n, i, to); seg(x1, y1, x2, y2, a) } }
    fun spokes(n: Int, r: Float) = rays(n, 0f, r)
    /** Fixed scatter, the same every time it is drawn. */
    fun scatter(n: Int, seed: Int, r: Float) { for (i in 0 until n) { val a = sin(i * 12.9898f + seed) * 43758.547f; val b = sin(i * 78.233f + seed) * 12543.123f
        dot(((a - kotlin.math.floor(a)) * 2 - 1) * 0.95f, ((b - kotlin.math.floor(b)) * 2 - 1) * 0.95f, r * (0.6f + (i % 3) * 0.4f)) } }
    fun wave(y: Float, amp: Float, cycles: Float, phase: Float = 0f) = curve { t -> -1f + 2 * t to y + amp * sin(TAU * cycles * t + phase) }
    fun spiral(turns: Float, r: Float, phase: Float = 0f) = curve(90) { t -> val a = phase + TAU * turns * t; r * t * cos(a) to r * t * sin(a) }
    fun flame(x: Float, y: Float, s: Float) {
        val q = Path(); val a = p(x, y - s); val b = p(x, y + s)
        q.moveTo(a.x, a.y)
        q.cubicTo(cx + (x + s * 1.0f) * u, cy + (y - s * 0.1f) * u, cx + (x + s * 0.8f) * u, cy + (y + s) * u, b.x, b.y)
        q.cubicTo(cx + (x - s * 0.8f) * u, cy + (y + s) * u, cx + (x - s * 1.0f) * u, cy + (y - s * 0.1f) * u, a.x, a.y)
        d.drawPath(q, color, style = line)
    }
    fun cloud(x: Float, y: Float, s: Float) {
        d.drawArc(color, 150f, 240f, false, Offset(cx + (x - 0.75f * s) * u, cy + (y - 0.55f * s) * u), Size(0.8f * s * u, 0.8f * s * u), style = line)
        d.drawArc(color, 180f, 200f, false, Offset(cx + (x - 0.25f * s) * u, cy + (y - 0.85f * s) * u), Size(1f * s * u, 1f * s * u), style = line)
        seg(x - 0.72f * s, y + 0.25f * s, x + 0.75f * s, y + 0.25f * s)
    }

    /** Mood symbols. Same coordinates as the layer icons: -1..1, y down. */
    fun symbol(name: String) {
        val pi = PI.toFloat()
        when (name) {
            "Rain" -> { cloud(0f, -0.3f, 1f); for ((x, y) in listOf(-0.5f to 0.25f, -0.1f to 0.45f, 0.3f to 0.25f, 0.65f to 0.5f, -0.3f to 0.75f, 0.15f to 0.8f)) seg(x, y, x - 0.1f, y + 0.22f) }
            "Ocean" -> { wave(-0.35f, 0.15f, 1.5f); wave(0.1f, 0.15f, 1.5f, 1.4f); wave(0.55f, 0.15f, 1.5f, 2.8f) }
            "Bowl" -> { oval(0f, -0.15f, 0.85f, 0.16f); curve(40) { t -> -0.85f + 1.7f * t to -0.15f + 0.75f * sin(pi * t) }; seg(-0.3f, 0.72f, 0.3f, 0.72f); for (i in 0 until 3) curve(20) { t -> -0.3f + 0.6f * t + i * 0.0f to -0.45f - i * 0.17f + 0.05f * sin(pi * 2 * t) } }
            "Bell" -> { curve(50) { t -> val x = -0.7f + 1.4f * t; x to 0.55f - 0.95f * (1f - (2f * t - 1f) * (2f * t - 1f)).let { kotlin.math.sqrt(it) } * (if (kotlin.math.abs(x) < 0.45f) 1f else 0.9f) }; seg(-0.75f, 0.55f, 0.75f, 0.55f); dot(0f, 0.72f, 0.12f); ring(0f, -0.62f, 0.1f) }
            "Temple" -> { for ((y, w) in listOf(-0.55f to 0.45f, -0.15f to 0.7f, 0.25f to 0.95f)) { curve(20) { t -> -w + 2 * w * t to y + 0.12f * kotlin.math.abs(2 * t - 1) * -1f + 0.12f } }
                seg(0f, -0.9f, 0f, -0.6f); for (y in listOf(-0.43f, -0.03f, 0.37f)) { seg(-0.35f - (y + 0.43f) * 0.4f, y, -0.35f - (y + 0.43f) * 0.4f, y + 0.25f); seg(0.35f + (y + 0.43f) * 0.4f, y, 0.35f + (y + 0.43f) * 0.4f, y + 0.25f) }; seg(-0.9f, 0.8f, 0.9f, 0.8f) }
            "Mountain" -> { curve(5) { t -> listOf(-0.95f to 0.7f, -0.45f to -0.2f, -0.15f to 0.2f, 0.3f to -0.65f, 0.95f to 0.7f, -0.95f to 0.7f)[Math.round(t * 5).coerceAtMost(5)] }; seg(0.12f, -0.35f, 0.3f, -0.25f); seg(0.3f, -0.25f, 0.46f, -0.38f); ring(-0.6f, -0.65f, 0.15f) }
            "Moon" -> { curve(50) { t -> val a = pi * (0.3f + 1.4f * t); 0.8f * cos(a) to 0.8f * sin(a) }; curve(50) { t -> val a = pi * (0.42f + 1.16f * t); 0.15f + 0.62f * cos(a) to 0.62f * sin(a) }; dot(0.55f, -0.55f, 0.05f); dot(0.7f, -0.1f, 0.04f) }
            "Sun" -> { ring(0f, 0f, 0.42f); rays(12, 0.58f, 0.92f) }
            "Fire" -> { flame(0f, 0.05f, 0.85f); flame(0f, 0.38f, 0.42f) }
            "Drum" -> { oval(0f, -0.4f, 0.8f, 0.22f); seg(-0.8f, -0.4f, -0.8f, 0.45f); seg(0.8f, -0.4f, 0.8f, 0.45f); curve(30) { t -> -0.8f + 1.6f * t to 0.45f + 0.22f * sin(pi * t) }; for (i in 0 until 4) { val x = -0.6f + i * 0.4f; seg(x, -0.2f, x + 0.2f, 0.6f, 0.6f) } }
            "Strings" -> { oval(0f, 0.45f, 0.42f, 0.45f); seg(-0.08f, 0f, -0.08f, -0.95f); seg(0.08f, 0f, 0.08f, -0.95f); for (i in 0 until 4) { val x = -0.18f + i * 0.12f; seg(x, -0.85f, x, 0.75f, 0.8f) } }
            "Keys" -> { for (i in 0 until 6) { val x = -0.8f + i * 0.32f; val h = 0.9f - i * 0.1f; curve(4, true) { t -> val q = Math.round(t * 4) % 4; (x + if (q == 1 || q == 2) 0.22f else 0f) to (if (q >= 2) h / 2 else -h / 2) } } }
            "Didgeridoo" -> { seg(-0.85f, 0.75f, 0.7f, -0.6f); seg(-0.7f, 0.88f, 0.85f, -0.45f); oval(0.78f, -0.53f, 0.12f, 0.12f); for (i in 0 until 4) { val k = 0.2f + i * 0.18f; seg(-0.85f + 1.55f * k + 0.05f, 0.75f - 1.35f * k + 0.08f, -0.7f + 1.55f * k + 0.05f, 0.88f - 1.35f * k + 0.08f, 0.6f) } }
            "Zen" -> { for (r in listOf(0.35f, 0.55f, 0.75f, 0.95f)) oval(0.15f, 0.3f, r, r * 0.45f); oval(0.15f, 0.2f, 0.22f, 0.15f); dot(-0.65f, -0.55f, 0.1f) }
            "Lotus" -> { for (a in listOf(-0.9f, -0.45f, 0f, 0.45f, 0.9f)) { curve(30, true) { t -> val s = sin(pi * t); val x = 0f + (t - 0.5f) * 0.35f * s; val y = 0.5f - 1.1f * t; (x * cos(a) - (y - 0.5f) * sin(a)) to (x * sin(a) + (y - 0.5f) * cos(a) + 0.5f) } }; seg(-0.85f, 0.55f, 0.85f, 0.55f) }
            "Wave" -> { wave(0f, 0.55f, 2f); seg(-1f, 0f, 1f, 0f, 0.3f) }
            "Eye" -> { curve(40) { t -> -0.95f + 1.9f * t to -0.6f * sin(pi * t) }; curve(40) { t -> -0.95f + 1.9f * t to 0.6f * sin(pi * t) }; ring(0f, 0f, 0.36f); dot(0f, 0f, 0.15f) }
            "Leaf" -> { curve(40) { t -> -0.75f + 1.5f * t to 0.75f - 1.5f * t - 0.45f * sin(pi * t) }; curve(40) { t -> -0.75f + 1.5f * t to 0.75f - 1.5f * t + 0.45f * sin(pi * t) }; seg(-0.75f, 0.75f, 0.75f, -0.75f, 0.7f) }
            "Star" -> curve(10, true) { t -> val i = Math.round(t * 10) % 10; val r = if (i % 2 == 0) 0.95f else 0.4f; val a = -pi / 2 + pi * i / 5; r * cos(a) to r * sin(a) }
            "Gong" -> { ring(0f, 0.1f, 0.62f); ring(0f, 0.1f, 0.22f, 0.6f); seg(-0.3f, -0.48f, -0.6f, -0.95f); seg(0.3f, -0.48f, 0.6f, -0.95f); seg(-0.75f, -0.95f, 0.75f, -0.95f) }
            "Chimes" -> { seg(-0.8f, -0.85f, 0.8f, -0.85f); for (i in 0 until 5) { val x = -0.6f + i * 0.3f; val h = 0.5f + 0.35f * sin(i * 1.7f); seg(x, -0.85f, x, -0.6f, 0.5f); seg(x - 0.06f, -0.6f, x - 0.06f, -0.6f + h); seg(x + 0.06f, -0.6f, x + 0.06f, -0.6f + h) } }
            "Heart" -> curve(60, true) { t -> val a = 2 * pi * t; val x = 16 * sin(a) * sin(a) * sin(a); val y = 13 * cos(a) - 5 * cos(2 * a) - 2 * cos(3 * a) - cos(4 * a); x / 18f to -y / 18f - 0.05f }
            "Wind" -> { for ((y, w) in listOf(-0.4f to 1.2f, 0.05f to 1.6f, 0.5f to 1f)) { seg(-0.9f, y, -0.9f + w, y); curve(20) { t -> val a = -pi / 2 + 1.6f * pi * t; -0.9f + w + 0.18f * cos(a) to y - 0.18f + 0.18f * sin(a) } } }
            "Crystal" -> { curve(6, true) { t -> listOf(0f to -0.95f, 0.55f to -0.35f, 0.4f to 0.6f, 0f to 0.95f, -0.4f to 0.6f, -0.55f to -0.35f)[Math.round(t * 6) % 6] }; seg(0f, -0.95f, 0f, 0.95f, 0.5f); seg(-0.55f, -0.35f, 0.55f, -0.35f, 0.5f) }
            "Handpan" -> { oval(0f, 0f, 0.95f, 0.6f); ring(0f, 0f, 0.2f); for (i in 0 until 7) { val a = 2 * pi * i / 7; oval(0.62f * cos(a), 0.38f * sin(a), 0.13f, 0.09f) } }
            "Noise" -> { var x = -0.95f; var i = 0; while (x < 0.95f) { val h = 0.2f + 0.7f * kotlin.math.abs(sin(i * 12.9898f) * 4375.85f % 1f); seg(x, -h, x, h); x += 0.12f; i++ } }
            "Feather" -> { curve(40) { t -> -0.6f + 1.2f * t to 0.8f - 1.6f * t + 0.1f * sin(pi * t) }; for (i in 1 until 8) { val k = i / 8f; val x = -0.6f + 1.2f * k; val y = 0.8f - 1.6f * k; seg(x, y, x - 0.35f * (1f - k * 0.5f), y - 0.1f); seg(x, y, x + 0.25f * (1f - k * 0.5f), y + 0.22f) } }
            "Spiral" -> spiral(2.6f, 0.95f)
            else -> draw(name)
        }
    }

    fun draw(name: String) {
        when (name) {
            "Honeycomb" -> { poly(6, 0.33f, 0f); for (i in 0 until 6) { val (x, y) = corner(6, i, 0.58f, PI.toFloat() / 6); poly(6, 0.33f, 0f, x, y) } }
            // particles
            "Starfield" -> { scatter(9, 3, 0.05f); seg(0.35f, -0.65f, 0.35f, -0.15f); seg(0.1f, -0.4f, 0.6f, -0.4f) }
            "Orbs" -> { ring(-0.4f, 0.25f, 0.42f); ring(0.45f, -0.3f, 0.3f); ring(0.5f, 0.55f, 0.18f) }
            "Dust" -> scatter(30, 8, 0.028f)
            // fields and light
            "Clouds", "Dream clouds" -> { cloud(-0.15f, -0.2f, 0.95f); cloud(0.25f, 0.5f, 0.7f) }
            "Fire" -> { flame(0f, 0f, 0.9f); flame(0f, 0.35f, 0.45f) }
            "Water" -> { wave(-0.45f, 0.14f, 2f); wave(0f, 0.14f, 2f, 1.2f); wave(0.45f, 0.14f, 2f, 2.4f) }
            "Nebula" -> { for (r in listOf(0.9f, 0.62f, 0.34f)) curve(60, true) { t -> val a = TAU * t; val w = r * (1f + 0.17f * sin(a * 4 + r * 5)); w * cos(a) to 0.78f * w * sin(a) }; scatter(4, 5, 0.04f) }
            "Iris" -> { curve(40) { t -> -1f + 2 * t to -0.72f * sin(PI.toFloat() * t) }; curve(40) { t -> -1f + 2 * t to 0.72f * sin(PI.toFloat() * t) }; ring(0f, 0f, 0.46f); dot(0f, 0f, 0.2f) }
            "Sun flare" -> { ring(0f, 0f, 0.38f); rays(12, 0.52f, 0.98f) }
            "Candle" -> { flame(0f, -0.55f, 0.36f); seg(-0.26f, -0.05f, 0.26f, -0.05f); seg(-0.26f, -0.05f, -0.26f, 0.95f); seg(0.26f, -0.05f, 0.26f, 0.95f); seg(-0.26f, 0.95f, 0.26f, 0.95f) }
            "Globe" -> { ring(0f, 0f, 0.95f); oval(0f, 0f, 0.42f, 0.95f); seg(-0.95f, 0f, 0.95f, 0f); oval(0f, -0.48f, 0.8f, 0.07f); oval(0f, 0.48f, 0.8f, 0.07f) }
            "Aether" -> { for (i in 0 until 3) spiral(0.75f, 0.98f, TAU * i / 3); dot(0f, 0f, 0.09f) }
            // classic backgrounds
            "Constellations" -> { val s = listOf(-0.85f to 0.45f, -0.4f to -0.25f, 0.1f to 0.15f, 0.55f to -0.6f, 0.85f to 0.4f); s.zipWithNext().forEach { (a, b) -> seg(a.first, a.second, b.first, b.second, 0.7f) }; s.forEach { dot(it.first, it.second, 0.09f) } }
            "Aurora" -> { for (i in 0 until 4) { val o = -0.6f + i * 0.4f; curve(30) { t -> o + 0.16f * sin(TAU * t * 1.2f + i) to -0.9f + 1.8f * t } } }
            "Fireflies" -> { for ((x, y) in listOf(-0.6f to -0.4f, 0.5f to -0.55f, -0.1f to 0.3f, 0.7f to 0.5f, -0.75f to 0.65f)) { dot(x, y, 0.08f); ring(x, y, 0.2f, 0.5f) } }
            "Bioluminescent sea" -> { wave(0.1f, 0.14f, 1.5f); wave(0.55f, 0.14f, 1.5f, 1.4f); scatter(7, 2, 0.035f) }
            "Soft rain" -> { for ((x, y) in listOf(-0.7f to -0.7f, 0f to -0.8f, 0.65f to -0.55f, -0.35f to -0.05f, 0.35f to 0.05f, -0.7f to 0.5f, 0.05f to 0.55f, 0.75f to 0.45f)) seg(x, y, x - 0.14f, y + 0.36f) }
            "Snowfall" -> { for ((x, y) in listOf(-0.5f to -0.45f, 0.45f to -0.3f, 0f to 0.45f)) for (i in 0 until 3) { val a = PI.toFloat() * i / 3; seg(x - 0.28f * cos(a), y - 0.28f * sin(a), x + 0.28f * cos(a), y + 0.28f * sin(a)) } }
            "Breathing orbs" -> { ring(0f, 0f, 0.95f, 0.35f); ring(0f, 0f, 0.65f, 0.65f); ring(0f, 0f, 0.35f); dot(0f, 0f, 0.1f) }
            "Slow galaxy" -> { spiral(1.3f, 0.98f); spiral(1.3f, 0.98f, TAU / 2); dot(0f, 0f, 0.1f) }
            "Hyperspace jump" -> rays(16, 0.2f, 0.98f)
            "Warp speed" -> { rays(8, 0.1f, 0.5f); rays(8, 0.65f, 0.98f) }
            "Wormhole gate" -> { oval(0f, 0f, 0.95f, 0.95f); oval(0.14f, 0f, 0.68f, 0.72f); oval(0.27f, 0f, 0.42f, 0.48f); oval(0.36f, 0f, 0.2f, 0.25f) }
            "Cosmic portal" -> { ring(0f, 0f, 0.7f); ring(0f, 0f, 0.56f, 0.6f); rays(8, 0.78f, 0.98f); dot(0f, 0f, 0.12f) }
            "Mandelbrot dive", FRACTAL -> { curve(70, true) { t -> val a = TAU * t; 0.2f + 0.68f * (cos(a) * (1f - cos(a)) / 2f + 0.25f) * 1.3f to 0.68f * sin(a) * (1f - cos(a)) / 2f * 1.3f }; ring(-0.68f, 0f, 0.24f) }
            "Julia bloom" -> curve(120, true) { t -> val a = TAU * t; val r = 0.55f + 0.42f * sin(a * 5) * sin(a * 2); r * cos(a) to r * sin(a) }
            "Liquid plasma" -> { curve(60, true) { t -> val a = TAU * t; val r = 0.72f + 0.2f * sin(a * 3 + 1f); r * cos(a) to r * sin(a) }; curve(40, true) { t -> val a = TAU * t; val r = 0.3f + 0.1f * sin(a * 2); r * cos(a) to r * sin(a) } }
            "Infinite tunnel" -> { for (s in listOf(0.95f, 0.58f, 0.32f, 0.14f)) poly(4, s * 1.2f, PI.toFloat() / 4); for (i in 0 until 4) { val (x, y) = corner(4, i, 1.14f, PI.toFloat() / 4); val (a, b) = corner(4, i, 0.17f, PI.toFloat() / 4); seg(x, y, a, b, 0.6f) } }
            "Kaleidoscope" -> { poly(6, 0.95f); spokes(6, 0.95f); poly(6, 0.48f, 0f) }
            "Hypno spiral" -> spiral(3.3f, 0.98f)
            // line visualizers
            "Sine waves" -> { wave(-0.3f, 0.45f, 1.5f); wave(0.3f, 0.3f, 2.5f, 1f) }
            "Lissajous" -> curve(120, true) { t -> 0.92f * sin(TAU * 3 * t + 0.6f) to 0.92f * sin(TAU * 2 * t) }
            "Spiral" -> spiral(2.2f, 0.98f)
            "Flow" -> { for (i in 0 until 4) { val y = -0.6f + i * 0.4f; wave(y, 0.12f + 0.04f * i, 1f, i * 0.8f) } }
            "Media file" -> { curve(4, true) { t -> val q = (t * 4).toInt() % 4; (if (q == 1 || q == 2) 0.92f else -0.92f) to (if (q >= 2) 0.72f else -0.72f) }; curve(3, true) { t -> val q = (t * 3).toInt() % 3; when (q) { 0 -> -0.26f to -0.36f; 1 -> 0.42f to 0f; else -> -0.26f to 0.36f } } }
            // anything added later: a shape of its own, derived from its name
            else -> { val h = name.hashCode(); val a = 1 + (h and 3); val b = 2 + ((h shr 3) and 3); curve(120, true) { t -> 0.9f * sin(TAU * a * t + 0.5f) to 0.9f * sin(TAU * b * t) } }
        }
    }
}
