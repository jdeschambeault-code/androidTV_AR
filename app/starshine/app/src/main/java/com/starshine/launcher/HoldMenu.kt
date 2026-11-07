package com.starshine.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * What hold OK opens on a tile: one menu, in one place, so the same gesture means the same thing everywhere.
 * It sits on top of the screen instead of replacing it, so a builder keeps its open layer and unsaved state underneath.
 */
object HoldMenu {
    /** Selects the item the menu was opened from. */
    const val SAME = "\u0000same"

    /** [focusAfter] is the item to select once done: [SAME], another item's id, or null when the action moves the selection itself. */
    class Action(val label: String, val confirm: Boolean = false, val focusAfter: String? = SAME, val run: () -> Unit)
    class Spec(val title: String, val actions: List<Action>, val from: String?)

    var spec by mutableStateOf<Spec?>(null)
        private set

    fun open(title: String, actions: List<Action>) {
        FocusMem.swallowOk = true            // the rest of this press isn't a press on the menu's first row
        M5.hoverTarget = null                // the air mouse's next click belongs to the menu, not to the tile under its cursor
        spec = Spec(title, actions, FocusMem.current ?: FocusMem.last)
    }

    fun pick(a: Action) {
        val from = spec?.from
        spec = null
        FocusMem.last = from                 // a menu this action opens returns here, not to a row that's about to disappear
        a.run()
        val to = if (a.focusAfter == SAME) from else a.focusAfter
        FocusMem.scope?.launch { FocusMem.restoreSoon(to) }
    }

    fun cancel() {
        val from = spec?.from
        spec = null
        FocusMem.scope?.launch { FocusMem.restoreSoon(from) }
    }

    /** The screen changed under the menu, so there's nothing to go back to. */
    fun dismiss() { spec = null }
}

@Composable
fun HoldMenuBox(s: HoldMenu.Spec) {
    var armed by remember(s) { mutableIntStateOf(-1) }
    BackHandler { HoldMenu.cancel() }
    LaunchedEffect(s) { FocusMem.restoreSoon("row:hm:0") }
    // takes every pointer event, so the cursor can't reach or select what's behind the menu
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).pointerInput(Unit) { detectTapGestures { HoldMenu.cancel() } }, contentAlignment = Alignment.Center) {
        Column(Modifier.width(300.dp)
            .background(Color(0xFF060C14).copy(alpha = 0.97f), RoundedCornerShape(14.dp)).border(1.dp, CYAN.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .padding(12.dp)
            .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup(),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(s.title, color = CYAN, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
            s.actions.forEachIndexed { i, a ->
                val asking = armed == i
                Row_(if (asking) "OK again: ${a.label}" else a.label, asking, id = "hm:$i", onFocus = { if (armed != i) armed = -1 }) {
                    if (a.confirm && !asking) armed = i else HoldMenu.pick(a)
                }
            }
            Row_("Cancel", false, id = "hm:no", onFocus = { armed = -1 }) { HoldMenu.cancel() }
        }
    }
}
