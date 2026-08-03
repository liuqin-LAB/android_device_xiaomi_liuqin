/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.keyboard

import android.content.Context
import android.content.SharedPreferences

/** Snapshot of the magnetic keyboard state shared with the settings UI. */
data class KeyboardStatus(
    val connected: Boolean = false,
    val batteryMillivolts: Int? = null,
    val keyboardVersion: Int? = null,
    val touchpadVersion: Int? = null,
    val firmware: String? = null,
    val bluetoothAddress: String? = null,
)

object KeyboardStatusStore {
    fun preferences(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("keyboard_status", Context.MODE_PRIVATE)

    fun snapshot(context: Context): KeyboardStatus {
        val prefs = preferences(context)
        return KeyboardStatus(
            connected = prefs.getBoolean("connected", false),
            batteryMillivolts = prefs.getInt("battery_mv", -1).takeIf { it > 0 },
            keyboardVersion = prefs.getInt("keyboard_version", -1).takeIf { it >= 0 },
            touchpadVersion = prefs.getInt("touchpad_version", -1).takeIf { it >= 0 },
            firmware = prefs.getString("firmware", null),
            bluetoothAddress = prefs.getString("address", null),
        )
    }

    internal fun publish(context: Context, status: KeyboardStatus) {
        preferences(context).edit()
            .putBoolean("connected", status.connected)
            .putInt("battery_mv", status.batteryMillivolts ?: -1)
            .putInt("keyboard_version", status.keyboardVersion ?: -1)
            .putInt("touchpad_version", status.touchpadVersion ?: -1)
            .putString("firmware", status.firmware)
            .putString("address", status.bluetoothAddress)
            .apply()
    }
}
