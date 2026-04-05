package com.example.safeshadow.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.safeshadow.alert.AlertManager

class SafetyAlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val reason = intent.getStringExtra("extra_reason") ?: "Unknown"

        when (intent.action) {
            "com.example.safeshadow.ACTION_USER_SAFE" -> {
                // Cancel notification
                context.getSystemService(NotificationManager::class.java)
                    .cancel(SafetyService.SAFETY_ALERT_NOTIFICATION_ID)
                // Send cancel intent to service
                val cancelIntent = Intent(context, SafetyService::class.java).apply {
                    action = SafetyService.ACTION_CANCEL_ALERT
                }
                context.startService(cancelIntent)
                Log.d("SafeShadow", "User marked safe")
            }

            "com.example.safeshadow.ACTION_SEND_HELP" -> {
                // Cancel notification
                context.getSystemService(NotificationManager::class.java)
                    .cancel(SafetyService.SAFETY_ALERT_NOTIFICATION_ID)
                // Send cancel intent to service
                val cancelIntent = Intent(context, SafetyService::class.java).apply {
                    action = SafetyService.ACTION_CANCEL_ALERT
                }
                context.startService(cancelIntent)
                // Send SOS alert
                AlertManager.sendSosAlert(context, reason)
                Log.d("SafeShadow", "Send help triggered")
            }

            "com.example.safeshadow.ACTION_CANCEL_SOS" -> {
                // Cancel notification 2002
                context.getSystemService(NotificationManager::class.java).cancel(2002)
                // Send local broadcast to MainActivity
                LocalBroadcastManager.getInstance(context).sendBroadcast(
                    Intent("com.example.safeshadow.LOCAL_CANCEL_SOS")
                )
                Log.d("SafeShadow", "SOS cancellation requested")
            }
        }
    }
}
