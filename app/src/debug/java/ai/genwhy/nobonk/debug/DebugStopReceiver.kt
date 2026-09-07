package ai.genwhy.nobonk.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ai.genwhy.nobonk.service.DetectionService

/** Debug-only: forwards to the same ACTION_STOP (user reason) the notification Stop action sends. */
class DebugStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startService(Intent(context, DetectionService::class.java).apply {
            action = DetectionService.ACTION_STOP
            putExtra(DetectionService.EXTRA_STOP_REASON, DetectionService.STOP_REASON_USER)
        })
    }
}
