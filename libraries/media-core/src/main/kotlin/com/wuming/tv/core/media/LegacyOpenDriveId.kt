package com.wuming.tv.core.media

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Strict read-only parser for identifiers emitted by the legacy OpenDrive spider. */
class LegacyOpenDriveId(
    val kind: Kind,
    val providerId: String,
    val itemId: String
) {
    enum class Kind(val prefix: String) {
        FILE("opendrive-file:"),
        FOLDER("opendrive-folder:"),
        SHARE("opendrive-share:");

        companion object {
            @JvmStatic
            fun fromPrefix(value: String): Kind {
                for (kind in entries) if (value.startsWith(kind.prefix)) return kind
                throw invalid()
            }
        }
    }

    @JvmName("kind")
    fun kindAccessor(): Kind = kind
    @JvmName("providerId")
    fun providerIdAccessor(): String = providerId
    @JvmName("itemId")
    fun itemIdAccessor(): String = itemId

    companion object {
        private const val MAX_LENGTH = 64 * 1024
        private val ID_DECODER = Base64.getUrlDecoder()

        @JvmStatic
        fun parse(value: String?): LegacyOpenDriveId {
            if (value == null || value.isBlank() || value.length > MAX_LENGTH) throw invalid()
            val kind = Kind.fromPrefix(value)
            val providerStart = kind.prefix.length
            val separator = value.indexOf(':', providerStart)
            if (separator == providerStart || separator == value.length - 1) throw invalid()
            val provider = value.substring(providerStart, separator)
            if (!validProviderId(provider)) throw invalid()
            try {
                val item = String(ID_DECODER.decode(value.substring(separator + 1)),
                    StandardCharsets.UTF_8)
                if (item.isBlank() || item.length > MAX_LENGTH || hasControl(item)) throw invalid()
                return LegacyOpenDriveId(kind, provider, item)
            } catch (_: RuntimeException) {
                throw invalid()
            }
        }

        @JvmStatic
        fun isLegacy(value: String?): Boolean {
            if (!hasLegacyPrefix(value)) return false
            try {
                parse(value)
                return true
            } catch (_: IllegalArgumentException) {
                return false
            }
        }

        @JvmStatic
        fun hasLegacyPrefix(value: String?): Boolean {
            if (value == null) return false
            for (kind in Kind.entries) if (value.startsWith(kind.prefix)) return true
            return false
        }

        private fun hasControl(value: String): Boolean {
            for (c in value) if (c.isISOControl()) return true
            return false
        }

        private fun validProviderId(value: String): Boolean {
            val length = value.length
            if (length == 0 || length > 32 || !lowerOrDigit(value[0])) return false
            for (i in 1 until length) {
                val current = value[i]
                if (!lowerOrDigit(current) && current != '_' && current != '-') return false
            }
            return true
        }

        private fun lowerOrDigit(value: Char): Boolean =
            value in 'a'..'z' || value in '0'..'9'

        private fun invalid(): IllegalArgumentException =
            IllegalArgumentException("Legacy OpenDrive resource ID is invalid")
    }
}
