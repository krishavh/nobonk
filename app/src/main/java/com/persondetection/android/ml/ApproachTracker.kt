package com.persondetection.android.ml

import com.persondetection.android.model.Detection
import com.persondetection.android.model.NormBox

/**
 * Pure multi-object approach tracker (fixes audit ML-03 / ML-07/08).
 *
 * The old [ApproachDetector] had three fatal bugs that meant the app's namesake —
 * warning about things closing on you — warned no one:
 *  1. **Class-agnostic, first-match IoU** — in a two-person scene a track could jump
 *     between people, producing phantom "approaching" flags.
 *  2. **IoU > 0.3 required** — a fast approacher's box grows so much frame-to-frame
 *     that consecutive boxes barely overlap, so the match failed and velocity reset
 *     to 0 → the fastest, most dangerous closers were never flagged.
 *  3. **No hysteresis** — a single noisy frame could assert/clear "approaching".
 *
 * Fixes here:
 *  - Match by **best IoU among same-class tracks**, each track consumed once.
 *  - **Centroid + growth fallback**: if IoU is low but the detection's centre still
 *    sits inside the (grown) track region and the box grew, it's the same object
 *    closing fast — match it anyway.
 *  - Approach is measured in **fill-fraction growth** (works even when metric distance
 *    saturates) and requires [approachFramesRequired] consecutive closing frames
 *    (hysteresis) before it is asserted.
 *
 * Fully deterministic and Android-free; [clock] is injectable for tests.
 */
class ApproachTracker(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val iouMatchThreshold: Float = 0.2f,
    private val approachFramesRequired: Int = 2,
    /** Minimum fill-fraction increase per second to count a frame as "closing". */
    private val minClosingFillRate: Float = 0.04f,
    private val maxMissedMs: Long = 1500L
) {
    private data class Track(
        val id: String,
        val className: String,
        var box: NormBox,
        var fill: Float,
        var lastSeen: Long,
        var fillVelocity: Float = 0f,   // fill fraction per second, EMA-smoothed
        var closingStreak: Int = 0
    )

    private val tracks = mutableListOf<Track>()
    private var nextId = 0L

    /** Exposed for the "collision imminent" HUD/escalation decisions. */
    fun isImminent(detectionId: String): Boolean =
        imminentIds.contains(detectionId)

    private var imminentIds: Set<String> = emptySet()

    /**
     * Feed the current frame's detections. Returns the set of **detection ids** that
     * are confidently approaching (survived hysteresis).
     */
    fun update(detections: List<Detection>): Set<String> {
        val now = clock()
        val approaching = mutableSetOf<String>()
        val imminent = mutableSetOf<String>()
        val available = tracks.toMutableList()

        for (det in detections) {
            val fill = AlertPolicy.fillFraction(det.boundingBox, det.className)
            val match = bestMatch(det, available)
            if (match != null) {
                available.remove(match)
                val dtSec = ((now - match.lastSeen).coerceAtLeast(1L)) / 1000f
                val instRate = (fill - match.fill) / dtSec
                // EMA smoothing to reject single-frame noise.
                match.fillVelocity = match.fillVelocity * 0.6f + instRate * 0.4f
                match.box = det.boundingBox
                match.fill = fill
                match.lastSeen = now

                if (match.fillVelocity > minClosingFillRate) {
                    match.closingStreak++
                } else {
                    match.closingStreak = 0
                }
                if (match.closingStreak >= approachFramesRequired) {
                    approaching.add(det.id)
                    // Estimate frames-to-fill; very low → imminent.
                    val remaining = (0.9f - fill).coerceAtLeast(0f)
                    val ttcSec = if (match.fillVelocity > 0f) remaining / match.fillVelocity else Float.MAX_VALUE
                    if (ttcSec < 1.2f) imminent.add(det.id)
                }
            } else {
                tracks.add(
                    Track(
                        id = "t${nextId++}",
                        className = det.className,
                        box = det.boundingBox,
                        fill = fill,
                        lastSeen = now
                    )
                )
            }
        }

        // Drop stale tracks.
        tracks.removeAll { now - it.lastSeen > maxMissedMs }
        imminentIds = imminent
        return approaching
    }

    /**
     * Best same-class match for [det] among [candidates]:
     *  1. highest IoU above [iouMatchThreshold]; otherwise
     *  2. centroid-in-region + growth fallback for fast closers.
     */
    private fun bestMatch(det: Detection, candidates: List<Track>): Track? {
        var best: Track? = null
        var bestIou = iouMatchThreshold
        for (t in candidates) {
            if (t.className != det.className) continue
            val iou = t.box.iou(det.boundingBox)
            if (iou > bestIou) {
                bestIou = iou
                best = t
            }
        }
        if (best != null) return best

        // Fallback: fast growth breaks IoU. Same class, centres mutually contained,
        // and the detection is at least as large → treat as the same object closing.
        var fbBest: Track? = null
        var fbDist = Float.MAX_VALUE
        for (t in candidates) {
            if (t.className != det.className) continue
            val centreShared = t.box.contains(det.boundingBox.centerX, det.boundingBox.centerY) ||
                det.boundingBox.contains(t.box.centerX, t.box.centerY)
            val grew = det.boundingBox.area >= t.box.area * 0.8f
            if (centreShared && grew) {
                val d = kotlin.math.abs(t.box.centerX - det.boundingBox.centerX) +
                        kotlin.math.abs(t.box.centerY - det.boundingBox.centerY)
                if (d < fbDist) {
                    fbDist = d
                    fbBest = t
                }
            }
        }
        return fbBest
    }

    fun reset() {
        tracks.clear()
        imminentIds = emptySet()
    }
}
