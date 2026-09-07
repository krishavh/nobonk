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

/**
 * The acknowledgment gate as an explicit state machine (unit-tested; owned process-wide by
 * [SessionState.gate]). "Cleared" means the user pressed OK / accepted *in this launch*.
 *
 *  - Reading the full notice from the reminder never clears the gate; Back returns to the
 *    pending reminder.
 *  - Camera permission requests, camera start and background start are allowed only while
 *    the gate is cleared for the current launch AND the persisted version is current.
 *  - A genuine new launch (the activity finished, or an idle background session stopped)
 *    resets the gate, so every launch re-prompts. Only configuration recreation (restored
 *    instance state) and returning to a live, authorized background session preserve it.
 */
class AckGate {
    /** Random per-process token: saved instance state is trusted only if it came from THIS process
     *  (rotation/config recreation). State restored after process death carries a different token. */
    val processToken: String = java.util.UUID.randomUUID().toString()
    var cleared: Boolean = false
        private set
    /** True while the background service is running detection after an authorized start. */
    var serviceActive: Boolean = false
        private set
    /** True while the activity is in the foreground (used to tell a hand-off from an idle stop). */
    var activityResumed: Boolean = false

    /**
     * Decide the screen at activity creation. [restoredCleared]/[restoredToken] are the saved-instance
     * values (null on a fresh create). Restored state counts only when [restoredToken] matches
     * [processToken]: same-process recreation (rotation) preserves, process-death restore re-prompts.
     */
    fun screenOnCreate(ackVersion: Int, restoredCleared: Boolean?, restoredToken: String? = null): SafetyNotice.Screen {
        if (restoredCleared == true && restoredToken == processToken && ackVersion >= SafetyNotice.VERSION) cleared = true
        if (serviceActive && ackVersion >= SafetyNotice.VERSION) cleared = true              // return to a live session
        return SafetyNotice.screenFor(ackVersion, cleared, serviceActive)
    }
    /** User pressed "OK — continue" on the reminder, or accepted the full notice. */
    fun onAcknowledged() { cleared = true }
    /** Opening the full notice from the reminder: no state change by design. */
    fun onReadFull() {}
    fun onActivityFinished() { if (!serviceActive) cleared = false }
    /** Service reports an authorized, successful ACTION_START. */
    fun onServiceStarted() { serviceActive = true }
    /** Service stopped (Stop action, hand-off or transient stop-only instance). */
    fun onServiceStopped() { serviceActive = false; if (!activityResumed) cleared = false }

    fun cameraAllowed(ackVersion: Int): Boolean = cleared && ackVersion >= SafetyNotice.VERSION
    fun permissionRequestAllowed(ackVersion: Int): Boolean = cameraAllowed(ackVersion)
    /**
     * Defensive check inside the service.
     *  - explicit ACTION_START: requires the current notice version AND a gate cleared in this
     *    launch (the UI cannot legitimately start detection past a pending reminder).
     *  - sticky null-intent restart (system re-creating the service after a process kill): the
     *    in-memory gate is gone, but such a restart only happens for a service that was already
     *    authorized and never stopped by the user; allow it if the persisted version is current.
     */
    fun serviceMayStart(ackVersion: Int, explicitStart: Boolean): Boolean =
        ackVersion >= SafetyNotice.VERSION && (cleared || !explicitStart)
}

/** Process-lifetime holder for the gate. */
object SessionState {
    val gate = AckGate()
}
