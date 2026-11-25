package com.igi.mobile.web

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.webkit.JavascriptInterface

/**
 * Exposes the phone's native gyroscope and gravity sensors to the game's WebView.
 *
 * The game prefers the Generic Sensor API (`Gyroscope` + `GravitySensor`) and only falls back to
 * `deviceorientation` when those constructors are missing. Android WebView does not provide them,
 * so the fallback is the only path it ever takes there — and that fallback derives pitch from
 * `asin(gravity.z)`, which is nearly flat in a landscape grip and cannot look up or down.
 *
 * `ShellCache` installs small `Gyroscope`/`GravitySensor` stand-ins backed by this bridge. The
 * game then uses its own gravity-compensated `cg()` math, which projects the real angular velocity
 * onto both screen axes. No game bundle patching is needed.
 */
class GyroBridge(context: Context) : SensorEventListener {

    private val manager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val lock = Any()
    private val rotationRate = floatArrayOf(0f, 0f, 0f)
    private val gravity = floatArrayOf(0f, GRAVITY_EARTH, 0f)
    private val lowPassGravity = floatArrayOf(0f, GRAVITY_EARTH, 0f)
    private var listeners = 0

    fun start() {
        val sensors = manager ?: return
        val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val gravitySensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        synchronized(lock) {
            if (listeners > 0) return
            if (gyro != null) sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
            if (gravitySensor != null) {
                sensors.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_GAME)
            }
            listeners = (if (gyro != null) 1 else 0) + (if (gravitySensor != null) 1 else 0)
        }
    }

    fun stop() {
        val sensors = manager ?: return
        synchronized(lock) {
            if (listeners == 0) return
            sensors.unregisterListener(this)
            listeners = 0
        }
    }

    /**
     * Called by the injected shim ~60 times a second. Returns `gx,gy,gz,ax,ay,az`: angular
     * velocity in rad/s followed by the gravity vector in m/s^2, both in the device coordinate
     * system expected by the Generic Sensor API.
     */
    @JavascriptInterface
    fun sample(): String = synchronized(lock) {
        val g = rotationRate
        val a = if (hasGravitySensor) gravity else lowPassGravity
        "${g[0]},${g[1]},${g[2]},${a[0]},${a[1]},${a[2]}"
    }

    private var hasGravitySensor = false

    override fun onSensorChanged(event: SensorEvent) {
        synchronized(lock) {
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> System.arraycopy(event.values, 0, rotationRate, 0, 3)
                Sensor.TYPE_GRAVITY -> {
                    hasGravitySensor = true
                    System.arraycopy(event.values, 0, gravity, 0, 3)
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    // No TYPE_GRAVITY: separate gravity from acceleration with a low-pass filter.
                    for (i in 0..2) {
                        lowPassGravity[i] = lowPassGravity[i] * GRAVITY_ALPHA +
                            event.values[i] * (1f - GRAVITY_ALPHA)
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val NAME = "IgiGyro"
        const val GRAVITY_EARTH = 9.81f
        const val GRAVITY_ALPHA = 0.8f
    }
}
