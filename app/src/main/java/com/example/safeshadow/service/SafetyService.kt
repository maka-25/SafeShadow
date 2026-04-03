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
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.safeshadow.MainActivity
import com.example.safeshadow.PrefsHelper
import com.example.safeshadow.R
import com.example.safeshadow.detection.FallDetector
import com.example.safeshadow.detection.RunningDetector
import com.example.safeshadow.detection.ShakeDetector
import com.example.safeshadow.travel.TravelModeManager
import com.example.safeshadow.ui.SafetyAlertActivity

class SafetyService : Service() {

    companion object {
        const val NOTIFICATION_ID               = 1001
        const val ACTION_STOP_SERVICE           = "ACTION_STOP_SERVICE"
        const val ACTION_START_TRAVEL           = "ACTION_START_TRAVEL"
        const val ACTION_STOP_TRAVEL            = "ACTION_STOP_TRAVEL"
        const val ACTION_TRAVEL_EXTENDED        = "ACTION_TRAVEL_EXTENDED"
        const val ACTION_TRAVEL_ALERT_DISMISSED = "ACTION_TRAVEL_ALERT_DISMISSED"
        const val ACTION_TEST_SHAKE             = "ACTION_TEST_SHAKE"
        const val ACTION_TEST_FALL              = "ACTION_TEST_FALL"
        const val ACTION_TEST_RUNNING           = "ACTION_TEST_RUNNING"
        const val ACTION_SOS_TRIGGERED          = "ACTION_SOS_TRIGGERED"
        const val ACTION_RELOAD_SETTINGS        = "ACTION_RELOAD_SETTINGS"

        private const val CHANNEL_ID   = "SAFE_CHANNEL"
        private const val CHANNEL_NAME = "Safety Service"

        // How long the shake confirmation countdown lasts (5 seconds)
        private const val SHAKE_CONFIRM_DURATION_MS = 5000L
    }

    private var intentionallyStopped = false

    private lateinit var sensorManager: SensorManager
    private lateinit var shakeDetector: ShakeDetector
    private lateinit var fallDetector: FallDetector
    private lateinit var runningDetector: RunningDetector

    private var travelModeManager: TravelModeManager? = null
    private var currentNotificationText = "Safety Mode Active 🛡️"

    // Shake confirmation countdown — counts down 5s then sends if not cancelled
    private var shakeConfirmTimer: CountDownTimer? = null
    private var shakeConfirmPending = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(currentNotificationText))
        setupDetectors()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(currentNotificationText))

        when (intent?.action) {

            ACTION_STOP_SERVICE -> {
                intentionallyStopped = true
                cancelShakeConfirmation()
                travelModeManager?.stop()
                travelModeManager = null
                stopDetectors()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START_TRAVEL -> {
                travelModeManager?.stop()
                travelModeManager = TravelModeManager(this)
                travelModeManager!!.start()
                val dest = PrefsHelper.getTravelDestination(this)
                val etaEnd = PrefsHelper.getTravelEtaEndTime(this)
                val remainingMin = ((etaEnd - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
                updateNotification("🚗 Travel: $dest (~$remainingMin min)")
            }

            ACTION_STOP_TRAVEL -> {
                travelModeManager?.stop()
                travelModeManager = null
                PrefsHelper.stopTravel(this)
                updateNotification("Safety Mode Active 🛡️")
            }

            ACTION_TRAVEL_EXTENDED -> {
                val etaEnd = PrefsHelper.getTravelEtaEndTime(this)
                val remainingMin = ((etaEnd - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
                val dest = PrefsHelper.getTravelDestination(this)
                updateNotification("🚗 Travel: $dest (~$remainingMin min)")
            }

            ACTION_TRAVEL_ALERT_DISMISSED -> {
                travelModeManager?.onAlertDismissed()
            }

            ACTION_RELOAD_SETTINGS -> {
                // Re-register detectors to pick up new toggle states from settings
                stopDetectors()
                setupDetectors()
                Log.d("SafeShadow", "Settings reloaded — detectors re-registered")
            }

            ACTION_TEST_SHAKE -> onShakeTriggered()

            ACTION_TEST_FALL -> onSuspiciousEventDetected("Possible fall detected")

            ACTION_TEST_RUNNING -> onSuspiciousEventDetected("You appear to be running")

            ACTION_SOS_TRIGGERED -> {
                if (PrefsHelper.isAlertOnCooldown(this)) {
                    Log.d("SafeShadow", "Manual SOS ignored — on cooldown")
                    return START_STICKY
                }
                updateNotification("🚨 SOS Triggered! Sending alert...")
                AlertManager.sendSosAlert(this, reason = "Manual SOS")
                resetNotificationAfterDelay()
            }
        }

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (PrefsHelper.isSafetyModeOn(this)) {
            val restartIntent = Intent(applicationContext, SafetyService::class.java)
            restartIntent.setPackage(packageName)
            startService(restartIntent)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelShakeConfirmation()
        travelModeManager?.stop()
        travelModeManager = null
        stopDetectors()
        if (!intentionallyStopped && PrefsHelper.isSafetyModeOn(this)) {
            sendBroadcast(Intent("com.example.safeshadow.RESTART_SERVICE"))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Notification Channel ────────────────────────────────────────────────────

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // ─── Notification Builder ────────────────────────────────────────────────────

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

    private fun updateNotification(text: String) {
        currentNotificationText = text
        startForeground(NOTIFICATION_ID, buildNotification(text))
    }

    // ─── Sensor Detectors ────────────────────────────────────────────────────────

    private fun setupDetectors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        shakeDetector = ShakeDetector {
            Log.d("SafeShadow", "Shake detected")
            vibratePhone()
            onShakeTriggered()
        }

        fallDetector = FallDetector {
            Log.d("SafeShadow", "Fall detected")
            onSuspiciousEventDetected("Possible fall detected")
        }

        runningDetector = RunningDetector {
            Log.d("SafeShadow", "Running detected")
            onSuspiciousEventDetected("You appear to be running")
        }

        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer != null) {
            // Always register shake — it is always active
            sensorManager.registerListener(
                shakeDetector, accelerometer, SensorManager.SENSOR_DELAY_GAME
            )
            // Fall detection — only if enabled in settings
            if (PrefsHelper.isFallDetectionEnabled(this)) {
                sensorManager.registerListener(
                    fallDetector, accelerometer, SensorManager.SENSOR_DELAY_GAME
                )
                Log.d("SafeShadow", "Fall detector registered")
            } else {
                Log.d("SafeShadow", "Fall detector SKIPPED — disabled in settings")
            }
            // Running detection — only if enabled in settings
            if (PrefsHelper.isRunningDetectionEnabled(this)) {
                sensorManager.registerListener(
                    runningDetector, accelerometer, SensorManager.SENSOR_DELAY_GAME
                )
                Log.d("SafeShadow", "Running detector registered")
            } else {
                Log.d("SafeShadow", "Running detector SKIPPED — disabled in settings")
            }
        } else {
            Log.w("SafeShadow", "No accelerometer on this device")
        }
    }

    private fun stopDetectors() {
        if (::sensorManager.isInitialized) {
            if (::shakeDetector.isInitialized) sensorManager.unregisterListener(shakeDetector)
            if (::fallDetector.isInitialized) sensorManager.unregisterListener(fallDetector)
            if (::runningDetector.isInitialized) sensorManager.unregisterListener(runningDetector)
        }
    }

    // ─── Alert Handlers ──────────────────────────────────────────────────────────

    private fun onShakeTriggered() {
        if (PrefsHelper.isAlertOnCooldown(this)) {
            Log.d("SafeShadow", "Shake ignored — cooldown active")
            return
        }

        // If shake confirmation is enabled — show 5s countdown in notification
        // If user does nothing in 5s, alert sends. Shake again to cancel.
        if (PrefsHelper.isShakeConfirmationEnabled(this)) {
            if (shakeConfirmPending) {
                // Second shake while confirmation pending — treat as cancel
                cancelShakeConfirmation()
                Log.d("SafeShadow", "Shake confirmation cancelled by second shake")
                return
            }
            startShakeConfirmation()
        } else {
            // No confirmation — send immediately
            sendShakeSos()
        }
    }

    private fun startShakeConfirmation() {
        shakeConfirmPending = true
        Log.d("SafeShadow", "Shake confirmation started — 5 second countdown")

        shakeConfirmTimer = object : CountDownTimer(SHAKE_CONFIRM_DURATION_MS, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = (millisUntilFinished / 1000).toInt()
                updateNotification("⚠️ SOS in ${secondsLeft}s — Shake again to cancel")
            }

            override fun onFinish() {
                shakeConfirmPending = false
                Log.d("SafeShadow", "Shake confirmation timed out — sending SOS")
                sendShakeSos()
            }
        }.start()
    }

    private fun cancelShakeConfirmation() {
        shakeConfirmTimer?.cancel()
        shakeConfirmTimer = null
        shakeConfirmPending = false
        // Restore normal notification
        if (!intentionallyStopped && PrefsHelper.isSafetyModeOn(this)) {
            if (PrefsHelper.isTravelActive(this)) {
                updateNotification("🚗 Travel: ${PrefsHelper.getTravelDestination(this)}")
            } else {
                updateNotification("Safety Mode Active 🛡️")
            }
        }
    }

    private fun sendShakeSos() {
        updateNotification("🚨 SOS Triggered! Sending alert...")
        AlertManager.sendSosAlert(this, reason = "Shake SOS")
        resetNotificationAfterDelay()
    }

    private fun onSuspiciousEventDetected(reason: String) {
        if (PrefsHelper.isAlertOnCooldown(this)) return
        SafetyAlertActivity.launch(this, reason)
    }

    private fun resetNotificationAfterDelay() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (!intentionallyStopped && PrefsHelper.isSafetyModeOn(this)) {
                if (PrefsHelper.isTravelActive(this)) {
                    updateNotification("🚗 Travel: ${PrefsHelper.getTravelDestination(this)}")
                } else {
                    updateNotification("Safety Mode Active 🛡️")
                }
            }
        }, 5000)
    }

    private fun vibratePhone() {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1)
            )
        } catch (e: Exception) {
            Log.e("SafeShadow", "Vibration failed: ${e.message}")
        }
    }
}