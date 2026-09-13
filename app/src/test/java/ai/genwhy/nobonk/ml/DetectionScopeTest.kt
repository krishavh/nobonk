package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.AlertLevel
import ai.genwhy.nobonk.model.Detection
import ai.genwhy.nobonk.model.NormBox
import org.junit.Assert.*
import org.junit.Test

class DetectionScopeTest {
    private fun detection(classId: Int, name: String) = Detection(
        id = "raw-$classId", boundingBox = NormBox(0.1f, 0.1f, 0.9f, 0.9f),
        confidence = 0.99f, distance = 0.5f, className = name, classId = classId,
        isApproaching = true, alertLevel = AlertLevel.HIGH
    )

    @Test fun `people default rejects every nonperson raw class even when high and approaching`() {
        val raw = (0 until 80).map { detection(it, if (it == 0) "person" else "class-$it") }
        assertFalse(DetectionScope.DEFAULT_INCLUDE_NON_PERSON)
        assertEquals(listOf(raw[0]), DetectionScope.filter(raw, DetectionScope.DEFAULT_INCLUDE_NON_PERSON))
        assertEquals(raw, DetectionScope.filter(raw, true))
    }

    @Test fun `person name and raw id must agree`() {
        val valid = detection(0, "person")
        val mismatches = listOf(detection(1, "person"), detection(-1, "person"), detection(0, "dog"), detection(0, "Person"))
        assertEquals(listOf(valid), DetectionScope.filter(mismatches + valid, false))
    }

    @Test fun `switching scope reuses immutable detections without carrying object alerts`() {
        val person = detection(0, "person")
        val dog = detection(16, "dog")
        val objects = DetectionScope.filter(listOf(person, dog), true)
        assertEquals(listOf(person), DetectionScope.filter(objects, false))
        assertEquals(listOf(person, dog), objects)
        assertTrue(DetectionScope.filter(listOf(dog), false).isEmpty())
    }

    @Test fun `environment heuristic warnings require everything opt in`() {
        assertFalse(DetectionScope.environmentAllowed(false))
        assertTrue(DetectionScope.environmentAllowed(true))
    }
}
