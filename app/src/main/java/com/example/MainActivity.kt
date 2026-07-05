package com.example

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.api.GeminiClient
import com.example.api.GenerateContentRequest
import com.example.api.Content as GeminiContent
import com.example.api.Part as GeminiPart
import com.example.service.FocusBlockerAccessibilityService
import com.example.service.FocusNotificationListenerService
import com.example.service.FirebaseSyncManager
import com.example.service.FirebaseSyncManager.OnboardingStep
import com.example.ui.theme.MyApplicationTheme
import java.text.SimpleDateFormat
import java.util.*

open class MainActivity : ComponentActivity(), com.razorpay.PaymentResultWithDataListener {

    private val viewModel: FocusViewModel by viewModels()

    override fun onPaymentSuccess(razorpayPaymentId: String?, paymentData: com.razorpay.PaymentData?) {
        val plan = com.example.service.SubscriptionManager.pendingPlanType ?: "Focus Pro"
        val paymentId = razorpayPaymentId ?: paymentData?.paymentId ?: "pay_rzp_unknown"
        val orderId = paymentData?.orderId
        val signature = paymentData?.signature
        
        var subscriptionId: String? = null
        try {
            val method = paymentData?.javaClass?.getMethod("getSubscriptionId")
            subscriptionId = method?.invoke(paymentData) as? String
        } catch (e: Exception) {
            // Safe fallback
        }
        if (subscriptionId.isNullOrBlank()) {
            subscriptionId = com.example.service.SubscriptionManager.pendingSubscriptionId
        }
        
        com.example.service.SubscriptionManager.handlePaymentSuccess(
            context = this,
            planType = plan,
            razorpayOrderId = orderId,
            razorpayPaymentId = paymentId,
            razorpaySignature = signature,
            razorpaySubscriptionId = subscriptionId
        ) { success, err ->
            if (success) {
                com.example.service.SubscriptionManager.notifySuccess(paymentId, plan)
            } else {
                com.example.service.SubscriptionManager.notifyError(-2, err ?: "Payment verification failed")
            }
        }
        com.example.service.SubscriptionManager.pendingPlanType = null
        com.example.service.SubscriptionManager.pendingSubscriptionId = null
    }

    override fun onPaymentError(code: Int, response: String?, paymentData: com.razorpay.PaymentData?) {
        val errorMsg = "Razorpay SDK Checkout Error (Code $code):\n${response ?: "Payment cancelled or failed"}"
        com.example.service.SubscriptionManager.notifyError(code, errorMsg)
        com.example.service.SubscriptionManager.pendingPlanType = null
        com.example.service.SubscriptionManager.pendingSubscriptionId = null
    }

    // Permissions/Service active verification states
    private var isAccessibilityEnabled by mutableStateOf(false)
    private var isDndEnabled by mutableStateOf(false)
    private var isNotifListenerEnabled by mutableStateOf(false)
    private var isUsageAccessEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        exemptHiddenApis()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Init Focus State Manager (reconstructs state if app died)
        FocusManager.init(applicationContext)

        // Initialize Firebase Sync Manager to restore session state/route immediately
        FirebaseSyncManager.initializeFirebase(applicationContext)

        // Initialize Razorpay Subscription sync manager
        com.example.service.SubscriptionManager.init(applicationContext)
        com.razorpay.Checkout.preload(applicationContext)

        // Request runtime notification permission on Android 13+
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val hasNotifPermission = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasNotifPermission) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        setContent {
            LaunchedEffect(Unit) {
                FirebaseSyncManager.markUiInitialized(applicationContext)
            }

            MyApplicationTheme {
                val onboardingStep by FirebaseSyncManager.onboardingStep.collectAsStateWithLifecycle()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFFFEF7FF)
                ) { innerPadding ->
                    if (onboardingStep != OnboardingStep.COMPLETED) {
                        OnboardingFlowContainer(
                            isAccessibilityEnabled = isAccessibilityEnabled,
                            isDndEnabled = isDndEnabled,
                            isNotifListenerEnabled = isNotifListenerEnabled,
                            isUsageAccessEnabled = isUsageAccessEnabled,
                            onRequestAccessibility = { navigateToAccessibilitySettings() },
                            onRequestDnd = { navigateToDndSettings() },
                            onRequestNotifListener = { navigateToNotificationListenerSettings() },
                            onRequestUsageAccess = { navigateToUsageAccessSettings() },
                            onCheckAllPermissions = { checkAllPermissions() },
                            modifier = Modifier.padding(innerPadding)
                        )
                    } else {
                        FocusAppDashboard(
                            viewModel = viewModel,
                            isAccessibilityEnabled = isAccessibilityEnabled,
                            isDndEnabled = isDndEnabled,
                            isNotifListenerEnabled = isNotifListenerEnabled,
                            onRequestAccessibility = { navigateToAccessibilitySettings() },
                            onRequestDnd = { navigateToDndSettings() },
                            onRequestNotifListener = { navigateToNotificationListenerSettings() },
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkAllPermissions()
    }

    private fun checkAllPermissions() {
        isAccessibilityEnabled = checkAccessibilityServiceEnabled(this)
        isDndEnabled = checkDndPermissionGranted(this)
        isNotifListenerEnabled = checkNotificationListenerEnabled(this)
        isUsageAccessEnabled = checkUsageAccessEnabled(this)
    }

    private fun exemptHiddenApis() {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                val forName = java.lang.Class::class.java.getDeclaredMethod("forName", String::class.java)
                val getDeclaredMethod = java.lang.Class::class.java.getDeclaredMethod(
                    "getDeclaredMethod",
                    String::class.java,
                    arrayOf<java.lang.Class<*>>().javaClass
                )
                val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as java.lang.Class<*>
                val getRuntime = getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null) as java.lang.reflect.Method
                val setHiddenApiExemptions = getDeclaredMethod.invoke(
                    vmRuntimeClass,
                    "setHiddenApiExemptions",
                    arrayOf(arrayOf<String>().javaClass)
                ) as java.lang.reflect.Method
                val vmRuntime = getRuntime.invoke(null)
                setHiddenApiExemptions.invoke(vmRuntime, arrayOf(arrayOf("L")))
                android.util.Log.d("Bypass", "Exempted hidden api constraints successfully")
            } catch (e: Throwable) {
                android.util.Log.e("Bypass", "Failed to exempt hidden api constraints", e)
            }
        }
    }

    private fun checkAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedService = "${context.packageName}/${FocusBlockerAccessibilityService::class.java.canonicalName}"
        val enabledServices = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(expectedService)
    }

    private fun checkDndPermissionGranted(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    private fun checkNotificationListenerEnabled(context: Context): Boolean {
        val cn = ComponentName(context, FocusNotificationListenerService::class.java)
        val flat = android.provider.Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        return flat != null && flat.indexOf(cn.flattenToShortString()) >= 0
    }

    private fun checkUsageAccessEnabled(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun navigateToAccessibilitySettings() {
        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun navigateToDndSettings() {
        startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    private fun navigateToNotificationListenerSettings() {
        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
    }

    private fun navigateToUsageAccessSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FocusAppDashboard(
    viewModel: FocusViewModel,
    isAccessibilityEnabled: Boolean,
    isDndEnabled: Boolean,
    isNotifListenerEnabled: Boolean,
    onRequestAccessibility: () -> Unit,
    onRequestDnd: () -> Unit,
    onRequestNotifListener: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val keyboardFocus = LocalFocusManager.current

    val isFocusActive by FocusManager.isFocusActive.collectAsStateWithLifecycle()
    val timeLeftSeconds by FocusManager.timeLeftSeconds.collectAsStateWithLifecycle()
    val topic by FocusManager.currentTopic.collectAsStateWithLifecycle()
    val appsBlocked by FocusManager.appsBlockedCount.collectAsStateWithLifecycle()
    val notifsMuted by FocusManager.notificationsMutedCount.collectAsStateWithLifecycle()
    val showSummary by FocusManager.showCompletionSummary.collectAsStateWithLifecycle()
    val lastSessionStars by com.example.FocusSessionManager.lastSessionStarsEarned.collectAsStateWithLifecycle()

    val historySessions by viewModel.allSessions.collectAsStateWithLifecycle()
    val completedToday = remember(historySessions) {
        val now = System.currentTimeMillis()
        val oneDayMs = 24 * 60 * 60 * 1000L
        historySessions.count { (now - it.dateTimestamp) < oneDayMs }
    }
    val totalFocusedMinutes by viewModel.totalFocusMinutes.collectAsStateWithLifecycle()
    val totalCompletedBlocks by viewModel.completedSessionsCount.collectAsStateWithLifecycle()

    val userName by FocusManager.userName.collectAsStateWithLifecycle()
    val userPassword by FocusManager.userPassword.collectAsStateWithLifecycle()
    val otherAppsAttemptedCount by FocusManager.otherAppsAttemptedCount.collectAsStateWithLifecycle()
    val weeklyTargetMinutes by FocusManager.weeklyTargetMinutes.collectAsStateWithLifecycle()

    val userRole by FirebaseSyncManager.userRole.collectAsStateWithLifecycle()
    val linkedStudentId by FirebaseSyncManager.linkedStudentId.collectAsStateWithLifecycle()
    val linkedStudentName by FirebaseSyncManager.linkedStudentName.collectAsStateWithLifecycle()
    val linkedStudentStats by FirebaseSyncManager.linkedStudentStats.collectAsStateWithLifecycle()
    val linkedStudentSessions by FirebaseSyncManager.linkedStudentSessions.collectAsStateWithLifecycle()
    val linkedStudentSneaks by FirebaseSyncManager.linkedStudentSneaks.collectAsStateWithLifecycle()

    var showMenuSideDrawer by remember { mutableStateOf(false) }
    var showProfilePanel by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf("FOCUS") }
    var selectedPastSessionForDetail by remember { mutableStateOf<com.example.data.FocusSession?>(null) }

    // Form settings inputs
    var studyTopicInput by remember { mutableStateOf("") }
    var customQuoteInput by remember { mutableStateOf("") }
    var selectedDurationMinutes by remember { mutableStateOf(25) }

    var hoursInput by remember { mutableStateOf("") }
    var minutesInput by remember { mutableStateOf("25") }

    LaunchedEffect(selectedDurationMinutes) {
        val currentH = selectedDurationMinutes / 60
        val currentM = selectedDurationMinutes % 60
        
        val parsedH = hoursInput.toIntOrNull() ?: 0
        val parsedM = minutesInput.toIntOrNull() ?: 0
        if ((parsedH * 60) + parsedM != selectedDurationMinutes) {
            hoursInput = if (currentH == 0) "" else currentH.toString()
            minutesInput = currentM.toString()
        }
    }

    var showAccessibilityHelpDialog by remember { mutableStateOf(false) }
    var showDndHelpDialog by remember { mutableStateOf(false) }
    var showNotifListenerHelpDialog by remember { mutableStateOf(false) }
    var showPermissionOnStartPromptDialog by remember { mutableStateOf(false) }
    var showAppBlockingDialog by remember { mutableStateOf(false) }
    var showFreeAppBlockingInfoDialog by remember { mutableStateOf(false) }
    var showDailyLimitReachedDialog by remember { mutableStateOf(false) }
    val customBlockedPackages by FocusManager.customBlockedPackages.collectAsStateWithLifecycle()

    var parentLockMode by remember { mutableStateOf("PASSCODE") } // "PASSCODE" or "STRICT"
    var parentPasscodeInput by remember { mutableStateOf("") }

    var showPasscodeUnlockDialog by remember { mutableStateOf(false) }
    var enteredPasscodeAttempt by remember { mutableStateOf("") }
    var passcodeAttemptError by remember { mutableStateOf(false) }

    val sessionLockMode by FocusManager.sessionLockMode.collectAsStateWithLifecycle()
    val sessionPasscode by FocusManager.sessionPasscode.collectAsStateWithLifecycle()

    val isProSubscribed by com.example.service.SubscriptionManager.isPro.collectAsStateWithLifecycle()
    val isParentSubscribed by com.example.service.SubscriptionManager.isParent.collectAsStateWithLifecycle()
    val isPremiumViaParent by com.example.service.SubscriptionManager.isPremiumViaParent.collectAsStateWithLifecycle()
    val subscriptionStatus by com.example.service.SubscriptionManager.status.collectAsStateWithLifecycle()

    var showSubscriptionManagementScreen by remember { mutableStateOf(false) }
    var showProPaywallDialog by remember { mutableStateOf(false) }

    // Synchronize form values of time presets
    val presets = listOf(15, 25, 45, 60, 90)

    // Main scroll layer
    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = Color.White,
                tonalElevation = 8.dp
            ) {
                val isParent = userRole == "parent"
                NavigationBarItem(
                    selected = selectedTab == "FOCUS",
                    onClick = { selectedTab = "FOCUS" },
                    icon = { Icon(if (isParent) Icons.Default.VerifiedUser else Icons.Default.Timer, contentDescription = if (isParent) "Monitor" else "Focus") },
                    label = { Text(if (isParent) "Monitor" else "Focus") },
                    modifier = Modifier.testTag("nav_focus")
                )
                NavigationBarItem(
                    selected = selectedTab == "STATS",
                    onClick = { selectedTab = "STATS" },
                    icon = { Icon(Icons.Default.BarChart, contentDescription = if (isParent) "Child Stats" else "Stats") },
                    label = { Text(if (isParent) "Child Stats" else "Stats") },
                    modifier = Modifier.testTag("nav_stats")
                )
                NavigationBarItem(
                    selected = selectedTab == "CHAT",
                    onClick = { selectedTab = "CHAT" },
                    icon = { Icon(Icons.Default.SmartToy, contentDescription = "AI Coach") },
                    label = { Text("AI Coach") },
                    modifier = Modifier.testTag("nav_chat")
                )
                NavigationBarItem(
                    selected = selectedTab == "SETTINGS",
                    onClick = { selectedTab = "SETTINGS" },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    modifier = Modifier.testTag("nav_settings")
                )
            }
        },
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent
    ) { innerPadding ->
        if (selectedTab == "FOCUS") {
            if (userRole == "parent") {
                if (isParentSubscribed) {
                    ParentDashboardContent(
                        innerPadding = innerPadding,
                        linkedStudentId = linkedStudentId,
                        linkedStudentName = linkedStudentName,
                        linkedStudentStats = linkedStudentStats,
                        linkedStudentSessions = linkedStudentSessions,
                        linkedStudentSneaks = linkedStudentSneaks,
                        onOpenHistoryDrawer = { showMenuSideDrawer = true },
                        onOpenProfile = { showProfilePanel = true }
                    )
                } else {
                    com.example.ui.ParentUpgradePaywallPlaceholder(
                        innerPadding = innerPadding,
                        onUpgradeClick = { showSubscriptionManagementScreen = true }
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .testTag("dashboard_root"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (subscriptionStatus == "Paused" || subscriptionStatus == "Halted") {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                            border = BorderStroke(1.dp, Color(0xFFEF5350)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFD32F2F))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Subscription Paused / Halted", fontWeight = FontWeight.Bold, color = Color(0xFFC62828), fontSize = 14.sp)
                                    Text("Your automatic renewal is on hold. Please update payment to resume focus enhancements.", color = Color(0xFFC62828), fontSize = 12.sp)
                                }
                                TextButton(onClick = { showSubscriptionManagementScreen = true }) {
                                    Text("Renew", fontWeight = FontWeight.Bold, color = Color(0xFFD32F2F))
                                }
                            }
                        }
                    }
                }

                // App Identity Header
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = { showMenuSideDrawer = true },
                        modifier = Modifier.testTag("open_history_drawer_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menu",
                            tint = Color(0xFF49454F),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            contentDescription = "Focus Logo",
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF6750A4))
                                .padding(4.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Focus",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                fontSize = 22.sp
                            ),
                            color = Color(0xFF1D1B20)
                        )
                    }
                    IconButton(
                        onClick = { showProfilePanel = true },
                        modifier = Modifier.testTag("open_profile_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = "Account",
                            tint = Color(0xFF49454F),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Text(
                    text = "DEEP STUDY ENGINE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = Color(0xFF6750A4),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        if (isPremiumViaParent) {
            item {
                Surface(
                    color = Color(0xFF6750A4).copy(alpha = 0.08f),
                    contentColor = Color(0xFF6750A4),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.2.dp, Color(0xFF6750A4).copy(alpha = 0.3f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF6750A4)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stars,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Premium via Parent",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF6750A4)
                            )
                            Text(
                                text = "Premium features unlocked by your parent subscription",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF49454F)
                            )
                        }
                    }
                }
            }
        }

        // Active State vs Setup state View Switch
        if (isFocusActive) {
            item {
                ActiveFocusTimerCard(
                    topic = topic,
                    timeLeftSeconds = timeLeftSeconds,
                    appsBlocked = appsBlocked,
                    notifsMuted = notifsMuted,
                    sessionLockMode = sessionLockMode,
                    isPro = isProSubscribed,
                    completedTodayCount = completedToday,
                    onEndSession = {
                        if (isProSubscribed && (sessionLockMode == "PASSCODE" || userPassword.isNotEmpty())) {
                            showPasscodeUnlockDialog = true
                        } else {
                            // In non-passcode mode, stop immediately
                            FocusManager.stopFocusSession(context, false)
                        }
                    }
                )
            }
        } else {
            // Configuration Setup Card
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFF3EDF7)
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Set Up Deep Work Session",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF1D1B20)
                        )

                        // Subject text field
                        OutlinedTextField(
                            value = studyTopicInput,
                            onValueChange = { studyTopicInput = it },
                            label = { Text("What are you focusing on today?") },
                            placeholder = { Text("e.g. Physics Revision, Programming, Essay Writing") },
                            leadingIcon = { Icon(Icons.Default.School, contentDescription = null, tint = Color(0xFF49454F)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { keyboardFocus.clearFocus() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("topic_input_field"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White,
                                focusedBorderColor = Color(0xFF6750A4),
                                unfocusedBorderColor = Color(0xFFCAC4D0),
                                focusedLabelColor = Color(0xFF6750A4),
                                unfocusedLabelColor = Color(0xFF49454F),
                                focusedTextColor = Color(0xFF1D1B20),
                                unfocusedTextColor = Color(0xFF1D1B20),
                                cursorColor = Color(0xFF6750A4),
                                focusedPlaceholderColor = Color(0xFF49454F).copy(alpha = 0.6f),
                                unfocusedPlaceholderColor = Color(0xFF49454F).copy(alpha = 0.6f)
                            )
                        )

                        // Custom Affirmation / Reminder text field
                        OutlinedTextField(
                            value = customQuoteInput,
                            onValueChange = { customQuoteInput = it },
                            label = { Text("Custom Motivation / Affirmation (Optional)") },
                            placeholder = { Text("e.g. krishna sees everything") },
                            leadingIcon = { Icon(Icons.Default.Favorite, contentDescription = null, tint = Color(0xFF49454F)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { keyboardFocus.clearFocus() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("custom_quote_input_field"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White,
                                focusedBorderColor = Color(0xFF6750A4),
                                unfocusedBorderColor = Color(0xFFCAC4D0),
                                focusedLabelColor = Color(0xFF6750A4),
                                unfocusedLabelColor = Color(0xFF49454F),
                                focusedTextColor = Color(0xFF1D1B20),
                                unfocusedTextColor = Color(0xFF1D1B20),
                                cursorColor = Color(0xFF6750A4),
                                focusedPlaceholderColor = Color(0xFF49454F).copy(alpha = 0.6f),
                                unfocusedPlaceholderColor = Color(0xFF49454F).copy(alpha = 0.6f)
                            )
                        )

                        // Presets chips select
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Duration Presets",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color(0xFF49454F)
                            )
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                presets.forEach { duration ->
                                    val isSelected = selectedDurationMinutes == duration
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedDurationMinutes = duration },
                                        label = { Text("$duration Min") },
                                        leadingIcon = if (isSelected) {
                                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                        } else null,
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFFE8DEF8),
                                            selectedLabelColor = Color(0xFF1D1B20),
                                            selectedLeadingIconColor = Color(0xFF1D1B20),
                                            containerColor = Color.Transparent,
                                            labelColor = Color(0xFF49454F)
                                        ),
                                        border = if (isSelected) null else FilterChipDefaults.filterChipBorder(
                                            enabled = true,
                                            selected = false,
                                            borderColor = Color(0xFFCAC4D0),
                                            borderWidth = 1.dp
                                        ),
                                        modifier = Modifier.testTag("preset_chip_$duration")
                                    )
                                }
                            }
                        }

                        // Fine adjustment slider & direct time input
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Custom Session Time",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color(0xFF49454F)
                                )
                                Text(
                                    text = if (selectedDurationMinutes >= 60) {
                                        val hr = selectedDurationMinutes / 60
                                        val min = selectedDurationMinutes % 60
                                        if (min == 0) "$hr hr" else "$hr hr $min min ($selectedDurationMinutes min)"
                                    } else {
                                        "$selectedDurationMinutes min"
                                    },
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF6750A4)
                                )
                            }
                            
                            // Visual slider up to 720 min (12 hours), capped at 120 min for Free.
                            val maxSliderVal = if (isProSubscribed) 720f else 120f
                            Slider(
                                value = selectedDurationMinutes.toFloat().coerceIn(1f, maxSliderVal),
                                onValueChange = { 
                                    selectedDurationMinutes = it.toInt().coerceIn(1, maxSliderVal.toInt())
                                },
                                valueRange = 1f..maxSliderVal,
                                colors = SliderDefaults.colors(
                                    activeTrackColor = Color(0xFF6750A4),
                                    inactiveTrackColor = Color(0xFFEADDFF),
                                    thumbColor = Color(0xFF6750A4)
                                ),
                                 modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("duration_slider")
                            )

                            if (isProSubscribed) {
                                // Quick direct entry fields for hours and minutes
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Hours TextField
                                    OutlinedTextField(
                                        value = hoursInput,
                                        onValueChange = { input ->
                                            val filtered = input.filter { it.isDigit() }
                                            hoursInput = filtered
                                            
                                            val h = filtered.toIntOrNull() ?: 0
                                            val m = minutesInput.toIntOrNull() ?: 0
                                            val total = (h * 60) + m
                                            val maxVal = if (isProSubscribed) 720 else 120
                                            selectedDurationMinutes = total.coerceIn(1, maxVal)
                                        },
                                        label = { Text("Hours") },
                                        placeholder = { Text("0") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Number,
                                            imeAction = ImeAction.Next
                                        ),
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = Color.White,
                                            unfocusedContainerColor = Color.White,
                                            focusedBorderColor = Color(0xFF6750A4),
                                            unfocusedBorderColor = Color(0xFFCAC4D0),
                                            focusedTextColor = Color(0xFF1D1B20),
                                            unfocusedTextColor = Color(0xFF1D1B20)
                                        )
                                    )

                                    // Minutes TextField
                                    OutlinedTextField(
                                        value = minutesInput,
                                        onValueChange = { input ->
                                            val filtered = input.filter { it.isDigit() }
                                            minutesInput = filtered
                                            
                                            val h = hoursInput.toIntOrNull() ?: 0
                                            val m = filtered.toIntOrNull() ?: 0
                                            val total = (h * 60) + m
                                            val maxVal = if (isProSubscribed) 720 else 120
                                            selectedDurationMinutes = total.coerceIn(1, maxVal)
                                        },
                                        label = { Text("Minutes") },
                                        placeholder = { Text("0") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Number,
                                            imeAction = ImeAction.Done
                                        ),
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = Color.White,
                                            unfocusedContainerColor = Color.White,
                                            focusedBorderColor = Color(0xFF6750A4),
                                            unfocusedBorderColor = Color(0xFFCAC4D0),
                                            focusedTextColor = Color(0xFF1D1B20),
                                            unfocusedTextColor = Color(0xFF1D1B20)
                                        )
                                    )
                                }
                            }
                        }

                        // Parental Lockdown Settings
                        if (isProSubscribed) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = Color(0xFFEADDFF)
                            )
                            
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Parental Locking Mode",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF6750A4)
                                )
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    val optionColorSelected = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFFE8DEF8),
                                        selectedLabelColor = Color(0xFF1D1B20)
                                    )
                                    val optionColorUnselected = FilterChipDefaults.filterChipColors(
                                        containerColor = Color.White.copy(alpha = 0.5f),
                                        labelColor = Color(0xFF49454F)
                                    )
                                    
                                    FilterChip(
                                        selected = parentLockMode == "PASSCODE",
                                        onClick = {
                                            if (isProSubscribed) {
                                                parentLockMode = "PASSCODE"
                                            } else {
                                                showProPaywallDialog = true
                                            }
                                        },
                                        label = { Text("Passcode Lock") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Lock,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        colors = if (parentLockMode == "PASSCODE") optionColorSelected else optionColorUnselected,
                                        modifier = Modifier.weight(1f)
                                    )
                                    
                                    FilterChip(
                                        selected = parentLockMode == "STRICT",
                                        onClick = { parentLockMode = "STRICT" },
                                        label = { Text("Strict (No Stop)") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.DoNotDisturbOn,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        colors = if (parentLockMode == "STRICT") optionColorSelected else optionColorUnselected,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                
                                if (parentLockMode == "PASSCODE") {
                                    OutlinedTextField(
                                        value = parentPasscodeInput,
                                        onValueChange = { input ->
                                            if (input.length <= 5) {
                                                parentPasscodeInput = input.filter { it.isDigit() }
                                            }
                                        },
                                        label = { Text("Create 5-Digit Parent Passcode") },
                                        placeholder = { Text("e.g. 12345") },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.VpnKey,
                                                contentDescription = null,
                                                tint = Color(0xFF49454F)
                                            )
                                        },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Number,
                                            imeAction = ImeAction.Done
                                        ),
                                        isError = parentPasscodeInput.length != 5 && parentPasscodeInput.isNotEmpty(),
                                        supportingText = {
                                            if (parentPasscodeInput.isEmpty()) {
                                                Text("Set passcode to end or pause session later.", color = Color(0xFF49454F))
                                            } else if (parentPasscodeInput.length != 5) {
                                                Text("Must be exactly 5 digits.", color = MaterialTheme.colorScheme.error)
                                            } else {
                                                Text("Passcode valid!", color = Color(0xFF2E7D32))
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = Color.White,
                                            unfocusedContainerColor = Color.White,
                                            focusedBorderColor = Color(0xFF6750A4),
                                            unfocusedBorderColor = Color(0xFFCAC4D0),
                                            focusedTextColor = Color(0xFF1D1B20),
                                            unfocusedTextColor = Color(0xFF1D1B20)
                                        )
                                    )
                                } else {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFDE8E8)),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Warning,
                                                contentDescription = null,
                                                tint = Color(0xFFB3261E),
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Text(
                                                text = "Once started, this session cannot be paused or stopped under any circumstances until the timer completes.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFFB3261E)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        
                        // App & Game Blocking Button
                        OutlinedButton(
                            onClick = {
                                if (isProSubscribed) {
                                    showAppBlockingDialog = true
                                } else {
                                    showFreeAppBlockingInfoDialog = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFF6750A4)
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEADDFF))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Block,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isProSubscribed) "Configure Apps & Games (${customBlockedPackages.size} restricted)" else "Basic Coverage (3 Apps Restricted)",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        // Start trigger CTA button
                        Button(
                            onClick = {
                                if (!isProSubscribed && completedToday >= 15) {
                                    showDailyLimitReachedDialog = true
                                } else {
                                    val currentTopic = studyTopicInput.ifBlank { "General Studying" }
                                    keyboardFocus.clearFocus()
                                    if (!isAccessibilityEnabled || !isNotifListenerEnabled) {
                                        showPermissionOnStartPromptDialog = true
                                    } else {
                                        val finalDuration = if (isProSubscribed) selectedDurationMinutes else selectedDurationMinutes.coerceIn(1, 120)
                                        FocusManager.startFocusSession(
                                            context, 
                                            currentTopic, 
                                            finalDuration, 
                                            customQuoteInput.ifBlank { null },
                                            lockMode = if (isProSubscribed) parentLockMode else "NONE",
                                            passcode = if (isProSubscribed && parentLockMode == "PASSCODE") parentPasscodeInput else ""
                                        )
                                    }
                                }
                            },
                            enabled = if (isProSubscribed) (parentLockMode != "PASSCODE" || parentPasscodeInput.length == 5) else true,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF6750A4),
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("enable_timer_button"),
                            shape = RoundedCornerShape(28.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Initiate Deep Work Mode",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                        }

                        if (!isProSubscribed) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = Color(0xFF00796B),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Daily Limit: $completedToday/15 Sessions Used (Resets every 24h)",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = Color(0xFF00796B)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Permissions Service control toggles dashboard
        item {
            SystemPermissionsSetupCard(
                isAccessibilityEnabled = isAccessibilityEnabled,
                isDndEnabled = isDndEnabled,
                isNotifListenerEnabled = isNotifListenerEnabled,
                onRequestAccessibility = {
                    if (isAccessibilityEnabled) {
                        onRequestAccessibility()
                    } else {
                        showAccessibilityHelpDialog = true
                    }
                },
                onRequestDnd = {
                    if (isDndEnabled) {
                        onRequestDnd()
                    } else {
                        showDndHelpDialog = true
                    }
                },
                onRequestNotifListener = {
                    if (isNotifListenerEnabled) {
                        onRequestNotifListener()
                    } else {
                        showNotifListenerHelpDialog = true
                    }
                }
            )
        }

        // Dashboard Stats Overview
        item {
            FocusStreakAndRewardCard()
        }

        item {
            StatsOverviewWidget(
                totalMinutes = totalFocusedMinutes ?: 0,
                completedBlocksCount = totalCompletedBlocks ?: 0
            )
        }

        // Section: Session History
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "History & Logs",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    if (historySessions.isNotEmpty()) {
                        TextButton(
                            onClick = { viewModel.clearHistory() },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear All")
                        }
                    }
                }
            }
        }

        if (historySessions.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Timer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "No focus sessions recorded yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Complete your first deep work timer to start cataloging study analytics.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        } else {
            items(historySessions) { session ->
                HistoryItemList(session = session, onClick = { selectedPastSessionForDetail = session })
            }
        }
    }
    }
    }
    else if (selectedTab == "STATS") {
        if (userRole == "parent") {
            if (isParentSubscribed) {
                ParentStatsTabContent(
                    innerPadding = innerPadding,
                    linkedStudentId = linkedStudentId,
                    linkedStudentName = linkedStudentName,
                    linkedStudentStats = linkedStudentStats,
                    linkedStudentSessions = linkedStudentSessions,
                    linkedStudentSneaks = linkedStudentSneaks,
                    onSessionClick = { selectedPastSessionForDetail = it }
                )
            } else {
                com.example.ui.ParentUpgradePaywallPlaceholder(
                    innerPadding = innerPadding,
                    onUpgradeClick = { showSubscriptionManagementScreen = true }
                )
            }
        } else {
            StatsTabContent(
                innerPadding = innerPadding,
                totalFocusedMinutes = totalFocusedMinutes ?: 0,
                totalCompletedBlocks = totalCompletedBlocks ?: 0,
                customBlockedPackages = customBlockedPackages,
                historySessions = historySessions,
                viewModel = viewModel,
                showAppBlockingDialog = { showAppBlockingDialog = true },
                onSessionClick = { selectedPastSessionForDetail = it }
            )
        }
    }
    else if (selectedTab == "SETTINGS") {
        SettingsTabContent(
            innerPadding = innerPadding,
            userName = userName,
            userPassword = userPassword,
            weeklyTargetMinutes = weeklyTargetMinutes,
            isAccessibilityEnabled = isAccessibilityEnabled,
            isDndEnabled = isDndEnabled,
            isNotifListenerEnabled = isNotifListenerEnabled,
            onRequestAccessibility = {
                if (isAccessibilityEnabled) {
                    onRequestAccessibility()
                } else {
                    showAccessibilityHelpDialog = true
                }
            },
            onRequestDnd = {
                if (isDndEnabled) {
                    onRequestDnd()
                } else {
                    showDndHelpDialog = true
                }
            },
            onRequestNotifListener = {
                if (isNotifListenerEnabled) {
                    onRequestNotifListener()
                } else {
                    showNotifListenerHelpDialog = true
                }
            },
            viewModel = viewModel,
            onNavigateToSubscriptions = { showSubscriptionManagementScreen = true }
        )
    }
    else if (selectedTab == "CHAT") {
        AiChatTabContent(
            innerPadding = innerPadding,
            userRole = userRole,
            onUpgradeRequest = { showProPaywallDialog = true }
        )
    }
}

    // Interactive Report Dialog on timer expiry
    if (showSummary != null) {
        FocusSessionReportDialog(
            session = showSummary!!,
            onDismiss = { 
                FocusManager.clearSummary()
                try {
                    val intent = Intent(context, com.example.MainDashboardActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to launch MainDashboardActivity redirect intent", e)
                }
            }
        )
    }

    // Past Focus Session Detail Dialog
    if (selectedPastSessionForDetail != null) {
        FocusSessionReportDialog(
            session = selectedPastSessionForDetail!!,
            isHistoryView = true,
            onDismiss = { selectedPastSessionForDetail = null }
        )
    }

    // Custom Sliding Drawer & Profile bottom sheet triggers
    FocusMenuSideDrawer(
        isOpen = showMenuSideDrawer,
        onClose = { showMenuSideDrawer = false },
        otherAppsAttempted = otherAppsAttemptedCount,
        historySessions = historySessions,
        totalFocusedMinutes = totalFocusedMinutes ?: 0,
        weeklyTargetMinutes = weeklyTargetMinutes,
        onWeeklyTargetChange = { FocusManager.updateWeeklyTargetMinutes(it) },
        onSessionClick = { selectedPastSessionForDetail = it }
    )

    FocusProfilePanel(
        isOpen = showProfilePanel,
        onClose = { showProfilePanel = false },
        userName = userName,
        onUserNameChange = { FocusManager.updateUserName(it) },
        userPass = userPassword,
        onUserPassChange = { FocusManager.updateUserPassword(it) }
    )

    if (showAppBlockingDialog) {
        if (isProSubscribed) {
            AppBlockingChecklistDialog(
                onDismiss = { showAppBlockingDialog = false }
            )
        } else {
            showAppBlockingDialog = false
            showFreeAppBlockingInfoDialog = true
        }
    }

    if (showFreeAppBlockingInfoDialog) {
        AlertDialog(
            onDismissRequest = { showFreeAppBlockingInfoDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Block,
                    contentDescription = null,
                    tint = Color(0xFF6750A4),
                    modifier = Modifier.size(40.dp)
                )
            },
            title = {
                Text(
                    text = "Basic Distraction Coverage",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Under the Student Free Tier, basic distraction blocking is automatically active for the most common distractions:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF49454F)
                    )
                    
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(imageVector = Icons.Default.Check, contentDescription = "Active", tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp))
                                Text("YouTube App & Website", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(imageVector = Icons.Default.Check, contentDescription = "Active", tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp))
                                Text("Instagram App", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(imageVector = Icons.Default.Check, contentDescription = "Active", tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp))
                                Text("Facebook App", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    
                    Text(
                        text = "Upgrade to Focus Pro or Parent plan to customize this list, select any installed apps/games, block arbitrary website domains, and set advanced lock mechanics.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF625B71)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFreeAppBlockingInfoDialog = false
                        showProPaywallDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
                ) {
                    Text("Upgrade for Custom Apps")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFreeAppBlockingInfoDialog = false }) {
                    Text("Got it")
                }
            }
        )
    }

    if (showDailyLimitReachedDialog) {
        AlertDialog(
            onDismissRequest = { showDailyLimitReachedDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFB3261E),
                    modifier = Modifier.size(40.dp)
                )
            },
            title = {
                Text(
                    text = "Daily Limit Reached",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "You have completed your 15 focus sessions limit for today under the Free Tier.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF49454F)
                    )
                    Text(
                        text = "To start unlimited focus sessions, select custom app lists, and access parent scheduling features, please upgrade to Premium.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF625B71)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDailyLimitReachedDialog = false
                        showProPaywallDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
                ) {
                    Text("Upgrade to Premium")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDailyLimitReachedDialog = false }) {
                    Text("Dismiss")
                }
            }
        )
    }

    // 1. Accessibility Guide Dialog
    if (showAccessibilityHelpDialog) {
        AlertDialog(
            onDismissRequest = { showAccessibilityHelpDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.AccessibilityNew,
                    contentDescription = null,
                    tint = Color(0xFF6750A4),
                    modifier = Modifier.size(40.dp)
                )
            },
            title = {
                Text(
                    text = "Accessibility Blocker",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Text(
                        text = "To monitor active windows and automatically overlay block screens when distracting apps are opened, please enable the Accessibility Service.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF49454F)
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "How to activate:",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF1D1B20)
                            )
                            Text(
                                text = "1. Click 'Go to Settings' below.\n2. Tap 'Installed apps' (or 'Downloaded services').\n3. Select 'Focus' & switch it ON.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF49454F)
                            )
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFDE8E8)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "⚠️ Locked / Restricted Setting? (Android 13+)",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFB3261E)
                            )
                            Text(
                                text = "If the switch is disabled or greyed out:\n1. Go to your home screen.\n2. Long-press 'Focus' app icon & tap 'App info'.\n3. Click 3 dots at elite top-right.\n4. Select 'Allow restricted settings' & authenticate.\n5. Re-open this app and tap Configure again!",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFB3261E)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAccessibilityHelpDialog = false
                        onRequestAccessibility()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
                ) {
                    Text("Go to Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityHelpDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 2. DND Guide Dialog
    if (showDndHelpDialog) {
        AlertDialog(
            onDismissRequest = { showDndHelpDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.DoNotDisturbOn,
                    contentDescription = null,
                    tint = Color(0xFF6750A4),
                    modifier = Modifier.size(40.dp)
                )
            },
            title = {
                Text(
                    text = "Do Not Disturb Sync",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "To let Focus automatically manage your phone's ringing and media priorities during deep work blocks.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF49454F)
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "How to activate:",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF1D1B20)
                            )
                            Text(
                                text = "1. Click 'Go to Settings' below.\n2. Find 'Focus' in the Access settings list.\n3. Turn the switch ON to allow Zen DND triggering.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF49454F)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDndHelpDialog = false
                        onRequestDnd()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
                ) {
                    Text("Go to Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDndHelpDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 3. Notification Listener Guide Dialog
    if (showNotifListenerHelpDialog) {
        AlertDialog(
            onDismissRequest = { showNotifListenerHelpDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = null,
                    tint = Color(0xFF6750A4),
                    modifier = Modifier.size(40.dp)
                )
            },
            title = {
                Text(
                    text = "Push Alert Filter",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "To block, mute, and instantly filter loud distractions from social networking apps during active Focus sessions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF49454F)
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "How to activate:",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF1D1B20)
                            )
                            Text(
                                text = "1. Click 'Go to Settings' below.\n2. Locate 'Focus' in the list.\n3. Switch ON 'Allow notification access'.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF49454F)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNotifListenerHelpDialog = false
                        onRequestNotifListener()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
                ) {
                    Text("Go to Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotifListenerHelpDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 4. Session Start Prompt Dialog
    if (showPermissionOnStartPromptDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionOnStartPromptDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = Color(0xFFD97706),
                    modifier = Modifier.size(40.dp)
                )
            },
            title = {
                Text(
                    text = "Setup Strict Protection?",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "You are starting a focus session, but the screen blocker and notification filters are not active yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF49454F)
                    )
                    Text(
                        text = "To guarantee you won't get distracted by social media, we highly recommend switching them on first. Would you like to set them up now?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF49454F)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionOnStartPromptDialog = false
                        showAccessibilityHelpDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
                ) {
                    Text("Setup Services")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPermissionOnStartPromptDialog = false
                        // Start session anyway as fallback
                        val currentTopic = studyTopicInput.ifBlank { "General Studying" }
                        val finalDuration = if (isProSubscribed) selectedDurationMinutes else selectedDurationMinutes.coerceIn(1, 120)
                        FocusManager.startFocusSession(
                            context,
                            currentTopic,
                            finalDuration,
                            customQuoteInput.ifBlank { null },
                            lockMode = if (isProSubscribed) parentLockMode else "NONE",
                            passcode = if (isProSubscribed && parentLockMode == "PASSCODE") parentPasscodeInput else ""
                        )
                    }
                ) {
                    Text("Start Timer Only")
                }
            }
        )
    }

    // 5. Parent Passcode Verification Dialog to End/Pause session
    if (showPasscodeUnlockDialog) {
        AlertDialog(
            onDismissRequest = {
                showPasscodeUnlockDialog = false
                enteredPasscodeAttempt = ""
                passcodeAttemptError = false
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = Color(0xFFB3261E),
                    modifier = Modifier.size(40.dp)
                )
            },
            title = {
                Text(
                    text = "Parental Authorization",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (userPassword.isNotEmpty()) {
                            "Enter your custom Password Lock or a 5-Digit Parent Passcode to stop/pause this session."
                        } else {
                            "A 5-Digit Parent Passcode is required to end or pause this session."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF49454F),
                        textAlign = TextAlign.Center
                    )
                    OutlinedTextField(
                        value = enteredPasscodeAttempt,
                        onValueChange = { input ->
                            val maxLen = if (userPassword.isNotEmpty()) 30 else 5
                            if (input.length <= maxLen) {
                                enteredPasscodeAttempt = if (userPassword.isNotEmpty()) input else input.filter { it.isDigit() }
                            }
                            passcodeAttemptError = false
                        },
                        label = { Text(if (userPassword.isNotEmpty()) "Enter Password Lock or Passcode" else "Enter 5-Digit Passcode") },
                        placeholder = { Text(if (userPassword.isNotEmpty()) "••••••" else "•••••") },
                        isError = passcodeAttemptError,
                        supportingText = {
                            if (passcodeAttemptError) {
                                Text("Incorrect credentials. Try again.", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (userPassword.isNotEmpty()) KeyboardType.Text else KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        visualTransformation = if (userPassword.isNotEmpty()) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            focusedBorderColor = Color(0xFFB3261E),
                            unfocusedBorderColor = Color(0xFFCAC4D0),
                            focusedTextColor = Color(0xFF1D1B20),
                            unfocusedTextColor = Color(0xFF1D1B20)
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val isMatchParent = enteredPasscodeAttempt == sessionPasscode
                        val isMatchUser = userPassword.isNotEmpty() && enteredPasscodeAttempt == userPassword
                        if (isMatchParent || isMatchUser) {
                            showPasscodeUnlockDialog = false
                            enteredPasscodeAttempt = ""
                            passcodeAttemptError = false
                            FocusManager.stopFocusSession(context, false)
                        } else {
                            passcodeAttemptError = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E))
                ) {
                    Text("Unlock & Stop")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPasscodeUnlockDialog = false
                        enteredPasscodeAttempt = ""
                        passcodeAttemptError = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showSubscriptionManagementScreen) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showSubscriptionManagementScreen = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            com.example.ui.SubscriptionManagementScreen(
                onDismiss = { showSubscriptionManagementScreen = false },
                onPaymentCompleted = { showSubscriptionManagementScreen = false }
            )
        }
    }

    if (showProPaywallDialog) {
        com.example.ui.PremiumPaywallDialog(
            requiredPlan = "Focus Pro",
            onDismiss = { showProPaywallDialog = false },
            onNavigateToPlans = { showSubscriptionManagementScreen = true }
        )
    }
}

@Composable
fun GeometricTimer(
    timeFormatted: String,
    progress: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(240.dp)
            .aspectRatio(1f),
        contentAlignment = Alignment.Center
    ) {
        val strokeWidth = 12.dp
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            // Draw background stroke
            drawCircle(
                color = Color(0xFFEADDFF),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth.toPx())
            )
            // Draw progress circle stroke
            drawArc(
                color = Color(0xFF6750A4),
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = strokeWidth.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            )
        }
        
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = timeFormatted,
                style = MaterialTheme.typography.displayLarge.copy(
                    fontWeight = FontWeight.Light,
                    fontSize = 44.sp,
                ),
                color = Color(0xFF1D1B20)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "DEEP WORK",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                ),
                color = Color(0xFF6750A4)
            )
        }
    }
}

@Composable
fun StatusCard(appsBlocked: Int, notifsMuted: Int, timeLeftSeconds: Long) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF6750A4)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DoNotDisturbOn,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column {
                    Text(
                        text = "Strict Mode Active",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color(0xFF1D1B20)
                    )
                    Text(
                        text = "$appsBlocked apps & $notifsMuted notifications blocked",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF49454F)
                    )
                }
            }
            
            HorizontalDivider(color = Color(0xFFCAC4D0), thickness = 1.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "NEXT WORK MILESTONE",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = Color(0xFF49454F)
                )
                
                val halfTimeText = if (timeLeftSeconds > 60) "${timeLeftSeconds / 60} MINS LEFT" else "$timeLeftSeconds SECS LEFT"
                Text(
                    text = halfTimeText,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF6750A4)
                )
            }
        }
    }
}

@Composable
fun MotivationalQuoteView(quote: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val displayQuote = if (quote.isNotBlank()) quote else "The successful warrior is the average man, with laser-like focus."
        val displayAuthor = if (quote.isNotBlank() && !quote.startsWith("Your future self")) "— Study Boost" else "— Bruce Lee"
        
        Text(
            text = "\"$displayQuote\"",
            style = MaterialTheme.typography.bodyLarge.copy(
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                lineHeight = 24.sp
            ),
            color = Color(0xFF49454F),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = displayAuthor.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            ),
            color = Color(0xFF6750A4)
        )
    }
}

@Composable
fun ActiveFocusTimerCard(
    topic: String,
    timeLeftSeconds: Long,
    appsBlocked: Int,
    notifsMuted: Int,
    sessionLockMode: String = "PASSCODE",
    isPro: Boolean = false,
    completedTodayCount: Int = 0,
    onEndSession: () -> Unit
) {
    LaunchedEffect(Unit) {
        FocusManager.incrementTimeChecksCount()
    }

    val minutes = timeLeftSeconds / 60
    val seconds = timeLeftSeconds % 60
    val timeFormatted = String.format("%02d:%02d", minutes, seconds)

    val totalDurationSeconds = FocusManager.currentDurationMinutes * 60f
    val progress = if (totalDurationSeconds > 0f) timeLeftSeconds.toFloat() / totalDurationSeconds else 1f

    val quote by FocusManager.currentQuote.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Study Context & Session Badges
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = Color(0xFF6750A4).copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.School,
                        contentDescription = null,
                        tint = Color(0xFF6750A4),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Studying: $topic",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF6750A4)
                    )
                }
            }

            if (!isPro) {
                Surface(
                    color = Color(0xFF00796B).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color(0xFF00796B),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Session ${completedTodayCount + 1}/15",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF00796B)
                        )
                    }
                }
            }
        }

        // Geometric Timer Component
        GeometricTimer(
            timeFormatted = timeFormatted,
            progress = progress,
            modifier = Modifier.padding(vertical = 12.dp)
        )

        // Status Card
        StatusCard(
            appsBlocked = appsBlocked,
            notifsMuted = notifsMuted,
            timeLeftSeconds = timeLeftSeconds
        )

        // Motivational Quote
        MotivationalQuoteView(quote = quote)

        // Primary Action Button (End Session)
        if (sessionLockMode == "STRICT") {
            Button(
                onClick = { /* Locked! */ },
                enabled = false,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE0E0E0),
                    contentColor = Color.Gray,
                    disabledContainerColor = Color(0xFFE0E0E0),
                    disabledContentColor = Color.Gray
                ),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Session strictly locked by parent",
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "STRICT LOCK (No Stop Allowed)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        } else {
            Button(
                onClick = onEndSession,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("disable_timer_button")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.StopCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isPro) "END WITH PARENT PASSCODE" else "END SESSION",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SystemPermissionsSetupCard(
    isAccessibilityEnabled: Boolean,
    isDndEnabled: Boolean,
    isNotifListenerEnabled: Boolean,
    onRequestAccessibility: () -> Unit,
    onRequestDnd: () -> Unit,
    onRequestNotifListener: () -> Unit
) {
    var showAccessibilityHelp by remember { mutableStateOf(false) }
    var selectedBrandTab by remember { mutableStateOf("Vivo/Oppo") }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "System Services & Permissions",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF1D1B20)
            )
            Text(
                text = "Configure these settings to allow Focus to automatically lock media feeds and suppress incoming alerts.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF49454F)
            )

            HorizontalDivider(color = Color(0xFFCAC4D0), thickness = 1.dp)

            // Item: DND Policy Toggle
            PermissionStatusRow(
                title = "Do Not Disturb Sync",
                subtitle = "Enters Android DND work profile mode automatically",
                isGranted = isDndEnabled,
                onClick = onRequestDnd,
                modifier = Modifier.testTag("permission_row_dnd")
            )

            // Item: Accessibility service toggle
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PermissionStatusRow(
                    title = "Accessibility Blocker overlay",
                    subtitle = "Monitors launches to display AI quote locks over social apps",
                    isGranted = isAccessibilityEnabled,
                    onClick = onRequestAccessibility,
                    modifier = Modifier.testTag("permission_row_accessibility")
                )

                // Expandable Instructions Help Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showAccessibilityHelp = !showAccessibilityHelp }
                        .background(Color(0xFF6750A4).copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Help Guide",
                        tint = Color(0xFF6750A4),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (showAccessibilityHelp) "Hide Instructions" else "Vivo, Redmi, Samsung setup guide. Click here!",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF6750A4)
                    )
                }

                // Beautiful Brand Instructions panel
                if (showAccessibilityHelp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .background(Color.White, shape = RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Locating 'Focus' Service in Settings",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF1D1B20)
                        )
                        
                        // Brand Chips Tab selector
                        val brands = listOf("Vivo/Oppo", "Xiaomi/Redmi", "Samsung", "OnePlus/Realme", "Others")
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            brands.forEach { brand ->
                                val isTabSelected = selectedBrandTab == brand
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (isTabSelected) Color(0xFF6750A4) else Color(0xFFF3EDF7),
                                    contentColor = if (isTabSelected) Color.White else Color(0xFF49454F),
                                    modifier = Modifier
                                        .clickable { selectedBrandTab = brand }
                                ) {
                                    Text(
                                        text = brand,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = Color(0xFFCAC4D0).copy(alpha = 0.5f), thickness = 1.dp)

                        // Selected instruction manual
                        val steps = when (selectedBrandTab) {
                            "Vivo/Oppo" -> listOf(
                                "1. Look for 'Focus' in the accessibility list that opened.",
                                "2. Path helper: Go to Settings -> 'Shortcuts & accessibility' -> 'Accessibility' (or System management -> Accessibility).",
                                "3. Open 'Installed Services' or 'Downloaded apps'.",
                                "4. Choose 'Focus' and switch it ON."
                            )
                            "Xiaomi/Redmi" -> listOf(
                                "1. Go to Settings -> 'Additional settings' -> 'Accessibility'.",
                                "2. Select 'Downloaded apps' (or Installed services).",
                                "3. Choose 'Focus' and turn it ON.",
                                "⚠️ Restricted Settings Warning? Long-press Focus app icon -> tap App Info (i) -> tap 3 dots in top-right -> select 'Allow restricted settings' -> return here."
                            )
                            "Samsung" -> listOf(
                                "1. Under settings list, find 'Focus'.",
                                "2. Path helper: Go to Settings -> 'Accessibility'.",
                                "3. Tap 'Installed apps' (or Installed services).",
                                "4. Select 'Focus' & switch ON."
                            )
                            "OnePlus/Realme" -> listOf(
                                "1. Path helper: Go to Settings -> 'System settings' (or Additional settings) -> 'Accessibility'.",
                                "2. Open 'Installed apps' / 'Downloaded apps'.",
                                "3. Select 'Focus' -> switch ON."
                            )
                            else -> listOf(
                                "1. Look for 'Focus' in the system settings list.",
                                "2. If not visible: Go to Settings -> 'Accessibility' -> 'Downloaded services' / 'Installed apps'.",
                                "3. Locate 'Focus' and switch ON."
                            )
                        }

                        steps.forEach { step ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = step,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF49454F)
                                )
                            }
                        }
                    }
                }
            }

            // Item: Notification Listener permission toggle
            PermissionStatusRow(
                title = "Push Notifications Filter",
                subtitle = "Intercepts and clears incoming noisy distractions",
                isGranted = isNotifListenerEnabled,
                onClick = onRequestNotifListener,
                modifier = Modifier.testTag("permission_row_notification")
            )
        }
    }
}

@Composable
fun PermissionStatusRow(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (isGranted) Color(0xFF10B981).copy(alpha = 0.15f)
                    else Color(0xFFFBBF24).copy(alpha = 0.15f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Default.Check else Icons.Default.AddModerator,
                contentDescription = null,
                tint = if (isGranted) Color(0xFF10B981) else Color(0xFFD97706),
                modifier = Modifier.size(18.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color(0xFF1D1B20))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Color(0xFF49454F))
        }

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (isGranted) Color(0xFF10B981).copy(alpha = 0.12f) else Color(0xFFFBBF24).copy(alpha = 0.12f),
            contentColor = if (isGranted) Color(0xFF10B981) else Color(0xFFD97706),
        ) {
            Text(
                text = if (isGranted) "Enabled" else "Configure",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun FocusStreakAndRewardCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val totalStars by com.example.RewardManager.totalStars.collectAsStateWithLifecycle()
    val currentStreak by com.example.StreakManager.currentStreak.collectAsStateWithLifecycle()
    val multiplier by com.example.StreakManager.streakMultiplier.collectAsStateWithLifecycle()

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFF9E6) // Beautiful subtle golden amber/light yellow
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF39C12).copy(alpha = 0.25f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .background(Color(0xFFFFEEBA), shape = RoundedCornerShape(16.dp))
            ) {
                Text(
                    text = if (currentStreak == 0) "❄️" else "🔥",
                    style = MaterialTheme.typography.headlineSmall
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (currentStreak == 0) "No active streak today" else "$currentStreak Day Streak!",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF7D6608)
                )
                Text(
                    text = if (currentStreak == 0) "Complete a session to start your streak!" else "Active Multiplier: ${multiplier}x Stars",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = Color(0xFF9A7D0A)
                )
            }

            Surface(
                color = Color(0xFFF39C12).copy(alpha = 0.12f),
                contentColor = Color(0xFFD35400),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("⭐", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "$totalStars",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

@Composable
fun StatsOverviewWidget(
    totalMinutes: Int,
    completedBlocksCount: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Blocks Completed widget Card
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8DEF8)),
            modifier = Modifier.weight(1f)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.WorkspacePremium,
                    contentDescription = null,
                    tint = Color(0xFF6750A4),
                    modifier = Modifier.size(24.dp)
                )
                Text("Study Sessions", style = MaterialTheme.typography.labelMedium, color = Color(0xFF49454F))
                Text(
                    text = "$completedBlocksCount blocks",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                    color = Color(0xFF1D1B20)
                )
            }
        }

        // Total focused minutes card
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFEADDFF)),
            modifier = Modifier.weight(1f)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Timelapse,
                    contentDescription = null,
                    tint = Color(0xFF6750A4),
                    modifier = Modifier.size(24.dp)
                )
                Text("Total Focus Time", style = MaterialTheme.typography.labelMedium, color = Color(0xFF49454F))
                
                val hours = totalMinutes / 60
                val mins = totalMinutes % 60
                val display = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
                Text(
                    text = display,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                    color = Color(0xFF1D1B20)
                )
            }
        }
    }
}

@Composable
fun HistoryItemList(
    session: com.example.data.FocusSession,
    onClick: (() -> Unit)? = null
) {
    val dateString = remember(session.dateTimestamp) {
        val sdf = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault())
        sdf.format(Date(session.dateTimestamp))
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
        modifier = if (onClick != null) {
            Modifier
                .fillMaxWidth()
                .clickable { onClick() }
        } else {
            Modifier.fillMaxWidth()
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        if (session.isCompleted) Color(0xFF10B981).copy(alpha = 0.1f)
                        else Color(0xFFEF4444).copy(alpha = 0.1f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (session.isCompleted) Icons.Default.Verified else Icons.Default.EventBusy,
                    contentDescription = null,
                    tint = if (session.isCompleted) Color(0xFF10B981) else Color(0xFFEF4444),
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.topic,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF1D1B20),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = dateString,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF49454F)
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${session.durationMinutes} min",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF6750A4)
                )
                if (session.appsBlockedCount > 0 || session.notificationsMutedCount > 0) {
                    val alerts = session.appsBlockedCount + session.notificationsMutedCount
                    Text(
                        text = "Blocked $alerts alerts",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFB3261E)
                    )
                } else {
                    Text(
                        text = "Pure study session",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF10B981)
                    )
                }
            }
        }
    }
}

@Composable
fun FocusMenuSideDrawer(
    isOpen: Boolean,
    onClose: () -> Unit,
    otherAppsAttempted: Int,
    historySessions: List<com.example.data.FocusSession>,
    totalFocusedMinutes: Int,
    weeklyTargetMinutes: Int,
    onWeeklyTargetChange: (Int) -> Unit,
    onSessionClick: (com.example.data.FocusSession) -> Unit = {}
) {
    if (!isOpen) return

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable { onClose() },
            contentAlignment = Alignment.CenterStart
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.85f)
                    .background(
                        color = Color(0xFFFEF7FF),
                        shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
                    )
                    .clickable(enabled = false) { }
                    .padding(vertical = 24.dp, horizontal = 16.dp)
            ) {
                // Drawer Header
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = Color(0xFF6750A4),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Focus Cabinet",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF1D1B20)
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF49454F))
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                // Stats / Real-time tracker panel
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "LIVE DISTRACTION MONITOR",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                            color = Color(0xFF6750A4)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Escape Attempts:", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (otherAppsAttempted > 0) Color(0xFFB3261E).copy(alpha = 0.12f) else Color(0xFF10B981).copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = "$otherAppsAttempted clicks",
                                    color = if (otherAppsAttempted > 0) Color(0xFFB3261E) else Color(0xFF10B981),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        // Distraction Level Analysis
                        val distractionLevel = when {
                            otherAppsAttempted == 0 -> "Pristine Style (Zero Escape Attempts) 🌟"
                            otherAppsAttempted <= 2 -> "Light Drifting (Low Distraction) 🎯"
                            otherAppsAttempted <= 5 -> "Moderate Interceptions ⚠️"
                            else -> "Heavily Distracted 🚨"
                        }
                        
                        Text(
                            text = distractionLevel,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF49454F)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Weekly stats
                val oneWeekAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
                val weeklyMinutes = historySessions.filter { 
                    it.dateTimestamp >= oneWeekAgo && it.isCompleted 
                }.sumOf { it.durationMinutes }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFEADDFF)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "WEEKLY STUDY TARGET",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                                color = Color(0xFF6750A4)
                            )
                            if (weeklyTargetMinutes > 0) {
                                TextButton(
                                    onClick = { onWeeklyTargetChange(0) },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.height(24.dp).testTag("remove_weekly_target_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Remove Target",
                                        tint = Color(0xFFB3261E),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Remove", color = Color(0xFFB3261E), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        if (weeklyTargetMinutes > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Focused This Week:", style = MaterialTheme.typography.bodyMedium)
                                Text("$weeklyMinutes / $weeklyTargetMinutes min", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                            }
                            
                            // Simple Weekly goal percentage indicator
                            val progress = (weeklyMinutes.toFloat() / weeklyTargetMinutes).coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { progress },
                                color = Color(0xFF6750A4),
                                trackColor = Color(0xFFFEF7FF),
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp).clip(RoundedCornerShape(3.dp))
                            )

                            // Quick adjust target:
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Adjust Goal:", style = MaterialTheme.typography.labelSmall, color = Color(0xFF49454F))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = { onWeeklyTargetChange(weeklyTargetMinutes - 30) },
                                        modifier = Modifier.size(24.dp).background(Color.White, CircleShape).testTag("decrease_target_button")
                                    ) {
                                        Text("-", fontWeight = FontWeight.Bold, color = Color(0xFF6750A4), fontSize = 14.sp)
                                    }
                                    Text("$weeklyTargetMinutes m", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(horizontal = 4.dp))
                                    IconButton(
                                        onClick = { onWeeklyTargetChange(weeklyTargetMinutes + 30) },
                                        modifier = Modifier.size(24.dp).background(Color.White, CircleShape).testTag("increase_target_button")
                                    ) {
                                        Text("+", fontWeight = FontWeight.Bold, color = Color(0xFF6750A4), fontSize = 14.sp)
                                    }
                                }
                            }
                        } else {
                            // Target is 0/removed, show a place/button to create target
                            Text(
                                text = "Weekly study target is currently removed. Create a new target to keep track of your focus commitments!",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF49454F)
                            )
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            Button(
                                onClick = { onWeeklyTargetChange(300) }, // Default to 300 minutes when created
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().testTag("create_weekly_target_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Create Study Target (300 min/wk)", fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val context = LocalContext.current
                var drawerTab by remember { mutableStateOf("SESSIONS") } // "SESSIONS" or "DISTRACTIONS"
                val distractionHistory = remember(isOpen, drawerTab) {
                    if (drawerTab == "DISTRACTIONS") {
                        com.example.HistoryManager.getFormattedHistory(context)
                    } else {
                        emptyList()
                    }
                }

                // Scrollable History / Distraction tab header & selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (drawerTab == "SESSIONS") "Historical Runs Logs" else "Distraction Interrupts",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    if (drawerTab == "DISTRACTIONS" && distractionHistory.isNotEmpty()) {
                        var showConfirmClearDialog by remember { mutableStateOf(false) }
                        
                        TextButton(
                            onClick = { showConfirmClearDialog = true },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear Interrupts", tint = Color(0xFFB3261E), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear", color = Color(0xFFB3261E), style = MaterialTheme.typography.labelMedium)
                        }
                        
                        if (showConfirmClearDialog) {
                            AlertDialog(
                                onDismissRequest = { showConfirmClearDialog = false },
                                title = { Text("Clear Distraction Logs?") },
                                text = { Text("This will permanently reset the counter for all intercepted distraction attempts. This cannot be undone.") },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            com.example.HistoryManager.clearHistory(context)
                                            com.example.service.FirebaseSyncManager.clearSneakAttemptsInCloud(context)
                                            showConfirmClearDialog = false
                                            drawerTab = "SESSIONS" // Trigger refresh
                                            drawerTab = "DISTRACTIONS"
                                        }
                                    ) {
                                        Text("Clear All", color = Color(0xFFB3261E))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showConfirmClearDialog = false }) {
                                        Text("Cancel")
                                    }
                                }
                            )
                        }
                    }
                }

                // Tab selectors: Session vs Distractions
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isSessionsSelected = drawerTab == "SESSIONS"
                    FilterChip(
                        selected = isSessionsSelected,
                        onClick = { drawerTab = "SESSIONS" },
                        label = { Text("Runs History") },
                        leadingIcon = if (isSessionsSelected) {
                            { Icon(Icons.Default.History, null, Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                    val isDistractionsSelected = drawerTab == "DISTRACTIONS"
                    FilterChip(
                        selected = isDistractionsSelected,
                        onClick = { drawerTab = "DISTRACTIONS" },
                        label = { Text("Distractions") },
                        leadingIcon = if (isDistractionsSelected) {
                            { Icon(Icons.Default.Block, null, Modifier.size(16.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (drawerTab == "SESSIONS") {
                        if (historySessions.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Ready to record logs.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.Gray
                                    )
                                }
                            }
                        } else {
                            items(historySessions) { session ->
                                val dateStr = remember(session.dateTimestamp) {
                                    val sdf = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
                                    sdf.format(Date(session.dateTimestamp))
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.White,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCAC4D0).copy(0.4f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSessionClick(session) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(session.topic, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(dateStr, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "${session.durationMinutes} min",
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                            color = Color(0xFF6750A4)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        if (distractionHistory.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No recorded distraction intercepts yet.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.Gray,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            items(distractionHistory, key = { it.packageName }) { item ->
                                DistractionHistoryItemRow(item = item)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FocusProfilePanel(
    isOpen: Boolean,
    onClose: () -> Unit,
    userName: String,
    onUserNameChange: (String) -> Unit,
    userPass: String,
    onUserPassChange: (String) -> Unit,
) {
    if (!isOpen) return

    var editingName by remember(userName) { mutableStateOf(userName) }
    var editingPassword by remember(userPass) { mutableStateOf(userPass) }
    var isPasswordVisible by remember { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable { onClose() },
            contentAlignment = Alignment.CenterEnd
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.85f)
                    .background(
                        color = Color(0xFFFEF7FF),
                        shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
                    )
                    .clickable(enabled = false) { }
                    .padding(vertical = 24.dp, horizontal = 16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = null,
                            tint = Color(0xFF6750A4),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Student Profile",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF1D1B20)
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF49454F))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Scrollable content (Name, Password, Terms, Privacy Policy)
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    item {
                        FocusStreakAndRewardCard()
                    }

                    item {
                        Text(
                            text = "CREDENTIALS & LOCK CONFIG",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                            color = Color(0xFF6750A4)
                        )
                    }

                    // Student Name Input
                    item {
                        OutlinedTextField(
                            value = editingName,
                            onValueChange = { editingName = it },
                            label = { Text("Student Name") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFF6750A4)) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White,
                                focusedBorderColor = Color(0xFF6750A4),
                                unfocusedBorderColor = Color(0xFFCAC4D0),
                                focusedTextColor = Color(0xFF1D1B20),
                                unfocusedTextColor = Color(0xFF1D1B20)
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("profile_name_input")
                        )
                    }

                    // Password Configuration Input
                    item {
                        val context = LocalContext.current
                        OutlinedTextField(
                            value = editingPassword,
                            onValueChange = { editingPassword = it },
                            label = { Text("Create / Edit Password lock") },
                            placeholder = { Text("To restrict access during deep work") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF6750A4)) },
                            trailingIcon = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    // Eye Toggle
                                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                        Icon(
                                            imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = if (isPasswordVisible) "Hide Password" else "Show Password",
                                            tint = Color(0xFF6750A4)
                                        )
                                    }
                                    
                                    // Save button (only if changed)
                                    if (editingPassword != userPass) {
                                        IconButton(
                                            onClick = {
                                                onUserPassChange(editingPassword)
                                                android.widget.Toast.makeText(context, "Password lock saved!", android.widget.Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.testTag("save_pass_trailing_sidebar")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Save Password Lock",
                                                tint = Color(0xFF2E7D32)
                                            )
                                        }
                                    }
                                }
                            },
                            visualTransformation = if (isPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White,
                                focusedBorderColor = Color(0xFF6750A4),
                                unfocusedBorderColor = Color(0xFFCAC4D0),
                                focusedTextColor = Color(0xFF1D1B20),
                                unfocusedTextColor = Color(0xFF1D1B20)
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("profile_password_input")
                        )
                    }

                    // PHYSICAL SAVE PROFILE BUTTON
                    item {
                        val context = LocalContext.current
                        val coroutineScope = rememberCoroutineScope()
                        var isSavingProfile by remember { mutableStateOf(false) }

                        Button(
                            onClick = {
                                isSavingProfile = true
                                onUserNameChange(editingName.trim())
                                onUserPassChange(editingPassword)
                                
                                FirebaseSyncManager.updateUsernameDisplayName(context, editingName.trim())

                                val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                                if (currentUser != null) {
                                    try {
                                        if (editingPassword.isNotEmpty()) {
                                            if (editingPassword.length >= 6) {
                                                val hasPasswordProvider = currentUser.providerData.any { it.providerId == com.google.firebase.auth.EmailAuthProvider.PROVIDER_ID }
                                                if (hasPasswordProvider) {
                                                    currentUser.updatePassword(editingPassword)
                                                        .addOnSuccessListener {
                                                            android.util.Log.d("Settings", "FirebaseAuth Password updated successfully!")
                                                            android.widget.Toast.makeText(context, "Firebase account login password updated also!", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                        .addOnFailureListener { e ->
                                                            android.util.Log.e("Settings", "Failed updating Firebase auth password", e)
                                                            android.widget.Toast.makeText(context, "Saved locally but Firebase password update notes: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                                                        }
                                                } else {
                                                    val email = currentUser.email
                                                    if (!email.isNullOrEmpty()) {
                                                        val credential = com.google.firebase.auth.EmailAuthProvider.getCredential(email, editingPassword)
                                                        currentUser.linkWithCredential(credential)
                                                            .addOnSuccessListener {
                                                                android.util.Log.d("Settings", "FirebaseAuth Account Linked successfully with Email/Password!")
                                                                android.widget.Toast.makeText(context, "Firebase account linked with password successfully! You can now sign in using both Google and Email/Password.", android.widget.Toast.LENGTH_LONG).show()
                                                            }
                                                            .addOnFailureListener { e ->
                                                                android.util.Log.e("Settings", "Failed to link Firebase auth email/password provider", e)
                                                                currentUser.updatePassword(editingPassword)
                                                                    .addOnSuccessListener {
                                                                        android.widget.Toast.makeText(context, "Firebase account login password updated!", android.widget.Toast.LENGTH_SHORT).show()
                                                                    }
                                                                    .addOnFailureListener { updateError ->
                                                                        android.widget.Toast.makeText(context, "Saved locally but Firebase link failed: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                                                                    }
                                                            }
                                                    } else {
                                                        currentUser.updatePassword(editingPassword)
                                                            .addOnSuccessListener {
                                                                android.widget.Toast.makeText(context, "Firebase account login password updated!", android.widget.Toast.LENGTH_SHORT).show()
                                                            }
                                                            .addOnFailureListener { e ->
                                                                android.widget.Toast.makeText(context, "Failed updating Firebase auth password: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                                                            }
                                                    }
                                                }
                                            } else {
                                                android.widget.Toast.makeText(context, "Password must be at least 6 characters for Firebase accounts.", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        }

                                        coroutineScope.launch {
                                            try {
                                                val stars = com.example.RewardManager.getTotalStars(context)
                                                val streak = com.example.StreakManager.getCurrentStreak(context)
                                                FirebaseSyncManager.syncData(
                                                    context = context,
                                                    userName = editingName.trim(),
                                                    totalStars = stars,
                                                    currentStreak = streak,
                                                    weeklyTargetMinutes = FocusManager.weeklyTargetMinutes.value,
                                                    sessions = emptyList() // Or just backup profile
                                                )
                                                android.widget.Toast.makeText(context, "Profile and cloud database updated successfully!", android.widget.Toast.LENGTH_SHORT).show()
                                            } catch (e: Exception) {
                                                android.util.Log.e("Settings", "Store Sync failing", e)
                                            } finally {
                                                isSavingProfile = false
                                            }
                                        }
                                    } catch (e: Exception) {
                                        isSavingProfile = false
                                        android.widget.Toast.makeText(context, "Profile and Lock saved! Remote Sync: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    isSavingProfile = false
                                    android.widget.Toast.makeText(context, "Profile and Passcode Lock settings saved successfully!", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("save_profile_sidebar_btn"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF6750A4),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isSavingProfile && editingName.trim().isNotEmpty()
                        ) {
                            if (isSavingProfile) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Saving...")
                            } else {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Save Credentials & Lock Settings", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                            }
                        }
                    }

                    // Study Mantra Section
                    item {
                        Text(
                            text = "YOUR STUDY MANTRA",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                            color = Color(0xFF6750A4)
                        )
                    }

                    item {
                        val context = LocalContext.current
                        var tempQuote by remember { mutableStateOf(com.example.QuoteManager.getSavedQuote(context)) }
                        
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF9E6)), // Warm golden theme
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF39C12).copy(alpha = 0.25f)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Your Personal Focus Phrase",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF7D6608)
                                )
                                OutlinedTextField(
                                    value = tempQuote,
                                    onValueChange = { if (it.length <= 120) tempQuote = it },
                                    placeholder = { Text("Type something that fires you up...", color = Color.Gray.copy(alpha = 0.8f)) },
                                    singleLine = false,
                                    maxLines = 3,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = Color.White,
                                        unfocusedContainerColor = Color.White,
                                        focusedBorderColor = Color(0xFFF39C12),
                                        unfocusedBorderColor = Color(0xFFF39C12).copy(alpha = 0.5f),
                                        focusedTextColor = Color(0xFF1D1B20),
                                        unfocusedTextColor = Color(0xFF1D1B20)
                                    ),
                                    modifier = Modifier.fillMaxWidth().testTag("custom_quote_input")
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Button(
                                        onClick = {
                                            val cleanInput = tempQuote.trim()
                                            if (cleanInput.isNotEmpty()) {
                                                com.example.QuoteManager.saveQuote(context, cleanInput)
                                                android.widget.Toast.makeText(context, "Mantra updated! Stay focused.", android.widget.Toast.LENGTH_SHORT).show()
                                            } else {
                                                android.widget.Toast.makeText(context, "Write a quick message to stay inspired!", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFFF39C12),
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Update Phrase", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                                    }
                                }
                            }
                        }
                    }

                    // Section Terms & Conditions
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFCAC4D0).copy(0.4f)))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "TERMS & CONDITIONS",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                            color = Color(0xFF6750A4)
                        )
                    }

                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "1. Student Focus Responsibility",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF1D1B20)
                                )
                                Text(
                                    text = "Focus App provides voluntary on-device tools to suppress intrusive digital items. The user remains solely accountable for executing their study commitments.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF49454F)
                                )
                                
                                Text(
                                    text = "2. Service Consent",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF1D1B20)
                                )
                                Text(
                                    text = "By initiating the Deep Work mode, you explicitly consent to the Accessibility Service blocking scheduled entertaining services and suppressing dynamic overlay banners.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF49454F)
                                )
                            }
                        }
                    }

                    // Section Privacy Policy
                    item {
                        Text(
                            text = "PRIVACY POLICY",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                            color = Color(0xFF6750A4)
                        )
                    }

                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "1. 100% Local Processing",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF1D1B20)
                                )
                                Text(
                                    text = "All study sessions parameters, duration minutes, and tracked escape attempts are processed securely on your local device. We never sell, share, or transit data online.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF49454F)
                                )
                                
                                Text(
                                    text = "2. Zero Accessibility Harvesting",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF1D1B20)
                                )
                                Text(
                                    text = "Our Accessibility Service uses event checking solely to lock distracting packages and prevent escape. No keystrokes, messages, or screen data are stored or written.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF49454F)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

data class AppInfoModel(
    val appName: String,
    val packageName: String,
    val isGame: Boolean,
    val isBlocked: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBlockingChecklistDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val customBlockedPackages by FocusManager.customBlockedPackages.collectAsStateWithLifecycle()
    val isFocusActive by FocusManager.isFocusActive.collectAsStateWithLifecycle()

    var installedAppsList by remember { mutableStateOf<List<AppInfoModel>>(emptyList()) }
    var isAppsLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf("ALL") } // "ALL", "GAMES", "BLOCKED"

    LaunchedEffect(customBlockedPackages) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val packages = pm.getInstalledPackages(0)
                val list = ArrayList<AppInfoModel>()
                for (pkgInfo in packages) {
                    val pkgName = pkgInfo.packageName
                    if (pkgName == context.packageName) continue
                    
                    val appInfo = pkgInfo.applicationInfo ?: continue
                    val appName = try {
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        pkgName
                    }
                    
                    val isGame = GameDetector.isAppAGame(context, pkgName)
                    list.add(
                        AppInfoModel(
                            appName = appName,
                            packageName = pkgName,
                            isGame = isGame,
                            isBlocked = customBlockedPackages.contains(pkgName)
                        )
                    )
                }
                list.sortBy { it.appName.lowercase() }
                
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    installedAppsList = list
                    isAppsLoading = false
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error loading installed apps", e)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    isAppsLoading = false
                }
            }
        }
    }

    val filteredApps = remember(installedAppsList, searchQuery, selectedTab, customBlockedPackages) {
        installedAppsList.filter { app ->
            val matchesSearch = app.appName.lowercase().contains(searchQuery.lowercase()) ||
                    app.packageName.lowercase().contains(searchQuery.lowercase())
            val matchesTab = when (selectedTab) {
                "GAMES" -> app.isGame
                "BLOCKED" -> customBlockedPackages.contains(app.packageName)
                else -> true
            }
            matchesSearch && matchesTab
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Restrict Apps & Games",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF1D1B20)
                        )
                        Text(
                            text = "${customBlockedPackages.size} restricted applications",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF6750A4)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close dialog",
                            tint = Color(0xFF49454F)
                        )
                    }
                }
                
                if (isFocusActive) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = Color(0xFFFFF3CD),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFEBAA)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Locked",
                                tint = Color(0xFF856404)
                            )
                            Text(
                                text = "Deep Study Mode is Active! Restricting toggles are locked until the active timer completes to keep your focus secure.",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF856404)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Search Box
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search apps or package names...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = Color(0xFF49454F)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear search",
                                    tint = Color(0xFF49454F)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFFF7F2FA),
                        unfocusedContainerColor = Color(0xFFF7F2FA),
                        focusedBorderColor = Color(0xFF6750A4),
                        unfocusedBorderColor = Color(0xFFCAC4D0),
                        focusedTextColor = Color(0xFF1D1B20),
                        unfocusedTextColor = Color(0xFF1D1B20)
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Tab Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val tabData = listOf("ALL" to "All", "GAMES" to "Games", "BLOCKED" to "Restricted")
                    tabData.forEach { (key, label) ->
                        val isSelected = selectedTab == key
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedTab = key },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFE8DEF8),
                                selectedLabelColor = Color(0xFF1D1B20)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // List Area
                if (isAppsLoading) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(color = Color(0xFF6750A4))
                            Text(
                                text = "Scanning installed applications...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF49454F)
                            )
                        }
                    }
                } else {
                    if (filteredApps.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SearchOff,
                                    contentDescription = null,
                                    tint = Color.LightGray,
                                    modifier = Modifier.size(64.dp)
                                )
                                Text(
                                    text = "No applications found",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF1D1B20)
                                )
                                Text(
                                    text = "Try adjusting your search query or switching filter tabs.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF49454F),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredApps, key = { it.packageName }) { app ->
                                val isBlocked = customBlockedPackages.contains(app.packageName)
                                AppItemRow(
                                    app = app,
                                    isBlocked = isBlocked,
                                    enabled = !isFocusActive,
                                    onToggle = {
                                        if (isFocusActive) {
                                            android.widget.Toast.makeText(context, "Cannot change restrictions during active Deep Study!", android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            FocusManager.toggleCustomBlockedPackage(context, app.packageName)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Actions/Cancel Button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("Done Selecting")
                }
            }
        }
    }
}

@Composable
fun AppItemRow(
    app: AppInfoModel,
    isBlocked: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isBlocked) {
                if (enabled) Color(0xFFFDE8E8) else Color(0xFFFDE8E8).copy(alpha = 0.6f)
            } else {
                Color(0xFFF3EDF7).copy(alpha = if (enabled) 0.5f else 0.3f)
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
                .then(
                    if (!enabled) Modifier.alpha(0.6f) else Modifier
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Dynamic high-quality App Icon
            AppIconBitmap(
                packageName = app.packageName,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Text Info Panel
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF1D1B20),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    if (app.isGame) {
                        Surface(
                            color = Color(0xFF10B981).copy(alpha = 0.15f),
                            contentColor = Color(0xFF047857),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "GAME",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF49454F),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (!enabled) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Locked settings",
                    tint = Color(0xFFB3261E),
                    modifier = Modifier.size(24.dp).padding(horizontal = 2.dp)
                )
            } else {
                // Checkbox for active restriction status
                Checkbox(
                    checked = isBlocked,
                    onCheckedChange = { onToggle() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Color(0xFFB3261E),
                        uncheckedColor = Color(0xFF49454F)
                    )
                )
            }
        }
    }
}

@Composable
fun AppIconBitmap(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pm = remember { context.packageManager }
    val iconBitmap = remember(packageName) {
        try {
            val drawable = pm.getApplicationIcon(packageName)
            val bitmap = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, 96, 96)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    if (iconBitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = iconBitmap,
            contentDescription = "App Icon",
            modifier = modifier
        )
    } else {
        Icon(
            imageVector = Icons.Default.Apps,
            contentDescription = "Standard App Icon",
            tint = Color(0xFF6750A4),
            modifier = modifier
        )
    }
}

@Composable
fun DistractionHistoryItemRow(item: com.example.HistoryItem) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCAC4D0).copy(0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconForHistory(
                drawable = item.appIcon,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.readableAppName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color(0xFF1D1B20)
                )
                Text(
                    text = item.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                color = Color(0xFFB3261E).copy(alpha = 0.12f),
                contentColor = Color(0xFFB3261E),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "${item.clickCount} blocks",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
fun AppIconForHistory(drawable: android.graphics.drawable.Drawable?, modifier: Modifier = Modifier) {
    val bitmap = remember(drawable) {
        if (drawable == null) null else {
            try {
                val bmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                drawable.setBounds(0, 0, 96, 96)
                drawable.draw(canvas)
                bmp.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }

    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = "App Icon",
            modifier = modifier
        )
    } else {
        Icon(
            imageVector = Icons.Default.Apps,
            contentDescription = "Standard App Icon",
            tint = Color(0xFF6750A4),
            modifier = modifier
        )
    }
}

@Composable
fun StatsTabContent(
    innerPadding: PaddingValues,
    totalFocusedMinutes: Int,
    totalCompletedBlocks: Int,
    customBlockedPackages: Set<String>,
    historySessions: List<com.example.data.FocusSession>,
    viewModel: FocusViewModel,
    showAppBlockingDialog: () -> Unit,
    onSessionClick: (com.example.data.FocusSession) -> Unit
) {
    val context = LocalContext.current
    var statsTabSelected by remember { mutableStateOf("SESSIONS") }
    val distractionHistory = remember(statsTabSelected) {
        if (statsTabSelected == "DISTRACTIONS") {
            com.example.HistoryManager.getFormattedHistory(context)
        } else {
            emptyList()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .testTag("stats_root"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Identity Header
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "Focus Logo",
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF6750A4))
                            .padding(4.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Stats & Highlights",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            fontSize = 22.sp
                        ),
                        color = Color(0xFF1D1B20)
                    )
                }
            }
        }

        // Focus streak & rewards
        item {
            FocusStreakAndRewardCard()
        }

        // Overview boxes (Minutes and Blocks)
        item {
            StatsOverviewWidget(
                totalMinutes = totalFocusedMinutes,
                completedBlocksCount = totalCompletedBlocks
            )
        }

        // App & Game Blocking Button
        item {
            OutlinedButton(
                onClick = showAppBlockingDialog,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFF6750A4)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEADDFF))
            ) {
                Icon(
                    imageVector = Icons.Default.Block,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Configure Apps & Games (${customBlockedPackages.size} restricted)",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        }

        // Segment Tabs: Runs History vs Distractions
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (statsTabSelected == "SESSIONS") "Historical Runs Logs" else "Distraction Interrupts",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                if (statsTabSelected == "DISTRACTIONS" && distractionHistory.isNotEmpty()) {
                    var showConfirmClearDialog by remember { mutableStateOf(false) }
                    
                    TextButton(
                        onClick = { showConfirmClearDialog = true },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear Interrupts", tint = Color(0xFFB3261E), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear", color = Color(0xFFB3261E), style = MaterialTheme.typography.labelMedium)
                    }
                    
                    if (showConfirmClearDialog) {
                        AlertDialog(
                            onDismissRequest = { showConfirmClearDialog = false },
                            title = { Text("Clear Distraction Logs?") },
                            text = { Text("This will permanently reset the counter for all intercepted distraction attempts. This cannot be undone.") },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        com.example.HistoryManager.clearHistory(context)
                                        com.example.service.FirebaseSyncManager.clearSneakAttemptsInCloud(context)
                                        showConfirmClearDialog = false
                                        // Toggle state to force refresh
                                        statsTabSelected = "SESSIONS"
                                        statsTabSelected = "DISTRACTIONS"
                                    }
                                ) {
                                    Text("Clear All", color = Color(0xFFB3261E))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showConfirmClearDialog = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }
                } else if (statsTabSelected == "SESSIONS" && historySessions.isNotEmpty()) {
                    TextButton(
                        onClick = { viewModel.clearHistory() },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear All")
                    }
                }
            }
        }

        // Tab selection pills: Session vs Distractions
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isSessionsSelected = statsTabSelected == "SESSIONS"
                FilterChip(
                    selected = isSessionsSelected,
                    onClick = { statsTabSelected = "SESSIONS" },
                    label = { Text("Runs History") },
                    leadingIcon = if (isSessionsSelected) {
                        { Icon(Icons.Default.History, null, Modifier.size(16.dp)) }
                    } else null,
                    modifier = Modifier.weight(1f)
                )
                val isDistractionsSelected = statsTabSelected == "DISTRACTIONS"
                FilterChip(
                    selected = isDistractionsSelected,
                    onClick = { statsTabSelected = "DISTRACTIONS" },
                    label = { Text("Distractions") },
                    leadingIcon = if (isDistractionsSelected) {
                        { Icon(Icons.Default.Block, null, Modifier.size(16.dp)) }
                    } else null,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Sessions or Distractions list items
        if (statsTabSelected == "SESSIONS") {
            if (historySessions.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No focus sessions recorded yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }
                }
            } else {
                items(historySessions) { session ->
                    HistoryItemList(session = session, onClick = { onSessionClick(session) })
                }
            }
        } else {
            if (distractionHistory.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No recorded distraction intercepts yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                items(distractionHistory, key = { it.packageName }) { item ->
                    DistractionHistoryItemRow(item = item)
                }
            }
        }
    }
}

@Composable
fun SettingsTabContent(
    innerPadding: PaddingValues,
    userName: String,
    userPassword: String,
    weeklyTargetMinutes: Int,
    isAccessibilityEnabled: Boolean,
    isDndEnabled: Boolean,
    isNotifListenerEnabled: Boolean,
    onRequestAccessibility: () -> Unit,
    onRequestDnd: () -> Unit,
    onRequestNotifListener: () -> Unit,
    viewModel: FocusViewModel,
    onNavigateToSubscriptions: () -> Unit
) {
    val context = LocalContext.current
    val userRole by FirebaseSyncManager.userRole.collectAsStateWithLifecycle()
    var editingName by remember(userName) { mutableStateOf(userName) }
    var editingPassword by remember(userPassword) { mutableStateOf(userPassword) }
    var editingWeeklyTarget by remember(weeklyTargetMinutes) { mutableStateOf(if (weeklyTargetMinutes == 0) "" else weeklyTargetMinutes.toString()) }
    var isPasswordVisible by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .testTag("settings_root"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Identity Header
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "Focus Logo",
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF6750A4))
                            .padding(4.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            fontSize = 22.sp
                        ),
                        color = Color(0xFF1D1B20)
                    )
                }
            }
        }

        // App Custom Premium Badge & Subscription Tier Card
        item {
            val plan by com.example.service.SubscriptionManager.currentPlan.collectAsStateWithLifecycle()
            val status by com.example.service.SubscriptionManager.status.collectAsStateWithLifecycle()
            val isPremiumViaParent by com.example.service.SubscriptionManager.isPremiumViaParent.collectAsStateWithLifecycle()
            val isProSubscribed by com.example.service.SubscriptionManager.isPro.collectAsStateWithLifecycle()
            
            val isPro = isProSubscribed
            
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onNavigateToSubscriptions() },
                colors = CardDefaults.cardColors(
                    containerColor = if (isPro) Color(0xFF6750A4).copy(alpha = 0.08f) else Color.White
                ),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.2.dp, if (isPro) Color(0xFF6750A4).copy(alpha = 0.35f) else Color(0xFFEADDFF))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (isPro) Color(0xFF6750A4) else Color(0xFF6750A4).copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPro) Icons.Default.Stars else Icons.Default.AddShoppingCart,
                            contentDescription = null,
                            tint = if (isPro) Color.White else Color(0xFF6750A4)
                        )
                    }
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isPro) "Active Plan: $plan" else "Unlock Focus Premium",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF1D1B20)
                        )
                        Text(
                            text = if (isPremiumViaParent) "Active • Provided by Parent" else if (isPro) "Billing Status: $status • Tap to Manage" else "Get custom blocklists, unlimited sessions & AI coaching",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF49454F)
                        )
                    }
                    
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Navigate to subscriptions",
                        tint = Color(0xFF49454F)
                    )
                }
            }
        }

        // Account Management Title Header & Card
        item {
            Text(
                text = "ACCOUNT MANAGEMENT",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = Color(0xFF6750A4)
            )
        }

        item {
            val usernameProfile by FirebaseSyncManager.usernameProfile.collectAsStateWithLifecycle()
            val currentUserEmail by FirebaseSyncManager.currentUserEmail.collectAsStateWithLifecycle()
            val userRoleState by FirebaseSyncManager.userRole.collectAsStateWithLifecycle()

            val displayName = usernameProfile.ifEmpty { userName.ifEmpty { "Connected Account" } }
            val displayEmail = currentUserEmail.ifEmpty { "No Email Linked" }
            val displayRole = if (userRoleState == "parent") "Parent" else "Student"

            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.2.dp, Color(0xFFEADDFF)),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("account_management_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Profile details header row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF6750A4).copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = Color(0xFF6750A4),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Signed In Profile",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF1D1B20)
                            )
                            Text(
                                text = "Manage your cloud account & session state",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }

                    HorizontalDivider(color = Color(0xFFEADDFF).copy(alpha = 0.6f))

                    // Account Details Fields
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Username Field
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Username",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.Gray
                            )
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = Color(0xFF1D1B20),
                                modifier = Modifier.testTag("account_username_text")
                            )
                        }

                        // Email Field
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Email",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.Gray
                            )
                            Text(
                                text = displayEmail,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF1D1B20),
                                modifier = Modifier.testTag("account_email_text")
                            )
                        }

                        // Role Field
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Account Role",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.Gray
                            )
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = if (userRoleState == "parent") Color(0xFFE8F5E9) else Color(0xFFE8EAF6),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = displayRole,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = if (userRoleState == "parent") Color(0xFF2E7D32) else Color(0xFF3F51B5)
                                    ),
                                    modifier = Modifier.testTag("account_role_text")
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = Color(0xFFEADDFF).copy(alpha = 0.6f))

                    // Sign Out and Switch Account Buttons
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Switch Account Button
                        OutlinedButton(
                            onClick = {
                                FirebaseSyncManager.signOut(context, forceDirectLogin = true)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("switch_account_btn"),
                            border = BorderStroke(1.2.dp, Color(0xFF6750A4)),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFF6750A4)
                            )
                        ) {
                            Icon(Icons.Default.Sync, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Switch Account", fontWeight = FontWeight.Bold)
                        }

                        // Logout Button
                        Button(
                            onClick = {
                                FirebaseSyncManager.signOut(context, forceDirectLogin = false)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("logout_btn"),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFB3261E),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Default.ExitToApp, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Log Out", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Student Profile Header & Inputs
        item {
            Text(
                text = if (userRole == "parent") "PARENT PROFILE" else "STUDENT PROFILE",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = Color(0xFF6750A4)
            )
        }

        // Student Name Input
        item {
            OutlinedTextField(
                value = editingName,
                onValueChange = { editingName = it },
                label = { Text(if (userRole == "parent") "Parent Name" else "Student Name") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFF6750A4)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedBorderColor = Color(0xFF6750A4),
                    unfocusedBorderColor = Color(0xFFCAC4D0),
                    focusedTextColor = Color(0xFF1D1B20),
                    unfocusedTextColor = Color(0xFF1D1B20)
                ),
                modifier = Modifier.fillMaxWidth().testTag("profile_name_input")
            )
        }

        // Password Configuration Input
        item {
            OutlinedTextField(
                value = editingPassword,
                onValueChange = { editingPassword = it },
                label = { Text("Create / Edit Password Lock") },
                placeholder = { Text("To restrict access during deep work") },
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF6750A4)) },
                trailingIcon = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        // Eye Toggle
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Icon(
                                imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (isPasswordVisible) "Hide Password" else "Show Password",
                                tint = Color(0xFF6750A4)
                            )
                        }
                        
                        // Save button (only if changed)
                        if (editingPassword != userPassword) {
                            IconButton(
                                onClick = {
                                    FocusManager.updateUserPassword(editingPassword)
                                    android.widget.Toast.makeText(context, "Password lock saved!", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.testTag("save_pass_trailing_settings")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Save Password Lock",
                                    tint = Color(0xFF2E7D32)
                                )
                            }
                        }
                    }
                },
                visualTransformation = if (isPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedBorderColor = Color(0xFF6750A4),
                    unfocusedBorderColor = Color(0xFFCAC4D0),
                    focusedTextColor = Color(0xFF1D1B20),
                    unfocusedTextColor = Color(0xFF1D1B20)
                ),
                modifier = Modifier.fillMaxWidth().testTag("profile_password_input")
            )
        }

        // Target goal settings
        item {
            OutlinedTextField(
                value = editingWeeklyTarget,
                onValueChange = { editingWeeklyTarget = it.filter { c -> c.isDigit() } },
                label = { Text("Weekly Study Target Minutes") },
                leadingIcon = { Icon(Icons.Default.Adjust, contentDescription = null, tint = Color(0xFF6750A4)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedBorderColor = Color(0xFF6750A4),
                    unfocusedBorderColor = Color(0xFFCAC4D0),
                    focusedTextColor = Color(0xFF1D1B20),
                    unfocusedTextColor = Color(0xFF1D1B20)
                ),
                modifier = Modifier.fillMaxWidth().testTag("weekly_target_input")
            )
        }

        // PHYSICAL SAVE PROFILE BUTTON
        item {
            val coroutineScope = rememberCoroutineScope()
            var isSavingProfile by remember { mutableStateOf(false) }

            Button(
                onClick = {
                    isSavingProfile = true
                    val targetMins = editingWeeklyTarget.toIntOrNull() ?: 0
                    FocusManager.updateUserName(editingName.trim())
                    FocusManager.updateUserPassword(editingPassword)
                    FocusManager.updateWeeklyTargetMinutes(targetMins)
                    
                    FirebaseSyncManager.updateUsernameDisplayName(context, editingName.trim())

                    val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                    if (currentUser != null) {
                        try {
                            if (editingPassword.isNotEmpty()) {
                                if (editingPassword.length >= 6) {
                                    val hasPasswordProvider = currentUser.providerData.any { it.providerId == com.google.firebase.auth.EmailAuthProvider.PROVIDER_ID }
                                    if (hasPasswordProvider) {
                                        currentUser.updatePassword(editingPassword)
                                            .addOnSuccessListener {
                                                android.util.Log.d("Settings", "FirebaseAuth Password updated successfully!")
                                                android.widget.Toast.makeText(context, "Firebase account login password updated also!", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                            .addOnFailureListener { e ->
                                                android.util.Log.e("Settings", "Failed updating Firebase auth password", e)
                                                android.widget.Toast.makeText(context, "Saved locally but Firebase password update notes: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                    } else {
                                        val email = currentUser.email
                                        if (!email.isNullOrEmpty()) {
                                            val credential = com.google.firebase.auth.EmailAuthProvider.getCredential(email, editingPassword)
                                            currentUser.linkWithCredential(credential)
                                                .addOnSuccessListener {
                                                    android.util.Log.d("Settings", "FirebaseAuth Account Linked successfully with Email/Password!")
                                                    android.widget.Toast.makeText(context, "Firebase account linked with password successfully! You can now sign in using both Google and Email/Password.", android.widget.Toast.LENGTH_LONG).show()
                                                }
                                                .addOnFailureListener { e ->
                                                    android.util.Log.e("Settings", "Failed to link Firebase auth email/password provider", e)
                                                    currentUser.updatePassword(editingPassword)
                                                        .addOnSuccessListener {
                                                            android.widget.Toast.makeText(context, "Firebase account login password updated!", android.widget.Toast.LENGTH_SHORT).show()
                                                        }
                                                        .addOnFailureListener { updateError ->
                                                            android.widget.Toast.makeText(context, "Saved locally but Firebase link failed: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                                                        }
                                                }
                                        } else {
                                            currentUser.updatePassword(editingPassword)
                                                .addOnSuccessListener {
                                                    android.widget.Toast.makeText(context, "Firebase account login password updated!", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                                .addOnFailureListener { e ->
                                                    android.widget.Toast.makeText(context, "Failed updating Firebase auth password: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                                                }
                                        }
                                    }
                                } else {
                                    android.widget.Toast.makeText(context, "Password must be at least 6 characters for Firebase accounts.", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }

                            coroutineScope.launch {
                                try {
                                    val activeSessions = viewModel.allSessions.value
                                    val stars = com.example.RewardManager.getTotalStars(context)
                                    val streak = com.example.StreakManager.getCurrentStreak(context)
                                    FirebaseSyncManager.syncData(
                                        context = context,
                                        userName = editingName.trim(),
                                        totalStars = stars,
                                        currentStreak = streak,
                                        weeklyTargetMinutes = targetMins,
                                        sessions = activeSessions
                                    )
                                    android.widget.Toast.makeText(context, "Profile settings and cloud database synched successfully!", android.widget.Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    android.util.Log.e("Settings", "Store Sync failing", e)
                                } finally {
                                    isSavingProfile = false
                                }
                            }
                        } catch (e: Exception) {
                            isSavingProfile = false
                            android.widget.Toast.makeText(context, "Profile and Lock saved! Remote Sync: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    } else {
                        isSavingProfile = false
                        android.widget.Toast.makeText(context, "Profile and Passcode Lock settings saved successfully!", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("save_profile_btn"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6750A4),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                enabled = !isSavingProfile && editingName.trim().isNotEmpty()
            ) {
                if (isSavingProfile) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Saving Profile...")
                } else {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save Profile & Lock Settings", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                }
            }
        }

        // Study Mantra Section (The Favorite Quote editor)
        item {
            Text(
                text = "YOUR STUDY MANTRA",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = Color(0xFF6750A4)
            )
        }

        item {
            val context = LocalContext.current
            var tempQuote by remember { mutableStateOf(com.example.QuoteManager.getSavedQuote(context)) }
            
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF9E6)), // Warm golden theme
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF39C12).copy(alpha = 0.25f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Your Personal Focus Phrase",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF7D6608)
                    )
                    OutlinedTextField(
                        value = tempQuote,
                        onValueChange = { if (it.length <= 120) tempQuote = it },
                        placeholder = { Text("Type something that fires you up...", color = Color.Gray.copy(alpha = 0.8f)) },
                        singleLine = false,
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            focusedBorderColor = Color(0xFFF39C12),
                            unfocusedBorderColor = Color(0xFFF39C12).copy(alpha = 0.5f),
                            focusedTextColor = Color(0xFF1D1B20),
                            unfocusedTextColor = Color(0xFF1D1B20)
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("custom_quote_input")
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = {
                                val cleanInput = tempQuote.trim()
                                if (cleanInput.isNotEmpty()) {
                                    com.example.QuoteManager.saveQuote(context, cleanInput)
                                    android.widget.Toast.makeText(context, "Mantra updated! Stay focused.", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, "Write a quick message to stay inspired!", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFF39C12),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Update Phrase", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }
        }

        // Firebase Cloud Sync Section
        item {
            Text(
                text = "FAMILY CLOUD BACKUP & PARENT PORTAL",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = Color(0xFF6750A4)
            )
        }

        item {
            val coroutineScope = rememberCoroutineScope()
            
            // Collect live integrated flows from FirebaseSyncManager
            val syncState by FirebaseSyncManager.syncState.collectAsStateWithLifecycle()
            val lastSyncTime by FirebaseSyncManager.lastSyncTime.collectAsStateWithLifecycle()
            val currentUserUid by FirebaseSyncManager.currentUserUid.collectAsStateWithLifecycle()
            val currentUserEmail by FirebaseSyncManager.currentUserEmail.collectAsStateWithLifecycle()
            val userRole by FirebaseSyncManager.userRole.collectAsStateWithLifecycle()

            val usernameProfile by FirebaseSyncManager.usernameProfile.collectAsStateWithLifecycle()
            val studentAge by FirebaseSyncManager.studentAge.collectAsStateWithLifecycle()
            val studentStudyLevel by FirebaseSyncManager.studentStudyLevel.collectAsStateWithLifecycle()
            val studentUpcomingExam by FirebaseSyncManager.studentUpcomingExam.collectAsStateWithLifecycle()
            val studentDailyGoal by FirebaseSyncManager.studentDailyGoal.collectAsStateWithLifecycle()
            val parentType by FirebaseSyncManager.parentType.collectAsStateWithLifecycle()
            val generatedInviteCode by FirebaseSyncManager.generatedInviteCode.collectAsStateWithLifecycle()

            val linkedStudentId by FirebaseSyncManager.linkedStudentId.collectAsStateWithLifecycle()
            val linkedStudentName by FirebaseSyncManager.linkedStudentName.collectAsStateWithLifecycle()
            val linkedStudentStats by FirebaseSyncManager.linkedStudentStats.collectAsStateWithLifecycle()
            val linkedStudentSessions by FirebaseSyncManager.linkedStudentSessions.collectAsStateWithLifecycle()
            val linkedStudentSneaks by FirebaseSyncManager.linkedStudentSneaks.collectAsStateWithLifecycle()
            val isProSubscribed by com.example.service.SubscriptionManager.isPro.collectAsStateWithLifecycle()
            val isPremiumViaParent by com.example.service.SubscriptionManager.isPremiumViaParent.collectAsStateWithLifecycle()

            var studentIdLinkInput by remember { mutableStateOf("") }
            var inviteCodeInput by remember { mutableStateOf("") }
            var isLinkingInvite by remember { mutableStateOf(false) }

            val lastSyncText = remember(lastSyncTime) {
                if (lastSyncTime == 0L) {
                    "Never backed up"
                } else {
                    java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(lastSyncTime))
                }
            }

            Card(
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEADDFF)),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Title and Signout Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Firebase Cloud Integration",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF1D1B20)
                            )
                            Text(
                                text = "Synced family profile & parental dashboard",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                        IconButton(
                            onClick = { FirebaseSyncManager.signOut(context) },
                            modifier = Modifier.testTag("auth_logout_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ExitToApp,
                                contentDescription = "Sign Out",
                                tint = Color(0xFFB3261E)
                            )
                        }
                    }

                    HorizontalDivider(color = Color(0xFFEADDFF).copy(alpha = 0.6f))

                    // Account Stats block
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF9F7FC), shape = RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = Color(0xFF6750A4),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Signed In Profile",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF6750A4)
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Name: $usernameProfile",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = Color.DarkGray
                            )
                            Text(
                                text = "Email: " + (currentUserEmail.ifEmpty { "Connected Account" }),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                            Text(
                                text = "Role: ${userRole.uppercase()}" + (if (userRole == "parent" && parentType.isNotEmpty()) " ($parentType)" else "") + (if (FirebaseSyncManager.isSandboxSimulationActive) " [SANDBOX MODE]" else ""),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = if (FirebaseSyncManager.isSandboxSimulationActive) Color(0xFFE65100) else Color(0xFF6750A4)
                            )
                        }

                        // Connection State Indicator
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = when (syncState) {
                                        is FirebaseSyncManager.SyncState.Success -> Color(0xFFE8F5E9)
                                        is FirebaseSyncManager.SyncState.Error -> Color(0xFFFFEBEE)
                                        else -> Color.White
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = when (syncState) {
                                        is FirebaseSyncManager.SyncState.Success -> Icons.Default.CheckCircle
                                        is FirebaseSyncManager.SyncState.Error -> Icons.Default.Error
                                        is FirebaseSyncManager.SyncState.Syncing -> Icons.Default.Sync
                                        else -> Icons.Default.CloudQueue
                                    },
                                    contentDescription = null,
                                    tint = when (syncState) {
                                        is FirebaseSyncManager.SyncState.Success -> Color(0xFF4CAF50)
                                        is FirebaseSyncManager.SyncState.Error -> Color(0xFFF44336)
                                        else -> Color(0xFF6750A4)
                                    },
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = when (syncState) {
                                            is FirebaseSyncManager.SyncState.Unconfigured -> "Sync Idle"
                                            is FirebaseSyncManager.SyncState.ConfiguredButNotInitialized -> "Offline"
                                            is FirebaseSyncManager.SyncState.Initializing -> "Connecting..."
                                            is FirebaseSyncManager.SyncState.Ready -> if (FirebaseSyncManager.isSandboxSimulationActive) "Sandbox Mode (Zero-Friction)" else "Initialized & Connected"
                                            is FirebaseSyncManager.SyncState.Syncing -> "Syncing live cloud database..."
                                            is FirebaseSyncManager.SyncState.Success -> if (FirebaseSyncManager.isSandboxSimulationActive) "Database Sync Success (Sandbox)!" else "Database Sync Success!"
                                            is FirebaseSyncManager.SyncState.Error -> "Connection Temporary Offline"
                                        },
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF1D1B20)
                                    )
                                    Text(
                                        text = "Last Synced: $lastSyncText",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }

                    // STUDENT CONTROLS
                    if (userRole == "student") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            HorizontalDivider(color = Color(0xFFEADDFF).copy(alpha = 0.6f))
                            
                            // Student Profile Details Header
                            Text(
                                text = "STUDENT PROFILE METADATA",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.Gray
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(modifier = Modifier.weight(1.5f)) {
                                    Text("Studying: $studentStudyLevel", style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray)
                                    if (studentUpcomingExam.isNotEmpty()) {
                                        Text("Upcoming Exam: $studentUpcomingExam", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Age: $studentAge", style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray)
                                    Text("Daily Goal: $studentDailyGoal", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                            }

                            HorizontalDivider(color = Color(0xFFEADDFF).copy(alpha = 0.4f))

                            // Enter Invite Code to link Parent
                            Text(
                                text = "LINK WITH PARENT COACH",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                                color = Color(0xFF6750A4)
                            )

                            val linkedParentName = linkedStudentStats["linkedParentName"] as? String ?: ""
                            val linkedParentId = linkedStudentStats["linkedParentId"] as? String ?: ""
                            if (linkedParentName.isNotEmpty() || linkedParentId.isNotEmpty()) {
                                if (isPremiumViaParent || isProSubscribed) {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF81C784)),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.VerifiedUser, "Linked", tint = Color(0xFF2E7D32), modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = "Device Paired with Parent Coach: " + (linkedParentName.ifEmpty { "Active Monitor" }),
                                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                                    color = Color(0xFF1B5E20)
                                                )
                                                Text(
                                                    text = "Focus Pro Premium Active via Parent Coach",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF2E7D32)
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB74D)),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Link, "Connected", tint = Color(0xFFE65100), modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = "Connected to Parent Coach: " + (linkedParentName.ifEmpty { "Active Monitor" }),
                                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                                    color = Color(0xFFE65100)
                                                )
                                                Text(
                                                    text = "Premium access will activate when your parent coach subscribes.",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFFE65100)
                                                )
                                            }
                                        }
                                    }
                                }


                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "Ask your parent to tap 'Generate Invite Code' on their device settings and type their 6-character code below.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = inviteCodeInput,
                                            onValueChange = { inviteCodeInput = it.take(6) },
                                            placeholder = { Text("e.g. A9B8D2") },
                                            label = { Text("Invite Code", fontSize = 11.sp) },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f).testTag("enter_invite_code")
                                        )

                                        Button(
                                            onClick = {
                                                if (inviteCodeInput.length == 6) {
                                                    isLinkingInvite = true
                                                    FirebaseSyncManager.linkStudentWithInviteCode(context, inviteCodeInput) { ok, msg ->
                                                        isLinkingInvite = false
                                                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                                                        if (ok) {
                                                            inviteCodeInput = ""
                                                            FirebaseSyncManager.fetchLinkedStudentDataDirectly()
                                                        }
                                                    }
                                                } else {
                                                    android.widget.Toast.makeText(context, "Enter a valid 6-char pairing code", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            if (isLinkingInvite) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                            } else {
                                                Text("Pair")
                                            }
                                        }
                                    }
                                }
                            }

                            // Forced Sync Button
                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        val activeSessions = viewModel.allSessions.value
                                        val stars = com.example.RewardManager.getTotalStars(context)
                                        val streak = com.example.StreakManager.getCurrentStreak(context)
                                        
                                        FirebaseSyncManager.syncData(
                                            context = context,
                                            userName = usernameProfile,
                                            totalStars = stars,
                                            currentStreak = streak,
                                            weeklyTargetMinutes = weeklyTargetMinutes,
                                            sessions = activeSessions
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().testTag("sync_now_btn"),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Sync Focus Sessions & Streaks Now")
                            }
                        }
                    }

                    // PARENT CONTROLS
                    if (userRole == "parent") {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            HorizontalDivider(color = Color(0xFFEADDFF).copy(alpha = 0.6f))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF3E5F5)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "Parent Cockpit Active",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color(0xFF8E24AA)
                                    )
                                    Text(
                                        text = "To keep your custom configurations neat and clutter-free, all active student pairing setup and real-time monitoring activity reports (study sessions & distraction logs) have been consolidated directly into the 'Monitor' and 'Child Stats' tabs at the bottom.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.DarkGray
                                    )
                                }
                            }
                        }
                    }
                    if (false) {
                        Column {
                            // Child Device Activity logs Reports Panel
                            if (linkedStudentId.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, Color(0xFF6750A4).copy(alpha = 0.2f), shape = RoundedCornerShape(10.dp))
                                        .background(Color.White, shape = RoundedCornerShape(10.dp))
                                        .padding(12.dp)
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text("PARENT MONITOR DASHBOARD", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                                Text(
                                                    text = linkedStudentName.ifEmpty { "Linked Student" },
                                                    fontWeight = FontWeight.Bold,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = Color(0xFF6750A4)
                                                )
                                                val ageVal = linkedStudentStats["age"] as? String ?: ""
                                                val studyVal = linkedStudentStats["studyLevel"] as? String ?: ""
                                                val examVal = linkedStudentStats["upcomingExam"] as? String ?: ""
                                                if (ageVal.isNotEmpty() || studyVal.isNotEmpty()) {
                                                    Text(
                                                        text = "Age: $ageVal • Studying: $studyVal" + (if (examVal.isNotEmpty()) " • Exam: $examVal" else ""),
                                                        fontSize = 10.sp,
                                                        color = Color.DarkGray
                                                    )
                                                }
                                            }
                                            IconButton(
                                                onClick = { FirebaseSyncManager.fetchLinkedStudentDataDirectly() },
                                                modifier = Modifier.testTag("refresh_student_btn")
                                            ) {
                                                Icon(Icons.Default.Sync, contentDescription = "Refresh data", tint = Color(0xFF6750A4), modifier = Modifier.size(18.dp))
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            val streakVal = (linkedStudentStats["currentStreak"] as? Long) ?: 0L
                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(6.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Text("🔥 Streak", fontSize = 9.sp, color = Color.DarkGray)
                                                    Text("$streakVal Days", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE65100))
                                                }
                                            }

                                            val starsVal = (linkedStudentStats["totalStars"] as? Long) ?: 0L
                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFDE7)),
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(6.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Text("⭐️ Stars", fontSize = 9.sp, color = Color.DarkGray)
                                                    Text("$starsVal Saved", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFF57F17))
                                                }
                                            }

                                            val minVal = (linkedStudentStats["weeklyTargetMinutes"] as? Long) ?: 120L
                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(6.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Text("🎯 Target", fontSize = 9.sp, color = Color.DarkGray)
                                                    Text("$minVal m/wk", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF2E7D32))
                                                }
                                            }
                                        }

                                        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))

                                        Text(
                                            text = "Focused Study History (${linkedStudentSessions.size}):",
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                            color = Color.DarkGray
                                        )

                                        if (linkedStudentSessions.isEmpty()) {
                                            Text("No study sessions logged.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                        } else {
                                            Column(
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                                modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState())
                                            ) {
                                                linkedStudentSessions.forEach { session ->
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(Color(0xFFFBFBFC), shape = RoundedCornerShape(4.dp))
                                                            .padding(6.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(session.topic, fontWeight = FontWeight.Medium, fontSize = 11.sp, color = Color.Black)
                                                            val dateStr = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(session.dateTimestamp))
                                                            Text(dateStr, fontSize = 9.sp, color = Color.Gray)
                                                        }
                                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                            AssistChip(
                                                                onClick = {},
                                                                label = { Text("${session.durationMinutes}m", fontSize = 9.sp) },
                                                                modifier = Modifier.height(20.dp).padding(0.dp)
                                                            )
                                                            if (session.appsBlockedCount > 0) {
                                                                AssistChip(
                                                                    onClick = {},
                                                                    label = { Text("⚠️ ${session.appsBlockedCount} CLICKS", fontSize = 9.sp) },
                                                                    modifier = Modifier.height(20.dp).padding(0.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))

                                        Text(
                                            text = "App Distraction/Interception Attempts:",
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                            color = Color(0xFFB3261E)
                                        )

                                        if (linkedStudentSneaks.isEmpty()) {
                                            Text("No unauthorized attempts logged.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32))
                                        } else {
                                            Column(
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                linkedStudentSneaks.forEach { sneak ->
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(Color(0xFFFFEBEE), shape = RoundedCornerShape(4.dp))
                                                            .padding(6.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        val pName = (sneak["packageName"] as? String) ?: ""
                                                        val appTitle = (sneak["readableAppName"] as? String) ?: pName
                                                        val counts = (sneak["clickCount"] as? Long) ?: 1L
                                                        val lastTime = (sneak["timestamp"] as? Long) ?: 0L
                                                        
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(appTitle, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFFC62828))
                                                            Text(pName, fontSize = 9.sp, color = Color.Gray, maxLines = 1)
                                                        }
                                                        Column(horizontalAlignment = Alignment.End) {
                                                            Text("$counts blocked", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFFB3261E))
                                                            if (lastTime > 0) {
                                                                val timeStr = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(lastTime))
                                                                Text(timeStr, fontSize = 8.sp, color = Color.Gray)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = "Ready to receive live data feeds. Share your invite pairing code with your student to pair devices remotely.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        }

        // Permission triggers Triggers Setup Section
        item {
            Text(
                text = "SYSTEM PERMISSIONS SETUP",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = Color(0xFF6750A4)
            )
        }

        item {
            SystemPermissionsSetupCard(
                isAccessibilityEnabled = isAccessibilityEnabled,
                isDndEnabled = isDndEnabled,
                isNotifListenerEnabled = isNotifListenerEnabled,
                onRequestAccessibility = onRequestAccessibility,
                onRequestDnd = onRequestDnd,
                onRequestNotifListener = onRequestNotifListener
            )
        }

        // Reset options
        item {
            Card(
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEADDFF)),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Need a Fresh Start?",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF1D1B20)
                    )
                    Text(
                        text = "Resetting is permanent and clears all settings, streaks, and stars.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = Color.Gray
                    )
                    var showResetConfirm by remember { mutableStateOf(false) }
                    Button(
                        onClick = { showResetConfirm = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Reset All Data", color = Color.White)
                    }

                    if (showResetConfirm) {
                        AlertDialog(
                            onDismissRequest = { showResetConfirm = false },
                            title = { Text("Reset All App Data?") },
                            text = { Text("Are you absolutely sure? This will wipe your study streak, earned stars, customized blocked apps, and history logs database completely.") },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showResetConfirm = false
                                        val pm = context.getSharedPreferences("FocusRewardPrefs", Context.MODE_PRIVATE)
                                        pm.edit().clear().apply()
                                        RewardManager.init(context)

                                        val sm = context.getSharedPreferences("FocusStreakPrefs", Context.MODE_PRIVATE)
                                        sm.edit().clear().apply()
                                        StreakManager.init(context)

                                        val blockPrefs = context.getSharedPreferences("FocusPrefs", Context.MODE_PRIVATE)
                                        blockPrefs.edit().clear().apply()
                                        FocusManager.init(context)

                                        viewModel.clearHistory()
                                        com.example.HistoryManager.clearHistory(context)

                                        android.widget.Toast.makeText(context, "All app stats reset successfully", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Text("Reset Everything", color = Color(0xFFB3261E))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showResetConfirm = false }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// UPGRADED BEAUTIFUL ONBOARDING SYSTEM VIEWS
// ==========================================

@Composable
fun OnboardingFlowContainer(
    isAccessibilityEnabled: Boolean,
    isDndEnabled: Boolean,
    isNotifListenerEnabled: Boolean,
    isUsageAccessEnabled: Boolean,
    onRequestAccessibility: () -> Unit,
    onRequestDnd: () -> Unit,
    onRequestNotifListener: () -> Unit,
    onRequestUsageAccess: () -> Unit,
    onCheckAllPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val onboardingStep by FirebaseSyncManager.onboardingStep.collectAsStateWithLifecycle()
    
    var emailInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    
    var roleSelection by remember { mutableStateOf("student") }
    var studentAgeInput by remember { mutableStateOf("") }
    var studentSelectedLevel by remember { mutableStateOf("School") }
    var examInput by remember { mutableStateOf("") }
    var dailyGoalInput by remember { mutableStateOf("60 mins") }
    var parentSelectedType by remember { mutableStateOf("Mother") }
    
    var showLoginForm by remember { mutableStateOf(false) }
    var isRegisteringForm by remember { mutableStateOf(true) }
    var googleAuthError by remember { mutableStateOf("") }
    var formAuthError by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    // local sub steps for STEP 1: WELCOME screen
    // 0 = Welcome brand options screen, 1 = Permission Setup screen, 2 = True Login Screen
    val syncPrefs = context.getSharedPreferences("FirebaseSyncPrefs", android.content.Context.MODE_PRIVATE)
    val startAtLogin = syncPrefs.getBoolean("start_at_login_screen", false)
    var onboardingSubStep by remember { mutableStateOf(if (startAtLogin) 2 else 0) }

    androidx.compose.runtime.LaunchedEffect(onboardingStep) {
        if (onboardingStep == OnboardingStep.WELCOME) {
            emailInput = ""
            passwordInput = ""
            nameInput = ""
            formAuthError = ""
            googleAuthError = ""
            
            val activePrefs = context.getSharedPreferences("FirebaseSyncPrefs", android.content.Context.MODE_PRIVATE)
            if (activePrefs.getBoolean("start_at_login_screen", false)) {
                onboardingSubStep = 2
                showLoginForm = true
                isRegisteringForm = false
                activePrefs.edit().putBoolean("start_at_login_screen", false).apply()
            } else {
                onboardingSubStep = 0
                showLoginForm = false
                isRegisteringForm = true
            }
        }
    }

    val googleSignInLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (data != null) {
            FirebaseSyncManager.handleGoogleSignInResult(context, data) { success, msg ->
                isLoading = false
                if (success) {
                    nameInput = FirebaseSyncManager.usernameProfile.value
                } else {
                    googleAuthError = msg
                }
            }
        } else {
            isLoading = false
            googleAuthError = "Google sign-in was cancelled or failed (Result Code: ${result.resultCode})."
        }
    }

    val allPermissionsGranted = isAccessibilityEnabled && isDndEnabled && isNotifListenerEnabled && isUsageAccessEnabled

    when (onboardingStep) {
        OnboardingStep.INITIALIZING -> {
            InitializingSplashScreen(modifier = modifier)
        }
        OnboardingStep.WELCOME -> {
            when (onboardingSubStep) {
                0 -> {
                    WelcomeIntroScreen(
                        onContinueGoogle = {
                            if (allPermissionsGranted) {
                                onboardingSubStep = 2 // Skip directly in case permissions are already granted
                            } else {
                                onboardingSubStep = 1 // Enforce Mandatory Setup Screen
                            }
                        },
                        onContinueEmail = {
                            if (allPermissionsGranted) {
                                onboardingSubStep = 2 // Skip directly if permissions are already granted
                            } else {
                                onboardingSubStep = 1 // Enforce Mandatory Setup Screen
                            }
                        },
                        modifier = modifier
                    )
                }
                1 -> {
                    PermissionSetupScreen(
                        isAccessibilityEnabled = isAccessibilityEnabled,
                        isDndEnabled = isDndEnabled,
                        isNotifListenerEnabled = isNotifListenerEnabled,
                        isUsageAccessEnabled = isUsageAccessEnabled,
                        onRequestAccessibility = onRequestAccessibility,
                        onRequestDnd = onRequestDnd,
                        onRequestNotifListener = onRequestNotifListener,
                        onRequestUsageAccess = onRequestUsageAccess,
                        onCheckAllPermissions = onCheckAllPermissions,
                        onContinue = {
                            onboardingSubStep = 2 // Advance to Auth Screen!
                        },
                        onBack = {
                            onboardingSubStep = 0
                        },
                        modifier = modifier
                    )
                }
                2 -> {
                    WelcomeExperienceScreen(
                        emailInput = emailInput,
                        onEmailChange = { emailInput = it },
                        passwordInput = passwordInput,
                        onPasswordChange = { passwordInput = it },
                        nameInput = nameInput,
                        onNameChange = { nameInput = it },
                        showLoginForm = showLoginForm,
                        onToggleLoginForm = { showLoginForm = it },
                        isRegistering = isRegisteringForm,
                        onToggleRegisterMode = { isRegisteringForm = it },
                        googleAuthError = googleAuthError,
                        formAuthError = formAuthError,
                        onClearGoogleError = { googleAuthError = "" },
                        onClearFormError = { formAuthError = "" },
                        isLoading = isLoading,
                        onAuthenticateEmail = {
                            val emailTrimmed = emailInput.trim().lowercase()
                            val passwordVal = passwordInput
                            if (emailTrimmed.isBlank() || passwordVal.isBlank()) {
                                formAuthError = "Please enter both details."
                                return@WelcomeExperienceScreen
                            }
                            if (isRegisteringForm && nameInput.isBlank()) {
                                formAuthError = "Please specify a username."
                                return@WelcomeExperienceScreen
                            }
                            isLoading = true
                            formAuthError = ""
                            if (isRegisteringForm) {
                                FirebaseSyncManager.registerWithEmail(context, emailTrimmed, passwordVal) { success, msg ->
                                    isLoading = false
                                    if (!success) {
                                        formAuthError = msg
                                    }
                                }
                            } else {
                                FirebaseSyncManager.loginWithEmail(context, emailTrimmed, passwordVal) { success, msg ->
                                    isLoading = false
                                    if (!success) {
                                        formAuthError = msg
                                    } else {
                                        nameInput = FirebaseSyncManager.usernameProfile.value
                                    }
                                }
                            }
                        },
                        onContinueGoogle = {
                            isLoading = true
                            googleAuthError = ""
                            val intent = FirebaseSyncManager.getGoogleSignInIntent(context)
                            if (intent != null) {
                                googleSignInLauncher.launch(intent)
                            } else {
                                isLoading = false
                                googleAuthError = "Could not initialize Google Sign-In"
                            }
                        },
                        onQuickSync = {
                            isLoading = true
                            googleAuthError = ""
                            FirebaseSyncManager.signInAnonymously(context)
                        },
                        onBack = {
                            onboardingSubStep = 1
                        },
                        modifier = modifier
                    )
                }
            }
        }
        OnboardingStep.ROLE_SELECT -> {
            RoleSelectionScreen(
                selectedRole = roleSelection,
                onRoleChange = { roleSelection = it },
                onContinue = {
                    FirebaseSyncManager.setPreferredRole(context, roleSelection)
                },
                modifier = modifier
            )
        }
        OnboardingStep.STUDENT_FORM -> {
            StudentOnboardingFormScreen(
                nameInput = nameInput.ifEmpty { FirebaseSyncManager.usernameProfile.value.ifEmpty { "Student" } },
                ageInput = studentAgeInput,
                onAgeChange = { studentAgeInput = it },
                selectedLevel = studentSelectedLevel,
                onLevelChange = { studentSelectedLevel = it },
                upcomingExam = examInput,
                onExamChange = { examInput = it },
                dailyGoal = dailyGoalInput,
                onGoalChange = { dailyGoalInput = it },
                onComplete = {
                    FirebaseSyncManager.completeOnboarding(
                        context = context,
                        role = "student",
                        username = if (nameInput.isNotBlank()) nameInput else "Student",
                        age = studentAgeInput,
                        studyLevel = studentSelectedLevel,
                        upcomingExam = examInput,
                        dailyGoal = dailyGoalInput,
                        parentType = ""
                    )
                },
                modifier = modifier
            )
        }
        OnboardingStep.PARENT_FORM -> {
            ParentOnboardingFormScreen(
                nameInput = nameInput.ifEmpty { FirebaseSyncManager.usernameProfile.value.ifEmpty { "Parent Coach" } },
                selectedType = parentSelectedType,
                onTypeChange = { parentSelectedType = it },
                onComplete = {
                    FirebaseSyncManager.completeOnboarding(
                        context = context,
                        role = "parent",
                        username = if (nameInput.isNotBlank()) nameInput else "Parent Coach",
                        age = "",
                        studyLevel = "",
                        upcomingExam = "",
                        dailyGoal = "",
                        parentType = parentSelectedType
                    )
                },
                modifier = modifier
            )
        }
        else -> {}
    }
}

// ----------------------------------------------------
// UI Stage Modules for refined Permission Flow onboarding
// ----------------------------------------------------

@Composable
fun InitializingSplashScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFFEF7FF)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Elegant brand logo (hourglass launcher style)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF21005D)) // Custom deep launcher background purple
                    .border(2.dp, Color(0xFF6750A4), CircleShape)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "Focus logo",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp)
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Focus",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1D1B20),
                        letterSpacing = 2.sp
                    )
                )
                Text(
                    text = "A secure gateway for productivity & mindfulness",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = Color(0xFF49454F)
                    ),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            CircularProgressIndicator(
                color = Color(0xFF6750A4),
                strokeWidth = 3.dp,
                modifier = Modifier.size(36.dp)
            )

            Text(
                text = "Securing synchronizations & profiles...",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = Color(0xFF49454F).copy(alpha = 0.7f)
                )
            )
        }
    }
}

@Composable
fun WelcomeIntroScreen(
    onContinueGoogle: () -> Unit,
    onContinueEmail: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFFEF7FF))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)
        ) {
            // Elegant brand indicator logo (hourglass launcher style)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF21005D)) // Custom deep launcher background purple
                    .border(2.dp, Color(0xFF6750A4), CircleShape)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "Focus logo",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Focus",
                    style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black),
                    color = Color(0xFF1D1B20),
                    letterSpacing = 2.5.sp
                )

                Text(
                    text = "Build better focus habits and reduce digital distractions during study time.",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                    color = Color(0xFF49454F),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Spec Info List
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                InfoFeatureRow(
                    icon = Icons.Default.Block,
                    title = "App Restriction During Focus Sessions",
                    description = "Temporarily restrict selected apps like Instagram, YouTube, and games during active study sessions with a clear focus screen and reminder message."
                )
                InfoFeatureRow(
                    icon = Icons.Default.Timer,
                    title = "Study Session Insights",
                    description = "View session summaries including focus duration, blocked app attempts, and overall focus score. Data syncs securely for user and parent viewing."
                )
                InfoFeatureRow(
                    icon = Icons.Default.Analytics,
                    title = "Parent & Student Insights",
                    description = "Generate simple shared reports using secure linking codes, showing focus scores, session history, and distraction patterns to support better study habits."
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Action Buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Button 1: Continue with Google
                OutlinedButton(
                    onClick = onContinueGoogle,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCAC4D0)),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color(0xFF1D1B20)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("continue_google_pre_btn"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .background(Color(0xFF4284F3), shape = RoundedCornerShape(4.dp))
                        ) {
                            Text(
                                "G",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Continue with Google",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF1D1B20)
                        )
                    }
                }

                // Button 2: Continue with Email
                Button(
                    onClick = onContinueEmail,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4), contentColor = Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("continue_email_pre_btn"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Email, contentDescription = "Email", modifier = Modifier.size(20.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Continue with Email", fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun InfoFeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(36.dp)
                .background(Color(0xFF6750A4).copy(alpha = 0.12f), CircleShape)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFF6750A4),
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF1D1B20)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF49454F),
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun PermissionSetupScreen(
    isAccessibilityEnabled: Boolean,
    isDndEnabled: Boolean,
    isNotifListenerEnabled: Boolean,
    isUsageAccessEnabled: Boolean,
    onRequestAccessibility: () -> Unit,
    onRequestDnd: () -> Unit,
    onRequestNotifListener: () -> Unit,
    onRequestUsageAccess: () -> Unit,
    onCheckAllPermissions: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalPermissionsCount = 4
    var grantedCount = 0
    if (isAccessibilityEnabled) grantedCount++
    if (isUsageAccessEnabled) grantedCount++
    if (isNotifListenerEnabled) grantedCount++
    if (isDndEnabled) grantedCount++

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFFEF7FF))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color(0xFF1D1B20)
                    )
                }
                Text(
                    text = "STEP 2 OF 3",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = Color(0xFF6750A4),
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(modifier = Modifier.width(32.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Icon & Title
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .background(Color(0xFF6750A4).copy(alpha = 0.12f), shape = RoundedCornerShape(16.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = "Security Screen",
                    tint = Color(0xFF6750A4),
                    modifier = Modifier.size(32.dp)
                )
            }

            Text(
                text = "🔐 Mandatory Permission Setup",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF1D1B20),
                textAlign = TextAlign.Center
            )

            Text(
                text = "Before account creation or login is fully usable, please enable the following core permissions to activate the Focus engine components.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF49454F),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            // Progress Banner
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Setup Progress",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF1D1B20)
                        )
                        Text(
                            text = "$grantedCount / $totalPermissionsCount Enabled",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (grantedCount == totalPermissionsCount) Color(0xFF2E7D32) else Color(0xFFE65100)
                            )
                        )
                    }

                    LinearProgressIndicator(
                        progress = { grantedCount.toFloat() / totalPermissionsCount.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = Color(0xFF6750A4),
                        trackColor = Color(0xFFEADDFF)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Step-by-Step Toggles
            PermissionToggleItem(
                title = "1. Accessibility Service",
                description = "Required to detect when distracting social apps are launched and automatically display motivational reminders.",
                isGranted = isAccessibilityEnabled,
                onClick = onRequestAccessibility
            )

            PermissionToggleItem(
                title = "2. Usage Access",
                description = "Crucial to monitor app usage times, detect screen distraction levels, and present analytical feedback.",
                isGranted = isUsageAccessEnabled,
                onClick = onRequestUsageAccess
            )

            PermissionToggleItem(
                title = "3. Notification Access",
                description = "Optional but highly recommended to filter out noisy chat banners and social notifications during focus hours.",
                isGranted = isNotifListenerEnabled,
                onClick = onRequestNotifListener
            )

            PermissionToggleItem(
                title = "4. Do Not Disturb Option",
                description = "Required to adjust silent modes automatically and keep workspace studies isolated from outside interruptions.",
                isGranted = isDndEnabled,
                onClick = onRequestDnd
            )

            Spacer(modifier = Modifier.weight(1f))

            // Check Status & Continue
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onCheckAllPermissions,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF1D1B20)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCAC4D0)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(50.dp)
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Check")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Check Status", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                }

                Button(
                    onClick = onContinue,
                    enabled = (grantedCount == totalPermissionsCount),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6750A4),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFEADDFF).copy(alpha = 0.5f),
                        disabledContentColor = Color(0xFF1D1B20).copy(alpha = 0.38f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(50.dp).testTag("permission_continue_btn")
                ) {
                    Text("Continue", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(imageVector = Icons.Default.ArrowForward, contentDescription = "Next")
                }
            }
        }
    }
}

@Composable
fun PermissionToggleItem(
    title: String,
    description: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) Color(0xFFE8F5E9) else Color(0xFFF3EDF7)
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (isGranted) Color(0xFF81C784).copy(alpha = 0.5f) else Color(0xFFCAC4D0).copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                        contentDescription = "Status",
                        tint = if (isGranted) Color(0xFF2E7D32) else Color(0xFFFF9800),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF1D1B20)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF49454F),
                    lineHeight = 15.sp
                )
            }

            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isGranted) Color(0xFF2E7D32) else Color(0xFF6750A4),
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text(
                    text = if (isGranted) "Enabled" else "Grant",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
fun WelcomeExperienceScreen(
    emailInput: String,
    onEmailChange: (String) -> Unit,
    passwordInput: String,
    onPasswordChange: (String) -> Unit,
    nameInput: String,
    onNameChange: (String) -> Unit,
    showLoginForm: Boolean,
    onToggleLoginForm: (Boolean) -> Unit,
    isRegistering: Boolean,
    onToggleRegisterMode: (Boolean) -> Unit,
    googleAuthError: String,
    formAuthError: String,
    onClearGoogleError: () -> Unit = {},
    onClearFormError: () -> Unit = {},
    isLoading: Boolean,
    onAuthenticateEmail: () -> Unit,
    onContinueGoogle: () -> Unit,
    onQuickSync: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isPasswordVisible by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFFEF7FF))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("auth_back_btn")) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color(0xFF1D1B20)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Back to Permissions",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = Color(0xFF49454F)
                )
            }

            // Beautiful Brand logo (hourglass launcher style)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF21005D)) // Custom deep launcher background purple
                    .border(1.5.dp, Color(0xFF6750A4), CircleShape)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "Focus logo",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                )
            }

            Text(
                text = "Focus",
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black),
                color = Color(0xFF1D1B20),
                letterSpacing = 2.sp
            )

            Text(
                text = "Build healthy digital habits and distraction-free study sessions.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF49454F),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Primary Google Action
            OutlinedButton(
                onClick = onContinueGoogle,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCAC4D0)),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color(0xFF1D1B20)),
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("google_login_btn"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(Color(0xFF4285F4), shape = RoundedCornerShape(4.dp))
                    ) {
                        Text(
                            "G",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Continue with Google",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF1D1B20)
                    )
                }
            }

            // Beautiful interactive Google / Firebase sync logging component
            if (googleAuthError.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFEBEE)
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFB3261E)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .testTag("auth_error_container")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Sync Warning Icon",
                                    tint = Color(0xFFB3261E),
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Google Sign-In Error Log",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFFB3261E)
                                )
                            }
                            IconButton(
                                onClick = onClearGoogleError,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss error log",
                                    tint = Color(0xFFB3261E).copy(alpha = 0.6f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        androidx.compose.material3.HorizontalDivider(
                            color = Color(0xFFB3261E).copy(alpha = 0.15f),
                            thickness = 1.dp
                        )

                        Text(
                            text = googleAuthError,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                lineHeight = 16.sp
                            ),
                            color = Color(0xFF1D1B20),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            text = "Note: Please verify the Google Play configuration or SHA-1 fingerprints inside the Firebase Console settings to activate this flow completely.",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                lineHeight = 14.sp
                            ),
                            color = Color(0xFF49454F),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Secondary option toggles
            if (!showLoginForm) {
                // Classy Visual Separator
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        color = Color(0xFFCAC4D0)
                    )
                    Text(
                        text = "OR EMAIL OPTIONS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF49454F)
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        color = Color(0xFFCAC4D0)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Prominent Sign In button to continue from where they left
                    Button(
                        onClick = {
                            onToggleLoginForm(true)
                            onToggleRegisterMode(false) // Direct to Login
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF6750A4),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("email_signin_toggle"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Sign In icon",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sign In", fontWeight = FontWeight.Bold)
                    }

                    // Register button
                    OutlinedButton(
                        onClick = {
                            onToggleLoginForm(true)
                            onToggleRegisterMode(true) // Direct to Register
                        },
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6750A4)),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color(0xFF6750A4)),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("email_register_toggle"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Register icon",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Register", fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                // Expanded Custom email login/register form card
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFCAC4D0)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isRegistering) "Create Focus Account" else "Sign In to Focus",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF1D1B20)
                            )
                            IconButton(
                                onClick = { onToggleLoginForm(false) },
                                modifier = Modifier.size(24.dp).testTag("close_email_form")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Form",
                                    tint = Color(0xFF49454F)
                                )
                            }
                        }

                        // Beautiful Tactile Segmented Tab Selection
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFEADDFF).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .background(
                                        if (!isRegistering) Color(0xFF6750A4) else Color.Transparent,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable { onToggleRegisterMode(false) }
                                    .testTag("tab_signin")
                            ) {
                                Text(
                                    text = "Sign In",
                                    color = if (!isRegistering) Color.White else Color(0xFF49454F),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .background(
                                        if (isRegistering) Color(0xFF6750A4) else Color.Transparent,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable { onToggleRegisterMode(true) }
                                    .testTag("tab_register")
                            ) {
                                Text(
                                    text = "Register",
                                    color = if (isRegistering) Color.White else Color(0xFF49454F),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }

                        if (isRegistering) {
                            OutlinedTextField(
                                value = nameInput,
                                onValueChange = onNameChange,
                                label = { Text("User name", color = Color(0xFF49454F)) },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFF1D1B20)),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF6750A4),
                                    unfocusedBorderColor = Color(0xFFCAC4D0),
                                    focusedLabelColor = Color(0xFF6750A4),
                                    unfocusedLabelColor = Color(0xFF49454F),
                                    focusedTextColor = Color(0xFF1D1B20),
                                    unfocusedTextColor = Color(0xFF1D1B20)
                                ),
                                modifier = Modifier.fillMaxWidth().testTag("onboarding_name_field"),
                                shape = RoundedCornerShape(10.dp)
                            )
                        }

                        OutlinedTextField(
                            value = emailInput,
                            onValueChange = onEmailChange,
                            label = { Text("Email", color = Color(0xFF49454F)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None
                            ),
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFF1D1B20)),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF6750A4),
                                unfocusedBorderColor = Color(0xFFCAC4D0),
                                focusedLabelColor = Color(0xFF6750A4),
                                unfocusedLabelColor = Color(0xFF49454F),
                                focusedTextColor = Color(0xFF1D1B20),
                                unfocusedTextColor = Color(0xFF1D1B20)
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("onboarding_email_field"),
                            shape = RoundedCornerShape(10.dp)
                        )

                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = onPasswordChange,
                            label = { Text("Password (min 6 keys)", color = Color(0xFF49454F)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None
                            ),
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFF1D1B20)),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF6750A4),
                                unfocusedBorderColor = Color(0xFFCAC4D0),
                                focusedLabelColor = Color(0xFF6750A4),
                                unfocusedLabelColor = Color(0xFF49454F),
                                focusedTextColor = Color(0xFF1D1B20),
                                unfocusedTextColor = Color(0xFF1D1B20)
                            ),
                            visualTransformation = if (isPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            trailingIcon = {
                                val image = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff
                                val description = if (isPasswordVisible) "Hide password" else "Show password"
                                IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                    Icon(imageVector = image, contentDescription = description, tint = Color(0xFF49454F).copy(alpha = 0.6f))
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("onboarding_password_field"),
                            shape = RoundedCornerShape(10.dp)
                        )

                        if (formAuthError.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = formAuthError, 
                                    color = Color(0xFFB3261E), 
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = onClearFormError,
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear form error",
                                        tint = Color(0xFFB3261E).copy(alpha = 0.6f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = onAuthenticateEmail,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            } else {
                                Text(if (isRegistering) "Register & Start" else "Sign In Now")
                            }
                        }
                    }
                }
            }

            TextButton(
                onClick = onQuickSync,
                modifier = Modifier.testTag("onboarding_skip")
            ) {
                Text("Continue with Guest/Quick Sync ID", color = Color(0xFF49454F).copy(alpha = 0.6f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun RoleSelectionScreen(
    selectedRole: String,
    onRoleChange: (String) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFFEF7FF))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Welcome to Focus!",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                    color = Color(0xFF1D1B20)
                )

                Text(
                    text = "Choose your role on this device to customize your layouts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Student Companion Card selection
                Card(
                    onClick = { onRoleChange("student") },
                    border = androidx.compose.foundation.BorderStroke(
                        width = if (selectedRole == "student") 2.dp else 1.dp,
                        color = if (selectedRole == "student") Color(0xFF6750A4) else Color(0xFFEADDFF)
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedRole == "student") Color(0xFFF3EDF7) else Color.White
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("role_card_student")
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    if (selectedRole == "student") Color(0xFF6750A4) else Color(0xFFF3EDF7),
                                    shape = RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.School,
                                contentDescription = null,
                                tint = if (selectedRole == "student") Color.White else Color(0xFF6750A4),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Student Companion",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF1D1B20)
                            )
                            Text(
                                "Session logging, custom block lists, streaks, and stars.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }

                // Parent Monitor Card selection
                Card(
                    onClick = { onRoleChange("parent") },
                    border = androidx.compose.foundation.BorderStroke(
                        width = if (selectedRole == "parent") 2.dp else 1.dp,
                        color = if (selectedRole == "parent") Color(0xFF6750A4) else Color(0xFFEADDFF)
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedRole == "parent") Color(0xFFF3EDF7) else Color.White
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("role_card_parent")
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    if (selectedRole == "parent") Color(0xFF6750A4) else Color(0xFFF3EDF7),
                                    shape = RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = null,
                                tint = if (selectedRole == "parent") Color.White else Color(0xFF6750A4),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Parent Monitor",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF1D1B20)
                            )
                            Text(
                                "Review study focus time, earn summaries, and prevent bypasses.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }

            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("role_continue_btn"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Continue", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun StudentOnboardingFormScreen(
    nameInput: String,
    ageInput: String,
    onAgeChange: (String) -> Unit,
    selectedLevel: String,
    onLevelChange: (String) -> Unit,
    upcomingExam: String,
    onExamChange: (String) -> Unit,
    dailyGoal: String,
    onGoalChange: (String) -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFFEF7FF))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = "Welcome, $nameInput!",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                color = Color(0xFF1D1B20)
            )

            Text(
                text = "Help us align your experience to your study goals.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )

            HorizontalDivider(color = Color(0xFFEADDFF))

            // Username display
            OutlinedTextField(
                value = nameInput,
                onValueChange = {},
                enabled = false,
                label = { Text("Profile Name") },
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )

            // Age field
            OutlinedTextField(
                value = ageInput,
                onValueChange = onAgeChange,
                label = { Text("Your Age") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("student_age_input"),
                shape = RoundedCornerShape(10.dp)
            )

            // Selection of Studies
            Text(
                text = "What focus studies are you pursuing?",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.DarkGray
            )

            val options = listOf("School", "Intermediate", "Degree", "Engineering", "Medical", "Other")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Render as nice select flow layout rows
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.take(3).forEach { option ->
                        val isSelected = selectedLevel == option
                        FilterChip(
                            selected = isSelected,
                            onClick = { onLevelChange(option) },
                            label = { Text(option) },
                            modifier = Modifier.weight(1f).testTag("chip_$option")
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.drop(3).forEach { option ->
                        val isSelected = selectedLevel == option
                        FilterChip(
                            selected = isSelected,
                            onClick = { onLevelChange(option) },
                            label = { Text(option) },
                            modifier = Modifier.weight(1f).testTag("chip_$option")
                        )
                    }
                }
            }

            // Upcoming Exam Title
            OutlinedTextField(
                value = upcomingExam,
                onValueChange = onExamChange,
                label = { Text("Upcoming Academic Exam") },
                placeholder = { Text("e.g. Mathematics, GCSE, UPSC, Medical Boards") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("student_exam_input"),
                shape = RoundedCornerShape(10.dp)
            )

            // Daily study goals selector list
            Text(
                text = "Daily Study Goal Target:",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = Color.Gray
            )

            val targetGoals = listOf("30 mins", "60 mins", "120 mins", "180 mins", "240 mins")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                targetGoals.forEach { goalOption ->
                    val isSelected = dailyGoal == goalOption
                    OutlinedButton(
                        onClick = { onGoalChange(goalOption) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isSelected) Color(0xFF6750A4) else Color.White,
                            contentColor = if (isSelected) Color.White else Color(0xFF6750A4)
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6750A4)),
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(goalOption, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onComplete,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("student_submit_btn"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Complete Onboarding & Start Focus", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ParentOnboardingFormScreen(
    nameInput: String,
    selectedType: String,
    onTypeChange: (String) -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFFEF7FF))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = "Coach setup, $nameInput!",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                color = Color(0xFF1D1B20)
            )

            Text(
                text = "Configure your family supervision parameters.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )

            HorizontalDivider(color = Color(0xFFEADDFF))

            // Parent Type choices
            Text(
                text = "Configure Parenting Role Type:",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.DarkGray
            )

            val types = listOf("Mother", "Father", "Guardian", "Educator", "Other")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                types.forEach { typeOption ->
                    val isSelected = selectedType == typeOption
                    Card(
                        onClick = { onTypeChange(typeOption) },
                        border = androidx.compose.foundation.BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) Color(0xFF6750A4) else Color(0xFFEADDFF)
                        ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFFF3EDF7) else Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("parent_type_$typeOption")
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onTypeChange(typeOption) }
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(typeOption, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onComplete,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("parent_submit_btn"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Activate Remote Parent Portal", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun FocusSessionReportDialog(
    session: com.example.data.FocusSession,
    isHistoryView: Boolean = false,
    onDismiss: () -> Unit
) {
    android.util.Log.d("FocusSessionReport", "Displaying persisted session report without recomputation")

    val computedPlannedMinutes = session.plannedDurationMinutes
    val computedActualMinutes = session.actualDurationMinutes
    val computedStatus = session.status
    val computedScore = session.focusScore
    val computedLevel = session.focusLevel
    val computedStars = session.starsEarned
    val computedTrend = session.trendIndicator
    val computedParentMessage = session.parentSummaryMessage
    val computedBlockedAttempts = session.totalDistractionAttempts
    val computedRepeatedApp = session.mostFrequentlyDistractingApp ?: "None"
    val dndActive = if (session.isDndActiveDuringSession) "On" else "Off"

    val appAttempts = run {
        val map = mutableMapOf<String, Int>()
        if (!session.appActivityJson.isNullOrEmpty()) {
            try {
                val json = org.json.JSONObject(session.appActivityJson)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = json.getInt(key)
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        map
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Analytics,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "Post Study Report",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                            modifier = Modifier.testTag("report_title")
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_report_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Report",
                            tint = Color.Gray
                        )
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                // Scrollable container for cards
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    
                    // CARD 1: Session Overview
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("overview_card")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Session Overview",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                
                                val badgeColor = when (computedStatus) {
                                    "Completed" -> Color(0xFF10B981) // Green
                                    "Interrupted" -> Color(0xFFF59E0B) // Amber
                                    else -> Color(0xFFEF4444) // Red
                                }
                                Surface(
                                    color = badgeColor.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor)
                                ) {
                                    Text(
                                        text = computedStatus,
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = badgeColor,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag("session_status")
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "Topic: ${session.topic}",
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("session_topic")
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Planned Duration", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                    Text("$computedPlannedMinutes Mins", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Actual Focused Time", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                    Text("$computedActualMinutes Mins", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }

                    // CARD 2: Focus Performance Metrics
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("performance_card")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Focus Performance",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(80.dp)
                                ) {
                                    val arcColor = when (computedLevel) {
                                        "High" -> Color(0xFF10B981)
                                        "Medium" -> Color(0xFFF59E0B)
                                        else -> Color(0xFFEF4444)
                                    }
                                    
                                    CircularProgressIndicator(
                                        progress = computedScore.toFloat() / 100f,
                                        modifier = Modifier.fillMaxSize(),
                                        color = arcColor,
                                        strokeWidth = 8.dp,
                                        trackColor = arcColor.copy(alpha = 0.15f)
                                    )
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "$computedScore",
                                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.testTag("focus_score")
                                        )
                                        Text("Score", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                    }
                                }

                                Column(
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text("Focus Level:", style = MaterialTheme.typography.bodyMedium)
                                        val levelColor = when (computedLevel) {
                                            "High" -> Color(0xFF10B981)
                                            "Medium" -> Color(0xFFF59E0B)
                                            else -> Color(0xFFEF4444)
                                        }
                                        Text(
                                            text = computedLevel,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
                                            color = levelColor,
                                            modifier = Modifier.testTag("focus_level")
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = null,
                                            tint = Color(0xFFF59E0B),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = "+$computedStars Stars Earned",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = Color(0xFFD35400),
                                            modifier = Modifier.testTag("stars_earned")
                                        )
                                    }

                                    val consistencyText = when {
                                        computedScore >= 80 -> "Excellent Focus Flow"
                                        computedScore >= 50 -> "Good Average Effort"
                                        else -> "Low Stability - Restructured focus suggested"
                                    }
                                    Text(
                                        text = "🎯 $consistencyText",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // CARD 3: App Activity Summary
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("app_activity_card")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "App Block Summary",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            if (appAttempts.isEmpty()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color(0xFF10B981)
                                    )
                                    Text(
                                        text = "Zero App Interceptions! Well done.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFF10B981)
                                    )
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    appAttempts.forEach { (appName, attempts) ->
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Block,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Text(
                                                        text = appName,
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                        maxLines = 1,
                                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "$attempts Attempts",
                                                    color = Color(0xFFEF4444),
                                                    fontWeight = FontWeight.Black,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    modifier = Modifier.testTag("attempts_$appName")
                                                )
                                            }
                                            
                                            val faction = (attempts.toFloat() / 10f).coerceIn(0.1f, 1.0f)
                                            LinearProgressIndicator(
                                                progress = faction,
                                                color = Color(0xFFEF4444),
                                                trackColor = Color.LightGray.copy(alpha = 0.3f),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(4.dp)
                                                    .clip(RoundedCornerShape(2.dp))
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // CARD 4: Distraction Insights
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("distraction_insights_card")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Distraction Detailed Insights",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Blocked App Access Attempts:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text("$computedBlockedAttempts times", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = if (computedBlockedAttempts > 0) Color(0xFFEF4444) else Color(0xFF10B981))
                            }

                            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Most Repeated Distractor App:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(computedRepeatedApp, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = if (computedRepeatedApp != "None") Color(0xFFEF4444) else Color(0xFF10B981), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }

                            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Restlessness Timer Checks:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text("${session.timeChecksCount} checks", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = if (session.timeChecksCount > 3) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurface)
                            }

                            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Historical Focus Trend:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                val trendIcon = when (computedTrend) {
                                    "Improved" -> "📈 Improved"
                                    "Needs Improvement" -> "📉 Needs Improvement"
                                    else -> "➡ Stable"
                                }
                                val trendColor = when (computedTrend) {
                                    "Improved" -> Color(0xFF10B981)
                                    "Needs Improvement" -> Color(0xFFEF4444)
                                    else -> Color.Gray
                                }
                                Text(trendIcon, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = trendColor, modifier = Modifier.testTag("trend_indicator"))
                            }
                        }
                    }

                    // CARD 5: Notifications & Focus Mode Summary
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("focus_mode_card")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Mutes & Focus Mode Details",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning, // repurposed as minor alert mute indicator
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text("Social Notifications Blocked:", style = MaterialTheme.typography.bodyMedium)
                                }
                                Text("${session.notificationsMutedCount} alerts", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            }

                            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DoNotDisturbOn,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text("Do Not Disturb Status:", style = MaterialTheme.typography.bodyMedium)
                                }
                                Text(dndActive, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = if (dndActive == "On") Color(0xFF10B981) else Color.Gray)
                            }
                        }
                    }

                    // CARD 6: Parent Summary Section
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("parent_insight_card")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VerifiedUser,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Parent Oversight Insight",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = computedParentMessage,
                                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.testTag("parent_summary_message")
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "🛡 Study Privacy Guarantee: No text logs or individual message contexts were collected, displaying only aggregate app usage.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("finish_summary_button")
                ) {
                    Text(
                        text = if (isHistoryView) "Close Report" else "Mark Closed & Update Dashboard",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
    }
}

@Composable
fun ParentDashboardContent(
    innerPadding: PaddingValues,
    linkedStudentId: String,
    linkedStudentName: String,
    linkedStudentStats: Map<String, Any>,
    linkedStudentSessions: List<com.example.data.FocusSession>,
    linkedStudentSneaks: List<Map<String, Any>>,
    onOpenHistoryDrawer: () -> Unit,
    onOpenProfile: () -> Unit
) {
    val context = LocalContext.current
    var studentIdLinkInput by remember { mutableStateOf("") }
    var inviteCodeInput by remember { mutableStateOf("") }
    var isLinkingInvite by remember { mutableStateOf(false) }
    val generatedInviteCode by FirebaseSyncManager.generatedInviteCode.collectAsStateWithLifecycle()
    val syncState by FirebaseSyncManager.syncState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .testTag("parent_dashboard_root"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Parent App Header
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onOpenHistoryDrawer,
                        modifier = Modifier.testTag("open_history_drawer_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menu",
                            tint = Color(0xFF49454F),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            contentDescription = "Focus Logo",
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF8E24AA)) // Purple theme for parents
                                .padding(4.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Focus Companion",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                fontSize = 22.sp
                            ),
                            color = Color(0xFF1D1B20)
                        )
                    }
                    IconButton(
                        onClick = onOpenProfile,
                        modifier = Modifier.testTag("open_profile_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = "Account",
                            tint = Color(0xFF49454F),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Text(
                    text = "PARENT & COACH COMPANION PORTAL",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = Color(0xFF8E24AA),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Live Connection Status Banner
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when (syncState) {
                        is FirebaseSyncManager.SyncState.Success -> Color(0xFFE8F5E9)
                        is FirebaseSyncManager.SyncState.Error -> Color(0xFFFFEBEE)
                        else -> Color(0xFFF3E5F5)
                    }
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when (syncState) {
                            is FirebaseSyncManager.SyncState.Success -> Icons.Default.CheckCircle
                            is FirebaseSyncManager.SyncState.Error -> Icons.Default.Error
                            is FirebaseSyncManager.SyncState.Syncing -> Icons.Default.Sync
                            else -> Icons.Default.CloudQueue
                        },
                        contentDescription = null,
                        tint = when (syncState) {
                            is FirebaseSyncManager.SyncState.Success -> Color(0xFF4CAF50)
                            is FirebaseSyncManager.SyncState.Error -> Color(0xFFF44336)
                            else -> Color(0xFF8E24AA)
                        },
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (linkedStudentId.isNotEmpty()) "Student Connected: $linkedStudentName" else "Portal Awaiting Connection",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF1D1B20)
                        )
                        Text(
                            text = when (syncState) {
                                is FirebaseSyncManager.SyncState.Unconfigured -> "Configure cloud tracking"
                                is FirebaseSyncManager.SyncState.ConfiguredButNotInitialized -> "Offline monitor mode"
                                is FirebaseSyncManager.SyncState.Initializing -> "Linking cloud feeds..."
                                is FirebaseSyncManager.SyncState.Ready -> "Zero-Friction Sandbox Mode Active"
                                is FirebaseSyncManager.SyncState.Syncing -> "Downloading student activity logs..."
                                is FirebaseSyncManager.SyncState.Success -> "Live sync refreshed successfully!"
                                is FirebaseSyncManager.SyncState.Error -> "Offline fallback mode active"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                    if (linkedStudentId.isNotEmpty()) {
                        IconButton(
                            onClick = { FirebaseSyncManager.fetchLinkedStudentDataDirectly(context) },
                            modifier = Modifier.testTag("refresh_dashboard_btn")
                        ) {
                            Icon(Icons.Default.Refresh, "Refresh", tint = Color(0xFF8E24AA))
                        }
                    }
                }
            }
        }

        // Live Active Remote Session Card
        if (linkedStudentId.isNotEmpty()) {
            val remoteSessionMap = linkedStudentStats["remoteAssignedSession"] as? Map<String, Any>
            val sessionStatus = remoteSessionMap?.get("status") as? String ?: ""
            val isSessionActive = remoteSessionMap != null && sessionStatus == "active"

            if (isSessionActive) {
                item {
                    var showStopConfirmDialog by remember { mutableStateOf(false) }
                    var isStoppingSession by remember { mutableStateOf(false) }
                    var stopResultMsg by remember { mutableStateOf<String?>(null) }
                    
                    val sessionTopic = remoteSessionMap?.get("topic") as? String ?: "Assigned Quick Study"
                    val sessionDuration = (remoteSessionMap?.get("durationMinutes") as? Number)?.toInt() ?: 25
                    val timestamp = (remoteSessionMap?.get("timestamp") as? Number)?.toLong() ?: 0L
                    
                    // Live ticker for elapsed time
                    var secondsTicker by remember { mutableStateOf(0) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            kotlinx.coroutines.delay(1000)
                            secondsTicker++
                        }
                    }
                    
                    val elapsedMs = if (timestamp > 0) System.currentTimeMillis() - timestamp else 0L
                    val elapsedSecondsTotal = elapsedMs / 1000
                    val elapsedMinutes = elapsedSecondsTotal / 60
                    val elapsedSeconds = elapsedSecondsTotal % 60
                    val elapsedText = String.format("%02d:%02d", elapsedMinutes, elapsedSeconds)

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFF3E5F5) // light lavender purple background
                        ),
                        border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF8E24AA)),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("parent_active_session_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(Color(0xFFD32F2F), CircleShape) // Red breathing pulse dot
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "ACTIVE FOCUS LOCK",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF8E24AA)
                                        )
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFF8E24AA), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "RUNNING",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }

                            Column {
                                Text(
                                    text = "Student: $linkedStudentName",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color(0xFF1D1B20)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Topic: \"$sessionTopic\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.DarkGray
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Planned Duration: $sessionDuration minutes",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.DarkGray
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "ELAPSED TIME",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = Color.Gray)
                                    )
                                    Text(
                                        text = elapsedText,
                                        style = MaterialTheme.typography.headlineMedium.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color(0xFF8E24AA),
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        ),
                                        modifier = Modifier.testTag("parent_elapsed_time_ticker")
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "STATUS",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = Color.Gray)
                                    )
                                    Text(
                                        text = "Blocked & Locked",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFF2E7D32)
                                    )
                                }
                            }

                            Button(
                                onClick = { showStopConfirmDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFD32F2F), // Strong warning red
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("parent_stop_session_btn")
                            ) {
                                if (isStoppingSession) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                                } else {
                                    Icon(Icons.Default.Stop, null, tint = Color.White)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Stop Focus Session Remotely", fontWeight = FontWeight.Bold)
                                }
                            }

                            if (stopResultMsg != null) {
                                Text(
                                    text = stopResultMsg ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFD32F2F),
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }

                    if (showStopConfirmDialog) {
                        AlertDialog(
                            onDismissRequest = { showStopConfirmDialog = false },
                            title = { Text("Stop Remote Focus Session?", fontWeight = FontWeight.Bold) },
                            text = { 
                                Text("Are you sure you want to stop the remote session for $linkedStudentName? This will end all blockers and timers on their device immediately.") 
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showStopConfirmDialog = false
                                        isStoppingSession = true
                                        FirebaseSyncManager.stopRemoteSession(context, linkedStudentId) { success, msg ->
                                            isStoppingSession = false
                                            stopResultMsg = msg
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                    modifier = Modifier.testTag("confirm_stop_session_btn")
                                ) {
                                    Text("Yes, Stop Session", fontWeight = FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                OutlinedButton(
                                    onClick = { showStopConfirmDialog = false },
                                    modifier = Modifier.testTag("cancel_stop_session_btn")
                                ) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }
                }
            }
        }

        // Parent Monitor Cockpit Card
        if (linkedStudentId.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEADDFF)),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Title
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("STUDENT PROFILE", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = Color.Gray))
                                Text(
                                    text = linkedStudentName,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF8E24AA)
                                )
                                val ageVal = linkedStudentStats["age"] as? String ?: ""
                                val studyVal = linkedStudentStats["studyLevel"] as? String ?: ""
                                val examVal = linkedStudentStats["upcomingExam"] as? String ?: ""
                                if (ageVal.isNotEmpty() || studyVal.isNotEmpty()) {
                                    Text(
                                        text = "Age: $ageVal • Class Level: $studyVal" + (if (examVal.isNotEmpty()) " • Exam: $examVal" else ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.DarkGray
                                    )
                                }
                            }
                            
                            // Visual Badge
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFFF3E5F5), shape = RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("ACTIVE MONITOR", color = Color(0xFF8E24AA), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                            }
                        }

                        // Child metrics row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val streakVal = (linkedStudentStats["currentStreak"] as? Long) ?: 0L
                            val starsVal = (linkedStudentStats["totalStars"] as? Long) ?: 0L
                            val minVal = (linkedStudentStats["weeklyTargetMinutes"] as? Long) ?: 120L

                            // Streak
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Default.Whatshot, null, tint = Color(0xFFE65100), modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("🔥 STREAK", fontSize = 10.sp, color = Color.DarkGray, fontWeight = FontWeight.Bold)
                                    Text("$streakVal Days", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = Color(0xFFE65100))
                                }
                            }

                            // Stars
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFDE7)),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Default.Star, null, tint = Color(0xFFF57F17), modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("⭐️ STARS", fontSize = 10.sp, color = Color.DarkGray, fontWeight = FontWeight.Bold)
                                    Text("$starsVal Saved", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = Color(0xFFF57F17))
                                }
                            }

                            // Target Tracker
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Default.TrackChanges, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("🎯 TARGET", fontSize = 10.sp, color = Color.DarkGray, fontWeight = FontWeight.Bold)
                                    Text("$minVal m/wk", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = Color(0xFF2E7D32))
                                }
                            }
                        }

                        // Short Summary of Last Study session
                        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
                        Text(
                            text = "LATEST FOCUS ACTIVITY RECORD",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = Color.DarkGray)
                        )

                        if (linkedStudentSessions.isEmpty()) {
                            Text("No study sessions logged yet. Tap the stats refresh button above to poll live data.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        } else {
                            val latest = linkedStudentSessions.first()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFFBFBFC), shape = RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(latest.topic, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.Black)
                                    val dateStr = java.text.SimpleDateFormat("MMMM dd, yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(latest.dateTimestamp))
                                    Text(dateStr, fontSize = 10.sp, color = Color.Gray)
                                }
                                AssistChip(
                                    onClick = {},
                                    label = { Text("${latest.durationMinutes} minutes focus done", fontWeight = FontWeight.Bold, fontSize = 10.sp) },
                                    colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFFE1BEE7), labelColor = Color(0xFF4A148C)),
                                    border = null
                                )
                            }
                        }

                        // Navigation Hint
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF3E5F5), shape = RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.TrendingUp, null, tint = Color(0xFF8E24AA), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Tap the 'Child Stats' tab below to view detailed historical logs, app blocking metrics, and live intercept reports!",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = Color(0xFF4A148C)
                                )
                            }
                        }
                    }
                }
            }

            // Parent Remote Session Quick Launch (3 Assignments limit per month)
            item {
                var assignTopic by remember { mutableStateOf("") }
                var assignDurationMins by remember { mutableStateOf(25) }
                var customDurationInput by remember { mutableStateOf("25") }
                var assignLockMode by remember { mutableStateOf("STRICT") }
                var assignPasscode by remember { mutableStateOf("12345") }
                var isAssigning by remember { mutableStateOf(false) }
                var assignStatusMsg by remember { mutableStateOf<String?>(null) }
                var isSuccessMsg by remember { mutableStateOf(true) }

                val remotePrefs = context.getSharedPreferences("parent_remote_counts", Context.MODE_PRIVATE)
                val currentMonthKey = remember { java.text.SimpleDateFormat("yyyy_MM", java.util.Locale.getDefault()).format(java.util.Date()) }
                var monthlyAssignmentsCount by remember { mutableStateOf(remotePrefs.getInt("${currentMonthKey}_count", 0)) }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF8E24AA)),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().testTag("parent_remote_assign_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = Color(0xFF8E24AA),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Remote Quick Launch",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF8E24AA)
                                )
                            }
                            // Remaining limit badge
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF8E24AA).copy(alpha = 0.1f), shape = RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "Left: ${3 - monthlyAssignmentsCount} of 3",
                                    color = Color(0xFF8E24AA),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }

                        Text(
                            text = "Locally or remotely command student's device and lock them into a focused study session timer. Limit of 3 remote assignments per month to maintain academic trust and prevent micromanagement.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.DarkGray
                        )

                        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))

                        // Topic
                        OutlinedTextField(
                            value = assignTopic,
                            onValueChange = { assignTopic = it },
                            label = { Text("Study Focus Topic") },
                            placeholder = { Text("e.g. Science Exam Prep, Essay Review") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("parent_assign_topic_input"),
                            shape = RoundedCornerShape(12.dp)
                        )

                        // Duration choices Row
                        Text("Session Duration:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = Color.Gray))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(15, 25, 45, 60).forEach { mins ->
                                val isSelected = assignDurationMins == mins
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) Color(0xFF8E24AA) else Color(0xFFF3EDF7)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { 
                                            assignDurationMins = mins 
                                            customDurationInput = mins.toString()
                                        },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "${mins}m",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 10.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else Color(0xFF8E24AA)
                                    )
                                }
                            }
                        }

                        // Manual custom duration entry
                        OutlinedTextField(
                            value = customDurationInput,
                            onValueChange = { newValue ->
                                val filtered = newValue.filter { it.isDigit() }.take(4)
                                customDurationInput = filtered
                                val parsed = filtered.toIntOrNull()
                                if (parsed != null && parsed > 0) {
                                    assignDurationMins = parsed
                                }
                            },
                            label = { Text("Or Enter Custom Duration") },
                            placeholder = { Text("e.g. 30, 90, 120") },
                            suffix = { Text("minutes", style = MaterialTheme.typography.bodyMedium.copy(color = Color.Gray)) },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("parent_assign_custom_duration_input"),
                            shape = RoundedCornerShape(12.dp)
                        )

                        // Strict or Passcode mode
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Lockout Mode:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = Color.Gray), modifier = Modifier.weight(1f))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = assignLockMode == "STRICT", onClick = { assignLockMode = "STRICT" })
                                Text("Strict", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { assignLockMode = "STRICT" })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = assignLockMode == "PASSCODE", onClick = { assignLockMode = "PASSCODE" })
                                Text("Passcode", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { assignLockMode = "PASSCODE" })
                            }
                        }

                        if (assignLockMode == "PASSCODE") {
                            OutlinedTextField(
                                value = assignPasscode,
                                onValueChange = { assignPasscode = it.filter { c -> c.isDigit() } },
                                label = { Text("5-Digit Override Passcode") },
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth().testTag("parent_assign_passcode_input"),
                                shape = RoundedCornerShape(12.dp)
                            )
                        }

                        if (assignStatusMsg != null) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSuccessMsg) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = assignStatusMsg ?: "",
                                    modifier = Modifier.padding(10.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSuccessMsg) Color(0xFF2E7D32) else Color(0xFFC62828)
                                )
                            }
                        }

                        Button(
                            onClick = {
                                isAssigning = true
                                assignStatusMsg = null
                                val finalTopic = assignTopic.ifBlank { "Assigned Quick Study" }
                                
                                FirebaseSyncManager.assignRemoteSession(
                                    context = context,
                                    studentId = linkedStudentId,
                                    topic = finalTopic,
                                    durationMinutes = assignDurationMins,
                                    lockMode = assignLockMode,
                                    passcode = if (assignLockMode == "PASSCODE") assignPasscode else "",
                                    onFinished = { success, msg ->
                                        isAssigning = false
                                        isSuccessMsg = success
                                        assignStatusMsg = msg
                                        if (success) {
                                            assignTopic = ""
                                            // Sync remaining count state
                                            monthlyAssignmentsCount = remotePrefs.getInt("${currentMonthKey}_count", 0)
                                        }
                                    }
                                )
                            },
                            enabled = !isAssigning && (3 - monthlyAssignmentsCount > 0),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF8E24AA),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("parent_assign_cta_btn")
                        ) {
                            if (isAssigning) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                            } else {
                                Icon(Icons.Default.Send, null, tint = Color.White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Trigger Remote Focus Session", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else {
            // Pairing Setup Panel directly on Dashboard
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEADDFF)),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "PAIR NEW STUDENT DEVICE",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF8E24AA)
                        )
                        HorizontalDivider(color = Color(0xFFEADDFF).copy(alpha = 0.5f))

                        if (generatedInviteCode.isNotEmpty()) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF3E5F5)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE040FB).copy(alpha = 0.3f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("YOUR 6-DIGIT ACTIVE PAIRING CODE", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = generatedInviteCode,
                                            fontSize = 28.sp,
                                            fontWeight = FontWeight.Black,
                                            letterSpacing = 2.sp,
                                            color = Color(0xFF8E24AA),
                                            style = MaterialTheme.typography.titleLarge
                                        )
                                        IconButton(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                val clip = android.content.ClipData.newPlainText("Parent Invite Code", generatedInviteCode)
                                                clipboard.setPrimaryClip(clip)
                                                android.widget.Toast.makeText(context, "Pairing code copied!", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Icon(Icons.Default.ContentCopy, "Copy", modifier = Modifier.size(16.dp), tint = Color(0xFF8E24AA))
                                        }
                                    }
                                    Text(
                                        text = "Share this 6-digit code with your child. They can type this code under Focus settings to connect instantly.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.DarkGray,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            Button(
                                onClick = { FirebaseSyncManager.generateParentInviteCode(context) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E24AA))
                            ) {
                                Icon(Icons.Default.VpnKey, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Generate Invite Code", fontWeight = FontWeight.Bold)
                            }
                        }

                        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))

                        Text(
                            text = "MANUAL LINK VIA STUDENT ID",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.Gray
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = studentIdLinkInput,
                                onValueChange = { studentIdLinkInput = it },
                                label = { Text("Manual Sync ID", fontSize = 11.sp) },
                                placeholder = { Text("Child user ID...") },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("link_student_code_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF8E24AA),
                                    focusedLabelColor = Color(0xFF8E24AA)
                                )
                            )

                            Button(
                                onClick = {
                                    FirebaseSyncManager.linkStudent(context, studentIdLinkInput) { ok, msg ->
                                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                                        if (ok) studentIdLinkInput = ""
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E24AA))
                            ) {
                                Text("Link")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ParentStatsTabContent(
    innerPadding: PaddingValues,
    linkedStudentId: String,
    linkedStudentName: String,
    linkedStudentStats: Map<String, Any>,
    linkedStudentSessions: List<com.example.data.FocusSession>,
    linkedStudentSneaks: List<Map<String, Any>>,
    onSessionClick: (com.example.data.FocusSession) -> Unit
) {
    val context = LocalContext.current
    var statsTabSelected by remember { mutableStateOf("SESSIONS") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .testTag("parent_stats_root"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Identity Header
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "Focus Logo",
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF8E24AA))
                            .padding(4.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Child Focus Analytics",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            fontSize = 22.sp
                        ),
                        color = Color(0xFF1D1B20)
                    )
                }
                if (linkedStudentId.isNotEmpty()) {
                    Text(
                        text = "STUDENT: ${linkedStudentName.uppercase()}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        if (linkedStudentId.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF9E6)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF39C12).copy(alpha = 0.25f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 30.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.LinkOff, null, tint = Color(0xFFF39C12), modifier = Modifier.size(48.dp))
                        Text(
                            text = "No Student Device Connected",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF1D1B20)
                        )
                        Text(
                            text = "To view study sessions and app block analytics, please first head to the Dashboard tab, generate a pairing code, and link your student's device.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = Color.DarkGray
                        )
                    }
                }
            }
        } else {
            // Live Stats Cards
            item {
                val streakVal = (linkedStudentStats["currentStreak"] as? Long) ?: 0L
                val starsVal = (linkedStudentStats["totalStars"] as? Long) ?: 0L
                val minVal = (linkedStudentStats["weeklyTargetMinutes"] as? Long) ?: 120L
                
                StatsOverviewWidget(
                    totalMinutes = linkedStudentSessions.sumOf { it.durationMinutes },
                    completedBlocksCount = linkedStudentSessions.size
                )
            }

            // Segment Tabs: Runs History vs Distractions
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (statsTabSelected == "SESSIONS") "Focused Study History (${linkedStudentSessions.size})" else "App Intercept Reports (${linkedStudentSneaks.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            // Tab selection pills: Session vs Distractions
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = statsTabSelected == "SESSIONS",
                        onClick = { statsTabSelected = "SESSIONS" },
                        leadingIcon = { Icon(Icons.Default.List, null, modifier = Modifier.size(16.dp)) },
                        label = { Text("Study Sessions") }
                    )
                    FilterChip(
                        selected = statsTabSelected == "DISTRACTIONS",
                        onClick = { statsTabSelected = "DISTRACTIONS" },
                        leadingIcon = { Icon(Icons.Default.Block, null, modifier = Modifier.size(16.dp)) },
                        label = { Text("Interceptions") }
                    )
                }
            }

            if (statsTabSelected == "SESSIONS") {
                if (linkedStudentSessions.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.School, contentDescription = null, modifier = Modifier.size(40.dp), tint = Color.LightGray)
                                Text("No logged study sessions found", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                            }
                        }
                    }
                } else {
                    items(linkedStudentSessions) { session ->
                        HistoryItemList(session = session, onClick = { onSessionClick(session) })
                    }
                }
            } else {
                if (linkedStudentSneaks.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.Verified, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(36.dp))
                                Text("Pristine Focus Record!", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF1B5E20))
                                Text("No social media or distracting app launches were intercepted during deep study sessions. Give your student a star!", style = MaterialTheme.typography.bodySmall, color = Color.DarkGray, textAlign = TextAlign.Center)
                            }
                        }
                    }
                } else {
                    items(linkedStudentSneaks) { sneak ->
                        val pName = (sneak["packageName"] as? String) ?: ""
                        val appTitle = (sneak["readableAppName"] as? String) ?: pName
                        val counts = (sneak["clickCount"] as? Long) ?: 1L
                        val lastTime = (sneak["timestamp"] as? Long) ?: 0L

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFFFEBEE),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF9A9A)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(appTitle, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFC62828))
                                    Text(pName, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("$counts times blocked", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB3261E))
                                    if (lastTime > 0) {
                                        val timeStr = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(lastTime))
                                        Text(timeStr, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// AI Study Agent Task representation
data class StudyPlanTask(
    val title: String,
    val duration: Int,
    val rationale: String,
    var isChecked: Boolean = false
)

@Composable
fun AiStudyPlannerCard(
    onApplySession: (String, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var messyNotesInput by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Parsed states
    var aiMotivation by remember { mutableStateOf("") }
    var generatedTasks by remember { mutableStateOf<List<StudyPlanTask>>(emptyList()) }

    val coroutineScope = rememberCoroutineScope()

    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(
            width = 2.dp,
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFF6750A4), Color(0xFFC084FC))
            )
        ),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth().testTag("ai_planner_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header with Sparks
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            brush = Brush.linearGradient(listOf(Color(0xFFF3E5F5), Color(0xFFEDE7F6))),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "AI Assistant Logo",
                        tint = Color(0xFF6750A4),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AI Study Coach",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF1D1B20)
                    )
                    Text(
                        text = "Turn your messy schedule into focus blocks",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            }

            Text(
                text = "Type your messy study list, exam topics, homework details, or raw thoughts below. Your AI study coach will instantly analyze and structure them into optimal, bite-sized study slots!",
                style = MaterialTheme.typography.bodySmall,
                color = Color.DarkGray
            )

            // Input field
            OutlinedTextField(
                value = messyNotesInput,
                onValueChange = { messyNotesInput = it },
                placeholder = {
                    Text(
                        "e.g. Science test on Friday. Need to write 2 pages essay. Math worksheet is due tomorrow page 12.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = Color.Gray.copy(alpha = 0.7f))
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .testTag("ai_planner_input"),
                shape = RoundedCornerShape(16.dp),
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6750A4),
                    unfocusedBorderColor = Color(0xFFCAC4D0),
                    focusedContainerColor = Color(0xFFFAF9FB),
                    unfocusedContainerColor = Color(0xFFFAF9FB)
                )
            )

            if (errorMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Error, null, tint = Color(0xFFC62828), modifier = Modifier.size(18.dp))
                        Text(
                            text = errorMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFC62828)
                        )
                    }
                }
            }

            // CTA Button to Generate
            Button(
                onClick = {
                    if (messyNotesInput.isBlank()) {
                        errorMessage = "Please enter some notes or tasks first!"
                        return@Button
                    }
                    isGenerating = true
                    errorMessage = null
                    coroutineScope.launch {
                        try {
                            val apiKey = GeminiClient.getApiKey()
                            if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
                                errorMessage = "Gemini API key is not configured in Secrets."
                                isGenerating = false
                                return@launch
                            }

                            val prompt = """
                                You are Sparky, an intelligent study scheduler agent. 
                                The student has these messy work list/thoughts: 
                                "${messyNotesInput}"
                                
                                Organize this into a clean study plan centered around focused sessions.
                                Format your answer as a JSON object strictly following this structure:
                                {
                                  "motivation": "Short, encouraging coach motivation statement.",
                                  "tasks": [
                                    {
                                      "title": "Clear actionable task title",
                                      "duration": 25, // suggested integer duration minutes for focus session
                                      "rationale": "One-line description why this helps and what to focus on"
                                    }
                                  ]
                                }
                                
                                Output only the valid JSON. No markdown wrappers. Ensure it can be parsed natively.
                            """.trimIndent()

                            val request = GenerateContentRequest(
                                contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt))))
                            )

                            val response = GeminiClient.service.generateContent(apiKey, request)
                            val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                            if (responseText.isNullOrBlank()) {
                                errorMessage = "Received an empty response from AI. Please try again."
                            } else {
                                try {
                                    val cleanJson = responseText.trim()
                                        .replace("```json", "")
                                        .replace("```", "")
                                        .trim()
                                    val jsonObject = org.json.JSONObject(cleanJson)
                                    aiMotivation = jsonObject.optString("motivation", "Let's work together to smash these study goals!")
                                    val tasksArray = jsonObject.optJSONArray("tasks")
                                    val tempTasks = mutableListOf<StudyPlanTask>()
                                    if (tasksArray != null) {
                                        for (i in 0 until tasksArray.length()) {
                                            val item = tasksArray.getJSONObject(i)
                                            tempTasks.add(
                                                StudyPlanTask(
                                                    title = item.optString("title", "Study Session"),
                                                    duration = item.optInt("duration", 25),
                                                    rationale = item.optString("rationale", "")
                                                )
                                            )
                                        }
                                    }
                                    generatedTasks = tempTasks
                                } catch (parseEx: Exception) {
                                    // Parse fallback if not valid JSON
                                    aiMotivation = "Parsed Text Study Plan:"
                                    val fallbackTasks = mutableListOf<StudyPlanTask>()
                                    // simple extract items by split
                                    val lines = responseText.split("\n")
                                    var count = 1
                                    for (line in lines) {
                                        val cleanLine = line.replace("*", "").replace("-", "").trim()
                                        if (cleanLine.isNotBlank() && cleanLine.length > 5 && !cleanLine.startsWith("{") && !cleanLine.startsWith("}")) {
                                            fallbackTasks.add(
                                                StudyPlanTask(
                                                    title = if (cleanLine.length > 40) cleanLine.take(40) + "..." else cleanLine,
                                                    duration = 25,
                                                    rationale = cleanLine
                                                )
                                            )
                                            count++
                                            if (count > 5) break
                                        }
                                    }
                                    generatedTasks = fallbackTasks
                                    if (fallbackTasks.isEmpty()) {
                                        aiMotivation = "Your schedule has been summarized:"
                                        generatedTasks = listOf(
                                            StudyPlanTask(
                                                title = "Self Study",
                                                duration = 30,
                                                rationale = responseText.take(150)
                                            )
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            errorMessage = "Error connecting to AI Assistant: ${e.message}"
                        } finally {
                            isGenerating = false
                        }
                    }
                },
                enabled = !isGenerating,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("ai_planner_submit_btn"),
                shape = RoundedCornerShape(24.dp)
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                } else {
                    Icon(imageVector = Icons.Default.SmartToy, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("De-clutter & Schedule", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            // Results Display Section
            if (aiMotivation.isNotEmpty() || generatedTasks.isNotEmpty()) {
                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF9F1FC), shape = RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFFEADBFF), shape = RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Celebration, null, tint = Color(0xFF8E24AA), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Sparky's Plan Motivation:",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF8E24AA)
                            )
                        }
                        Text(
                            text = aiMotivation,
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                            color = Color(0xFF2C2A2E)
                        )
                    }
                }

                Text(
                    text = "Organized Focus Blocks:",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF1D1B20)
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    generatedTasks.forEachIndexed { index, task ->
                        var isTaskChecked by remember(task) { mutableStateOf(task.isChecked) }
                        
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isTaskChecked) Color(0xFFE8F5E9) else Color(0xFFFBFBFC)
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isTaskChecked) Color(0xFF81C784) else Color(0xFFE0E0E0)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isTaskChecked,
                                    onCheckedChange = { checked ->
                                        isTaskChecked = checked
                                        task.isChecked = checked
                                    }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = task.title,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isTaskChecked) Color(0xFF2E7D32) else Color(0xFF1D1B20),
                                        textDecoration = if (isTaskChecked) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                                    )
                                    if (task.rationale.isNotEmpty()) {
                                        Text(
                                            text = task.rationale,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Gray
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                // Quick Apply button
                                OutlinedButton(
                                    onClick = {
                                        onApplySession(task.title, task.duration)
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.height(32.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color(0xFF6750A4)
                                    )
                                ) {
                                    Text("${task.duration}m", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.Default.FlashOn, null, modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

data class ChatSession(
    val id: String,
    val title: String,
    val timestamp: Long,
    val messages: List<GeminiContent>
)

fun saveChatSessionsToPrefs(context: android.content.Context, uid: String, sessions: List<ChatSession>) {
    try {
        val rootArray = org.json.JSONArray()
        sessions.forEach { s ->
            val sObj = org.json.JSONObject()
            sObj.put("id", s.id)
            sObj.put("title", s.title)
            sObj.put("timestamp", s.timestamp)
            
            val msgsArray = org.json.JSONArray()
            s.messages.forEach { m ->
                val mObj = org.json.JSONObject()
                mObj.put("role", m.role)
                
                val partsArray = org.json.JSONArray()
                m.parts.forEach { p ->
                    val pObj = org.json.JSONObject()
                    pObj.put("text", p.text)
                    partsArray.put(pObj)
                }
                mObj.put("parts", partsArray)
                msgsArray.put(mObj)
            }
            sObj.put("messages", msgsArray)
            rootArray.put(sObj)
        }
        val key = "foci_chat_history_" + uid
        val prefs = context.getSharedPreferences("FirebasePrefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString(key, rootArray.toString()).apply()
    } catch (e: Exception) {
        android.util.Log.e("AiChatTabContent", "Failed saving history to prefs", e)
    }
}

fun loadChatSessionsFromPrefs(context: android.content.Context, uid: String): List<ChatSession> {
    val result = mutableListOf<ChatSession>()
    try {
        val key = "foci_chat_history_" + uid
        val prefs = context.getSharedPreferences("FirebasePrefs", android.content.Context.MODE_PRIVATE)
        val rawJson = prefs.getString(key, null) ?: return emptyList()
        val rootArray = org.json.JSONArray(rawJson)
        for (i in 0 until rootArray.length()) {
            val sObj = rootArray.getJSONObject(i)
            val id = sObj.getString("id")
            val title = sObj.getString("title")
            val timestamp = sObj.getLong("timestamp")
            
            val msgsArray = sObj.getJSONArray("messages")
            val msgList = mutableListOf<GeminiContent>()
            for (j in 0 until msgsArray.length()) {
                val mObj = msgsArray.getJSONObject(j)
                val role = mObj.optString("role", "user")
                
                val partsArray = mObj.getJSONArray("parts")
                val partsList = mutableListOf<GeminiPart>()
                for (k in 0 until partsArray.length()) {
                    val pObj = partsArray.getJSONObject(k)
                    val text = pObj.getString("text")
                    partsList.add(GeminiPart(text = text))
                }
                msgList.add(GeminiContent(parts = partsList, role = role))
            }
            result.add(ChatSession(id, title, timestamp, msgList))
        }
    } catch (e: Exception) {
        android.util.Log.e("AiChatTabContent", "Failed loading history from prefs", e)
    }
    return result
}

suspend fun buildFociContextString(context: android.content.Context, userRole: String): String {
    val sb = StringBuilder()
    if (userRole == "parent") {
        val childName = FirebaseSyncManager.linkedStudentName.value.ifEmpty { "your child" }
        val childStats = FirebaseSyncManager.linkedStudentStats.value
        val childSessions = FirebaseSyncManager.linkedStudentSessions.value
        val childSneaks = FirebaseSyncManager.linkedStudentSneaks.value
        
        sb.append("You are currently speaking to the PARENT. The parent is monitoring child: $childName.\n\n")
        sb.append("--- CHILD PROFILE DATA ---\n")
        sb.append("- Username/Name: $childName\n")
        if (childStats.isNotEmpty()) {
            sb.append("- Age: ${childStats["studentAge"] ?: "N/A"}\n")
            sb.append("- Study Level: ${childStats["studentStudyLevel"] ?: "N/A"}\n")
            sb.append("- Upcoming Exam: ${childStats["studentUpcomingExam"] ?: "N/A"}\n")
            sb.append("- Daily Study Goal: ${childStats["studentDailyGoal"] ?: "N/A"}\n")
            sb.append("- Streak: ${childStats["streak"] ?: "N/A"} days\n")
            sb.append("- Stars Earned: ${childStats["stars"] ?: "0"}\n")
        } else {
            sb.append("- Age: N/A\n- Study Level: N/A\n- Upcoming Exam: N/A\n- Daily Study Goal: N/A\n")
        }
        
        sb.append("\n--- CHILD RECENT FOCUS SESSIONS ---\n")
        if (childSessions.isEmpty()) {
            sb.append("No recorded Focus Sessions logged in database/Firestore profile.\n")
        } else {
            childSessions.take(10).forEach { session ->
                val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(session.dateTimestamp))
                sb.append("- Topic: \"${session.topic}\", Duration: ${session.actualDurationMinutes} mins, Focus Score: ${session.focusScore}/100, Distractions: ${session.totalDistractionAttempts} times, Status: ${session.status} (Completed: ${session.isCompleted}), Date: $dateStr\n")
            }
        }
        
        sb.append("\n--- CHILD RECENT DISTRACTION SNEAKS (ATTEMPTED OPENINGS of BLOCKED APPS) ---\n")
        if (childSneaks.isEmpty()) {
            sb.append("No recorded distraction attempts.\n")
        } else {
            childSneaks.forEach { sneak ->
                val appLabel = sneak["readableAppName"] ?: sneak["packageName"] ?: "Unknown App"
                val count = sneak["clickCount"] ?: sneak["count"] ?: 1
                sb.append("- App: $appLabel, Distraction Attempts: $count times\n")
            }
        }
    } else {
        // Student role
        val rawUName = FirebaseSyncManager.usernameProfile.value.ifEmpty { FocusManager.userName.value }
        val username = if (rawUName.isEmpty() || rawUName == "Focused Student") "Student" else rawUName
        val age = FirebaseSyncManager.studentAge.value
        val studyLevel = FirebaseSyncManager.studentStudyLevel.value
        val upcomingExam = FirebaseSyncManager.studentUpcomingExam.value
        val dailyGoal = FirebaseSyncManager.studentDailyGoal.value
        val currentStreak = com.example.StreakManager.getCurrentStreak(context)
        val totalStars = com.example.RewardManager.getTotalStars(context)
        
        sb.append("You are speaking directly to academic STUDENT: $username.\n\n")
        sb.append("--- STUDENT PROFILE DATA ---\n")
        sb.append("- Username: $username\n")
        sb.append("- Age: ${age.ifEmpty { "N/A" }}\n")
        sb.append("- Study Level: ${studyLevel.ifEmpty { "N/A" }}\n")
        sb.append("- Upcoming Exam: ${upcomingExam.ifEmpty { "N/A" }}\n")
        sb.append("- Daily Study Goal: ${dailyGoal.ifEmpty { "N/A" }}\n")
        sb.append("- Current Streak: $currentStreak days\n")
        sb.append("- Total Stars Collected: $totalStars\n")
        
        // Load sessions from Room database
        try {
            val db = com.example.data.FocusDatabase.getDatabase(context)
            val currentUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
            val localSessions = if (currentUid.isNotEmpty()) {
                db.focusSessionDao().getAllSessions(currentUid).first()
            } else {
                emptyList()
            }
            
            sb.append("\n--- RECENTS SESSIONS ---\n")
            if (localSessions.isEmpty()) {
                sb.append("No local recorded Focus Sessions yet.\n")
            } else {
                // today study time, weekly study time
                val now = System.currentTimeMillis()
                val oneDayMs = 24L * 60 * 60 * 1000
                val sevenDaysMs = 7L * oneDayMs
                
                val todaySessions = localSessions.filter { now - it.dateTimestamp < oneDayMs }
                val weeklySessions = localSessions.filter { now - it.dateTimestamp < sevenDaysMs }
                
                val todayMins = todaySessions.sumOf { it.actualDurationMinutes }
                val weeklyMins = weeklySessions.sumOf { it.actualDurationMinutes }
                
                sb.append("- Today's Total study time: $todayMins mins\n")
                sb.append("- Weekly Total study time: $weeklyMins mins\n")
                sb.append("- Total completed sessions count: ${localSessions.count { it.isCompleted }}\n")
                
                sb.append("\n--- LAST 10 SESSIONS LOGS ---\n")
                localSessions.take(10).forEach { s ->
                    val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(s.dateTimestamp))
                    sb.append("- Topic: \"${s.topic}\", Actual Duration: ${s.actualDurationMinutes}/${s.plannedDurationMinutes} mins, Focus Score: ${s.focusScore}, Focus Level: ${s.focusLevel}, Star count: ${s.starsEarned}, Distractions: ${s.totalDistractionAttempts} times, Status: ${s.status}, Date: $dateStr\n")
                }
            }
        } catch (e: Exception) {
            sb.append("Could not fetch sessions from DB: ${e.message}\n")
        }
        
        // App sneak attempts (distractions)
        try {
            val formattedHistory = com.example.HistoryManager.getFormattedHistory(context)
            sb.append("\n--- SNEAK ATTEMPTS (BLOCKED APP ACCESS INTERCEPTIONS) ---\n")
            if (formattedHistory.isEmpty()) {
                sb.append("Zero recorded apps intercepted today. Incredible discipline!\n")
            } else {
                formattedHistory.take(8).forEach { item ->
                    sb.append("- App intercepted: ${item.readableAppName}, Attempt count: ${item.clickCount} times\n")
                }
            }
        } catch (e: Exception) {
            sb.append("Could not fetch intercept history: ${e.message}\n")
        }
    }
    return sb.toString()
}

@Composable
fun AiChatTabContent(
    innerPadding: PaddingValues,
    userRole: String,
    onUpgradeRequest: () -> Unit
) {
    val context = LocalContext.current
    val userUid = remember { FirebaseSyncManager.currentUserUid.value }
    val coroutineScope = rememberCoroutineScope()

    // Monthly quota manager for AI Coach (10/month max for Free, 35/month for Pro)
    val sharedPrefs = remember { context.getSharedPreferences("ai_coach_quota", android.content.Context.MODE_PRIVATE) }
    val currentMonthStr = remember { java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.getDefault()).format(java.util.Date()) }
    
    val isProSubscribed by com.example.service.SubscriptionManager.isPro.collectAsStateWithLifecycle()
    val maxMessages = if (isProSubscribed) 35 else 10
    
    var monthlyMessageCount by remember {
        mutableStateOf(
            if (sharedPrefs.getString("month", "") == currentMonthStr) {
                sharedPrefs.getInt("count", 0)
            } else {
                0
            }
        )
    }
    
    // Drawer/Sidebar display state
    var showSidebar by remember { mutableStateOf(false) }
    
    // UI input controller states
    var textInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Active session ID
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    
    // Loaded list of sessions
    val sessionsList = remember { mutableStateListOf<ChatSession>() }
    
    // Message List State
    val chatMessages = remember { mutableStateListOf<GeminiContent>() }
    
    val startNewChat: () -> Unit = {
        activeSessionId = null
        chatMessages.clear()
        val welcomeText = if (userRole == "parent") {
            "Hello! I am Foci, your digital parenting and family focus partner. I am here to help you guide your teen to navigate distractions, establish screen boundaries, or discuss report patterns. How can I assist you today?"
        } else {
            "Hey there! Foci here. ⚡ I'm your private focus coach, ready to help you beat distractions, power up your study streaks, and build massive focus habits. Hit me up or choose a prompt to get started! 🎯"
        }
        chatMessages.add(GeminiContent(parts = listOf(GeminiPart(text = welcomeText)), role = "model"))
    }
    
    val saveCurrentSessionState: () -> Unit = {
        if (chatMessages.isNotEmpty()) {
            val titleText = if (chatMessages.size >= 2) {
                val firstUserMsg = chatMessages.firstOrNull { it.role == "user" }?.parts?.firstOrNull()?.text
                if (!firstUserMsg.isNullOrBlank()) {
                    if (firstUserMsg.length > 40) firstUserMsg.take(37) + "..." else firstUserMsg
                } else {
                    "Chat on ${java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
                }
            } else {
                "New Chat"
            }
            
            val currentId = activeSessionId ?: java.util.UUID.randomUUID().toString()
            val currentSession = ChatSession(
                id = currentId,
                title = titleText,
                timestamp = System.currentTimeMillis(),
                messages = chatMessages.toList()
            )
            
            val index = sessionsList.indexOfFirst { it.id == currentId }
            if (activeSessionId == null) {
                activeSessionId = currentId
            }
            if (index != -1) {
                sessionsList[index] = currentSession
            } else {
                sessionsList.add(0, currentSession)
            }
            saveChatSessionsToPrefs(context, userUid, sessionsList.toList())
        }
    }
    
    // Initializer Load Actions
    LaunchedEffect(userUid) {
        sessionsList.clear()
        val loaded = loadChatSessionsFromPrefs(context, userUid)
        sessionsList.addAll(loaded)
        if (loaded.isNotEmpty() && activeSessionId == null) {
            val mostRecent = loaded.first()
            activeSessionId = mostRecent.id
            chatMessages.clear()
            chatMessages.addAll(mostRecent.messages)
        } else if (chatMessages.isEmpty()) {
            startNewChat()
        }
    }
    
    val deleteSession: (String) -> Unit = { sessionId ->
        if (activeSessionId == sessionId) {
            startNewChat()
        }
        sessionsList.removeAll { it.id == sessionId }
        saveChatSessionsToPrefs(context, userUid, sessionsList.toList())
    }
    
    val selectSession: (ChatSession) -> Unit = { session ->
        activeSessionId = session.id
        chatMessages.clear()
        chatMessages.addAll(session.messages)
        showSidebar = false
    }
    
    // Quick-prompts suggestions listing
    val promptSuggestions = remember(userRole) {
        if (userRole == "parent") {
            listOf(
                "How can I set a screen contract with my teen?",
                "What is a good motivation reward after study blocks?",
                "My teen gets distracted by social media, help!"
            )
        } else {
            listOf(
                "Give me a motivation booster right now!",
                "Suggest a 20-minute study breakout strategy",
                "How can I resist opening games during focus blocks?"
            )
        }
    }
    
    // Handle message sending action
    val onSendMessage: (String) -> Unit = { prompt ->
        if (prompt.isNotBlank() && !isLoading) {
            if (monthlyMessageCount >= maxMessages) {
                onUpgradeRequest()
            } else {
                val userMsg = GeminiContent(parts = listOf(GeminiPart(text = prompt)), role = "user")
                chatMessages.add(userMsg)
                saveCurrentSessionState()
                
                // Increment quota
                val newCount = monthlyMessageCount + 1
                monthlyMessageCount = newCount
                sharedPrefs.edit()
                    .putString("month", currentMonthStr)
                    .putInt("count", newCount)
                    .apply()

                isLoading = true
                errorMessage = null
                
                coroutineScope.launch {
                try {
                    val apiKey = GeminiClient.getApiKey()
                    if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
                        errorMessage = "Gemini API key is not configured in Secrets."
                        isLoading = false
                        return@launch
                    }
                    
                    // Fetch real productivity data context
                    val productivityDataContext = buildFociContextString(context, userRole)
                    
                    // Build customized system instructions
                    val systemInstructionText = """
                        You are 'Foci', the AI Focus Coach inside the Focus app.
                        Your role is to act as a supportive, intelligent productivity coach for students. You help students improve focus, reduce distractions, build study habits, and prepare for exams using their real productivity data.
                        
                        IMPORTANT ARCHITECTURE RULES:
                        1. You do NOT rely on chat memory to remember students contextually. Rather, you are provided with their real productivity data.
                        2. You must use the student's stored profile and productivity data from Firebase / database whenever available to tailor your responses.
                        
                        COACHING OUTLINES & RESPONSIBILITIES:
                        1. Motivation: Provide encouraging, realistic motivation.
                        2. Study Planning: Generate daily plans, weekly plans, revision schedules, and exam preparation plans.
                        3. Focus Recovery: If the student experiences many distraction attempts, recommend recovery strategies, shorter study sprints, or environment improvements.
                        4. Performance Reviews: Analyze focus trends, consistency, goal completion, and study habits.
                        5. Habit Building: Help students build streaks, increase consistency, and improve time management.
                        6. Exam Support: Based on upcoming exams, create revision schedules, suggest priorities, and recommend practice sessions.
                        
                        DISTRACTION ANALYSIS:
                        Highlight distraction trends, suggest realistic improvements, and avoid shaming. Focus on habit-building.
                        Example:
                        Instead of: "You failed because you opened Instagram 12 times."
                        Say: "Instagram accounted for most distraction attempts this week. Consider scheduling a dedicated social media break after your study block."
                        
                        PARENT SUPPORT MODE:
                        If speaking to the parent (noted in the profile context block), you must:
                        - Use supportive language
                        - Provide objective, clear productivity summaries of their child's sessions and streaks
                        - Avoid judgmental language
                        - Highlight progress and clear improvement opportunities
                        
                        RESPONSE STYLE:
                        - Friendly, positive, professional, action-oriented, clear, and concise.
                        - Always use the real student data provided below! Never invent statistics.
                        - If data is unavailable or insufficient, clearly state that more study activity is needed before deep analysis can be generated.
                        
                        REAL-TIME PROFILE & PRODUCTIVITY DATA PROVIDED BY SYSTEM:
                        $productivityDataContext
                    """.trimIndent()
                    
                    val systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemInstructionText)))
                    
                    // Create GenerateContentRequest
                    val request = GenerateContentRequest(
                        contents = chatMessages.toList(),
                        systemInstruction = systemInstruction
                    )
                    
                    val response = GeminiClient.service.generateContent(apiKey, request)
                    val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    
                    if (!responseText.isNullOrBlank()) {
                        chatMessages.add(GeminiContent(parts = listOf(GeminiPart(text = responseText)), role = "model"))
                        saveCurrentSessionState()
                    } else {
                        errorMessage = "The coach didn't output a response. Please try again."
                    }
                } catch (e: Exception) {
                    errorMessage = "Error connecting to AI Assistant: ${e.localizedMessage ?: "Timeout or network failure"}"
                    android.util.Log.e("AiChatTabContent", "Failed calling Gemini API", e)
                } finally {
                    isLoading = false
                }
            }
        }
    }
}
    
    // Main UI Box containing Sidebar Drawer logic
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
            .background(Color(0xFFFBF9FF)) // Light, neat backdrop style
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header panel: Custom Coaching Identity
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF6750A4) // Primary Deep Indigo Theme Purple
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { showSidebar = true },
                        modifier = Modifier.testTag("chat_open_drawer_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Open Chat Menu",
                            tint = Color.White
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(4.dp))
                    
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = "Foci AI Coach Icon",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(10.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Foci AI Focus Coach",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (userRole == "parent") "Family Habit & Boundary Advisor" else "Instant Focus Motivation Booster",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 11.sp
                        )
                    }
                }
            }
            
            // Message History or Blank Thread state
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                reverseLayout = false, // standard chat layout
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                items(chatMessages) { message ->
                    val isModel = message.role == "model"
                    val msgText = message.parts.firstOrNull()?.text ?: ""
                    
                    ChatBubble(
                        text = msgText,
                        isModel = isModel
                    )
                }
                
                // Show loading state
                if (isLoading) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(bottomEnd = 16.dp, topStart = 16.dp, topEnd = 16.dp))
                                    .background(Color(0xFFEADDFF).copy(alpha = 0.6f))
                                    .padding(vertical = 12.dp, horizontal = 16.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFF6750A4)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Foci is typing...",
                                        fontSize = 13.sp,
                                        color = Color(0xFF21005D),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
                
                // Show Error indicator
                if (errorMessage != null) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFFFF0F0)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = "Error notification",
                                    tint = Color.Red,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = errorMessage!!,
                                    color = Color.Red,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
            
            // Quick prompts display (only when NOT waiting for model response)
            if (!isLoading) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    items(promptSuggestions) { prompt ->
                        SuggestionPill(
                            prompt = prompt,
                            onClick = {
                                textInput = ""
                                onSendMessage(prompt)
                            }
                        )
                    }
                }
            }
            
            // Input Controls panel at the bottom
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.ime),
                color = Color.White,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = { Text("Ask Foci anything...") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_input_text_field"),
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (textInput.isNotBlank() && !isLoading) {
                                    val toSend = textInput
                                    textInput = ""
                                    onSendMessage(toSend)
                                }
                            }
                        ),
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6750A4),
                            unfocusedBorderColor = Color.LightGray
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (textInput.isNotBlank() && !isLoading) {
                                val toSend = textInput
                                textInput = ""
                                onSendMessage(toSend)
                            }
                        },
                        enabled = textInput.isNotBlank() && !isLoading,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (textInput.isNotBlank() && !isLoading) Color(0xFF6750A4) else Color(0xFFE0E0E0)
                            )
                            .testTag("chat_send_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send Message",
                            tint = if (textInput.isNotBlank() && !isLoading) Color.White else Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        
        // Semi-transparent Backdrop for Sidebar Drawer
        if (showSidebar) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable { showSidebar = false }
            )
        }
        
        // Sliding Sidebar panel
        AnimatedVisibility(
            visible = showSidebar,
            enter = slideInHorizontally(initialOffsetX = { -it }),
            exit = slideOutHorizontally(targetOffsetX = { -it })
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(280.dp)
                    .background(Color.White)
                    .clickable(enabled = false) { /* prevent click-through */ },
                color = Color.White,
                shadowElevation = 16.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Drawer Header
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.SmartToy,
                                contentDescription = "Foci Logo",
                                tint = Color(0xFF6750A4),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Foci Coach 🎯",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF6750A4)
                            )
                        }
                    }
                    
                    // Start New Chat Button
                    Button(
                        onClick = {
                            startNewChat()
                            showSidebar = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .testTag("drawer_new_chat_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF6750A4)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "Add Icon", tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start New Chat", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFFEADDFF).copy(alpha = 0.6f))
                    
                    Text(
                        text = "History Chats",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    if (sessionsList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No saved chats yet",
                                color = Color.Gray,
                                fontSize = 13.sp,
                                fontStyle = FontStyle.Italic
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(sessionsList) { session ->
                                val isSelected = activeSessionId == session.id
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) Color(0xFF6750A4).copy(alpha = 0.08f) else Color.Transparent
                                        )
                                        .clickable {
                                            selectSession(session)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = session.title,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color(0xFF6750A4) else Color(0xFF1D1B20),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(session.timestamp)),
                                            fontSize = 10.sp,
                                            color = Color.Gray
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            deleteSession(session.id)
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete chat",
                                            tint = Color.Gray.copy(alpha = 0.7f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Premium Monthly AI Coach message Quota Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Monthly AI Quota",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF6750A4)
                                )
                                Text(
                                    text = "$monthlyMessageCount / $maxMessages",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF6750A4)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { (monthlyMessageCount.toFloat() / maxMessages.toFloat()).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                                color = if (monthlyMessageCount >= maxMessages) Color(0xFFB3261E) else Color(0xFF6750A4),
                                trackColor = Color(0xFFEADDFF)
                            )
                            if (!isProSubscribed) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Button(
                                    onClick = {
                                        showSidebar = false
                                        onUpgradeRequest()
                                    },
                                    modifier = Modifier.fillMaxWidth().height(32.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("Upgrade to Foci Pro for 35", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBubble(
    text: String,
    isModel: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (isModel) Arrangement.Start else Arrangement.End
    ) {
        if (!isModel) {
            Spacer(modifier = Modifier.width(48.dp)) // Give some space on left for user messages
        }
        
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isModel) 0.dp else 16.dp,
                        bottomEnd = if (isModel) 16.dp else 0.dp
                    )
                )
                .background(
                    if (isModel) Color(0xFFF3EDF7) else Color(0xFFEADDFF)
                )
                .padding(vertical = 10.dp, horizontal = 14.dp)
        ) {
            MarkdownText(
                text = text,
                isModel = isModel
            )
        }
        
        if (isModel) {
            Spacer(modifier = Modifier.width(48.dp)) // Give some space on right for model messages
        }
    }
}

@Composable
fun MarkdownText(
    text: String,
    isModel: Boolean,
    modifier: Modifier = Modifier
) {
    val lines = text.split("\n")
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        lines.forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                return@forEach
            }

            // Render headers
            if (trimmedLine.startsWith("#")) {
                val headerLevel = trimmedLine.takeWhile { it == '#' }.length
                val headerText = trimmedLine.substring(headerLevel).trim()
                val parsedHeader = parseInlineMarkdown(headerText)
                
                val textStyle = when (headerLevel) {
                    1 -> MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        color = if (isModel) Color(0xFF6750A4) else Color(0xFF21005D)
                    )
                    2 -> MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = if (isModel) Color(0xFF6750A4) else Color(0xFF21005D)
                    )
                    else -> MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = if (isModel) Color(0xFF1D1B20) else Color(0xFF21005D)
                    )
                }
                
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = parsedHeader,
                    style = textStyle
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            // Render bullet list items
            else if (trimmedLine.startsWith("* ") || trimmedLine.startsWith("- ") || trimmedLine.startsWith("• ")) {
                val bulletChar = "• "
                val itemText = trimmedLine.substring(2).trim()
                val parsedItem = parseInlineMarkdown(itemText)
                
                // Calculate nesting indent by counting spaces in original line
                val leadingSpaces = line.takeWhile { it.isWhitespace() }.length
                val paddingStart = (leadingSpaces * 6 + 4).coerceIn(4, 32).dp
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = paddingStart, top = 2.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = bulletChar,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isModel) Color(0xFF6750A4) else Color(0xFF21005D)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = parsedItem,
                        fontSize = 14.sp,
                        color = if (isModel) Color(0xFF1D1B20) else Color(0xFF21005D),
                        fontWeight = if (isModel) FontWeight.Normal else FontWeight.Medium,
                        lineHeight = 20.sp,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
            // Render basic/normal paragraph lines
            else {
                val parsedParagraph = parseInlineMarkdown(trimmedLine)
                Text(
                    text = parsedParagraph,
                    fontSize = 14.sp,
                    color = if (isModel) Color(0xFF1D1B20) else Color(0xFF21005D),
                    fontWeight = if (isModel) FontWeight.Normal else FontWeight.Medium,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

fun parseInlineMarkdown(text: String): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            if (text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(text.substring(i + 2, end))
                    pop()
                    i = end + 2
                } else {
                    append("**")
                    i += 2
                }
            } else if (text.startsWith("*", i) && !text.startsWith("**", i)) {
                val end = text.indexOf("*", i + 1)
                if (end != -1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(text.substring(i + 1, end))
                    pop()
                    i = end + 1
                } else {
                    append("*")
                    i += 1
                }
            } else if (text.startsWith("`", i)) {
                val end = text.indexOf("`", i + 1)
                if (end != -1) {
                    pushStyle(SpanStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        background = Color(0x1B000000), // Transparent charcoal background
                        color = Color(0xFF6750A4) // Matching theme purple
                    ))
                    append(text.substring(i + 1, end))
                    pop()
                    i = end + 1
                } else {
                    append("`")
                    i += 1
                }
            } else if (text.startsWith("_", i)) {
                val end = text.indexOf("_", i + 1)
                if (end != -1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(text.substring(i + 1, end))
                    pop()
                    i = end + 1
                } else {
                    append("_")
                    i += 1
                }
            } else {
                append(text[i])
                i++
            }
        }
    }
}

@Composable
fun SuggestionPill(
    prompt: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag("ai_suggestion_pill_${prompt.hashCode()}"),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFF3EDF7),
        border = BorderStroke(1.dp, Color(0xFFEADDFF))
    ) {
        Text(
            text = prompt,
            fontSize = 11.sp,
            color = Color(0xFF49454F),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

