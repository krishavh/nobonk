package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.NormBox

/**
 * Per-track exponential smoothing of bounding boxes for *display only*.
 *
 * YOLO boxes jitter by a few percent frame to frame; drawn raw, the corner brackets
 * shimmer. Smoothing the drawn box (never the box used for the alert ladder) makes the
 * overlay calm without adding any latency to the safety logic. An approaching object
 * uses a faster alpha so the bracket keeps up with it.
 */
class BoxSmoother(
    private val alphaCalm: Float = 0.45f,
    private val alphaApproaching: Float = 0.75f,
    private val maxAgeMs: Long = 700L
) {
    private class Entry(var box: NormBox, var lastMs: Long)
    private val tracks = HashMap<String, Entry>()

    fun smooth(id: String, box: NormBox, approaching: Boolean, nowMs: Long): NormBox {
        val e = tracks[id]
        val out = if (e == null || nowMs - e.lastMs > maxAgeMs) {
            box
        } else {
            val a = if (approaching) alphaApproaching else alphaCalm
            NormBox(
                lerp(e.box.left, box.left, a), lerp(e.box.top, box.top, a),
                lerp(e.box.right, box.right, a), lerp(e.box.bottom, box.bottom, a)
            )
        }
        if (e == null) tracks[id] = Entry(out, nowMs) else { e.box = out; e.lastMs = nowMs }
        if (tracks.size > 64) tracks.entries.removeAll { nowMs - it.value.lastMs > maxAgeMs }
        return out
    }

    fun reset() = tracks.clear()

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
