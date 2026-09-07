package ai.genwhy.nobonk.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import ai.genwhy.nobonk.util.Dbg
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageProxy
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import android.hardware.camera2.CameraCharacteristics
import ai.genwhy.nobonk.model.AlertLevel
import ai.genwhy.nobonk.model.Detection
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * THE single detection pipeline (fixes audit PERF-U01 / the whole "two diverged
 * pipelines" theme).
 *
 * Before this, [ai.genwhy.nobonk.viewmodel.DetectionViewModel] (foreground)
 * and [ai.genwhy.nobonk.service.DetectionService] (background — the real use
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
        val includeNonPerson: Boolean,
        val soundEnabled: Boolean = true,
        val hapticsEnabled: Boolean = true,
        val voiceEnabled: Boolean = false
    )

    data class Result(
        val detections: List<Detection>,      // alertLevel + isApproaching populated
        val highestAlert: AlertLevel,         // linger-debounced (drives overlay/sound/HUD)
        val lookUpLabel: String?,             // class label for the LOOK UP overlay when HIGH
        val cameraBlocked: Boolean,
        val wallDetected: Boolean,
        val groundHazard: Boolean,
        /** Ready-to-show background HUD line, or null to hide it. */
        val hudMessage: String?,
        /** Dim-but-not-blocked scene → show "reduced reliability" banner. */
        val lowLight: Boolean = false,
        /** Phone-angle reliability (now gated in BOTH foreground and background). */
        val angleQuality: SensorMonitor.AngleQuality = SensorMonitor.AngleQuality.OK,
        val angleHint: String = "",
        /** Stereo pan (−1 left … +1 right) of the top hazard, null when nothing is alerting. */
        val bearingPan: Float? = null,
        /** True when the night-boost gain was applied to this frame's detector input. */
        val nightBoost: Boolean = false,
        /** Detector wall time for this frame in ms (letterbox + inference + NMS). */
        val inferMs: Long = 0L,
        /** How long the phone has been held still (0 = moving/unknown); cadence input. */
        val stationaryMs: Long = 0L
    )

    private val approachTracker = ApproachTracker()
    private val frameAnalyzer = FrameAnalyzer()

    // Phone-angle monitor now lives in the ENGINE, so the background DetectionService
    // (which never touched SensorMonitor before) gets the same angle gating as the
    // foreground. Created lazily on the first [startSensors] call.
    private var sensorMonitor: SensorMonitor? = null

    /** Begin listening to the gravity sensor. Idempotent. */
    fun startSensors() {
        val sm = sensorMonitor ?: SensorMonitor(appContext).also { sensorMonitor = it }
        sm.start()
    }

    /** Stop listening to the gravity sensor. */
    fun stopSensors() {
        sensorMonitor?.stop()
    }

    private var objectDetector: ObjectDetector? = null
    val isHardwareAccelerated: Boolean get() = objectDetector?.isHardwareAccelerated ?: false
    /** The verified active execution provider ("NNAPI" | "XNNPACK" | "CPU"). */
    val executionProvider: String get() = objectDetector?.activeExecutionProvider ?: "CPU"
    val inputSize: Int get() = objectDetector?.inputSize ?: 416

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? =
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val lastHapticTime = mutableMapOf<AlertLevel, Long>()
    private val lastCueTime = mutableMapOf<AlertLevel, Long>()
    private val boxSmoother = BoxSmoother()
    private var lastAnyCueTime = 0L
    private var audioTrack: AudioTrack? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var lastSpokenAt = 0L

    // Per-track HIGH re-alert mute (Round-2): after a HIGH fires the loud LOOK-UP + sound
    // on a track, don't re-blast the same track for MUTE_MS — the box stays red and
    // haptics continue, but we stop hammering the user for one persistent hazard.
    private val highMute = HighReAlertMute(muteMs = 2_000L)

    // Alert-level hysteresis (fixes ML-11 flicker): escalate immediately, but hold the
    // level for LINGER_MS before de-escalating so overlay/sound/HUD don't strobe when an
    // object hovers right at a ladder boundary.
    private var heldAlert = AlertLevel.NONE
    private var heldUntil = 0L
    private var heldLabel: String? = null
    private val lingerMs = 700L

    // Reused analysis pixel buffer (fixes PERF-C04: no per-pixel getPixel() JNI calls).
    private var analysisPixels: IntArray = IntArray(0)

    /** (Re)load the model. Safe to call off the main thread. */
    fun loadModel(modelName: String, inputPx: Int, skipNms: Boolean) {
        objectDetector?.close()
        objectDetector = ObjectDetector(appContext, modelName, inputPx, skipNms).also { it.focalNorm = focalNorm }
    }

    /** Normalized focal length in use by the distance estimator (see [CameraIntrinsics]). */
    @Volatile var focalNorm: Float = CameraIntrinsics.DEFAULT_FOCAL_NORM
        private set

    /**
     * Read the bound camera's lens/sensor characteristics so the distance label is
     * calibrated to THIS phone instead of a typical one. Safe to call from any thread and
     * before or after [loadModel]; silently keeps the default if the camera reports nothing.
     */
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    fun attachCamera(cameraInfo: CameraInfo) {
        try {
            val c2 = Camera2CameraInfo.from(cameraInfo)
            val focals = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val size = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val f = focals?.firstOrNull() ?: return
            val norm = CameraIntrinsics.normalizedFocal(f, size?.width ?: 0f, size?.height ?: 0f, cameraInfo.sensorRotationDegrees) ?: return
            focalNorm = norm
            objectDetector?.focalNorm = norm
            Dbg.d(TAG, "Camera intrinsics: f=${f}mm sensor=${size} → focalNorm=$norm")
        } catch (e: Exception) {
            Dbg.e(TAG, "attachCamera failed: ${e.message}")
        }
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
    // ── Zero-allocation frame path (T-PERF-FRAME) ───────────────────────────────
    // CameraX hands us an RGBA_8888 plane. We copy it into a REUSABLE raw bitmap, then
    // rotate-to-upright + downscale in ONE Canvas draw into a REUSABLE work bitmap.
    // Previously: toBitmap() + createBitmap(rotate) + createScaledBitmap = 3 allocations
    // per frame (GC churn, frame-time spikes). Now: none in steady state.
    private var rawBitmap: Bitmap? = null
    private var workBitmap: Bitmap? = null
    private var packedRows: java.nio.ByteBuffer? = null
    private var rowScratch: ByteArray? = null
    private val workMatrix = Matrix()
    private val workPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var lastMeanBrightness = 128f
    private var lastInferMs = 0L
    private var appliedGain = 1f
    private val gainMatrix = ColorMatrix()

    private fun proxyToRawBitmap(p: ImageProxy): Bitmap {
        val w = p.width; val h = p.height
        var bmp = rawBitmap
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp?.recycle(); bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); rawBitmap = bmp
        }
        val plane = p.planes[0]
        if (plane.pixelStride != 4) {
            // Unexpected layout — fall back to CameraX's converter (allocates, but correct).
            val fb = p.toBitmap(); return fb
        }
        val buf = plane.buffer; buf.rewind()
        val rowStride = plane.rowStride
        if (rowStride == w * 4) {
            bmp.copyPixelsFromBuffer(buf)
        } else {
            // Row padding present: repack rows tightly into a reusable direct buffer.
            val need = w * h * 4
            var packed = packedRows
            if (packed == null || packed.capacity() < need) { packed = java.nio.ByteBuffer.allocateDirect(need); packedRows = packed }
            var row = rowScratch
            if (row == null || row.size < w * 4) { row = ByteArray(w * 4); rowScratch = row }
            packed.clear()
            for (y in 0 until h) { buf.position(y * rowStride); buf.get(row, 0, w * 4); packed.put(row, 0, w * 4) }
            packed.rewind(); bmp.copyPixelsFromBuffer(packed)
        }
        return bmp
    }

    /** Rotate to upright and downscale so the longest edge == [inputSize] — one draw, reusable output. */
    private fun toUprightWork(raw: Bitmap, rotationDeg: Int, crop: android.graphics.Rect? = null): Bitmap {
        val g = if (crop == null || crop.isEmpty) FrameGeometry.compute(raw.width, raw.height, rotationDeg, inputSize)
                else FrameGeometry.compute(raw.width, raw.height, rotationDeg, inputSize, crop.left, crop.top, crop.width(), crop.height())
        var out = workBitmap
        if (out == null || out.width != g.outW || out.height != g.outH) {
            out?.recycle(); out = Bitmap.createBitmap(g.outW, g.outH, Bitmap.Config.ARGB_8888); workBitmap = out
        }
        workMatrix.reset()
        workMatrix.postTranslate(-g.cropLeft, -g.cropTop)   // ViewPort crop → origin
        workMatrix.postRotate(rotationDeg.toFloat())
        workMatrix.postTranslate(g.shiftX, g.shiftY)
        workMatrix.postScale(g.scale, g.scale)
        // Night boost: brighten the detector input in the same draw when the last frame was dark.
        val gain = LowLight.gainFor(lastMeanBrightness)
        if (gain != appliedGain) {
            appliedGain = gain
            workPaint.colorFilter = if (gain > LowLight.BOOST_ACTIVE_GAIN) {
                gainMatrix.setScale(gain, gain, gain, 1f); ColorMatrixColorFilter(gainMatrix)
            } else null
        }
        Canvas(out).drawBitmap(raw, workMatrix, workPaint)
        return out
    }

    /**
     * The one and only frame-processing path. Closes [imageProxy]. Fires shared haptics +
     * sound. Returns a [Result] the caller renders however it likes (Compose overlay or
     * WindowManager HUD).
     */
    /** Set by [halt]: no further processing, cues or speech, even for a frame already in flight. */
    @Volatile var halted: Boolean = false
        private set

    /** Stop emitting anything immediately (cues, speech, sensors); [close] releases the rest. */
    fun halt() {
        halted = true
        stopSensors()
        audioTrack?.let { t -> runCatching { t.stop() } }
        tts?.let { t -> runCatching { t.stop() } }
        vibrator?.let { v -> runCatching { v.cancel() } }
    }

    suspend fun process(imageProxy: ImageProxy, config: Config): Result {
        if (halted) {
            imageProxy.close()
            return Result(emptyList(), AlertLevel.NONE, lookUpLabel = null, cameraBlocked = false, wallDetected = false, groundHazard = false, hudMessage = null)
        }
        val detector = objectDetector
        val work = try {
            val raw = proxyToRawBitmap(imageProxy)
            toUprightWork(raw, imageProxy.imageInfo.rotationDegrees, imageProxy.cropRect)
        } finally {
            imageProxy.close()
        }

        // ── Blocked-camera check: low brightness AND low variance (fixes ML-06) ──
        val (boostedMean, variance) = brightnessAndVariance(work)
        // Undo the boost so the blocked/low-light logic sees the true scene brightness.
        val meanBrightness = boostedMean / appliedGain
        val nightBoost = appliedGain > LowLight.BOOST_ACTIVE_GAIN
        lastMeanBrightness = meanBrightness
        val blocked = LowLight.isBlocked(meanBrightness, variance)

        if (blocked || detector == null) {
            // Reset linger so a stale HIGH doesn't survive a blocked frame.
            heldAlert = AlertLevel.NONE; heldLabel = null; heldUntil = 0L
            return Result(emptyList(), AlertLevel.NONE, lookUpLabel = null, cameraBlocked = blocked,
                wallDetected = false, groundHazard = false, hudMessage = null)
        }

        // ── Detection + environment analysis in parallel ──
        var wall = false
        var ground = false
        val raw: List<Detection> = coroutineScope {
            val wallJob = async { frameAnalyzer.analyze(work); }
            val t0 = System.nanoTime()
            val yoloJob = async { detector.detect(work) }
            val d = yoloJob.await()
            lastInferMs = (System.nanoTime() - t0) / 1_000_000
            wallJob.await()
            wall = frameAnalyzer.isWallDetected
            ground = frameAnalyzer.isGroundHazardDetected
            d
        }

        val filtered = if (config.includeNonPerson) raw else raw.filter { it.className == "person" }

        // Reliability signals for this frame.
        val lowLight = LowLight.isLowLight(meanBrightness, blocked = false)
        val angleQuality = sensorMonitor?.angleQuality ?: SensorMonitor.AngleQuality.OK
        val angleHint = sensorMonitor?.angleHint ?: ""
        val angleBad = angleQuality == SensorMonitor.AngleQuality.BAD

        // ── Approach tracking + fill-based alert level (with TTC force-HIGH) ──
        val approachingIds = approachTracker.update(filtered)
        val scored = filtered.map { det ->
            val approaching = approachingIds.contains(det.id)
            val imminent = approachTracker.isImminent(det.id)   // TTC ≤ 1.5 s, on-bearing
            val level = AlertPolicy.levelFor(
                det.boundingBox, det.className, config.distanceThreshold, approaching, imminent
            )
            det.copy(isApproaching = approaching, alertLevel = level)
        }

        val rawHighest = scored.maxByOrNull { it.alertLevel.ordinal }?.alertLevel ?: AlertLevel.NONE
        val topDet = scored.filter { it.alertLevel == rawHighest && rawHighest != AlertLevel.NONE }
            .maxByOrNull { AlertPolicy.fillFraction(it.boundingBox, it.className) }

        // ── Alert-level linger ──
        val now = System.currentTimeMillis()
        if (rawHighest.ordinal >= heldAlert.ordinal) {
            heldAlert = rawHighest
            if (rawHighest != AlertLevel.NONE) { heldLabel = topDet?.className; heldUntil = now + lingerMs }
        } else if (now >= heldUntil) {
            heldAlert = rawHighest
            heldLabel = topDet?.className
        }
        val displayAlert = heldAlert

        // ── Per-track HIGH re-alert mute + bad-angle gating ──
        // When the angle is BAD the camera is pointed at the ceiling/ground: detections
        // are unreliable, so we suppress the loud LOOK-UP + sound and instead surface a
        // "point phone forward" reliability cue. When the same track already fired HIGH
        // within the mute window, we also hold the loud re-alert.
        // The mute window is consumed only when the cue actually fires (a HIGH seen at a
        // bad angle must not delay the first audible alert after the angle is corrected).
        val fireHigh = displayAlert == AlertLevel.HIGH &&
            highMute.shouldEmit(topDet?.let { approachTracker.trackIdFor(it.id) }, now, canEmit = !angleBad)
        val mutedRepeat = displayAlert == AlertLevel.HIGH && !angleBad && !fireHigh
        // Distinguish "don't re-PLAY the sound" from "hide the visual warning" (HIGH-1).
        // A persistent hazard re-fires HIGH every frame; after the first alert we mute the
        // re-played SOUND on that track for the mute window — but the red LOOK-UP visual
        // (overlay + HUD line) must stay up, not blink out for 2 s. Only a BAD angle (camera
        // at ceiling/ground → unreliable detections) suppresses the visual, swapping in the
        // "point phone forward" cue instead.
        val suppressSound = mutedRepeat || angleBad
        val suppressVisual = angleBad

        // ── Shared feedback (identical in both modes), driven by the debounced level ──
        val pan = topDet?.let { AlertCue.panFor(it.boundingBox.centerX) }
        if (halted) return Result(emptyList(), AlertLevel.NONE, lookUpLabel = null, cameraBlocked = false, wallDetected = false, groundHazard = false, hudMessage = null)
        if (displayAlert != AlertLevel.NONE && !angleBad && config.hapticsEnabled) handleHaptics(displayAlert)
        // Sound: HIGH = urgent triple chirp, MEDIUM = softer double chirp, LOW = haptic only.
        // The cue is panned toward the object so a left-side hazard is heard on the left.
        if (config.soundEnabled && !suppressSound && displayAlert.ordinal >= AlertLevel.MEDIUM.ordinal) {
            playAlertCue(displayAlert, pan ?: 0f)
        }
        if (config.voiceEnabled && !suppressSound && displayAlert == AlertLevel.HIGH) {
            speak(VoiceCue.phrase(displayAlert, heldLabel, AlertCue.sideFor(pan)))
        }

        val lookUpLabel = if (displayAlert == AlertLevel.HIGH && !suppressVisual) heldLabel else null
        val hud = buildHud(
            displayAlert, heldLabel, topDet?.isApproaching == true, wall, ground,
            angleBad, angleHint, lowLight, AlertCue.sideFor(pan)
        )
        // Display-only box smoothing (alert ladder above used the raw boxes).
        val shown = scored.map { d ->
            val key = approachTracker.trackIdFor(d.id) ?: d.id
            d.copy(boundingBox = boxSmoother.smooth(key, d.boundingBox, d.isApproaching, now))
        }
        return Result(
            shown, displayAlert, lookUpLabel, blocked, wall, ground, hud,
            lowLight = lowLight, angleQuality = angleQuality, angleHint = angleHint,
            bearingPan = if (displayAlert != AlertLevel.NONE) pan else null,
            nightBoost = nightBoost, inferMs = lastInferMs,
            stationaryMs = sensorMonitor?.stationaryMs(now) ?: 0L
        )
    }

    private fun buildHud(
        highest: AlertLevel, className: String?, closing: Boolean, wall: Boolean, ground: Boolean,
        angleBad: Boolean, angleHint: String, lowLight: Boolean, side: AlertCue.Side = AlertCue.Side.AHEAD
    ): String? = when {
        // Bad angle takes priority: detection is unreliable, so tell the user to fix it
        // instead of blasting a possibly-bogus LOOK UP.
        angleBad -> "📐 " + angleHint.ifEmpty { "POINT PHONE FORWARD — camera off-angle" }
        // Show the LOOK-UP line whenever the debounced level is HIGH (the re-alert mute
        // silences the SOUND, not the visual — HIGH-1). Bad angle is already handled above.
        highest == AlertLevel.HIGH -> {
            val where = when (side) {
                AlertCue.Side.LEFT -> "ON YOUR LEFT"
                AlertCue.Side.RIGHT -> "ON YOUR RIGHT"
                AlertCue.Side.AHEAD -> "AHEAD"
            }
            val label = when (className) {
                "person" -> "PERSON $where"
                "car", "truck", "bus" -> "VEHICLE $where"
                "motorcycle", "bicycle" -> "BIKE $where"
                "dog", "cat", "horse" -> "ANIMAL $where"
                else -> "OBJECT $where"
            }
            "⚠️ LOOK UP!  $label${if (closing) " (closing)" else ""}"
        }
        wall   -> "🧱 WALL AHEAD — LOOK UP NOW"
        ground -> "⚠️ WATCH YOUR STEP!"
        lowLight -> "🔅 LOW LIGHT — reduced reliability"
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
            Dbg.e(TAG, "Haptic failed: ${e.message}")
        }
    }

    /**
     * Play the synthesised chirp for [level], panned by [pan]. Rate-limited per level (so
     * a persistent MEDIUM doesn't nag) and globally (so a MEDIUM never lands on top of a
     * HIGH still ringing). Uses the accessibility-assistance audio usage so the cue plays
     * over music at a sensible volume without hijacking the alarm stream.
     */
    private fun playAlertCue(level: AlertLevel, pan: Float) {
        val now = System.currentTimeMillis()
        if (now - (lastCueTime[level] ?: 0L) < AlertCue.repeatIntervalMs(level)) return
        if (now - lastAnyCueTime < 400L && level != AlertLevel.HIGH) return
        val pcm = AlertCue.pcm(level, pan) ?: return
        lastCueTime[level] = now
        lastAnyCueTime = now
        try {
            audioTrack?.let { t -> runCatching { t.stop() }; t.release() }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AlertCue.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            audioTrack = track
        } catch (e: Exception) {
            Dbg.e(TAG, "Alert cue failed: ${e.message}")
        }
    }

    /**
     * Demo/verification: fire the HIGH cue set once (haptic + centre-panned chirp + voice
     * if enabled), bypassing the rate limits. Lets a user hear what an alert will be like
     * and lets a tester check cues without a second person.
     */
    fun previewCue(config: Config) {
        lastHapticTime.clear(); lastCueTime.clear(); lastAnyCueTime = 0L; lastSpokenAt = 0L
        if (config.hapticsEnabled) handleHaptics(AlertLevel.HIGH)
        if (config.soundEnabled) playAlertCue(AlertLevel.HIGH, 0f)
        if (config.voiceEnabled) speak(VoiceCue.phrase(AlertLevel.HIGH, "person", AlertCue.Side.AHEAD))
    }

    /** Warm the text-to-speech engine so the very first HIGH can speak (init is async). */
    fun prepareVoice() {
        if (tts != null) return
        try {
            tts = TextToSpeech(appContext) { status -> ttsReady = status == TextToSpeech.SUCCESS }.also { t ->
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }
        } catch (e: Exception) { Dbg.e(TAG, "TTS init failed: ${e.message}") }
    }

    /** Lazily create the TTS engine (first HIGH with voice on), then speak [text] once per [VoiceCue.REPEAT_MS]. */
    private fun speak(text: String?) {
        text ?: return
        val now = System.currentTimeMillis()
        if (now - lastSpokenAt < VoiceCue.REPEAT_MS) return
        if (tts == null) prepareVoice()
        val engine = tts ?: return
        if (!ttsReady) return   // first call warms the engine; the next HIGH speaks
        lastSpokenAt = now
        try { engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nobonk-$now") } catch (e: Exception) { Dbg.e(TAG, "TTS speak failed: ${e.message}") }
    }

    fun close() {
        rawBitmap?.recycle(); rawBitmap = null
        workBitmap?.recycle(); workBitmap = null

        objectDetector?.close()
        objectDetector = null
        audioTrack?.let { t -> runCatching { t.stop() }; t.release() }; audioTrack = null
        tts?.let { t -> runCatching { t.stop() }; runCatching { t.shutdown() } }; tts = null; ttsReady = false
        approachTracker.reset()
        boxSmoother.reset()
        stopSensors()
        sensorMonitor = null
        highMute.reset()
    }

    companion object { private const val TAG = "DetectionEngine" }
}
