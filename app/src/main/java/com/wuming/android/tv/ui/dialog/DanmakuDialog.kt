package com.wuming.android.tv.ui.dialog

import android.app.Activity
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.BeanUtils
import com.wuming.android.tv.bean.Danmaku
import com.wuming.android.tv.databinding.DialogDanmakuBinding
import com.wuming.android.tv.player.PlayerManager
import com.wuming.android.tv.setting.DanmakuSetting
import com.wuming.android.tv.ui.adapter.DanmakuAdapter
import com.wuming.android.tv.ui.custom.SpaceItemDecoration
import com.wuming.android.tv.utils.FileChooser

class DanmakuDialog :
    BaseDialog(),
    DanmakuAdapter.OnClickListener {
    private val adapter = DanmakuAdapter(this)
    private var binding: DialogDanmakuBinding? = null
    private var player: PlayerManager? = null

    companion object {
        @JvmStatic fun create(): DanmakuDialog = DanmakuDialog()
    }

    fun player(player: PlayerManager?): DanmakuDialog {
        this.player = player
        return this
    }

    fun show(activity: FragmentActivity) {
        if (!isDialogShowing(activity)) show(activity.supportFragmentManager, null)
    }

    override fun getBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): ViewBinding =
        DialogDanmakuBinding.inflate(inflater, container, false).also {
            binding = it
        }

    override fun releaseBinding() {
        binding = null
    }

    override fun initView() {
        val player = player
        if (player == null) {
            dismissAllowingStateLoss()
            return
        }
        adapter.clear()
        val binding = binding!!
        binding.recycler.itemAnimator = null
        binding.recycler.setHasFixedSize(true)
        binding.recycler.adapter = adapter.addAll(player.getDanmakus().orEmpty())
        binding.recycler.addItemDecoration(SpaceItemDecoration(1, R.dimen.vs_16))
        val recycler = binding.recycler
        recycler.post { if (this.binding?.recycler === recycler) recycler.scrollToPosition(adapter.getSelected()) }
        binding.recycler.visibility = if (adapter.itemCount == 0) View.GONE else View.VISIBLE
        val hasSearch = player.getMetadata() != null && BeanUtils.isNotEmpty(DanmakuSetting.getEffectiveApiUrl())
        binding.search.visibility = if (hasSearch) View.VISIBLE else View.GONE
    }

    override fun initEvent() {
        if (player == null) return
        binding!!.choose.setOnClickListener(this::showChooser)
        binding!!.search.setOnClickListener(this::onSearch)
        binding!!.setting.setOnClickListener(this::onSetting)
    }

    private fun showChooser(view: View) {
        FileChooser.from(launcher).show(arrayOf("text/*"))
        player!!.pause()
    }

    private fun onSearch(view: View) {
        DanmakuSearchDialog.create().player(player).show(requireActivity())
    }

    private fun onSetting(view: View) {
        DanmakuSettingDialog.create().player(player).show(requireActivity())
    }

    override fun onItemClick(item: Danmaku) {
        player!!.setDanmaku(if (item.isSelected()) Danmaku.empty() else item)
        dismiss()
    }

    private val launcher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data
            if (player == null || result.resultCode != Activity.RESULT_OK || uri == null || view == null) return@registerForActivityResult
            FileChooser.importFile(viewLifecycleOwner, uri) { path ->
                val player = player
                if (player != null) {
                    player.setDanmaku(Danmaku.from(path))
                    dismiss()
                }
            }
        }

    override fun onDestroyView() {
        player = null
        adapter.clear()
        super.onDestroyView()
    }
}
