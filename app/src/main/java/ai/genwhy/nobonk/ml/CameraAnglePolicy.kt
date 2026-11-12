package ai.genwhy.nobonk.ml

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Rear-camera elevation, not Android's Euler pitch or a distance calibration. */
internal class CameraAnglePolicy {
    data class Reading(val pitchDegrees: Float, val quality: SensorMonitor.AngleQuality, val hint: String)
    private var pitch: Float? = null
    private var sampledAtMs: Long? = null
    private var classifiedQuality = SensorMonitor.AngleQuality.UNKNOWN

    @Synchronized fun reset() {
        pitch = null; sampledAtMs = null; classifiedQuality = SensorMonitor.AngleQuality.UNKNOWN
    }

    @Synchronized fun update(x: Float, y: Float, z: Float, atMs: Long) {
        val raw = rearCameraPitch(x, y, z) ?: return
        if (atMs < 0 || sampledAtMs?.let { atMs <= it } == true) return
        // Start fresh after a gap; a previous session/posture must not bias calibration.
        val previous = pitch?.takeIf { sampledAtMs?.let { old -> atMs - old <= STALE_MS } == true }
        val smoothed = previous?.let { it * 0.7f + raw * 0.3f } ?: raw
        val retainBad = previous != null && classifiedQuality == SensorMonitor.AngleQuality.BAD
        classifiedQuality = when {
            abs(smoothed) > BAD_DEGREES || (retainBad && abs(smoothed) > BAD_RECOVERY_DEGREES) -> SensorMonitor.AngleQuality.BAD
            abs(smoothed) > WARNING_DEGREES -> SensorMonitor.AngleQuality.WARNING
            else -> SensorMonitor.AngleQuality.OK
        }
        pitch = smoothed
        sampledAtMs = atMs
    }

    @Synchronized fun reading(nowMs: Long): Reading {
        val value = pitch
        val age = sampledAtMs?.let { nowMs - it }
        if (value == null || age == null || age !in 0..STALE_MS) {
            return Reading(0f, SensorMonitor.AngleQuality.UNKNOWN,
                "Camera angle unavailable — check that the rear camera points ahead")
        }
        // Reading/polling must not change hysteresis or freshness. Only accepted samples do.
        val quality = classifiedQuality
        val hint = when {
            quality == SensorMonitor.AngleQuality.OK -> ""
            value < 0f -> "Point the rear camera ahead — it is angled toward the ground"
            else -> "Point the rear camera ahead — it is angled upward"
        }
        return Reading(value, quality, hint)
    }

    companion object {
        const val STALE_MS = 2_000L
        // Preserve the previous conservative cutoffs, now applied in both directions.
        const val WARNING_DEGREES = 72f
        const val BAD_DEGREES = 82f
        // Once nearly vertical, require a clearly improved view before releasing the BAD gate.
        const val BAD_RECOVERY_DEGREES = 78f

        /**
         * Android reports +z when the screen faces up. Rear camera points along -z,
         * so its elevation is atan2(-z, hypot(x,y)). The x/y magnitude preserves
         * elevation under portrait/landscape rotation and upside-down holding.
         * Finite, near-zero vectors are not usable gravity estimates.
         */
        fun rearCameraPitch(x: Float, y: Float, z: Float): Float? {
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
            val horizontal = hypot(x.toDouble(), y.toDouble())
            if (hypot(horizontal, z.toDouble()) < 1.0) return null
            return Math.toDegrees(atan2(-z.toDouble(), horizontal)).toFloat()
        }
    }
}
