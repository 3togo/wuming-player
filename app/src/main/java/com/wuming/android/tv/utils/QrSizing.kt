package com.wuming.android.tv.utils

import android.content.Context
import android.content.res.Resources
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.wuming.android.tv.R

/**
 * Single source of truth for the geometry of every QR code the app renders.
 *
 * <p><b>WARNING — REGRESSION GUARD, DO NOT DELETE OR WORK AROUND.</b>
 * The Bilibili login QR used to be laid out with a hard-coded 260&nbsp;dp square
 * (and the cloud-drive login QR with a hard-coded {@code vs_260} XML size). A fixed
 * square ignores the space that is actually left between the dialog title, the status
 * line and the button panel, so on leanback/TV — and on any small or heavily
 * font-scaled display — the bottom of the QR code is pushed under the button panel
 * and clipped away. The clipped rows are exactly what a phone camera needs, so the
 * login looks "broken" while nothing is actually wrong with the network flow.
 *
 * <p>This defect keeps coming back because the QR square is easy to hard-code again
 * whenever the dialog is rebuilt. Therefore:
 * <ul>
 *   <li>Never hard-code a QR size in a layout or in a dialog: ask
 *       {@link #estimatePx(Context)} / {@link #fitSquarePx(int, int, int, int)}.</li>
 *   <li>Never size the QR from the encoded bitmap: encode once at
 *       {@link #encodePx()} and let the {@code ImageView} scale it down. The view is
 *       what has to fit the screen; the bitmap resolution does not.</li>
 *   <li>Any new QR surface (new provider, new pairing page) must go through this class
 *       and must call the post-layout {@link #fitSquarePx} correction.</li>
 * </ul>
 */

class QrSizing private constructor() {
    companion object {
        private const val ENCODE_PX = 512
        private const val MIN_SIZE_DP = 140
        private const val MAX_SIZE_DP = 300
        private const val HEIGHT_RATIO = 0.46f
        private const val WIDTH_RATIO = 0.60f
        private const val WINDOW_MARGIN_DP = 32
        const val MIN_ADJUST_PX = 8

        @JvmStatic fun encodePx(): Int = ENCODE_PX

        @JvmStatic fun minPx(): Int = ResUtil.dp2px(MIN_SIZE_DP)

        @JvmStatic fun maxPx(): Int = ResUtil.dp2px(MAX_SIZE_DP)

        @JvmStatic fun estimatePx(context: Context): Int =
            clampPx(
                minOf(
                    Math.round(ResUtil.getScreenHeight(context) * HEIGHT_RATIO),
                    Math.round(ResUtil.getScreenWidth(context) * WIDTH_RATIO),
                ),
            )

        // Budget usable display height, excluding bars: raw bounds clipped codes on phones.
        @JvmStatic fun maxWindowHeightPx(context: Context): Int = maxOf(minPx(), usableHeightPx(context) - ResUtil.dp2px(WINDOW_MARGIN_DP))

        private fun usableHeightPx(context: Context): Int = maxOf(minPx(), ResUtil.getScreenHeight(context) - systemBarsPx(context))

        private fun systemBarsPx(context: Context): Int =
            maxOf(0, systemDimenPx(context.resources, "status_bar_height")) +
                maxOf(0, systemDimenPx(context.resources, "navigation_bar_height"))

        private fun systemDimenPx(
            resources: Resources,
            name: String,
        ): Int {
            val id = resources.getIdentifier(name, "dimen", "android")
            return if (id == 0) 0 else resources.getDimensionPixelSize(id)
        }

        @JvmStatic fun clampPx(sizePx: Int): Int = maxOf(minPx(), minOf(maxPx(), sizePx))

        /** Subtract the measured title/status/button chrome before sizing the QR square. */
        @JvmStatic fun fitSquarePx(
            windowHeightPx: Int,
            qrHeightPx: Int,
            maxWindowHeightPx: Int,
            preferredPx: Int,
        ): Int {
            if (windowHeightPx <= 0 || qrHeightPx <= 0) return clampPx(preferredPx)
            val chrome = maxOf(0, windowHeightPx - qrHeightPx)
            return clampPx(minOf(preferredPx, maxWindowHeightPx - chrome))
        }

        @JvmStatic fun squareHost(
            context: Context,
            qrView: View,
            sizePx: Int,
        ): View {
            val host = LinearLayout(context)
            host.orientation = LinearLayout.VERTICAL
            host.gravity = Gravity.CENTER_HORIZONTAL
            val padding = ResUtil.getDimensionPixelSize(R.dimen.vs_16)
            host.setPadding(padding, padding, padding, padding)
            host.addView(qrView, LinearLayout.LayoutParams(sizePx, sizePx))
            return host
        }

        @JvmStatic fun applySquare(
            view: View?,
            sizePx: Int,
        ) {
            if (view == null || sizePx <= 0) return
            val params = view.layoutParams ?: ViewGroup.LayoutParams(sizePx, sizePx)
            params.width = sizePx
            params.height = sizePx
            view.layoutParams = params
        }
    }
}
