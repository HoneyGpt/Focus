package com.example.ui

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.service.SubscriptionManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun android.content.Context.findActivity(): android.app.Activity? {
    var context = this
    while (context is android.content.ContextWrapper) {
        if (context is android.app.Activity) {
            return context
        }
        context = context.baseContext
    }
    return context as? android.app.Activity
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubscriptionManagementScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onPaymentCompleted: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val status by SubscriptionManager.status.collectAsStateWithLifecycle()
    val currentPlan by SubscriptionManager.currentPlan.collectAsStateWithLifecycle()
    val isPremiumViaParent by SubscriptionManager.isPremiumViaParent.collectAsStateWithLifecycle()
    val subscriptionId by SubscriptionManager.subscriptionId.collectAsStateWithLifecycle()
    val activatedAt by SubscriptionManager.activatedAt.collectAsStateWithLifecycle()
    val nextDueDate by SubscriptionManager.nextDueDate.collectAsStateWithLifecycle()
    val isVerifying by SubscriptionManager.isVerifying.collectAsStateWithLifecycle()
    val isPro by SubscriptionManager.isPro.collectAsStateWithLifecycle()
    
    val scope = rememberCoroutineScope()
    var showCheckoutPlan by remember { mutableStateOf<String?>(null) } // "Focus Pro" or "Focus Parent"
    var restrictiveBackendError by remember { mutableStateOf<String?>(null) }
    var showCancelConfirmation by remember { mutableStateOf(false) }
    var cancelInProgress by remember { mutableStateOf(false) }

    // Listen to payment successes and errors reactively
    LaunchedEffect(Unit) {
        SubscriptionManager.paymentResult.collect { result ->
            when (result) {
                is SubscriptionManager.PaymentResult.Idle -> {
                    // Do nothing on idle
                }
                is SubscriptionManager.PaymentResult.Success -> {
                    Toast.makeText(context, "Welcome to ${result.planType}! Premium unlocked.", Toast.LENGTH_LONG).show()
                    showCheckoutPlan = null
                    onPaymentCompleted()
                }
                is SubscriptionManager.PaymentResult.Error -> {
                    // Always show raw payment error diagnostic info
                    restrictiveBackendError = result.message
                    showCheckoutPlan = null
                }
            }
        }
    }

    // Trigger real Razorpay payment flow when user chooses a plan
    LaunchedEffect(showCheckoutPlan) {
        val selectedPlan = showCheckoutPlan
        if (selectedPlan != null) {
            if (activity != null) {
                val amount = if (selectedPlan == "Focus Pro") 99 else 299
                Toast.makeText(context, "Initializing secure Razorpay checkout...", Toast.LENGTH_SHORT).show()
                SubscriptionManager.startRazorpayPayment(activity, selectedPlan, amount)
            } else {
                Toast.makeText(context, "Activity context not found", Toast.LENGTH_SHORT).show()
            }
            showCheckoutPlan = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFFEF7FF))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Back Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Color(0xFF1D1B20)
                )
            }
            Text(
                text = "Premium Plans",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF1D1B20),
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        // Current Plan Status Card
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFEADDFF))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Current Status",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF49454F)
                    )
                    
                    val badgeColor = if (isPremiumViaParent) Color(0xFF6750A4) else when (status) {
                        "Active" -> Color(0xFF4CAF50)
                        "Cancelled" -> Color(0xFFFF9800)
                        "Expired" -> Color(0xFFF44336)
                        "Paused", "Halted" -> Color(0xFF2196F3)
                        else -> Color(0xFF9E9E9E)
                    }
                    Surface(
                        color = badgeColor.copy(alpha = 0.15f),
                        contentColor = badgeColor,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (isPremiumViaParent) "PREMIUM VIA PARENT" else status.uppercase(),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                Text(
                    text = if (status == "Active") currentPlan else "No Active Plan",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = if (status == "Active") Color(0xFF6750A4) else Color(0xFF49454F)
                )

                if (status == "Active" && subscriptionId.isNotEmpty()) {
                    Divider(color = Color(0xFFCAC4D0).copy(alpha = 0.5f))
                    
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Subscription ID", style = MaterialTheme.typography.bodySmall, color = Color(0xFF49454F))
                            Text(subscriptionId, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = Color(0xFF1D1B20))
                        }
                        if (activatedAt > 0L) {
                            val activeStr = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(activatedAt))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Activation Date", style = MaterialTheme.typography.bodySmall, color = Color(0xFF49454F))
                                Text(activeStr, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = Color(0xFF1D1B20))
                            }
                        }
                        if (nextDueDate > 0L) {
                            val dueStr = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(nextDueDate))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (status == "Cancelled") "Accessible Until" else "Next Renewal Date", style = MaterialTheme.typography.bodySmall, color = Color(0xFF49454F))
                                Text(dueStr, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = Color(0xFF1D1B20))
                            }
                        }
                    }
                }
            }
        }

        val isFocusProActive = (status.equals("Active", ignoreCase = true) || status.equals("Cancelled", ignoreCase = true)) && currentPlan.equals("Focus Pro", ignoreCase = true)
        val isFocusParentActive = (status.equals("Active", ignoreCase = true) || status.equals("Cancelled", ignoreCase = true)) && currentPlan.equals("Focus Parent", ignoreCase = true)
        val isPremium = isFocusProActive || isFocusParentActive || isPremiumViaParent

        Spacer(modifier = Modifier.height(8.dp))

        if (isPremium) {
            if (isPremiumViaParent) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF6750A4).copy(alpha = 0.08f)),
                    border = BorderStroke(1.2.dp, Color(0xFF6750A4).copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF6750A4)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stars,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        
                        Text(
                            text = "Premium via Parent Active",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF6750A4)
                        )
                        
                        Text(
                            text = "You have full access to all premium features including unlimited focus sessions, custom blocking, premium analytics, and AI coaching. Managed by your parent's active Focus Parent subscription.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = Color(0xFF49454F)
                        )
                    }
                }
            } else {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50).copy(alpha = 0.08f)),
                    border = BorderStroke(1.2.dp, Color(0xFF4CAF50).copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        
                        Text(
                            text = if (isFocusParentActive) "Focus Parent Active" else "Focus Premium Active",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF4CAF50)
                        )
                        
                        val successMessage = if (isFocusParentActive) {
                            "Thank you for subscribing to Focus Parent!\n\nYou have unlocked:\n• Unlimited focus sessions\n• Custom app blocking\n• Raw diagnostic metrics\n• 35 AI coaching credits per month\n• Parent dashboard\n• Premium access for linked student accounts"
                        } else {
                            "Thank you for subscribing to Focus Pro!\n\nYou have unlocked:\n• Unlimited focus sessions\n• Custom app blocking\n• Raw diagnostic metrics\n• 35 AI coaching credits per month"
                        }

                        Text(
                            text = successMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Start,
                            color = Color(0xFF49454F),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        )
                    }
                }

                // If they directly own an active subscription, allow managing/canceling
                if (status.equals("Active", ignoreCase = true)) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFBA1A1A).copy(alpha = 0.05f)),
                        border = BorderStroke(1.dp, Color(0xFFBA1A1A).copy(alpha = 0.2f))
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Subscription Management",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFFBA1A1A)
                            )
                            Text(
                                text = "Cancel your active subscription anytime. Your benefits remain active until the end of the current billing cycle.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF49454F)
                            )
                            Button(
                                onClick = { showCancelConfirmation = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBA1A1A)),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                if (cancelInProgress) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                                } else {
                                    Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Cancel Active Subscription", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Always show the Pricing Plans grid for clarity and upgrades, unless they have premium via parent.
        if (!isPremiumViaParent) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "AVAILABLE PRICING PLANS",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp, fontWeight = FontWeight.Bold),
                color = Color(0xFF49454F)
            )

            // Focus Pro Card
            PremiumPlanCard(
                planName = "Focus Pro",
                price = "₹99/mo",
                description = "Empower your study routine with raw diagnostic metrics, deep isolation layers, and personal accountability safeguards.",
                highlights = listOf(
                    "Unlimited Focus Sessions (Sprints)",
                    "Custom Intercept Blacklists",
                    "Advanced Parental Passcode Protection",
                    "35 AI coaching credits per month"
                ),
                icon = Icons.Default.FlashOn,
                isSelected = isFocusProActive,
                buttonText = when {
                    isFocusProActive -> "Current Plan (Active)"
                    isFocusParentActive -> "Included in Focus Parent"
                    else -> null
                },
                buttonEnabled = !isFocusProActive && !isFocusParentActive,
                onPurchaseClick = {
                    if (isFocusProActive || isFocusParentActive) {
                        Toast.makeText(context, "You already have an active subscription.", Toast.LENGTH_LONG).show()
                    } else {
                        showCheckoutPlan = "Focus Pro"
                    }
                }
            )

            // Focus Parent Card
            PremiumPlanCard(
                planName = "Focus Parent",
                price = "₹299/mo",
                description = "Get professional analytics to help build habits alongside your children. Includes all Pro level features for students.",
                highlights = listOf(
                    "Secure Student Cloud Coupling",
                    "On-Demand Application Monitoring",
                    "Weekly Distribution Reports",
                    "PDF Summaries Exportation",
                    "Premium access for linked student accounts",
                    "Everything in Focus Pro"
                ),
                icon = Icons.Default.SupervisorAccount,
                isSelected = isFocusParentActive,
                buttonText = when {
                    isFocusParentActive -> "Current Plan (Active)"
                    isFocusProActive -> "Upgrade to Focus Parent (₹299/mo)"
                    else -> null
                },
                buttonEnabled = !isFocusParentActive,
                onPurchaseClick = {
                    if (isFocusParentActive) {
                        Toast.makeText(context, "You already have an active Parent subscription.", Toast.LENGTH_LONG).show()
                    } else {
                        showCheckoutPlan = "Focus Parent"
                    }
                }
            )
        }
    }

    if (restrictiveBackendError != null) {
        AlertDialog(
            onDismissRequest = { restrictiveBackendError = null },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "Payment Error",
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "Payment Error Details",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "The payment creation process failed. Below is the raw error payload returned during the secure initialization check:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    // Monospaced text card to display the raw error
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = restrictiveBackendError ?: "Unknown payment error",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Raw Payment Error", restrictiveBackendError)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Error copied to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Error",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy Error Info", style = MaterialTheme.typography.labelMedium)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { restrictiveBackendError = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Close")
                }
            }
        )
    }

    if (isVerifying) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                border = BorderStroke(1.dp, Color(0xFFEADDFF))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFF6750A4),
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = "Verifying your subscription...",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF1D1B20),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "This might take a few moments as we secure your plan with Razorpay. Please do not close the app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF49454F),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    if (showCancelConfirmation) {
        AlertDialog(
            onDismissRequest = { showCancelConfirmation = false },
            title = {
                Text(
                    text = "Cancel Subscription?",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF1D1B20)
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to cancel your active subscription? You will still retain premium benefits until the end of your current billing period.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF49454F)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCancelConfirmation = false
                        cancelInProgress = true
                        scope.launch {
                            SubscriptionManager.cancelActiveSubscription { success, error ->
                                cancelInProgress = false
                                if (success) {
                                    Toast.makeText(context, "Subscription successfully cancelled.", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Failed to cancel: ${error ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Yes, Cancel")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirmation = false }) {
                    Text("Keep Plan", color = Color(0xFF6750A4))
                }
            }
        )
    }
}

@Composable
fun PremiumPlanCard(
    planName: String,
    price: String,
    description: String,
    highlights: List<String>,
    icon: ImageVector,
    isSelected: Boolean,
    buttonText: String? = null,
    buttonEnabled: Boolean = true,
    onPurchaseClick: () -> Unit
) {
    val containerColor = if (isSelected) Color(0xFF6750A4).copy(alpha = 0.04f) else Color.White
    val borderBrush = if (isSelected) {
        Brush.linearGradient(
            colors = listOf(Color(0xFF6750A4), Color(0xFF9C27B0))
        )
    } else {
        Brush.linearGradient(
            colors = listOf(Color(0xFFEADDFF), Color(0xFFEADDFF))
        )
    }
    val borderWeight = if (isSelected) 2.dp else 1.dp

    Card(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(borderWeight, borderBrush)
    ) {
        Column(
            modifier = Modifier
                .background(containerColor)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF6750A4).copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = Color(0xFF6750A4), modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(planName, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = Color(0xFF1D1B20))
                            if (isSelected) {
                                Surface(
                                    color = Color(0xFF6750A4).copy(alpha = 0.15f),
                                    contentColor = Color(0xFF6750A4),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color(0xFF6750A4))
                                        Text("ACTIVE", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp))
                                    }
                                }
                            }
                        }
                    }
                }
                
                Text(price, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold), color = Color(0xFF6750A4))
            }

            Text(description, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF49454F))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                highlights.forEach { tag ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF6750A4), modifier = Modifier.size(18.dp))
                        Text(tag, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF1D1B20))
                    }
                }
            }

            Button(
                onClick = onPurchaseClick,
                enabled = buttonEnabled,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (!buttonEnabled) Color(0xFFEADDFF).copy(alpha = 0.5f) else if (isSelected) Color(0xFF6750A4).copy(alpha = 0.15f) else Color(0xFF6750A4),
                    contentColor = if (!buttonEnabled) Color(0xFF49454F).copy(alpha = 0.6f) else if (isSelected) Color(0xFF6750A4) else Color.White
                )
            ) {
                Text(
                    text = buttonText ?: if (isSelected) "Active Plan" else "Subscribe Now ($price)",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}



@Composable
fun PremiumPaywallDialog(
    requiredPlan: String = "Focus Pro",
    onDismiss: () -> Unit,
    onNavigateToPlans: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            border = BorderStroke(1.dp, Color(0xFFEADDFF))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Crown Hero Icon
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF6750A4).copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Stars,
                        contentDescription = "Subscription crown icon",
                        tint = Color(0xFF6750A4),
                        modifier = Modifier.size(36.dp)
                    )
                }

                Text(
                    text = "Upgrade to $requiredPlan",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF1D1B20),
                    textAlign = TextAlign.Center
                )

                val detailsText = if (requiredPlan == "Focus Parent") {
                    "Live session tracking, PDF summaries, and comprehensive child study metrics require a Focus Parent cloud subscription."
                } else {
                    "Unlock custom app blocking tools, uncompromised passcode protection, infinite study timers, and 35 coach AI credits per month with Focus Pro."
                }

                Text(
                    text = detailsText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF49454F),
                    textAlign = TextAlign.Center
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            onDismiss()
                            onNavigateToPlans()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("View Pricing Plans", fontWeight = FontWeight.Bold)
                    }
                    
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Maybe Later", color = Color(0xFF6750A4))
                    }
                }
            }
        }
    }
}

@Composable
fun ParentUpgradePaywallPlaceholder(
    innerPadding: PaddingValues,
    onUpgradeClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .background(Color(0xFFFEF7FF))
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(Color(0xFF6750A4).copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.SupervisorAccount,
                contentDescription = null,
                tint = Color(0xFF6750A4),
                modifier = Modifier.size(44.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(20.dp))
        
        Text(
            text = "Focus Parent Required",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = Color(0xFF1D1B20),
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Text(
            text = "Setup secure parent-child device sync, monitor study sessions in real-time, get comprehensive study logs, and export PDF summaries with a Focus Parent subscription.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF49454F),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = onUpgradeClick,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.Stars, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Unlock Focus Parent (₹299/mo)", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
        }
    }
}

