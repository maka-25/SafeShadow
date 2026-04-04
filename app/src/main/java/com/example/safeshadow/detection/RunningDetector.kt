package com.example.safeshadow.detection

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * RunningDetector
 *
 * Detects rhythmic running/panic-running movement.
 *
 * Key fix: upper G limit tightened to 2.5G (was 3.2G).
 * Shaking produces 3.0G+ so there is now a clean gap between
 * running range (1.5–2.5G) and shake range (3.0G+).
 * This prevents shake events from being counted as running steps.
 *
 * Also: if a high-G spike (>2.8G) is seen, the step counter resets.
 * This suppresses running detection during active shaking.
 */
class RunningDetector(
    private val onRunningDetected: () -> Unit
) : SensorEventListener {

    companion object {
        private const val RUNNING_MIN_G = 1.5f       // Min G for a running step
        private const val RUNNING_MAX_G = 2.5f       // Max G — above this is shake territory

        // If G exceeds this, assume shake is happening — reset running state
        private const val SHAKE_SUPPRESSION_THRESHOLD = 2.8f

        private const val STEP_MIN_INTERVAL = 200L   // Min ms between steps (very fast run)
        private const val STEP_MAX_INTERVAL = 800L   // Max ms between steps (slow jog)
        private const val STEPS_TO_CONFIRM = 12      // Steps needed to confirm running
        private const val STEP_RESET_TIME = 3000L    // Reset if no step for 3 seconds
        private const val COOLDOWN = 60000L          // 1 min cooldown after trigger
    }

    private var stepCount = 0
    private var lastStepTime = 0L
    private var lastTriggerTime = 0L
    private var lastPeakTime = 0L
    private var isPeak = false

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        val now = System.currentTimeMillis()

        // Respect cooldown
        if (now - lastTriggerTime < COOLDOWN) return

        // Shake suppression — if G is very high, reset running state entirely
        // This means shaking won't accidentally push us to STEPS_TO_CONFIRM
        if (gForce > SHAKE_SUPPRESSION_THRESHOLD) {
            stepCount = 0
            lastPeakTime = 0L
            isPeak = false
            return
        }

        // Reset step count if too much time passed since last step
        if (now - lastStepTime > STEP_RESET_TIME && stepCount > 0) {
            stepCount = 0
        }

        // Detect a step peak — G rises into running range then falls
        if (gForce in RUNNING_MIN_G..RUNNING_MAX_G) {
            if (!isPeak) {
                val timeSinceLastPeak = now - lastPeakTime

                // Check interval is consistent with running cadence
                if (lastPeakTime == 0L ||
                    timeSinceLastPeak in STEP_MIN_INTERVAL..STEP_MAX_INTERVAL
                ) {
                    isPeak = true
                    lastPeakTime = now
                    lastStepTime = now
                    stepCount++

                    if (stepCount >= STEPS_TO_CONFIRM) {
                        stepCount = 0
                        lastTriggerTime = now
                        lastPeakTime = 0L
                        onRunningDetected()
                    }
                } else if (timeSinceLastPeak > STEP_MAX_INTERVAL) {
                    // Gap too long — not a consistent running pattern, reset
                    stepCount = 0
                    lastPeakTime = now
                    isPeak = true
                }
            }
        } else {
            isPeak = false
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun reset() {
        stepCount = 0
        lastStepTime = 0L
        lastPeakTime = 0L
        isPeak = false
    }
}