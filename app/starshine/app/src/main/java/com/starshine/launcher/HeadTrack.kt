package com.starshine.launcher

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Head tracking for 3D scenes: the head's rotation turns the camera (look around inside the scene).
 * It reads Android's rotation sensor, so it works wherever the glasses' motion sensor reaches Android (a phone with
 * USB glasses, or a box that exposes them). The Rokid Station doesn't: its USB lists no device and Android reports
 * no sensors, so there the setting says so instead. Rokid Max glasses track rotation only (3DoF), not position.
 */
object HeadTrack : SensorEventListener {
    /** Degrees from where the head was when tracking started or was recentred. Read by the renderer. */
    @Volatile var yaw = 0f; @Volatile var pitch = 0f; @Volatile var roll = 0f
    var on by mutableStateOf(false); private set
    var available by mutableStateOf(false); private set
    private var sm: SensorManager? = null
    private var base: FloatArray? = null
    private val rot = FloatArray(9); private val o = FloatArray(3)

    private fun sensor(m: SensorManager) = m.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: m.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    fun load(ctx: Context) {
        val m = ctx.getSystemService(SensorManager::class.java); sm = m
        available = m?.let { sensor(it) } != null
        on = available && ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE).getBoolean("headTrack", false)
        if (on) start()
    }
    fun set(ctx: Context, v: Boolean) {
        on = v && available
        ctx.getSharedPreferences("starshine", Context.MODE_PRIVATE).edit().putBoolean("headTrack", on).apply()
        if (on) start() else stop()
    }
    /** Straight ahead is wherever the head points now. */
    fun recenter() { base = null }

    private fun start() { val m = sm ?: return; base = null; sensor(m)?.let { m.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    private fun stop() { sm?.unregisterListener(this); yaw = 0f; pitch = 0f; roll = 0f }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        SensorManager.getOrientation(rot, o)
        val b = base ?: o.copyOf().also { base = it }
        fun deg(a: Float): Float { var d = Math.toDegrees(a.toDouble()).toFloat(); while (d > 180f) d -= 360f; while (d < -180f) d += 360f; return d }
        yaw = deg(o[0] - b[0]); pitch = deg(o[1] - b[1]); roll = deg(o[2] - b[2])
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
