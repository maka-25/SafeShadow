package com.example.safeshadow

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.safeshadow.service.SafetyService
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import com.safeshadow.R
import com.example.safeshadow.ui.SetupActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggleSafety: Button
    private lateinit var tvStatus: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            Toast.makeText(this,
                "Some permissions denied — alerts may not work",
                Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggleSafety = findViewById(R.id.btnToggleSafety)
        tvStatus = findViewById(R.id.tvStatus)

        requestAllPermissions()
        requestBatteryOptimizationExemption()

        btnToggleSafety.setOnClickListener {
            if (isSafetyServiceRunning()) {
                stopSafetyService()
            } else {
                startSafetyService()
            }
        }

        // TEMPORARY TEST BUTTON
        val btnTestShake = findViewById<Button>(R.id.btnTestShake)
        btnTestShake.setOnClickListener {
            if (!isSafetyServiceRunning()) {
                Toast.makeText(
                    this,
                    "Turn ON Safety Mode first before testing",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val intent = Intent(this, SafetyService::class.java).apply {
                action = SafetyService.ACTION_TEST_SHAKE
            }
            startService(intent)
        }

        // TEMPORARY TEST BUTTONS
        findViewById<Button>(R.id.btnTestFall).setOnClickListener {
            if (!isSafetyServiceRunning()) {
                Toast.makeText(this, "Turn ON Safety Mode first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, SafetyService::class.java).apply {
                action = SafetyService.ACTION_TEST_FALL
            }
            startService(intent)
        }

        findViewById<Button>(R.id.btnTestRunning).setOnClickListener {
            if (!isSafetyServiceRunning()) {
                Toast.makeText(this, "Turn ON Safety Mode first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, SafetyService::class.java).apply {
                action = SafetyService.ACTION_TEST_RUNNING
            }
            startService(intent)
        }

        findViewById<Button>(R.id.btnSetupContacts).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    // Called every time activity comes to foreground
    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun isSafetyServiceRunning(): Boolean {
        // Source of truth: SharedPreferences, not static variable
        return PrefsHelper.isSafetyModeOn(this)
    }

    private fun startSafetyService() {
        // Prevent double-start
        if (isSafetyServiceRunning()) {
            updateUI()
            return
        }
        PrefsHelper.setSafetyModeOn(this, true)
        val intent = Intent(this, SafetyService::class.java)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Safety Mode ON", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun stopSafetyService() {
        // Prevent double-stop
        if (!isSafetyServiceRunning()) {
            updateUI()
            return
        }
        PrefsHelper.setSafetyModeOn(this, false)
        val intent = Intent(this, SafetyService::class.java).apply {
            action = SafetyService.ACTION_STOP_SERVICE
        }
        startService(intent)
        Toast.makeText(this, "Safety Mode OFF", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun updateUI() {
        if (isSafetyServiceRunning()) {
            tvStatus.text = "🟢 Safety Mode: ON"
            btnToggleSafety.text = "Turn OFF Safety Mode"
            btnToggleSafety.backgroundTintList =
                ColorStateList.valueOf(
                    Color.parseColor("#388E3C") // green
                )
        } else {
            tvStatus.text = "🔴 Safety Mode: OFF"
            btnToggleSafety.text = "Turn ON Safety Mode"
            btnToggleSafety.backgroundTintList =
                ColorStateList.valueOf(
                    Color.parseColor("#B71C1C") // red
                )
        }
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Permissions Needed")
                .setMessage(
                    "SafeShadow needs the following to protect you:\n\n" +
                            "📍 Location — to include your position in SOS alerts\n\n" +
                            "💬 SMS — to send emergency messages to your contacts\n\n" +
                            "🔔 Notifications — to show Safety Mode is active\n\n" +
                            "You can still use the app if you deny, but alerts won't work."
                )
                .setPositiveButton("Grant Permissions") { _, _ ->
                    permissionLauncher.launch(notGranted.toTypedArray())
                }
                .setNegativeButton("Skip for Now", null)
                .show()
        }
    }

    private fun requestBatteryOptimizationExemption() {
        // Only ask once ever — don't show again if already asked
        if (PrefsHelper.wasBatteryOptimizationAsked(this)) return

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("One More Step")
                .setMessage(
                    "To keep Safety Mode running reliably in the background, " +
                            "please disable battery optimization for SafeShadow.\n\n" +
                            "Tap OK → find SafeShadow → select 'Don't optimize'"
                )
                .setPositiveButton("OK") { _, _ ->
                    PrefsHelper.setBatteryOptimizationAsked(this)
                    try {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        ).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        // Some devices don't support this — fail silently
                    }
                }
                .setNegativeButton("Skip") { _, _ ->
                    // Mark as asked even if skipped so it never shows again
                    PrefsHelper.setBatteryOptimizationAsked(this)
                }
                .show()
        } else {
            // Already exempted — mark as asked so we never check again
            PrefsHelper.setBatteryOptimizationAsked(this)
        }
    }
}