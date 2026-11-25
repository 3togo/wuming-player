package com.wuming.tv.core.playback

/** The v5 wire envelope authenticates selected carrier bits with two cipher blocks. */
internal class DramaSignatureCarrier(key: ByteArray, timestamp: Int) {
    private val mode = timestamp and 3
    private val substitution = DramaWire.table("permutation").copyOfRange(mode * 256, mode * 256 + 256)
    private val rows = arrayOf(intArrayOf(2, 0, 3, 1), intArrayOf(1, 3, 2, 0), intArrayOf(3, 2, 1, 0), intArrayOf(2, 3, 1, 0))[mode]
    private val positions = arrayOf(
        intArrayOf(0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11),
        intArrayOf(0, 9, 14, 11, 4, 13, 2, 3, 8, 1, 6, 15, 12, 5, 10, 7),
        intArrayOf(0, 9, 14, 3, 4, 13, 2, 15, 8, 1, 6, 11, 12, 5, 10, 7),
        intArrayOf(0, 9, 14, 15, 4, 13, 2, 3, 8, 1, 6, 11, 12, 5, 10, 7))[mode]
    private val schedule: IntArray

    init {
        val constant = intArrayOf(-1145708942, -731673253, -1834617029, -1980194359)[mode]
        val initial = DramaWire.xor(DramaWire.join(*Array(4) { DramaWire.le(constant) }), key)
        schedule = IntArray(48) { if (it < 16) initial[it].toInt() and 255 else 0 }
        var shift = 8
        for (word in 4..11) {
            val previous = schedule.copyOfRange((word - 1) * 4, word * 4)
            if (word % 4 == 0) {
                val first = previous[0]
                previous[0] = substitution[previous[1]] xor ((constant shr (shift and 24)) and 255)
                previous[1] = substitution[previous[2]]; previous[2] = substitution[previous[3]]
                previous[3] = substitution[first]
            }
            shift += 2
            for (i in 0..3) schedule[word * 4 + i] = previous[i] xor schedule[(word - 4) * 4 + i]
        }
    }

    private fun block(bytes: ByteArray): ByteArray {
        var state = IntArray(16) { bytes[it].toInt() and 255 }
        fun addKey(start: Int) {
            for (i in state.indices) state[i] = state[i] xor schedule[(start + i / 4) * 4 + rows[i % 4]]
        }
        addKey(0)
        for (round in 1..2) {
            val substituted = IntArray(16) { substitution[state[rows[it / 4] * 4 + it % 4]] }
            state = IntArray(16) { substituted[positions[it]] }
            if (round == 1) {
                state = IntArray(16) { state[it / 4 * 4 + rows[it % 4]] }
                fun twice(value: Int) = ((value shl 1) xor if (value and 128 != 0) 27 else 0) and 255
                for (column in 0..3) {
                    val original = IntArray(4) { state[it * 4 + column] }
                    val total = original.fold(0) { a, b -> a xor b }
                    for (row in 0..3) state[row * 4 + column] = original[row] xor total xor twice(original[row] xor original[(row + 1) % 4])
                }
            }
            addKey(round * 4)
        }
        return ByteArray(16) { (state[it] xor schedule[(it / 4 + 4) * 4 + it % 4]).toByte() }
    }

    fun encode(carrier: ByteArray, iv: ByteArray): ByteArray {
        require(carrier.size >= 248 && iv.size == 16)
        val sourceBits = intArrayOf(6, 5, 3, 4, 2, 7, 1, 0)
        val targetBits = intArrayOf(1, 7, 3, 6, 2, 0, 4, 5)
        val selected = ByteArray(32)
        for (i in 0..30) for (bit in 0..7) {
            val value = (carrier[i * 8 + bit].toInt() ushr sourceBits[bit]) and 1
            selected[i] = (selected[i].toInt() or (value shl targetBits[bit])).toByte()
        }
        selected[31] = 1
        val first = block(DramaWire.xor(selected.copyOfRange(0, 16), iv))
        val encrypted = first + block(DramaWire.xor(selected.copyOfRange(16, 32), first))
        val output = carrier.clone()
        for (i in 0..30) for (bit in 0..7) {
            val mask = 1 shl sourceBits[bit]
            val value = (encrypted[i].toInt() ushr targetBits[bit]) and 1
            val offset = i * 8 + bit
            output[offset] = ((output[offset].toInt() and mask.inv()) or (value shl sourceBits[bit])).toByte()
        }
        return byteArrayOf(encrypted[31]) + output
    }
}
