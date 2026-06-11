package com.persondetection.android.viewmodel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.persondetection.android.data.DetectionEvent
import com.persondetection.android.data.DetectionRepository
import com.persondetection.android.data.SessionSummary
import com.persondetection.android.ml.ApproachDetector
import com.persondetection.android.ml.FrameAnalyzer
import com.persondetection.android.ml.ObjectDetector
import com.persondetection.android.ml.SensorMonitor
import com.persondetection.android.model.AlertLevel
import com.persondetection.android.model.Detection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accuracy presets — two model families, three sizes each.
 *
 * ── YOLO11 (anchor-free, needs NMS post-processing) ─────────────────────────
 *  Y11S — yolo11s @ 416 px : fastest YOLO11, good baseline
 *  Y11M — yolo11m @ 416 px : balanced YOLO11
 *  Y11H — yolo11m @ 640 px : highest YOLO11 accuracy (full resolution)
 *
 * ── YOLO26 (NMS-free, DFL removed — better NNAPI graph) ─────────────────────
 *  Y26N — yolo26n @ 416 px : 43% faster CPU than YOLO11n — snappiest option
 *  Y26S — yolo26s @ 416 px : balanced, comparable mAP to YOLO11m
 *  Y26M — yolo26m @ 416 px : best accuracy, similar to YOLO11 HIGH but faster
 *
 * [skipNms] = true tells ObjectDetector to skip NMS post-processing.
 * YOLO26's one-to-one assignment head produces non-overlapping predictions,
 * so NMS is a no-op — skipping it saves ~2–5 ms per frame.
 */
enum class AccuracyMode(
    val modelFile: String,
    val inputPx: Int,
    val label: String,
    val skipNms: Boolean = false,
    val family: String = "YOLO11"
) {
    // ── YOLO11 ──────────────────────────────────────────────────────────────
    Y11S("yolo11s.onnx",    416, "S", skipNms = false, family = "YOLO11"),
    Y11M("yolo11m.onnx",    416, "M", skipNms = false, family = "YOLO11"),
    Y11H("yolo11m.onnx",    640, "H", skipNms = false, family = "YOLO11"),

    // ── YOLO26 ──────────────────────────────────────────────────────────────
    Y26N("yolo26n_416.onnx", 416, "S", skipNms = true, family = "YOLO26"),
    Y26S("yolo26s_416.onnx", 416, "M", skipNms = true, family = "YOLO26"),
    Y26M("yolo26m_416.onnx", 416, "H", skipNms = true, family = "YOLO26"),
}

/**
 * ViewModel coordinating camera, object detection, and approach tracking.
 * Enhanced for Version 2 with model switching and battery checks.
 */
class DetectionViewModel : ViewModel() {
    var detections by mutableStateOf<List<Detection>>(emptyList())
        private set
    
    // Default 2.0m — at walking speed you cover 1m in <1s, so 1.0m was too late.
    var distanceThreshold by mutableFloatStateOf(2.0f)
    
    // NOTE: isProcessing was removed — the actual frame-skip gate is the
    // AtomicBoolean _processingGate below, which avoids Compose snapshot overhead.

    var isInitializing by mutableStateOf(true)
        private set
    
    var initializationStatus by mutableStateOf("Starting system...")
        private set

    var isObjectDetectionEnabled by mutableStateOf(false)

    // Default to Y11S so the app works with existing assets until YOLO26 models are dropped in.
    // Switch to Y26N once yolo26n_416.onnx is in assets/ for the fastest experience.
    var accuracyMode by mutableStateOf(AccuracyMode.Y11S)
    
    var batteryLevel by mutableIntStateOf(100)
        private set

    var isCameraBlocked by mutableStateOf(false)
        private set

    // Environment warnings
    var isWallDetected by mutableStateOf(false)
        private set
    var isGroundHazardDetected by mutableStateOf(false)
        private set
    var phoneAngleHint by mutableStateOf("")
        private set
    var phoneAngleQuality by mutableStateOf(SensorMonitor.AngleQuality.OK)
        private set
    /** Last raw wall score (mean adjacent-cell diff). Shown in debug overlay; lower = more wall-like. */
    var wallDebugScore by mutableFloatStateOf(0f)
        private set

    // Acceleration status — updated after model loads
    var isHardwareAccelerated by mutableStateOf(false)
        private set

    // ── History / analytics state ────────────────────────────────────────────
    var historyEvents by mutableStateOf<List<DetectionEvent>>(emptyList())
        private set
    var historySessions by mutableStateOf<List<SessionSummary>>(emptyList())
        private set

    private var objectDetector: ObjectDetector? = null
    private val approachDetector = ApproachDetector()
    private val frameAnalyzer = FrameAnalyzer()
    private var sensorMonitor: SensorMonitor? = null

    private var appContext: Context? = null
    private var vibrator: Vibrator? = null
    private val lastHapticTime = mutableMapOf<AlertLevel, Long>()

    // ── Session + location tracking ──────────────────────────────────────────
    private val sessionId = UUID.randomUUID().toString()
    private var repository: DetectionRepository? = null
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var lastKnownLocation: Location? = null
    private val lastEventTime = mutableMapOf<String, Long>()  // key = className, debounce per class

    private var lastProcessTime = 0L
    private val minFrameIntervalMs = 100

    /**
     * Thread-safe gate that prevents concurrent frame processing.
     * Using AtomicBoolean instead of mutableStateOf avoids Compose snapshot
     * overhead on the hot camera-callback path and is safe to read/write from
     * any thread without synchronisation.
     */
    private val _processingGate = AtomicBoolean(false)

    companion object {
        private const val TAG = "DetectionViewModel"
        private const val BRIGHTNESS_THRESHOLD = 35
        // Minimum gap between logged events per object class (avoid flooding storage)
        private const val EVENT_LOG_DEBOUNCE_MS = 3_000L
    }
    
    fun initialize(context: Context) {
        appContext = context.applicationContext
        @Suppress("DEPRECATION")
        vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        // Start monitoring phone orientation
        sensorMonitor = SensorMonitor(context.applicationContext).also { it.start() }

        // Initialise repository and load existing history
        repository = DetectionRepository(context.applicationContext)
        refreshHistory()

        // Start GPS tracking if permission is granted
        startLocationTracking(context.applicationContext)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                isInitializing = true
                
                // 1. Check Battery
                initializationStatus = "Checking battery integrity..."
                val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                batteryLevel = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else 100
                
                delay(1000)
                if (batteryLevel < 10) {
                    initializationStatus = "CRITICAL: Battery too low ($batteryLevel%). System halted."
                    return@launch
                }

                loadModel(context)
                
                initializationStatus = "System ready. Object detection is BETA."
                delay(1000)
                
                isInitializing = false
            } catch (e: Exception) {
                Log.e(TAG, "✗ Failed to initialize: ${e.message}")
                initializationStatus = "Error: ${e.message} — tap a model to retry"
                // CRITICAL: must still clear isInitializing or every frame is
                // dropped forever and detection never starts.
                isInitializing = false
            }
        }
    }

    private suspend fun loadModel(context: Context) {
        val mode = accuracyMode
        initializationStatus = "Loading ${mode.modelFile} @ ${mode.inputPx}px..."

        objectDetector?.close()
        objectDetector = ObjectDetector(
            context   = context,
            modelName = mode.modelFile,
            requestedInputSize = mode.inputPx,
            skipNms   = mode.skipNms   // true for YOLO26 — NMS-free architecture
        )
        isHardwareAccelerated = objectDetector?.isHardwareAccelerated ?: false
        delay(1000)

        val accelLabel = if (isHardwareAccelerated) "NPU/DSP via NNAPI" else "CPU"
        initializationStatus = "Running AI pre-flight tests on $accelLabel..."
        val detector = objectDetector ?: return
        val dummyBitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        repeat(2) {
            detector.detectPeople(dummyBitmap)
            delay(300)
        }
    }

    fun setAccuracyMode(mode: AccuracyMode, context: Context) {
        if (mode == accuracyMode && objectDetector != null) return  // no-op if already set
        accuracyMode = mode
        // Only reload the model — do NOT call initialize() again which would leak
        // a second SensorMonitor, LocationListener, and DetectionRepository.
        viewModelScope.launch(Dispatchers.Main) {
            isInitializing = true
            initializationStatus = "Switching to ${mode.family} ${mode.label} (${mode.modelFile} @ ${mode.inputPx}px)…"
            try {
                withContext(Dispatchers.IO) { loadModel(context) }
                initializationStatus = "Ready — ${mode.family} ${mode.label}"
                delay(800)
            } catch (e: Exception) {
                Log.e(TAG, "Model switch failed: ${e.message}")
                initializationStatus = "Failed to load ${mode.modelFile}: ${e.message}"
            } finally {
                // Always clear isInitializing so frames aren't dropped forever.
                isInitializing = false
            }
        }
    }

    // ── Location tracking ─────────────────────────────────────────────────────

    @Suppress("MissingPermission")
    private fun startLocationTracking(context: Context) {
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            Log.i(TAG, "Location permission not granted — events stored without GPS")
            return
        }

        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastKnownLocation = location
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        locationListener = listener

        try {
            // Prefer GPS, fall back to network
            val provider = when {
                locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ->
                    LocationManager.GPS_PROVIDER
                locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true ->
                    LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider != null) {
                locationManager?.requestLocationUpdates(
                    provider,
                    30_000L,   // min time: 30 seconds
                    10f,       // min distance: 10 metres
                    listener
                )
                // Seed with last known location immediately
                lastKnownLocation = locationManager?.getLastKnownLocation(provider)
                Log.i(TAG, "Location tracking started on $provider")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Location tracking failed: ${e.message}")
        }
    }

    // ── History helpers ───────────────────────────────────────────────────────

    /** Reloads history from disk on the IO dispatcher and updates state. */
    fun refreshHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val repo = repository ?: return@launch
            val events   = repo.getAllEvents()
            val sessions = repo.getRecentSessions(20)
            withContext(Dispatchers.Main) {
                historyEvents   = events
                historySessions = sessions
            }
        }
    }

    /** Logs a warning event to persistent storage (debounced per class). */
    private fun logEvent(detection: Detection, alertLevel: AlertLevel) {
        val repo = repository ?: return
        val now = System.currentTimeMillis()
        val key = detection.className
        val last = lastEventTime[key] ?: 0L
        if (now - last < EVENT_LOG_DEBOUNCE_MS) return
        lastEventTime[key] = now

        val loc = lastKnownLocation
        val event = DetectionEvent(
            sessionId   = sessionId,
            timestamp   = now,
            latitude    = loc?.latitude,   // null when no GPS fix — no longer stored as 0.0/0.0
            longitude   = loc?.longitude,
            className   = detection.className,
            distance    = detection.distance,
            alertLevel  = alertLevel.name,
            isApproaching = detection.isApproaching
        )

        viewModelScope.launch(Dispatchers.IO) {
            repo.addEvent(event)
            // Refresh the in-memory list incrementally
            val updated = historyEvents + event
            val sessions = repo.getRecentSessions(20)
            withContext(Dispatchers.Main) {
                historyEvents   = updated
                historySessions = sessions
            }
        }
    }
    
    fun processFrame(imageProxy: ImageProxy) {
        if (isInitializing || batteryLevel < 10) {
            imageProxy.close()
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessTime < minFrameIntervalMs) {
            imageProxy.close()
            return
        }

        // compareAndSet(false→true) atomically claims the processing slot.
        // If another frame is already running this returns false and we drop
        // this frame — no lock contention, no Compose state overhead.
        if (!_processingGate.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        lastProcessTime = currentTime

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val fullBitmap = imageProxyToBitmap(imageProxy)
                imageProxy.close()

                // ── Blocked-camera check uses the full bitmap (100-point grid) ──
                // Computed locally on this thread — NOT read from Compose state
                // (which is Main-thread only and would be stale here).
                val blocked = computeCameraBlocked(fullBitmap)

                val detector = objectDetector ?: return@launch

                // ── Pre-scale ONCE to the model's input resolution ──────────────
                // This single scale is shared by both YOLO and FrameAnalyzer,
                // eliminating the duplicate rescale that previously happened inside
                // ObjectDetector.detectPeople().  Nearest-neighbor (filter=false)
                // is ~2× faster than bilinear — imperceptible quality difference
                // at the detection task level.
                val modelSize = detector.inputSize
                val scaledBitmap = if (!blocked) {
                    if (fullBitmap.width == modelSize && fullBitmap.height == modelSize) {
                        fullBitmap
                    } else {
                        Bitmap.createScaledBitmap(fullBitmap, modelSize, modelSize, false)
                    }
                } else null

                // ── Sensor snapshot (reads are cheap, do before async blocks) ───
                val newAngleQuality = sensorMonitor?.angleQuality ?: SensorMonitor.AngleQuality.OK
                val newAngleHint    = sensorMonitor?.angleHint    ?: ""

                // ── YOLO + wall detection run in parallel ───────────────────────
                // frameAnalyzer and detector are fully independent; running them
                // concurrently recovers 5–30 ms per frame depending on device.
                var newWallDetected  = false
                var newGroundHazard  = false
                var allDetections    = emptyList<Detection>()

                if (!blocked && scaledBitmap != null) {
                    coroutineScope {
                        val wallJob = async {
                            frameAnalyzer.analyze(scaledBitmap)
                        }
                        val yoloJob = async {
                            detector.detectPeople(scaledBitmap)  // already right size — no internal rescale
                        }
                        allDetections = yoloJob.await()
                        wallJob.await()
                        newWallDetected = frameAnalyzer.isWallDetected
                        newGroundHazard = frameAnalyzer.isGroundHazardDetected
                    }
                }

                val filteredDetections = if (isObjectDetectionEnabled) {
                    allDetections
                } else {
                    allDetections.filter { it.className == "person" }
                }

                val approachingIds = approachDetector.updateDetections(filteredDetections)
                val detectionsWithApproach = filteredDetections.map { detection ->
                    detection.copy(isApproaching = approachingIds.contains(detection.id))
                }

                // ── Haptics & audio ─────────────────────────────────────────────
                val highestAlert = detectionsWithApproach
                    .map { getAlertLevel(it) }
                    .maxByOrNull { it.ordinal } ?: AlertLevel.NONE
                if (highestAlert != AlertLevel.NONE) {
                    handleHaptics(highestAlert)
                }
                if (detectionsWithApproach.any {
                        it.className == "person" && getAlertLevel(it) == AlertLevel.HIGH
                    }) {
                    appContext?.let { playAlertSound(it) }
                }

                // ── Persist notable detections ──────────────────────────────────
                for (detection in detectionsWithApproach) {
                    val level = getAlertLevel(detection)
                    if (level != AlertLevel.NONE) logEvent(detection, level)
                }

                // ── All Compose state written on Main thread ────────────────────
                withContext(Dispatchers.Main) {
                    detections            = detectionsWithApproach
                    isWallDetected        = newWallDetected
                    isGroundHazardDetected = newGroundHazard
                    phoneAngleQuality     = newAngleQuality
                    phoneAngleHint        = newAngleHint
                    wallDebugScore        = frameAnalyzer.lastWallScore
                }

            } catch (e: Exception) {
                Log.e(TAG, "Frame processing error: ${e.message}")
            } finally {
                _processingGate.set(false)
            }
        }
    }

    /**
     * Computes whether the camera is blocked (pocket/hand) on the calling thread
     * and returns the result directly. Also dispatches a Main-thread update so the
     * UI state stays in sync — but callers MUST use the return value, not the
     * Compose state, for any logic that runs on a background thread.
     */
    private fun computeCameraBlocked(bitmap: Bitmap): Boolean {
        var totalBrightness = 0L
        val stepX = maxOf(1, bitmap.width / 10)
        val stepY = maxOf(1, bitmap.height / 10)
        var count = 0
        for (x in 0 until bitmap.width step stepX) {
            for (y in 0 until bitmap.height step stepY) {
                val pixel = bitmap.getPixel(x, y)
                totalBrightness += (0.299 * (pixel shr 16 and 0xFF) +
                        0.587 * (pixel shr 8  and 0xFF) +
                        0.114 * (pixel        and 0xFF)).toLong()
                count++
            }
        }
        val avgBrightness = if (count > 0) totalBrightness / count else 0L
        val blocked = avgBrightness < BRIGHTNESS_THRESHOLD
        // Update Compose state on Main so the UI (CameraBlockedOverlay) stays correct
        if (blocked != isCameraBlocked) {
            viewModelScope.launch(Dispatchers.Main) { isCameraBlocked = blocked }
        }
        return blocked
    }
    
    fun getAlertLevel(detection: Detection): AlertLevel {
        val distance = detection.distance
        // Non-person objects: alert when very close (vehicle/animal proximity)
        if (detection.className != "person") {
            return if (distance < 0.8f) AlertLevel.HIGH else AlertLevel.NONE
        }
        // Person thresholds — previously 0.4/0.7 meant HIGH only fired at <0.6 m
        // (essentially walking into them).  New values give meaningful advance warning:
        //   HIGH   < 65% of threshold  →  at default 1.5 m = ~1.0 m away
        //   MEDIUM < 85% of threshold  →  at default 1.5 m = ~1.3 m away
        //   LOW    < threshold         →  at default 1.5 m = ~1.5 m away
        return when {
            distance >= distanceThreshold         -> AlertLevel.NONE
            distance >= distanceThreshold * 0.85f -> AlertLevel.LOW
            distance >= distanceThreshold * 0.65f -> AlertLevel.MEDIUM
            else                                  -> AlertLevel.HIGH
        }
    }
    
    fun getDistanceColor(distance: Float): androidx.compose.ui.graphics.Color {
        // Mirror the same thresholds used in getAlertLevel so box colours
        // always agree with the haptic/alert level the user configured
        return when {
            distance < distanceThreshold * 0.65f -> androidx.compose.ui.graphics.Color.Red
            distance < distanceThreshold * 0.85f -> androidx.compose.ui.graphics.Color(0xFFFFA500)
            distance < distanceThreshold         -> androidx.compose.ui.graphics.Color.Yellow
            else                                 -> androidx.compose.ui.graphics.Color.Green
        }
    }
    
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer
        // Capture sizes BEFORE reading — buffer.remaining() drops to 0 after get() advances position
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height), 70, out)
        val raw = android.graphics.BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())

        // ── Rotate to match display orientation ──────────────────────────────
        // CameraX ImageAnalysis delivers frames in sensor orientation — typically
        // landscape (rotationDegrees = 90) when the phone is held in portrait.
        // Without this rotation, YOLO computes boxes in the rotated (sideways)
        // coordinate space, and the Canvas draws them in completely wrong positions
        // on the portrait screen — causing boxes to appear invisible or misplaced.
        val rotation = imageProxy.imageInfo.rotationDegrees
        return if (rotation != 0) {
            val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, false)
            raw.recycle()   // free the unrotated copy immediately
            rotated
        } else {
            raw
        }
    }

    private fun handleHaptics(alertLevel: AlertLevel) {
        val now = System.currentTimeMillis()
        val lastTime = lastHapticTime[alertLevel] ?: 0L
        val interval = when (alertLevel) {
            AlertLevel.LOW    -> 600L
            AlertLevel.MEDIUM -> 300L
            AlertLevel.HIGH   -> 100L
            else              -> Long.MAX_VALUE
        }
        if (now - lastTime >= interval) {
            triggerHaptic(alertLevel)
            lastHapticTime[alertLevel] = now
        }
    }

    private fun triggerHaptic(alertLevel: AlertLevel) {
        val vib = vibrator ?: return
        val pattern = when (alertLevel) {
            AlertLevel.LOW    -> longArrayOf(0, 50)
            AlertLevel.MEDIUM -> longArrayOf(0, 50, 100, 50)
            AlertLevel.HIGH   -> longArrayOf(0, 100, 50, 100, 50, 100)
            else              -> return
        }
        val amplitudes = when (alertLevel) {
            AlertLevel.LOW    -> intArrayOf(0, 128)
            AlertLevel.MEDIUM -> intArrayOf(0, 128, 0, 128)
            AlertLevel.HIGH   -> intArrayOf(0, 255, 0, 255, 0, 255)
            else              -> return
        }
        vib.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
    }

    private fun playAlertSound(context: Context) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context, uri)?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Alert sound failed: ${e.message}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        sensorMonitor?.stop()
        objectDetector?.close()
        try { locationListener?.let { locationManager?.removeUpdates(it) } } catch (_: Exception) {}
    }
}
