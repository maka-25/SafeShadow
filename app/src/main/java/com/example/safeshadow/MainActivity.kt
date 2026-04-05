package com.example.safeshadow

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.safeshadow.service.SafetyService
import com.example.safeshadow.ui.SetupActivity
import com.example.safeshadow.ui.SettingsActivity
import com.example.safeshadow.ui.TravelModeActivity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggleSafety: Button
    private lateinit var tvStatus: TextView
    private lateinit var btnTravelMode: Button
    private lateinit var btnSOS: Button

    // SOS countdown state
    private var sosCountdownTimer: CountDownTimer? = null
    private var sosHandler: Handler? = null
    private var localBroadcastReceiver: BroadcastReceiver? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            Toast.makeText(
                this,
                "Some permissions denied — alerts may not work",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Use the app toolbar so gear icon appears in the top-right corner
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayShowTitleEnabled(false)

        btnToggleSafety = findViewById(R.id.btnToggleSafety)
        tvStatus        = findViewById(R.id.tvStatus)
        btnTravelMode   = findViewById(R.id.btnTravelMode)
        btnSOS          = findViewById(R.id.btnSOS)

        requestAllPermissions()
        requestBatteryOptimizationExemption()

        // Register LocalBroadcastManager receiver for SOS cancellation
        localBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.example.safeshadow.LOCAL_CANCEL_SOS") {
                    cancelSosCountdown()
                }
            }
        }
        val filter = android.content.IntentFilter("com.example.safeshadow.LOCAL_CANCEL_SOS")
        LocalBroadcastManager.getInstance(this).registerReceiver(localBroadcastReceiver!!, filter)

        btnToggleSafety.setOnClickListener {
            if (isSafetyServiceRunning()) stopSafetyService() else startSafetyService()
        }

        btnTravelMode.setOnClickListener {
            if (!isSafetyServiceRunning()) {
                Toast.makeText(
                    this,
                    "Please turn ON Safety Mode before using Travel Mode",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, TravelModeActivity::class.java))
        }

        findViewById<Button>(R.id.btnSetupContacts).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        // ── Manual SOS ────────────────────────────────────────────────────────────
        btnSOS.setOnClickListener {
            if (!isSafetyServiceRunning()) {
                Toast.makeText(this, "Turn ON Safety Mode first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (PrefsHelper.isAlertOnCooldown(this)) {
                Toast.makeText(
                    this, "Alert already sent recently. Please wait.", Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            startSosCountdown()
        }

    }

    // ─── Toolbar menu — gear icon ────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        sosCountdownTimer?.cancel()
        if (localBroadcastReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(localBroadcastReceiver!!)
        }
    }

    // ─── Safety service helpers ──────────────────────────────────────────────────

    private fun isSafetyServiceRunning(): Boolean = PrefsHelper.isSafetyModeOn(this)

    private fun startSafetyService() {
        val hasLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasSms = ContextCompat.checkSelfPermission(
            this, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasLocation || !hasSms) {
            Toast.makeText(
                this, "Please grant Location and SMS permissions first", Toast.LENGTH_LONG
            ).show()
            requestAllPermissions()
            return
        }

        if (PrefsHelper.getContactNumbers(this).isEmpty()) {
            Toast.makeText(
                this, "Please add at least one emergency contact", Toast.LENGTH_LONG
            ).show()
            return
        }

        if (isSafetyServiceRunning()) { updateUI(); return }

        PrefsHelper.setSafetyModeOn(this, true)
        ContextCompat.startForegroundService(this, Intent(this, SafetyService::class.java))
        Toast.makeText(this, "Safety Mode ON", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun stopSafetyService() {
        if (!isSafetyServiceRunning()) { updateUI(); return }

        if (PrefsHelper.isTravelActive(this)) {
            AlertDialog.Builder(this)
                .setTitle("Stop Safety Mode?")
                .setMessage(
                    "Travel Mode is currently active.\n\n" +
                            "Turning off Safety Mode will also stop Travel Mode monitoring.\n\n" +
                            "Are you sure?"
                )
                .setPositiveButton("YES, STOP") { _, _ ->
                    PrefsHelper.stopTravel(this)
                    doStopSafetyService()
                }
                .setNegativeButton("CANCEL", null)
                .show()
            return
        }

        doStopSafetyService()
    }

    private fun doStopSafetyService() {
        PrefsHelper.setSafetyModeOn(this, false)
        startService(Intent(this, SafetyService::class.java).apply {
            action = SafetyService.ACTION_STOP_SERVICE
        })
        Toast.makeText(this, "Safety Mode OFF", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    // ─── SOS Countdown ───────────────────────────────────────────────────────────

    private fun startSosCountdown() {
        // Disable buttons
        btnToggleSafety.isEnabled = false
        btnTravelMode.isEnabled = false

        // Show countdown notification
        showSosNotification()

        var secondsLeft = 5

        sosCountdownTimer = object : CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                secondsLeft = (millisUntilFinished / 1000).toInt()
                btnSOS.text = "Cancel ($secondsLeft)"
            }

            override fun onFinish() {
                // Cancel notification
                getSystemService(NotificationManager::class.java).cancel(2002)
                
                // Set cooldown and send SOS
                PrefsHelper.setLastAlertTime(this@MainActivity)
                startService(Intent(this@MainActivity, SafetyService::class.java).apply {
                    action = SafetyService.ACTION_SOS_TRIGGERED
                })
                
                // Reset button
                btnSOS.text = "SOS"
                btnToggleSafety.isEnabled = true
                btnTravelMode.isEnabled = true
            }
        }.start()

        // Allow user to cancel by tapping button
        btnSOS.setOnClickListener {
            cancelSosCountdown()
        }
    }

    private fun cancelSosCountdown() {
        sosCountdownTimer?.cancel()
        sosCountdownTimer = null
        
        // Cancel notification
        getSystemService(NotificationManager::class.java).cancel(2002)
        
        // Reset button
        btnSOS.text = "SOS"
        btnToggleSafety.isEnabled = true
        btnTravelMode.isEnabled = true

        // Re-attach the original click listener
        btnSOS.setOnClickListener {
            if (!isSafetyServiceRunning()) {
                Toast.makeText(this, "Turn ON Safety Mode first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (PrefsHelper.isAlertOnCooldown(this)) {
                Toast.makeText(
                    this, "Alert already sent recently. Please wait.", Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            startSosCountdown()
        }
    }

    private fun showSosNotification() {
        // Create safety alert notification channel if not exists
        val channel = android.app.NotificationChannel(
            "safety_alert_channel", "Safety Alerts", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        // Intent for "Cancel SOS" action
        val cancelPendingIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent("com.example.safeshadow.ACTION_CANCEL_SOS").apply {
                setPackage(packageName)
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = androidx.core.app.NotificationCompat.Builder(this, "safety_alert_channel")
            .setContentTitle("SOS sending in 5 seconds")
            .setContentText("Tap Cancel to stop")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .addAction(0, "Cancel SOS", cancelPendingIntent)
            .build()

        getSystemService(NotificationManager::class.java).notify(2002, notification)
    }

    // ─── UI update ───────────────────────────────────────────────────────────────

    private fun updateUI() {
        if (isSafetyServiceRunning()) {
            tvStatus.text = "Safety Mode: Active"
            btnToggleSafety.text = "Turn OFF Safety Mode"
            btnToggleSafety.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#388E3C"))

            if (PrefsHelper.isTravelActive(this)) {
                val dest = PrefsHelper.getTravelDestination(this)
                btnTravelMode.text = "Travel Active: $dest"
                btnTravelMode.backgroundTintList =
                    ColorStateList.valueOf(Color.parseColor("#1565C0"))
            } else {
                btnTravelMode.text = "Start Travel Mode"
                btnTravelMode.backgroundTintList =
                    ColorStateList.valueOf(Color.parseColor("#0288D1"))
            }
            btnTravelMode.isEnabled = true

        } else {
            tvStatus.text = "Safety Mode: Inactive"
            btnToggleSafety.text = "Turn ON Safety Mode"
            btnToggleSafety.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#B71C1C"))
            btnTravelMode.text = "Start Travel Mode"
            btnTravelMode.isEnabled = false
            btnTravelMode.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor("#9E9E9E"))
        }
    }

    // ─── Permissions ─────────────────────────────────────────────────────────────

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
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                        )
                    } catch (e: Exception) { /* fail silently */ }
                }
                .setNegativeButton("Skip") { _, _ ->
                    PrefsHelper.setBatteryOptimizationAsked(this)
                }
                .show()
        } else {
            PrefsHelper.setBatteryOptimizationAsked(this)
        }
    }
}