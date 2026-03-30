package com.example.safeshadow.alert

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.safeshadow.PrefsHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AlertManager {

    private const val TAG = "SafeShadow"

    // Main function — fetches location then sends SMS to all contacts
    fun sendSosAlert(context: Context, reason: String = "SOS") {
        // Use getContactNumbers which extracts just phone numbers
        val contacts = PrefsHelper.getContactNumbers(context)

        if (contacts.isEmpty()) {
            Log.w(TAG, "No emergency contacts set — alert not sent")
            return
        }

        LocationHelper.getLastLocation(
            context = context,
            onSuccess = { lat, lng ->
                val mapsLink = LocationHelper.buildMapsLink(lat, lng)
                val message = buildMessage(reason, mapsLink)
                sendSmsToAll(context, contacts, message)
            },
            onFailure = {
                Log.w(TAG, "Location unavailable — sending alert without location")
                val message = buildMessage(reason, null)
                sendSmsToAll(context, contacts, message)
            }
        )
    }

    private fun buildMessage(reason: String, mapsLink: String?): String {
        val timestamp = SimpleDateFormat(
            "dd/MM/yyyy HH:mm:ss",
            Locale.getDefault()
        ).format(Date())

        return if (mapsLink != null) {
            "🚨 SAFESHADOW ALERT 🚨\n" +
                    "I may be in danger! ($reason)\n" +
                    "Time: $timestamp\n" +
                    "My location: $mapsLink\n" +
                    "Please contact me immediately or call emergency services."
        } else {
            "🚨 SAFESHADOW ALERT 🚨\n" +
                    "I may be in danger! ($reason)\n" +
                    "Time: $timestamp\n" +
                    "Location unavailable.\n" +
                    "Please contact me immediately or call emergency services."
        }
    }

    private fun sendSmsToAll(
        context: Context,
        contacts: List<String>,
        message: String
    ) {
        // Check SMS permission
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "SMS permission not granted")
            return
        }

        val smsManager = context.getSystemService(SmsManager::class.java)

        contacts.forEach { number ->
            try {
                // divideMessage handles messages longer than 160 chars
                val parts = smsManager.divideMessage(message)
                smsManager.sendMultipartTextMessage(
                    number,
                    null,
                    parts,
                    null,
                    null
                )
                Log.d(TAG, "SMS sent to $number")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send SMS to $number: ${e.message}")
            }
        }
    }
}