/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.stylus

import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import org.lineageos.liuqinparts.R

/** Standard Bluetooth accessory state provided by [StylusStatusStore]. */
class StylusSettingsFragment : SettingsBasePreferenceFragment() {
    private lateinit var connection: Preference
    private lateinit var battery: Preference
    private lateinit var firmware: Preference

    private val statusObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refreshStatus()
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(context)

        val deviceInfoCategory =
            PreferenceCategory(context).apply { title = getString(R.string.device_info_title) }
        preferenceScreen.addPreference(deviceInfoCategory)

        connection =
            Preference(context).apply {
                key = KEY_CONNECTION
                title = getString(R.string.connection_title)
                isSelectable = false
            }
        deviceInfoCategory.addPreference(connection)

        battery =
            Preference(context).apply {
                key = KEY_BATTERY
                title = getString(R.string.battery_title)
                isSelectable = false
            }
        deviceInfoCategory.addPreference(battery)

        firmware =
            Preference(context).apply {
                key = KEY_FIRMWARE
                title = getString(R.string.firmware_title)
                isSelectable = false
            }
        deviceInfoCategory.addPreference(firmware)
    }

    override fun onResume() {
        super.onResume()
        requireContext()
            .contentResolver
            .registerContentObserver(StylusStatusStore.uri, false, statusObserver)
        refreshStatus()
    }

    override fun onPause() {
        requireContext().contentResolver.unregisterContentObserver(statusObserver)
        super.onPause()
    }

    private fun refreshStatus() {
        val status = StylusStatusStore.snapshot(requireContext())
        connection.summary =
            getString(
                when (status.state) {
                    StylusConnectionState.CONNECTED -> R.string.connected
                    StylusConnectionState.CONNECTING -> R.string.connecting
                    StylusConnectionState.PAIRING -> R.string.pairing
                    StylusConnectionState.BLUETOOTH_OFF -> R.string.bluetooth_off
                    StylusConnectionState.CONNECTION_FAILED -> R.string.connection_failed
                    StylusConnectionState.DISCONNECTED -> R.string.disconnected
                }
            )
        battery.summary =
            when {
                status.battery == null -> getString(R.string.unavailable)
                status.docked -> getString(R.string.charging_battery, status.battery)
                else -> getString(R.string.battery_percent, status.battery)
            }
        firmware.summary = status.firmwareRevision ?: getString(R.string.unavailable)
    }

    private companion object {
        const val KEY_CONNECTION = "stylus_connection"
        const val KEY_BATTERY = "stylus_battery"
        const val KEY_FIRMWARE = "stylus_firmware"
    }
}
