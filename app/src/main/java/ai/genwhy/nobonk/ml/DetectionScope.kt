package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.Detection

/** People is the default. Broader detections require a new, explicit opt-in. */
object DetectionScope {
    const val DEFAULT_INCLUDE_NON_PERSON = false
    const val PREF_EVERYTHING = "detect_everything_people_default_v1"

    fun filter(detections: List<Detection>, includeNonPerson: Boolean): List<Detection> =
        if (includeNonPerson) detections else detections.filter { it.classId == 0 && it.className == "person" }

    fun environmentAllowed(includeNonPerson: Boolean): Boolean = includeNonPerson
}
