package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.AlertLevel
import ai.genwhy.nobonk.model.Detection
import ai.genwhy.nobonk.model.NormBox
import org.junit.Assert.*
import org.junit.Test

class AuditRegressionTest {
    @Test fun clippedPersonHeightGrowthAloneDoesNotMeanApproaching() {
        for (topClipped in listOf(true, false)) {
            var now = 0L
            val tracker = ApproachTracker(clock = { now })
            for ((i, height) in listOf(.30f, .38f, .46f).withIndex()) {
                now = i * 100L
                val box = if (topClipped) NormBox(.4f, 0f, .6f, height)
                    else NormBox(.4f, 1f - height, .6f, 1f)
                val d = Detection("p", box, .9f, 2f, "person", 0)
                assertFalse("Clipped height growth is not enough evidence", tracker.update(listOf(d)).contains("p"))
                assertFalse(tracker.isImminent("p"))
            }
        }
    }

    @Test fun clippedPersonGrowingInBothDimensionsStillEscalates() {
        var now = 0L
        val tracker = ApproachTracker(clock = { now })
        var imminent = false
        for ((i, height) in listOf(.30f, .38f, .46f, .54f).withIndex()) {
            now = i * 100L
            val width = height * .4f
            val d = Detection("p", NormBox(.5f - width / 2f, 0f, .5f + width / 2f, height), .9f, 2f, "person", 0)
            tracker.update(listOf(d))
            imminent = imminent || tracker.isImminent("p")
        }
        assertTrue("A real closer must retain early warning", imminent)
        assertEquals(AlertLevel.HIGH, AlertPolicy.levelFor(NormBox(.4f, 0f, .6f, .9f), "person", 2f, false))
    }

    @Test fun briefCoveredOrSuppressedFrameCannotClaimANewPerson() {
        for (covered in listOf(true, false)) {
            val cadence = AlertCadence()
            assertTrue(cadence.shouldEmit(AlertLevel.HIGH, "a", 0, true))
            if (covered) cadence.interruptObservation()
            else assertFalse(cadence.shouldEmit(AlertLevel.HIGH, "a", 100, false))
            assertFalse(cadence.shouldEmit(AlertLevel.NONE, null, 200, true))
            assertFalse("A new tracker ID after interruption is not a new person", cadence.shouldEmit(AlertLevel.HIGH, "new-a", 1500, true))
            assertFalse(cadence.shouldEmit(AlertLevel.HIGH, "new-a", 5999, true))
            assertTrue(cadence.shouldEmit(AlertLevel.HIGH, "new-a", 6000, true))
            assertTrue("A genuinely new person can alert once continuity is restored", cadence.shouldEmit(AlertLevel.HIGH, "b", 7500, true))
        }
    }

    @Test fun interruptionDoesNotDelayEscalation() {
        val cadence = AlertCadence()
        assertTrue(cadence.shouldEmit(AlertLevel.LOW, "a", 0, true))
        cadence.interruptObservation()
        assertTrue(cadence.shouldEmit(AlertLevel.HIGH, "new-a", 400, true))
    }

    @Test fun clippedGrowthNeedsAtLeastHalfAsMuchRelativeWidthGrowth() {
        for (widthRatio in listOf(.45f, .51f)) {
            var now = 0L
            val tracker = ApproachTracker(clock = { now })
            var width = .12f
            var previousHeight = .30f
            var everImminent = false
            for ((i, height) in listOf(.30f, .40f, .50f, .60f).withIndex()) {
                now = i * 100L
                if (i > 0) width *= 1f + ((height - previousHeight) / previousHeight) * widthRatio
                val d = Detection("p", NormBox(.5f - width/2, 0f, .5f + width/2, height), .9f, 2f, "person", 0)
                tracker.update(listOf(d))
                everImminent = everImminent || tracker.isImminent("p")
                previousHeight = height
            }
            assertEquals("Corroboration boundary for ratio $widthRatio", widthRatio > .5f, everImminent)
        }
    }

    @Test fun uncorroboratedRevealClearsAnEstablishedApproachStreak() {
        var now = 0L
        val tracker = ApproachTracker(clock = { now })
        for ((i, height) in listOf(.30f, .40f, .50f).withIndex()) {
            now = i * 100L
            val width = height * .4f
            tracker.update(listOf(Detection("p", NormBox(.5f-width/2, 0f, .5f+width/2, height), .9f, 2f, "person", 0)))
        }
        assertTrue(tracker.isImminent("p"))
        now += 100
        val reveal = Detection("p", NormBox(.4f, 0f, .6f, .58f), .9f, 2f, "person", 0)
        assertFalse(tracker.update(listOf(reveal)).contains("p"))
        assertFalse(tracker.isImminent("p"))
    }

    @Test fun previousClippedBoxStillNeedsCorroborationAfterItEntersTheFrame() {
        var now = 0L
        val tracker = ApproachTracker(clock = { now })
        for ((i, box) in listOf(NormBox(.4f, 0f, .6f, .3f),
            NormBox(.4f, 0f, .6f, .38f), NormBox(.4f, .02f, .6f, .48f)).withIndex()) {
            now = i * 100L
            assertFalse(tracker.update(listOf(Detection("p", box, .9f, 2f, "person", 0))).contains("p"))
            assertFalse(tracker.isImminent("p"))
        }
    }
}
