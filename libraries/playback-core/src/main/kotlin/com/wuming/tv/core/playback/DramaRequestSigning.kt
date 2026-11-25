package com.wuming.tv.core.playback

import com.wuming.tv.core.base.Hex
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.Random

/** Process-local v5 request authentication. No account or media key is an input. */
internal class DramaRequestSigning(
    private val entropy: Random = SecureRandom(),
    private val started: Long = System.currentTimeMillis(),
) {
    private val session = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(18).also(entropy::nextBytes))

    class Signed(val query: String, val headers: Map<String, String>) {
        override fun toString() = "SignedDramaRequest"
    }

    fun sign(parameters: Map<String, String>, body: ByteArray, now: Long): Signed {
        val seconds = (now / 1000).toInt()
        val bodyHash = DramaWire.digest("MD5", body)
        var query = ""
        var queryHash = byteArrayOf()
        for (attempt in 0..1023) {
            val fields = LinkedHashMap(parameters).apply { put("_rticket", (now + attempt).toString()) }
            query = fields.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
            queryHash = DramaWire.sm3(query.toByteArray(Charsets.UTF_8))
            if (checksum(checksum(checksum(539167016, queryHash), bodyHash), DramaWire.le(seconds)) and 15 == 12) break
            if (attempt == 1023) error("Cannot authenticate drama request")
        }
        val requestHash = requestHash(queryHash, bodyHash, seconds)
        val helios = helios(seconds, entropy.nextInt())
        val device = DramaWire.message(1 to 1, 2 to 2, 3 to "8662", 4 to parameters.getValue("device_id"),
            5 to session, 6 to "!noperm!", 12 to "Asia/Shanghai,8", 13 to "zh_CN",
            22 to parameters.getValue("os_version"), 25 to started, 30 to parameters.getValue("device_type"),
            31 to "HONOR", 32 to parameters.getValue("device_type"), 34 to "HONOR")
        val payload = DramaWire.message(1 to marker, 2 to 5, 3 to (entropy.nextInt().toLong() and 0xffffffffL),
            4 to "8662", 5 to parameters.getValue("device_id"), 6 to "646637794", 7 to parameters.getValue("version_name"),
            8 to "v04.10.03-ml-android", 9 to 67764992, 10 to ByteArray(8), 12 to seconds,
            13 to requestHash, 14 to queryHash.copyOf(6),
            15 to DramaWire.message(1 to 1, 2 to 1, 3 to 1, 5 to 1), 17 to seconds, 20 to "none", 21 to 369,
            23 to DramaWire.message(6 to "v04.10.03.04-bugfix", 8 to parameters.getValue("channel"),
                12 to device, 14 to parameters.getValue("version_name")),
            24 to "{\"cmr\":16777216,\"cmr2\":16777216,\"un_h\":11,\"vpn\":0,\"kd\":0,\"dyn\":\"\",\"do\":1,\"tk\":true}")
        val nonce = entropy.nextInt()
        val mixer = DramaWire.sm3(DramaWire.join(seed, DramaWire.le(nonce), seed))
        fun rotateByte(value: Int, bits: Int) = ((value shl bits) or (value ushr (8 - bits))) and 255
        val mixed = IntArray(payload.size)
        for (i in payload.indices) {
            val offset = (i * 4) and 28
            val a = mixer[offset].toInt() and 255
            val b = mixer[offset + 1].toInt() and 255
            val x = ((rotateByte(payload[i].toInt() and 255, 4) + a).inv() xor b) and 255
            mixed[payload.lastIndex - i] = (((rotateByte(x, 3) + b) and 255) xor a).inv() and 255
        }
        val tail = mixed.lastIndex
        val ending = mixed[tail] xor mixed[tail - 1]
        val originalFirst = mixed[0]
        mixed[0] = (ending.inv() + originalFirst) and 255
        mixed[1] = ((mixed[0] xor mixed[tail] xor 254) + mixed[1]) and 255
        mixed[2] = (mixed[2] + ((ending - originalFirst) xor rotateByte(mixed[1], 3) xor 2)) and 255
        for (i in 3 until tail) mixed[i] = ((rotateByte(mixed[i - 1], 3) xor mixed[i - 2] xor i).inv() + mixed[i]) and 255
        mixed[tail] = mixed[tail] xor mixed[tail - 1]
        mixed[0] = ((mixed[0] xor mixed[1]) + mixed.drop(1).sum()) and 255
        val envelope = ByteArray(8) + ByteArray(mixed.size) { mixed[it].toByte() }
        envelope.reverse()
        val high = nonce ushr 24
        val next = (nonce ushr 16) and 255
        val mask = DramaWire.le(((((next shl 11) or high) xor (next ushr 5)) xor next).inv())
        for (i in envelope.indices) envelope[i] = (envelope[i].toInt() xor mask[i.inv() and 3].toInt()).toByte()
        val carrier = DramaWire.join(byteArrayOf(0xca.toByte()), DramaWire.le(entropy.nextInt()),
            DramaWire.le(402653185 or ((queryHash[0].toInt() and 63) shl 14) or ((requestHash[0].toInt() and 63) shl 8)),
            envelope, byteArrayOf(next.toByte(), high.toByte()))
        val authenticated = DramaSignatureCarrier(DramaWire.digest("MD5", seed.copyOfRange(0, 16)), seconds)
            .encode(carrier, DramaWire.digest("MD5", seed.copyOfRange(16, 32)))
        val prefix = DramaWire.le(5) + marker
        val clock = DramaWire.le(seconds)
        for (i in prefix.indices) prefix[i] = (prefix[i].toInt() xor clock[i and 3].toInt()).toByte()
        val medusa = DramaWire.join(prefix, byteArrayOf(nonce.toByte(), (nonce ushr 8).toByte(), 0, 1), authenticated)
        return Signed(query, mapOf("x-khronos" to seconds.toString(), "x-helios" to base64(helios),
            "x-medusa" to base64(medusa), "x-ss-stub" to Hex.encodeUpper(bodyHash)))
    }

    private fun requestHash(query: ByteArray, body: ByteArray, seconds: Int): ByteArray {
        val offset = -1656055870
        val shift = offset + seconds
        val constants = DramaWire.table("rounds").map { Integer.rotateRight(it, shift + 1) }
        val salt = byteArrayOf(250.toByte(), 69, 97, 215.toByte()).map {
            val x = it.toInt() and 255
            (((x shl 8) or x) shr ((shift + 2) and 7)).toByte()
        }.toByteArray()
        val input = ByteBuffer.wrap(DramaWire.join(query, body, DramaWire.le(seconds), salt, byteArrayOf(0, 0, 0, 0, 0, 0, 1, 0xa0.toByte())))
        val words = IntArray(128)
        for (i in 0..15) words[i] = input.int
        for (i in 0..111) {
            val a = words[i + 1]; val b = words[i + 14]
            words[i + 16] = words[i] + words[i + 9] +
                (Integer.rotateLeft(a, 14) xor Integer.rotateLeft(a, 25) xor (a ushr 3)) +
                (Integer.rotateLeft(b, 13) xor Integer.rotateLeft(b, 15) xor (b ushr 10))
        }
        val initial = intArrayOf(2059030472, 1729520903, 1677982208, 873419055, 984304912, -1289919430, -1420842003, -2142554032)
            .map { Integer.rotateRight(it, shift) }
        val v = initial.toIntArray()
        repeat(96) { round ->
            val index = offset + round
            val first = words[index and 127] + (v[4] xor ((v[4] xor v[0]) and v[1])) +
                (Integer.rotateLeft(v[1], 26) xor Integer.rotateLeft(v[1], 21) xor Integer.rotateLeft(v[1], 7)) + constants[index and 63] + v[6]
            val second = (Integer.rotateLeft(v[3], 30) xor Integer.rotateLeft(v[3], 19) xor Integer.rotateLeft(v[3], 10)) +
                (((v[3] or v[5]) and v[2]) or (v[3] and v[5]))
            val last = v[7]
            System.arraycopy(v, 0, v, 1, 7)
            v[7] = second + first; v[0] = last + first
        }
        val digest = ByteBuffer.allocate(32).apply { for (i in v.indices) putInt(v[i] + initial[i]) }.array()
        val folded = DramaWire.xor(digest.copyOfRange(0, 16), digest.copyOfRange(16, 32))
        var check = 539100192
        for (i in 0..11) check = (if (i % 2 == 0) (check shl 7) xor (folded[i].toInt() and 255)
            else ((check shl 11) or (folded[i].toInt() and 255)).inv()) xor (check ushr if (i % 2 == 0) 3 else 5) xor check
        return folded + DramaWire.le(32 or ((check or 4) xor 16777216))
    }

    private fun helios(seconds: Int, nonce: Int): ByteArray {
        val key = Hex.encodeLower(DramaWire.digest("MD5", DramaWire.le(nonce) + "8662".toByteArray())).toByteArray(Charsets.US_ASCII)
        val queue = java.util.ArrayDeque<Long>().apply { add(DramaWire.long(key, 16)); add(DramaWire.long(key, 24)) }
        var previous = DramaWire.long(key, 8)
        val rounds = LongArray(35); rounds[0] = DramaWire.long(key, 0)
        for (i in 0..33) {
            val next = i.toLong() xor (java.lang.Long.rotateRight(previous, 8) + rounds[i])
            queue.add(next); rounds[i + 1] = next xor java.lang.Long.rotateRight(rounds[i], 61)
            previous = queue.removeFirst()
        }
        val text = "$seconds-646637794-8662".toByteArray(Charsets.UTF_8)
        val padding = 16 - text.size % 16
        val padded = text + ByteArray(padding) { padding.toByte() }
        val output = java.io.ByteArrayOutputStream().apply { write(DramaWire.le(nonce)) }
        for (offset in padded.indices step 16) {
            var left = DramaWire.long(padded, offset); var right = DramaWire.long(padded, offset + 8)
            for (i in 0..33) { right = rounds[i] xor (java.lang.Long.rotateRight(right, 8) + left); left = java.lang.Long.rotateRight(left, 61) xor right }
            output.write(DramaWire.le(left)); output.write(DramaWire.le(right))
        }
        return output.toByteArray()
    }

    private fun checksum(start: Int, bytes: ByteArray): Int {
        var value = start
        bytes.forEachIndexed { i, byte ->
            val b = byte.toInt() and 255
            value = if (i % 2 == 0) (value shl 6) xor (value ushr 4) xor value xor b
            else ((value shl 12) or b) xor (value ushr 7) xor value xor -1
        }
        return value
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~")
    private fun base64(value: ByteArray) = Base64.getEncoder().encodeToString(value)
    private companion object {
        val marker = byteArrayOf(45, 75, 79, -54, 73, 117, 13, 67, 63, -75, -82, 44, 34, 109, -52, 86)
        val seed = byteArrayOf(-78, 60, 42, 71, 10, 32, -47, 5, -36, 63, -116, 68, 56, -8, 39, 46,
            -44, 109, 108, -119, -1, -36, -34, -35, 81, 8, 105, -115, 38, -23, 72, -99)
    }
}
