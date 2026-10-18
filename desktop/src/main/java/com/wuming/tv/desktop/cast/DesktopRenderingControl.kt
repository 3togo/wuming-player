package com.wuming.tv.desktop.cast

import org.fourthline.cling.model.types.UnsignedIntegerFourBytes
import org.fourthline.cling.model.types.UnsignedIntegerTwoBytes
import org.fourthline.cling.support.model.Channel
import org.fourthline.cling.support.renderingcontrol.AbstractAudioRenderingControl
import org.fourthline.cling.support.renderingcontrol.RenderingControlException
import org.fourthline.cling.support.renderingcontrol.lastchange.ChannelMute
import org.fourthline.cling.support.renderingcontrol.lastchange.ChannelVolume
import org.fourthline.cling.support.renderingcontrol.lastchange.RenderingControlVariable

class DesktopRenderingControl(
    private val player: CastPlayer?,
) : AbstractAudioRenderingControl() {
    private var previous: CastPlayer.Snapshot? = null

    private fun validate(
        id: UnsignedIntegerFourBytes?,
        channel: String?,
    ) {
        if (id!!.value != 0L) throw RenderingControlException(702, "Invalid instance ID")
        if (channel != "Master") throw RenderingControlException(402, "Only Master channel is supported")
    }

    override fun getCurrentInstanceIds(): Array<UnsignedIntegerFourBytes> = arrayOf(getDefaultInstanceID())

    protected override fun getCurrentChannels(): Array<Channel> = arrayOf(Channel.Master)

    @Throws(RenderingControlException::class)
    override fun getMute(
        id: UnsignedIntegerFourBytes?,
        channel: String?,
    ): Boolean {
        validate(id, channel)
        return player!!.snapshot().muted
    }

    @Throws(RenderingControlException::class)
    override fun setMute(
        id: UnsignedIntegerFourBytes?,
        channel: String?,
        value: Boolean,
    ) {
        validate(id, channel)
        player!!.mute(value)
        refresh()
    }

    @Throws(RenderingControlException::class)
    override fun getVolume(
        id: UnsignedIntegerFourBytes?,
        channel: String?,
    ): UnsignedIntegerTwoBytes {
        validate(id, channel)
        return UnsignedIntegerTwoBytes(
            player!!
                .snapshot()
                .volume
                .coerceIn(0, 100)
                .toLong(),
        )
    }

    @Throws(RenderingControlException::class)
    override fun setVolume(
        id: UnsignedIntegerFourBytes?,
        channel: String?,
        value: UnsignedIntegerTwoBytes?,
    ) {
        validate(id, channel)
        if (value!!.value > 100) throw RenderingControlException(601, "Volume must be 0 to 100")
        player!!.volume(value.value.toInt())
        refresh()
    }

    @Synchronized
    fun refresh() {
        val now = player!!.snapshot()
        val before = previous
        if (before != null && before.volume == now.volume && before.muted == now.muted) return
        previous = now
        lastChange.setEventedValue(
            getDefaultInstanceID(),
            RenderingControlVariable.Volume(ChannelVolume(Channel.Master, now.volume)),
            RenderingControlVariable.Mute(ChannelMute(Channel.Master, now.muted)),
        )
    }
}
