package com.persondetection.android.service

import android.animation.ObjectAnimator
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.RingtoneManager
import android.os.*
import android.util.Log
import android.view.*
import android.view.animation.LinearInterpolator
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.persondetection.android.MainActivity
import com.persondetection.android.R
import com.persondetection.android.ml.ApproachDetector
import com.persondetection.android.ml.FrameAnalyzer
import com.persondetection.android.ml.ObjectDetector
import com.persondetection.android.model.AlertLevel
import kotlinx.coroutines.*
import java.util.concurrent.Executors

class DetectionService : LifecycleService() {
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    
    private var objectDetector: ObjectDetector? = null
    private val approachDetector = ApproachDetector()
    private val frameAnalyzer = FrameAnalyzer()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    
    private lateinit var vibrator: Vibrator
    private var distanceThreshold = 1.5f // Default for V2
    private var lastHapticTime = mutableMapOf<AlertLevel, Long>()

    // Single cached handler — creating a new Handler() on every frame causes
    // a memory leak that grows at ~30 objects/sec and eventually crashes the service.
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
    }

    override fun onCreate() {
        super.onCreate()
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        when (intent?.action) {
            ACTION_START -> startForegroundService()
            ACTION_STOP  -> stopSelf()
            // null intent = service was restarted by Android after being killed (START_STICKY).
            // Resume everything as if ACTION_START was sent.
            null         -> startForegroundService()
        }

        // START_STICKY: if Android kills the service due to memory pressure or battery
        // optimisation, it will be restarted automatically with a null intent.
        return START_STICKY
    }

    private fun startForegroundService() {
        val notification = createNotification("Scanning for people...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Show the knight-rider overlay immediately on the main thread —
        // before any model loading or camera binding so the user gets instant
        // visual feedback that the service is active.
        showScanningIndicator()

        serviceScope.launch {
            // Load the model on a background thread (can take 1-2s on first run).
            try {
                objectDetector = ObjectDetector(this@DetectionService)
            } catch (e: Exception) {
                Log.e(TAG, "Model load failed: ${e.message}", e)
                // Service stays alive with the overlay — camera won't run but
                // the notification and knight-rider still show.
                return@launch
            }

            withContext(Dispatchers.Main) {
                // Small delay so the activity fully releases the camera before the
                // service tries to bind.  Without this, unbindAll() can race with
                // the activity's onPause and leave CameraPreview dead on return.
                delay(400)
                startCamera()
            }
        }
    }

    private fun showScanningIndicator() {
        if (scanningView != null) return

        val density = resources.displayMetrics.density
        val heightPx = (8 * density).toInt() // 8dp → pixels

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = 0
        }

        try {
            scanningView = LayoutInflater.from(this).inflate(R.layout.layout_scanning_indicator, null)
            val line = scanningView!!.findViewById<View>(R.id.scanningLine)

            windowManager.addView(scanningView, params)

            // Pulse the red line between 40 % and 100 % opacity so it looks alive
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
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processFrame(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: androidx.camera.core.ImageProxy) {
        val detector = objectDetector ?: run {
            imageProxy.close()
            return
        }

        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            imageProxy.close()

            // Environment analysis (wall / ground hazard)
            frameAnalyzer.analyze(bitmap)

            val detections = detector.detectPeople(bitmap)
            val approachingIds = approachDetector.updateDetections(detections)

            val highestAlert = detections.map { getAlertLevel(it) }
                .maxByOrNull { it.ordinal } ?: AlertLevel.NONE

            // ── Build HUD message ─────────────────────────────────────────────
            // Priority: HIGH person/object collision > ground hazard > nothing
            val hudMessage: String? = when {
                highestAlert == AlertLevel.HIGH -> {
                    val topDetection = detections
                        .filter { getAlertLevel(it) == AlertLevel.HIGH }
                        .minByOrNull { it.distance }
                    val label = when (topDetection?.className) {
                        "person"                  -> "PERSON AHEAD"
                        "car", "truck", "bus"     -> "VEHICLE AHEAD"
                        "motorcycle", "bicycle"   -> "BIKE AHEAD"
                        "dog", "cat"              -> "ANIMAL AHEAD"
                        else                      -> "OBJECT AHEAD"
                    }
                    "⚠️ LOOK UP!  $label"
                }
                frameAnalyzer.isWallDetected         -> "🧱 WALL AHEAD — LOOK UP NOW"
                frameAnalyzer.isGroundHazardDetected -> "⚠️ WATCH YOUR STEP!"
                else -> null   // nothing to show — hide HUD
            }

            mainHandler.post {
                updateHud(hudMessage)
            }

            if (highestAlert != AlertLevel.NONE) {
                handleHaptics(highestAlert)
                val hasPersonAtHighAlert = detections.any {
                    it.className == "person" && getAlertLevel(it) == AlertLevel.HIGH
                }
                if (hasPersonAtHighAlert) playAlertSound()
                updateNotification("Alert: $highestAlert — ${detections.size} object(s) detected")
            }
        } catch (e: Exception) {
            // imageProxy is already closed above before processing begins —
            // do NOT close it again here or it throws a second exception.
            Log.e(TAG, "Frame processing error: ${e.message}", e)
        }
    }
    
    private fun playAlertSound() {
        try {
            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(applicationContext, notification)
            r.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun imageProxyToBitmap(imageProxy: androidx.camera.core.ImageProxy): android.graphics.Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val imageBytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    /**
     * Show or update the HUD banner.
     * @param message Non-null = show/update with this text. Null = hide the HUD.
     */
    private fun updateHud(message: String?) {
        if (message != null) {
            if (hudView == null) {
                // First appearance — inflate and add to window
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP
                    y = 100
                }
                try {
                    hudView = LayoutInflater.from(this).inflate(R.layout.layout_collision_warning, null)
                    windowManager.addView(hudView, params)
                } catch (e: Exception) {
                    Log.e(TAG, "HUD add error", e)
                    return
                }
            }
            // Update text and background colour based on message type
            val tv = hudView!!.findViewById<android.widget.TextView>(R.id.warningText)
            tv?.text = message
            val bgColor = when {
                message.contains("STEP") -> android.graphics.Color.parseColor("#CC994400")  // amber — ground hazard
                message.contains("WALL") -> android.graphics.Color.parseColor("#CC1565C0")  // blue  — wall ahead
                else                     -> android.graphics.Color.parseColor("#CCCC0000")  // red   — collision
            }
            hudView!!.setBackgroundColor(bgColor)
        } else if (hudView != null) {
            try { windowManager.removeView(hudView) } catch (e: Exception) {}
            hudView = null
        }
    }

    private fun getAlertLevel(detection: com.persondetection.android.model.Detection): AlertLevel {
        val distance = detection.distance
        if (detection.className != "person") {
            return if (distance < 0.8f) AlertLevel.HIGH else AlertLevel.NONE
        }
        // Matched to ViewModel thresholds: HIGH at 0.65×, MEDIUM at 0.85×
        return when {
            distance >= distanceThreshold         -> AlertLevel.NONE
            distance >= distanceThreshold * 0.85f -> AlertLevel.LOW
            distance >= distanceThreshold * 0.65f -> AlertLevel.MEDIUM
            else                                  -> AlertLevel.HIGH
        }
    }

    private fun handleHaptics(alertLevel: AlertLevel) {
        val currentTime = System.currentTimeMillis()
        val lastTime = lastHapticTime[alertLevel] ?: 0L
        val interval = when (alertLevel) {
            AlertLevel.LOW -> 600L
            AlertLevel.MEDIUM -> 300L
            AlertLevel.HIGH -> 100L
            else -> Long.MAX_VALUE
        }
        if (currentTime - lastTime >= interval) {
            triggerHaptic(alertLevel)
            lastHapticTime[alertLevel] = currentTime
        }
    }

    private fun triggerHaptic(alertLevel: AlertLevel) {
        val pattern = when (alertLevel) {
            AlertLevel.LOW -> longArrayOf(0, 50)
            AlertLevel.MEDIUM -> longArrayOf(0, 50, 100, 50)
            AlertLevel.HIGH -> longArrayOf(0, 100, 50, 100, 50, 100)
            else -> return
        }
        val amplitudes = when (alertLevel) {
            AlertLevel.LOW -> intArrayOf(0, 128)
            AlertLevel.MEDIUM -> intArrayOf(0, 128, 0, 128)
            AlertLevel.HIGH -> intArrayOf(0, 255, 0, 255, 0, 255)
            else -> return
        }
        val effect = VibrationEffect.createWaveform(pattern, amplitudes, -1)
        vibrator.vibrate(effect)
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Person Detection Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Detection Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        knightRiderAnimator?.cancel()
        updateHud(null)
        if (scanningView != null) {
            try { windowManager.removeView(scanningView) } catch (e: Exception) {}
            scanningView = null
        }
        serviceJob.cancel()
        objectDetector?.close()
        cameraExecutor.shutdown()
    }
}
