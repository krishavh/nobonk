package ai.genwhy.nobonk.service

import org.junit.Assert.*
import org.junit.Test

class CameraAvailabilityTest {
    @Test fun bindingOrOpeningDoesNotAuthorizeCameraFrames() {
        val camera = CameraAvailability()
        assertNull(camera.admitFrame())
        assertFalse(camera.observe(open = false))
        assertEquals(CameraAvailability.State.WAITING, camera.state())
        assertTrue(camera.observe(open = true))
        assertTrue(camera.mayPublish(camera.admitFrame()!!))
    }
    @Test fun interruptionImmediatelyInvalidatesAnInFlightHazard() {
        val camera = CameraAvailability(); camera.observe(true)
        val frame = camera.admitFrame()!!
        assertTrue(camera.observe(false))
        assertEquals(CameraAvailability.State.INTERRUPTED, camera.state())
        assertNull(camera.admitFrame())
        assertFalse(camera.mayPublish(frame))
    }
    @Test fun reopeningRequiresANewFrameRatherThanRepublishingTheOldHazard() {
        val camera = CameraAvailability(); camera.observe(true)
        val old = camera.admitFrame()!!
        camera.observe(false); camera.observe(true)
        assertFalse(camera.mayPublish(old))
        assertTrue(camera.mayPublish(camera.admitFrame()!!))
    }
    @Test fun duplicateOpenEventsDoNotDiscardGoodFrames() {
        val camera = CameraAvailability(); camera.observe(true)
        val frame = camera.admitFrame()!!
        assertFalse(camera.observe(true))
        assertTrue(camera.mayPublish(frame))
    }
    @Test fun criticalFailureIsTerminalEvenIfOpenArrivesLater() {
        val camera = CameraAvailability(); camera.observe(true)
        val frame = camera.admitFrame()!!
        assertTrue(camera.observe(open = true, critical = true))
        assertEquals(CameraAvailability.State.FAILED, camera.state())
        assertFalse(camera.observe(true)); assertFalse(camera.observe(false))
        assertFalse(camera.mayPublish(frame)); assertNull(camera.admitFrame())
    }
    @Test fun stopFromEveryPhaseRejectsLateCameraEvents() {
        for (phase in 0..3) {
            val camera = CameraAvailability()
            if (phase > 0) camera.observe(true)
            val old = camera.admitFrame()
            if (phase == 2) camera.observe(false)
            if (phase == 3) camera.observe(false, critical = true)
            camera.stop()
            assertFalse(camera.observe(true)); assertFalse(camera.observe(false, critical = true))
            assertNull(camera.admitFrame())
            if (old != null) assertFalse(camera.mayPublish(old))
            assertEquals(CameraAvailability.State.STOPPED, camera.state())
        }
    }
}
