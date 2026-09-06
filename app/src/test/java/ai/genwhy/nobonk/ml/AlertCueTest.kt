package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.AlertLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertCueTest {
    @Test fun noneHasNoCue() {
        assertNull(AlertCue.pcm(AlertLevel.NONE))
        assertNull(AlertCue.shapeFor(AlertLevel.NONE))
    }
    @Test fun bufferLengthMatchesShape() {
        val s = AlertCue.shapeFor(AlertLevel.HIGH)!!
        val pcm = AlertCue.pcm(AlertLevel.HIGH)!!
        val pulseN = AlertCue.SAMPLE_RATE * s.pulseMs / 1000
        val gapN = AlertCue.SAMPLE_RATE * s.gapMs / 1000
        assertEquals((s.pulses * pulseN + (s.pulses - 1) * gapN) * 2, pcm.size)
    }
    @Test fun highIsLouderAndLongerThanLow() {
        val hi = AlertCue.pcm(AlertLevel.HIGH)!!
        val lo = AlertCue.pcm(AlertLevel.LOW)!!
        assertTrue(hi.size > lo.size)
        assertTrue(AlertCue.channelRms(hi, 0) > AlertCue.channelRms(lo, 0))
    }
    @Test fun centreIsBalanced() {
        val pcm = AlertCue.pcm(AlertLevel.HIGH, AlertCue.panFor(0.5f))!!
        val l = AlertCue.channelRms(pcm, 0); val r = AlertCue.channelRms(pcm, 1)
        assertTrue(kotlin.math.abs(l - r) / l < 0.02)
    }
    @Test fun leftObjectIsHeardOnTheLeft() {
        val pcm = AlertCue.pcm(AlertLevel.HIGH, AlertCue.panFor(0.05f))!!
        assertTrue(AlertCue.channelRms(pcm, 0) > AlertCue.channelRms(pcm, 1) * 3)
    }
    @Test fun rightObjectIsHeardOnTheRight() {
        val pcm = AlertCue.pcm(AlertLevel.MEDIUM, AlertCue.panFor(0.95f))!!
        assertTrue(AlertCue.channelRms(pcm, 1) > AlertCue.channelRms(pcm, 0) * 3)
    }
    @Test fun deadZoneKeepsNearCentreStraightAhead() {
        assertEquals(0f, AlertCue.panFor(0.45f)); assertEquals(0f, AlertCue.panFor(0.58f))
        assertEquals(1f, AlertCue.panFor(1.2f)); assertEquals(-1f, AlertCue.panFor(-0.3f))
    }
    @Test fun neverClips() {
        for (lvl in listOf(AlertLevel.HIGH, AlertLevel.MEDIUM, AlertLevel.LOW))
            for (pan in listOf(-1f, 0f, 1f)) {
                val pcm = AlertCue.pcm(lvl, pan)!!
                assertTrue(pcm.none { it == Short.MAX_VALUE || it == Short.MIN_VALUE })
            }
    }
}

class BearingSideTest {
    @Test fun sides() {
        assertEquals(AlertCue.Side.LEFT, AlertCue.sideFor(-0.6f))
        assertEquals(AlertCue.Side.RIGHT, AlertCue.sideFor(0.6f))
        assertEquals(AlertCue.Side.AHEAD, AlertCue.sideFor(0.1f))
        assertEquals(AlertCue.Side.AHEAD, AlertCue.sideFor(null))
    }
}
