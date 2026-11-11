package com.starshine.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private fun col(rgb: Int) = Color(0xFF000000.toInt() or rgb)

/** A colour map drawn as a strip, looping back to its first colour like the fractal does. */
@Composable
fun ColorMapStrip(map: Fractal.ColorMap, modifier: Modifier) {
    val stops = map.stops.map { col(it) }
    Box(modifier.background(Brush.horizontalGradient(stops + stops.first()), RoundedCornerShape(3.dp)))
}

/**
 * Choose a colour map, or make your own: a new map starts as a copy of the one in use, and each of its five colours has
 * hue, saturation and brightness. Built-in maps can't be changed, only copied.
 */
@Composable
fun ColorMapEditor(current: Int, onPick: (Int) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf(if (current >= Fractal.BUILT_IN_MAPS.size) current else -1) }
    var confirm by remember { mutableStateOf(false) }
    BackHandler { if (editing >= 0) editing = -1 else onClose() }
    LaunchedEffect(editing) { delay(60); FocusMem.restoreSoon(if (editing >= 0) "row:cm:done" else "row:cm:new") }
    val maps = Fractal.maps
    Text(if (editing >= 0) "EDIT COLOR MAP" else "COLOR MAPS", color = CYAN, fontSize = 11.sp, letterSpacing = 1.2.sp, maxLines = 1)
    Text(if (editing >= 0) "Five colours, looping · Back = the list" else "OK = use it · Back = layer settings", color = DIM, fontSize = 9.sp, maxLines = 1, modifier = Modifier.padding(bottom = 6.dp))
    val e = editing
    if (e >= 0) {
        val ci = e - Fractal.BUILT_IN_MAPS.size
        val map = Fractal.custom.getOrNull(ci) ?: run { editing = -1; return }
        fun save(m: Fractal.ColorMap) = Fractal.saveCustom(ctx, Fractal.custom.toMutableList().also { it[ci] = m })
        fun hsv(rgb: Int): FloatArray = FloatArray(3).also { android.graphics.Color.RGBToHSV((rgb shr 16) and 255, (rgb shr 8) and 255, rgb and 255, it) }
        fun setStop(i: Int, h: FloatArray) = save(map.copy(stops = map.stops.toMutableList().also { it[i] = android.graphics.Color.HSVToColor(h) and 0xFFFFFF }))
        LoopColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            item { ColorMapStrip(map, Modifier.fillMaxWidth().height(14.dp).padding(horizontal = 12.dp)) }
            item { Row_("✓ Done: " + map.name, false, id = "cm:done", onFocus = {}) { editing = -1 } }
            for (i in 0 until 5) {
                item(key = "cmh:$i") {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp)) {
                        Text("Color ${i + 1}", color = FG, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Box(Modifier.width(36.dp).height(12.dp).background(col(map.stops[i]), RoundedCornerShape(3.dp)).border(1.dp, DIM, RoundedCornerShape(3.dp)))
                    }
                }
                item(key = "cm:$i:h") {
                    val h = hsv(map.stops[i])
                    Adjust("Color ${i + 1} hue", "${Math.round(h[0])}°", { setStop(i, h.also { it[0] = (it[0] - 10f + 360f) % 360f }) }, compact = true, fill = h[0] / 360f, mini = "Hue") { setStop(i, h.also { it[0] = (it[0] + 10f) % 360f }) }
                }
                item(key = "cm:$i:s") {
                    val h = hsv(map.stops[i])
                    Adjust("Color ${i + 1} saturation", "${Math.round(h[1] * 100)}%", { setStop(i, h.also { it[1] = (it[1] - 0.05f).coerceAtLeast(0f) }) }, compact = true, fill = h[1], mini = "Saturation") { setStop(i, h.also { it[1] = (it[1] + 0.05f).coerceAtMost(1f) }) }
                }
                item(key = "cm:$i:v") {
                    val h = hsv(map.stops[i])
                    Adjust("Color ${i + 1} brightness", "${Math.round(h[2] * 100)}%", { setStop(i, h.also { it[2] = (it[2] - 0.05f).coerceAtLeast(0f) }) }, compact = true, fill = h[2], mini = "Brightness") { setStop(i, h.also { it[2] = (it[2] + 0.05f).coerceAtMost(1f) }) }
                }
            }
            item { Row_(if (confirm) "🗑 OK again to delete" else "🗑 Delete this color map", confirm, id = "cm:del", onFocus = {}) {
                if (!confirm) confirm = true
                else { Fractal.saveCustom(ctx, Fractal.custom.filterIndexed { k, _ -> k != ci }); onPick(0); confirm = false; editing = -1 }
            } }
        }
    } else LoopColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { Row_("＋ New color map (copy of the one in use)", false, id = "cm:new", onFocus = {}) {
            val base = Fractal.map(current)
            var n = 1; while (maps.any { it.name == "My colors $n" }) n++
            Fractal.saveCustom(ctx, Fractal.custom + base.copy(name = "My colors $n"))
            val i = Fractal.maps.size - 1; onPick(i); editing = i
        } }
        maps.forEachIndexed { i, m ->
            item(key = "cm:m:$i") {
                Box {
                    Row_((if (i == current) "● " else "   ") + m.name, i == current, id = "cm:m:$i", onFocus = {}) { onPick(i) }
                    ColorMapStrip(m, Modifier.padding(start = 128.dp, top = 9.dp, end = 12.dp).fillMaxWidth().height(10.dp))
                }
            }
            if (i >= Fractal.BUILT_IN_MAPS.size && i == current) item(key = "cm:e:$i") { Row_("   ✎ Edit " + m.name, false, id = "cm:e:$i", small = true, onFocus = {}) { editing = i } }
        }
    }
}
