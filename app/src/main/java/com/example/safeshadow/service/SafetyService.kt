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
import android.media.AudioAttributes
import android.media.RingtoneManager
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

        private const val CHANNEL_ID            = "SAFE_CHANNEL"
        private const val CHANNEL_NAME          = "Safety Service"
        private const val ALERT_CHANNEL_ID      = "safety_alert_channel"
        private const val ALERT_CHANNEL_NAME    = "Safety Alerts"
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

    // Track last shown reason to update PendingIntent extras correctly
    private var lastAlertReason: String = ""

    override fun onCreate() {
        super.onCreate()
        createChannels()
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
                stopDetectors()
                setupDetectors()
                Log.d("SafeShadow", "Settings reloaded — detectors re-registered")
            }

            ACTION_TEST_SHAKE  -> onShakeTriggered()
            ACTION_TEST_FALL   -> onSuspiciousEventDetected("Possible fall detected")
            ACTION_TEST_RUNNING -> onSuspiciousEventDetected("You appear to be running")

            ACTION_SOS_TRIGGERED -> {
                // Note: cooldown check is intentionally NOT done here.
                // MainActivity already checked cooldown before starting the countdown.
                // AlertManager.sendSosAlert() sets cooldown inside the location callback
                // just before SMS sends. Checking here would block the intent because
                // cooldown might already be set by a previous detection event.
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

    // ─── Notification Channels ────────────────────────────────────────────────

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        // Foreground service channel — silent, low importance
        val serviceChannel = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(serviceChannel)

        // Safety alert channel — high importance WITH sound so heads-up appears
        val alertSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID, ALERT_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(alertSound, audioAttributes)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(alertChannel)
    }

    // ─── Notification Builder ─────────────────────────────────────────────────

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

    // ─── Sensor Detectors ─────────────────────────────────────────────────────

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
            sensorManager.registerListener(
                shakeDetector, accelerometer, SensorManager.SENSOR_DELAY_GAME
            )
            if (PrefsHelper.isFallDetectionEnabled(this)) {
                sensorManager.registerListener(
                    fallDetector, accelerometer, SensorManager.SENSOR_DELAY_GAME
                )
                Log.d("SafeShadow", "Fall detector registered")
            } else {
                Log.d("SafeShadow", "Fall detector SKIPPED — disabled in settings")
            }
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

    // ─── Alert Handlers ───────────────────────────────────────────────────────

    private fun onShakeTriggered() {
        if (PrefsHelper.isAlertOnCooldown(this)) {
            Log.d("SafeShadow", "Shake ignored — cooldown active")
            return
        }
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

        runningSessionRunnable?.let { runningSessionHandler?.removeCallbacks(it) }

        runningSessionHandler = Handler(Looper.getMainLooper())
        runningSessionRunnable = Runnable {
            isRunningSessionActive = false
            Log.d("SafeShadow", "Running session ended — 60s elapsed")
        }
        runningSessionHandler!!.postDelayed(runningSessionRunnable!!, 60000)

        showSafetyNotification("You appear to be running")
    }

    private fun cancelRunningSession() {
        isRunningSessionActive = false
        runningSessionRunnable?.let { runningSessionHandler?.removeCallbacks(it) }
        runningSessionHandler = null
        runningSessionRunnable = null
    }

    private fun onSuspiciousEventDetected(reason: String) {
        if (PrefsHelper.isAlertOnCooldown(this)) return
        showSafetyNotification(reason)
    }

    private fun showSafetyNotification(reason: String) {
        lastAlertReason = reason

        // Use unique request codes based on reason hashcode so extras always update
        val reasonCode = reason.hashCode()

        val safePendingIntent = PendingIntent.getBroadcast(
            this,
            reasonCode,
            Intent("com.example.safeshadow.ACTION_USER_SAFE").apply {
                setPackage(packageName)
                putExtra("extra_reason", reason)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val helpPendingIntent = PendingIntent.getBroadcast(
            this,
            reasonCode + 1,
            Intent("com.example.safeshadow.ACTION_SEND_HELP").apply {
                setPackage(packageName)
                putExtra("extra_reason", reason)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Full screen intent makes it show as heads-up even on locked screen
        val fullScreenIntent = PendingIntent.getActivity(
            this,
            reasonCode + 2,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("⚠️ Are you safe?")
            .setContentText("SafeShadow detected: $reason. Tap an action.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenIntent, true)
            .addAction(0, "✅ I'm Safe", safePendingIntent)
            .addAction(0, "🆘 Send Help", helpPendingIntent)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(SAFETY_ALERT_NOTIFICATION_ID, notification)

        // Auto-send SOS after 15 seconds if no response
        safetyAlertRunnable?.let { safetyAlertHandler?.removeCallbacks(it) }
        safetyAlertHandler = Handler(Looper.getMainLooper())
        safetyAlertRunnable = Runnable {
            PrefsHelper.setLastAlertTime(this)
            AlertManager.sendSosAlert(this, reason = "$reason (No Response)")
            getSystemService(NotificationManager::class.java)
                .cancel(SAFETY_ALERT_NOTIFICATION_ID)
        }
        safetyAlertHandler!!.postDelayed(safetyAlertRunnable!!, 15000)

        Log.d("SafeShadow", "Safety notification shown for: $reason")
    }

    fun cancelSafetyAlert() {
        safetyAlertRunnable?.let { safetyAlertHandler?.removeCallbacks(it) }
        safetyAlertHandler = null
        safetyAlertRunnable = null
        getSystemService(NotificationManager::class.java).cancel(SAFETY_ALERT_NOTIFICATION_ID)
        Log.d("SafeShadow", "Safety alert cancelled")
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