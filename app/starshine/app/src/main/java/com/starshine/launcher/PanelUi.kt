package com.starshine.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Collapsible sections for the layer settings of scenes and moods. One is open at a time in a panel. The open one is
 * remembered per panel, not per layer, so stepping through layers keeps you in the section you're tuning.
 */
object Sections {
    private val open = mutableStateMapOf<String, String>()
    fun isOpen(panel: String, name: String, first: String) = (open[panel] ?: first) == name
    fun set(panel: String, name: String, opened: Boolean, first: String) {
        if (opened) open[panel] = name else if (isOpen(panel, name, first)) open[panel] = ""
    }
}

/** A section: its header always shows, and a closed one sums up what's inside. `first` is the section open by default. */
fun LazyListScope.section(panel: String, name: String, first: String, summary: String = "", content: LazyListScope.() -> Unit) {
    val opened = Sections.isOpen(panel, name, first)
    item(key = "sec:$panel:$name") {
        SectionHeader(name, opened, summary) { Sections.set(panel, name, it, first) }
    }
    if (opened) content()
}

@Composable
private fun SectionHeader(name: String, opened: Boolean, summary: String, onOpen: (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().remembered("row:sec:$name")
            .focusProperties { left = FocusRequester.Cancel; right = FocusRequester.Cancel }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) M5.carryEdit = false }.focusOnHover()
            .onPreviewKeyEvent { e ->
                when {
                    e.type != KeyEventType.KeyDown -> false
                    e.key == Key.DirectionRight -> { onOpen(true); true }
                    e.key == Key.DirectionLeft -> { onOpen(false); true }
                    else -> false
                }
            }
            .clickable { onOpen(!opened) }
            .background(if (focused) CYAN.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(10.dp))
            .border(1.dp, if (focused) CYAN else Color.Transparent, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text((if (opened) "▾ " else "▸ ") + name, color = FG, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (!opened && summary.isNotEmpty()) Text(summary, color = DIM, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 8.dp))
    }
}
