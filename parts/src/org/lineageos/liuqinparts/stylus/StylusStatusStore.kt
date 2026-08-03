/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.stylus

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.UserHandle
import org.json.JSONObject
import org.lineageos.liuqinparts.settings.DeviceStatusSettings

enum class StylusConnectionState {
    DISCONNECTED,
    CONNECTING,
    PAIRING,
    CONNECTED,
    BLUETOOTH_OFF,
    CONNECTION_FAILED,
}

data class StylusStatus(
    val state: StylusConnectionState = StylusConnectionState.DISCONNECTED,
    val name: String? = null,
    val address: String? = null,
    val battery: Int? = null,
    val docked: Boolean = false,
    val firmwareRevision: String? = null,
    val vendorId: Int? = null,
    val productId: Int? = null,
)

object StylusStatusStore {
    private val deviceStatus = DeviceStatusSettings("liuqin_stylus_status")
    val uri: Uri get() = deviceStatus.uri

    private fun preferences(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("stylus_status", Context.MODE_PRIVATE)

    fun snapshot(context: Context): StylusStatus {
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) {
            val status = deviceStatus.snapshot(context)
            return StylusStatus(
                state = StylusConnectionState.entries.firstOrNull {
                    it.name == status.optString("state")
                } ?: StylusConnectionState.DISCONNECTED,
                battery = status.optInt("battery", -1).takeIf { it in 0..100 },
                docked = status.optBoolean("docked", false),
                firmwareRevision = status.opt("firmware") as? String,
            )
        }
        val prefs = preferences(context)
        val state = StylusConnectionState.entries.firstOrNull {
            it.name == prefs.getString("state", null)
        } ?: StylusConnectionState.DISCONNECTED
        return StylusStatus(
            state = state,
            name = prefs.getString("name", null),
            address = prefs.getString("address", null),
            battery = prefs.getInt("battery", -1).takeIf { it in 0..100 },
            docked = prefs.getBoolean("docked", false),
            firmwareRevision = prefs.getString("firmware", null),
            vendorId = prefs.getInt("vendor", -1).takeIf { it >= 0 },
            productId = prefs.getInt("product", -1).takeIf { it >= 0 },
        )
    }

    internal fun publish(context: Context, status: StylusStatus) {
        preferences(context).edit()
            .putString("state", status.state.name)
            .putString("name", status.name)
            .putString("address", status.address)
            .putInt("battery", status.battery ?: -1)
            .putBoolean("docked", status.docked)
            .putString("firmware", status.firmwareRevision)
            .putInt("vendor", status.vendorId ?: -1)
            .putInt("product", status.productId ?: -1)
            .apply()
        // Pairing identity remains private; other users only need the state
        // and telemetry displayed in settings.
        deviceStatus.publish(
            context,
            JSONObject()
                .put("state", status.state.name)
                .put("battery", status.battery ?: -1)
                .put("docked", status.docked)
                .put("firmware", status.firmwareRevision),
        )
    }
}
