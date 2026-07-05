package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "focus_sessions")
data class FocusSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topic: String,
    val durationMinutes: Int,
    val dateTimestamp: Long,
    val appsBlockedCount: Int,
    val notificationsMutedCount: Int,
    val isCompleted: Boolean,
    
    // Detailed Study Session Report fields with defaults for compatibility
    val plannedDurationMinutes: Int = 0,
    val actualDurationMinutes: Int = 0,
    val status: String = "Completed", // "Completed" / "Interrupted" / "Ended Early"
    val focusScore: Int = 100,
    val focusLevel: String = "High", // "Low" / "Medium" / "High"
    val starsEarned: Int = 0,
    val appActivityJson: String? = null, // JSON string mapping package names to attempts count
    val totalDistractionAttempts: Int = 0,
    val mostFrequentlyDistractingApp: String? = null,
    val timeChecksCount: Int = 0,
    val trendIndicator: String = "Stable", // "Improved" / "Stable" / "Needs Improvement"
    val isDndActiveDuringSession: Boolean = false,
    val parentSummaryMessage: String = "",
    val userId: String
)
