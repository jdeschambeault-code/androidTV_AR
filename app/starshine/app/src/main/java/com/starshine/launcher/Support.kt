package com.starshine.launcher

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Sponsors get a support key that removes the small watermark. A key is "name.signature"; only the public half of the
 * signing key is here, so keys can be checked offline and nothing is sent anywhere.
 */
object Support {
    private const val PUBLIC_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEvkT62CrB8swC4Gwdu/qUCJy7aSHaAv+8nk8vOW/+urfbj1n+8Q2VgzpPFsq8Gjlc4AQFGO8eKt5qho5YBiiadw=="
    const val URL = "github.com/sponsors/jdeschambeault-code"
    var supporter by mutableStateOf("")

    private fun sp(ctx: Context) = ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE)
    fun load(ctx: Context) { supporter = verify(sp(ctx).getString("supportKey", "") ?: "") ?: "" }

    /** The supporter's name if the key is genuine, else null. */
    fun verify(key: String): String? = runCatching {
        val dot = key.trim().lastIndexOf('.')
        if (dot <= 0) return null
        val name = key.trim().substring(0, dot)
        val sig = Base64.decode(key.trim().substring(dot + 1), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, Base64.NO_WRAP)))
        val v = Signature.getInstance("SHA256withECDSA"); v.initVerify(pub); v.update(name.toByteArray())
        if (v.verify(sig)) name else null
    }.getOrNull()

    fun enter(ctx: Context, key: String): Boolean {
        val name = verify(key) ?: return false
        sp(ctx).edit().putString("supportKey", key.trim()).apply(); supporter = name
        return true
    }
}

/** Dim on purpose: a faint mark in the center and a small line in the corner. */
@Composable
fun Watermark() {
    if (Support.supporter.isNotEmpty()) return
    Box(Modifier.fillMaxSize()) {
        Text("@j;D", color = FG.copy(alpha = 0.16f), fontSize = 22.sp, modifier = Modifier.align(Alignment.Center))
        Text("Starshine · sponsor to remove this mark", color = FG.copy(alpha = 0.32f), fontSize = 8.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 5.dp))
    }
}

/** Settings → About: status and key entry. */
@Composable
fun SupportRows() {
    val ctx = LocalContext.current
    var note by remember { mutableStateOf("") }
    if (Support.supporter.isNotEmpty()) Text("♥ Thank you, ${Support.supporter}. The watermark is off.", color = GREEN, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
    else {
        Row_("♥ Enter a support key  ›", false, id = "sup:key", onFocus = {}) {
            val box = android.widget.EditText(ctx).apply { hint = "Name.key"; setSingleLine() }
            android.app.AlertDialog.Builder(ctx).setTitle("Support key").setView(box)
                .setPositiveButton("Save") { _, _ -> note = if (Support.enter(ctx, box.text.toString())) "" else "That key isn't valid" }
                .setNegativeButton("Cancel", null).show()
        }
        Text(note.ifEmpty { "Sponsor with US$10 at ${Support.URL} to get a key. PC toolkit → Enter Support Key saves typing it." }, color = if (note.isEmpty()) DIM else AMBER, fontSize = 10.sp, modifier = Modifier.padding(8.dp))
    }
}
