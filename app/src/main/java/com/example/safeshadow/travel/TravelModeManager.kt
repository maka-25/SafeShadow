package com.example.safeshadow.travel

import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.safeshadow.PrefsHelper
import com.example.safeshadow.alert.LocationHelper
import com.example.safeshadow.ui.TravelAlertActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * TravelModeManager
 *
 * Runs inside SafetyService. Manages two parallel checks:
 *
 * 1. ETA check — polls every 2 minutes. When ETA expires, launches TravelAlertActivity.
 * 2. Stillness check — if phone hasn't moved > 50m for 10 continuous minutes mid-journey,
 *    launches TravelAlertActivity.
 *
 * Design rules:
 * - Never shows dialogs directly (always via TravelAlertActivity)
 * - Never sends alerts directly (always through TravelAlertActivity, which calls AlertManager)
 * - Cleans up completely on stop()
 */
class TravelModeManager(private val context: Context) {

    companion object {
        private const val TAG = "TravelModeManager"

        // How often we poll location during travel (2 minutes — battery friendly)
        private const val LOCATION_POLL_INTERVAL_MS = 2 * 60 * 1000L

        // How long phone must be still before triggering a stillness check (10 minutes)
        private const val STILLNESS_THRESHOLD_MS = 10 * 60 * 1000L

        // Radius within which movement is considered "still" (50 metres — city travel)
        private const val STILLNESS_RADIUS_M = 50f

        // Grace period at start — don't check stillness for first 5 minutes
        // (user may still be at home, gathering things, saying goodbye, etc.)
        private const val STILLNESS_GRACE_PERIOD_MS = 5 * 60 * 1000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track whether a TravelAlert is currently shown — prevent stacking dialogs
    private var alertShowing = false

    // Runnable that fires every LOCATION_POLL_INTERVAL_MS
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!PrefsHelper.isTravelActive(context)) {
                Log.d(TAG, "Travel no longer active — stopping poll")
                return
            }
            checkTravelStatus()
            // Schedule next poll
            handler.postDelayed(this, LOCATION_POLL_INTERVAL_MS)
        }
    }

    /** Call this when user starts travel mode */
    fun start() {
        Log.d(TAG, "TravelModeManager started")
        alertShowing = false
        // First poll after 1 minute (give user time to start moving)
        handler.postDelayed(pollRunnable, 60 * 1000L)
    }

    /** Call this when travel mode is stopped (arrival confirmed or manual stop) */
    fun stop() {
        Log.d(TAG, "TravelModeManager stopped")
        handler.removeCallbacks(pollRunnable)
        scope.cancel()
        alertShowing = false
    }

    /** Call from TravelAlertActivity when an alert is dismissed (safe confirmed or extended) */
    fun onAlertDismissed() {
        alertShowing = false
    }

    // ─── Core check ─────────────────────────────────────────────────────────────

    private fun checkTravelStatus() {
        if (alertShowing) {
            Log.d(TAG, "Alert already showing — skipping check")
            return
        }

        val etaEndTime = PrefsHelper.getTravelEtaEndTime(context)
        val now = System.currentTimeMillis()

        // ETA expired?
        if (now >= etaEndTime) {
            Log.d(TAG, "ETA expired — launching TravelAlertActivity (ETA reason)")
            launchTravelAlert(TravelAlertActivity.REASON_ETA_EXPIRED)
            return
        }

        // Not yet in stillness grace period?
        val startTime = PrefsHelper.getTravelStartTime(context)
        val timeSinceStart = now - startTime
        if (timeSinceStart < STILLNESS_GRACE_PERIOD_MS) {
            Log.d(TAG, "In grace period — skipping stillness check")
            return
        }

        // Check current location for stillness
        scope.launch {
            checkStillness()
        }
    }

    // ─── Stillness detection ─────────────────────────────────────────────────────

    private suspend fun checkStillness() {
        try {
            val location = LocationHelper.getCurrentLocationSuspend(context) ?: run {
                Log.w(TAG, "Could not get location for stillness check")
                return
            }

            val now = System.currentTimeMillis()
            val lastLat = PrefsHelper.getLastKnownLat(context)
            val lastLng = PrefsHelper.getLastKnownLng(context)
            val lastTime = PrefsHelper.getLastKnownLocTime(context)

            // First location reading — just save it, nothing to compare against yet
            if (lastTime == 0L) {
                PrefsHelper.saveLastKnownLocation(context, location.latitude, location.longitude, now)
                Log.d(TAG, "First location saved: ${location.latitude}, ${location.longitude}")
                return
            }

            // Calculate distance from last known position
            val lastLocation = Location("last").apply {
                latitude = lastLat
                longitude = lastLng
            }
            val distanceMoved = location.distanceTo(lastLocation)

            Log.d(TAG, "Distance moved since last check: ${distanceMoved}m")

            if (distanceMoved > STILLNESS_RADIUS_M) {
                // Phone has moved — reset stillness clock
                Log.d(TAG, "Phone moved ${distanceMoved}m — updating position, resetting stillness clock")
                PrefsHelper.saveLastKnownLocation(context, location.latitude, location.longitude, now)
            } else {
                // Phone hasn't moved much — how long has it been still?
                val stillForMs = now - lastTime
                Log.d(TAG, "Phone still for ${stillForMs / 1000}s (threshold: ${STILLNESS_THRESHOLD_MS / 1000}s)")

                if (stillForMs >= STILLNESS_THRESHOLD_MS) {
                    Log.d(TAG, "Stillness threshold exceeded — launching TravelAlertActivity")
                    // Reset the stillness clock so we don't fire again immediately
                    PrefsHelper.saveLastKnownLocation(context, location.latitude, location.longitude, now)
                    handler.post {
                        launchTravelAlert(TravelAlertActivity.REASON_STILLNESS)
                    }
                }
                // If still but not long enough — do nothing, keep monitoring
            }

        } catch (e: Exception) {
            Log.e(TAG, "Stillness check failed: ${e.message}")
        }
    }

    // ─── Launch safety check dialog ─────────────────────────────────────────────

    private fun launchTravelAlert(reason: String) {
        if (alertShowing) return
        alertShowing = true
        TravelAlertActivity.launch(context, reason)
    }
}