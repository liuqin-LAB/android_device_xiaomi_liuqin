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

/**
 * Persistent LCD configuration for the toggles
 * `gesture_wakeup_option` (double-tap bit), `pick_up_gesture_wakeup_mode`
 * (pickup sensor) and `stylus_tap_to_wake` (pen tap bit), all in
 * Settings.System.
 *
 * The LCD controller reports double-tap and pen gestures as standard
 * KEY_WAKEUP input events, so there is no wake-gesture sensor subscription
 * to manage here. Lid/tablet switches are handled by the framework input
 * policy; this controller only programs the persistent LCD gesture modes and
 * the standard pickup sensor.
 *
 * Like the stock `PowerManagerServiceImpl`, the pickup sensor is only
 * registered while the panel is non-interactive and adaptive sleep/screen
 * attention is on. Stock also skips a visible keyguard; the non-interactive
 * gate already covers that, because no keyguard is visible while the panel
 * is off.
 */
class WakeGestureSettingsController(private val context: Context, private val handler: Handler) : AutoCloseable {
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
            if (event.values.firstOrNull() == 1f) wakeForPickup(event.timestamp)
        }
    }
    private val trigger = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            handler.post {
                registered = false
                if (event.values.firstOrNull() == 1f) wakeForPickup(event.timestamp)
                schedule()
            }
        }
    }
    fun start() {
        if (running) return
        running = true
        observe(GestureSettings.DOUBLE_TAP)
        observe(GestureSettings.PICKUP)
        observe(GestureSettings.PEN)
        observeSecure(ADAPTIVE_SLEEP)
        observeSecure(SCREEN_ATTENTION)
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_SWITCHED)
        }, null, handler, Context.RECEIVER_EXPORTED)
        syncSettings()
    }
    private fun observe(key: String) {
        resolver.registerContentObserver(
            Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL,
        )
    }
    private fun observeSecure(key: String) {
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(key), false, observer, UserHandle.USER_ALL,
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
        val user = ActivityManager.getCurrentUser()
        val doubleTap = GestureSettings.enabled(context, GestureSettings.DOUBLE_TAP, user)
        val pen = GestureSettings.enabled(context, GestureSettings.PEN, user)
        val doubleReady = writeMode("gesture_double_tap_enabled", doubleTap)
        val penReady = writeMode("gesture_pen_tap_enabled", pen)
        updatePickup()
        if (!doubleReady || !penReady || (pickup == null && GestureSettings.enabled(context, GestureSettings.PICKUP)))
            handler.postDelayed(sync, 5_000L)
    }
    private fun updatePickup() {
        if (pickup == null) pickup = sensors.getDefaultSensor(Sensor.TYPE_PICK_UP_GESTURE, true)
        val sensor = pickup ?: return
        val enabled = running && !power.isInteractive && pickupAllowed()
        if (registered == enabled) return
        if (enabled) {
            registered = if (sensor.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT)
                sensors.requestTriggerSensor(trigger, sensor)
            else sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
        } else {
            if (sensor.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) sensors.cancelTriggerSensor(trigger, sensor)
            else sensors.unregisterListener(listener, sensor)
            registered = false
        }
    }
    private fun pickupAllowed(): Boolean {
        if (!GestureSettings.enabled(context, GestureSettings.PICKUP)) return false
        val adaptiveSleep = Settings.Secure.getIntForUser(resolver, ADAPTIVE_SLEEP, 0, UserHandle.USER_CURRENT) == 1
        val screenAttention = Settings.Secure.getIntForUser(resolver, SCREEN_ATTENTION, 0, UserHandle.USER_CURRENT) != 0
        return adaptiveSleep || screenAttention
    }
    private fun wakeForPickup(timestamp: Long) {
        if (!running || power.isInteractive || !pickupAllowed()) return
        val now = SystemClock.uptimeMillis()
        val age = (SystemClock.elapsedRealtimeNanos() - timestamp) / 1_000_000
        val eventTime = (now - age).coerceAtLeast(0)
        // Stock only raises user activity because the MIUI dim/AOD state is
        // still showing. Lineage has no dim state after screen off, so the
        // same event is followed by a full gesture wake.
        power.userActivity(eventTime, POWER_USER_ACTIVITY_EVENT_OTHER, 0)
        power.wakeUp(eventTime, PowerManager.WAKE_REASON_GESTURE, TAG)
    }
    override fun close() {
        running = false
        handler.removeCallbacks(sync)
        sensors.unregisterListener(listener)
        pickup?.let { sensors.cancelTriggerSensor(trigger, it) }
        resolver.unregisterContentObserver(observer)
        context.unregisterReceiver(receiver)
    }
    companion object {
        private const val TAG = "LiuqinWakeSettings"
        private const val POWER_USER_ACTIVITY_EVENT_OTHER = 0
        private const val ADAPTIVE_SLEEP = "adaptive_sleep"
        private const val SCREEN_ATTENTION = "gaze_lock_screen_setting"
    }
}
