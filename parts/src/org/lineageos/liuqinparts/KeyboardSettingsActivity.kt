/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts

import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.android.settingslib.widget.SliderPreference
import org.lineageos.liuqinparts.keyboard.KeyboardStatusStore

class KeyboardSettingsActivity : CollapsingToolbarBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                    KeyboardSettingsFragment(),
                )
                .commit()
        }
    }
}

class KeyboardSettingsFragment : SettingsBasePreferenceFragment() {
    private lateinit var connection: Preference
    private lateinit var battery: Preference
    private lateinit var firmware: Preference
    private lateinit var backlight: SliderPreference
    private lateinit var backlightAuto: SwitchPreferenceCompat
    private lateinit var tapToClick: SwitchPreferenceCompat

    private val handler = Handler(Looper.getMainLooper())
    private val statusObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            refreshStatus()
        }
    }
    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            refreshControls()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(context)

        val backlightCategory = PreferenceCategory(context).apply {
            title = getString(R.string.backlight_title)
        }
        preferenceScreen.addPreference(backlightCategory)

        backlight = SliderPreference(context).apply {
            key = BACKLIGHT_BRIGHTNESS
            title = getString(R.string.brightness_title)
            min = 0
            max = 100
            sliderIncrement = 1
            showSliderValue = true
            isPersistent = false
            value = readSystemInt(context, BACKLIGHT_BRIGHTNESS, DEFAULT_BACKLIGHT)
                .coerceIn(0, 100)
            setOnPreferenceChangeListener { _, value ->
                putSystemInt(context, BACKLIGHT_BRIGHTNESS, value as Int)
            }
        }
        backlightCategory.addPreference(backlight)

        backlightAuto = SwitchPreferenceCompat(context).apply {
            key = BACKLIGHT_AUTO
            title = getString(R.string.backlight_auto_title)
            isPersistent = false
            isChecked = readSystemInt(context, BACKLIGHT_AUTO, 0) != 0
            setOnPreferenceChangeListener { _, value ->
                putSystemInt(context, BACKLIGHT_AUTO, if (value as Boolean) 1 else 0)
            }
        }
        backlightCategory.addPreference(backlightAuto)

        val touchpadCategory = PreferenceCategory(context).apply {
            title = getString(R.string.touchpad_title)
        }
        preferenceScreen.addPreference(touchpadCategory)

        tapToClick = SwitchPreferenceCompat(context).apply {
            key = TAP_TO_CLICK
            title = getString(R.string.tap_to_click_title)
            isPersistent = false
            isChecked = readSystemInt(context, TAP_TO_CLICK, DEFAULT_TAP_TO_CLICK) != 0
            setOnPreferenceChangeListener { _, value ->
                putSystemInt(context, TAP_TO_CLICK, if (value as Boolean) 1 else 0)
            }
        }
        touchpadCategory.addPreference(tapToClick)

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
            KeyboardStatusStore.uri,
            false,
            statusObserver,
        )
        requireContext().contentResolver.registerContentObserver(
            Settings.System.getUriFor(BACKLIGHT_BRIGHTNESS),
            false,
            settingsObserver,
        )
        requireContext().contentResolver.registerContentObserver(
            Settings.System.getUriFor(BACKLIGHT_AUTO),
            false,
            settingsObserver,
        )
        requireContext().contentResolver.registerContentObserver(
            Settings.System.getUriFor(TAP_TO_CLICK),
            false,
            settingsObserver,
        )
        refreshStatus()
        refreshControls()
    }

    override fun onPause() {
        requireContext().contentResolver.unregisterContentObserver(statusObserver)
        requireContext().contentResolver.unregisterContentObserver(settingsObserver)
        super.onPause()
    }

    private fun refreshStatus() {
        val status = KeyboardStatusStore.snapshot(requireContext())
        connection.summary = getString(
            if (status.connected) R.string.connected else R.string.disconnected,
        )
        battery.summary = status.batteryMillivolts
            ?.let { getString(R.string.battery_millivolts, it) }
            ?: getString(R.string.unavailable)
        firmware.summary = status.firmware ?: getString(R.string.unavailable)
    }

    private fun refreshControls() {
        val context = requireContext()
        backlight.value = readSystemInt(context, BACKLIGHT_BRIGHTNESS, DEFAULT_BACKLIGHT)
            .coerceIn(0, 100)
        backlightAuto.isChecked = readSystemInt(context, BACKLIGHT_AUTO, 0) != 0
        tapToClick.isChecked = readSystemInt(context, TAP_TO_CLICK, DEFAULT_TAP_TO_CLICK) != 0
    }

    private fun readSystemInt(context: android.content.Context, key: String, default: Int): Int =
        Settings.System.getIntForUser(context.contentResolver, key, default, UserHandle.USER_CURRENT)

    private fun putSystemInt(context: android.content.Context, key: String, value: Int): Boolean =
        Settings.System.putIntForUser(
            context.contentResolver,
            key,
            value,
            UserHandle.USER_CURRENT,
        )

    private companion object {
        const val KEY_CONNECTION = "keyboard_connection"
        const val KEY_BATTERY = "keyboard_battery"
        const val KEY_FIRMWARE = "keyboard_firmware"
        const val BACKLIGHT_BRIGHTNESS = "keyboard_back_light_brightness"
        const val BACKLIGHT_AUTO = "keyboard_back_light_automatic_adjustment"
        const val TAP_TO_CLICK = Settings.System.TOUCHPAD_TAP_TO_CLICK
        const val DEFAULT_BACKLIGHT = 0
        const val DEFAULT_TAP_TO_CLICK = 1
    }
}
