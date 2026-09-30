package ai.genwhy.nobonk.service

/**
 * Explicit lifecycle for the background detection service (pure Kotlin, unit-tested).
 *
 * Why: Stop was unreliable because work queued *before* Stop kept completing *after* it —
 * a frame in flight re-posted the alert notification and re-created the warning overlay from
 * an already-destroyed service, and a Stop during model loading left the loaded engine and
 * sensors alive. Every asynchronous step now asks this object whether it may proceed.
 *
 * All transitions are synchronized: Stop (main thread) and model-load completion (worker) race,
 * and a check-then-write must not let BINDING_CAMERA overwrite STOPPED.
 */
class ServiceLifecycle {
    enum class Phase { IDLE, WAITING_FOR_WALKING, WALKING_PROMPTED, LOADING_MODEL, BINDING_CAMERA, RUNNING, STOPPED }
    enum class StopReason { USER, HANDOFF }

    var phase: Phase = Phase.IDLE
        private set
    var stopReason: StopReason? = null
        private set

    private var generation = 0L
    @Synchronized fun scanGeneration(): Long = generation
    @Synchronized fun isCurrent(token: Long): Boolean = token == generation && phase != Phase.STOPPED

    val isStopped: Boolean get() = synchronized(this) { phase == Phase.STOPPED }

    /** True exactly once per instance. Duplicate starts must not allocate another native engine. */
    @Synchronized fun onStartRequested(waitForWalking: Boolean = false): Boolean {
        if (phase != Phase.IDLE) return false
        phase = if (waitForWalking) Phase.WAITING_FOR_WALKING else Phase.LOADING_MODEL
        generation++
        startedBeforeStop = true
        return true
    }
    /** Walking only authorizes one reminder, NEVER a model load or camera bind. */
    @Synchronized fun onWalkingConfirmed(): Boolean {
        if (phase != Phase.WAITING_FOR_WALKING) return false
        generation++
        phase = Phase.WALKING_PROMPTED
        return true
    }
    /** Model finished loading. False = a Stop arrived meanwhile: release the engine, do NOT bind the camera. */
    @Synchronized fun onModelLoaded(token: Long = generation): Boolean {
        if (token != generation || phase != Phase.LOADING_MODEL) return false
        phase = Phase.BINDING_CAMERA
        return true
    }
    /** About to bind the camera. False = stopped meanwhile. */
    @Synchronized fun mayBindCamera(token: Long = generation): Boolean = token == generation && phase == Phase.BINDING_CAMERA
    @Synchronized fun onCameraBound(token: Long = generation) { if (mayBindCamera(token)) phase = Phase.RUNNING }

    /** Frames are analysed only while running. */
    @Synchronized fun mayProcessFrames(token: Long = generation): Boolean = token == generation && phase == Phase.RUNNING
    /** Notification / HUD updates and cues only while running (never after Stop). */
    @Synchronized fun mayPostAlerts(token: Long = generation): Boolean = token == generation && phase == Phase.RUNNING

    /** Stop from any phase. Idempotent; the first reason wins. */
    @Synchronized fun stop(reason: StopReason) {
        if (isStopped) return
        generation++
        phase = Phase.STOPPED
        stopReason = reason
    }

    /** onStartCommand return value: sticky only while we intend to keep running. */
    @Synchronized fun sticky(): Boolean = !isStopped

    /** The user pressed Stop (app button or notification) — the activity must not silently resume scanning. */
    /** True once a start was actually accepted on this instance (a stop-only instance never leaves IDLE). */
    val everStarted: Boolean get() = synchronized(this) { phase != Phase.IDLE && (stopReason == null || startedBeforeStop) }
    private var startedBeforeStop = false

    /**
     * The user pressed Stop on a session that had actually started (model loading, camera binding
     * or running). A Stop delivered to a transient instance that only ever received ACTION_STOP
     * (nothing was running) is NOT a user stop of a session and must not make the app resume in
     * the stopped state later.
     */
    val stoppedByUser: Boolean get() = synchronized(this) { stopReason == StopReason.USER && startedBeforeStop }
}
