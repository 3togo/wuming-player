package com.wuming.android.tv.ui.dialog

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewbinding.ViewBinding
import com.wuming.android.tv.R
import com.wuming.android.tv.databinding.DialogPassBinding
import com.wuming.android.tv.impl.PassCallback
import com.wuming.android.tv.utils.DialogUtil
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.TvAlertDialogBuilder

open class PassDialog : BaseDialog() {
    private var binding: DialogPassBinding? = null
    private var callback: PassCallback? = null

    companion object {
        @JvmStatic fun create(): PassDialog = PassDialog()
    }

    open fun show(activity: FragmentActivity) {
        if (isDialogShowing(activity) || activity !is PassCallback) return
        callback = activity
        show(activity.supportFragmentManager, null)
    }

    override fun getBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): ViewBinding =
        DialogPassBinding.inflate(inflater, container, false).also {
            binding =
                it
        }

    override fun releaseBinding() {
        binding = null
    }

    override fun initEvent() {
        val positive = binding!!.root.findViewById<View>(resId("positive"))
        if (positive != null) {
            positive.setOnClickListener { onPass() }
            binding!!.`pass`.setOnEditorActionListener { _, action, _ ->
                if (action ==
                    EditorInfo.IME_ACTION_DONE
                ) {
                    positive.performClick()
                }
                ; true
            }
        } else {
            binding!!.`pass`.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) onPass()
                true
            }
        }
    }

    private fun resId(name: String): Int = resources.getIdentifier(name, "id", requireContext().packageName)

    private fun onPass() {
        val pass =
            binding!!
                .`pass`.text
                .toString()
                .trim { it <= ' ' }
        if (pass.isNotEmpty()) callback?.setPass(pass)
        dismiss()
    }

    override fun onResume() {
        super.onResume()
        dialog?.window?.setLayout(ResUtil.getDimensionPixelSize(R.dimen.vs_250), -1)
    }

    override fun onDestroyView() {
        callback = null
        super.onDestroyView()
    }
}
