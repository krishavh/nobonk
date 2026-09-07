package ai.genwhy.nobonk.ml

import java.util.concurrent.atomic.AtomicInteger

/**
 * Generation counter for foreground scanning. A frame captures the generation when it is
 * dispatched; when its (slow) inference finishes, results, cues, history and UI updates are
 * applied only if that generation is still current. Stop bumps the generation and marks the
 * session inactive, so nothing already in flight can leak into the stopped state or into a
 * later Start (which bumps again).
 */
class ScanSession {
    private val generation = AtomicInteger(0)
    @Volatile var active: Boolean = true
        private set
    @Volatile private var powerAvailable = true
    @Volatile private var ownerAvailable = true
    val mayScan: Boolean get() = active && powerAvailable && ownerAvailable

    fun current(): Int = generation.get()
    @Synchronized fun isCurrent(gen: Int): Boolean = mayScan && gen == generation.get()
    @Synchronized fun stop() { active = false; generation.incrementAndGet() }
    @Synchronized fun start() { generation.incrementAndGet(); active = true }

    /** Battery recovery preserves user intent: a user-stopped session stays stopped. */
    @Synchronized fun setPowerAvailable(available: Boolean) {
        if (powerAvailable == available) return
        powerAvailable = false
        generation.incrementAndGet() // neither pre-pause nor pre-recovery frames can publish
        powerAvailable = available
    }

    /** Foreground owner left its resumed detection screen; background has a separate session. */
    @Synchronized fun setOwnerAvailable(available: Boolean) {
        if (ownerAvailable == available) return
        ownerAvailable = false
        generation.incrementAndGet()
        ownerAvailable = available
    }
}
