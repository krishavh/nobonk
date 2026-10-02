package ai.genwhy.nobonk.ml

import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndToEndDecoderTest {
    private val square = Letterbox.compute(416, 416, 416)

    private fun rows(vararg r: FloatArray): FloatBuffer = FloatBuffer.wrap(r.reduce { a, b -> a + b })
    private fun row(x1: Float, y1: Float, x2: Float, y2: Float, score: Float, cls: Float) =
        floatArrayOf(x1, y1, x2, y2, score, cls)
    private val good = row(104f, 104f, 312f, 312f, 0.9f, 2f) // car, [0.25, 0.25, 0.75, 0.75]

    @Test fun validRowDecodesToNormalizedBox() {
        val out = CocoRawHeadDecoder.decodeEndToEnd(rows(good), 1, square, 0.4f)
        assertEquals(1, out.size)
        assertEquals("car", out[0].className)
        assertEquals(0.25f, out[0].boundingBox.left, 1e-5f)
        assertEquals(0.75f, out[0].boundingBox.bottom, 1e-5f)
    }

    @Test fun nanScoreRowIsDroppedInsteadOfOutrankingRealBoxes() {
        val out = CocoRawHeadDecoder.decodeEndToEnd(
            rows(row(0f, 0f, 416f, 416f, Float.NaN, 2f), good), 2, square, 0.4f)
        assertEquals(listOf(0.9f), out.map { it.confidence })
    }

    @Test fun infiniteScoreRowIsDropped() {
        val out = CocoRawHeadDecoder.decodeEndToEnd(
            rows(row(0f, 0f, 416f, 416f, Float.POSITIVE_INFINITY, 2f)), 1, square, 0.4f)
        assertTrue(out.isEmpty())
    }

    @Test fun malformedGeometryAndFractionalClassesCannotBecomePeople() {
        val invalid = listOf(
            row(Float.NaN, 104f, 312f, 312f, .9f, 0f),
            row(104f, 104f, Float.POSITIVE_INFINITY, 312f, .9f, 0f),
            row(104f, 104f, 312f, 312f, .9f, .5f),
            row(104f, 104f, 312f, 312f, .9f, 80f),
            row(104f, 104f, 312f, 312f, 2f, 0f)
        )
        val out = CocoRawHeadDecoder.decodeEndToEnd(rows(*(invalid + good).toTypedArray()), 6, square, .4f)
        assertEquals(listOf("car"), out.map { it.className })
    }

    @Test fun rawHeadRejectsNonFiniteOrNegativeGeometryBeforeClamping() {
        val output = arrayOf(Array(84) { FloatArray(4) })
        for (i in 0..3) {
            output[0][0][i] = 208f; output[0][1][i] = 208f
            output[0][2][i] = 100f; output[0][3][i] = 100f; output[0][4][i] = .9f
        }
        output[0][0][0] = Float.NaN
        output[0][2][1] = Float.POSITIVE_INFINITY
        output[0][3][2] = -100f
        assertEquals(1, CocoRawHeadDecoder.decode(output, true, 80, square, .4f).size)
    }

    @Test fun nanOrNegativeClassIsNotDecodedAsPerson() {
        val out = CocoRawHeadDecoder.decodeEndToEnd(
            rows(row(104f, 104f, 312f, 312f, 0.9f, Float.NaN), row(104f, 104f, 312f, 312f, 0.9f, -1f)),
            2, square, 0.4f)
        assertTrue(out.isEmpty())
    }

    @Test fun boxEntirelyInLetterboxPaddingIsDropped() {
        // 640x320 landscape → content occupies y in 104..312; a box in the top pad maps to zero height.
        val landscape = Letterbox.compute(640, 320, 416)
        val out = CocoRawHeadDecoder.decodeEndToEnd(
            rows(row(100f, 10f, 300f, 90f, 0.9f, 0f)), 1, landscape, 0.4f)
        assertTrue(out.isEmpty())
    }

    @Test fun readsRelativeToBufferPositionWithoutMovingIt() {
        val buffer = FloatBuffer.wrap(floatArrayOf(-7f, -7f) + good)
        buffer.position(2)
        val out = CocoRawHeadDecoder.decodeEndToEnd(buffer, 1, square, 0.4f)
        assertEquals(listOf("car"), out.map { it.className })
        assertEquals(2, buffer.position())
    }

    @Test(expected = IllegalArgumentException::class)
    fun shortBufferIsRejected() {
        CocoRawHeadDecoder.decodeEndToEnd(rows(good), 2, square, 0.4f)
    }

    @Test fun rawHeadDropsInfiniteScoresAndPaddingOnlyBoxes() {
        // Standard layout [1][4 + classes][boxes]: box 0 has an infinite score, box 1 lies in the pad.
        val classes = 80
        val landscape = Letterbox.compute(640, 320, 416)
        val output = arrayOf(Array(4 + classes) { FloatArray(3) })
        fun put(i: Int, xc: Float, yc: Float, w: Float, h: Float, score: Float) {
            output[0][0][i] = xc; output[0][1][i] = yc; output[0][2][i] = w; output[0][3][i] = h
            output[0][4][i] = score
        }
        put(0, 208f, 208f, 100f, 100f, Float.POSITIVE_INFINITY)
        put(1, 208f, 50f, 100f, 60f, 0.9f)
        put(2, 208f, 208f, 100f, 100f, 0.9f)
        val out = CocoRawHeadDecoder.decode(output, true, classes, landscape, 0.4f)
        assertEquals(1, out.size)
        assertEquals("person", out[0].className)
    }
}
