package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.AlertLevel

/**
 * Spoken-word cue for the LOOK UP moment ("Person on your left").
 *
 * Off by default; when on, NoBonk speaks one short phrase per new HIGH hazard through
 * the phone's text-to-speech engine (runs offline with the system voice). Aimed at
 * blind and low-vision walkers and at anyone with the phone in a pocket and earbuds in.
 * Pure phrase builder here; the engine owns the TextToSpeech object.
 */
object VoiceCue {
    /** Minimum gap between spoken phrases so speech never stacks or nags. */
    const val REPEAT_MS = 2_500L

    fun noun(className: String): String = when (className) {
        "person" -> "Person"
        "car", "truck", "bus" -> "Vehicle"
        "motorcycle", "bicycle" -> "Bike"
        "dog", "cat", "horse" -> "Animal"
        else -> "Obstacle"
    }

    fun where(side: AlertCue.Side): String = when (side) {
        AlertCue.Side.LEFT -> "on your left"
        AlertCue.Side.RIGHT -> "on your right"
        AlertCue.Side.AHEAD -> "ahead"
    }

    /** Null when nothing should be spoken for this level. */
    fun phrase(level: AlertLevel, className: String?, side: AlertCue.Side): String? = when (level) {
        AlertLevel.HIGH -> "${noun(className ?: "")} ${where(side)}. Look up."
        else -> null
    }
}
