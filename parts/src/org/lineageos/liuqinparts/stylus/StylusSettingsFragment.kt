/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts.stylus

import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import org.lineageos.liuqinparts.R
import org.lineageos.liuqinparts.settings.SecureSettings

/**
 * Connection, battery and firmware come from [StylusStatusStore]; the button
 * actions keep the stock secure settings consumed by KeyHandler.
 */
class StylusSettingsFragment : SettingsBasePreferenceFragment() {
    private val secureSettings by lazy(LazyThreadSafetyMode.NONE) {
        SecureSettings.from(requireContext())
    }

    private lateinit var connection: Preference
    private lateinit var battery: Preference
    private lateinit var firmware: Preference

    private val statusObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshStatus()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val resources = context.resources
        preferenceScreen = preferenceManager.createPreferenceScreen(context)

        val buttonsCategory = PreferenceCategory(context).apply {
            title = getString(R.string.button_actions_title)
        }
        preferenceScreen.addPreference(buttonsCategory)

        buttonsCategory.addPreference(
            createActionPreference(
                StylusSettingsContract.PRIMARY_BUTTON_ACTION,
                getString(R.string.primary_button_title),
                resources.getTextArray(R.array.button_actions),
                resources.getTextArray(R.array.button_values),
                StylusSettingsContract.DEFAULT_PRIMARY_BUTTON_ACTION,
            ),
        )
        buttonsCategory.addPreference(
            createActionPreference(
                StylusSettingsContract.SECONDARY_BUTTON_ACTION,
                getString(R.string.secondary_button_title),
                resources.getTextArray(R.array.button_actions),
                resources.getTextArray(R.array.button_values),
                StylusSettingsContract.DEFAULT_SECONDARY_BUTTON_ACTION,
            ),
        )

        val deviceInfoCategory = PreferenceCategory(context).apply {
            title = getString(R.string.device_info_title)
        }
        preferenceScreen.addPreference(deviceInfoCategory)

        connection = Preference(context).apply {
            key = KEY_CONNECTION
            title = getString(R.string.connection_title)
            isSelectable = false
        }
        deviceInfoCategory.addPreference(connection)

        battery = Preference(context).apply {
            key = KEY_BATTERY
            title = getString(R.string.battery_title)
            isSelectable = false
        }
        deviceInfoCategory.addPreference(battery)

        firmware = Preference(context).apply {
            key = KEY_FIRMWARE
            title = getString(R.string.firmware_title)
            isSelectable = false
        }
        deviceInfoCategory.addPreference(firmware)
    }

    override fun onResume() {
        super.onResume()
        requireContext().contentResolver.registerContentObserver(
            StylusStatusStore.uri,
            false,
            statusObserver,
        )
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
        requireContext().contentResolver.unregisterContentObserver(statusObserver)
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
        dialogTitle = title
        this.entries = entries
        this.entryValues = entryValues
        isPersistent = false
        value = StylusSettingsContract.sanitizeAction(secureSettings.getString(key), defaultValue)
        updateSummary(this, value)
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
