package com.example.safeshadow.alert

import android.app.Activity
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

        // Track per-contact results, not per-part
        // Key = contact index, Value = true if at least one part succeeded
        private val contactResults = mutableMapOf<Int, Boolean>()
        private var totalContacts = 0
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        Log.d(TAG, "onReceive called — resultCode: $resultCode")

        val smsIndex = intent.getIntExtra("sms_index", -1)
        val smsTotal = intent.getIntExtra("sms_total", 0)

        if (smsIndex == -1 || smsTotal == 0) {
            Log.w(TAG, "Missing extras — sms_index: $smsIndex, sms_total: $smsTotal")
            return
        }

        // Set total contacts on first valid receipt
        if (totalContacts == 0) {
            totalContacts = smsTotal
            Log.d(TAG, "SMS batch started — total contacts: $totalContacts")
        }

        // For each contact, mark success if ANY part succeeds
        // If result is OK and not already marked success, mark it
        val currentSuccess = contactResults[smsIndex] ?: false
        if (resultCode == Activity.RESULT_OK) {
            contactResults[smsIndex] = true
            Log.d(TAG, "SMS part succeeded for contact $smsIndex")
        } else {
            // Only mark failure if not already marked success by a previous part
            if (!currentSuccess) {
                contactResults[smsIndex] = false
            }
            Log.w(TAG, "SMS part failed for contact $smsIndex — resultCode: $resultCode")
        }

        // Check if all contacts have at least one result recorded
        // We check size because multiple parts per contact all report same index
        if (contactResults.size == totalContacts) {
            val failedContacts = contactResults.values.count { !it }
            Log.d(TAG, "All contacts processed — failed: $failedContacts / $totalContacts")

            if (failedContacts == totalContacts) {
                // Every single contact failed
                showFailureNotification(context)
            } else if (failedContacts > 0) {
                // Some failed
                Log.w(TAG, "$failedContacts contact(s) did not receive SMS")
            }

            // Reset for next batch
            contactResults.clear()
            totalContacts = 0
        }
    }

    private fun showFailureNotification(context: Context) {
        Log.w(TAG, "All SMS failed — showing failure notification")

        val notification = NotificationCompat.Builder(context, "safety_alert_channel_v2")
            .setContentTitle("⚠️ SOS Failed to Send")
            .setContentText("Could not reach emergency contacts. Check signal and contact numbers.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java).notify(2003, notification)
    }
}