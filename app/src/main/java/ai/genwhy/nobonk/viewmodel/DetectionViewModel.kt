package ai.genwhy.nobonk.viewmodel

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
import ai.genwhy.nobonk.util.Dbg
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.genwhy.nobonk.data.DetectionEvent
import ai.genwhy.nobonk.data.DetectionRepository
import ai.genwhy.nobonk.data.SessionSummary
import ai.genwhy.nobonk.ml.DetectionEngine
import ai.genwhy.nobonk.ml.FrameCadence
import ai.genwhy.nobonk.ml.SensorMonitor
import ai.genwhy.nobonk.model.AlertLevel
import ai.genwhy.nobonk.model.Detection
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
    val family: String = "YOLO26"
) {
    // Both assets are the RAW one-to-many head ([1,84,3549]); the app runs its own
    // per-class NMS (ml/Nms.kt). Measured 2026-09-06 (x86 CPU, ORT 1.28): the raw head
    // is ~1.5x faster than the end-to-end export (yolo26s 31 ms vs 47 ms) and avoids
    // TopK/gather ops that accelerators (NNAPI) may not support.
    /** YOLO26-nano — fastest, best battery; the everyday pick on mid-range phones. */
    Y26N("yolo26n_416.onnx", 416, "Fast",  skipNms = false, family = "YOLO26"),
    /** YOLO26-small — sharper on small/far objects. */
    Y26S("yolo26s_416.onnx", 416, "Sharp", skipNms = false, family = "YOLO26"),
}

/**
 * Foreground ViewModel. All detection logic now lives in the shared [DetectionEngine];
 * this class only owns Compose state, model selection, history, and (opt-in) location.
 */
class DetectionViewModel : ViewModel() {
    var detections by mutableStateOf<List<Detection>>(emptyList())
        private set

    var distanceThreshold by mutableFloatStateOf(2.0f)
        private set

    /** Audible chirps for MEDIUM/HIGH alerts (persisted). */
    var soundEnabled by mutableStateOf(true)
        private set

    /** Vibration ladder for LOW/MEDIUM/HIGH (persisted). */
    var hapticsEnabled by mutableStateOf(true)
        private set

    /** Spoken "Person on your left. Look up." on HIGH (persisted, default off). */
    var voiceEnabled by mutableStateOf(false)
        private set

    /** Stereo pan of the current top hazard, −1 (left) … +1 (right); null when clear. */
    var bearingPan by mutableStateOf<Float?>(null)
        private set

    var isInitializing by mutableStateOf(true)
        private set
    var initializationStatus by mutableStateOf("Starting system...")
        private set

    // Round-2: vehicles/bikes/obstacles ON by default (marketing promises them; TTC
    // gating now makes them safe to surface without sidewalk spam).
    var isObjectDetectionEnabled by mutableStateOf(true)
    var accuracyMode by mutableStateOf(AccuracyMode.Y26S)

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
    // Adaptive cadence inputs (written on the result path, read on the camera thread).
    @Volatile private var cadenceAlert = AlertLevel.NONE
    @Volatile private var cadenceHadDetections = false
    @Volatile private var lastSeenAt = 0L
    private val _processingGate = AtomicBoolean(false)

    companion object {
        private const val TAG = "DetectionViewModel"
        private const val EVENT_LOG_DEBOUNCE_MS = 3_000L
        private const val PREFS = "nobonk_prefs"
        private const val P_THRESHOLD = "threshold_m"
        private const val P_MODE = "accuracy_mode"
        private const val P_EVERYTHING = "detect_everything"
        private const val P_SOUND = "sound"
        private const val P_HAPTICS = "haptics"
        private const val P_VOICE = "voice"
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun restoreSettings() {
        val p = prefs() ?: return
        distanceThreshold = p.getFloat(P_THRESHOLD, distanceThreshold)
        isObjectDetectionEnabled = p.getBoolean(P_EVERYTHING, isObjectDetectionEnabled)
        soundEnabled = p.getBoolean(P_SOUND, true)
        hapticsEnabled = p.getBoolean(P_HAPTICS, true)
        voiceEnabled = p.getBoolean(P_VOICE, false)
        p.getString(P_MODE, null)?.let { name -> AccuracyMode.entries.firstOrNull { it.name == name }?.let { accuracyMode = it } }
    }

    fun setThreshold(meters: Float) { distanceThreshold = meters; prefs()?.edit()?.putFloat(P_THRESHOLD, meters)?.apply() }
    fun setDetectEverything(on: Boolean) { isObjectDetectionEnabled = on; prefs()?.edit()?.putBoolean(P_EVERYTHING, on)?.apply() }
    fun toggleSound(on: Boolean) { soundEnabled = on; prefs()?.edit()?.putBoolean(P_SOUND, on)?.apply() }
    fun toggleVoice(on: Boolean) { voiceEnabled = on; prefs()?.edit()?.putBoolean(P_VOICE, on)?.apply() }
    fun toggleHaptics(on: Boolean) { hapticsEnabled = on; prefs()?.edit()?.putBoolean(P_HAPTICS, on)?.apply() }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        restoreSettings()
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
                Dbg.e(TAG, "Failed to initialize: ${e.message}")
                initializationStatus = "Error: ${e.message} — tap a model to retry"
                isInitializing = false
            }
        }
    }

    /** Camera the preview bound; its intrinsics calibrate the distance label. */
    fun onCameraBound(info: androidx.camera.core.CameraInfo) {
        cameraInfo = info
        engine?.attachCamera(info)
    }
    private var cameraInfo: androidx.camera.core.CameraInfo? = null

    private fun loadModel(context: Context) {
        val mode = accuracyMode
        initializationStatus = "Loading ${mode.modelFile} @ ${mode.inputPx}px..."
        val eng = engine ?: DetectionEngine(context.applicationContext).also { engine = it }
        eng.loadModel(mode.modelFile, mode.inputPx, mode.skipNms)
        cameraInfo?.let { eng.attachCamera(it) }
        eng.startSensors()   // angle monitoring for the foreground pipeline
        isHardwareAccelerated = eng.isHardwareAccelerated
        initializationStatus = "Running AI pre-flight..."
        eng.warmUp()
    }

    fun setAccuracyMode(mode: AccuracyMode, context: Context) {
        if (mode == accuracyMode && engine != null) return
        accuracyMode = mode
        prefs()?.edit()?.putString(P_MODE, mode.name)?.apply()
        viewModelScope.launch(Dispatchers.Main) {
            isInitializing = true
            initializationStatus = "Switching to ${mode.family} ${mode.label}…"
            try {
                withContext(Dispatchers.IO) { loadModel(context) }
                initializationStatus = "Ready — ${mode.family} ${mode.label}"
                delay(600)
            } catch (e: Exception) {
                Dbg.e(TAG, "Model switch failed: ${e.message}")
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
            Dbg.i(TAG, "Coarse location not granted — tagging stays off")
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
            Dbg.w(TAG, "Location tracking failed: ${e.message}")
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
        val interval = FrameCadence.intervalMs(cadenceAlert, cadenceHadDetections, now - lastSeenAt, batteryLevel)
        if (now - lastProcessTime < interval) { imageProxy.close(); return }
        if (!_processingGate.compareAndSet(false, true)) { imageProxy.close(); return }
        lastProcessTime = now
        val eng = engine ?: run { imageProxy.close(); _processingGate.set(false); return }

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val cfg = DetectionEngine.Config(distanceThreshold, isObjectDetectionEnabled, soundEnabled, hapticsEnabled, voiceEnabled)
                val result = eng.process(imageProxy, cfg)
                cadenceAlert = result.highestAlert
                cadenceHadDetections = result.detections.isNotEmpty()
                if (cadenceHadDetections) lastSeenAt = System.currentTimeMillis()

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
                    bearingPan = result.bearingPan
                }
            } catch (e: Exception) {
                Dbg.e(TAG, "Frame processing error: ${e.message}")
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
