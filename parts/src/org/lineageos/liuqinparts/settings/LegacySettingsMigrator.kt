/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.settings

import android.content.Context
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.Log

/** Removes public accessory identities left by earlier versions of Parts. */
internal object LegacySettingsMigrator {
    fun clear(context: Context) {
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) return
        val users =
            try {
                context.getSystemService(UserManager::class.java).getUserHandles(true)
            } catch (exception: RuntimeException) {
                Log.w(TAG, "Could not enumerate users for legacy settings cleanup", exception)
                return
            }
        for (user in users) {
            for (key in SYSTEM_KEYS) {
                clearSetting(user.identifier, key) {
                    Settings.System.putStringForUser(
                        context.contentResolver,
                        key,
                        null,
                        user.identifier,
                    )
                }
            }
            for (key in SECURE_KEYS) {
                clearSetting(user.identifier, key) {
                    Settings.Secure.putStringForUser(
                        context.contentResolver,
                        key,
                        null,
                        user.identifier,
                    )
                }
            }
        }
    }

    private inline fun clearSetting(userId: Int, key: String, remove: () -> Boolean) {
        try {
            if (!remove()) Log.w(TAG, "Could not clear legacy setting $key for user $userId")
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not clear legacy setting $key for user $userId", exception)
        }
    }

    private const val TAG = "LiuqinParts.Migration"
    private val SYSTEM_KEYS =
        listOf("miui_keyboard_address", "stylus_hall_status", "notify_keyboard_info_changed")
    private val SECURE_KEYS =
        listOf(
            "miui_bluetooth_store_pen_address",
            "miui_bluetooth_store_pen_dfu_address",
            "setting_stylus_version",
            "keyboard_type_level",
            "liuqin_stylus_primary_button_action",
            "liuqin_stylus_secondary_button_action",
        )
}
