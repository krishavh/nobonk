package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.Detection
import ai.genwhy.nobonk.model.NormBox

/** Suppresses one-frame person hazards; visual detections remain the caller's responsibility. */
class PersonConfirmation(private val maxGapMs: Long = 750) {
    init { require(maxGapMs > 0) }
    private var previous: List<NormBox> = emptyList()
    private var previousMs: Long? = null

    @Synchronized fun reset() {
        previous = emptyList()
        previousMs = null
    }

    /** Returns confirmed person IDs only. Nonperson scoring is unchanged by the caller. */
    @Synchronized fun update(detections: List<Detection>, nowMs: Long): Set<String> {
        if (nowMs < 0 || previousMs?.let { nowMs < it } == true) {
            reset()
            return emptySet()
        }
        // A duplicate observation cannot turn one frame into two confirmations.
        if (previousMs == nowMs) return emptySet()
        if (previousMs?.let { nowMs - it > maxGapMs } == true) reset()
        val idCounts = detections.groupingBy { it.id }.eachCount()
        val people = detections.filter {
            it.classId == 0 && it.className == "person" && it.id.isNotBlank() && idCounts[it.id] == 1
        }
        data class Match(val current: Int, val prior: Int, val overlap: Float)
        val matches = people.flatMapIndexed { current, person ->
            previous.mapIndexedNotNull { prior, box ->
                val overlap = person.boundingBox.iou(box)
                if (overlap > 0.2f) Match(current, prior, overlap) else null
            }
        }.sortedByDescending { it.overlap }
        val usedCurrent = mutableSetOf<Int>()
        val usedPrior = mutableSetOf<Int>()
        val confirmed = mutableSetOf<String>()
        for (match in matches) {
            if (match.current in usedCurrent || match.prior in usedPrior) continue
            usedCurrent.add(match.current)
            usedPrior.add(match.prior)
            confirmed.add(people[match.current].id)
        }
        // Only the immediately preceding frame can confirm a person; missing observations break continuity.
        previous = people.map { it.boundingBox }
        previousMs = nowMs
        return confirmed
    }
}
