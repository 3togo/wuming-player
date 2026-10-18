package com.wuming.android.tv.ui.custom

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import com.wuming.android.tv.databinding.ViewEmptyBinding
import com.wuming.android.tv.databinding.ViewProgressBinding
import java.util.ArrayList

open class ProgressLayout : RelativeLayout {
    companion object {
        private const val TAG_PROGRESS = "ProgressLayout.TAG_PROGRESS"
    }

    enum class State { CONTENT, PROGRESS, EMPTY }

    private lateinit var mContentViews: MutableList<View>
    private lateinit var mProgressView: View
    private var mEmptyView: View? = null
    private var mState: State? = null
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle) {
        init()
    }

    private fun init() {
        mState = State.CONTENT
        mContentViews = ArrayList()
        initView()
    }

    private fun initView() {
        mProgressView = ViewProgressBinding.inflate(LayoutInflater.from(context)).root
        mProgressView.tag = TAG_PROGRESS
        mProgressView.visibility = GONE
        val params = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.addRule(CENTER_IN_PARENT)
        addView(mProgressView, params)
    }

    private fun ensureEmptyView() {
        if (mEmptyView != null) return
        mEmptyView = ViewEmptyBinding.inflate(LayoutInflater.from(context)).root
        mEmptyView!!.tag = TAG_PROGRESS
        mEmptyView!!.visibility = GONE
        val params = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.addRule(CENTER_IN_PARENT)
        addView(mEmptyView, params)
    }

    override fun addView(
        child: View,
        index: Int,
        params: ViewGroup.LayoutParams,
    ) {
        super.addView(child, index, params)
        if (child.tag != TAG_PROGRESS) mContentViews.add(child)
    }

    open fun showProgress() {
        switchState(State.PROGRESS)
    }

    open fun showEmpty() {
        switchState(State.EMPTY)
    }

    open fun showContent() {
        switchState(State.CONTENT)
    }

    open fun showContent(
        flag: Boolean,
        size: Int,
    ) {
        if (flag && size == 0) showEmpty() else showContent()
    }

    open fun isProgress(): Boolean = mState == State.PROGRESS

    open fun isContent(): Boolean = mState == State.CONTENT

    open fun isEmpty(): Boolean = mState == State.EMPTY

    open fun switchState(state: State) {
        if (mState == state) return
        mState = state
        when (state) {
            State.CONTENT -> {
                mEmptyView?.visibility = GONE
                mProgressView.visibility = GONE
                setContentVisibility(true)
            }

            State.PROGRESS -> {
                mEmptyView?.visibility = GONE
                mProgressView.visibility = VISIBLE
                setContentVisibility(false)
            }

            State.EMPTY -> {
                ensureEmptyView()
                mEmptyView!!.visibility = VISIBLE
                mProgressView.visibility = GONE
                setContentVisibility(false)
            }
        }
    }

    private fun setContentVisibility(visible: Boolean) {
        for (view in mContentViews) if (visible) showView(view) else hideView(view)
    }

    private fun showView(view: View) {
        view.alpha = 0f
        view.visibility = VISIBLE
        view.animate().alpha(1f).setDuration(100)
    }

    private fun hideView(view: View) {
        view.visibility = INVISIBLE
    }
}
