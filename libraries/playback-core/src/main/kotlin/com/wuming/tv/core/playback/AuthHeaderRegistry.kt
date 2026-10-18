package com.wuming.tv.core.playback

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

class AuthHeaderRegistry private constructor() {

    companion object {
        private const val MAX_METADATA_BYTES = 256 * 1024
        private const val MAX_HEADERS = 256
        private const val MAX_FIELD_BYTES = 64 * 1024
        private val REGISTRY = ConcurrentHashMap<String, Map<String, String>>()

        @JvmStatic
        fun register(uri: String, headers: Map<String, String>) {
            REGISTRY[uri] = Collections.unmodifiableMap(LinkedHashMap(headers))
        }

        @JvmStatic
        fun lookup(uri: String): Map<String, String>? = REGISTRY[uri]

        @JvmStatic
        fun unregister(uri: String) {
            REGISTRY.remove(uri)
        }

        @JvmStatic
        fun serialize(headers: Map<String, String>): ByteArray {
            return try {
                ByteArrayOutputStream().use { baos ->
                    DataOutputStream(baos).use { dos ->
                        dos.writeInt(headers.size)
                        for ((key, value) in headers) {
                            val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
                            val valueBytes = value.toByteArray(StandardCharsets.UTF_8)
                            dos.writeInt(keyBytes.size)
                            dos.write(keyBytes)
                            dos.writeInt(valueBytes.size)
                            dos.write(valueBytes)
                        }
                        baos.toByteArray()
                    }
                }
            } catch (impossible: IOException) {
                throw AssertionError(impossible)
            }
        }

        @JvmStatic
        fun deserialize(data: ByteArray?): Map<String, String> {
            if (data == null || data.isEmpty() || data.size > MAX_METADATA_BYTES) return Collections.emptyMap()
            return try {
                ByteArrayInputStream(data).use { bais ->
                    DataInputStream(bais).use { dis ->
                        val count = dis.readInt()
                        if (count < 0 || count > MAX_HEADERS || count > dis.available() / 8) return Collections.emptyMap()
                        val headers = LinkedHashMap<String, String>(count)
                        for (i in 0 until count) {
                            val keyLen = dis.readInt()
                            if (keyLen < 0 || keyLen > MAX_FIELD_BYTES || keyLen > dis.available() - 4) return Collections.emptyMap()
                            val keyBytes = ByteArray(keyLen)
                            dis.readFully(keyBytes)
                            val valueLen = dis.readInt()
                            if (valueLen < 0 || valueLen > MAX_FIELD_BYTES || valueLen > dis.available()) return Collections.emptyMap()
                            val valueBytes = ByteArray(valueLen)
                            dis.readFully(valueBytes)
                            headers[String(keyBytes, StandardCharsets.UTF_8)] =
                                String(valueBytes, StandardCharsets.UTF_8)
                        }
                        if (dis.available() != 0) return Collections.emptyMap()
                        Collections.unmodifiableMap(headers)
                    }
                }
            } catch (e: IOException) {
                Collections.emptyMap()
            }
        }
    }
}
