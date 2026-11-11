package com.wuming.android.tv.ui

import android.widget.TextView
import com.github.catvod.utils.Prefers
import com.wuming.android.tv.R
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.TextScale

/** Leanback presentation preference; percentages refer to the previously approved size. */
object PlaybackTextSize {
    private const val KEY = "leanback_playback_text_percent"
    @JvmStatic fun percent(): Int = Prefers.getInt(KEY, 80).coerceIn(40, 160)
    @JvmStatic fun save(percent: Int) = Prefers.put(KEY, percent.coerceIn(40, 160))
    @JvmStatic fun apply(info: TextView, clock: TextView) {
        // Reset to the screen-relative reference so reopening never compounds scaling.
        ResUtil.scalePlayerOverlay(info, clock)
        val scale = (clock.getTag(R.id.tag_typography_overlay_scale) as Float) * 0.8f * percent() / 100f
        TextScale.setPlayerOverlayScale(info, scale)
        TextScale.setPlayerOverlayScale(clock, scale)
    }
}
