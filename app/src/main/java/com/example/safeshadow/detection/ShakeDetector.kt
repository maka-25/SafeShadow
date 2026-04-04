package com.example.safeshadow.detection

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * ShakeDetector
 *
 * Detects 3 deliberate shakes within 3 seconds.
 *
 * Key improvements over naive threshold detection:
 * - Threshold raised to 3.0G (was 2.2G) — eliminates most accidental triggers
 * - Direction-change requirement: each shake must reverse axis direction
 *   (real shakes go back and forth; dropping/throwing goes one direction)
 * - Dominant axis tracking: all shakes must be on the same axis (X, Y or Z)
 *   which is how a real shake works — not random multi-axis noise
 * - 10 second cooldown after trigger
 */
class ShakeDetector(
    private val onShakeDetected: () -> Unit
) : SensorEventListener {

    companion object {
        // How hard the shake must be — 3.0G filters out bumps, drops, running
        private const val SHAKE_THRESHOLD_GRAVITY = 3.0f

        // All 3 shakes must happen within this window
        private const val SHAKE_COUNT_RESET_TIME = 3000L

        // Minimum required shakes
        private const val REQUIRED_SHAKES = 3

        // Minimum time between two shake peaks (prevents noise bursts counting as multiple)
        private const val MIN_TIME_BETWEEN_SHAKES = 250L

        // Cooldown after a shake SOS is triggered — prevents immediate re-trigger
        private const val COOLDOWN_AFTER_TRIGGER = 10000L

        // After a shake peak, wait this long before accepting next peak
        // This ensures the phone has time to reverse direction
        private const val DIRECTION_REVERSE_WINDOW = 400L
    }

    private var shakeCount = 0
    private var lastShakeTime = 0L
    private var firstShakeTime = 0L
    private var lastTriggerTime = 0L

    // Direction tracking — real shakes alternate direction on dominant axis
    private var lastDominantAxis = -1   // 0=X, 1=Y, 2=Z
    private var lastAxisSign = 0        // +1 or -1

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

        // Must exceed threshold
        if (gForce <= SHAKE_THRESHOLD_GRAVITY) return

        val now = System.currentTimeMillis()

        // Respect post-trigger cooldown
        if (now - lastTriggerTime < COOLDOWN_AFTER_TRIGGER) return

        // Respect minimum time between shake peaks
        if (now - lastShakeTime < MIN_TIME_BETWEEN_SHAKES) return

        // Find the dominant axis (the axis with the highest absolute value)
        val absX = kotlin.math.abs(x)
        val absY = kotlin.math.abs(y)
        val absZ = kotlin.math.abs(z)

        val dominantAxis: Int
        val axisValue: Float

        when {
            absX >= absY && absX >= absZ -> { dominantAxis = 0; axisValue = x }
            absY >= absX && absY >= absZ -> { dominantAxis = 1; axisValue = y }
            else                         -> { dominantAxis = 2; axisValue = z }
        }

        val currentSign = if (axisValue > 0) 1 else -1

        // Reset shake count if window expired
        if (now - firstShakeTime > SHAKE_COUNT_RESET_TIME) {
            shakeCount = 0
            firstShakeTime = now
            lastDominantAxis = dominantAxis
            lastAxisSign = currentSign
        }

        // Direction-change check:
        // Real shake = same axis, opposite sign each time
        // Random bump = different axes or same sign (one-way force)
        val isValidShake = if (shakeCount == 0) {
            // First shake — always accept, just record direction
            true
        } else {
            // Subsequent shakes must be on same axis AND reversed direction
            dominantAxis == lastDominantAxis && currentSign != lastAxisSign
        }

        if (!isValidShake) {
            // Wrong axis or same direction — reset and start fresh from this shake
            shakeCount = 1
            firstShakeTime = now
            lastDominantAxis = dominantAxis
            lastAxisSign = currentSign
            lastShakeTime = now
            return
        }

        // Valid shake — record it
        lastShakeTime = now
        lastDominantAxis = dominantAxis
        lastAxisSign = currentSign
        shakeCount++

        if (shakeCount == 1) {
            firstShakeTime = now
        }

        if (shakeCount >= REQUIRED_SHAKES) {
            // Confirmed — 3 real back-and-forth shakes on same axis
            shakeCount = 0
            firstShakeTime = 0
            lastShakeTime = 0
            lastDominantAxis = -1
            lastAxisSign = 0
            lastTriggerTime = now
            onShakeDetected()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}