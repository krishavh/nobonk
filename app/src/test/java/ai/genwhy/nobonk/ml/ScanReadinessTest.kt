package ai.genwhy.nobonk.ml

import org.junit.Assert.*
import org.junit.Test

class ScanReadinessTest {
    @Test fun `warmup alone is insufficient and usable frames must span time`() {
        val readiness = ScanReadiness()
        assertFalse(readiness.observe(1000, true))
        assertFalse(readiness.observe(1100, true))
        assertFalse(readiness.observe(1200, true))
        assertFalse(readiness.observe(1599, true))
        assertTrue(readiness.observe(1600, true))
        assertTrue(readiness.observe(1900, true))
    }

    @Test fun `duration alone does not replace minimum frame count`() {
        val readiness = ScanReadiness()
        assertFalse(readiness.observe(0, true))
        assertFalse(readiness.observe(1000, true))
        assertTrue(readiness.observe(1100, true))
    }

    @Test fun `blocked frame clears readiness and requires fresh qualification`() {
        val readiness = ready()
        assertFalse(readiness.observe(900, false))
        assertFalse(readiness.observe(1000, true))
        assertFalse(readiness.observe(1300, true))
        assertTrue(readiness.observe(1600, true))
    }

    @Test fun `stop or scope change reset does not inherit previous readiness`() {
        val readiness = ready()
        readiness.reset()
        assertFalse(readiness.observe(1000, true))
        assertFalse(readiness.observe(1300, true))
        assertTrue(readiness.observe(1600, true))
    }

    @Test fun `camera frame gap resets ready state and starts fresh streak`() {
        val readiness = ready()
        assertFalse(readiness.observe(5601, true))
        assertFalse(readiness.observe(5901, true))
        assertTrue(readiness.observe(6201, true))
    }

    @Test fun `maximum allowed gap remains valid`() {
        val readiness = ready()
        assertTrue(readiness.observe(5600, true))
    }

    @Test fun `regressed or negative timestamps cannot assert readiness`() {
        val readiness = ready()
        assertFalse(readiness.observe(599, true))
        assertFalse(readiness.observe(-1, true))
        assertFalse(readiness.observe(1000, true))
        assertFalse(readiness.observe(1300, true))
        assertTrue(readiness.observe(1600, true))
    }

    @Test fun `repeated timestamps cannot satisfy the minimum duration`() {
        val readiness = ScanReadiness()
        repeat(20) { assertFalse(readiness.observe(1000, true)) }
        assertFalse(readiness.observe(1600, true))
        assertTrue(readiness.observe(1700, true))
    }

    private fun ready(): ScanReadiness = ScanReadiness().also {
        assertFalse(it.observe(0, true))
        assertFalse(it.observe(300, true))
        assertTrue(it.observe(600, true))
    }
}
