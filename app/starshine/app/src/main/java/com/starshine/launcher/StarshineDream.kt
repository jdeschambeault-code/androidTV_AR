package com.starshine.launcher

import android.service.dreams.DreamService
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** Android screensaver ("Daydream") showing a Starshine background + a slowly drifting clock (burn-in safe). */
class StarshineDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        setContentView(ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@StarshineDream)
            setViewTreeSavedStateRegistryOwner(this@StarshineDream)
            setContent { DreamContent() }
        })
    }

    override fun onDreamingStarted() { super.onDreamingStarted(); registry.currentState = Lifecycle.State.RESUMED }
    override fun onDreamingStopped() { super.onDreamingStopped(); registry.currentState = Lifecycle.State.CREATED }
    override fun onDestroy() { registry.currentState = Lifecycle.State.DESTROYED; super.onDestroy() }
}

@Composable
private fun DreamContent() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val bg = prefs.saverBg.ifEmpty { prefs.bg }
    val now by produceState(Date()) { while (true) { value = Date(); delay(1_000) } }
    androidx.compose.runtime.LaunchedEffect(bg) { Nebula.alpha = prefs.nebulaA; Nebula.image = if (bg == "Starfield") Nebula.load(ctx, prefs.nebula) else null }
    val t = now.time / 1000.0
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Background(bg, prefs.style, prefs.fps30)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = prefs.dim)))
        Column(
            Modifier.align(Alignment.Center).offset((sin(t / 97.0) * 260 + prefs.offX * 960).dp, (cos(t / 131.0) * 140 + prefs.offY * 540).dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), color = FG.copy(alpha = 0.85f), fontSize = 64.sp, fontWeight = FontWeight.Thin)
            Text(SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now), color = DIM, fontSize = 14.sp)
        }
    }
}
