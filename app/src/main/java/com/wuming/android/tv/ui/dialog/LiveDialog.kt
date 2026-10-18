package com.wuming.android.tv.ui.dialog

import android.app.Activity
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.wuming.android.tv.R
import com.wuming.android.tv.api.config.LiveConfig
import com.wuming.android.tv.bean.Live
import com.wuming.android.tv.databinding.DialogLiveBinding
import com.wuming.android.tv.impl.LiveCallback
import com.wuming.android.tv.ui.adapter.LiveAdapter
import com.wuming.android.tv.ui.custom.SpaceItemDecoration
import com.wuming.android.tv.utils.AppLog
import com.wuming.android.tv.utils.LayoutConfig
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.TvAlertDialogBuilder
import com.wuming.android.tv.utils.UiSafe

open class LiveDialog : LiveAdapter.OnClickListener {
    private val callback: LiveCallback
    private lateinit var binding: DialogLiveBinding
    private lateinit var adapter: LiveAdapter
    private lateinit var dialog: AlertDialog
    private var full = false

    companion object {
        @JvmStatic fun create(activity: Activity): LiveDialog = LiveDialog(activity)

        @JvmStatic fun create(fragment: Fragment): LiveDialog = LiveDialog(fragment)
    }
    private constructor(activity: Activity) {
        callback = activity as LiveCallback
        full = true
        init(activity)
    }
    private constructor(fragment: Fragment) {
        callback = fragment as LiveCallback
        init(fragment.requireActivity())
    }

    private fun init(activity: Activity) {
        binding =
            DialogLiveBinding.inflate(
                com.wuming.android.tv.utils.DialogUtil
                    .inflater(activity),
            )
        dialog = TvAlertDialogBuilder(activity, R.style.DarkDialogStyle).setView(binding.root).create()
        adapter = LiveAdapter(this)
    }

    open fun action(): LiveDialog {
        adapter.setAction(true)
        return this
    }

    open fun show() {
        AppLog.i("LiveDialog", "show: full=" + full + " action=true itemCount=" + adapter.itemCount)
        setRecyclerView()
        setDialog()
    }

    private fun setRecyclerView() {
        binding.recycler.adapter = adapter
        binding.recycler.itemAnimator = null
        binding.recycler.setHasFixedSize(true)
        binding.recycler.addItemDecoration(SpaceItemDecoration(1, if (full) R.dimen.vs_8 else R.dimen.vs_16))
        binding.recycler.post { binding.recycler.scrollToPosition(LiveConfig.getHomeIndex()) }
        if (full) binding.recycler.setMaxHeight(ResUtil.getDimensionPixelSize(R.dimen.vs_264))
    }

    private fun setDialog() {
        if (adapter.itemCount == 0) return
        val window = dialog.window!!
        val params = window.attributes
        params.width =
            LayoutConfig.getResponsiveDialogWidth(
                dialog.context,
                if (full) LayoutConfig.LIST_DIALOG_LANDSCAPE_WIDTH_RATIO else LayoutConfig.LIST_DIALOG_PORTRAIT_WIDTH_RATIO,
                LayoutConfig.LIST_DIALOG_PORTRAIT_WIDTH_RATIO,
            )
        window.attributes = params
        window.setDimAmount(0f)
        dialog.show()
    }

    override fun onItemClick(item: Live) {
        callback.setLive(item)
        dialog.dismiss()
    }

    override fun onBootClick(
        position: Int,
        item: Live,
    ) {
        item.boot(!item.isBoot()).saveAsync()
        UiSafe.notifyItemChanged(binding.recycler, adapter, position)
    }

    override fun onPassClick(
        position: Int,
        item: Live,
    ) {
        item.pass(!item.isPass()).saveAsync()
        UiSafe.notifyItemChanged(binding.recycler, adapter, position)
    }

    override fun onBootLongClick(item: Live): Boolean {
        val result = !item.isBoot()
        for (live in LiveConfig.get().getLives()) live.boot(result).saveAsync()
        UiSafe.notifyItemRangeChanged(binding.recycler, adapter)
        return true
    }

    override fun onPassLongClick(item: Live): Boolean {
        val result = !item.isPass()
        for (live in LiveConfig.get().getLives()) live.pass(result).saveAsync()
        UiSafe.notifyItemRangeChanged(binding.recycler, adapter)
        return true
    }
}
