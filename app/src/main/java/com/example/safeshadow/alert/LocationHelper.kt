package com.example.safeshadow.alert

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

object LocationHelper {

    fun getLastLocation(
        context: Context,
        onSuccess: (lat: Double, lng: Double) -> Unit,
        onFailure: () -> Unit
    ) {
        // Check permission first
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            onFailure()
            return
        }

        val fusedClient = LocationServices.getFusedLocationProviderClient(context)

        // First try last known location (fast, no battery cost)
        fusedClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    onSuccess(location.latitude, location.longitude)
                } else {
                    // Last location unavailable — request fresh one
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

    // Builds a Google Maps link from coordinates
    fun buildMapsLink(lat: Double, lng: Double): String {
        return "https://maps.google.com/?q=$lat,$lng"
    }
}