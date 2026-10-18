package com.wuming.android.tv.ui.dialog

import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewbinding.ViewBinding
import com.wuming.android.tv.R
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.TextScale
import com.wuming.android.tv.utils.ViewCleanup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

abstract class BaseDialog : BottomSheetDialogFragment() {
    companion object {
        /** Prevent multiple bottom sheets from stacking in the same activity. */
        @JvmStatic protected fun isDialogShowing(activity: FragmentActivity): Boolean =
            activity.supportFragmentManager.fragments.any { it is BottomSheetDialogFragment }
    }

    protected abstract fun getBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): ViewBinding

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

    protected open fun transparent(): Boolean = false

    protected open fun setDimAmount(amount: Float) {
        val window = dialog?.window ?: return
        window.setDimAmount(amount)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener { setBehavior(dialog) }
        setWindow(dialog)
        return dialog
    }

    private fun setBehavior(dialog: BottomSheetDialog) {
        val bottomSheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        if (transparent()) bottomSheet.setBackgroundColor(ResUtil.getColor(R.color.transparent))
        val behavior = BottomSheetBehavior.from(bottomSheet)
        behavior.isFitToContents = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
    }

    @Suppress("DEPRECATION")
    private fun setWindow(dialog: Dialog?) {
        val activity = activity ?: return
        if (dialog == null) return
        val dialogWindow = dialog.window ?: return
        val activityWindow = activity.window ?: return
        dialogWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val controller = WindowCompat.getInsetsController(activityWindow, activityWindow.decorView)
        val isFullscreen =
            controller.systemBarsBehavior == WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE ||
                (activityWindow.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0
        if (isFullscreen) WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) dialog?.window?.isNavigationBarContrastEnforced = false
    }
}
