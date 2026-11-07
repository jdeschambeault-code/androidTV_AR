package com.starshine.launcher

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Minimal Jellyfin client: Quick Connect sign-in (no password typing on a TV) + "Continue watching". */
object Jellyfin {
    private const val AUTH = "MediaBrowser Client=\"Starshine\", Device=\"Rokid Station\", DeviceId=\"starshine-rokid-station\", Version=\"0.3\""
    private fun auth(token: String?) = if (token.isNullOrEmpty()) AUTH else "$AUTH, Token=\"$token\""

    data class Item(val id: String, val title: String, val subtitle: String, val progress: Float, val image: String)

    private fun http(method: String, url: String, token: String? = null, body: String? = null): String? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            requestMethod = method; connectTimeout = 4000; readTimeout = 6000
            setRequestProperty("Authorization", auth(token)); setRequestProperty("Content-Type", "application/json")
            if (body != null) { doOutput = true; outputStream.use { it.write(body.toByteArray()) } }
            if (responseCode in 200..299) inputStream.bufferedReader().readText() else null
        }
    }.getOrNull()

    /** Returns (code to type into Jellyfin, secret) */
    fun initiate(base: String): Pair<String, String>? {
        val r = http("POST", "$base/QuickConnect/Initiate") ?: http("GET", "$base/QuickConnect/Initiate") ?: return null
        val j = JSONObject(r); return j.getString("Code") to j.getString("Secret")
    }

    fun approved(base: String, secret: String): Boolean =
        http("GET", "$base/QuickConnect/Connect?secret=$secret")?.let { JSONObject(it).optBoolean("Authenticated") } ?: false

    /** Returns (accessToken, userId) */
    fun login(base: String, secret: String): Pair<String, String>? {
        val r = http("POST", "$base/Users/AuthenticateWithQuickConnect", body = JSONObject().put("Secret", secret).toString()) ?: return null
        val j = JSONObject(r); return j.getString("AccessToken") to j.getJSONObject("User").getString("Id")
    }

    fun resume(base: String, token: String, userId: String): List<Item> {
        val q = "limit=12&mediaTypes=Video&fields=PrimaryImageAspectRatio&enableImageTypes=Primary,Thumb,Backdrop"
        val r = http("GET", "$base/UserItems/Resume?userId=$userId&$q", token)
            ?: http("GET", "$base/Users/$userId/Items/Resume?$q", token) ?: return emptyList()
        val arr = JSONObject(r).optJSONArray("Items") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val id = o.getString("Id")
            val series = o.optString("SeriesName", "")
            val sub = if (series.isNotEmpty()) "$series · S${o.optInt("ParentIndexNumber")}E${o.optInt("IndexNumber")}" else o.optString("ProductionYear", "")
            val pct = (o.optJSONObject("UserData")?.optDouble("PlayedPercentage", 0.0) ?: 0.0).toFloat() / 100f
            val img = if (o.optString("Type") == "Episode") "$base/Items/$id/Images/Primary?maxHeight=240"
                      else "$base/Items/$id/Images/Backdrop?maxHeight=240"
            Item(id, o.optString("Name"), sub, pct, img)
        }
    }
}
