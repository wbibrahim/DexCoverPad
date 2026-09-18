package com.example.dex_touchpad.services

import android.os.Process
import android.util.Log
import com.example.dex_touchpad.IMouseControl
import kotlin.math.roundToInt

private const val TAG = "ShizukuUserService"
private const val BUTTON_LEFT = 272
private const val BUTTON_RIGHT = 273
private const val BUTTON_MIDDLE = 274
private const val MIN_REPORT_VALUE = -127
private const val MAX_REPORT_VALUE = 127

/**
 * Runs under Shizuku's shell identity and owns the virtual UHID mouse.
 *
 * Shizuku connects the app directly to this binder, so no broadcast or
 * standalone native helper process is needed.
 */
class ShizukuUserService : IMouseControl.Stub() {

    private val nativeLock = Any()
    @Volatile private var ready = false
    @Volatile private var error: String? = null
    private var remainderX = 0f
    private var remainderY = 0f
    private var scrollRemainder = 0f

    init {
        Log.d(TAG, "Creating virtual mouse as UID=${Process.myUid()}")
        try {
            ready = MouseNative.nativeCreateUHid()
            if (!ready) {
                error = "Android did not allow the virtual mouse to start"
                Log.e(TAG, error!!)
            } else {
                Log.i(TAG, "Virtual UHID mouse is ready")
            }
        } catch (t: Throwable) {
            error = "Could not load the virtual mouse: ${t.message ?: t.javaClass.simpleName}"
            Log.e(TAG, error, t)
        }
    }

    override fun moveCursor(deltaX: Float, deltaY: Float) = synchronized(nativeLock) {
        if (!ready) return@synchronized

        val totalX = deltaX + remainderX
        val totalY = deltaY + remainderY
        val reportX = totalX.roundToInt().coerceIn(MIN_REPORT_VALUE, MAX_REPORT_VALUE)
        val reportY = totalY.roundToInt().coerceIn(MIN_REPORT_VALUE, MAX_REPORT_VALUE)
        remainderX = totalX - reportX
        remainderY = totalY - reportY

        if (reportX != 0 || reportY != 0) {
            MouseNative.nativeUHidEvent(reportX, reportY)
        }
    }

    override fun sendClick(buttonCode: Int) = synchronized(nativeLock) {
        if (!ready) return@synchronized

        when (buttonCode) {
            BUTTON_LEFT -> {
                MouseNative.nativeUHidPressL(true)
                MouseNative.nativeUHidPressL(false)
            }
            BUTTON_RIGHT -> {
                MouseNative.nativeUHidPressR(true)
                MouseNative.nativeUHidPressR(false)
            }
            BUTTON_MIDDLE -> {
                MouseNative.nativeUHidPressM(true)
                MouseNative.nativeUHidPressM(false)
            }
            else -> Log.w(TAG, "Ignoring unknown mouse button code $buttonCode")
        }
    }

    override fun sendScroll(verticalDelta: Float, horizontalDelta: Float) =
        synchronized(nativeLock) {
            if (!ready) return@synchronized

            val total = verticalDelta + scrollRemainder
            val report = total.roundToInt().coerceIn(MIN_REPORT_VALUE, MAX_REPORT_VALUE)
            scrollRemainder = total - report
            if (report != 0) {
                MouseNative.nativeUHidScroll(report)
            }
        }

    override fun isReady(): Boolean = ready

    override fun getError(): String? = error

    override fun destroy() {
        Log.d(TAG, "Destroying virtual mouse")
        synchronized(nativeLock) {
            if (ready) {
                runCatching { MouseNative.nativeCloseUHid() }
                    .onFailure { Log.w(TAG, "Could not close virtual mouse", it) }
            }
            ready = false
        }
        System.exit(0)
    }
}
