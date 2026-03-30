package com.example.safeshadow.service

import com.example.safeshadow.alert.AlertManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.safeshadow.MainActivity
import com.example.safeshadow.PrefsHelper
import com.safeshadow.R
import com.example.safeshadow.detection.ShakeDetector
import com.example.safeshadow.detection.FallDetector
import com.example.safeshadow.detection.RunningDetector
import com.example.safeshadow.ui.SafetyAlertActivity

class SafetyService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val ACTION_START_TRAVEL = "ACTION_START_TRAVEL"
        const val ACTION_STOP_TRAVEL = "ACTION_STOP_TRAVEL"
        const val ACTION_TEST_SHAKE = "ACTION_TEST_SHAKE"
        private const val CHANNEL_ID = "SAFE_CHANNEL"
        private const val CHANNEL_NAME = "Safety Service"
        const val ACTION_TEST_FALL = "ACTION_TEST_FALL"
        const val ACTION_TEST_RUNNING = "ACTION_TEST_RUNNING"
        const val ACTION_SOS_TRIGGERED = "ACTION_SOS_TRIGGERED"
    }

    private var lastAlertTime = 0L
    private val ALERT_COOLDOWN = 15000L  // 15 seconds between alerts
    private var intentionallyStopped = false
    private lateinit var sensorManager: SensorManager
    private lateinit var shakeDetector: ShakeDetector
    private lateinit var fallDetector: FallDetector
    private lateinit var runningDetector: RunningDetector
    private var currentNotificationText = "Safety Mode Active 🛡️"

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Call startForeground IMMEDIATELY — no delay, no conditions
        startForeground(NOTIFICATION_ID, buildNotification(currentNotificationText))
        setupShakeDetector()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always re-anchor the foreground notification on every start
        startForeground(NOTIFICATION_ID, buildNotification(currentNotificationText))

        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                intentionallyStopped = true
                stopShakeDetector()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_TRAVEL -> updateNotification("Travel Mode Active 🚗")
            ACTION_STOP_TRAVEL  -> updateNotification("Safety Mode Active 🛡️")
            ACTION_TEST_SHAKE   -> onShakeTriggered()
            ACTION_TEST_FALL    -> onSuspiciousEventDetected("Possible fall detected")
            ACTION_TEST_RUNNING -> onSuspiciousEventDetected("You appear to be running")
            ACTION_SOS_TRIGGERED -> {
                updateNotification("🚨 SOS Triggered! Sending alert...")
                // Reset notification after 5 seconds
                android.os.Handler(mainLooper).postDelayed({
                    if (!intentionallyStopped && PrefsHelper.isSafetyModeOn(this)) {
                        updateNotification("Safety Mode Active 🛡️")
                    }
                }, 5000)
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Directly restart service — simpler and more reliable than AlarmManager
        if (PrefsHelper.isSafetyModeOn(this)) {
            val restartIntent = Intent(applicationContext, SafetyService::class.java)
            restartIntent.setPackage(packageName)
            startService(restartIntent)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopShakeDetector()
        if (!intentionallyStopped && PrefsHelper.isSafetyModeOn(this)) {
            // Restart via broadcast as backup
            sendBroadcast(Intent("com.safeshadow.RESTART_SERVICE"))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Notification Channel ───────────────────────────────────────────────────

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW  // Must NOT be IMPORTANCE_MIN
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    // ─── Notification Builder ───────────────────────────────────────────────────

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SafeShadow")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // ─── Key fix: use startForeground NOT notificationManager.notify() ──────────

    private fun updateNotification(text: String) {
        currentNotificationText = text
        // ONLY startForeground — never notificationManager.notify()
        startForeground(NOTIFICATION_ID, buildNotification(text))
    }

    // ─── Shake Detection ────────────────────────────────────────────────────────

    private fun setupShakeDetector() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        shakeDetector = ShakeDetector {
            Log.d("SafeShadow", "Shake detected — triggering SOS")
            vibratePhone()
            onShakeTriggered()
        }

        fallDetector = FallDetector {
            Log.d("SafeShadow", "Fall detected — showing safety check")
            onSuspiciousEventDetected("Possible fall detected")
        }

        runningDetector = RunningDetector {
            Log.d("SafeShadow", "Running detected — showing safety check")
            onSuspiciousEventDetected("You appear to be running")
        }

        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (accelerometer != null) {
            // Register all three detectors on same accelerometer
            sensorManager.registerListener(
                shakeDetector,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME
            )
            sensorManager.registerListener(
                fallDetector,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME
            )
            sensorManager.registerListener(
                runningDetector,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME
            )
            Log.d("SafeShadow", "All detectors registered")
        } else {
            Log.w("SafeShadow", "No accelerometer on this device")
        }
    }

    private fun stopShakeDetector() {
        if (::sensorManager.isInitialized) {
            sensorManager.unregisterListener(shakeDetector)
            sensorManager.unregisterListener(fallDetector)
            sensorManager.unregisterListener(runningDetector)
        }
    }

    private fun onShakeTriggered() {
        val now = System.currentTimeMillis()

        // Second layer — ignore if alert was sent too recently
        if (now - lastAlertTime < ALERT_COOLDOWN) {
            Log.d("SafeShadow", "Alert cooldown active — ignoring trigger")
            return
        }

        lastAlertTime = now
        updateNotification("🚨 SOS Triggered! Sending alert...")
        AlertManager.sendSosAlert(this, reason = "Shake SOS")

        android.os.Handler(mainLooper).postDelayed({
            if (!intentionallyStopped && PrefsHelper.isSafetyModeOn(this)) {
                updateNotification("Safety Mode Active 🛡️")
            }
        }, 5000)
    }

    private fun onSuspiciousEventDetected(reason: String) {
        // Use global prefs cooldown — works across service and activity
        if (PrefsHelper.isAlertOnCooldown(this, ALERT_COOLDOWN)) return
        SafetyAlertActivity.launch(this, reason)
    }

    private fun vibratePhone() {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 400, 200, 400, 200, 400),
                    -1
                )
            )
        } catch (e: Exception) {
            Log.e("SafeShadow", "Vibration failed: ${e.message}")
        }
    }
}