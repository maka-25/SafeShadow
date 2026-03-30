package com.example.safeshadow

import android.content.Context
import android.content.SharedPreferences

object PrefsHelper {

    private const val PREFS_NAME = "safeshadow_prefs"
    private const val KEY_CONTACTS = "emergency_contacts"
    private const val KEY_SAFETY_MODE = "safety_mode_on"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ─── Contacts ──────────────────────────────────────────────────────────────

    // Save list of Contact objects as "Name|Phone,Name|Phone"
    fun saveContacts(context: Context, contacts: List<Contact>) {
        val raw = contacts.joinToString(",") { "${it.name}|${it.phone}" }
        prefs(context).edit().putString(KEY_CONTACTS, raw).apply()
    }

    fun getContacts(context: Context): List<Contact> {
        val raw = prefs(context).getString(KEY_CONTACTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",").mapNotNull {
            val parts = it.split("|")
            if (parts.size == 2) Contact(parts[0].trim(), parts[1].trim())
            else null
        }
    }

    // Returns just phone numbers for SMS sending
    fun getContactNumbers(context: Context): List<String> =
        getContacts(context).map { it.phone }

    // ─── Safety Mode ───────────────────────────────────────────────────────────

    fun setSafetyModeOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_SAFETY_MODE, on).apply()
    }

    fun isSafetyModeOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SAFETY_MODE, false)

    // ─── Battery Optimization ──────────────────────────────────────────────────

    fun setBatteryOptimizationAsked(context: Context) {
        prefs(context).edit().putBoolean("battery_opt_asked", true).apply()
    }

    fun wasBatteryOptimizationAsked(context: Context): Boolean =
        prefs(context).getBoolean("battery_opt_asked", false)

    // ─── Alert Cooldown ────────────────────────────────────────────────────────

    fun setLastAlertTime(context: Context) {
        prefs(context).edit()
            .putLong("last_alert_time", System.currentTimeMillis())
            .apply()
    }

    fun isAlertOnCooldown(context: Context, cooldownMs: Long = 15000L): Boolean {
        val last = prefs(context).getLong("last_alert_time", 0L)
        return System.currentTimeMillis() - last < cooldownMs
    }
}

// Simple data class for a contact
data class Contact(val name: String, val phone: String)