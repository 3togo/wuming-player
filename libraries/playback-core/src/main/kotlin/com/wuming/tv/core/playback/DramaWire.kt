package com.wuming.tv.core.playback

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Wire encodings for anonymous drama-service registration and request authentication. */
internal object DramaWire {
    private val tables by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/com/wuming/tv/core/playback/drama-wire-tables.json"))
        stream.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
    }
    fun table(name: String): IntArray = tables.getAsJsonArray(name).map { it.asInt }.toIntArray()
    fun digest(name: String, bytes: ByteArray): ByteArray = MessageDigest.getInstance(name).digest(bytes)
    fun le(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    fun le(value: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
    fun long(bytes: ByteArray, offset: Int): Long = ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long
    fun xor(value: ByteArray, key: ByteArray): ByteArray = ByteArray(value.size) { (value[it].toInt() xor key[it % key.size].toInt()).toByte() }
    fun join(vararg values: ByteArray): ByteArray = ByteArrayOutputStream().apply { values.forEach { write(it) } }.toByteArray()

    fun message(vararg fields: Pair<Int, Any>): ByteArray = ByteArrayOutputStream().apply {
        fun varint(input: Long) {
            var value = input
            while (value and -128L != 0L) { write((value.toInt() and 127) or 128); value = value ushr 7 }
            write(value.toInt())
        }
        for ((number, value) in fields) {
            if (value is Number) {
                varint(number.toLong() shl 3)
                val signed = value.toLong()
                varint((signed shl 1) xor (signed shr 63))
            } else {
                val bytes = if (value is ByteArray) value else value.toString().toByteArray(Charsets.UTF_8)
                if (bytes.isNotEmpty()) {
                    varint((number.toLong() shl 3) or 2); varint(bytes.size.toLong()); write(bytes)
                }
            }
        }
    }.toByteArray()

    /** SM3, including multi-block padding; kept local to this service protocol. */
    fun sm3(bytes: ByteArray): ByteArray {
        val padded = bytes.copyOf(((bytes.size + 9 + 63) / 64) * 64)
        padded[bytes.size] = 0x80.toByte()
        ByteBuffer.wrap(padded, padded.size - 8, 8).putLong(bytes.size.toLong() * 8)
        val state = intArrayOf(0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600.toInt(),
            0xa96f30bc.toInt(), 0x163138aa, 0xe38dee4d.toInt(), 0xb0fb0e4e.toInt())
        fun rotate(value: Int, count: Int) = Integer.rotateLeft(value, count)
        for (offset in padded.indices step 64) {
            val words = IntArray(68)
            val input = ByteBuffer.wrap(padded, offset, 64)
            for (i in 0..15) words[i] = input.int
            for (i in 16..67) {
                val x = words[i - 16] xor words[i - 9] xor rotate(words[i - 3], 15)
                words[i] = x xor rotate(x, 15) xor rotate(x, 23) xor rotate(words[i - 13], 7) xor words[i - 6]
            }
            val v = state.clone()
            for (i in 0..63) {
                val a12 = rotate(v[0], 12)
                val s1 = rotate(a12 + v[4] + rotate(if (i < 16) 0x79cc4519 else 0x7a879d8a, i), 7)
                val f = if (i < 16) v[0] xor v[1] xor v[2] else (v[0] and v[1]) or (v[0] and v[2]) or (v[1] and v[2])
                val g = if (i < 16) v[4] xor v[5] xor v[6] else (v[4] and v[5]) or (v[4].inv() and v[6])
                val t1 = f + v[3] + (s1 xor a12) + (words[i] xor words[i + 4])
                val t2 = g + v[7] + s1 + words[i]
                v[3] = v[2]; v[2] = rotate(v[1], 9); v[1] = v[0]; v[0] = t1
                v[7] = v[6]; v[6] = rotate(v[5], 19); v[5] = v[4]
                v[4] = t2 xor rotate(t2, 9) xor rotate(t2, 17)
            }
            for (i in state.indices) state[i] = state[i] xor v[i]
        }
        return ByteBuffer.allocate(32).apply { state.forEach { putInt(it) } }.array()
    }
}
