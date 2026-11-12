package ai.genwhy.nobonk.ml

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CameraAnglePolicyTest {
    private fun sample(policy: CameraAnglePolicy, degrees: Float, atMs: Long) {
        val p = Math.toRadians(degrees.toDouble())
        policy.update(0f, (9.81 * cos(p)).toFloat(), (-9.81 * sin(p)).toFloat(), atMs)
    }

    @Test fun uprightPortraitLandscapeAndUpsideDownAllFaceHorizon() {
        listOf(Triple(0f, 9.81f, 0f), Triple(9.81f, 0f, 0f), Triple(0f, -9.81f, 0f), Triple(-9.81f, 0f, 0f)).forEach {
            assertEquals(0f, CameraAnglePolicy.rearCameraPitch(it.first, it.second, it.third)!!, 0.001f)
        }
    }
    @Test fun screenUpMeansRearCameraDownAndScreenDownMeansRearCameraUp() {
        assertEquals(-90f, CameraAnglePolicy.rearCameraPitch(0f, 0f, 9.81f)!!, 0.001f)
        assertEquals(90f, CameraAnglePolicy.rearCameraPitch(0f, 0f, -9.81f)!!, 0.001f)
    }
    @Test fun elevationIsUnchangedByRollAcrossFullCircle() {
        for (pitch in -90..90 step 5) for (roll in 0..360 step 15) {
            val p = Math.toRadians(pitch.toDouble()); val r = Math.toRadians(roll.toDouble())
            val result = CameraAnglePolicy.rearCameraPitch((9.81 * cos(p) * sin(r)).toFloat(), (9.81 * cos(p) * cos(r)).toFloat(), (-9.81 * sin(p)).toFloat())!!
            assertEquals("pitch=$pitch roll=$roll", pitch.toFloat(), result, 0.001f)
        }
    }
    @Test fun malformedAndDegenerateVectorsAreRejected() {
        assertNull(CameraAnglePolicy.rearCameraPitch(0f, 0f, 0f))
        assertNull(CameraAnglePolicy.rearCameraPitch(0.01f, 0.01f, 0.01f))
        assertNull(CameraAnglePolicy.rearCameraPitch(Float.NaN, 9.81f, 0f))
        assertNull(CameraAnglePolicy.rearCameraPitch(0f, Float.POSITIVE_INFINITY, 0f))
        assertNull(CameraAnglePolicy.rearCameraPitch(0f, 9.81f, Float.NEGATIVE_INFINITY))
    }
    @Test fun firstSampleIsNotFalselySmoothedTowardHorizontal() {
        val policy = CameraAnglePolicy(); policy.update(0f, 0f, 9.81f, 100)
        assertEquals(SensorMonitor.AngleQuality.BAD, policy.reading(100).quality)
        assertTrue(policy.reading(100).hint.contains("ground"))
    }
    @Test fun conservativeWarningAndBadThresholdsApplyInBothDirections() {
        for (sign in listOf(-1, 1)) for ((degrees, expected) in listOf(60 to SensorMonitor.AngleQuality.OK, 77 to SensorMonitor.AngleQuality.WARNING, 87 to SensorMonitor.AngleQuality.BAD)) {
            val policy = CameraAnglePolicy(); val p = Math.toRadians((degrees * sign).toDouble())
            policy.update(0f, (9.81 * cos(p)).toFloat(), (-9.81 * sin(p)).toFloat(), 100)
            assertEquals(expected, policy.reading(100).quality)
        }
    }
    @Test fun absentStaleAndStoppedReadingsNeverClaimAngleOk() {
        val policy = CameraAnglePolicy()
        assertEquals(SensorMonitor.AngleQuality.UNKNOWN, policy.reading(100).quality)
        policy.update(0f, 9.81f, 0f, 100)
        assertEquals(SensorMonitor.AngleQuality.OK, policy.reading(2100).quality)
        assertEquals(SensorMonitor.AngleQuality.UNKNOWN, policy.reading(2101).quality)
        assertEquals(SensorMonitor.AngleQuality.UNKNOWN, policy.reading(99).quality)
        policy.reset()
        assertEquals(SensorMonitor.AngleQuality.UNKNOWN, policy.reading(101).quality)
    }
    @Test fun staleMalformedAndOutOfOrderSamplesCannotRenewFreshness() {
        val policy = CameraAnglePolicy(); policy.update(0f, 9.81f, 0f, 100)
        policy.update(0f, 0f, 9.81f, 99)
        policy.update(Float.NaN, 9.81f, 0f, 2099)
        assertEquals(SensorMonitor.AngleQuality.UNKNOWN, policy.reading(2101).quality)
    }
    @Test fun freshSampleAfterGapOrResetDoesNotBlendOldPosture() {
        val policy = CameraAnglePolicy(); policy.update(0f, 9.81f, 0f, 100)
        policy.update(0f, 0f, -9.81f, 2200)
        assertEquals(90f, policy.reading(2200).pitchDegrees, 0.001f)
        assertTrue(policy.reading(2200).hint.contains("upward"))
        policy.reset(); policy.update(0f, 0f, 9.81f, 2300)
        assertEquals(-90f, policy.reading(2300).pitchDegrees, 0.001f)
    }

    @Test fun badBoundaryOscillationDoesNotRepeatedlyReleaseTheAngleGate() {
        for (sign in listOf(-1, 1)) {
            val policy = CameraAnglePolicy()
            sample(policy, sign * 82.4f, 100)
            for (i in 1..30) {
                val time = 100L + i * 100L
                sample(policy, sign * (if (i % 2 == 0) 82.4f else 81.6f), time)
                assertEquals(SensorMonitor.AngleQuality.BAD, policy.reading(time).quality)
            }
        }
    }

    @Test fun badRecoversAt78DegreesInEitherDirectionWithoutASecondWarmup() {
        for (sign in listOf(-1, 1)) {
            val policy = CameraAnglePolicy()
            sample(policy, sign * 90f, 100)
            // The accepted smoothed sample is 0.7 * 90 + 0.3 * 50 = 78 degrees.
            sample(policy, sign * 50f, 200)
            assertEquals(sign * 78f, policy.reading(200).pitchDegrees, 0.001f)
            assertEquals(SensorMonitor.AngleQuality.WARNING, policy.reading(200).quality)
            sample(policy, sign * 30f, 300)
            assertEquals(SensorMonitor.AngleQuality.OK, policy.reading(300).quality)
        }
    }

    @Test fun recoveryBandAloneDoesNotEnterBadButCrossing82Does() {
        for (sign in listOf(-1, 1)) {
            val policy = CameraAnglePolicy()
            sample(policy, sign * 80f, 100)
            assertEquals(SensorMonitor.AngleQuality.WARNING, policy.reading(100).quality)
            sample(policy, sign * 90f, 200) // smoothed 83 degrees
            assertEquals(SensorMonitor.AngleQuality.BAD, policy.reading(200).quality)
        }
    }

    @Test fun staleGapAndExplicitResetDiscardBadHysteresis() {
        for (sign in listOf(-1, 1)) {
            val policy = CameraAnglePolicy()
            sample(policy, sign * 90f, 100)
            assertEquals(SensorMonitor.AngleQuality.UNKNOWN, policy.reading(2101).quality)
            sample(policy, sign * 80f, 2200)
            assertEquals(SensorMonitor.AngleQuality.WARNING, policy.reading(2200).quality)
            sample(policy, sign * 90f, 2300)
            assertEquals(SensorMonitor.AngleQuality.BAD, policy.reading(2300).quality)
            policy.reset()
            sample(policy, sign * 80f, 2400)
            assertEquals(SensorMonitor.AngleQuality.WARNING, policy.reading(2400).quality)
        }
    }

    @Test fun pollingCannotChangeHysteresisAndRejectedSamplesCannotReleaseIt() {
        val policy = CameraAnglePolicy()
        sample(policy, 90f, 100)
        assertEquals(SensorMonitor.AngleQuality.UNKNOWN, policy.reading(99).quality)
        assertEquals(SensorMonitor.AngleQuality.UNKNOWN, policy.reading(2101).quality)
        sample(policy, 0f, 99) // old event
        policy.update(Float.NaN, 9.81f, 0f, 150)
        sample(policy, 80f, 200) // smoothed 87 degrees; polling above must not reset it
        assertEquals(87f, policy.reading(200).pitchDegrees, 0.001f)
        assertEquals(SensorMonitor.AngleQuality.BAD, policy.reading(200).quality)
    }
}
