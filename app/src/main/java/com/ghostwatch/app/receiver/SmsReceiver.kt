package com.ghostwatch.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.util.Log
import com.ghostwatch.app.GhostConfig
import com.ghostwatch.app.service.GhostService

class SmsReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Reconstruct full message (may be multi-part)
        val sender = messages[0].originatingAddress ?: return
        val body   = messages.joinToString("") { it.messageBody ?: "" }.trim()

        Log.d(TAG, "SMS from $sender: $body")

        // Check trusted number (if configured)
        if (GhostConfig.TRUSTED_NUMBER.isNotBlank()) {
            val normalizedSender  = normalizeNumber(sender)
            val normalizedTrusted = normalizeNumber(GhostConfig.TRUSTED_NUMBER)
            if (!normalizedSender.endsWith(normalizedTrusted) &&
                !normalizedTrusted.endsWith(normalizedSender)) {
                Log.d(TAG, "SMS ignored — not from trusted number")
                return
            }
        }

        // Match command (case-insensitive)
        val command = body.uppercase().trim()

        // Call watch commands — handled here, no service needed
        if (command == GhostConfig.CMD_CALL_ON.uppercase()) {
            context.getSharedPreferences("ghost_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("call_watch_active", true).apply()
            sendSms(context, sender, GhostConfig.REPLY_CALL_WATCH_ON)
            Log.d(TAG, "Call watch enabled")
            return
        }
        if (command == GhostConfig.CMD_CALL_OFF.uppercase()) {
            context.getSharedPreferences("ghost_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("call_watch_active", false).apply()
            sendSms(context, sender, GhostConfig.REPLY_CALL_WATCH_OFF)
            Log.d(TAG, "Call watch disabled")
            return
        }

        val serviceIntent = when (command) {
            GhostConfig.CMD_START.uppercase() -> Intent(context, GhostService::class.java).apply {
                action = GhostService.ACTION_START
                putExtra(GhostService.EXTRA_REPLY_TO, sender)
            }
            GhostConfig.CMD_STOP.uppercase() -> Intent(context, GhostService::class.java).apply {
                action = GhostService.ACTION_STOP
                putExtra(GhostService.EXTRA_REPLY_TO, sender)
            }
            GhostConfig.CMD_STATUS.uppercase() -> Intent(context, GhostService::class.java).apply {
                action = GhostService.ACTION_STATUS
                putExtra(GhostService.EXTRA_REPLY_TO, sender)
            }
            else -> null
        }

        serviceIntent?.let {
            context.startForegroundService(it)
            Log.d(TAG, "Command dispatched: ${it.action}")
        }
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
            smsManager.sendTextMessage(number, null, message, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "SMS reply failed: ${e.message}")
        }
    }

    /**
     * Normalize phone numbers for comparison:
     * strips spaces, dashes, parentheses, and leading +/0/91
     */
    private fun normalizeNumber(number: String): String {
        return number
            .replace(Regex("[\\s\\-().+]"), "")
            .trimStart('0')
            .let { if (it.startsWith("91") && it.length > 10) it.substring(2) else it }
    }
}
