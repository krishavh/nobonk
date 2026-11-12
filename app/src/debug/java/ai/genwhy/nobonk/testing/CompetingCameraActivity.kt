package ai.genwhy.nobonk.testing

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView

/** Debug-only real Camera2 competitor. No mock CameraX states, frames or hazards. */
class CompetingCameraActivity : Activity() {
    companion object { @Volatile var current: CompetingCameraActivity? = null }
    @Volatile var opened = false
        private set
    @Volatile var failure: String? = null
        private set
    private var device: CameraDevice? = null
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        setContentView(TextView(this).apply { text = "NoBonk debug: real rear-camera competition" })
    }
    override fun onResume() {
        super.onResume()
        val token = ++generation
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            failure = "Camera permission denied"; return
        }
        try {
            val manager = getSystemService(CameraManager::class.java)
            val rear = manager.cameraIdList.first { manager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            manager.openCamera(rear, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (token != generation || isFinishing || isDestroyed) { camera.close(); return }
                    device = camera; opened = true
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (token == generation) { device = null; opened = false; failure = "Camera competitor was disconnected" }
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (token == generation) { device = null; opened = false; failure = "Camera2 error $error" }
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) { failure = "${e.javaClass.simpleName}: ${e.message}" }
    }
    private fun release() { generation++; opened = false; device?.close(); device = null }
    override fun onPause() { release(); super.onPause() }
    override fun onDestroy() { release(); if (current === this) current = null; super.onDestroy() }
}
