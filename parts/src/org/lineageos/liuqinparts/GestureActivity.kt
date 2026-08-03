// Copyright (C) 2026 The LineageOS Project
//
// SPDX-License-Identifier: Apache-2.0
package org.lineageos.liuqinparts

import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment

data class GestureEntry(val activity: String, val key: String, val title: Int, val summary: Int)
object GestureEntries {
    val all = listOf(
        GestureEntry("DoubleTapActivity", GestureSettings.DOUBLE_TAP, R.string.double_tap_title, R.string.double_tap_summary),
        GestureEntry("PickupActivity", GestureSettings.PICKUP, R.string.pickup_title, R.string.pickup_summary),
        GestureEntry("PenWakeActivity", GestureSettings.PEN, R.string.pen_wake_title, R.string.pen_wake_summary),
    )
}
class GestureActivity : CollapsingToolbarBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entry = GestureEntries.all.firstOrNull { intent.component?.className?.endsWith(".${it.activity}") == true }
            ?: run {
                finish()
                return
            }
        setTitle(entry.title)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(
                com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                GestureFragment().apply { arguments = Bundle().apply { putString("key", entry.key) } },
            ).commit()
        }
    }
}
class GestureFragment : SettingsBasePreferenceFragment() {
    private lateinit var entry: GestureEntry
    private lateinit var toggle: MainSwitchPreference
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { toggle.isChecked = GestureSettings.enabled(requireContext(), entry.key) }
    }
    override fun onCreatePreferences(state: Bundle?, rootKey: String?) {
        entry = GestureEntries.all.first { it.key == requireArguments().getString("key") }
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        toggle = MainSwitchPreference(requireContext()).apply {
            key = entry.key
            setTitle(R.string.use_gesture)
            setSummary(entry.summary)
            isIconSpaceReserved = false
            isPersistent = false
            setOnPreferenceChangeListener { _, value -> GestureSettings.set(requireContext(), entry.key, value as Boolean) }
        }
        preferenceScreen.addPreference(toggle)
    }
    override fun onResume() {
        super.onResume()
        toggle.isChecked = GestureSettings.enabled(requireContext(), entry.key)
        val uri = Settings.System.getUriFor(entry.key)
        requireContext().contentResolver.registerContentObserver(uri, false, observer)
    }
    override fun onPause() {
        requireContext().contentResolver.unregisterContentObserver(observer)
        super.onPause()
    }
}
