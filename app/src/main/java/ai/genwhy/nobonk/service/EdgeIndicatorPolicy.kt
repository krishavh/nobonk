package ai.genwhy.nobonk.service

import ai.genwhy.nobonk.model.AlertLevel

/**
 * Colour/thickness rules for the slim screen-edge "scanning" indicator that replaces the
 * wide top scan bar in background mode. Static by default: the strips only change colour
 * with the alert level — no continuous animation, so no battery or attention cost.
 */
object EdgeIndicatorPolicy {
    /** Strip thickness in dp — visible at a glance, covers essentially no content. */
    const val THICKNESS_DP = 3
    /** ARGB colours: calm mint (watching), amber (medium), red (high), grey (camera blocked / not scanning). */
    const val CALM = 0x99_2E_E6_A6.toInt()
    const val MEDIUM = 0xE6_FF_B3_00.toInt()
    const val HIGH = 0xFF_FF_3B_30.toInt()
    const val BLOCKED = 0x80_9E_9E_9E.toInt()

    fun colorFor(level: AlertLevel, cameraBlocked: Boolean): Int = when {
        cameraBlocked -> BLOCKED
        level == AlertLevel.HIGH -> HIGH
        level == AlertLevel.MEDIUM -> MEDIUM
        else -> CALM   // NONE and LOW: quiet "I'm watching" without demanding attention
    }
    /** The indicator never animates continuously; only a colour change on level change. */
    fun shouldRedraw(previousColor: Int, newColor: Int): Boolean = previousColor != newColor
}
