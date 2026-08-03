// SPDX-License-Identifier: Apache-2.0
package org.lineageos.liuqinparts

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.lineageos.liuqinparts.stylus.StylusController
import org.lineageos.liuqinparts.stylus.StylusDeviceListener
import org.lineageos.liuqinparts.keyboard.KeyboardController
import org.lineageos.liuqinparts.touch.WakeGestureSettingsController

class PartsService : Service() {
    private lateinit var wake: WakeGestureSettingsController
    private lateinit var stylus: StylusController
    private lateinit var stylusDevices: StylusDeviceListener
    private lateinit var keyboard: KeyboardController

    override fun onCreate() {
        super.onCreate()
        val handler = Handler(Looper.getMainLooper())
        wake = WakeGestureSettingsController(this, handler).also { it.start() }
        keyboard = KeyboardController(this, handler).also { it.start() }
        stylus = StylusController(this, handler).also { it.start() }
        stylusDevices = StylusDeviceListener(this, handler).also { it.start() }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent): IBinder? = null
    override fun onDestroy() {
        stylusDevices.close()
        stylus.close()
        keyboard.close()
        wake.close()
        super.onDestroy()
    }
}
