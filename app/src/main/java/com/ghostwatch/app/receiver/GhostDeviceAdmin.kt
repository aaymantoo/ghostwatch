package com.ghostwatch.app.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class GhostDeviceAdmin : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) =
        Log.d("GhostDeviceAdmin", "Device admin enabled — screen lock available")

    override fun onDisabled(context: Context, intent: Intent) =
        Log.d("GhostDeviceAdmin", "Device admin disabled")
}
