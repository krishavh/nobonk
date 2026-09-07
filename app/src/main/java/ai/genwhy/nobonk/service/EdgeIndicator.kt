package ai.genwhy.nobonk.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import ai.genwhy.nobonk.model.AlertLevel
import ai.genwhy.nobonk.util.Dbg

/**
 * Four thin, non-touchable overlay strips along the screen edges (inside the status-bar /
 * cutout and gesture-bar insets). Each strip is its own tiny window, so no touch can ever
 * pass through an app-owned overlay, and nothing of the underlying app is covered beyond
 * a 3 dp border. Colour follows [EdgeIndicatorPolicy]; there is no animation. Window alpha stays
 * under Android's maximum obscuring opacity so touches are never blocked. The app is portrait-locked
 * and the strips live only for a background session, so no rotation re-layout is needed.
 */
class EdgeIndicator(private val context: Context, private val wm: WindowManager) {
    private val strips = ArrayList<View>(4)
    private var color = 0

    fun show(level: AlertLevel = AlertLevel.NONE, cameraBlocked: Boolean = false) {
        if (strips.isNotEmpty()) { setLevel(level, cameraBlocked); return }
        val d = context.resources.displayMetrics.density
        val t = (EdgeIndicatorPolicy.THICKNESS_DP * d).toInt().coerceAtLeast(2)
        val (top, bottom) = insets()
        color = EdgeIndicatorPolicy.colorFor(level, cameraBlocked)
        fun params(w: Int, h: Int, gravity: Int, x: Int, y: Int) = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity; this.x = x; this.y = y
            // Android 12+ blocks touches passing through an app overlay whose window alpha exceeds the
            // system's maximum obscuring opacity (0.8); stay under it so nothing beneath is ever blocked.
            alpha = 0.75f
        }
        val M = WindowManager.LayoutParams.MATCH_PARENT
        val screenH = screenHeightPx()
        val sideH = (screenH - top - bottom - 2 * t).coerceAtLeast(t)   // between the two horizontal strips: no corner overlap
        val specs = listOf(
            params(M, t, Gravity.TOP or Gravity.START, 0, top),                 // just below status bar / cutout
            params(M, t, Gravity.BOTTOM or Gravity.START, 0, bottom),           // just above the gesture bar
            params(t, sideH, Gravity.START or Gravity.TOP, 0, top + t),
            params(t, sideH, Gravity.END or Gravity.TOP, 0, top + t)
        )
        for (p in specs) {
            val v = View(context).apply { setBackgroundColor(color); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            try { wm.addView(v, p); strips += v } catch (e: Exception) { Dbg.e("EdgeIndicator", "addView failed", e) }
        }
    }

    fun setLevel(level: AlertLevel, cameraBlocked: Boolean) {
        val c = EdgeIndicatorPolicy.colorFor(level, cameraBlocked)
        if (!EdgeIndicatorPolicy.shouldRedraw(color, c)) return
        color = c
        for (v in strips) v.setBackgroundColor(c)
    }

    fun hide() {
        for (v in strips) try { wm.removeView(v) } catch (_: Exception) {}
        strips.clear()
    }

    private fun screenHeightPx(): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm.currentWindowMetrics.bounds.height() else context.resources.displayMetrics.heightPixels
    } catch (_: Exception) { context.resources.displayMetrics.heightPixels }

    /** (top, bottom) insets in px so the strips never sit under the clock or the gesture bar. */
    private fun insets(): Pair<Int, Int> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.navigationBars())
            i.top to i.bottom
        } else {
            val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            (if (id > 0) context.resources.getDimensionPixelSize(id) else 0) to 0
        }
    } catch (_: Exception) { 0 to 0 }
}
