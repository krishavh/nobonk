package ai.genwhy.nobonk.service

/**
 * Explicit lifecycle for the background detection service (pure Kotlin, unit-tested).
 *
 * Why: Stop was unreliable because work queued *before* Stop kept completing *after* it —
 * a frame in flight re-posted the alert notification and re-created the warning overlay from
 * an already-destroyed service, and a Stop during model loading left the loaded engine and
 * sensors alive. Every asynchronous step now asks this object whether it may proceed.
 */
class ServiceLifecycle {
    enum class Phase { IDLE, LOADING_MODEL, BINDING_CAMERA, RUNNING, STOPPED }
    enum class StopReason { USER, HANDOFF }

    var phase: Phase = Phase.IDLE
        private set
    var stopReason: StopReason? = null
        private set

    val isStopped: Boolean get() = phase == Phase.STOPPED

    /** ACTION_START (or sticky restart) arrived. False = refuse (already stopped on this instance). */
    fun onStartRequested(): Boolean {
        if (isStopped) return false
        if (phase == Phase.IDLE) phase = Phase.LOADING_MODEL
        return true
    }
    /** Model finished loading. False = a Stop arrived meanwhile: release the engine, do NOT bind the camera. */
    fun onModelLoaded(): Boolean {
        if (isStopped) return false
        phase = Phase.BINDING_CAMERA
        return true
    }
    /** About to bind the camera. False = stopped meanwhile. */
    fun mayBindCamera(): Boolean = phase == Phase.BINDING_CAMERA
    fun onCameraBound() { if (!isStopped) phase = Phase.RUNNING }

    /** Frames are analysed only while running. */
    fun mayProcessFrames(): Boolean = phase == Phase.RUNNING
    /** Notification / HUD updates and cues only while running (never after Stop). */
    fun mayPostAlerts(): Boolean = phase == Phase.RUNNING

    /** Stop from any phase. Idempotent; the first reason wins. */
    fun stop(reason: StopReason) {
        if (isStopped) return
        phase = Phase.STOPPED
        stopReason = reason
    }

    /** onStartCommand return value: sticky only while we intend to keep running. */
    fun sticky(): Boolean = !isStopped

    /** The user pressed Stop (app button or notification) — the activity must not silently resume scanning. */
    val stoppedByUser: Boolean get() = stopReason == StopReason.USER
}
