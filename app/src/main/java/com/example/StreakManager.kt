package com.example

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object StreakManager {
    private const val PREF_STREAK = "FocusStreakPrefs"
    private const val KEY_CURRENT_STREAK = "current_streak"
    private const val KEY_LAST_SUCCESS_TIME = "last_success_timestamp"

    private val _currentStreak = MutableStateFlow(0)
    val currentStreak = _currentStreak.asStateFlow()

    private val _streakMultiplier = MutableStateFlow(1)
    val streakMultiplier = _streakMultiplier.asStateFlow()

    fun init(context: Context) {
        // Also checks if expired
        getCurrentStreak(context)
    }

    // Call this whenever a student successfully finishes a study session
    fun updateStreakAfterSession(context: Context) {
        val prefs = context.getSharedPreferences(PREF_STREAK, Context.MODE_PRIVATE)
        
        val currentTime = System.currentTimeMillis()
        val lastSuccessTime = prefs.getLong(KEY_LAST_SUCCESS_TIME, 0)
        var streak = prefs.getInt(KEY_CURRENT_STREAK, 0)

        if (lastSuccessTime == 0L) {
            // First time ever completing a session
            streak = 1
        } else {
            val diffMillis = currentTime - lastSuccessTime
            val diffHours = diffMillis / (1000 * 60 * 60)

            if (diffHours >= 12 && diffHours <= 36) {
                // Completed a session in the next day window (12 to 36 hours later) -> Advance streak
                streak++
            } else if (diffHours > 36) {
                // Took too long (broken streak)! Reset back to 1
                streak = 1
            }
            // If diffHours < 12, they are studying multiple times on the same day. Keep the streak as is.
        }

        // Save updated data
        prefs.edit()
            .putInt(KEY_CURRENT_STREAK, streak)
            .putLong(KEY_LAST_SUCCESS_TIME, currentTime)
            .apply()

        _currentStreak.value = streak
        _streakMultiplier.value = calculateMultiplier(streak)
    }

    fun getCurrentStreak(context: Context): Int {
        val prefs = context.getSharedPreferences(PREF_STREAK, Context.MODE_PRIVATE)
        
        // Double-check if the streak expired in the background before displaying it
        val currentTime = System.currentTimeMillis()
        val lastSuccessTime = prefs.getLong(KEY_LAST_SUCCESS_TIME, 0)
        val diffHours = if (lastSuccessTime != 0L) (currentTime - lastSuccessTime) / (1000 * 60 * 60) else 0

        var streak = prefs.getInt(KEY_CURRENT_STREAK, 0)
        if (lastSuccessTime != 0L && diffHours > 36) {
            // Student forgot to study yesterday. Reset streak to 0.
            prefs.edit()
                .putInt(KEY_CURRENT_STREAK, 0)
                .putLong(KEY_LAST_SUCCESS_TIME, 0L)
                .apply()
            streak = 0
        }

        _currentStreak.value = streak
        _streakMultiplier.value = calculateMultiplier(streak)
        return streak
    }

    fun setStreakDirectly(context: Context, streak: Int) {
        val prefs = context.getSharedPreferences(PREF_STREAK, Context.MODE_PRIVATE)
        val successTime = if (streak == 0) 0L else System.currentTimeMillis()
        prefs.edit()
            .putInt(KEY_CURRENT_STREAK, streak)
            .putLong(KEY_LAST_SUCCESS_TIME, successTime)
            .apply()
        _currentStreak.value = streak
        _streakMultiplier.value = calculateMultiplier(streak)
    }

    // Returns a reward multiplier based on their dedication
    fun getStreakMultiplier(context: Context): Int {
        val streak = getCurrentStreak(context)
        return calculateMultiplier(streak)
    }

    private fun calculateMultiplier(streak: Int): Int {
        return when {
            streak >= 5 -> 3 // 5+ Days = 3x Stars
            streak >= 3 -> 2 // 3-4 Days = 2x Stars
            else -> 1        // Default normal rate
        }
    }
}
