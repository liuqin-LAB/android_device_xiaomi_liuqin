/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.settings

import android.content.Context
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings
import org.json.JSONException
import org.json.JSONObject

/** Device-wide, non-personal accessory telemetry for settings in every user. */
internal class DeviceStatusSettings(private val key: String) {
    val uri: Uri = Settings.Global.getUriFor(key)

    fun snapshot(context: Context): JSONObject {
        val value = Settings.Global.getString(context.contentResolver, key)
            ?: return JSONObject()
        return try {
            JSONObject(value)
        } catch (_: JSONException) {
            JSONObject()
        }
    }

    fun publish(context: Context, status: JSONObject) {
        check(UserHandle.myUserId() == UserHandle.USER_SYSTEM) {
            "Accessory status must be published by the system-user service"
        }
        // One value keeps each snapshot atomic. SettingsProvider restricts
        // global writes to WRITE_SECURE_SETTINGS and notifies all users.
        Settings.Global.putString(context.contentResolver, key, status.toString())
    }
}
