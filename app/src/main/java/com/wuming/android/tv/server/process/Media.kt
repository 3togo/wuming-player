package com.wuming.android.tv.server.process

import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.wuming.android.tv.player.PlayerManager
import com.wuming.android.tv.server.Nano
import com.wuming.android.tv.server.Server
import com.wuming.android.tv.server.impl.Process
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response

open class Media : Process {
    override fun isRequest(
        session: IHTTPSession,
        url: String,
    ): Boolean = url.startsWith("/media")

    override fun doResponse(
        session: IHTTPSession,
        url: String,
        files: Map<String, String>,
    ): Response {
        val service = Server.get().service ?: return Nano.ok("{}")
        // Teardown may clear the player between the service and player reads.
        val player = service.player() ?: return Nano.ok("{}")
        val item = player.getCurrentMediaItem()
        val meta = if (item != null) item.mediaMetadata else MediaMetadata.EMPTY
        val result = JsonObject()
        result.addProperty("state", getState(player))
        result.addProperty("speed", player.getSpeed())
        result.addProperty("duration", player.getDuration())
        result.addProperty("position", player.getPosition())
        result.addProperty("url", getString(player.getUrl()))
        result.addProperty("title", getString(meta.title))
        result.addProperty("artist", getString(meta.artist))
        result.addProperty("artwork", getString(meta.artworkUri))
        return Nano.ok(result.toString())
    }

    private fun getState(player: PlayerManager): Int {
        if (player.isPlaying()) return 3
        val state = player.getPlaybackState()
        if (state == Player.STATE_BUFFERING) return 6
        if (state == Player.STATE_READY) return 2
        return 1
    }

    private fun getString(text: CharSequence?): String = text?.toString() ?: ""

    private fun getString(uri: Uri?): String = uri?.toString() ?: ""
}
