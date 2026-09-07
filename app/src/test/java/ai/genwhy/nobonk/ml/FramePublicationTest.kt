package ai.genwhy.nobonk.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real-thread regressions for the foreground Stop races: inference blocked across Stop+Start,
 * and a result queued for main-thread publication across a Stop.
 */
class FramePublicationTest {
    /** Simulates a frame: captures gen, blocks in "inference", then asks whether cues/publication are allowed. */
    private class Frame(val session: ScanSession) {
        val gen = session.current()
        val inferenceDone = CountDownLatch(1)
        var cuesAllowed = false; var published = false; var historyCommitted = false
        fun run(cueCheck: () -> Boolean, publish: () -> Unit) {
            inferenceDone.await(2, TimeUnit.SECONDS)
            cuesAllowed = cueCheck()                       // engine-side per-frame validity
            if (!session.isCurrent(gen)) return
            publish()                                      // main-thread boundary
        }
    }

    @Test fun stopThenStartWhileInferenceBlockedEmitsNothingFromTheStaleFrame() {
        val s = ScanSession(); val f = Frame(s)
        val t = Thread { f.run(cueCheck = { s.isCurrent(f.gen) }, publish = { f.published = true }) }; t.start()
        s.stop(); s.start()                                 // user Stop, then Start, while frame A is still inferring
        f.inferenceDone.countDown(); t.join()
        assertFalse(f.cuesAllowed); assertFalse(f.published)
        assertTrue(s.active)                                // the new session is live for new frames
    }
    @Test fun resultQueuedForMainAcrossStopIsDroppedAtTheBoundary() {
        val s = ScanSession(); val f = Frame(s)
        val published = AtomicInteger(0)
        // Simulate: inference finished (gen still current), then Stop is queued on Main BEFORE our publication runs.
        val cueOk = s.isCurrent(f.gen); assertTrue(cueOk)
        s.stop()                                            // Stop reaches Main first
        if (s.isCurrent(f.gen)) { published.incrementAndGet(); f.historyCommitted = true }   // our queued publication re-checks
        assertEquals(0, published.get()); assertFalse(f.historyCommitted)
    }
    @Test fun currentFramePublishesAndCommitsHistory() {
        val s = ScanSession(); val f = Frame(s)
        f.inferenceDone.countDown()
        f.run(cueCheck = { s.isCurrent(f.gen) }, publish = { f.published = true; f.historyCommitted = true })
        assertTrue(f.cuesAllowed); assertTrue(f.published); assertTrue(f.historyCommitted)
    }
}
