package ai.genwhy.nobonk

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import ai.genwhy.nobonk.service.DetectionService
import ai.genwhy.nobonk.ui.DetectionScreen
import ai.genwhy.nobonk.ui.SafetyNoticeScreen
import ai.genwhy.nobonk.ui.StayAwareReminder
import ai.genwhy.nobonk.ui.HistoryScreen
import ai.genwhy.nobonk.ui.LicensesScreen
import ai.genwhy.nobonk.ui.theme.PersonDetectionTheme
import ai.genwhy.nobonk.viewmodel.DetectionViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: DetectionViewModel by viewModels()
    private var hasPermission by mutableStateOf(false)
    private var canDrawOverlays by mutableStateOf(false)
    private var showHistory by mutableStateOf(false)
    private var showLicenses by mutableStateOf(false)
    // Safety-notice gate: persisted acknowledged version + which screen to show now.
    private var ackVersion by mutableIntStateOf(0)
    private var noticeScreen by mutableStateOf(ai.genwhy.nobonk.safety.SafetyNotice.Screen.FULL_NOTICE)
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

        val prefs = getSharedPreferences("nobonk_prefs", Context.MODE_PRIVATE)
        // Safety notice: the current version must be acknowledged before any camera request
        // or camera start (fresh installs and upgrades from first_run_done-only builds alike).
        ackVersion = prefs.getInt(ai.genwhy.nobonk.safety.SafetyNotice.PREF_ACK_VERSION, 0)
        // Returning to a live background session (Open NoBonk pill / notification) must not
        // interrupt it with a reminder — the session itself proves acknowledgment this process.
        if (ai.genwhy.nobonk.service.DetectionService.isRunning) ai.genwhy.nobonk.safety.SessionState.acknowledgedThisProcess = true
        noticeScreen = ai.genwhy.nobonk.safety.SafetyNotice.screenFor(ackVersion, ai.genwhy.nobonk.safety.SessionState.acknowledgedThisProcess, ai.genwhy.nobonk.service.DetectionService.isRunning)
        // A returning user may already have granted camera — reflect that so we don't
        // pointlessly re-prompt or get stuck on a blank screen.
        hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        // Keep the screen on while the app is in the foreground — this is a
        // safety app and the user must not be distracted by a locking screen.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Only request permissions immediately for returning users; first-run users are
        // prompted after they read the rationale and tap "continue".
        if (ai.genwhy.nobonk.safety.SafetyNotice.cameraAllowed(ackVersion) && !hasPermission) requestCorePermissions()
        viewModel.initialize(applicationContext)

        setContent {
            PersonDetectionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    canDrawOverlays = Settings.canDrawOverlays(this)

                    if (noticeScreen == ai.genwhy.nobonk.safety.SafetyNotice.Screen.FULL_NOTICE) {
                        // ── Full safety notice + explicit acknowledgment (gates the camera) ──
                        SafetyNoticeScreen(
                            onAccept = {
                                val v = ai.genwhy.nobonk.safety.SafetyNotice.acknowledgedVersion()
                                prefs.edit().putInt(ai.genwhy.nobonk.safety.SafetyNotice.PREF_ACK_VERSION, v).putBoolean(PREF_FIRST_RUN_DONE, true).apply()
                                ackVersion = v
                                ai.genwhy.nobonk.safety.SessionState.acknowledgedThisProcess = true
                                noticeScreen = ai.genwhy.nobonk.safety.SafetyNotice.Screen.NONE
                                if (!hasPermission) requestCorePermissions()
                            },
                            onNotNow = { finish() }
                        )
                    } else if (noticeScreen == ai.genwhy.nobonk.safety.SafetyNotice.Screen.REMINDER) {
                        // ── Concise stay-aware reminder on a genuine cold launch ──
                        StayAwareReminder(
                            onContinue = {
                                ai.genwhy.nobonk.safety.SessionState.acknowledgedThisProcess = true
                                noticeScreen = ai.genwhy.nobonk.safety.SafetyNotice.Screen.NONE
                            },
                            onReadFull = { ai.genwhy.nobonk.safety.SessionState.acknowledgedThisProcess = true; noticeScreen = ai.genwhy.nobonk.safety.SafetyNotice.Screen.NONE; showLicenses = true }
                        )
                    } else if (hasPermission) {
                        when {
                            showLicenses -> {
                                // ── Open-source licenses (AGPL §13) ─────
                                LicensesScreen(onBack = { showLicenses = false })
                            }
                            showHistory -> {
                                // ── Analytics dashboard ─────────────────
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
                                    onDisableLocation = { viewModel.disableLocationTagging() },
                                    onShowLicenses = { showLicenses = true }
                                )
                            }
                            else -> {
                                // ── Main detection screen ───────────────
                                DetectionScreen(
                                    viewModel         = viewModel,
                                    onStartBackground = { startDetectionService() },
                                    onStopBackground  = { stopDetectionService() },
                                    canDrawOverlays   = canDrawOverlays,
                                    onGrantOverlay    = { requestOverlayPermission() },
                                    onShowHistory     = { showHistory = true },
                                    onShowAbout       = { showLicenses = true },
                                    cameraRebindKey   = cameraRebindKey
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** Requests only camera (+ notifications on 13+). Location stays opt-in, in-context. */
    private fun requestCorePermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
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
            putExtra(DetectionService.EXTRA_SOUND, viewModel.soundEnabled)
            putExtra(DetectionService.EXTRA_HAPTICS, viewModel.hapticsEnabled)
            putExtra(DetectionService.EXTRA_VOICE, viewModel.voiceEnabled)
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

    companion object {
        private const val PREF_FIRST_RUN_DONE = "first_run_done"
    }
}
