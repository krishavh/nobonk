package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.Detection
import ai.genwhy.nobonk.model.NormBox

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
    /**
     * Minimum fill-fraction increase per second to count a frame as "closing".
     * Raised from 0.04 → 0.12 (Round-2 calibration) so only genuine fast-closers
     * survive; a slow drift in box size no longer trips "approaching".
     */
    private val minClosingFillRate: Float = 0.12f,
    /**
     * Constant-bearing collision cone (Round-2 calibration, the biggest false-positive
     * killer). An object on a real collision course keeps its box centre near the frame
     * centre; one you'll harmlessly pass drifts laterally. On-bearing is asserted when the
     * centre sat inside |centerX − 0.5| < [bearingCone] for at least [approachFramesRequired]
     * of the last [bearingWindow] frames (an N-of-M test, NOT a fragile consecutive streak:
     * a single jittered frame no longer resets the count and drops the TTC lead-time path).
     */
    private val bearingCone: Float = 0.15f,
    /** Window length M for the N-of-M on-bearing test above. */
    private val bearingWindow: Int = 4,
    /**
     * Constant-bearing-by-low-drift: an object converging at a fixed NON-zero bearing
     * (both parties walking, meeting at an angle) keeps a near-constant centerX even though
     * it is not centred. Once a track has been seen for [constantBearingFrames] frames with
     * a smoothed lateral drift below [constantBearingDrift] per frame it counts as on-bearing
     * too — the true constant-bearing case the centred cone alone would miss. Kept tight so
     * laterally-drifting pass-bys (high drift) stay excluded.
     */
    private val constantBearingFrames: Int = 4,
    private val constantBearingDrift: Float = 0.03f,
    /**
     * Time-to-contact (s) at or below which a closing, on-bearing object is "imminent".
     * Raised 1.2 → 1.5 s so [DetectionEngine] gets head-on lead time after pipeline
     * latency (10 fps + hysteresis + linger + inference eats ~0.2–0.4 s).
     */
    private val imminentTtcSec: Float = 1.5f,
    private val maxMissedMs: Long = 1500L
) {
    private data class Track(
        val id: String,
        val className: String,
        var box: NormBox,
        var fill: Float,
        var lastSeen: Long,
        var fillVelocity: Float = 0f,   // fill fraction per second, EMA-smoothed
        var closingStreak: Int = 0,
        var bearingBits: Int = 0,       // ring bitmask: 1 = frame was inside the bearing cone
        var framesMatched: Int = 0,     // frames this track has been observed
        var lateralDriftEma: Float = 1f // EMA of |Δ centerX| per frame; starts high (unknown)
    )

    private val tracks = mutableListOf<Track>()
    private var nextId = 0L

    /** Exposed for the "collision imminent" HUD/escalation decisions. */
    fun isImminent(detectionId: String): Boolean =
        imminentIds.contains(detectionId)

    private var imminentIds: Set<String> = emptySet()

    /**
     * Stable track id for a current-frame detection id, or null if this detection did
     * not match any existing track (i.e. it's brand new this frame). Lets the engine
     * mute repeat HIGH re-alerts per *track* rather than per (unstable) detection id.
     */
    fun trackIdFor(detectionId: String): String? = detIdToTrackId[detectionId]

    private var detIdToTrackId: Map<String, String> = emptyMap()

    /**
     * Feed the current frame's detections. Returns the set of **detection ids** that
     * are confidently approaching (survived hysteresis).
     */
    fun update(detections: List<Detection>): Set<String> {
        val now = clock()
        val approaching = mutableSetOf<String>()
        val imminent = mutableSetOf<String>()
        val idMap = mutableMapOf<String, String>()
        val available = tracks.toMutableList()

        for (det in detections) {
            val fill = AlertPolicy.fillFraction(det.boundingBox, det.className)
            val match = bestMatch(det, available)
            if (match != null) {
                available.remove(match)
                idMap[det.id] = match.id
                val dtSec = ((now - match.lastSeen).coerceAtLeast(1L)) / 1000f
                val instRate = (fill - match.fill) / dtSec
                // EMA smoothing to reject single-frame noise.
                match.fillVelocity = match.fillVelocity * 0.6f + instRate * 0.4f
                val prevCenterX = match.box.centerX
                val centerX = det.boundingBox.centerX
                match.lateralDriftEma =
                    match.lateralDriftEma * 0.6f + kotlin.math.abs(centerX - prevCenterX) * 0.4f
                match.framesMatched++
                match.box = det.boundingBox
                match.fill = fill
                match.lastSeen = now

                // Constant-bearing test (N-of-M, jitter-tolerant): record whether the centre
                // is in the cone this frame into a ring bitmask and require it in >= N of the
                // last M frames — a single jittered frame no longer zeroes the count.
                val onBearingNow = kotlin.math.abs(centerX - 0.5f) < bearingCone
                match.bearingBits = ((match.bearingBits shl 1) or (if (onBearingNow) 1 else 0)) and
                    ((1 shl bearingWindow) - 1)

                if (match.fillVelocity > minClosingFillRate) {
                    match.closingStreak++
                } else {
                    match.closingStreak = 0
                }

                // Approach requires BOTH sustained closing AND an on-bearing track. On-bearing
                // is either the N-of-M centred test OR a settled low-lateral-drift track (a
                // constant NON-zero bearing). A laterally-drifting pass-by satisfies neither
                // and is never flagged, even as its box grows.
                val closing = match.closingStreak >= approachFramesRequired
                val centeredEnough = Integer.bitCount(match.bearingBits) >= approachFramesRequired
                val constantBearing = match.framesMatched >= constantBearingFrames &&
                    match.lateralDriftEma < constantBearingDrift
                val onBearing = centeredEnough || constantBearing
                if (closing && onBearing) {
                    approaching.add(det.id)
                    // Estimate time-to-contact from fill-growth rate; low → imminent.
                    val remaining = (0.9f - fill).coerceAtLeast(0f)
                    val ttcSec = if (match.fillVelocity > 0f) remaining / match.fillVelocity else Float.MAX_VALUE
                    if (ttcSec <= imminentTtcSec) imminent.add(det.id)
                }
            } else {
                tracks.add(
                    Track(
                        id = "t${nextId++}",
                        className = det.className,
                        box = det.boundingBox,
                        fill = fill,
                        lastSeen = now,
                        framesMatched = 1,
                        bearingBits = if (kotlin.math.abs(det.boundingBox.centerX - 0.5f) < bearingCone) 1 else 0
                    )
                )
            }
        }

        // Drop stale tracks.
        tracks.removeAll { now - it.lastSeen > maxMissedMs }
        imminentIds = imminent
        detIdToTrackId = idMap
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
        detIdToTrackId = emptyMap()
    }
}
