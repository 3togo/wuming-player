package com.wuming.android.tv.python

import com.google.gson.JsonParser
import com.wuming.android.tv.App
import com.wuming.tv.core.runtime.python.NativePythonBridge
import com.wuming.tv.core.runtime.python.PythonRuntimeBundle
import java.io.File

object AndroidPythonRuntime {
    @Synchronized fun start() {
        val app = App.get()
        val manifest = app.assets.open("python/runtime.json").bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        val home = PythonRuntimeBundle.install(File(app.filesDir, "python-runtime"), manifest.get("sha256").asString) {
            app.assets.open("python/runtime.zip")
        }
        // Class-loader resolution also works with uncompressed libraries inside the APK.
        System.loadLibrary("python3.13")
        NativePythonBridge.start(home, listOf(File(home, "bridge"), File(home, "lib/python3.13/site-packages")))
    }
}
