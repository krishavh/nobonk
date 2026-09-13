package ai.genwhy.nobonk.ui

import ai.genwhy.nobonk.ml.AlertPolicy
import ai.genwhy.nobonk.ml.CocoRawHeadDecoder
import ai.genwhy.nobonk.ml.Letterbox
import ai.genwhy.nobonk.model.AlertLevel
import ai.genwhy.nobonk.model.Detection
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.nio.FloatBuffer

/** Exercises actual raw decoding, alert scoring, and the production Canvas renderer on Android.
 * Pixel checks catch missing/transparent/misplaced boxes even if status text still says detected. */
class DetectionOverlayRegressionTest {
    @get:Rule val compose = createComposeRule()

    private fun detection(classId: Int, fill: Float): Detection {
        val values = FloatArray(84)
        values[0] = 208f
        values[1] = (0.1f + fill / 2f) * 416f
        values[2] = 41.6f
        values[3] = fill * 416f
        values[4 + classId] = 0.95f
        val found = CocoRawHeadDecoder.decode(FloatBuffer.wrap(values), true, 80, 1,
            Letterbox.compute(200, 400, 416), 0.4f).single()
        return found.copy(alertLevel = AlertPolicy.levelFor(found.boundingBox, found.className, 2f, false))
    }

    private fun render(found: Detection) {
        compose.setContent { Box(Modifier.size(200.dp, 400.dp).background(Color.Black)) {
            DetectionOverlay(listOf(found), found.alertLevel)
        } }
        compose.waitForIdle()
    }

    private fun cornerPixelCount(argb: Int): Int {
        val image = compose.onRoot().captureToImage()
        val pixels = image.toPixelMap()
        var hits = 0
        // Left bracket: independently specified expected screen coordinates.
        for (x in (image.width * .38f).toInt()..(image.width * .42f).toInt()) {
            for (y in (image.height * .105f).toInt()..(image.height * .135f).toInt()) {
                if (pixels[x, y].toArgb() == argb) hits++
            }
        }
        return hits
    }

    @Test fun bottleBelowAlertThresholdStillHasAVisibleCyanBox() {
        val d = detection(39, .1f)
        assertEquals("bottle", d.className); assertEquals(AlertLevel.NONE, d.alertLevel)
        render(d); assertTrue("No cyan detection bracket", cornerPixelCount(0xFF38BDF8.toInt()) > 5)
    }
    @Test fun pottedPlantLowAlertDrawsGreenBracket() {
        val d = detection(58, .3f)
        assertEquals("potted plant", d.className); assertEquals(AlertLevel.LOW, d.alertLevel)
        render(d); assertTrue("No green detection bracket", cornerPixelCount(0xFF34D399.toInt()) > 5)
    }
    @Test fun chairMediumAlertDrawsAmberBracket() {
        val d = detection(56, .45f)
        assertEquals("chair", d.className); assertEquals(AlertLevel.MEDIUM, d.alertLevel)
        render(d); assertTrue("No amber detection bracket", cornerPixelCount(0xFFFBBF24.toInt()) > 5)
    }
    @Test fun nearbyChairHighAlertDrawsRedBracket() {
        val d = detection(56, .65f)
        assertEquals("chair", d.className); assertEquals(AlertLevel.HIGH, d.alertLevel)
        render(d); assertTrue("No red detection bracket", cornerPixelCount(0xFFFB7185.toInt()) > 5)
    }
    @Test fun clearedDetectionsRemoveOldAlertBoxes() {
        val detections = mutableStateOf(listOf(detection(56, .65f)))
        compose.setContent { Box(Modifier.size(200.dp, 400.dp).background(Color.Black)) {
            DetectionOverlay(detections.value, detections.value.firstOrNull()?.alertLevel ?: AlertLevel.NONE)
        } }
        assertTrue(cornerPixelCount(0xFFFB7185.toInt()) > 5)
        compose.runOnIdle { detections.value = emptyList() }
        compose.waitForIdle()
        assertEquals(0, cornerPixelCount(0xFFFB7185.toInt()))
    }
}
