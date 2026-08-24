package com.persondetection.android.ml

import com.persondetection.android.model.AlertLevel
import com.persondetection.android.model.Detection
import com.persondetection.android.model.NormBox
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Proves ML-03: fast closers are flagged (and escalate to HIGH) without false positives from track swaps. */
class ApproachTrackerTest {

    private fun person(id: String, cx: Float, fill: Float): Detection {
        val box = NormBox.fromCenter(cx, 0.5f, (fill * 0.4f).coerceAtMost(0.9f), fill)
        return Detection(id, box, 0.9f, 1.5f, "person", 0)
    }

    @Test fun `fast closing person is flagged and reaches HIGH within a few frames`() {
        var now = 0L
        val tracker = ApproachTracker(clock = { now })
        val fills = listOf(0.10f, 0.20f, 0.32f, 0.45f, 0.58f, 0.72f, 0.86f, 1.0f)
        var approachFrame = -1
        var highFrame = -1
        for ((i, f) in fills.withIndex()) {
            now = i * 100L
            val det = person("p", 0.5f, f)
            val approaching = tracker.update(listOf(det)).contains("p")
            val level = AlertPolicy.levelFor(det.boundingBox, "person", 2.0f, approaching)
            if (approaching && approachFrame < 0) approachFrame = i
            if (level == AlertLevel.HIGH && highFrame < 0) highFrame = i
        }
        assertTrue("approaching asserted early (frame $approachFrame)", approachFrame in 0..4)
        assertTrue("HIGH within ~0.6s (frame $highFrame)", highFrame in 0..6)
    }

    @Test fun `static person in a two-person scene is never falsely flagged approaching`() {
        var now = 0L
        val tracker = ApproachTracker(clock = { now })
        // A: static, off to the left (both stationary AND off-bearing → never flagged).
        val aBox = NormBox.fromCenter(0.20f, 0.5f, 0.12f, 0.30f)
        // B: closing head-on (centred, growing) → must be flagged.
        val bFills = listOf(0.20f, 0.34f, 0.50f, 0.70f, 0.90f)
        var falseFlag = false
        var bFlagged = false
        for ((i, bf) in bFills.withIndex()) {
            now = i * 100L
            val a = Detection("A", aBox, 0.9f, 3f, "person", 0)
            val b = person("B", 0.5f, bf)
            val approaching = tracker.update(listOf(a, b))
            if (approaching.contains("A")) falseFlag = true
            if (i == bFills.lastIndex) bFlagged = approaching.contains("B")
        }
        assertFalse("static A must not be flagged", falseFlag)
        assertTrue("moving B must be flagged", bFlagged)
    }
}
