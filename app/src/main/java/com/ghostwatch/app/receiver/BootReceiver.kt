package com.ghostwatch.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.ghostwatch.app.service.GhostService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val receivedAction = intent.action ?: return
        if (receivedAction != Intent.ACTION_BOOT_COMPLETED &&
            receivedAction != Intent.ACTION_MY_PACKAGE_REPLACED) return

        Log.d("BootReceiver", "Boot completed — checking if GhostWatch should restart")

        // Only restart if it was active before reboot
        val prefs: SharedPreferences = context.getSharedPreferences("ghost_prefs", Context.MODE_PRIVATE)
        val wasActive = prefs.getBoolean("was_active", false)

        if (wasActive) {
            Log.d("BootReceiver", "Restarting GhostWatch service after boot")
            val serviceIntent = Intent(context, GhostService::class.java).apply {
                action = GhostService.ACTION_START
                // No reply_to — silent restart, no confirmation SMS on reboot
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
