package ai.genwhy.nobonk.ml

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2

/**
 * Monitors phone orientation via the gravity sensor and exposes the camera
 * pitch angle (degrees).
 *
 *   0°   → camera pointing horizontally (phone perfectly vertical)
 *  +N°   → camera tilted upward (screen tilted toward user — normal phone-use posture)
 *  -N°   → camera tilted downward
 *
 * This app targets distracted walkers using the BACK camera.
 * Reality: if you're looking at the screen, the back camera faces forward.
 * Holding the phone naturally at chest/face level puts the camera at roughly
 * 0° to +50° — all perfectly fine for detecting people ahead.
 *
 * The ONLY angle we need to warn about is when the phone is laid too flat
 * (e.g. on a table, or held nearly horizontal) so the back camera looks at
 * the ceiling rather than forward. A downward-pointing camera is not a real
 * concern: if the camera were pointing at the ground, the user wouldn't be
 * looking at the screen.
 *
 * Warning thresholds:
 *   > 72° → phone getting too flat, camera losing forward view
 *   > 82° → phone nearly horizontal, camera faces ceiling
 */
class SensorMonitor(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** Gravity sensor, falling back to the raw accelerometer if unavailable. */
    private val gravitySensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) // fallback

    /** Camera pitch in degrees. Updated at UI rate; 0 until first sensor event. */
    var cameraPitchDegrees: Float = 0f
        private set

    /**
     * OK      → any normal phone-holding posture, detection works well
     * WARNING → phone getting too flat, camera losing its forward view
     * BAD     → phone nearly horizontal, camera looking at ceiling
     */
    enum class AngleQuality { OK, WARNING, BAD }

    /** Quality bucket derived from the current [cameraPitchDegrees]. */
    val angleQuality: AngleQuality
        get() = when {
            cameraPitchDegrees <= 72f -> AngleQuality.OK       // everything up to almost-flat is fine
            cameraPitchDegrees <= 82f -> AngleQuality.WARNING  // phone getting quite flat
            else                      -> AngleQuality.BAD      // phone is flat, camera at ceiling
        }

    /** Human-readable hint shown to the user. Empty when angle is fine. */
    val angleHint: String
        get() = when {
            cameraPitchDegrees > 82f -> "TILT PHONE DOWN — camera facing ceiling"
            cameraPitchDegrees > 72f -> "Tilt phone down slightly for a better view"
            else                     -> ""
        }

    // ── Lifecycle ────────────────────────────────────────────────

    /** Registers this monitor for gravity/accelerometer updates. No-op if no sensor exists. */
    fun start() {
        gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    /** Unregisters this monitor; safe to call multiple times. */
    fun stop() {
        sensorManager.unregisterListener(this)
    }

    // ── SensorEventListener ─────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent?) {
        val values = event?.values ?: return
        // Some drivers deliver fewer than 3 components; bail out rather than crash.
        if (values.size < 3) return

        // Device axes (portrait):
        //   y → up along phone     z → out of screen (toward user)
        // Camera optical axis = -z direction.
        // Pitch = angle of -z relative to the horizontal plane.
        val gy = values[1]
        val gz = values[2]

        // Degenerate reading (all-zero vector during sensor warm-up) — atan2(0,0)
        // is well-defined as 0 but the value is meaningless, so skip it.
        if (gy == 0f && gz == 0f) return

        // atan2(-gz, -gy):
        //   phone vertical (screen at user)  → (0, 9.8)  → 0°
        //   phone tilted back (camera up)    → (+, +)     → positive
        //   phone tilted forward (camera dn) → (-, +)     → negative
        val rawPitch = Math.toDegrees(atan2(-gz.toDouble(), -gy.toDouble())).toFloat()

        // Light EMA smoothing to avoid jitter; clamp guards against NaN/Inf
        // from a misbehaving driver corrupting the running average.
        val smoothed = cameraPitchDegrees * 0.7f + rawPitch * 0.3f
        cameraPitchDegrees = if (smoothed.isFinite()) smoothed else cameraPitchDegrees
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }
}
