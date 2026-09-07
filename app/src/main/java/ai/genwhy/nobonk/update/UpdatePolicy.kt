package ai.genwhy.nobonk.update

/**
 * When may NoBonk suggest a Google Play flexible in-app update? Pure rules, unit-tested
 * against fake update states. The Play client itself lives in [UpdateCoordinator].
 *
 * Never during active foreground or background scanning, never over the safety gate, never
 * while snoozed ("Later" snoozes for a day; nothing is dismissed permanently). A finished download offers a
 * user-controlled restart only when idle. No Play (sideload / emulator) simply means NONE.
 */
object UpdatePolicy {
    const val SNOOZE_MS: Long = 24L * 60 * 60 * 1000

    /** UNAVAILABLE = Play could not be asked (sideload / no Play / check failed); NONE = Play answered "no update". */
    enum class Availability { UNKNOWN, UNAVAILABLE, NONE, AVAILABLE_FLEXIBLE, DOWNLOADING, DOWNLOADED, FAILED }
    enum class Prompt { NONE, OFFER_UPDATE, OFFER_RESTART }

    data class Context(
        val availability: Availability,
        val availableVersionCode: Int,
        val scanning: Boolean,
        val backgroundActive: Boolean,
        val gateCleared: Boolean,
        val snoozedUntilMs: Long,
        val nowMs: Long
    )

    fun promptFor(c: Context): Prompt {
        if (!c.gateCleared || c.scanning || c.backgroundActive) return Prompt.NONE
        if (c.nowMs < c.snoozedUntilMs) return Prompt.NONE   // "Later" = a day-long snooze, nothing is dismissed forever
        return when (c.availability) {
            Availability.AVAILABLE_FLEXIBLE -> Prompt.OFFER_UPDATE
            Availability.DOWNLOADED -> Prompt.OFFER_RESTART
            else -> Prompt.NONE
        }
    }

    /** "Later" → snooze for a day. */
    fun snoozeUntil(nowMs: Long): Long = nowMs + SNOOZE_MS

    /** Completing (restart) is allowed only when nothing is scanning. */
    fun mayCompleteInstall(scanning: Boolean, backgroundActive: Boolean): Boolean = !scanning && !backgroundActive
}
