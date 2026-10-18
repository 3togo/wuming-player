package com.wuming.tv.core.media

/** A bounded, side-effect-free decoder for one family of external inputs. */
fun interface InputDecoder {
    fun decode(input: String?): DecodeResult
}
