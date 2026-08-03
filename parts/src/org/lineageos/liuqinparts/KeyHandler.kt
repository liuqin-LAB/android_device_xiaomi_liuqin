/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserHandle
import android.provider.Settings
import android.view.KeyEvent
import com.android.internal.os.DeviceKeyHandler

/** Mirrors the Nanosic Caps Lock state for the keyboard indicator. */
class KeyHandler(private val context: Context) : DeviceKeyHandler {
    private val handler = Handler(Looper.getMainLooper())
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private var lastEventTime = Long.MIN_VALUE
    private val recentEvents = LinkedHashSet<EventIdentity>()

    override fun handleKeyEvent(event: KeyEvent): KeyEvent? {
        if (
            event.keyCode != KeyEvent.KEYCODE_CAPS_LOCK ||
                event.action != KeyEvent.ACTION_UP ||
                event.device?.vendorId != VENDOR_NANOSIC
        ) {
            return event
        }
        val identity = EventIdentity(event.deviceId, event.downTime, event.eventTime)
        val capsLockOn = event.isCapsLockOn
        // Queue and dispatch callbacks can arrive out of order. Keep the newest state
        // and deduplicate identities without dropping distinct events in the same millisecond.
        handler.post {
            if (!powerManager.isInteractive) return@post
            if (identity.eventTime < lastEventTime || !recentEvents.add(identity)) return@post
            lastEventTime = identity.eventTime
            if (recentEvents.size > RECENT_EVENT_LIMIT) {
                recentEvents.remove(recentEvents.first())
            }
            Settings.System.putIntForUser(
                context.contentResolver,
                CAPS_LOCK_STATE,
                if (capsLockOn) 1 else 0,
                UserHandle.USER_CURRENT,
            )
        }
        return event
    }

    private data class EventIdentity(val deviceId: Int, val downTime: Long, val eventTime: Long)

    private companion object {
        const val RECENT_EVENT_LIMIT = 32
        const val VENDOR_NANOSIC = 0x15d9
        const val CAPS_LOCK_STATE = "liuqin_keyboard_caps_lock"
    }
}
