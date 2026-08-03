// Copyright (C) 2026 The LineageOS Project
//
// SPDX-License-Identifier: Apache-2.0
package org.lineageos.liuqinparts.touch

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import org.lineageos.liuqinparts.GestureSettings
import java.io.File

class WakeGestureSettingsController(
    private val context: Context,
    private val handler: Handler,
) : AutoCloseable {
    private val resolver = context.contentResolver
    private val power = context.getSystemService(PowerManager::class.java)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var running = false
    private var pickup: Sensor? = null
    private var registered = false
    private val applied = mutableMapOf<String, Boolean>()
    private val sync = Runnable { syncSettings() }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { schedule() }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Recheck nodes on resume: a driver rebind can reset its applied state.
            if (intent.action == Intent.ACTION_SCREEN_ON) applied.clear()
            schedule()
        }
    }
    private val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.firstOrNull() == 1f) wakeForPickup()
        }
    }
    private val trigger = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            if (!running) return
            registered = false
            // Keep the wake request inside the sensor callback's wake lock.
            if (event.values.firstOrNull() == 1f) wakeForPickup()
            syncSettings()
        }
    }
    fun start() {
        if (running) return
        running = true
        observe(GestureSettings.DOUBLE_TAP)
        observe(GestureSettings.PICKUP)
        observe(GestureSettings.PEN)
        context.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_SWITCHED)
            },
            null,
            handler,
            Context.RECEIVER_EXPORTED,
        )
        syncSettings()
    }
    private fun observe(key: String) {
        resolver.registerContentObserver(
            Settings.System.getUriFor(key),
            false,
            observer,
            UserHandle.USER_ALL,
        )
    }
    private fun schedule() {
        if (!running) return
        handler.removeCallbacks(sync)
        handler.post(sync)
    }
    private fun writeMode(name: String, enabled: Boolean): Boolean {
        if (applied[name] == enabled) return true
        return try {
            File("/sys/class/touch/touch_dev/$name").writeText(if (enabled) "1" else "0")
            applied[name] = enabled
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cannot apply $name", e)
            false
        }
    }
    private fun syncSettings() {
        if (!running) return
        handler.removeCallbacks(sync)
        val user = ActivityManager.getCurrentUser()
        val doubleTap = GestureSettings.enabled(context, GestureSettings.DOUBLE_TAP, user)
        val pen = GestureSettings.enabled(context, GestureSettings.PEN, user)
        val doubleReady = writeMode("gesture_double_tap_enabled", doubleTap)
        val penReady = writeMode("gesture_pen_tap_enabled", pen)
        val pickupReady = updatePickup(user)
        if (!doubleReady || !penReady || !pickupReady) {
            handler.postDelayed(sync, RETRY_DELAY_MS)
        }
    }
    private fun updatePickup(user: Int): Boolean {
        val enabled = !power.isInteractive &&
            GestureSettings.enabled(context, GestureSettings.PICKUP, user)
        if (!enabled) {
            unregisterPickup()
            return true
        }
        if (registered) return true

        val sensor = sensors.getDefaultSensor(Sensor.TYPE_PICK_UP_GESTURE, true)
            ?: return false
        pickup = sensor
        registered = if (sensor.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) {
            sensors.requestTriggerSensor(trigger, sensor)
        } else {
            sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
        }
        if (!registered) pickup = null
        return registered
    }
    private fun unregisterPickup() {
        pickup?.let { sensor ->
            if (sensor.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) {
                sensors.cancelTriggerSensor(trigger, sensor)
            } else {
                sensors.unregisterListener(listener, sensor)
            }
        }
        registered = false
        pickup = null
    }
    private fun wakeForPickup() {
        if (!running || power.isInteractive ||
            !GestureSettings.enabled(context, GestureSettings.PICKUP)
        ) {
            return
        }
        power.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_GESTURE, TAG)
    }
    override fun close() {
        if (!running) return
        running = false
        handler.removeCallbacks(sync)
        unregisterPickup()
        applied.clear()
        resolver.unregisterContentObserver(observer)
        context.unregisterReceiver(receiver)
    }
    companion object {
        private const val TAG = "LiuqinWakeSettings"
        private const val RETRY_DELAY_MS = 5_000L
    }
}
