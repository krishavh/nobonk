package ai.genwhy.nobonk.ml

/** Owns candidate resources until a verified winner is handed to the caller. */
object ProviderSelection {
    data class Choice<T>(val name: String, val resource: T, val cached: Boolean)

    fun <T : AutoCloseable> select(
        providers: List<String>, cached: String?, create: (String) -> T,
        verify: (T) -> Unit, measure: (T) -> Double
    ): Choice<T> {
        val candidates = linkedMapOf<String, T>()
        val timings = linkedMapOf<String, Double>()
        var handedOff: T? = null
        try {
            if (cached in providers) {
                var resource: T? = null
                try {
                    resource = create(cached!!)
                    verify(resource)
                    handedOff = resource
                    return Choice(cached, resource, true)
                } catch (_: Exception) { runCatching { resource?.close() } }
            }
            for (name in providers) {
                var resource: T? = null
                try {
                    resource = create(name)
                    verify(resource)
                    val elapsed = EpChooser.median(List(3) { measure(resource) })
                    require(elapsed.isFinite() && elapsed > 0)
                    candidates[name] = resource
                    timings[name] = elapsed
                } catch (_: Exception) { runCatching { resource?.close() } }
            }
            val winner = EpChooser.pick(timings) ?: error("No execution provider could run the model")
            handedOff = candidates.getValue(winner)
            return Choice(winner, handedOff, false)
        } finally {
            candidates.values.filter { it !== handedOff }.forEach { runCatching { it.close() } }
        }
    }

    fun validCache(savedAt: Long, now: Long): Boolean = savedAt > 0 && now >= savedAt && now - savedAt < 30L * 24 * 60 * 60 * 1000
}
