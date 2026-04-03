package com.example.safeshadow.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.example.safeshadow.PrefsHelper
import com.example.safeshadow.R
import com.example.safeshadow.service.SafetyService

class SettingsActivity : AppCompatActivity() {

    private lateinit var switchFallDetection: Switch
    private lateinit var switchRunningDetection: Switch
    private lateinit var switchShakeConfirmation: Switch
    private lateinit var radioGroupCooldown: RadioGroup
    private lateinit var radioCooldown15: RadioButton
    private lateinit var radioCooldown30: RadioButton
    private lateinit var radioCooldown60: RadioButton
    private lateinit var radioCooldown120: RadioButton
    private lateinit var etCustomSosMessage: EditText
    private lateinit var radioGroupTheme: RadioGroup
    private lateinit var radioThemeSystem: RadioButton
    private lateinit var radioThemeLight: RadioButton
    private lateinit var radioThemeDark: RadioButton
    private lateinit var btnSaveSettings: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Show back arrow in toolbar
        supportActionBar?.apply {
            title = "Settings"
            setDisplayHomeAsUpEnabled(true)
        }

        bindViews()
        loadCurrentSettings()
        setupSaveButton()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ─── View Binding ────────────────────────────────────────────────────────────

    private fun bindViews() {
        switchFallDetection    = findViewById(R.id.switchFallDetection)
        switchRunningDetection = findViewById(R.id.switchRunningDetection)
        switchShakeConfirmation= findViewById(R.id.switchShakeConfirmation)
        radioGroupCooldown     = findViewById(R.id.radioGroupCooldown)
        radioCooldown15        = findViewById(R.id.radioCooldown15)
        radioCooldown30        = findViewById(R.id.radioCooldown30)
        radioCooldown60        = findViewById(R.id.radioCooldown60)
        radioCooldown120       = findViewById(R.id.radioCooldown120)
        etCustomSosMessage     = findViewById(R.id.etCustomSosMessage)
        radioGroupTheme        = findViewById(R.id.radioGroupTheme)
        radioThemeSystem       = findViewById(R.id.radioThemeSystem)
        radioThemeLight        = findViewById(R.id.radioThemeLight)
        radioThemeDark         = findViewById(R.id.radioThemeDark)
        btnSaveSettings        = findViewById(R.id.btnSaveSettings)
    }

    // ─── Load saved values into UI ───────────────────────────────────────────────

    private fun loadCurrentSettings() {
        // Detection toggles
        switchFallDetection.isChecked    = PrefsHelper.isFallDetectionEnabled(this)
        switchRunningDetection.isChecked = PrefsHelper.isRunningDetectionEnabled(this)
        switchShakeConfirmation.isChecked= PrefsHelper.isShakeConfirmationEnabled(this)

        // Cooldown radio
        val cooldownSeconds = PrefsHelper.getAlertCooldownMs(this) / 1000
        when (cooldownSeconds.toInt()) {
            15  -> radioCooldown15.isChecked  = true
            30  -> radioCooldown30.isChecked  = true
            60  -> radioCooldown60.isChecked  = true
            120 -> radioCooldown120.isChecked = true
            else -> radioCooldown15.isChecked = true
        }

        // Custom SOS message
        etCustomSosMessage.setText(PrefsHelper.getCustomSosMessage(this))

        // Theme radio
        when (PrefsHelper.getTheme(this)) {
            "light"  -> radioThemeLight.isChecked  = true
            "dark"   -> radioThemeDark.isChecked   = true
            else     -> radioThemeSystem.isChecked  = true
        }
    }

    // ─── Save button ─────────────────────────────────────────────────────────────

    private fun setupSaveButton() {
        btnSaveSettings.setOnClickListener {
            saveAllSettings()
        }
    }

    private fun saveAllSettings() {
        // ── Detection toggles ────────────────────────────────────────────────────
        PrefsHelper.setFallDetectionEnabled(this, switchFallDetection.isChecked)
        PrefsHelper.setRunningDetectionEnabled(this, switchRunningDetection.isChecked)
        PrefsHelper.setShakeConfirmationEnabled(this, switchShakeConfirmation.isChecked)

        // ── Cooldown ─────────────────────────────────────────────────────────────
        val cooldownSeconds = when (radioGroupCooldown.checkedRadioButtonId) {
            R.id.radioCooldown30  -> 30
            R.id.radioCooldown60  -> 60
            R.id.radioCooldown120 -> 120
            else                  -> 15
        }
        PrefsHelper.setAlertCooldownSeconds(this, cooldownSeconds)

        // ── Custom SOS message ───────────────────────────────────────────────────
        val customMsg = etCustomSosMessage.text.toString()
        if (customMsg.length > 200) {
            etCustomSosMessage.error = "Message too long (max 200 characters)"
            return
        }
        PrefsHelper.setCustomSosMessage(this, customMsg)

        // ── Theme ────────────────────────────────────────────────────────────────
        val selectedTheme = when (radioGroupTheme.checkedRadioButtonId) {
            R.id.radioThemeLight -> "light"
            R.id.radioThemeDark  -> "dark"
            else                 -> "system"
        }
        val previousTheme = PrefsHelper.getTheme(this)
        PrefsHelper.setTheme(this, selectedTheme)

        // Apply theme immediately if it changed
        if (selectedTheme != previousTheme) {
            applyTheme(selectedTheme)
        }

        // ── Notify SafetyService to reload detector settings ─────────────────────
        if (PrefsHelper.isSafetyModeOn(this)) {
            val intent = Intent(this, SafetyService::class.java).apply {
                action = SafetyService.ACTION_RELOAD_SETTINGS
            }
            startService(intent)
        }

        Toast.makeText(this, "Settings saved ✓", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun applyTheme(theme: String) {
        val mode = when (theme) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark"  -> AppCompatDelegate.MODE_NIGHT_YES
            else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}