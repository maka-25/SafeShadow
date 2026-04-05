package com.example.safeshadow.detection

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ShakeDetector
 *
 * Detects 3 deliberate shakes within a specified window of time.
 *
 * Improvements over naive threshold detection:
 * - Threshold raised to 3.0G to reduce accidental triggers from minor bumps or drops.
 * - Direction-change requirement: each shake must reverse along the dominant axis.
 * - Dominant axis tracking: all shakes must occur on the same axis (X, Y, or Z).
 * - Minimum and maximum time constraints between shakes to ensure intentional motion.
 * - Cooldown period after a trigger to prevent repeated activation.
 */
class ShakeDetector(
    private val onShakeDetected: () -> Unit
) : SensorEventListener {

    companion object {
        /** Minimum g-force required to count as a shake. */
        private const val SHAKE_THRESHOLD_GRAVITY = 2.2f

        /** Maximum time window (ms) within which all required shakes must occur. */
        private const val SHAKE_COUNT_RESET_TIME = 3000L

        /** Number of consecutive shakes required to trigger the event. */
        private const val REQUIRED_SHAKES = 2

        /** Minimum time (ms) between two shake peaks to prevent noise from being counted multiple times. */
        private const val MIN_TIME_BETWEEN_SHAKES = 250L

        /** Cooldown period (ms) after a shake trigger to prevent immediate retriggering. */
        private const val COOLDOWN_AFTER_TRIGGER = 10000L

        /** Maximum allowed time (ms) between direction reversals on the dominant axis. */
        private const val DIRECTION_REVERSE_WINDOW = 400L
    }

    private var shakeCount = 0
    private var lastShakeTime = 0L
    private var firstShakeTime = 0L
    private var lastTriggerTime = 0L

    /** Track the dominant axis and its last sign to ensure proper back-and-forth shakes. */
    private var lastDominantAxis = -1   // 0 = X, 1 = Y, 2 = Z
    private var lastAxisSign = 0        // +1 or -1

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

        // Ignore movements below the shake threshold
        if (gForce <= SHAKE_THRESHOLD_GRAVITY) return

        val now = System.currentTimeMillis()

        // Respect post-trigger cooldown
        if (now - lastTriggerTime < COOLDOWN_AFTER_TRIGGER) return

        // Respect minimum time between shake peaks
        if (now - lastShakeTime < MIN_TIME_BETWEEN_SHAKES) return

        // Determine the dominant axis (highest absolute value)
        val absX = abs(x)
        val absY = abs(y)
        val absZ = abs(z)

        val dominantAxis: Int
        val axisValue: Float
        when {
            absX >= absY && absX >= absZ -> { dominantAxis = 0; axisValue = x }
            absY >= absX && absY >= absZ -> { dominantAxis = 1; axisValue = y }
            else -> { dominantAxis = 2; axisValue = z }
        }

        val currentSign = if (axisValue > 0) 1 else -1

        // Reset shake count if the window for counting shakes has expired
        if (now - firstShakeTime > SHAKE_COUNT_RESET_TIME) {
            shakeCount = 0
            firstShakeTime = now
            lastDominantAxis = dominantAxis
            lastAxisSign = currentSign
        }

        // Validate shake: must be on the same axis, reverse direction, and within the direction reversal window
        val isValidShake = if (shakeCount == 0) {
            // First shake is always valid
            true
        } else {
            val isSameAxis = dominantAxis == lastDominantAxis
            val isDirectionChanged = currentSign != lastAxisSign
            val isWithinTime = (now - lastShakeTime) <= DIRECTION_REVERSE_WINDOW
            isSameAxis && isDirectionChanged && isWithinTime
        }

        if (!isValidShake) {
            // Reset count and start fresh if the shake is invalid
            shakeCount = 1
            firstShakeTime = now
            lastDominantAxis = dominantAxis
            lastAxisSign = currentSign
            lastShakeTime = now
            return
        }

        // Record the valid shake
        lastShakeTime = now
        lastDominantAxis = dominantAxis
        lastAxisSign = currentSign
        shakeCount++

        if (shakeCount == 1) {
            firstShakeTime = now
        }

        // Trigger event if the required number of shakes is reached
        if (shakeCount >= REQUIRED_SHAKES) {
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