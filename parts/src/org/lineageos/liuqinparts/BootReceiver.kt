// SPDX-License-Identifier: Apache-2.0
package org.lineageos.liuqinparts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserHandle

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (UserHandle.myUserId() == UserHandle.USER_SYSTEM) {
            context.startService(Intent(context, PartsService::class.java))
        }
    }
}
