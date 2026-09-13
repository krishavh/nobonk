package ai.genwhy.nobonk.service

import ai.genwhy.nobonk.motion.SustainedWalkingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pure JVM tests for the walking-mode lifecycle: arming, sustained-step qualification,
 * Stop dominance over every queued callback, and the race between Stop and walking/model
 * completion. Only [ServiceLifecycle] and [SustainedWalkingPolicy] are exercised — no
 * Android runtime, no mocking.
 */
class WalkingLifecycleTest {

    private lateinit var life: ServiceLifecycle

    @Before
    fun setUp() {
        life = ServiceLifecycle()
    }

    // ------------------------------------------------------------------
    // Arming
    // ------------------------------------------------------------------

    @Test
    fun `arm with walking flag enters WAITING_FOR_WALKING`() {
        assertTrue(life.onStartRequested(waitForWalking = true))
        assertEquals(ServiceLifecycle.Phase.WAITING_FOR_WALKING, life.phase)
        assertNull(life.stopReason)
    }

    @Test
    fun `duplicate arm while waiting is rejected and does not resurrect`() {
        assertTrue(life.onStartRequested(waitForWalking = true))
        assertFalse(life.onStartRequested(waitForWalking = true))
        assertEquals(ServiceLifecycle.Phase.WAITING_FOR_WALKING, life.phase)
    }

    @Test
    fun `duplicate arm after stop cannot resurrect the session`() {
        assertTrue(life.onStartRequested(waitForWalking = true))
        life.stop(ServiceLifecycle.StopReason.USER)
        assertFalse(life.onStartRequested(waitForWalking = true))
        assertEquals(ServiceLifecycle.Phase.STOPPED, life.phase)
        assertEquals(ServiceLifecycle.StopReason.USER, life.stopReason)
    }

    @Test
    fun `arm without walking flag skips waiting phase`() {
        assertTrue(life.onStartRequested(waitForWalking = false))
        assertEquals(ServiceLifecycle.Phase.LOADING_MODEL, life.phase)
    }

    // ------------------------------------------------------------------
    // Waiting phase: nothing may proceed
    // ------------------------------------------------------------------

    @Test
    fun `waiting phase blocks model load camera frames and alerts`() {
        assertTrue(life.onStartRequested(waitForWalking = true))
        assertFalse(life.onModelLoaded())
        assertFalse(life.mayBindCamera())
        assertFalse(life.mayProcessFrames())
        assertFalse(life.mayPostAlerts())
        assertEquals(ServiceLifecycle.Phase.WAITING_FOR_WALKING, life.phase)
    }

    @Test
    fun `walking confirmation is the only exit from waiting`() {
        assertTrue(life.onStartRequested(waitForWalking = true))
        assertTrue(life.onWalkingConfirmed())
        assertEquals(ServiceLifecycle.Phase.LOADING_MODEL, life.phase)
    }

    @Test
    fun `walking confirmation is accepted exactly once`() {
        assertTrue(life.onStartRequested(waitForWalking = true))
        assertTrue(life.onWalkingConfirmed())
        assertFalse(life.onWalkingConfirmed())
        assertEquals(ServiceLifecycle.Phase.LOADING_MODEL, life.phase)
    }

    @Test
    fun `walking confirmation rejected when not armed for walking`() {
        assertTrue(life.onStartRequested(waitForWalking = false))
        assertFalse(life.onWalkingConfirmed())
        assertEquals(ServiceLifecycle.Phase.LOADING_MODEL, life.phase)
    }

    @Test
    fun `walking confirmation rejected after stop`() {
        assertTrue(life.onStartRequested(waitForWalking = true))
        life.stop(ServiceLifecycle.StopReason.USER)
        assertFalse(life.onWalkingConfirmed())
        assertEquals(ServiceLifecycle.Phase.STOPPED, life.phase)
    }

    // ------------------------------------------------------------------
    // SustainedWalkingPolicy qualification
    // ------------------------------------------------------------------

    private val stepInterval = 1_500_000_000L // 1.5 s between steps

    /** Feed `count` steps ending at `endNanos`; returns the policy's trigger result. */
    private fun feedSteps(policy: SustainedWalkingPolicy, count: Int, endNanos: Long): Boolean {
        var triggered = false
        for (i in 0 until count) {
            val t = endNanos - (count - 1 - i) * stepInterval
            if (policy.onStep(t, t)) triggered = true
        }
        return triggered
    }

    @Test
    fun `policy qualifies after sustained steps`() {
        val policy = SustainedWalkingPolicy()
        // 16 steps at 1.5 s spacing span 22.5 s >= 20 s sustained window.
        assertTrue(feedSteps(policy, 16, 22_500_000_000L))
    }

    @Test
    fun `policy trigger is terminal and cancel is terminal`() {
        val policy = SustainedWalkingPolicy()
        assertTrue(feedSteps(policy, 16, 22_500_000_000L))
        // Post-trigger events (e.g. a queued sensor callback) never re-trigger.
        assertFalse(policy.onStep(24_000_000_000L, 24_000_000_000L))
        policy.cancel()
        assertFalse(policy.onStep(26_000_000_000L, 26_000_000_000L))
    }

    @Test
    fun `policy cancel before qualification denies all later steps`() {
        val policy = SustainedWalkingPolicy()
        policy.cancel()
        assertFalse(feedSteps(policy, 20, 30_000_000_000L))
    }

    // ------------------------------------------------------------------
    // Policy feeds lifecycle; Stop denies late callbacks
    // ------------------------------------------------------------------

    @Test
    fun `real qualification advances lifecycle then stop denies late confirmation`() {
        val policy = SustainedWalkingPolicy()
        assertTrue(life.onStartRequested(waitForWalking = true))
        // Actual policy qualification (not a hand-faked transition).
        assertTrue(feedSteps(policy, 16, 22_500_000_000L))
        assertTrue(life.onWalkingConfirmed())
        assertEquals(ServiceLifecycle.Phase.LOADING_MODEL, life.phase)
        // Stop wins; a queued walking callback arriving afterwards is denied.
        life.stop(ServiceLifecycle.StopReason.USER)
        assertFalse(life.onWalkingConfirmed())
        assertEquals(ServiceLifecycle.Phase.STOPPED, life.phase)
    }

    @Test
    fun `stop before queued walking callback is terminal`() {
        val policy = SustainedWalkingPolicy()
        assertTrue(life.onStartRequested(waitForWalking = true))
        assertTrue(feedSteps(policy, 16, 22_500_000_000L))
        // Stop lands before the (already queued) walking callback runs.
        life.stop(ServiceLifecycle.StopReason.USER)
        assertFalse(life.onWalkingConfirmed())
        assertFalse(life.onModelLoaded())
        assertFalse(life.mayBindCamera())
        assertFalse(life.mayProcessFrames())
        assertFalse(life.mayPostAlerts())
        assertEquals(ServiceLifecycle.Phase.STOPPED, life.phase)
    }

    // ------------------------------------------------------------------
    // Stop reason: first wins
    // ------------------------------------------------------------------

    @Test
    fun `first stop reason wins`() {
        assertTrue(life.onStartRequested(waitForWalking = true))
        life.stop(ServiceLifecycle.StopReason.HANDOFF)
        life.stop(ServiceLifecycle.StopReason.USER)
        assertEquals(ServiceLifecycle.StopReason.HANDOFF, life.stopReason)
    }

    @Test
    fun `stop is idempotent and keeps the first reason`() {
        assertTrue(life.onStartRequested(waitForWalking = true))
        life.stop(ServiceLifecycle.StopReason.USER)
        life.stop(ServiceLifecycle.StopReason.HANDOFF)
        life.stop(ServiceLifecycle.StopReason.USER)
        assertEquals(ServiceLifecycle.StopReason.USER, life.stopReason)
        assertEquals(ServiceLifecycle.Phase.STOPPED, life.phase)
    }

    // ------------------------------------------------------------------
    // Concurrency: real Stop vs walking / model completion
    // ------------------------------------------------------------------

    /** Both workers and the calling test rendezvous; futures propagate worker failures. */
    private fun race(first: () -> Unit, second: () -> Unit) {
        val pool = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(3)
        try {
            val a = pool.submit { barrier.await(5, TimeUnit.SECONDS); first() }
            val b = pool.submit { barrier.await(5, TimeUnit.SECONDS); second() }
            barrier.await(5, TimeUnit.SECONDS)
            a.get(5, TimeUnit.SECONDS)
            b.get(5, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
            assertTrue("Workers must terminate", pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun assertTerminal(session: ServiceLifecycle) {
        assertEquals(ServiceLifecycle.Phase.STOPPED, session.phase)
        assertFalse(session.onWalkingConfirmed())
        assertFalse(session.onModelLoaded())
        session.onCameraBound()
        assertFalse(session.mayBindCamera())
        assertFalse(session.mayProcessFrames())
        assertFalse(session.mayPostAlerts())
        assertFalse(session.onStartRequested(waitForWalking = true))
    }

    @Test
    fun `concurrent stop versus walking confirmation remains terminal`() {
        repeat(50) {
            val session = ServiceLifecycle()
            assertTrue(session.onStartRequested(waitForWalking = true))
            race({ session.stop(ServiceLifecycle.StopReason.USER) }, {
                if (session.onWalkingConfirmed() && session.onModelLoaded()) session.onCameraBound()
            })
            assertTerminal(session)
            assertEquals(ServiceLifecycle.StopReason.USER, session.stopReason)
        }
    }

    @Test
    fun `concurrent stop versus model completion cannot bind after stop`() {
        repeat(50) {
            val session = ServiceLifecycle()
            assertTrue(session.onStartRequested(waitForWalking = true))
            assertTrue(session.onWalkingConfirmed())
            race({ session.stop(ServiceLifecycle.StopReason.HANDOFF) }, {
                if (session.onModelLoaded()) session.onCameraBound()
            })
            assertTerminal(session)
            assertEquals(ServiceLifecycle.StopReason.HANDOFF, session.stopReason)
        }
    }

    @Test
    fun `stop racing real qualification cancels future events`() {
        repeat(50) {
            val session = ServiceLifecycle()
            val policy = SustainedWalkingPolicy()
            assertTrue(session.onStartRequested(waitForWalking = true))
            race({ session.stop(ServiceLifecycle.StopReason.USER); policy.cancel() }, {
                if (feedSteps(policy, 16, 22_500_000_000L)) session.onWalkingConfirmed()
            })
            assertTerminal(session)
            assertFalse(policy.onStep(30_000_000_000L, 30_000_000_000L))
        }
    }

    @Test
    fun `everStarted and stoppedByUser reflect a real walking session`() {
        assertTrue(life.onStartRequested(waitForWalking = true))
        assertTrue(life.everStarted)
        life.stop(ServiceLifecycle.StopReason.USER)
        assertTrue(life.stoppedByUser)
    }

    @Test
    fun `stop-only instance is not a user stop of a session`() {
        life.stop(ServiceLifecycle.StopReason.USER)
        assertFalse(life.everStarted)
        assertFalse(life.stoppedByUser)
    }
}
