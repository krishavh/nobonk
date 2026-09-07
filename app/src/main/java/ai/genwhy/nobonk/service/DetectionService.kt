package ai.genwhy.nobonk.service

import android.animation.ObjectAnimator
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.*
import ai.genwhy.nobonk.util.Dbg
import android.view.*
import android.view.animation.LinearInterpolator
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
import ai.genwhy.nobonk.model.AlertLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var distanceThreshold = 2.0f
    // Round-2: vehicles/bikes/obstacles ON by default (the marketing promises them, and
    // TTC gating now makes them safe to surface). The intent extra still overrides.
    private var includeNonPerson = true
    private var modelFile = "yolo26s_416.onnx"
    private var inputPx = 416
    private var skipNms = true
    private var soundEnabled = true
    private var hapticsEnabled = true
    private var voiceEnabled = false

    // FPS cap + single-flight gate (fixes PERF-C03: no unbounded background inference).
    private val gate = AtomicBoolean(false)
    private var lastProcessTime = 0L
    @Volatile private var cadenceAlert = AlertLevel.NONE
    @Volatile private var cadenceHadDetections = false
    @Volatile private var lastSeenAt = 0L
    @Volatile private var cadenceBlocked = false
    @Volatile private var cadenceStationaryMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var hudView: View? = null
    private var scanningView: View? = null
    /** Small always-available 'Open NoBonk' pill (top-end) shown for the whole background session. */
    private var returnView: View? = null
    private var knightRiderAnimator: ObjectAnimator? = null
    /** Every async step asks this before proceeding; Stop flips it once, from any phase. */
    private val life = ServiceLifecycle()
    private var startupJob: Job? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null

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
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
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
        when (intent?.action) {
            ACTION_STOP -> {
                val reason = if (intent.getStringExtra(EXTRA_STOP_REASON) == STOP_REASON_HANDOFF) ServiceLifecycle.StopReason.HANDOFF else ServiceLifecycle.StopReason.USER
                shutdown(reason)
                return START_NOT_STICKY
            }
            else -> {
                if (!life.onStartRequested()) { Dbg.w(TAG, "start refused: this instance is already stopped"); stopSelf(); return START_NOT_STICKY }
                // Defensive: never run detection for an install that has not acknowledged the
                // current safety notice (the UI gate is the first line, this is the second).
                val ack = getSharedPreferences("nobonk_prefs", Context.MODE_PRIVATE).getInt(ai.genwhy.nobonk.safety.SafetyNotice.PREF_ACK_VERSION, 0)
                val explicit = intent != null   // null = sticky restart after a process kill
                if (!ai.genwhy.nobonk.safety.SessionState.gate.serviceMayStart(ack, explicitStart = explicit)) { Dbg.w(TAG, "start refused: safety gate not cleared (explicit=$explicit)"); stopSelf(); return START_NOT_STICKY }
                startForegroundService()   // ACTION_START or null (restarted)
                ai.genwhy.nobonk.safety.SessionState.gate.onServiceStarted()
            }
        }
        return if (life.sticky()) START_STICKY else START_NOT_STICKY
    }

    private fun startForegroundService() {
        val notification = createNotification("Watching your path · tap to open")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        showScanningIndicator()
        showReturnControl()

        startupJob = lifecycleScope.launch(Dispatchers.Default) {
            val eng = try {
                DetectionEngine(this@DetectionService).also { it.loadModel(modelFile, inputPx, skipNms) }
            } catch (e: Exception) {
                Dbg.e(TAG, "Model load failed: ${e.message}", e)
                withContext(Dispatchers.Main) { shutdown(ServiceLifecycle.StopReason.HANDOFF) }
                return@launch
            }
            // A Stop that arrived during the (long, blocking) model load must not leak the engine
            // or start sensors/camera afterwards.
            if (!life.onModelLoaded()) { eng.close(); return@launch }
            engine = eng
            if (voiceEnabled) eng.prepareVoice()
            eng.startSensors()   // background angle gating
            kotlinx.coroutines.delay(400)   // let the activity release the camera first
            withContext(Dispatchers.Main) { if (life.mayBindCamera()) startCamera() }
        }
    }

    private fun showScanningIndicator() {
        if (scanningView != null) return
        val density = resources.displayMetrics.density
        val heightPx = (8 * density).toInt()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP; y = 0 }
        try {
            scanningView = LayoutInflater.from(this).inflate(R.layout.layout_scanning_indicator, null)
            val line = scanningView!!.findViewById<View>(R.id.scanningLine)
            windowManager.addView(scanningView, params)
            knightRiderAnimator = ObjectAnimator.ofFloat(line, "alpha", 0.4f, 1.0f).apply {
                duration = 800
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                interpolator = LinearInterpolator()
                start()
            }
        } catch (e: Exception) {
            Dbg.e(TAG, "Failed to show scanning indicator", e)
        }
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
            setTextColor(android.graphics.Color.parseColor("#FF04140D"))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = (48 * d).toInt(); minWidth = (48 * d).toInt()   // accessible touch target
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 24 * d; setColor(android.graphics.Color.parseColor("#FF2EE6A6"))
                setStroke((2 * d).toInt(), android.graphics.Color.parseColor("#FF0B1220"))
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
            val provider = cameraProviderFuture.get()
            cameraProvider = provider
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { proxy -> processFrame(proxy) } }
            analysis = imageAnalysis
            try {
                provider.unbindAll()
                val cam = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, imageAnalysis)
                engine?.attachCamera(cam.cameraInfo)
                life.onCameraBound()
            } catch (e: Exception) {
                Dbg.e(TAG, "Camera binding failed", e)
                shutdown(ServiceLifecycle.StopReason.HANDOFF)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        if (!life.mayProcessFrames()) { imageProxy.close(); return }
        val eng = engine ?: run { imageProxy.close(); return }
        val now = System.currentTimeMillis()
        val interval = FrameCadence.intervalMs(cadenceAlert, cadenceHadDetections, now - lastSeenAt, batteryPct(), cadenceBlocked, cadenceStationaryMs)
        if (now - lastProcessTime < interval) { imageProxy.close(); return }
        if (!gate.compareAndSet(false, true)) { imageProxy.close(); return }
        lastProcessTime = now

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val cfg = DetectionEngine.Config(distanceThreshold, includeNonPerson, soundEnabled, hapticsEnabled, voiceEnabled)
                val result = eng.process(imageProxy, cfg)   // closes imageProxy, fires haptics+sound
                cadenceAlert = result.highestAlert
                cadenceHadDetections = result.detections.isNotEmpty()
                cadenceBlocked = result.cameraBlocked
                cadenceStationaryMs = result.stationaryMs
                if (cadenceHadDetections) lastSeenAt = System.currentTimeMillis()
                // A frame that was in flight when Stop arrived must not re-create the HUD or re-post
                // the notification from a stopped service (this was the visible "Stop didn't work").
                if (!life.mayPostAlerts()) return@launch
                mainHandler.post { updateHud(result.hudMessage) }
                if (result.highestAlert != AlertLevel.NONE) {
                    val n = result.detections.size
                    updateNotification("${result.highestAlert.name.lowercase().replaceFirstChar { it.uppercase() }} alert · $n object${if (n == 1) "" else "s"} in view")
                }
            } catch (e: Exception) {
                Dbg.e(TAG, "Frame processing error: ${e.message}", e)
            } finally {
                gate.set(false)
            }
        }
    }

    /** Cheap sticky-intent battery read for the cadence policy (no receiver needed). */
    private fun batteryPct(): Int = try {
        val i = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) level * 100 / scale else 100
    } catch (_: Exception) { 100 }

    private fun updateHud(message: String?) {
        if (message != null && !life.mayPostAlerts()) return
        if (message != null) {
            if (hudView == null) {
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply { gravity = Gravity.TOP; y = topInsetPx() + (8 * resources.displayMetrics.density).toInt() }
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
            .setContentTitle("NoBonk is watching")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_view)
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
        if (!life.mayPostAlerts()) return
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
        startupJob?.cancel(); startupJob = null
        engine?.halt()
        try { analysis?.clearAnalyzer() } catch (_: Exception) {}
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        analysis = null
        mainHandler.removeCallbacksAndMessages(null)
        knightRiderAnimator?.cancel(); knightRiderAnimator = null
        hudView?.let { v -> try { windowManager.removeView(v) } catch (_: Exception) {} }; hudView = null
        removeReturnControl()
        scanningView?.let { v -> try { windowManager.removeView(v) } catch (_: Exception) {} }; scanningView = null
        engine?.close(); engine = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID) } catch (_: Exception) {}
        if (life.stoppedByUser) ai.genwhy.nobonk.safety.SessionState.backgroundStoppedByUser = true
        ai.genwhy.nobonk.safety.SessionState.gate.onServiceStopped()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        shutdown(life.stopReason ?: ServiceLifecycle.StopReason.HANDOFF)
        cameraExecutor.shutdown()
    }
}
