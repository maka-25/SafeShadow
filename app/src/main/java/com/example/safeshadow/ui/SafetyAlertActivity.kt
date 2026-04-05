package com.example.safeshadow.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.example.safeshadow.PrefsHelper
import com.example.safeshadow.alert.AlertManager
import com.example.safeshadow.service.SafetyService

class SafetyAlertActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_REASON = "reason"
        const val TIMEOUT_MS = 15000L

        fun launch(context: Context, reason: String) {
            // Check global cooldown before launching
            if (PrefsHelper.isAlertOnCooldown(context, TIMEOUT_MS)) return

            val intent = Intent(context, SafetyAlertActivity::class.java).apply {
                putExtra(EXTRA_REASON, reason)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        }
    }

    private var countDownTimer: CountDownTimer? = null
    private var dialogShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lock screen even when phone is locked
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        // Prevent showing twice
        if (dialogShown) return
        dialogShown = true

        val reason = intent.getStringExtra(EXTRA_REASON) ?: "Suspicious activity"
        showSafetyDialog(reason)
    }

    // Prevent relaunching if already showing
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Do nothing — dialog already showing
    }

    private fun showSafetyDialog(reason: String) {
        var secondsLeft = (TIMEOUT_MS / 1000).toInt()

        val dialog = AlertDialog.Builder(this)
            .setTitle("⚠️ Are You Safe?")
            .setMessage(buildMessage(reason, secondsLeft))
            .setCancelable(false)
            .setPositiveButton("✅ YES, I'm Safe") { _, _ ->
                countDownTimer?.cancel()
                // Reset cooldown — user confirmed safe
                finish()
            }
            .setNegativeButton("🆘 NO, Send Help") { _, _ ->
                countDownTimer?.cancel()
                triggerAlert(reason)
                finish()
            }
            .create()

        dialog.show()

        countDownTimer = object : CountDownTimer(TIMEOUT_MS, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                secondsLeft = (millisUntilFinished / 1000).toInt()
                if (dialog.isShowing) {
                    dialog.setMessage(buildMessage(reason, secondsLeft))
                }
            }

            override fun onFinish() {
                if (!isFinishing) {
                    triggerAlert("$reason (No Response)")
                    finish()
                }
            }
        }.start()
    }

    private fun triggerAlert(reason: String) {
        // Set cooldown before sending alert
        PrefsHelper.setLastAlertTime(this)
        // Update notification to show alert is being sent
        notifyServiceSosTriggered()
        // Send SMS with location
        AlertManager.sendSosAlert(this, reason)
    }

    private fun notifyServiceSosTriggered() {
        // Tell the service to update notification
        val intent = Intent(this, SafetyService::class.java).apply {
            action = SafetyService.ACTION_SOS_TRIGGERED
        }
        startService(intent)
    }

    private fun buildMessage(reason: String, secondsLeft: Int): String {
        return "SafeShadow detected: $reason\n\n" +
                "Are you okay?\n\n" +
                "If you do not respond in $secondsLeft seconds,\n" +
                "an emergency alert will be sent automatically."
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }
}