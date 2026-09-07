package ai.genwhy.nobonk.ml

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProviderCancellationTest {
    private class Resource(val name: String) : AutoCloseable {
        val closes = AtomicInteger()
        override fun close() { check(closes.incrementAndGet() == 1) { "Double close" } }
    }

    @Test fun alreadyCancelledStartupNeverOpensAnyProviderIncludingCached() {
        for (cached in listOf(null, "CPU")) {
            val job = Job().also { it.cancel() }
            assertThrows(CancellationException::class.java) {
                ProviderSelection.select<Resource>(listOf("CPU", "XNNPACK"), cached,
                    create = { error("Must not create after cancellation") },
                    verify = { error("Must not verify") }, measure = { error("Must not time") },
                    checkActive = { job.ensureActive() })
            }
        }
    }

    @Test fun cancellationDuringNativeCreateClosesReturnedResourceBeforeVerification() {
        val job = Job(); val opened = mutableListOf<Resource>()
        assertThrows(CancellationException::class.java) {
            ProviderSelection.select(listOf("CPU", "XNNPACK"), null,
                create = { Resource(it).also { r -> opened.add(r); job.cancel() } },
                verify = { error("Cancelled create must not start verification") },
                measure = { error("Must not time") }, checkActive = { job.ensureActive() })
        }
        assertEquals(listOf("CPU"), opened.map { it.name })
        assertEquals(1, opened.single().closes.get())
    }

    @Test fun cancellationDuringCachedVerificationDoesNotBecomeFallback() {
        val job = Job(); val opened = mutableListOf<Resource>()
        assertThrows(CancellationException::class.java) {
            ProviderSelection.select(listOf("CPU", "XNNPACK"), "XNNPACK",
                create = { Resource(it).also(opened::add) }, verify = { job.cancel() },
                measure = { error("Must not benchmark a cancelled cache verification") },
                checkActive = { job.ensureActive() })
        }
        assertEquals(listOf("XNNPACK"), opened.map { it.name })
        assertEquals(1, opened.single().closes.get())
    }

    @Test fun cancellationAfterAnyTimedProbeStopsRemainingWorkWithoutReturningCacheChoice() {
        for (cancelAt in 1..3) {
            val job = Job(); val opened = mutableListOf<Resource>(); var measures = 0
            var committedChoice = false
            assertThrows(CancellationException::class.java) {
                ProviderSelection.select(listOf("CPU", "XNNPACK"), null,
                    create = { Resource(it).also(opened::add) }, verify = {},
                    measure = { if (++measures == cancelAt) job.cancel(); 10.0 },
                    checkActive = { job.ensureActive() })
                committedChoice = true // the caller may only cache a completed selection
            }
            assertFalse(committedChoice)
            assertEquals(cancelAt, measures)
            assertEquals(listOf("CPU"), opened.map { it.name })
            assertEquals(1, opened.single().closes.get())
        }
    }

    @Test fun cancellationAfterCandidateClosePreventsOpeningNextCandidate() {
        val job = Job(); var creates = 0; var closes = 0
        assertThrows(CancellationException::class.java) {
            ProviderSelection.select(listOf("CPU", "XNNPACK"), null,
                create = { creates++; AutoCloseable { closes++; job.cancel() } },
                verify = {}, measure = { 10.0 }, checkActive = { job.ensureActive() })
        }
        assertEquals(1, creates)
        assertEquals(1, closes)
    }

    @Test fun cancellationDuringWinnerRecreationClosesEveryCandidateExactlyOnce() {
        val job = Job(); val opened = mutableListOf<Resource>()
        assertThrows(CancellationException::class.java) {
            ProviderSelection.select(listOf("CPU", "XNNPACK"), null,
                create = {
                    Resource(it).also { r -> opened.add(r); if (opened.size == 3) job.cancel() }
                }, verify = {}, measure = { if (it.name == "XNNPACK") 10.0 else 20.0 },
                checkActive = { job.ensureActive() })
        }
        assertEquals(listOf("CPU", "XNNPACK", "XNNPACK"), opened.map { it.name })
        assertTrue(opened.all { it.closes.get() == 1 })
    }

    @Test fun providerCancellationExceptionIsNeverTreatedAsUnsupportedProvider() {
        val cause = CancellationException("Request was replaced"); var creates = 0
        val thrown = assertThrows(CancellationException::class.java) {
            ProviderSelection.select<Resource>(listOf("CPU", "XNNPACK"), null,
                create = { creates++; throw cause }, verify = {}, measure = { 10.0 })
        }
        assertSame(cause, thrown)
        assertEquals(1, creates)
    }

    @Test fun finalProviderFailureAfterStopStillPropagatesCancellation() {
        for (failureStage in listOf("verify", "measure", "winner")) {
            val job = Job(); val opened = mutableListOf<Resource>()
            fun failIf(stage: String) {
                if (failureStage == stage) {
                    job.cancel()
                    throw IllegalStateException("Native operation failed after Stop")
                }
            }
            assertThrows(CancellationException::class.java) {
                ProviderSelection.select(listOf("CPU"), null,
                    create = { Resource(it).also(opened::add) },
                    verify = { failIf(if (opened.size == 2) "winner" else "verify") },
                    measure = { failIf("measure"); 10.0 }, checkActive = { job.ensureActive() })
            }
            assertEquals(if (failureStage == "winner") 2 else 1, opened.size)
            assertTrue(opened.all { it.closes.get() == 1 })
        }
    }

    @Test fun cancellingWhileNativeCallIsBlockedNeverClosesUntilThatCallReturns() {
        val job = Job(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val nativeReturned = CountDownLatch(1); val closes = AtomicInteger(); val creates = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val task = executor.submit<Unit> {
                ProviderSelection.select(listOf("CPU", "XNNPACK"), null,
                    create = { creates.incrementAndGet(); AutoCloseable {
                        assertEquals("Native call must have finished before close", 0L, nativeReturned.count)
                        assertEquals(1, closes.incrementAndGet())
                    } }, verify = {}, measure = {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        nativeReturned.countDown()
                        10.0
                    }, checkActive = { job.ensureActive() })
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel()
            assertEquals("Stop cannot release an in-flight native session", 0, closes.get())
            release.countDown()
            val failure = assertThrows(ExecutionException::class.java) { task.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is CancellationException)
            assertEquals(1, creates.get())
            assertEquals(1, closes.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun freshStartupAfterCancellationCanReturnAndReuseVerifiedCacheNormally() {
        val stopped = Job().also { it.cancel() }
        assertThrows(CancellationException::class.java) {
            ProviderSelection.select<Resource>(listOf("CPU", "XNNPACK"), null,
                create = { error("Stopped") }, verify = {}, measure = { 10.0 },
                checkActive = { stopped.ensureActive() })
        }
        val active = Job(); val opened = mutableListOf<Resource>(); var verified = 0
        val choice = ProviderSelection.select(listOf("CPU", "XNNPACK"), "CPU",
            create = { Resource(it).also(opened::add) }, verify = { verified++ },
            measure = { error("A valid restart cache must skip benchmarking") },
            checkActive = { active.ensureActive() })
        assertTrue(choice.cached)
        assertEquals(1, verified)
        assertEquals(listOf("CPU"), opened.map { it.name })
        assertEquals(0, choice.resource.closes.get())
        choice.resource.close()
        assertEquals(1, choice.resource.closes.get())
    }
}
