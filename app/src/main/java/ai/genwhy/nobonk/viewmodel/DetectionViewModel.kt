package ai.genwhy.nobonk.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
import ai.genwhy.nobonk.ml.ScanSession
import ai.genwhy.nobonk.ml.BatteryLevel
import ai.genwhy.nobonk.ml.BatteryMonitor
import ai.genwhy.nobonk.ml.SensorMonitor
import ai.genwhy.nobonk.model.AlertLevel
import ai.genwhy.nobonk.model.Detection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Two YOLO26 raw-head models. Both use in-app NMS.
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

    /** Night boost currently applied to the detector input. */
    var isNightBoost by mutableStateOf(false)
        private set

    /** Rolling detector latency (ms) and processed-frame rate, for the status bar. */
    var inferMs by mutableIntStateOf(0)
        private set
    var fps by mutableFloatStateOf(0f)
        private set
    private var lastResultAt = 0L
    private var fpsEma = 0f

    /** Foreground scanning on/off. Off after the user presses Stop (in-app or notification) until Start. */
    var scanningEnabled by mutableStateOf(true)
        private set
    /** Generation-tagged session: in-flight frames from before Stop cannot post results, cues or history. */
    private val session = ScanSession().also { it.setOwnerAvailable(false) }

    /** DetectionScreen owns foreground work only while resumed and visible past the safety gate. */
    fun setForegroundActive(active: Boolean) {
        session.setOwnerAvailable(active)
        engine?.muted = !session.mayScan
        if (session.mayScan && modelReady && !isInitializing) engine?.startSensors()
        else {
            engine?.silence(); engine?.stopSensors()
            if (!active) clearScanResult()
        }
    }
    fun stopScanning() {
        session.stop()
        engine?.muted = true
        engine?.silence()   // cancel a chirp / speech / vibration already playing
        engine?.stopSensors()   // no accelerometer/gravity sampling while stopped
        scanningEnabled = false
        clearScanResult()
        try { locationListener?.let { locationManager?.removeUpdates(it) } } catch (_: Exception) {}
    }

    private fun clearScanResult() {
        detections = emptyList(); frameAlert = AlertLevel.NONE; lookUpLabel = null; bearingPan = null
        isWallDetected = false; isGroundHazardDetected = false
        isCameraBlocked = false; isLowLight = false; isNightBoost = false
        phoneAngleHint = ""; phoneAngleQuality = SensorMonitor.AngleQuality.OK
        fps = 0f; inferMs = 0; lastResultAt = 0L; fpsEma = 0f
        cadenceAlert = AlertLevel.NONE; cadenceHadDetections = false; cadenceBlocked = false
    }
    fun startScanning() {
        if (cleared.get()) return
        cameraError = null
        ai.genwhy.nobonk.safety.SessionState.backgroundStoppedByUser = false   // explicit new session: obsolete stop memory cleared
        session.start(); scanningEnabled = true
        engine?.muted = !session.mayScan
        if (session.mayScan) engine?.startSensors()
        if (locationTaggingEnabled) appContext?.let { enableLocationTagging(it) }
        if (!modelReady && !isInitializing) appContext?.let { requestModel(it, accuracyMode) }
    }

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
    var accuracyMode by mutableStateOf(AccuracyMode.Y26N)

    var batteryLevel by mutableIntStateOf(100)
        private set
    private var batteryMonitor: BatteryMonitor? = null

    private fun onBatteryLevel(level: Int) {
        val wasPaused = batteryLevel < BatteryLevel.MIN_SCAN_PERCENT
        batteryLevel = level
        val available = level >= BatteryLevel.MIN_SCAN_PERCENT
        session.setPowerAvailable(available)
        if (!available) {
            engine?.muted = true
            engine?.silence(); engine?.stopSensors()
            clearScanResult()
        } else if (wasPaused && session.mayScan && modelReady && !isInitializing) {
            engine?.muted = false
            engine?.startSensors()
        }
    }
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

    var executionProvider by mutableStateOf("CPU")
        private set

    /** Location tagging is OPT-IN and default OFF (fixes SEC-N01/N03/N08). */
    var locationTaggingEnabled by mutableStateOf(false)
        private set

    // ── History / analytics state ────────────────────────────────────────────
    var historyEvents by mutableStateOf<List<DetectionEvent>>(emptyList())
        private set
    var historySessions by mutableStateOf<List<SessionSummary>>(emptyList())
        private set

    @Volatile private var engine: DetectionEngine? = null
    private val engineMutex = Mutex()
    private val historyMutex = Mutex()
    private val historyGeneration = AtomicLong(0)
    private val modelGeneration = AtomicLong(0)
    private val cleared = AtomicBoolean(false)
    @Volatile private var modelReady = false
    private var initialized = false
    private var modelJob: Job? = null
    var cameraError by mutableStateOf<String?>(null)
        private set
    var historyError by mutableStateOf<String?>(null)
        private set
    fun dismissHistoryError() { historyError = null }
    fun reportCameraError(message: String) { stopScanning(); cameraError = message }

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
    @Volatile private var cadenceBlocked = false
    @Volatile private var cadenceStationaryMs = 0L
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
    /** Play the HIGH cue set once so the user knows what an alert feels like. */
    fun testAlert() {
        val eng = engine ?: return
        viewModelScope.launch(Dispatchers.Main.immediate) {   // same cue ownership rule as live cues
            if (!session.mayScan) return@launch
            eng.previewCue(DetectionEngine.Config(distanceThreshold, isObjectDetectionEnabled, soundEnabled, hapticsEnabled, voiceEnabled))
        }
    }
    fun toggleVoice(on: Boolean) {
        voiceEnabled = on; prefs()?.edit()?.putBoolean(P_VOICE, on)?.apply()
        if (on) engine?.prepareVoice()
    }
    fun toggleHaptics(on: Boolean) { hapticsEnabled = on; prefs()?.edit()?.putBoolean(P_HAPTICS, on)?.apply() }

    fun initialize(context: Context) {
        if (initialized) return // same ViewModel survives Activity configuration changes
        initialized = true
        appContext = context.applicationContext
        restoreSettings()
        batteryMonitor = BatteryMonitor(context, ::onBatteryLevel).also { it.start() }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                historyMutex.withLock { repository = DetectionRepository(context.applicationContext) }
                refreshHistory()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { historyError = "Local history is unavailable. Scanning still works." }
            }
        }
        requestModel(context.applicationContext, accuracyMode)
    }

    fun onCameraBound(info: androidx.camera.core.CameraInfo) {
        cameraInfo = info
        engine?.attachCamera(info)
    }
    private var cameraInfo: androidx.camera.core.CameraInfo? = null

    /** Native inference, model replacement and disposal share one suspendable owner lock. */
    private fun requestModel(context: Context, mode: AccuracyMode) {
        val request = modelGeneration.incrementAndGet()
        modelJob?.cancel()
        session.stop() // results queued by the previous model cannot publish
        isInitializing = true
        modelReady = false
        cameraError = null
        initializationStatus = "Loading ${mode.label}…"
        modelJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    engineMutex.withLock {
                        if (cleared.get() || request != modelGeneration.get()) return@withLock
                        val eng = engine ?: DetectionEngine(context.applicationContext).also { engine = it }
                        eng.loadModel(mode.modelFile, mode.inputPx, mode.skipNms)
                        cameraInfo?.let { eng.attachCamera(it) }
                        eng.warmUp()
                    }
                }
                if (cleared.get() || request != modelGeneration.get()) return@launch
                engine?.let { eng ->
                    if (voiceEnabled) eng.prepareVoice()
                    if (scanningEnabled) session.start()
                    eng.muted = !session.mayScan
                    if (session.mayScan) eng.startSensors() else eng.stopSensors()
                    executionProvider = eng.executionProvider
                }
                modelReady = true
                initializationStatus = "Ready — ${mode.label}"
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (request == modelGeneration.get() && !cleared.get()) {
                    Dbg.e(TAG, "Model load failed", e)
                    reportCameraError("Could not load ${mode.label}. Select Fast or Sharp to retry.")
                }
            } finally {
                if (request == modelGeneration.get() && !cleared.get()) isInitializing = false
            }
        }
    }

    fun setAccuracyMode(mode: AccuracyMode, context: Context) {
        if (mode == accuracyMode && engine != null && cameraError == null && !isInitializing) return
        accuracyMode = mode
        prefs()?.edit()?.putString(P_MODE, mode.name)?.apply()
        requestModel(context.applicationContext, mode)
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
        try { locationListener?.let { locationManager?.removeUpdates(it) } } catch (_: Exception) {}
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
        val generation = historyGeneration.get()
        viewModelScope.launch(Dispatchers.IO) {
            historyMutex.withLock {
                val repo = repository ?: return@withLock
                val events: List<DetectionEvent>
                val sessions: List<SessionSummary>
                try { events = repo.getAllEvents(); sessions = repo.getRecentSessions(20) }
                catch (e: Exception) {
                    withContext(Dispatchers.Main) { historyError = "History could not be read. Please try again." }
                    return@withLock
                }
                withContext(Dispatchers.Main) {
                    if (generation != historyGeneration.get()) return@withContext
                    historyEvents = events; historySessions = sessions
                }
            }
        }
    }

    fun clearHistory() {
        val generation = historyGeneration.incrementAndGet() // invalidates old writes and UI publications now
        lastEventTime.clear()
        viewModelScope.launch(Dispatchers.IO) {
            historyMutex.withLock {
                try {
                    (repository ?: error("History unavailable")).clearAll()
                    withContext(Dispatchers.Main) {
                        if (generation == historyGeneration.get()) {
                            historyEvents = emptyList(); historySessions = emptyList(); historyError = null
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { historyError = "History could not be cleared. Please try again." }
                }
            }
        }
    }

    private fun logEvent(detection: Detection) {
        val now = System.currentTimeMillis()
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (elapsed - (lastEventTime[detection.className] ?: 0L) < EVENT_LOG_DEBOUNCE_MS) return
        lastEventTime[detection.className] = elapsed
        val loc = if (locationTaggingEnabled) lastKnownLocation else null
        val generation = historyGeneration.get()
        val event = DetectionEvent(sessionId = sessionId, timestamp = now, latitude = loc?.latitude,
            longitude = loc?.longitude, className = detection.className, distance = detection.distance,
            alertLevel = detection.alertLevel.name, isApproaching = detection.isApproaching)
        viewModelScope.launch(Dispatchers.IO) {
            historyMutex.withLock {
                if (generation != historyGeneration.get()) return@withLock
                val repo = repository ?: return@withLock
                if (!repo.addEvent(event)) return@withLock
                val updated = repo.getAllEvents()
                withContext(Dispatchers.Main) {
                    if (generation == historyGeneration.get()) historyEvents = updated
                }
            }
        }
    }

    fun processFrame(imageProxy: ImageProxy) {
        if (!session.mayScan || isInitializing) { imageProxy.close(); return }
        // Wall-clock changes must not freeze frame admission or inflate cue cooldowns.
        val now = android.os.SystemClock.elapsedRealtime()
        val interval = FrameCadence.intervalMs(cadenceAlert, cadenceHadDetections, now - lastSeenAt, batteryLevel, cadenceBlocked, cadenceStationaryMs)
        if (now - lastProcessTime < interval) { imageProxy.close(); return }
        if (!_processingGate.compareAndSet(false, true)) { imageProxy.close(); return }
        lastProcessTime = now
        val eng = engine ?: run { imageProxy.close(); _processingGate.set(false); return }
        val gen = session.current()

        viewModelScope.launch(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
            var handedToEngine = false
            try {
                // Cues are validated per frame inside the engine (Stop+Start cannot unmute a stale inference).
                val cfg = DetectionEngine.Config(distanceThreshold, isObjectDetectionEnabled, soundEnabled, hapticsEnabled, voiceEnabled, cuesAllowed = { session.isCurrent(gen) }, sessionToken = gen)
                val result = engineMutex.withLock {
                    if (cleared.get() || !session.isCurrent(gen) || isInitializing) {
                        return@launch
                    }
                    handedToEngine = true
                    eng.process(imageProxy, cfg)
                }
                // Stop (or Stop+Start) happened while this frame was in inference: drop everything.
                if (!session.isCurrent(gen)) return@launch
                cadenceAlert = result.highestAlert
                cadenceHadDetections = result.detections.isNotEmpty()
                cadenceBlocked = result.cameraBlocked
                cadenceStationaryMs = result.stationaryMs
                if (cadenceHadDetections) lastSeenAt = android.os.SystemClock.elapsedRealtime()

                withContext(Dispatchers.Main) {
                    // Re-check at the publication boundary: a Stop queued ahead of us on Main wins.
                    if (!session.isCurrent(gen)) return@withContext
                    for (d in result.detections) if (d.alertLevel != AlertLevel.NONE) logEvent(d)   // history commit only for the owned session
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
                    isNightBoost = result.nightBoost
                    inferMs = result.inferMs.toInt()
                    val t = android.os.SystemClock.elapsedRealtime()
                    if (lastResultAt != 0L) {
                        val inst = 1000f / (t - lastResultAt).coerceAtLeast(1L)
                        fpsEma = if (fpsEma == 0f) inst else fpsEma * 0.8f + inst * 0.2f
                        fps = fpsEma
                    }
                    lastResultAt = t
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Dbg.e(TAG, "Frame processing error", e)
                withContext(Dispatchers.Main) {
                    if (session.isCurrent(gen)) reportCameraError("Detection was interrupted. Tap Start scanning to retry.")
                }
            } finally {
                if (!handedToEngine) imageProxy.close() // releases frames cancelled while waiting for the owner lock
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
        cleared.set(true); session.stop(); modelGeneration.incrementAndGet()
        batteryMonitor?.close(); batteryMonitor = null
        engine?.halt()
        // ViewModel scope is cancelled now. A separate bounded cleanup waits for native work to drain.
        CoroutineScope(Dispatchers.Default).launch {
            engineMutex.withLock { engine?.close(); engine = null }
        }
        try { locationListener?.let { locationManager?.removeUpdates(it) } } catch (_: Exception) {}
    }
}
