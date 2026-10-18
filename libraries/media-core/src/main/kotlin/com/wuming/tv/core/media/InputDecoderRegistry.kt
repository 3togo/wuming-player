package com.wuming.tv.core.media

/** Ordered decoder registry. Invalid matches stop routing instead of falling through. */
class InputDecoderRegistry(decoders: List<InputDecoder>?) {
    private val decoders: List<InputDecoder> = decoders?.toList() ?: emptyList()

    fun decode(input: String?): DecodeResult {
        for (decoder in decoders) {
            val result = decoder.decode(input)
            if (result.status != DecodeResult.Status.NO_MATCH) return result
        }
        return DecodeResult.noMatch()
    }
}
