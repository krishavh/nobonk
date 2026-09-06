package ai.genwhy.nobonk.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionGateTest {
    @Test fun unknownReadsAsMoving() {
        assertEquals(0L, MotionGate().stationaryMs(10_000L))
    }
    @Test fun stillPhoneAccumulatesStationaryTime() {
        val g = MotionGate()
        var t = 0L
        repeat(50) { g.push(9.81f + (if (it % 2 == 0) 0.05f else -0.05f), t); t += 100 }
        assertTrue(g.stationaryMs(t) >= 4_800L)
    }
    @Test fun stepResetsStationaryTime() {
        val g = MotionGate()
        var t = 0L
        repeat(30) { g.push(9.81f, t); t += 100 }
        g.push(12.5f, t)              // a step
        assertEquals(0L, g.stationaryMs(t))
        assertEquals(300L, g.stationaryMs(t + 300))
    }
    @Test fun slowGravityDriftIsNotMotion() {
        val g = MotionGate()
        var t = 0L
        var mag = 9.6f
        repeat(200) { g.push(mag, t); mag += 0.002f; t += 50 }   // tilt drift, 0.4 m/s² over 10 s
        assertTrue(g.stationaryMs(t) > 9_000L)
    }
    @Test fun garbageIgnored() {
        val g = MotionGate(); g.push(9.8f, 0L); g.push(Float.NaN, 100L); g.push(Float.POSITIVE_INFINITY, 200L)
        assertEquals(1_000L, g.stationaryMs(1_000L))
    }
}
