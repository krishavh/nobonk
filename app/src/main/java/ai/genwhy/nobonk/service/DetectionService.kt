package ai.genwhy.nobonk.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.*
import ai.genwhy.nobonk.util.Dbg
import android.view.*
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import ai.genwhy.nobonk.MainActivity
import ai.genwhy.nobonk.R
import ai.genwhy.nobonk.ml.DetectionEngine
import ai.genwhy.nobonk.ml.FrameCadence
import ai.genwhy.nobonk.ml.BatteryMonitor
import ai.genwhy.nobonk.model.AlertLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background foreground-service pipeline. Now uses the SAME [DetectionEngine] as the
 * foreground ViewModel — so it gets the rotation-correct frame conversion, the user's
 * threshold + object-detection setting (passed via intent extras), the fill-based alarm
 * ladder, approach escalation, and shared haptics/sound. This is the real use case
 * (phone in hand, app hidden), and it was previously the buggiest path.
 */
class DetectionService : LifecycleService() {

    private var engine: DetectionEngine? = null
    private lateinit var batteryMonitor: BatteryMonitor
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var distanceThreshold = 2.0f
    // Round-2: vehicles/bikes/obstacles ON by default (the marketing promises them, and
    // TTC gating now makes them safe to surface). The intent extra still overrides.
    private var includeNonPerson = true
    private var modelFile = "yolo26n_416.onnx"
    private var inputPx = 416
    private var skipNms = false
    private var soundEnabled = true
    private var hapticsEnabled = true
    private var voiceEnabled = false

    // FPS cap + single-flight gate (fixes PERF-C03: no unbounded background inference).
    private val gate = AtomicBoolean(false)
    /** Engine awaiting release while a frame is in flight; whoever clears it last (frame finally / shutdown) closes it. */
    private val pendingRelease = java.util.concurrent.atomic.AtomicReference<DetectionEngine?>(null)
    private var lastProcessTime = 0L
    @Volatile private var cadenceAlert = AlertLevel.NONE
    @Volatile private var cadenceHadDetections = false
    @Volatile private var lastSeenAt = 0L
    @Volatile private var cadenceBlocked = false
    @Volatile private var cadenceStationaryMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var hudView: View? = null
    private var edge: EdgeIndicator? = null
    /** Small always-available 'Open NoBonk' pill (top-end) shown for the whole background session. */
    private var returnView: View? = null
    private var hudMessage: String? = null
    private var lastNotificationContent: String? = null
    /** Every async step asks this before proceeding; Stop flips it once, from any phase. */
    private val life = ServiceLifecycle()
    private var startupJob: Job? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private val scanStatus = BackgroundScanStatus()
    private var latestResult: DetectionEngine.Result? = null
    private val statusWatchdog = object : Runnable {
        override fun run() {
            if (!life.mayPostAlerts()) return
            renderBackgroundStatus()
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    companion object {
        private const val CHANNEL_ID = "DetectionServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "DetectionService"

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_THRESHOLD = "extra_threshold"
        const val EXTRA_INCLUDE_NONPERSON = "extra_include_nonperson"
        const val EXTRA_MODEL = "extra_model"
        const val EXTRA_INPUT_PX = "extra_input_px"
        const val EXTRA_SKIP_NMS = "extra_skip_nms"
        const val EXTRA_SOUND = "extra_sound"
        const val EXTRA_HAPTICS = "extra_haptics"
        const val EXTRA_VOICE = "extra_voice"
        /** ACTION_STOP extra: "user" (default; app Stop button / notification) or "handoff" (activity taking the camera back). */
        const val EXTRA_STOP_REASON = "extra_stop_reason"
        const val STOP_REASON_USER = "user"
        const val STOP_REASON_HANDOFF = "handoff"
    }

    override fun onCreate() {
        super.onCreate()
        batteryMonitor = BatteryMonitor(this).also { it.start() }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                val reason = if (intent.getStringExtra(EXTRA_STOP_REASON) == STOP_REASON_HANDOFF) ServiceLifecycle.StopReason.HANDOFF else ServiceLifecycle.StopReason.USER
                shutdown(reason)
                return START_NOT_STICKY
            }
            else -> {
                if (!life.onStartRequested()) {
                    if (life.isStopped) { stopSelf(); return START_NOT_STICKY }
                    return START_STICKY // already loading/running; keep its single engine and settings
                }
                intent?.let {
                    distanceThreshold = it.getFloatExtra(EXTRA_THRESHOLD, distanceThreshold)
                    includeNonPerson = it.getBooleanExtra(EXTRA_INCLUDE_NONPERSON, includeNonPerson)
                    modelFile = it.getStringExtra(EXTRA_MODEL) ?: modelFile
                    inputPx = it.getIntExtra(EXTRA_INPUT_PX, inputPx)
                    skipNms = it.getBooleanExtra(EXTRA_SKIP_NMS, skipNms)
                    soundEnabled = it.getBooleanExtra(EXTRA_SOUND, soundEnabled)
                    hapticsEnabled = it.getBooleanExtra(EXTRA_HAPTICS, hapticsEnabled)
                    voiceEnabled = it.getBooleanExtra(EXTRA_VOICE, voiceEnabled)
                }
                // Defensive: never run detection for an install that has not acknowledged the
                // current safety notice (the UI gate is the first line, this is the second).
                val ack = getSharedPreferences("nobonk_prefs", Context.MODE_PRIVATE).getInt(ai.genwhy.nobonk.safety.SafetyNotice.PREF_ACK_VERSION, 0)
                val explicit = intent != null   // null = sticky restart after a process kill
                if (!ai.genwhy.nobonk.safety.SessionState.gate.serviceMayStart(ack, explicitStart = explicit)) { Dbg.w(TAG, "start refused: safety gate not cleared (explicit=$explicit)"); stopSelf(); return START_NOT_STICKY }
                try {
                    startForegroundService()
                    ai.genwhy.nobonk.safety.SessionState.gate.onServiceStarted()
                } catch (e: Exception) {
                    ai.genwhy.nobonk.safety.SessionState.backgroundFailure = "Background camera could not start. Check camera access and try again."
                    android.widget.Toast.makeText(this, "NoBonk could not start. Open the app to retry.", android.widget.Toast.LENGTH_LONG).show()
                    shutdown(ServiceLifecycle.StopReason.HANDOFF)
                    return START_NOT_STICKY
                }
            }
        }
        return if (life.sticky()) START_STICKY else START_NOT_STICKY
    }

    private fun startForegroundService() {
        val notification = createNotification("Preparing camera · tap to open")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        showEdgeIndicator()
        showReturnControl()

        startupJob = lifecycleScope.launch(Dispatchers.Default) {
            var pendingEngine: DetectionEngine? = null
            val eng = try {
                DetectionEngine(this@DetectionService).also { candidate ->
                    pendingEngine = candidate
                    candidate.loadModel(modelFile, inputPx, skipNms) {
                        coroutineContext.ensureActive()
                        if (life.isStopped) throw CancellationException("Background startup stopped")
                    }
                }
            } catch (e: Exception) {
                // The local engine has not been adopted and has no sensors/camera yet.
                // Native calls finish before cancellation checks release their sessions.
                pendingEngine?.let { runCatching { it.close() } }
                if (e is CancellationException) throw e
                Dbg.e(TAG, "Model load failed: ${e.message}", e)
                withContext(Dispatchers.Main + NonCancellable) {
                    if (!life.isStopped) {
                        ai.genwhy.nobonk.safety.SessionState.backgroundFailure = "Background detection could not load its model. Tap Start scanning to retry."
                        shutdown(ServiceLifecycle.StopReason.HANDOFF)
                    }
                }
                return@launch
            }
            // Engine ownership is confined to the main thread. Adoption is NonCancellable so a Stop
            // that races the load can never orphan a loaded engine: either shutdown() already ran
            // (onModelLoaded → false → close here) or it sees `engine` set and releases it.
            withContext(Dispatchers.Main + NonCancellable) {
                if (!life.onModelLoaded()) { eng.close(); return@withContext }
                engine = eng
                if (voiceEnabled) eng.prepareVoice()
                eng.startSensors()   // background angle gating
            }
            if (life.isStopped) return@launch
            kotlinx.coroutines.delay(400)   // let the activity release the camera first
            withContext(Dispatchers.Main) { if (life.mayBindCamera()) startCamera() }
        }
    }

    /** Moving red screen-edge trail. Hazard text and the return control remain separate. */
    private fun showEdgeIndicator() {
        if (edge != null) return
        edge = EdgeIndicator(this, windowManager).also { it.show(AlertLevel.NONE, cameraBlocked = true) }
    }
    /** Top inset (status bar + display cutout) in px, so overlay windows never sit under the clock. */
    private fun topInsetPx(): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
        } else {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else (24 * resources.displayMetrics.density).toInt()
        }
    } catch (_: Exception) { (24 * resources.displayMetrics.density).toInt() }

    /**
     * 'Open NoBonk' return control. Its own tiny overlay window: only the pill consumes
     * touches (the window IS the pill), everything else passes through to the app below.
     * Tapping brings the existing NoBonk task forward; the activity then takes over the
     * camera from this service (its onResume stops the service), so detection continues in
     * the foreground with a single camera client. Removed in onDestroy.
     */
    private fun showReturnControl() {
        if (returnView != null) return
        val d = resources.displayMetrics.density
        val pill = android.widget.TextView(this).apply {
            text = "Open NoBonk"
            contentDescription = "Open NoBonk controls"
            setTextColor(android.graphics.Color.parseColor("#FFF8FAFC"))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = (48 * d).toInt(); minWidth = (48 * d).toInt()   // accessible touch target
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 24 * d; setColor(android.graphics.Color.parseColor("#FF111827"))
                setStroke((1 * d).toInt().coerceAtLeast(1), android.graphics.Color.parseColor("#FFFF3B30"))
            }
            elevation = 6 * d
            isClickable = true; isFocusable = true
            setOnClickListener { openApp() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = (12 * d).toInt(); y = topInsetPx() + (8 * d).toInt() }
        try { windowManager.addView(pill, params); returnView = pill } catch (e: Exception) { Dbg.e(TAG, "return control add error", e) }
    }

    private fun removeReturnControl() {
        returnView?.let { v -> try { windowManager.removeView(v) } catch (_: Exception) {} }
        returnView = null
    }

    /** Bring the existing NoBonk task to the front (no new instance, no camera duplication). */
    private fun openApp() {
        try {
            val i = Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            startActivity(i)
        } catch (e: Exception) {
            Dbg.e(TAG, "openApp failed (notification tap remains the fallback)", e)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            if (!life.mayBindCamera()) return@addListener   // Stop arrived while the provider was resolving
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor) { proxy -> processFrame(proxy) } }
                analysis = imageAnalysis
                provider.unbindAll()
                val cam = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, imageAnalysis)
                engine?.attachCamera(cam.cameraInfo)
                life.onCameraBound()
                scanStatus.cameraBound(SystemClock.elapsedRealtime())
                mainHandler.post(statusWatchdog)
            } catch (e: Exception) {
                Dbg.e(TAG, "Camera binding failed", e)
                ai.genwhy.nobonk.safety.SessionState.backgroundFailure = "Background camera unavailable. Check camera access, then try again."
                shutdown(ServiceLifecycle.StopReason.HANDOFF)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        if (!life.mayProcessFrames()) { imageProxy.close(); return }
        val eng = engine ?: run { imageProxy.close(); return }
        val now = android.os.SystemClock.elapsedRealtime()
        val interval = FrameCadence.intervalMs(cadenceAlert, cadenceHadDetections, now - lastSeenAt, batteryMonitor.level, cadenceBlocked, cadenceStationaryMs)
        if (now - lastProcessTime < interval) { imageProxy.close(); return }
        if (!gate.compareAndSet(false, true)) { imageProxy.close(); return }
        lastProcessTime = now

        // ATOMIC: the body always runs (and so does `finally`) even if the scope is cancelled first —
        // otherwise a cancelled launch would leave the single-flight gate held forever.
        lifecycleScope.launch(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
            try {
                val cfg = DetectionEngine.Config(distanceThreshold, includeNonPerson, soundEnabled, hapticsEnabled, voiceEnabled,
                    cuesAllowed = { life.mayPostAlerts() && BackgroundScanStatus.isFresh(now, SystemClock.elapsedRealtime()) })
                val result = eng.process(imageProxy, cfg)   // closes imageProxy, fires haptics+sound
                cadenceAlert = result.highestAlert
                cadenceHadDetections = result.detections.isNotEmpty()
                cadenceBlocked = result.cameraBlocked
                cadenceStationaryMs = result.stationaryMs
                if (cadenceHadDetections) lastSeenAt = android.os.SystemClock.elapsedRealtime()
                // A frame that was in flight when Stop arrived must not re-create the HUD or re-post
                // the notification from a stopped service (this was the visible "Stop didn't work").
                // All publication (HUD, edge colour, notification) happens in ONE main-thread block with
                // the lifecycle check inside it, so it serializes with shutdown() (also main-thread): a
                // Stop that lands first removes this post or makes the check fail; nothing is re-posted.
                mainHandler.post {
                    if (!life.mayPostAlerts()) return@post
                    scanStatus.frameCompleted(now, result.cameraBlocked)
                    latestResult = result
                    renderBackgroundStatus()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Dbg.e(TAG, "Frame processing error: ${e.message}", e)
                withContext(Dispatchers.Main + NonCancellable) {
                    if (!life.isStopped) {
                        ai.genwhy.nobonk.safety.SessionState.backgroundFailure = "Background detection was interrupted. Open NoBonk to retry."
                        shutdown(ServiceLifecycle.StopReason.HANDOFF)
                    }
                }
            } finally {
                gate.set(false)
                pendingRelease.getAndSet(null)?.let { runCatching { it.close() } }   // deferred release after Stop
            }
        }
    }

    /** Main-thread publication; a stale result can never keep the scanning trail or hazard alive. */
    private fun renderBackgroundStatus() {
        if (!life.mayPostAlerts()) return
        val state = scanStatus.state(SystemClock.elapsedRealtime())
        val result = latestResult
        edge?.setLevel(result?.highestAlert ?: AlertLevel.NONE, state != BackgroundScanStatus.State.SCANNING)
        when (state) {
            BackgroundScanStatus.State.WAITING -> {
                updateHud(null)
                updateNotification("Waiting for camera results · tap to open")
            }
            BackgroundScanStatus.State.STALE -> {
                updateHud("SCANNING PAUSED — no recent camera results. Open NoBonk to check.")
                updateNotification("Scanning paused · no recent camera results")
            }
            BackgroundScanStatus.State.COVERED -> {
                updateHud("CAMERA COVERED — uncover the rear camera")
                updateNotification("Camera covered · tap to open")
            }
            BackgroundScanStatus.State.SCANNING -> {
                updateHud(result?.hudMessage)
                updateNotification(when {
                    result == null -> "Waiting for camera results · tap to open"
                    result.angleQuality == ai.genwhy.nobonk.ml.SensorMonitor.AngleQuality.BAD -> "Point the camera forward · tap to open"
                    result.highestAlert != AlertLevel.NONE -> "${result.highestAlert.name.lowercase().replaceFirstChar { it.uppercase() }} alert · tap to open"
                    result.lowLight -> "Low light · detection is less reliable"
                    else -> "Scanning · keep looking up"
                })
            }
        }
    }

    private fun updateHud(message: String?) {
        if (message != null && !life.mayPostAlerts()) return
        if (message == hudMessage && (message == null || hudView != null)) return
        hudMessage = message
        if (message != null) {
            if (hudView == null) {
                val params = WindowManager.LayoutParams(
                    (resources.displayMetrics.widthPixels - 32 * resources.displayMetrics.density).toInt().coerceAtLeast(1),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = topInsetPx() + (68 * resources.displayMetrics.density).toInt()
                    alpha = 0.75f // no overlap with the edge strips or Open NoBonk pill
                }
                try {
                    hudView = LayoutInflater.from(this).inflate(R.layout.layout_collision_warning, null)
                    windowManager.addView(hudView, params)
                } catch (e: Exception) {
                    Dbg.e(TAG, "HUD add error", e); return
                }
            }
            val tv = hudView!!.findViewById<android.widget.TextView>(R.id.warningText)
            tv?.text = message
            // Opaque, high-contrast backing bar (accessibility: glanceable in motion).
            val bgColor = when {
                message.contains("ANGLE") || message.contains("PHONE") -> android.graphics.Color.parseColor("#FFC62828")
                message.contains("LOW LIGHT") -> android.graphics.Color.parseColor("#FF263238")
                message.contains("STEP") -> android.graphics.Color.parseColor("#FF994400")
                message.contains("WALL") -> android.graphics.Color.parseColor("#FF0D47A1")
                else -> android.graphics.Color.parseColor("#FFCC0000")
            }
            hudView!!.setBackgroundColor(bgColor)
        } else if (hudView != null) {
            try { windowManager.removeView(hudView) } catch (_: Exception) {}
            hudView = null
        }
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, DetectionService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NoBonk")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentIntent(pendingIntent)
            .addAction(0, "Stop", stopPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification(content: String) {
        if (!life.mayPostAlerts() || content == lastNotificationContent) return
        lastNotificationContent = content
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Detection Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    /**
     * The one Stop path (ACTION_STOP from the app or the notification, bind failure, onDestroy).
     * Idempotent. Order matters: flip the lifecycle first so nothing queued can post anything,
     * then silence the engine, release the camera, tear down UI, drop the notification.
     */
    private fun shutdown(reason: ServiceLifecycle.StopReason) {
        val first = !life.isStopped
        life.stop(reason)
        if (!first) return
        batteryMonitor.close()
        startupJob?.cancel(); startupJob = null
        val eng = engine; engine = null
        latestResult = null
        eng?.halt()   // no further cues/speech/vibration/sensors, even for a frame already in flight
        try { analysis?.let { cameraProvider?.unbind(it) } } catch (_: Exception) {}   // only OUR use case — never a foreground preview
        try { analysis?.clearAnalyzer() } catch (_: Exception) {}
        analysis = null
        mainHandler.removeCallbacksAndMessages(null)
        hudView?.let { v -> try { windowManager.removeView(v) } catch (_: Exception) {} }; hudView = null
        removeReturnControl()
        edge?.hide(); edge = null
        releaseEngineWhenIdle(eng)
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID) } catch (_: Exception) {}
        if (life.stoppedByUser) ai.genwhy.nobonk.safety.SessionState.backgroundStoppedByUser = true
        ai.genwhy.nobonk.safety.SessionState.gate.onServiceStopped()
        stopSelf()
    }

    /**
     * Never close the ONNX session under an in-flight inference: the frame coroutine holds the
     * single-flight [gate] while it runs, so release is deferred to whoever finishes last.
     */
    private fun releaseEngineWhenIdle(eng: DetectionEngine?) {
        eng ?: return
        // Ownership hand-off, no timeout: park the engine, then if no frame holds the gate, take it
        // back and close it; if a frame does, its `finally` (ATOMIC start guarantees it runs) closes it.
        // getAndSet makes exactly one party the closer.
        pendingRelease.set(eng)
        if (!gate.get()) pendingRelease.getAndSet(null)?.let { runCatching { it.close() } }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!life.isStopped) {
            edge?.relayout()
            removeReturnControl(); showReturnControl()
            val message = hudMessage
            updateHud(null); updateHud(message)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        shutdown(life.stopReason ?: ServiceLifecycle.StopReason.HANDOFF)
        cameraExecutor.shutdown()
    }
}
