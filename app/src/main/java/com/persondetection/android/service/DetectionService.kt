package com.persondetection.android.service

import android.animation.ObjectAnimator
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.*
import android.util.Log
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
import com.persondetection.android.MainActivity
import com.persondetection.android.R
import com.persondetection.android.ml.DetectionEngine
import com.persondetection.android.model.AlertLevel
import kotlinx.coroutines.Dispatchers
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
    private var modelFile = "yolo11s.onnx"
    private var inputPx = 416
    private var skipNms = false

    // FPS cap + single-flight gate (fixes PERF-C03: no unbounded background inference).
    private val gate = AtomicBoolean(false)
    private var lastProcessTime = 0L
    private val minFrameIntervalMs = 100

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var hudView: View? = null
    private var scanningView: View? = null
    private var knightRiderAnimator: ObjectAnimator? = null

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
        }
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            else -> startForegroundService()   // ACTION_START or null (restarted)
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val notification = createNotification("Scanning for people...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        showScanningIndicator()

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                engine = DetectionEngine(this@DetectionService).also {
                    it.loadModel(modelFile, inputPx, skipNms)
                    it.startSensors()   // background angle gating (was never wired before)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Model load failed: ${e.message}", e)
                return@launch
            }
            kotlinx.coroutines.delay(400)   // let the activity release the camera first
            withContext(Dispatchers.Main) { startCamera() }
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
            Log.e(TAG, "Failed to show scanning indicator", e)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { proxy -> processFrame(proxy) } }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, imageAnalysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val eng = engine ?: run { imageProxy.close(); return }
        val now = System.currentTimeMillis()
        if (now - lastProcessTime < minFrameIntervalMs) { imageProxy.close(); return }
        if (!gate.compareAndSet(false, true)) { imageProxy.close(); return }
        lastProcessTime = now

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val cfg = DetectionEngine.Config(distanceThreshold, includeNonPerson)
                val result = eng.process(imageProxy, cfg)   // closes imageProxy, fires haptics+sound
                mainHandler.post { updateHud(result.hudMessage) }
                if (result.highestAlert != AlertLevel.NONE) {
                    updateNotification("Alert: ${result.highestAlert} — ${result.detections.size} object(s)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame processing error: ${e.message}", e)
            } finally {
                gate.set(false)
            }
        }
    }

    private fun updateHud(message: String?) {
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
                ).apply { gravity = Gravity.TOP; y = 100 }
                try {
                    hudView = LayoutInflater.from(this).inflate(R.layout.layout_collision_warning, null)
                    windowManager.addView(hudView, params)
                } catch (e: Exception) {
                    Log.e(TAG, "HUD add error", e); return
                }
            }
            val tv = hudView!!.findViewById<android.widget.TextView>(R.id.warningText)
            tv?.text = message
            val bgColor = when {
                message.contains("ANGLE") || message.contains("PHONE") -> android.graphics.Color.parseColor("#CCD32F2F")
                message.contains("LOW LIGHT") -> android.graphics.Color.parseColor("#CC37474F")
                message.contains("STEP") -> android.graphics.Color.parseColor("#CC994400")
                message.contains("WALL") -> android.graphics.Color.parseColor("#CC1565C0")
                else -> android.graphics.Color.parseColor("#CCCC0000")
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NoBonk active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Detection Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        knightRiderAnimator?.cancel()
        updateHud(null)
        if (scanningView != null) {
            try { windowManager.removeView(scanningView) } catch (_: Exception) {}
            scanningView = null
        }
        engine?.close()
        cameraExecutor.shutdown()
    }
}
