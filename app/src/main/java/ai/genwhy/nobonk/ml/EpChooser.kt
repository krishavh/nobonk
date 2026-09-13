package ai.genwhy.nobonk.ml

/** Choose from verified providers by measured median latency, retaining XNNPACK on exact ties.
 * NNAPI can use a device accelerator and partial ORT CPU fallback; its name does not prove NPU use.
 */
object EpChooser {
    const val PREFERRED = "XNNPACK"
    fun pick(medianMs: Map<String, Double>): String? = medianMs.entries
        .filter { it.value.isFinite() && it.value > 0 }
        .minWithOrNull(compareBy<Map.Entry<String, Double>> { it.value }
            .thenBy { if (it.key == PREFERRED) 0 else 1 }.thenBy { it.key })?.key

    fun median(samples: List<Double>): Double {
        if (samples.isEmpty()) return Double.MAX_VALUE
        val s = samples.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}
