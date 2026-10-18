package com.wuming.android.tv.server.process

internal object RemoteCastCommandTracker {
    const val SESSION_TTL_MILLIS = com.wuming.tv.core.upnp.RemoteCastCommandTracker.SESSION_TTL_MILLIS
    @JvmStatic fun accept(sessionId: String?, now: Long) = com.wuming.tv.core.upnp.RemoteCastCommandTracker.accept(sessionId, now)
    @JvmStatic fun canStop(sessionId: String?) = com.wuming.tv.core.upnp.RemoteCastCommandTracker.canStop(sessionId)
    @JvmStatic fun stopped(sessionId: String?) = com.wuming.tv.core.upnp.RemoteCastCommandTracker.stopped(sessionId)
    @JvmStatic fun resetForTest() = com.wuming.tv.core.upnp.RemoteCastCommandTracker.resetForTest()
}
