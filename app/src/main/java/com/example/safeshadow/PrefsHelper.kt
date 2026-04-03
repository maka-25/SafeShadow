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

    // ─── Travel Mode ───────────────────────────────────────────────────────────

    /** Save travel session when user starts travel mode */
    fun startTravel(
        context: Context,
        destination: String,
        etaMinutes: Int,
        destLat: Double,
        destLng: Double
    ) {
        val startTime = System.currentTimeMillis()
        val etaEndTime = startTime + (etaMinutes * 60 * 1000L)
        prefs(context).edit()
            .putBoolean("travel_active", true)
            .putString("travel_destination", destination)
            .putLong("travel_start_time", startTime)
            .putLong("travel_eta_end_time", etaEndTime)
            .putFloat("travel_dest_lat", destLat.toFloat())
            .putFloat("travel_dest_lng", destLng.toFloat())
            .putInt("travel_extensions_used", 0)
            .apply()
    }

    /** Extend ETA by given minutes (max 3 extensions enforced in UI) */
    fun extendTravel(context: Context, extraMinutes: Int) {
        val current = prefs(context).getLong("travel_eta_end_time", System.currentTimeMillis())
        val extensions = prefs(context).getInt("travel_extensions_used", 0)
        prefs(context).edit()
            .putLong("travel_eta_end_time", current + (extraMinutes * 60 * 1000L))
            .putInt("travel_extensions_used", extensions + 1)
            .apply()
    }

    /** Stop / clear travel session */
    fun stopTravel(context: Context) {
        prefs(context).edit()
            .putBoolean("travel_active", false)
            .putString("travel_destination", "")
            .putLong("travel_start_time", 0L)
            .putLong("travel_eta_end_time", 0L)
            .putFloat("travel_dest_lat", 0f)
            .putFloat("travel_dest_lng", 0f)
            .putInt("travel_extensions_used", 0)
            .apply()
    }

    fun isTravelActive(context: Context): Boolean =
        prefs(context).getBoolean("travel_active", false)

    fun getTravelDestination(context: Context): String =
        prefs(context).getString("travel_destination", "Unknown") ?: "Unknown"

    fun getTravelEtaEndTime(context: Context): Long =
        prefs(context).getLong("travel_eta_end_time", 0L)

    fun getTravelStartTime(context: Context): Long =
        prefs(context).getLong("travel_start_time", 0L)

    fun getTravelDestLat(context: Context): Double =
        prefs(context).getFloat("travel_dest_lat", 0f).toDouble()

    fun getTravelDestLng(context: Context): Double =
        prefs(context).getFloat("travel_dest_lng", 0f).toDouble()

    fun getTravelExtensionsUsed(context: Context): Int =
        prefs(context).getInt("travel_extensions_used", 0)

    /** Track last known location for stillness detection */
    fun saveLastKnownLocation(context: Context, lat: Double, lng: Double, timeMs: Long) {
        prefs(context).edit()
            .putFloat("last_loc_lat", lat.toFloat())
            .putFloat("last_loc_lng", lng.toFloat())
            .putLong("last_loc_time", timeMs)
            .apply()
    }

    fun getLastKnownLat(context: Context): Double =
        prefs(context).getFloat("last_loc_lat", 0f).toDouble()

    fun getLastKnownLng(context: Context): Double =
        prefs(context).getFloat("last_loc_lng", 0f).toDouble()

    fun getLastKnownLocTime(context: Context): Long =
        prefs(context).getLong("last_loc_time", 0L)
}

// Simple data class for a contact
data class Contact(val name: String, val phone: String)