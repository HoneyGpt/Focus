package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.service.FocusBlockerAccessibilityService
import com.example.ui.theme.MyApplicationTheme

class FocusLockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val isFocusActive by FocusManager.isFocusActive.collectAsStateWithLifecycle()
                
                // If focus becomes inactive, automatically dismiss the lock
                LaunchedEffect(isFocusActive) {
                    if (!isFocusActive) {
                        finish()
                    }
                }

                // Handle system back press by redirecting back to study (home screen)
                BackHandler {
                    redirectHome()
                }

                LockScreen()
            }
        }
    }

    private fun redirectHome() {
        FocusBlockerAccessibilityService.goHome()
        finish()
    }

    @Composable
    fun LockScreen() {
        val topic by FocusManager.currentTopic.collectAsStateWithLifecycle()
        val quote by FocusManager.currentQuote.collectAsStateWithLifecycle()
        val timeLeftSeconds by FocusManager.timeLeftSeconds.collectAsStateWithLifecycle()

        val context = LocalContext.current
        val customQuote = remember { QuoteManager.getSavedQuote(context) }
        val displayText = remember(quote, customQuote) {
            val defaultQuote = "Success is the sum of small efforts, repeated day in and day out."
            if (customQuote != defaultQuote && customQuote.isNotBlank()) {
                customQuote
            } else if (quote.isNotBlank()) {
                quote
            } else {
                customQuote
            }
        }

        // Soft, breathing scaling animation for the central lock icon
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.95f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )

        val minutes = timeLeftSeconds / 60
        val seconds = timeLeftSeconds % 60
        val timeFormatted = String.format("%02d:%02d", minutes, seconds)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFFEF7FF))
                .safeDrawingPadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Focus Topic Capsule
                Surface(
                    color = Color(0xFF6750A4).copy(alpha = 0.12f),
                    contentColor = Color(0xFF6750A4),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.School,
                            contentDescription = "Studying Topic Icon",
                            tint = Color(0xFF6750A4),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Currently Studying: $topic",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }

                // Breathing Padlock Icon with Geometric background ring
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF6750A4).copy(alpha = 0.15f),
                                    Color.Transparent
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked distraction",
                        tint = Color(0xFF6750A4),
                        modifier = Modifier
                            .size(70.dp)
                            .testTag("app_lock_icon")
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // AI Generated / Context Quote Card
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFF3EDF7)
                    ),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "\"$displayText\"",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                fontSize = 18.sp,
                                lineHeight = 26.sp
                            ),
                            color = Color(0xFF1D1B20),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        
                        Text(
                            text = "— STUDY FOCUS SYSTEM —",
                            style = MaterialTheme.typography.labelSmall.copy(
                                letterSpacing = 1.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF6750A4)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Work Timer Progress Display
                Text(
                    text = "Time Remaining in Session",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF49454F)
                )
                Text(
                    text = timeFormatted,
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.Light,
                        fontSize = 44.sp
                    ),
                    color = Color(0xFF1D1B20),
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Spacer(modifier = Modifier.height(40.dp))

                // Action Button: Return to Focus
                Button(
                    onClick = { redirectHome() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6750A4),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(28.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(54.dp)
                        .testTag("exit_to_work_button")
                ) {
                    Text(
                        text = "Go Back to Studies",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    )
                }
            }
        }
    }
}
