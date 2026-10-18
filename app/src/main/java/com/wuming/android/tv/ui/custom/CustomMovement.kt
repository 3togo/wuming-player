package com.wuming.android.tv.ui.custom

import android.graphics.RectF
import android.text.Layout
import android.text.NoCopySpan
import android.text.Selection
import android.text.Spannable
import android.text.method.ScrollingMovementMethod
import android.text.style.ClickableSpan
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

open class CustomMovement : ScrollingMovementMethod() {
    companion object {
        private const val CLICK = 1
        private const val UP = 2
        private const val DOWN = 3
        private val FROM_BELOW: Any = NoCopySpan.Concrete()
        private var sInstance: CustomMovement? = null

        @JvmStatic fun getInstance(): CustomMovement {
            if (sInstance == null) sInstance = CustomMovement()
            return sInstance!!
        }

        @JvmStatic fun bind(view: TextView) {
            val text = view.text
            val hasLinks = text is Spannable && text.getSpans(0, text.length, ClickableSpan::class.java).isNotEmpty()
            view.movementMethod = if (hasLinks) getInstance() else null
        }
    }

    override fun canSelectArbitrarily(): Boolean = true

    override fun handleMovementKey(
        widget: TextView,
        buffer: Spannable,
        keyCode: Int,
        movementMetaState: Int,
        event: KeyEvent,
    ): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
            KeyEvent.metaStateHasNoModifiers(movementMetaState)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && action(CLICK, widget, buffer)) return true
        }
        return super.handleMovementKey(widget, buffer, keyCode, movementMetaState, event)
    }

    private fun findFocus(
        widget: TextView,
        direction: Int,
    ): Boolean {
        val view =
            widget.focusSearch(direction) ?: return false
        view.requestFocus()
        return true
    }

    override fun up(
        widget: TextView,
        buffer: Spannable,
    ): Boolean = action(UP, widget, buffer) || findFocus(widget, View.FOCUS_UP) || super.up(widget, buffer)

    override fun down(
        widget: TextView,
        buffer: Spannable,
    ): Boolean = action(DOWN, widget, buffer) || findFocus(widget, View.FOCUS_DOWN) || super.down(widget, buffer)

    override fun left(
        widget: TextView,
        buffer: Spannable,
    ): Boolean {
        action(UP, widget, buffer)
        return true
    }

    override fun right(
        widget: TextView,
        buffer: Spannable,
    ): Boolean {
        action(DOWN, widget, buffer)
        return true
    }

    private fun action(
        what: Int,
        widget: TextView,
        buffer: Spannable,
    ): Boolean {
        val layout = widget.layout ?: return false
        val a = Selection.getSelectionStart(buffer)
        val b = Selection.getSelectionEnd(buffer)
        var selStart = minOf(a, b)
        var selEnd = maxOf(a, b)
        if (selStart < 0 && buffer.getSpanStart(FROM_BELOW) >= 0) {
            selStart = buffer.length
            selEnd = buffer.length
        }
        if (what == CLICK) return doClick(buffer, widget, selStart, selEnd)
        val padding = widget.totalPaddingTop + widget.totalPaddingBottom
        val first = layout.getLineStart(layout.getLineForVertical(widget.scrollY))
        val last = layout.getLineEnd(layout.getLineForVertical(widget.scrollY + widget.height - padding))
        if (selStart > last) {
            selStart = Int.MAX_VALUE
            selEnd = Int.MAX_VALUE
        }
        if (selEnd < first) {
            selStart = -1
            selEnd = -1
        }
        val candidates = buffer.getSpans(first, last, ClickableSpan::class.java)
        return if (what == UP) doUp(buffer, candidates, selStart, selEnd) else doDown(buffer, candidates, selStart, selEnd)
    }

    private fun doClick(
        buffer: Spannable,
        widget: TextView,
        selStart: Int,
        selEnd: Int,
    ): Boolean {
        if (selStart == selEnd) return false
        val links = buffer.getSpans(selStart, selEnd, ClickableSpan::class.java)
        if (links.size != 1) return false
        links[0].onClick(widget)
        return true
    }

    private fun doUp(
        buffer: Spannable,
        candidates: Array<ClickableSpan>,
        selStart: Int,
        selEnd: Int,
    ): Boolean {
        var bestStart = -1
        var bestEnd = -1
        for (candidate in candidates) {
            val end = buffer.getSpanEnd(candidate)
            if ((end < selEnd || selStart == selEnd) && end > bestEnd) {
                bestStart = buffer.getSpanStart(candidate)
                bestEnd = end
            }
        }
        if (bestStart < 0) return false
        Selection.setSelection(buffer, bestEnd, bestStart)
        return true
    }

    private fun doDown(
        buffer: Spannable,
        candidates: Array<ClickableSpan>,
        selStart: Int,
        selEnd: Int,
    ): Boolean {
        var bestStart = Int.MAX_VALUE
        var bestEnd = Int.MAX_VALUE
        for (candidate in candidates) {
            val start = buffer.getSpanStart(candidate)
            if ((start > selStart || selStart == selEnd) && start < bestStart) {
                bestStart = start
                bestEnd = buffer.getSpanEnd(candidate)
            }
        }
        if (bestEnd == Int.MAX_VALUE) return false
        Selection.setSelection(buffer, bestStart, bestEnd)
        return true
    }

    override fun onTouchEvent(
        widget: TextView,
        buffer: Spannable,
        event: MotionEvent,
    ): Boolean {
        val action = event.action
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_DOWN) return super.onTouchEvent(widget, buffer, event)
        val layout = widget.layout ?: return super.onTouchEvent(widget, buffer, event)
        val x = event.x.toInt() - widget.totalPaddingLeft + widget.scrollX
        val y = event.y.toInt() - widget.totalPaddingTop + widget.scrollY
        val line = layout.getLineForVertical(y)
        val bounds =
            RectF(
                layout.getLineLeft(line),
                layout.getLineTop(line).toFloat(),
                layout.getLineLeft(line) + layout.getLineWidth(line),
                layout.getLineBottom(line).toFloat(),
            )
        if (!bounds.contains(x.toFloat(), y.toFloat())) return super.onTouchEvent(widget, buffer, event)
        val off = layout.getOffsetForHorizontal(line, x.toFloat())
        val links = buffer.getSpans(off, off, ClickableSpan::class.java)
        if (links.isEmpty()) {
            Selection.removeSelection(buffer)
            return super.onTouchEvent(widget, buffer, event)
        }
        if (action == MotionEvent.ACTION_UP) links[0].onClick(widget)
        return true
    }

    override fun initialize(
        widget: TextView,
        text: Spannable,
    ) {
        Selection.removeSelection(text)
        text.removeSpan(FROM_BELOW)
    }

    override fun onTakeFocus(
        view: TextView,
        text: Spannable,
        dir: Int,
    ) {
        Selection.removeSelection(text)
        if (dir == View.FOCUS_BACKWARD) text.setSpan(FROM_BELOW, 0, 0, Spannable.SPAN_POINT_POINT) else text.removeSpan(FROM_BELOW)
    }
}
