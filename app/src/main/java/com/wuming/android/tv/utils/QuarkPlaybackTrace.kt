package com.wuming.android.tv.utils

import com.wuming.android.tv.App
import com.wuming.android.tv.BuildConfig
import java.io.File
import java.io.FileWriter

/** Bounded debug diagnostics containing quality decisions, never media URLs or credentials. */
object QuarkPlaybackTrace {
    @JvmStatic
    @Synchronized
    fun write(message: String) {
        if (!BuildConfig.DEBUG) return
        try {
            val file = File(App.get().filesDir, "quark-playback-trace.txt")
            FileWriter(file, file.length() < 32 * 1024).use { writer ->
                writer.write("${System.currentTimeMillis()} $message\n")
            }
        } catch (ignored: Exception) {
        }
    }
}
