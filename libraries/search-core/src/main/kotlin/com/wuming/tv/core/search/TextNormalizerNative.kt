package com.wuming.tv.core.search

import com.wuming.tv.core.base.native.NativeLoader

/** Native text normalization with Kotlin fallback. */
object TextNormalizerNative {

    private val nativeAvailable: Boolean get() = NativeLoader.available

    fun normalize(input: String?): String {
        if (input == null) return ""
        if (nativeAvailable) {
            try {
                return normalizeNative(input)
            } catch (_: UnsatisfiedLinkError) {
            }
        }
        return input.trim().lowercase()
    }

    @JvmStatic
    private external fun normalizeNative(input: String): String
}