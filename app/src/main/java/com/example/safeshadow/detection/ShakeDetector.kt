package com.example.safeshadow.detection

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class ShakeDetector(
    private val onShakeDetected: () -> Unit
) : SensorEventListener {

    companion object {
        private const val SHAKE_THRESHOLD_GRAVITY = 2.2f  // Raised slightly
        private const val SHAKE_COUNT_RESET_TIME = 3000L
        private const val REQUIRED_SHAKES = 3
        private const val MIN_TIME_BETWEEN_SHAKES = 300L
        private const val COOLDOWN_AFTER_TRIGGER = 10000L // 10 sec cooldown after trigger
    }

    private var shakeCount = 0
    private var lastShakeTime = 0L
    private var firstShakeTime = 0L
    private var lastTriggerTime = 0L  // Track when last SOS was sent

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

        if (gForce > SHAKE_THRESHOLD_GRAVITY) {
            val now = System.currentTimeMillis()

            // Don't trigger again within cooldown period
            if (now - lastTriggerTime < COOLDOWN_AFTER_TRIGGER) return

            if (now - lastShakeTime < MIN_TIME_BETWEEN_SHAKES) return

            if (now - firstShakeTime > SHAKE_COUNT_RESET_TIME) {
                shakeCount = 0
                firstShakeTime = now
            }

            lastShakeTime = now
            shakeCount++

            if (shakeCount >= REQUIRED_SHAKES) {
                shakeCount = 0
                firstShakeTime = 0
                lastShakeTime = 0
                lastTriggerTime = now  // Set cooldown timestamp
                onShakeDetected()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}