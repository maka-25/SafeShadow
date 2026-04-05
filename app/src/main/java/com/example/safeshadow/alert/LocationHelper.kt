package com.example.safeshadow.alert

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object LocationHelper {

    private const val TAG = "SafeShadow"
    private const val LOCATION_TIMEOUT_MS = 8000L

    fun getLastLocation(
        context: Context,
        onSuccess: (lat: Double, lng: Double) -> Unit,
        onFailure: () -> Unit
    ) {
        Log.d(TAG, "getLastLocation called")

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Location permission not granted")
            onFailure()
            return
        }

        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val handler = Handler(Looper.getMainLooper())
        var callbackInvoked = false

        val timeoutRunnable = Runnable {
            if (!callbackInvoked) {
                callbackInvoked = true
                Log.w(TAG, "Location request timed out after $LOCATION_TIMEOUT_MS ms")
                onFailure()
            }
        }

        handler.postDelayed(timeoutRunnable, LOCATION_TIMEOUT_MS)

        fun completeSuccess(lat: Double, lng: Double) {
            if (callbackInvoked) return
            callbackInvoked = true
            handler.removeCallbacks(timeoutRunnable)
            Log.d(TAG, "getLastLocation successful: $lat,$lng")
            onSuccess(lat, lng)
        }

        fun completeFailure(message: String) {
            if (callbackInvoked) return
            callbackInvoked = true
            handler.removeCallbacks(timeoutRunnable)
            Log.w(TAG, "getLastLocation failed: $message")
            onFailure()
        }

        fusedClient.lastLocation
            .addOnSuccessListener { location ->
                Log.d(TAG, "lastLocation returned: $location")
                if (location != null) {
                    completeSuccess(location.latitude, location.longitude)
                } else {
                    Log.d(TAG, "lastLocation was null — requesting fresh location")
                    requestFreshLocation(context, fusedClient, ::completeSuccess) {
                        completeFailure("fresh location unavailable")
                    }
                }
            }
            .addOnFailureListener { exception ->
                Log.w(TAG, "lastLocation failed: ${exception.message}")
                requestFreshLocation(context, fusedClient, ::completeSuccess) {
                    completeFailure("fresh location failed")
                }
            }
    }

    private fun requestFreshLocation(
        context: Context,
        fusedClient: FusedLocationProviderClient,
        onSuccess: (lat: Double, lng: Double) -> Unit,
        onFailure: () -> Unit
    ) {
        Log.d(TAG, "requestFreshLocation called")

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Location permission not granted for fresh location")
            onFailure()
            return
        }

        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(30000)
            .build()

        fusedClient.getCurrentLocation(request, null)
            .addOnSuccessListener { location ->
                Log.d(TAG, "getCurrentLocation returned: $location")
                if (location != null) {
                    onSuccess(location.latitude, location.longitude)
                } else {
                    Log.w(TAG, "getCurrentLocation returned null")
                    onFailure()
                }
            }
            .addOnFailureListener { exception ->
                Log.w(TAG, "getCurrentLocation failed: ${exception.message}")
                onFailure()
            }
    }

    /**
     * Suspend version used by TravelModeManager for coroutine-based stillness checks.
     * Returns a Location object or null if unavailable / permission denied.
     */
    suspend fun getCurrentLocationSuspend(context: Context): Location? =
        suspendCoroutine { continuation ->
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                continuation.resume(null)
                return@suspendCoroutine
            }

            val fusedClient = LocationServices.getFusedLocationProviderClient(context)

            // Try last known location first (fast, no battery cost)
            fusedClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) {
                        continuation.resume(location)
                    } else {
                        // Fall back to fresh location request
                        val request = CurrentLocationRequest.Builder()
                            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                            .setMaxUpdateAgeMillis(60000)
                            .build()

                        if (ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            continuation.resume(null)
                            return@addOnSuccessListener
                        }

                        fusedClient.getCurrentLocation(request, null)
                            .addOnSuccessListener { freshLocation ->
                                continuation.resume(freshLocation)
                            }
                            .addOnFailureListener {
                                continuation.resume(null)
                            }
                    }
                }
                .addOnFailureListener {
                    continuation.resume(null)
                }
        }

    fun buildMapsLink(lat: Double, lng: Double): String {
        return "https://maps.google.com/?q=$lat,$lng"
    }
}