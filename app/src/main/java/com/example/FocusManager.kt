package com.example

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.example.api.GenerateContentRequest
import com.example.api.GeminiClient
import com.example.api.Content
import com.example.api.Part
import com.example.data.FocusDatabase
import com.example.data.FocusSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

object FocusManager {
    private const val TAG = "FocusManager"
    private const val PREFS_NAME = "focus_prefs"
    private const val KEY_ACTIVE = "focus_active"
    private const val KEY_END_TIME = "focus_end_time"
    private const val KEY_DURATION = "focus_duration"
    private const val KEY_TOPIC = "focus_topic"
    private const val KEY_QUOTE = "focus_quote"
    private const val KEY_APPS_BLOCKED = "apps_blocked"
    private const val KEY_NOTIFS_MUTED = "notifs_muted"
    private const val KEY_OTHER_APPS_ATTEMPTED = "other_apps_attempted"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_USER_PASSWORD = "user_password"
    private const val KEY_WEEKLY_TARGET = "weekly_target_minutes"
    private const val KEY_LOCK_MODE = "focus_lock_mode"
    private const val KEY_LOCK_PASSCODE = "focus_lock_passcode"
    private const val KEY_CUSTOM_BLOCKED_PACKAGES = "custom_blocked_packages"

    private lateinit var prefs: SharedPreferences
    private var timerJob: Job? = null
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _isFocusActive = MutableStateFlow(false)
    val isFocusActive = _isFocusActive.asStateFlow()

    private val _timeLeftSeconds = MutableStateFlow(0L)
    val timeLeftSeconds = _timeLeftSeconds.asStateFlow()

    private val _currentTopic = MutableStateFlow("General Studying")
    val currentTopic = _currentTopic.asStateFlow()

    private val _currentQuote = MutableStateFlow("Your future self is waiting. Stay focused!")
    val currentQuote = _currentQuote.asStateFlow()

    private val _appsBlockedCount = MutableStateFlow(0)
    val appsBlockedCount = _appsBlockedCount.asStateFlow()

    private val _notificationsMutedCount = MutableStateFlow(0)
    val notificationsMutedCount = _notificationsMutedCount.asStateFlow()

    private val _otherAppsAttemptedCount = MutableStateFlow(0)
    val otherAppsAttemptedCount = _otherAppsAttemptedCount.asStateFlow()

    private val _timeChecksCount = MutableStateFlow(0)
    val timeChecksCount = _timeChecksCount.asStateFlow()

    private val _userName = MutableStateFlow("Focused Student")
    val userName = _userName.asStateFlow()

    private val _userPassword = MutableStateFlow("")
    val userPassword = _userPassword.asStateFlow()

    private val _weeklyTargetMinutes = MutableStateFlow(300)
    val weeklyTargetMinutes = _weeklyTargetMinutes.asStateFlow()

    // Triggered when a focus session officially completes (timer runs out)
    private val _showCompletionSummary = MutableStateFlow<FocusSession?>(null)
    val showCompletionSummary = _showCompletionSummary.asStateFlow()

    private val _sessionLockMode = MutableStateFlow("PASSCODE") // "PASSCODE" or "STRICT"
    val sessionLockMode = _sessionLockMode.asStateFlow()

    private val _sessionPasscode = MutableStateFlow("")
    val sessionPasscode = _sessionPasscode.asStateFlow()

    private val _customBlockedPackages = MutableStateFlow<Set<String>>(emptySet())
    val customBlockedPackages = _customBlockedPackages.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String>("")
    val activeSessionId = _activeSessionId.asStateFlow()
    
    private val _lastSessionEndTime = MutableStateFlow<Long>(0L)
    val lastSessionEndTime = _lastSessionEndTime.asStateFlow()

    val currentDurationMinutes: Int
        get() = if (::prefs.isInitialized) prefs.getInt(KEY_DURATION, 25) else 25

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        RewardManager.init(context)
        StreakManager.init(context)
        
        // Restore state
        val active = prefs.getBoolean(KEY_ACTIVE, false)
        val endTime = prefs.getLong(KEY_END_TIME, 0L)
        val topic = prefs.getString(KEY_TOPIC, "General Studying") ?: "General Studying"
        val quote = prefs.getString(KEY_QUOTE, "Your future self is waiting. Stay focused!") ?: "Your future self is waiting. Stay focused!"
        val appsBlocked = prefs.getInt(KEY_APPS_BLOCKED, 0)
        val notifsMuted = prefs.getInt(KEY_NOTIFS_MUTED, 0)
        val otherAppsAttempted = prefs.getInt(KEY_OTHER_APPS_ATTEMPTED, 0)
        val uName = prefs.getString(KEY_USER_NAME, "Focused Student") ?: "Focused Student"
        val uPassword = prefs.getString(KEY_USER_PASSWORD, "") ?: ""
        val weeklyTarget = prefs.getInt(KEY_WEEKLY_TARGET, 300)
        val lMode = prefs.getString(KEY_LOCK_MODE, "PASSCODE") ?: "PASSCODE"
        val pCode = prefs.getString(KEY_LOCK_PASSCODE, "") ?: ""
        val savedSessionId = prefs.getString("KEY_ACTIVE_SESSION_ID", "") ?: ""

        _currentTopic.value = topic
        _currentQuote.value = quote
        _appsBlockedCount.value = appsBlocked
        _notificationsMutedCount.value = notifsMuted
        _otherAppsAttemptedCount.value = otherAppsAttempted
        _userName.value = uName
        _userPassword.value = uPassword
        _weeklyTargetMinutes.value = weeklyTarget
        _sessionLockMode.value = lMode
        _sessionPasscode.value = pCode
        _activeSessionId.value = savedSessionId

        val savedBlocked = prefs.getStringSet(KEY_CUSTOM_BLOCKED_PACKAGES, null)
        if (savedBlocked != null) {
            _customBlockedPackages.value = savedBlocked
        } else {
            mainScope.launch(Dispatchers.IO) {
                initializeDefaultBlockListIfNeeded(context)
            }
        }

        if (active && endTime > System.currentTimeMillis()) {
            _isFocusActive.value = true
            _timeLeftSeconds.value = (endTime - System.currentTimeMillis()) / 1000
            startTimerLoop(context, endTime)
        } else if (active) {
            // It expired while the app was not running!
            // Automatically complete it
            _isFocusActive.value = false
            _timeLeftSeconds.value = 0L
            saveFinishedSessionToDb(context, topic, prefs.getInt(KEY_DURATION, 60), appsBlocked, notifsMuted, true)
            resetPrefs()
        } else {
            _isFocusActive.value = false
            _timeLeftSeconds.value = 0L
        }
    }

    fun startFocusSession(
        context: Context,
        topic: String,
        durationMinutes: Int,
        customQuote: String? = null,
        lockMode: String = "PASSCODE",
        passcode: String = "",
        assignedSessionId: String? = null
    ) {
        val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
        val currentUid = auth.currentUser?.uid
        if (currentUid.isNullOrEmpty()) {
            Log.e(TAG, "Cannot start focus session: user is not authenticated.")
            android.widget.Toast.makeText(context, "Authentication is required to start a focus session. Please sign in.", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        // Cancel any existing timer
        timerJob?.cancel()

        _currentTopic.value = topic
        _appsBlockedCount.value = 0
        _notificationsMutedCount.value = 0
        _otherAppsAttemptedCount.value = 0
        _timeChecksCount.value = 0
        _showCompletionSummary.value = null
        _sessionLockMode.value = lockMode
        _sessionPasscode.value = passcode
        
        val sessionId = assignedSessionId ?: java.util.UUID.randomUUID().toString()
        _activeSessionId.value = sessionId

        val endTime = System.currentTimeMillis() + (durationMinutes * 60 * 1000)
        _timeLeftSeconds.value = (durationMinutes * 60).toLong()
        _isFocusActive.value = true

        val finalQuote = if (!customQuote.isNullOrBlank()) {
            customQuote.trim()
        } else {
            "Your future self is waiting. Stay focused!"
        }
        _currentQuote.value = finalQuote

        // Save parameters to prefs
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_END_TIME, endTime)
            .putInt(KEY_DURATION, durationMinutes)
            .putString(KEY_TOPIC, topic)
            .putString(KEY_QUOTE, finalQuote)
            .putInt(KEY_APPS_BLOCKED, 0)
            .putInt(KEY_NOTIFS_MUTED, 0)
            .putInt(KEY_OTHER_APPS_ATTEMPTED, 0)
            .putString(KEY_LOCK_MODE, lockMode)
            .putString(KEY_LOCK_PASSCODE, passcode)
            .putString("KEY_ACTIVE_SESSION_ID", sessionId)
            .apply()

        // Turn on DND
        toggleDnd(context, true)

        // Trigger start haptic feedback
        triggerHapticFeedback(context)

        // Only fetch from Gemini if no custom quote was specified
        if (customQuote.isNullOrBlank()) {
            fetchCustomQuoteFromGemini(topic)
        }

        // Start tracking session for star rewards
        FocusSessionManager.startSession()

        // Start countdown timer loop
        startTimerLoop(context, endTime)
    }

    fun stopFocusSession(context: Context, isCompleted: Boolean = false, statusOverride: String? = null) {
        timerJob?.cancel()
        toggleDnd(context, false)
        _lastSessionEndTime.value = System.currentTimeMillis()

        val active = _isFocusActive.value
        val topic = _currentTopic.value
        val duration = prefs.getInt(KEY_DURATION, 60)
        val appsBlocked = _appsBlockedCount.value
        val notifsMuted = _notificationsMutedCount.value

        _isFocusActive.value = false
        _timeLeftSeconds.value = 0L

        if (active) {
            FocusSessionManager.endSessionAndCalculateStars(context)
            saveFinishedSessionToDb(context, topic, duration, appsBlocked, notifsMuted, isCompleted, statusOverride)
        }

        // Trigger end haptic feedback
        triggerHapticFeedback(context)

        resetPrefs()
    }

    fun clearSummary() {
        _showCompletionSummary.value = null
    }

    private fun resetPrefs() {
        prefs.edit()
            .putBoolean(KEY_ACTIVE, false)
            .putLong(KEY_END_TIME, 0L)
            .putInt(KEY_APPS_BLOCKED, 0)
            .putInt(KEY_NOTIFS_MUTED, 0)
            .putString(KEY_LOCK_MODE, "PASSCODE")
            .putString(KEY_LOCK_PASSCODE, "")
            .apply()
        _sessionLockMode.value = "PASSCODE"
        _sessionPasscode.value = ""
    }

    private fun startTimerLoop(context: Context, endTime: Long) {
        timerJob = mainScope.launch {
            while (isActive) {
                val remaining = endTime - System.currentTimeMillis()
                if (remaining <= 0) {
                    _timeLeftSeconds.value = 0
                    stopFocusSession(context, true)
                    break
                }
                _timeLeftSeconds.value = remaining / 1000
                delay(1000)
            }
        }
    }

    fun incrementAppsBlocked(context: Context) {
        val newVal = _appsBlockedCount.value + 1
        _appsBlockedCount.value = newVal
        prefs.edit().putInt(KEY_APPS_BLOCKED, newVal).apply()
    }

    fun incrementNotificationsMuted(context: Context) {
        val newVal = _notificationsMutedCount.value + 1
        _notificationsMutedCount.value = newVal
        prefs.edit().putInt(KEY_NOTIFS_MUTED, newVal).apply()
    }

    fun incrementOtherAppsAttempted(context: Context) {
        val newVal = _otherAppsAttemptedCount.value + 1
        _otherAppsAttemptedCount.value = newVal
        if (::prefs.isInitialized) {
            prefs.edit().putInt(KEY_OTHER_APPS_ATTEMPTED, newVal).apply()
        }
    }

    fun incrementTimeChecksCount() {
        _timeChecksCount.value += 1
    }

    fun updateUserName(name: String) {
        _userName.value = name
        if (::prefs.isInitialized) {
            prefs.edit().putString(KEY_USER_NAME, name).apply()
        }
    }

    fun updateUserPassword(password: String) {
        _userPassword.value = password
        if (::prefs.isInitialized) {
            prefs.edit().putString(KEY_USER_PASSWORD, password).apply()
        }
    }

    fun updateWeeklyTargetMinutes(minutes: Int) {
        _weeklyTargetMinutes.value = minutes.coerceAtLeast(0)
        if (::prefs.isInitialized) {
            prefs.edit().putInt(KEY_WEEKLY_TARGET, minutes.coerceAtLeast(0)).apply()
        }
    }

    fun triggerHapticFeedback(context: Context) {
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(100)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Haptic trigger failed", e)
        }
    }

    private fun saveFinishedSessionToDb(
        context: Context,
        topic: String,
        durationMinutes: Int,
        appsBlocked: Int,
        notifsMuted: Int,
        isCompleted: Boolean,
        statusOverride: String? = null
    ) {
        val startTime = FocusSessionManager.getSessionStartTime()
        
        mainScope.launch(Dispatchers.IO) {
            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            val currentUid = auth.currentUser?.uid
            if (currentUid.isNullOrEmpty()) {
                Log.e(TAG, "Aborting session creation: FirebaseAuth.currentUser is null or empty. All sessions must be permanently bound to an authenticated UID at creation.")
                FocusSessionManager.clearSessionStartTime()
                return@launch
            }

            try {
                val db = FocusDatabase.getDatabase(context)
                
                // 1. Calculate actual duration in minutes
                val actualDurationMin = if (isCompleted) {
                    durationMinutes
                } else {
                    if (startTime > 0L) {
                        val elapsedMs = System.currentTimeMillis() - startTime
                        val mins = (elapsedMs / (1000 * 60)).toInt()
                        val secsRemaining = ((elapsedMs % (1000 * 60)) / 1000).toInt()
                        if (mins == 0 && secsRemaining >= 15) {
                            1
                        } else {
                            mins
                        }
                    } else {
                        0
                    }
                }.coerceIn(0, durationMinutes)

                // 2. Determine session completion status
                val sessionStatus = statusOverride ?: when {
                    isCompleted -> "Completed"
                    actualDurationMin <= 2 -> "Ended Early"
                    else -> "Interrupted"
                }

                // 3. Focus score calculation
                val timeChecks = _timeChecksCount.value
                val startingScore = 100
                val appBlockingPenalty = appsBlocked * 10
                val notificationPenalty = notifsMuted * 3
                val timeCheckPenalty = if (timeChecks > 3) (timeChecks - 3) * 2 else 0
                val calculatedScore = (startingScore - appBlockingPenalty - notificationPenalty - timeCheckPenalty).coerceIn(0, 100)

                // 4. Focus level
                val levelOfFocus = when {
                    calculatedScore >= 80 -> "High"
                    calculatedScore >= 50 -> "Medium"
                    else -> "Low"
                }

                // 5. Stars earned in this session
                val stars = FocusSessionManager.lastSessionStarsEarned.value

                // 6. Map blocked apps (package name -> count) to friendly name attempts
                val rawAttempts = FocusSessionManager.getSessionAppAttempts()
                val jsonObject = org.json.JSONObject()
                var maxAttempts = 0
                var worstApp: String? = null

                val pm = context.packageManager
                for ((pkg, count) in rawAttempts) {
                    var displayName: String
                    try {
                        val appInfo = pm.getApplicationInfo(pkg, 0)
                        displayName = pm.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        displayName = pkg.substringAfterLast('.')
                    }
                    jsonObject.put(displayName, count)

                    if (count > maxAttempts) {
                        maxAttempts = count
                        worstApp = displayName
                    }
                }
                val appSummaryJson = jsonObject.toString()

                // 7. Calculate trend indicator compared to last session
                val allSessionsList = db.focusSessionDao().getAllSessions(currentUid).first()
                val lastSessionObj = allSessionsList.firstOrNull()
                val trend = if (lastSessionObj != null) {
                    val diff = calculatedScore - lastSessionObj.focusScore
                    when {
                        diff > 5 -> "Improved"
                        diff < -5 -> "Needs Improvement"
                        else -> "Stable"
                    }
                } else {
                    "Stable"
                }

                // 8. Generate dynamic parent summary message
                val parentMsg = when {
                    calculatedScore >= 80 -> "Your child maintained outstanding focus during this session, successfully avoiding digital distractions."
                    calculatedScore >= 50 -> "Your child showed good overview focus but experienced moderate distraction attempts during their study block."
                    else -> "Focus support needed. Your child frequently attempted to open blocked apps during this study session."
                }

                // 9. Build final detailed FocusSession
                val session = FocusSession(
                    topic = topic,
                    durationMinutes = durationMinutes,
                    dateTimestamp = System.currentTimeMillis(),
                    appsBlockedCount = appsBlocked,
                    notificationsMutedCount = notifsMuted,
                    isCompleted = isCompleted,
                    
                    plannedDurationMinutes = durationMinutes,
                    actualDurationMinutes = actualDurationMin,
                    status = sessionStatus,
                    focusScore = calculatedScore,
                    focusLevel = levelOfFocus,
                    starsEarned = stars,
                    appActivityJson = appSummaryJson,
                    totalDistractionAttempts = appsBlocked,
                    mostFrequentlyDistractingApp = worstApp,
                    timeChecksCount = timeChecks,
                    trendIndicator = trend,
                    isDndActiveDuringSession = true,
                    parentSummaryMessage = parentMsg,
                    userId = currentUid
                )

                // 10. Persist and trigger dialog update
                db.focusSessionDao().insertSession(session)
                
                withContext(Dispatchers.Main) {
                    _showCompletionSummary.value = session
                }

                // Clear session start state as cleanup
                FocusSessionManager.clearSessionStartTime()

                if (com.example.service.FirebaseSyncManager.isSandboxSimulationActive) {
                    val syncPrefs = context.getSharedPreferences("FirebaseSyncPrefs", Context.MODE_PRIVATE)
                    syncPrefs.edit()
                        .putString("sandbox_remote_session_status", "Finished")
                        .apply()
                }

                // Auto sync with Firebase if enabled
                if (com.example.service.FirebaseSyncManager.isEnabled(context)) {
                    val refreshedSessions = db.focusSessionDao().getAllSessions(currentUid).first()
                    val uName = prefs.getString(KEY_USER_NAME, "Focused Student") ?: "Focused Student"
                    val wTarget = prefs.getInt(KEY_WEEKLY_TARGET, 120)
                    val starsTotal = com.example.RewardManager.getTotalStars(context)
                    val streak = com.example.StreakManager.getCurrentStreak(context)
                    
                    // Update remoteAssignedSession status in Firestore
                    val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
                    val uid = auth.currentUser?.uid
                    if (uid != null) {
                        try {
                            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                .collection("users").document(uid)
                                .update("remoteAssignedSession.status", "Finished")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error updating remote session status on completion", e)
                        }
                    }

                    com.example.service.FirebaseSyncManager.syncData(
                        context = context,
                        userName = uName,
                        totalStars = starsTotal,
                        currentStreak = streak,
                        weeklyTargetMinutes = wTarget,
                        sessions = refreshedSessions
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving session", e)
                FocusSessionManager.clearSessionStartTime()
            }
        }
    }

    private fun fetchCustomQuoteFromGemini(topic: String) {
        val apiKey = com.example.api.GeminiClient.getApiKey()
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            _currentQuote.value = getFallbackQuote(topic)
            prefs.edit().putString(KEY_QUOTE, _currentQuote.value).apply()
            return
        }

        mainScope.launch(Dispatchers.IO) {
            val prompt = """
                Write a single, short, razor-sharp, ultra-motivating productivity quote (under 15 words) for a student attempting to study "$topic" who has just been blocked from a distracting social media or video app. 
                Make it incredibly relevant to "$topic". Keep it snappy, inspiring, and direct. Do not include quotes around the text.
            """.trimIndent()

            val request = GenerateContentRequest(
                contents = listOf(Content(parts = listOf(Part(text = prompt))))
            )

            try {
                val response = GeminiClient.service.generateContent(apiKey, request)
                val quote = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (!quote.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        _currentQuote.value = quote.trim().replace("\"", "")
                        prefs.edit().putString(KEY_QUOTE, _currentQuote.value).apply()
                    }
                } else {
                    useFallback(topic)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch AI quote", e)
                useFallback(topic)
            }
        }
    }

    private suspend fun useFallback(topic: String) {
        withContext(Dispatchers.Main) {
            _currentQuote.value = getFallbackQuote(topic)
            prefs.edit().putString(KEY_QUOTE, _currentQuote.value).apply()
        }
    }

    fun getFallbackQuote(topic: String): String {
        val cleanTopic = topic.lowercase()
        return when {
            cleanTopic.contains("math") || cleanTopic.contains("algebra") || cleanTopic.contains("calc") -> {
                "Every complex equation solved brings you one step closer to your goals. Stay focused."
            }
            cleanTopic.contains("code") || cleanTopic.contains("program") || cleanTopic.contains("react") || cleanTopic.contains("android") -> {
                "Stop debugging social media. Direct all your runtime focus to your code."
            }
            cleanTopic.contains("science") || cleanTopic.contains("physics") || cleanTopic.contains("bio") || cleanTopic.contains("chem") -> {
                "Study the laws of focus. Distractions are just random thermal noise."
            }
            cleanTopic.contains("history") || cleanTopic.contains("art") || cleanTopic.contains("english") || cleanTopic.contains("write") -> {
                "You are writing your own history right now. Don't let scroll-loops rewrite it."
            }
            else -> {
                "Put in the deep work now. Your future self will thank you for this hour of focus."
            }
        }
    }

    fun toggleDnd(context: Context, enable: Boolean) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager.isNotificationPolicyAccessGranted) {
                val filter = if (enable) {
                    NotificationManager.INTERRUPTION_FILTER_NONE
                } else {
                    NotificationManager.INTERRUPTION_FILTER_ALL
                }
                notificationManager.setInterruptionFilter(filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting DND filter", e)
        }
    }

    fun setCustomBlockedPackages(context: Context, packages: Set<String>) {
        if (!::prefs.isInitialized) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        _customBlockedPackages.value = packages
        prefs.edit().putStringSet(KEY_CUSTOM_BLOCKED_PACKAGES, packages).apply()
    }

    fun toggleCustomBlockedPackage(context: Context, packageName: String) {
        if (!::prefs.isInitialized) return
        if (_isFocusActive.value) {
            Log.w(TAG, "Cannot modify restricted apps list during an active study session.")
            return
        }
        val current = _customBlockedPackages.value.toMutableSet()
        if (current.contains(packageName)) {
            current.remove(packageName)
            Log.d(TAG, "Removed app from blocker list: $packageName")
        } else {
            current.add(packageName)
            Log.d(TAG, "Added app to blocker list: $packageName")
        }
        _customBlockedPackages.value = current
        prefs.edit().putStringSet(KEY_CUSTOM_BLOCKED_PACKAGES, current).apply()
        
        // Auto trigger background sync to back up new tracks/blocklists immediately
        com.example.service.FirebaseSyncManager.triggerBackgroundSync(context)
    }

    fun isPackageBlocked(packageName: String): Boolean {
        if (!com.example.service.SubscriptionManager.isPro.value) {
            // Under Free Tier, basic distraction coverage restricts ONLY youtube, instagram, facebook apps
            val basicPlanPackages = setOf(
                "com.google.android.youtube",
                "com.instagram.android",
                "com.facebook.katana",
                "com.facebook.lite"
            )
            return basicPlanPackages.contains(packageName)
        }
        return _customBlockedPackages.value.contains(packageName)
    }

    private fun initializeDefaultBlockListIfNeeded(context: Context) {
        if (!::prefs.isInitialized) return
        if (prefs.contains(KEY_CUSTOM_BLOCKED_PACKAGES)) return

        val defaults = java.util.HashSet<String>()
        defaults.addAll(com.example.service.FocusBlockerAccessibilityService.BLOCKED_PACKAGES)

        try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0)
            for (pkgInfo in packages) {
                val pkgName = pkgInfo.packageName
                if (pkgName != context.packageName && GameDetector.isAppAGame(context, pkgName)) {
                    defaults.add(pkgName)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning installed games for default block list", e)
        }

        _customBlockedPackages.value = defaults
        prefs.edit().putStringSet(KEY_CUSTOM_BLOCKED_PACKAGES, defaults).apply()
        Log.d(TAG, "Initialized custom block list with ${defaults.size} games and standard apps.")
    }
}
