package com.persondetection.android

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.persondetection.android.service.DetectionService
import com.persondetection.android.ui.DetectionScreen
import com.persondetection.android.ui.HistoryScreen
import com.persondetection.android.ui.theme.PersonDetectionTheme
import com.persondetection.android.viewmodel.DetectionViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: DetectionViewModel by viewModels()
    private var hasPermission by mutableStateOf(false)
    private var canDrawOverlays by mutableStateOf(false)
    private var showHistory by mutableStateOf(false)
    // Incremented on every onResume so CameraPreview knows to rebind.
    // Wrapping CameraPreview in key(cameraRebindKey) forces Compose to fully
    // recreate the AndroidView — re-running the factory lambda which re-calls
    // cameraProvider.unbindAll() + bindToLifecycle with the activity lifecycle.
    private var cameraRebindKey by mutableIntStateOf(0)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Only camera (+ notifications) are requested up front. Location is opt-in.
        hasPermission = permissions[Manifest.permission.CAMERA] == true
    }

    // In-context COARSE-location opt-in, triggered from the history screen.
    private val requestLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.enableLocationTagging(applicationContext)
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        canDrawOverlays = Settings.canDrawOverlays(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Location is NOT requested here — it is opt-in, in-context (history screen).
        val permissions = mutableListOf(
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Keep the screen on while the app is in the foreground — this is a
        // safety app and the user must not be distracted by a locking screen.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        requestPermissionLauncher.launch(permissions.toTypedArray())
        viewModel.initialize(applicationContext)

        setContent {
            PersonDetectionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    canDrawOverlays = Settings.canDrawOverlays(this)

                    if (hasPermission) {
                        if (showHistory) {
                            // ── Analytics dashboard ───────────────────
                            HistoryScreen(
                                events   = viewModel.historyEvents,
                                sessions = viewModel.historySessions,
                                onBack   = {
                                    viewModel.refreshHistory()   // reload on return
                                    showHistory = false
                                },
                                onClearHistory = { viewModel.clearHistory() },
                                locationTaggingEnabled = viewModel.locationTaggingEnabled,
                                onEnableLocation = {
                                    requestLocationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                                },
                                onDisableLocation = { viewModel.disableLocationTagging() }
                            )
                        } else {
                            // ── Main detection screen ─────────────────
                            DetectionScreen(
                                viewModel         = viewModel,
                                onStartBackground = { startDetectionService() },
                                onStopBackground  = { stopDetectionService() },
                                canDrawOverlays   = canDrawOverlays,
                                onGrantOverlay    = { requestOverlayPermission() },
                                onShowHistory     = { showHistory = true },
                                cameraRebindKey   = cameraRebindKey
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Stop the background service so the camera is released back to the activity.
        stopDetectionService()
        canDrawOverlays = Settings.canDrawOverlays(this)
        // Increment the key AFTER stopping the service so CameraPreview recreates
        // itself and calls cameraProvider.unbindAll() + bindToLifecycle fresh.
        cameraRebindKey++
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun startDetectionService() {
        val mode = viewModel.accuracyMode
        val intent = Intent(this, DetectionService::class.java).apply {
            action = DetectionService.ACTION_START
            // Hand the user's config to the background pipeline so it matches foreground.
            putExtra(DetectionService.EXTRA_THRESHOLD, viewModel.distanceThreshold)
            putExtra(DetectionService.EXTRA_INCLUDE_NONPERSON, viewModel.isObjectDetectionEnabled)
            putExtra(DetectionService.EXTRA_MODEL, mode.modelFile)
            putExtra(DetectionService.EXTRA_INPUT_PX, mode.inputPx)
            putExtra(DetectionService.EXTRA_SKIP_NMS, mode.skipNms)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        // Minimize the app to make "start background" obvious
        moveTaskToBack(true)
    }

    private fun stopDetectionService() {
        val intent = Intent(this, DetectionService::class.java).apply {
            action = DetectionService.ACTION_STOP
        }
        startService(intent)
    }
}
