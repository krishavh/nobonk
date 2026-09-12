package ai.genwhy.nobonk.service

/** A bound camera is not proof that detection is receiving usable, recent frames. */
class BackgroundScanStatus {
    enum class State { WAITING, SCANNING, COVERED, STALE }

    private var boundAt: Long? = null
    private var frameAt: Long? = null
    private var covered = false

    fun cameraBound(nowMs: Long) {
        boundAt = nowMs
        frameAt = null
        covered = false
    }

    /** Use the time analysis started, not completion: slow results must not appear fresh. */
    fun frameCompleted(capturedAtMs: Long, cameraCovered: Boolean) {
        if (boundAt == null || capturedAtMs < (frameAt ?: Long.MIN_VALUE)) return
        frameAt = capturedAtMs
        covered = cameraCovered
    }

    fun state(nowMs: Long): State {
        val started = boundAt ?: return State.WAITING
        val last = frameAt ?: return if (isFresh(started, nowMs)) State.WAITING else State.STALE
        if (!isFresh(last, nowMs)) return State.STALE
        return if (covered) State.COVERED else State.SCANNING
    }

    companion object {
        const val MAX_FRAME_AGE_MS = 5_000L
        fun isFresh(capturedAtMs: Long, nowMs: Long): Boolean =
            nowMs >= capturedAtMs && nowMs - capturedAtMs < MAX_FRAME_AGE_MS
    }
}
