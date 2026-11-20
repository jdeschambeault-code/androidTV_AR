package com.starshine.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.zIndex
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

val CYAN: Color get() = Theme.accent
val VIOLET: Color get() = Theme.ring
val FG: Color get() = Theme.text
val DIM: Color get() = Theme.dim
val GREEN = Color(0xFF3DFF9A)
val AMBER = Color(0xFFFFC857)
val RED = Color(0xFFFF5C7A)
private const val SELF = "com.starshine.launcher"
const val SETTINGS_ID = "@settings"
const val BROWSER_ID = "@browser"
private const val JELLYFIN_PKG = "org.jellyfin.androidtv"
private const val YTMUSIC_PKG = "com.google.android.youtube.tvmusic"
private const val SMARTTUBE = "org.smarttube.stable"   // was com.teamsmart.videomanager.tv before 2025
private const val GOOGLE_HOME = "com.google.android.tvlauncher"
private val HIDDEN = setOf("com.droidlogic.tv.settings")
private const val EASYCONTROL = "top.saymzx.easycontrol.app"   // views an optional Bliss OS VM on the pod
const val BLISS_ID = "@bliss"   // Bliss OS tile, only with starshine.bliss in local.properties: opens Easycontrol or explains how to install it

/** Your own LAN sites, from starshine.webTiles in local.properties ("Name|url;Name|url"). */
private val PRIVATE_TILES = BuildConfig.WEB_TILES.split(";").filter { it.contains("|") }.map { "web:$it" }

val WEB_PRESETS = listOf(
    "web:Songsterr|https://www.songsterr.com",
    "web:Ultimate Guitar|https://www.ultimate-guitar.com",
    "web:YouTube Music|https://music.youtube.com"
) + PRIVATE_TILES

private val DEFAULT_DOCK = listOf(
    "com.google.android.youtube.tv", "com.android.vending", "com.amazon.amazonvideo.livingroom",
    JELLYFIN_PKG, "com.synology.dsvideo", "com.internet.tvbrowser",
    SMARTTUBE, "com.limelight", "org.mozilla.firefox", WEB_PRESETS[0]
) + PRIVATE_TILES.take(2) + (if (BuildConfig.BLISS.isNotEmpty()) listOf(BLISS_ID) else emptyList())

private val BROWSERS = listOf("@builtin" to "Starshine built-in", "org.mozilla.firefox" to "Firefox", "com.phlox.tvwebbrowser" to "TV Bro", "" to "System default")

/** Scene mood = background + look + dim + sound. */
data class Mood(val name: String, val bg: String, val style: BgStyle, val dim: Float, val ambient: String)
val MOODS = listOf(
    Mood("Focus", "Constellations", BgStyle(speed = 0.6f, glow = 0.8f), 0.35f, "Off"),
    Mood("Movie", "Starfield", BgStyle(speed = 0.4f, glow = 0.6f, brightness = 0.7f), 0.6f, "Off"),
    Mood("Sleep", "Breathing orbs", BgStyle(speed = 0.5f, hue = 330f, brightness = 0.6f, saturation = 0.7f), 0.5f, "Rain"),
    Mood("Calm", "Bioluminescent sea", BgStyle(speed = 0.7f), 0.25f, "Ocean"),
    Mood("Night forest", "Fireflies", BgStyle(speed = 0.8f, glow = 1.2f), 0.2f, "Off"),
    Mood("Party", "Aurora", BgStyle(speed = 1.6f, glow = 1.6f, saturation = 1.4f), 0.1f, "Off")
)

data class AppEntry(val id: String, val label: String, val icon: ImageBitmap? = null, val banner: ImageBitmap? = null, val round: ImageBitmap? = null) {
    val isWeb get() = id.startsWith("web:")
}

fun webEntry(id: String) = AppEntry(id, id.removePrefix("web:").substringBefore("|"))

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE)
    private fun f(k: String, d: Float) = sp.getFloat(k, d)
    private fun put(k: String, v: Float) = sp.edit().putFloat(k, v).apply()
    private fun s(k: String, d: String) = sp.getString(k, d)!!
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var bg get() = s("bg", BG2_ID); set(v) = put("bg", v)
    var saverBg get() = s("saverBg", ""); set(v) = put("saverBg", v)
    var dim get() = f("dim", 0.2f); set(v) = put("dim", v)
    var fps30 get() = b("fps30", true); set(v) = put("fps30", v)
    var browser get() = s("browser", "@builtin"); set(v) = put("browser", v)
    var jellyfin get() = s("jf", BuildConfig.JELLYFIN); set(v) = put("jf", v)
    var jfToken get() = s("jfToken", ""); set(v) = put("jfToken", v)
    var jfUser get() = s("jfUser", ""); set(v) = put("jfUser", v)
    var showResume get() = b("showResume", true); set(v) = put("showResume", v)
    var dockScale get() = f("dockScale", 0.5f); set(v) = put("dockScale", v)
    var hideSecs get() = f("hideSecs", 10f); set(v) = put("hideSecs", v)
    var topHideSecs get() = f("topHideSecs", 40f); set(v) = put("topHideSecs", v)
    var uiScale get() = f("uiScale", 1f); set(v) = put("uiScale", v)
    var glasses get() = b("glasses", false); set(v) = put("glasses", v)
    var offX get() = f("offX", 0f); set(v) = put("offX", v)
    var offY get() = f("offY", 0f); set(v) = put("offY", v)
    var ambient get() = s("ambient", "Off"); set(v) = put("ambient", v)
    var ambientVol get() = f("ambientVol", 0.35f); set(v) = put("ambientVol", v)
    var nebula get() = s("nebula", "Violet"); set(v) = put("nebula", v)
    var nebulaA get() = f("nebulaA", 0.55f); set(v) = put("nebulaA", v)
    /** Quick-panel switches: elements on or off, without touching which background or sound is chosen. */
    var bgOn get() = b("bgOn", true); set(v) = put("bgOn", v)
    var dimOn get() = b("dimOn", true); set(v) = put("dimOn", v)
    var lastSound get() = s("lastSound", ""); set(v) = put("lastSound", v)
    fun reactive(bg: String) = b("react:$bg", false)
    fun setReactive(bg: String, on: Boolean) = put("react:$bg", on)
    fun migrated(tag: String): Boolean { val done = b("mig_$tag", false); if (!done) put("mig_$tag", true); return done }
    var dock: List<String>
        get() = sp.getString("dock2", null)?.split("\n")?.filter { it.isNotBlank() } ?: DEFAULT_DOCK
        set(v) = put("dock2", v.joinToString("\n"))
    var style: BgStyle
        get() = BgStyle(f("speed", 1f), f("density", 0.75f), f("glow", 1f), f("hue", 0f), f("size", 1f),
            f("brightness", 1f), f("saturation", 1f), f("twinkle", 1f), f("vignette", 1f), f("dirX", 0f), f("dirY", 0f), f("dirZ", 1f))
        set(v) {
            put("speed", v.speed); put("density", v.density); put("glow", v.glow); put("hue", v.hue); put("size", v.size)
            put("brightness", v.brightness); put("saturation", v.saturation); put("twinkle", v.twinkle); put("vignette", v.vignette)
            put("dirX", v.dirX); put("dirY", v.dirY); put("dirZ", v.dirZ)
        }
}

class MainActivity : ComponentActivity() {
    private var refresh by mutableIntStateOf(0)
    private var quickRequest by mutableIntStateOf(0)
    private var homeRequest by mutableIntStateOf(0)
    private var sceneRequest by mutableStateOf("")
    private var builderRequest by mutableIntStateOf(0)
    private var peekRequest by mutableStateOf("")
    private var buildRequest by mutableStateOf("")
    /** Test hook: "--es journeyTest <name>" plays a journey with the audio test; "--ef journeyScale 0.1" shortens its steps. */
    private var journeyRequest by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i("StarshineInput", "screen created, tab was " + savedTab)
        Sound.init(this); Brightness.load(this); HeadTrack.load(this); MusicPlayer.load(this)
        Theme.load(this)
        Drone.load(this)
        SamsungTv.load(this)
        Med.load(this)
        Drafts.load(this)
        Bg2.load(this); Bg2.adoptDefault(this)
        Packs.load(this)
        Support.load(this)
        Look.load(this)
        intent?.getStringExtra("supportKey")?.let { Support.enter(this, it) }
        getExternalFilesDir("nebulae"); getExternalFilesDir("backgrounds")
        val p = Prefs(this)
        Ambient.volume = p.ambientVol
        if (Sound.current.isEmpty() && p.ambient != "Off") Sound.playAmbient(this, p.ambient, p.ambientVol, explicit = false)
        if (intent?.getBooleanExtra("quick", false) == true) quickRequest++
        // opened normally (not via the Home button) -> Home button goes to Starshine again
        getSharedPreferences("starshine", MODE_PRIVATE).edit().putBoolean("homeOverride", true).apply()
        // a recreated screen (after the system closed it) must not replay the extras it was first opened with
        if (savedInstanceState != null) intent?.replaceExtras(android.os.Bundle())
        sceneRequest = intent?.getStringExtra("bgScene") ?: ""
        journeyRequest = intent?.getStringExtra("journeyTest")?.let { it + "|" + intent.getFloatExtra("journeyScale", 1f) } ?: ""
        if (intent?.getBooleanExtra("bgNew", false) == true) builderRequest++
        peekRequest = intent?.getStringExtra("bgPeek") ?: ""; buildRequest = intent?.getStringExtra("bgBuild") ?: ""
        Bg2.noFx = intent?.getBooleanExtra("noFx", false) == true
        if (intent?.getBooleanExtra("soundTest", false) == true) soundTest()
        // the logo in the theme is only for the start: never let it show through behind the interface
        window.setBackgroundDrawableResource(android.R.color.black)
        setContent {
            Box(Modifier.fillMaxSize()) {
                Launcher(refresh, quickRequest, homeRequest, sceneRequest, builderRequest, peekRequest, buildRequest, journeyRequest)
                StartupLogo()
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("quick", false)) quickRequest++
        if (intent.getBooleanExtra("home", false)) homeRequest++
        intent.getStringExtra("bgScene")?.let { sceneRequest = it }
        if (intent.getBooleanExtra("bgNew", false)) builderRequest++
        intent.getStringExtra("bgPeek")?.let { peekRequest = it }
        Bg2.noFx = intent.getBooleanExtra("noFx", false)
        // test hook: --es playerTest <package> = choose that player and press ▶ (same as the quick panel)
        intent.getStringExtra("playerTest")?.let { MusicPlayer.choose(this, it); MusicPlayer.play(this) }
        // test hook: --es browseTest <package> = log what that player's library shows Starshine (tag StarshinePlayer)
        intent.getStringExtra("browseTest")?.let { MusicPlayer.probeBrowse(this, it) }
        intent.getStringExtra("supportKey")?.let { Support.enter(this, it) }
        intent.getStringExtra("bgBuild")?.let { buildRequest = it }
        intent.getStringExtra("journeyTest")?.let { journeyRequest = it + "|" + intent.getFloatExtra("journeyScale", 1f) + "|" + System.nanoTime() }
        if (intent.getBooleanExtra("soundTest", false)) soundTest()
    }
    /** First stop for every key from every remote. Compose only delivers keys while something has focus, so this runs before it. */
    override fun dispatchKeyEvent(e: android.view.KeyEvent): Boolean {
        val c = e.keyCode
        if (e.action == android.view.KeyEvent.ACTION_DOWN && e.repeatCount == 0)
            android.util.Log.i("StarshineInput", "raw key " + android.view.KeyEvent.keyCodeToString(c) + " scan=" + e.scanCode + " from " + (e.device?.name ?: "injected"))
        val arrow = c == android.view.KeyEvent.KEYCODE_DPAD_UP || c == android.view.KeyEvent.KEYCODE_DPAD_DOWN || c == android.view.KeyEvent.KEYCODE_DPAD_LEFT || c == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        val ok = c == android.view.KeyEvent.KEYCODE_DPAD_CENTER || c == android.view.KeyEvent.KEYCODE_ENTER || c == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER ||
            c == android.view.KeyEvent.KEYCODE_BUTTON_SELECT || c == android.view.KeyEvent.KEYCODE_BUTTON_A
        // the rest of a long press that opened something: not a press on what's now selected
        if (ok && FocusMem.swallowOk) { if (e.action == android.view.KeyEvent.ACTION_UP) FocusMem.swallowOk = false; return true }
        // focus got lost: put it back before the press, instead of letting Android pick the first item on screen (the clock)
        if ((arrow || ok) && e.action == android.view.KeyEvent.ACTION_DOWN && FocusMem.current == null) {
            android.util.Log.i("StarshineInput", "focus was lost, restoring " + FocusMem.last)
            // a mouse click (the M5's OK) puts the window in touch mode, where most items can't hold focus: leave it first
            (findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0))?.requestFocusFromTouch()
            FocusMem.restore(FocusMem.last)
            if (FocusMem.current == null) FocusMem.pendingDock = true
            // OK with nothing selected only brings the selection back: it doesn't also press it (that opened the last
            // item, often the clock = Quick settings, without anything visibly selected)
            if (ok) { FocusMem.swallowOk = true; return true }
        }
        if (c == android.view.KeyEvent.KEYCODE_BUTTON_SELECT || c == android.view.KeyEvent.KEYCODE_BUTTON_A)
            return super.dispatchKeyEvent(android.view.KeyEvent(e.downTime, e.eventTime, e.action, android.view.KeyEvent.KEYCODE_DPAD_CENTER, e.repeatCount))
        val handled = super.dispatchKeyEvent(e)
        // nothing further that way: a new press loops to the far end. A held arrow stops at the end instead of spinning round.
        if (!handled && arrow && e.action == android.view.KeyEvent.ACTION_DOWN && e.repeatCount == 0 && FocusMem.wrap(c)) return true
        return handled
    }
    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN)
            android.util.Log.i("StarshineInput", "raw click buttons=" + e.buttonState + " tool=" + e.getToolType(0) + " from " + (e.device?.name ?: "?") + " focus=" + FocusMem.current + " hover=" + (M5.hoverTarget != null))
        return super.dispatchTouchEvent(e)
    }
    override fun dispatchGenericMotionEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_BUTTON_PRESS)
            android.util.Log.i("StarshineInput", "raw button buttons=" + e.buttonState + " from " + (e.device?.name ?: "?"))
        return super.dispatchGenericMotionEvent(e)
    }
    private val blackout = Runnable {
        if (getSharedPreferences("starshine", MODE_PRIVATE).getBoolean("phoneOff", false) && BlackoutActivity.canRun(this)) BlackoutActivity.start(this)
    }
    private val idle by lazy { android.os.Handler(mainLooper) }
    override fun onUserInteraction() { super.onUserInteraction(); idle.removeCallbacks(blackout); idle.postDelayed(blackout, 30_000) }
    override fun onPause() { super.onPause(); idle.removeCallbacks(blackout) }
    // another app in front: moods go quiet there if the user switched that off (Settings → Sound)
    override fun onStop() { super.onStop(); Sound.leftApp() }
    override fun onStart() { super.onStart(); Sound.backInApp() }
    private fun soundTest() { val d = java.io.File(getExternalFilesDir(null), "soundtest"); Thread { Ambient.renderTest(d) }.apply { isDaemon = true; start() } }
    override fun onResume() { super.onResume(); refresh++; FocusMem.pendingDock = true; idle.removeCallbacks(blackout); idle.postDelayed(blackout, 30_000) }
    // sound keeps playing in silent apps; Sound pauses it automatically when another app plays audio
}

/* ---------------- startup logo: covers the first frames, once each time the app starts ---------------- */

private var logoShown = false

@Composable
private fun StartupLogo() {
    var show by remember { mutableStateOf(!logoShown) }
    if (!show) return
    val fade = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(Unit) { logoShown = true; delay(1200); fade.animateTo(0f, tween(450)); show = false }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(Color.Black), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_starshine), "Starshine", Modifier.size(256.dp))
    }
}

/* ---------------- focus memory: focus returns exactly where you were ---------------- */

object FocusMem {
    val map = HashMap<String, FocusRequester>()
    var last: String? = null
    /** Item holding focus right now, null if focus got lost (a closed menu, a removed item). */
    var current: String? = null
    /** The tab on screen, and whether a menu is open over it. Kept up to date by the root screen. */
    var tabName = "Home"
    var modalOpen = false
    /** Set while closing a menu that was opened from another menu. */
    var returnTo: String? = null
    /** Last selected item of each tab, so one tab's memory can't send the selection somewhere else in another. */
    private val spots = HashMap<String, String>()
    fun note(id: String) {
        val top = inTop(id) || id.startsWith("sub:")
        if (!top && !modalOpen) spots[tabName] = id
    }
    private fun inTop(id: String?) = id != null && (id == "clock" || id.startsWith("tab:") || id.startsWith("pill:"))
    /** A tab's icon that isn't the open tab: its arrows up and down don't reach into what's open. */
    private fun onOtherTab() = current?.let { it.startsWith("tab:") && it != "tab:$tabName" } == true
    /** Selects the tab's remembered item, when it's on screen. False leaves the choice to the normal search. */
    fun enterContent(tab: String): Boolean {
        val target = spots[tab]?.let { map[it] } ?: return false
        return runCatching { target.requestFocus() }.isSuccess
    }
    /** Up from the top menu: the tab's remembered item. With none, the search stops here and looping takes over. */
    fun upFromTop(): FocusRequester = if (onOtherTab()) FocusRequester.Cancel else spots[tabName]?.let { map[it] } ?: FocusRequester.Cancel
    /** Down from the top menu: into the Backgrounds / Moods tabs on that screen, otherwise back to where you were. */
    fun downFromTop(tab: String): FocusRequester {
        if (onOtherTab()) return FocusRequester.Cancel
        if (tab == "Bg") map["sub:${Med.subTab}"]?.let { return it }
        // Home: the nearest item straight below (a diagonal step at most), not a remembered spot
        if (tab == "Home") return FocusRequester.Default
        return spots[tab]?.let { map[it] } ?: FocusRequester.Default
    }
    /** Set when Starshine comes to the front: the selection starts on the clock in the top menu; Down goes to the dock. */
    var pendingDock by mutableStateOf(true)
    fun restore(id: String?) { id?.let { runCatching { map[it]?.requestFocus() } } }

    /** Scroll positions of the menus survive closing and reopening them, so the item you came from still exists to return to. */
    private val lists = HashMap<String, androidx.compose.foundation.lazy.LazyListState>()
    fun list(id: String) = lists.getOrPut(id) { androidx.compose.foundation.lazy.LazyListState() }

    /** OK was held to open a menu: ignore that press until it's released. */
    var swallowOk = false

    /* looping: when an arrow finds nothing further, walk the other way to the far end */
    var focus: androidx.compose.ui.focus.FocusManager? = null
    var scope: kotlinx.coroutines.CoroutineScope? = null
    /** The scrolling list holding focus, so a long one can jump to its far end instead of being walked item by item. */
    var activeList: androidx.compose.foundation.lazy.LazyListState? = null
    /** While walking, Up stops at the top of the tab's content instead of leaving for the top menu. */
    var noExit = false
    private var walking = false

    fun wrap(keyCode: Int): Boolean {
        val fm = focus ?: return false; val sc = scope ?: return false
        if (walking || M5.editing != null || Bg2.fullPreview || Drone.immersive) return false
        val updown = keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP || keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
        if (updown && onOtherTab()) return true
        // Up from the top menu goes straight to the remembered item: a search by position finds the pills first
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && inTop(current) && enterContent(tabName)) return true
        val (dir, back) = when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up to FocusDirection.Down
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down to FocusDirection.Up
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left to FocusDirection.Right
            else -> FocusDirection.Right to FocusDirection.Left
        }
        val vertical = dir == FocusDirection.Up || dir == FocusDirection.Down
        android.util.Log.i("StarshineInput", "loop $dir from $current")
        walking = true; noExit = true
        sc.launch {
            try {
                // from the top menu, Up first has to step into the content
                if (vertical && activeList == null) { fm.moveFocus(back); withFrameNanos { } }
                val list = if (vertical) activeList else null
                if (list != null) {
                    list.scrollToItem(if (dir == FocusDirection.Down) 0 else (list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                    withFrameNanos { }
                }
                for (step in 0 until 40) {
                    val before = current
                    if (!fm.moveFocus(back)) break
                    withFrameNanos { }
                    if (list != null && activeList !== list) { fm.moveFocus(dir); break }   // left the list: its end was the step before
                    if (before != null && current == before) break
                }
            } finally { walking = false; noExit = false }
        }
        return true
    }

    /** Focus an item that may only just be appearing. Retries briefly until it's there. */
    suspend fun restoreSoon(id: String?) {
        if (id == null) return
        repeat(15) { restore(id); if (current == id) return; delay(50) }
    }
}

/** A LazyColumn that takes part in looping. */
@Composable
fun LoopColumn(modifier: Modifier = Modifier, state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
               verticalArrangement: Arrangement.Vertical = Arrangement.Top, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(modifier.onFocusChanged { if (it.hasFocus) FocusMem.activeList = state else if (FocusMem.activeList === state) FocusMem.activeList = null },
        state = state, verticalArrangement = verticalArrangement, content = content)
}

fun Modifier.remembered(id: String): Modifier = composed {
    val fr = remember(id) { FocusRequester() }
    DisposableEffect(id) { FocusMem.map[id] = fr; onDispose { if (FocusMem.map[id] === fr) FocusMem.map.remove(id) } }
    this.focusRequester(fr).onFocusChanged {
        if (it.isFocused) {
            FocusMem.last = id; FocusMem.current = id
            FocusMem.note(id)
            android.util.Log.i("StarshineInput", "focus $id")
        } else if (FocusMem.current == id) FocusMem.current = null
    }
}

private var savedTab: Tab? = null

private enum class Tab(val title: String) { Home("Home"), Apps("Apps"), Bg("Bg/Moods"), Med("Meditation"), Drone("Drone"), Tv("TV") }

private val SETTINGS_CATS = listOf(
    "Display & position" to "Dock size, auto-hide, UI scale, glasses mode, screen position, frame rate",
    "Theme" to "Color presets, ring color, panel transparency, fonts",
    "Drone" to "Show or hide the Drone menu, immersive view, drone-only AR dim, picture size",
    "TV" to "Samsung TV remote: find a TV on the network, show or hide the TV menu, forget the TV",
    "Files" to "Browse storage, play videos in VLC, use a file as background",
    "Sound" to "Volume, stop sound, frequency generator",
    "Voice setup" to "Check the microphone and recognizer, test a voice command",
    "M5 air mouse" to "Click behaviour, hover, edit lock, buttons, calibration",
    "Gestures" to "Draw shapes with the M5 to open apps or change the background",
    "Web & Jellyfin" to "Browser for web tiles, Continue watching, Quick Connect",
    "Screensaver" to "Starshine screensaver",
    "Dock" to "Reset dock order",
    "System" to "Wi-Fi, Bluetooth, display, Amlogic, Android settings",
    "About & exit" to "Version, exit Starshine"
)

/** True when an OK press would act on something currently slid off screen (only then is it used just to reveal it). */
private fun focusIsHidden(dockHidden: Boolean, topHidden: Boolean): Boolean {
    val f = FocusMem.last ?: return dockHidden
    val inTop = f == "clock" || f.startsWith("tab:") || f.startsWith("pill:")
    return if (inTop) topHidden else dockHidden && f.startsWith("tile:")
}

private fun isArrow(k: Key) = k == Key.DirectionLeft || k == Key.DirectionRight || k == Key.DirectionUp || k == Key.DirectionDown

/* ---------------- root ---------------- */

@Composable
fun Launcher(refresh: Int, quickRequest: Int, homeRequest: Int = 0, sceneRequest: String = "", builderRequest: Int = 0, peekRequest: String = "", buildRequest: String = "", journeyRequest: String = "") {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.compose.runtime.SideEffect { FocusMem.focus = focusManager; FocusMem.scope = scope }
    val view = LocalView.current
    remember { M5.load(ctx); Bg2.load(ctx); 0 }
    val glowCursor = remember { mutableStateOf(ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE).getBoolean("glowCursor", true)) }
    LaunchedEffect(glowCursor.value) { runCatching { view.pointerIcon = if (glowCursor.value) glowDotIcon(ctx) else null } }
    var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
    LaunchedEffect(refresh) { apps = withContext(Dispatchers.IO) { loadApps(ctx.packageManager) } }
    var dock by remember {
        // personal swap (starshine.dockSwap "Old>New" in local.properties): once, the tile named Old becomes your web tile New
        val swap = BuildConfig.DOCK_SWAP.split(">").map { it.trim() }
        val to = PRIVATE_TILES.firstOrNull { swap.size == 2 && webEntry(it).label == swap[1] }
        val d0 = prefs.dock
        val d = if (to == null || to in d0 || d0.none { it.startsWith("web:") && webEntry(it).label == swap[0] } || prefs.migrated("swap:" + BuildConfig.DOCK_SWAP)) d0
            else d0.map { if (it.startsWith("web:") && webEntry(it).label == swap[0]) to else it }.also { prefs.dock = it }
        // once: SmartTube's new package; Songsterr back as its web version (the app needs Android 12L, the pod has 12)
        val d2 = if (prefs.migrated("smarttubeSongsterr")) d else
            d.map { if (it == "com.teamsmart.videomanager.tv") SMARTTUBE else it }
                .let { l -> if (l.any { it.startsWith("web:") && webEntry(it).label == "Songsterr" }) l else l + WEB_PRESETS[0] }
                .distinct().also { prefs.dock = it }
        // the Songsterr app (your Premium is in it) replaces its web tile, once it's installed (the Android 7+ build)
        val songsterrApp = runCatching { ctx.packageManager.getLaunchIntentForPackage("com.songsterr") != null }.getOrDefault(false)
        val d3 = if (!songsterrApp || "com.songsterr" in d2) d2 else
            d2.map { if (it.startsWith("web:") && webEntry(it).label == "Songsterr") "com.songsterr" else it }
                .let { if ("com.songsterr" in it) it else it + "com.songsterr" }.also { prefs.dock = it }
        mutableStateOf(d3)
    }
    var bg by remember { mutableStateOf(prefs.bg) }
    var style by remember { mutableStateOf(prefs.style) }
    var dim by remember { mutableStateOf(prefs.dim) }
    var fps30 by remember { mutableStateOf(prefs.fps30) }
    var browser by remember { mutableStateOf(prefs.browser) }
    var dockScale by remember { mutableStateOf(prefs.dockScale) }
    var hideSecs by remember { mutableStateOf(prefs.hideSecs.let { if (prefs.migrated("v6")) it else 10f.also { v -> prefs.hideSecs = v } }) }
    var topHideSecs by remember { mutableStateOf(prefs.topHideSecs) }
    var topHidden by remember { mutableStateOf(false) }
    var uiScale by remember { mutableStateOf(prefs.uiScale) }
    var glasses by remember { mutableStateOf(prefs.glasses) }
    var offX by remember { mutableStateOf(prefs.offX) }
    var offY by remember { mutableStateOf(prefs.offY) }
    var ambientVol by remember { mutableStateOf(prefs.ambientVol) }
    var bgOn by remember { mutableStateOf(prefs.bgOn) }
    var dimOn by remember { mutableStateOf(prefs.dimOn) }
    var pickForNode by remember { mutableIntStateOf(-1) }
    var posZoom by remember { mutableStateOf(false) }
    var showResume by remember { mutableStateOf(prefs.showResume) }
    var jfToken by remember { mutableStateOf(prefs.jfToken) }
    var tab by remember { mutableStateOf(savedTab ?: Tab.Home) }
    var moving by remember { mutableStateOf<String?>(null) }
    // modal stack: (modal id, focus id to return to)
    var modals by remember { mutableStateOf(listOf<Pair<String, String?>>()) }
    val top = modals.lastOrNull()?.first
    LaunchedEffect(top) { glowCursor.value = ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE).getBoolean("glowCursor", true) }
    var positioning by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf("") }
    var sleepAt by remember { mutableLongStateOf(0L) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var dockHidden by remember { mutableStateOf(false) }
    var swallowUp by remember { mutableStateOf<Key?>(null) }
    var presetsVersion by remember { mutableIntStateOf(0) }
    val lastRepeat = remember { longArrayOf(0L) }
    var gestures by remember { mutableStateOf<Map<String, String>>(Gestures.load(ctx)) }
    var gesturing by remember { mutableStateOf(false) }
    var gestureArmed by remember { mutableStateOf(false) }
    var recordingGesture by remember { mutableStateOf(false) }
    val trail = remember { mutableStateListOf<Offset>() }
    val media = remember(refresh, tab) { mediaFiles(ctx) }

    fun openModal(id: String) { modals = modals + (id to FocusMem.last) }
    /** Home while building: the builder comes back when you return to its tab. */
    fun noteResume() { modals.forEach { if (it.first == "bg2edit") Drafts.setResume(ctx, true, true) else if (it.first == "moodedit") Drafts.setResume(ctx, false, true) } }
    fun closeModal() {
        val r = modals.lastOrNull()?.second; modals = modals.dropLast(1)
        // back into the menu underneath: it takes the selection you opened this from, not its first row
        FocusMem.returnTo = if (modals.isNotEmpty()) r else null
        scope.launch { delay(60); FocusMem.restoreSoon(r) }
    }
    fun saveDock(d: List<String>) { dock = d; prefs.dock = d }
    fun setStyle(s: BgStyle) { style = s; prefs.style = s }
    fun savePos() { prefs.offX = offX; prefs.offY = offY }
    /** Back to the original full-screen layout: position, zoom and glasses safe-zone together. */
    fun resetLayout() {
        offX = 0f; offY = 0f; uiScale = 1f; glasses = false; posZoom = false
        prefs.uiScale = 1f; prefs.glasses = false; savePos(); toast = "Layout reset: full screen"
    }
    fun playAmbient(m: String) { prefs.ambient = m; prefs.lastSound = "a:$m"; Sound.playAmbient(ctx, m, ambientVol) }
    fun playTone(t: Tone) { prefs.ambient = "Off"; prefs.lastSound = "t:" + t.name; Sound.playTone(ctx, t) }
    /** Applies a Bg scene from its clean saved state. Its sound starts only if the scene has one; otherwise sound is left as it is. */
    fun applyScene(s: BgScene) {
        Bg2.setCurrent(ctx, s); Bg2.preview = s; bg = BG2_ID; prefs.bg = BG2_ID; bgOn = true; prefs.bgOn = true
        when { s.sound.startsWith("t:") -> Tones.loadPresets(ctx).find { it.name == s.sound.removePrefix("t:") }?.let { playTone(it) }
            s.sound.isNotEmpty() -> playAmbient(s.sound) }
        toast = "Scene: ${s.name}"
    }
    fun stopSound() { prefs.ambient = "Off"; Sound.stop() }
    fun applyMood(m: Mood) {
        bg = m.bg; prefs.bg = m.bg; setStyle(m.style)   // AR dim is a global setting: moods leave it alone
        if (m.ambient == "Off") stopSound() else playAmbient(m.ambient); toast = "Mood: ${m.name}"
    }
    fun cleanRam() { toast = "Cleaning background apps…"; scope.launch { toast = withContext(Dispatchers.IO) { SystemInfo.boost(ctx, apps.map { it.id }) }.text } }

    // ---- voice
    val voice = remember { Voice(ctx, onPartial = { heard = it }, onResult = { text, err -> listening = false; Sound.release(); if (text == null) toast = "Voice: $err (Settings → Voice setup)" else heard = text }) }
    DisposableEffect(Unit) { onDispose { voice.stop() } }
    fun startVoice() {
        if (!voice.available()) { toast = "No speech recognizer found (Settings → Voice setup)"; return }
        if (!Voice.hasMic(ctx)) { (ctx as? Activity)?.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1); toast = "Allow the microphone, then try again"; return }
        heard = ""; listening = true; Sound.hold(); voice.listen()
    }
    fun runVoice(raw: String) {
        val t = raw.lowercase(Locale.getDefault()).trim()
        fun app(q: String) = Voice.bestMatch(q, apps + WEB_PRESETS.map { webEntry(it) }) { it.label }
        fun after(vararg w: String) = w.firstNotNullOfOrNull { k -> if (t.startsWith("$k ")) t.removePrefix("$k ").trim() else null }
        val num = Regex("\\d+").find(t)?.value?.toLongOrNull()
        val presets = Tones.loadPresets(ctx)
        toast = "🎤 $raw"
        when {
            after("open", "launch", "start") != null -> { val q = after("open", "launch", "start")!!; app(q)?.let { launch(ctx, it.id, browser) } ?: run { toast = "No app called \"$q\"" } }
            t == "home" || t == "go home" -> tab = Tab.Home
            t.startsWith("apps") || t == "all apps" -> tab = Tab.Apps
            t == "backgrounds" || t == "moods" -> tab = Tab.Bg
            t.startsWith("settings") -> openModal("quick")
            after("background", "show") != null -> { val q = after("background", "show")!!; PROCEDURAL.firstOrNull { it.lowercase().contains(q) }?.let { bg = it; prefs.bg = it } ?: run { toast = "No background \"$q\"" } }
            after("mood", "play") != null -> {
                val q = after("mood", "play")!!
                MOODS.firstOrNull { it.name.lowercase().contains(q) }?.let { applyMood(it) }
                    ?: Ambient.MODES.drop(1).firstOrNull { it.lowercase().contains(q) }?.let { playAmbient(it) }
                    ?: presets.firstOrNull { it.name.lowercase().contains(q) }?.let { playTone(it) }
                    ?: run { toast = "No mood \"$q\"" }
            }
            t.contains("stop") && (t.contains("sound") || t.contains("mood") || t.contains("tone") || t.contains("music")) -> stopSound()
            t.contains("volume up") || t == "louder" -> SystemInfo.volume(ctx, AudioManager.ADJUST_RAISE)
            t.contains("volume down") || t == "quieter" -> SystemInfo.volume(ctx, AudioManager.ADJUST_LOWER)
            t == "mute" || t == "unmute" -> SystemInfo.volume(ctx, AudioManager.ADJUST_TOGGLE_MUTE)
            t.contains("clean") || t.contains("free memory") || t.contains("ram") -> cleanRam()
            t.contains("screensaver") -> SystemInfo.startScreensaver(ctx)
            t.contains("sleep") && num != null -> { sleepAt = System.currentTimeMillis() + num * 60_000; toast = "Sleep in $num min" }
            t.contains("sleep") && t.contains("off") -> { sleepAt = 0; toast = "Sleep timer off" }
            t.contains("glasses") -> { glasses = !t.contains("off"); prefs.glasses = glasses }
            t.startsWith("move") || t.startsWith("shift") -> {
                when { t.contains("left") -> offX -= 0.04f; t.contains("right") -> offX += 0.04f; t.contains("up") -> offY -= 0.04f; t.contains("down") -> offY += 0.04f }
                savePos()
            }
            t.contains("center") || t.contains("reset position") -> { offX = 0f; offY = 0f; savePos() }
            t.contains("bigger") || t.contains("zoom in") -> { uiScale += 0.05f; prefs.uiScale = uiScale }
            t.contains("smaller") || t.contains("zoom out") -> { uiScale = (uiScale - 0.05f).coerceAtLeast(0.02f); prefs.uiScale = uiScale }
            t == "pause" || t == "play" || t == "resume" -> NowPlaying.current(ctx)?.let { NowPlaying.toggle(it) }
            t == "next" || t.contains("skip") -> NowPlaying.current(ctx)?.let { NowPlaying.next(it) }
            t.contains("open apps") || t.contains("switch app") || t.contains("recent") -> openModal("apps")
            t.contains("quick panel") || t == "menu" -> openModal("quick")
            after("search for", "search", "find") != null -> open(ctx, Intent("android.search.action.GLOBAL_SEARCH").putExtra(android.app.SearchManager.QUERY, after("search for", "search", "find")))
            else -> app(t)?.let { launch(ctx, it.id, browser) } ?: run { toast = "Unknown command: \"$raw\"" }
        }
    }
    LaunchedEffect(heard, listening) { if (!listening && heard.isNotEmpty()) { val h = heard; heard = ""; runVoice(h) } }

    // Home button: back to the Home tab with everything closed
    LaunchedEffect(homeRequest) { if (homeRequest > 0) { noteResume(); modals = emptyList(); M5.editing = null; moving = null; if (positioning) savePos(); positioning = false; posZoom = false; tab = Tab.Home } }
    LaunchedEffect(sceneRequest) { if (sceneRequest.isNotEmpty()) Bg2.pristine(sceneRequest)?.let { applyScene(it) } }
    LaunchedEffect(journeyRequest) {
        if (journeyRequest.isEmpty()) return@LaunchedEffect
        val parts = journeyRequest.split("|"); val k = parts.getOrNull(1)?.toFloatOrNull() ?: 1f
        (if (parts[0] == "first") Med.journeys.firstOrNull { it.steps.size > 1 } else Med.journeys.firstOrNull { it.name == parts[0] })?.let { j ->
            modals = emptyList(); tab = Tab.Home; delay(500)
            AudioProbe.start(ctx, j.name); Med.paused = false; Med.playing = j.copy(steps = j.steps.map { it.copy(minutes = it.minutes * k) }, loop = false)
        }
    }
    LaunchedEffect(bg) { Bg2.applied = bg == BG2_ID }
    val journey = Med.playing
    LaunchedEffect(journey) {
        if (journey != null) Med.run(ctx, journey) { s -> Bg2.setCurrent(ctx, s); bg = BG2_ID; prefs.bg = BG2_ID; bgOn = true; prefs.bgOn = true; Bg2.applied = true }
        else if (AudioProbe.active) toast = "Audio test: " + withContext(Dispatchers.IO) { AudioProbe.finish() }
    }
    // full-screen preview of a scene without applying it, any button returns
    LaunchedEffect(peekRequest) {
        // test hook: "--es bgPeek Scene_name~Type:key=v,key=v" previews a scene with those settings on its layers of that type
        // (_ stands for a space and ~ separates: both pass through Windows cmd and the device shell unquoted)
        val name = peekRequest.substringBefore('~').let { n -> if (Bg2.pristine(n) == null) n.replace('_', ' ') else n }
        val set = peekRequest.substringAfter('~', "").replace('_', ' ')
        if (name.isNotEmpty()) { modals = emptyList(); tab = Tab.Bg; delay(250); Bg2.pristine(name)?.let { s0 ->
            val type = set.substringBefore(':', ""); val kv = set.substringAfter(':', "").split(',').mapNotNull { p -> p.split('=').takeIf { it.size == 2 }?.let { it[0] to (it[1].toFloatOrNull() ?: return@mapNotNull null) } }
            val s = if (type.isEmpty()) s0 else s0.copy(layers = s0.layers.map { l -> if (l.type == type) kv.fold(l) { a, (k, v) -> a.set(k, v) } else l })
            Bg2.preview = s; lastBrowsedScene = s.name; Bg2.fullPreview = true } }
    }
    LaunchedEffect(buildRequest) {
        if (buildRequest.isNotEmpty()) { modals = emptyList(); Bg2.fullPreview = false; tab = Tab.Bg; delay(250); Bg2.pristine(buildRequest)?.let { Bg2.preview = it; lastBrowsedScene = it.name; openModal("bg2edit") } }
    }
    LaunchedEffect(builderRequest) {
        if (builderRequest > 0) {
            modals = emptyList(); tab = Tab.Bg; delay(150)
            val b = Bg2.blank(); Bg2.preview = b; lastBrowsedScene = b.name; openModal("bg2edit")
        }
    }
    LaunchedEffect(tab) { savedTab = tab; if (tab == Tab.Bg || tab == Tab.Med) { Bg2.preview = Bg2.current; lastBrowsedScene = Bg2.current.name } else { Bg2.fullPreview = false; Bg2.classicView = null }; if (tab != Tab.Drone) Drone.leave(ctx)
        // an editor left open last time is NOT reopened by itself (that dropped you deep into the menus with no clean way
        // back); the list offers "↩ Resume unsaved" instead
        if (tab == Tab.Bg) {
            if (Drafts.resumeScene) Drafts.setResume(ctx, true, false)
            if (Drafts.resumeMood) Drafts.setResume(ctx, false, false)
        }
    }
    androidx.compose.runtime.SideEffect { FocusMem.tabName = tab.name; FocusMem.modalOpen = top != null }
    // opened from outside (long-press Home, the quick extra): on top of nothing, so it can't open behind another panel
    LaunchedEffect(quickRequest) { if (quickRequest > 0 && top != "quick") modals = listOf("quick" to FocusMem.last) }
    LaunchedEffect(toast) { if (toast.isNotEmpty()) { delay(2600); toast = "" } }
    LaunchedEffect(sleepAt) {
        if (sleepAt > 0) { delay((sleepAt - System.currentTimeMillis()).coerceAtLeast(0)); NowPlaying.pauseAll(ctx); stopSound(); SystemInfo.startScreensaver(ctx); sleepAt = 0 }
    }
    LaunchedEffect(tab, top, moving, hideSecs) {
        snapshotFlow { lastInput }.collectLatest {
            dockHidden = false
            if (hideSecs > 0 && tab == Tab.Home && top == null && moving == null) { delay((hideSecs * 1000).toLong()); dockHidden = true }
        }
    }
    LaunchedEffect(top, topHideSecs) {
        snapshotFlow { lastInput }.collectLatest {
            topHidden = false
            if (topHideSecs > 0 && top == null) { delay((topHideSecs * 1000).toLong()); topHidden = true }
        }
    }
    // kept as State and read only inside graphicsLayer: hiding the menus moves a GPU layer, no recomposition or layout
    // 10 higher than before, and faded out as it goes: in glasses mode the screen is scaled down and the date peeked out
    val topOffset = animateDpAsState(if (topHidden) (-160).dp else 0.dp, tween(if (topHidden) 900 else 350), label = "top")
    val topAlpha = animateFloatAsState(if (topHidden) 0f else 1f, tween(if (topHidden) 900 else 250), label = "topA")
    val dockOffset = animateDpAsState(if (dockHidden) 260.dp else 0.dp, tween(if (dockHidden) 900 else 350), label = "dock")

    BackHandler {
        when {
            M5.editing != null -> M5.editing = null
            positioning -> { positioning = false; savePos() }
            listening -> { listening = false; voice.stop(); Sound.release() }
            moving != null -> moving = null
            top != null -> closeModal()
            // Back climbs one level at a time: from inside a tab to its icon in the top menu, then from there to Home
            FocusMem.current != "tab:${tab.name}" -> FocusMem.restore("tab:${tab.name}")
            tab != Tab.Home -> { tab = Tab.Home; scope.launch { FocusMem.restoreSoon("tab:Home") } }
        }
    }

    fun key(code: Int) {
        val t = SystemClock.uptimeMillis()
        view.dispatchKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, code, 0))
        view.dispatchKeyEvent(android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, code, 0))
    }
    fun m5Action(a: String) = when (a) {
        "Back" -> key(android.view.KeyEvent.KEYCODE_BACK)
        "Quick panel" -> if (top == "quick") closeModal() else openModal("quick")
        "Voice" -> startVoice()
        "Open apps" -> if (top == "apps") closeModal() else openModal("apps")
        else -> {}
    }

    fun allBgs() = PROCEDURAL + SHADERS + mediaFiles(ctx).map { "file:" + it.name }
    fun runAction(code: String) {
        when {
            code.startsWith("app:") -> launch(ctx, code.removePrefix("app:"), browser)
            // on a Bg scene, next / previous go through the scenes: the old list of classic backgrounds would
            // silently swap the scene for a flat classic one
            (code == "bg:+1" || code == "bg:-1") && bg == BG2_ID -> {
                val sc = Bg2.mine + BUILT_IN_SCENES
                if (sc.isNotEmpty()) {
                    val i = sc.indexOfFirst { it.name == Bg2.current.name }.coerceAtLeast(0)
                    applyScene(sc[((i + if (code == "bg:+1") 1 else -1) % sc.size + sc.size) % sc.size])
                }
            }
            code == "bg:+1" || code == "bg:-1" -> {
                val l = allBgs(); val i = l.indexOf(bg).coerceAtLeast(0)
                val n = l[((i + if (code == "bg:+1") 1 else -1) % l.size + l.size) % l.size]
                bg = n; prefs.bg = n; toast = "Background: " + n.removePrefix("file:")
            }
            code.startsWith("bg:") -> { bg = code.removePrefix("bg:"); prefs.bg = bg }
            code.startsWith("mood:") -> {
                val q = code.removePrefix("mood:")
                val m = MOODS.find { it.name == q }
                if (m != null) applyMood(m) else if (q in Ambient.MODES) playAmbient(q) else Tones.loadPresets(ctx).find { it.name == q }?.let { playTone(it) }
            }
            code == "sound:stop" -> stopSound()
            code == "ui:quick" -> openModal("quick")
            code == "ui:home" -> { noteResume(); modals = emptyList(); tab = Tab.Home }
            code == "ui:voice" -> startVoice()
            code == "ui:apps" -> openModal("apps")
            code == "ui:saver" -> SystemInfo.startScreensaver(ctx)
        }
    }
    fun finishGesture(fromRightButton: Boolean) {
        val pts = trail.toList(); trail.clear()
        var len = 0f; for (i in 1 until pts.size) len += (pts[i] - pts[i - 1]).getDistance()
        if (fromRightButton && len < 40f) { m5Action(M5.rightClick); return }      // plain right click
        val pat = Gestures.recognize(pts)
        if (pat.isEmpty()) { toast = "Gesture too short, draw a bigger shape"; return }
        if (recordingGesture) {
            recordingGesture = false
            gestures = LinkedHashMap(gestures).apply { putIfAbsent(pat, "ui:home") }.also { Gestures.save(ctx, it) }
            openModal("gesture:$pat"); return
        }
        val act = Gestures.match(pat, gestures.keys)?.let { gestures[it] }
        if (act == null) toast = Gestures.arrows(pat) + "  has no action yet (Settings → Gestures)"
        else { toast = Gestures.arrows(pat) + "   " + Gestures.describe(act) { id -> apps.find { it.id == id }?.label }; runAction(act) }
    }

    val inset = if (glasses) 0.06f else 0f
    val scale = if (glasses) uiScale * 0.85f else uiScale

    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = Theme.family)) {
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
        awaitPointerEventScope {
            var lastT = 0L; var downAt = 0L; var hold: Job? = null
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                lastInput = System.currentTimeMillis(); dockHidden = false; topHidden = false
                val ch = ev.changes.firstOrNull() ?: continue
                val now = SystemClock.uptimeMillis()
                if (ev.type == PointerEventType.Move || ev.type == PointerEventType.Enter) {
                    M5.speed = (ch.position - M5.pointer).getDistance() * 1000f / (now - lastT).coerceAtLeast(1L); M5.pointer = ch.position; lastT = now
                }
                if (ev.type == PointerEventType.Press) android.util.Log.i("StarshineInput", "pointer press buttons=${ev.buttons} type=${ch.type}")
                if ((gesturing || gestureArmed) && ev.type == PointerEventType.Move) trail.add(ch.position)
                if (ev.type == PointerEventType.Press && ev.buttons.isSecondaryPressed) {
                    ev.changes.forEach { it.consume() }; gesturing = true; trail.clear(); trail.add(ch.position)
                } else if (gesturing && ev.type == PointerEventType.Release) {
                    ev.changes.forEach { it.consume() }; gesturing = false; M5.logKey("Right button gesture"); finishGesture(true)
                } else if (gestureArmed && ev.type == PointerEventType.Press) {
                    ev.changes.forEach { it.consume() }; gestureArmed = false; downAt = -1L; finishGesture(false)
                } else if (downAt == -1L && ev.type == PointerEventType.Release) {
                    ev.changes.forEach { it.consume() }; downAt = 0L
                } else if (ev.type == PointerEventType.Press && M5.clickAsOk) {   // any non-right click (some air mice report no button flag)
                    ev.changes.forEach { it.consume() }; downAt = now
                    // the cursor wins if it moved onto an item after the last arrow press; otherwise the D-pad selection wins
                    val target = M5.hoverTarget.takeIf { M5.editing == null && M5.hoverAt > M5.lastArrowAt }
                    // a click on empty space presses the selected item only while you're steering with the arrows; after
                    // the cursor was used it does nothing (it used to press whatever the cursor last selected, e.g. the clock
                    // in a hidden top bar, which opened Quick settings out of nowhere)
                    if (target == null && M5.hoverAt > M5.lastArrowAt && M5.editing == null) {
                        android.util.Log.i("StarshineInput", "click on empty space: nothing pressed"); downAt = -1L; continue
                    }
                    view.requestFocusFromTouch()   // the click put the window in touch mode, where most items can't hold focus
                    if (target != null) runCatching { target.requestFocus() } else FocusMem.restore(FocusMem.last)
                    android.util.Log.i("StarshineInput", "click -> " + (if (target != null) "item under cursor" else "selected item") + " focus=" + FocusMem.current + " viewFocused=" + view.isFocused)
                    M5.logKey(if (target != null) "Mouse click → item under cursor" else "Mouse click → OK on selected item")
                    view.dispatchKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER, 0))
                    hold = scope.launch {
                        delay(550); val t = SystemClock.uptimeMillis()
                        view.dispatchKeyEvent(android.view.KeyEvent(downAt, t, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER, 1))
                    }
                } else if (ev.type == PointerEventType.Release && downAt != 0L) {
                    ev.changes.forEach { it.consume() }; hold?.cancel()
                    view.dispatchKeyEvent(android.view.KeyEvent(downAt, SystemClock.uptimeMillis(), android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_CENTER, 0)); downAt = 0L
                }
            }
        }
    }.onPreviewKeyEvent { e ->
        lastInput = System.currentTimeMillis()
        if (Bg2.fullPreview || Drone.immersive) { if (e.type == KeyEventType.KeyDown) { Bg2.fullPreview = false; Drone.immersive = false; swallowUp = e.key }; return@onPreviewKeyEvent true }
        if (e.type == KeyEventType.KeyDown && isArrow(e.key)) M5.lastArrowAt = SystemClock.uptimeMillis()
        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0)
            android.util.Log.i("StarshineInput", "key " + android.view.KeyEvent.keyCodeToString(e.nativeKeyEvent.keyCode) + " from " + (e.nativeKeyEvent.device?.name ?: "injected"))
        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0)
            M5.logKey(android.view.KeyEvent.keyCodeToString(e.nativeKeyEvent.keyCode).removePrefix("KEYCODE_") + "  ·  " + (e.nativeKeyEvent.device?.name ?: "injected"))
        when {
            positioning && e.type == KeyEventType.KeyDown -> {
                val fast = e.nativeKeyEvent.repeatCount > 3
                val st = if (fast) 0.02f else 0.005f
                val zf = if (fast) 1.04f else 1.01f
                when {
                    e.isOk() && e.nativeKeyEvent.repeatCount == 0 -> posZoom = !posZoom
                    posZoom && (e.key == Key.DirectionUp || e.key == Key.DirectionRight) -> { uiScale *= zf; prefs.uiScale = uiScale }
                    posZoom && (e.key == Key.DirectionDown || e.key == Key.DirectionLeft) -> { uiScale = (uiScale / zf).coerceAtLeast(0.02f); prefs.uiScale = uiScale }
                    e.key == Key.DirectionLeft -> offX -= st
                    e.key == Key.DirectionRight -> offX += st
                    e.key == Key.DirectionUp -> offY -= st
                    e.key == Key.DirectionDown -> offY += st
                    e.key == Key.Menu -> resetLayout()
                    e.isOk() -> {}
                    else -> { positioning = false; posZoom = false; savePos() }
                }
                true
            }
            positioning -> true
            // gamepad-style OK (some air mice): Compose clickable only knows centre/Enter, so hand it a real centre press
            e.key == Key.ButtonSelect || e.key == Key.ButtonA -> {
                val n = e.nativeKeyEvent
                view.dispatchKeyEvent(android.view.KeyEvent(n.downTime, n.eventTime, n.action, android.view.KeyEvent.KEYCODE_DPAD_CENTER, n.repeatCount))
                true
            }
            // held arrows keep moving: short grace period (a normal press stays one step), then a steady rate
            e.type == KeyEventType.KeyDown && isArrow(e.key) && e.nativeKeyEvent.repeatCount > 0 &&
                !(M5.editing != null && (e.key == Key.DirectionLeft || e.key == Key.DirectionRight)) -> {
                val n = e.nativeKeyEvent
                // a long press stays one step; repeats that arrive late (busy UI, Bluetooth lag) are dropped so the selection stops when you let go
                val stale = SystemClock.uptimeMillis() - n.eventTime > 120L
                if (stale || n.eventTime - n.downTime < 500L || n.eventTime - lastRepeat[0] < 170L) true else { lastRepeat[0] = n.eventTime; false }
            }
            e.type == KeyEventType.KeyDown && (e.key == Key.Search || e.key == Key.VoiceAssist) -> { startVoice(); true }
            swallowUp != null && e.type == KeyEventType.KeyUp && e.key == swallowUp -> { swallowUp = null; true }
            e.type == KeyEventType.KeyDown && e.isOk() && e.nativeKeyEvent.repeatCount == 0 && focusIsHidden(dockHidden && tab == Tab.Home, topHidden) -> { dockHidden = false; topHidden = false; swallowUp = e.key; true }
            (dockHidden || topHidden) && e.type == KeyEventType.KeyDown -> { dockHidden = false; topHidden = false; false }
            e.type == KeyEventType.KeyDown && e.key == Key.Menu && M5.editing == null -> { m5Action(M5.menuKey); true }
            else -> false
        }
    }) {
        LaunchedEffect(bg) {
            Nebula.alpha = prefs.nebulaA
            Nebula.image = if (bg == "Starfield") withContext(Dispatchers.Default) { Nebula.load(ctx, prefs.nebula) } else null
        }
        val reactive = remember(bg, top) { prefs.reactive(bg) }
        val classicView = Bg2.classicView
        if (tab == Tab.Drone) DroneStage()
        else if ((tab == Tab.Bg && classicView == null) || tab == Tab.Med) Bg2Stage(fps30)
        else if (tab == Tab.Bg) Background(classicView!!, style, fps30, remember(classicView, top) { prefs.reactive(classicView!!) })
        else if (bgOn) Background(bg, style, fps30, reactive)
        if (dimOn && tab != Tab.Drone) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim.coerceIn(0f, 1f))))

        // main screen is only composed when no modal is open, so nothing behind a modal can take focus
        BoxWithConstraints(Modifier.fillMaxSize()) { Box(
            Modifier.fillMaxSize()
                .offset(x = maxWidth * offX, y = maxHeight * offY)
                .padding(horizontal = maxWidth * inset, vertical = maxHeight * inset)
                .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (Bg2.fullPreview || Drone.immersive) 0f else 1f }
        ) {
        if (top == null) {
            Column(Modifier.fillMaxSize()) {
                val edge = if (glasses) 0.dp else Look.edgePad.dp
                Box(Modifier.zIndex(1f).graphicsLayer { translationY = topOffset.value.toPx(); alpha = topAlpha.value }.padding(start = edge, end = edge, top = edge)) { TopBar(tab, onTab = { if (moving == null) tab = it }, onClock = { openModal("quick") },
                    onRam = { cleanRam() }, onVoice = { startVoice() }, onApps = { openModal("apps") }) }
                Box(Modifier.weight(1f).fillMaxWidth()
                    // Home: Up goes to the nearest top-menu item (a diagonal step at most); other tabs: up to their own tab
                    .focusProperties { exit = { d -> if (d == FocusDirection.Up) (if (FocusMem.noExit) FocusRequester.Cancel else if (tab == Tab.Home) FocusRequester.Default else FocusMem.map["tab:${tab.name}"] ?: FocusRequester.Default) else FocusRequester.Default } }
                    .focusGroup()) {
                    // the player card, docked on the left edge of Home (Settings → Sound)
                    if (tab == Tab.Home && MusicPlayer.edge) PlayerEdgeCard(Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
                    when (tab) {
                        Tab.Home -> Column(Modifier.align(Alignment.BottomCenter).graphicsLayer { translationY = dockOffset.value.toPx() }.padding(bottom = 22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            if (!MusicPlayer.edge) NowPlayingCard()   // the left-edge card replaces it
                            if (showResume && jfToken.isNotEmpty()) ContinueWatching(prefs.jellyfin, jfToken, prefs.jfUser, refresh) { launch(ctx, JELLYFIN_PKG) }
                            Dock(
                                items = if (apps.isEmpty()) emptyList() else dock.mapNotNull { resolve(it, apps) }, moving = moving, tileWidth = (128 * dockScale).toInt().coerceAtLeast(24), iconsOnly = Look.iconsOnly, round = Look.round,
                                onLaunch = {
                                    if (it.id == BLISS_ID) {
                                        val easy = apps.find { a -> a.id == EASYCONTROL } ?: apps.find { a -> a.label.contains("easycontrol", true) }
                                        if (easy != null) launch(ctx, easy.id) else openModal("bliss")
                                    } else launch(ctx, it.id, browser)
                                },
                                onStartMove = { moving = it },
                                onMove = { id, dir ->
                                    val i = dock.indexOf(id); val j = i + dir
                                    if (i >= 0 && j in dock.indices) saveDock(dock.toMutableList().apply { add(j, removeAt(i)) })
                                },
                                onRemove = { id -> saveDock(dock - id); moving = null },
                                onEndMove = { moving = null },
                                onAdd = { openModal("add") })
                        }
                        Tab.Apps -> AppsGrid(apps, dock, onLaunch = { launch(ctx, it.id, browser) },
                            onToggle = { a -> saveDock(if (a.id in dock) dock - a.id else dock + a.id) })
                        Tab.Bg -> Column(Modifier.fillMaxSize()) { GeneratorTabs(); Box(Modifier.weight(1f)) { if (Med.subTab == 1) MoodTab(onBuild = { openModal("moodedit") }, onStop = { stopSound() }, onIconFile = { openModal("iconfile:$it") }) else if (Med.subTab == 2) RenderTab() else key(Med.subTab) { BgTab(presetsVersion = presetsVersion, onApply = { applyScene(it) },
                            classics = PROCEDURAL + SHADERS + media.map { "file:" + it.name },
                            onBuild = { Bg2.classicView = null; openModal("bg2edit") },
                            onNew = { space -> Bg2.classicView = null; val b = Bg2.blank(space); Bg2.preview = b; lastBrowsedScene = b.name; openModal("bg2edit") },
                            onFiles = { openModal("files") },
                            onExport = { toast = "Rendering 15 s of ${Bg2.preview.name}…"; Exporter.exportScene(ctx, Bg2.preview) { f -> toast = if (f != null) "Saved to " + Exporter.publicName.ifEmpty { f.name } else "Export failed" } },
                            onStop = { stopSound() }) } } }
                        Tab.Med -> MeditationTab(onPlay = { Med.paused = false; Med.playing = it; tab = Tab.Home }, onStop = { Med.playing = null; stopSound() }, onAudio = { Med.subTab = 1; tab = Tab.Bg })
                        Tab.Drone -> DroneTab()
                        Tab.Tv -> SamsungTab()
                    }
                }
            }
        }

        // ---------------- modals ----------------
        when (top) {
            null -> {}
            "bg" -> ModalFrame("Background · ${bg.removePrefix("file:")}", "OK on a row = edit · ← → change · Back = done") { first ->
                BackgroundSettings(first, bg, style, folder = ctx.getExternalFilesDir("backgrounds")?.absolutePath ?: "",
                    onStyle = { setStyle(it) },
                    onSaver = { prefs.saverBg = bg; toast = "Screensaver uses: $bg" },
                    onExport = {
                        if (bg in PROCEDURAL) { toast = "Rendering 15 s video of $bg…"; Exporter.export(ctx, bg, style) { f -> toast = if (f != null) "Saved to " + Exporter.publicName.ifEmpty { f.name } else "Export failed" } }
                        else toast = "Video export works for the 10 classic animated backgrounds (not GPU shaders or files)"
                    })
            }
            "tone" -> ModalFrame("Frequency generator", "OK on a row = edit · ← → change · Back = done", width = 520) { first ->
                ToneEditor(first, onPlay = { playTone(it) }, onStop = { stopSound() }, onSaved = { presetsVersion++ })
            }
            "settings" -> ModalFrame("Settings", "OK = open · Back = close", width = 640) { first ->
                SettingsHome(first) { c -> if (c == "Files") openModal("files") else openModal("set:$c") }
            }
            "quick" -> ModalFrame("Quick settings", "Back = close", width = Q_WIDTH, height = Q_WIDTH * 2, alignment = Alignment.TopEnd) { first ->
                QuickPanel(first, sleepAt = sleepAt, glasses = glasses,
                    bgOn = bgOn, onBgOn = { bgOn = it; prefs.bgOn = it }, dimOn = dimOn, onDimOn = { dimOn = it; prefs.dimOn = it }, dim = dim, onDim = { dim = it; prefs.dim = it },
                    onSoundToggle = {
                        if (Sound.current.isNotEmpty()) {
                            // remember what was on (a mood too), and tell a running journey the silence is wanted
                            val cur = Sound.current
                            val keep = if (Med.stacks.any { it.name == cur }) "m:$cur" else prefs.lastSound
                            stopSound(); prefs.lastSound = keep; Sound.userOff = true
                        } else { val l = prefs.lastSound
                            Sound.userOff = false
                            when {
                                Med.playing != null -> {}   // the journey brings its own sound back within a moment
                                l.startsWith("t:") -> Tones.loadPresets(ctx).find { it.name == l.removePrefix("t:") }?.let { playTone(it) }
                                l.startsWith("a:") -> playAmbient(l.removePrefix("a:"))
                                l.startsWith("m:") -> Med.stacks.firstOrNull { it.name == l.removePrefix("m:") }?.let { Sound.playStack(ctx, it, 1f) }
                                else -> toast = "Choose a sound below first"
                            } }
                    },
                    onSleep = { sleepAt = it; toast = if (it == 0L) "Sleep timer off" else "Sleep in ${(it - System.currentTimeMillis()) / 60000 + 1} min" },
                    onGlasses = { glasses = it; prefs.glasses = it },
                    onRam = { cleanRam() }, onStop = { stopSound() }, onScene = { applyScene(it) },
                    onSetting = { c -> if (c == ALL_SETTINGS) openModal("settings") else if (c == "Files") openModal("files") else openModal("set:$c") },
                    onGesture = { closeModal(); gestureArmed = true; trail.clear(); android.util.Log.i("StarshineInput", "gesture armed from quick panel") },
                    onAction = { code -> if (code == "ui:tone") openModal("tone") else { if (code != "bg:+1") closeModal(); runAction(code) } },
                    allSettings = ALL_SETTINGS)
            }
            "moodedit" -> MoodEditor(onClose = { closeModal() }, onToast = { toast = it }, onIconFile = { openModal("iconfile:$it") })
            "bg2edit" -> BgEditor(onClose = { closeModal() }, onApply = { s -> Bg2.setCurrent(ctx, s); bg = BG2_ID; prefs.bg = BG2_ID; bgOn = true; prefs.bgOn = true },
                onToast = { toast = it }, onPickMedia = { pickForNode = it; openModal("pick") })
            "files" -> ModalFrame("Files", "OK = open · Back = up one level", width = 720) { first ->
                FileExplorer(first,
                    onUseAsBackground = { f -> val n = "file:" + f.absolutePath; bg = n; prefs.bg = n; bgOn = true; prefs.bgOn = true; toast = "Background: " + f.name },
                    onAddToBgLayers = { f ->
                        val sc = if (tab == Tab.Bg) Bg2.preview else Bg2.current
                        val next = sc.copy(layers = sc.layers + Layer(sc.nextId, "Media file", media = f.absolutePath))
                        when {
                            sc.layers.size >= Bg2.maxLayers(sc.space) -> toast = "A scene can hold up to ${Bg2.maxLayers(sc.space)} layers"
                            tab == Tab.Bg -> { Bg2.preview = next; toast = "Added ${f.name} to the preview. Apply it to see it as background" }
                            else -> { Bg2.setCurrent(ctx, next); Bg2.preview = next; bg = BG2_ID; prefs.bg = BG2_ID; toast = "Added ${f.name} to ${sc.name}" }
                        }
                    },
                    onToast = { toast = it })
            }
            "pick" -> ModalFrame("Choose a file for this node", "OK = choose · Back = up one level", width = 720) { first ->
                FileExplorer(first, pick = { f ->
                    val sc = Bg2.preview
                    Bg2.preview = sc.copy(layers = sc.layers.map { if (it.id == pickForNode) it.copy(media = f.absolutePath) else it }); closeModal()
                }, onUseAsBackground = {}, onAddToBgLayers = {}, onToast = { toast = it })
            }
            "bliss" -> ModalFrame("Bliss OS", "Back = close", width = 600) { first ->
                LoopColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { Box(Modifier.focusRequester(first)) { Row_("Bliss OS runs at ${BuildConfig.BLISS}. It opens here through the Easycontrol app.", false, id = "bliss:1", onFocus = {}) {} } }
                    item { Text("1. On the PC, open the Rokid TV Toolkit → Bliss OS → \"Install Easycontrol on the Rokid pod\".", color = FG, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
                    item { Text("2. Open this tile again → Easycontrol → add device: ${BuildConfig.BLISS}.", color = FG, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
                    item { Text("3. The first time, accept the \"Allow USB debugging\" prompt on the Bliss OS screen (use the toolkit's Bliss OS → Mirror to see it on the PC).", color = FG, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
                }
            }
            "help" -> HelpViewer { closeModal() }
            "apps" -> ModalFrame("Open apps", "OK = switch · hold OK = close app · Back = close", width = 380, alignTop = true) { first ->
                OpenAppsList(first, apps, browser, prefs.jellyfin, onDone = { closeModal() }, onToast = { toast = it })
            }
            "add" -> AddPicker(
                candidates = apps.filter { it.id !in dock } + WEB_PRESETS.filter { it !in dock }.map { webEntry(it) } +
                    (if (SETTINGS_ID in dock) emptyList() else listOf(AppEntry(SETTINGS_ID, "Android Settings"))),
                onPick = { saveDock(dock + it.id); closeModal() })
            "m5" -> M5Panel(onClose = { closeModal() })
            "qc" -> QuickConnectDialog(prefs.jellyfin,
                onDone = { token, user -> jfToken = token; prefs.jfToken = token; prefs.jfUser = user; closeModal(); toast = "Jellyfin connected" },
                onClose = { closeModal() })
            "set:Gestures" -> ModalFrame("Gestures", "OK = edit · Back = done", width = 620) { first ->
                GestureList(first, gestures, describe = { Gestures.describe(it) { id -> apps.find { a -> a.id == id }?.label } },
                    onEdit = { openModal("gesture:$it") },
                    onRecord = { modals = emptyList(); recordingGesture = true; gestureArmed = true; toast = "Draw the new gesture: hold the right button and move, or move then click" },
                    onReset = { gestures = LinkedHashMap(Gestures.DEFAULTS).also { Gestures.save(ctx, it) }; toast = "All gestures reset to the 6 examples" },
                    onRemove = { p -> gestures = LinkedHashMap(gestures).apply { remove(p) }.also { Gestures.save(ctx, it) }; toast = "Removed " + Gestures.arrows(p) },
                    onSave = { Gestures.save(ctx, gestures); toast = "Gestures saved (" + gestures.size + ")" })
            }
            else -> if (top.startsWith("iconfile:")) {
                val mood = top.removePrefix("iconfile:")
                ModalFrame("Picture for $mood", "OK = choose · Back = up one level", width = 720) { first ->
                    FileExplorer(first, pick = { f -> MoodIcons.set(ctx, mood, "file:" + f.absolutePath); toast = "Icon set for $mood"; closeModal() },
                        onUseAsBackground = {}, onAddToBgLayers = {}, onToast = { toast = it })
                }
            } else if (top.startsWith("gesture:")) {
                val pat = top.removePrefix("gesture:")
                ModalFrame("Gesture " + Gestures.arrows(pat), "OK on Action = edit · ← → choose · Back = done", width = 620) { first ->
                    val presets = remember { Tones.loadPresets(ctx) }
                    val choices = remember(apps, presets) {
                        listOf("ui:home", "ui:quick", "ui:apps", "ui:voice", "ui:saver", "bg:+1", "bg:-1", "sound:stop") +
                            (MOODS.map { it.name } + Ambient.MODES.drop(1) + presets.map { it.name }).map { "mood:$it" } +
                            allBgs().map { "bg:$it" } + apps.map { "app:" + it.id }
                    }
                    GestureEditor(first, gestures[pat] ?: "ui:home", choices,
                        describe = { Gestures.describe(it) { id -> apps.find { a -> a.id == id }?.label } },
                        onSet = { code -> gestures = LinkedHashMap(gestures).apply { put(pat, code) }.also { Gestures.save(ctx, it) } },
                        onDelete = { gestures = LinkedHashMap(gestures).apply { remove(pat) }.also { Gestures.save(ctx, it) }; closeModal() })
                }
            } else if (top.startsWith("set:")) {
                val cat = top.removePrefix("set:")
                ModalFrame(cat, "OK on a row = edit / open · ← → change · Back = done", width = 560) { first ->
                    SettingsCategory(cat, first,
                        dockScale = dockScale, hideSecs = hideSecs, uiScale = uiScale, glasses = glasses, offX = offX, offY = offY, fps30 = fps30,
                        ambientVol = ambientVol, browser = browser, showResume = showResume, signedIn = jfToken.isNotEmpty(),
                        dim = dim, onDim = { dim = it; prefs.dim = it },
                        onDock = { dockScale = it; prefs.dockScale = it }, onHide = { hideSecs = it; prefs.hideSecs = it },
                        topHideSecs = topHideSecs, onTopHide = { topHideSecs = it; prefs.topHideSecs = it },
                        onUi = { uiScale = it; prefs.uiScale = it }, onGlasses = { glasses = it; prefs.glasses = it },
                        onOffset = { x, y -> offX = x; offY = y; savePos() }, onResetLayout = { resetLayout() }, onPosition = { modals = emptyList(); positioning = true },
                        onFps = { fps30 = it; prefs.fps30 = it },
                        onAmbientVol = { ambientVol = it; prefs.ambientVol = it; Sound.setAmbientVolume(it) },
                        onStopSound = { stopSound() }, onGenerator = { openModal("tone") },
                        onBrowser = { browser = it; prefs.browser = it }, onResume = { showResume = it; prefs.showResume = it },
                        onJellyfin = { if (jfToken.isEmpty()) openModal("qc") else { jfToken = ""; prefs.jfToken = ""; prefs.jfUser = ""; toast = "Signed out of Jellyfin" } },
                        onCalibrate = { openModal("m5") }, onHelp = { openModal("help") },
                        onResetDock = { saveDock(DEFAULT_DOCK); toast = "Dock reset" },
                        onExit = {
                            ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE).edit().putBoolean("homeOverride", false).apply()
                            open(ctx, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(GOOGLE_HOME))
                            (ctx as? Activity)?.finishAndRemoveTask()
                        },
                        open = { open(ctx, it) })
                }
            }
        }

        } }  // transform box + BoxWithConstraints

        if (!Drone.immersive) Watermark()

        // brightness shade over everything Starshine shows, when KeyService isn't there to lay it over every app (see Brightness)
        if (!Brightness.systemWide && Brightness.level < 1f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = Brightness.alpha())))
        if (trail.size > 1) Canvas(Modifier.fillMaxSize()) {
            val p = Path().apply { moveTo(trail[0].x, trail[0].y); for (i in 1 until trail.size) lineTo(trail[i].x, trail[i].y) }
            drawPath(p, VIOLET.copy(alpha = 0.25f), style = Stroke(22f, cap = StrokeCap.Round))
            drawPath(p, Color.White.copy(alpha = 0.9f), style = Stroke(5f, cap = StrokeCap.Round))
        }
        if (gestureArmed && trail.size <= 1) Text("✍ Move the M5 to draw your gesture, then click", color = FG, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(50)).border(1.dp, VIOLET, RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp))
        if (positioning) Box(Modifier.fillMaxSize().border(2.dp, VIOLET.copy(alpha = 0.6f))) {
            Text((if (posZoom) "🔍 ZOOM · ↑ → bigger · ↓ ← smaller" else "✥ MOVE · arrows move") + " (hold = faster) · OK = switch move/zoom · Menu = reset · Back = done   (x ${pct(offX)}, y ${pct(offY)}, zoom ${pct(uiScale)})",
                color = FG, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(12.dp)).border(1.dp, VIOLET, RoundedCornerShape(12.dp)).padding(14.dp))
        }
        if (listening) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🎤", fontSize = 48.sp)
                Text(if (heard.isEmpty()) "Listening… say e.g. \"open Jellyfin\", \"mood calm\", \"play rain\"" else heard, color = FG, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp))
                Text("Back = cancel", color = DIM, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (toast.isNotEmpty()) Text(toast, color = FG, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp)
                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(50)).border(1.dp, CYAN.copy(alpha = 0.4f), RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 8.dp))
    }
    }
}

/* ---------------- top bar ---------------- */
// (Launcher ends above; its root is wrapped in the theme font provider)

@Composable
private fun TopBar(tab: Tab, onTab: (Tab) -> Unit, onClock: () -> Unit, onRam: () -> Unit, onVoice: () -> Unit, onApps: () -> Unit) {
    val ctx = LocalContext.current
    val now by produceState(Date()) { while (true) { value = Date(); delay(5_000) } }
    val ram by produceState(SystemInfo.ram(ctx), Unit) { while (true) { value = SystemInfo.ram(ctx); delay(5_000) } }
    Row(Modifier.fillMaxWidth()
        .focusProperties { exit = { d -> if (d == FocusDirection.Down) FocusMem.downFromTop(tab.name) else FocusRequester.Default } }
        .focusGroup().padding(horizontal = 10.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Tab.entries.filter { (it != Tab.Drone || Drone.menuOn) && (it != Tab.Tv || SamsungTv.menuShown) }.forEach { t ->
            var focused by remember { mutableStateOf(false) }
            val on = t == tab
            // tabs switch only on OK (no switching just by passing over them)
            Box(
                Modifier.padding(end = 2.dp).remembered("tab:${t.name}").focusProperties { up = FocusMem.upFromTop() }.onFocusChanged { focused = it.isFocused }.focusOnHover()
                    .clickable { onTab(t) }
                    .background(if (focused) VIOLET.copy(alpha = 0.25f) else if (on) Color.White.copy(alpha = 0.07f) else Color.Transparent, RoundedCornerShape(50))
                    .border(1.dp, if (focused) VIOLET else Color.Transparent, RoundedCornerShape(50))
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                // same vertical centre as the Voice / RAM / Apps pills; the active dot no longer pushes the text up
                Text(t.title.uppercase(), color = if (on || focused) FG else DIM, fontSize = 11.sp, letterSpacing = 1.3.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                if (on) Box(Modifier.align(Alignment.BottomCenter).offset(y = 6.dp).size(3.dp).background(VIOLET, CircleShape))
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(if (ram.usedPct < 70) GREEN else if (ram.usedPct < 85) AMBER else RED, "RAM ${ram.usedPct}%", "RAM ${ram.availMb} MB free · OK = clean background apps", onRam, id = "pill:ram")
            Pill(VIOLET, "Apps ▾", "Open apps", onApps, id = "pill:apps")
        }
        Spacer(Modifier.width(10.dp))
        // last in the bar, and the only way in to Quick settings: the panel drops down from here, anchored top right
        var clockFocused by remember { mutableStateOf(false) }
        Column(Modifier.remembered("clock").focusProperties { up = FocusMem.upFromTop() }.onFocusChanged { clockFocused = it.isFocused }.focusOnHover().clickable { onClock() }
            .background(if (clockFocused) VIOLET.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(10.dp))
            .border(1.dp, if (clockFocused) VIOLET else Color.Transparent, RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), color = FG, fontSize = 24.sp, fontWeight = FontWeight.Light)
            Text(SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now), color = if (clockFocused) FG else DIM, fontSize = 9.sp)   // same text focused or not: a wider one moved the bar
        }
    }
}

/* ---------------- modal frame ---------------- */

@Composable
fun ModalFrame(title: String, hint: String, width: Int = 480, alignTop: Boolean = false, alignment: Alignment? = null, height: Int? = null, content: @Composable (FocusRequester) -> Unit) {
    val first = remember { FocusRequester() }
    var inside by remember { mutableStateOf(false) }
    // the scrim reaches past this box: modals sit inside the scaled, inset interface, and a scrim the size of that left a
    // lighter frame around it (the "overlay" edge). Compose doesn't clip to bounds here, so it covers the whole screen
    Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = Theme.scrimAlpha), topLeft = androidx.compose.ui.geometry.Offset(-4000f, -4000f), size = androidx.compose.ui.geometry.Size(size.width + 8000f, size.height + 8000f)) }) {
        Column(
            Modifier.onFocusChanged { inside = it.hasFocus }.align(alignment ?: if (alignTop) Alignment.TopEnd else Alignment.Center)
                // a panel anchored top right hangs just under the top bar, as if dropping from its last pill
                .then(if (alignment == Alignment.TopEnd) Modifier.padding(top = 62.dp, end = 14.dp) else Modifier.padding(if (alignTop || alignment != null) 40.dp else 24.dp))
                .width(width.dp).then(if (height != null) Modifier.height(height.dp) else Modifier.heightIn(max = 470.dp))
                .background(Color(0xFF060C14).copy(alpha = (Theme.panelAlpha + 0.33f).coerceAtMost(0.98f)), RoundedCornerShape(18.dp)).border(1.dp, CYAN.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                .padding(18.dp)
        ) {
            Text(title.uppercase(), color = CYAN, fontSize = 13.sp, letterSpacing = 1.5.sp)
            Text(hint, color = DIM, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
            content(first)
        }
    }
    LaunchedEffect(Unit) {
        delay(70)
        val back = FocusMem.returnTo
        FocusMem.returnTo = null
        if (back != null) FocusMem.restoreSoon(back) else runCatching { first.requestFocus() }
        // a menu that remembers its scroll position may not have its first row loaded: take the first row on screen instead
        delay(150)
        if (!inside) { FocusMem.focus?.moveFocus(FocusDirection.Down); delay(150) }
        if (!inside) android.util.Log.i("StarshineInput", "focus none")
    }
}

@Composable
private fun Overlay(title: String, hint: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).padding(44.dp)) {
        Column {
            Text(title, color = CYAN, fontSize = 13.sp, letterSpacing = 1.5.sp)
            Text(hint, color = DIM, fontSize = 11.sp, modifier = Modifier.padding(bottom = 14.dp))
            content()
        }
    }
}

@Composable
fun Header(t: String) = Text(t.uppercase(), color = DIM, fontSize = 9.sp, letterSpacing = 1.3.sp, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))

/* ---------------- settings: categories in Quick edit, each opens its own modal ---------------- */

private val SETTINGS_ICONS = mapOf(
    "Display & position" to "🖥", "Theme" to "🎨", "Drone" to "🛸", "TV" to "📺", "Files" to "📁", "Sound" to "🔊", "Voice setup" to "🎤", "M5 air mouse" to "🖱",
    "Gestures" to "✍", "Web & Jellyfin" to "🌐", "Screensaver" to "✨", "Dock" to "🚀", "System" to "⚙", "About & exit" to "ℹ"
)

@Composable
fun SettingsCategory(
    cat: String, first: FocusRequester,
    dockScale: Float, hideSecs: Float, uiScale: Float, glasses: Boolean, offX: Float, offY: Float, fps30: Boolean,
    ambientVol: Float, browser: String, showResume: Boolean, signedIn: Boolean, dim: Float, onDim: (Float) -> Unit,
    onDock: (Float) -> Unit, onHide: (Float) -> Unit, topHideSecs: Float, onTopHide: (Float) -> Unit, onUi: (Float) -> Unit, onGlasses: (Boolean) -> Unit,
    onOffset: (Float, Float) -> Unit, onResetLayout: () -> Unit, onPosition: () -> Unit, onFps: (Boolean) -> Unit,
    onAmbientVol: (Float) -> Unit, onStopSound: () -> Unit, onGenerator: () -> Unit,
    onBrowser: (String) -> Unit, onResume: (Boolean) -> Unit, onJellyfin: () -> Unit,
    onCalibrate: () -> Unit, onHelp: () -> Unit, onResetDock: () -> Unit, onExit: () -> Unit, open: (Intent) -> Unit
) {
    val sctx = LocalContext.current
    var ptr by remember { mutableStateOf(M5.pointerSpeed(sctx)) }
    var ptrMsg by remember { mutableStateOf("") }
    fun m5(change: () -> Unit) { change(); M5.save(sctx) }
    fun cyc(list: List<String>, cur: String, d: Int) = list[(list.indexOf(cur).coerceAtLeast(0) + d + list.size) % list.size]
    val bi = BROWSERS.indexOfFirst { it.first == browser }.coerceAtLeast(0)
    val f = Modifier.focusRequester(first)
    LoopColumn(state = FocusMem.list("set:$cat"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when (cat) {
            "Display & position" -> {
                item { Box(f) { Adjust("AR dim, all backgrounds (black = see-through)", pct(dim), { onDim((dim - 0.05f).coerceAtLeast(0f)) }) { onDim((dim + 0.05f).coerceAtMost(1f)) } } }
                item { PhoneOffRow() }
                item {
                    val flip = { HeadTrack.set(sctx, !HeadTrack.on) }
                    Adjust("3D camera follows head", if (!HeadTrack.available) "no motion sensor found" else if (HeadTrack.on) "on" else "off", { flip() }) { flip() }
                }
                if (HeadTrack.on) item { Row_("⌖ Recenter: straight ahead = where you look now", false, id = "set:recenter", onFocus = {}) { HeadTrack.recenter() } }
                item { Adjust("Graphics quality", Bg2.QUALITY[Bg2.quality], { Bg2.setQuality(sctx, (Bg2.quality + 2) % 3) }) { Bg2.setQuality(sctx, (Bg2.quality + 1) % 3) } }
                item { Adjust("Dock size", pct(dockScale), { onDock((dockScale - 0.05f).coerceAtLeast(0.2f)) }) { onDock(dockScale + 0.05f) } }
                item { Adjust("Dock tiles", Look.styleName, { Look.cycleStyle(sctx, -1) }) { Look.cycleStyle(sctx, 1) } }
                item { Adjust("Auto-hide dock after", if (hideSecs <= 0f) "never" else "${hideSecs.toInt()} s", { onHide((hideSecs - 1f).coerceAtLeast(0f)) }) { onHide(hideSecs + 1f) } }
                item { Adjust("Auto-hide top menu after", if (topHideSecs <= 0f) "never" else "${topHideSecs.toInt()} s", { onTopHide((topHideSecs - 5f).coerceAtLeast(0f)) }) { onTopHide(topHideSecs + 5f) } }
                item { Adjust("Zoom (UI scale, no limits)", pct(uiScale), { onUi((uiScale - 0.05f).coerceAtLeast(0.02f)) }) { onUi(uiScale + 0.05f) } }
                item { Adjust("Glasses mode (centered safe zone)", if (glasses) "on" else "off", { onGlasses(!glasses) }) { onGlasses(!glasses) } }
                item { Adjust("Top menu edge spacing" + if (glasses) " (full screen only)" else "", "${Look.edgePad} px", { Look.changeEdge(sctx, -4) }) { Look.changeEdge(sctx, 4) } }
                item { Adjust("Horizontal position", pct(offX), { onOffset(offX - 0.01f, offY) }) { onOffset(offX + 0.01f, offY) } }
                item { Adjust("Vertical position", pct(offY), { onOffset(offX, offY - 0.01f) }) { onOffset(offX, offY + 0.01f) } }
                item { Row_("✥ Move & zoom with arrows (OK switches move/zoom)", false, onFocus = {}) { onPosition() } }
                item { Row_("Reset layout to full screen (position, zoom, glasses mode)", false, onFocus = {}) { onResetLayout() } }
                item { Adjust("Animation frame rate", if (fps30) "30 fps · Eco" else "60 fps · Smooth", { onFps(!fps30) }) { onFps(!fps30) } }
            }
            "Sound" -> {
                item { Box(f) { Adjust("Ambient volume", pct(ambientVol), { onAmbientVol((ambientVol - 0.05f).coerceAtLeast(0f)) }) { onAmbientVol((ambientVol + 0.05f).coerceAtMost(1f)) } } }
                item { Row_("■ Stop sound", false, onFocus = {}) { onStopSound() } }
                item { Row_("〰 Frequency generator & presets…", false, onFocus = {}) { onGenerator() } }
                item { Adjust("Moods in other apps", if (Sound.playOutside) "keep playing" else "silent", { Sound.setOutside(sctx, !Sound.playOutside) }) { Sound.setOutside(sctx, !Sound.playOutside) } }
                item { PlayerSettingsRows() }
                item { Text("Moods play on the Home screen and, unless set to silent above, in silent apps. They pause automatically when any app plays audio and resume when it stops. Frequency volume is in the generator.", color = DIM, fontSize = 10.sp, modifier = Modifier.padding(8.dp)) }
            }
            "Voice setup" -> { item { Box(f) { VoiceSetup() } } }
            "Drone" -> { item { Box(f) { DroneRows(showOptOut = true) } } }
            "TV" -> { item { Box(f) { TvSettingsRows() } } }
            "Theme" -> { item { Box(f) { ThemeRows() } } }
            "M5 air mouse" -> {
                item { Box(f) { Row_("🎯 Test, button map & auto-calibrate  ›", false, onFocus = {}) { onCalibrate() } } }
                item { Adjust("Click = OK on selected item", if (M5.clickAsOk) "on" else "off", { m5 { M5.clickAsOk = !M5.clickAsOk } }) { m5 { M5.clickAsOk = !M5.clickAsOk } } }
                item { Adjust("Hover selects items", if (M5.hoverSelect) "on" else "off (1 press = 1 move)", { m5 { M5.hoverSelect = !M5.hoverSelect } }) { m5 { M5.hoverSelect = !M5.hoverSelect } } }
                item { Adjust("Hover delay", "${M5.dwellMs} ms", { m5 { M5.dwellMs = (M5.dwellMs - 50).coerceAtLeast(0) } }) { m5 { M5.dwellMs += 50 } } }
                item { Adjust("Jitter guard", "${M5.deadzone.toInt()} px", { m5 { M5.deadzone = (M5.deadzone - 2f).coerceAtLeast(0f) } }) { m5 { M5.deadzone += 2f } } }
                item { Adjust("Lock setting while editing", if (M5.editLock) "on" else "off", { m5 { M5.editLock = !M5.editLock } }) { m5 { M5.editLock = !M5.editLock } } }
                item { Adjust("Right click does", M5.rightClick, { m5 { M5.rightClick = cyc(M5.ACTIONS_RIGHT, M5.rightClick, -1) } }) { m5 { M5.rightClick = cyc(M5.ACTIONS_RIGHT, M5.rightClick, +1) } } }
                item { Adjust("Menu key does", M5.menuKey, { m5 { M5.menuKey = cyc(M5.ACTIONS_MENU, M5.menuKey, -1) } }) { m5 { M5.menuKey = cyc(M5.ACTIONS_MENU, M5.menuKey, +1) } } }
                item { CursorSwitch() }
                item { Adjust("Cursor motion speed (system)", ptrMsg.ifEmpty { ptr?.toString() ?: "?" },
                    { ptrMsg = if (M5.setPointerSpeed(sctx, (ptr ?: 0) - 1)) "" else "use PC toolkit → M5 Remote"; ptr = M5.pointerSpeed(sctx) }) {
                    ptrMsg = if (M5.setPointerSpeed(sctx, (ptr ?: 0) + 1)) "" else "use PC toolkit → M5 Remote"; ptr = M5.pointerSpeed(sctx) } }
            }
            "Web & Jellyfin" -> {
                item { Row_("🌐 Open a web address  ›", false, id = "set:web", onFocus = {}) { WebActivity.askAddress(sctx) } }
                item {
                    val sp = remember { sctx.getSharedPreferences("starshine", Context.MODE_PRIVATE) }
                    var auto by remember { mutableStateOf(sp.getBoolean(WebActivity.AUTO_VLC, true)) }
                    fun flip() { auto = !auto; sp.edit().putBoolean(WebActivity.AUTO_VLC, auto).apply() }
                    Adjust("Web video opens in VLC (Starshine browser)", if (auto) "on" else "off", { flip() }) { flip() }
                }
                item { Box(f) { Adjust("Browser for web tiles", BROWSERS[bi].second, { onBrowser(BROWSERS[(bi - 1 + BROWSERS.size) % BROWSERS.size].first) }) { onBrowser(BROWSERS[(bi + 1) % BROWSERS.size].first) } } }
                item { Adjust("Jellyfin Continue watching row", if (showResume) "show" else "hide", { onResume(!showResume) }) { onResume(!showResume) } }
                item {
                    var url by remember { mutableStateOf(Prefs(sctx).jellyfin) }
                    Row_("Jellyfin server: " + url.ifEmpty { "not set" } + "  ›", false, id = "set:jfurl", onFocus = {}) {
                        val box = android.widget.EditText(sctx).apply { setText(url.ifEmpty { "http://" }); setSingleLine() }
                        android.app.AlertDialog.Builder(sctx).setTitle("Jellyfin server address").setView(box)
                            .setPositiveButton("Save") { _, _ -> url = box.text.toString().trim().trimEnd('/'); Prefs(sctx).jellyfin = url }
                            .setNegativeButton("Cancel", null).show()
                    }
                }
                item { Row_(if (signedIn) "Jellyfin: signed in · sign out" else "Jellyfin: sign in with Quick Connect  ›", false, onFocus = {}) { onJellyfin() } }
            }
            "Screensaver" -> {
                item { Box(f) { Row_("Choose screensaver (pick \"Starshine\")  ›", false, onFocus = {}) { open(Intent(Settings.ACTION_DREAM_SETTINGS)) } } }
                item { Text("Use Backgrounds → Classic backgrounds → → on a background → \"Use as screensaver\" to choose what it shows.", color = DIM, fontSize = 10.sp, modifier = Modifier.padding(8.dp)) }
            }
            "Dock" -> {
                item { Box(f) { Row_("Reset dock to Google TV favorites order", false, onFocus = {}) { onResetDock() } } }
                item { Text("On Home: hold OK on a tile to move (← →) or remove (↓). The + tile adds apps.", color = DIM, fontSize = 10.sp, modifier = Modifier.padding(8.dp)) }
            }
            "System" -> {
                item { Box(f) { HomeSwitch() } }
                item { Row_("📶 Wi-Fi settings  ›", false, onFocus = {}) { open(Intent(Settings.ACTION_WIFI_SETTINGS)) } }
                item { Row_("ᛒ Bluetooth settings  ›", false, onFocus = {}) { open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }
                item { Row_("🖥 Display settings  ›", false, onFocus = {}) { open(Intent(Settings.ACTION_DISPLAY_SETTINGS)) } }
                item { Row_("Advanced device settings (Amlogic)  ›", false, onFocus = {}) { open(Intent().setClassName("com.droidlogic.tv.settings", "com.droidlogic.tv.settings.more.MorePrefFragmentActivity")) } }
                item { Row_("Android settings  ›", false, onFocus = {}) { open(Intent(Settings.ACTION_SETTINGS)) } }
            }
            else -> {
                item { Box(f) { Row_("❓ Help  ›", false, onFocus = {}) { onHelp() } } }
                item { SupportRows() }
                item { Row_("⏻  Exit Starshine (Home goes to Google TV until you open Starshine again)", false, onFocus = {}) { onExit() } }
                item { Text("Starshine 2.0 · made for Rokid Station", color = DIM, fontSize = 10.sp, modifier = Modifier.padding(8.dp)) }
            }
        }
    }
}

/* ---------------- background settings modal ---------------- */

@Composable
fun BackgroundSettings(first: FocusRequester, bg: String, style: BgStyle, folder: String,
                       onStyle: (BgStyle) -> Unit, onSaver: () -> Unit, onExport: () -> Unit) {
    val exporting by produceState(-1f) { while (true) { value = Exporter.progress; delay(500) } }
    LoopColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { Box(Modifier.focusRequester(first)) { Adjust("Speed (negative = reverse)", f1(style.speed) + "x", { onStyle(style.copy(speed = style.speed - 0.1f)) }) { onStyle(style.copy(speed = style.speed + 0.1f)) } } }
        item { Adjust("Density", pct(style.density), { onStyle(style.copy(density = (style.density - 0.1f).coerceAtLeast(0f))) }) { onStyle(style.copy(density = style.density + 0.1f)) } }
        item { Adjust("Particle size", f1(style.size) + "x", { onStyle(style.copy(size = (style.size - 0.1f).coerceAtLeast(0.1f))) }) { onStyle(style.copy(size = style.size + 0.1f)) } }
        item { Adjust("Glow", pct(style.glow), { onStyle(style.copy(glow = (style.glow - 0.1f).coerceAtLeast(0f))) }) { onStyle(style.copy(glow = style.glow + 0.1f)) } }
        item { Adjust("Twinkle", pct(style.twinkle), { onStyle(style.copy(twinkle = (style.twinkle - 0.1f).coerceAtLeast(0f))) }) { onStyle(style.copy(twinkle = style.twinkle + 0.1f)) } }
        item { Adjust("Brightness", pct(style.brightness), { onStyle(style.copy(brightness = (style.brightness - 0.1f).coerceAtLeast(0f))) }) { onStyle(style.copy(brightness = style.brightness + 0.1f)) } }
        item { Adjust("Saturation", pct(style.saturation), { onStyle(style.copy(saturation = (style.saturation - 0.1f).coerceAtLeast(0f))) }) { onStyle(style.copy(saturation = style.saturation + 0.1f)) } }
        item { Adjust("Vignette", pct(style.vignette), { onStyle(style.copy(vignette = (style.vignette - 0.1f).coerceAtLeast(0f))) }) { onStyle(style.copy(vignette = style.vignette + 0.1f)) } }
        item { Adjust("Color shift", "${style.hue.toInt()}°", { onStyle(style.copy(hue = (style.hue - 15f + 360f) % 360f)) }) { onStyle(style.copy(hue = (style.hue + 15f) % 360f)) } }
        item { ReactSwitch(bg) }
        item { Adjust("Direction X (← left · right →)", f1(style.dirX), { onStyle(style.copy(dirX = style.dirX - 0.1f)) }) { onStyle(style.copy(dirX = style.dirX + 0.1f)) } }
        item { Adjust("Direction Y (← up · down →)", f1(style.dirY), { onStyle(style.copy(dirY = style.dirY - 0.1f)) }) { onStyle(style.copy(dirY = style.dirY + 0.1f)) } }
        item { Adjust("Direction Z (← away · toward →)", f1(style.dirZ), { onStyle(style.copy(dirZ = style.dirZ - 0.1f)) }) { onStyle(style.copy(dirZ = style.dirZ + 0.1f)) } }
        item { Row_("Reset direction (straight ahead)", false, onFocus = {}) { onStyle(style.copy(dirX = 0f, dirY = 0f, dirZ = 1f)) } }
        if (bg == "Starfield") item { NebulaRows() }
        item { Row_("Reset look to default", false, onFocus = {}) { onStyle(BgStyle()) } }
        item { Row_("Use this background as screensaver", false, onFocus = {}) { onSaver() } }
        item { Row_(if (exporting >= 0f) "Rendering video… ${(exporting * 100).toInt()}%" else "Export as 15 s video loop (MP4)", false, id = "export", onFocus = {}) { onExport() } }
        item { Text("Your own videos/GIFs: adb push file.mp4 $folder/", color = DIM, fontSize = 9.sp, modifier = Modifier.padding(top = 6.dp)) }
    }
}

/* ---------------- quick panel (clock) ---------------- */



private const val ALL_SETTINGS = "@all"

/** Every settings category as a big round icon with its name under it; the line on top describes the selected one. */
@Composable
private fun SettingsHome(first: FocusRequester, onOpen: (String) -> Unit) {
    var sel by remember { mutableStateOf(SETTINGS_CATS.first().first) }
    Column {
        Text(SETTINGS_CATS.firstOrNull { it.first == sel }?.second ?: " ", color = DIM, fontSize = 10.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 10.dp))
        LoopColumn(state = FocusMem.list("settingsHome"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SETTINGS_CATS.map { it.first }.chunked(5).forEachIndexed { r, row ->
                item(key = "sh:" + row.first()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEachIndexed { i, name ->
                            var focused by remember { mutableStateOf(false) }
                            Column(Modifier.weight(1f).then(if (r == 0 && i == 0) Modifier.focusRequester(first) else Modifier)
                                .remembered("set:home:$name").onFocusChanged { focused = it.isFocused; if (it.isFocused) sel = name }
                                .focusOnHover().clickable { onOpen(name) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(54.dp).clip(CircleShape)
                                    .background(if (focused) CYAN.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.07f))
                                    .border(if (focused) 2.dp else 1.dp, if (focused) CYAN else CYAN.copy(alpha = 0.16f), CircleShape),
                                    contentAlignment = Alignment.Center) { Text(SETTINGS_ICONS[name] ?: "•", fontSize = 24.sp, color = FG) }
                                Text(name, color = if (focused) FG else DIM, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 5.dp))
                            }
                        }
                        repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}


/* ---------------- open apps: vertical drop-down ---------------- */

@Composable
fun OpenAppsList(first: FocusRequester, apps: List<AppEntry>, browser: String, jellyfinUrl: String, onDone: () -> Unit, onToast: (String) -> Unit) {
    val ctx = LocalContext.current
    val jf by produceState(-1L, jellyfinUrl) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val t0 = System.currentTimeMillis()
                    val ok = (URL("$jellyfinUrl/System/Info/Public").openConnection() as HttpURLConnection).run { connectTimeout = 3000; readTimeout = 3000; responseCode == 200 }
                    if (ok) maxOf(1L, System.currentTimeMillis() - t0) else 0L
                }.getOrDefault(0L)
            }
            delay(30_000)
        }
    }
    val granted = remember { SystemInfo.hasUsageAccess(ctx) }
    var recent by remember { mutableStateOf(SystemInfo.recentApps(ctx, apps.map { it.id }.toSet()).filter { it != JELLYFIN_PKG }.mapNotNull { id -> apps.find { it.id == id } }) }
    var longFired by remember { mutableStateOf(false) }
    val jellyfin = apps.find { it.id == JELLYFIN_PKG }
    fun holdToClose(a: AppEntry): (KeyEvent) -> Boolean = { e ->
        when {
            !e.isOk() -> false
            e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 1 -> { longFired = true; SystemInfo.close(ctx, a.id); recent = recent - a; onToast("Closed ${a.label}"); true }
            e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 1 -> true
            e.type == KeyEventType.KeyUp && longFired -> { longFired = false; true }
            else -> false
        }
    }
    LoopColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (jellyfin != null && jellyfinUrl.isNotEmpty()) item(key = "jf") {
            Box(Modifier.focusRequester(first)) {
                Pill(when { jf < 0 -> DIM; jf == 0L -> RED; else -> GREEN }, "Jellyfin · " + when { jf < 0 -> "checking"; jf == 0L -> "server down"; else -> "$jf ms" },
                    "Jellyfin · " + when { jf < 0 -> "checking"; jf == 0L -> "server unreachable"; else -> "$jf ms · OK open" },
                    onClick = { launch(ctx, JELLYFIN_PKG); onDone() }, onKey = holdToClose(jellyfin), id = "open:jf")
            }
        }
        items(recent, key = { it.id }) { a ->
            Pill(CYAN, a.label, "${a.label} · OK switch · hold OK close", onClick = { launch(ctx, a.id, browser); onDone() }, onKey = holdToClose(a), id = "open:${a.id}")
        }
        if (!granted) item(key = "hint") { Pill(AMBER, "usage access off", "Run Build & Install Starshine once", onClick = {}, id = "open:hint") }
        item(key = "closeall") {
            Pill(RED, "✕ Clean background apps", "Clean background apps (only the PC toolkit can fully close them)", onClick = {
                onToast("Cleaning background apps…")
                Thread { val r = SystemInfo.boost(ctx, apps.map { it.id }); onToast(r.text + ". Some apps may stay or restart") }.start()
                recent = emptyList()
            }, id = "open:closeall")
        }
    }
}

/* ---------------- frequency generator modal ---------------- */

@Composable
fun ToneEditor(first: FocusRequester, onPlay: (Tone) -> Unit, onStop: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE) }
    var presets by remember { mutableStateOf(Tones.loadPresets(ctx)) }
    var idx by remember { mutableIntStateOf(sp.getInt("toneIdx", 0).coerceIn(0, (presets.size - 1).coerceAtLeast(0))) }
    var tone by remember { mutableStateOf(presets.firstOrNull { it.name == Sound.current } ?: presets.getOrElse(idx) { Tone() }) }
    val playing = Sound.current == tone.name || (Sound.current.isNotEmpty() && Tones.playing)
    var note by remember { mutableStateOf("") }
    fun set(t: Tone) { tone = t; Sound.updateTone(t) }
    fun pick(i: Int) { if (presets.isEmpty()) return; idx = (i % presets.size + presets.size) % presets.size; sp.edit().putInt("toneIdx", idx).apply(); tone = presets[idx]; if (playing) onPlay(tone) }
    fun save(list: List<Tone>) { presets = list; Tones.savePresets(ctx, list); onSaved() }
    fun hz(v: Float) = if (v >= 100f) String.format(Locale.US, "%.1f Hz", v) else String.format(Locale.US, "%.2f Hz", v)
    fun beat(v: Float) = tone.copy(beat = v.coerceAtLeast(0f), beatEnd = if (tone.sweepMin <= 0f) v.coerceAtLeast(0f) else tone.beatEnd)
    LoopColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { Box(Modifier.focusRequester(first)) { Adjust("Preset", "${idx + 1}/${presets.size} · ${tone.name}", { pick(idx - 1) }) { pick(idx + 1) } } }
        item { Row_(if (playing) "■ Stop" else "▶ Play", playing, id = "tone:playstop", onFocus = {}) { if (playing) onStop() else onPlay(tone) } }
        item { Adjust("Carrier · coarse ±10 Hz", hz(tone.carrier), { set(tone.copy(carrier = (tone.carrier - 10f).coerceAtLeast(1f))) }) { set(tone.copy(carrier = (tone.carrier + 10f).coerceAtMost(22000f))) } }
        item { Adjust("Carrier · fine ±0.1 Hz", hz(tone.carrier), { set(tone.copy(carrier = (tone.carrier - 0.1f).coerceAtLeast(1f))) }) { set(tone.copy(carrier = (tone.carrier + 0.1f).coerceAtMost(22000f))) } }
        item { Adjust("Beat (binaural) · ±1 Hz", hz(tone.beat), { set(beat(tone.beat - 1f)) }) { set(beat(tone.beat + 1f)) } }
        item { Adjust("Beat · fine ±0.05 Hz", hz(tone.beat), { set(beat(tone.beat - 0.05f)) }) { set(beat(tone.beat + 0.05f)) } }
        item { Adjust("Sweep beat to", hz(tone.beatEnd), { set(tone.copy(beatEnd = (tone.beatEnd - 0.5f).coerceAtLeast(0f))) }) { set(tone.copy(beatEnd = tone.beatEnd + 0.5f)) } }
        item { Adjust("Sweep duration", if (tone.sweepMin <= 0f) "off" else "${tone.sweepMin.toInt()} min", { set(tone.copy(sweepMin = (tone.sweepMin - 1f).coerceAtLeast(0f))) }) { set(tone.copy(sweepMin = tone.sweepMin + 1f)) } }
        item { Adjust("Waveform", Tone.WAVES[tone.wave.coerceIn(0, 3)], { set(tone.copy(wave = (tone.wave + 3) % 4)) }) { set(tone.copy(wave = (tone.wave + 1) % 4)) } }
        item { Adjust("Isochronic pulse", if (tone.iso <= 0f) "off" else hz(tone.iso), { set(tone.copy(iso = (tone.iso - 0.5f).coerceAtLeast(0f))) }) { set(tone.copy(iso = tone.iso + 0.5f)) } }
        item { Adjust("Pulse depth", pct(tone.isoDepth), { set(tone.copy(isoDepth = (tone.isoDepth - 0.05f).coerceAtLeast(0f))) }) { set(tone.copy(isoDepth = (tone.isoDepth + 0.05f).coerceAtMost(1f))) } }
        item { Adjust("2nd harmonic", pct(tone.harmonic), { set(tone.copy(harmonic = (tone.harmonic - 0.05f).coerceAtLeast(0f))) }) { set(tone.copy(harmonic = (tone.harmonic + 0.05f).coerceAtMost(1f))) } }
        item { Adjust("Pink-noise bed", pct(tone.noise), { set(tone.copy(noise = (tone.noise - 0.05f).coerceAtLeast(0f))) }) { set(tone.copy(noise = (tone.noise + 0.05f).coerceAtMost(1f))) } }
        item { Adjust("Didgeridoo", if (tone.didge) "on" else "off", { set(tone.copy(didge = !tone.didge)) }) { set(tone.copy(didge = !tone.didge)) } }
        if (tone.didge) {
            item { Adjust("   Drone note", hz(tone.didgePitch), { set(tone.copy(didgePitch = (tone.didgePitch - 1f).coerceAtLeast(30f))) }) { set(tone.copy(didgePitch = (tone.didgePitch + 1f).coerceAtMost(160f))) } }
            item { Adjust("   Didgeridoo level", pct(tone.didgeLevel), { set(tone.copy(didgeLevel = (tone.didgeLevel - 0.05f).coerceAtLeast(0f))) }) { set(tone.copy(didgeLevel = (tone.didgeLevel + 0.05f).coerceAtMost(1f))) } }
            item { Adjust("   Mouth movement", pct(tone.didgeMouth), { set(tone.copy(didgeMouth = (tone.didgeMouth - 0.05f).coerceAtLeast(0f))) }) { set(tone.copy(didgeMouth = (tone.didgeMouth + 0.05f).coerceAtMost(1f))) } }
            item { Adjust("   Mouth speed", hz(tone.didgeRate), { set(tone.copy(didgeRate = (tone.didgeRate - 0.05f).coerceAtLeast(0.05f))) }) { set(tone.copy(didgeRate = (tone.didgeRate + 0.05f).coerceAtMost(6f))) } }
            item { Adjust("   Breath", pct(tone.didgeBreath), { set(tone.copy(didgeBreath = (tone.didgeBreath - 0.05f).coerceAtLeast(0f))) }) { set(tone.copy(didgeBreath = (tone.didgeBreath + 0.05f).coerceAtMost(1f))) } }
            item { Adjust("   Hide the tone (didgeridoo only)", if (tone.toneOff) "yes" else "no", { set(tone.copy(toneOff = !tone.toneOff)) }) { set(tone.copy(toneOff = !tone.toneOff)) } }
        }
        item { Adjust("Balance L / R", if (abs(tone.balance) < 0.01f) "center" else (if (tone.balance < 0) "L " else "R ") + pct(abs(tone.balance)),
            { set(tone.copy(balance = (tone.balance - 0.05f).coerceAtLeast(-1f))) }) { set(tone.copy(balance = (tone.balance + 0.05f).coerceAtMost(1f))) } }
        item { Adjust("Volume", pct(tone.volume), { set(tone.copy(volume = (tone.volume - 0.02f).coerceAtLeast(0f))) }) { set(tone.copy(volume = (tone.volume + 0.02f).coerceAtMost(1f))) } }
        item { Adjust("Fade in / smoothing", String.format(Locale.US, "%.1f s", tone.fadeSec), { set(tone.copy(fadeSec = (tone.fadeSec - 0.5f).coerceAtLeast(0f))) }) { set(tone.copy(fadeSec = tone.fadeSec + 0.5f)) } }
        item { Row_("＋ Save as new preset", false, onFocus = {}) {
            val n = "My ${hz(tone.carrier)}" + (if (tone.beat > 0f) " / ${hz(tone.beat)}" else "")
            save(presets + tone.copy(name = n)); idx = presets.size - 1; sp.edit().putInt("toneIdx", idx).apply(); tone = tone.copy(name = n); note = "Saved \"$n\" (now in Moods)"
        } }
        item { Row_("⟳ Overwrite current preset", false, onFocus = {}) {
            if (presets.isNotEmpty()) { val n = presets[idx].name; save(presets.toMutableList().apply { set(idx, tone.copy(name = n)) }); note = "Updated \"$n\"" }
        } }
        item { Row_("🗑 Delete current preset", false, onFocus = {}) {
            if (presets.size > 1) { val n = presets[idx].name; save(presets.filterIndexed { i, _ -> i != idx }); pick(idx.coerceAtMost(presets.size - 1)); note = "Deleted \"$n\"" }
        } }
        item { Row_("Restore built-in presets", false, onFocus = {}) { save(presets + Tone.BUILT_IN.filter { b -> presets.none { it.name == b.name } }); note = "Built-in presets restored" } }
        item { Text(note.ifEmpty { "Left ear = carrier − beat/2 · right = + beat/2. Binaural needs stereo. Keep the volume low." }, color = DIM, fontSize = 9.sp, modifier = Modifier.padding(top = 6.dp)) }
    }
}

fun loadApps(pm: PackageManager): List<AppEntry> {
    val seen = LinkedHashMap<String, AppEntry>()
    for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
        for (r in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
            val p = r.activityInfo.packageName
            if (p == SELF || p in HIDDEN || seen.containsKey(p)) continue
            val d = runCatching { r.loadIcon(pm) }.getOrNull()
            val icon = runCatching { d!!.toBitmap(144, 144).asImageBitmap() }.getOrNull()
            val banner = runCatching {
                (r.activityInfo.loadBanner(pm) ?: pm.getApplicationBanner(r.activityInfo.applicationInfo))?.toBitmap(320, 180)?.asImageBitmap()
            }.getOrNull()
            val round = runCatching { Look.roundArt(d!!, 112).asImageBitmap() }.getOrNull()
            seen[p] = AppEntry(p, r.loadLabel(pm).toString(), icon, banner, round)
        }
    }
    // Starshine's own package is left out above, so its browser is listed by hand
    val ownD = runCatching { pm.getApplicationIcon(SELF) }.getOrNull()
    val own = runCatching { ownD!!.toBitmap(144, 144).asImageBitmap() }.getOrNull()
    seen[BROWSER_ID] = AppEntry(BROWSER_ID, "Starshine browser", own, round = runCatching { Look.roundArt(ownD!!, 112).asImageBitmap() }.getOrNull())
    return seen.values.sortedBy { it.label.lowercase() }
}

fun resolve(id: String, apps: List<AppEntry>): AppEntry? = when {
    id == SETTINGS_ID -> AppEntry(SETTINGS_ID, "Android Settings")
    id == BLISS_ID -> AppEntry(BLISS_ID, "Bliss OS", icon = apps.find { it.id == EASYCONTROL }?.icon)
    id.startsWith("web:") -> webEntry(id)
    else -> apps.find { it.id == id }
}

fun launch(ctx: Context, id: String, browser: String = "") {
    val pm = ctx.packageManager
    val i = when {
        id == SETTINGS_ID -> Intent(Settings.ACTION_SETTINGS)
        id == BROWSER_ID -> Intent(ctx, WebActivity::class.java)
        id.startsWith("web:") -> {
            val uri = Uri.parse(id.substringAfter("|"))
            if (browser == "@builtin" || (BuildConfig.PRIVATE_DOMAIN.isNotEmpty() && (uri.host ?: "").endsWith(BuildConfig.PRIVATE_DOMAIN))) Intent(ctx, WebActivity::class.java).setData(uri)
            else Intent(Intent.ACTION_VIEW, uri).apply { if (browser.isNotEmpty() && pm.getLaunchIntentForPackage(browser) != null) setPackage(browser) }
        }
        else -> pm.getLeanbackLaunchIntentForPackage(id) ?: pm.getLaunchIntentForPackage(id) ?: return
    }
    // which code opened it (tag StarshineLaunch): an app coming back to the front by itself is otherwise impossible to trace
    android.util.Log.i("StarshineLaunch", "open $id from " + Throwable().stackTrace.drop(1).take(4).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" })
    runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun open(ctx: Context, i: Intent) = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

fun mediaFiles(ctx: Context): List<File> =
    ctx.getExternalFilesDir("backgrounds")?.listFiles()
        ?.filter { it.extension.lowercase() in setOf("mp4", "webm", "mkv", "gif", "webp", "png", "jpg", "jpeg") }
        ?.sortedBy { it.name } ?: emptyList()


// OK on every remote: D-pad centre, Enter, numpad Enter, and the gamepad-style SELECT / A that many air mice (incl. the M5) send
private fun KeyEvent.isOk() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter || key == Key.ButtonSelect || key == Key.ButtonA

@Composable
fun Background(bg: String, style: BgStyle, fps30: Boolean, reactive: Boolean = false) {
    val ctx = LocalContext.current
    if (bg == BG2_ID) { Bg2Background(fps30, Modifier.fillMaxSize()); return }
    if (bg.startsWith("file:")) {
        val nm = bg.removePrefix("file:")
        val f = if (nm.startsWith("/")) File(nm) else File(ctx.getExternalFilesDir("backgrounds"), nm)
        if (f.exists()) {
            if (f.extension.lowercase() in setOf("mp4", "webm", "mkv")) VideoBackground(Uri.fromFile(f)) else AnimatedImage(f)
            return
        }
    }
    if (bg in SHADERS) { ShaderBackground(bg, style, Modifier.fillMaxSize(), reactive); return }
    ThreadedBackground(if (bg in PROCEDURAL) bg else PROCEDURAL[0], style, fps30, reactive, Modifier.fillMaxSize())
}

@Composable
fun AnimatedImage(f: File) {
    val ctx = LocalContext.current
    val loader = remember {
        ImageLoader.Builder(ctx).components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }.build()
    }
    AsyncImage(model = f, contentDescription = null, imageLoader = loader, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
}

@OptIn(UnstableApi::class)
@Composable
fun VideoBackground(uri: Uri) {
    val ctx = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(uri)); repeatMode = Player.REPEAT_MODE_ALL; volume = 0f
            prepare(); playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = {
        PlayerView(it).apply {
            useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            isFocusable = false; descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            this.player = player
        }
    }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun Pill(dot: Color, text: String, focusedText: String, onClick: () -> Unit, onKey: ((KeyEvent) -> Boolean)? = null, id: String = text) {
    var focused by remember { mutableStateOf(false) }
    Box(contentAlignment = Alignment.BottomEnd) {
        Row(
            Modifier.remembered(id).focusProperties { if (id.startsWith("pill:")) up = FocusMem.upFromTop() }.onFocusChanged { focused = it.isFocused }.focusOnHover().onPreviewKeyEvent { onKey?.invoke(it) ?: false }.clickable { onClick() }
                .background(if (focused) VIOLET.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.3f), RoundedCornerShape(50))
                .border(1.dp, if (focused) VIOLET else Color.Transparent, RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).drawBehind { drawCircle(dot.copy(alpha = 0.35f), radius = size.minDimension * 1.2f) }.background(dot, CircleShape))
            // the pill keeps its own short text: a longer one widened it, squeezed the tab names onto two lines and pushed
            // the whole top bar down
            Text(text, color = if (focused) FG else DIM, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
        }
        // what OK does, in a label just under the top bar: laid out at zero size, so it can never move the bar
        if (focused && focusedText != text) Text(focusedText, color = FG, fontSize = 10.sp, maxLines = 1,
            modifier = Modifier.layout { m, _ ->
                val p = m.measure(androidx.compose.ui.unit.Constraints())
                layout(0, 0) { p.place(-p.width, 22.dp.roundToPx()) }
            }.background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(8.dp)).border(1.dp, VIOLET.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

private val TILE_SHAPE = RoundedCornerShape(12.dp)

@Composable
fun Tile(
    a: AppEntry?, modifier: Modifier = Modifier, width: Int = 128, label: String? = null,
    badge: Boolean = false, moving: Boolean = false, iconOnly: Boolean = false, round: Boolean = false,
    onKey: ((KeyEvent) -> Boolean)? = null, onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    // Apple style magnifies more, and lifts the icon, like the macOS Dock
    val scale by animateFloatAsState(if (focused) (if (Look.glass) 1.32f else 1.12f) else 1f, label = "scale")
    val lift by animateFloatAsState(if (focused && Look.glass) -8f else 0f, label = "lift")
    val h = width * 9 / 16
    val small = width < 90
    // round (Android TV style): a circle with the app icon in it, the name floating above when selected
    if (round && label == null) {
        val dia = (h + 10).coerceAtLeast(30)
        val names = Look.roundNames
        // magnified, the circle grows upward only: its bottom edge stays put, so the name under it is never covered,
        // and a name floating above moves up with it
        val grow = (scale - 1f) * dia / 2f
        Column(modifier.width((dia + if (names) 26 else 14).dp).remembered("tile:" + (a?.id ?: "+add")).onFocusChanged { focused = it.isFocused }.focusOnHover()
            .onPreviewKeyEvent { e -> onKey?.invoke(e) ?: false }.clickable { onClick() }, horizontalAlignment = Alignment.CenterHorizontally) {
          Box(contentAlignment = Alignment.TopCenter) {
            if (focused && !names) Text(a?.label ?: "Add app", color = FG, fontSize = 10.sp, maxLines = 1, softWrap = false,
                modifier = Modifier.offset(y = (-19f - 2f * grow + lift).dp).zIndex(1f).wrapContentWidth(unbounded = true)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
            Box(Modifier.size(dia.dp).graphicsLayer { translationY = (lift - grow) * density }.scale(scale)
                .drawBehind { if (focused) drawCircle(Brush.radialGradient(listOf(CYAN.copy(alpha = 0.40f), VIOLET.copy(alpha = 0.12f), Color.Transparent), radius = size.width * 0.8f), radius = size.width * 0.8f) }
                .clip(CircleShape).background(Color(0xCC0B1520))
                .border(if (focused || moving) 2.dp else 1.dp, if (moving) VIOLET else if (focused) CYAN else CYAN.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center) {
                when {
                    a == null -> Text("+", color = FG, fontSize = (dia * 0.5f).sp, fontWeight = FontWeight.Light)
                    a.round != null -> Image(a.round, a.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    a.icon != null -> Image(a.icon, a.label, Modifier.size((dia * 0.62f).dp))
                    a.banner != null -> Image(a.banner, a.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else -> Text(if (a.isWeb) "🌐" else "⚙", fontSize = (dia * 0.4f).sp, color = FG)
                }
                if (badge) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(7.dp).background(CYAN, CircleShape))
                if (moving) Text("‹ ›", color = Color.White, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp))
            }
          }
          if (names) Text(a?.label ?: "Add app", color = if (focused) FG else DIM, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(top = 6.dp))
        }
        return
    }
    Column(
        modifier.width(width.dp).remembered("tile:" + (a?.id ?: "+add")).onFocusChanged { focused = it.isFocused }.focusOnHover()
            .onPreviewKeyEvent { e -> onKey?.invoke(e) ?: false }
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // icons only: the tile is the icon plus 10, and the name floats above it when selected, so the dock never changes height
        val iconSize = h * 0.62f
        val slim = iconOnly && !small && label == null
        Box(contentAlignment = Alignment.TopCenter) {
        if (slim && focused) Text(a?.label ?: "Add app", color = FG, fontSize = 10.sp, maxLines = 1, softWrap = false,
            modifier = Modifier.offset(y = (-19).dp).wrapContentWidth(unbounded = true)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
        Box(
            Modifier.size(width.dp, if (slim) (iconSize + 10f).dp else h.dp).scale(scale)
                .drawBehind {
                    if (focused) drawRoundRect(
                        Brush.radialGradient(listOf(CYAN.copy(alpha = 0.40f), VIOLET.copy(alpha = 0.12f), Color.Transparent), radius = size.width * 0.85f),
                        topLeft = Offset(-size.width * 0.12f, -size.height * 0.2f), size = Size(size.width * 1.24f, size.height * 1.4f),
                        cornerRadius = CornerRadius(40f))
                }
                .clip(if (small) RoundedCornerShape(8.dp) else TILE_SHAPE)
                .background(Color(0xCC0B1520))
                .border(if (focused || moving) 2.dp else 1.dp, if (moving) VIOLET else if (focused) CYAN else CYAN.copy(alpha = 0.16f),
                    if (small) RoundedCornerShape(8.dp) else TILE_SHAPE),
            contentAlignment = Alignment.Center
        ) {
            when {
                a == null -> Text("+", color = FG, fontSize = (h * 0.6f).sp, fontWeight = FontWeight.Light)
                // icons only: the app icon, centred, without its name or banner art (the name shows under the tile when selected)
                iconOnly && !small -> if (a.icon != null) Image(a.icon, a.label, Modifier.size(iconSize.dp))
                    else if (a.banner != null) Image(a.banner, a.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Text(if (a.isWeb) "🌐" else "⚙", fontSize = (iconSize * 0.65f).sp, color = FG)
                a.banner != null -> Image(a.banner, a.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                small -> if (a.icon != null) Image(a.icon, a.label, Modifier.size((h * 0.7f).dp))
                    else if (a.isWeb) Text(a.label, color = FG, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
                    else Text("⚙", fontSize = (h * 0.45f).sp, color = FG)
                else -> Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (a.icon != null) Image(a.icon, a.label, Modifier.size((h * 0.55f).dp))
                    else Text(if (a.isWeb) "🌐" else "⚙", fontSize = 22.sp, color = FG)
                    Text(a.label, color = FG, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (badge) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(7.dp).background(CYAN, CircleShape))
            if (moving) Text("‹ ›", color = Color.White, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp))
        }
        }
        if (!slim) Text(label ?: if (focused) (a?.label ?: "Add app") else " ", color = FG, fontSize = 10.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable
fun NowPlayingCard() {
    val ctx = LocalContext.current
    val p by produceState<Playing?>(null) { while (true) { value = runCatching { NowPlaying.current(ctx) }.getOrNull(); delay(2_000) } }
    val np = p ?: return
    // the same card as Quick settings and the left edge: picture, ⏮ ⏯ ⏭ and ☰ (choose a playlist or track)
    PlayerBlock(np, Modifier.padding(bottom = 12.dp), idp = "home", wide = true)
}

@Composable
fun ContinueWatching(base: String, token: String, user: String, refresh: Int, onOpen: () -> Unit) {
    val items by produceState(emptyList<Jellyfin.Item>(), token, refresh) {
        while (true) { value = withContext(Dispatchers.IO) { Jellyfin.resume(base, token, user) }; delay(300_000) }
    }
    if (items.isEmpty()) return
    Column(Modifier.padding(bottom = 10.dp)) {
        Text("CONTINUE WATCHING", color = DIM, fontSize = 9.sp, letterSpacing = 1.4.sp, modifier = Modifier.padding(start = 20.dp, bottom = 4.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.id }) { it2 ->
                var focused by remember { mutableStateOf(false) }
                Column(Modifier.width(132.dp).remembered("cw:" + it2.id).onFocusChanged { focused = it.isFocused }.focusOnHover().clickable { onOpen() }) {
                    Box(Modifier.size(132.dp, 74.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xCC0B1520))
                        .border(if (focused) 2.dp else 1.dp, if (focused) CYAN else CYAN.copy(alpha = 0.15f), RoundedCornerShape(10.dp))) {
                        AsyncImage(it2.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(it2.progress.coerceIn(0f, 1f)).height(3.dp).background(CYAN))
                    }
                    Text(it2.title, color = if (focused) FG else DIM, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    if (focused) Text(it2.subtitle, color = DIM, fontSize = 9.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(text, color = FG, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 3.dp)
        .remembered("np:$text").onFocusChanged { focused = it.isFocused }.focusOnHover().clickable { onClick() }
        .background(if (focused) CYAN.copy(alpha = 0.2f) else Color.Transparent, CircleShape)
        .border(1.dp, if (focused) CYAN else DIM.copy(alpha = 0.3f), CircleShape).padding(horizontal = 10.dp, vertical = 6.dp))
}

@Composable
fun Dock(
    items: List<AppEntry>, moving: String?, tileWidth: Int, iconsOnly: Boolean = false, round: Boolean = false,
    onLaunch: (AppEntry) -> Unit, onStartMove: (String) -> Unit, onMove: (String, Int) -> Unit,
    onRemove: (String) -> Unit, onEndMove: () -> Unit, onAdd: () -> Unit
) {
    val requesters = remember { HashMap<String, FocusRequester>() }
    var longFired by remember { mutableStateOf(false) }
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val rowScope = rememberCoroutineScope()
    fun loopTo(index: Int, id: String) { rowScope.launch { rowState.scrollToItem(index); FocusMem.restoreSoon(id) } }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (moving != null) Text("← → move  ·  ↓ remove  ·  OK done", color = DIM, fontSize = 10.sp, modifier = Modifier.padding(bottom = 6.dp))
        LazyRow(
            Modifier.onPreviewKeyEvent { e ->
                // loops inside the dock, so the last tile doesn't jump up to the pills at the far right of the top menu
                if (e.type != KeyEventType.KeyDown || moving != null || items.isEmpty()) false
                else if (e.key == Key.DirectionRight && FocusMem.current == "tile:+add") { if (e.nativeKeyEvent.repeatCount == 0) loopTo(0, "tile:" + items.first().id); true }
                else if (e.key == Key.DirectionLeft && FocusMem.current == "tile:" + items.first().id) { if (e.nativeKeyEvent.repeatCount == 0) loopTo(items.size, "tile:+add"); true }
                else false
            }.then(
                // Apple style: one frosted shelf with a thin light rim; otherwise the dark bar
                if (Look.glass) Modifier.background(Color.White.copy(alpha = 0.13f), RoundedCornerShape(26.dp)).border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(26.dp))
                else Modifier.background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(20.dp))),
            state = rowState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = if (iconsOnly) 5.dp else 10.dp),
            horizontalArrangement = Arrangement.spacedBy((tileWidth / 8).coerceAtLeast(6).dp)
        ) {
            items(items, key = { it.id }) { a ->
                val fr = requesters.getOrPut(a.id) { FocusRequester() }
                // Up = whatever is nearest above (the player card, Continue Watching, or the top menu item over this tile)
                Tile(a, Modifier.focusRequester(fr), width = tileWidth, moving = moving == a.id, iconOnly = iconsOnly, round = round, onKey = { e ->
                    if (moving == a.id) {
                        if (e.type == KeyEventType.KeyDown) when {
                            e.key == Key.DirectionLeft -> onMove(a.id, -1)
                            e.key == Key.DirectionRight -> onMove(a.id, +1)
                            e.key == Key.DirectionDown -> onRemove(a.id)
                            e.isOk() && e.nativeKeyEvent.repeatCount == 0 && !longFired -> onEndMove()
                        }
                        if (e.type == KeyEventType.KeyUp && e.isOk()) longFired = false
                        true
                    } else when {
                        !e.isOk() -> false
                        e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 1 -> { longFired = true; onStartMove(a.id); true }
                        e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 1 -> true
                        else -> false
                    }
                }) { if (moving == null) onLaunch(a) }
            }
            // Apple style: a thin divider before "add", as the Dock separates apps from the rest
            if (Look.glass) item(key = "sep") { Box(Modifier.padding(top = 6.dp).width(1.dp).height((tileWidth * 0.45f).coerceAtLeast(20f).dp).background(Color.White.copy(alpha = 0.35f))) }
            item(key = "+add") { Tile(null, width = tileWidth, iconOnly = iconsOnly, round = round) { if (moving == null) onAdd() } }
        }
    }
    LaunchedEffect(items.map { it.id }, moving) { moving?.let { runCatching { requesters[it]?.requestFocus() } } }
    // first launch only: put focus on the first dock tile (retry until the lazy row has laid it out)
    val latestItems = rememberUpdatedState(items)
    LaunchedEffect(items.isEmpty(), FocusMem.pendingDock) {   // apps load in the background, so wait for them
        if (!FocusMem.pendingDock || items.isEmpty()) return@LaunchedEffect
        repeat(20) {
            delay(100)
            FocusMem.restore("tab:Home")   // Home is where the selection starts
            if (FocusMem.current == "tab:Home") { FocusMem.pendingDock = false; return@LaunchedEffect }
        }
        FocusMem.pendingDock = false
    }
}

@Composable
fun AddPicker(candidates: List<AppEntry>, onPick: (AppEntry) -> Unit) {
    val first = remember { FocusRequester() }
    Overlay("ADD TO DOCK", "OK = add · Back = close") {
        LazyVerticalGrid(GridCells.Adaptive(150.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(candidates, key = { it.id }) { a -> Tile(a, if (a == candidates.firstOrNull()) Modifier.focusRequester(first) else Modifier, label = a.label) { onPick(a) } }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

@Composable
fun QuickConnectDialog(base: String, onDone: (String, String) -> Unit, onClose: () -> Unit) {
    var code by remember { mutableStateOf("…") }
    var status by remember { mutableStateOf("Contacting Jellyfin…") }
    LaunchedEffect(Unit) {
        val init = withContext(Dispatchers.IO) { Jellyfin.initiate(base) }
        if (init == null) { status = "Quick Connect is off on the server. Enable it in Jellyfin Dashboard → General → Quick Connect."; return@LaunchedEffect }
        code = init.first; status = "On your phone/PC: Jellyfin → profile → Quick Connect → enter this code"
        repeat(120) {
            delay(3000)
            if (withContext(Dispatchers.IO) { Jellyfin.approved(base, init.second) }) {
                val r = withContext(Dispatchers.IO) { Jellyfin.login(base, init.second) }
                if (r != null) { onDone(r.first, r.second); return@LaunchedEffect } else { status = "Approved, but sign-in failed. Try again."; return@LaunchedEffect }
            }
        }
        status = "Timed out. Press Back and try again."
    }
    Overlay("JELLYFIN QUICK CONNECT", "Back = cancel") {
        Text(code, color = FG, fontSize = 56.sp, fontWeight = FontWeight.Light, letterSpacing = 8.sp)
        Text(status, color = DIM, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        Box(Modifier.size(1.dp).focusable())
    }
}

@Composable
fun Chip(text: String, id: String = text, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(text, color = if (focused) FG else DIM, fontSize = 12.sp, maxLines = 1, modifier = Modifier
        .remembered("chip:$id").onFocusChanged { focused = it.isFocused }.focusOnHover().clickable { onClick() }
        .background(if (focused) CYAN.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
        .border(1.dp, if (focused) CYAN else Color.Transparent, RoundedCornerShape(12.dp))
        .padding(horizontal = 14.dp, vertical = 10.dp))
}

@Composable
fun AppsGrid(apps: List<AppEntry>, dock: List<String>, onLaunch: (AppEntry) -> Unit, onToggle: (AppEntry) -> Unit) {
    var longFired by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp)) {
        Text("OK = open  ·  hold OK = add to / remove from dock  (● = in dock)", color = DIM, fontSize = 11.sp, modifier = Modifier.padding(bottom = 10.dp))
        LazyVerticalGrid(GridCells.Adaptive(150.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(apps, key = { it.id }) { a ->
                Tile(a, label = a.label, badge = a.id in dock, onKey = { e ->
                    when {
                        !e.isOk() -> false
                        e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 1 -> { longFired = true; onToggle(a); true }
                        e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 1 -> true
                        e.type == KeyEventType.KeyUp && longFired -> { longFired = false; true }
                        else -> false
                    }
                }) { onLaunch(a) }
            }
        }
    }
}

private fun f1(v: Float) = String.format(Locale.US, "%.1f", v)

private fun pct(v: Float) = "${(v * 100).toInt()}%"

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Column(modifier.background(Color.Black.copy(alpha = Theme.panelAlpha), RoundedCornerShape(18.dp))
        .border(1.dp, CYAN.copy(alpha = 0.15f), RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }

@Composable
fun Adjust(label: String, value: String, dec: () -> Unit, compact: Boolean = false, fill: Float? = null, onOk: (() -> Unit)? = null,
           mini: String? = null, onHold: (() -> Unit)? = null, inc: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val editing = M5.editing == label
    val barColor = CYAN.copy(alpha = 0.10f)
    Row(
        Modifier.fillMaxWidth().remembered("adj:$label").focusProperties { left = FocusRequester.Cancel; right = FocusRequester.Cancel }.onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused && M5.editing == label) M5.editing = null
                if (it.isFocused && M5.carryEdit) { M5.carryEdit = false; M5.editing = label }
            }.focusOnHover()
            .onPreviewKeyEvent { e ->
                val down = e.type == KeyEventType.KeyDown
                when {
                    // a long press opens what the row offers beyond its value (the expression of a setting)
                    onHold != null && down && e.isOk() && e.nativeKeyEvent.repeatCount == 1 -> { M5.editing = null; FocusMem.swallowOk = true; onHold(); true }
                    onHold != null && e.isOk() && !down && FocusMem.swallowOk -> true
                    // the row's own OK action: a press normally, a hold when OK is what starts editing
                    onOk != null && M5.editLock && down && e.isOk() && e.nativeKeyEvent.repeatCount == 1 -> { M5.editing = null; onOk(); true }
                    !M5.editLock -> if (!down) false else when {
                        e.key == Key.DirectionLeft -> { dec(); true }
                        e.key == Key.DirectionRight -> { inc(); true }
                        onOk != null && e.isOk() -> { if (e.nativeKeyEvent.repeatCount == 0) onOk(); true }
                        else -> false
                    }
                    // ↑ ↓ leave the value field and go straight to the neighbouring one, which is ready to edit
                    editing && (e.key == Key.DirectionUp || e.key == Key.DirectionDown) -> { if (down) { M5.editing = null; M5.carryEdit = true }; false }
                    editing -> {   // ← → change only this row until Back / OK
                        if (down) when {
                            e.key == Key.DirectionLeft -> dec()
                            e.key == Key.DirectionRight -> inc()
                            e.key == Key.Back || (e.isOk() && e.nativeKeyEvent.repeatCount == 0) -> M5.editing = null
                        }
                        true
                    }
                    down && e.isOk() && e.nativeKeyEvent.repeatCount == 0 -> { M5.carryEdit = false; M5.editing = label; true }
                    e.isOk() -> true
                    else -> false
                }
            }
            .focusable()
            .background(if (editing) AMBER.copy(alpha = 0.14f) else if (focused) CYAN.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(10.dp))
            .border(if (editing) 2.dp else 1.dp, if (editing) AMBER else if (focused) CYAN else Color.Transparent, RoundedCornerShape(10.dp))
            .drawBehind {
                // how far along its range the value is
                if (fill != null) drawRoundRect(barColor, size = androidx.compose.ui.geometry.Size(size.width * fill.coerceIn(0f, 1f), size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
            }
            // a mini row is one axis of a group (X, Y, Z under "Position"): indented, shorter and in smaller type
            .then(if (mini != null) Modifier.padding(start = 18.dp) else Modifier)
            .padding(horizontal = if (mini != null) 8.dp else 12.dp, vertical = if (mini != null) 3.dp else 8.dp)
    ) {
        val size = if (mini != null) 10.sp else 12.sp
        // the name keeps at least half the row; a long value shortens with "…" instead of squeezing the name
        Text(mini ?: label, color = if (focused || editing) FG else DIM, fontSize = size, maxLines = if (compact || mini != null) 1 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = true))
        Text(if (editing) "◂ $value ▸" else if (focused && M5.editLock && !compact && mini == null) "$value · OK to edit" else "‹ $value ›", color = if (editing) AMBER else FG, fontSize = size,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp).widthIn(max = if (compact || mini != null) 120.dp else 260.dp))
    }
}

/** The name of a group of X / Y / Z rows: written larger than the axes under it, which keeps long lists easy to scan. */
@Composable
fun AxisGroup(name: String, summary: String = "") {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = FG, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (summary.isNotEmpty()) Text(summary, color = DIM, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
fun Row_(label: String, selected: Boolean, id: String = label, side: String = "", small: Boolean = false, category: Boolean = false, onHold: (() -> Unit)? = null, onFocus: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    Text(label, color = if (focused || selected || category) FG else DIM, fontSize = if (category) 13.sp else if (small) 10.sp else 12.sp,
        fontWeight = if (category) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth()
            .remembered("row:$id")
            .focusProperties { if (!side.contains('L')) left = FocusRequester.Cancel; if (!side.contains('R')) right = FocusRequester.Cancel }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) { M5.carryEdit = false; onFocus() } }.focusOnHover()
            // a hold of OK (or of the M5 click) runs onHold instead, and the release that follows is swallowed
            .then(if (onHold == null) Modifier else Modifier.onPreviewKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                when {
                    ok && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 1 -> { held = true; onHold(); true }
                    ok && e.type == KeyEventType.KeyDown && held -> true
                    ok && e.type == KeyEventType.KeyUp && held -> { held = false; true }
                    else -> false
                }
            })
            .clickable { onClick() }
            .background(if (focused) CYAN.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(10.dp))
            .border(1.dp, if (focused) CYAN else if (selected) CYAN.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(10.dp))
            .padding(horizontal = if (small) 14.dp else 12.dp, vertical = if (small) 3.dp else 7.dp))
}

/** Mouse / M5 air-mouse support: hovering an item focuses it (same glow as D-pad focus). */
fun Modifier.focusOnHover(): Modifier = composed {
    val fr = remember { FocusRequester() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var hovered by remember { mutableStateOf(false) }
    DisposableEffect(fr) { onDispose { if (M5.hoverTarget === fr) M5.hoverTarget = null } }
    this.onGloballyPositioned { origin = it.positionInWindow() }
        .drawWithContent {   // M5 mouse-over state: violet outline under the cursor
            drawContent()
            if (hovered) drawRoundRect(VIOLET.copy(alpha = 0.85f), style = Stroke(2.dp.toPx()), cornerRadius = CornerRadius(12.dp.toPx()))
        }
        .focusRequester(fr).pointerInput(Unit) {
        coroutineScope {
            var job: Job? = null
            var inside = false
            var pos = Offset.Zero
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    val p = e.changes.firstOrNull()?.position ?: continue
                    pos = origin + p
                    when (e.type) {
                        PointerEventType.Enter -> {
                            inside = true; hovered = true; job?.cancel()
                            M5.hoverTarget = fr; M5.hoverAt = SystemClock.uptimeMillis()
                            job = launch {
                                delay(M5.dwellMs.toLong())
                                val moved = (pos - M5.lastHoverPos).getDistance()
                                if (inside && M5.hoverSelect && M5.editing == null && moved > M5.deadzone) {
                                    runCatching { fr.requestFocus() }; M5.lastHoverPos = pos
                                }
                            }
                        }
                        PointerEventType.Exit -> { inside = false; hovered = false; job?.cancel(); if (M5.hoverTarget === fr) M5.hoverTarget = null }
                        else -> {}
                    }
                }
            }
        }
    }
}

/** Preset at (saved index + delta), saving the new index. */
fun toneAt(ctx: Context, delta: Int): Tone {
    val sp = ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE)
    val list = Tones.loadPresets(ctx); if (list.isEmpty()) return Tone()
    val i = ((sp.getInt("toneIdx", 0) + delta) % list.size + list.size) % list.size
    sp.edit().putInt("toneIdx", i).apply(); return list[i]
}

@Composable
fun M5Panel(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val devices = remember {
        InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter { !it.isVirtual }.map { d ->
            val kinds = buildList {
                if (d.supportsSource(InputDevice.SOURCE_MOUSE)) add("mouse")
                if (d.supportsSource(InputDevice.SOURCE_KEYBOARD)) add("keys")
                if (d.supportsSource(InputDevice.SOURCE_DPAD)) add("d-pad")
            }
            d.name + "  (" + kinds.joinToString() + ")"
        }
    }
    val first = remember { FocusRequester() }
    Overlay("M5 AIR MOUSE · TEST & CALIBRATE", "Move and press buttons to see them live · Back = close") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel(Modifier.width(330.dp)) {
                Text("LIVE", color = CYAN, fontSize = 11.sp, letterSpacing = 1.4.sp)
                Text("Pointer  x ${M5.pointer.x.toInt()}  y ${M5.pointer.y.toInt()}", color = FG, fontSize = 12.sp)
                Text("Speed  ${M5.speed.toInt()} px/s", color = FG, fontSize = 12.sp)
                Text("Hover delay ${M5.dwellMs} ms · jitter guard ${M5.deadzone.toInt()} px", color = DIM, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Text("LAST BUTTONS", color = CYAN, fontSize = 11.sp, letterSpacing = 1.4.sp)
                M5.keyLog.forEach { Text(it, color = DIM, fontSize = 10.sp, maxLines = 1) }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.focusRequester(first)) {
                    Chip(if (running) "Hold the M5 still…" else "🎯 Auto-calibrate (hold still 3 s)") {
                        if (!running) scope.launch {
                            running = true; result = ""
                            val pts = ArrayList<Offset>()
                            repeat(60) { pts.add(M5.pointer); delay(50) }
                            val cx = pts.map { it.x }.average().toFloat(); val cy = pts.map { it.y }.average().toFloat()
                            val jitter = pts.maxOf { (it - Offset(cx, cy)).getDistance() }
                            M5.deadzone = (jitter * 1.5f + 6f).coerceIn(6f, 80f)
                            M5.dwellMs = if (jitter > 20f) 380 else if (jitter > 8f) 300 else 240
                            M5.save(ctx); running = false
                            result = "Hand tremor ≈ ${jitter.toInt()} px → jitter guard ${M5.deadzone.toInt()} px, hover delay ${M5.dwellMs} ms (saved)"
                        }
                    }
                }
                if (result.isNotEmpty()) Text(result, color = AMBER, fontSize = 11.sp)
            }
            Panel(Modifier.width(380.dp)) {
                Text("BUTTON MAP", color = CYAN, fontSize = 11.sp, letterSpacing = 1.4.sp)
                listOf(
                    "OK / left click" to (if (M5.clickAsOk) "OK on the selected item (hold = hold-OK)" else "click where the cursor is"),
                    "Right click" to M5.rightClick,
                    "Arrows (M5 keyboard)" to "move selection · change a locked setting",
                    "OK on a setting" to (if (M5.editLock) "lock it for editing · Back/OK = done" else "← → change directly"),
                    "Back" to "back · leave edit · close panels",
                    "Home / long-press Home" to "Home / Starshine quick panel",
                    "Menu" to M5.menuKey,
                    "Mic / Search" to "Starshine voice command",
                    "Volume" to "system volume",
                    "Moving the M5" to (if (M5.hoverSelect) "selects after resting ${M5.dwellMs} ms" else "pointer only, no selection")
                ).forEach { (k, v) ->
                    Row { Text(k, color = FG, fontSize = 11.sp, modifier = Modifier.width(150.dp)); Text(v, color = DIM, fontSize = 11.sp) }
                }
                Spacer(Modifier.height(8.dp))
                Text("DETECTED INPUT DEVICES", color = CYAN, fontSize = 11.sp, letterSpacing = 1.4.sp)
                devices.forEach { Text(it, color = if (it.startsWith("M5")) FG else DIM, fontSize = 10.sp, maxLines = 1) }
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
}

@Composable
fun HomeSwitch() {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE) }
    var on by remember { mutableStateOf(sp.getBoolean("homeOverride", true)) }
    fun flip() { on = !on; sp.edit().putBoolean("homeOverride", on).apply() }
    Adjust("Home button opens Starshine", if (on) "on" else "off (Google TV)", { flip() }) { flip() }
}

/** Per-background switch: pulse this background with the playing mood / frequency. */
@Composable
fun ReactSwitch(bg: String) {
    val ctx = LocalContext.current
    val p = remember { Prefs(ctx) }
    var on by remember(bg) { mutableStateOf(p.reactive(bg)) }
    fun flip() { on = !on; p.setReactive(bg, on) }
    Adjust("Animate with sound / moods", if (on) "on" else "off", { flip() }) { flip() }
}

/**
 * The look modulated by the current sound: beat of a frequency (capped at 2 Hz, photosensitivity-safe) or the swell of an ambient sound.
 * Plain function, called by the render threads. Returns the plain style when nothing is playing.
 */
fun soundStyleAt(style: BgStyle, t: Float): BgStyle {
    if (Sound.current.isEmpty() || Sound.pausedByOther) return style
    val tone = Sound.toneNow
    val rate = if (tone != null) (if (tone.beat > 0f) tone.beat else 1f).coerceIn(0.1f, 2f) else 0.12f
    val pulse = 0.5f + 0.5f * kotlin.math.sin(2f * Math.PI.toFloat() * rate * t)
    val amt = if (tone != null) tone.volume.coerceIn(0.1f, 1f) * 2f else Ambient.volume.coerceIn(0.1f, 1f) * 1.6f
    return style.copy(
        glow = style.glow * (1f + 0.6f * amt * pulse),
        speed = style.speed * (1f + 0.35f * amt * pulse),
        size = style.size * (1f + 0.08f * amt * pulse),
        brightness = style.brightness * (1f + 0.15f * amt * pulse)
    )
}

/** Starfield nebula backdrop: built-in nebulae + your own images, brightness. Changes show live. */
@Composable
fun NebulaRows() {
    val ctx = LocalContext.current
    val p = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val options = remember { Nebula.PRESETS + Nebula.photos(ctx) + mediaFiles(ctx).filter { it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp") }.map { "file:" + it.name } }
    var sel by remember { mutableStateOf(p.nebula) }
    var a by remember { mutableStateOf(p.nebulaA) }
    fun pick(d: Int) {
        sel = options[(options.indexOf(sel).coerceAtLeast(0) + d + options.size) % options.size]; p.nebula = sel
        scope.launch { Nebula.image = withContext(Dispatchers.Default) { Nebula.load(ctx, sel) } }
    }
    Column {
        Adjust("Nebula backdrop", sel.removePrefix("file:").removePrefix("neb:").substringBeforeLast('.').replace('_', ' '), { pick(-1) }) { pick(+1) }
        Adjust("Nebula brightness", pct(a), { a = (a - 0.05f).coerceAtLeast(0f); p.nebulaA = a; Nebula.alpha = a }) { a += 0.05f; p.nebulaA = a; Nebula.alpha = a }
    }
}

/* ---------------- gestures ---------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GestureList(first: FocusRequester, gestures: Map<String, String>, describe: (String) -> String,
                onEdit: (String) -> Unit, onRecord: () -> Unit, onReset: () -> Unit,
                onRemove: (String) -> Unit = {}, onSave: () -> Unit = {}) {
    LoopColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.focusRequester(first)) { Chip("＋ Add gesture", id = "g:add") { onRecord() } }
                Chip("💾 Save", id = "g:save") { onSave() }
                Chip("↺ Reset all", id = "g:resetall") { onReset() }
            }
        }
        items(gestures.entries.toList(), key = { it.key }) { (p, a) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Row_(Gestures.arrows(p).padEnd(8) + "   " + describe(a), false, id = "g:$p", side = "R", onFocus = {}) { onEdit(p) } }
                Chip("−", id = "g:rm:$p") { onRemove(p) }
            }
        }
        item { Text("How to draw: hold the M5's right button and move it in a shape, then let go (a short right click is still Back). " +
            "No right button? Quick panel → ✍ Draw gesture, move, then click. Shapes are read as strokes in 8 directions: → ← ↑ ↓ ↗ ↘ ↙ ↖ and combinations such as ↓→ or ↗↘.",
            color = DIM, fontSize = 10.sp, modifier = Modifier.padding(8.dp)) }
    }
}

@Composable
fun GestureEditor(first: FocusRequester, current: String, choices: List<String>, describe: (String) -> String,
                  onSet: (String) -> Unit, onDelete: () -> Unit) {
    var code by remember { mutableStateOf(current) }
    fun step(d: Int) { val i = choices.indexOf(code).coerceAtLeast(0); code = choices[(i + d + choices.size) % choices.size]; onSet(code) }
    LoopColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        item { Box(Modifier.focusRequester(first)) { Adjust("Action", describe(code), { step(-1) }) { step(+1) } } }
        item { Text("Choose: Home, Quick panel, Open apps, Voice, Screensaver, next/previous background, stop sound, any mood or frequency, any background, any app. Hold ← → to scroll fast.",
            color = DIM, fontSize = 10.sp, modifier = Modifier.padding(8.dp)) }
        item { Row_("🗑 Delete this gesture", false, id = "g:delete", onFocus = {}) { onDelete() } }
    }
}

/** Phone only: black out the phone's own screen while Starshine runs on the glasses. */
@Composable
fun PhoneOffRow() {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE) }
    var on by remember { mutableStateOf(sp.getBoolean("phoneOff", false)) }
    fun flip() { on = !on; sp.edit().putBoolean("phoneOff", on).apply() }
    Adjust("Phone screen black after 30 s (phones with glasses)", if (on) "on" else "off", { flip() }) { flip() }
}

/** Voice setup: shows why recognition fails, lets you pick the recognizer, and tests it live. */
@Composable
fun VoiceSetup() {
    val ctx = LocalContext.current
    val services = remember { Voice.services(ctx) }
    var chosen by remember { mutableStateOf(Voice.chosen(ctx)) }
    var result by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(Voice.hasMic(ctx)) }
    val test = remember { Voice(ctx, onPartial = { result = "Hearing: $it" }, onResult = { t, err -> running = false; Sound.release(); result = if (t != null) "Heard: \"$t\"" else "Failed: $err" }) }
    DisposableEffect(Unit) { onDispose { test.stop() } }
    LaunchedEffect(Unit) { while (true) { granted = Voice.hasMic(ctx); delay(1000) } }
    val options = listOf("") + services
    fun pick(d: Int) { chosen = options[(options.indexOf(chosen).coerceAtLeast(0) + d + options.size) % options.size]; Voice.setChosen(ctx, chosen) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row_(if (result.isEmpty()) (if (running) "Listening… say \"open Jellyfin\"" else "🎤 Test: OK, then say \"open Jellyfin\"") else "🎤 $result  (OK = test again)", false, id = "voice:test", onFocus = {}) {
            if (!granted) (ctx as? Activity)?.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
            else { result = ""; running = true; Sound.hold(); test.listen() }
        }
        Adjust("Recognizer", if (chosen.isEmpty()) "Automatic" else Voice.shortName(chosen), { pick(-1) }) { pick(+1) }
        Text("Microphone permission: " + if (granted) "granted" else "not granted (OK on Test to allow)", color = if (granted) GREEN else RED, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp))
        Text("Audio inputs: " + Voice.inputs(ctx).ifEmpty { "none reported (use the remote's mic button)" }, color = DIM, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp))
        Text("Installed recognizers: " + services.joinToString { Voice.shortName(it) }.ifEmpty { "none" }, color = DIM, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp))
        Text("Automatic tries the Google TV recognizer first, then the others. The one that works is remembered.", color = DIM, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp))
    }
}

/** Starshine cursor: a small glowing cyan dot with a white core (system arrow in other apps). */
fun glowDotIcon(ctx: Context): android.view.PointerIcon {
    // at most 40 px: the pod's pointer layer is about 44 px wide, so a 56 px icon had its glow cut off on the right
    val size = 40
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp); val r = size / 2f
    val glow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        shader = android.graphics.RadialGradient(r, r, r, intArrayOf(0xEE5EE7FF.toInt(), 0x775EE7FF, 0x00B18CFF), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
    }
    c.drawCircle(r, r, r, glow)
    c.drawCircle(r, r, r * 0.22f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
    return android.view.PointerIcon.create(bmp, r, r)
}

@Composable
fun CursorSwitch() {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE) }
    var on by remember { mutableStateOf(sp.getBoolean("glowCursor", true)) }
    fun flip() { on = !on; sp.edit().putBoolean("glowCursor", on).apply() }
    Adjust("Cursor style in Starshine", if (on) "glowing dot" else "system arrow", { flip() }) { flip() }
}
