package com.wuming.tv.core.media

import java.util.Locale
import java.util.UUID

/**
 * Stable identifier for an OpenDrive media resource.
 *
 * The URI identifies a catalog resource, not a provider URL or playback
 * session. Provider and format details belong to the resource manifest, which
 * allows them to evolve without changing this identifier.
 */
class OpenDriveUri(authority: String?, kind: Kind?, resourceId: String?) {

    private val authority: String = normalizeAuthority(authority)
    private val kind: Kind = kind ?: throw NullPointerException("OpenDrive resource kind is required")
    private val resourceId: String = (resourceId
        ?: throw NullPointerException("OpenDrive resource ID is required")).also {
        require(validResourceId(it)) { "OpenDrive resource ID is invalid" }
    }

    /** Java-record-compatible accessors retained for existing Android callers. */
    fun authority(): String = authority

    fun kind(): Kind = kind

    fun resourceId(): String = resourceId

    override fun toString(): String = "$SCHEME://$authority/${kind.value()}/$resourceId"

    override fun equals(other: Any?): Boolean =
        this === other || other is OpenDriveUri &&
            authority == other.authority && kind == other.kind && resourceId == other.resourceId

    override fun hashCode(): Int {
        var result = authority.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + resourceId.hashCode()
        return result
    }

    enum class Kind(private val serializedValue: String) {
        ASSET("asset"),
        COLLECTION("collection"),
        LIVE("live");

        fun value(): String = serializedValue

        companion object {
            internal fun parse(value: String, start: Int, end: Int): Kind {
                for (kind in entries) {
                    val serializedValue = kind.value()
                    if (end - start == serializedValue.length &&
                        value.regionMatches(
                            start,
                            serializedValue,
                            0,
                            serializedValue.length,
                        )
                    ) {
                        return kind
                    }
                }
                throw OpenDriveUri.invalid()
            }
        }
    }

    companion object {
        const val SCHEME: String = "opendrive"
        const val LOCAL_AUTHORITY: String = "local"

        private const val URI_PREFIX: String = "$SCHEME://"

        @JvmStatic
        fun local(kind: Kind): OpenDriveUri = OpenDriveUri(
            LOCAL_AUTHORITY,
            kind,
            UUID.randomUUID().toString().lowercase(Locale.ROOT),
        )

        @JvmStatic
        fun parse(value: String?): OpenDriveUri {
            if (value.isNullOrBlank() || value.length > 512) throw invalid()
            if (!value.startsWith(URI_PREFIX)) throw invalid()
            val authorityStart = URI_PREFIX.length
            val kindStart = value.indexOf('/', authorityStart)
            if (kindStart <= authorityStart) throw invalid()
            val resourceStart = value.indexOf('/', kindStart + 1)
            if (resourceStart <= kindStart + 1 || resourceStart == value.length - 1 ||
                value.indexOf('/', resourceStart + 1) >= 0
            ) {
                throw invalid()
            }
            val authority = value.substring(authorityStart, kindStart)
            if (!validAuthority(authority)) throw invalid()
            val kind = Kind.parse(value, kindStart + 1, resourceStart)
            val resourceId = value.substring(resourceStart + 1)
            if (!validResourceId(resourceId)) throw invalid()
            return OpenDriveUri(authority, kind, resourceId)
        }

        @JvmStatic
        fun isValid(value: String?): Boolean {
            if (value == null || !value.startsWith(URI_PREFIX)) return false
            return try {
                parse(value)
                true
            } catch (_: IllegalArgumentException) {
                false
            }
        }

        @JvmStatic
        fun hasScheme(value: String?): Boolean {
            val prefix = "$SCHEME:"
            return value != null && value.regionMatches(
                0,
                prefix,
                0,
                prefix.length,
                ignoreCase = true,
            )
        }

        private fun normalizeAuthority(value: String?): String {
            val normalized = (value
                ?: throw NullPointerException("OpenDrive catalog authority is required"))
                .lowercase(Locale.ROOT)
            require(validAuthority(normalized)) { "OpenDrive catalog authority is invalid" }
            return normalized
        }

        private fun validAuthority(value: String): Boolean {
            val length = value.length
            if (length == 0 || length > 128 || !asciiLowerOrDigit(value[0]) ||
                !asciiLowerOrDigit(value[length - 1])
            ) {
                return false
            }
            var previous = '\u0000'
            for (index in 1 until length - 1) {
                val current = value[index]
                if (!asciiLowerOrDigit(current) && current != '.' && current != '-') return false
                if (current == '.' && previous == '.') return false
                previous = current
            }
            return true
        }

        private fun validResourceId(value: String): Boolean {
            val length = value.length
            if (length == 0 || length > 128 || !asciiAlphaNumeric(value[0])) return false
            for (index in 1 until length) {
                val current = value[index]
                if (!asciiAlphaNumeric(current) && current != '.' && current != '_' &&
                    current != '~' && current != '-'
                ) {
                    return false
                }
            }
            return true
        }

        private fun asciiLowerOrDigit(value: Char): Boolean =
            value in 'a'..'z' || value in '0'..'9'

        private fun asciiAlphaNumeric(value: Char): Boolean =
            value in 'a'..'z' || value in 'A'..'Z' || value in '0'..'9'

        private fun invalid(): IllegalArgumentException =
            IllegalArgumentException("OpenDrive URI is invalid")
    }
}
