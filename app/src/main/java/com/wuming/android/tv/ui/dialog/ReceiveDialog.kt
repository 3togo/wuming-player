package com.wuming.android.tv.ui.dialog

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewbinding.ViewBinding
import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.bean.History
import com.wuming.android.tv.bean.VodShortName
import com.wuming.android.tv.databinding.DialogReceiveBinding
import com.wuming.android.tv.event.CastEvent
import com.wuming.android.tv.impl.Callback
import com.wuming.android.tv.library.UserLibrary
import com.wuming.android.tv.ui.activity.VideoActivity
import com.wuming.android.tv.utils.ImgUtil
import com.wuming.android.tv.utils.Notify
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.lang.ref.WeakReference

open class ReceiveDialog : BaseDialog() {
    private var binding: DialogReceiveBinding? = null
    private var event: CastEvent? = null

    companion object {
        @JvmStatic fun create(): ReceiveDialog = ReceiveDialog()
    }

    open fun event(event: CastEvent?): ReceiveDialog {
        this.event = event
        return this
    }

    open fun show(activity: FragmentActivity) {
        if (!isDialogShowing(activity)) show(activity.supportFragmentManager, null)
    }

    open fun show(fragment: Fragment) {
        if (fragment.childFragmentManager.fragments.any { it is BottomSheetDialogFragment }) return
        show(fragment.childFragmentManager, null)
    }

    override fun getBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): ViewBinding =
        DialogReceiveBinding.inflate(inflater, container, false).also {
            binding = it
        }

    override fun releaseBinding() {
        binding = null
    }

    override fun initView() {
        val event = event
        if (event == null) {
            dismissAllowingStateLoss()
            return
        }
        val item = event.history()
        binding!!.name.text = VodShortName.lookup(item.getVodName())
        binding!!.from.text = event.device().getName()
        ImgUtil.load(item.getVodName(), item.getVodPic(), binding!!.image)
    }

    override fun initEvent() {
        if (event != null) binding!!.frame.setOnClickListener { onReceiveCast() }
    }

    private fun showProgress() {
        binding!!.frame.isEnabled = false
        binding!!.play.visibility = View.GONE
        binding!!.progress.root.visibility =
            View.VISIBLE
    }

    private fun hideProgress() {
        binding!!.frame.isEnabled = true
        binding!!.play.visibility = View.VISIBLE
        binding!!.progress.root.visibility =
            View.GONE
    }

    private fun onReceiveCast() {
        val event = event ?: return
        if (binding == null || activity == null) return
        if (VodConfig.get().getConfig() == event.config()) {
            VideoActivity.cast(activity, UserLibrary.get().saveHistory(event.history(), VodConfig.getCid()), event.audioTrackFormat())
            dismiss()
        } else {
            showProgress()
            VodConfig.load(event.config(), LoadCallback(this))
        }
    }

    private fun onLoadSuccess() {
        if (binding == null || event == null) return
        hideProgress()
        onReceiveCast()
    }

    private fun onLoadError(msg: String) {
        if (binding == null) return
        Notify.show(msg)
        hideProgress()
    }

    override fun onDestroyView() {
        event = null
        super.onDestroyView()
    }

    private class LoadCallback(
        dialog: ReceiveDialog,
    ) : Callback() {
        private val dialogRef = WeakReference(dialog)

        override fun success() {
            dialogRef.get()?.onLoadSuccess()
        }

        override fun error(msg: String) {
            dialogRef.get()?.onLoadError(msg)
        }
    }
}
