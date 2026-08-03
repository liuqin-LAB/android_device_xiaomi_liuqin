// SPDX-License-Identifier: Apache-2.0
package org.lineageos.liuqinparts.stylus

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * Port of the stock `MiuiStylusDeviceListener`.
 *
 * The stock listener sends the raw generation delta to TOUCH_MODE_STYLUS_CONNECTION:
 * connect is `generation | 0x10`, disconnect is `generation`. Like the stock
 * mode20 `{min = -1, max = 18}` clamp, the kernel decodes the generation-1
 * shield (`0x11`/`0x01`) and the generation-2 counter (`0x12`/`0x02`) and
 * clamps the generation 3..7 connect values (`0x13`..`0x17`) to `0x12`, so
 * supported pens still open the shorthand gate. Their disconnect values
 * (`0x03`..`0x07`) stay no-ops, exactly like stock.
 */
class StylusDeviceListener(
    private val context: Context,
    private val handler: Handler,
) : AutoCloseable {
    private val input = context.getSystemService(InputManager::class.java)
    private val devices = HashMap<Int, Int>()

    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            val device = input?.getInputDevice(deviceId) ?: return
            val generation = XiaomiStylusDevice.typeOf(device)
            writeVersion(generation)
            if (generation == TYPE_NONE) return
            if (devices.containsKey(deviceId)) return
            devices[deviceId] = generation
            write(generation or CONNECT_FLAG)
        }

        override fun onInputDeviceChanged(deviceId: Int) = Unit

        override fun onInputDeviceRemoved(deviceId: Int) {
            val generation = devices.remove(deviceId) ?: return
            writeVersion(TYPE_NONE)
            write(generation)
        }
    }

    fun start() {
        input?.registerInputDeviceListener(listener, handler)
        write(DISCONNECT_RESET)
    }

    override fun close() {
        input?.unregisterInputDeviceListener(listener)
    }

    private fun write(value: Int) {
        try {
            File(NODE).writeText(value.toString())
        } catch (exception: Exception) {
            Log.w(TAG, "Cannot apply stylus connection value $value", exception)
        }
    }

    private fun writeVersion(value: Int) {
        try {
            Settings.Secure.putInt(context.contentResolver, SETTING_STYLUS_VERSION, value)
        } catch (exception: Exception) {
            Log.w(TAG, "Cannot store the stylus version $value", exception)
        }
    }

    companion object {
        private const val TAG = "LiuqinStylusDevice"
        private const val NODE = "/sys/class/touch/touch_dev/stylus_connection"
        private const val SETTING_STYLUS_VERSION = "setting_stylus_version"
        private const val TYPE_NONE = 0
        private const val CONNECT_FLAG = 0x10
        private const val DISCONNECT_RESET = -1
    }
}
