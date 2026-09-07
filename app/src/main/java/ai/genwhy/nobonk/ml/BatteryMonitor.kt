package ai.genwhy.nobonk.ml

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat

/** One receiver per owner, instead of an IPC battery query on every camera frame. */
internal class BatteryMonitor(context: Context, private val onLevel: (Int) -> Unit = {}) : AutoCloseable {
    private val app = context.applicationContext
    @Volatile var level: Int = 100
        private set
    private var registered = false
    private var closed = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { update(intent) }
    }

    /** Called on Main, like Android's receiver callbacks and [close]. */
    fun start() {
        if (registered || closed) return
        val sticky = ContextCompat.registerReceiver(app, receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        update(sticky)
    }

    private fun update(intent: Intent?) {
        if (closed || intent?.action != Intent.ACTION_BATTERY_CHANGED) return
        val next = BatteryLevel.percent(intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1), level)
        if (next != level) { level = next; onLevel(next) }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (registered) { app.unregisterReceiver(receiver); registered = false }
    }
}
