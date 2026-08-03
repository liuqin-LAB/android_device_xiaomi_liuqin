// Copyright (C) 2026 The LineageOS Project
//
// SPDX-License-Identifier: Apache-2.0
package org.lineageos.liuqinparts

import android.database.Cursor
import android.database.MatrixCursor
import android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_INTENT_ACTION
import android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_INTENT_TARGET_CLASS
import android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_INTENT_TARGET_PACKAGE
import android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_KEY
import android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_SCREEN_TITLE
import android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_SUMMARY_ON
import android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_TITLE
import android.provider.SearchIndexablesContract.INDEXABLES_RAW_COLUMNS
import android.provider.SearchIndexablesContract.INDEXABLES_XML_RES_COLUMNS
import android.provider.SearchIndexablesContract.NON_INDEXABLES_KEYS_COLUMNS
import android.provider.SearchIndexablesProvider

class ConfigPanelSearchIndexablesProvider : SearchIndexablesProvider() {
    override fun onCreate() = true
    override fun queryXmlResources(projection: Array<String?>?): Cursor = MatrixCursor(INDEXABLES_XML_RES_COLUMNS)
    override fun queryNonIndexableKeys(projection: Array<String?>?): Cursor = MatrixCursor(NON_INDEXABLES_KEYS_COLUMNS)
    override fun queryRawData(projection: Array<String?>?): Cursor = MatrixCursor(INDEXABLES_RAW_COLUMNS).apply {
        val providerContext = requireNotNull(context)
        fun addEntry(activity: String, key: String?, title: Int, summary: Int?) {
            val row = arrayOfNulls<Any>(INDEXABLES_RAW_COLUMNS.size)
            row[COLUMN_INDEX_RAW_TITLE] = providerContext.getString(title)
            row[COLUMN_INDEX_RAW_SUMMARY_ON] = summary?.let { providerContext.getString(it) }
            row[COLUMN_INDEX_RAW_KEY] = key
            row[COLUMN_INDEX_RAW_SCREEN_TITLE] = providerContext.getString(title)
            row[COLUMN_INDEX_RAW_INTENT_ACTION] = "android.intent.action.MAIN"
            row[COLUMN_INDEX_RAW_INTENT_TARGET_PACKAGE] = providerContext.packageName
            row[COLUMN_INDEX_RAW_INTENT_TARGET_CLASS] = "${providerContext.packageName}.$activity"
            addRow(row)
        }

        GestureEntries.all.forEach { entry ->
            addEntry(entry.activity, entry.key, entry.title, entry.summary)
        }
        addEntry(
            "StylusAndKeyboardActivity",
            "top_level_stylus_and_keyboard",
            R.string.stylus_and_keyboard_title,
            R.string.stylus_and_keyboard_summary,
        )
        addEntry("StylusSettingsActivity", null, R.string.stylus_title, null)
    }
}
