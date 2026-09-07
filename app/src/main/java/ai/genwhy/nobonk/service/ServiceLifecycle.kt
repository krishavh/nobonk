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
    enum class Phase { IDLE, LOADING_MODEL, BINDING_CAMERA, RUNNING, STOPPED }
    enum class StopReason { USER, HANDOFF }

    var phase: Phase = Phase.IDLE
        private set
    var stopReason: StopReason? = null
        private set

    val isStopped: Boolean get() = synchronized(this) { phase == Phase.STOPPED }

    /** ACTION_START (or sticky restart) arrived. False = refuse (already stopped on this instance). */
    @Synchronized fun onStartRequested(): Boolean {
        if (isStopped) return false
        if (phase == Phase.IDLE) phase = Phase.LOADING_MODEL
        startedBeforeStop = true
        return true
    }
    /** Model finished loading. False = a Stop arrived meanwhile: release the engine, do NOT bind the camera. */
    @Synchronized fun onModelLoaded(): Boolean {
        if (isStopped) return false
        phase = Phase.BINDING_CAMERA
        return true
    }
    /** About to bind the camera. False = stopped meanwhile. */
    @Synchronized fun mayBindCamera(): Boolean = phase == Phase.BINDING_CAMERA
    @Synchronized fun onCameraBound() { if (!isStopped) phase = Phase.RUNNING }

    /** Frames are analysed only while running. */
    @Synchronized fun mayProcessFrames(): Boolean = phase == Phase.RUNNING
    /** Notification / HUD updates and cues only while running (never after Stop). */
    @Synchronized fun mayPostAlerts(): Boolean = phase == Phase.RUNNING

    /** Stop from any phase. Idempotent; the first reason wins. */
    @Synchronized fun stop(reason: StopReason) {
        if (isStopped) return
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
