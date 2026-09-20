package ai.genwhy.nobonk.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class RetiringResourceTest {
    @Test fun retirementRejectsAdmissionAndWaitsForOwnFrame() {
        val closes = AtomicInteger(); val r = RetiringResource("engine") { closes.incrementAndGet() }
        assertEquals("engine", r.acquire()); assertNull(r.acquire())
        r.retire(); r.retire(); assertNull(r.acquire()); assertEquals(0, closes.get())
        r.release(); r.retire(); assertEquals(1, closes.get())
    }
    @Test fun idleEngineClosesImmediatelyOnce() {
        val closes = AtomicInteger(); val r = RetiringResource("idle") { closes.incrementAndGet() }
        r.retire(); r.retire(); assertEquals(1, closes.get()); assertNull(r.acquire())
    }
    @Test fun oldFrameCompletionCannotCloseNewFrameEngine() {
        val closed = mutableListOf<String>()
        val old = RetiringResource("old") { closed.add(it) }
        val fresh = RetiringResource("fresh") { closed.add(it) }
        old.acquire(); old.retire(); fresh.acquire(); fresh.retire()
        old.release(); assertEquals(listOf("old"), closed)
        fresh.release(); assertEquals(listOf("old", "fresh"), closed)
    }
    @Test fun retirementRacingFrameCompletionClosesExactlyOnce() {
        repeat(300) {
            val closes = AtomicInteger(); val r = RetiringResource(it) { closes.incrementAndGet() }
            r.acquire(); val start = CountDownLatch(1)
            val retire = Thread { start.await(); r.retire() }
            val frame = Thread { start.await(); r.release() }
            retire.start(); frame.start(); start.countDown(); retire.join(); frame.join()
            assertEquals(1, closes.get()); assertNull(r.acquire())
        }
    }
}
