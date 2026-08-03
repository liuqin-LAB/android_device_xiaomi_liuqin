// Copyright (C) 2026 The LineageOS Project
//
// SPDX-License-Identifier: Apache-2.0
package org.lineageos.liuqinparts

import android.content.Context
import android.os.UserHandle
import android.provider.Settings

/**
 * Settings keys for the pad gesture toggles:
 * `gesture_wakeup_option` (0 none / 1 single / 2 double),
 * `pick_up_gesture_wakeup_mode` (0 off / 1 on) and `stylus_tap_to_wake`
 * (0 off / 1 on) live in Settings.System.
 * Double tap and pen tap come from the LCD controller as standard KEY_WAKEUP
 * input events; the toggles only program the persistent controller modes.
 */
object GestureSettings {
    const val DOUBLE_TAP = "gesture_wakeup_option"
    const val PICKUP = "pick_up_gesture_wakeup_mode"
    const val PEN = "stylus_tap_to_wake"

    const val DOUBLE_TAP_OFF = 0
    const val DOUBLE_TAP_SINGLE = 1
    const val DOUBLE_TAP_ON = 2

    private const val LEGACY_DOUBLE_TAP = "gesture_wakeup"
    private const val LEGACY_DOUBLE_TAP_ON = 1
    private const val PICKUP_ON = 1
    private const val PEN_ON = 1

    fun enabled(context: Context, key: String, user: Int = UserHandle.USER_CURRENT): Boolean {
        val resolver = context.contentResolver
        return when (key) {
            DOUBLE_TAP -> {
                val option = Settings.System.getIntForUser(resolver, key, -1, user)
                if (option >= 0) {
                    option == DOUBLE_TAP_ON
                } else {
                    Settings.System.getIntForUser(resolver, LEGACY_DOUBLE_TAP, 0, user) ==
                        LEGACY_DOUBLE_TAP_ON
                }
            }

            PICKUP -> Settings.System.getIntForUser(resolver, key, 0, user) == PICKUP_ON

            PEN -> Settings.System.getIntForUser(resolver, key, 0, user) == PEN_ON

            else -> Settings.System.getIntForUser(resolver, key, 0, user) != 0
        }
    }

    fun set(context: Context, key: String, enabled: Boolean): Boolean {
        val resolver = context.contentResolver
        val user = UserHandle.USER_CURRENT
        val value = when {
            !enabled -> 0
            key == DOUBLE_TAP -> DOUBLE_TAP_ON
            else -> 1
        }
        return Settings.System.putIntForUser(resolver, key, value, user)
    }
}
