package ai.genwhy.nobonk.ml

/** Fresh usable camera results, not completion of a synthetic model warmup. */
class ScanReadiness(
    private val minFrames: Int = 3,
    private val minDurationMs: Long = 600,
    private val maxFrameGapMs: Long = 5_000
) {
    init {
        require(minFrames > 0)
        require(minDurationMs >= 0)
        require(maxFrameGapMs > 0)
    }

    private var firstMs: Long? = null
    private var lastMs: Long? = null
    private var frames = 0
    private var ready = false

    @Synchronized fun reset() {
        firstMs = null
        lastMs = null
        frames = 0
        ready = false
    }

    /** Caller rejects obsolete session results before observing their monotonic timestamp. */
    @Synchronized fun observe(nowMs: Long, usable: Boolean): Boolean {
        val previous = lastMs
        if (!usable || nowMs < 0 || (previous != null && nowMs < previous)) {
            reset()
            return false
        }
        if (previous == nowMs) return ready
        if (previous != null && nowMs - previous > maxFrameGapMs) reset()
        if (firstMs == null) firstMs = nowMs
        lastMs = nowMs
        if (frames < minFrames) frames++
        ready = ready || (frames >= minFrames && nowMs - firstMs!! >= minDurationMs)
        return ready
    }
}
