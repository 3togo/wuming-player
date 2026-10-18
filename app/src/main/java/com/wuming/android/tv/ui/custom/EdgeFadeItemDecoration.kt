package com.wuming.android.tv.ui.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.TypedValue
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Adds edge gradients when a horizontal list extends beyond its visible width. */
open class EdgeFadeItemDecoration
    @JvmOverloads
    constructor(
        context: Context,
        fadeWidthDp: Int = 48,
    ) : RecyclerView.ItemDecoration() {
        private val fadeWidth =
            TypedValue
                .applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    fadeWidthDp.toFloat(),
                    context.resources.displayMetrics,
                ).toInt()
        private val paint = Paint().apply { style = Paint.Style.FILL }

        override fun onDrawOver(
            canvas: Canvas,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            super.onDrawOver(canvas, parent, state)
            if (parent.childCount == 0) return
            if (canScrollHorizontally(parent)) drawHorizontalFade(canvas, parent)
        }

        private fun canScrollHorizontally(parent: RecyclerView): Boolean {
            val manager = parent.layoutManager as? LinearLayoutManager ?: return false
            if (manager.orientation != LinearLayoutManager.HORIZONTAL) return false
            val first = parent.getChildAt(0) ?: return false
            val last = parent.getChildAt(parent.childCount - 1) ?: return false
            return last.right - first.left > parent.width
        }

        private fun drawHorizontalFade(
            canvas: Canvas,
            parent: RecyclerView,
        ) {
            val width = parent.width.toFloat()
            val height = parent.height.toFloat()
            paint.shader = LinearGradient(width - fadeWidth, 0f, width, 0f, 0x00000000, 0x80000000.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(width - fadeWidth, 0f, width, height, paint)
            if (parent.computeHorizontalScrollOffset() > 0) {
                paint.shader = LinearGradient(0f, 0f, fadeWidth.toFloat(), 0f, 0x80000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, fadeWidth.toFloat(), height, paint)
            }
            paint.shader = null
        }
    }
