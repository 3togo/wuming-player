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
import com.wuming.android.tv.databinding.DialogDohBinding
import com.wuming.android.tv.impl.DohCallback
import com.wuming.android.tv.ui.adapter.DohAdapter
import com.wuming.android.tv.ui.custom.SpaceItemDecoration
import com.wuming.android.tv.utils.DialogUtil
import com.wuming.android.tv.utils.LayoutConfig
import com.wuming.android.tv.utils.TvAlertDialogBuilder
import com.github.catvod.bean.Doh

open class DohDialog(
    activity: Activity,
) : DohAdapter.OnClickListener {
    private val callback = activity as DohCallback
    private val binding = DialogDohBinding.inflate(DialogUtil.inflater(activity))
    private val dialog = TvAlertDialogBuilder(activity).setView(binding.root).setOnDismissListener { clear() }.create()
    private val adapter = DohAdapter(this)

    companion object {
        @JvmStatic fun create(activity: Activity): DohDialog = DohDialog(activity)
    }

    open fun index(index: Int): DohDialog {
        adapter.setSelect(index)
        return this
    }

    open fun show() {
        setRecyclerView()
        setDialog()
    }

    private fun setRecyclerView() {
        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
        binding.recycler.addItemDecoration(SpaceItemDecoration(1, R.dimen.vs_16))
        binding.recycler.post { binding.recycler.scrollToPosition(adapter.getSelect()) }
    }

    private fun setDialog() {
        if (adapter.itemCount == 0) return
        val window = dialog.window!!
        val params = window.attributes
        params.width = LayoutConfig.getScreenWidthPixels(LayoutConfig.DOH_DIALOG_WIDTH_RATIO)
        window.attributes = params
        window.setDimAmount(0f)
        dialog.show()
    }

    override fun onItemClick(item: Doh) {
        callback.setDoh(item)
        dialog.dismiss()
    }

    private fun clear() {
        binding.recycler.adapter = null
        dialog.dismiss()
    }
}
