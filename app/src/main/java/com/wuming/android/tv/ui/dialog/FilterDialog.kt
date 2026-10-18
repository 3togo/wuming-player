package com.wuming.android.tv.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding
import com.wuming.android.tv.bean.Filter
import com.wuming.android.tv.databinding.DialogFilterBinding
import com.wuming.android.tv.impl.FilterCallback
import com.wuming.android.tv.ui.adapter.FilterAdapter
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.Util
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

open class FilterDialog : BaseDialog() {
    private var binding: DialogFilterBinding? = null
    private var callback: FilterCallback? = null
    private var filter: List<Filter>? = null

    companion object {
        @JvmStatic fun create(): FilterDialog = FilterDialog()
    }

    open fun filter(filter: List<Filter>): FilterDialog {
        this.filter = filter
        arguments = Bundle().apply { putParcelableArrayList("filters", ArrayList(filter)) }
        return this
    }

    open fun show(fragment: Fragment) {
        if (!fragment.isAdded || fragment.isRemoving) return
        for (f in fragment.childFragmentManager.fragments) if (f is BottomSheetDialogFragment) return
        if (fragment.childFragmentManager.isStateSaved || fragment !is FilterCallback) return
        callback = fragment
        show(fragment.childFragmentManager, null)
    }

    override fun getBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): ViewBinding =
        DialogFilterBinding.inflate(inflater, container, false).also {
            binding =
                it
        }

    override fun releaseBinding() {
        binding = null
        callback = null
        filter = null
    }

    override fun initView() {
        val target = callback ?: (parentFragment as? FilterCallback)
        @Suppress("DEPRECATION")
        val items = filter ?: arguments?.getParcelableArrayList<Filter>("filters")
        if (target == null || items == null) {
            dismissAllowingStateLoss()
            return
        }
        callback = target
        binding!!.recycler.adapter = FilterAdapter(target, items)
        binding!!.recycler.setHasFixedSize(Util.isLeanback() || !ResUtil.isPad())
    }
}
