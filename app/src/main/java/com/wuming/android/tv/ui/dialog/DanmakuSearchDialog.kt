package com.wuming.android.tv.ui.dialog

import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewbinding.ViewBinding
import com.wuming.android.tv.R
import com.wuming.android.tv.api.DanmakuApi
import com.wuming.android.tv.bean.Danmaku
import com.wuming.android.tv.databinding.DialogDanmakuSearchBinding
import com.wuming.android.tv.player.PlayerManager
import com.wuming.android.tv.ui.adapter.DanmakuAdapter
import com.wuming.android.tv.ui.custom.SpaceItemDecoration
import com.wuming.android.tv.utils.KeyUtil
import com.wuming.android.tv.utils.Notify
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.UiTaskScope
import com.wuming.android.tv.utils.Util
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.lang.ref.WeakReference

class DanmakuSearchDialog :
    BaseDialog(),
    DanmakuAdapter.OnClickListener {
    private val adapter = DanmakuAdapter(this)
    private var binding: DialogDanmakuSearchBinding? = null
    private var player: PlayerManager? = null
    private var request: SearchRequest? = null

    companion object {
        @JvmStatic fun create(): DanmakuSearchDialog = DanmakuSearchDialog()
    }

    fun player(player: PlayerManager?): DanmakuSearchDialog {
        this.player = player
        return this
    }

    fun show(activity: FragmentActivity) {
        if (activity.supportFragmentManager.fragments.any { it is DanmakuSearchDialog }) return
        show(activity.supportFragmentManager, null)
    }

    override fun getBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): ViewBinding =
        DialogDanmakuSearchBinding.inflate(inflater, container, false).also {
            binding =
                it
        }

    override fun releaseBinding() {
        binding = null
    }

    override fun initView() {
        if (player == null) {
            dismissAllowingStateLoss()
            return
        }
        val binding = binding!!
        binding.recycler.adapter = adapter
        binding.recycler.itemAnimator = null
        binding.recycler.setHasFixedSize(false)
        binding.recycler.addItemDecoration(SpaceItemDecoration(1, R.dimen.vs_16))
        setKeyword(player!!.getMetadata()!!.title!!)
        Util.showKeyboard(binding.keyword)
    }

    override fun initEvent() {
        if (player == null) return
        binding!!.keyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH &&
                binding!!
                    .keyword.text
                    .toString()
                    .trim { it <= ' ' }
                    .isNotEmpty()
            ) {
                search()
            }
            true
        }
        binding!!.keyword.setOnKeyListener { _, _, event ->
            KeyUtil.isActionDown(event) && KeyUtil.isDownKey(event) && binding!!.recycler.visibility == android.view.View.VISIBLE &&
                binding!!.recycler.requestFocus()
        }
    }

    override fun onItemClick(item: Danmaku) {
        player!!.setDanmaku(if (item.isSelected()) Danmaku.empty() else item)
        dismiss()
    }

    private fun setKeyword(text: CharSequence) {
        binding!!.keyword.setText(text)
        binding!!.keyword.setSelection(text.length)
    }

    private fun showProgress() {
        binding!!.recycler.visibility = android.view.View.GONE
        binding!!.progress.visibility =
            android.view.View.VISIBLE
    }

    private fun hideProgress(empty: Boolean) {
        binding!!.progress.visibility = android.view.View.GONE
        binding!!.recycler.visibility =
            if (empty) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun search() {
        showProgress()
        adapter.clear()
        Util.hideKeyboard(binding!!.keyword)
        cancelSearch()
        try {
            val call =
                DanmakuApi.createCall(
                    binding!!
                        .keyword.text
                        .toString()
                        .trim { it <= ' ' },
                    player!!.getMetadata()!!.artist!!.toString().trim {
                        it <=
                            ' '
                    },
                )
            request = SearchRequest(this, call)
            call.enqueue(request!!)
        } catch (error: RuntimeException) {
            cancelSearch()
            onError(error)
        }
    }

    private fun onSuccess(items: List<Danmaku>) {
        if (binding ==
            null
        ) {
            return
        }
        adapter.addAll(items)
        hideProgress(items.isEmpty())
        binding!!.recycler.requestFocus()
    }

    private fun onError(e: Exception) {
        if (binding == null) return
        hideProgress(true)
        Notify.show(e.message ?: "")
    }

    private fun cancelSearch() {
        request?.close()
        request = null
    }

    /** Network and queued callbacks never retain the dialog, its views or player. */
    private class SearchRequest(
        owner: DanmakuSearchDialog,
        private val call: Call,
    ) : Callback,
        AutoCloseable {
        private val ownerRef = WeakReference(owner)
        private val deliveries = UiTaskScope()

        override fun onResponse(
            call: Call,
            response: Response,
        ) {
            try {
                response.use { current ->
                    if (call.isCanceled()) return
                    val body = current.body
                    if (!current.isSuccessful) throw IOException("Failed response")
                    val items = Danmaku.arrayFrom(body.string())
                    if (items.isEmpty()) throw IOException(ResUtil.getString(R.string.error_empty))
                    deliver(items, null)
                }
            } catch (error: Exception) {
                deliver(null, error)
            }
        }

        override fun onFailure(
            call: Call,
            e: IOException,
        ) {
            deliver(null, e)
        }

        private fun deliver(
            items: List<Danmaku>?,
            error: Exception?,
        ) {
            deliveries.post({
                val owner = ownerRef.get()
                if (owner != null && owner.request === this && owner.binding != null && !call.isCanceled()) {
                    owner.request = null
                    close()
                    if (error == null) owner.onSuccess(items!!) else owner.onError(error)
                }
            }, 0)
        }

        override fun close() {
            ownerRef.clear()
            deliveries.close()
            call.cancel()
        }
    }

    override fun onDestroyView() {
        cancelSearch()
        adapter.clear()
        player = null
        super.onDestroyView()
    }
}
