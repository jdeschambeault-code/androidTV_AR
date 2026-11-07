package com.starshine.launcher

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Temporary saves while building: one unsaved scene and one unsaved mood. They survive leaving the builder, pressing Home
 * and restarting the app, and go away once the work is saved, reset or discarded.
 */
object Drafts {
    var scene by mutableStateOf<BgScene?>(null); private set
    var mood by mutableStateOf<AudioStack?>(null); private set
    /** The builder was left with Home: it reopens on the way back to its tab. */
    var resumeScene = false; private set
    var resumeMood = false; private set
    private var sceneOff = false
    private var moodOff = false
    private var loaded = false
    private fun sp(ctx: Context) = ctx.getSharedPreferences("starshine_drafts", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = sp(ctx)
        runCatching { p.getString("scene", null)?.let { scene = BgScene.fromJson(JSONObject(it)) } }
        runCatching { p.getString("mood", null)?.let { mood = AudioStack.fromJson(JSONObject(it)) } }
        resumeScene = p.getBoolean("resumeScene", false); resumeMood = p.getBoolean("resumeMood", false)
    }

    fun builderOpened(isScene: Boolean) { if (isScene) sceneOff = false else moodOff = false }

    /** Called as the scene changes in the builder. Only its own draft is cleared by matching what's saved. */
    fun noteScene(ctx: Context, s: BgScene) {
        if (sceneOff) return
        val clean = s.layers.isEmpty() || Bg2.pristine(s.name) == s
        if (clean) { if (scene?.name == s.name) { scene = null; sp(ctx).edit().remove("scene").apply() } }
        else if (s != scene) { scene = s; sp(ctx).edit().putString("scene", s.toJson().toString()).apply() }
    }
    fun noteMood(ctx: Context, m: AudioStack) {
        if (moodOff) return
        val clean = m.layers.isEmpty() || Med.stacks.firstOrNull { it.name == m.name } == m
        if (clean) { if (mood?.name == m.name) { mood = null; sp(ctx).edit().remove("mood").apply() } }
        else if (m != mood) { mood = m; sp(ctx).edit().putString("mood", m.toJson().toString()).apply() }
    }
    fun discardScene(ctx: Context) { sceneOff = true; scene = null; sp(ctx).edit().remove("scene").putBoolean("resumeScene", false).apply(); resumeScene = false }
    fun discardMood(ctx: Context) { moodOff = true; mood = null; sp(ctx).edit().remove("mood").putBoolean("resumeMood", false).apply(); resumeMood = false }
    fun setResume(ctx: Context, isScene: Boolean, on: Boolean) {
        if (isScene) resumeScene = on else resumeMood = on
        sp(ctx).edit().putBoolean(if (isScene) "resumeScene" else "resumeMood", on).apply()
    }
    fun renamed(ctx: Context, old: String, new: String) {
        scene?.takeIf { it.name == old }?.let { scene = it.copy(name = new); sp(ctx).edit().putString("scene", scene!!.toJson().toString()).apply() }
        mood?.takeIf { it.name == old }?.let { mood = it.copy(name = new); sp(ctx).edit().putString("mood", mood!!.toJson().toString()).apply() }
    }
}

/** Hold OK on a scene or mood: its own actions first if it has any, then edit, rename, delete. Takes the place of the list until closed. */
@Composable
fun ItemMenu(name: String, renameLabel: String, canDelete: Boolean, onEdit: () -> Unit, onRename: (String) -> Boolean, onDelete: () -> Unit, onClose: () -> Unit,
             actions: List<Pair<String, () -> Unit>> = emptyList()) {
    var renaming by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(TextFieldValue(name, TextRange(name.length))) }
    var problem by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    BackHandler { if (renaming) renaming = false else onClose() }
    LaunchedEffect(renaming) {
        if (renaming) { delay(80); runCatching { field.requestFocus() } } else FocusMem.restoreSoon(if (actions.isEmpty()) "row:im:edit" else "row:im:a0")
    }
    fun save() {
        val n = text.text.trim()
        if (n == name) { renaming = false; return }
        if (n.isEmpty() || !onRename(n)) problem = if (n.isEmpty()) "Enter a name" else "That name is already used"
    }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
        .border(1.dp, CYAN.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(name, color = CYAN, fontSize = 12.sp, maxLines = 2)
        if (!renaming) {
            actions.forEachIndexed { i, (label, act) -> Row_(label, false, id = "im:a$i", onFocus = {}) { act() } }
            Row_("◈ Edit and tweak the layers", false, id = "im:edit", onFocus = {}) { onEdit() }
            Row_("✎ $renameLabel", false, id = "im:rename", onFocus = {}) { problem = ""; renaming = true }
            if (canDelete) Row_(if (confirm) "🗑 OK again to delete" else "🗑 Delete", confirm, id = "im:del", onFocus = {}) { if (confirm) onDelete() else confirm = true }
            Row_("Cancel", false, id = "im:no", onFocus = {}) { onClose() }
        } else {
            var focused by remember { mutableStateOf(false) }
            BasicTextField(text, { text = it; problem = "" }, singleLine = true,
                textStyle = TextStyle(color = FG, fontSize = 13.sp), cursorBrush = SolidColor(CYAN),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().focusRequester(field).onFocusChanged { focused = it.isFocused }
                    .border(1.dp, if (focused) CYAN else DIM.copy(alpha = 0.4f), RoundedCornerShape(8.dp)).padding(8.dp))
            if (problem.isNotEmpty()) Text(problem, color = AMBER, fontSize = 10.sp)
            Row_("✓ Save this name", false, id = "im:save", onFocus = {}) { save() }
            Row_("Cancel", false, id = "im:back", onFocus = {}) { renaming = false }
        }
    }
}
