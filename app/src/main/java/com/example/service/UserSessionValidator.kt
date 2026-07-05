package com.example.service

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore

object UserSessionValidator {
    private const val TAG = "UserSessionValidator"

    /**
     * Checks if a user profile exists in Firestore and fetches their onboarding status.
     * Keeps user in splash during active checking, preventing the login screen from
     * overwriting an already authenticated user profile.
     */
    fun validateSession(
        context: Context,
        uid: String,
        onResult: (exists: Boolean, onboardingCompleted: Boolean, role: String?, username: String?) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (uid.isEmpty()) {
            onResult(false, false, null, null)
            return
        }

        val db = FirebaseFirestore.getInstance()
        db.collection("users").document(uid).get()
            .addOnSuccessListener { documentSnapshot ->
                if (documentSnapshot != null && documentSnapshot.exists()) {
                    val onboardingCompleted = documentSnapshot.getBoolean("onboardingCompleted") ?: false
                    val role = documentSnapshot.getString("role")
                    val username = documentSnapshot.getString("username") ?: documentSnapshot.getString("name")
                    
                    Log.i(TAG, "Session validation success. User exists! Onboarding completed: $onboardingCompleted, Role: $role")
                    onResult(true, onboardingCompleted || !role.isNullOrEmpty(), role, username)
                } else {
                    Log.i(TAG, "Session validation finished. User authenticated but no profile document found in Firestore.")
                    onResult(false, false, null, null)
                }
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Session validation failed to query Firestore.", exception)
                onFailure(exception)
            }
    }
}
