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

    fun current(): Int = generation.get()
    fun isCurrent(gen: Int): Boolean = active && gen == generation.get()
    fun stop() { active = false; generation.incrementAndGet() }
    fun start() { generation.incrementAndGet(); active = true }
}
