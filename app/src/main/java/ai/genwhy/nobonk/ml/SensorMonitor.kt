package ai.genwhy.nobonk.ml

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

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
 *
 * Thread-safety: [onSensorChanged] is invoked on the main thread (the listener
 * is registered with [SensorManager.SENSOR_DELAY_UI]), so the mutable pitch
 * state is confined to that thread and needs no synchronization. [start] and
 * [stop] are also expected to be called from the main thread.
 */
class SensorMonitor(context: Context) : SensorEventListener {

    // `as?` instead of a hard cast: on the rare device/robolectric setup where
    // SENSOR_SERVICE is missing or of an unexpected type, degrade gracefully
    // (no sensor, pitch stays 0) rather than crashing in the constructor.
    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /**
     * Gravity sensor, falling back to the raw accelerometer if the fused
     * gravity sensor is unavailable on this device. Null when the device has
     * neither, in which case [start] is a no-op and [cameraPitchDegrees] stays 0.
     */
    private val gravitySensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /**
     * Camera pitch in degrees, clamped to [-90, 90].
     *
     * Returns 0 until the first valid sensor event arrives; readings from a
     * misbehaving driver (NaN/Inf components, degenerate vectors) are ignored
     * rather than propagated.
     */
    var cameraPitchDegrees: Float = 0f
        private set

    /**
     * Quality bucket derived from the current [cameraPitchDegrees].
     */
    enum class AngleQuality {
        /** Any normal phone-holding posture; detection works well. */
        OK,

        /** Phone getting too flat; camera losing its forward view. */
        WARNING,

        /** Phone nearly horizontal; camera looking at the ceiling. */
        BAD,
    }

    /**
     * Quality bucket for the current [cameraPitchDegrees]:
     * pitch ≤ 72° is [AngleQuality.OK], ≤ 82° is [AngleQuality.WARNING],
     * anything above is [AngleQuality.BAD]. Negative pitches (camera pointing
     * down) are always [AngleQuality.OK] — see the class KDoc for why.
     */
    val angleQuality: AngleQuality
        get() = when {
            cameraPitchDegrees <= WARNING_THRESHOLD_DEGREES -> AngleQuality.OK
            cameraPitchDegrees <= BAD_THRESHOLD_DEGREES -> AngleQuality.WARNING
            else -> AngleQuality.BAD
        }

    /**
     * Human-readable hint shown to the user, derived from the current
     * [cameraPitchDegrees]. Empty when the angle is fine (i.e. at or below
     * the warning threshold — including all negative pitches).
     */
    val angleHint: String
        get() = when {
            cameraPitchDegrees > BAD_THRESHOLD_DEGREES -> "TILT PHONE DOWN — camera facing ceiling"
            cameraPitchDegrees > WARNING_THRESHOLD_DEGREES -> "Tilt phone down slightly for a better view"
            else -> ""
        }

    // ── Lifecycle ────────────────────────────────────────────────

    /**
     * Registers this monitor for gravity/accelerometer updates at UI rate.
     * No-op if the sensor service or sensor is unavailable. Registering an
     * already-registered listener is harmless (the system deduplicates it),
     * so no extra bookkeeping is needed here.
     */
    fun start() {
        val manager = sensorManager ?: return
        val sensor = gravitySensor ?: return
        manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    /** Unregisters this monitor; safe to call multiple times, even without a prior [start]. */
    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    // ── SensorEventListener ─────────────────────────────────────

    /**
     * Updates [cameraPitchDegrees] from the latest gravity/accelerometer reading.
     *
     * Malformed events (null, fewer than 3 components, non-finite or degenerate
     * vectors) are silently ignored so a misbehaving driver can never crash the
     * app or poison the smoothed value.
     */
    override fun onSensorChanged(event: SensorEvent?) {
        // `takeIf` folds the null and short-event checks into one guard; some
        // drivers deliver fewer than 3 components, in which case we bail out.
        // Note: `values` is reused by the system across events, but we only read
        // the components synchronously here, so no copy is needed.
        val values = event?.values?.takeIf { it.size >= AXIS_COUNT } ?: return

        // Only the y (along phone) and z (out of screen) components matter for
        // pitch; values[0] (x, across phone) is intentionally ignored — roll
        // about the camera's optical axis doesn't change where it points.
        val gy = values[1]
        val gz = values[2]

        // Guard against NaN/Inf components from a misbehaving driver.
        if (!gy.isFinite() || !gz.isFinite()) return
        // Degenerate reading (all-zero vector during sensor warm-up) — atan2(0,0)
        // is well-defined as 0 but the value is meaningless, so skip it.
        if (gy == 0f && gz == 0f) return

        val rawPitch = computePitch(gy, gz)
        // Reject a non-finite raw pitch (impossible after the guards above, but
        // cheap to defend) so it can never poison the running average.
        if (!rawPitch.isFinite()) return

        // Light EMA smoothing to avoid jitter; the clamp keeps the running
        // average inside the physically meaningful range even if a driver ever
        // reports an out-of-range magnitude.
        val smoothed = cameraPitchDegrees * PREVIOUS_SAMPLE_WEIGHT +
            rawPitch * NEW_SAMPLE_WEIGHT
        cameraPitchDegrees = smoothed.coerceIn(PITCH_CLAMP_MIN, PITCH_CLAMP_MAX)
    }

    /**
     * Not used; pitch quality does not depend on sensor accuracy reporting.
     */
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op: accuracy changes don't affect the pitch estimate.
    }

    /**
     * Camera pitch from the device's y/z gravity components, in degrees.
     *
     * Device axes (portrait):
     *   y → up along phone     z → out of screen (toward user)
     * Camera optical axis = -z direction.
     * Pitch = angle of -z relative to the horizontal plane.
     *
     * atan2(-gz, -gy):
     *   phone vertical (screen at user)  → (0, 9.8)  → 0°
     *   phone tilted back (camera up)    → (+, +)     → positive
     *   phone tilted forward (camera dn) → (-, +)     → negative
     *
     * Both inputs are guaranteed finite and not both zero by the caller, so
     * the result is always well-defined. Computed in Double to preserve the
     * full precision of the degree conversion before truncating to Float.
     */
    private fun computePitch(gy: Float, gz: Float): Float =
        Math.toDegrees(Math.atan2(-gz.toDouble(), -gy.toDouble())).toFloat()

    private companion object {
        /** Above this pitch the camera starts losing its forward view. */
        const val WARNING_THRESHOLD_DEGREES = 72f

        /** Above this pitch the phone is nearly horizontal, camera at the ceiling. */
        const val BAD_THRESHOLD_DEGREES = 82f

        /** Weight of the previous sample in the EMA smoothing (new sample gets 30%). */
        const val PREVIOUS_SAMPLE_WEIGHT = 0.7f

        /** Complement of [PREVIOUS_SAMPLE_WEIGHT]; weight applied to the new sample. */
        const val NEW_SAMPLE_WEIGHT = 1f - PREVIOUS_SAMPLE_WEIGHT

        /** Physically meaningful pitch range in degrees, used to clamp the smoothed value. */
        const val PITCH_CLAMP_MIN = -90f
        const val PITCH_CLAMP_MAX = 90f

        /** Minimum number of components a usable sensor event must carry (x, y, z). */
        const val AXIS_COUNT = 3
    }
}
