package com.wuming.android.tv.ui

import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Bundle
import android.content.Intent
import androidx.test.runner.AndroidJUnitRunner
import com.wuming.android.tv.ui.activity.MemoryTestActivity

/** Keep a verification-only root underneath ActivityScenario screens on restrictive boxes. */
class ForegroundSmokeRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        // AndroidX otherwise finishes the host before reporting final test results;
        // this firmware kills the process in that gap, losing successful results.
        arguments.putString("waitForActivitiesToComplete", "false")
        super.onCreate(arguments)
    }

    override fun onStart() {
        val run = InstrumentationRegistry.getArguments().getString("smoke_run_id")
        if (run != null) {
            val marker = File(targetContext.cacheDir, "foreground-ready-" + run)
            marker.writeText("ready")
            val ack = File(marker.path + ".ack")
            val deadline = android.os.SystemClock.elapsedRealtime() + 15000
            while (!ack.exists() && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(50)
            }
            check(ack.exists()) { "Foreground startup handshake timed out" }
            marker.delete()
            ack.delete()
        }
        val host = startActivitySync(Intent(targetContext, MemoryTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        waitForIdleSync()
        check(!host.isFinishing) { "Smoke foreground host failed to start" }
        super.onStart()
    }
}
