package com.wuming.tv.core.cloud

/** Shared task-state contract: destination IDs can appear before completion or on failure. */
object QuarkSavePolicy {
    enum class State { PENDING, COMPLETE, QUOTA_EXHAUSTED, FAILED, MISSING_FILE }

    @JvmStatic
    fun state(status: Long, file: String?, remaining: Long, required: Long): State = when {
        status < 2 -> State.PENDING
        status >= 3 && required > 0 && remaining < required -> State.QUOTA_EXHAUSTED
        status >= 3 -> State.FAILED
        file.isNullOrBlank() -> State.MISSING_FILE
        else -> State.COMPLETE
    }
}
