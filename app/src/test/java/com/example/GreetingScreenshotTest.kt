package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun greeting_screenshot() {
    composeTestRule.setContent {
      MyApplicationTheme {
        Box(
          modifier = Modifier
              .fillMaxSize()
              .background(
                  brush = Brush.verticalGradient(
                      colors = listOf(
                          Color(0xFF0F172A),
                          Color(0xFF1E1B4B)
                      )
                  )
              )
              .padding(24.dp),
          contentAlignment = Alignment.Center
        ) {
          Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(16.dp)
          ) {
              Surface(
                  color = Color(0xFF6366F1).copy(alpha = 0.2f),
                  contentColor = Color(0xFF818CF8),
                  shape = RoundedCornerShape(12.dp)
              ) {
                  Row(
                      modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(6.dp)
                  ) {
                      Icon(Icons.Default.School, contentDescription = null, modifier = Modifier.size(16.dp))
                      Text("Currently Studying: Chemistry Revision", style = MaterialTheme.typography.labelMedium)
                  }
              }

              Box(
                  modifier = Modifier
                      .size(100.dp)
                      .clip(CircleShape)
                      .background(Color(0xFF818CF8).copy(alpha = 0.15f)),
                  contentAlignment = Alignment.Center
              ) {
                  Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF818CF8), modifier = Modifier.size(50.dp))
              }

              Text(
                  text = "Deep focus in progress",
                  style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                  color = Color.White
              )

              Card(
                  colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.8f)),
                  shape = RoundedCornerShape(12.dp),
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
              ) {
                  Text(
                      text = "Atoms make up everything, but your focus makes up your future. Keep studying!",
                      style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                      color = Color.White,
                      textAlign = TextAlign.Center,
                      modifier = Modifier.padding(18.dp)
                  )
              }
          }
        }
      }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
  }
}
