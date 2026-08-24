package com.persondetection.android.ml

import com.persondetection.android.model.AlertLevel
import com.persondetection.android.model.Detection
import com.persondetection.android.model.NormBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-2 safety-calibration proofs (CALIBRATION_FINDINGS.md CRITICAL + HIGH):
 *
 *  1. A fast, head-on (on-bearing) closer reaches HIGH via the previously-DEAD
 *     time-to-contact (TTC) signal — even while its box fill stays BELOW the
 *     fill-only HIGH backstop, so the alarm is genuinely TTC-driven, not fill-driven.
 *  2. A laterally-drifting pass-by object (grows, but off the collision bearing) does
 *     NOT reach HIGH — the constant-bearing gate kills the crowded-sidewalk false alarm.
 *  3. A vehicle reaches HIGH via TTC (fill can never grow early enough at road speeds).
 *
 * These drive the real [ApproachTracker] frame-by-frame and feed its verdicts into the
 * real [AlertPolicy], mirroring exactly what DetectionEngine.process() now does.
 */
class TtcBearingCalibrationTest {

    /** Upright person box: height carries proximity, centred at [cx]. */
    private fun personBox(cx: Float, fill: Float): NormBox {
        val h = fill.coerceIn(0f, 1f)
        val w = (h * 0.4f).coerceAtMost(0.9f)
        return NormBox.fromCenter(cx, 0.5f, w, h)
    }

    /** Wide vehicle box: width carries proximity, centred at [cx]. */
    private fun carBox(cx: Float, fill: Float): NormBox {
        val w = fill.coerceIn(0f, 1f)
        val h = (w * 0.6f).coerceAtMost(1.0f)
        return NormBox.fromCenter(cx, 0.5f, w, h)
    }

    private fun det(id: String, box: NormBox, cls: String) =
        Detection(id, box, 0.9f, 1.5f, cls, 0)

    @Test fun `fast head-on closer reaches HIGH via TTC while fill stays below the HIGH backstop`() {
        var now = 0L
        val tracker = ApproachTracker(clock = { now })
        // Centred (on-bearing), fast growth, but capped at 0.56 — BELOW the person HIGH
        // fill backstop (0.60). Any HIGH here must therefore come from TTC, not fill.
        val fills = listOf(0.12f, 0.30f, 0.42f, 0.50f, 0.56f)

        var ttcDroveHigh = false
        for ((i, f) in fills.withIndex()) {
            now = i * 100L
            val d = det("p", personBox(0.5f, f), "person")
            val approaching = tracker.update(listOf(d)).contains("p")
            val imminent = tracker.isImminent("p")

            val withTtc  = AlertPolicy.levelFor(d.boundingBox, "person", 2.0f, approaching, imminent)
            val fillOnly = AlertPolicy.levelFor(d.boundingBox, "person", 2.0f, false, false)

            // Fill alone (no tracker help) must never reach HIGH in this sequence.
            assertNotEquals("fill-only must stay below HIGH at fill=$f", AlertLevel.HIGH, fillOnly)
            if (imminent && withTtc == AlertLevel.HIGH) ttcDroveHigh = true
        }
        assertTrue("TTC must force HIGH for a fast head-on closer", ttcDroveHigh)
    }

    @Test fun `laterally drifting pass-by object never reaches HIGH`() {
        var now = 0L
        val tracker = ApproachTracker(clock = { now })
        // Grows (closing in size) but the centre marches sideways out of the collision
        // cone — the object you'll harmlessly pass. Fill capped below the HIGH backstop.
        val cxs   = listOf(0.68f, 0.74f, 0.80f, 0.86f, 0.92f)
        val fills = listOf(0.20f, 0.34f, 0.46f, 0.54f, 0.58f)

        var everHigh = false
        var everApproaching = false
        for (i in cxs.indices) {
            now = i * 100L
            val d = det("x", personBox(cxs[i], fills[i]), "person")
            val approaching = tracker.update(listOf(d)).contains("x")
            val imminent = tracker.isImminent("x")
            val level = AlertPolicy.levelFor(d.boundingBox, "person", 2.0f, approaching, imminent)
            if (approaching) everApproaching = true
            if (level == AlertLevel.HIGH) everHigh = true
        }
        assertEquals("off-bearing pass-by must never be flagged approaching", false, everApproaching)
        assertEquals("off-bearing pass-by must never reach HIGH", false, everHigh)
    }

    @Test fun `vehicle reaches HIGH via TTC`() {
        var now = 0L
        val tracker = ApproachTracker(clock = { now })
        // Centred car closing fast, fill capped at 0.50 — BELOW the vehicle HIGH backstop
        // (0.55). HIGH must come from TTC.
        val fills = listOf(0.15f, 0.30f, 0.42f, 0.48f, 0.50f)

        var ttcDroveHigh = false
        for ((i, f) in fills.withIndex()) {
            now = i * 100L
            val d = det("v", carBox(0.5f, f), "car")
            val approaching = tracker.update(listOf(d)).contains("v")
            val imminent = tracker.isImminent("v")
            val withTtc  = AlertPolicy.levelFor(d.boundingBox, "car", 2.0f, approaching, imminent)
            val fillOnly = AlertPolicy.levelFor(d.boundingBox, "car", 2.0f, false, false)
            assertNotEquals("vehicle fill-only must stay below HIGH at fill=$f", AlertLevel.HIGH, fillOnly)
            if (imminent && withTtc == AlertLevel.HIGH) ttcDroveHigh = true
        }
        assertTrue("TTC must force HIGH for a closing vehicle", ttcDroveHigh)
    }
}
