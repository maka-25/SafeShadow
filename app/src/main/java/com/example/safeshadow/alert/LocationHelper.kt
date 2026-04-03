package com.example.safeshadow.alert

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object LocationHelper {

    fun getLastLocation(
        context: Context,
        onSuccess: (lat: Double, lng: Double) -> Unit,
        onFailure: () -> Unit
    ) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            onFailure()
            return
        }

        val fusedClient = LocationServices.getFusedLocationProviderClient(context)

        fusedClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    onSuccess(location.latitude, location.longitude)
                } else {
                    requestFreshLocation(context, fusedClient, onSuccess, onFailure)
                }
            }
            .addOnFailureListener {
                requestFreshLocation(context, fusedClient, onSuccess, onFailure)
            }
    }

    private fun requestFreshLocation(
        context: Context,
        fusedClient: FusedLocationProviderClient,
        onSuccess: (lat: Double, lng: Double) -> Unit,
        onFailure: () -> Unit
    ) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            onFailure()
            return
        }

        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(30000)
            .build()

        fusedClient.getCurrentLocation(request, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    onSuccess(location.latitude, location.longitude)
                } else {
                    onFailure()
                }
            }
            .addOnFailureListener {
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