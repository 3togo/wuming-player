package com.wuming.tv.desktop.cast

import org.fourthline.cling.model.types.UnsignedIntegerFourBytes
import org.fourthline.cling.support.avtransport.AVTransportException
import org.fourthline.cling.support.avtransport.AbstractAVTransportService
import org.fourthline.cling.support.avtransport.lastchange.AVTransportVariable
import org.fourthline.cling.support.model.DeviceCapabilities
import org.fourthline.cling.support.model.MediaInfo
import org.fourthline.cling.support.model.PlayMode
import org.fourthline.cling.support.model.PositionInfo
import org.fourthline.cling.support.model.RecordQualityMode
import org.fourthline.cling.support.model.StorageMedium
import org.fourthline.cling.support.model.TransportAction
import org.fourthline.cling.support.model.TransportInfo
import org.fourthline.cling.support.model.TransportSettings
import org.fourthline.cling.support.model.TransportState
import org.fourthline.cling.support.model.TransportStatus
import java.net.URI

/** Single-instance transport. URI changes never auto-play; the controller must send Play. */
class DesktopAvTransport(
    private val player: CastPlayer?,
) : AbstractAVTransportService() {
    private var uri = ""
    private var metadata = ""
    private var stopped = true
    private var closed = false
    private var lastState = ""

    private fun instance(id: UnsignedIntegerFourBytes?) {
        if (id!!.value != 0L) throw AVTransportException(718, "Invalid instance ID")
        if (closed) throw AVTransportException(701, "Receiving is disabled")
    }

    private fun media(id: UnsignedIntegerFourBytes?) {
        instance(id)
        if (uri.isEmpty()) throw AVTransportException(701, "No media selected")
    }

    private fun execute(action: () -> Unit) {
        try {
            action()
        } catch (error: RuntimeException) {
            throw AVTransportException(501, "Player command failed")
        }
    }

    override fun getCurrentInstanceIds(): Array<UnsignedIntegerFourBytes> = arrayOf(getDefaultInstanceID())

    @Synchronized
    @Throws(AVTransportException::class)
    override fun setAVTransportURI(
        id: UnsignedIntegerFourBytes?,
        value: String?,
        meta: String?,
    ) {
        instance(id)
        try {
            CastUris.requireHttp(value)
        } catch (error: IllegalArgumentException) {
            throw AVTransportException(716, error.message)
        }
        if (meta != null && meta.length > 262144) throw AVTransportException(402, "Metadata too large")
        val target = player!!
        execute(target::stop)
        uri = value!!
        metadata = meta.orEmpty()
        stopped = true
        lastState = ""
        refresh()
    }

    @Throws(AVTransportException::class)
    override fun setNextAVTransportURI(
        id: UnsignedIntegerFourBytes?,
        uri: String?,
        metadata: String?,
    ) {
        instance(id)
        unsupported()
    }

    @Synchronized
    @Throws(AVTransportException::class)
    override fun getMediaInfo(id: UnsignedIntegerFourBytes?): MediaInfo {
        instance(id)
        return MediaInfo(
            uri,
            metadata,
            UnsignedIntegerFourBytes(if (uri.isEmpty()) 0 else 1),
            CastUris.time(player!!.snapshot().duration),
            StorageMedium.NETWORK,
        )
    }

    @Synchronized
    @Throws(AVTransportException::class)
    override fun getTransportInfo(id: UnsignedIntegerFourBytes?): TransportInfo {
        instance(id)
        val snapshot = player!!.snapshot()
        val state =
            when {
                uri.isEmpty() -> TransportState.NO_MEDIA_PRESENT
                stopped || snapshot.state == "ERROR" -> TransportState.STOPPED
                else -> TransportState.valueOf(snapshot.state)
            }
        return TransportInfo(state, if (snapshot.state == "ERROR") TransportStatus.ERROR_OCCURRED else TransportStatus.OK, "1")
    }

    @Synchronized
    @Throws(AVTransportException::class)
    override fun getPositionInfo(id: UnsignedIntegerFourBytes?): PositionInfo {
        instance(id)
        val snapshot = player!!.snapshot()
        val position = CastUris.time(if (stopped) 0.0 else snapshot.position)
        return PositionInfo(
            if (uri.isEmpty()) 0 else 1,
            CastUris.time(snapshot.duration),
            metadata,
            uri,
            position,
            position,
            Int.MAX_VALUE,
            Int.MAX_VALUE,
        )
    }

    @Throws(AVTransportException::class)
    override fun getDeviceCapabilities(id: UnsignedIntegerFourBytes?): DeviceCapabilities {
        instance(id)
        return DeviceCapabilities(arrayOf(StorageMedium.NETWORK))
    }

    @Throws(AVTransportException::class)
    override fun getTransportSettings(id: UnsignedIntegerFourBytes?): TransportSettings {
        instance(id)
        return TransportSettings(PlayMode.NORMAL, RecordQualityMode.NOT_IMPLEMENTED)
    }

    @Synchronized
    @Throws(AVTransportException::class)
    override fun stop(id: UnsignedIntegerFourBytes?) {
        instance(id)
        val target = player!!
        execute(target::stop)
        stopped = true
        refresh()
    }

    @Synchronized
    @Throws(AVTransportException::class)
    override fun play(
        id: UnsignedIntegerFourBytes?,
        speed: String?,
    ) {
        media(id)
        if (speed != "1") throw AVTransportException(717, "Only normal speed is supported")
        execute {
            if (stopped || player!!.snapshot().state == "STOPPED") player!!.load(uri)
            player!!.play()
        }
        stopped = false
        refresh()
    }

    @Synchronized
    @Throws(AVTransportException::class)
    override fun pause(id: UnsignedIntegerFourBytes?) {
        media(id)
        if (stopped) throw AVTransportException(701, "Transport is stopped")
        val target = player!!
        execute(target::pause)
        refresh()
    }

    @Synchronized
    @Throws(AVTransportException::class)
    override fun seek(
        id: UnsignedIntegerFourBytes?,
        unit: String?,
        target: String?,
    ) {
        media(id)
        if (unit!! != "REL_TIME" && unit != "ABS_TIME") throw AVTransportException(710, "Unsupported seek mode")
        val seconds =
            try {
                CastUris.seconds(target!!)
            } catch (
                error: IllegalArgumentException,
            ) {
                throw AVTransportException(711, error.message)
            }
        if (stopped) throw AVTransportException(701, "Play before seeking")
        execute { player!!.seek(seconds) }
    }

    @Throws(AVTransportException::class)
    override fun record(id: UnsignedIntegerFourBytes?) {
        instance(id)
        unsupported()
    }

    @Throws(AVTransportException::class)
    override fun next(id: UnsignedIntegerFourBytes?) {
        instance(id)
        unsupported()
    }

    @Throws(AVTransportException::class)
    override fun previous(id: UnsignedIntegerFourBytes?) {
        instance(id)
        unsupported()
    }

    @Throws(AVTransportException::class)
    override fun setPlayMode(
        id: UnsignedIntegerFourBytes?,
        mode: String?,
    ) {
        instance(id)
        if (mode != "NORMAL") throw AVTransportException(712, "Only normal play mode is supported")
    }

    @Throws(AVTransportException::class)
    override fun setRecordQualityMode(
        id: UnsignedIntegerFourBytes?,
        mode: String?,
    ) {
        instance(id)
        unsupported()
    }

    private fun unsupported(): Nothing = throw AVTransportException(701, "Action not supported")

    @Synchronized
    @Throws(AVTransportException::class)
    protected override fun getCurrentTransportActions(id: UnsignedIntegerFourBytes?): Array<TransportAction> {
        instance(id)
        if (uri.isEmpty()) return emptyArray()
        return if (stopped) {
            arrayOf(
                TransportAction.Play,
            )
        } else {
            arrayOf(TransportAction.Play, TransportAction.Pause, TransportAction.Stop, TransportAction.Seek)
        }
    }

    @Synchronized
    fun refresh() {
        if (closed) return
        try {
            val info = getTransportInfo(getDefaultInstanceID())
            val state = info.currentTransportState.name + info.currentTransportStatus.name
            if (state == lastState) return
            lastState = state
            lastChange.setEventedValue(
                getDefaultInstanceID(),
                AVTransportVariable.TransportState(info.currentTransportState),
                AVTransportVariable.TransportStatus(info.currentTransportStatus),
                AVTransportVariable.AVTransportURI(URI.create(uri)),
            )
        } catch (ignored: Exception) {
            // A later poll retries publication.
        }
    }

    @Synchronized
    fun disable() {
        closed = true
        player!!.close()
    }
}
