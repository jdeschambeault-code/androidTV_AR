package com.starshine.launcher

import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import android.provider.Settings
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Live video from the KY UFO drone (network FLOW-UFO-...). View only: this code never sends flight commands.
 * While connected the pod is on the drone's Wi-Fi, so home network, internet and PC adb are unavailable until you leave.
 */
object Drone {
    /** Start of the drone's Wi-Fi name, changeable for other drones. */
    var ssidPrefix by mutableStateOf("FLOW-UFO")
    /** A stream address the user typed; empty = find it automatically. */
    var customUrl by mutableStateOf("")
    private var found = ""
    val url: String get() = customUrl.ifEmpty { found.ifEmpty { URL } }
    val host: String get() = runCatching { Uri.parse(url).host }.getOrNull() ?: HOST
    val port: Int get() = runCatching { Uri.parse(url).port }.getOrNull()?.takeIf { it > 0 } ?: 554
    const val HOST = "192.168.1.1"
    const val RTSP_PORT = 7070
    private const val CONTROL_PORT = 7099
    const val URL = "rtsp://$HOST:$RTSP_PORT/webcam"

    var menuOn by mutableStateOf(true)
    var immersive by mutableStateOf(false)
    var dim by mutableFloatStateOf(0f)        // AR dim for the drone view only
    var fill by mutableStateOf(true)
    var vr by mutableStateOf(false)            // side-by-side view for each eye
    var vrGap by mutableFloatStateOf(0f)       // moves the two pictures toward or away from each other
    var zoom by mutableFloatStateOf(1f)
    var offX by mutableFloatStateOf(0f)
    var offY by mutableFloatStateOf(0f)

    var status by mutableStateOf("Not connected")
    var linked by mutableStateOf(false)       // the drone's video port answers
    var live by mutableStateOf(false)         // pictures are arriving
    var joining by mutableStateOf(false)
    var attempt by mutableIntStateOf(0)
    var failures = 0
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var heartbeatOn = false

    private fun sp(ctx: Context) = ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE)
    /** Only the KY UFO family gets its heartbeat. */
    val isKyUfo get() = host == HOST
    fun load(ctx: Context) {
        val p = sp(ctx); menuOn = p.getBoolean("droneMenu", true); dim = p.getFloat("droneDim", 0f); fill = p.getBoolean("droneFill", true)
        vr = p.getBoolean("droneVr", false); vrGap = p.getFloat("droneVrGap", 0f); ssidPrefix = p.getString("droneSsid", "FLOW-UFO")!!; customUrl = p.getString("droneUrl", "")!!; zoom = p.getFloat("droneZoom", 1f); offX = p.getFloat("droneX", 0f); offY = p.getFloat("droneY", 0f)
    }
    fun save(ctx: Context) {
        sp(ctx).edit().putBoolean("droneMenu", menuOn).putFloat("droneDim", dim).putBoolean("droneFill", fill)
            .putBoolean("droneVr", vr).putFloat("droneVrGap", vrGap).putString("droneSsid", ssidPrefix).putString("droneUrl", customUrl).putFloat("droneZoom", zoom).putFloat("droneX", offX).putFloat("droneY", offY).apply()
    }

    fun log(ctx: Context, s: String) {
        Log.i("StarshineDrone", s)
        runCatching { java.io.File(ctx.getExternalFilesDir(null), "drone.log").appendText(java.util.Date().toString() + "  " + s + "\n") }
    }

    /** Ask Android to join the drone's network. It's released again by [leave], which returns the pod to home Wi-Fi. */
    fun join(ctx: Context) {
        if (callback != null || linked) return
        if (Build.VERSION.SDK_INT < 29) { status = "Join $ssidPrefix in Wi-Fi settings"; return }
        joining = true; status = "Joining the drone's Wi-Fi… accept the prompt if one appears"
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val spec = WifiNetworkSpecifier.Builder().setSsidPattern(PatternMatcher(ssidPrefix, PatternMatcher.PATTERN_PREFIX)).build()
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { cm.bindProcessToNetwork(n); joining = false; status = "On the drone's Wi-Fi, looking for video…"; log(ctx, "joined drone network") }
            override fun onUnavailable() { joining = false; callback = null; status = "Couldn't join automatically. Use Wi-Fi settings below"; log(ctx, "join unavailable") }
            override fun onLost(n: Network) { cm.bindProcessToNetwork(null); joining = false; linked = false; live = false; status = "Drone Wi-Fi lost"; log(ctx, "network lost") }
        }
        callback = cb
        runCatching { cm.requestNetwork(req, cb, 45_000) }.onFailure { joining = false; callback = null; status = "Couldn't join: " + it.message; log(ctx, "join error " + it) }
    }

    /** Drop the drone network. Android returns to the saved home Wi-Fi by itself. */
    fun leave(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        runCatching { cm.bindProcessToNetwork(null) }
        callback?.let { c -> runCatching { cm.unregisterNetworkCallback(c) } }
        callback = null; joining = false; linked = false; live = false; immersive = false; failures = 0; heartbeatOn = false
        status = "Not connected"
    }

    /**
     * Looks for an RTSP camera: on the Wi-Fi gateway and the addresses these drones commonly use, asking each common path
     * whether it has a stream. There's no standard, so a drone that doesn't use RTSP needs its own support.
     */
    fun probe(ctx: Context): Boolean {
        if (customUrl.isNotEmpty()) {
            val h = host; val p = port
            return runCatching { Socket().use { it.connect(InetSocketAddress(h, p), 1500) }; true }.getOrDefault(false)
        }
        @Suppress("DEPRECATION") val gw = runCatching {
            val g = ctx.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java).dhcpInfo.gateway
            "${g and 255}.${g shr 8 and 255}.${g shr 16 and 255}.${g shr 24 and 255}"
        }.getOrDefault("")
        val hosts = listOf(HOST, gw, "192.168.0.1", "192.168.10.1", "192.168.169.1", "172.16.10.1").filter { it.isNotEmpty() && it != "0.0.0.0" }.distinct()
        for (h in hosts) for (p in listOf(RTSP_PORT, 554, 8554)) {
            val open = runCatching { Socket().use { it.connect(InetSocketAddress(h, p), 500) }; true }.getOrDefault(false)
            if (!open) continue
            if (h == HOST && p == RTSP_PORT) { found = URL; return true }   // the KY UFO family answers here
            for (path in listOf("/webcam", "/live", "/", "/11", "/stream", "/h264")) {
                val u = "rtsp://$h:$p$path"
                val ok = runCatching {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(h, p), 800); s.soTimeout = 1200
                        s.getOutputStream().write("DESCRIBE $u RTSP/1.0\r\nCSeq: 1\r\nAccept: application/sdp\r\n\r\n".toByteArray())
                        val b = ByteArray(256); val n = s.getInputStream().read(b)
                        n > 0 && String(b, 0, n).contains(" 200 ")
                    }
                }.getOrDefault(false)
                if (ok) { found = u; log(ctx, "found a stream at $u"); return true }
            }
        }
        return false
    }

    /** The same "viewer here" heartbeat the other KY UFO tools send (not a flight command). */
    suspend fun heartbeat(ctx: Context) = withContext(Dispatchers.IO) {
        heartbeatOn = true
        runCatching {
            DatagramSocket().use { s ->
                val addr = InetAddress.getByName(HOST)
                while (isActive && heartbeatOn && linked) {
                    s.send(DatagramPacket(byteArrayOf(1, 1), 2, addr, CONTROL_PORT))
                    delay(1000)
                }
            }
        }.onFailure { log(ctx, "heartbeat stopped: $it") }
    }

    /** Startup packet used by those tools, tried only if video doesn't start without it. */
    fun wake(ctx: Context) {
        Thread { runCatching { DatagramSocket().use { it.send(DatagramPacket(byteArrayOf(8, 1), 2, InetAddress.getByName(HOST), CONTROL_PORT)) }; log(ctx, "sent startup packet") } }.start()
    }
}

@Composable
private fun DroneVideo() {
    val ctx = LocalContext.current
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    val tryNumber = Drone.attempt
    val stream = remember { arrayOfNulls<DroneStream>(1) }
    val texture = remember { arrayOfNulls<SurfaceTexture>(1) }
    var ready by remember { mutableStateOf(false) }
    // exactly one connection at a time: the camera accepts a single viewer
    LaunchedEffect(tryNumber, ready) {
        val st = texture[0]
        if (!ready || st == null) return@LaunchedEffect
        stream[0]?.stop(); delay(400)
        stream[0] = DroneStream(ctx.applicationContext, Drone.url, Drone.host, Drone.port, Surface(st), false, Drone.isKyUfo,
            onSize = { w, h -> if (h > 0) aspect = w.toFloat() / h },
            onLive = { Drone.live = true; Drone.failures = 0; Drone.status = "Live"; Drone.log(ctx, "live") },
            onFail = { why -> Drone.live = false; Drone.failures++; Drone.status = "No picture yet (try ${Drone.failures}): $why" }
        ).also { it.start() }
    }
    DisposableEffect(Unit) { onDispose { stream[0]?.stop(); if (DroneRecorder.recording) DroneRecorder.stop { } } }
    val m = if (Drone.fill) Modifier.fillMaxSize() else Modifier.fillMaxHeight().aspectRatio(aspect, matchHeightConstraintsFirst = true)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // TextureView, because zoom and position are applied to the view
        AndroidView(factory = { c ->
            TextureView(c).apply {
                isFocusable = false
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { texture[0] = st; ready = true }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { stream[0]?.stop(); texture[0] = null; ready = false; return true }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        }, modifier = m.graphicsLayer {
            scaleX = Drone.zoom; scaleY = Drone.zoom
            translationX = Drone.offX * size.width; translationY = Drone.offY * size.height
        })
    }
}

/** Replaces the background while the Drone tab is open: no background, its own AR dim. */
@Composable
fun DroneStage() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            // once linked, the video stream itself shows whether the drone is there: the camera takes one connection at a time
            if (!Drone.linked && withContext(Dispatchers.IO) { Drone.probe(ctx) }) { Drone.linked = true; Drone.failures = 0; Drone.status = "Drone found, starting video…"; Drone.log(ctx, "RTSP port answered") }
            if (Drone.linked && Drone.failures >= 12) { Drone.linked = false; Drone.live = false; Drone.status = "Lost the drone"; Drone.log(ctx, "starting over after 12 failures") }
            delay(2500)
        }
    }
    LaunchedEffect(Drone.linked) { if (Drone.linked && Drone.isKyUfo) Drone.heartbeat(ctx) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (Drone.linked) DroneVideo()
        if (Drone.dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = Drone.dim.coerceIn(0f, 1f))))
    }
}

/** Settings shared by the Drone tab and Settings → Drone. */
@Composable
fun DroneRows(showOptOut: Boolean) {
    val ctx = LocalContext.current
    fun pct(v: Float) = "${(v * 100).toInt()}%"
    fun change(block: () -> Unit) { block(); Drone.save(ctx) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (showOptOut) Adjust("Show the Drone menu", if (Drone.menuOn) "yes" else "no", { change { Drone.menuOn = !Drone.menuOn } }) { change { Drone.menuOn = !Drone.menuOn } }
        Row_("⛶ Immersive view (any button returns)", false, id = "dr:imm", onFocus = {}) { Drone.immersive = true }
        if (!showOptOut) {
            var rec by remember { mutableStateOf(DroneRecorder.recording) }
            var saved by remember { mutableStateOf("") }
            Row_(if (rec) "⏹ Stop recording" else "⏺ Record video", rec, id = "dr:rec", onFocus = {}) {
                if (rec) { rec = false; Thread { DroneRecorder.stop { w -> saved = w ?: "Nothing recorded" } }.start() }
                else if (Drone.live) { DroneRecorder.start(ctx); rec = true; saved = "" } else saved = "Wait for the picture first"
            }
            if (saved.isNotEmpty()) Text(saved, color = DIM, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 12.dp))
        }
        Adjust("AR dim, drone only", pct(Drone.dim), { change { Drone.dim = (Drone.dim - 0.05f).coerceAtLeast(0f) } }, compact = !showOptOut) { change { Drone.dim = (Drone.dim + 0.05f).coerceAtMost(1f) } }
        Adjust("VR dual view", if (Drone.vr) "on" else "off", { change { Drone.vr = !Drone.vr } }, compact = !showOptOut) { change { Drone.vr = !Drone.vr } }
        if (Drone.vr) Adjust("VR eye distance", pct(Drone.vrGap), { change { Drone.vrGap = (Drone.vrGap - 0.02f).coerceAtLeast(-0.3f) } }, compact = !showOptOut) { change { Drone.vrGap = (Drone.vrGap + 0.02f).coerceAtMost(0.3f) } }
        Adjust("Picture", if (Drone.fill) "Fill screen" else "Keep shape", { change { Drone.fill = !Drone.fill } }, compact = !showOptOut) { change { Drone.fill = !Drone.fill } }
        Adjust("Size", pct(Drone.zoom), { change { Drone.zoom = (Drone.zoom - 0.05f).coerceAtLeast(0.2f) } }, compact = !showOptOut) { change { Drone.zoom = (Drone.zoom + 0.05f).coerceAtMost(4f) } }
        Adjust("Position ←→", pct(Drone.offX), { change { Drone.offX -= 0.02f } }, compact = !showOptOut) { change { Drone.offX += 0.02f } }
        Adjust("Position ↕", pct(Drone.offY), { change { Drone.offY -= 0.02f } }, compact = !showOptOut) { change { Drone.offY += 0.02f } }
        Row_("↺ Reset picture", false, id = "dr:reset", onFocus = {}) { change { Drone.zoom = 1f; Drone.offX = 0f; Drone.offY = 0f; Drone.fill = true } }
        fun ask(title: String, value: String, done: (String) -> Unit) {
            val box = android.widget.EditText(ctx).apply { setText(value); setSingleLine() }
            android.app.AlertDialog.Builder(ctx).setTitle(title).setView(box).setPositiveButton("Save") { _, _ -> done(box.text.toString().trim()); Drone.save(ctx) }.setNegativeButton("Cancel", null).show()
        }
        Row_("Drone Wi-Fi name starts with: " + Drone.ssidPrefix, false, id = "dr:ssid", small = true, onFocus = {}) { ask("Drone Wi-Fi name starts with", Drone.ssidPrefix) { if (it.isNotEmpty()) Drone.ssidPrefix = it } }
        Row_("Stream address: " + Drone.customUrl.ifEmpty { "find automatically" }, false, id = "dr:url", small = true, onFocus = {}) { ask("Stream address (rtsp://…), empty = automatic", Drone.customUrl.ifEmpty { "rtsp://" }) { Drone.customUrl = if (it == "rtsp://") "" else it } }
    }
}

@Composable
fun DroneTab() {
    val ctx = LocalContext.current
    Panel(Modifier.padding(start = 24.dp, bottom = 8.dp).width(256.dp).fillMaxHeight()) {
        Text("DRONE · KY UFO", color = CYAN, fontSize = 12.sp, letterSpacing = 1.5.sp)
        Text(Drone.status, color = if (Drone.live) GREEN else AMBER, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp).fillMaxWidth())
        LoopColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                if (Drone.linked || Drone.joining) Row_("✕ Disconnect, back to home Wi-Fi", false, id = "dr:leave", onFocus = {}) { Drone.leave(ctx) }
                else Row_("▶ Connect to the drone", false, id = "dr:join", onFocus = {}) { Drone.join(ctx) }
            }
            item { Row_("📶 Wi-Fi settings  ›", false, id = "dr:wifi", onFocus = {}) { runCatching { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
            item { DroneRows(showOptOut = false) }
            item { Text("Video only: Starshine never sends flight commands. While connected the pod has no home network or internet. Recordings go to Movies/Starshine. Fly by line of sight.", color = DIM, fontSize = 9.sp, modifier = Modifier.padding(8.dp)) }
        }
    }
}
