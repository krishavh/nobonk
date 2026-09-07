package ai.genwhy.nobonk.service

import ai.genwhy.nobonk.model.AlertLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeIndicatorPolicyTest {
    @Test fun calmForNoneAndLow() {
        assertEquals(EdgeIndicatorPolicy.CALM, EdgeIndicatorPolicy.colorFor(AlertLevel.NONE, false))
        assertEquals(EdgeIndicatorPolicy.CALM, EdgeIndicatorPolicy.colorFor(AlertLevel.LOW, false))
    }
    @Test fun escalatesWithLevel() {
        assertEquals(EdgeIndicatorPolicy.MEDIUM, EdgeIndicatorPolicy.colorFor(AlertLevel.MEDIUM, false))
        assertEquals(EdgeIndicatorPolicy.HIGH, EdgeIndicatorPolicy.colorFor(AlertLevel.HIGH, false))
    }
    @Test fun blockedCameraOverridesEverything() {
        assertEquals(EdgeIndicatorPolicy.BLOCKED, EdgeIndicatorPolicy.colorFor(AlertLevel.HIGH, true))
    }
    @Test fun redrawOnlyOnChange() {
        assertFalse(EdgeIndicatorPolicy.shouldRedraw(EdgeIndicatorPolicy.CALM, EdgeIndicatorPolicy.CALM))
        assertTrue(EdgeIndicatorPolicy.shouldRedraw(EdgeIndicatorPolicy.CALM, EdgeIndicatorPolicy.HIGH))
    }
    @Test fun thinStrip() { assertTrue(EdgeIndicatorPolicy.THICKNESS_DP in 2..4) }
}
