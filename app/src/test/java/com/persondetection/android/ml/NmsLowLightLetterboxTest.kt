package com.persondetection.android.ml

import com.persondetection.android.model.Detection
import com.persondetection.android.model.NormBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NmsTest {
    @Test fun `duplicate boxes collapse but distinct classes survive`() {
        val a = Detection("a", NormBox(0.10f, 0.10f, 0.40f, 0.80f), 0.90f, 1f, "person", 0)
        val aDup = Detection("a2", NormBox(0.12f, 0.11f, 0.42f, 0.82f), 0.80f, 1f, "person", 0)
        val car = Detection("c", NormBox(0.11f, 0.11f, 0.41f, 0.79f), 0.85f, 2f, "car", 2)
        val out = Nms.apply(listOf(a, aDup, car), 0.45f)
        assertEquals("one person kept", 1, out.count { it.className == "person" })
        assertTrue("overlapping car of different class survives", out.any { it.className == "car" })
        assertEquals("kept highest-confidence person", "a", out.first { it.className == "person" }.id)
    }
}

class LowLightTest {
    @Test fun `flat dark lens is blocked`() = assertTrue(LowLight.isBlocked(10f, 5f))
    @Test fun `dark but structured street is not blocked`() = assertFalse(LowLight.isBlocked(25f, 400f))
    @Test fun `bright scene is not blocked`() = assertFalse(LowLight.isBlocked(120f, 800f))
    @Test fun `dim textured wall is not blocked`() = assertFalse(LowLight.isBlocked(30f, 120f))
}

class LetterboxTest {
    @Test fun `box round-trips through letterbox geometry`() {
        val t = Letterbox.compute(640, 480, 416)
        val orig = NormBox(0.30f, 0.20f, 0.55f, 0.70f)
        val px = Letterbox.originalNormToModelPx(orig, t)
        val back = Letterbox.boxToOriginalNorm(px[0], px[1], px[2], px[3], t)
        val eps = 0.01f
        assertTrue(abs(back.left - orig.left) < eps)
        assertTrue(abs(back.top - orig.top) < eps)
        assertTrue(abs(back.right - orig.right) < eps)
        assertTrue(abs(back.bottom - orig.bottom) < eps)
        assertTrue("pads short axis", t.padY > 0f)
        assertEquals("no horizontal pad for wide source", 0f, t.padX, 0.001f)
    }
}
