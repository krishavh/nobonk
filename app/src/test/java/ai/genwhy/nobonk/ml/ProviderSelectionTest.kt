package ai.genwhy.nobonk.ml

import org.junit.Assert.*
import org.junit.Test

class ProviderSelectionTest {
    private class Resource(val name: String) : AutoCloseable { var closed = false; override fun close() { closed = true } }
    @Test fun cachedChoiceVerifiesButDoesNotBenchmarkOrOpenOtherProviders() {
        val opened = mutableListOf<Resource>(); var verified = 0
        val result = ProviderSelection.select(listOf("CPU", "NNAPI"), "CPU", { Resource(it).also(opened::add) }, { verified++ }, { error("must not benchmark cached choice") })
        assertEquals(listOf("CPU"), opened.map { it.name }); assertEquals(1, verified)
        assertTrue(result.cached); assertFalse(result.resource.closed)
    }
    @Test fun invalidCachedProviderFallsBackAndClosesFailedAndLosingSessions() {
        val opened = mutableListOf<Resource>()
        val result = ProviderSelection.select(listOf("NNAPI", "CPU", "XNNPACK"), "NNAPI", { Resource(it).also(opened::add) }, { if (it.name == "NNAPI") error("driver failed") }, { if (it.name == "CPU") 50.0 else 20.0 })
        assertEquals("XNNPACK", result.name); assertFalse(result.cached)
        assertTrue(opened.filter { it !== result.resource }.all { it.closed }); assertFalse(result.resource.closed)
    }
    @Test fun totalFailureClosesEveryResourceAndDoesNotReturnAnUnverifiedCpu() {
        val opened = mutableListOf<Resource>()
        assertThrows(IllegalStateException::class.java) {
            ProviderSelection.select(listOf("CPU", "NNAPI"), null, { Resource(it).also(opened::add) }, { error("broken model") }, { 1.0 })
        }
        assertEquals(2, opened.size); assertTrue(opened.all { it.closed })
    }
    @Test fun benchmarkNeverKeepsTwoNativeModelsAlive() {
        var live = 0
        class NativeModel : AutoCloseable { init { live++; assertEquals(1, live) }; override fun close() { live-- } }
        val result = ProviderSelection.select(listOf("XNNPACK", "NNAPI", "CPU"), null, { NativeModel() }, {}, { 10.0 })
        assertEquals(1, live)
        result.resource.close()
        assertEquals(0, live)
    }
    @Test fun cacheExpiresAndRejectsClockRollback() {
        assertFalse(ProviderSelection.validCache(0, 100))
        assertFalse(ProviderSelection.validCache(200, 100))
        assertTrue(ProviderSelection.validCache(100, 200))
        assertFalse(ProviderSelection.validCache(100, 100 + 30L * 24 * 60 * 60 * 1000))
    }
}
