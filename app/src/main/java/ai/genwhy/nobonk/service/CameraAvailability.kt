package ai.genwhy.nobonk.service

/** CameraX availability is separate from service lifetime and frame freshness. */
class CameraAvailability {
    enum class State { WAITING, OPEN, INTERRUPTED, FAILED, STOPPED }
    private var current = State.WAITING
    private var generation = 0L
    @Synchronized fun state(): State = current

    /** True when presentation must change. A failed/stopped session can never reopen. */
    @Synchronized fun observe(open: Boolean, critical: Boolean = false): Boolean {
        if (current == State.STOPPED || current == State.FAILED) return false
        val next = when {
            critical -> State.FAILED
            open -> State.OPEN
            current == State.WAITING -> State.WAITING
            else -> State.INTERRUPTED
        }
        if (next == current) return false
        current = next
        generation++ // invalidate every frame admitted before this transition
        return true
    }

    @Synchronized fun admitFrame(): Long? = generation.takeIf { current == State.OPEN }
    @Synchronized fun mayPublish(token: Long): Boolean = current == State.OPEN && token == generation
    @Synchronized fun stop() { current = State.STOPPED; generation++ }
}
