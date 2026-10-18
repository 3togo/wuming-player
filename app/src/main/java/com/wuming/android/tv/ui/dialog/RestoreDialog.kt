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
import com.wuming.android.tv.App
import com.wuming.android.tv.R
import com.wuming.android.tv.databinding.DialogRestoreBinding
import com.wuming.android.tv.db.AppDatabase
import com.wuming.android.tv.impl.Callback
import com.wuming.android.tv.ui.adapter.BaseFileAdapter
import com.wuming.android.tv.ui.adapter.RestoreAdapter
import com.wuming.android.tv.ui.custom.SpaceItemDecoration
import com.wuming.android.tv.utils.DialogUtil
import com.wuming.android.tv.utils.LayoutConfig
import com.wuming.android.tv.utils.LifecycleCallback
import com.wuming.android.tv.utils.TvAlertDialogBuilder
import java.io.File

open class RestoreDialog : BaseFileAdapter.OnClickListener {
    private var binding: DialogRestoreBinding? = null
    private var adapter: RestoreAdapter? = null
    private var callback: Callback? = null
    private var context: Context? = null
    private var dialog: AlertDialog? = null

    companion object {
        @JvmStatic fun create(): RestoreDialog = RestoreDialog()

        @JvmStatic fun create(fragment: Fragment): RestoreDialog = RestoreDialog().also { it.context = fragment.requireContext() }

        @JvmStatic fun create(activity: FragmentActivity): RestoreDialog = RestoreDialog().also { it.context = activity }
    }

    open fun show(
        activity: FragmentActivity,
        callback: Callback?,
    ) {
        context = activity
        show(callback)
    }

    open fun show(callback: Callback?) {
        this.callback = callback
        val currentContext = context ?: return
        binding = DialogRestoreBinding.inflate(DialogUtil.inflater(currentContext))
        adapter = RestoreAdapter(this)
        if (adapter!!.itemCount == 0) return
        initDialog()
    }

    private fun initDialog() {
        val currentContext = context ?: return
        dialog = TvAlertDialogBuilder(currentContext).setView(binding!!.root).setOnDismissListener { clear() }.create()
        val window = dialog!!.window!!
        val params = window.attributes
        params.width =
            LayoutConfig.getResponsiveDialogWidth(
                currentContext,
                LayoutConfig.COMPACT_DIALOG_LANDSCAPE_WIDTH_RATIO,
                LayoutConfig.COMPACT_DIALOG_PORTRAIT_WIDTH_RATIO,
            )
        window.attributes = params
        window.setDimAmount(0f)
        initRecyclerView()

        dialog!!.show()
    }

    private fun initRecyclerView() {
        val recycler = binding!!.recycler
        recycler.adapter = adapter
        recycler.itemAnimator = null
        recycler.setHasFixedSize(false)
        recycler.addItemDecoration(SpaceItemDecoration(1, R.dimen.vs_16))
        recycler.visibility = if (adapter!!.itemCount == 0) View.GONE else View.VISIBLE
    }

    override fun onItemClick(item: File) {
        AppDatabase.restore(item, callback)
    }

    override fun onDeleteClick(item: File) {
        if (adapter!!.remove(item) == 0) binding!!.recycler.visibility = View.GONE
    }

    private fun clear() {
        (callback as? LifecycleCallback)?.close()
        binding?.recycler?.adapter = null
        adapter = null
        binding = null
        callback = null
        context = null
        dialog = null
    }
}
