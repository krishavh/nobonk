package ai.genwhy.nobonk.service

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPositionTest {
    @Test fun allEdgesRespectInsetsAndTouchTarget() {
        assertEquals(OverlayPosition.Point(10, 40), OverlayPosition.clamp(-50,-40,400,800,120,48,10,40,10,20))
        assertEquals(OverlayPosition.Point(270, 732), OverlayPosition.clamp(900,900,400,800,120,48,10,40,10,20))
    }
    @Test fun rotationAndOversizedTextCannotProduceInvalidBounds() {
        assertEquals(OverlayPosition.Point(270, 300), OverlayPosition.clamp(700,300,400,800,120,48,10,40,10,20))
        assertEquals(OverlayPosition.Point(10, 40), OverlayPosition.clamp(100,100,100,60,200,70,10,40,10,20))
    }
}
