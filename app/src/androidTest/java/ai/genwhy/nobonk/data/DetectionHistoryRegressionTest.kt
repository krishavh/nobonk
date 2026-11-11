package ai.genwhy.nobonk.data

import ai.genwhy.nobonk.ml.CocoRawHeadDecoder
import ai.genwhy.nobonk.requireIsolatedEmulator
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class DetectionHistoryRegressionTest {
    private fun event(name: String) = DetectionEvent(
        sessionId = "history-regression", className = name, distance = 1f,
        alertLevel = "LOW", isApproaching = false
    )

    @Test fun everyShippedObjectLabelSurvivesJsonRoundTrip() {
        requireIsolatedEmulator()
        val names = (0 until 80).map(CocoRawHeadDecoder::classNameFor) + "object"
        assertEquals(81, names.toSet().size)
        names.forEach { name ->
            val original = event(name)
            assertEquals(name, original, DetectionEvent.fromJson(original.toJson()))
        }
    }

    @Test fun unknownLabelsStillFailValidation() {
        requireIsolatedEmulator()
        try {
            DetectionEvent.fromJson(event("not a model class").toJson())
            fail("Unknown labels must still be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun encryptedHistoryKeepsObjectsWhenRepositoryReopens() {
        requireIsolatedEmulator()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = DetectionRepository(context)
        // Test-only emulator app storage; never run this on a personal phone.
        repository.clearAll()
        try {
            val expected = (0 until 80).map { event(CocoRawHeadDecoder.classNameFor(it)) }
            expected.forEach { assertTrue(repository.addEvent(it)) }
            assertEquals(expected.toSet(), DetectionRepository(context).getAllEvents().toSet())
        } finally { repository.clearAll() }
    }
}
