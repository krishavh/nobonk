package ai.genwhy.nobonk.testing

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import ai.genwhy.nobonk.motion.WalkingMotionSource
import ai.genwhy.nobonk.motion.WalkingSessionPolicy
import ai.genwhy.nobonk.safety.SafetyNotice
import ai.genwhy.nobonk.safety.SessionState
import ai.genwhy.nobonk.service.DetectionService
import ai.genwhy.nobonk.service.ServiceLifecycle

/** Instrumentation fixture only. Neither this component nor its controls exists in release builds. */
class WalkingServiceHarness : DetectionService() {
    companion object { @Volatile var current: WalkingServiceHarness? = null }
    private var transition: ((WalkingSessionPolicy.Transition) -> Unit)? = null
    var monitorActive = false
        private set
    override fun onCreate() { super.onCreate(); current = this }
    override fun walkingMotionAvailable() = true
    override fun createWalkingMonitor(onTransition: (WalkingSessionPolicy.Transition) -> Unit): WalkingMotionSource {
        transition = onTransition
        return object : WalkingMotionSource {
            override fun start(): Boolean { monitorActive = true; return true }
            override fun close() { monitorActive = false; transition = null }
        }
    }
    fun emit(value: WalkingSessionPolicy.Transition) { if (monitorActive) transition?.invoke(value) }
    fun field(name: String): Any? = DetectionService::class.java.getDeclaredField(name).run {
        isAccessible = true; get(this@WalkingServiceHarness)
    }
    val phase get() = (field("life") as ServiceLifecycle).phase
    override fun onDestroy() { super.onDestroy(); if (current === this) current = null }
}

class WalkingHarnessActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(android.widget.TextView(this).apply { text = "NoBonk instrumentation: synthetic motion, real camera service" })
    }
    fun startWalking(waitForWalking: Boolean = true) {
        getSharedPreferences("nobonk_prefs", MODE_PRIVATE).edit().putInt(SafetyNotice.PREF_ACK_VERSION, SafetyNotice.VERSION).commit()
        SessionState.gate.onAcknowledged()
        startForegroundService(Intent(this, WalkingServiceHarness::class.java).apply {
            action = DetectionService.ACTION_START
            putExtra(DetectionService.EXTRA_WAIT_FOR_WALKING, waitForWalking)
            putExtra(DetectionService.EXTRA_SOUND, false)
            putExtra(DetectionService.EXTRA_HAPTICS, false)
            putExtra(DetectionService.EXTRA_VOICE, false)
        })
    }
}
