package com.starshine.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Settings → About → Demo: a guided tour of about 2½ minutes, 15 to 25 s per section, with a caption naming each one.
 * Any key stops it, and the scene, look and sound from before come back. It drives the Home screen itself (Launcher
 * runs the steps); this object holds what's shared.
 *
 * Recording: the toolkit's demo.js records the screen for the whole tour (Android's screenrecord, up to 3 minutes per
 * file, which is why the tour stays under that) and saves each section's start time as chapters, from the
 * "StarshineDemo" log lines.
 */
object Demo {
    data class Section(val title: String, val about: String, val seconds: Int)

    val SECTIONS = listOf(
        Section("Home", "The dock, the top menu and what's playing", 15),
        Section("Flat scenes", "Layered 2D backgrounds that react to sound", 21),
        Section("3D space", "Scenes in one space: particles, planets, portals", 24),
        Section("Landscapes", "Real places, brought to life with sway, mist and light", 20),
        Section("Moods", "Soundscapes generated live: rain, ocean, bowls, drones", 20),
        Section("Visual Meditation", "Journeys of scenes and sounds that blend step by step", 20),
        Section("Quick settings", "Volume, brightness, scenes and the music player, from any app", 15),
        Section("Render looks", "Bloom, glare, depth of field and colour, per scene or for all", 20)
    )
    val TOTAL get() = SECTIONS.sumOf { it.seconds }

    /** Raised to start the tour (Settings row, or the toolkit's --ez demo true). */
    var request by mutableIntStateOf(0)
    /** The last request the tour ran for. */
    var handled = 0
    /** The key that stopped the tour: its release is swallowed too, so it doesn't also press what's selected. */
    var swallowUp = false
    var running by mutableStateOf(false)
    var caption by mutableStateOf<Section?>(null)
    private var t0 = 0L

    fun begin() { running = true; t0 = System.currentTimeMillis(); android.util.Log.i("StarshineDemo", "start, ${SECTIONS.size} sections, ${TOTAL} s") }
    fun section(i: Int) {
        caption = SECTIONS[i]
        android.util.Log.i("StarshineDemo", "section ${i + 1} '${SECTIONS[i].title}' at ${(System.currentTimeMillis() - t0) / 1000.0} s")
    }
    fun end() { running = false; caption = null; android.util.Log.i("StarshineDemo", "end at ${(System.currentTimeMillis() - t0) / 1000.0} s") }
    /** Any key while the tour runs stops it (the key itself is not passed on). */
    fun stop() { if (running) { running = false; android.util.Log.i("StarshineDemo", "stopped by a key") } }

    /** Waits [seconds], in short steps so a key stops the tour at once. Returns false once it was stopped. */
    suspend fun hold(seconds: Float): Boolean {
        var left = (seconds * 1000).toLong()
        while (left > 0 && running) { delay(minOf(250L, left)); left -= 250 }
        return running
    }
}

/** The caption at the bottom of the screen while the tour runs. */
@Composable
fun DemoCaption(s: Demo.Section, modifier: Modifier = Modifier) {
    val i = Demo.SECTIONS.indexOf(s)
    Column(modifier.padding(bottom = 36.dp).widthIn(max = 560.dp)
        .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(16.dp)).border(1.dp, CYAN.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
        .padding(horizontal = 22.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${i + 1} / ${Demo.SECTIONS.size}  ·  ${s.title.uppercase()}", color = CYAN, fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold)
        Text(s.about, color = FG, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
        Text("Starshine demo · any key stops", color = DIM, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
