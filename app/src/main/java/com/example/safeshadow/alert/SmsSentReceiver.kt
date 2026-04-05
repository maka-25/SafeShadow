package com.example.safeshadow.alert

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.safeshadow.R

class SmsSentReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsSentReceiver"
        private var failureCount = 0
        private var totalCount = 0
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val smsIndex = intent.getIntExtra("sms_index", -1)
        val smsTotal = intent.getIntExtra("sms_total", 0)

        // Track total count on first receipt
        if (totalCount == 0) {
            totalCount = smsTotal
            Log.d(TAG, "SMS batch starting: $totalCount contacts")
        }

        // Check result code
        if (resultCode != android.app.Activity.RESULT_OK) {
            failureCount++
            Log.w(TAG, "SMS delivery failed for contact $smsIndex. Failures: $failureCount/$totalCount")
        } else {
            Log.d(TAG, "SMS delivered successfully for contact $smsIndex")
        }

        // If all SMS have been processed and all failed, show notification
        if (smsIndex + 1 == totalCount) {
            if (failureCount == totalCount) {
                showFailureNotification(context)
            }
            // Reset counters for next batch
            failureCount = 0
            totalCount = 0
        }
    }

    private fun showFailureNotification(context: Context) {
        Log.w(TAG, "All SMS failed to send — showing failure notification")

        val notification = NotificationCompat.Builder(context, "safety_alert_channel")
            .setContentTitle("SOS Failed to Send")
            .setContentText("Could not reach emergency contacts. Check signal and contact numbers.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java).notify(2003, notification)
    }
}
