package com.starshine.launcher

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * Shows HELP.md, the same file GitHub displays. The build copies it and docs/images into the app, so help works offline
 * and can't differ from the repository. Handles the Markdown HELP.md uses: headings, lists, tables, pictures, bold, code, links.
 */
private sealed class Block {
    class Heading(val level: Int, val text: String) : Block()
    class Para(val text: String, val bullet: Boolean) : Block()
    class Picture(val path: String) : Block()
}

private fun parse(md: String): List<Block> {
    val out = ArrayList<Block>()
    val picture = Regex("""^!\[[^\]]*]\(([^)]+)\)""")
    for (raw in md.lines()) {
        val l = raw.trim()
        when {
            l.isEmpty() || l.startsWith("<!--") || l == "---" -> {}
            l.startsWith("#") -> { val n = l.takeWhile { it == '#' }.length; out.add(Block.Heading(n, l.drop(n).trim())) }
            picture.containsMatchIn(l) -> out.add(Block.Picture(picture.find(l)!!.groupValues[1]))
            l.startsWith("|") -> {
                if (!l.replace(" ", "").all { it == '|' || it == '-' || it == ':' })
                    out.add(Block.Para(l.trim('|').split("|").joinToString("  ·  ") { it.trim() }, true))
            }
            l.startsWith("- ") || l.startsWith("* ") -> out.add(Block.Para(l.drop(2), true))
            Regex("""^\d+\. """).containsMatchIn(l) -> out.add(Block.Para(l, true))
            else -> out.add(Block.Para(l, false))
        }
    }
    return out
}

/** **bold**, `code` and [text](link) -> styled text. */
private fun inline(s: String, code: Color): AnnotatedString = buildAnnotatedString {
    val t = Regex("""\[([^\]]+)]\([^)]+\)""").replace(s) { it.groupValues[1] }
    var i = 0
    while (i < t.length) {
        when {
            t.startsWith("**", i) && t.indexOf("**", i + 2) > 0 -> {
                val e = t.indexOf("**", i + 2)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(t.substring(i + 2, e)) }; i = e + 2
            }
            t[i] == '`' && t.indexOf('`', i + 1) > 0 -> {
                val e = t.indexOf('`', i + 1)
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = code)) { append(t.substring(i + 1, e)) }; i = e + 1
            }
            else -> { append(t[i]); i++ }
        }
    }
}

private fun loadHelp(ctx: Context) = runCatching { ctx.assets.open("help/HELP.md").bufferedReader().use { it.readText() } }.getOrDefault("# Help\nHelp isn't included in this build.")

/** ↑ ↓ scroll · ← → previous / next section · Back closes. */
@Composable
fun HelpViewer(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val blocks = remember { parse(loadHelp(ctx)) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    BackHandler { onClose() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun jump(d: Int) {
        val now = list.firstVisibleItemIndex
        val heads = blocks.withIndex().filter { val b = it.value; b is Block.Heading && b.level <= 2 }.map { it.index }
        val target = if (d > 0) heads.firstOrNull { it > now } else heads.lastOrNull { it < now }
        scope.launch { list.animateScrollToItem(target ?: if (d > 0) blocks.lastIndex.coerceAtLeast(0) else 0) }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFA060C14)).focusRequester(focus).onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent e.key != Key.Back
        when (e.key) {
            Key.DirectionDown -> { scope.launch { list.animateScrollBy(150f) }; true }
            Key.DirectionUp -> { scope.launch { list.animateScrollBy(-150f) }; true }
            Key.DirectionRight -> { jump(1); true }
            Key.DirectionLeft -> { jump(-1); true }
            else -> false
        }
    }.focusable()) {
        Text("↑ ↓ scroll  ·  ← → section  ·  Back = close", color = DIM, fontSize = 9.sp, modifier = Modifier.padding(start = 90.dp, top = 8.dp))
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 90.dp, end = 90.dp, top = 28.dp, bottom = 60.dp), userScrollEnabled = false) {
            itemsIndexed(blocks) { _, b ->
                when (b) {
                    is Block.Heading -> Text(inline(b.text, CYAN), color = if (b.level <= 2) CYAN else FG, fontWeight = FontWeight.SemiBold,
                        fontSize = when (b.level) { 1 -> 22.sp; 2 -> 16.sp; else -> 13.sp },
                        modifier = Modifier.padding(top = if (b.level <= 2) 18.dp else 10.dp, bottom = 4.dp))
                    is Block.Para -> Text(inline(if (b.bullet) "•  " + b.text else b.text, AMBER), color = FG, fontSize = 12.sp,
                        modifier = Modifier.padding(start = if (b.bullet) 10.dp else 0.dp, top = 2.dp, bottom = 3.dp))
                    is Block.Picture -> Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        AsyncImage("file:///android_asset/help/images/" + b.path.substringAfterLast('/'), null,
                            Modifier.fillMaxWidth().heightIn(max = 340.dp), contentScale = ContentScale.Fit)
                    }
                }
            }
        }
    }
}
