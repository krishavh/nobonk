package ai.genwhy.nobonk.ml

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/**
 * Estimates rear-camera elevation from Android's gravity vector: 0° is horizontal,
 * positive points upward and negative downward. It remains correct in landscape.
 * An angle within the warning thresholds does not prove an unobstructed view or
 * calibrate distance. Raw accelerometer fallback can be less accurate during motion.
 * Lifecycle calls and sensor callbacks are on Main; policy and motion reads are synchronized.
 */
class SensorMonitor(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val angle = CameraAnglePolicy()
    private var started = false
    val motion = MotionGate()

    fun stationaryMs(nowMs: Long = SystemClock.elapsedRealtime()): Long = motion.stationaryMs(nowMs)

    /** Legacy numeric API; use [angleQuality] to distinguish unknown from horizontal. */
    val cameraPitchDegrees: Float get() = angle.reading(SystemClock.elapsedRealtime()).pitchDegrees

    enum class AngleQuality {
        /** Within conservative tilt thresholds, not a guarantee of detection. */
        OK,
        /** Rear camera is steeply angled upward or downward. */
        WARNING,
        /** Rear camera is nearly vertical, losing its forward view. */
        BAD,
        /** Missing, stopped, stale or not-yet-initialized angle sensor. */
        UNKNOWN,
    }

    val angleQuality: AngleQuality get() = angle.reading(SystemClock.elapsedRealtime()).quality
    val angleHint: String get() = angle.reading(SystemClock.elapsedRealtime()).hint

    fun start() {
        if (started) return
        angle.reset()
        motion.reset()
        val manager = sensorManager ?: return
        val sensor = gravitySensor ?: return
        started = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        if (accelSensor != null && accelSensor != sensor) {
            manager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        started = false
        motion.reset()
        angle.reset()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!started) return // Ignore an already-queued callback after Stop.
        val values = event?.values?.takeIf { it.size >= 3 } ?: return
        val type = event.sensor?.type ?: return
        val x = values[0]; val y = values[1]; val z = values[2]
        if (type == Sensor.TYPE_ACCELEROMETER) {
            motion.push(kotlin.math.sqrt(x * x + y * y + z * z), SystemClock.elapsedRealtime())
        }
        if (type != gravitySensor?.type) return
        // Sensor timestamps share elapsedRealtime's time base; delayed events must not
        // make an old posture look fresh merely because the callback arrived now.
        angle.update(x, y, z, event.timestamp / 1_000_000L)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
