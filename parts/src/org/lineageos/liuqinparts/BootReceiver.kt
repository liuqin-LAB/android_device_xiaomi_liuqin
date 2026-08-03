// Copyright (C) 2026 The LineageOS Project
//
// SPDX-License-Identifier: Apache-2.0
package org.lineageos.liuqinparts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserHandle

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        if (UserHandle.myUserId() == UserHandle.USER_SYSTEM) {
            context.startService(Intent(context, PartsService::class.java))
        }
    }
}
