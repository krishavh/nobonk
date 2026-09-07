package ai.genwhy.nobonk.viewmodel

import java.util.concurrent.atomic.AtomicInteger

/**
 * Serializes model loads by generation: each load takes a ticket; when it finishes it may
 * adopt its result only if its ticket is still current (no newer load requested, no Stop/clear).
 * Pure Kotlin, unit-tested with real threads.
 */
class LoadTicket {
    private val gen = AtomicInteger(0)
    @Volatile private var closed = false
    /** Start a new load; supersedes any earlier one. */
    fun begin(): Int = gen.incrementAndGet()
    fun isCurrent(ticket: Int): Boolean = !closed && ticket == gen.get()
    /** Stop / clear: nothing in flight may adopt its result anymore. */
    fun closeAll() { closed = true; gen.incrementAndGet() }
    fun reopen() { closed = false }
}
