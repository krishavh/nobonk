package ai.genwhy.nobonk.motion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

/** Local step events only. No location, step history, polling or network requests. */
class WalkingMonitor(context: Context, private val onWalking: () -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val policy = SustainedWalkingPolicy()
    private var active = false

    fun start(): Boolean {
        val sensor = (manager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR, true)
            ?: manager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)) ?: return false
        active = true
        val registered = try {
            manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, 0, Handler(Looper.getMainLooper()))
        } catch (_: SecurityException) { false }
        if (!registered) close()
        return registered
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (!active || event.sensor.type != Sensor.TYPE_STEP_DETECTOR || event.values.firstOrNull() != 1f) return
        if (policy.onStep(event.timestamp, SystemClock.elapsedRealtimeNanos())) {
            close()
            onWalking()
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    fun close() {
        active = false
        policy.cancel()
        manager?.unregisterListener(this)
    }
    companion object {
        fun supported(context: Context): Boolean = context.getSystemService(SensorManager::class.java)
            ?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null
        fun permitted(context: Context): Boolean = ContextCompat.checkSelfPermission(context,
            Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    }
}
