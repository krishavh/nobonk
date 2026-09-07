package ai.genwhy.nobonk.ml

/** Owns candidate resources until a verified winner is handed to the caller. */
object ProviderSelection {
    data class Choice<T>(val name: String, val resource: T, val cached: Boolean)

    fun <T : AutoCloseable> select(
        providers: List<String>, cached: String?, create: (String) -> T,
        verify: (T) -> Unit, measure: (T) -> Double
    ): Choice<T> {
        val timings = linkedMapOf<String, Double>()
        fun verified(name: String): T {
            val resource = create(name)
            try { verify(resource); return resource }
            catch (failure: Exception) { runCatching { resource.close() }; throw failure }
        }
        if (cached in providers) {
            try { return Choice(cached!!, verified(cached), true) }
            catch (_: Exception) { /* Re-measure when an OS driver cannot restore a cached choice. */ }
        }
        // Keep only one native model session alive. Retaining three candidates is
        // expensive on older phones even when the eventual winner is a small CPU model.
        for (name in providers) {
            try {
                verified(name).use { resource ->
                    val elapsed = EpChooser.median(List(3) { measure(resource) })
                    require(elapsed.isFinite() && elapsed > 0)
                    timings[name] = elapsed
                }
            } catch (_: Exception) { /* Unsupported or failed providers are not candidates. */ }
        }
        while (timings.isNotEmpty()) {
            val winner = EpChooser.pick(timings)!!
            try { return Choice(winner, verified(winner), false) }
            catch (_: Exception) { timings.remove(winner) }
        }
        error("No execution provider could run the model")
    }

    fun validCache(savedAt: Long, now: Long): Boolean = savedAt > 0 && now >= savedAt && now - savedAt < 30L * 24 * 60 * 60 * 1000
}
