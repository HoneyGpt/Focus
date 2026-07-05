package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FocusSessionDao {
    @Query("SELECT * FROM focus_sessions WHERE userId = :userId ORDER BY dateTimestamp DESC")
    fun getAllSessions(userId: String): Flow<List<FocusSession>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: FocusSession): Long

    @Query("SELECT * FROM focus_sessions WHERE dateTimestamp = :timestamp LIMIT 1")
    suspend fun getSessionByTimestamp(timestamp: Long): FocusSession?

    @Query("SELECT SUM(durationMinutes) FROM focus_sessions WHERE isCompleted = 1 AND userId = :userId")
    fun getTotalFocusMinutes(userId: String): Flow<Int?>

    @Query("SELECT COUNT(*) FROM focus_sessions WHERE isCompleted = 1 AND userId = :userId")
    fun getCompletedSessionsCount(userId: String): Flow<Int?>

    @Query("DELETE FROM focus_sessions")
    suspend fun clearAll()

    @Query("DELETE FROM focus_sessions WHERE userId = :userId")
    suspend fun clearAllForUser(userId: String)
}
