package com.wuming.android.tv.ui.dialog

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.viewbinding.ViewBinding
import com.wuming.android.tv.utils.TextScale
import com.wuming.android.tv.utils.Util
import com.wuming.android.tv.utils.ViewCleanup
import com.google.android.material.sidesheet.SideSheetDialog

abstract class BaseSideSheetDialog : AppCompatDialogFragment() {
    protected abstract fun getBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): ViewBinding

    protected abstract fun getWidth(): Int

    @Suppress("DEPRECATION")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = SideSheetDialog(requireContext())
        dialog.behavior.isDraggable = false
        val window = dialog.window ?: return dialog
        activity?.let { host ->
            if (Util.isFullscreen(host)) window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = getBinding(inflater, container).root
        TextScale.apply(root)
        return root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initEvent()
    }

    override fun onDestroyView() {
        val root = view
        super.onDestroyView()
        ViewCleanup.release(root)
        releaseBinding()
    }

    protected abstract fun releaseBinding()

    protected open fun initView() {}

    protected open fun initEvent() {}

    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<FrameLayout>(com.google.android.material.R.id.m3_side_sheet) ?: return
        val params = sheet.layoutParams
        params.width = getWidth()
        sheet.layoutParams = params
    }
}
