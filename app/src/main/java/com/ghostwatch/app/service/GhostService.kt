package com.ghostwatch.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.*
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.ghostwatch.app.GhostConfig
import com.ghostwatch.app.R
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class GhostService : LifecycleService() {

    companion object {
        const val TAG = "GhostService"
        const val CHANNEL_ID = "ghost_channel"
        const val NOTIF_ID = 1001
        const val ACTION_START   = "com.ghostwatch.START"
        const val ACTION_STOP    = "com.ghostwatch.STOP"
        const val ACTION_STATUS  = "com.ghostwatch.STATUS"
        const val EXTRA_REPLY_TO = "reply_to"

        // Public state — read by MainActivity
        val isActive   = AtomicBoolean(false)
        val alertsSent = AtomicInteger(0)
        var serviceStartTime = 0L
    }

    private var audioRecord: AudioRecord? = null
    private var micThread: Thread? = null
    private val micRunning = AtomicBoolean(false)
    private val lastAlertTime = AtomicLong(0L)
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Lifecycle ─────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                val replyTo = intent.getStringExtra(EXTRA_REPLY_TO)
                startMonitoring(replyTo)
            }
            ACTION_STOP -> {
                val replyTo = intent.getStringExtra(EXTRA_REPLY_TO)
                stopMonitoring(replyTo)
                stopSelf()
            }
            ACTION_STATUS -> {
                val replyTo = intent.getStringExtra(EXTRA_REPLY_TO)
                sendStatusReply(replyTo)
            }
        }

        // START_STICKY = Android will restart this service if killed
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMic()
        wakeLock?.release()
        isActive.set(false)
        Log.d(TAG, "Service destroyed")
    }

    // ── Start / Stop ──────────────────────────────────────────────

    private fun startMonitoring(replyTo: String?) {
        if (isActive.get()) {
            replyTo?.let { sendSms(it, GhostConfig.REPLY_ALREADY_ON) }
            return
        }

        serviceStartTime = System.currentTimeMillis()
        isActive.set(true)
        alertsSent.set(0)

        // Show foreground notification (required by Android)
        startForeground(NOTIF_ID, buildNotification())

        // Start mic in background thread
        startMic()

        replyTo?.let { sendSms(it, GhostConfig.REPLY_ACTIVATED) }
        Log.d(TAG, "Monitoring started")
    }

    private fun stopMonitoring(replyTo: String?) {
        if (!isActive.get()) {
            replyTo?.let { sendSms(it, GhostConfig.REPLY_ALREADY_OFF) }
            return
        }
        stopMic()
        isActive.set(false)
        replyTo?.let { sendSms(it, GhostConfig.REPLY_DEACTIVATED) }
        Log.d(TAG, "Monitoring stopped")
    }

    // ── Microphone Monitoring ─────────────────────────────────────

    private fun startMic() {
        val sampleRate = 44100
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                return
            }

            audioRecord?.startRecording()
            micRunning.set(true)

            micThread = thread(name = "GhostMicThread") {
                val buffer = ShortArray(bufferSize)
                var lastSampleTime = 0L

                while (micRunning.get()) {
                    val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0

                    val now = System.currentTimeMillis()
                    if (read > 0 && now - lastSampleTime >= GhostConfig.SAMPLE_INTERVAL_MS) {
                        lastSampleTime = now

                        // Calculate RMS amplitude
                        val amplitude = calculateAmplitude(buffer, read)

                        if (amplitude >= GhostConfig.SOUND_THRESHOLD) {
                            checkAndFireAlert(amplitude)
                        }
                    }
                }

                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Mic permission denied: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Mic error: ${e.message}")
        }
    }

    private fun stopMic() {
        micRunning.set(false)
        micThread?.interrupt()
        micThread = null
    }

    private fun calculateAmplitude(buffer: ShortArray, read: Int): Int {
        var sum = 0.0
        for (i in 0 until read) {
            sum += buffer[i].toLong() * buffer[i].toLong()
        }
        return Math.sqrt(sum / read).toInt()
    }

    // ── Alert Logic ───────────────────────────────────────────────

    private fun checkAndFireAlert(amplitude: Int) {
        val now = System.currentTimeMillis()
        val cooldownMs = GhostConfig.ALERT_COOLDOWN_SECONDS * 1000L

        if (now - lastAlertTime.get() < cooldownMs) return
        lastAlertTime.set(now)

        val levelPercent = ((amplitude.toFloat() / 32767f) * 100).toInt().coerceIn(0, 100)
        val timeStr = SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault()).format(Date(now))
        val msg = GhostConfig.replyAlert(levelPercent, timeStr)

        Log.d(TAG, "Alert! Amplitude=$amplitude ($levelPercent%) → SMS")

        // Send SMS to trusted number
        sendSms(GhostConfig.TRUSTED_NUMBER, msg)
        alertsSent.incrementAndGet()
    }

    // ── Status Reply ──────────────────────────────────────────────

    private fun sendStatusReply(replyTo: String?) {
        replyTo ?: return
        val battery = getBatteryLevel()
        val uptime  = getUptime()
        val msg = GhostConfig.replyStatus(
            active    = isActive.get(),
            battery   = battery,
            alertsSent = alertsSent.get(),
            uptime    = uptime
        )
        sendSms(replyTo, msg)
    }

    private fun getBatteryLevel(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun getUptime(): String {
        if (serviceStartTime == 0L) return "N/A"
        val diff = System.currentTimeMillis() - serviceStartTime
        val hrs  = diff / 3_600_000
        val mins = (diff % 3_600_000) / 60_000
        return "${hrs}h ${mins}m"
    }

    // ── SMS ───────────────────────────────────────────────────────

    private fun sendSms(number: String, message: String) {
        if (number.isBlank()) return
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            // Split long messages automatically
            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) {
                smsManager.sendTextMessage(number, null, message, null, null)
            } else {
                smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            }
            Log.d(TAG, "SMS sent to $number")
        } catch (e: Exception) {
            Log.e(TAG, "SMS send failed: ${e.message}")
        }
    }

    // ── Notification ──────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background Service",   // Generic name
            NotificationManager.IMPORTANCE_MIN  // No sound, no popup
        ).apply {
            setShowBadge(false)
            description = ""
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // Tap notification → open MainActivity
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, Class.forName("com.ghostwatch.app.ui.MainActivity")),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(GhostConfig.NOTIFICATION_TITLE)
            .setContentText(GhostConfig.NOTIFICATION_TEXT)
            .setSmallIcon(R.drawable.ic_ghost_notif)  // tiny, non-descript icon
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    // ── Wake Lock ─────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GhostWatch::MicLock"
        ).apply {
            acquire(24 * 60 * 60 * 1000L) // 24h max, reacquired on restart
        }
    }
}
