package com.wuming.tv.desktop.player

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wuming.tv.desktop.config.DesktopMpvConfiguration
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Observes effective mpv values, rather than assuming comma/list option semantics from argv. */
class MpvConfigurationIntegrationTest {
    @Test fun realPlayerPreservesUserLanguageAndAppliesImportedOverrides() {
        val executable = Path.of("/usr/bin/mpv")
        assumeTrue("mpv executable unavailable", Files.isExecutable(executable))
        val directory = Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("user.home"), "temp")), "mpv-config-ipc-")
        try {
            val config = DesktopMpvConfiguration(directory.resolve("mpv.conf"))
            fun observe(): Pair<List<String>, Boolean> {
                val socket = directory.resolve("player.sock")
                Files.deleteIfExists(socket)
                val adapter = ExternalPlayerAdapter(executable, ProcessStarter { null }, mpvConfiguration = config,
                    subtitleLocale = { Locale.forLanguageTag("zh-TW") })
                val command = adapter.command("null://", emptyMap(), mpvSocketPath = socket).toMutableList()
                // Isolate normal config, then establish a pre-existing user preference before app defaults.
                command.addAll(1, listOf("--no-config", "--slang=fr"))
                command.addAll(command.size - 1, listOf("--vo=null", "--ao=null"))
                val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve("player.log").toFile()).start()
                try {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (!Files.exists(socket) && process.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
                    assertTrue("mpv did not start: ${Files.readString(directory.resolve("player.log"))}", Files.exists(socket))
                    SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                        channel.connect(UnixDomainSocketAddress.of(socket))
                        channel.configureBlocking(false)
                        val pending = StringBuilder()
                        fun get(property: String, id: Int): JsonObject {
                            val bytes = ByteBuffer.wrap(("{\"command\":[\"get_property\",\"$property\"],\"request_id\":$id}\n").toByteArray())
                            while (bytes.hasRemaining()) channel.write(bytes)
                            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                            val input = ByteBuffer.allocate(8192)
                            while (System.nanoTime() < until) {
                                input.clear()
                                val size = channel.read(input)
                                if (size > 0) pending.append(String(input.array(), 0, size, Charsets.UTF_8))
                                while (pending.indexOf("\n") >= 0) {
                                    val end = pending.indexOf("\n")
                                    val line = pending.substring(0, end)
                                    pending.delete(0, end + 1)
                                    val result = JsonParser.parseString(line).asJsonObject
                                    if (result.get("request_id")?.asInt == id) {
                                        assertEquals("success", result.get("error").asString)
                                        return result
                                    }
                                }
                                if (size < 0) error("mpv closed IPC")
                                Thread.sleep(10)
                            }
                            error("mpv did not answer $property")
                        }
                        return get("slang", 1).getAsJsonArray("data").map { it.asString } to get("border", 2).get("data").asBoolean
                    }
                } finally {
                    process.destroy()
                    if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(3, TimeUnit.SECONDS)
                    Files.deleteIfExists(socket)
                }
            }
            assertEquals(listOf("fr", "zh-TW", "zh-Hant", "zh") to false, observe())
            config.save("slang=ja\nborder=yes\n")
            assertEquals(listOf("ja") to false, observe())
        } finally { directory.toFile().deleteRecursively() }
    }
}
