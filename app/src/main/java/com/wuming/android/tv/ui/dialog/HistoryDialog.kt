package com.wuming.android.tv.ui.dialog

import com.wuming.android.tv.utils.ControlSpacing
import android.app.Activity
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import com.wuming.android.tv.App
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.Config
import com.wuming.android.tv.databinding.DialogHistoryBinding
import com.wuming.android.tv.impl.ConfigCallback
import com.wuming.android.tv.ui.adapter.ConfigAdapter
import com.wuming.android.tv.ui.custom.SpaceItemDecoration
import com.wuming.android.tv.utils.LayoutConfig
import com.wuming.android.tv.utils.LifecycleCleanup
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.Task
import com.wuming.android.tv.utils.TvAlertDialogBuilder
import java.lang.ref.WeakReference
import java.util.concurrent.Future

open class HistoryDialog : ConfigAdapter.OnClickListener {
    private val callbackRef: WeakReference<ConfigCallback>
    private var binding: DialogHistoryBinding? = null
    private var adapter: ConfigAdapter? = null
    private var dialog: AlertDialog? = null
    private var readOnly = false
    private var full = false
    private var type = 0
    private var loadTask: Future<*>? = null
    private var generation = 0
    private var lifecycleCleanup: LifecycleCleanup? = null

    companion object {
        @JvmStatic fun create(activity: Activity): HistoryDialog = HistoryDialog(activity)

        @JvmStatic fun create(fragment: Fragment): HistoryDialog = HistoryDialog(fragment)
    }
    constructor(activity: Activity) {
        callbackRef = WeakReference(activity as ConfigCallback)
        full = true
        init(activity)
        if (activity is LifecycleOwner) bindLifecycle(activity)
    }
    constructor(fragment: Fragment) {
        callbackRef = WeakReference(fragment as ConfigCallback)
        init(fragment.requireActivity())
        bindLifecycle(fragment.viewLifecycleOwner)
    }

    open fun type(type: Int): HistoryDialog {
        this.type = type
        return this
    }

    private fun bindLifecycle(owner: LifecycleOwner) {
        lifecycleCleanup =
            LifecycleCleanup(owner) {
                dialog?.dismiss()
                clear()
            }
    }

    private fun init(activity: Activity) {
        binding =
            DialogHistoryBinding.inflate(
                com.wuming.android.tv.utils.DialogUtil
                    .inflater(activity),
            )
        dialog = TvAlertDialogBuilder(activity, R.style.DarkDialogStyle).setTitle(R.string.popup_config_history).setView(binding!!.root).setOnDismissListener { clear() }.create()
        adapter = ConfigAdapter(this)
    }

    open fun readOnly(): HistoryDialog {
        readOnly = true
        return this
    }

    open fun show() {
        if (dialog == null) return
        loadTask?.cancel(true)
        val ownerRef = WeakReference(this)
        val requestedType = type
        val requestId = ++generation
        loadTask =
            Task.submit {
                val items = Config.getAll(requestedType)
                App.post {
                    val owner = ownerRef.get()
                    if (owner != null && owner.generation == requestId && owner.binding != null && owner.adapter != null &&
                        owner.dialog != null
                    ) {
                        owner.loadTask = null
                        if (items.isEmpty()) {
                            owner.clear()
                        } else {
                            owner.setRecyclerView(items)
                            owner.setDialog()
                        }
                    }
                }
            }
    }

    private fun setRecyclerView(items: List<Config>) {
        val binding = binding!!
        val padding = com.wuming.android.tv.utils.ControlSpacing.dimensionPixels(dialog!!.context, R.dimen.ds_space_large)
        binding.recycler.setPadding(padding, padding, padding, padding)
        binding.recycler.itemAnimator = null
        binding.recycler.setHasFixedSize(false)
        binding.recycler.addItemDecoration(SpaceItemDecoration(1, R.dimen.ds_control_gap, dialog!!.context))
        binding.recycler.adapter = adapter!!.readOnly(readOnly).setItems(items)
        if (full) binding.recycler.setMaxHeight(ResUtil.getDimensionPixelSize(R.dimen.vs_264))
    }

    private fun setDialog() {
        if (adapter!!.itemCount == 0) return
        val dialog = dialog!!
        val window = dialog.window!!
        val params = window.attributes
        params.width =
            LayoutConfig.getScreenWidthPixels(
                if (full &&
                    ResUtil.isLand(dialog.context)
                ) {
                    LayoutConfig.HISTORY_DIALOG_COMPACT_WIDTH_RATIO
                } else {
                    LayoutConfig.HISTORY_DIALOG_FULL_WIDTH_RATIO
                },
            )
        window.attributes = params
        window.setDimAmount(0.35f)
        dialog.show()
    }

    override fun onTextClick(item: Config) {
        callbackRef.get()?.setConfig(item)
        dialog?.dismiss()
    }

    override fun onDeleteClick(item: Config) {
        val adapter = adapter
        if (adapter != null && adapter.remove(item) == 0) dialog?.dismiss()
    }

    private fun clear() {
        generation++
        loadTask?.cancel(true)
        loadTask = null
        lifecycleCleanup?.close()
        lifecycleCleanup = null
        callbackRef.clear()
        binding?.recycler?.adapter = null
        adapter = null
        binding = null
        dialog = null
    }
}
