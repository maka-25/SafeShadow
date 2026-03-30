package com.example.safeshadow.detection

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class FallDetector(
    private val onFallDetected: () -> Unit
) : SensorEventListener {

    companion object {
        private const val FREE_FALL_THRESHOLD = 0.4f    // Near zero G = falling
        private const val IMPACT_THRESHOLD = 3.5f       // High G = impact after fall
        private const val STILLNESS_THRESHOLD = 1.2f    // Low G = phone is still
        private const val STILLNESS_DURATION = 2000L    // Still for 2 seconds
        private const val FALL_DETECTION_WINDOW = 3000L // Fall + impact within 3 seconds
        private const val COOLDOWN = 15000L             // 15 sec between detections
    }

    private enum class FallState {
        IDLE, FREE_FALLING, IMPACTED
    }

    private var state = FallState.IDLE
    private var freeFallTime = 0L
    private var impactTime = 0L
    private var stillnessStartTime = 0L
    private var lastTriggerTime = 0L

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        val now = System.currentTimeMillis()

        // Respect cooldown
        if (now - lastTriggerTime < COOLDOWN) return

        when (state) {
            FallState.IDLE -> {
                // Detect free fall — near zero gravity
                if (gForce < FREE_FALL_THRESHOLD) {
                    state = FallState.FREE_FALLING
                    freeFallTime = now
                }
            }

            FallState.FREE_FALLING -> {
                // Reset if window expired
                if (now - freeFallTime > FALL_DETECTION_WINDOW) {
                    state = FallState.IDLE
                    return
                }
                // Detect impact after free fall
                if (gForce > IMPACT_THRESHOLD) {
                    state = FallState.IMPACTED
                    impactTime = now
                    stillnessStartTime = 0L
                }
            }

            FallState.IMPACTED -> {
                // Reset if window expired
                if (now - impactTime > FALL_DETECTION_WINDOW) {
                    state = FallState.IDLE
                    return
                }
                // Wait for stillness after impact
                if (gForce < STILLNESS_THRESHOLD) {
                    if (stillnessStartTime == 0L) {
                        stillnessStartTime = now
                    } else if (now - stillnessStartTime >= STILLNESS_DURATION) {
                        // Fall confirmed — free fall → impact → stillness
                        state = FallState.IDLE
                        lastTriggerTime = now
                        onFallDetected()
                    }
                } else {
                    // Movement again — reset stillness timer
                    stillnessStartTime = 0L
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun reset() {
        state = FallState.IDLE
        freeFallTime = 0L
        impactTime = 0L
        stillnessStartTime = 0L
    }
}