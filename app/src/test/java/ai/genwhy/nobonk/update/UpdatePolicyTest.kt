package ai.genwhy.nobonk.update

import ai.genwhy.nobonk.update.UpdatePolicy.Availability
import ai.genwhy.nobonk.update.UpdatePolicy.Prompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private fun ctx(av: Availability = Availability.AVAILABLE_FLEXIBLE, ver: Int = 12, scanning: Boolean = false, bg: Boolean = false,
                    gate: Boolean = true, snooze: Long = 0, now: Long = 1_000_000) =
        UpdatePolicy.Context(av, ver, scanning, bg, gate, snooze, now)

    @Test fun idleWithFlexibleUpdateOffersUpdate() { assertEquals(Prompt.OFFER_UPDATE, UpdatePolicy.promptFor(ctx())) }
    @Test fun neverDuringForegroundScanning() { assertEquals(Prompt.NONE, UpdatePolicy.promptFor(ctx(scanning = true))) }
    @Test fun neverDuringBackgroundSession() { assertEquals(Prompt.NONE, UpdatePolicy.promptFor(ctx(bg = true))) }
    @Test fun neverOverSafetyGate() { assertEquals(Prompt.NONE, UpdatePolicy.promptFor(ctx(gate = false))) }
    @Test fun snoozedIsSilent() {
        val now = 1_000_000L; val until = UpdatePolicy.snoozeUntil(now)
        assertEquals(Prompt.NONE, UpdatePolicy.promptFor(ctx(snooze = until, now = now + 1000)))
        assertEquals(Prompt.OFFER_UPDATE, UpdatePolicy.promptFor(ctx(snooze = until, now = until)))
    }
    @Test fun laterIsADaySnoozeNotAPermanentDismissal() {
        val now = 1_000_000L; val until = UpdatePolicy.snoozeUntil(now)
        assertEquals(until - now, UpdatePolicy.SNOOZE_MS)
        assertEquals(Prompt.OFFER_UPDATE, UpdatePolicy.promptFor(ctx(ver = 12, snooze = until, now = until + 1)))   // same version offered again after the day
    }
    @Test fun downloadedOffersRestartOnlyWhenIdle() {
        assertEquals(Prompt.OFFER_RESTART, UpdatePolicy.promptFor(ctx(av = Availability.DOWNLOADED)))
        assertEquals(Prompt.NONE, UpdatePolicy.promptFor(ctx(av = Availability.DOWNLOADED, scanning = true)))
        assertTrue(UpdatePolicy.mayCompleteInstall(false, false)); assertFalse(UpdatePolicy.mayCompleteInstall(true, false)); assertFalse(UpdatePolicy.mayCompleteInstall(false, true))
    }
    @Test fun noPlayFailedUnknownAndDownloadingAreSilent() {
        for (av in listOf(Availability.NONE, Availability.UNAVAILABLE, Availability.FAILED, Availability.UNKNOWN, Availability.DOWNLOADING))
            assertEquals(Prompt.NONE, UpdatePolicy.promptFor(ctx(av = av)))
    }
}
