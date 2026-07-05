package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.FocusDatabase
import com.example.data.FocusSession
import com.example.data.SessionRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FocusViewModel(application: Application) : AndroidViewModel(application) {
    private val db = FocusDatabase.getDatabase(application)
    private val repository = SessionRepository(db.focusSessionDao())

    private val currentUserIdFlow = MutableStateFlow(com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "")

    init {
        viewModelScope.launch {
            com.example.service.FirebaseSyncManager.currentUserUid.collect { uid ->
                currentUserIdFlow.value = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val allSessions: StateFlow<List<FocusSession>> = currentUserIdFlow
        .flatMapLatest { uid ->
            if (uid.isEmpty()) {
                kotlinx.coroutines.flow.flowOf(emptyList())
            } else {
                repository.getSessionsForUser(uid)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val totalFocusMinutes: StateFlow<Int?> = currentUserIdFlow
        .flatMapLatest { uid ->
            if (uid.isEmpty()) {
                kotlinx.coroutines.flow.flowOf(0)
            } else {
                repository.getTotalFocusMinutesForUser(uid)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val completedSessionsCount: StateFlow<Int?> = currentUserIdFlow
        .flatMapLatest { uid ->
            if (uid.isEmpty()) {
                kotlinx.coroutines.flow.flowOf(0)
            } else {
                repository.getCompletedSessionsCountForUser(uid)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    fun clearHistory() {
        val uid = currentUserIdFlow.value
        if (uid.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearAllForUser(uid)
        }
        com.example.service.FirebaseSyncManager.clearSneakAttemptsInCloud(getApplication())
    }

    override fun onCleared() {
        super.onCleared()
    }
}
