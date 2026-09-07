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
    // True while we expect to come straight back from something we launched ourselves
    // (permission dialogs, the overlay-settings screen, starting the background session):
    // those stops are hand-offs, not the user leaving the app.
    private var expectingReturn = false

    // Google Play flexible in-app updates (Play-installed builds only; sideload/emulator → silently none).
    private var updateCoordinator: ai.genwhy.nobonk.update.UpdateCoordinator? = null
    private var updateAvailability by mutableStateOf(ai.genwhy.nobonk.update.UpdatePolicy.Availability.UNKNOWN)
    private var updateVersion by mutableIntStateOf(0)
    private var updateStatusText by mutableStateOf("")
    private var updateSnoozedUntil by mutableLongStateOf(0L)   // observable so 'Later' dismisses the card immediately
    private val updateFlowLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        expectingReturn = false
        if (r.resultCode != RESULT_OK) snoozeUpdate()   // declined / failed → quiet for a day
    }
    private fun updatePrompt(): ai.genwhy.nobonk.update.UpdatePolicy.Prompt {
        return ai.genwhy.nobonk.update.UpdatePolicy.promptFor(ai.genwhy.nobonk.update.UpdatePolicy.Context(
            availability = updateAvailability, availableVersionCode = updateVersion,
            scanning = viewModel.scanningEnabled, backgroundActive = ai.genwhy.nobonk.safety.SessionState.gate.serviceActive,
            gateCleared = ai.genwhy.nobonk.safety.SessionState.gate.cameraAllowed(ackVersion) && noticeScreen == ai.genwhy.nobonk.safety.SafetyNotice.Screen.NONE,
            snoozedUntilMs = updateSnoozedUntil, nowMs = System.currentTimeMillis()))
    }
    private fun snoozeUpdate() {
        updateSnoozedUntil = ai.genwhy.nobonk.update.UpdatePolicy.snoozeUntil(System.currentTimeMillis())
        getSharedPreferences("nobonk_prefs", Context.MODE_PRIVATE).edit().putLong(PREF_UPDATE_SNOOZE, updateSnoozedUntil).apply()
        updateStatusText = "Snoozed for a day."
    }
    private fun onUpdateNow() {
        val c = updateCoordinator ?: return
        if (updatePrompt() == ai.genwhy.nobonk.update.UpdatePolicy.Prompt.NONE) return   // gate / scanning re-checked at click time, not only at render
        if (updateAvailability == ai.genwhy.nobonk.update.UpdatePolicy.Availability.DOWNLOADED) {
            if (ai.genwhy.nobonk.update.UpdatePolicy.mayCompleteInstall(viewModel.scanningEnabled, ai.genwhy.nobonk.safety.SessionState.gate.serviceActive)) c.completeUpdate()
            return
        }
        expectingReturn = true   // Play's update sheet is a hand-off, not the user leaving
        if (!c.startFlexible(this, updateFlowLauncher)) expectingReturn = false
    }
    private fun checkForUpdates(manual: Boolean) {
        val c = updateCoordinator ?: ai.genwhy.nobonk.update.UpdateCoordinator(this) { av, ver ->
            runOnUiThread {
                updateAvailability = av; updateVersion = ver
                updateStatusText = when (av) {
                    ai.genwhy.nobonk.update.UpdatePolicy.Availability.AVAILABLE_FLEXIBLE -> "Update available on Google Play."
                    ai.genwhy.nobonk.update.UpdatePolicy.Availability.DOWNLOADED -> "Update downloaded — restart when you are not scanning."
                    ai.genwhy.nobonk.update.UpdatePolicy.Availability.DOWNLOADING -> "Downloading update…"
                    ai.genwhy.nobonk.update.UpdatePolicy.Availability.FAILED -> "Update could not be completed. You can retry from Google Play."
                    ai.genwhy.nobonk.update.UpdatePolicy.Availability.NONE -> "Google Play reports no newer version."
                    ai.genwhy.nobonk.update.UpdatePolicy.Availability.UNAVAILABLE -> "Couldn't check with Google Play (this install may not be from Play)."
                    else -> ""
                }
            }
        }.also { updateCoordinator = it }
        if (manual) updateStatusText = "Checking Google Play…"
        c.check()
    }
    /** Idle moments: gate just cleared, or scanning just stopped — the only times a card can show. */
    private fun onIdleMoment() { if (ai.genwhy.nobonk.safety.SessionState.gate.cameraAllowed(ackVersion)) checkForUpdates(manual = false) }
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
        expectingReturn = false   // hand-off complete (dialogs often pause/resume without onStop)
    }

    // In-context COARSE-location opt-in, triggered from the history screen.
    private val requestLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        expectingReturn = false
        if (granted) viewModel.enableLocationTagging(applicationContext)
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        expectingReturn = false
        canDrawOverlays = Settings.canDrawOverlays(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("nobonk_prefs", Context.MODE_PRIVATE)
        // Safety notice: the current version must be acknowledged before any camera request
        // or camera start (fresh installs and upgrades from first_run_done-only builds alike).
        ackVersion = prefs.getInt(ai.genwhy.nobonk.safety.SafetyNotice.PREF_ACK_VERSION, 0)
        updateSnoozedUntil = prefs.getLong(PREF_UPDATE_SNOOZE, 0L)
        // Gate decision for THIS launch: config recreation (saved state) and a live, authorized
        // background session keep it cleared; anything else re-prompts (every launch).
        val gate = ai.genwhy.nobonk.safety.SessionState.gate
        noticeScreen = gate.screenOnCreate(ackVersion, savedInstanceState?.getBoolean(STATE_GATE_CLEARED), savedInstanceState?.getString(STATE_GATE_TOKEN))
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
        // Permissions are requested only once the gate is cleared for this launch (return to a
        // live session); otherwise they are requested from the OK / accept callbacks below.
        if (gate.permissionRequestAllowed(ackVersion) && !hasPermission) requestCorePermissions()
        viewModel.initialize(applicationContext)

        setContent {
            PersonDetectionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    canDrawOverlays = Settings.canDrawOverlays(this)

                    viewModel.historyError?.let { message ->
                        androidx.compose.material3.AlertDialog(onDismissRequest = { viewModel.dismissHistoryError() },
                            title = { androidx.compose.material3.Text("History") }, text = { androidx.compose.material3.Text(message) },
                            confirmButton = { androidx.compose.material3.TextButton(onClick = { viewModel.dismissHistoryError() }) { androidx.compose.material3.Text("OK") } })
                    }
                    // System Back on About/History pops that screen (on Android 12+ it would otherwise
                    // background the root task) — so Back from the full notice returns to the reminder.
                    androidx.activity.compose.BackHandler(enabled = showLicenses || showHistory) {
                        if (showLicenses) showLicenses = false else { viewModel.refreshHistory(); showHistory = false }
                    }
                    if (showLicenses && noticeScreen != ai.genwhy.nobonk.safety.SafetyNotice.Screen.NONE) {
                        // Full text requested from the reminder — read-only, gate still pending.
                        LicensesScreen(onBack = { showLicenses = false })   // read-only over the gate: no update actions here
                    } else if (noticeScreen == ai.genwhy.nobonk.safety.SafetyNotice.Screen.FULL_NOTICE) {
                        // ── Full safety notice + explicit acknowledgment (gates the camera) ──
                        SafetyNoticeScreen(
                            onAccept = {
                                val v = ai.genwhy.nobonk.safety.SafetyNotice.acknowledgedVersion()
                                prefs.edit().putInt(ai.genwhy.nobonk.safety.SafetyNotice.PREF_ACK_VERSION, v).putBoolean(PREF_FIRST_RUN_DONE, true).apply()
                                ackVersion = v
                                gate.onAcknowledged()
                                noticeScreen = ai.genwhy.nobonk.safety.SafetyNotice.Screen.NONE
                                if (gate.permissionRequestAllowed(ackVersion) && !hasPermission) requestCorePermissions()
                                onIdleMoment()
                            },
                            onNotNow = { finish() }
                        )
                    } else if (noticeScreen == ai.genwhy.nobonk.safety.SafetyNotice.Screen.REMINDER) {
                        // ── Concise stay-aware reminder on a genuine cold launch ──
                        StayAwareReminder(
                            onContinue = {
                                gate.onAcknowledged()
                                noticeScreen = ai.genwhy.nobonk.safety.SafetyNotice.Screen.NONE
                                if (gate.permissionRequestAllowed(ackVersion) && !hasPermission) requestCorePermissions()
                                onIdleMoment()
                            },
                            // Reading never acknowledges: About opens over the pending reminder and Back returns to it.
                            onReadFull = { gate.onReadFull(); showLicenses = true }
                        )
                    } else if (!hasPermission) {
                        ai.genwhy.nobonk.ui.CameraPermissionScreen(
                            onRetry = { requestCorePermissions() },
                            onSettings = {
                                expectingReturn = true
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                            },
                            onExit = { finish() }
                        )
                    } else if (hasPermission) {
                        when {
                            showLicenses -> {
                                // ── Open-source licenses (AGPL §13) ─────
                                LicensesScreen(onBack = { showLicenses = false }, updateStatus = updateStatusText, onCheckUpdates = { checkForUpdates(manual = true) })
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
                                        expectingReturn = true
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
                                    onStopBackground  = { stopDetectionService(); onIdleMoment() },
                                    canDrawOverlays   = canDrawOverlays,
                                    onGrantOverlay    = { requestOverlayPermission() },
                                    onShowHistory     = { showHistory = true },
                                    onShowAbout       = { showLicenses = true },
                                    cameraRebindKey   = cameraRebindKey,
                                    updatePrompt      = updatePrompt(),
                                    onUpdateNow       = { onUpdateNow() },
                                    onUpdateLater     = { snoozeUpdate() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_GATE_CLEARED, ai.genwhy.nobonk.safety.SessionState.gate.cleared)
        outState.putString(STATE_GATE_TOKEN, ai.genwhy.nobonk.safety.SessionState.gate.processToken)
    }

    override fun onPause() { super.onPause(); ai.genwhy.nobonk.safety.SessionState.gate.activityResumed = false }

    override fun onStop() {
        super.onStop()
        // Back (Android 12+ moves the root task to the background without finishing), Home,
        // or switching apps: unless this is a config change, a hand-off we initiated, or an
        // authorized background session is running, the launch is over → re-prompt on return.
        if (!isChangingConfigurations && !expectingReturn) ai.genwhy.nobonk.safety.SessionState.gate.onLeftApp()
        expectingReturn = false
    }

    override fun onStart() {
        super.onStart()
        // Warm reopen (no onCreate): re-evaluate the gate so the reminder shows when required.
        val gate = ai.genwhy.nobonk.safety.SessionState.gate
        if (noticeScreen == ai.genwhy.nobonk.safety.SafetyNotice.Screen.NONE) noticeScreen = gate.screenOnStart(ackVersion)
    }

    override fun onDestroy() {
        super.onDestroy()
        updateCoordinator?.dispose(); updateCoordinator = null   // release the Play install listener
        // A finished activity (Back / Not now / task removed) is a genuine end of launch: re-prompt next time.
        if (isFinishing) ai.genwhy.nobonk.safety.SessionState.gate.onActivityFinished()
    }

    /** Requests only camera (+ notifications on 13+). Location stays opt-in, in-context. */
    private fun requestCorePermissions() {
        expectingReturn = true   // system permission dialog: not the user leaving
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        ai.genwhy.nobonk.safety.SessionState.gate.activityResumed = true
        hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) viewModel.stopScanning()
        // Take the camera back from the background service (hand-off, not a user Stop). Only when a
        // service is actually active — never create a service just to stop it.
        if (ai.genwhy.nobonk.safety.SessionState.gate.serviceActive) stopDetectionService(DetectionService.STOP_REASON_HANDOFF)
        // If the user pressed Stop (notification or app) since we last looked, do not resume scanning.
        if (ai.genwhy.nobonk.safety.SessionState.backgroundStoppedByUser) {
            ai.genwhy.nobonk.safety.SessionState.backgroundStoppedByUser = false
            viewModel.stopScanning()
        }
        ai.genwhy.nobonk.safety.SessionState.backgroundFailure?.let {
            ai.genwhy.nobonk.safety.SessionState.backgroundFailure = null
            viewModel.reportCameraError(it)
        }
        canDrawOverlays = Settings.canDrawOverlays(this)
        // Quiet Play update check once the gate is cleared (prompting is separately policy-gated).
        if (ai.genwhy.nobonk.safety.SessionState.gate.cameraAllowed(ackVersion)) checkForUpdates(manual = false)
        // Increment the key AFTER stopping the service so CameraPreview recreates
        // itself and calls cameraProvider.unbindAll() + bindToLifecycle fresh.
        cameraRebindKey++
    }

    private fun requestOverlayPermission() {
        expectingReturn = true   // Settings screen we opened: hand-off, not leaving
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun startDetectionService() {
        if (!ai.genwhy.nobonk.safety.SessionState.gate.cameraAllowed(ackVersion)) return   // never start detection past a pending gate
        expectingReturn = true   // we are about to move the task back for an authorized session
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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            moveTaskToBack(true)
        } catch (e: Exception) {
            expectingReturn = false
            viewModel.reportCameraError("Background scanning could not start. Check camera access and try again.")
        }
    }

    private fun stopDetectionService(reason: String = DetectionService.STOP_REASON_USER) {
        val intent = Intent(this, DetectionService::class.java).apply {
            action = DetectionService.ACTION_STOP
            putExtra(DetectionService.EXTRA_STOP_REASON, reason)
        }
        try { startService(intent) } catch (e: Exception) { ai.genwhy.nobonk.util.Dbg.w("MainActivity", "stop intent failed: ${e.message}") }
    }

    companion object {
        private const val PREF_FIRST_RUN_DONE = "first_run_done"
        private const val STATE_GATE_CLEARED = "state_gate_cleared"
        private const val STATE_GATE_TOKEN = "state_gate_token"
        private const val PREF_UPDATE_SNOOZE = "update_snooze_until"
    }
}
