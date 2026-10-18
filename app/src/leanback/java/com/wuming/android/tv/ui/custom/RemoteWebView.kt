package com.wuming.android.tv.ui.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import com.wuming.android.tv.R

/** Remote pointer for provider websites whose controls do not support keyboard focus. */
class RemoteWebView(
    context: Context,
    attrs: AttributeSet?,
) : WebView(context, attrs) {
    private val pointer = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pointerX = -1f
    private var pointerY = -1f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        pointerX = if (pointerX < 0) w / 2f else minOf(pointerX, maxOf(0, w - 1).toFloat())
        pointerY = if (pointerY < 0) h / 2f else minOf(pointerY, maxOf(0, h - 1).toFloat())
    }

    override fun onFocusChanged(
        focused: Boolean,
        direction: Int,
        previous: Rect?,
    ) {
        super.onFocusChanged(focused, direction, previous)
        invalidate()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val arrow =
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP ||
                code == KeyEvent.KEYCODE_DPAD_DOWN
        val select = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (code == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_UP) rootView.findViewById<View>(R.id.back)?.requestFocus()
            return true
        }
        if (code == KeyEvent.KEYCODE_PAGE_UP || code == KeyEvent.KEYCODE_PAGE_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (code == KeyEvent.KEYCODE_PAGE_UP) pageUp(false) else pageDown(false)
            }
            return true
        }
        if (!arrow && !select) return super.dispatchKeyEvent(event)
        if (select) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) clickPointer()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val step = resources.displayMetrics.density * (if (event.repeatCount > 5) 24 else 12)
            val dx =
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    -step
                } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    step
                } else {
                    0f
                }
            val dy =
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    -step
                } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    step
                } else {
                    0f
                }
            val x = pointerX + dx
            val y = pointerY + dy
            // At an edge, scroll the document while keeping the pointer in the viewport.
            if (x < 0 || x > width - 1) scrollBy(dx.toInt(), 0)
            if (y < 0 || y > height - 1) scrollBy(0, dy.toInt())
            pointerX = maxOf(0f, minOf((width - 1).toFloat(), x))
            pointerY = maxOf(0f, minOf((height - 1).toFloat(), y))
            invalidate()
        }
        return true
    }

    private fun clickPointer() {
        if (width <= 0 || height <= 0) return
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, pointerX, pointerY, 0)
        val up = MotionEvent.obtain(time, time, MotionEvent.ACTION_UP, pointerX, pointerY, 0)
        try {
            dispatchTouchEvent(down)
            dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
        requestFocus()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasFocus()) return
        val radius = 7 * resources.displayMetrics.density
        val x = scrollX + pointerX
        val y = scrollY + pointerY
        pointer.style = Paint.Style.FILL
        pointer.color = Color.BLACK
        canvas.drawCircle(x, y, radius, pointer)
        pointer.style = Paint.Style.STROKE
        pointer.strokeWidth = 2 * resources.displayMetrics.density
        pointer.color = Color.WHITE
        canvas.drawCircle(x, y, radius, pointer)
        canvas.drawLine(x - radius * 1.6f, y, x + radius * 1.6f, y, pointer)
        canvas.drawLine(x, y - radius * 1.6f, x, y + radius * 1.6f, pointer)
    }
}
