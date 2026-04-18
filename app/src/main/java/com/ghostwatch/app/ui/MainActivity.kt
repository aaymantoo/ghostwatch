package com.ghostwatch.app.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ghostwatch.app.GhostConfig
import com.ghostwatch.app.R
import com.ghostwatch.app.databinding.ActivityMainBinding
import com.ghostwatch.app.receiver.GhostDeviceAdmin
import com.ghostwatch.app.service.GhostAccessibilityService
import com.ghostwatch.app.service.GhostService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private val requiredPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.MODIFY_AUDIO_SETTINGS,
        Manifest.permission.READ_CONTACTS
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.all { it.value }
        if (allGranted) {
            updateUI()
            Toast.makeText(this, "All permissions granted ✓", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Some permissions denied — app may not work", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("ghost_prefs", MODE_PRIVATE)

        setupUI()
        checkAndRequestPermissions()
        requestBatteryOptimizationExemption()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    // ── UI Setup ──────────────────────────────────────────────────

    private fun setupUI() {
        // Start button
        binding.btnActivate.setOnClickListener {
            if (!allPermissionsGranted()) {
                checkAndRequestPermissions()
                return@setOnClickListener
            }
            val intent = Intent(this, GhostService::class.java).apply {
                action = GhostService.ACTION_START
            }
            startForegroundService(intent)
            prefs.edit().putBoolean("was_active", true).apply()
            GhostService.isActive.set(true)  // optimistic update — service sets this async
            updateUI()
        }

        // Stop button
        binding.btnDeactivate.setOnClickListener {
            val intent = Intent(this, GhostService::class.java).apply {
                action = GhostService.ACTION_STOP
            }
            startService(intent)
            prefs.edit().putBoolean("was_active", false).apply()
            GhostService.isActive.set(false)  // optimistic update — service sets this async
            updateUI()
        }

        // Stealth: hide app from recents
        binding.btnStealth.setOnClickListener {
            moveTaskToBack(true)
        }

        // Hide launcher icon (removes from app drawer — restore via SMS 'GHOST SHOW')
        binding.btnHideIcon.setOnClickListener {
            setLauncherIconEnabled(false)
            Toast.makeText(this, "Icon hidden. Send 'GHOST SHOW' to restore.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        }

        // Call watch toggle
        binding.btnCallWatch.setOnClickListener {
            val current = prefs.getBoolean("call_watch_active", false)
            prefs.edit().putBoolean("call_watch_active", !current).apply()
            updateCallWatchUI()
        }

        // Device admin — required for stealth screen lock
        binding.btnAdmin.setOnClickListener {
            if (isDeviceAdminActive()) {
                // Already active — open device admin settings so user can see it
                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            } else {
                val admin = ComponentName(this, GhostDeviceAdmin::class.java)
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Required to lock the screen after auto-answering calls (stealth mode)."
                    )
                }
                startActivity(intent)
            }
        }

        // Accessibility service — opens system settings for the user to enable it
        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }

        // Show config
        binding.tvCmdStart.text  = GhostConfig.CMD_START
        binding.tvCmdStop.text   = GhostConfig.CMD_STOP
        binding.tvCmdStatus.text = GhostConfig.CMD_STATUS
        binding.tvThreshold.text = "${GhostConfig.SOUND_THRESHOLD} amplitude"
    }

    private fun updateUI() {
        val active = GhostService.isActive.get()
        binding.statusIndicator.setBackgroundResource(
            if (active) R.drawable.dot_green else R.drawable.dot_red
        )
        binding.tvStatus.text   = if (active) "ACTIVE — MONITORING" else "INACTIVE"
        binding.tvAlerts.text   = "Alerts sent: ${GhostService.alertsSent.get()}"
        binding.btnActivate.isEnabled   = !active
        binding.btnDeactivate.isEnabled = active
        updateCallWatchUI()
        updateAdminUI()
        updateAccessibilityUI()
    }

    private fun updateCallWatchUI() {
        val on = prefs.getBoolean("call_watch_active", false)
        binding.callWatchIndicator.setBackgroundResource(
            if (on) R.drawable.dot_green else R.drawable.dot_red
        )
        binding.tvCallWatchStatus.text =
            if (on) "ON — notifying all calls" else "OFF — calls not tracked"
        binding.btnCallWatch.text = if (on) "TURN OFF" else "TURN ON"
        binding.btnCallWatch.backgroundTintList = androidx.core.content.res.ResourcesCompat.getColorStateList(
            resources, if (on) R.color.warn else R.color.ok, theme
        )
    }

    private fun updateAdminUI() {
        val active = isDeviceAdminActive()
        binding.adminIndicator.setBackgroundResource(
            if (active) R.drawable.dot_green else R.drawable.dot_red
        )
        binding.tvAdminStatus.text =
            if (active) "Active — screen locks after auto-answer" else "Not active — screen visible on answer"
        binding.btnAdmin.text = if (active) "VIEW" else "ENABLE"
    }

    private fun isDeviceAdminActive(): Boolean {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(this, GhostDeviceAdmin::class.java)
        return dpm.isAdminActive(admin)
    }

    private fun updateAccessibilityUI() {
        val enabled = isAccessibilityServiceEnabled()
        binding.accessibilityIndicator.setBackgroundResource(
            if (enabled) R.drawable.dot_green else R.drawable.dot_red
        )
        binding.tvAccessibilityStatus.text =
            if (enabled) "Active — MIUI auto-answer ready" else "Disabled — tap ENABLE to fix"
        binding.btnAccessibility.text = if (enabled) "OPEN" else "ENABLE"
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java)
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
            it.resolveInfo.serviceInfo.name == GhostAccessibilityService::class.java.name
        }
    }

    // ── Permissions ───────────────────────────────────────────────

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun setLauncherIconEnabled(enabled: Boolean) {
        val component = ComponentName(this, MainActivity::class.java)
        val newState = if (enabled)
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        packageManager.setComponentEnabledSetting(
            component,
            newState,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // Some OEMs don't support this — open battery settings instead
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
}
