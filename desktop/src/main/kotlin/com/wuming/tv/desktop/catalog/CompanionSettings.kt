package com.wuming.tv.desktop.catalog

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties

data class CompanionConnection(val address: String, val token: String)

class CompanionSettings(
    private val file: Path = Path.of(
        System.getProperty("user.home"),
        ".config",
        "tvbox-desktop",
        "companion.properties",
    ),
) {

    fun load(): CompanionConnection? {
        if (!Files.isRegularFile(file)) return null
        val properties = Properties()
        Files.newInputStream(file).use(properties::load)
        val address = properties.getProperty("address", "").trim()
        val token = properties.getProperty("token", "").trim()
        return if (address.isBlank() || !token.matches(Regex("[0-9a-f]{64}"))) null
        else CompanionConnection(address, token)
    }

    fun save(connection: CompanionConnection) {
        require(connection.token.matches(Regex("[0-9a-f]{64}"))) { "Invalid pairing token" }
        Files.createDirectories(file.parent)
        val properties = Properties().apply {
            setProperty("address", connection.address)
            setProperty("token", connection.token)
        }
        Files.newOutputStream(
            file,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { properties.store(it, "TVBox Desktop companion pairing") }
        runCatching {
            Files.setPosixFilePermissions(file, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }
    }
}
