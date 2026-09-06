package ai.genwhy.nobonk.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraIntrinsicsTest {
    @Test fun portraitUsesLongSensorSide() {
        // 6.4 × 4.8 mm sensor, 6.9 mm lens, sensor rotated 90° → 6.9 / 6.4
        assertEquals(6.9f / 6.4f, CameraIntrinsics.normalizedFocal(6.9f, 6.4f, 4.8f, 90)!!, 1e-5f)
        assertEquals(6.9f / 6.4f, CameraIntrinsics.normalizedFocal(6.9f, 4.8f, 6.4f, 270)!!, 1e-5f)
    }
    @Test fun landscapeUsesShortSensorSide() {
        assertEquals(6.9f / 4.8f, CameraIntrinsics.normalizedFocal(6.9f, 6.4f, 4.8f, 0)!!, 1e-5f)
    }
    @Test fun rejectsGarbage() {
        assertNull(CameraIntrinsics.normalizedFocal(0f, 6.4f, 4.8f, 90))
        assertNull(CameraIntrinsics.normalizedFocal(4.7f, 0f, 4.8f, 90))
        assertNull(CameraIntrinsics.normalizedFocal(50f, 6.4f, 4.8f, 90))   // 7.8 — not a phone
        assertNull(CameraIntrinsics.normalizedFocal(Float.NaN, 6.4f, 4.8f, 90))
    }
}
