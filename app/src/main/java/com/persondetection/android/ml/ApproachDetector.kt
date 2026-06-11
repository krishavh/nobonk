package com.persondetection.android.ml

import androidx.compose.ui.geometry.Rect
import com.persondetection.android.model.Detection
import java.util.UUID

data class TrackedPerson(
    val id: String,
    var lastBox: Rect,
    var lastDistance: Float,
    var velocity: Float = 0f, 
    var lastSeen: Long = System.currentTimeMillis()
)

/**
 * Implements IoU (Intersection over Union) tracking and Time-to-Collision (TTC) 
 * physics to identify people approaching at high speeds.
 */
class ApproachDetector {
    private val trackedPeople = mutableMapOf<String, TrackedPerson>()
    private val iouThreshold = 0.3f
    private val maxMissedTime = 2000L // Remove tracks after 2 seconds

    /**
     * Update tracking with new detections and return IDs of approaching people.
     */
    fun updateDetections(detections: List<Detection>): Set<String> {
        val now = System.currentTimeMillis()
        val approachingIds = mutableSetOf<String>()
        
        detections.forEach { det ->
            val match = findMatch(det.boundingBox)
            if (match != null) {
                val dt = (now - match.lastSeen) / 1000f
                if (dt > 0) {
                    // Positive velocity means distance is decreasing (approaching)
                    val v = (match.lastDistance - det.distance) / dt
                    // Simple EMA for velocity smoothing
                    match.velocity = (match.velocity * 0.7f) + (v * 0.3f)
                }
                match.lastBox = det.boundingBox
                match.lastDistance = det.distance
                match.lastSeen = now
                
                // Alert if person will hit you in less than 2 seconds (TTC)
                // Threshold: velocity > 0.3 m/s and TTC < 2.0 seconds
                if (match.velocity > 0.3f && (det.distance / match.velocity) < 2.0f) {
                    approachingIds.add(det.id)
                }
            } else {
                val newId = UUID.randomUUID().toString()
                trackedPeople[newId] = TrackedPerson(
                    id = newId, 
                    lastBox = det.boundingBox, 
                    lastDistance = det.distance,
                    lastSeen = now
                )
            }
        }
        
        // Cleanup old tracks
        val toRemove = trackedPeople.filter { now - it.value.lastSeen > maxMissedTime }.keys
        toRemove.forEach { trackedPeople.remove(it) }
        
        return approachingIds
    }

    /**
     * Check for imminent collisions using current tracks
     */
    fun isCollisionImminent(detections: List<Detection>): Boolean {
        val approaching = updateDetections(detections)
        return approaching.isNotEmpty()
    }

    private fun findMatch(box: Rect): TrackedPerson? = 
        trackedPeople.values.find { calculateIoU(it.lastBox, box) > iouThreshold }

    private fun calculateIoU(a: Rect, b: Rect): Float {
        val intersectLeft = maxOf(a.left, b.left)
        val intersectTop = maxOf(a.top, b.top)
        val intersectRight = minOf(a.right, b.right)
        val intersectBottom = minOf(a.bottom, b.bottom)
        
        if (intersectLeft >= intersectRight || intersectTop >= intersectBottom) return 0f
        
        val intersectionArea = (intersectRight - intersectLeft) * (intersectBottom - intersectTop)
        val areaA = a.width * a.height
        val areaB = b.width * b.height
        val unionArea = areaA + areaB - intersectionArea
        
        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    fun reset() {
        trackedPeople.clear()
    }
}
