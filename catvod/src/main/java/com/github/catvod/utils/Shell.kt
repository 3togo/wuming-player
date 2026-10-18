package com.github.catvod.utils

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object Shell {

    private val TAG: String = Shell::class.java.simpleName
    private const val TIMEOUT_SECONDS = 30L

    @JvmStatic
    fun exec(command: String): String {
        var p: Process? = null
        return try {
            val sb = StringBuilder()
            val pb = ProcessBuilder("sh", "-c", command)
            pb.redirectErrorStream(true)
            p = pb.start()
            BufferedReader(InputStreamReader(p.inputStream)).use { br ->
                var line = br.readLine()
                while (line != null) {
                    sb.append(line).append("\n")
                    line = br.readLine()
                }
            }
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Shell command timed out after ${TIMEOUT_SECONDS}s: $command")
                p.destroyForcibly()
                return ""
            }
            Log.d(TAG, "Shell command '$command' with exit code '${p.exitValue()}'")
            Util.substring(sb.toString())!!
        } catch (e: Exception) {
            Log.e(TAG, "Shell command failed", e)
            ""
        } finally {
            p?.destroy()
        }
    }
}
