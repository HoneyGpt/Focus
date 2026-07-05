package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.FocusLockActivity
import com.example.FocusManager

class FocusBlockerAccessibilityService : AccessibilityService() {

    private var lastClickTime = 0L
    private val DEBOUNCE_DELAY = 500L // Milliseconds threshold
    private var lastPackageName = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "FocusBlockerAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!FocusManager.isFocusActive.value) return

        val eventType = event.eventType
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            
            val packageName = event.packageName?.toString() ?: return
            if (packageName == this.packageName) return // Ignore warning screen overlay

            val isBlocked = if (!com.example.service.SubscriptionManager.isPro.value) {
                // Free Plan blocks only basic coverage
                FocusManager.isPackageBlocked(packageName)
            } else {
                FocusManager.isPackageBlocked(packageName) || 
                    (packageName != this.packageName && com.example.GameDetector.isAppAGame(this, packageName))
            }

            if (isBlocked) {
                val currentTime = System.currentTimeMillis()
                
                // Debounce engine check: Debounce if click happens too fast on the same package
                if (packageName == lastPackageName && (currentTime - lastClickTime < DEBOUNCE_DELAY)) {
                    return // Drop duplicate accidental sub-clicks
                }

                // Update timestamps
                lastClickTime = currentTime
                lastPackageName = packageName

                // 1. Log the app package and increment database/pref count safely
                com.example.HistoryManager.logAttemptedClick(this, packageName)
                
                // Track distraction for star rewards calculation
                com.example.FocusSessionManager.incrementDistractionCount(packageName)

                // Trigger background sync to parent companion board
                com.example.service.FirebaseSyncManager.triggerBackgroundSync(applicationContext)

                // 2. Increment other apps attempted count (original logic)
                val isIgnoredSystemPackage = packageName == "android" || 
                        packageName.contains("launcher") || 
                        packageName.contains("systemui") ||
                        packageName.contains("inputmethod")
                if (!isIgnoredSystemPackage) {
                    FocusManager.incrementOtherAppsAttempted(applicationContext)
                }

                Log.d(TAG, "Intercepted blocked package: $packageName")
                triggerBlockOverlay(packageName)
                return
            }

            // 2. Website / Browser Blocking
            if (BROWSER_PACKAGES.contains(packageName)) {
                val rootNode = rootInActiveWindow ?: event.source ?: return
                if (checkForBlockedWebsites(rootNode)) {
                    val currentTime = System.currentTimeMillis()
                    
                    // Debounce engine check for browsers too
                    if (packageName == lastPackageName && (currentTime - lastClickTime < DEBOUNCE_DELAY)) {
                        return
                    }
                    
                    lastClickTime = currentTime
                    lastPackageName = packageName

                    com.example.HistoryManager.logAttemptedClick(this, packageName)
                    com.example.FocusSessionManager.incrementDistractionCount(packageName)
                    FocusManager.incrementOtherAppsAttempted(applicationContext)

                    // Trigger background sync to parent companion board
                    com.example.service.FirebaseSyncManager.triggerBackgroundSync(applicationContext)

                    Log.d(TAG, "Intercepted blocked website in browser: $packageName")
                    triggerBlockOverlay(packageName)
                }
            }
        }
    }

    private fun checkForBlockedWebsites(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        // Check text of current node
        val text = node.text?.toString()?.lowercase() ?: ""
        val domainsToCheck = if (com.example.service.SubscriptionManager.isPro.value) {
            BLOCKED_DOMAINS
        } else {
            setOf("youtube.com", "youtu.be", "instagram.com", "facebook.com")
        }
        for (domain in domainsToCheck) {
            if (text.contains(domain)) {
                return true
            }
        }

        // Recursively inspect children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (checkForBlockedWebsites(child)) {
                child?.recycle()
                return true
            }
            child?.recycle()
        }

        return false
    }

    private fun triggerBlockOverlay(triggerSource: String) {
        FocusManager.incrementAppsBlocked(applicationContext)
        
        // Launch Lock Screen overlay Activity
        val intent = Intent(this, FocusLockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("EXTRA_TRIGGER_SOURCE", triggerSource)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        Log.d(TAG, "FocusBlockerAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    companion object {
        private const val TAG = "AccessibilityBlocker"
        private var instance: FocusBlockerAccessibilityService? = null

        val BLOCKED_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.facebook.katana",
            "com.facebook.lite",
            "com.facebook.orca",
            "com.twitter.android",
            "com.snapchat.android",
            "com.pinterest",
            "com.reddit.frontpage",
            "com.whatsapp",
            "com.linkedin.android",
            "org.telegram.messenger",
            "com.discord",
            "com.google.android.play.games",
            "com.tencent.ig",
            "com.dts.freefireth",
            "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient",
            "com.storymatrix.dramabox",
            "com.google.android.googlequicksearchbox",
            "com.android.chrome",
            "com.android.vending",
            "com.sec.android.app.sbrowser",
            "org.mozilla.firefox",
            "org.mozilla.fenix",
            "com.brave.browser",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.duckduckgo.mobile.android",
            "com.heytap.browser",
            "com.mi.android.globalbrowser",
            "com.amazon.mShop.android.shopping",
            "com.flipkart.android",
            "com.myntra.android",
            "com.meesho.supply",
            "com.zzkko",
            "com.einnovation.temu",
            "com.google.android.apps.walletnfcrel",
            "com.phonepe.app",
            "net.one97.paytm",
            "com.binance.dev",
            "com.nextbillion.groww",
            "com.zerodha.kite3",
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.jio.media.jiobeats",
            "com.wynk.music"
        )

        val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.opera.browser",
            "com.microsoft.empath"
        )

        val BLOCKED_DOMAINS = setOf(
            "youtube.com",
            "youtu.be",
            "instagram.com",
            "tiktok.com",
            "facebook.com",
            "twitter.com",
            "x.com",
            "snapchat.com",
            "reddit.com",
            "pinterest.com",
            "whatsapp.com",
            "linkedin.com",
            "telegram.org",
            "t.me",
            "discord.com",
            "netflix.com",
            "primevideo.com"
        )

        fun goHome() {
            instance?.performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }
}
