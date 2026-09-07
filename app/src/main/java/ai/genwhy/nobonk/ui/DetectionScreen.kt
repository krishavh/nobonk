package ai.genwhy.nobonk.ui

import ai.genwhy.nobonk.ml.SensorMonitor
import ai.genwhy.nobonk.model.AlertLevel
import ai.genwhy.nobonk.model.Detection
import ai.genwhy.nobonk.ui.components.*
import ai.genwhy.nobonk.ui.theme.NB
import ai.genwhy.nobonk.viewmodel.AccuracyMode
import ai.genwhy.nobonk.viewmodel.DetectionViewModel
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The live detection screen. Layering (bottom → top): camera preview, corner-bracket
 * detection overlay, top status bar, notice banners, the bottom control dock, then the
 * full-screen states (camera covered / LOOK UP / warming up).
 *
 * Design rules: one colour language everywhere (NB.alert), glass panels over the camera,
 * nothing important inside the top 88 dp (status bar) or under the dock.
 */
@Composable
fun DetectionScreen(
    viewModel: DetectionViewModel,
    onStartBackground: () -> Unit,
    onStopBackground: () -> Unit,
    canDrawOverlays: Boolean,
    onGrantOverlay: () -> Unit,
    onShowHistory: () -> Unit = {},
    onShowAbout: () -> Unit = {},
    cameraRebindKey: Int = 0,
    /** Play in-app update prompt (NONE = nothing to show). Only rendered while not scanning. */
    updatePrompt: ai.genwhy.nobonk.update.UpdatePolicy.Prompt = ai.genwhy.nobonk.update.UpdatePolicy.Prompt.NONE,
    onUpdateNow: () -> Unit = {},
    onUpdateLater: () -> Unit = {}
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
    val isLowLight = viewModel.isLowLight
    val executionProvider = viewModel.executionProvider
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(viewModel, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            viewModel.setForegroundActive(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        viewModel.setForegroundActive(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.removeObserver(observer); viewModel.setForegroundActive(false) }
    }

    Box(modifier = Modifier.fillMaxSize().background(NB.Night)) {
        // The camera is bound only while scanning; Stop releases it (CameraPreview unbinds on dispose).
        if (viewModel.scanningEnabled && batteryLevel >= ai.genwhy.nobonk.ml.BatteryLevel.MIN_SCAN_PERCENT) {
            key(cameraRebindKey) {
                CameraPreview(modifier = Modifier.fillMaxSize(), onFrameAnalyzed = { viewModel.processFrame(it) }, onCameraBound = { viewModel.onCameraBound(it) }, onError = { viewModel.reportCameraError(it) })
            }
        }

        DetectionOverlay(detections = detections, frameAlert = viewModel.frameAlert)

        if (!isInitializing || !viewModel.scanningEnabled) {
            TopStatusBar(
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 10.dp),
                batteryLevel = batteryLevel,
                executionProvider = executionProvider,
                mode = accuracyMode,
                live = viewModel.scanningEnabled && batteryLevel >= ai.genwhy.nobonk.ml.BatteryLevel.MIN_SCAN_PERCENT && !isCameraBlocked && viewModel.cameraError == null,
                stats = if (viewModel.fps > 0f) String.format(Locale.US, "%.0f fps · %d ms", viewModel.fps, viewModel.inferMs) else null
            )
        }

        // Notices stack under the status bar — one slot, most important first.
        Column(
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 58.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            viewModel.cameraError?.let { NoticeBanner("!", "Scanning unavailable", it, color = NB.Watch) }
            when {
                isCameraBlocked -> Unit
                phoneAngleQuality != SensorMonitor.AngleQuality.OK && phoneAngleHint.isNotEmpty() ->
                    NoticeBanner("📐", "Camera angle", phoneAngleHint,
                        color = if (phoneAngleQuality == SensorMonitor.AngleQuality.BAD) NB.Danger else NB.Watch,
                        description = "Camera angle warning. $phoneAngleHint")
                isLowLight -> NoticeBanner("🔅", if (viewModel.isNightBoost) "Low light · night boost on" else "Low light", "Detection is less reliable in the dark", color = NB.Watch)
            }
            if (!viewModel.scanningEnabled && updatePrompt != ai.genwhy.nobonk.update.UpdatePolicy.Prompt.NONE)
                ai.genwhy.nobonk.ui.components.UpdateCard(restart = updatePrompt == ai.genwhy.nobonk.update.UpdatePolicy.Prompt.OFFER_RESTART, onPrimary = onUpdateNow, onLater = onUpdateLater)
            if (isWallDetected && !isCameraBlocked)
                NoticeBanner("🧱", "Possible obstacle", "Surface warning · wall-like surface ahead, object not identified", color = NB.Watch, description = "Possible obstacle. Surface warning: wall-like surface ahead, object not identified.")
        }

        if (isGroundHazard && !isCameraBlocked) {
            GroundHazardBanner(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 250.dp))
        }

        ControlDock(
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
            detections = detections,
            distanceThreshold = distanceThreshold,
            onThresholdChange = { viewModel.setThreshold(it) },
            onStartBackground = onStartBackground,
            onStopBackground = { onStopBackground(); viewModel.stopScanning() },
            scanningEnabled = viewModel.scanningEnabled,
            onStartScanning = { viewModel.startScanning() },
            canDrawOverlays = canDrawOverlays,
            onGrantOverlay = onGrantOverlay,
            isObjectDetectionEnabled = isObjectDetectionEnabled,
            onObjectDetectionToggle = { viewModel.setDetectEverything(it) },
            soundEnabled = viewModel.soundEnabled,
            onSoundToggle = { viewModel.toggleSound(it) },
            hapticsEnabled = viewModel.hapticsEnabled,
            onHapticsToggle = { viewModel.toggleHaptics(it) },
            voiceEnabled = viewModel.voiceEnabled,
            onVoiceToggle = { viewModel.toggleVoice(it) },
            onTestAlert = { viewModel.testAlert() },
            accuracyMode = accuracyMode,
            onAccuracyChange = { viewModel.setAccuracyMode(it, context) },
            onShowHistory = onShowHistory,
            onShowAbout = onShowAbout,
            heuristicObstacle = isWallDetected || isGroundHazard,
            pausedReason = when {
                !viewModel.scanningEnabled -> "Stopped — tap Start scanning"
                isInitializing -> "Starting…"
                isCameraBlocked -> "Camera blocked"
                phoneAngleQuality == SensorMonitor.AngleQuality.BAD -> "Point phone forward"
                batteryLevel < 10 -> "Paused — battery too low"
                else -> null
            }
        )

        if (isCameraBlocked) {
            CameraBlockedOverlay()
        } else if (viewModel.frameAlert == AlertLevel.HIGH && phoneAngleQuality != SensorMonitor.AngleQuality.BAD) {
            LookUpOverlay(className = viewModel.lookUpLabel ?: "person", bearingPan = viewModel.bearingPan)
        }
        // Warming overlay only while a scan session actually wants the model: after a foreground Stop
        // the stopped dock must be visible immediately while the cancelled load unwinds.
        if (isInitializing && viewModel.scanningEnabled) {
            InitializingOverlay(initializationStatus)
            TextButton(onClick = { onStopBackground(); viewModel.stopScanning() }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp)) { Text("Stop", color = NB.Ink) }
        }
    }
}

/* ───────────────────────── overlay ───────────────────────── */

@Composable
private fun DetectionOverlay(detections: List<Detection>, frameAlert: AlertLevel) {
    val t = rememberInfiniteTransition(label = "bracket")
    val breathe by t.animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val density = androidx.compose.ui.platform.LocalDensity.current
    val textPx = with(density) { 12.sp.toPx() }          // honours the user's font scale
    val padHPx = with(density) { 7.dp.toPx() }; val padVPx = with(density) { 4.dp.toPx() }
    val strokePx = with(density) { 2.dp.toPx() }; val strokeHiPx = with(density) { 3.dp.toPx() }
    val labelPaint = remember(textPx) {
        android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = textPx; typeface = android.graphics.Typeface.DEFAULT_BOLD; isAntiAlias = true }
    }
    val bgPaint = remember { android.graphics.Paint().apply { isAntiAlias = true } }

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Danger vignette when the frame-level alert is HIGH — the eye reads the edges first.
        if (frameAlert == AlertLevel.HIGH) {
            drawRect(Brush.radialGradient(listOf(Color.Transparent, NB.Danger.copy(alpha = 0.10f + 0.18f * breathe)), center = center, radius = size.maxDimension * 0.75f))
        }
        for (d in detections) {
            val box = d.boundingBox
            val color = NB.alert(d.alertLevel)
            val l = box.left * size.width; val tp = box.top * size.height
            val w = (box.right - box.left) * size.width; val h = (box.bottom - box.top) * size.height
            if (w <= 2f || h <= 2f) continue
            val stroke = if (d.alertLevel == AlertLevel.HIGH) strokeHiPx else strokePx
            val arm = (minOf(w, h) * 0.22f).coerceIn(6f * strokePx, 24f * strokePx)
            // soft fill
            drawRoundRect(color.copy(alpha = if (d.alertLevel == AlertLevel.NONE) 0.06f else 0.14f), Offset(l, tp), Size(w, h), CornerRadius(14f, 14f))
            // corner brackets
            val s = Stroke(width = stroke, cap = StrokeCap.Round)
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(color, Offset(x, y), Offset(x + dx * arm, y), stroke, StrokeCap.Round)
                drawLine(color, Offset(x, y), Offset(x, y + dy * arm), stroke, StrokeCap.Round)
            }
            corner(l, tp, 1f, 1f); corner(l + w, tp, -1f, 1f); corner(l, tp + h, 1f, -1f); corner(l + w, tp + h, -1f, -1f)
            // approaching: breathing outer ring
            if (d.isApproaching) {
                val g = 6f + 6f * breathe
                drawRoundRect(NB.Danger.copy(alpha = 0.55f + 0.35f * breathe), Offset(l - g, tp - g), Size(w + 2 * g, h + 2 * g), CornerRadius(18f, 18f), style = s)
            }
            // label pill
            drawContext.canvas.nativeCanvas.apply {
                val dist = if (d.hasDistanceEstimate) String.format(Locale.US, " %.1f m", d.distance) else ""
                val label = d.className.uppercase() + dist + (if (d.isApproaching) "  ▲" else "")
                val fm = labelPaint.fontMetrics
                val tw = labelPaint.measureText(label); val th = fm.descent - fm.ascent; val padH = padHPx; val padV = padVPx
                val pillW = tw + padH * 2; val pillH = th + padV * 2
                // Keep the pill on screen: clamp x to the canvas, prefer above the box, else just inside it.
                val x = l.coerceIn(0f, (size.width - pillW).coerceAtLeast(0f))
                val yAbove = tp - pillH - padV
                val y = (if (yAbove >= padV) yAbove else tp + padV).coerceIn(0f, (size.height - pillH).coerceAtLeast(0f))
                val argb = color.toArgb()
                bgPaint.color = android.graphics.Color.argb(215, (argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
                drawRoundRect(x, y, x + pillW, y + pillH, pillH / 2f, pillH / 2f, bgPaint)
                labelPaint.color = if (d.alertLevel == AlertLevel.MEDIUM) android.graphics.Color.rgb(20, 16, 4) else android.graphics.Color.WHITE
                drawText(label, x + padH, y + padV - fm.ascent, labelPaint)
            }
        }
    }
}

/* ───────────────────────── top status ───────────────────────── */

@Composable
private fun TopStatusBar(modifier: Modifier, batteryLevel: Int, executionProvider: String, mode: AccuracyMode, live: Boolean, stats: String? = null) {
  Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Row(
        modifier = Modifier
            .clip(NB.PillShape)
            .background(NB.Glass)
            .border(1.dp, NB.GlassLine, NB.PillShape)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { contentDescription = "NoBonk ${if (live) "active" else "paused"}. ${if (executionProvider == "NNAPI") "NNAPI, device-selected processing" else "CPU"}. Battery $batteryLevel percent." },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PulseDot(if (live) NB.Safe else NB.Watch)
        Text("NOBONK", color = NB.Ink, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Pill(if (executionProvider == "NNAPI") "NNAPI" else "CPU", color = if (executionProvider == "NNAPI") NB.Accent else NB.Sub)
        Pill(mode.label.uppercase(), color = NB.Accent2)
        Text("$batteryLevel%", color = if (batteryLevel < 20) NB.Watch else NB.Sub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
    if (stats != null) {
        Spacer(Modifier.height(4.dp))
        Text(stats, color = NB.Dim, fontSize = 10.sp, letterSpacing = 0.5.sp)
    }
  }
}

/* ───────────────────────── bottom dock ───────────────────────── */

@Composable
private fun ControlDock(
    modifier: Modifier,
    detections: List<Detection>,
    distanceThreshold: Float,
    onThresholdChange: (Float) -> Unit,
    onStartBackground: () -> Unit,
    onStopBackground: () -> Unit,
    scanningEnabled: Boolean = true,
    onStartScanning: () -> Unit = {},
    canDrawOverlays: Boolean,
    onGrantOverlay: () -> Unit,
    isObjectDetectionEnabled: Boolean,
    onObjectDetectionToggle: (Boolean) -> Unit,
    accuracyMode: AccuracyMode,
    onAccuracyChange: (AccuracyMode) -> Unit,
    soundEnabled: Boolean,
    onSoundToggle: (Boolean) -> Unit,
    hapticsEnabled: Boolean,
    onHapticsToggle: (Boolean) -> Unit,
    voiceEnabled: Boolean,
    onVoiceToggle: (Boolean) -> Unit,
    onTestAlert: () -> Unit = {},
    onShowHistory: () -> Unit,
    onShowAbout: () -> Unit = {},
    /** When non-null the pipeline is not watching (blocked / starting / off-angle); shown instead of a detection summary. */
    pausedReason: String? = null,
    /** Camera heuristics (wall / ground) flag something even though the model recognised no object. */
    heuristicObstacle: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val nearest = detections.filter { it.hasDistanceEstimate }.minByOrNull { it.distance }
    // Never imply "safe": no detections means exactly that — nothing the model recognised.
    val nearestColor = nearest?.let { NB.alert(it.alertLevel) } ?: NB.Sub

    GlassCard(modifier = modifier.fillMaxWidth(), accent = nearest?.let { NB.alert(it.alertLevel).takeIf { _ -> it.alertLevel != AlertLevel.NONE } }) {
        // Row 1 — what's ahead + proximity meter
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel(if (pausedReason != null) "Status" else if (nearest == null) "Watching" else "Nearest")
                Text(
                    if (pausedReason != null) pausedReason else if (nearest == null) (if (heuristicObstacle) "Possible obstacle ahead" else "No objects detected") else "${nearest.className.replaceFirstChar { it.uppercase() }} · ${String.format(Locale.US, "%.1f", nearest.distance)} m",
                    color = if (pausedReason != null || (nearest == null && heuristicObstacle)) NB.Watch else if (nearest == null) NB.Ink else nearestColor, fontSize = 20.sp, fontWeight = FontWeight.Bold
                )
            }
            ProximityMeter(distance = nearest?.distance, threshold = distanceThreshold, color = nearestColor,
                emptyLabel = if (heuristicObstacle) "surface warning · object not identified" else "no object identified")
        }
        Spacer(Modifier.height(12.dp))
        // Row 2 — alert distance
        SectionLabel("Alert sensitivity · approximate metres")
        Spacer(Modifier.height(6.dp))
        val presets = listOf(0.5f to "0.5 m", 1.0f to "1 m", 2.0f to "2 m", 3.5f to "3.5 m")
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.forEach { (v, label) -> SegChip(label, distanceThreshold == v, NB.Accent, Modifier.weight(1f)) { onThresholdChange(v) } }
        }
        if (expanded) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) {
                    SectionLabel("Model")
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AccuracyMode.entries.forEach { m -> SegChip(m.label, accuracyMode == m, NB.Accent2, Modifier.weight(1f)) { onAccuracyChange(m) } }
                    }
                }
                Column(Modifier.weight(1f)) {
                    SectionLabel("Detect")
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SegChip("People", !isObjectDetectionEnabled, NB.Safe, Modifier.weight(1f)) { onObjectDetectionToggle(false) }
                        SegChip("Everything", isObjectDetectionEnabled, NB.Safe, Modifier.weight(1f)) { onObjectDetectionToggle(true) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            SectionLabel("Cues")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SegChip(if (soundEnabled) "🔊 Sound" else "🔇 Sound", soundEnabled, NB.Watch, Modifier.weight(1f)) { onSoundToggle(!soundEnabled) }
                SegChip("📳 Haptics", hapticsEnabled, NB.Watch, Modifier.weight(1f)) { onHapticsToggle(!hapticsEnabled) }
                SegChip("🗣 Voice", voiceEnabled, NB.Watch, Modifier.weight(1f)) { onVoiceToggle(!voiceEnabled) }
                SegChip("▶ Test", false, NB.Danger, Modifier.weight(0.8f)) { onTestAlert() }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Sound and voice are panned toward the hazard — with earbuds, left means left.",
                color = NB.Dim, fontSize = 10.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "About NoBonk · safety notice · privacy · licenses",
                color = NB.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onShowAbout() }.padding(vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        // Row 3 — actions
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (scanningEnabled) {
                Button(
                    onClick = { if (canDrawOverlays) onStartBackground() else onGrantOverlay() },
                    modifier = Modifier.weight(1f).height(48.dp), shape = NB.ChipShape,
                    colors = ButtonDefaults.buttonColors(containerColor = if (canDrawOverlays) NB.Safe else NB.Watch, contentColor = Color(0xFF04140D))
                ) { Text(if (canDrawOverlays) "Run in background" else "Allow overlay", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1) }
                OutlinedButton(onClick = onStopBackground, modifier = Modifier.height(48.dp), shape = NB.ChipShape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NB.Danger),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NB.Danger.copy(alpha = 0.6f))) { Text("Stop", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            } else {
                // Stopped: nothing scans until the user explicitly starts again.
                Button(onClick = onStartScanning, modifier = Modifier.weight(1f).height(48.dp), shape = NB.ChipShape,
                    colors = ButtonDefaults.buttonColors(containerColor = NB.Safe, contentColor = Color(0xFF04140D))
                ) { Text("Start scanning", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1) }
            }
            IconButton(onClick = onShowHistory, modifier = Modifier.size(48.dp).clip(NB.ChipShape).background(Color.White.copy(alpha = 0.06f))) {
                Icon(Icons.Default.List, contentDescription = "History", tint = NB.Sub)
            }
            Box(
                modifier = Modifier.size(48.dp).clip(NB.ChipShape).background(Color.White.copy(alpha = 0.06f)).clickable { expanded = !expanded }
                    .semantics { contentDescription = if (expanded) "Hide settings" else "Show settings" },
                contentAlignment = Alignment.Center
            ) { Text(if (expanded) "▾" else "⚙", color = NB.Sub, fontSize = 18.sp) }
        }
        // Maker credit — sits beneath the History / settings controls, outside their tap targets.
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Made by Krishav" },
            horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(5.dp).clip(NB.PillShape).background(NB.Accent))
            Spacer(Modifier.width(7.dp))
            Text("BY", color = NB.Sub, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.2.sp)
            Spacer(Modifier.width(5.dp))
            Text("KRISHAV", color = NB.Ink, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.6.sp)
        }
    }
}

/** Horizontal meter: how close the nearest object is relative to the alert distance. */
@Composable
private fun ProximityMeter(distance: Float?, threshold: Float, color: Color, emptyLabel: String = "no object identified") {
    val frac = if (distance == null) 0f else (1f - (distance / (threshold * 2f))).coerceIn(0.04f, 1f)
    val anim by animateFloatAsState(frac, animationSpec = tween(220), label = "prox")
    Column(horizontalAlignment = Alignment.End) {
        Canvas(Modifier.width(110.dp).height(14.dp)) {
            val r = CornerRadius(7f, 7f)
            drawRoundRect(Color.White.copy(alpha = 0.10f), size = size, cornerRadius = r)
            drawRoundRect(color, size = Size(size.width * anim, size.height), cornerRadius = r)
            // threshold tick at the halfway mark (distance == threshold)
            drawLine(Color.White.copy(alpha = 0.6f), Offset(size.width * 0.5f, 0f), Offset(size.width * 0.5f, size.height), 2f)
        }
        Spacer(Modifier.height(4.dp))
        Text(if (distance == null) emptyLabel else "alert at ${String.format(Locale.US, "%.1f", threshold)} m", color = NB.Dim, fontSize = 10.sp)
    }
}

/* ───────────────────────── banners & overlays ───────────────────────── */

@Composable
fun GroundHazardBanner(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "gh")
    val a by t.animateFloat(0.75f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "a")
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .clip(NB.CardShape).background(NB.Watch.copy(alpha = a)).padding(vertical = 14.dp)
            .semantics { contentDescription = "Watch your step. Possible pothole or drop ahead." },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("WATCH YOUR STEP", color = Color(0xFF1A1200), fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Text("Possible pothole or drop ahead", color = Color(0xFF1A1200).copy(alpha = 0.85f), fontSize = 14.sp)
    }
}

@Composable
fun LookUpOverlay(className: String, bearingPan: Float? = null) {
    val side = ai.genwhy.nobonk.ml.AlertCue.sideFor(bearingPan)
    val what = when (className) {
        "person" -> "PERSON"
        "car", "truck", "bus" -> "VEHICLE"
        "motorcycle", "bicycle" -> "BIKE"
        "dog", "cat", "horse" -> "ANIMAL"
        else -> "OBJECT"
    }
    val where = when (side) {
        ai.genwhy.nobonk.ml.AlertCue.Side.LEFT -> "ON YOUR LEFT"
        ai.genwhy.nobonk.ml.AlertCue.Side.RIGHT -> "ON YOUR RIGHT"
        ai.genwhy.nobonk.ml.AlertCue.Side.AHEAD -> "AHEAD"
    }
    val subtitle = "$what $where"
    val arrow = when (side) {
        ai.genwhy.nobonk.ml.AlertCue.Side.LEFT -> "◀"
        ai.genwhy.nobonk.ml.AlertCue.Side.RIGHT -> "▶"
        ai.genwhy.nobonk.ml.AlertCue.Side.AHEAD -> null
    }
    val t = rememberInfiniteTransition(label = "alert")
    val bg by t.animateColor(NB.Danger.copy(alpha = 0.55f), NB.Danger.copy(alpha = 0.85f), infiniteRepeatable(tween(260), RepeatMode.Reverse), label = "c")
    val scale by t.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(260), RepeatMode.Reverse), label = "s")
    Box(
        modifier = Modifier.fillMaxSize().background(bg).semantics { contentDescription = "Look up now. $subtitle. Collision warning." },
        contentAlignment = Alignment.Center
    ) {
        // Side glow: a bright band on the hazard's edge so peripheral vision gets the direction too.
        if (arrow != null) {
            val left = side == ai.genwhy.nobonk.ml.AlertCue.Side.LEFT
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(0.28f).align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
                    .background(Brush.horizontalGradient(
                        if (left) listOf(Color.White.copy(alpha = 0.55f), Color.Transparent)
                        else listOf(Color.Transparent, Color.White.copy(alpha = 0.55f))
                    ))
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(96.dp), tint = Color.White)
            Spacer(Modifier.height(8.dp))
            Text("LOOK UP", color = Color.White, fontSize = (64 * scale).sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp, textAlign = TextAlign.Center)
            if (arrow != null) {
                Text(arrow, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            }
            Text(subtitle, color = Color.White.copy(alpha = 0.95f), fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun InitializingOverlay(status: String) {
    val critical = status.contains("CRITICAL")
    Box(Modifier.fillMaxSize().background(NB.Night), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Wordmark()
            Spacer(Modifier.height(28.dp))
            Text(if (critical) "SOMETHING WENT WRONG" else "GETTING READY", color = if (critical) NB.Danger else NB.Sub, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.4.sp)
            Spacer(Modifier.height(10.dp))
            Text(status, color = if (critical) NB.Danger else NB.Ink, fontSize = 14.sp, textAlign = TextAlign.Center)
            if (!critical) { Spacer(Modifier.height(18.dp)); LinearProgressIndicator(modifier = Modifier.width(180.dp).clip(NB.PillShape), color = NB.Accent, trackColor = NB.Line) }
        }
    }
}

@Composable
fun CameraBlockedOverlay() {
    Box(Modifier.fillMaxSize().background(NB.Night.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("🖐", fontSize = 56.sp)
            Spacer(Modifier.height(10.dp))
            Text("CAMERA COVERED", color = NB.Ink, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            Text("Point the back camera at the path ahead", color = NB.Sub, fontSize = 15.sp, textAlign = TextAlign.Center)
        }
    }
}

/** NoBonk wordmark: the approved NoBonk icon (R.drawable.nobonk_brand, same image as the launcher/store icon) plus the name. Formerly a drawn eye-like mark; kept as one composable so it scales anywhere. */
@Composable
fun Wordmark(size: Int = 64) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(ai.genwhy.nobonk.R.drawable.nobonk_brand),
            contentDescription = null,
            modifier = Modifier.size(size.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape((size * 0.22f).dp))
        )
        Column {
            Text("NoBonk", color = NB.Ink, fontSize = (size * 0.5f).sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
            Text("look up, not down", color = NB.Sub, fontSize = (size * 0.19f).sp, letterSpacing = 1.sp)
        }
    }
}

/* ───────────────────────── camera ───────────────────────── */

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onFrameAnalyzed: (androidx.camera.core.ImageProxy) -> Unit,
    onCameraBound: (androidx.camera.core.CameraInfo) -> Unit = {},
    onError: (String) -> Unit = {}
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val providerRef = remember { java.util.concurrent.atomic.AtomicReference<ProcessCameraProvider?>(null) }
    val ownedUseCases = remember { java.util.concurrent.atomic.AtomicReference<List<androidx.camera.core.UseCase>>(emptyList()) }
    val disposed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            // Leaving the preview (Stop, screen change): no late callback may bind, and we release
            // only the use cases THIS preview owns (a newly started background service keeps its own).
            disposed.set(true)
            try { val p = providerRef.get(); val u = ownedUseCases.get(); if (p != null && u.isNotEmpty()) p.unbind(*u.toTypedArray()) } catch (_: Exception) {}
            cameraExecutor.shutdown()
        }
    }

    AndroidView(modifier = modifier, factory = { ctx ->
        val previewView = PreviewView(ctx)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
        // Bind after layout so the PreviewView can hand us its ViewPort: Preview and
        // ImageAnalysis then share one field of view (same crop), which is what makes the
        // normalized detection boxes line up with the FILL_CENTER preview on tall screens.
        cameraProviderFuture.addListener({ if (disposed.get()) return@addListener; previewView.post {
            if (disposed.get()) return@post   // disposed between provider resolution and layout
            val cameraProvider = cameraProviderFuture.get()
            providerRef.set(cameraProvider)
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { imageProxy -> onFrameAnalyzed(imageProxy) } }
            try {
                cameraProvider.unbindAll()
                val viewPort = previewView.viewPort ?: androidx.camera.core.ViewPort.Builder(
                    android.util.Rational(previewView.width.coerceAtLeast(1), previewView.height.coerceAtLeast(1)),
                    previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                ).build()
                val group = androidx.camera.core.UseCaseGroup.Builder().setViewPort(viewPort).addUseCase(preview).addUseCase(imageAnalysis).build()
                val cam = cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
                ownedUseCases.set(listOf(preview, imageAnalysis))
                if (disposed.get()) { cameraProvider.unbind(preview, imageAnalysis); return@post }   // disposed during bind
                onCameraBound(cam.cameraInfo)
            } catch (e: Exception) { ai.genwhy.nobonk.util.Dbg.e("CameraPreview", "Camera bind failed", e); onError("Camera unavailable. Check camera access, then tap Start scanning.") }
        } }, ContextCompat.getMainExecutor(ctx))
        previewView
    })
}


@Composable
fun CameraPermissionScreen(onRetry: () -> Unit, onSettings: () -> Unit, onExit: () -> Unit) {
    Column(Modifier.fillMaxSize().background(NB.Night).safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Wordmark(48)
        Spacer(Modifier.height(24.dp))
        Text("Camera access is off", color = NB.Ink, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text("NoBonk needs the rear camera to scan. Frames stay on your phone. You can allow access now, or open Settings if Android no longer shows the permission prompt.", color = NB.Sub, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("Allow camera") }
        TextButton(onClick = onSettings) { Text("Open app settings") }
        TextButton(onClick = onExit) { Text("Not now") }
    }
}
