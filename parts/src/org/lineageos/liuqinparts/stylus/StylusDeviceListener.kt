// Copyright (C) 2026 The LineageOS Project
//
// SPDX-License-Identifier: Apache-2.0
package org.lineageos.liuqinparts.stylus

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.provider.Settings
import android.util.Log
import java.io.File

class StylusDeviceListener(
    private val context: Context,
    private val handler: Handler,
) : AutoCloseable {
    private val input = context.getSystemService(InputManager::class.java)
    private val devices = linkedMapOf<Int, Int>()
    private var started = false

    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            updateDevice(deviceId)
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            updateDevice(deviceId)
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            if (!started) return
            removeDevice(deviceId)
        }
    }

    fun start() {
        if (started) return
        started = true
        write(DISCONNECT_RESET)
        writeVersion(TYPE_NONE)
        input?.registerInputDeviceListener(listener, handler)
        input?.inputDeviceIds?.forEach(::updateDevice)
    }

    override fun close() {
        if (!started) return
        started = false
        input?.unregisterInputDeviceListener(listener)
        devices.clear()
        write(DISCONNECT_RESET)
        writeVersion(TYPE_NONE)
    }

    private fun updateDevice(deviceId: Int) {
        if (!started) return
        val generation = XiaomiStylusDevice.typeOf(input?.getInputDevice(deviceId))
        if (generation == (devices[deviceId] ?: TYPE_NONE)) return
        removeDevice(deviceId)
        if (generation == TYPE_NONE) return
        val alreadyConnected = generation in devices.values
        devices[deviceId] = generation
        if (!alreadyConnected) write(generation or CONNECT_FLAG)
        writeVersion(generation)
    }

    private fun removeDevice(deviceId: Int) {
        val generation = devices.remove(deviceId) ?: return
        if (generation !in devices.values) write(generation)
        writeVersion(devices.values.lastOrNull() ?: TYPE_NONE)
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
