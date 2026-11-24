package com.wuming.tv.core.catalog

/** One retry per clock correction, only for a failed request. Host serializes calls. */
class ClockCorrectionRecovery {
    private var running = false
    private var failed = false
    private var correctedDuringRequest = false

    fun started() {
        running = true
        failed = false
        correctedDuringRequest = false
    }

    fun clockCorrected(): Boolean {
        if (running) correctedDuringRequest = true
        val retry = failed
        failed = false
        return retry
    }

    fun completed(failure: Boolean): Boolean {
        running = false
        failed = failure
        val retry = failure && correctedDuringRequest
        correctedDuringRequest = false
        if (retry) failed = false
        return retry
    }

    fun cancel() {
        running = false
        failed = false
        correctedDuringRequest = false
    }
}
