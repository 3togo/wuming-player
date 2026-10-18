package com.wuming.android.tv.ui.dialog

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.ui.DefaultTrackNameProvider
import androidx.viewbinding.ViewBinding
import com.wuming.android.tv.App
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.Sub
import com.wuming.android.tv.bean.Track
import com.wuming.android.tv.databinding.DialogTrackBinding
import com.wuming.android.tv.player.PlayerHelper
import com.wuming.android.tv.player.PlayerManager
import com.wuming.android.tv.ui.adapter.TrackAdapter
import com.wuming.android.tv.ui.custom.SpaceItemDecoration
import com.wuming.android.tv.utils.DialogUtil
import com.wuming.android.tv.utils.FileChooser
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.TvAlertDialogBuilder
import com.wuming.android.tv.utils.Util

class TrackDialog :
    BaseDialog(),
    TrackAdapter.OnClickListener {
    private val adapter = TrackAdapter(this)
    private val provider = DefaultTrackNameProvider(App.get().resources)
    private var binding: DialogTrackBinding? = null
    private var player: PlayerManager? = null
    private var listener: Listener? = null
    private var type = 0

    companion object {
        @JvmStatic fun create(): TrackDialog = TrackDialog()
    }

    fun player(player: PlayerManager?): TrackDialog {
        this.player = player
        return this
    }

    fun type(type: Int): TrackDialog {
        this.type = type
        return this
    }

    fun show(activity: FragmentActivity) {
        if (isDialogShowing(activity) || activity !is Listener) return
        listener = activity
        show(activity.supportFragmentManager, null)
    }

    override fun getBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): ViewBinding =
        DialogTrackBinding.inflate(inflater, container, false).also {
            binding =
                it
        }

    override fun releaseBinding() {
        binding = null
    }

    override fun initView() {
        val current = player
        if (current == null || type < C.TRACK_TYPE_AUDIO || type > C.TRACK_TYPE_TEXT) {
            dismissAllowingStateLoss()
            return
        }
        val views = binding!!
        views.recycler.itemAnimator = null
        views.recycler.setHasFixedSize(true)
        views.recycler.adapter = adapter.replaceAll(getTrack())
        views.recycler.addItemDecoration(SpaceItemDecoration(1, R.dimen.vs_16))
        val recycler = views.recycler
        recycler.post { if (binding?.recycler === recycler) recycler.scrollToPosition(adapter.getSelected()) }
        views.recycler.visibility = if (adapter.itemCount == 0) View.GONE else View.VISIBLE
        views.choose.visibility = if (type == C.TRACK_TYPE_TEXT && current.isVod()) View.VISIBLE else View.GONE
        views.subtitle.visibility = if (type == C.TRACK_TYPE_TEXT && current.haveTrack(C.TRACK_TYPE_TEXT)) View.VISIBLE else View.GONE
        views.title.text = ResUtil.getStringArray(R.array.select_track)[type - 1]
    }

    override fun initEvent() {
        if (player == null) return
        binding!!.choose.setOnClickListener { showChooser() }
        binding!!.subtitle.setOnClickListener { onSubtitle() }
    }

    private fun onSubtitle() {
        val host = activity ?: return
        val target = java.lang.ref.WeakReference(listener)
        com.wuming.android.tv.utils.LifecycleDelayedAction.post(host, 100, Runnable {
            if (Util.isActive(host)) target.get()?.onSubtitleClick()
        })
        dismiss()
    }

    private fun showChooser() {
        FileChooser
            .from(
                launcher,
            ).show(
                arrayOf(
                    MimeTypes.APPLICATION_SUBRIP,
                    MimeTypes.TEXT_SSA,
                    MimeTypes.TEXT_VTT,
                    MimeTypes.APPLICATION_TTML,
                    "audio/*",
                    "text/*",
                    "application/octet-stream",
                ),
            )
        player!!.pause()
    }

    private fun getTrack(): List<Track> {
        val items = ArrayList<Track>()
        addTrack(items)
        return items
    }

    private fun addTrack(items: MutableList<Track>) {
        val groups = player!!.getCurrentTracks().groups
        for (group in groups) {
            if (group.type != type) continue
            for (j in 0 until group.length) {
                val format = group.getTrackFormat(j)
                val item = Track(type, provider.getTrackName(format), PlayerHelper.describeFormat(format))
                item.isSelected = group.isTrackSelected(j)
                items.add(item)
            }
        }
    }

    override fun onItemClick(item: Track) {
        player!!.setTrack(java.util.Arrays.asList(item.key(player!!.getKey()).save()))
        dismiss()
    }

    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data
            if (player == null || result.resultCode != Activity.RESULT_OK || uri == null || view == null) return@registerForActivityResult
            FileChooser.importFile(viewLifecycleOwner, uri) { path ->
                val current = player
                if (current != null) {
                    current.setSub(Sub.from(path))
                    dismiss()
                }
            }
        }

    override fun onDestroyView() {
        listener = null
        player = null
        adapter.clear()
        super.onDestroyView()
    }

    interface Listener {
        fun onSubtitleClick()
    }
}
