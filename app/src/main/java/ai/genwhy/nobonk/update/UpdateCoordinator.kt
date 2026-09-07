package ai.genwhy.nobonk.update

import android.app.Activity
import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import ai.genwhy.nobonk.util.Dbg
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Thin wrapper over Google Play's flexible in-app update flow (app-update-ktx 2.1.0).
 * Only reports state; [UpdatePolicy] decides whether to prompt. Play-installed builds only —
 * on a sideload / emulator without Play the availability task fails and we report NONE.
 * No external downloader, no tracking. Listener is registered on check and released in [dispose].
 */
class UpdateCoordinator(context: Context, private val onState: (UpdatePolicy.Availability, Int) -> Unit) {
    private val manager: AppUpdateManager? = try { AppUpdateManagerFactory.create(context.applicationContext) } catch (e: Exception) { null }
    private var info: AppUpdateInfo? = null
    private var listener: InstallStateUpdatedListener? = null
    @Volatile private var disposed = false

    fun check() {
        val m = manager ?: run { onState(UpdatePolicy.Availability.UNAVAILABLE, 0); return }
        info = null   // AppUpdateInfo is single-use: always refresh before a new offer
        try {
            m.appUpdateInfo.addOnSuccessListener { i ->
                if (disposed) return@addOnSuccessListener   // late result after the activity is gone
                info = i
                val state = when {
                    i.installStatus() == InstallStatus.DOWNLOADED -> UpdatePolicy.Availability.DOWNLOADED
                    i.installStatus() == InstallStatus.DOWNLOADING -> UpdatePolicy.Availability.DOWNLOADING
                    i.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && i.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> UpdatePolicy.Availability.AVAILABLE_FLEXIBLE
                    else -> UpdatePolicy.Availability.NONE
                }
                onState(state, i.availableVersionCode())
                ensureListener(m)
            }.addOnFailureListener { e ->
                if (disposed) return@addOnFailureListener
                Dbg.w("UpdateCoordinator", "no Play update info (sideload / no Play?): ${e.message}")
                onState(UpdatePolicy.Availability.UNAVAILABLE, 0)
            }
        } catch (e: Exception) { onState(UpdatePolicy.Availability.UNAVAILABLE, 0) }
    }

    private fun ensureListener(m: AppUpdateManager) {
        if (listener != null || disposed) return
        val l = InstallStateUpdatedListener { s ->
            when (s.installStatus()) {
                InstallStatus.DOWNLOADED -> onState(UpdatePolicy.Availability.DOWNLOADED, info?.availableVersionCode() ?: 0)
                InstallStatus.DOWNLOADING -> onState(UpdatePolicy.Availability.DOWNLOADING, info?.availableVersionCode() ?: 0)
                InstallStatus.FAILED, InstallStatus.CANCELED -> onState(UpdatePolicy.Availability.FAILED, info?.availableVersionCode() ?: 0)
                else -> Unit
            }
        }
        listener = l
        try { m.registerListener(l) } catch (_: Exception) { listener = null }
    }

    /** Start the Play flexible flow (Play shows its own sheet; result returns via [launcher]). */
    fun startFlexible(activity: Activity, launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean {
        val m = manager ?: return false; val i = info ?: return false
        info = null   // consumed: the next offer must re-check first
        return try {
            val started = m.startUpdateFlowForResult(i, launcher, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build())
            if (!started) onState(UpdatePolicy.Availability.FAILED, 0)
            started
        } catch (e: Exception) { Dbg.w("UpdateCoordinator", "startUpdateFlow failed: ${e.message}"); onState(UpdatePolicy.Availability.FAILED, 0); false }
    }

    /** User-controlled restart to apply a downloaded update (caller checks [UpdatePolicy.mayCompleteInstall]). */
    fun completeUpdate() { try { manager?.completeUpdate() } catch (e: Exception) { Dbg.w("UpdateCoordinator", "completeUpdate failed: ${e.message}") } }

    fun dispose() { disposed = true; listener?.let { l -> try { manager?.unregisterListener(l) } catch (_: Exception) {} }; listener = null }
}
