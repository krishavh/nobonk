package ai.genwhy.nobonk.ml

import kotlin.math.abs

/**
 * Is the phone moving? Fed with raw accelerometer magnitudes, keeps a slow estimate of
 * gravity and treats any deviation above [threshold] m/s² as motion. Walking produces
 * 1-3 m/s² swings at every step; a phone held still while the user waits at a crossing
 * stays under 0.3. Used only to slow the analysis cadence when the user has been
 * standing still for a while — never to suppress an alert.
 *
 * Unknown (no sample yet) reads as "moving" so a missing sensor costs nothing but power.
 */
class MotionGate(
    private val threshold: Float = 0.6f,
    private val gravityAlpha: Float = 0.05f
) {
    private var gravityEstimate = 9.81f
    private var haveSample = false
    private var lastMotionAt = Long.MIN_VALUE

    @Synchronized fun push(magnitude: Float, nowMs: Long) {
        if (!magnitude.isFinite()) return
        if (!haveSample) { gravityEstimate = magnitude; haveSample = true; lastMotionAt = nowMs; return }
        val dev = abs(magnitude - gravityEstimate)
        gravityEstimate += (magnitude - gravityEstimate) * gravityAlpha
        if (dev > threshold) lastMotionAt = nowMs
    }

    /** Milliseconds since the last motion; 0 when unknown or moving. */
    @Synchronized fun stationaryMs(nowMs: Long): Long = if (!haveSample) 0L else (nowMs - lastMotionAt).coerceAtLeast(0L)

    @Synchronized fun reset() { haveSample = false; lastMotionAt = Long.MIN_VALUE; gravityEstimate = 9.81f }
}
