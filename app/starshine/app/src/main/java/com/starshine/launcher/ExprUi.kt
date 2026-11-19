package com.starshine.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

/** The beat that plays when a sound formula is tried with nothing playing. */
const val TEST_BEAT = "DnB test beat"

/**
 * Animate one setting with a formula: hold OK on the setting to come here. Three groups, one open at a time:
 *   Adjust            — how far (amount) and how fast (speed) the chosen formula moves the setting, or write your own
 *   Motion            — formulas that move by themselves over time
 *   Connect to sound  — formulas driven by the music: bass, beat, voice…
 * The setting moves around the value you gave it on its slider.
 */
@Composable
fun ExprPanel(l: Layer, p: BgParams.P, edit: (Layer) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler { onClose() }
    // while it's open the selection can't fall onto a layer tile, which would open another layer
    androidx.compose.runtime.DisposableEffect(Unit) { Bg2.subPanel = true; onDispose { Bg2.subPanel = false } }
    val cur = l.ex[p.key]
    // trying a sound formula with nothing playing: a drum and bass test beat plays, so the reaction shows; it stops on leaving
    var startedBeat by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    fun needSound() { if (Sound.current.isEmpty()) { Sound.playStack(ctx, AudioStack(TEST_BEAT, listOf(AudioLayer(1, DNB_KIND, mapOf("level" to 0.6f)))), 0.5f); startedBeat = true } }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (startedBeat && Sound.current == TEST_BEAT) Sound.stop() } }
    LaunchedEffect(cur) { if (cur != null && Expr.usesSound(cur)) needSound() }
    LaunchedEffect(Unit) { delay(60); FocusMem.restoreSoon("row:sec:" + if (cur != null) "Adjust" else "Motion") }
    // positions reach ±1000: they start small and step in proportion, like their slider
    val far = p.key in setOf("x", "y", "z", "tx", "ty", "tz")
    val span = if (far) 10f else p.max - p.min
    val amount = Expr.amount(l, p.key); val speed = Expr.speed(l, p.key)
    fun step(a: Float) = if (far) maxOf(0.1f, kotlin.math.abs(a) * 0.1f) else maxOf(p.step, span * 0.02f)
    val first = if (cur != null) "Adjust" else "Motion"
    Text("ANIMATE " + p.label.uppercase(), color = CYAN, fontSize = 11.sp, letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(cur?.let { "ƒ  $it" } ?: "Choose how it moves · Back = done", color = if (cur != null) AMBER else DIM, fontSize = 10.sp, maxLines = 2, modifier = Modifier.padding(bottom = 6.dp))
    LoopColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (cur != null) section("expr", "Adjust", first, "amount " + p.fmt(amount) + if (Expr.usesTime(cur)) String.format(Locale.US, " · speed ×%.2f", speed) else "") {
            // amount = a in the formula: how far the setting moves from your value (at full sound, for the sound ones)
            item { Adjust("Amount (a)", (if (amount >= 0f) "+" else "") + p.fmt(amount), { edit(Expr.set(l, p.key, cur, Stepper.next(amount, step(amount), -1, -4000f, 4000f))) }, compact = true) {
                edit(Expr.set(l, p.key, cur, Stepper.next(amount, step(amount), 1, -4000f, 4000f))) } }
            // speed multiplies time (t, and the sound clock st): only formulas that use them have one
            if (Expr.usesTime(cur)) item { Adjust("Speed (× t)", String.format(Locale.US, "×%.2f", speed), { edit(Expr.set(l, p.key, cur, amount, (speed / 1.25f).coerceAtLeast(0.01f))) }, compact = true) {
                edit(Expr.set(l, p.key, cur, amount, (speed * 1.25f).coerceAtMost(100f))) } }
            item { Text(if (Expr.usesTime(cur)) "a = how far it moves · speed = how fast its motion runs"
                else "a = how far it moves at full sound. This formula has no time in it, so it has no speed: it follows the sound directly.",
                color = DIM, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
            item { Row_("✎ Edit the formula…", false, id = "ex:edit", onFocus = {}) {
                formulaDialog(ctx, p.label, cur) { txt -> edit(Expr.set(l, p.key, txt, amount)) }
            } }
            item { Row_("■ Stop animating this setting", false, id = "ex:off", onFocus = {}) { edit(Expr.remove(l, p.key)) } }
        }
        // a new formula starts at a fifth of the setting's range (a third for sound), at normal speed
        section("expr", "Motion", first, "moves by itself") {
            Expr.TEMPLATES.forEachIndexed { i, t ->
                item(key = "ex:t$i") { Row_((if (cur == t.text) "● " else "") + t.name + " · " + t.help, cur == t.text, id = "ex:t$i", small = true, onFocus = {}) {
                    edit(Expr.set(l, p.key, t.text, if (cur != null && amount != 0f) amount else span * 0.2f, if (cur != null) speed else 1f)) } }
            }
        }
        section("expr", "Connect to sound", first, "follows the music (a test beat plays if nothing does)") {
            Expr.SOUND_TEMPLATES.forEachIndexed { i, t ->
                item(key = "ex:s$i") { Row_((if (cur == t.text) "● " else "") + t.name + " · " + t.help, cur == t.text, id = "ex:s$i", small = true, onFocus = {}) {
                    edit(Expr.set(l, p.key, t.text, if (cur != null && amount != 0f) amount else span * 0.3f, if (cur != null) speed else 1f)) } }
            }
        }
        item { Row_("✓ Done", false, id = "ex:done", onFocus = {}) { onClose() } }
    }
}

/* ---------------- the formula editor: raw text, with an Insert list and examples ---------------- */

/** What the Insert list offers: shown text, what goes in the formula, what it means. */
private val INSERTS = listOf(
    Triple("v · your value", "v", "the value set on the slider"),
    Triple("a · amount", "a", "how far it moves (Amount row)"),
    Triple("t · time", "t", "seconds, × the Speed row"),
    Triple("st · sound clock", "st", "a clock that runs faster as the sound gets louder"),
    Triple("bass", "bass", "low sound, 0..1"), Triple("mid", "mid", "middle sound, 0..1"), Triple("high", "high", "high sound, 0..1"),
    Triple("beat", "beat", "1 on each beat, then falls"), Triple("level", "level", "overall loudness, 0..1"),
    Triple("voice", "voice", "voice range, 0..1"), Triple("swell", "swell", "slow rise and fall of the music"),
    Triple("tonebeat", "tonebeat", "the frequency generator's beat"), Triple("tonepitch", "tonepitch", "the frequency generator's pitch"),
    Triple("sin( )", "sin()", "wave -1..1"), Triple("cos( )", "cos()", "wave -1..1, a quarter turn later"),
    Triple("tri( )", "tri()", "there and back, 0..1"), Triple("saw( )", "saw()", "ramp up, then jump back, 0..1"),
    Triple("pulse( )", "pulse()", "quick rise, slow fall, 0..1"), Triple("noise( )", "noise()", "smooth random, 0..1"),
    Triple("fract( )", "fract()", "the part after the point: loops 0..1"), Triple("floor( )", "floor()", "rounds down: steps"),
    Triple("abs( )", "abs()", "without the minus sign"), Triple("sqrt( )", "sqrt()", "square root"),
    Triple("min( , )", "min(,)", "the smaller of two"), Triple("max( , )", "max(,)", "the larger of two"),
    Triple("clamp(x, lo, hi)", "clamp(,,)", "keeps x between lo and hi"), Triple("mix(a, b, k)", "mix(,,)", "from a to b as k goes 0..1"),
    Triple("step(edge, x)", "step(,)", "0 below edge, 1 above"), Triple("pi", "pi", "3.14159"),
    Triple("^ power", "^", "x^2 = x squared"), Triple("% remainder", "%", "loops a number")
)

/** Examples: name, formula, what to try. Choosing one puts it in the box to tweak. */
private val EXAMPLES = listOf(
    Triple("Slow wave", "v + a*sin(t*0.5)", "half speed: change 0.5 to slow it down or speed it up"),
    Triple("Wave, never negative", "v + a*(0.5+0.5*sin(t))", "0.5+0.5*sin(…) turns -1..1 into 0..1"),
    Triple("Two waves together", "v + a*(sin(t) + 0.3*sin(t*3.1))", "a second, faster wave on top: less mechanical"),
    Triple("Bass, but smoothed", "v + a*bass*bass", "squaring keeps quiet parts still and lifts only the loud ones"),
    Triple("Kick only above a level", "v + a*step(0.6, beat)", "moves only when the beat is over 0.6"),
    Triple("Breath that follows the music", "v + a*(0.5-0.5*cos(st))", "the sound clock st makes the breath quicken with loudness"),
    Triple("Steps of 15", "v + 15*floor(t)", "jumps 15 each second: angles in fixed steps"),
    Triple("Ping-pong between two values", "mix(v, v + a, tri(t/6.2832))", "goes to value + amount and back"),
    Triple("Drift, limited", "clamp(v + a*(noise(t)*2-1), v - a/2, v + a/2)", "wanders, but never more than half the amount"),
    Triple("Faster on the beat", "v + a*sin(t + 3*beat)", "the wave jumps ahead on each beat")
)

/**
 * The formula as text, with help: Insert ▾ puts a variable or function at the cursor, ? Examples fills the box with a
 * formula to tweak, and the line under the box says whether it can be read.
 */
fun formulaDialog(ctx: android.content.Context, label: String, current: String, onUse: (String) -> Unit) {
    val pad = (12 * ctx.resources.displayMetrics.density).toInt()
    val col = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
    val box = android.widget.EditText(ctx).apply { setText(current); setSingleLine(); setSelection(current.length) }
    val status = android.widget.TextView(ctx).apply { textSize = 12f }
    fun check() {
        val ok = Expr.valid(box.text.toString().trim())
        status.text = if (ok) "✓ can be read" else "✗ can't be read yet"
        status.setTextColor(if (ok) 0xFF5DD39E.toInt() else 0xFFFFB347.toInt())
    }
    box.addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) = check()
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
    check()
    /** Puts text at the cursor; a function goes in with the cursor between its brackets. */
    fun insert(code: String) {
        val s = box.selectionStart.coerceAtLeast(0); val e = box.selectionEnd.coerceAtLeast(s)
        val needsSpace = code.first().isLetter() && s > 0 && box.text[s - 1].isLetterOrDigit()
        val text = (if (needsSpace) " " else "") + code
        box.text.replace(s, e, text)
        val inside = text.indexOf('(')
        box.setSelection(if (inside >= 0) s + inside + 1 else s + text.length)
        box.requestFocus()
    }
    val row = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
    val insertBtn = android.widget.Button(ctx).apply { text = "Insert ▾"; isAllCaps = false }
    val examplesBtn = android.widget.Button(ctx).apply { text = "? Examples"; isAllCaps = false }
    insertBtn.setOnClickListener {
        android.app.AlertDialog.Builder(ctx).setTitle("Insert at the cursor")
            .setItems(INSERTS.map { it.first + "  —  " + it.third }.toTypedArray()) { _, i -> insert(INSERTS[i].second) }.show()
    }
    examplesBtn.setOnClickListener {
        android.app.AlertDialog.Builder(ctx).setTitle("Examples: choose one, then tweak it")
            .setItems(EXAMPLES.map { it.first + "\n   " + it.second + "\n   " + it.third }.toTypedArray()) { _, i ->
                box.setText(EXAMPLES[i].second); box.setSelection(box.text.length); box.requestFocus()
            }.show()
    }
    row.addView(insertBtn); row.addView(examplesBtn)
    col.addView(box); col.addView(status); col.addView(row)
    android.app.AlertDialog.Builder(ctx).setTitle("Formula for $label")
        .setMessage("v = your value · a = amount · t = time × speed · sound values 0..1")
        .setView(col)
        .setPositiveButton("Use it") { _, _ ->
            val txt = box.text.toString().trim()
            if (Expr.valid(txt)) onUse(txt)
            else android.widget.Toast.makeText(ctx, "That formula can't be read: nothing changed", android.widget.Toast.LENGTH_LONG).show()
        }.setNegativeButton("Cancel", null).show()
}
