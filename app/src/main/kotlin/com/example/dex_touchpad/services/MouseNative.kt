package com.example.dex_touchpad.services

/** JNI bridge implemented by the bundled libdextouchpad.so. */
object MouseNative {
    init {
        System.loadLibrary("dextouchpad")
    }

    external fun nativeCreateUHid(): Boolean
    external fun nativeCloseUHid(): Boolean
    external fun nativeUHidEvent(deltaX: Int, deltaY: Int)
    external fun nativeUHidPressL(pressed: Boolean)
    external fun nativeUHidPressR(pressed: Boolean)
    external fun nativeUHidPressM(pressed: Boolean)
    external fun nativeUHidScroll(amount: Int)
}
