package com.example.data

import kotlinx.coroutines.flow.Flow

class SessionRepository(private val dao: FocusSessionDao) {
    fun getSessionsForUser(userId: String): Flow<List<FocusSession>> = dao.getAllSessions(userId)
    fun getTotalFocusMinutesForUser(userId: String): Flow<Int?> = dao.getTotalFocusMinutes(userId)
    fun getCompletedSessionsCountForUser(userId: String): Flow<Int?> = dao.getCompletedSessionsCount(userId)

    suspend fun insert(session: FocusSession): Long {
        return dao.insertSession(session)
    }

    suspend fun clearAll() {
        dao.clearAll()
    }

    suspend fun clearAllForUser(userId: String) {
        dao.clearAllForUser(userId)
    }
}
