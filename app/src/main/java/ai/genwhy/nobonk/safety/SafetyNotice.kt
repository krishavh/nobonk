package ai.genwhy.nobonk.safety

/**
 * The safety notice and the rules for when it is shown. Pure Kotlin so the gating is
 * unit-tested; the activity only persists one integer (the acknowledged notice version)
 * in app-private SharedPreferences. Nothing is uploaded or logged.
 *
 * Rules
 *  - FULL_NOTICE (scrollable text + unchecked checkbox + "I understand — continue" /
 *    "Not now"): shown until the *current* [VERSION] has been acknowledged. Covers a fresh
 *    install (0), an upgrade from builds that only stored `first_run_done`, and any future
 *    wording change that bumps [VERSION]. The camera is never requested or started before
 *    this acknowledgment.
 *  - REMINDER (concise "stay aware" card; the user must press "OK — continue", nothing
 *    auto-dismisses): on every genuine cold app launch after acknowledgment. Not shown again within the same process (rotation,
 *    History/About round-trips) and not when the user comes back from a running
 *    background session via the Open NoBonk pill or the notification.
 *  - NONE: proceed to detection.
 */
object SafetyNotice {
    /** Bump when the notice wording changes materially; users re-acknowledge once. */
    const val VERSION = 2
    const val PREF_ACK_VERSION = "safety_ack_version"

    const val INTRO = "NoBonk watches the path ahead through the back camera and offers extra awareness cues — vibration, sound and an on-screen prompt — while you glance at your phone."

    const val MAIN_TEXT = "NoBonk is an experimental student-built tool intended to offer extra awareness cues. " +
        "It can miss or misidentify hazards, give late or incorrect alerts, and stop detecting when the camera is blocked or the app is interrupted. " +
        "No alert does not mean the path is clear. Always look up and pay attention to your surroundings. " +
        "Never rely on NoBonk for crossing roads, driving, cycling, or navigating dangerous areas. " +
        "It is not a certified safety device or a substitute for your own judgment."

    const val CHECKBOX_TEXT = "I understand that NoBonk may fail to warn me and does not replace staying aware of my surroundings."

    const val REMINDER_TITLE = "Stay aware"
    const val REMINDER_TEXT = "NoBonk can miss hazards and no alert does not mean the path is clear. Keep looking up — this is a helper, not a replacement for your attention."

    const val ACCEPT_LABEL = "I understand — continue"
    /** Mandatory on every genuine cold launch; the reminder never auto-dismisses. */
    const val REMINDER_OK_LABEL = "OK — continue"
    const val DECLINE_LABEL = "Not now"

    enum class Screen { FULL_NOTICE, REMINDER, NONE }

    /**
     * @param ackVersion              persisted acknowledged version (0 = never)
     * @param acknowledgedThisProcess in-memory flag, true after the user accepted the
     *                                notice or the reminder in this app process
     * @param serviceRunning          the background detection service is running in this
     *                                process (user is returning to an active session)
     */
    fun screenFor(ackVersion: Int, acknowledgedThisProcess: Boolean, serviceRunning: Boolean): Screen = when {
        ackVersion < VERSION -> Screen.FULL_NOTICE
        acknowledgedThisProcess || serviceRunning -> Screen.NONE
        else -> Screen.REMINDER
    }

    /** Camera permission requests, camera binding and background detection are all gated on this. */
    fun cameraAllowed(ackVersion: Int): Boolean = ackVersion >= VERSION

    /** The continue button on the full notice is enabled only with the box checked. */
    fun canContinue(checked: Boolean): Boolean = checked

    /** Value to persist after the user accepts the full notice. */
    fun acknowledgedVersion(): Int = VERSION
}

/** Process-lifetime state (not persisted): survives rotation and screen changes, resets on a cold launch. */
object SessionState {
    @Volatile var acknowledgedThisProcess: Boolean = false
}
