package com.wuming.tv.core.base.native

object NativeLoader {
    @Volatile
    @JvmField
    var available: Boolean = try {
        System.loadLibrary("tvbox_native")
        nativeProbe()
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    @JvmStatic
    private external fun nativeProbe(): Boolean
}
