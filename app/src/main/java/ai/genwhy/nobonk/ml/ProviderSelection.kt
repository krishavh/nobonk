package ai.genwhy.nobonk.ml

import java.util.concurrent.CancellationException

/** Owns candidate resources until a verified winner is handed to the caller. */
object ProviderSelection {
    data class Choice<T>(val name: String, val resource: T, val cached: Boolean)

    fun <T : AutoCloseable> select(
        providers: List<String>, cached: String?, create: (String) -> T,
        verify: (T) -> Unit, measure: (T) -> Double,
        checkActive: () -> Unit = {}
    ): Choice<T> {
        val timings = linkedMapOf<String, Double>()
        fun verified(name: String): T {
            checkActive()
            val resource = create(name)
            try {
                checkActive() // a native create may have finished after its owner stopped
                verify(resource)
                checkActive()
                return resource
            } catch (failure: Throwable) { runCatching { resource.close() }; throw failure }
        }
        checkActive()
        if (cached in providers) {
            try { return Choice(cached!!, verified(cached), true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Re-measure when an OS driver cannot restore a cached choice. */ }
        }
        // Keep only one native model session alive. Retaining three candidates is
        // expensive on older phones even when the eventual winner is a small CPU model.
        for (name in providers) {
            try {
                verified(name).use { resource ->
                    val elapsed = EpChooser.median(List(3) {
                        checkActive()
                        val elapsed = measure(resource)
                        checkActive()
                        elapsed
                    })
                    require(elapsed.isFinite() && elapsed > 0)
                    timings[name] = elapsed
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Unsupported or failed providers are not candidates. */ }
        }
        while (timings.isNotEmpty()) {
            checkActive()
            val winner = EpChooser.pick(timings)!!
            try { return Choice(winner, verified(winner), false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { timings.remove(winner) }
        }
        // A stopped native call may return an ordinary provider error. Preserve
        // cancellation even when that was the final candidate or failed winner.
        checkActive()
        error("No execution provider could run the model")
    }

    fun validCache(savedAt: Long, now: Long): Boolean = savedAt > 0 && now >= savedAt && now - savedAt < 30L * 24 * 60 * 60 * 1000
}
