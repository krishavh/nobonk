package ai.genwhy.nobonk.ml

/**
 * Picks the execution provider from *measured* latency rather than a fixed preference list.
 *
 * NNAPI "working" only means the graph runs; on many phones (Tensor especially) YOLO ops
 * fall back to NNAPI's CPU reference path and end up slower than XNNPACK. So we time each
 * candidate that builds and keep the fastest. XNNPACK gets a small bias because it is the
 * most predictable path (no driver variance, no thermal-throttled accelerator surprises).
 */
object EpChooser {
    const val PREFERRED = "XNNPACK"
    /** An accelerator must beat XNNPACK by this factor to be chosen over it. */
    const val REQUIRED_SPEEDUP = 1.15f

    /** @param medianMs per-EP median inference latency; @return the winning EP name, or null if empty. */
    fun pick(medianMs: Map<String, Double>): String? {
        if (medianMs.isEmpty()) return null
        val best = medianMs.minByOrNull { it.value }!!
        val preferred = medianMs[PREFERRED] ?: return best.key
        return if (best.key != PREFERRED && best.value * REQUIRED_SPEEDUP < preferred) best.key else PREFERRED
    }

    fun median(samples: List<Double>): Double {
        if (samples.isEmpty()) return Double.MAX_VALUE
        val s = samples.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}
