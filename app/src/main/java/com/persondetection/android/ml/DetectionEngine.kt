package com.persondetection.android.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.camera.core.ImageProxy
import com.persondetection.android.model.AlertLevel
import com.persondetection.android.model.Detection
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * THE single detection pipeline (fixes audit PERF-U01 / the whole "two diverged
 * pipelines" theme).
 *
 * Before this, [com.persondetection.android.viewmodel.DetectionViewModel] (foreground)
 * and [com.persondetection.android.service.DetectionService] (background — the real use
 * case) ran completely separate copies of the pipeline. The background copy had a
 * **rotation bug** (it dropped `rotationDegrees` → width/height swapped → systematically
 * wrong boxes/distances), ignored the user's threshold/accuracy settings, and discarded
 * the approach result entirely. Six functions were duplicated.
 *
 * Now BOTH callers own one [DetectionEngine], pass the same [Config], and get identical:
 *   frame→bitmap (RGBA, rotation-correct), letterboxed detection, approach tracking,
 *   wall/ground analysis, fill-based alert level, haptics, and alert sound.
 */
class DetectionEngine(private val appContext: Context) {

    data class Config(
        val distanceThreshold: Float,
        /** When false, only "person" detections are surfaced. */
        val includeNonPerson: Boolean
    )

    data class Result(
        val detections: List<Detection>,      // alertLevel + isApproaching populated
        val highestAlert: AlertLevel,
        val cameraBlocked: Boolean,
        val wallDetected: Boolean,
        val groundHazard: Boolean,
        /** Ready-to-show background HUD line, or null to hide it. */
        val hudMessage: String?
    )

    private val approachTracker = ApproachTracker()
    private val frameAnalyzer = FrameAnalyzer()

    private var objectDetector: ObjectDetector? = null
    val isHardwareAccelerated: Boolean get() = objectDetector?.isHardwareAccelerated ?: false
    val inputSize: Int get() = objectDetector?.inputSize ?: 416

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? =
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val lastHapticTime = mutableMapOf<AlertLevel, Long>()
    private var lastSoundTime = 0L

    // Reused analysis pixel buffer (fixes PERF-C04: no per-pixel getPixel() JNI calls).
    private var analysisPixels: IntArray = IntArray(0)

    /** (Re)load the model. Safe to call off the main thread. */
    fun loadModel(modelName: String, inputPx: Int, skipNms: Boolean) {
        objectDetector?.close()
        objectDetector = ObjectDetector(appContext, modelName, inputPx, skipNms)
    }

    fun warmUp() {
        val d = objectDetector ?: return
        val dummy = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        repeat(2) { d.detect(dummy) }
        dummy.recycle()
    }

    /**
     * Convert an [ImageProxy] to an upright RGBA bitmap. Uses CameraX's
     * `imageProxy.toBitmap()` (requires the analysis output format to be
     * `OUTPUT_IMAGE_FORMAT_RGBA_8888`) then rotates by `rotationDegrees` — the rotation
     * the background pipeline previously dropped. No YUV→JPEG round-trip, no NV21 stride
     * bug (fixes PERF-C01/C02).
     */
    private fun imageProxyToUprightBitmap(imageProxy: ImageProxy): Bitmap {
        val raw = imageProxy.toBitmap()
        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation == 0) return raw
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, false)
        if (rotated != raw) raw.recycle()
        return rotated
    }

    /** Downscale so the longest edge == [inputSize], preserving aspect (bounds cost). */
    private fun toWorkBitmap(src: Bitmap): Bitmap {
        val maxEdge = maxOf(src.width, src.height)
        if (maxEdge <= inputSize) return src
        val scale = inputSize.toFloat() / maxEdge
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createScaledBitmap(src, w, h, true)
        if (out != src) src.recycle()
        return out
    }

    /**
     * The one and only frame-processing path. Closes [imageProxy]. Fires shared haptics +
     * sound. Returns a [Result] the caller renders however it likes (Compose overlay or
     * WindowManager HUD).
     */
    suspend fun process(imageProxy: ImageProxy, config: Config): Result {
        val detector = objectDetector
        val upright = try {
            imageProxyToUprightBitmap(imageProxy)
        } finally {
            imageProxy.close()
        }
        val work = toWorkBitmap(upright)

        // ── Blocked-camera check: low brightness AND low variance (fixes ML-06) ──
        val (meanBrightness, variance) = brightnessAndVariance(work)
        val blocked = LowLight.isBlocked(meanBrightness, variance)

        if (blocked || detector == null) {
            work.recycle()
            return Result(emptyList(), AlertLevel.NONE, cameraBlocked = blocked,
                wallDetected = false, groundHazard = false, hudMessage = null)
        }

        // ── Detection + environment analysis in parallel ──
        var wall = false
        var ground = false
        val raw: List<Detection> = coroutineScope {
            val wallJob = async { frameAnalyzer.analyze(work); }
            val yoloJob = async { detector.detect(work) }
            val d = yoloJob.await()
            wallJob.await()
            wall = frameAnalyzer.isWallDetected
            ground = frameAnalyzer.isGroundHazardDetected
            d
        }
        work.recycle()

        val filtered = if (config.includeNonPerson) raw else raw.filter { it.className == "person" }

        // ── Approach tracking + fill-based alert level ──
        val approachingIds = approachTracker.update(filtered)
        val scored = filtered.map { det ->
            val approaching = approachingIds.contains(det.id)
            val level = AlertPolicy.levelFor(det.boundingBox, det.className, config.distanceThreshold, approaching)
            det.copy(isApproaching = approaching, alertLevel = level)
        }

        val highest = scored.maxByOrNull { it.alertLevel.ordinal }?.alertLevel ?: AlertLevel.NONE

        // ── Shared feedback (identical in both modes) ──
        if (highest != AlertLevel.NONE) handleHaptics(highest)
        if (scored.any { it.alertLevel == AlertLevel.HIGH }) playAlertSound()

        val hud = buildHud(scored, highest, wall, ground)
        return Result(scored, highest, blocked, wall, ground, hud)
    }

    private fun buildHud(
        detections: List<Detection>, highest: AlertLevel, wall: Boolean, ground: Boolean
    ): String? = when {
        highest == AlertLevel.HIGH -> {
            val top = detections.filter { it.alertLevel == AlertLevel.HIGH }
                .maxByOrNull { AlertPolicy.fillFraction(it.boundingBox, it.className) }
            val label = when (top?.className) {
                "person" -> "PERSON AHEAD"
                "car", "truck", "bus" -> "VEHICLE AHEAD"
                "motorcycle", "bicycle" -> "BIKE AHEAD"
                "dog", "cat", "horse" -> "ANIMAL AHEAD"
                else -> "OBJECT AHEAD"
            }
            val closing = if (top?.isApproaching == true) " (closing)" else ""
            "⚠️ LOOK UP!  $label$closing"
        }
        wall   -> "🧱 WALL AHEAD — LOOK UP NOW"
        ground -> "⚠️ WATCH YOUR STEP!"
        else   -> null
    }

    // ── Brightness + variance over a sparse grid (single batched getPixels) ──
    private fun brightnessAndVariance(bitmap: Bitmap): Pair<Float, Float> {
        val w = bitmap.width
        val h = bitmap.height
        if (w == 0 || h == 0) return 0f to 0f
        if (analysisPixels.size < w * h) analysisPixels = IntArray(w * h)
        bitmap.getPixels(analysisPixels, 0, w, 0, 0, w, h)
        val stepX = maxOf(1, w / 16)
        val stepY = maxOf(1, h / 16)
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = analysisPixels[y * w + x]
                val luma = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                sum += luma; sumSq += luma * luma; n++
                x += stepX
            }
            y += stepY
        }
        if (n == 0) return 0f to 0f
        val mean = sum / n
        val variance = (sumSq / n) - mean * mean
        return mean.toFloat() to variance.toFloat().coerceAtLeast(0f)
    }

    // ── Shared haptics + sound (de-duplicated from VM + Service) ──
    private fun handleHaptics(level: AlertLevel) {
        val vib = vibrator ?: return
        val now = System.currentTimeMillis()
        val interval = when (level) {
            AlertLevel.LOW -> 600L; AlertLevel.MEDIUM -> 300L; AlertLevel.HIGH -> 100L
            else -> return
        }
        if (now - (lastHapticTime[level] ?: 0L) < interval) return
        lastHapticTime[level] = now
        val pattern = when (level) {
            AlertLevel.LOW -> longArrayOf(0, 50)
            AlertLevel.MEDIUM -> longArrayOf(0, 50, 100, 50)
            AlertLevel.HIGH -> longArrayOf(0, 100, 50, 100, 50, 100)
            else -> return
        }
        val amps = when (level) {
            AlertLevel.LOW -> intArrayOf(0, 128)
            AlertLevel.MEDIUM -> intArrayOf(0, 128, 0, 128)
            AlertLevel.HIGH -> intArrayOf(0, 255, 0, 255, 0, 255)
            else -> return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, amps, -1))
            } else {
                @Suppress("DEPRECATION") vib.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Haptic failed: ${e.message}")
        }
    }

    private fun playAlertSound() {
        val now = System.currentTimeMillis()
        if (now - lastSoundTime < 1500L) return   // don't stack ringtones
        lastSoundTime = now
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(appContext, uri)?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Alert sound failed: ${e.message}")
        }
    }

    fun close() {
        objectDetector?.close()
        objectDetector = null
        approachTracker.reset()
    }

    companion object { private const val TAG = "DetectionEngine" }
}
