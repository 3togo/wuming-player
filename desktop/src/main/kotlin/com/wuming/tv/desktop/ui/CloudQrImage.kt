package com.wuming.tv.desktop.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage

internal object CloudQrImage {
    fun render(content: String, size: Int = 280): BufferedImage {
        require(content.startsWith("https://") && content.length <= 4096) { "Invalid cloud login QR payload" }
        val matrix = MultiFormatWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
        return BufferedImage(size, size, BufferedImage.TYPE_INT_RGB).apply {
            for (y in 0 until size) for (x in 0 until size) {
                setRGB(x, y, if (matrix[x, y]) 0xff000000.toInt() else 0xffffffff.toInt())
            }
        }
    }
}
