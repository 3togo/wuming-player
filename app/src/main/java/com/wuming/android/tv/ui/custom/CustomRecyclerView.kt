package com.wuming.android.tv.ui.custom

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R

open class CustomRecyclerView : RecyclerView {
    private var minWidth = 0
    private var minHeight = 0
    private var maxWidth = 0
    private var maxHeight = 0
    private var touchSlop = 0
    private var x1 = 0f
    private var y1 = 0f
    private var focusPosition = 0
    private val focusTask = Runnable { focus(focusPosition) }
    constructor(context: Context) : super(context) {
        init(context, null)
    }
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context, attrs)
    }
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context, attrs)
    }

    private fun init(
        context: Context,
        attrs: AttributeSet?,
    ) {
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        if (attrs != null) setAttrs(context, attrs)
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    private fun setAttrs(
        context: Context,
        attrs: AttributeSet,
    ) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.CustomRecyclerView)
        minWidth = a.getDimensionPixelSize(R.styleable.CustomRecyclerView_android_minWidth, 0)
        minHeight = a.getDimensionPixelSize(R.styleable.CustomRecyclerView_android_minHeight, 0)
        maxWidth = a.getDimensionPixelSize(R.styleable.CustomRecyclerView_maxWidth, 0)
        maxHeight = a.getDimensionPixelSize(R.styleable.CustomRecyclerView_maxHeight, 0)
        a.recycle()
    }

    open fun setMinWidth(minWidth: Int) {
        this.minWidth = minWidth
    }

    open fun setMaxWidth(maxWidth: Int) {
        this.maxWidth = maxWidth
    }

    open fun setMinHeight(minHeight: Int) {
        this.minHeight = minHeight
    }

    open fun setMaxHeight(maxHeight: Int) {
        this.maxHeight = maxHeight
    }

    private fun getConstrainedSize(
        measuredSize: Int,
        minSize: Int,
        maxSize: Int,
    ): Int {
        var result = measuredSize
        if (maxSize > 0) result = minOf(result, maxSize)
        if (minSize > 0) result = maxOf(result, minSize)
        return result
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(
            getConstrainedSize(measuredWidth, minWidth, maxWidth),
            getConstrainedSize(measuredHeight, minHeight, maxHeight),
        )
    }

    private fun focus(position: Int) {
        findViewHolderForLayoutPosition(position)?.itemView?.requestFocus()
    }

    override fun scrollToPosition(position: Int) {
        super.scrollToPosition(position)
        removeCallbacks(focusTask)
        focusPosition = position
        postDelayed(focusTask, 50)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(focusTask)
        super.onDetachedFromWindow()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount != 1) return false
        when (event.action) {
            MotionEvent.ACTION_UP -> {
                x1 = 0f
                y1 = 0f
            }

            MotionEvent.ACTION_DOWN -> {
                x1 = event.x
                y1 = event.y
                parent.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                val offsetX = kotlin.math.abs(event.x - x1)
                val offsetY = kotlin.math.abs(event.y - y1)
                if (offsetX > offsetY && offsetX > touchSlop) parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.dispatchTouchEvent(event)
    }
}
