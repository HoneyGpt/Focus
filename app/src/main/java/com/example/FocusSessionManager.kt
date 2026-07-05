package com.example

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object FocusSessionManager {
    private var sessionStartTime = 0L
    
    private val _distractionsThisSession = MutableStateFlow(0)
    val distractionsThisSession = _distractionsThisSession.asStateFlow()

    private val _lastSessionStarsEarned = MutableStateFlow(0)
    val lastSessionStarsEarned = _lastSessionStarsEarned.asStateFlow()

    private val _sessionAppAttempts = mutableMapOf<String, Int>()

    fun startSession() {
        sessionStartTime = System.currentTimeMillis()
        _distractionsThisSession.value = 0
        _lastSessionStarsEarned.value = 0
        _sessionAppAttempts.clear()
    }

    fun getSessionStartTime(): Long = sessionStartTime

    fun getSessionAppAttempts(): Map<String, Int> = _sessionAppAttempts.toMap()

    fun incrementDistractionCount(packageName: String? = null) {
        _distractionsThisSession.value += 1
        packageName?.let {
            _sessionAppAttempts[it] = (_sessionAppAttempts[it] ?: 0) + 1
        }
    }

    fun endSessionAndCalculateStars(context: Context): Int {
        if (sessionStartTime == 0L) return 0

        val durationMillis = System.currentTimeMillis() - sessionStartTime
        val minutesStudied = (durationMillis / (1000 * 60)).toInt()
        
        // 1. Calculate Base Stars: 1 star for every 10 minutes of study
        var baseStars = minutesStudied / 10
        if (baseStars == 0 && minutesStudied > 0) {
            baseStars = 1 // Minimum 1 star for trying
        }

        // 2. Penalty rule: Lose 1 star for every 3 times they got distracted/blocked
        val penalty = _distractionsThisSession.value / 3
        var finalStarsEarned = baseStars - penalty

        // Ensure they never get negative stars for a session
        if (finalStarsEarned < 0) {
            finalStarsEarned = 0
        }

        // 3. APPLY STREAK BONUS IF APPLICABLE
        if (finalStarsEarned > 0) {
            // Update their daily timestamp record first
            StreakManager.updateStreakAfterSession(context)
            
            // Fetch how much their bonus multiplier scales up the reward
            val multiplier = StreakManager.getStreakMultiplier(context)
            finalStarsEarned *= multiplier
        }

        // 4. Save to Persistent Disk Profile
        RewardManager.addStars(context, finalStarsEarned)
        _lastSessionStarsEarned.value = finalStarsEarned
        
        return finalStarsEarned
    }

    fun clearSessionStartTime() {
        sessionStartTime = 0L
    }
}
