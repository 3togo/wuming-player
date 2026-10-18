package com.wuming.android.tv.dlna

import android.content.Context
import android.media.AudioManager
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes
import org.fourthline.cling.model.types.UnsignedIntegerTwoBytes
import org.fourthline.cling.support.model.Channel
import org.fourthline.cling.support.renderingcontrol.AbstractAudioRenderingControl

class DLNARenderingControlImpl(private val context: Context) : AbstractAudioRenderingControl() {

    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun getCurrentInstanceIds(): Array<UnsignedIntegerFourBytes> =
        arrayOf(AbstractAudioRenderingControl.getDefaultInstanceID())

    override fun getMute(instanceId: UnsignedIntegerFourBytes, channelName: String): Boolean =
        audioManager.isStreamMute(AudioManager.STREAM_MUSIC)

    override fun setMute(instanceId: UnsignedIntegerFourBytes, channelName: String, desiredMute: Boolean) {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (desiredMute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            0
        )
    }

    override fun getVolume(instanceId: UnsignedIntegerFourBytes, channelName: String): UnsignedIntegerTwoBytes {
        val am = audioManager
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return UnsignedIntegerTwoBytes(if (max > 0) current * 100L / max else 100)
    }

    override fun setVolume(
        instanceId: UnsignedIntegerFourBytes,
        channelName: String,
        desiredVolume: UnsignedIntegerTwoBytes
    ) {
        val manager = audioManager
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volume = (desiredVolume.value * max / 100).toInt()
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
    }

    override fun getCurrentChannels(): Array<Channel> = arrayOf(Channel.Master)
}
