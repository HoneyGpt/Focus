package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Focus", appName)
  }

  @Test
  fun queryLiveFirestoreStatus() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val resultLog = StringBuilder()
    
    // Initialize Firebase Options
    val options = com.google.firebase.FirebaseOptions.Builder()
        .setApiKey("AIzaSyDy4-RrS2ChSLBwz8NqAZElt8v-Wxhu7jk")
        .setApplicationId("1:691037183659:android:16b11dcb1a2865e74ba1ee")
        .setProjectId("green-bedrock-473114-d4")
        .build()
        
    val app = com.google.firebase.FirebaseApp.initializeApp(context, options, "test_proj_app")
    val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance(app)
    
    val latch = CountDownLatch(1)
    
    resultLog.append("\n=== FIRESTORE SCAN ACTIVE ===\n")
    
    firestore.collection("users").get()
        .addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val documents = task.result
                if (documents != null) {
                    resultLog.append("Successfully queried users. Total user count: ${documents.size()}\n")
                    var targetUid: String? = null
                    var targetUserType: String? = null
                    
                    for (doc in documents) {
                        val email = doc.getString("email")
                        val role = doc.getString("role") ?: doc.getString("userType") ?: "not set"
                        resultLog.append("Found User: uid=${doc.id}, email=$email, role=$role\n")
                        if (email?.equals("harshitabhaskaruni@gmail.com", ignoreCase = true) == true) {
                            targetUid = doc.id
                            targetUserType = role
                        }
                    }
                    
                    if (targetUid != null) {
                        resultLog.append("\nTarget user detected: harshitabhaskaruni@gmail.com with uid=$targetUid, userType=$targetUserType\n")
                        firestore.collection("subscriptions").document(targetUid).get()
                            .addOnCompleteListener { subTask ->
                                if (subTask.isSuccessful) {
                                    val subDoc = subTask.result
                                    if (subDoc != null && subDoc.exists()) {
                                        resultLog.append("Subscription document found for target:\n")
                                        resultLog.append(" - status: ${subDoc.getString("status")}\n")
                                        resultLog.append(" - planType: ${subDoc.getString("planType")}\n")
                                        resultLog.append(" - integration: ${subDoc.getString("integration") ?: "None"}\n")
                                    } else {
                                        resultLog.append("No subscription document exists for UID: $targetUid\n")
                                    }
                                } else {
                                    resultLog.append("Failed to fetch subscription doc: ${subTask.exception?.message}\n")
                                }
                                latch.countDown()
                            }
                    } else {
                        resultLog.append("\nTarget user harshitabhaskaruni@gmail.com NOT found in users collection.\n")
                        latch.countDown()
                    }
                } else {
                    resultLog.append("Query returned null snapshots.\n")
                    latch.countDown()
                }
            } else {
                resultLog.append("Failed to query users: ${task.exception?.message}\n")
                latch.countDown()
            }
        }
        
    latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
    resultLog.append("=== SCAN FINISHED ===\n")
    
    // Intentionally fail the test to output results clearly in the build console output
    fail(resultLog.toString())
  }
}

