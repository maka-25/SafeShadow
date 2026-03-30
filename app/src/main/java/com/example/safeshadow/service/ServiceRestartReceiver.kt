package com.example.safeshadow.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.safeshadow.PrefsHelper

class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (
            action == "com.safeshadow.RESTART_SERVICE" ||
            action == Intent.ACTION_BOOT_COMPLETED
        ) {
            if (PrefsHelper.isSafetyModeOn(context)) {
                val serviceIntent = Intent(context, SafetyService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}