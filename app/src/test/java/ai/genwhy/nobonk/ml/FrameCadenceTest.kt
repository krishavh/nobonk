package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.AlertLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCadenceTest {
    @Test fun activeAlertRunsFullRateEvenOnLowBattery() {
        assertEquals(FrameCadence.BASE_MS, FrameCadence.intervalMs(AlertLevel.HIGH, true, 0, 15))
        assertEquals(FrameCadence.BASE_MS, FrameCadence.intervalMs(AlertLevel.MEDIUM, true, 99_000, 5))
    }
    @Test fun detectionsWithoutAlertKeepFullRate() {
        assertEquals(FrameCadence.BASE_MS, FrameCadence.intervalMs(AlertLevel.NONE, true, 50_000, 80))
    }
    @Test fun idleBacksOffInSteps() {
        assertEquals(FrameCadence.BASE_MS, FrameCadence.intervalMs(AlertLevel.NONE, false, 1_000, 80))
        assertEquals(FrameCadence.BASE_MS * 2, FrameCadence.intervalMs(AlertLevel.NONE, false, 3_000, 80))
        assertEquals(FrameCadence.BASE_MS * 3, FrameCadence.intervalMs(AlertLevel.NONE, false, 12_000, 80))
    }
    @Test fun blockedLensIdlesAtHalfSecondRegardless() {
        assertEquals(FrameCadence.BLOCKED_MS, FrameCadence.intervalMs(AlertLevel.NONE, false, 0, 100, cameraBlocked = true))
        assertEquals(FrameCadence.BLOCKED_MS, FrameCadence.intervalMs(AlertLevel.HIGH, true, 0, 5, cameraBlocked = true))
    }
    @Test fun standingStillWithEmptyFrameDropsToTwoFps() {
        assertEquals(FrameCadence.STATIONARY_MS, FrameCadence.intervalMs(AlertLevel.NONE, false, 1_000, 90, stationaryMs = 15_000))
        assertEquals(FrameCadence.BASE_MS, FrameCadence.intervalMs(AlertLevel.NONE, true, 1_000, 90, stationaryMs = 15_000))
        assertEquals(FrameCadence.BASE_MS, FrameCadence.intervalMs(AlertLevel.LOW, false, 1_000, 90, stationaryMs = 15_000))
        assertEquals(500L, FrameCadence.intervalMs(AlertLevel.NONE, false, 20_000, 10, stationaryMs = 15_000))   // max(450 low-battery idle, 500 stationary)
    }
    @Test fun lowBatteryStretchesIdleOnly() {
        val idle = FrameCadence.intervalMs(AlertLevel.NONE, false, 20_000, 10)
        assertEquals((FrameCadence.BASE_MS * 3 * FrameCadence.LOW_BATTERY_FACTOR).toLong(), idle)
        assertTrue(idle <= 500L)   // still ≥ 2 fps — never "asleep"
    }
}
