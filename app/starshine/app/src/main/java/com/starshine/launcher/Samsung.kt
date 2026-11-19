package com.starshine.launcher

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Controls a Samsung (Tizen) TV over the home network, the way Samsung's phone remote does:
 * a WebSocket on :8002 that accepts remote keys (KEY_HDMI2, KEY_TV, KEY_MENU…), plus :8001 REST to open apps.
 * The TV must have IP Remote on (Settings → Connection → Network → Expert Settings → IP Remote) and,
 * the first time, the user picks "Allow" on the TV; the token it returns is saved.
 */
object SamsungTv {
    var menuOn by mutableStateOf(true)
    /** The TV menu shows only once a TV has been found (Settings → TV finds one), and when not turned off there. */
    val menuShown get() = menuOn && host.isNotEmpty()
    fun forget(ctx: Context) { host = ""; tvName = ""; token = ""; status = ""; save(ctx) }
    var host by mutableStateOf("")
    var tvName by mutableStateOf("")
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    private var token = ""
    val inputNames = Array(4) { "" }          // your names for HDMI 1-4
    const val HUB_APP = "SSTVHub001.TVHub"    // TV Hub (app/tizen-hub) on the TV

    private fun sp(ctx: Context) = ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE)
    fun load(ctx: Context) {
        val p = sp(ctx); menuOn = p.getBoolean("tvMenu", true); host = p.getString("tvHost", "")!!; tvName = p.getString("tvName", "")!!; token = p.getString("tvToken", "")!!
        for (i in 0..3) inputNames[i] = p.getString("tvHdmi${i + 1}", "")!!
    }
    fun save(ctx: Context) {
        val e = sp(ctx).edit().putBoolean("tvMenu", menuOn).putString("tvHost", host).putString("tvName", tvName).putString("tvToken", token)
        for (i in 0..3) e.putString("tvHdmi${i + 1}", inputNames[i])
        e.apply()
    }

    /* ---------- finding the TV ---------- */
    private fun info(ip: String): JSONObject? = runCatching {
        val c = URL("http://$ip:8001/api/v2/").openConnection() as HttpURLConnection
        c.connectTimeout = 700; c.readTimeout = 1500
        c.inputStream.use { JSONObject(it.readBytes().decodeToString()) }.optJSONObject("device")?.takeIf { it.optString("OS") == "Tizen" }
    }.getOrNull()

    /** Looks for a Samsung TV on this device's /24 network. */
    suspend fun discover(ctx: Context, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        status = "Looking for the TV…"
        if (!force && host.isNotEmpty() && info(host) != null) { status = ""; return@withContext true }
        val me = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress
        if (me == null) { status = "No network"; return@withContext false }
        val base = me.substringBeforeLast('.')
        val found = coroutineScope {
            (1..254).chunked(64).firstNotNullOfOrNull { batch ->
                batch.map { n -> async { "$base.$n".let { ip -> info(ip)?.let { ip to it } } } }.awaitAll().filterNotNull().firstOrNull()
            }
        }
        if (found == null) { status = "No Samsung TV found on $base.x"; return@withContext false }
        host = found.first; tvName = found.second.optString("name").ifEmpty { found.second.optString("modelName") }; token = ""
        save(ctx); status = ""; true
    }

    /* ---------- remote keys over the WebSocket ---------- */
    private var conn: Ws? = null

    /** Sends one remote key. Returns null when it worked, otherwise what to tell the user. */
    @Synchronized fun key(ctx: Context, key: String): String? {
        if (host.isEmpty()) return "No TV yet"
        for (attempt in 0..1) {
            val c = conn ?: connect(ctx).let { (ws, err) -> if (ws == null) return err; conn = ws; ws }
            val ok = runCatching {
                c.send(JSONObject().put("method", "ms.remote.control").put("params", JSONObject()
                    .put("Cmd", "Click").put("DataOfCmd", key).put("Option", "false").put("TypeOfRemote", "SendRemoteKey")).toString())
            }.isSuccess
            if (ok) return null
            runCatching { c.close() }; conn = null   // stale connection: reconnect once
        }
        return "The TV stopped answering"
    }

    private fun connect(ctx: Context): Pair<Ws?, String?> {
        val name = Base64.encodeToString("Starshine".toByteArray(), Base64.NO_WRAP)
        val path = "/api/v2/channels/samsung.remote.control?name=$name" + if (token.isNotEmpty()) "&token=$token" else ""
        val ws = runCatching { Ws(host, 8002, path) }.getOrElse { return null to "TV not reachable (is IP Remote on?)" }
        status = if (token.isEmpty()) "On the TV, choose Allow for \"Starshine\"" else "Connecting…"
        ws.timeout(if (token.isEmpty()) 30_000 else 5_000)
        while (true) {
            val msg = runCatching { ws.read() }.getOrNull() ?: run { ws.close(); status = ""; return null to "No answer from the TV. Turn on IP Remote (Settings → Connection → Network → Expert Settings)." }
            val ev = runCatching { JSONObject(msg) }.getOrNull() ?: continue
            when (ev.optString("event")) {
                "ms.channel.connect" -> {
                    ev.optJSONObject("data")?.optString("token")?.takeIf { it.isNotEmpty() }?.let { token = it; save(ctx) }
                    ws.timeout(0); status = ""; return ws to null
                }
                "ms.channel.unauthorized", "ms.channel.timeOut" -> { ws.close(); status = ""; return null to "The TV refused. Allow \"Starshine\" in Settings → General → External Device Manager → Device Connection Manager." }
            }
        }
    }

    /** Opens an app on the TV (REST first, then the socket). */
    fun openApp(ctx: Context, appId: String): String? {
        val rest = runCatching {
            val c = URL("http://$host:8001/api/v2/applications/$appId").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 1500; c.readTimeout = 3000; c.responseCode
        }.getOrDefault(-1)
        if (rest in 200..299) return null
        synchronized(this) {
            val c = conn ?: connect(ctx).let { (ws, err) -> if (ws == null) return err; conn = ws; ws }
            return runCatching {
                c.send(JSONObject().put("method", "ms.channel.emit").put("params", JSONObject().put("event", "ed.apps.launch").put("to", "host")
                    .put("data", JSONObject().put("appId", appId).put("action_type", "DEEP_LINK"))).toString())
            }.fold({ null }, { conn = null; "Couldn't open the app" })
        }
    }

    /** Minimal WebSocket client (text frames only). The TV's certificate is self-signed, so this
     *  connection alone skips certificate checks; it only ever talks to the TV on the home network. */
    private class Ws(host: String, port: Int, path: String) {
        private val s: SSLSocket
        private val inp: InputStream
        private val out: OutputStream
        init {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            })
            val f = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }.socketFactory
            s = f.createSocket() as SSLSocket
            s.connect(InetSocketAddress(host, port), 3000); s.soTimeout = 5000
            inp = s.inputStream; out = s.outputStream
            val key = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
            out.write(("GET $path HTTP/1.1\r\nHost: $host:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray()); out.flush()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) { val b = inp.read(); if (b < 0) error("closed"); head.append(b.toChar()) }
            if (!head.startsWith("HTTP/1.1 101")) { s.close(); error("refused: " + head.lineSequence().first()) }
        }
        fun timeout(ms: Int) { s.soTimeout = ms }
        private fun readN(n: Int) = ByteArray(n).also { b -> var g = 0; while (g < n) { val r = inp.read(b, g, n - g); if (r < 0) error("closed"); g += r } }
        /** Next text message, or null when the TV closed the socket. */
        fun read(): String? {
            while (true) {
                val h = readN(2); val op = h[0].toInt() and 0x0F
                var len = (h[1].toInt() and 0x7F).toLong()
                if (len == 126L) len = readN(2).let { ((it[0].toInt() and 0xFF) shl 8 or (it[1].toInt() and 0xFF)).toLong() }
                else if (len == 127L) len = readN(8).fold(0L) { a, b -> (a shl 8) or (b.toLong() and 0xFF) }
                val mask = if (h[1].toInt() and 0x80 != 0) readN(4) else null
                val body = readN(len.toInt()); if (mask != null) for (i in body.indices) body[i] = (body[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (op) { 1 -> return body.decodeToString(); 8 -> return null; 9 -> frame(10, body) }
            }
        }
        fun send(text: String) = frame(1, text.toByteArray())
        @Synchronized private fun frame(op: Int, data: ByteArray) {
            val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
            val hdr = java.io.ByteArrayOutputStream()
            hdr.write(0x80 or op)
            when { data.size < 126 -> hdr.write(0x80 or data.size); data.size < 65536 -> { hdr.write(0x80 or 126); hdr.write(data.size shr 8); hdr.write(data.size and 0xFF) }
                   else -> { hdr.write(0x80 or 127); for (i in 7 downTo 0) hdr.write(((data.size.toLong() shr (8 * i)) and 0xFF).toInt()) } }
            hdr.write(mask)
            out.write(hdr.toByteArray()); out.write(ByteArray(data.size) { (data[it].toInt() xor mask[it % 4].toInt()).toByte() }); out.flush()
        }
        fun close() = runCatching { s.close() }
    }
}

/** The TV tab: switch the Samsung TV's input, open its settings, and so on, from the Android remote. */
@Composable
fun SamsungTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var said by androidx.compose.runtime.remember { mutableStateOf("") }
    fun press(key: String, done: String) = scope.launch {
        SamsungTv.busy = true
        val err = withContext(Dispatchers.IO) { SamsungTv.key(ctx, key) }
        SamsungTv.busy = false; said = err ?: done
    }
    fun ask(title: String, value: String, done: (String) -> Unit) {
        val box = android.widget.EditText(ctx).apply { setText(value); setSingleLine() }
        android.app.AlertDialog.Builder(ctx).setTitle(title).setView(box).setPositiveButton("Save") { _, _ -> done(box.text.toString().trim()); SamsungTv.save(ctx) }.setNegativeButton("Cancel", null).show()
    }
    LaunchedEffect(Unit) { if (SamsungTv.host.isEmpty()) SamsungTv.discover(ctx) }
    Panel(Modifier.padding(start = 24.dp, bottom = 8.dp).width(256.dp).fillMaxHeight()) {
        Text("SAMSUNG TV", color = CYAN, fontSize = 12.sp, letterSpacing = 1.5.sp)
        val head = when {
            SamsungTv.status.isNotEmpty() -> SamsungTv.status
            SamsungTv.host.isEmpty() -> "No TV found yet"
            else -> SamsungTv.tvName.replace("&quot;", "\"") + " · " + SamsungTv.host
        }
        Text(head, color = if (SamsungTv.status.isEmpty() && SamsungTv.host.isNotEmpty()) GREEN else AMBER, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp).fillMaxWidth())
        if (said.isNotEmpty()) Text(said, color = DIM, fontSize = 9.sp, modifier = Modifier.padding(bottom = 4.dp).fillMaxWidth())
        LoopColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { Row_("📺 TV (antenna)", false, id = "tv:tv", onFocus = {}) { press("KEY_TV", "TV") } }
            for (i in 0..3) item {
                val n = SamsungTv.inputNames[i]
                Row_("⮕ " + (if (n.isNotEmpty()) "$n  (HDMI ${i + 1})" else "HDMI ${i + 1}"), false, id = "tv:hdmi${i + 1}", onFocus = {}) { press("KEY_HDMI${i + 1}", n.ifEmpty { "HDMI ${i + 1}" }) }
            }
            item { Row_("🗂 Samsung source list", false, id = "tv:src", onFocus = {}) { press("KEY_SOURCE", "Source list open on the TV") } }
            item { Row_("⚙ TV settings", false, id = "tv:menu", onFocus = {}) { press("KEY_MENU", "Settings open on the TV") } }
            item { Row_("◼ Open TV Hub", false, id = "tv:hub", onFocus = {}) {
                scope.launch { said = withContext(Dispatchers.IO) { SamsungTv.openApp(ctx, SamsungTv.HUB_APP) } ?: "TV Hub open on the TV" }
            } }
            item { Adjust("TV volume", "–  +", { press("KEY_VOLDOWN", "Volume down") }) { press("KEY_VOLUP", "Volume up") } }
            item { Row_("🔇 Mute", false, id = "tv:mute", onFocus = {}) { press("KEY_MUTE", "Mute") } }
            item { Row_("⏻ Turn the TV off", false, id = "tv:off", onFocus = {}) { press("KEY_POWER", "TV off") } }
            item { Text("NAMES & SETUP", color = DIM, fontSize = 9.sp, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) }
            for (i in 0..3) item {
                Row_("Name HDMI ${i + 1}: " + SamsungTv.inputNames[i].ifEmpty { "–" }, false, id = "tv:name${i + 1}", small = true, onFocus = {}) {
                    ask("Name for HDMI ${i + 1} (e.g. Android TV)", SamsungTv.inputNames[i]) { SamsungTv.inputNames[i] = it }
                }
            }
            item { Row_("↻ Find the TV again", false, id = "tv:find", small = true, onFocus = {}) {
                scope.launch { SamsungTv.discover(ctx, force = true) }
            } }
            item { Text("Needs IP Remote on the TV: Settings → Connection → Network → Expert Settings → IP Remote. The first time, choose Allow on the TV.", color = DIM, fontSize = 9.sp, modifier = Modifier.padding(8.dp)) }
        }
    }
}

/**
 * Settings → TV. Any Samsung Tizen TV (2016 or newer) on the same network works, not one model: it answers on :8001 and
 * takes remote keys on :8002. The TV menu appears in the top bar once one has been found.
 */
@Composable
fun TvSettingsRows() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val head = when {
            SamsungTv.status.isNotEmpty() -> SamsungTv.status
            SamsungTv.host.isEmpty() -> "No TV yet: the TV menu stays hidden until one is found"
            else -> "Found: " + SamsungTv.tvName.replace("&quot;", "\"") + " · " + SamsungTv.host
        }
        Text(head, color = if (SamsungTv.host.isNotEmpty() && SamsungTv.status.isEmpty()) GREEN else AMBER, fontSize = 10.sp, modifier = Modifier.padding(8.dp).fillMaxWidth())
        Row_(if (SamsungTv.host.isEmpty()) "🔍 Find a Samsung TV on the network" else "↻ Find the TV again", false, id = "tvs:find", onFocus = {}) {
            if (!SamsungTv.busy) scope.launch { SamsungTv.busy = true; SamsungTv.discover(ctx, force = true); SamsungTv.busy = false }
        }
        Adjust("Show the TV menu", if (SamsungTv.menuOn) "yes" else "no", { SamsungTv.menuOn = !SamsungTv.menuOn; SamsungTv.save(ctx) }) { SamsungTv.menuOn = !SamsungTv.menuOn; SamsungTv.save(ctx) }
        if (SamsungTv.host.isNotEmpty()) Row_("🗑 Forget this TV (hides the menu)", false, id = "tvs:forget", onFocus = {}) { SamsungTv.forget(ctx) }
        Text("Works with Samsung smart TVs running Tizen (2016 and newer), any model. Turn on IP Remote on the TV: Settings → Connection → Network → Expert Settings → IP Remote. The first time Starshine sends a key, choose Allow on the TV.",
            color = DIM, fontSize = 9.sp, modifier = Modifier.padding(8.dp))
    }
}
