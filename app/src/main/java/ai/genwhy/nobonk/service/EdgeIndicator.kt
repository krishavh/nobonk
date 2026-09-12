package ai.genwhy.nobonk.service

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import ai.genwhy.nobonk.model.AlertLevel
import ai.genwhy.nobonk.util.Dbg

/** A moving red trail drawn in four narrow, non-touchable edge windows. */
class EdgeIndicator(private val context: Context, private val wm: WindowManager) {
    private val strips = ArrayList<View>(4)
    private val handler = Handler(Looper.getMainLooper())
    private val frame = object : Runnable {
        override fun run() {
            val awake = context.getSystemService(android.os.PowerManager::class.java).isInteractive
            if (awake) strips.forEach { it.invalidate() }
            if (strips.isNotEmpty() && !blocked) {
                // Recheck infrequently with the screen off or animation disabled, so changing
                // the system preference can resume the trail without another hazard arriving.
                handler.postDelayed(this, if (awake && android.animation.ValueAnimator.areAnimatorsEnabled()) 50L else 1000L)
            }
        }
    }
    private var level = AlertLevel.NONE
    private var blocked = false

    fun show(level: AlertLevel = AlertLevel.NONE, cameraBlocked: Boolean = false) {
        if (strips.isNotEmpty()) { setLevel(level, cameraBlocked); return }
        this.level = level; this.blocked = cameraBlocked
        val d = context.resources.displayMetrics.density
        val t = (EdgeIndicatorPolicy.THICKNESS_DP * d).toInt().coerceAtLeast(2)
        val (top, bottom) = insets()
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
        val screenW = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm.currentWindowMetrics.bounds.width() else context.resources.displayMetrics.widthPixels
        val perimeter = 2f * (screenW + sideH)
        for ((index, p) in specs.withIndex()) {
            val v = object : View(context) {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    if (blocked) { canvas.drawColor(EdgeIndicatorPolicy.BLOCKED); return }
                    // Clockwise: top → right → bottom → left, with a fading tail.
                    val length = if (index < 2) width.toFloat() else height.toFloat()
                    val start = when (index) { 0 -> 0f; 3 -> screenW.toFloat(); 1 -> screenW + sideH.toFloat(); else -> 2f * screenW + sideH }
                    val head = if (android.animation.ValueAnimator.areAnimatorsEnabled()) (SystemClock.uptimeMillis() % 6000L) / 6000f * perimeter else perimeter * 0.12f
                    val tail = perimeter * 0.18f
                    val step = (4 * d).coerceAtLeast(2f)
                    var pos = 0f
                    while (pos < length) {
                        val behind = (head - (start + pos) + perimeter) % perimeter
                        if (behind <= tail) {
                            paint.color = EdgeIndicatorPolicy.HIGH
                            paint.alpha = (255 * (1f - behind / tail)).toInt()
                            val end = (pos + step).coerceAtMost(length)
                            when (index) {
                                0 -> canvas.drawRect(pos, 0f, end, height.toFloat(), paint)
                                3 -> canvas.drawRect(0f, pos, width.toFloat(), end, paint)
                                1 -> canvas.drawRect(length - end, 0f, length - pos, height.toFloat(), paint)
                                else -> canvas.drawRect(0f, length - end, width.toFloat(), length - pos, paint)
                            }
                        }
                        pos += step
                    }
                }
            }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            try { wm.addView(v, p); strips += v } catch (e: Exception) { Dbg.e("EdgeIndicator", "addView failed", e) }
        }
        handler.removeCallbacks(frame)
        if (!blocked && strips.isNotEmpty()) handler.post(frame)
    }

    /** Display rotated / insets changed while another app is in front: rebuild geometry, keep state. */
    fun relayout() { if (strips.isEmpty()) return; hide(); show(level, blocked) }

    fun setLevel(level: AlertLevel, cameraBlocked: Boolean) {
        if (this.level == level && blocked == cameraBlocked) return
        this.level = level; this.blocked = cameraBlocked
        handler.removeCallbacks(frame)
        strips.forEach { it.invalidate() }
        if (!blocked && strips.isNotEmpty()) handler.post(frame)
    }

    fun hide() {
        handler.removeCallbacks(frame)
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
