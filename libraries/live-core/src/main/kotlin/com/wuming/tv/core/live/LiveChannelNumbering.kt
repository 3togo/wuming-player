package com.wuming.tv.core.live

/**
 * How a live list numbers the channels a playlist never named. Channel-by-number tuning and the
 * on-screen list both need an ordinal, and both platforms must show the same one, so the format lives
 * here instead of in either port.
 */
object LiveChannelNumbering {

    /** The label of the [ordinal]th unnumbered channel, zero-padded like every live list in the wild. */
    @JvmStatic
    fun label(ordinal: Int): String = ordinal.toString().padStart(3, '0')
}
