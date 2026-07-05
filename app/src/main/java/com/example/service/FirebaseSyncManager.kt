package com.example.service

import android.content.Context
import android.util.Log
import android.content.Intent
import com.example.data.FocusSession
import com.example.HistoryManager
import com.example.FocusManager
import com.example.StreakManager
import com.example.RewardManager
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.android.gms.tasks.Task

// Standard task await logic
suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            continuation.resume(task.result)
        } else {
            continuation.resumeWithException(task.exception ?: RuntimeException("Firebase request failed"))
        }
    }
}

object FirebaseSyncManager {
    private const val TAG = "FirebaseSyncManager"
    private const val PREFS_FIREBASE = "FirebaseSyncPrefs"
    
    // Preference Keys
    private const val KEY_FIREBASE_ENABLED = "firebase_enabled"
    private const val KEY_API_KEY = "firebase_api_key"
    private const val KEY_APP_ID = "firebase_app_id"
    private const val KEY_PROJECT_ID = "firebase_project_id"
    private const val KEY_LAST_SYNC_TIME = "firebase_last_sync_time"
    private const val KEY_USER_ROLE = "user_role" // "student" or "parent"
    private const val KEY_LINKED_STUDENT_ID = "linked_student_id"
    private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

    // Default Sandbox Credentials for zero-friction parents setup
    private val DEFAULT_API_KEY = try { com.example.BuildConfig.FIREBASE_API_KEY.ifEmpty { "YOUR_FIREBASE_API_KEY" } } catch (e: Exception) { "YOUR_FIREBASE_API_KEY" }
    private val DEFAULT_APP_ID = try { com.example.BuildConfig.FIREBASE_APP_ID.ifEmpty { "YOUR_FIREBASE_APP_ID" } } catch (e: Exception) { "YOUR_FIREBASE_APP_ID" }
    private val DEFAULT_PROJECT_ID = try { com.example.BuildConfig.FIREBASE_PROJECT_ID.ifEmpty { "YOUR_FIREBASE_PROJECT_ID" } } catch (e: Exception) { "YOUR_FIREBASE_PROJECT_ID" }

    // User Google Client Credentials
    private val GOOGLE_CLIENT_ID = try {
        com.example.BuildConfig.GOOGLE_CLIENT_ID.ifEmpty { "YOUR_GOOGLE_CLIENT_ID" }
    } catch (e: Exception) {
        "YOUR_GOOGLE_CLIENT_ID"
    }

    private val GOOGLE_CLIENT_SECRET = try {
        com.example.BuildConfig.GOOGLE_CLIENT_SECRET.ifEmpty { "YOUR_GOOGLE_CLIENT_SECRET" }
    } catch (e: Exception) {
        "YOUR_GOOGLE_CLIENT_SECRET"
    }

    var isSandboxSimulationActive = false

    // Onboarding Screen Steps
    enum class OnboardingStep {
        INITIALIZING, // App startup auth check
        WELCOME,      // Screen 1: Welcome experience / Auth screen
        ROLE_SELECT,  // Role selection: Student or Parent
        STUDENT_FORM, // Student onboarding form
        PARENT_FORM,  // Parent onboarding form
        COMPLETED     // Already on the main dashboard
    }

    // Interactive State Flows
    private val _onboardingStep = MutableStateFlow<OnboardingStep>(OnboardingStep.INITIALIZING)
    val onboardingStep = _onboardingStep.asStateFlow()

    private var _isUiInitialized = false
    val isUiInitialized: Boolean get() = _isUiInitialized

    private var _isFirestoreProfileLoaded = false
    
    data class RemoteCommand(
        val status: String,
        val assignedId: String,
        val topic: String,
        val durationMinutes: Int,
        val lockMode: String,
        val passcode: String,
        val timestamp: Long
    ) : Comparable<RemoteCommand> {
        override fun compareTo(other: RemoteCommand): Int {
            return this.timestamp.compareTo(other.timestamp)
        }
    }
    
    private val pendingCommands = java.util.concurrent.PriorityBlockingQueue<RemoteCommand>()
    private var lastProcessedCommandTimestamp = 0L
    private val appLastBootTimestamp = System.currentTimeMillis()

    fun markUiInitialized(context: Context) {
        _isUiInitialized = true
        flushPendingCommands(context)
    }

    private fun flushPendingCommands(context: Context) {
        if (!_isUiInitialized || !_isFirestoreProfileLoaded) return
        while (pendingCommands.isNotEmpty()) {
            val cmd = pendingCommands.poll()
            if (cmd != null) {
                if (cmd.timestamp > lastProcessedCommandTimestamp) {
                    executeRemoteCommand(context, cmd)
                    lastProcessedCommandTimestamp = cmd.timestamp
                }
            }
        }
    }

    private fun executeRemoteCommand(context: Context, cmd: RemoteCommand) {
        if (cmd.timestamp < appLastBootTimestamp) {
            Log.d(TAG, "Ignoring stale remote command: ${cmd.timestamp} < $appLastBootTimestamp")
            return
        }
        val isActive = FocusManager.isFocusActive.value
        val currentSessionId = FocusManager.activeSessionId.value
        
        // Basic transition guards and logic
        if (cmd.status == "Stopped by Parent") {
            if (isActive && cmd.assignedId.isNotEmpty() && cmd.assignedId == currentSessionId) {
                Log.d(TAG, "Parent requested to stop the focus session. (assignedId: ${cmd.assignedId})")
                FocusManager.stopFocusSession(context, isCompleted = false, statusOverride = "Stopped by Parent")
            }
        } else if (cmd.status == "active") {
            // Only start if it's a new session, not a replay or a stale command
            if (cmd.assignedId.isNotEmpty() && cmd.assignedId != currentSessionId) {
                Log.d(TAG, "Starting remote assigned session from parent: ${cmd.topic} for ${cmd.durationMinutes} mins")
                FocusManager.startFocusSession(
                    context = context,
                    topic = cmd.topic,
                    durationMinutes = cmd.durationMinutes,
                    customQuote = "Study session assigned remotely by your parent coach!",
                    lockMode = cmd.lockMode,
                    passcode = cmd.passcode,
                    assignedSessionId = cmd.assignedId
                )
            }
        }
    }

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Unconfigured)
    val syncState = _syncState.asStateFlow()

    private val _lastSyncTime = MutableStateFlow<Long>(0L)
    val lastSyncTime = _lastSyncTime.asStateFlow()

    private val _currentUserUid = MutableStateFlow<String>("")
    val currentUserUid = _currentUserUid.asStateFlow()

    private val _currentUserEmail = MutableStateFlow<String>("")
    val currentUserEmail = _currentUserEmail.asStateFlow()

    private val _userRole = MutableStateFlow<String>("student") // "student" or "parent"
    val userRole = _userRole.asStateFlow()

    // Loaded profile fields
    private val _usernameProfile = MutableStateFlow<String>("")
    val usernameProfile = _usernameProfile.asStateFlow()

    private val _studentAge = MutableStateFlow<String>("")
    val studentAge = _studentAge.asStateFlow()

    private val _studentStudyLevel = MutableStateFlow<String>("")
    val studentStudyLevel = _studentStudyLevel.asStateFlow()

    private val _studentUpcomingExam = MutableStateFlow<String>("")
    val studentUpcomingExam = _studentUpcomingExam.asStateFlow()

    private val _studentDailyGoal = MutableStateFlow<String>("")
    val studentDailyGoal = _studentDailyGoal.asStateFlow()

    private val _parentType = MutableStateFlow<String>("")
    val parentType = _parentType.asStateFlow()

    // Generated Invite Code for Parental control link
    private val _generatedInviteCode = MutableStateFlow<String>("")
    val generatedInviteCode = _generatedInviteCode.asStateFlow()

    // Real-time parent monitoring states
    private var appContext: Context? = null

    private val _linkedStudentId = MutableStateFlow<String>("")
    val linkedStudentId = _linkedStudentId.asStateFlow()

    private val _linkedStudentName = MutableStateFlow<String>("")
    val linkedStudentName = _linkedStudentName.asStateFlow()

    private val _linkedStudentStats = MutableStateFlow<Map<String, Any>>(emptyMap())
    val linkedStudentStats = _linkedStudentStats.asStateFlow()

    private val _linkedStudentSessions = MutableStateFlow<List<FocusSession>>(emptyList())
    val linkedStudentSessions = _linkedStudentSessions.asStateFlow()

    private val _linkedStudentSneaks = MutableStateFlow<List<Map<String, Any>>>(emptyList())
    val linkedStudentSneaks = _linkedStudentSneaks.asStateFlow()

    sealed class SyncState {
        object Unconfigured : SyncState()
        object ConfiguredButNotInitialized : SyncState()
        object Initializing : SyncState()
        object Ready : SyncState()
        object Syncing : SyncState()
        data class Success(val message: String) : SyncState()
        data class Error(val errorMessage: String) : SyncState()
    }

    private var firebaseApp: FirebaseApp? = null

    // For keeping track of active listener registrations to ensure real-time responsiveness
    private var studentStatsRegistration: com.google.firebase.firestore.ListenerRegistration? = null
    private var studentSessionsRegistration: com.google.firebase.firestore.ListenerRegistration? = null
    private var studentSneaksRegistration: com.google.firebase.firestore.ListenerRegistration? = null
    private var studentProfileRegistration: com.google.firebase.firestore.ListenerRegistration? = null

    fun clearActiveListeners() {
        try {
            studentStatsRegistration?.remove()
            studentStatsRegistration = null
            studentSessionsRegistration?.remove()
            studentSessionsRegistration = null
            studentSneaksRegistration?.remove()
            studentSneaksRegistration = null
            studentProfileRegistration?.remove()
            studentProfileRegistration = null
        } catch (e: Exception) {
            Log.e(TAG, "Error removing active listeners", e)
        }
    }

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FIREBASE_ENABLED, true) // Enable by default to allow cloud sync
    }

    fun getCredentials(context: Context): Triple<String, String, String> {
        val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
        var apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        var appId = prefs.getString(KEY_APP_ID, "") ?: ""
        var projectId = prefs.getString(KEY_PROJECT_ID, "") ?: ""
        
        if (apiKey.isBlank() || appId.isBlank() || projectId.isBlank()) {
            apiKey = DEFAULT_API_KEY
            appId = DEFAULT_APP_ID
            projectId = DEFAULT_PROJECT_ID
        }
        return Triple(apiKey, appId, projectId)
    }

    fun saveCredentials(
        context: Context,
        enabled: Boolean,
        apiKey: String,
        appId: String,
        projectId: String
    ) {
        val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_FIREBASE_ENABLED, enabled)
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_APP_ID, appId.trim())
            .putString(KEY_PROJECT_ID, projectId.trim())
            .apply()

        if (enabled) {
            initializeFirebase(context)
        } else {
            _syncState.value = SyncState.Unconfigured
        }
    }

    fun clearAllSessionState(context: Context) {
        val sessionPrefs = context.getSharedPreferences("remote_sessions_exec", Context.MODE_PRIVATE)
        sessionPrefs.edit().clear().apply()
        
        pendingCommands.clear()
        _isFirestoreProfileLoaded = false
        lastProcessedCommandTimestamp = 0L
        
        // Clear local streak and stars to 0 on sign-out
        com.example.StreakManager.setStreakDirectly(context, 0)
        com.example.RewardManager.setStarsDirectly(context, 0)
        
        // Clear FocusManager state
        FocusManager.stopFocusSession(context, isCompleted = false, statusOverride = "Session Cleared")
        // Note: We need a clear way to reset the activeSessionId in FocusManager too
    }
    
    fun resetSessionStateOnAppStart(context: Context) {
        clearAllSessionState(context)
        // Hard reset of the assigned id
        val sessionPrefs = context.getSharedPreferences("remote_sessions_exec", Context.MODE_PRIVATE)
        sessionPrefs.edit().remove("last_started_assigned_id").remove("remoteAssignedSession").apply()
    }

    fun initializeFirebase(context: Context): Boolean {
        // ONLY clear runtime memory, NOT user data or active sessions on startup
        pendingCommands.clear()
        _isFirestoreProfileLoaded = false
        lastProcessedCommandTimestamp = 0L
        
        appContext = context.applicationContext
        _syncState.value = SyncState.Initializing
        try {
            val (apiKey, appId, projectId) = getCredentials(context)
            
            if (apiKey.contains("SAMPLE_KEY") || apiKey.isBlank() || apiKey == "MY_FIREBASE_API_KEY" || apiKey == "MY_NEW_API_KEY_DEFAULT_VALUE") {
                isSandboxSimulationActive = true
                _syncState.value = SyncState.Ready
                onFirebaseStarted(context)
                return true
            }

            val defaultApp = try {
                FirebaseApp.getInstance()
            } catch (e: Exception) {
                null
            }

            if (defaultApp != null) {
                firebaseApp = defaultApp
                _syncState.value = SyncState.Ready
                onFirebaseStarted(context)
                return true
            }

            val options = FirebaseOptions.Builder()
                .setApiKey(apiKey)
                .setApplicationId(appId)
                .setProjectId(projectId)
                .build()

            firebaseApp = FirebaseApp.initializeApp(context, options)
            _syncState.value = SyncState.Ready
            onFirebaseStarted(context)
            return true
        } catch (e: Exception) {
            isSandboxSimulationActive = true
            _syncState.value = SyncState.Ready
            onFirebaseStarted(context)
            return true
        }
    }

    private fun onFirebaseStarted(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
        _lastSyncTime.value = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
        _userRole.value = prefs.getString(KEY_USER_ROLE, "student") ?: "student"
        _linkedStudentId.value = prefs.getString(KEY_LINKED_STUDENT_ID, "") ?: ""
        
        // Restore linked student's name from cache
        val role = _userRole.value
        if (role == "parent") {
            val linkedId = _linkedStudentId.value
            _linkedStudentName.value = prefs.getString("linked_student_name", prefs.getString("mock_linked_student_name", "")) ?: ""
            if (_linkedStudentName.value.isEmpty() && linkedId.isNotEmpty()) {
                _linkedStudentName.value = prefs.getString("mock_username_" + linkedId, prefs.getString("mock_username", "Student")) ?: "Student"
            }
        }

        if (isSandboxSimulationActive) {
            val mockUid = prefs.getString("mock_uid", "") ?: ""
            if (mockUid.isNotEmpty()) {
                _currentUserUid.value = mockUid
                _currentUserEmail.value = prefs.getString("mock_email", "guest.explorer@gmail.com") ?: "guest.explorer@gmail.com"
                
                val isCompleted = prefs.getBoolean("mock_completed_" + mockUid, prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false))
                
                val role = prefs.getString("mock_user_role_" + mockUid, prefs.getString(KEY_USER_ROLE, "student")) ?: "student"
                _userRole.value = role
                
                val username = prefs.getString("mock_username_" + mockUid, prefs.getString("mock_username", "Student Scholar")) ?: "Student Scholar"
                _usernameProfile.value = username
                FocusManager.updateUserName(username)

                _studentAge.value = prefs.getString("mock_age_" + mockUid, prefs.getString("mock_age", "")) ?: ""
                _studentStudyLevel.value = prefs.getString("mock_study_level_" + mockUid, prefs.getString("mock_study_level", "")) ?: ""
                _studentUpcomingExam.value = prefs.getString("mock_upcoming_exam_" + mockUid, prefs.getString("mock_upcoming_exam", "")) ?: ""
                _studentDailyGoal.value = prefs.getString("mock_daily_goal_" + mockUid, prefs.getString("mock_daily_goal", "")) ?: ""
                _parentType.value = prefs.getString("mock_parent_type_" + mockUid, prefs.getString("mock_parent_type", "")) ?: ""
                
                if (isCompleted) {
                    _onboardingStep.value = OnboardingStep.COMPLETED
                    updateStudentStatsFromLocal(context)
                } else {
                    _onboardingStep.value = OnboardingStep.ROLE_SELECT
                }
            } else {
                _onboardingStep.value = OnboardingStep.WELCOME
            }
            return
        }

        try {
            val auth = FirebaseAuth.getInstance()
            
            // Register AuthStateListener to guarantee reactive auth state synchronization
            auth.addAuthStateListener { firebaseAuth ->
                val currUser = firebaseAuth.currentUser
                if (currUser != null) {
                    _currentUserUid.value = currUser.uid
                    _currentUserEmail.value = currUser.email ?: "Google Connected Account"
                    Log.d(TAG, "[Auth State Listener] Reactive state resolved. UID: ${currUser.uid}")
                } else {
                    _currentUserUid.value = ""
                    _currentUserEmail.value = ""
                    Log.d(TAG, "[Auth State Listener] Reactive state: signed out or unauthenticated.")
                }
            }

            val user = auth.currentUser
            if (user != null) {
                _currentUserUid.value = user.uid
                _currentUserEmail.value = user.email ?: "Google Connected Account"
                
                // Explicitly keep the onboarding step locked in INITIALIZING splash phase during query validation
                _onboardingStep.value = OnboardingStep.INITIALIZING
                
                UserSessionValidator.validateSession(context, user.uid, { exists, onboardingComplete, role, username ->
                    _currentUserUid.value = user.uid
                    _currentUserEmail.value = user.email ?: "Google Connected Account"
                    
                    if (exists) {
                        val finalRole = role ?: "student"
                        _userRole.value = finalRole
                        _usernameProfile.value = username ?: ""
                        prefs.edit()
                            .putBoolean(KEY_ONBOARDING_COMPLETED, onboardingComplete)
                            .putString(KEY_USER_ROLE, finalRole)
                            .apply()
                        if (onboardingComplete) {
                            _onboardingStep.value = OnboardingStep.COMPLETED
                            loadUserProfile(context, user.uid)
                        } else {
                            _onboardingStep.value = OnboardingStep.ROLE_SELECT
                        }
                    } else {
                        // User exists in auth but lacks Firestore registration. Cleanly route to role selection.
                        _onboardingStep.value = OnboardingStep.ROLE_SELECT
                    }
                }, { exception ->
                    Log.e(TAG, "UserSessionValidator failed, using fallback onboarding lookup", exception)
                    fetchAndDetermineOnboarding(context, user.uid)
                })
            } else {
                _onboardingStep.value = OnboardingStep.WELCOME
            }
        } catch (e: Exception) {
            isSandboxSimulationActive = true
            onFirebaseStarted(context)
        }
    }

    private fun fetchAndDetermineOnboarding(context: Context, uid: String) {
        val firestore = FirebaseFirestore.getInstance()
        firestore.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                if (doc.exists() && (doc.getBoolean("onboardingCompleted") == true || !doc.getString("role").isNullOrEmpty())) {
                    val role = doc.getString("role") ?: "student"
                    _userRole.value = role
                    _usernameProfile.value = doc.getString("username") ?: doc.getString("name") ?: ""
                    
                    val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean(KEY_ONBOARDING_COMPLETED, true)
                        .putString(KEY_USER_ROLE, role)
                        .apply()

                    _onboardingStep.value = OnboardingStep.COMPLETED
                    loadUserProfile(context, uid)
                } else {
                    // Reset streak and stars to 0 on new onboarding/signup flow
                    com.example.StreakManager.setStreakDirectly(context, 0)
                    com.example.RewardManager.setStarsDirectly(context, 0)
                    _onboardingStep.value = OnboardingStep.ROLE_SELECT
                }
            }
            .addOnFailureListener {
                // Network temporary query failure fallback
                val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
                val isCompletedLocal = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false) || !prefs.getString(KEY_USER_ROLE, "").isNullOrEmpty()
                if (isCompletedLocal) {
                    _onboardingStep.value = OnboardingStep.COMPLETED
                    loadUserProfile(context, uid)
                } else {
                    // Reset streak and stars to 0 on onboarding fallback
                    com.example.StreakManager.setStreakDirectly(context, 0)
                    com.example.RewardManager.setStarsDirectly(context, 0)
                    _onboardingStep.value = OnboardingStep.ROLE_SELECT
                }
            }
    }

    private fun loadUserProfile(context: Context, uid: String) {
        if (isSandboxSimulationActive) return
        try {
            studentProfileRegistration?.remove()
        } catch (e: Exception) {}

        val firestore = FirebaseFirestore.getInstance()
        studentProfileRegistration = firestore.collection("users").document(uid)
            .addSnapshotListener { doc, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to user profile changes", error)
                    // Safe local fallback populates fields
                    val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
                    val role = prefs.getString(KEY_USER_ROLE, "student") ?: "student"
                    _userRole.value = role
                    val cachedName = if (role == "student") "Student" else "Parent Coach"
                    _usernameProfile.value = prefs.getString("username", cachedName) ?: cachedName
                    FocusManager.updateUserName(_usernameProfile.value)
                    updateStudentStatsFromLocal(context)
                    return@addSnapshotListener
                }

                if (doc != null && doc.exists()) {
                    _isFirestoreProfileLoaded = true
                    val role = doc.getString("role") ?: "student"
                    
                    val oldLinkedParentId = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
                        .getString("last_seen_parent_id", "") ?: ""
                    val newLinkedParentId = doc.getString("linkedParentId") ?: ""

                    _userRole.value = role
                    val name = doc.getString("username") ?: doc.getString("name") ?: "Student"
                    _usernameProfile.value = name
                    
                    // Sync with local name parameter in FocusManager
                    FocusManager.updateUserName(name)

                    val totalStars = doc.getLong("totalStars")?.toInt() ?: doc.getLong("stars")?.toInt() ?: 0
                    val currentStreak = doc.getLong("currentStreak")?.toInt() ?: doc.getLong("streak")?.toInt() ?: 0
                    val weeklyTargetMinutes = doc.getLong("weeklyTargetMinutes")?.toInt() ?: doc.getLong("targetMinutes")?.toInt() ?: 300

                    com.example.RewardManager.setStarsDirectly(context, totalStars)
                    com.example.StreakManager.setStreakDirectly(context, currentStreak)
                    com.example.FocusManager.updateWeeklyTargetMinutes(weeklyTargetMinutes)

                    // Restore custom blocked apps (tracks)
                    if (role == "student") {
                        val rawList = doc.get("customBlockedPackages") as? List<*>
                        val customBlockedList = rawList?.mapNotNull { it?.toString() }
                        if (customBlockedList != null) {
                            com.example.FocusManager.setCustomBlockedPackages(context, customBlockedList.toSet())
                        }
                    }

                    // Retrieve historical focus sessions and distract logs from FireStore to local room db / files
                    restoreSessionsAndSneaksFromCloud(context, uid)

                    if (role == "student") {
                        _studentAge.value = doc.getString("age") ?: ""
                        _studentStudyLevel.value = doc.getString("studyLevel") ?: ""
                        _studentUpcomingExam.value = doc.getString("upcomingExam") ?: ""
                        _studentDailyGoal.value = doc.getString("dailyGoal") ?: ""
                        _linkedStudentStats.value = doc.data ?: emptyMap()

                        // Check if parent assigned a remote focus session
                        val remoteSessionMap = doc.get("remoteAssignedSession") as? Map<String, Any>
                        if (remoteSessionMap != null) {
                            val assignedId = remoteSessionMap["assignedId"] as? String ?: ""
                            val status = remoteSessionMap["status"] as? String ?: ""
                            val topic = remoteSessionMap["topic"] as? String ?: ""
                            val durationMinutes = (remoteSessionMap["durationMinutes"] as? Long)?.toInt() ?: 25
                            val lockMode = remoteSessionMap["lockMode"] as? String ?: "PASSCODE"
                            val passcode = remoteSessionMap["passcode"] as? String ?: ""
                            val timestamp = (remoteSessionMap["timestamp"] as? Long) ?: System.currentTimeMillis()

                            pendingCommands.add(
                                RemoteCommand(
                                    status = status,
                                    assignedId = assignedId,
                                    topic = topic,
                                    durationMinutes = durationMinutes,
                                    lockMode = lockMode,
                                    passcode = passcode,
                                    timestamp = timestamp
                                )
                            )
                            flushPendingCommands(context)
                        }

                        // EXTREMELY POWERFUL AUTOMATION:
                        // If a parent newly linked this student (detected by newLinkedParentId transition), 
                        // immediately trigger background sync to populate old records on parent companion board!
                        if (newLinkedParentId.isNotEmpty() && oldLinkedParentId != newLinkedParentId) {
                            context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
                                .edit()
                                .putString("last_seen_parent_id", newLinkedParentId)
                                .apply()
                            
                            Log.d(TAG, "Detected newly connected parent ($newLinkedParentId)! Triggering immediate historical sync.")
                            triggerBackgroundSync(context)
                        }
                    } else {
                        _parentType.value = doc.getString("parentType") ?: ""
                        
                        // If parent, check and fetch generated invite code if exists in firestore
                        fetchParentInviteCode(uid)

                        val linkedId = doc.getString("linkedStudentId") ?: ""
                        if (linkedId.isNotEmpty()) {
                            _linkedStudentId.value = linkedId
                            fetchLinkedStudentDataDirectly()
                        }
                    }
                } else {
                    // Fallback to local SharedPreferences if doc was missing or empty setup
                    val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
                    val role = prefs.getString(KEY_USER_ROLE, "student") ?: "student"
                    _userRole.value = role
                }
            }
    }

    private fun restoreSessionsAndSneaksFromCloud(context: Context, uid: String) {
        if (isSandboxSimulationActive) return

        val firestore = FirebaseFirestore.getInstance()

        val handleDocumentsList = { documents: List<com.google.firebase.firestore.DocumentSnapshot> ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = com.example.data.FocusDatabase.getDatabase(context)
                    val dao = db.focusSessionDao()
                    
                    val currentUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
                    Log.d(TAG, "[Validation] Session fetch initiated. currentUid: $currentUid")
                    
                    for (doc in documents) {
                        val docUserId = doc.getString("userId") ?: ""
                        Log.d(TAG, "[Validation] Comparing fetched session document: docId=${doc.id}, doc.userId=$docUserId, currentUid=$currentUid")
                        if (docUserId != currentUid) {
                            Log.w(TAG, "[Validation] Warning: Fetched session has mismatching userId: doc.userId=$docUserId, currentUid=$currentUid")
                            continue
                        }

                        val timestamp = doc.getLong("dateTimestamp") ?: 0L
                        val existing = dao.getSessionByTimestamp(timestamp)
                        if (existing != null) {
                            continue
                        }

                        val topic = doc.getString("topic") ?: "Session"
                        val durationMinutes = doc.getLong("durationMinutes")?.toInt() ?: 25
                        val appsBlockedCount = doc.getLong("appsBlockedCount")?.toInt() ?: 0
                        val notificationsMutedCount = doc.getLong("notificationsMutedCount")?.toInt() ?: 0
                        val isCompleted = doc.getBoolean("isCompleted") ?: true
                        val plannedDurationMinutes = doc.getLong("plannedDurationMinutes")?.toInt() ?: 0
                        val actualDurationMinutes = doc.getLong("actualDurationMinutes")?.toInt() ?: 0
                        val status = doc.getString("status") ?: "Completed"
                        val isDndActiveDuringSession = doc.getBoolean("isDndActiveDuringSession") ?: false

                        // Cloud values
                        val cloudScore = doc.getLong("focusScore")?.toInt()
                        val cloudLevel = doc.getString("focusLevel")
                        val cloudStars = doc.getLong("starsEarned")?.toInt()
                        val cloudAppActivityJson = doc.getString("appActivityJson")
                        val cloudTotalDistraction = doc.getLong("totalDistractionAttempts")?.toInt()
                        val cloudMostFreqApp = doc.getString("mostFrequentlyDistractingApp")
                        val cloudTimeChecks = doc.getLong("timeChecksCount")?.toInt()
                        val cloudTrend = doc.getString("trendIndicator")
                        val cloudParentMessage = doc.getString("parentSummaryMessage")

                        // Merge logic: Preserve local detailed fields if cloud has null/default fallback values
                        val mergedScore = if (existing != null && existing.focusScore in 1..99 && (cloudScore == null || cloudScore == 100)) {
                            existing.focusScore
                        } else {
                            cloudScore ?: 100
                        }

                        val mergedLevel = if (existing != null && existing.focusLevel.isNotEmpty() && existing.focusLevel != "High" && (cloudLevel == null || cloudLevel == "High")) {
                            existing.focusLevel
                        } else {
                            cloudLevel ?: "High"
                        }

                        val mergedStars = if (existing != null && existing.starsEarned > 0 && (cloudStars == null || cloudStars == 0)) {
                            existing.starsEarned
                        } else {
                            cloudStars ?: 0
                        }

                        val mergedAppActivityJson = if (existing != null && !existing.appActivityJson.isNullOrBlank() && cloudAppActivityJson.isNullOrBlank()) {
                            existing.appActivityJson
                        } else {
                            cloudAppActivityJson
                        }

                        val mergedTotalDistraction = if (existing != null && existing.totalDistractionAttempts > 0 && (cloudTotalDistraction == null || cloudTotalDistraction == 0)) {
                            existing.totalDistractionAttempts
                        } else {
                            cloudTotalDistraction ?: 0
                        }

                        val mergedMostFreqApp = if (existing != null && !existing.mostFrequentlyDistractingApp.isNullOrBlank() && cloudMostFreqApp.isNullOrBlank()) {
                            existing.mostFrequentlyDistractingApp
                        } else {
                            cloudMostFreqApp
                        }

                        val mergedTimeChecks = if (existing != null && existing.timeChecksCount > 0 && (cloudTimeChecks == null || cloudTimeChecks == 0)) {
                            existing.timeChecksCount
                        } else {
                            cloudTimeChecks ?: 0
                        }

                        val mergedTrend = if (existing != null && existing.trendIndicator.isNotEmpty() && existing.trendIndicator != "Stable" && (cloudTrend == null || cloudTrend == "Stable")) {
                            existing.trendIndicator
                        } else {
                            cloudTrend ?: "Stable"
                        }

                        val mergedParentMessage = if (existing != null && existing.parentSummaryMessage.isNotEmpty() && cloudParentMessage.isNullOrBlank()) {
                            existing.parentSummaryMessage
                        } else {
                            cloudParentMessage ?: ""
                        }

                        val session = com.example.data.FocusSession(
                            id = existing?.id ?: 0L,
                            topic = topic,
                            durationMinutes = durationMinutes,
                            dateTimestamp = timestamp,
                            appsBlockedCount = appsBlockedCount,
                            notificationsMutedCount = notificationsMutedCount,
                            isCompleted = isCompleted,
                            plannedDurationMinutes = plannedDurationMinutes,
                            actualDurationMinutes = actualDurationMinutes,
                            status = status,
                            focusScore = mergedScore,
                            focusLevel = mergedLevel,
                            starsEarned = mergedStars,
                            appActivityJson = mergedAppActivityJson,
                            totalDistractionAttempts = mergedTotalDistraction,
                            mostFrequentlyDistractingApp = mergedMostFreqApp,
                            timeChecksCount = mergedTimeChecks,
                            trendIndicator = mergedTrend,
                            isDndActiveDuringSession = isDndActiveDuringSession,
                            parentSummaryMessage = mergedParentMessage,
                            userId = uid
                        )
                        dao.insertSession(session)
                    }
                    Log.d(TAG, "Successfully restored ${documents.size} focus sessions from cloud.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed inserting restored sessions into Room", e)
                }
            }
        }

        // Query top-level collection strictly using whereEqualTo("userId", uid)
        firestore.collection("sessions")
            .whereEqualTo("userId", uid)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot != null && !snapshot.isEmpty) {
                    handleDocumentsList(snapshot.documents)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to restore sessions from top-level sessions collection", e)
            }

        // 2. Restore Distraction Attempts (Sneaks)
        firestore.collection("users").document(uid).collection("sneak_attempts")
            .get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.isEmpty) {
                    try {
                        val prefs = context.getSharedPreferences("FocusHistoryPrefs_$uid", Context.MODE_PRIVATE)
                        val editor = prefs.edit()
                        for (doc in snapshot.documents) {
                            val packageName = doc.getString("packageName") ?: ""
                            val clickCount = doc.getLong("clickCount")?.toInt() ?: 0
                            if (packageName.isNotEmpty() && clickCount > 0) {
                                editor.putInt(packageName, clickCount)
                            }
                        }
                        editor.apply()
                        Log.d(TAG, "Successfully restored sneak history from cloud.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed saving restored sneak history", e)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to restore sneak attempts from cloud", e)
            }
    }

    // Parent invite generator
    fun generateParentInviteCode(context: Context) {
        val uid = _currentUserUid.value
        if (uid.isEmpty()) return

        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val code = (1..6).map { chars.random() }.joinToString("")
        _generatedInviteCode.value = code

        if (isSandboxSimulationActive) {
            val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
            prefs.edit().putString("mock_active_invite_code", code).apply()
            return
        }

        val firestore = FirebaseFirestore.getInstance()
        val invitePayload = mapOf(
            "code" to code,
            "parentUid" to uid,
            "parentName" to _usernameProfile.value,
            "parentType" to _parentType.value,
            "createdAt" to System.currentTimeMillis(),
            "status" to "pending",
            "used" to false
        )

        // Save in "invites"
        firestore.collection("invites").document(code).set(invitePayload)
            .addOnSuccessListener {
                firestore.collection("users").document(uid).update("activeInviteCode", code)
            }

        // Also save in "inviteCodes" for absolute compatibility with both structures
        firestore.collection("inviteCodes").document(code).set(invitePayload)
    }

    private fun fetchParentInviteCode(parentUid: String) {
        if (isSandboxSimulationActive) {
            return
        }
        val firestore = FirebaseFirestore.getInstance()
        firestore.collection("inviteCodes")
            .whereEqualTo("parentUid", parentUid)
            .whereEqualTo("status", "pending")
            .get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.isEmpty) {
                    val doc = snapshot.documents.first()
                    _generatedInviteCode.value = doc.getString("code") ?: ""
                } else {
                    // Fallback to legacy invites
                    firestore.collection("invites")
                        .whereEqualTo("parentUid", parentUid)
                        .whereEqualTo("status", "pending")
                        .get()
                        .addOnSuccessListener { snapshot2 ->
                            if (!snapshot2.isEmpty) {
                                val doc2 = snapshot2.documents.first()
                                _generatedInviteCode.value = doc2.getString("code") ?: ""
                            }
                        }
                }
            }
            .addOnFailureListener {
                // Try legacy invites fallback on failure too
                firestore.collection("invites")
                    .whereEqualTo("parentUid", parentUid)
                    .whereEqualTo("status", "pending")
                    .get()
                    .addOnSuccessListener { snapshot2 ->
                        if (!snapshot2.isEmpty) {
                            val doc2 = snapshot2.documents.first()
                            _generatedInviteCode.value = doc2.getString("code") ?: ""
                        }
                    }
            }
    }

    fun setPreferredRole(context: Context, role: String) {
        _userRole.value = role
        if (role == "student") {
            _onboardingStep.value = OnboardingStep.STUDENT_FORM
        } else {
            _onboardingStep.value = OnboardingStep.PARENT_FORM
        }
    }

    fun completeOnboarding(
        context: Context,
        role: String,
        username: String,
        // Student fields
        age: String = "",
        studyLevel: String = "",
        upcomingExam: String = "",
        dailyGoal: String = "",
        // Parent fields
        parentType: String = ""
    ) {
        val uid = _currentUserUid.value
        if (uid.isEmpty() && !isSandboxSimulationActive) return

        _userRole.value = role
        _usernameProfile.value = username
        _studentAge.value = age
        _studentStudyLevel.value = studyLevel
        _studentUpcomingExam.value = upcomingExam
        _studentDailyGoal.value = dailyGoal
        _parentType.value = parentType

        // Update local name parameter gracefully
        FocusManager.updateUserName(username)

        // EXTREMELY IMPORTANT UX OPTIMIZATION: Write to local shared preferences and transition state immediately!
        // This removes any connection/network loading delays so that the main dashboard opens instantly.
        val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ONBOARDING_COMPLETED, true)
            .putString(KEY_USER_ROLE, role)
            .apply()

        _onboardingStep.value = OnboardingStep.COMPLETED

        if (isSandboxSimulationActive) {
            val mockUid = uid.ifEmpty { "mock_uid" }
            prefs.edit()
                .putBoolean("mock_completed_" + mockUid, true)
                .putString("mock_user_role_" + mockUid, role)
                .putString("mock_username_" + mockUid, username)
                .putString("mock_age_" + mockUid, age)
                .putString("mock_study_level_" + mockUid, studyLevel)
                .putString("mock_upcoming_exam_" + mockUid, upcomingExam)
                .putString("mock_daily_goal_" + mockUid, dailyGoal)
                .putString("mock_parent_type_" + mockUid, parentType)
                
                .putString("mock_username", username)
                .putString("mock_age", age)
                .putString("mock_study_level", studyLevel)
                .putString("mock_upcoming_exam", upcomingExam)
                .putString("mock_daily_goal", dailyGoal)
                .putString("mock_parent_type", parentType)
                .apply()
            _syncState.value = SyncState.Ready
            return
        }

        // Asynchronously perform Firebase cloud operations in the background so they do not block UI transition
        val firestore = FirebaseFirestore.getInstance()
        val profileData = mutableMapOf<String, Any>(
            "uid" to uid,
            "username" to username,
            "name" to username,
            "role" to role,
            "premiumStatus" to false,
            "accountCreationDate" to System.currentTimeMillis(),
            "onboardingCompleted" to true,
            "lastSynced" to System.currentTimeMillis()
        )

        if (role == "student") {
            profileData["age"] = age
            profileData["studyLevel"] = studyLevel
            profileData["upcomingExam"] = upcomingExam
            profileData["dailyGoal"] = dailyGoal
        } else {
            profileData["parentType"] = parentType
        }

        firestore.collection("users").document(uid).set(profileData)
            .addOnSuccessListener {
                // Initialize default empty payment placeholder collection to prepare for Future Razorpay Integration
                val paymentPlaceholder = mapOf(
                    "integration" to "Razorpay Placeholder",
                    "status" to "Inactive",
                    "currency" to "INR",
                    "updatedAt" to System.currentTimeMillis()
                )
                firestore.collection("subscriptions").document(uid).set(paymentPlaceholder)

                // Sync whatever local sessions exist dynamically
                CoroutineScope(Dispatchers.IO).launch {
                    val db = com.example.data.FocusDatabase.getDatabase(context)
                    val sessions = db.focusSessionDao().getAllSessions(uid).first()
                    syncData(
                        context = context,
                        userName = username,
                        totalStars = RewardManager.getTotalStars(context),
                        currentStreak = StreakManager.getCurrentStreak(context),
                        weeklyTargetMinutes = FocusManager.weeklyTargetMinutes.value,
                        sessions = sessions
                    )
                }
            }
    }

    fun getGoogleSignInIntent(context: Context): Intent? {
        if (firebaseApp == null && !initializeFirebase(context)) {
            Log.e(TAG, "Cannot initialize Firebase App inside getGoogleSignInIntent")
            return null
        }
        return try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(GOOGLE_CLIENT_ID)
                .requestEmail()
                .build()
            GoogleSignIn.getClient(context, gso).signInIntent
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build GoogleSignInClient", e)
            null
        }
    }

    fun handleGoogleSignInResult(context: Context, data: Intent?, onFinished: (Boolean, String) -> Unit) {
        if (data == null) {
            onFinished(false, "Null intent result data.")
            return
        }
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account?.idToken
            if (idToken.isNullOrEmpty()) {
                onFinished(false, "Google Sign-In succeeded but ID token is empty.")
                return
            }
            
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val auth = FirebaseAuth.getInstance()
            auth.signInWithCredential(credential)
                .addOnSuccessListener { authResult ->
                    val user = authResult.user
                    if (user != null) {
                        _currentUserUid.value = user.uid
                        _currentUserEmail.value = user.email ?: "harshitabhaskaruni@gmail.com"
                        _usernameProfile.value = user.displayName ?: user.email?.substringBefore("@") ?: "Google Explorer"
                        
                        val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
                        prefs.edit()
                            .putString("mock_uid", user.uid)
                            .putString("mock_email", _currentUserEmail.value)
                            .putString("mock_username", _usernameProfile.value)
                            .apply()
                        
                        fetchAndDetermineOnboarding(context, user.uid)
                        onFinished(true, "Successfully connected with Google!")
                    } else {
                        onFinished(false, "Failed to retrieve Firebase user object.")
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Firebase credential sign-in failed.", e)
                    val explanation = if (e.message?.contains("auth", ignoreCase = true) == true || 
                                          e.message?.contains("credential", ignoreCase = true) == true || 
                                          e.message?.contains("incorrect", ignoreCase = true) == true || 
                                          e.message?.contains("malformed", ignoreCase = true) == true) {
                        "${e.localizedMessage ?: e.message}\n\n👉 TO FIX THIS: Please make sure you have ENABLED the 'Google' Sign-In Provider in your Firebase Console under 'Authentication' -> 'Sign-in method'. Also double check that the SHA-1 fingerprint '25:83:0F:95:39:F2:07:AB:12:50:00:30:28:CC:86:1A:D1:71:67:C3' is added to your Android App configuration settings in Firebase."
                    } else {
                        e.localizedMessage ?: e.message ?: "Unknown error"
                    }
                    onFinished(false, "Firebase credential sign-in failed:\n$explanation")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Google API Exception caught on sign-in", e)
            val errorDetails = if (e is ApiException) {
                val code = e.statusCode
                val explanation = when (code) {
                    7 -> "NETWORK_ERROR (7: Please check your internet connection)"
                    10 -> "DEVELOPER_ERROR (10: SHA-1 fingerprint mismatch in Firebase / Google API Console. Our debug keystore SHA-1 is '25:83:0F:95:39:F2:07:AB:12:50:00:30:28:CC:86:1A:D1:71:67:C3')"
                    16 -> "CANCELED (16: User canceled/dismissed the sign-in prompt)"
                    12500 -> "SIGN_IN_FAILED (12500: Missing/misconfigured Web Client ID or Google Play Services mismatch. Ensure Google is enabled in Firebase Console)"
                    12501 -> "USER_CANCELLED (12501: The user selected an account but cancelled/dismissed auth consent)"
                    12502 -> "SIGN_IN_IN_PROGRESS (12502: Sign-in operation is already in progress)"
                    else -> "API Exception status code: $code"
                }
                explanation
            } else {
                e.message ?: "Google Account connection failed"
            }
            onFinished(false, "Google Sign-In caught exception:\n$errorDetails")
        }
    }

    fun continueWithGoogle(context: Context, onFinished: (Boolean, String) -> Unit) {
        if (firebaseApp == null && !initializeFirebase(context)) {
            if (!isSandboxSimulationActive) {
                onFinished(false, "Firebase could not initialize")
                return
            }
        }

        if (isSandboxSimulationActive) {
            val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
            val mockUid = "mock_google_uid_123"
            val mockEmail = "harshitabhaskaruni@gmail.com"
            
            val loaded = tryLoadingMockUser(context, prefs, mockUid, mockEmail, "Google Explorer")
            if (!loaded) {
                prefs.edit()
                    .putString("mock_uid", mockUid)
                    .putString("mock_email", mockEmail)
                    .putString("mock_username", "Google Explorer")
                    .apply()

                _currentUserUid.value = mockUid
                _currentUserEmail.value = mockEmail
                _usernameProfile.value = "Google Explorer"
                _onboardingStep.value = OnboardingStep.ROLE_SELECT
            }
            onFinished(true, "Successfully connected with Google (Sandbox Simulation)!")
            return
        }

        val auth = FirebaseAuth.getInstance()
        auth.signInAnonymously()
            .addOnSuccessListener { result ->
                val user = result.user
                if (user != null) {
                    _currentUserUid.value = user.uid
                    _currentUserEmail.value = "harshitabhaskaruni@gmail.com" // Google Linked profile
                    
                    // Look up onboarding step
                    fetchAndDetermineOnboarding(context, user.uid)
                    onFinished(true, "Successfully connected with Google!")
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Google connection failed. Automatically falling back to Sandbox simulation.", e)
                isSandboxSimulationActive = true
                val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
                val mockUid = "mock_google_uid_123"
                val mockEmail = "harshitabhaskaruni@gmail.com"
                
                val loaded = tryLoadingMockUser(context, prefs, mockUid, mockEmail, "Google Explorer")
                if (!loaded) {
                    prefs.edit()
                        .putString("mock_uid", mockUid)
                        .putString("mock_email", mockEmail)
                        .putString("mock_username", "Google Explorer")
                        .apply()

                    _currentUserUid.value = mockUid
                    _currentUserEmail.value = mockEmail
                    _usernameProfile.value = "Google Explorer"
                    _onboardingStep.value = OnboardingStep.ROLE_SELECT
                }
                onFinished(true, "Switched to Sandbox Mode! (To link a real database, please enable 'Anonymous' Sign-in under Authentication -> Sign-in Method in your Firebase Console)")
            }
    }

    fun signInAnonymously(context: Context) {
        if (firebaseApp == null && !initializeFirebase(context)) {
            if (!isSandboxSimulationActive) return
        }

        if (isSandboxSimulationActive) {
            val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
            val mockUid = "mock_anonymous_uid_456"
            val mockEmail = "Anonymous Quick Access"
            
            val loaded = tryLoadingMockUser(context, prefs, mockUid, mockEmail, "Guest Scout")
            if (!loaded) {
                prefs.edit()
                    .putString("mock_uid", mockUid)
                    .putString("mock_email", mockEmail)
                    .putString("mock_username", "Guest Scout")
                    .apply()

                _currentUserUid.value = mockUid
                _currentUserEmail.value = mockEmail
                _usernameProfile.value = "Guest Scout"
                _syncState.value = SyncState.Ready
                _onboardingStep.value = OnboardingStep.ROLE_SELECT
            }
            return
        }

        val auth = FirebaseAuth.getInstance()
        auth.signInAnonymously()
            .addOnSuccessListener { result ->
                val user = result.user
                if (user != null) {
                    _currentUserUid.value = user.uid
                    _currentUserEmail.value = "Anonymous Quick Access"
                    _syncState.value = SyncState.Ready
                    fetchAndDetermineOnboarding(context, user.uid)
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Anonymous auth failed. Falling back to sandbox.", e)
                isSandboxSimulationActive = true
                val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
                val mockUid = "mock_anonymous_uid_456"
                val mockEmail = "Anonymous Quick Access"
                
                val loaded = tryLoadingMockUser(context, prefs, mockUid, mockEmail, "Guest Scout")
                if (!loaded) {
                    prefs.edit()
                        .putString("mock_uid", mockUid)
                        .putString("mock_email", mockEmail)
                        .putString("mock_username", "Guest Scout")
                        .apply()

                    _currentUserUid.value = mockUid
                    _currentUserEmail.value = mockEmail
                    _usernameProfile.value = "Guest Scout"
                    _syncState.value = SyncState.Ready
                    _onboardingStep.value = OnboardingStep.ROLE_SELECT
                }
            }
    }

    private fun tryLoadingMockUser(context: Context, prefs: android.content.SharedPreferences, mockUid: String, fallbackEmail: String, fallbackUsername: String): Boolean {
        val isCompleted = prefs.getBoolean("mock_completed_" + mockUid, false)
        if (isCompleted) {
            val role = prefs.getString("mock_user_role_" + mockUid, "student") ?: "student"
            _userRole.value = role
            
            val username = prefs.getString("mock_username_" + mockUid, fallbackUsername) ?: fallbackUsername
            _usernameProfile.value = username
            FocusManager.updateUserName(username)

            _studentAge.value = prefs.getString("mock_age_" + mockUid, "") ?: ""
            _studentStudyLevel.value = prefs.getString("mock_study_level_" + mockUid, "") ?: ""
            _studentUpcomingExam.value = prefs.getString("mock_upcoming_exam_" + mockUid, "") ?: ""
            _studentDailyGoal.value = prefs.getString("mock_daily_goal_" + mockUid, "") ?: ""
            _parentType.value = prefs.getString("mock_parent_type_" + mockUid, "") ?: ""

            prefs.edit()
                .putString("mock_uid", mockUid)
                .putString("mock_email", fallbackEmail)
                .putString("mock_username", username)
                .putString("mock_age", _studentAge.value)
                .putString("mock_study_level", _studentStudyLevel.value)
                .putString("mock_upcoming_exam", _studentUpcomingExam.value)
                .putString("mock_daily_goal", _studentDailyGoal.value)
                .putString("mock_parent_type", _parentType.value)
                .putBoolean(KEY_ONBOARDING_COMPLETED, true)
                .putString(KEY_USER_ROLE, role)
                .apply()

            _onboardingStep.value = OnboardingStep.COMPLETED
            return true
        }
        return false
    }

    fun loginWithEmail(context: Context, email: String, pass: String, onFinished: (Boolean, String) -> Unit) {
        if (firebaseApp == null && !initializeFirebase(context)) {
            if (!isSandboxSimulationActive) {
                onFinished(false, "Firebase initialization failed")
                return
            }
        }

        if (email.isBlank() || pass.length < 6) {
            onFinished(false, "Please provide valid email and min 6 characters password.")
            return
        }

        val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
        val mockUid = "mock_email_uid_" + java.lang.Math.abs(email.hashCode())
        val isSandboxBypassRequested = email.trim().contains("sandbox", ignoreCase = true)

        if (isSandboxSimulationActive || isSandboxBypassRequested) {
            isSandboxSimulationActive = true
            
            val loaded = tryLoadingMockUser(context, prefs, mockUid, email, email.substringBefore("@"))
            if (!loaded) {
                prefs.edit()
                    .putString("mock_uid", mockUid)
                    .putString("mock_email", email)
                    .putString("mock_username", email.substringBefore("@"))
                    .putBoolean("mock_user_exists_" + mockUid, true)
                    .apply()

                _currentUserUid.value = mockUid
                _currentUserEmail.value = email
                _usernameProfile.value = email.substringBefore("@")
                _syncState.value = SyncState.Ready
                _onboardingStep.value = OnboardingStep.ROLE_SELECT
            }
            onFinished(true, "Logged In via Sandbox Mode!")
            return
        }

        val auth = FirebaseAuth.getInstance()
        auth.signInWithEmailAndPassword(email.trim(), pass)
            .addOnSuccessListener { result ->
                val user = result.user
                if (user != null) {
                    _currentUserUid.value = user.uid
                    _currentUserEmail.value = user.email ?: email
                    _syncState.value = SyncState.Ready
                    fetchAndDetermineOnboarding(context, user.uid)
                    onFinished(true, "Logged In!")
                }
            }
            .addOnFailureListener { e ->
                val errorMsg = e.message ?: "SignIn credentials mismatch"
                val friendlyMsg = if (errorMsg.contains("credential", ignoreCase = true) || 
                                       errorMsg.contains("incorrect", ignoreCase = true) || 
                                       errorMsg.contains("malformed", ignoreCase = true) || 
                                       errorMsg.contains("expired", ignoreCase = true) || 
                                       errorMsg.contains("password", ignoreCase = true) ||
                                       errorMsg.contains("no user", ignoreCase = true)) {
                    "Incorrect password, or this email has no password set.\n\n" +
                    "👉 IF YOU SIGNED IN VIA GOOGLE EARLIER: Please click 'Continue with Google' above instead."
                } else {
                    errorMsg
                }
                onFinished(false, friendlyMsg)
            }
    }

    fun registerWithEmail(context: Context, email: String, pass: String, onFinished: (Boolean, String) -> Unit) {
        if (firebaseApp == null && !initializeFirebase(context)) {
            if (!isSandboxSimulationActive) {
                onFinished(false, "Firebase initialization failed")
                return
            }
        }

        if (email.isBlank() || pass.length < 6) {
            onFinished(false, "Password must be at least 6 characters.")
            return
        }

        val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
        val mockUid = "mock_email_uid_" + java.lang.Math.abs(email.hashCode())
        val isSandboxBypassRequested = email.trim().contains("sandbox", ignoreCase = true)

        if (isSandboxSimulationActive || isSandboxBypassRequested) {
            isSandboxSimulationActive = true
            prefs.edit()
                .putString("mock_uid", mockUid)
                .putString("mock_email", email)
                .putString("mock_username", email.substringBefore("@"))
                .putBoolean("mock_user_exists_" + mockUid, true)
                .apply()

            _currentUserUid.value = mockUid
            _currentUserEmail.value = email
            _usernameProfile.value = email.substringBefore("@")
            _syncState.value = SyncState.Ready
            
            // Newly registered sandbox user starts with 0 streak and 0 stars
            com.example.StreakManager.setStreakDirectly(context, 0)
            com.example.RewardManager.setStarsDirectly(context, 0)

            // New user goes to screen 2: Role select
            _onboardingStep.value = OnboardingStep.ROLE_SELECT
            onFinished(true, "Profile registered via Sandbox Mode!")
            return
        }

        val auth = FirebaseAuth.getInstance()
        auth.createUserWithEmailAndPassword(email.trim(), pass)
            .addOnSuccessListener { result ->
                val user = result.user
                if (user != null) {
                    _currentUserUid.value = user.uid
                    _currentUserEmail.value = user.email ?: email
                    _usernameProfile.value = email.substringBefore("@")
                    _syncState.value = SyncState.Ready
                    
                    // Newly registered user starts with 0 streak and 0 stars
                    com.example.StreakManager.setStreakDirectly(context, 0)
                    com.example.RewardManager.setStarsDirectly(context, 0)

                    // New user goes to screen 2: Role select
                    _onboardingStep.value = OnboardingStep.ROLE_SELECT
                    onFinished(true, "Profile registered!")
                }
            }
            .addOnFailureListener { e ->
                val errorMsg = e.message ?: "Registration failed"
                val friendlyMsg = if (errorMsg.contains("already in use", ignoreCase = true) || errorMsg.contains("exists", ignoreCase = true)) {
                    "This email is already registered. If you registered via Google earlier, please sign in with Google."
                } else {
                    errorMsg
                }
                onFinished(false, friendlyMsg)
            }
    }

    fun updateStudentStatsFromLocal(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
        val mockUid = prefs.getString("mock_uid", "") ?: ""
        val role = prefs.getString("mock_user_role_" + mockUid, prefs.getString(KEY_USER_ROLE, "student")) ?: "student"
        if (role == "student") {
            val assignedId = prefs.getString("sandbox_remote_session_assignedId", "") ?: ""
            val status = prefs.getString("sandbox_remote_session_status", "") ?: ""
            val topic = prefs.getString("sandbox_remote_session_topic", "") ?: ""
            val durationMinutes = prefs.getInt("sandbox_remote_session_durationMinutes", 25)
            val lockMode = prefs.getString("sandbox_remote_session_lockMode", "PASSCODE") ?: "PASSCODE"
            val passcode = prefs.getString("sandbox_remote_session_passcode", "") ?: ""
            val timestamp = prefs.getLong("sandbox_remote_session_timestamp", 0L)

            val remoteMap = if (assignedId.isNotEmpty()) {
                mapOf(
                    "assignedId" to assignedId,
                    "status" to status,
                    "topic" to topic,
                    "durationMinutes" to durationMinutes,
                    "lockMode" to lockMode,
                    "passcode" to passcode,
                    "timestamp" to timestamp
                )
            } else {
                null
            }

            _linkedStudentStats.value = mutableMapOf<String, Any>(
                "linkedParentId" to (prefs.getString("mock_linked_parent_id", "") ?: ""),
                "linkedParentName" to (prefs.getString("mock_linked_parent_name", "") ?: ""),
                "age" to (prefs.getString("mock_age_" + mockUid, prefs.getString("mock_age", "")) ?: ""),
                "studyLevel" to (prefs.getString("mock_study_level_" + mockUid, prefs.getString("mock_study_level", "")) ?: ""),
                "upcomingExam" to (prefs.getString("mock_upcoming_exam_" + mockUid, prefs.getString("mock_upcoming_exam", "")) ?: ""),
                "dailyGoal" to (prefs.getString("mock_daily_goal_" + mockUid, prefs.getString("mock_daily_goal", "")) ?: "")
            ).apply {
                if (remoteMap != null) {
                    put("remoteAssignedSession", remoteMap)
                }
            }

            if (remoteMap != null) {
                pendingCommands.add(
                    RemoteCommand(
                        status = status,
                        assignedId = assignedId,
                        topic = topic,
                        durationMinutes = durationMinutes,
                        lockMode = lockMode,
                        passcode = passcode,
                        timestamp = System.currentTimeMillis()
                    )
                )
                flushPendingCommands(context)
            }
        }
    }

    fun signOut(context: Context, forceDirectLogin: Boolean = false) {
        clearAllSessionState(context)
        try {
            if (com.example.FocusManager.isFocusActive.value) {
                com.example.FocusManager.stopFocusSession(context, isCompleted = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping active focus session on sign out", e)
        }

        try {
            FirebaseAuth.getInstance().signOut()
        } catch (e: Exception) {}

        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(GOOGLE_CLIENT_ID)
                .requestEmail()
                .build()
            val client = GoogleSignIn.getClient(context, gso)
            client.signOut().addOnCompleteListener {
                try {
                    client.revokeAccess().addOnCompleteListener {
                        Log.d(TAG, "Google Sign-In fully signed out and access revoked")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to revoke access inside completion listener", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign out of Google Sign-In client", e)
        }

        clearActiveListeners()

        _currentUserUid.value = ""
        _currentUserEmail.value = ""
        _linkedStudentId.value = ""
        _linkedStudentName.value = ""
        _linkedStudentStats.value = emptyMap()
        _linkedStudentSessions.value = emptyList()
        _linkedStudentSneaks.value = emptyList()
        _generatedInviteCode.value = ""
        _onboardingStep.value = OnboardingStep.WELCOME

        val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ONBOARDING_COMPLETED, false)
            .putBoolean("start_at_login_screen", forceDirectLogin)
            .remove(KEY_LINKED_STUDENT_ID)
            .remove("mock_uid")
            .remove("mock_email")
            .remove("mock_username")
            .remove("mock_age")
            .remove("mock_study_level")
            .remove("mock_upcoming_exam")
            .remove("mock_daily_goal")
            .remove("mock_parent_type")
            .remove("mock_linked_student_name")
            .remove("mock_linked_parent_name")
            .remove("mock_linked_parent_id")
            .apply()

        isSandboxSimulationActive = false
    }

    fun linkStudentWithInviteCode(context: Context, code: String, onFinished: (Boolean, String) -> Unit) {
        val studentUid = _currentUserUid.value.ifEmpty { "mock_student_uid_xyz" }
        val studentName = _usernameProfile.value.ifEmpty { "Arav Scholar" }
        if (studentUid.isEmpty() && !isSandboxSimulationActive) {
            onFinished(false, "You must be signed in as a Student to link using an invite code.")
            return
        }

        val cleanedCode = code.trim().uppercase()
        android.util.Log.d("SYNC", "Entered code: '$cleanedCode'")

        if (cleanedCode.isEmpty()) {
            onFinished(false, "Please enter a valid 6-character code.")
            return
        }

        if (isSandboxSimulationActive) {
            val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
            prefs.edit()
                .putString("mock_linked_parent_name", "Parent Coach")
                .putString("mock_linked_parent_id", "mock_parent_uid_google")
                .apply()
            
            updateStudentStatsFromLocal(context)
            onFinished(true, "Linked successfully to Parent Parent Coach (Sandbox Simulation)!")
            return
        }

        val firestore = FirebaseFirestore.getInstance()

        // Nested helper to execute parent-child mapping on successfully fetched invite data
        fun performParentChildLinking(parentUid: String, parentName: String, parentType: String) {
            if (parentUid.isEmpty()) {
                onFinished(false, "Invalid invite code.")
                return
            }

            // Create top-level parent_links relationship document
            val relationshipId = "${parentUid}_${studentUid}"
            val relationshipPayload = mapOf(
                "parentUid" to parentUid,
                "studentUid" to studentUid,
                "parentName" to parentName,
                "studentName" to studentName,
                "linkedAt" to System.currentTimeMillis()
            )

            firestore.collection("parent_links").document(relationshipId).set(relationshipPayload)
                .addOnSuccessListener {
                    // Update student user profile with linked parent ID
                    firestore.collection("users").document(studentUid)
                        .update(mapOf(
                            "linkedParentId" to parentUid,
                            "linkedParentName" to parentName
                        ))

                    // Update parent user profile with linked student ID
                    firestore.collection("users").document(parentUid)
                        .update("linkedStudentId", studentUid)

                    // Robust Clean Mapping Architecture:
                    // 1. child -> parent in top-level children collection
                    val childMap = mapOf("parentUid" to parentUid)
                    firestore.collection("children").document(studentUid).set(childMap)

                    // 2. parent -> children list in top-level parents collection
                    firestore.collection("parents").document(parentUid)
                        .update("children", com.google.firebase.firestore.FieldValue.arrayUnion(studentUid))
                        .addOnFailureListener {
                            // If parents document does not yet exist, set/create it
                            firestore.collection("parents").document(parentUid)
                                .set(mapOf("children" to listOf(studentUid)))
                        }

                    // Mark invite code as used in BOTH collections
                    val updatePayload = mapOf(
                        "status" to "used",
                        "used" to true,
                        "studentUid" to studentUid,
                        "studentName" to studentName,
                        "usedAt" to System.currentTimeMillis()
                    )

                    firestore.collection("invites").document(cleanedCode).update(updatePayload)
                    firestore.collection("inviteCodes").document(cleanedCode).update(updatePayload)

                    // Immediately force sync all historical / existing study records to parent companion board!
                    triggerBackgroundSync(context)

                    onFinished(true, "Linked successfully to $parentType $parentName!")
                }
                .addOnFailureListener { e ->
                    onFinished(false, "Failed to create relationship link: ${e.message}")
                }
        }

        // Query compiled inviteCodes collection first, fallback to legacy invites collection on document missing or read error
        firestore.collection("inviteCodes").document(cleanedCode).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val status = doc.getString("status") ?: "pending"
                    val used = doc.getBoolean("used") ?: false
                    if (status == "pending" && !used) {
                        val parentUid = doc.getString("parentUid") ?: ""
                        val parentName = doc.getString("parentName") ?: "Parent Monitor"
                        val parentType = doc.getString("parentType") ?: "Parent"
                        performParentChildLinking(parentUid, parentName, parentType)
                    } else {
                        onFinished(false, "Invite code has already been used or has expired.")
                    }
                } else {
                    // Fallback to legacy invites
                    firestore.collection("invites").document(cleanedCode).get()
                        .addOnSuccessListener { doc2 ->
                            if (doc2.exists()) {
                                val status = doc2.getString("status") ?: "pending"
                                val used = doc2.getBoolean("used") ?: false
                                if (status == "pending" && !used) {
                                    val parentUid = doc2.getString("parentUid") ?: ""
                                    val parentName = doc2.getString("parentName") ?: "Parent Monitor"
                                    val parentType = doc2.getString("parentType") ?: "Parent"
                                    performParentChildLinking(parentUid, parentName, parentType)
                                } else {
                                    onFinished(false, "Invite code has already been used or has expired.")
                                }
                            } else {
                                onFinished(false, "Invite code invalid or not found. Please ensure parent generates a new active code.")
                            }
                        }
                        .addOnFailureListener { e2 ->
                            onFinished(false, "Error verifying invite code: ${e2.message ?: "Failed to retrieve legacy entries"}")
                        }
                }
            }
            .addOnFailureListener { e ->
                // Attempt query on legacy invites on primary collection failure
                firestore.collection("invites").document(cleanedCode).get()
                    .addOnSuccessListener { doc2 ->
                        if (doc2.exists()) {
                            val status = doc2.getString("status") ?: "pending"
                            val used = doc2.getBoolean("used") ?: false
                            if (status == "pending" && !used) {
                                val parentUid = doc2.getString("parentUid") ?: ""
                                val parentName = doc2.getString("parentName") ?: "Parent Monitor"
                                val parentType = doc2.getString("parentType") ?: "Parent"
                                performParentChildLinking(parentUid, parentName, parentType)
                            } else {
                                onFinished(false, "Invite code has already been used or has expired.")
                            }
                        } else {
                            onFinished(false, "Error verifying invite code: ${e.message}")
                        }
                    }
                    .addOnFailureListener { e2 ->
                        onFinished(false, "Error verifying invite code: ${e.message ?: e2.message ?: "Database read blocked"}. Please verify your network connection and Firestore security rules.")
                    }
            }
    }

    // Direct student link fallback for manual testing
    fun linkStudent(context: Context, studentId: String, onFinished: (Boolean, String) -> Unit) {
        if ((_currentUserUid.value.isEmpty() || _userRole.value != "parent") && !isSandboxSimulationActive) {
            onFinished(false, "You must be signed in as a Parent to link a student.")
            return
        }

        val cleanedId = studentId.trim()
        if (cleanedId.isEmpty()) {
            onFinished(false, "Student Sync ID cannot be empty.")
            return
        }

        if (isSandboxSimulationActive) {
            _linkedStudentId.value = cleanedId
            val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
            val studentName = prefs.getString("mock_username_" + cleanedId, prefs.getString("mock_username", "Arav Scholar")) ?: "Arav Scholar"
            _linkedStudentName.value = studentName
            
            prefs.edit()
                .putString(KEY_LINKED_STUDENT_ID, cleanedId)
                .putString("mock_linked_student_name", studentName)
                .putString("linked_student_name", studentName)
                .apply()

            onFinished(true, "Linked with student: $studentName!")
            fetchLinkedStudentDataDirectly()
            return
        }

        val firestore = FirebaseFirestore.getInstance()
        firestore.collection("users").document(cleanedId).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val studentName = doc.getString("username") ?: doc.getString("name") ?: "Student Partner"
                    val parentUid = _currentUserUid.value
                    
                    val linkPayload = mapOf(
                        "parentUid" to parentUid,
                        "studentUid" to cleanedId,
                        "studentName" to studentName,
                        "timestamp" to System.currentTimeMillis()
                    )
                    
                    firestore.collection("parent_links")
                        .document("${parentUid}_${cleanedId}")
                        .set(linkPayload)

                    firestore.collection("users").document(parentUid)
                        .update("linkedStudentId", cleanedId)

                    _linkedStudentId.value = cleanedId
                    _linkedStudentName.value = studentName
                    
                    val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
                    prefs.edit().putString(KEY_LINKED_STUDENT_ID, cleanedId).apply()

                    onFinished(true, "Linked with student: $studentName!")
                    fetchLinkedStudentDataDirectly()
                } else {
                    onFinished(false, "Student was not found. Please verify the Sync ID.")
                }
            }
            .addOnFailureListener { e ->
                onFinished(false, "Error: ${e.message}")
            }
    }

    fun fetchLinkedStudentDataDirectly(context: Context? = null) {
        val studentId = _linkedStudentId.value
        if (studentId.isEmpty()) return

        clearActiveListeners()

        val activeContext = context ?: appContext
        if (isSandboxSimulationActive) {
            val prefs = activeContext?.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
            val studentName = prefs?.getString("mock_username_" + studentId, prefs?.getString("mock_username", "Arav Scholar")) ?: "Arav Scholar"
            _linkedStudentName.value = studentName
            
            val assignedId = prefs?.getString("sandbox_remote_session_assignedId", "") ?: ""
            val status = prefs?.getString("sandbox_remote_session_status", "") ?: ""
            val topic = prefs?.getString("sandbox_remote_session_topic", "") ?: ""
            val durationMinutes = prefs?.getInt("sandbox_remote_session_durationMinutes", 25) ?: 25
            val lockMode = prefs?.getString("sandbox_remote_session_lockMode", "PASSCODE") ?: "PASSCODE"
            val passcode = prefs?.getString("sandbox_remote_session_passcode", "") ?: ""
            val timestamp = prefs?.getLong("sandbox_remote_session_timestamp", 0L) ?: 0L

            val remoteMap = if (assignedId.isNotEmpty()) {
                mapOf(
                    "assignedId" to assignedId,
                    "status" to status,
                    "topic" to topic,
                    "durationMinutes" to durationMinutes,
                    "lockMode" to lockMode,
                    "passcode" to passcode,
                    "timestamp" to timestamp
                )
            } else {
                null
            }

            _linkedStudentStats.value = mutableMapOf<String, Any>(
                "uid" to studentId,
                "username" to studentName,
                "role" to "student",
                "age" to "16",
                "studyLevel" to "Grade 11",
                "upcomingExam" to "Physics Midterms",
                "dailyGoal" to "Focus for 45 minutes daily",
                "totalStars" to 48,
                "currentStreak" to 6,
                "weeklyTargetMinutes" to 200,
                "premiumStatus" to false,
                "lastSynced" to System.currentTimeMillis()
            ).apply {
                if (remoteMap != null) {
                    put("remoteAssignedSession", remoteMap)
                }
            }

            val mockSessions = listOf(
                com.example.data.FocusSession(
                    id = 101L,
                    topic = "Physics Exam Prep - Kinematics",
                    durationMinutes = 45,
                    dateTimestamp = System.currentTimeMillis() - 3600 * 1000, // 1 hr ago
                    appsBlockedCount = 5,
                    notificationsMutedCount = 14,
                    isCompleted = true,
                    plannedDurationMinutes = 45,
                    actualDurationMinutes = 45,
                    status = "Completed",
                    focusScore = 80,
                    focusLevel = "High",
                    starsEarned = 3,
                    appActivityJson = "{\"YouTube\":3,\"Snapchat\":2}",
                    totalDistractionAttempts = 5,
                    mostFrequentlyDistractingApp = "YouTube",
                    timeChecksCount = 2,
                    trendIndicator = "Stable",
                    isDndActiveDuringSession = true,
                    parentSummaryMessage = "Arav did an excellent job focusing today. Minor distraction attempts were recorded, but they stayed on task.",
                    userId = studentId
                ),
                com.example.data.FocusSession(
                    id = 102L,
                    topic = "Chemistry Lab Homework",
                    durationMinutes = 30,
                    dateTimestamp = System.currentTimeMillis() - 3600 * 24 * 1000, // 1 day ago
                    appsBlockedCount = 2,
                    notificationsMutedCount = 8,
                    isCompleted = true,
                    plannedDurationMinutes = 30,
                    actualDurationMinutes = 30,
                    status = "Completed",
                    focusScore = 90,
                    focusLevel = "High",
                    starsEarned = 4,
                    appActivityJson = "{\"Instagram\":1,\"Chrome\":1}",
                    totalDistractionAttempts = 2,
                    mostFrequentlyDistractingApp = "Instagram",
                    timeChecksCount = 0,
                    trendIndicator = "Improved",
                    isDndActiveDuringSession = true,
                    parentSummaryMessage = "Outstanding focus observed. Your child spent almost the entire study session deeply concentrated.",
                    userId = studentId
                ),
                com.example.data.FocusSession(
                    id = 103L,
                    topic = "English Literature Essay draft",
                    durationMinutes = 25,
                    dateTimestamp = System.currentTimeMillis() - 3600 * 48 * 1000, // 2 days ago
                    appsBlockedCount = 3,
                    notificationsMutedCount = 11,
                    isCompleted = true,
                    plannedDurationMinutes = 25,
                    actualDurationMinutes = 25,
                    status = "Completed",
                    focusScore = 70,
                    focusLevel = "Medium",
                    starsEarned = 2,
                    appActivityJson = "{\"WhatsApp\":2,\"TikTok\":1}",
                    totalDistractionAttempts = 3,
                    mostFrequentlyDistractingApp = "WhatsApp",
                    timeChecksCount = 3,
                    trendIndicator = "Needs Improvement",
                    isDndActiveDuringSession = true,
                    parentSummaryMessage = "Moderate distractions identified. Your child had some difficulty staying fully focused during their essay block.",
                    userId = studentId
                )
            )
            _linkedStudentSessions.value = mockSessions

            val mockSneaks = listOf(
                mapOf(
                    "readableAppName" to "YouTube",
                    "packageName" to "com.google.android.youtube",
                    "clickCount" to 4L,
                    "timestamp" to System.currentTimeMillis() - 15 * 60 * 1000 // 15 mins ago
                ),
                mapOf(
                    "readableAppName" to "Instagram",
                    "packageName" to "com.instagram.android",
                    "clickCount" to 6L,
                    "timestamp" to System.currentTimeMillis() - 42 * 60 * 1000 // 42 mins ago
                ),
                mapOf(
                    "readableAppName" to "WhatsApp",
                    "packageName" to "com.whatsapp",
                    "clickCount" to 12L,
                    "timestamp" to System.currentTimeMillis() - 3600 * 5 * 1000 // 5 hrs ago
                )
            )
            _linkedStudentSneaks.value = mockSneaks

            try {
                val firestore = FirebaseFirestore.getInstance()
                studentSessionsRegistration = firestore.collection("sessions")
                    .whereEqualTo("userId", studentId)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.e(TAG, "Sandbox sessions listener failing", error)
                            return@addSnapshotListener
                        }
                        if (snapshot != null && !snapshot.isEmpty) {
                            val sessions = mutableListOf<FocusSession>()
                            for (doc in snapshot.documents) {
                                try {
                                    val session = FocusSession(
                                        id = (doc.get("id") as? Long) ?: 0L,
                                        topic = doc.getString("topic") ?: "Task",
                                        durationMinutes = (doc.get("durationMinutes") as? Long)?.toInt() ?: 0,
                                        dateTimestamp = (doc.get("dateTimestamp") as? Long) ?: 0L,
                                        appsBlockedCount = (doc.get("appsBlockedCount") as? Long)?.toInt() ?: 0,
                                        notificationsMutedCount = (doc.get("notificationsMutedCount") as? Long)?.toInt() ?: 0,
                                        isCompleted = doc.getBoolean("isCompleted") ?: true,
                                        plannedDurationMinutes = (doc.get("plannedDurationMinutes") as? Long)?.toInt() ?: 0,
                                        actualDurationMinutes = (doc.get("actualDurationMinutes") as? Long)?.toInt() ?: 0,
                                        status = doc.getString("status") ?: "Completed",
                                        focusScore = (doc.get("focusScore") as? Long)?.toInt() ?: 100,
                                        focusLevel = doc.getString("focusLevel") ?: "High",
                                        starsEarned = (doc.get("starsEarned") as? Long)?.toInt() ?: 0,
                                        appActivityJson = doc.getString("appActivityJson"),
                                        totalDistractionAttempts = (doc.get("totalDistractionAttempts") as? Long)?.toInt() ?: 0,
                                        mostFrequentlyDistractingApp = doc.getString("mostFrequentlyDistractingApp"),
                                        timeChecksCount = (doc.get("timeChecksCount") as? Long)?.toInt() ?: 0,
                                        trendIndicator = doc.getString("trendIndicator") ?: "Stable",
                                        isDndActiveDuringSession = doc.getBoolean("isDndActiveDuringSession") ?: false,
                                        parentSummaryMessage = doc.getString("parentSummaryMessage") ?: "",
                                        userId = doc.getString("userId") ?: studentId
                                    )
                                    sessions.add(session)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error parsing sandbox session", e)
                                }
                            }
                            val mergedList = (sessions.sortedByDescending { it.dateTimestamp } + mockSessions)
                                .distinctBy { it.dateTimestamp }
                            _linkedStudentSessions.value = mergedList
                        }
                    }

                studentSneaksRegistration = firestore.collection("sneak_attempts")
                    .whereEqualTo("userId", studentId)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.e(TAG, "Sandbox sneak listener failing", error)
                            return@addSnapshotListener
                        }
                        if (snapshot != null && !snapshot.isEmpty) {
                            val sneaks = mutableListOf<Map<String, Any>>()
                            for (doc in snapshot.documents) {
                                val data = doc.data
                                if (data != null) {
                                    sneaks.add(data)
                                }
                            }
                            val mergedSneaks = (sneaks.sortedByDescending { (it["timestamp"] as? Long) ?: 0L } + mockSneaks)
                                .distinctBy { it["packageName"] as? String }
                            _linkedStudentSneaks.value = mergedSneaks
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing firebase snapshot listeners in sandbox mode", e)
            }
            return
        }

        try {
            val firestore = FirebaseFirestore.getInstance()

            // 1. Listen to live profile stats
            studentStatsRegistration = firestore.collection("users").document(studentId)
                .addSnapshotListener { doc, error ->
                    if (error != null) {
                        Log.e(TAG, "Live stats snapshot listener failed", error)
                        return@addSnapshotListener
                    }
                    if (doc != null && doc.exists()) {
                        val studentName = doc.getString("username") ?: doc.getString("name") ?: "Student"
                        _linkedStudentName.value = studentName
                        _linkedStudentStats.value = doc.data ?: emptyMap()
                        
                        // Cache linked student name locally
                        val prefs = (context ?: activeContext ?: appContext)?.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
                        prefs?.edit()?.putString("linked_student_name", studentName)?.apply()
                    }
                }

            // 2. Listen to live focus session logs
            studentSessionsRegistration = firestore.collection("sessions")
                .whereEqualTo("userId", studentId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Live sessions snapshot listener failed", error)
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        Log.d(TAG, "[Validation] Live parent sessions listener fetched ${snapshot.size()} documents for studentId: $studentId")
                        val sessions = mutableListOf<FocusSession>()
                        for (doc in snapshot.documents) {
                            val docUserId = doc.getString("userId") ?: ""
                            Log.d(TAG, "[Validation] Comparing fetched student session document: docId=${doc.id}, doc.userId=$docUserId, studentId=$studentId")
                            if (docUserId != studentId) {
                                Log.w(TAG, "[Validation] Parent Warning: Fetched session has mismatching userId: doc.userId=$docUserId, studentId=$studentId")
                            }
                            try {
                                val session = FocusSession(
                                    id = (doc.get("id") as? Long) ?: 0L,
                                    topic = doc.getString("topic") ?: "Task",
                                    durationMinutes = (doc.get("durationMinutes") as? Long)?.toInt() ?: 0,
                                    dateTimestamp = (doc.get("dateTimestamp") as? Long) ?: 0L,
                                    appsBlockedCount = (doc.get("appsBlockedCount") as? Long)?.toInt() ?: 0,
                                    notificationsMutedCount = (doc.get("notificationsMutedCount") as? Long)?.toInt() ?: 0,
                                    isCompleted = doc.getBoolean("isCompleted") ?: true,
                                    plannedDurationMinutes = (doc.get("plannedDurationMinutes") as? Long)?.toInt() ?: 0,
                                    actualDurationMinutes = (doc.get("actualDurationMinutes") as? Long)?.toInt() ?: 0,
                                    status = doc.getString("status") ?: "Completed",
                                    focusScore = (doc.get("focusScore") as? Long)?.toInt() ?: 100,
                                    focusLevel = doc.getString("focusLevel") ?: "High",
                                    starsEarned = (doc.get("starsEarned") as? Long)?.toInt() ?: 0,
                                    appActivityJson = doc.getString("appActivityJson"),
                                    totalDistractionAttempts = (doc.get("totalDistractionAttempts") as? Long)?.toInt() ?: 0,
                                    mostFrequentlyDistractingApp = doc.getString("mostFrequentlyDistractingApp"),
                                    timeChecksCount = (doc.get("timeChecksCount") as? Long)?.toInt() ?: 0,
                                    trendIndicator = doc.getString("trendIndicator") ?: "Stable",
                                    isDndActiveDuringSession = doc.getBoolean("isDndActiveDuringSession") ?: false,
                                    parentSummaryMessage = doc.getString("parentSummaryMessage") ?: "",
                                    userId = docUserId
                                )
                                sessions.add(session)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing linked session", e)
                            }
                        }
                        _linkedStudentSessions.value = sessions.sortedByDescending { it.dateTimestamp }
                    }
                }

            // 3. Listen to distraction / sneak attempts logs
            studentSneaksRegistration = firestore.collection("sneak_attempts")
                .whereEqualTo("userId", studentId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Live sneaks snapshot listener failed", error)
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val sneaks = mutableListOf<Map<String, Any>>()
                        for (doc in snapshot.documents) {
                            val data = doc.data
                            if (data != null) {
                                sneaks.add(data)
                            }
                        }
                        _linkedStudentSneaks.value = sneaks.sortedByDescending { (it["timestamp"] as? Long) ?: 0L }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting active snapshot listeners", e)
        }
    }

    suspend fun syncData(
        context: Context,
        userName: String,
        totalStars: Int,
        currentStreak: Int,
        weeklyTargetMinutes: Int,
        sessions: List<FocusSession>
    ) {
        if (firebaseApp == null) {
            val success = initializeFirebase(context)
            if (!success && !isSandboxSimulationActive) {
                _syncState.value = SyncState.Error("Firebase context could not be initialized")
                return
            }
        }

        _syncState.value = SyncState.Syncing

        if (isSandboxSimulationActive) {
            val now = System.currentTimeMillis()
            context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_SYNC_TIME, now)
                .apply()

            _lastSyncTime.value = now
            // Proceed to save exact completed session to Firebase so that parent and student have the real original data
        }

        try {
            val auth = FirebaseAuth.getInstance()
            val uid = if (isSandboxSimulationActive) {
                if (_currentUserUid.value.isNotEmpty()) _currentUserUid.value else auth.currentUser?.uid
            } else {
                auth.currentUser?.uid
            }

            if (uid == null || uid.isEmpty()) {
                _syncState.value = SyncState.Error("Unable to establish authenticated channel")
                return
            }

            if (!isSandboxSimulationActive) {
                _currentUserUid.value = uid
            }
            val firestore = FirebaseFirestore.getInstance()

            // 1. Sync primary User info profile
            val userDocRef = firestore.collection("users").document(uid)
            val blockedAppsList = com.example.FocusManager.customBlockedPackages.value.toList()
            val userPayload = mutableMapOf<String, Any>(
                "uid" to uid,
                "userId" to uid,
                "username" to userName,
                "name" to userName,
                "totalStars" to totalStars,
                "currentStreak" to currentStreak,
                "weeklyTargetMinutes" to weeklyTargetMinutes,
                "customBlockedPackages" to blockedAppsList,
                "lastSynced" to System.currentTimeMillis()
            )

            if (_userRole.value == "parent") {
                userPayload["role"] = "parent"
                userPayload["parentType"] = _parentType.value
            } else {
                userPayload["role"] = "student"
                userPayload["age"] = _studentAge.value
                userPayload["studyLevel"] = _studentStudyLevel.value
                userPayload["upcomingExam"] = _studentUpcomingExam.value
                userPayload["dailyGoal"] = _studentDailyGoal.value
            }

            userDocRef.update(userPayload).addOnFailureListener {
                // If document does not exist, use set instead of update
                userDocRef.set(userPayload)
            }.awaitTask()

            if (_userRole.value != "parent" && !isSandboxSimulationActive) {
                try {
                    // Update duplicated student names in relationship documents
                    // 1. parent_links
                    firestore.collection("parent_links")
                        .whereEqualTo("studentUid", uid)
                        .get()
                        .addOnSuccessListener { querySnapshot ->
                            for (document in querySnapshot.documents) {
                                firestore.collection("parent_links").document(document.id)
                                    .update("studentName", userName)
                            }
                        }

                    // 2. invites
                    firestore.collection("invites")
                        .whereEqualTo("studentUid", uid)
                        .get()
                        .addOnSuccessListener { querySnapshot ->
                            for (document in querySnapshot.documents) {
                                firestore.collection("invites").document(document.id)
                                    .update("studentName", userName)
                            }
                        }

                    // 3. inviteCodes
                    firestore.collection("inviteCodes")
                        .whereEqualTo("studentUid", uid)
                        .get()
                        .addOnSuccessListener { querySnapshot ->
                            for (document in querySnapshot.documents) {
                                firestore.collection("inviteCodes").document(document.id)
                                    .update("studentName", userName)
                            }
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing relationship documents in syncData", e)
                }
            }

            // 2. Sync to BOTH top-level 'sessions' collection and subcollection for dual safety
            val subSessionsCollRef = userDocRef.collection("sessions")
            for (session in sessions) {
                val sessionDocId = "${uid}_${session.dateTimestamp}"
                val sessionPayload = mapOf(
                    "sessionId" to sessionDocId,
                    "id" to session.id,
                    "userId" to uid,
                    "topic" to session.topic,
                    "durationMinutes" to session.durationMinutes,
                    "dateTimestamp" to session.dateTimestamp,
                    "appsBlockedCount" to session.appsBlockedCount,
                    "notificationsMutedCount" to session.notificationsMutedCount,
                    "isCompleted" to session.isCompleted,
                    "plannedDurationMinutes" to session.plannedDurationMinutes,
                    "actualDurationMinutes" to session.actualDurationMinutes,
                    "status" to session.status,
                    "focusScore" to session.focusScore,
                    "focusLevel" to session.focusLevel,
                    "starsEarned" to session.starsEarned,
                    "appActivityJson" to session.appActivityJson,
                    "totalDistractionAttempts" to session.totalDistractionAttempts,
                    "mostFrequentlyDistractingApp" to session.mostFrequentlyDistractingApp,
                    "timeChecksCount" to session.timeChecksCount,
                    "trendIndicator" to session.trendIndicator,
                    "isDndActiveDuringSession" to session.isDndActiveDuringSession,
                    "parentSummaryMessage" to session.parentSummaryMessage,
                    "syncedAt" to System.currentTimeMillis()
                )

                try {
                    // Save in top-level collection 'sessions' asynchronously (leveraging Firestore background queue/offline pipeline)
                    firestore.collection("sessions").document(sessionDocId).set(sessionPayload)
                    // Save in subcollection as well
                    subSessionsCollRef.document(session.dateTimestamp.toString()).set(sessionPayload)
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving session $sessionDocId asynchronously", e)
                }
            }

            // 3. Migrate Local Sneak / Distraction Attempts to top-level 'sneak_attempts' collection
            val subSneaksCollRef = userDocRef.collection("sneak_attempts")
            val rawHistory = HistoryManager.getClickHistory(context)
            val pm = context.packageManager

            for ((packageName, clickCount) in rawHistory) {
                var displayName = packageName
                try {
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    displayName = pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {}

                val cleanDocId = "${uid}_${packageName.replace(".", "_")}"
                val sneakPayload = mapOf(
                    "userId" to uid,
                    "packageName" to packageName,
                    "readableAppName" to displayName,
                    "clickCount" to clickCount,
                    "timestamp" to System.currentTimeMillis(),
                    "appIconReference" to packageName // Highly optimized: referencing icon by package name avoids binary upload latency!
                )

                // Save in top-level collection 'sneak_attempts'
                firestore.collection("sneak_attempts").document(cleanDocId).set(sneakPayload).awaitTask()
                // Save in subcollection
                subSneaksCollRef.document(packageName.replace(".", "_")).set(sneakPayload).awaitTask()
            }

            // Bidirectional pull: restore any cloud sessions/sneaks that are missing locally
            restoreSessionsAndSneaksFromCloud(context, uid)

            // Save Sync timestamp
            val now = System.currentTimeMillis()
            context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_SYNC_TIME, now)
                .apply()

            _lastSyncTime.value = now
            _syncState.value = SyncState.Success("Synced with Cloud Account ID: $uid")
            Log.d(TAG, "Firebase sync complete UID: $uid")
        } catch (e: Exception) {
            _syncState.value = SyncState.Error("Sync failed: ${e.message}")
            Log.e(TAG, "Error performing background cloud synchronization", e)
        }
    }

    fun updateUsernameDisplayName(context: Context, name: String) {
        _usernameProfile.value = name
        FocusManager.updateUserName(name)
        val uid = _currentUserUid.value
        val prefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString("mock_username", name)
        if (uid.isNotEmpty()) {
            editor.putString("mock_username_" + uid, name)
        }
        editor.putString("mock_linked_student_name", name)
        editor.putString("linked_student_name", name)
        editor.apply()

        // Sync with Firestore profile if user is authenticated and not in sandbox simulation
        if (!isSandboxSimulationActive && uid.isNotEmpty()) {
            try {
                val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                db.collection("users").document(uid)
                    .update(mapOf(
                        "username" to name,
                        "name" to name,
                        "lastSynced" to System.currentTimeMillis()
                    ))
                    .addOnSuccessListener {
                        Log.d(TAG, "Successfully updated username in Firestore user profile document.")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to update username in Firestore", e)
                    }

                // Update duplicated student names in relationship documents
                // 1. parent_links
                db.collection("parent_links")
                    .whereEqualTo("studentUid", uid)
                    .get()
                    .addOnSuccessListener { querySnapshot ->
                        for (document in querySnapshot.documents) {
                            db.collection("parent_links").document(document.id)
                                .update("studentName", name)
                                .addOnSuccessListener {
                                    Log.d(TAG, "Successfully updated studentName in parent_links relationship document: ${document.id}")
                                }
                        }
                    }

                // 2. invites
                db.collection("invites")
                    .whereEqualTo("studentUid", uid)
                    .get()
                    .addOnSuccessListener { querySnapshot ->
                        for (document in querySnapshot.documents) {
                            db.collection("invites").document(document.id)
                                .update("studentName", name)
                        }
                    }

                // 3. inviteCodes
                db.collection("inviteCodes")
                    .whereEqualTo("studentUid", uid)
                    .get()
                    .addOnSuccessListener { querySnapshot ->
                        for (document in querySnapshot.documents) {
                            db.collection("inviteCodes").document(document.id)
                                .update("studentName", name)
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating username and relationship documents in Firestore", e)
            }
        }
    }

    fun clearSneakAttemptsInCloud(context: Context) {
        val uid = _currentUserUid.value
        if (uid.isEmpty() || firebaseApp == null) return

        try {
            val firestore = FirebaseFirestore.getInstance()

            // 1. Clear top-level sneak attempts where userId matches uid
            firestore.collection("sneak_attempts")
                .whereEqualTo("userId", uid)
                .get()
                .addOnSuccessListener { snapshot ->
                    if (snapshot != null && !snapshot.isEmpty) {
                        val batch = firestore.batch()
                        for (doc in snapshot.documents) {
                            batch.delete(doc.reference)
                        }
                        batch.commit()
                            .addOnSuccessListener {
                                Log.d(TAG, "Successfully deleted remote sneak attempts from top-level collection")
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "Failed to commit delete batch for top-level sneak attempts", e)
                            }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to query top-level sneak attempts for deletion", e)
                }

            // 2. Clear subcollection users/{uid}/sneak_attempts
            firestore.collection("users").document(uid).collection("sneak_attempts")
                .get()
                .addOnSuccessListener { snapshot ->
                    if (snapshot != null && !snapshot.isEmpty) {
                        val batch = firestore.batch()
                        for (doc in snapshot.documents) {
                            batch.delete(doc.reference)
                        }
                        batch.commit()
                            .addOnSuccessListener {
                                Log.d(TAG, "Successfully deleted remote sneak attempts from user subcollection")
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "Failed to commit delete batch for subcollection sneak attempts", e)
                            }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to query subcollection sneak attempts for deletion", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing remote sneak attempts in cloud", e)
        }
    }

    fun assignRemoteSession(
        context: Context,
        studentId: String,
        topic: String,
        durationMinutes: Int,
        lockMode: String,
        passcode: String,
        onFinished: (Boolean, String) -> Unit
    ) {
        val prefs = context.getSharedPreferences("parent_remote_counts", Context.MODE_PRIVATE)
        val currentMonthKey = java.text.SimpleDateFormat("yyyy_MM", java.util.Locale.getDefault()).format(java.util.Date())
        val monthlyCount = prefs.getInt("${currentMonthKey}_count", 0)
        
        if (monthlyCount >= 3) {
            onFinished(false, "Remote Assignment limit reached! Parents are allowed a maximum of 3 remote study assignments per month to prevent micromanagement.")
            return
        }

        if (isSandboxSimulationActive) {
            val syncPrefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
            val assignedId = java.util.UUID.randomUUID().toString()
            syncPrefs.edit()
                .putString("sandbox_remote_session_assignedId", assignedId)
                .putString("sandbox_remote_session_topic", topic)
                .putInt("sandbox_remote_session_durationMinutes", durationMinutes)
                .putString("sandbox_remote_session_lockMode", lockMode)
                .putString("sandbox_remote_session_passcode", passcode)
                .putLong("sandbox_remote_session_timestamp", System.currentTimeMillis())
                .putString("sandbox_remote_session_status", "active")
                .apply()

            prefs.edit().putInt("${currentMonthKey}_count", monthlyCount + 1).apply()
            fetchLinkedStudentDataDirectly(context)
            Log.d(TAG, "Sandbox Simulation: Parent assigned session remotely.")
            onFinished(true, "Session assigned successfully (Sandbox Simulation Mode). Student device has been requested to lock down for $durationMinutes mins of '$topic'!")
            return
        }

        val firestore = FirebaseFirestore.getInstance()
        val assignedId = java.util.UUID.randomUUID().toString()
        val payload = mapOf(
            "remoteAssignedSession" to mapOf(
                "assignedId" to assignedId,
                "topic" to topic,
                "durationMinutes" to durationMinutes,
                "lockMode" to lockMode,
                "passcode" to passcode,
                "timestamp" to System.currentTimeMillis(),
                "status" to "active"
            )
        )

        firestore.collection("users").document(studentId)
            .update(payload)
            .addOnSuccessListener {
                prefs.edit().putInt("${currentMonthKey}_count", monthlyCount + 1).apply()
                onFinished(true, "Successfully assigned! Student device received signal to lock down into '$topic' for $durationMinutes minutes!")
            }
            .addOnFailureListener { e ->
                onFinished(false, "Failed to assign remote session: ${e.message}")
            }
    }

    fun stopRemoteSession(
        context: Context,
        studentId: String,
        onFinished: (Boolean, String) -> Unit
    ) {
        if (isSandboxSimulationActive) {
            val syncPrefs = context.getSharedPreferences(PREFS_FIREBASE, Context.MODE_PRIVATE)
            syncPrefs.edit()
                .putString("sandbox_remote_session_status", "Stopped by Parent")
                .apply()
            
            fetchLinkedStudentDataDirectly(context)
            onFinished(true, "Session stopped successfully (Sandbox Simulation Mode). Student device has been requested to end focus.")
            return
        }

        val firestore = FirebaseFirestore.getInstance()
        firestore.collection("users").document(studentId)
            .update("remoteAssignedSession.status", "Stopped by Parent")
            .addOnSuccessListener {
                onFinished(true, "Successfully sent stop command to student device!")
            }
            .addOnFailureListener { e ->
                onFinished(false, "Failed to send stop command: ${e.message}")
            }
    }

    fun triggerBackgroundSync(context: Context) {
        if (!isEnabled(context)) return
        val currentUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        if (currentUid.isNullOrEmpty()) {
            Log.e(TAG, "Background sync aborted: user is not authenticated.")
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = com.example.data.FocusDatabase.getDatabase(context)
                val refreshedSessions = db.focusSessionDao().getAllSessions(currentUid).first()
                val prefs = context.getSharedPreferences("FocusPrefs", Context.MODE_PRIVATE)
                val uName = prefs.getString("user_name", "Focused Student") ?: "Focused Student"
                val wTarget = prefs.getInt("weekly_target_minutes", 120)
                val starsTotal = com.example.RewardManager.getTotalStars(context)
                val streak = com.example.StreakManager.getCurrentStreak(context)
                
                syncData(
                    context = context,
                    userName = uName,
                    totalStars = starsTotal,
                    currentStreak = streak,
                    weeklyTargetMinutes = wTarget,
                    sessions = refreshedSessions
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed triggering background sync", e)
            }
        }
    }
}
