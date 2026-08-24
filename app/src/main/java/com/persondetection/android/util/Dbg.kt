package com.persondetection.android.util

import android.util.Log
import com.persondetection.android.BuildConfig

/**
 * Thin logging facade whose every call is guarded by [BuildConfig.DEBUG] (fixes SEC-N04).
 *
 * Release builds ship with `BuildConfig.DEBUG == false`, so every method here becomes a
 * no-op and NO app log — verbose, per-frame, or otherwise — is ever emitted in a release
 * artifact. Debug builds log exactly as before. Call these instead of [android.util.Log].
 */
object Dbg {
    fun d(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.d(tag, msg) }
    fun i(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.i(tag, msg) }
    fun w(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.w(tag, msg) }
    fun w(tag: String, msg: String, tr: Throwable) { if (BuildConfig.DEBUG) Log.w(tag, msg, tr) }
    fun e(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.e(tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable) { if (BuildConfig.DEBUG) Log.e(tag, msg, tr) }
}
