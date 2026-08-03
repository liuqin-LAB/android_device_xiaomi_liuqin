/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.liuqinparts

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import com.android.settingslib.widget.SettingsBasePreferenceFragment

class StylusAndKeyboardActivity : CollapsingToolbarBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                    StylusAndKeyboardFragment(),
                )
                .commit()
        }
    }
}

class StylusAndKeyboardFragment : SettingsBasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(context)

        preferenceScreen.addPreference(Preference(context).apply {
            key = "stylus"
            title = getString(R.string.stylus_title)
            setIcon(R.drawable.ic_stylus_note)
            intent = Intent(context, StylusSettingsActivity::class.java)
        })

        preferenceScreen.addPreference(Preference(context).apply {
            key = "magnetic_keyboard"
            title = getString(R.string.keyboard_title)
            setIcon(R.drawable.ic_keyboard)
            intent = Intent(context, KeyboardSettingsActivity::class.java)
        })
    }
}
