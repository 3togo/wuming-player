package com.wuming.tv.desktop.cast

interface CastPlayer : AutoCloseable {
    data class Snapshot(val state: String, val position: Double, val duration: Double, val volume: Int, val muted: Boolean)
    fun load(uri: String)
    fun play()
    fun pause()
    fun stop()
    fun seek(seconds: Double)
    fun volume(volume: Int)
    fun mute(mute: Boolean)
    fun snapshot(): Snapshot
    override fun close()
}
