package com.example.safeshadow.alert

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.safeshadow.PrefsHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AlertManager {

    private const val TAG = "SafeShadow"

    fun sendSosAlert(context: Context, reason: String = "SOS") {
        // Start global cooldown immediately
        PrefsHelper.setLastAlertTime(context)

        val contacts = PrefsHelper.getContactNumbers(context)
        if (contacts.isEmpty()) {
            Log.w(TAG, "No emergency contacts set — alert not sent")
            showToast(context, "No emergency contacts set!")
            return
        }

        LocationHelper.getLastLocation(
            context = context,
            onSuccess = { lat, lng ->
                val mapsLink = LocationHelper.buildMapsLink(lat, lng)
                val message = buildMessage(context, reason, mapsLink)
                sendSmsToAll(context, contacts, message)
            },
            onFailure = {
                Log.w(TAG, "Location unavailable — sending alert without location")
                val message = buildMessage(context, reason, null)
                sendSmsToAll(context, contacts, message)
            }
        )
    }

    // ─── Message Builder ──────────────────────────────────────────────────────────

    private fun buildMessage(context: Context, reason: String, mapsLink: String?): String {
        val timestamp = SimpleDateFormat(
            "dd/MM/yyyy HH:mm:ss", Locale.getDefault()
        ).format(Date())

        val customPrefix = PrefsHelper.getCustomSosMessage(context)
        val prefix = if (customPrefix.isNotBlank()) "$customPrefix\n\n" else ""

        return if (mapsLink != null) {
            "${prefix}SAFESHADOW ALERT\n" +
                    "I may be in danger! ($reason)\n" +
                    "Time: $timestamp\n" +
                    "My location: $mapsLink\n" +
                    "Please contact me immediately or call emergency services."
        } else {
            "${prefix}SAFESHADOW ALERT\n" +
                    "I may be in danger! ($reason)\n" +
                    "Time: $timestamp\n" +
                    "Location unavailable.\n" +
                    "Please contact me immediately or call emergency services."
        }
    }

    // ─── SMS Sender ──────────────────────────────────────────────────────────────

    private fun sendSmsToAll(
        context: Context,
        contacts: List<String>,
        message: String
    ) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "SMS permission not granted")
            showToast(context, "SMS permission not granted")
            return
        }

        val smsManager = context.getSystemService(SmsManager::class.java)
        var sentCount = 0
        val totalCount = contacts.size

        contacts.forEachIndexed { index, number ->
            try {
                val parts = smsManager.divideMessage(message)

                // Create sentIntent with SMS_SENT action
                val sentIntent = PendingIntent.getBroadcast(
                    context,
                    index,
                    android.content.Intent("com.example.safeshadow.SMS_SENT").apply {
                        setPackage(context.packageName)
                        putExtra("sms_index", index)
                        putExtra("sms_total", totalCount)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                // FIX: Use ArrayList instead of Array
                val sentIntents = ArrayList<PendingIntent>().apply {
                    repeat(parts.size) {
                        add(sentIntent)
                    }
                }

                smsManager.sendMultipartTextMessage(
                    number,
                    null,
                    parts,
                    sentIntents,
                    null
                )

                Log.d(TAG, "SMS sent to $number (index $index of $totalCount)")
                sentCount++

            } catch (e: Exception) {
                Log.e(TAG, "Failed to send SMS to $number: ${e.message}")
            }
        }

        val resultMessage = if (sentCount > 0) {
            "SMS sent to $sentCount contact(s)"
        } else {
            "Failed to send SMS"
        }

        showToast(context, resultMessage)
    }

    // ─── Toast helper ────────────────────────────────────────────────────────────

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}