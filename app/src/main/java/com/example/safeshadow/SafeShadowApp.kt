package com.example.safeshadow

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.appcompat.app.AppCompatDelegate

class SafeShadowApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Apply saved theme before any activity is created
        applyTheme()

        // Create app-level notification channel (used by SafetyService)
        createNotificationChannel()
    }

    private fun applyTheme() {
        val mode = when (PrefsHelper.getTheme(this)) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark"  -> AppCompatDelegate.MODE_NIGHT_YES
            else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "SAFE_CHANNEL",
            "Safety Alerts",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "SafeShadow background safety monitoring"
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}