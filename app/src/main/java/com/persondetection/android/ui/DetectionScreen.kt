package com.persondetection.android.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.persondetection.android.ml.SensorMonitor
import com.persondetection.android.model.AlertLevel
import com.persondetection.android.model.Detection
import com.persondetection.android.viewmodel.AccuracyMode
import com.persondetection.android.viewmodel.DetectionViewModel
import java.util.Locale
import java.util.concurrent.Executors

@Composable
fun DetectionScreen(
    viewModel: DetectionViewModel,
    onStartBackground: () -> Unit,
    onStopBackground: () -> Unit,
    canDrawOverlays: Boolean,
    onGrantOverlay: () -> Unit,
    onShowHistory: () -> Unit = {},
    cameraRebindKey: Int = 0
) {
    val detections = viewModel.detections
    val distanceThreshold = viewModel.distanceThreshold
    val isInitializing = viewModel.isInitializing
    val initializationStatus = viewModel.initializationStatus
    val isObjectDetectionEnabled = viewModel.isObjectDetectionEnabled
    val batteryLevel = viewModel.batteryLevel
    val isCameraBlocked = viewModel.isCameraBlocked
    val accuracyMode = viewModel.accuracyMode
    val isWallDetected = viewModel.isWallDetected
    val isGroundHazard = viewModel.isGroundHazardDetected
    val phoneAngleHint = viewModel.phoneAngleHint
    val phoneAngleQuality = viewModel.phoneAngleQuality
    val isHardwareAccelerated = viewModel.isHardwareAccelerated
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        // key(cameraRebindKey) forces CameraPreview to be fully recreated each
        // time we return from background. This re-runs the factory lambda which
        // calls cameraProvider.unbindAll() + bindToLifecycle fresh, reclaiming
        // the camera from the DetectionService.
        key(cameraRebindKey) {
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                onFrameAnalyzed = { imageProxy ->
                    viewModel.processFrame(imageProxy)
                }
            )
        }

        // ── Bounding boxes ─────────────────────────────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            for (detection in detections) {
                val box = detection.boundingBox
                val color = viewModel.colorFor(detection)

                val left = box.left * size.width
                val top = box.top * size.height
                val w = (box.right - box.left) * size.width
                val h = (box.bottom - box.top) * size.height

                // Semi-transparent fill — raised to 0.25f so the box is clearly visible
                drawRoundRect(
                    color = color.copy(alpha = 0.25f),
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(12f, 12f)
                )
                // Rounded border — 6f instead of 3f for visibility
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(12f, 12f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f)
                )

                // Approaching indicator: thicker outer ring
                if (detection.isApproaching) {
                    drawRoundRect(
                        color = Color.Red.copy(alpha = 0.7f),
                        topLeft = Offset(left - 5f, top - 5f),
                        size = Size(w + 10f, h + 10f),
                        cornerRadius = CornerRadius(16f, 16f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f)
                    )
                }

                // Label with background pill
                drawContext.canvas.nativeCanvas.apply {
                    val label = "${detection.className.uppercase()} ${String.format(Locale.US, "%.1f", detection.distance)}m"
                    val textPaint = android.graphics.Paint().apply {
                        this.color = android.graphics.Color.WHITE
                        textSize = 36f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        isAntiAlias = true
                    }
                    // Fix: use color.toArgb() to get the real Android ARGB int from a Compose Color.
                    // Previously this used color.hashCode() which is an arbitrary Java object hash
                    // and produces random/wrong background colours.
                    val argb = color.toArgb()
                    val bgPaint = android.graphics.Paint().apply {
                        this.color = android.graphics.Color.argb(
                            200,
                            (argb shr 16) and 0xFF,
                            (argb shr 8) and 0xFF,
                            argb and 0xFF
                        )
                        isAntiAlias = true
                    }
                    val textWidth = textPaint.measureText(label)
                    val textHeight = 36f
                    val padH = 12f
                    val padV = 6f
                    val labelY = (top - textHeight - padV * 2).coerceAtLeast(0f)

                    // Pill background
                    drawRoundRect(
                        left, labelY,
                        left + textWidth + padH * 2,
                        labelY + textHeight + padV * 2,
                        14f, 14f, bgPaint
                    )
                    // Label text
                    drawText(label, left + padH, labelY + textHeight + padV - 2f, textPaint)
                }
            }
        }

        // ── Status bar ─────────────────────────────────────────
        if (!isInitializing) {
            SystemActiveIndicator(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp),
                batteryLevel = batteryLevel,
                isHardwareAccelerated = isHardwareAccelerated
            )
        }

        // ── Phone angle warning ────────────────────────────────
        if (phoneAngleQuality != SensorMonitor.AngleQuality.OK && phoneAngleHint.isNotEmpty()) {
            AngleWarningBanner(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp),
                hint = phoneAngleHint,
                isBad = phoneAngleQuality == SensorMonitor.AngleQuality.BAD
            )
        }

        // ── Ground hazard warning (bottom of screen) ──────────
        if (isGroundHazard && !isCameraBlocked) {
            GroundHazardBanner(modifier = Modifier.align(Alignment.BottomCenter))
        }

        // ── Control panel ──────────────────────────────────────
        ControlPanel(
            modifier = Modifier.align(Alignment.CenterEnd),
            distanceThreshold = distanceThreshold,
            onThresholdChange = { viewModel.distanceThreshold = it },
            onStartBackground = onStartBackground,
            onStopBackground = onStopBackground,
            canDrawOverlays = canDrawOverlays,
            onGrantOverlay = onGrantOverlay,
            isObjectDetectionEnabled = isObjectDetectionEnabled,
            onObjectDetectionToggle = { viewModel.isObjectDetectionEnabled = it },
            accuracyMode = accuracyMode,
            onAccuracyChange = { viewModel.setAccuracyMode(it, context) },
            onShowHistory = onShowHistory
        )

        // ── Wall banner — calm, non-blocking ─────────────────────────────────
        if (isWallDetected && !isCameraBlocked) {
            WallWarningBanner(modifier = Modifier.align(Alignment.TopCenter).padding(top = 110.dp))
        }

        // ── Full-screen overlays (highest priority on top) ────────────────────
        if (isCameraBlocked) {
            CameraBlockedOverlay()
        } else if (viewModel.frameAlert == AlertLevel.HIGH) {
            // Driven by the engine's linger-debounced alert so the overlay doesn't strobe.
            LookUpOverlay(className = viewModel.lookUpLabel ?: "person")
        }

        if (isInitializing) {
            InitializingOverlay(initializationStatus)
        }
    }
}

// ── New: Angle warning banner ───────────────────────────────────

@Composable
fun AngleWarningBanner(modifier: Modifier = Modifier, hint: String, isBad: Boolean) {
    val bgColor = if (isBad) Color(0xFFD32F2F) else Color(0xFFF57C00)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor.copy(alpha = 0.9f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("📐", fontSize = 18.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = hint,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}

// ── New: Ground hazard banner ───────────────────────────────────

@Composable
fun GroundHazardBanner(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "groundPulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.7f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse),
        label = "groundAlpha"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color(0xFFFF6F00).copy(alpha = 0.85f))
                )
            )
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("WATCH YOUR STEP", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text("Possible pothole or drop ahead", color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
        }
    }
}

// ── New: Wall warning banner (calm, non-blocking) ───────────────

@Composable
fun WallWarningBanner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1565C0).copy(alpha = 0.85f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🧱", fontSize = 16.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Obstacle ahead — watch your path",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp
        )
    }
}

// ── Existing composables (unchanged except where noted) ─────────

@Composable
fun SystemActiveIndicator(
    modifier: Modifier = Modifier,
    batteryLevel: Int,
    isHardwareAccelerated: Boolean = false
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Pulsing dot — yellow if battery low
        Box(
            modifier = Modifier
                .size(12.dp)
                .graphicsLayer { this.alpha = alpha }
                .clip(CircleShape)
                .background(if (batteryLevel < 20) Color.Yellow else Color.Green)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "AI ACTIVE",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.width(8.dp))
        // Acceleration chip — cyan for NPU, grey for CPU
        val chipColor = if (isHardwareAccelerated) Color(0xFF00BCD4) else Color.White.copy(alpha = 0.4f)
        Text(
            text = if (isHardwareAccelerated) "NPU" else "CPU",
            color = chipColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(chipColor.copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$batteryLevel%",
            color = if (batteryLevel < 20) Color.Yellow else Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun ControlPanel(
    modifier: Modifier,
    distanceThreshold: Float,
    onThresholdChange: (Float) -> Unit,
    onStartBackground: () -> Unit,
    onStopBackground: () -> Unit,
    canDrawOverlays: Boolean,
    onGrantOverlay: () -> Unit,
    isObjectDetectionEnabled: Boolean,
    onObjectDetectionToggle: (Boolean) -> Unit,
    accuracyMode: AccuracyMode,
    onAccuracyChange: (AccuracyMode) -> Unit,
    onShowHistory: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .width(120.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.8f), Color.Black.copy(alpha = 0.5f))
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
            .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = { if (canDrawOverlays) onStartBackground() else onGrantOverlay() },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (canDrawOverlays) Color(0xFF2E7D32) else Color(0xFFEF6C00)
            )
        ) {
            Text(if (canDrawOverlays) "START\nBG" else "GRANT\nHUD", fontSize = 10.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onStopBackground,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
            border = androidx.compose.foundation.BorderStroke(2.dp, Color.Red.copy(alpha = 0.5f))
        ) {
            Text("STOP\nBG", fontSize = 10.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("ALERT AT", color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        val presets = listOf(0.5f, 1.0f, 2.0f, 3.5f)
        val labels = listOf("0.5m", "1m", "2m", "3.5m")

        Column(Modifier.selectableGroup()) {
            presets.forEachIndexed { index, preset ->
                val selected = distanceThreshold == preset
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .padding(vertical = 2.dp)
                        .selectable(
                            selected = selected,
                            onClick = { onThresholdChange(preset) },
                            role = Role.RadioButton
                        ),
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) Color.Green.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                    border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, Color.Green) else null
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(labels[index], color = if (selected) Color.Green else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("ACCURACY", color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        // Two-row segmented selector: YOLO11 (top) / YOLO26 (bottom)
        // YOLO11 = blue family | YOLO26 = green family
        val yolo11Color = Color(0xFF4FC3F7)   // light blue
        val yolo26Color = Color(0xFF69F0AE)   // green
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.07f))
                .padding(vertical = 3.dp, horizontal = 3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            listOf(
                "YOLO11" to AccuracyMode.entries.filter { it.family == "YOLO11" },
                "YOLO26" to AccuracyMode.entries.filter { it.family == "YOLO26" }
            ).forEach { (familyLabel, modes) ->
                val familyColor = if (familyLabel == "YOLO11") yolo11Color else yolo26Color
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    // Family badge
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .align(Alignment.CenterVertically),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = familyLabel,
                            color = familyColor.copy(alpha = 0.6f),
                            fontSize = 6.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    // Mode chips
                    modes.forEach { mode ->
                        val selected = accuracyMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selected) familyColor.copy(alpha = 0.22f) else Color.Transparent)
                                .border(
                                    width = if (selected) 1.dp else 0.dp,
                                    color = if (selected) familyColor else Color.Transparent,
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .selectable(selected = selected, onClick = { onAccuracyChange(mode) })
                                .padding(vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = mode.label,  // S, M, or H
                                color = if (selected) familyColor else Color.White.copy(alpha = 0.40f),
                                fontSize = 9.sp,
                                fontWeight = if (selected) FontWeight.Black else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        // Description line: family · size · NMS status
        val modeDesc = buildString {
            append(accuracyMode.family)
            append(" · ")
            append(accuracyMode.label)   // S, M, or H
            append(" · ${accuracyMode.inputPx}px")
            if (accuracyMode.skipNms) append(" · NMS-free")
        }
        Text(
            text = modeDesc,
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 8.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text("OBJECTS", color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Switch(
            checked = isObjectDetectionEnabled,
            onCheckedChange = onObjectDetectionToggle,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Green, checkedTrackColor = Color.Green.copy(alpha = 0.3f))
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ── History button ──────────────────────────────────────
        Button(
            onClick = onShowHistory,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1A237E)
            )
        ) {
            Text("📊\nHISTORY", fontSize = 9.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, lineHeight = 13.sp)
        }
    }
}

@Composable
fun LookUpOverlay(className: String) {
    // Human-readable subtitle based on what the model detected
    val subtitle = when (className) {
        "person"                  -> "PERSON AHEAD"
        "car", "truck", "bus"     -> "VEHICLE AHEAD"
        "motorcycle", "bicycle"   -> "BIKE AHEAD"
        "dog", "cat", "horse"     -> "ANIMAL AHEAD"
        else                      -> "OBJECT AHEAD"
    }
    val infiniteTransition = rememberInfiniteTransition(label = "alert")
    val color by infiniteTransition.animateColor(
        initialValue = Color.Red.copy(alpha = 0.2f),
        targetValue = Color.Red.copy(alpha = 0.6f),
        animationSpec = infiniteRepeatable(animation = tween(500), repeatMode = RepeatMode.Reverse),
        label = "color"
    )
    Box(modifier = Modifier.fillMaxSize().background(color), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(100.dp), tint = Color.White)
            Spacer(modifier = Modifier.height(16.dp))
            Text("LOOK UP!", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Text(subtitle, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun InitializingOverlay(status: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(64.dp), color = Color.Green, strokeWidth = 6.dp)
            Spacer(modifier = Modifier.height(32.dp))
            Text("ENGINE WARMUP", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(status, color = if (status.contains("CRITICAL")) Color.Red else Color.Green, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)

            if (!status.contains("CRITICAL")) {
                Spacer(modifier = Modifier.height(40.dp))
                Surface(color = Color.White.copy(alpha = 0.1f), shape = RoundedCornerShape(12.dp)) {
                    Text(
                        text = "NOTE: Object detection is in BETA. System is optimizing NPU paths.",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
fun CameraBlockedOverlay() {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(80.dp), tint = Color.Yellow)
            Spacer(modifier = Modifier.height(16.dp))
            Text("CAMERA COVERED", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
            Text("Hold phone better for visibility", color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun CameraPreview(modifier: Modifier = Modifier, onFrameAnalyzed: (androidx.camera.core.ImageProxy) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    AndroidView(modifier = modifier, factory = { ctx ->
        val previewView = PreviewView(ctx)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { imageProxy -> onFrameAnalyzed(imageProxy) } }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
            } catch (e: Exception) { e.printStackTrace() }
        }, ContextCompat.getMainExecutor(ctx))
        previewView
    })
}
