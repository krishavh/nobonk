package ai.genwhy.nobonk.ml

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighReAlertMuteTest {
    @Test fun firstHighFiresThenMutesForWindow() {
        val m = HighReAlertMute(muteMs = 2_000)
        assertTrue(m.shouldEmit("t1", 0, canEmit = true))
        assertFalse(m.shouldEmit("t1", 500, canEmit = true))
        assertFalse(m.shouldEmit("t1", 1_999, canEmit = true))
        assertTrue(m.shouldEmit("t1", 2_000, canEmit = true))
    }
    @Test fun badAngleHighDoesNotConsumeTheWindow() {
        // Regression (review finding): a HIGH seen while off-angle must not delay the
        // first audible alert once the angle is corrected.
        val m = HighReAlertMute(muteMs = 2_000)
        assertFalse(m.shouldEmit("t1", 0, canEmit = false))
        assertTrue(m.shouldEmit("t1", 300, canEmit = true))     // fires immediately
    }
    @Test fun tracksAreIndependent() {
        val m = HighReAlertMute(muteMs = 2_000)
        assertTrue(m.shouldEmit("a", 0, true))
        assertTrue(m.shouldEmit("b", 100, true))
        assertFalse(m.shouldEmit("a", 200, true))
    }
    @Test fun untrackedAlwaysFiresWhenAllowed() {
        val m = HighReAlertMute()
        assertTrue(m.shouldEmit(null, 0, true)); assertTrue(m.shouldEmit(null, 10, true))
        assertFalse(m.shouldEmit(null, 20, false))
    }
}
