package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.AlertLevel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthesises NoBonk's alert chirps as 16-bit stereo PCM, panned toward the hazard.
 *
 * Why synthesise instead of playing the notification ringtone:
 *  - the ringtone is whatever the user picked, often long, and gives no direction;
 *  - a short, rising two-tone chirp is recognisable in a noisy street and can be
 *    repeated at a cadence that tracks the alert level;
 *  - baking the pan into the buffer lets us point the sound at the object: a person
 *    approaching from the left is heard on the left. With earbuds this is a real
 *    spatial cue; on a phone speaker it degrades gracefully to mono.
 *
 * Everything here is pure Kotlin so the shapes are unit-tested; the engine wraps the
 * result in an AudioTrack.
 */
object AlertCue {
    const val SAMPLE_RATE = 44_100

    /** Chirp recipe for one alert level. */
    data class Shape(val pulses: Int, val pulseMs: Int, val gapMs: Int, val hz0: Float, val hz1: Float, val gain: Float)

    fun shapeFor(level: AlertLevel): Shape? = when (level) {
        AlertLevel.HIGH   -> Shape(pulses = 3, pulseMs = 70, gapMs = 45, hz0 = 880f, hz1 = 1320f, gain = 0.85f)
        AlertLevel.MEDIUM -> Shape(pulses = 2, pulseMs = 60, gapMs = 70, hz0 = 660f, hz1 = 880f,  gain = 0.55f)
        AlertLevel.LOW    -> Shape(pulses = 1, pulseMs = 55, gapMs = 0,  hz0 = 520f, hz1 = 620f,  gain = 0.35f)
        AlertLevel.NONE   -> null
    }

    /** Minimum spacing between two cues of the same level (ms). */
    fun repeatIntervalMs(level: AlertLevel): Long = when (level) {
        AlertLevel.HIGH -> 900L
        AlertLevel.MEDIUM -> 2_200L
        AlertLevel.LOW -> 4_000L
        AlertLevel.NONE -> Long.MAX_VALUE
    }

    /**
     * Map a normalized horizontal object position to a stereo pan in −1 (hard left) … +1
     * (hard right). The centre 20 % of the frame is treated as straight ahead so a
     * centred object doesn't wobble between ears.
     */
    fun panFor(centerX: Float): Float {
        val x = ((centerX - 0.5f) * 2f).coerceIn(-1f, 1f)
        return when {
            x > 0.2f -> (x - 0.2f) / 0.8f
            x < -0.2f -> (x + 0.2f) / 0.8f
            else -> 0f
        }
    }

    /**
     * Render the interleaved stereo PCM buffer for [level] panned by [pan] (−1..1).
     * Returns `null` for [AlertLevel.NONE].
     */
    fun pcm(level: AlertLevel, pan: Float = 0f): ShortArray? {
        val s = shapeFor(level) ?: return null
        val pulseN = SAMPLE_RATE * s.pulseMs / 1000
        val gapN = SAMPLE_RATE * s.gapMs / 1000
        val totalN = s.pulses * pulseN + (s.pulses - 1) * gapN
        val out = ShortArray(totalN * 2)
        // Constant-power pan law, unity at the hard-panned channel so it can never clip
        // (centre plays at −3 dB per channel, which sums to the same perceived loudness).
        val theta = (pan.coerceIn(-1f, 1f) + 1f) * (PI.toFloat() / 4f)
        val gl = cos(theta)
        val gr = sin(theta)
        val fade = pulseN / 8
        var idx = 0
        var phase = 0.0
        for (p in 0 until s.pulses) {
            for (i in 0 until pulseN) {
                val t = i.toFloat() / pulseN
                val hz = s.hz0 + (s.hz1 - s.hz0) * t
                phase += 2.0 * PI * hz / SAMPLE_RATE
                val env = when {
                    i < fade -> i.toFloat() / fade
                    i > pulseN - fade -> (pulseN - i).toFloat() / fade
                    else -> 1f
                }
                val v = (sin(phase) * env * s.gain * Short.MAX_VALUE * 0.9).toFloat()
                out[idx++] = (v * gl).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                out[idx++] = (v * gr).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            if (p < s.pulses - 1) idx += gapN * 2   // silence
        }
        return out
    }

    /** RMS of one channel (0 = left, 1 = right) — used by tests to verify pan direction. */
    fun channelRms(pcm: ShortArray, channel: Int): Double {
        var sum = 0.0
        var n = 0
        var i = channel
        while (i < pcm.size) { val v = pcm[i].toDouble(); sum += v * v; n++; i += 2 }
        return if (n == 0) 0.0 else sqrt(sum / n)
    }
}
