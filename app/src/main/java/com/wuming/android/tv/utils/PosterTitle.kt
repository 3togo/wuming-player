package com.wuming.android.tv.utils

import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.widget.TextView

/**
 * Keeps a two-line poster title from splitting a word when a space is available.
 *
 * The decision is made after measurement, so short titles remain on one line.
 * CJK titles without spaces retain Android's normal character wrapping.
 */
object PosterTitle {
    @JvmStatic
    fun wrapAtSpace(view: TextView) {
        val original = view.text
        if (TextUtils.isEmpty(original) || !containsSpace(original)) return
        view.post { moveFirstBreakToSpace(view, original) }
    }

    private fun moveFirstBreakToSpace(view: TextView, original: CharSequence) {
        if (!TextUtils.equals(view.text, original)) return
        val layout = view.layout ?: return
        if (layout.lineCount < 2) return

        val lineEnd = minOf(original.length, layout.getLineEnd(0))
        val space = findPreviousSpace(original, lineEnd)
        if (space <= 0) return

        val adjusted = SpannableStringBuilder(original)
        adjusted.replace(space, space + 1, "\n")
        view.text = adjusted
    }

    private fun findPreviousSpace(text: CharSequence, before: Int): Int {
        for (index in minOf(before - 1, text.length - 1) downTo 0) {
            if (Character.isWhitespace(text[index])) return index
        }
        return -1
    }

    private fun containsSpace(text: CharSequence): Boolean {
        for (index in text.indices) {
            if (Character.isWhitespace(text[index])) return true
        }
        return false
    }
}
