package com.example.safeshadow.alert

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
    private const val LOCATION_TIMEOUT_MS = 6000L

    fun sendSosAlert(context: Context, reason: String = "SOS") {
        Log.d(TAG, "sendSosAlert called — reason: $reason")

        val appContext = context.applicationContext

        val contacts = PrefsHelper.getContactNumbers(appContext)
        if (contacts.isEmpty()) {
            Log.w(TAG, "No emergency contacts — alert not sent")
            showToast(appContext, "No emergency contacts set!")
            return
        }

        Log.d(TAG, "Contacts: ${contacts.size} — fetching location...")

        var alertDispatched = false
        val mainHandler = Handler(Looper.getMainLooper())

        val timeoutRunnable = Runnable {
            if (!alertDispatched) {
                alertDispatched = true
                Log.w(TAG, "Location timed out — sending without location")
                PrefsHelper.setLastAlertTime(appContext)
                val message = buildMessage(appContext, reason, null)
                sendSmsToAll(appContext, contacts, message)
            }
        }

        mainHandler.postDelayed(timeoutRunnable, LOCATION_TIMEOUT_MS)

        LocationHelper.getLastLocation(
            context = appContext,
            onSuccess = { lat, lng ->
                if (!alertDispatched) {
                    alertDispatched = true
                    mainHandler.removeCallbacks(timeoutRunnable)
                    Log.d(TAG, "Location received: $lat, $lng")
                    PrefsHelper.setLastAlertTime(appContext)
                    val mapsLink = LocationHelper.buildMapsLink(lat, lng)
                    val message = buildMessage(appContext, reason, mapsLink)
                    sendSmsToAll(appContext, contacts, message)
                }
            },
            onFailure = {
                if (!alertDispatched) {
                    alertDispatched = true
                    mainHandler.removeCallbacks(timeoutRunnable)
                    Log.w(TAG, "Location unavailable — sending without location")
                    PrefsHelper.setLastAlertTime(appContext)
                    val message = buildMessage(appContext, reason, null)
                    sendSmsToAll(appContext, contacts, message)
                }
            }
        )
    }

    // Message Builder

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

    // SMS Sender

    private fun sendSmsToAll(
        context: Context,
        contacts: List<String>,
        message: String
    ) {
        Log.d(TAG, "sendSmsToAll — contacts: ${contacts.size}")

        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "SMS permission not granted")
            showToast(context, "SMS permission not granted!")
            return
        }

        val smsManager = context.getSystemService(SmsManager::class.java)
        if (smsManager == null) {
            Log.e(TAG, "SmsManager is null")
            showToast(context, "SMS service unavailable")
            return
        }

        var sentCount = 0
        val totalCount = contacts.size

        contacts.forEachIndexed { index, number ->
            try {
                Log.d(TAG, "Sending to $number (index $index)")
                val parts = smsManager.divideMessage(message)

                val sentIntent = PendingIntent.getBroadcast(
                    context,
                    index,
                    Intent("com.example.safeshadow.SMS_SENT").apply {
                        setPackage(context.packageName)
                        putExtra("sms_index", index)
                        putExtra("sms_total", totalCount)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                val sentIntents = ArrayList<PendingIntent>().apply {
                    repeat(parts.size) { add(sentIntent) }
                }

                smsManager.sendMultipartTextMessage(
                    number, null, parts, sentIntents, null
                )

                Log.d(TAG, "SMS dispatched to $number")
                sentCount++

            } catch (e: Exception) {
                Log.e(TAG, "SMS failed to $number: ${e.message}")
            }
        }

        val toastMsg = if (sentCount > 0) "SOS sent to $sentCount contact(s)"
        else "Failed to send SOS — check contacts"

        Log.d(TAG, "Done — sent: $sentCount / $totalCount")
        showToast(context, toastMsg)
    }

    // Toast

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }
}