package com.wuming.tv.desktop.cast

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class MpvCastPlayer
@Throws(Exception::class)
internal constructor(
    executable: Path?,
    extraOptions: List<String>?
) : CastPlayer {
    private val directory: Path
    private val process: Process
    private val channel: SocketChannel
    private val requests = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
    private val sequence = AtomicLong()
    private val properties = ConcurrentHashMap<String, JsonElement>()

    @Volatile private var loading = false

    @Volatile private var failed = false

    @Volatile private var closed = false

    @Throws(Exception::class)
    constructor(executable: Path?) : this(executable, emptyList())

    init {
        directory = Files.createTempDirectory(
            "tvbox-cast-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        )
        var started: Process? = null
        var connected: SocketChannel? = null
        try {
            val socket = directory.resolve("player.sock")
            val arguments =
                arrayListOf(
                    executable!!.toString(), "--no-config", "--load-scripts=no", "--ytdl=no",
                    "--idle=yes", "--keep-open=yes", "--force-window=yes",
                    "--title-bar=no", "--border=no", "--window-dragging=yes", "--osc=yes",
                    "--input-ipc-server=$socket"
                )
            arguments.addAll(extraOptions!!)
            started = ProcessBuilder(arguments)
                .redirectOutput(
                    ProcessBuilder.Redirect.DISCARD
                ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (started.isAlive && connected == null && System.nanoTime() < deadline) {
                val attempt = SocketChannel.open(StandardProtocolFamily.UNIX)
                try {
                    attempt.connect(UnixDomainSocketAddress.of(socket))
                    connected = attempt
                } catch (error: IOException) {
                    attempt.close()
                    Thread.sleep(25)
                }
            }
            if (connected == null) throw IOException("mpv did not start its casting control socket")
            process = started
            channel = connected
            Thread(::read, "tvbox-cast-mpv").apply {
                isDaemon = true
                start()
            }
            var id = 1
            for (property in listOf(
                "pause",
                "idle-active",
                "eof-reached",
                "time-pos",
                "duration",
                "volume",
                "mute"
            )) {
                command("observe_property", id++, property)
            }
        } catch (error: Exception) {
            connected?.close()
            started?.destroyForcibly()
            Files.deleteIfExists(directory.resolve("player.sock"))
            Files.deleteIfExists(directory)
            throw error
        }
    }

    private fun read() {
        try {
            BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val message = JsonParser.parseString(line).asJsonObject
                    if (message.has(
                            "request_id"
                        )
                    ) {
                        requests[message.get("request_id").asLong]?.complete(message)
                    }
                    val event = if (message.has("event")) message.get("event").asString else ""
                    if (event == "property-change" && message.has("name")) {
                        val name = message.get("name").asString
                        val data = message.get("data")
                        if (data == null ||
                            data.isJsonNull
                        ) {
                            properties.remove(name)
                        } else {
                            properties[name] = data
                        }
                    }
                    if (event == "file-loaded") loading = false
                    if (event == "end-file") {
                        loading = false
                        failed = message.has("reason") && message.get("reason").asString == "error"
                    }
                }
            }
        } catch (_: Exception) {
            // Pending commands fail below; no media URL or credentials enter logs.
        } finally {
            requests.values.forEach {
                it.completeExceptionally(IOException("Casting player disconnected"))
            }
        }
    }

    private fun command(vararg values: Any?): JsonObject {
        if (closed ||
            !process.isAlive
        ) {
            throw IllegalStateException("Casting player is closed; restart receiving")
        }
        val id = sequence.incrementAndGet()
        val reply = CompletableFuture<JsonObject>()
        requests[id] = reply
        try {
            val message = JsonObject()
            message.add("command", Gson().toJsonTree(values))
            message.addProperty("request_id", id)
            val bytes = StandardCharsets.UTF_8.encode("$message\n")
            synchronized(channel) { while (bytes.hasRemaining()) channel.write(bytes) }
            val response = reply.get(4, TimeUnit.SECONDS)
            if (response.get("error").asString !=
                "success"
            ) {
                throw IllegalStateException("mpv rejected the playback command")
            }
            return response
        } catch (error: Exception) {
            throw IllegalStateException("Casting player command failed", error)
        } finally {
            requests.remove(id)
        }
    }

    @Synchronized override fun load(uri: String) {
        CastUris.requireHttp(uri)
        failed = false
        loading = true
        try {
            command("loadfile", uri, "replace")
            command("set_property", "pause", true)
        } catch (
            error: RuntimeException
        ) {
            loading = false
            throw error
        }
    }

    @Synchronized override fun play() {
        if (bool("eof-reached")) command("seek", 0, "absolute")
        command("set_property", "pause", false)
    }

    @Synchronized override fun pause() {
        command("set_property", "pause", true)
    }

    @Synchronized override fun stop() {
        command("stop")
        loading = false
    }

    @Synchronized override fun seek(seconds: Double) {
        command("seek", seconds, "absolute")
    }

    @Synchronized override fun volume(volume: Int) {
        command("set_property", "volume", volume.coerceIn(0, 100))
    }

    @Synchronized override fun mute(mute: Boolean) {
        command("set_property", "mute", mute)
    }
    private fun bool(key: String): Boolean = properties[key]?.asBoolean ?: false
    private fun number(key: String): Double = properties[key]?.asDouble ?: 0.0
    override fun snapshot(): CastPlayer.Snapshot {
        val state = when {
            failed || closed || !process.isAlive -> "ERROR"
            loading -> "TRANSITIONING"
            bool("idle-active") || bool("eof-reached") -> "STOPPED"
            bool("pause") -> "PAUSED_PLAYBACK"
            else -> "PLAYING"
        }
        return CastPlayer.Snapshot(
            state,
            number("time-pos"),
            number("duration"),
            number("volume").toInt(),
            bool("mute")
        )
    }

    @Synchronized override fun close() {
        closed = true
        try {
            channel.close()
        } catch (_: IOException) { }
        process.destroy()
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        } catch (
            _: InterruptedException
        ) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
        }
        try {
            Files.deleteIfExists(directory.resolve("player.sock"))
            Files.deleteIfExists(directory)
        } catch (
            _: IOException
        ) { }
    }
}
