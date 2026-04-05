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
        const val SAFETY_ALERT_NOTIFICATION_ID  = 2001
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
        const val ACTION_CANCEL_ALERT           = "com.example.safeshadow.ACTION_CANCEL_ALERT"

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

    // Safety alert handler and runnable
    private var safetyAlertHandler: Handler? = null
    private var safetyAlertRunnable: Runnable? = null

    // Running session tracking
    private var isRunningSessionActive = false
    private var runningSessionHandler: Handler? = null
    private var runningSessionRunnable: Runnable? = null

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
                cancelRunningSession()
                travelModeManager?.stop()
                travelModeManager = null
                stopDetectors()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_CANCEL_ALERT -> {
                cancelSafetyAlert()
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
        cancelSafetyAlert()
        cancelRunningSession()
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
            onRunningDetected()
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

        // Return early if running session is active
        if (isRunningSessionActive) {
            Log.d("SafeShadow", "Shake ignored — running session active")
            return
        }

        showSafetyNotification("Shake detected")
    }

    private fun onRunningDetected() {
        if (PrefsHelper.isAlertOnCooldown(this)) return
        
        isRunningSessionActive = true
        Log.d("SafeShadow", "Running session started")
        
        // Cancel any pending running session callback
        if (runningSessionRunnable != null) {
            runningSessionHandler?.removeCallbacks(runningSessionRunnable!!)
        }
        
        // Post 60 second callback to disable running session
        runningSessionHandler = Handler(Looper.getMainLooper())
        runningSessionRunnable = Runnable {
            isRunningSessionActive = false
            Log.d("SafeShadow", "Running session ended — 60s elapsed")
        }
        runningSessionHandler!!.postDelayed(runningSessionRunnable!!, 60000)
        
        // Show safety notification
        showSafetyNotification("You appear to be running")
    }

    private fun cancelRunningSession() {
        isRunningSessionActive = false
        if (runningSessionHandler != null && runningSessionRunnable != null) {
            runningSessionHandler!!.removeCallbacks(runningSessionRunnable!!)
            runningSessionHandler = null
            runningSessionRunnable = null
        }
    }

    private fun onSuspiciousEventDetected(reason: String) {
        if (PrefsHelper.isAlertOnCooldown(this)) return
        showSafetyNotification(reason)
    }

    private fun showSafetyNotification(reason: String) {
        // Create safety alert notification channel
        val channel = NotificationChannel(
            "safety_alert_channel", "Safety Alerts", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        // Intent for "I'm Safe" action
        val safePendingIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent("com.example.safeshadow.ACTION_USER_SAFE").apply {
                setPackage(packageName)
                putExtra("extra_reason", reason)
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        // Intent for "Send Help" action
        val helpPendingIntent = PendingIntent.getBroadcast(
            this, 1,
            Intent("com.example.safeshadow.ACTION_SEND_HELP").apply {
                setPackage(packageName)
                putExtra("extra_reason", reason)
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "safety_alert_channel")
            .setContentTitle("Are you safe?")
            .setContentText("SafeShadow detected: $reason")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .addAction(0, "I'm Safe", safePendingIntent)
            .addAction(0, "Send Help", helpPendingIntent)
            .build()

        getSystemService(NotificationManager::class.java).notify(SAFETY_ALERT_NOTIFICATION_ID, notification)

        // Post delayed task to send SOS alert if no response
        safetyAlertHandler = Handler(Looper.getMainLooper())
        safetyAlertRunnable = Runnable {
            AlertManager.sendSosAlert(this, reason = "$reason (No Response)")
        }
        safetyAlertHandler!!.postDelayed(safetyAlertRunnable!!, 15000)
    }

    private fun cancelSafetyAlert() {
        // Remove callback if pending
        if (safetyAlertHandler != null && safetyAlertRunnable != null) {
            safetyAlertHandler!!.removeCallbacks(safetyAlertRunnable!!)
            safetyAlertHandler = null
            safetyAlertRunnable = null
        }
        // Cancel notification
        getSystemService(NotificationManager::class.java).cancel(SAFETY_ALERT_NOTIFICATION_ID)
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