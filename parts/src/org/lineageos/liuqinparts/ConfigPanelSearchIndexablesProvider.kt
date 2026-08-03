// SPDX-License-Identifier: Apache-2.0
package org.lineageos.liuqinparts

import android.database.Cursor
import android.database.MatrixCursor
import android.provider.SearchIndexablesContract.*
import android.provider.SearchIndexablesProvider

class ConfigPanelSearchIndexablesProvider : SearchIndexablesProvider() {
    override fun onCreate() = true
    override fun queryXmlResources(projection: Array<String?>?): Cursor = MatrixCursor(INDEXABLES_XML_RES_COLUMNS)
    override fun queryNonIndexableKeys(projection: Array<String?>?): Cursor = MatrixCursor(NON_INDEXABLES_KEYS_COLUMNS)
    override fun queryRawData(projection: Array<String?>?): Cursor = MatrixCursor(INDEXABLES_RAW_COLUMNS).apply {
        fun addEntry(activity: String, key: String?, title: Int, summary: Int?) {
            val row = arrayOfNulls<Any>(INDEXABLES_RAW_COLUMNS.size)
            row[COLUMN_INDEX_RAW_TITLE] = context!!.getString(title)
            row[COLUMN_INDEX_RAW_SUMMARY_ON] = summary?.let { context!!.getString(it) }
            row[COLUMN_INDEX_RAW_KEY] = key
            row[COLUMN_INDEX_RAW_SCREEN_TITLE] = context!!.getString(title)
            row[COLUMN_INDEX_RAW_INTENT_ACTION] = "android.intent.action.MAIN"
            row[COLUMN_INDEX_RAW_INTENT_TARGET_PACKAGE] = context!!.packageName
            row[COLUMN_INDEX_RAW_INTENT_TARGET_CLASS] = "${context!!.packageName}.$activity"
            addRow(row)
        }

        GestureEntries.all.forEach { entry ->
            addEntry(entry.activity, entry.key, entry.title, entry.summary)
        }
        addEntry("StylusSettingsActivity", null, R.string.stylus_title, null)
        addEntry("KeyboardSettingsActivity", null, R.string.keyboard_title, null)
    }
}
