package com.wuming.tv.core.playback

/** Reads coded dimensions from an AVC SPS NAL without Android/Media3 or an
 * external decoder. Input bounds and Exp-Golomb loops fail closed on malformed data. */
internal object AvcSpsParser : TsStreamProbe.SpsParserPort {
    override fun parseSps(data: ByteArray, offset: Int, end: Int): HlsResolutionAdFilter.VideoInfo {
        require(offset >= 0 && end <= data.size && end - offset >= 4)
        require(data[offset].toInt() and 31 == 7)
        val rbsp = ArrayList<Byte>(end - offset)
        var zeros = 0
        for (i in offset + 1 until end) {
            val value = data[i].toInt() and 255
            if (zeros >= 2 && value == 3) {
                require(i + 1 < end && data[i + 1].toInt() and 255 <= 3)
                zeros = 0
                continue
            }
            rbsp.add(data[i])
            zeros = if (value == 0) zeros + 1 else 0
        }
        val bits = Bits(rbsp.toByteArray())
        val profile = bits.read(8)
        bits.read(8) // constraint flags
        bits.read(8) // level
        bits.ue() // SPS id
        var chroma = 1
        var separateColorPlane = false
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = bits.ue().also { require(it in 0..3) }
            if (chroma == 3) separateColorPlane = bits.read(1) != 0
            require(bits.ue() <= 6) // bit depth luma minus 8
            require(bits.ue() <= 6) // bit depth chroma minus 8
            bits.read(1)
            if (bits.read(1) != 0) {
                repeat(if (chroma == 3) 12 else 8) { index ->
                    if (bits.read(1) != 0) {
                        var last = 8
                        var next = 8
                        repeat(if (index < 6) 16 else 64) {
                            if (next != 0) next = Math.floorMod(last.toLong() + bits.se(), 256L).toInt()
                            if (next != 0) last = next
                        }
                    }
                }
            }
        }
        bits.ue() // log2 max frame num minus 4
        when (bits.ue()) {
            0 -> bits.ue()
            1 -> {
                bits.read(1)
                bits.se()
                bits.se()
                val cycle = bits.ue().also { require(it <= 255) }
                repeat(cycle) { bits.se() }
            }
            2 -> Unit
            else -> error("Invalid picture order count type")
        }
        bits.ue() // reference frames
        bits.read(1)
        val widthMbs = bits.ue().also { require(it < 65_536) } + 1
        val heightUnits = bits.ue().also { require(it < 65_536) } + 1
        val frameOnly = bits.read(1)
        if (frameOnly == 0) bits.read(1)
        bits.read(1)
        var left = 0
        var right = 0
        var top = 0
        var bottom = 0
        if (bits.read(1) != 0) {
            left = bits.ue(); right = bits.ue(); top = bits.ue(); bottom = bits.ue()
        }
        val chromaArray = if (separateColorPlane) 0 else chroma
        val cropX = if (chromaArray == 1 || chromaArray == 2) 2 else 1
        val cropY = (if (chromaArray == 1) 2 else 1) * (2 - frameOnly)
        val width = widthMbs * 16L - (left.toLong() + right) * cropX
        val height = heightUnits * 16L * (2 - frameOnly) - (top.toLong() + bottom) * cropY
        require(width in 1..Int.MAX_VALUE && height in 1..Int.MAX_VALUE)
        return HlsResolutionAdFilter.VideoInfo(width.toInt(), height.toInt(), 0.0)
    }

    private class Bits(private val bytes: ByteArray) {
        private var position = 0
        fun read(count: Int): Int {
            require(count in 0..31 && position.toLong() + count <= bytes.size.toLong() * 8)
            var result = 0
            repeat(count) {
                result = (result shl 1) or ((bytes[position / 8].toInt() shr (7 - position % 8)) and 1)
                position++
            }
            return result
        }
        fun ue(): Int {
            var zeros = 0
            while (read(1) == 0) { zeros++; require(zeros < 31) }
            return ((1L shl zeros) - 1 + read(zeros)).also { require(it <= Int.MAX_VALUE) }.toInt()
        }
        fun se(): Int {
            val value = ue()
            return if (value and 1 == 0) -(value / 2) else value / 2 + 1
        }
    }
}
