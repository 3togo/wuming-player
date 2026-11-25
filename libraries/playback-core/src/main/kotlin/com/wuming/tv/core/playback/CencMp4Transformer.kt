package com.wuming.tv.core.playback

import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Same-length transformation of supported, unfragmented ISO CENC AES-CTR MP4 ranges. */
class CencMp4Transformer private constructor(
    val length: Long, private val moovOffset: Long, private val moov: ByteArray,
    private val samples: List<Sample>, key: ByteArray,
) {
    private val secret = SecretKeySpec(key.clone(), "AES")
    private class Sample(val offset: Long, val size: Int, val iv: ByteArray, val parts: List<Pair<Int, Int>>)
    private class Box(val start: Int, val end: Int, val type: String, val payload: Int)

    fun transform(offset: Long, original: ByteArray): ByteArray {
        require(offset >= 0 && offset <= length && original.size <= length - offset)
        val output = original.clone()
        val end = offset + output.size
        val headerStart = maxOf(offset, moovOffset)
        val headerEnd = minOf(end, moovOffset + moov.size)
        if (headerStart < headerEnd) moov.copyInto(output, (headerStart - offset).toInt(),
            (headerStart - moovOffset).toInt(), (headerEnd - moovOffset).toInt())
        var low = 0
        var high = samples.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (samples[middle].offset + samples[middle].size <= offset) low = middle + 1 else high = middle
        }
        for (index in low until samples.size) {
            val sample = samples[index]
            if (sample.offset >= end) break
            var position = sample.offset
            var encryptedBefore = 0L
            for ((clear, encrypted) in sample.parts) {
                position += clear
                val start = maxOf(position, offset)
                val stop = minOf(position + encrypted, end)
                if (start < stop) {
                    val cipherOffset = encryptedBefore + start - position
                    val iv = sample.iv.copyOf(16)
                    var carry = cipherOffset / 16
                    for (byte in 15 downTo 0) {
                        val value = (iv[byte].toInt() and 255).toLong() + (carry and 255)
                        iv[byte] = value.toByte()
                        carry = (carry ushr 8) + (value ushr 8)
                    }
                    check(carry == 0L) { "CENC counter overflow" }
                    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(iv))
                    val skip = (cipherOffset % 16).toInt()
                    if (skip > 0) cipher.update(ByteArray(skip))
                    val local = (start - offset).toInt()
                    cipher.doFinal(output, local, (stop - start).toInt()).copyInto(output, local)
                }
                position += encrypted
                encryptedBefore += encrypted
            }
        }
        return output
    }

    companion object {
        private const val MAX_MOOV = 4 * 1024 * 1024
        private const val MAX_SAMPLES = 100_000
        fun load(length: Long, key: ByteArray, read: (Long, Int) -> ByteArray): CencMp4Transformer {
            require(length >= 8 && key.size == 16)
            var offset = 0L
            var header: ByteArray? = null
            var moovOffset = 0L
            repeat(256) {
                if (header == null && offset + 8 <= length) {
                    val bytes = read(offset, minOf(16L, length - offset).toInt())
                    val size = when (val short = u32(bytes, 0)) {
                        0L -> length - offset
                        1L -> u64(bytes, 8)
                        else -> short
                    }
                    require(size >= 8 && size <= length - offset) { "Invalid MP4 box extent" }
                    val type = String(bytes, 4, 4, Charsets.US_ASCII)
                    require(type != "moof") { "Fragmented CENC requires a different playback path" }
                    if (type == "moov") {
                        require(size <= MAX_MOOV) { "MP4 metadata exceeds size limit" }
                        moovOffset = offset
                        header = read(offset, size.toInt())
                        require(header!!.size == size.toInt()) { "Truncated MP4 metadata" }
                    } else offset += size
                }
            }
            val moov = header ?: throw IOException("MP4 metadata unavailable")
            val root = boxes(moov, 0, moov.size).single()
            val samples = mutableListOf<Sample>()
            for (track in children(moov, root).filter { it.type == "trak" }) {
                val stbl = child(moov, child(moov, child(moov, track, "mdia"), "minf"), "stbl")
                val tables = children(moov, stbl).associateBy { it.type }
                val stsd = tables.getValue("stsd")
                require(stsd.payload + 8 <= stsd.end)
                require(u32(moov, stsd.payload + 4) == 1L) { "Multiple MP4 sample descriptions unsupported" }
                val entry = boxes(moov, stsd.payload + 8, stsd.end).single()
                if (entry.type !in setOf("encv", "enca")) continue
                val childOffset = entry.start + if (entry.type == "encv") 86 else 36
                val sinf = boxes(moov, childOffset, entry.end).single { it.type == "sinf" }
                val frma = child(moov, sinf, "frma")
                val schm = child(moov, sinf, "schm")
                require(String(moov, schm.payload + 4, 4, Charsets.US_ASCII) == "cenc") { "Unsupported MP4 encryption scheme" }
                val tenc = child(moov, child(moov, sinf, "schi"), "tenc")
                require(tenc.end - tenc.payload >= 24 && moov[tenc.payload].toInt() == 0)
                require(moov[tenc.payload + 6].toInt() == 1)
                val ivSize = moov[tenc.payload + 7].toInt() and 255
                require(ivSize == 8 || ivSize == 16) { "Unsupported CENC IV size" }
                val sizesBox = tables.getValue("stsz")
                require(sizesBox.payload + 12 <= sizesBox.end)
                val fixed = int32(moov, sizesBox.payload + 4)
                val count = count(moov, sizesBox.payload + 8)
                require(sizesBox.end - sizesBox.payload == 12 + if (fixed == 0) count * 4 else 0)
                val sizes = IntArray(count) { if (fixed == 0) int32(moov, sizesBox.payload + 12 + it * 4) else fixed }
                val offsetsBox = tables["stco"] ?: tables.getValue("co64")
                require(offsetsBox.payload + 8 <= offsetsBox.end)
                require(offsetsBox.end - offsetsBox.payload == 8 + count(moov, offsetsBox.payload + 4) * if (offsetsBox.type == "stco") 4 else 8)
                val offsets = LongArray(count(moov, offsetsBox.payload + 4)) {
                    if (offsetsBox.type == "stco") u32(moov, offsetsBox.payload + 8 + it * 4)
                    else u64(moov, offsetsBox.payload + 8 + it * 8)
                }
                val stsc = tables.getValue("stsc")
                require(stsc.payload + 8 <= stsc.end)
                require(stsc.end - stsc.payload == 8 + count(moov, stsc.payload + 4) * 12)
                val mappings = List(count(moov, stsc.payload + 4)) { i ->
                    val p = stsc.payload + 8 + i * 12
                    require(u32(moov, p + 8) == 1L) { "Unsupported sample description mapping" }
                    int32(moov, p) to int32(moov, p + 4)
                }
                require(mappings.isNotEmpty() && mappings.first().first == 1)
                require(mappings.zipWithNext().all { it.first.first < it.second.first })
                val positions = LongArray(count)
                var sampleIndex = 0
                var mapping = 0
                offsets.forEachIndexed { chunk, location ->
                    while (mapping + 1 < mappings.size && mappings[mapping + 1].first <= chunk + 1) mapping++
                    var position = location
                    require(mappings[mapping].second in 1..MAX_SAMPLES)
                    repeat(mappings[mapping].second) {
                        require(sampleIndex < count)
                        positions[sampleIndex] = position
                        position += sizes[sampleIndex++]
                        require(position <= length)
                    }
                }
                require(sampleIndex == count)
                val senc = tables["senc"] ?: throw IOException("CENC sample table unavailable")
                require(senc.payload + 8 <= senc.end)
                val flags = u32(moov, senc.payload) and 0xffffff
                require(flags and 1L == 0L && count(moov, senc.payload + 4) == count)
                var cursor = senc.payload + 8
                repeat(count) { index ->
                    require(cursor + ivSize <= senc.end)
                    val iv = moov.copyOfRange(cursor, cursor + ivSize)
                    cursor += ivSize
                    val parts = mutableListOf<Pair<Int, Int>>()
                    if (flags and 2L != 0L) {
                        require(cursor + 2 <= senc.end)
                        val partCount = u16(moov, cursor); cursor += 2
                        require(partCount <= 4096 && cursor + partCount * 6 <= senc.end)
                        repeat(partCount) {
                            parts += u16(moov, cursor) to int32(moov, cursor + 2)
                            cursor += 6
                        }
                    } else parts += 0 to sizes[index]
                    require(cursor <= senc.end && parts.sumOf { it.first.toLong() + it.second } == sizes[index].toLong())
                    samples += Sample(positions[index], sizes[index], iv, parts)
                    require(samples.size <= MAX_SAMPLES)
                }
                require(cursor == senc.end)
                moov.copyInto(moov, entry.start + 4, frma.payload, frma.payload + 4)
                for (box in listOf(sinf, senc) + listOfNotNull(tables["saiz"], tables["saio"])) {
                    "free".toByteArray(Charsets.US_ASCII).copyInto(moov, box.start + 4)
                }
            }
            require(samples.isNotEmpty()) { "No supported CENC samples" }
            val ordered = samples.sortedBy { it.offset }
            require(ordered.all { it.offset + it.size <= moovOffset || it.offset >= moovOffset + moov.size })
            require(ordered.zipWithNext().all { it.first.offset + it.first.size <= it.second.offset })
            return CencMp4Transformer(length, moovOffset, moov, ordered, key)
        }

        private fun children(bytes: ByteArray, box: Box) = boxes(bytes, box.payload, box.end)
        private fun child(bytes: ByteArray, box: Box, type: String) = children(bytes, box).single { it.type == type }
        private fun boxes(bytes: ByteArray, start: Int, end: Int): List<Box> {
            val result = mutableListOf<Box>()
            var p = start
            while (p < end) {
                require(p + 8 <= end)
                var size = u32(bytes, p)
                val header = if (size == 1L) 16 else 8
                if (size == 1L) size = u64(bytes, p + 8)
                if (size == 0L) size = (end - p).toLong()
                require(size >= header && size <= end - p)
                result += Box(p, p + size.toInt(), String(bytes, p + 4, 4, Charsets.US_ASCII), p + header)
                require(result.size <= MAX_SAMPLES) { "Too many MP4 boxes" }
                p += size.toInt()
            }
            return result
        }
        private fun count(b: ByteArray, p: Int) = int32(b, p).also { require(it <= MAX_SAMPLES) }
        private fun int32(b: ByteArray, p: Int) = u32(b, p).also { require(it <= Int.MAX_VALUE) }.toInt()
        private fun u16(b: ByteArray, p: Int): Int { require(p >= 0 && p + 2 <= b.size); return ((b[p].toInt() and 255) shl 8) or (b[p + 1].toInt() and 255) }
        private fun u32(b: ByteArray, p: Int) = (u16(b, p).toLong() shl 16) or u16(b, p + 2).toLong()
        private fun u64(b: ByteArray, p: Int) = ((u32(b, p) shl 32) or u32(b, p + 4)).also { require(it >= 0) }
    }
}
