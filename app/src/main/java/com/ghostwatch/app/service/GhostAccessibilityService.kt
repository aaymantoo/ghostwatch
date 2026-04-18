package com.ghostwatch.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ghostwatch.app.receiver.CallReceiver

class GhostAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "GhostA11y"

        @Volatile var instance: GhostAccessibilityService? = null

        // Phase 1: tap the green answer button
        @Volatile var pendingAnswer = false

        // Phase 2: tap the merge/conference button (only when a call was already active)
        @Volatile var pendingMerge = false

        // Phase 3: hang up — triggered when user unlocks the screen during a trusted call
        @Volatile var pendingHangup = false

        private val CALL_PACKAGES = setOf(
            "com.android.incallui",
            "com.miui.incallui",
            "com.android.dialer",
            "com.miui.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer"
        )

        private val ANSWER_KEYWORDS = listOf(
            "answer", "accept", "pick up", "pickup",
            "उठाएं", "जवाब दें"
        )

        // MIUI versions label this button differently — cover all known variants
        private val MERGE_KEYWORDS = listOf(
            "merge", "merge calls", "conference", "add to conference",
            "add call", "join", "combine",
            "मर्ज", "कॉन्फ्रेंस"
        )

        private val END_CALL_KEYWORDS = listOf(
            "end", "end call", "hang up", "hangup", "disconnect", "decline",
            "समाप्त", "कॉल समाप्त"
        )
    }

    // Fires when the user dismisses the keyguard (unlocks the screen)
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!CallReceiver.trustedCallActive) return
            Log.d(TAG, "Screen unlocked during trusted call — disconnecting")
            // Primary: TelecomManager (API 28+, no UI required)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    getSystemService(TelecomManager::class.java)?.endCall()
                    Log.d(TAG, "TelecomManager.endCall() called")
                } catch (e: Exception) {
                    Log.w(TAG, "TelecomManager.endCall failed: ${e.message}")
                }
            }
            // Fallback: accessibility will click End Call when in-call UI surfaces
            pendingHangup = true
        }
    }

    override fun onServiceConnected() {
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!pendingAnswer && !pendingMerge && !pendingHangup) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg !in CALL_PACKAGES) return

        val root = rootInActiveWindow ?: return

        if (pendingAnswer) {
            if (tryClickButton(root, ANSWER_KEYWORDS)) {
                pendingAnswer = false
                Log.d(TAG, "Answer button clicked on $pkg")
                // Push call screen to background for stealth — lock fires from CallReceiver
                Handler(Looper.getMainLooper()).postDelayed({
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }, 300L)
            }
            return  // don't attempt merge until answer phase is done
        }

        if (pendingMerge) {
            if (tryClickButton(root, MERGE_KEYWORDS)) {
                pendingMerge = false
                Log.d(TAG, "Merge button clicked on $pkg")
            }
        }

        if (pendingHangup) {
            if (tryClickButton(root, END_CALL_KEYWORDS)) {
                pendingHangup = false
                Log.d(TAG, "End call button clicked on $pkg")
            }
        }
    }

    private fun tryClickButton(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        if (node.isClickable) {
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            if (keywords.any { desc.contains(it) || text.contains(it) }) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (tryClickButton(child, keywords)) return true
        }
        return false
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
        instance = null
        super.onDestroy()
    }
}
