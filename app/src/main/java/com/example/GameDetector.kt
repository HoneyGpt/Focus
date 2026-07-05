package com.example

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

object GameDetector {
    fun isAppAGame(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            
            // Check by CATEGORY_GAME (API >= 26)
            val isCategoryGame = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                appInfo.category == ApplicationInfo.CATEGORY_GAME
            } else {
                false
            }
            
            // Check by FLAG_IS_GAME in flags field
            @Suppress("DEPRECATION")
            val isFlagGame = (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0

            isCategoryGame || isFlagGame
        } catch (e: Exception) {
            false
        }
    }
}
