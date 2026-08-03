/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.stylus

import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import org.lineageos.liuqinparts.R
import org.lineageos.liuqinparts.settings.SecureSettings

/**
 * Stylus page rewritten for the sysfs/Bluetooth controller in this tree.
 * Connection, battery and firmware come from [StylusStatusStore]; the button
 * actions keep the stock secure settings consumed by KeyHandler.
 */
class StylusSettingsFragment : PreferenceFragmentCompat() {
    private val secureSettings by lazy(LazyThreadSafetyMode.NONE) {
        SecureSettings.from(requireContext())
    }

    private lateinit var connection: Preference
    private lateinit var battery: Preference
    private lateinit var firmware: Preference

    private val statusListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshStatus() }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val resources = context.resources
        preferenceScreen = preferenceManager.createPreferenceScreen(context)

        connection = Preference(context).apply {
            key = KEY_CONNECTION
            title = getString(R.string.connection_title)
            isSelectable = false
        }
        preferenceScreen.addPreference(connection)

        battery = Preference(context).apply {
            key = KEY_BATTERY
            title = getString(R.string.battery_title)
            isSelectable = false
        }
        preferenceScreen.addPreference(battery)

        firmware = Preference(context).apply {
            key = KEY_FIRMWARE
            title = getString(R.string.firmware_title)
            isSelectable = false
        }
        preferenceScreen.addPreference(firmware)

        preferenceScreen.addPreference(
            createActionPreference(
                StylusSettingsContract.PRIMARY_BUTTON_ACTION,
                getString(R.string.primary_button_title),
                resources.getTextArray(R.array.button_actions),
                resources.getTextArray(R.array.button_values),
                StylusSettingsContract.DEFAULT_PRIMARY_BUTTON_ACTION,
            ),
        )
        preferenceScreen.addPreference(
            createActionPreference(
                StylusSettingsContract.SECONDARY_BUTTON_ACTION,
                getString(R.string.secondary_button_title),
                resources.getTextArray(R.array.button_actions),
                resources.getTextArray(R.array.button_values),
                StylusSettingsContract.DEFAULT_SECONDARY_BUTTON_ACTION,
            ),
        )
    }

    override fun onResume() {
        super.onResume()
        StylusStatusStore.preferences(requireContext())
            .registerOnSharedPreferenceChangeListener(statusListener)
        refreshStatus()
        refreshActionPreference(
            StylusSettingsContract.PRIMARY_BUTTON_ACTION,
            StylusSettingsContract.DEFAULT_PRIMARY_BUTTON_ACTION,
        )
        refreshActionPreference(
            StylusSettingsContract.SECONDARY_BUTTON_ACTION,
            StylusSettingsContract.DEFAULT_SECONDARY_BUTTON_ACTION,
        )
    }

    override fun onPause() {
        StylusStatusStore.preferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(statusListener)
        super.onPause()
    }

    private fun createActionPreference(
        key: String,
        title: String,
        entries: Array<CharSequence>,
        entryValues: Array<CharSequence>,
        defaultValue: String,
    ): ListPreference = ListPreference(requireContext()).apply {
        this.key = key
        this.title = title
        this.entries = entries
        this.entryValues = entryValues
        isPersistent = false
        refreshActionPreference(key, defaultValue)
        setOnPreferenceChangeListener { preference, newValue ->
            val action = newValue as? String
                ?: return@setOnPreferenceChangeListener false
            if (action !in StylusSettingsContract.Action.SUPPORTED) {
                return@setOnPreferenceChangeListener false
            }
            if (!secureSettings.putString(key, action)) {
                return@setOnPreferenceChangeListener false
            }
            updateSummary(preference as ListPreference, action)
            true
        }
    }

    private fun refreshActionPreference(key: String, defaultValue: String) {
        val preference = findPreference<ListPreference>(key) ?: return
        val action = StylusSettingsContract.sanitizeAction(
            secureSettings.getString(key),
            defaultValue,
        )
        preference.value = action
        updateSummary(preference, action)
    }

    private fun updateSummary(preference: ListPreference, action: String) {
        val index = preference.findIndexOfValue(action)
        preference.summary = if (index >= 0) preference.entries[index] else null
    }

    private fun refreshStatus() {
        val status = StylusStatusStore.snapshot(requireContext())
        connection.summary = getString(
            when (status.state) {
                StylusConnectionState.CONNECTED -> R.string.connected
                StylusConnectionState.CONNECTING -> R.string.connecting
                StylusConnectionState.PAIRING -> R.string.pairing
                StylusConnectionState.BLUETOOTH_OFF -> R.string.bluetooth_off
                StylusConnectionState.CONNECTION_FAILED -> R.string.connection_failed
                StylusConnectionState.DISCONNECTED -> R.string.disconnected
            },
        )
        battery.summary = when {
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
