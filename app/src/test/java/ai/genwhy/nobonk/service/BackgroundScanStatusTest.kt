package ai.genwhy.nobonk.service

import org.junit.Assert.*
import org.junit.Test

class BackgroundScanStatusTest {
    @Test fun bindingAloneNeverAdvertisesScanning() {
        val status = BackgroundScanStatus()
        assertEquals(BackgroundScanStatus.State.WAITING, status.state(50_000))
        status.cameraBound(50_000)
        assertEquals(BackgroundScanStatus.State.WAITING, status.state(54_999))
        assertEquals(BackgroundScanStatus.State.STALE, status.state(55_000))
    }

    @Test fun cameraInterruptionExpiresHazardAndRecoversOnANewFrame() {
        val status = BackgroundScanStatus()
        status.cameraBound(1_000)
        status.frameCompleted(1_100, false)
        assertEquals(BackgroundScanStatus.State.SCANNING, status.state(1_150))
        assertEquals(BackgroundScanStatus.State.STALE, status.state(6_100))
        status.frameCompleted(7_000, false)
        assertEquals(BackgroundScanStatus.State.SCANNING, status.state(7_100))
    }

    @Test fun slowInferenceCannotMakeAnOldFrameLookFresh() {
        val status = BackgroundScanStatus()
        status.cameraBound(1_000)
        status.frameCompleted(1_100, false)
        assertEquals(BackgroundScanStatus.State.STALE, status.state(6_101))
        assertFalse(BackgroundScanStatus.isFresh(1_100, 6_101))
    }

    @Test fun coveredAndStaleAreDistinctAndUncoveringRestoresScanning() {
        val status = BackgroundScanStatus()
        status.cameraBound(1_000)
        status.frameCompleted(1_100, true)
        assertEquals(BackgroundScanStatus.State.COVERED, status.state(1_200))
        assertEquals(BackgroundScanStatus.State.STALE, status.state(6_100))
        status.frameCompleted(6_200, false)
        assertEquals(BackgroundScanStatus.State.SCANNING, status.state(6_300))
    }

    @Test fun olderCompletionCannotOverwriteANewerCameraState() {
        val status = BackgroundScanStatus()
        status.cameraBound(1_000)
        status.frameCompleted(1_200, true)
        status.frameCompleted(1_100, false)
        assertEquals(BackgroundScanStatus.State.COVERED, status.state(1_300))
    }

    @Test fun rebindingRequiresANewResult() {
        val status = BackgroundScanStatus()
        status.cameraBound(1_000)
        status.frameCompleted(1_100, false)
        status.cameraBound(2_000)
        assertEquals(BackgroundScanStatus.State.WAITING, status.state(2_001))
    }

    @Test fun aFreshResultStillCannotPublishAfterStop() {
        val life = ServiceLifecycle()
        life.onStartRequested(); life.onModelLoaded(); life.onCameraBound()
        life.stop(ServiceLifecycle.StopReason.USER)
        assertFalse(life.mayPostAlerts() && BackgroundScanStatus.isFresh(1_000, 1_100))
        assertFalse(BackgroundScanStatus.isFresh(1_200, 1_100))
    }
}
