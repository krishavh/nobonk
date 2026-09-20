package ai.genwhy.nobonk.service

/** Owns one engine. Retirement rejects new frames and defers close until its own frame ends. */
class RetiringResource<T>(private val value: T, private val close: (T) -> Unit) {
    private var inFlight = false
    private var retired = false
    private var closed = false

    @Synchronized fun acquire(): T? {
        if (retired || inFlight) return null
        inFlight = true
        return value
    }

    fun release() {
        val dispose = synchronized(this) {
            check(inFlight) { "Frame released without acquisition" }
            inFlight = false
            takeClose()
        }
        if (dispose) close(value)
    }

    fun retire() {
        val dispose = synchronized(this) { retired = true; takeClose() }
        if (dispose) close(value)
    }

    /** Called only under the owner lock; native close runs outside the lock. */
    private fun takeClose(): Boolean {
        if (!retired || inFlight || closed) return false
        closed = true
        return true
    }
}
