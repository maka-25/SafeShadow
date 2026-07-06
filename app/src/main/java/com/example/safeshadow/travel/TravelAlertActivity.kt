package com.example.safeshadow.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.safeshadow.PrefsHelper
import com.example.safeshadow.R
import com.example.safeshadow.alert.AlertManager
import com.example.safeshadow.service.SafetyService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TravelAlertActivity
 *
 * Transparent activity shown as a safety check during Travel Mode.
 * Works exactly like SafetyAlertActivity but with travel-specific logic:
 *
 * - Shows reason (ETA expired / phone was still / manual check)
 * - 30 second countdown (more generous than 15s since travel context)
 * - I AM SAFE → dismisses, travel continues monitoring
 * - EXTEND TIME → extends ETA by 15 minutes, dismisses (max 3 extensions)
 * - I NEED HELP / No response → sends alert immediately, stops travel mode
 *
 * Must never be shown from Service context directly — always use launch().
 */
class TravelAlertActivity : AppCompatActivity() {

    companion object {
        const val REASON_ETA_EXPIRED = "ETA_EXPIRED"
        const val REASON_STILLNESS = "STILLNESS"

        private const val EXTRA_REASON = "travel_alert_reason"
        private const val COUNTDOWN_SECONDS = 30
        private const val EXTEND_MINUTES = 15
        private const val MAX_EXTENSIONS = 3
        private const val TAG = "TravelAlertActivity"

        fun launch(context: Context, reason: String) {
            val intent = Intent(context, TravelAlertActivity::class.java).apply {
                putExtra(EXTRA_REASON, reason)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        }
    }

    private var countDownTimer: CountDownTimer? = null
    private lateinit var tvCountdown: TextView
    private lateinit var tvMessage: TextView
    private lateinit var tvDestination: TextView
    private lateinit var btnSafe: Button
    private lateinit var btnHelp: Button
    private lateinit var btnExtend: Button
    private var reason: String = REASON_ETA_EXPIRED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lock screen - same as SafetyAlertActivity
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        setContentView(R.layout.activity_travel_alert)

        tvMessage = findViewById(R.id.tvTravelAlertMessage)
        tvCountdown = findViewById(R.id.tvTravelCountdown)
        tvDestination = findViewById(R.id.tvTravelDestination)
        btnSafe = findViewById(R.id.btnTravelSafe)
        btnHelp = findViewById(R.id.btnTravelHelp)
        btnExtend = findViewById(R.id.btnTravelExtend)

        reason = intent.getStringExtra(EXTRA_REASON) ?: REASON_ETA_EXPIRED

        setupUI()
        startCountdown()
    }

    private fun setupUI() {
        val destination = PrefsHelper.getTravelDestination(this)
        val extensionsUsed = PrefsHelper.getTravelExtensionsUsed(this)
        val etaTime = PrefsHelper.getTravelEtaEndTime(this)
        val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(etaTime))

        tvDestination.text = "📍 Destination: $destination"

        when (reason) {
            REASON_ETA_EXPIRED -> {
                tvMessage.text =
                    "⏰ Your expected arrival time ($timeStr) has passed.\n\nAre you safe?"
            }
            REASON_STILLNESS -> {
                tvMessage.text =
                    "📵 Your phone hasn't moved for 10 minutes.\n\nAre you safe?"
            }
            else -> {
                tvMessage.text = "SafeShadow safety check.\n\nAre you safe?"
            }
        }

        // Show extend button only if ETA expired and extensions remaining
        if (reason == REASON_ETA_EXPIRED && extensionsUsed < MAX_EXTENSIONS) {
            val remaining = MAX_EXTENSIONS - extensionsUsed
            btnExtend.visibility = android.view.View.VISIBLE
            btnExtend.text = "⏱ Extend +$EXTEND_MINUTES min ($remaining left)"
        } else {
            btnExtend.visibility = android.view.View.GONE
        }

        btnSafe.setOnClickListener { onUserSafe() }
        btnHelp.setOnClickListener { onUserNeedsHelp() }
        btnExtend.setOnClickListener { onUserExtends() }
    }

    private fun startCountdown() {
        countDownTimer = object : CountDownTimer(
            (COUNTDOWN_SECONDS * 1000).toLong(), 1000
        ) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = millisUntilFinished / 1000
                tvCountdown.text = "Sending alert in $secondsLeft seconds..."

                // Turn red in last 10 seconds
                if (secondsLeft <= 10) {
                    tvCountdown.setTextColor(Color.RED)
                }
            }

            override fun onFinish() {
                Log.d(TAG, "Countdown finished — auto sending alert")
                tvCountdown.text = "Sending alert now..."
                sendAlertAndFinish("No response to safety check")
            }
        }.start()
    }

    // Button handlers

    private fun onUserSafe() {
        Log.d(TAG, "User confirmed safe")
        countDownTimer?.cancel()

        // If ETA expired and they confirm safe -> travel mode ends
        if (reason == REASON_ETA_EXPIRED) {
            stopTravelMode()
        }
        // If stillness -> just continue monitoring
        notifyManagerAlertDismissed()
        finish()
    }

    private fun onUserExtends() {
        Log.d(TAG, "User extended ETA by $EXTEND_MINUTES minutes")
        countDownTimer?.cancel()
        PrefsHelper.extendTravel(this, EXTEND_MINUTES)

        // Update service notification with new ETA
        val intent = Intent(this, SafetyService::class.java).apply {
            action = SafetyService.ACTION_TRAVEL_EXTENDED
        }
        startService(intent)

        notifyManagerAlertDismissed()
        finish()
    }

    private fun onUserNeedsHelp() {
        Log.d(TAG, "User pressed I NEED HELP")
        countDownTimer?.cancel()
        sendAlertAndFinish("User pressed I NEED HELP during travel")
    }

    // Alert sending

    private fun sendAlertAndFinish(alertReason: String) {
        val destination = PrefsHelper.getTravelDestination(this)
        val startTime = PrefsHelper.getTravelStartTime(this)
        val startTimeStr = SimpleDateFormat(
            "hh:mm a", Locale.getDefault()
        ).format(Date(startTime))

        // Full travel alert reason for SMS
        val fullReason = "Travel Mode Alert\n" +
                "Destination: $destination\n" +
                "Travel started: $startTimeStr\n" +
                "Reason: $alertReason"

        AlertManager.sendSosAlert(this, reason = fullReason)

        // Stop travel mode - auto stop after alert
        stopTravelMode()
        notifyManagerAlertDismissed()
        finish()
    }

    private fun stopTravelMode() {
        PrefsHelper.stopTravel(this)
        val intent = Intent(this, SafetyService::class.java).apply {
            action = SafetyService.ACTION_STOP_TRAVEL
        }
        startService(intent)
    }

    private fun notifyManagerAlertDismissed() {
        // Tell SafetyService to reset the alertShowing flag in TravelModeManager
        val intent = Intent(this, SafetyService::class.java).apply {
            action = SafetyService.ACTION_TRAVEL_ALERT_DISMISSED
        }
        startService(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }

    // Prevent back button from dismissing without a choice
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Do nothing - user must tap a button
    }
}