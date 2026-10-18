package com.wuming.android.tv.ui.dialog

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewbinding.ViewBinding
import com.wuming.android.tv.R
import com.wuming.android.tv.databinding.DialogTimerBinding
import com.wuming.android.tv.utils.DialogUtil
import com.wuming.android.tv.utils.Timer
import com.wuming.android.tv.utils.TvAlertDialogBuilder
import com.wuming.android.tv.utils.Util
import java.lang.ref.WeakReference
import java.util.Formatter
import java.util.Locale
import java.util.concurrent.TimeUnit

open class TimerDialog : BaseDialog() {
    private var binding: DialogTimerBinding? = null
    private val builder = StringBuilder()
    private val formatter = Formatter(builder, Locale.getDefault())
    private val timerCallback = TimerCallback(this)

    companion object {
        @JvmStatic fun create(): TimerDialog = TimerDialog()
    }

    open fun show(activity: FragmentActivity) {
        if (isDialogShowing(activity)) return
        show(activity.supportFragmentManager, null)
    }

    override fun getBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): ViewBinding =
        DialogTimerBinding.inflate(inflater, container, false).also {
            binding =
                it
        }

    override fun releaseBinding() {
        binding = null
    }

    override fun initView() {
        onTick(Timer.get().getTick())
        binding!!.list.visibility = if (Timer.get().isRunning()) View.GONE else View.VISIBLE
        binding!!.timer.visibility = if (Timer.get().isRunning()) View.VISIBLE else View.GONE
    }

    override fun initEvent() {
        Timer.get().setCallback(timerCallback)
        binding!!.delay.setOnClickListener { Timer.get().delay() }
        binding!!.reset.setOnClickListener {
            Timer.get().reset()
            dismiss()
        }
        binding!!.time1.setOnClickListener(::setTimer)
        binding!!.time2.setOnClickListener(::setTimer)
        binding!!.time3.setOnClickListener(::setTimer)
        binding!!.time4.setOnClickListener(::setTimer)
    }

    private fun setTimer(view: View) {
        val minutes = view.tag.toString().toInt()
        Timer.get().set(TimeUnit.MINUTES.toMillis(minutes.toLong()))
        dismiss()
    }

    open fun onTick(tick: Long) {
        binding?.tick?.text = Util.format(builder, formatter, tick)
    }

    open fun onFinish() {
        dismiss()
    }

    override fun onDestroyView() {
        Timer.get().setCallback(null)
        super.onDestroyView()
    }

    private class TimerCallback(
        dialog: TimerDialog,
    ) : Timer.Callback {
        private val dialogRef = WeakReference(dialog)

        override fun onTick(tick: Long) {
            dialogRef.get()?.onTick(tick)
        }

        override fun onFinish() {
            dialogRef.get()?.onFinish()
        }
    }
}
