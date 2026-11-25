package com.wuming.tv.core.playback

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CencMp4TransformerTest {
    private val key = ByteArray(16) { it.toByte() }
    private val clear = ByteArray(32) { (it + 40).toByte() }
    private fun ints(vararg values: Int) = ByteBuffer.allocate(values.size * 4).apply { values.forEach(::putInt) }.array()
    private fun box(name: String, vararg data: ByteArray): ByteArray {
        val payload = data.fold(ByteArray(0)) { a, b -> a + b }
        return ints(payload.size + 8) + name.toByteArray() + payload
    }
    private fun fixture(subsamples: Boolean): Pair<ByteArray, Int> {
        val iv = ByteArray(8) { (it + 1).toByte() }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv.copyOf(16)))
        val encrypted = if (subsamples) {
            val crypt = cipher.doFinal(clear.copyOfRange(5, 16) + clear.copyOfRange(19, 32))
            clear.copyOfRange(0, 5) + crypt.copyOfRange(0, 11) + clear.copyOfRange(16, 19) + crypt.copyOfRange(11, 24)
        } else cipher.doFinal(clear)
        fun moov(offset: Int): ByteArray {
            val tenc = box("tenc", ByteArray(6), byteArrayOf(1, 8), ByteArray(16))
            val sinf = box("sinf", box("frma", "avc1".toByteArray()), box("schm", ints(0), "cenc".toByteArray(), ints(0)), box("schi", tenc))
            val entry = box("encv", ByteArray(78), sinf)
            val senc = box("senc", ints(if (subsamples) 2 else 0, 1), iv,
                if (subsamples) byteArrayOf(0, 2, 0, 5) + ints(11) + byteArrayOf(0, 3) + ints(13) else ByteArray(0))
            val stbl = box("stbl", box("stsd", ints(0, 1), entry), box("stsz", ints(0, 0, 1, clear.size)),
                box("stco", ints(0, 1, offset)), box("stsc", ints(0, 1, 1, 1, 1)), senc)
            return box("moov", box("trak", box("mdia", box("minf", stbl))))
        }
        val offset = moov(0).size + 8
        return (moov(offset) + box("mdat", encrypted)) to offset
    }

    @Test fun fullAndArbitrarySeekRangesDecryptIdenticallyIncludingSubsamples() {
        for (subsamples in listOf(false, true)) {
            val (bytes, payload) = fixture(subsamples)
            val transformer = CencMp4Transformer.load(bytes.size.toLong(), key) { offset, size -> bytes.copyOfRange(offset.toInt(), offset.toInt() + size) }
            val transformed = transformer.transform(0, bytes)
            assertArrayEquals(clear, transformed.copyOfRange(payload, transformed.size))
            assertTrue(String(transformed, Charsets.ISO_8859_1).contains("avc1"))
            assertFalse(String(transformed, Charsets.ISO_8859_1).contains("encv"))
            for (start in bytes.indices) {
                val end = minOf(bytes.size, start + 13)
                assertArrayEquals("seek at $start subsamples=$subsamples", transformed.copyOfRange(start, end),
                    transformer.transform(start.toLong(), bytes.copyOfRange(start, end)))
            }
        }
    }

    @Test fun malformedBoxExtentsAndSampleTablesAreRejectedBeforePlayback() {
        val (original, _) = fixture(false)
        for (bytes in listOf(original.copyOf(7), original.clone().apply { ByteBuffer.wrap(this).putInt(Int.MAX_VALUE) })) {
            assertThrows(IllegalArgumentException::class.java) {
                CencMp4Transformer.load(bytes.size.toLong(), key) { offset, size -> bytes.copyOfRange(offset.toInt(), offset.toInt() + size) }
            }
        }
    }
}
