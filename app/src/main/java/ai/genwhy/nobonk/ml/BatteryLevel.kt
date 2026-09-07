package ai.genwhy.nobonk.ml

/** Invalid battery broadcasts retain the last valid value; they must not resume a paused scan. */
internal object BatteryLevel {
    const val MIN_SCAN_PERCENT = 10

    fun percent(level: Int, scale: Int, previous: Int): Int =
        if (level < 0 || scale <= 0) previous
        else (level.toLong() * 100 / scale).coerceIn(0, 100).toInt()
}
