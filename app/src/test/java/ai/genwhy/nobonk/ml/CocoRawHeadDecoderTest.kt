package ai.genwhy.nobonk.ml

import java.util.Properties
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CocoRawHeadDecoderTest {
    // Class IDs and tensor dimensions are extracted from the shipped model, not the app's map.
    private val metadata = Properties().apply {
        CocoRawHeadDecoderTest::class.java.getResourceAsStream("/yolo26n-model.properties")!!.use { load(it) }
    }
    private val classCount = metadata.getProperty("model.classes").toInt()
    private val classNames = (0 until classCount).associateWith { metadata.getProperty("class.$it") }
    private fun classId(name: String) = classNames.entries.single { it.value == name }.key
    private val portrait = Letterbox.compute(320, 640, 416)

    private fun tensor(): Array<Array<FloatArray>> = arrayOf(Array(metadata.getProperty("model.output.channels").toInt()) {
        FloatArray(metadata.getProperty("model.output.candidates").toInt())
    })

    private fun candidate(output: Array<Array<FloatArray>>, index: Int, name: String, score: Float) {
        // On a 320x640 frame this maps to [0.125, 0.25, 0.875, 0.75].
        output[0][0][index] = 208f
        output[0][1][index] = 208f
        output[0][2][index] = 156f
        output[0][3][index] = 208f
        output[0][4 + classId(name)][index] = score
    }

    private fun decode(output: Array<Array<FloatArray>>, isStandard: Boolean = true): List<ai.genwhy.nobonk.model.Detection> {
        val reference = CocoRawHeadDecoder.decode(output, isStandard, classCount, portrait, 0.40f)
        val buffer = ByteBuffer.allocateDirect(output[0].sumOf { it.size } * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        output[0].forEach { buffer.put(it) }; buffer.rewind()
        val boxes = if (isStandard) output[0][0].size else output[0].size
        val actual = CocoRawHeadDecoder.decode(buffer, isStandard, classCount, boxes, portrait, 0.40f)
        assertEquals(reference.map { Triple(it.classId, it.boundingBox, it.confidence) },
            actual.map { Triple(it.classId, it.boundingBox, it.confidence) })
        assertEquals(0, buffer.position()) // frame-to-frame reusable output stays untouched
        return actual
    }

    @Test fun everythingRetainsAll80ClassesFromTheShippedModel() {
        val output = tensor()
        classNames.forEach { (index, name) -> candidate(output, index, name, 0.9f) }
        val decoded = decode(output)
        assertEquals(classNames.values.toSet(), decoded.map { it.className }.toSet())
        assertEquals(80, decoded.size)
        decoded.forEach { assertEquals(classNames[it.classId], it.className) }
    }

    @Test fun actualSharpRecordingCandidateReachesOverlayInputAsBottle() {
        val capture = Properties().apply {
            CocoRawHeadDecoderTest::class.java.getResourceAsStream("/sharp-bottle-regression.properties")!!.use { load(it) }
        }
        val values = capture.getProperty("channels").split(",").map { it.toFloat() }.toFloatArray()
        val transform = Letterbox.compute(capture.getProperty("frame.width").toInt(), capture.getProperty("frame.height").toInt(), 416)
        val found = CocoRawHeadDecoder.decode(FloatBuffer.wrap(values), true, 80, 1, transform, 0.4f).single()
        assertEquals(39, found.classId)
        assertEquals("bottle", found.className)
        assertTrue(found.confidence >= 0.57f)
        assertTrue(found.boundingBox.width > 0f && found.boundingBox.height > 0f)
        // At the recording's 1 m sensitivity this stationary bottle is below the alert
        // threshold. It must still reach the renderer and have a neutral-colored box.
        assertEquals(ai.genwhy.nobonk.model.AlertLevel.NONE,
            AlertPolicy.levelFor(found.boundingBox, found.className, 1f, false))
    }

    @Test fun bottlePlantAndChairInUserReportAreNeverSilentlyFiltered() {
        val output = tensor()
        listOf("bottle", "potted plant", "chair").forEachIndexed { index, name -> candidate(output, index, name, 0.9f) }
        assertEquals(setOf("bottle", "potted plant", "chair"), decode(output).map { it.className }.toSet())
    }

    @Test fun chairSurvivesBothRawLayoutsAndCompetingLowerPersonScore() {
        val output = tensor()
        candidate(output, 0, "chair", 0.93f)
        candidate(output, 0, "person", 0.50f)
        val transposed = arrayOf(Array(output[0][0].size) { index -> FloatArray(output[0].size) { channel -> output[0][channel][index] } })
        for (found in listOf(decode(output).single(), decode(transposed, false).single())) {
            assertEquals(classId("chair"), found.classId)
            assertEquals("chair", found.className)
            assertEquals(0.93f, found.confidence, 0f)
        }
    }

    @Test fun chairRemainsVisibleWhenItOverlapsAPerson() {
        val output = tensor()
        candidate(output, 0, "chair", 0.93f)
        candidate(output, 1, "chair", 0.80f)
        candidate(output, 2, "person", 0.90f)
        val kept = Nms.apply(decode(output), 0.45f)
        assertEquals(setOf("chair", "person"), kept.map { it.className }.toSet())
        assertEquals(2, kept.size)
    }

    @Test fun catsAndDogsSurviveClassAwareNmsWhileDuplicateCatsCollapse() {
        val output = tensor()
        candidate(output, 0, "cat", 0.95f)
        candidate(output, 1, "cat", 0.80f)
        candidate(output, 2, "dog", 0.90f)
        val decoded = decode(output)
        assertEquals(3, decoded.size)
        val kept = Nms.apply(decoded, 0.45f)
        assertEquals(setOf(classId("cat"), classId("dog")), kept.map { it.classId }.toSet())
        assertEquals(2, kept.size)
        assertEquals(0.95f, kept.single { it.className == "cat" }.confidence, 0f)
        assertEquals(0.90f, kept.single { it.className == "dog" }.confidence, 0f)
    }

    @Test fun aHorseIsNotDecodedAsACat() {
        val output = tensor()
        candidate(output, 0, "horse", 0.99f)
        assertEquals("horse", decode(output).single().className)
        assertEquals("horse", CocoRawHeadDecoder.classNameFor(classId("horse")))
    }

    @Test fun highestClassScoreWinsWithoutRelabelingHorseAsDog() {
        val output = tensor()
        candidate(output, 0, "horse", 0.99f)
        candidate(output, 0, "dog", 0.72f)
        val found = decode(output).single()
        assertEquals(classId("horse"), found.classId)
        assertEquals("horse", found.className)
        assertEquals(0.99f, found.confidence, 0f)
    }

    @Test fun confidenceBoundaryAndLetterboxMappingRemainUnchanged() {
        val output = tensor()
        candidate(output, 0, "cat", 0.40f)
        candidate(output, 1, "dog", 0.399f)
        val found = decode(output).single()
        assertEquals("cat", found.className)
        assertEquals(0.125f, found.boundingBox.left, 1e-6f)
        assertEquals(0.25f, found.boundingBox.top, 1e-6f)
        assertEquals(0.875f, found.boundingBox.right, 1e-6f)
        assertEquals(0.75f, found.boundingBox.bottom, 1e-6f)
    }

    @Test fun transposedRawOutputUsesTheSameClassMapping() {
        val output = tensor()
        candidate(output, 0, "cat", 0.85f)
        candidate(output, 1, "dog", 0.90f)
        candidate(output, 2, "horse", 0.99f)
        val transposed = arrayOf(Array(output[0][0].size) { index -> FloatArray(output[0].size) { channel -> output[0][channel][index] } })
        val standard = decode(output)
        val decoded = decode(transposed, isStandard = false)
        assertEquals(standard.map { Triple(it.classId, it.className, it.boundingBox) },
            decoded.map { Triple(it.classId, it.className, it.boundingBox) })
        assertEquals(standard.map { it.confidence }, decoded.map { it.confidence })
    }
}
