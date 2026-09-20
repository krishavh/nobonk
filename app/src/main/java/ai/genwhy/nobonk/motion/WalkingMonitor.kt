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

interface WalkingMotionSource { fun start(): Boolean; fun close() }

/** Local step events only. No location, stored step history or network requests. */
class WalkingMonitor(context: Context, private val onTransition: (WalkingSessionPolicy.Transition) -> Unit) : SensorEventListener, WalkingMotionSource {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val policy = WalkingSessionPolicy()
    private val handler = Handler(Looper.getMainLooper())
    private val inactivityCheck = object : Runnable {
        override fun run() {
            if (!active) return
            dispatch(policy.onTime(SystemClock.elapsedRealtimeNanos()))
            if (active) handler.postDelayed(this, 1_000L)
        }
    }
    private fun dispatch(transition: WalkingSessionPolicy.Transition) {
        if (active && transition != WalkingSessionPolicy.Transition.NONE) onTransition(transition)
    }
    private var active = false

    override fun start(): Boolean {
        val sensor = (manager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR, true)
            ?: manager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)) ?: return false
        active = true
        val registered = try {
            manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, 0, handler)
        } catch (_: SecurityException) { false }
        if (!registered) close() else handler.postDelayed(inactivityCheck, 1_000L)
        return registered
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (!active || event.sensor.type != Sensor.TYPE_STEP_DETECTOR || event.values.firstOrNull() != 1f) return
        dispatch(policy.onStep(event.timestamp, SystemClock.elapsedRealtimeNanos()))
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun close() {
        active = false
        policy.cancel()
        handler.removeCallbacks(inactivityCheck)
        manager?.unregisterListener(this)
    }
    companion object {
        fun supported(context: Context): Boolean = context.getSystemService(SensorManager::class.java)
            ?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null
        fun permitted(context: Context): Boolean = ContextCompat.checkSelfPermission(context,
            Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    }
}
