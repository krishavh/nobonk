package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.NormBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxSmootherTest {
    private val a = NormBox(0.10f, 0.10f, 0.30f, 0.50f)
    private val b = NormBox(0.20f, 0.10f, 0.40f, 0.50f)

    @Test fun firstSightingIsPassedThrough() {
        assertEquals(a, BoxSmoother().smooth("t1", a, false, 0L))
    }
    @Test fun secondFrameMovesPartWayCalm() {
        val s = BoxSmoother(alphaCalm = 0.5f)
        s.smooth("t1", a, false, 0L)
        val out = s.smooth("t1", b, false, 100L)
        assertEquals(0.15f, out.left, 1e-6f); assertEquals(0.35f, out.right, 1e-6f)
    }
    @Test fun approachingTracksFaster() {
        val calm = BoxSmoother(); calm.smooth("t", a, false, 0L)
        val fast = BoxSmoother(); fast.smooth("t", a, true, 0L)
        val c = calm.smooth("t", b, false, 100L); val f = fast.smooth("t", b, true, 100L)
        assertTrue(f.left > c.left)
    }
    @Test fun staleTrackRestartsInsteadOfDraggingFromThePast() {
        val s = BoxSmoother(maxAgeMs = 500L)
        s.smooth("t1", a, false, 0L)
        assertEquals(b, s.smooth("t1", b, false, 2_000L))
    }
    @Test fun tracksAreIndependent() {
        val s = BoxSmoother()
        s.smooth("t1", a, false, 0L)
        assertEquals(b, s.smooth("t2", b, false, 0L))
    }
}
