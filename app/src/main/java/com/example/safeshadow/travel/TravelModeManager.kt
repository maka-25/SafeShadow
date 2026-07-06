package com.example.safeshadow.travel

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.safeshadow.MainActivity
import com.example.safeshadow.PrefsHelper
import com.example.safeshadow.R
import com.example.safeshadow.alert.AlertManager
import com.example.safeshadow.alert.LocationHelper
import com.example.safeshadow.ui.TravelAlertActivity
import com.example.safeshadow.service.SafetyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TravelModeManager(private val context: Context) {

    companion object {
        private const val TAG = "TravelModeManager"
        private const val LOCATION_POLL_INTERVAL_MS = 2 * 60 * 1000L
        private const val STILLNESS_THRESHOLD_MS    = 10 * 60 * 1000L
        private const val STILLNESS_RADIUS_M        = 50f
        private const val STILLNESS_GRACE_PERIOD_MS = 5 * 60 * 1000L

        // Use high request codes to avoid collision with SafetyService PendingIntents
        private const val TRAVEL_SAFE_REQUEST_CODE   = 3001
        private const val TRAVEL_HELP_REQUEST_CODE   = 3002
        private const val TRAVEL_SCREEN_REQUEST_CODE = 3003
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var alertShowing = false
    private var travelAlertTimeoutRunnable: Runnable? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!PrefsHelper.isTravelActive(context)) {
                Log.d(TAG, "Travel no longer active — stopping poll")
                return
            }
            checkTravelStatus()
            handler.postDelayed(this, LOCATION_POLL_INTERVAL_MS)
        }
    }

    fun start() {
        Log.d(TAG, "TravelModeManager started")
        alertShowing = false
        handler.postDelayed(pollRunnable, 60 * 1000L)
    }

    fun stop() {
        Log.d(TAG, "TravelModeManager stopped")
        handler.removeCallbacks(pollRunnable)
        cancelTravelAlert()
        scope.cancel()
        alertShowing = false
    }

    fun onAlertDismissed() {
        alertShowing = false
    }

    // Core check

    private fun checkTravelStatus() {
        if (alertShowing) {
            Log.d(TAG, "Alert already showing — skipping check")
            return
        }

        val etaEndTime = PrefsHelper.getTravelEtaEndTime(context)
        val now = System.currentTimeMillis()

        if (now >= etaEndTime) {
            Log.d(TAG, "ETA expired — showing travel alert notification")
            showTravelAlertNotification(TravelAlertActivity.REASON_ETA_EXPIRED)
            return
        }

        val startTime = PrefsHelper.getTravelStartTime(context)
        val timeSinceStart = now - startTime
        if (timeSinceStart < STILLNESS_GRACE_PERIOD_MS) {
            Log.d(TAG, "In grace period — skipping stillness check")
            return
        }

        scope.launch {
            checkStillness()
        }
    }

    // Stillness detection

    private suspend fun checkStillness() {
        try {
            val location = LocationHelper.getCurrentLocationSuspend(context) ?: run {
                Log.w(TAG, "Could not get location for stillness check")
                return
            }

            if (location.speed > 0.5f) {
                Log.d(TAG, "Stillness suppressed — speed: ${location.speed} m/s")
                return
            }

            val now = System.currentTimeMillis()
            val lastLat  = PrefsHelper.getLastKnownLat(context)
            val lastLng  = PrefsHelper.getLastKnownLng(context)
            val lastTime = PrefsHelper.getLastKnownLocTime(context)

            if (lastTime == 0L) {
                PrefsHelper.saveLastKnownLocation(context, location.latitude, location.longitude, now)
                Log.d(TAG, "First location saved")
                return
            }

            val lastLocation = Location("last").apply {
                latitude  = lastLat
                longitude = lastLng
            }
            val distanceMoved = location.distanceTo(lastLocation)

            Log.d(TAG, "Distance moved: ${distanceMoved}m")

            if (distanceMoved > STILLNESS_RADIUS_M) {
                PrefsHelper.saveLastKnownLocation(context, location.latitude, location.longitude, now)
                Log.d(TAG, "Phone moved — stillness clock reset")
            } else {
                val stillForMs = now - lastTime
                Log.d(TAG, "Still for ${stillForMs / 1000}s")

                if (stillForMs >= STILLNESS_THRESHOLD_MS) {
                    Log.d(TAG, "Stillness threshold exceeded — showing alert")
                    PrefsHelper.saveLastKnownLocation(context, location.latitude, location.longitude, now)
                    handler.post {
                        showTravelAlertNotification(TravelAlertActivity.REASON_STILLNESS)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Stillness check failed: ${e.message}")
        }
    }

    // Travel alert notification

    private fun showTravelAlertNotification(reason: String) {
        if (alertShowing) return
        alertShowing = true
        cancelTravelAlert()

        val title = if (reason == TravelAlertActivity.REASON_ETA_EXPIRED) {
            "🗺️ Travel ETA Reached"
        } else {
            "⚠️ Are you okay?"
        }

        val body = if (reason == TravelAlertActivity.REASON_ETA_EXPIRED) {
            "Have you arrived safely at your destination?"
        } else {
            "You have been still for a while. Are you safe?"
        }

        Log.d(TAG, "Showing travel alert notification — reason: $reason")

        val safePending = PendingIntent.getBroadcast(
            context,
            TRAVEL_SAFE_REQUEST_CODE,
            Intent("com.example.safeshadow.ACTION_TRAVEL_SAFE").apply {
                setPackage(context.packageName)
                putExtra("travel_reason", reason)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val helpPending = PendingIntent.getBroadcast(
            context,
            TRAVEL_HELP_REQUEST_CODE,
            Intent("com.example.safeshadow.ACTION_TRAVEL_HELP").apply {
                setPackage(context.packageName)
                putExtra("travel_reason", reason)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val fullScreenIntent = PendingIntent.getActivity(
            context,
            TRAVEL_SCREEN_REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, "safety_alert_channel_v2")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenIntent, true)
            .addAction(0, "✅ I'm Safe", safePending)
            .addAction(0, "🆘 I Need Help", helpPending)
            .build()

        context.getSystemService(NotificationManager::class.java)?.notify(2004, notification)

        // Auto-send SOS after 30 seconds if no response
        travelAlertTimeoutRunnable = Runnable {
            Log.w(TAG, "No response to travel alert — auto-sending SOS")
            AlertManager.sendSosAlert(context, "No response to travel safety check")
            cancelTravelAlert()
            PrefsHelper.stopTravel(context)
            context.startService(Intent(context, SafetyService::class.java).apply {
                action = SafetyService.ACTION_STOP_TRAVEL
            })
            alertShowing = false
        }
        handler.postDelayed(travelAlertTimeoutRunnable!!, 30 * 1000L)
    }

    fun cancelTravelAlert() {
        travelAlertTimeoutRunnable?.let { handler.removeCallbacks(it) }
        travelAlertTimeoutRunnable = null
        context.getSystemService(NotificationManager::class.java)?.cancel(2004)
    }
}