package com.persondetection.android.ml

import com.persondetection.android.model.AlertLevel
import com.persondetection.android.model.NormBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the audit's Critical findings ML-01/ML-02 are fixed: the HIGH alarm is now
 * physically reachable for a person AND for a car at every threshold preset, and the
 * full NONE→LOW→MEDIUM→HIGH ladder is traversable as an object approaches.
 */
class AlertPolicyTest {

    private val presets = listOf(0.5f, 1.0f, 2.0f, 3.5f)

    private fun personBox(fill: Float): NormBox {
        val h = fill
        val w = (h * 0.4f).coerceAtMost(0.9f)
        return NormBox.fromCenter(0.5f, 0.5f, w, h)
    }

    private fun carBox(fill: Float): NormBox {
        val w = fill
        val h = (w * 0.6f).coerceAtMost(1.0f)
        return NormBox.fromCenter(0.5f, 0.5f, w, h)
    }

    @Test fun `HIGH reachable for a person at every threshold preset`() {
        for (t in presets) {
            assertEquals(
                "person filling frame must be HIGH at ${t}m",
                AlertLevel.HIGH,
                AlertPolicy.levelFor(personBox(0.98f), "person", t, false)
            )
        }
    }

    @Test fun `HIGH reachable for a car at every threshold preset`() {
        for (t in presets) {
            assertEquals(
                "car filling frame must be HIGH at ${t}m",
                AlertLevel.HIGH,
                AlertPolicy.levelFor(carBox(0.98f), "car", t, false)
            )
        }
    }

    @Test fun `person ladder is monotonic and spans NONE to HIGH`() {
        for (t in presets) {
            val levels = listOf(0.05f, 0.30f, 0.55f, 0.98f).map {
                AlertPolicy.levelFor(personBox(it), "person", t, false)
            }
            assertTrue("monotone @${t}m: $levels", levels.zipWithNext().all { (a, b) -> b.ordinal >= a.ordinal })
            assertEquals("tiny is NONE @${t}m", AlertLevel.NONE, levels.first())
            assertEquals("frame-filling is HIGH @${t}m", AlertLevel.HIGH, levels.last())
        }
    }

    @Test fun `larger threshold preset warns earlier`() {
        val fill = 0.35f
        val wide = AlertPolicy.levelFor(personBox(fill), "person", 3.5f, false)
        val narrow = AlertPolicy.levelFor(personBox(fill), "person", 0.5f, false)
        assertTrue("3.5m ($wide) >= 0.5m ($narrow) at fill=$fill", wide.ordinal >= narrow.ordinal)
    }

    @Test fun `approaching escalates one level`() {
        val staticLevel = AlertPolicy.levelFor(personBox(0.50f), "person", 2.0f, false)
        val movingLevel = AlertPolicy.levelFor(personBox(0.50f), "person", 2.0f, true)
        assertEquals("static should be MEDIUM", AlertLevel.MEDIUM, staticLevel)
        assertEquals("approaching bumps to HIGH", AlertLevel.HIGH, movingLevel)
    }
}
