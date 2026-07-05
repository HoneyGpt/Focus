package com.example

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat

data class HistoryItem(
    val readableAppName: String,
    val packageName: String,
    val clickCount: Int,
    val appIcon: Drawable?
)

object HistoryManager {
    private const val PREF_HISTORY_PREFIX = "FocusHistoryPrefs_"

    private fun getPrefName(context: Context): String? {
        val uid = com.example.service.FirebaseSyncManager.currentUserUid.value
        if (uid.isBlank()) return null
        return "$PREF_HISTORY_PREFIX$uid"
    }

    @JvmStatic
    fun logAttemptedClick(context: Context, packageName: String) {
        val prefName = getPrefName(context) ?: return
        val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val currentCount = prefs.getInt(packageName, 0)
        prefs.edit().putInt(packageName, currentCount + 1).apply()
    }

    @JvmStatic
    fun getClickHistory(context: Context): Map<String, Int> {
        val prefName = getPrefName(context) ?: return emptyMap()
        val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val allEntries = prefs.all
        val historyMap = HashMap<String, Int>()
        for ((key, value) in allEntries) {
            if (value is Int) {
                historyMap[key] = value
            }
        }
        return historyMap
    }

    @JvmStatic
    fun clearHistory(context: Context) {
        val prefName = getPrefName(context) ?: return
        context.getSharedPreferences(prefName, Context.MODE_PRIVATE).edit().clear().apply()
    }

    @JvmStatic
    fun getFormattedHistory(context: Context): List<HistoryItem> {
        val historyList = ArrayList<HistoryItem>()
        val pm = context.packageManager
        val rawHistory = getClickHistory(context)

        for ((packageName, clickCount) in rawHistory) {
            var displayName: String
            var icon: Drawable? = null

            try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                displayName = pm.getApplicationLabel(appInfo).toString()
                icon = pm.getApplicationIcon(appInfo)
            } catch (e: Exception) {
                displayName = packageName
                icon = ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)
            }

            historyList.add(HistoryItem(displayName, packageName, clickCount, icon))
        }

        // Sort by click count descending so most intercepted apps are shown first
        historyList.sortByDescending { it.clickCount }
        return historyList
    }
}
