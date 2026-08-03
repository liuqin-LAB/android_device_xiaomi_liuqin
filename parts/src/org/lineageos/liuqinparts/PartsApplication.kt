// Copyright (C) 2026 The LineageOS Project
//
// SPDX-License-Identifier: Apache-2.0
package org.lineageos.liuqinparts

import android.app.Application
import android.content.Intent
import android.os.UserHandle

class PartsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (UserHandle.myUserId() == UserHandle.USER_SYSTEM) {
            startService(Intent(this, PartsService::class.java))
        }
    }
}
