package ai.genwhy.nobonk.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpChooserTest {
    @Test fun emptyIsNull() { assertNull(EpChooser.pick(emptyMap())) }
    @Test fun xnnpackWinsTies() {
        assertEquals("XNNPACK", EpChooser.pick(mapOf("NNAPI" to 40.0, "XNNPACK" to 40.0)))
        assertEquals("XNNPACK", EpChooser.pick(mapOf("NNAPI" to 36.0, "XNNPACK" to 40.0)))   // only 10 % faster
    }
    @Test fun clearlyFasterAcceleratorWins() {
        assertEquals("NNAPI", EpChooser.pick(mapOf("NNAPI" to 20.0, "XNNPACK" to 40.0)))
    }
    @Test fun slowNnapiLoses() {
        assertEquals("XNNPACK", EpChooser.pick(mapOf("NNAPI" to 120.0, "XNNPACK" to 40.0, "CPU" to 70.0)))
    }
    @Test fun withoutXnnpackFastestWins() {
        assertEquals("CPU", EpChooser.pick(mapOf("NNAPI" to 90.0, "CPU" to 70.0)))
    }
    @Test fun plainCpuCanWinWhenClearlyFaster() {   // observed on the x86_64 emulator
        assertEquals("CPU", EpChooser.pick(mapOf("XNNPACK" to 1413.0, "NNAPI" to 791.2, "CPU" to 431.8)))
    }
    @Test fun medianIsRobust() {
        assertEquals(40.0, EpChooser.median(listOf(40.0, 900.0, 39.0)), 1e-9)
        assertEquals(45.0, EpChooser.median(listOf(40.0, 50.0)), 1e-9)
    }
}
