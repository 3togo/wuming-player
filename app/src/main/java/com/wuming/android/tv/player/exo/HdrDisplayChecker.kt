package com.wuming.android.tv.player.exo

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.Format

/**
 * Check whether connected display supports HDR color transfer and HDR metadata.
 * Used in CapabilityTrackSelector to reject HDR tracks when display cannot render HDR,
 * even if the MediaCodec decoder can decode the HDR bitstream.
 */
object HdrDisplayChecker {

    /**
     * HDR type constants matching Android Display HDR types.
     */
    const val HDR_TYPE_NONE = 0
    const val HDR_TYPE_HDR10 = Display.HdrCapabilities.HDR_TYPE_HDR10
    const val HDR_TYPE_HLG = Display.HdrCapabilities.HDR_TYPE_HLG
    const val HDR_TYPE_HDR10_PLUS = Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS

    fun getSupportedHdrTypes(context: Context): Set<Int> {
        return VideoCapabilityCache.get().savedReport()?.hdrTypes ?: querySupportedHdrTypes(context)
    }

    internal fun querySupportedHdrTypes(context: Context): Set<Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptySet()
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptySet()
        val supportedTypes = mutableSetOf<Int>()
        val hdrTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            display.mode.supportedHdrTypes
        } else {
            legacyHdrTypes(display)
        }
        for (type in hdrTypes) {
            supportedTypes.add(type)
        }
        return supportedTypes
    }

    @Suppress("DEPRECATION")
    private fun legacyHdrTypes(display: Display): IntArray =
        display.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()

    /**
     * Check if the display can support the color transfer specified in Format.ColorInfo.
     */
    fun isColorTransferSupported(context: Context, format: Format): Boolean {
        val colorInfo = format.colorInfo ?: return true // SDR, always allowed
        return isColorTransferSupported(colorInfo.colorTransfer, getSupportedHdrTypes(context))
    }

    fun isColorTransferSupported(transfer: Int, supportedHdr: Set<Int>): Boolean = when (transfer) {
        C.COLOR_TRANSFER_ST2084 -> supportedHdr.contains(HDR_TYPE_HDR10) || supportedHdr.contains(HDR_TYPE_HDR10_PLUS)
        C.COLOR_TRANSFER_HLG -> supportedHdr.contains(HDR_TYPE_HLG)
        else -> true // SDR transfers
    }

    /**
     * Determine whether this format should be rejected because display cannot render its HDR color transfer.
     */
    fun shouldRejectHdrFormat(context: Context, format: Format): Boolean {
        if (format.colorInfo == null) return false
        return !isColorTransferSupported(context, format)
    }
}
