package com.example.service

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*

object SubscriptionManager {
    private const val TAG = "SubscriptionManager"
    
    // Remote parameters mapping
    var pendingPlanType: String? = null
    var pendingSubscriptionId: String? = null

    sealed class PaymentResult {
        object Idle : PaymentResult()
        data class Success(val paymentId: String, val planType: String) : PaymentResult()
        data class Error(val code: Int, val message: String) : PaymentResult()
    }

    private val _paymentResult = MutableStateFlow<PaymentResult>(PaymentResult.Idle)
    val paymentResult = _paymentResult.asStateFlow()

    fun notifySuccess(paymentId: String, planType: String) {
        _paymentResult.tryEmit(PaymentResult.Success(paymentId, planType))
    }
    
    fun notifyError(code: Int, message: String) {
        _paymentResult.tryEmit(PaymentResult.Error(code, message))
    }
    
    fun startRazorpayPayment(activity: android.app.Activity, planType: String, amountINR: Int) {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser

        if (user == null) {
            Log.e(TAG, "User is NULL")
            notifyError(-2, "failed - login needed")
            return
        }

        // Prevent duplicate purchases, but allow upgrading from Focus Pro to Focus Parent
        val hasParentActive = (getSubscriptionStatus().equals("Active", ignoreCase = true) && getCurrentPlan().equals("Focus Parent", ignoreCase = true)) || _isPremiumViaParent.value
        val hasProActive = getSubscriptionStatus().equals("Active", ignoreCase = true) && getCurrentPlan().equals("Focus Pro", ignoreCase = true)
        
        val isUpgradeToParent = hasProActive && planType.equals("Focus Parent", ignoreCase = true)
        
        if (hasParentActive || (hasProActive && !isUpgradeToParent)) {
            Log.w(TAG, "Aborting checkout flow: user already has an active or higher subscription.")
            notifyError(-6, "You already have an active subscription.")
            return
        }

        pendingPlanType = planType
        
        Log.i(TAG, "Starting production-grade backend order creation for plan: $planType, amount: $amountINR INR")

        user.getIdToken(true) // FORCE REFRESH TOKEN
            .addOnSuccessListener { result ->
                val apiPlanType = if (planType.equals("Focus Pro", ignoreCase = true) || planType.equals("pro", ignoreCase = true)) {
                    "pro"
                } else if (planType.equals("Focus Parent", ignoreCase = true) || planType.equals("parent", ignoreCase = true)) {
                    "parent"
                } else {
                    planType
                }

                val functions = FirebaseFunctions.getInstance()
                
                // Build the request data, completely omitting missing phone details to prevent default placeholders
                val data = hashMapOf<String, Any>()
                data["planType"] = apiPlanType
                
                val userEmail = user.email
                if (!userEmail.isNullOrBlank()) {
                    data["email"] = userEmail
                }
                
                val userPhone = user.phoneNumber
                if (!userPhone.isNullOrBlank() && userPhone.trim().isNotBlank() && userPhone != "9999999999") {
                    data["contact"] = userPhone
                }

                functions
                    .getHttpsCallable("createRazorpayOrder")
                    .call(data)
                    .addOnSuccessListener { response ->
                        try {
                            val resultData = response.data as? Map<String, Any>
                            val orderId = resultData?.get("orderId") as? String
                            val subscriptionId = resultData?.get("subscriptionId") as? String
                            val paymentUrl = resultData?.get("paymentUrl") as? String
                            val keyId = resultData?.get("keyId") as? String ?: "rzp_live_T7qdOA6MamdgQu"
                            
                            if (orderId.isNullOrBlank() && subscriptionId.isNullOrBlank()) {
                                Log.e(TAG, "createRazorpayOrder returned invalid Order/Subscription ID")
                                notifyError(-3, "Server error: Invalid Razorpay Order or Subscription ID returned")
                                return@addOnSuccessListener
                            }
                            
                            pendingSubscriptionId = subscriptionId
                            
                            val checkout = com.razorpay.Checkout()
                            checkout.setKeyID(keyId)
                            
                            val options = org.json.JSONObject()
                            options.put("name", "Focus App")
                            options.put("description", "Subscription to $planType")
                            if (!subscriptionId.isNullOrBlank()) {
                                options.put("subscription_id", subscriptionId)
                            } else {
                                options.put("order_id", orderId)
                                options.put("currency", "INR")
                                val amount = (resultData?.get("amount") as? Number)?.toInt() ?: (amountINR * 100)
                                options.put("amount", amount)
                            }
                            
                            // No prefill.
                            // Razorpay will ask the customer to enter details manually.
                            
                            Log.i(TAG, "Opening Razorpay Checkout for Order: $orderId, Subscription: $subscriptionId")
                            
                            val retryObj = org.json.JSONObject()
                            retryObj.put("enabled", true)
                            retryObj.put("max_count", 4)
                            options.put("retry", retryObj)
                            
                            checkout.open(activity, options)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error preparing secure Razorpay checkout options", e)
                            notifyError(-1, e.message ?: "Failed to prepare payment options")
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed calling createRazorpayOrder Cloud Function", e)
                        val errorDetail = if (e is com.google.firebase.functions.FirebaseFunctionsException) {
                            "Status Code: ${e.code}\n" +
                            "Message: ${e.message}\n" +
                            "Details: ${e.details ?: "No extra details."}"
                        } else {
                            e.message ?: "Order creation failed"
                        }
                        notifyError(-4, "Backend Order Creation Failed!\n\n$errorDetail")
                    }
            }
            .addOnFailureListener {
                Log.e(TAG, "TOKEN REFRESH FAILED", it)
                notifyError(-5, "Auth Token Refresh Failed: " + (it.message ?: "Failed to acquire credential"))
            }
    }
    
    // Real-time observable state flows
    private val _isPro = MutableStateFlow(false)
    val isPro = _isPro.asStateFlow()
    
    private val _isParent = MutableStateFlow(false)
    val isParent = _isParent.asStateFlow()
    
    private val _isPremiumViaParent = MutableStateFlow(false)
    val isPremiumViaParent = _isPremiumViaParent.asStateFlow()
    
    private val _status = MutableStateFlow("Inactive")
    val status = _status.asStateFlow()
    
    private val _currentPlan = MutableStateFlow("Free Tier")
    val currentPlan = _currentPlan.asStateFlow()
    
    private val _subscriptionId = MutableStateFlow("")
    val subscriptionId = _subscriptionId.asStateFlow()
    
    private val _paymentId = MutableStateFlow("")
    val paymentId = _paymentId.asStateFlow()
    
    private val _activatedAt = MutableStateFlow(0L)
    val activatedAt = _activatedAt.asStateFlow()
    
    private val _nextDueDate = MutableStateFlow(0L)
    val nextDueDate = _nextDueDate.asStateFlow()

    private val _isVerifying = MutableStateFlow(false)
    val isVerifying = _isVerifying.asStateFlow()
    
    private var subscriptionListener: ListenerRegistration? = null
    private var userProfileListener: ListenerRegistration? = null
    private var parentSubscriptionListener: ListenerRegistration? = null

    // Cache values to consolidate state computation
    private var childDirectStatus = "Inactive"
    private var childDirectPlan = "Free Tier"
    private var childDirectSubId = ""
    private var childDirectPayId = ""
    private var childDirectActivatedAt = 0L
    private var childDirectNextDueDate = 0L

    private var parentStatus = "Inactive"
    private var parentPlan = ""
    private var parentExpiryDate = 0L

    // Initialize/Startup verification
    fun init(context: Context) {
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser
        if (currentUser != null) {
            setupRealtimeSubscriptionListener(currentUser.uid)
        } else {
            resetSubscriptionState()
        }
        
        // Setup listener for user logins/sign-outs
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user != null) {
                setupRealtimeSubscriptionListener(user.uid)
            } else {
                teardownListener()
                resetSubscriptionState()
            }
        }
    }

    // Helper functions as requested
    fun isProUser(): Boolean {
        return _isPro.value
    }
    
    fun isParentUser(): Boolean {
        return _isParent.value
    }
    
    fun getSubscriptionStatus(): String {
        return _status.value
    }
    
    fun getCurrentPlan(): String {
        return _currentPlan.value
    }

    fun cancelActiveSubscription(onFinished: (Boolean, String?) -> Unit) {
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser
        if (currentUser == null) {
            onFinished(false, "User not authenticated")
            return
        }
        val firestore = FirebaseFirestore.getInstance()
        firestore.collection("subscriptions").document(currentUser.uid)
            .update("status", "Cancelled")
            .addOnSuccessListener {
                Log.i(TAG, "Successfully updated subscription status to Cancelled in Firestore")
                onFinished(true, null)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to update subscription status to Cancelled in Firestore", e)
                onFinished(false, e.message ?: "Database write error")
            }
    }

    // Razorpay success event handler that performs secure backend verification
    fun handlePaymentSuccess(
        context: Context,
        planType: String, // "Focus Pro" or "Focus Parent"
        razorpayOrderId: String?,
        razorpayPaymentId: String,
        razorpaySignature: String?,
        razorpaySubscriptionId: String? = null,
        onFinished: (Boolean, String?) -> Unit
    ) {
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser
        if (currentUser == null) {
            onFinished(false, "User not authenticated in Firebase.")
            return
        }

        Log.i(TAG, "Initiating backend payment signature verification for payment: $razorpayPaymentId")

        _isVerifying.value = true

        val apiPlanType = if (planType.equals("Focus Pro", ignoreCase = true) || planType.equals("pro", ignoreCase = true)) {
            "pro"
        } else if (planType.equals("Focus Parent", ignoreCase = true) || planType.equals("parent", ignoreCase = true)) {
            "parent"
        } else {
            planType
        }

        val functions = FirebaseFunctions.getInstance()
        val data = hashMapOf(
            "razorpay_order_id" to razorpayOrderId,
            "razorpay_subscription_id" to razorpaySubscriptionId,
            "razorpay_payment_id" to razorpayPaymentId,
            "razorpay_signature" to razorpaySignature,
            "planType" to apiPlanType
        )

        functions.getHttpsCallable("verifyRazorpayPayment")
            .call(data)
            .addOnSuccessListener { result ->
                try {
                    val resultData = result.data as? Map<String, Any>
                    val success = resultData?.get("success") as? Boolean ?: false
                    val message = resultData?.get("message") as? String ?: "Payment verified."
                    
                    if (success) {
                        Log.i(TAG, "Backend signature verification SUCCESS. Instantly unlocking Premium and redirecting...")
                        _isVerifying.value = false
                        notifySuccess(razorpayPaymentId, planType)
                        onFinished(true, null)
                    } else {
                        Log.e(TAG, "Backend signature verification failed: $message")
                        _isVerifying.value = false
                        onFinished(false, message)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing server verification response", e)
                    _isVerifying.value = false
                    onFinished(false, e.message ?: "Failed parsing payment verification response")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "verifyRazorpayPayment Cloud Function call failed", e)
                _isVerifying.value = false
                val errorDetail = if (e is com.google.firebase.functions.FirebaseFunctionsException) {
                    "Status Code: ${e.code}\n" +
                    "Message: ${e.message}\n" +
                    "Details: ${e.details ?: "No extra details."}"
                } else {
                    e.message ?: "Payment verification failed"
                }
                onFinished(false, "Backend Payment Verification Failed!\n\n$errorDetail")
            }
    }

    private fun setupRealtimeSubscriptionListener(uid: String) {
        teardownListener()
        val firestore = FirebaseFirestore.getInstance()
        
        // 1. Listen to direct subscription
        subscriptionListener = firestore.collection("subscriptions").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to subscription updates", error)
                    return@addSnapshotListener
                }
                
                if (snapshot != null && snapshot.exists()) {
                    childDirectStatus = snapshot.getString("status") ?: "Inactive"
                    val rawPlan = snapshot.getString("planType") ?: "Free Tier"
                    childDirectPlan = if (rawPlan.equals("pro", ignoreCase = true) || rawPlan.equals("Focus Pro", ignoreCase = true)) {
                        "Focus Pro"
                    } else if (rawPlan.equals("parent", ignoreCase = true) || rawPlan.equals("Focus Parent", ignoreCase = true)) {
                        "Focus Parent"
                    } else {
                        rawPlan
                    }
                    childDirectSubId = snapshot.getString("subscriptionId") ?: ""
                    childDirectPayId = snapshot.getString("paymentId") ?: ""
                    childDirectActivatedAt = snapshot.getLong("activatedAt") ?: 0L
                    childDirectNextDueDate = snapshot.getLong("nextDueDate") ?: 0L
                } else {
                    childDirectStatus = "Inactive"
                    childDirectPlan = "Free Tier"
                    childDirectSubId = ""
                    childDirectPayId = ""
                    childDirectActivatedAt = 0L
                    childDirectNextDueDate = 0L
                }
                recalculateSubscriptionState()

                // Trigger UI update when the Firestore status becomes Active
                if (_isVerifying.value && childDirectStatus.equals("Active", ignoreCase = true)) {
                    Log.i(TAG, "Firestore subscription is now ACTIVE. Unlocking Premium on the client.")
                    _isVerifying.value = false
                    notifySuccess(childDirectPayId, childDirectPlan)
                }
            }

        // 2. Listen to user profile to find linkedParentId dynamically
        userProfileListener = firestore.collection("users").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to user profile updates", error)
                    return@addSnapshotListener
                }
                
                if (snapshot != null && snapshot.exists()) {
                    val role = snapshot.getString("role") ?: "student"
                    val linkedParentId = snapshot.getString("linkedParentId") ?: ""
                    
                    if (role == "student" && linkedParentId.isNotEmpty()) {
                        setupParentSubscriptionListener(linkedParentId)
                    } else {
                        parentSubscriptionListener?.remove()
                        parentSubscriptionListener = null
                        parentStatus = "Inactive"
                        parentPlan = ""
                        parentExpiryDate = 0L
                        recalculateSubscriptionState()
                    }
                } else {
                    parentSubscriptionListener?.remove()
                    parentSubscriptionListener = null
                    parentStatus = "Inactive"
                    parentPlan = ""
                    parentExpiryDate = 0L
                    recalculateSubscriptionState()
                }
            }
    }

    private fun setupParentSubscriptionListener(parentId: String) {
        parentSubscriptionListener?.remove()
        
        val firestore = FirebaseFirestore.getInstance()
        parentSubscriptionListener = firestore.collection("subscriptions").document(parentId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to parent subscription updates", error)
                    return@addSnapshotListener
                }
                
                if (snapshot != null && snapshot.exists()) {
                    parentStatus = snapshot.getString("status") ?: "Inactive"
                    val rawPlan = snapshot.getString("planType") ?: ""
                    parentPlan = if (rawPlan.equals("pro", ignoreCase = true) || rawPlan.equals("Focus Pro", ignoreCase = true)) {
                        "Focus Pro"
                    } else if (rawPlan.equals("parent", ignoreCase = true) || rawPlan.equals("Focus Parent", ignoreCase = true)) {
                        "Focus Parent"
                    } else {
                        rawPlan
                    }
                    parentExpiryDate = snapshot.getLong("expiryDate") ?: snapshot.getLong("nextDueDate") ?: 0L
                } else {
                    parentStatus = "Inactive"
                    parentPlan = ""
                    parentExpiryDate = 0L
                }
                recalculateSubscriptionState()
            }
    }

    private fun recalculateSubscriptionState() {
        val now = System.currentTimeMillis()
        
        val isChildDirectActive = childDirectStatus == "Active" || 
                (childDirectStatus == "Cancelled" && childDirectNextDueDate > 0L && now < childDirectNextDueDate)

        val isParentSubActive = (parentStatus == "Active" && parentPlan == "Focus Parent") || 
                (parentStatus == "Cancelled" && parentPlan == "Focus Parent" && parentExpiryDate > 0L && now < parentExpiryDate)

        if (isChildDirectActive) {
            _isPremiumViaParent.value = false
            _status.value = childDirectStatus
            _currentPlan.value = childDirectPlan
            _subscriptionId.value = childDirectSubId
            _paymentId.value = childDirectPayId
            _activatedAt.value = childDirectActivatedAt
            _nextDueDate.value = childDirectNextDueDate
            
            if (childDirectPlan.equals("Focus Parent", ignoreCase = true)) {
                _isPro.value = true
                _isParent.value = true
            } else if (childDirectPlan.equals("Focus Pro", ignoreCase = true)) {
                _isPro.value = true
                _isParent.value = false
            } else {
                _isPro.value = false
                _isParent.value = false
            }
        } else if (isParentSubActive) {
            _isPremiumViaParent.value = true
            _isPro.value = true
            _isParent.value = false
            _status.value = "Active"
            _currentPlan.value = "Premium via Parent"
            _subscriptionId.value = ""
            _paymentId.value = ""
            _activatedAt.value = 0L
            _nextDueDate.value = parentExpiryDate
        } else {
            _isPremiumViaParent.value = false
            _status.value = childDirectStatus
            _currentPlan.value = childDirectPlan
            _subscriptionId.value = childDirectSubId
            _paymentId.value = childDirectPayId
            _activatedAt.value = childDirectActivatedAt
            _nextDueDate.value = childDirectNextDueDate
            _isPro.value = false
            _isParent.value = false
        }
    }

    private fun updateLocalState(
        statusValue: String,
        planTypeVal: String,
        subIdVal: String?,
        payIdVal: String,
        activatedVal: Long,
        dueVal: Long
    ) {
        childDirectStatus = statusValue
        childDirectPlan = planTypeVal
        childDirectSubId = subIdVal ?: ""
        childDirectPayId = payIdVal
        childDirectActivatedAt = activatedVal
        childDirectNextDueDate = dueVal
        recalculateSubscriptionState()
    }

    private fun teardownListener() {
        subscriptionListener?.remove()
        subscriptionListener = null
        userProfileListener?.remove()
        userProfileListener = null
        parentSubscriptionListener?.remove()
        parentSubscriptionListener = null
    }

    private fun resetSubscriptionState() {
        childDirectStatus = "Inactive"
        childDirectPlan = "Free Tier"
        childDirectSubId = ""
        childDirectPayId = ""
        childDirectActivatedAt = 0L
        childDirectNextDueDate = 0L

        parentStatus = "Inactive"
        parentPlan = ""
        parentExpiryDate = 0L

        _isPremiumViaParent.value = false
        _isPro.value = false
        _isParent.value = false
        _status.value = "Inactive"
        _currentPlan.value = "Free Tier"
        _subscriptionId.value = ""
        _paymentId.value = ""
        _activatedAt.value = 0L
        _nextDueDate.value = 0L
        _isVerifying.value = false
    }
}
