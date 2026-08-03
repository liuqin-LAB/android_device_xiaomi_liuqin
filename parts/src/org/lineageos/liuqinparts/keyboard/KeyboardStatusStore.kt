/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.UserHandle
import org.json.JSONObject
import org.lineageos.liuqinparts.settings.DeviceStatusSettings

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
    private val deviceStatus = DeviceStatusSettings("liuqin_keyboard_status")
    val uri: Uri get() = deviceStatus.uri

    private fun preferences(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("keyboard_status", Context.MODE_PRIVATE)

    fun snapshot(context: Context): KeyboardStatus {
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) {
            val status = deviceStatus.snapshot(context)
            return KeyboardStatus(
                connected = status.optBoolean("connected", false),
                batteryMillivolts = status.optInt("battery_mv", -1).takeIf { it > 0 },
                firmware = status.opt("firmware") as? String,
            )
        }
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
        // Keep the Bluetooth address and private protocol details in user 0.
        deviceStatus.publish(
            context,
            JSONObject()
                .put("connected", status.connected)
                .put("battery_mv", status.batteryMillivolts ?: -1)
                .put("firmware", status.firmware),
        )
    }
}
