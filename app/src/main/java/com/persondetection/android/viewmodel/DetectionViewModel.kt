package com.persondetection.android.viewmodel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.persondetection.android.data.DetectionEvent
import com.persondetection.android.data.DetectionRepository
import com.persondetection.android.data.SessionSummary
import com.persondetection.android.ml.DetectionEngine
import com.persondetection.android.ml.SensorMonitor
import com.persondetection.android.model.AlertLevel
import com.persondetection.android.model.Detection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accuracy presets — two model families, three sizes each.
 * [skipNms] = true for YOLO26 (NMS-free one-to-one head).
 */
enum class AccuracyMode(
    val modelFile: String,
    val inputPx: Int,
    val label: String,
    val skipNms: Boolean = false,
    val family: String = "YOLO11"
) {
    Y11S("yolo11s.onnx",    416, "S", skipNms = false, family = "YOLO11"),
    Y11M("yolo11m.onnx",    416, "M", skipNms = false, family = "YOLO11"),
    Y11H("yolo11m.onnx",    640, "H", skipNms = false, family = "YOLO11"),
    Y26N("yolo26n_416.onnx", 416, "S", skipNms = true, family = "YOLO26"),
    Y26S("yolo26s_416.onnx", 416, "M", skipNms = true, family = "YOLO26"),
    Y26M("yolo26m_416.onnx", 416, "H", skipNms = true, family = "YOLO26"),
}

/**
 * Foreground ViewModel. All detection logic now lives in the shared [DetectionEngine];
 * this class only owns Compose state, model selection, history, and (opt-in) location.
 */
class DetectionViewModel : ViewModel() {
    var detections by mutableStateOf<List<Detection>>(emptyList())
        private set

    var distanceThreshold by mutableFloatStateOf(2.0f)

    var isInitializing by mutableStateOf(true)
        private set
    var initializationStatus by mutableStateOf("Starting system...")
        private set

    // Round-2: vehicles/bikes/obstacles ON by default (marketing promises them; TTC
    // gating now makes them safe to surface without sidewalk spam).
    var isObjectDetectionEnabled by mutableStateOf(true)
    var accuracyMode by mutableStateOf(AccuracyMode.Y11S)

    var batteryLevel by mutableIntStateOf(100)
        private set
    var isCameraBlocked by mutableStateOf(false)
        private set

    /** Debounced frame-level alert + label, from the engine (drives the LOOK UP overlay). */
    var frameAlert by mutableStateOf(AlertLevel.NONE)
        private set
    var lookUpLabel by mutableStateOf<String?>(null)
        private set

    var isWallDetected by mutableStateOf(false)
        private set
    var isGroundHazardDetected by mutableStateOf(false)
        private set
    var phoneAngleHint by mutableStateOf("")
        private set
    var phoneAngleQuality by mutableStateOf(SensorMonitor.AngleQuality.OK)
        private set
    /** Dim-but-not-blocked scene → drives the "low light — reduced reliability" banner. */
    var isLowLight by mutableStateOf(false)
        private set

    var isHardwareAccelerated by mutableStateOf(false)
        private set

    /** Location tagging is OPT-IN and default OFF (fixes SEC-N01/N03/N08). */
    var locationTaggingEnabled by mutableStateOf(false)
        private set

    // ── History / analytics state ────────────────────────────────────────────
    var historyEvents by mutableStateOf<List<DetectionEvent>>(emptyList())
        private set
    var historySessions by mutableStateOf<List<SessionSummary>>(emptyList())
        private set

    private var engine: DetectionEngine? = null
    private var appContext: Context? = null

    private val sessionId = UUID.randomUUID().toString()
    private var repository: DetectionRepository? = null
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var lastKnownLocation: Location? = null
    private val lastEventTime = mutableMapOf<String, Long>()

    private var lastProcessTime = 0L
    private val minFrameIntervalMs = 100
    private val _processingGate = AtomicBoolean(false)

    companion object {
        private const val TAG = "DetectionViewModel"
        private const val EVENT_LOG_DEBOUNCE_MS = 3_000L
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        // Phone-angle monitoring now lives in the shared DetectionEngine (so the
        // background service is gated too); the engine is created in loadModel().
        repository = DetectionRepository(context.applicationContext)
        refreshHistory()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                isInitializing = true
                initializationStatus = "Checking battery integrity..."
                val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                batteryLevel = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else 100
                delay(1000)
                if (batteryLevel < 10) {
                    initializationStatus = "CRITICAL: Battery too low ($batteryLevel%). System halted."
                    isInitializing = false
                    return@launch
                }
                loadModel(context)
                initializationStatus = "System ready."
                delay(600)
                isInitializing = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize: ${e.message}")
                initializationStatus = "Error: ${e.message} — tap a model to retry"
                isInitializing = false
            }
        }
    }

    private fun loadModel(context: Context) {
        val mode = accuracyMode
        initializationStatus = "Loading ${mode.modelFile} @ ${mode.inputPx}px..."
        val eng = engine ?: DetectionEngine(context.applicationContext).also { engine = it }
        eng.loadModel(mode.modelFile, mode.inputPx, mode.skipNms)
        eng.startSensors()   // angle monitoring for the foreground pipeline
        isHardwareAccelerated = eng.isHardwareAccelerated
        initializationStatus = "Running AI pre-flight..."
        eng.warmUp()
    }

    fun setAccuracyMode(mode: AccuracyMode, context: Context) {
        if (mode == accuracyMode && engine != null) return
        accuracyMode = mode
        viewModelScope.launch(Dispatchers.Main) {
            isInitializing = true
            initializationStatus = "Switching to ${mode.family} ${mode.label}…"
            try {
                withContext(Dispatchers.IO) { loadModel(context) }
                initializationStatus = "Ready — ${mode.family} ${mode.label}"
                delay(600)
            } catch (e: Exception) {
                Log.e(TAG, "Model switch failed: ${e.message}")
                initializationStatus = "Failed to load ${mode.modelFile}: ${e.message}"
            } finally {
                isInitializing = false
            }
        }
    }

    // ── Location tracking (opt-in, COARSE only) ────────────────────────────────

    /**
     * Turn on location tagging. Call this from an in-context opt-in (e.g. the history
     * map) AFTER the user has granted COARSE location. Default is OFF and no location is
     * requested up front.
     */
    @Suppress("MissingPermission")
    fun enableLocationTagging(context: Context) {
        val coarseGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!coarseGranted) {
            Log.i(TAG, "Coarse location not granted — tagging stays off")
            return
        }
        locationTaggingEnabled = true
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { lastKnownLocation = location }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        locationListener = listener
        try {
            val provider = when {
                locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true -> LocationManager.NETWORK_PROVIDER
                locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true -> LocationManager.GPS_PROVIDER
                else -> null
            }
            if (provider != null) {
                locationManager?.requestLocationUpdates(provider, 30_000L, 10f, listener)
                lastKnownLocation = locationManager?.getLastKnownLocation(provider)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Location tracking failed: ${e.message}")
        }
    }

    fun disableLocationTagging() {
        locationTaggingEnabled = false
        try { locationListener?.let { locationManager?.removeUpdates(it) } } catch (_: Exception) {}
        lastKnownLocation = null
    }

    // ── History helpers ───────────────────────────────────────────────────────

    fun refreshHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val repo = repository ?: return@launch
            val events = repo.getAllEvents()
            val sessions = repo.getRecentSessions(20)
            withContext(Dispatchers.Main) {
                historyEvents = events
                historySessions = sessions
            }
        }
    }

    /** Clears all stored history (wires the previously-dead clearAll()). */
    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            repository?.clearAll()
            withContext(Dispatchers.Main) {
                historyEvents = emptyList()
                historySessions = emptyList()
            }
        }
    }

    private fun logEvent(detection: Detection) {
        val repo = repository ?: return
        val now = System.currentTimeMillis()
        val key = detection.className
        if (now - (lastEventTime[key] ?: 0L) < EVENT_LOG_DEBOUNCE_MS) return
        lastEventTime[key] = now
        val loc = if (locationTaggingEnabled) lastKnownLocation else null
        val event = DetectionEvent(
            sessionId = sessionId,
            timestamp = now,
            latitude = loc?.latitude,
            longitude = loc?.longitude,
            className = detection.className,
            distance = detection.distance,
            alertLevel = detection.alertLevel.name,
            isApproaching = detection.isApproaching
        )
        viewModelScope.launch(Dispatchers.IO) {
            repo.addEvent(event)
            val updated = historyEvents + event
            withContext(Dispatchers.Main) { historyEvents = updated }
        }
    }

    fun processFrame(imageProxy: ImageProxy) {
        if (isInitializing || batteryLevel < 10) { imageProxy.close(); return }
        val now = System.currentTimeMillis()
        if (now - lastProcessTime < minFrameIntervalMs) { imageProxy.close(); return }
        if (!_processingGate.compareAndSet(false, true)) { imageProxy.close(); return }
        lastProcessTime = now
        val eng = engine ?: run { imageProxy.close(); _processingGate.set(false); return }

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val cfg = DetectionEngine.Config(distanceThreshold, isObjectDetectionEnabled)
                val result = eng.process(imageProxy, cfg)

                for (d in result.detections) if (d.alertLevel != AlertLevel.NONE) logEvent(d)

                withContext(Dispatchers.Main) {
                    detections = result.detections
                    frameAlert = result.highestAlert
                    lookUpLabel = result.lookUpLabel
                    isCameraBlocked = result.cameraBlocked
                    isWallDetected = result.wallDetected
                    isGroundHazardDetected = result.groundHazard
                    phoneAngleQuality = result.angleQuality
                    phoneAngleHint = result.angleHint
                    isLowLight = result.lowLight
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame processing error: ${e.message}")
            } finally {
                _processingGate.set(false)
            }
        }
    }

    /** UI colour for a detection's box, driven by its computed alert level. */
    fun colorFor(detection: Detection): Color = when (detection.alertLevel) {
        AlertLevel.HIGH   -> Color.Red
        AlertLevel.MEDIUM -> Color(0xFFFFA500)
        AlertLevel.LOW    -> Color.Yellow
        AlertLevel.NONE   -> Color.Green
    }

    override fun onCleared() {
        super.onCleared()
        engine?.close()   // also stops the engine's sensor monitor
        try { locationListener?.let { locationManager?.removeUpdates(it) } } catch (_: Exception) {}
    }
}
