package ai.genwhy.nobonk.ml

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameGeometryTest {
    @Test fun portraitSensorRotated90BecomesUpright() {
        val p = FrameGeometry.compute(1280, 720, 90, 416)
        assertEquals(720, p.rotW); assertEquals(1280, p.rotH)
        assertEquals(720f, p.shiftX, 0f); assertEquals(0f, p.shiftY, 0f)
        assertEquals(416, p.outH); assertEquals(234, p.outW)   // 720 * 416/1280 = 234
        assertEquals(416f / 1280f, p.scale, 1e-6f)
    }
    @Test fun rotation270ShiftsByWidth() {
        val p = FrameGeometry.compute(1280, 720, 270, 416)
        assertEquals(0f, p.shiftX, 0f); assertEquals(1280f, p.shiftY, 0f)
        assertEquals(720, p.rotW); assertEquals(1280, p.rotH)
    }
    @Test fun rotation180ShiftsByBoth() {
        val p = FrameGeometry.compute(640, 480, 180, 416)
        assertEquals(640f, p.shiftX, 0f); assertEquals(480f, p.shiftY, 0f)
        assertEquals(416, p.outW); assertEquals(312, p.outH)
    }
    @Test fun neverUpscalesSmallFrames() {
        val p = FrameGeometry.compute(320, 240, 0, 416)
        assertEquals(1f, p.scale, 0f); assertEquals(320, p.outW); assertEquals(240, p.outH)
    }
    @Test fun negativeAndOversizedRotationsNormalise() {
        assertEquals(FrameGeometry.compute(100, 50, 90, 416), FrameGeometry.compute(100, 50, 450, 416))
        assertEquals(FrameGeometry.compute(100, 50, 270, 416), FrameGeometry.compute(100, 50, -90, 416))
    }
}
