package com.wuming.tv.core.media

/** Explicit decoder outcome, preventing malformed inputs from falling through silently. */
class DecodeResult(
    val status: Status,
    val media: DecodedMedia?,
    message: String?
) {
    val message: String = message ?: ""

    enum class Status { NO_MATCH, DECODED, INVALID }

    init {
        if (status == Status.DECODED && media == null) {
            throw IllegalArgumentException("Decoded media is required")
        }
        if (status != Status.DECODED && media != null) {
            throw IllegalArgumentException("Unexpected decoded media")
        }
    }

    @JvmName("status")
    fun statusAccessor(): Status = status
    @JvmName("media")
    fun mediaAccessor(): DecodedMedia? = media
    @JvmName("message")
    fun messageAccessor(): String = message

    companion object {
        @JvmStatic
        fun noMatch(): DecodeResult = DecodeResult(Status.NO_MATCH, null, "")

        @JvmStatic
        fun decoded(media: DecodedMedia): DecodeResult = DecodeResult(Status.DECODED, media, "")

        @JvmStatic
        fun invalid(message: String?): DecodeResult = DecodeResult(Status.INVALID, null, message)
    }
}
