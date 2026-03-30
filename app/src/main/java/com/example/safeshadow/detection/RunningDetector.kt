package com.example.safeshadow.detection

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class RunningDetector(
    private val onRunningDetected: () -> Unit
) : SensorEventListener {

    companion object {
        private const val RUNNING_MIN_G = 1.5f       // Min G for running step
        private const val RUNNING_MAX_G = 3.2f       // Max G — above this is shake/fall
        private const val STEP_MIN_INTERVAL = 200L   // Min ms between steps
        private const val STEP_MAX_INTERVAL = 800L   // Max ms between steps
        private const val STEPS_TO_CONFIRM = 12      // Steps needed to confirm running
        private const val STEP_RESET_TIME = 3000L    // Reset if no step for 3 seconds
        private const val COOLDOWN = 60000L          // 1 min cooldown after trigger
    }

    private var stepCount = 0
    private var lastStepTime = 0L
    private var lastTriggerTime = 0L
    private var lastPeakTime = 0L
    private var lastGForce = 0f
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
                }
            }
        } else {
            isPeak = false
        }

        lastGForce = gForce
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun reset() {
        stepCount = 0
        lastStepTime = 0L
        lastPeakTime = 0L
        isPeak = false
    }
}