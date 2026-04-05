package com.example.safeshadow.travel

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.safeshadow.PrefsHelper
import com.example.safeshadow.alert.AlertManager
import com.example.safeshadow.service.SafetyService
import com.example.safeshadow.ui.TravelAlertActivity

class TravelAlertReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TravelAlertReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.example.safeshadow.ACTION_TRAVEL_SAFE" -> handleSafe(context, intent)
            "com.example.safeshadow.ACTION_TRAVEL_HELP" -> handleHelp(context, intent)
            else -> Log.w(TAG, "Received unexpected action: ${intent.action}")
        }
    }

    private fun handleSafe(context: Context, intent: Intent) {
        val reason = intent.getStringExtra("travel_reason") ?: ""
        Log.d(TAG, "Travel safe action received: $reason")
        cancelTravelNotification(context)

        if (reason == TravelAlertActivity.REASON_ETA_EXPIRED) {
            PrefsHelper.stopTravel(context)
            context.startService(Intent(context, SafetyService::class.java).apply {
                action = SafetyService.ACTION_STOP_TRAVEL
            })
        }

        context.startService(Intent(context, SafetyService::class.java).apply {
            action = SafetyService.ACTION_TRAVEL_ALERT_DISMISSED
        })
    }

    private fun handleHelp(context: Context, intent: Intent) {
        val reason = intent.getStringExtra("travel_reason") ?: ""
        Log.d(TAG, "Travel help action received: $reason")
        cancelTravelNotification(context)

        AlertManager.sendSosAlert(context, "Travel Mode Alert — $reason")
        PrefsHelper.stopTravel(context)
        context.startService(Intent(context, SafetyService::class.java).apply {
            action = SafetyService.ACTION_STOP_TRAVEL
        })
        context.startService(Intent(context, SafetyService::class.java).apply {
            action = SafetyService.ACTION_TRAVEL_ALERT_DISMISSED
        })
    }

    private fun cancelTravelNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(2004)
    }
}
