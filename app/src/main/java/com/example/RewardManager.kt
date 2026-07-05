package com.example

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object RewardManager {
    private const val PREF_REWARDS = "FocusRewardPrefs"
    private const val KEY_TOTAL_STARS = "total_stars"

    private val _totalStars = MutableStateFlow(0)
    val totalStars = _totalStars.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREF_REWARDS, Context.MODE_PRIVATE)
        _totalStars.value = prefs.getInt(KEY_TOTAL_STARS, 0)
    }

    // Call this when a study session successfully completes
    fun addStars(context: Context, amount: Int) {
        val prefs = context.getSharedPreferences(PREF_REWARDS, Context.MODE_PRIVATE)
        val currentStars = prefs.getInt(KEY_TOTAL_STARS, 0)
        val newStars = currentStars + amount
        prefs.edit().putInt(KEY_TOTAL_STARS, newStars).apply()
        _totalStars.value = newStars
    }

    fun setStarsDirectly(context: Context, amount: Int) {
        val prefs = context.getSharedPreferences(PREF_REWARDS, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_TOTAL_STARS, amount).apply()
        _totalStars.value = amount
    }

    // Call this to display total stars on the profile dashboard
    fun getTotalStars(context: Context): Int {
        val prefs = context.getSharedPreferences(PREF_REWARDS, Context.MODE_PRIVATE)
        val stars = prefs.getInt(KEY_TOTAL_STARS, 0)
        _totalStars.value = stars
        return stars
    }
}
