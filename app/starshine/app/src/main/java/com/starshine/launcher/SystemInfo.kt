package com.starshine.launcher

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Process

/** Small wrappers around system services used by the top-bar pills and the quick panel. */
object SystemInfo {

    data class Ram(val availMb: Long, val totalMb: Long) { val usedPct get() = if (totalMb > 0) (100 - availMb * 100 / totalMb).toInt() else 0 }

    fun ram(ctx: Context): Ram {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return Ram(mi.availMem / 1_048_576, mi.totalMem / 1_048_576)
    }

    private fun swapFreeMb(): Long = runCatching {
        java.io.File("/proc/meminfo").readLines().first { it.startsWith("SwapFree") }.filter { it.isDigit() }.toLong() / 1024
    }.getOrDefault(0L)

    data class Cleaned(val ramMb: Long, val swapMb: Long) { val text get() = "Freed $ramMb MB of RAM and $swapMb MB of swap" }

    /**
     * Asks Android to close the background processes of every launchable app (except Starshine). Apps can't be force-stopped
     * from here, so some stay or restart. Memory is released gradually, so call this off the main thread: it waits before measuring.
     */
    fun boost(ctx: Context, packages: List<String>): Cleaned {
        val before = ram(ctx).availMb; val swapBefore = swapFreeMb()
        val am = ctx.getSystemService(ActivityManager::class.java)
        packages.filter { it != ctx.packageName && !it.startsWith("@") && !it.startsWith("web:") }
            .forEach { runCatching { am.killBackgroundProcesses(it) } }
        Thread.sleep(2000)
        return Cleaned((ram(ctx).availMb - before).coerceAtLeast(0), (swapFreeMb() - swapBefore).coerceAtLeast(0))
    }

    fun close(ctx: Context, pkg: String) = runCatching { ctx.getSystemService(ActivityManager::class.java).killBackgroundProcesses(pkg) }

    data class Bt(val on: Boolean, val connected: List<String>)

    fun bluetooth(ctx: Context): Bt {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return Bt(false, emptyList())
        val on = runCatching { adapter.isEnabled }.getOrDefault(false)
        val connected = runCatching {
            adapter.bondedDevices.filter { d ->
                runCatching { BluetoothDevice::class.java.getMethod("isConnected").invoke(d) as Boolean }.getOrDefault(false)
            }.map { runCatching { it.name }.getOrNull() ?: "device" }
        }.getOrDefault(emptyList())   // needs BLUETOOTH_CONNECT (granted by build.js)
        return Bt(on, connected)
    }

    fun hasUsageAccess(ctx: Context): Boolean = runCatching {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Recently used launchable apps, most recent first (needs usage access, granted by build.js). */
    fun recentApps(ctx: Context, launchable: Set<String>, max: Int = 10): List<String> = runCatching {
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 12 * 3_600_000L, now)
            .filter { it.lastTimeUsed > 0 && it.packageName in launchable && it.packageName != ctx.packageName }
            .groupBy { it.packageName }.map { (p, l) -> p to l.maxOf { it.lastTimeUsed } }
            .sortedByDescending { it.second }.take(max).map { it.first }
    }.getOrDefault(emptyList())

    fun volume(ctx: Context, direction: Int) {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    fun volumeMax(ctx: Context) = ctx.getSystemService(AudioManager::class.java).getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    fun volumeNow(ctx: Context) = ctx.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_MUSIC)
    fun setVolume(ctx: Context, v: Int) = ctx.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)

    /** The screen brightness, 0-255. Some TV boxes accept the setting without it changing the picture. */
    fun brightness(ctx: Context) = runCatching { android.provider.Settings.System.getInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
    /** False when Android hasn't given the permission to change it. */
    fun setBrightness(ctx: Context, v: Int): Boolean {
        if (!android.provider.Settings.System.canWrite(ctx)) return false
        return runCatching { android.provider.Settings.System.putInt(ctx.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, v) }.getOrDefault(false)
    }

    fun startScreensaver(ctx: Context) = runCatching {
        ctx.startActivity(Intent().setClassName("com.android.systemui", "com.android.systemui.Somnambulator").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
