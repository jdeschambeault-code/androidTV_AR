package com.starshine.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * One-shot voice command capture. Needs RECORD_AUDIO and the RecognitionService <queries> entry in the manifest (Android 11+).
 * The pod's default recognizer is Google TTS's service, so Automatic tries the Google TV one (Katniss) first and
 * moves to the next recognizer on a real failure. "Nothing heard" errors are not failures of the recognizer.
 */
class Voice(private val ctx: Context, private val onPartial: (String) -> Unit, private val onResult: (String?, String) -> Unit) {
    private var rec: SpeechRecognizer? = null
    private var queue: List<String> = emptyList()

    fun available() = services(ctx).isNotEmpty() || SpeechRecognizer.isRecognitionAvailable(ctx)

    fun listen() {
        val chosen = chosen(ctx)
        queue = if (chosen.isNotEmpty()) listOf(chosen) else ordered(ctx)
        next("")
    }

    private fun next(lastError: String) {
        stop()
        val svc = queue.firstOrNull()
        if (svc == null && lastError.isNotEmpty()) { onResult(null, lastError); return }
        queue = queue.drop(1)
        Log.i(TAG, "listen with ${svc ?: "system default"}")
        val r = if (svc != null) SpeechRecognizer.createSpeechRecognizer(ctx, ComponentName.unflattenFromString(svc))
                else SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                Log.i(TAG, "result '$text' from $svc")
                if (svc != null && chosen(ctx).isEmpty() && text != null) remember(ctx, svc)
                onResult(text, if (text == null) "nothing heard" else "")
            }
            override fun onPartialResults(partial: Bundle?) { partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial) }
            override fun onError(error: Int) {
                val name = errorName(error)
                Log.w(TAG, "error $name from $svc")
                val heardNothing = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (heardNothing || queue.isEmpty()) onResult(null, name) else next(name)
            }
            override fun onReadyForSpeech(params: Bundle?) { Log.i(TAG, "ready") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1))
    }

    fun stop() { runCatching { rec?.destroy() }; rec = null }

    companion object {
        private const val TAG = "StarshineInput"
        private fun sp(ctx: Context) = ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE)

        fun hasMic(ctx: Context) = ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** Every recognizer on the device as "package/class". */
        fun services(ctx: Context): List<String> = runCatching {
            ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .map { it.serviceInfo.packageName + "/" + it.serviceInfo.name }
        }.getOrDefault(emptyList())

        /** The recognizer picked in Voice setup ("" = Automatic). */
        fun chosen(ctx: Context) = sp(ctx).getString("voiceSvc", "")!!
        fun setChosen(ctx: Context, svc: String) = sp(ctx).edit().putString("voiceSvc", svc).apply()
        private fun remember(ctx: Context, svc: String) = sp(ctx).edit().putString("voiceWorked", svc).apply()

        private fun ordered(ctx: Context): List<String> {
            val worked = sp(ctx).getString("voiceWorked", "")!!
            return services(ctx).sortedBy { when { it == worked -> 0; it.contains("katniss") -> 1; it.contains("tts") -> 3; else -> 2 } }
        }

        fun shortName(svc: String) = when {
            svc.contains("katniss") -> "Google TV"
            svc.contains("tts") -> "Google TTS"
            else -> svc.substringBefore("/")
        }

        fun inputs(ctx: Context): String = runCatching {
            ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY }
                .joinToString { it.productName.toString() + " (type " + it.type + ")" }
        }.getOrDefault("")

        fun errorName(e: Int) = when (e) {
            SpeechRecognizer.ERROR_AUDIO -> "microphone error"
            SpeechRecognizer.ERROR_CLIENT -> "recognizer refused the request"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission missing"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network error"
            SpeechRecognizer.ERROR_NO_MATCH -> "didn't catch that"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "recognizer server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "nothing heard"
            else -> "recognizer error $e"
        }

        private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

        private fun distance(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1); cur[0] = i
                for (j in 1..b.length) cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = cur
            }
            return prev[b.length]
        }

        /** Forgiving name match, because speech gives "jelly fin", "jelly-fin", "jellyfish"-like spellings for "Jellyfin". */
        fun <T> bestMatch(query: String, items: List<T>, label: (T) -> String): T? {
            val q = norm(query); if (q.isEmpty()) return null
            items.firstOrNull { norm(label(it)) == q }?.let { return it }
            items.firstOrNull { norm(label(it)).contains(q) }?.let { return it }
            items.firstOrNull { q.contains(norm(label(it))) && norm(label(it)).length >= 3 }?.let { return it }
            val best = items.minByOrNull { distance(q, norm(label(it))) } ?: return null
            return best.takeIf { distance(q, norm(label(it))) <= (q.length / 3).coerceAtLeast(1) }
        }
    }
}
