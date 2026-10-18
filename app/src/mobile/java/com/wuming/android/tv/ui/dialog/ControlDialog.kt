package com.wuming.android.tv.ui.dialog

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.viewbinding.ViewBinding
import androidx.lifecycle.Lifecycle
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.History
import com.wuming.android.tv.bean.Parse
import com.wuming.android.tv.databinding.ActivityVideoBinding
import com.wuming.android.tv.databinding.DialogControlBinding
import com.wuming.android.tv.player.PlayerManager
import com.wuming.android.tv.ui.adapter.ParseAdapter
import com.wuming.android.tv.ui.base.ViewType
import com.wuming.android.tv.ui.custom.SpaceItemDecoration
import com.wuming.android.tv.utils.LifecycleDelayedAction
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.Timer
import com.wuming.android.tv.utils.UiSafe
import com.google.android.material.slider.Slider
import java.lang.ref.WeakReference
import java.util.Arrays

open class ControlDialog :
    BaseDialog(),
    com.wuming.android.tv.ui.adapter.BaseParseAdapter.OnClickListener {
    private var binding: DialogControlBinding? = null
    private var parentBinding: ActivityVideoBinding? = null
    private val parent: ActivityVideoBinding get() = checkNotNull(parentBinding)
    private var ownerActivity: FragmentActivity? = null
    private var scales: List<TextView> = emptyList()
    private var playerManager: PlayerManager? = null
    private val player: PlayerManager get() = checkNotNull(playerManager)
    private val scale = ResUtil.getStringArray(R.array.select_scale)
    private var listener: Listener? = null
    private var history: History? = null
    private var parse = false

    companion object {
        @JvmStatic fun create(): ControlDialog = ControlDialog()
    }

    open fun parent(parent: ActivityVideoBinding): ControlDialog {
        this.parentBinding = parent
        return this
    }

    open fun history(history: History?): ControlDialog {
        this.history = history
        return this
    }

    open fun parse(parse: Boolean): ControlDialog {
        this.parse = parse
        return this
    }

    open fun player(player: PlayerManager): ControlDialog {
        this.playerManager = player
        return this
    }

    open fun show(activity: FragmentActivity): ControlDialog {
        if (activity.isFinishing || activity.isDestroyed || isDialogShowing(activity) ||
            activity.supportFragmentManager.isStateSaved || activity !is Listener
        ) return this
        listener = activity
        ownerActivity = activity
        show(activity.supportFragmentManager, null)
        return this
    }

    override fun getBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): ViewBinding {
        val binding = DialogControlBinding.inflate(inflater, container, false)
        this.binding = binding
        scales = Arrays.asList(binding.scale0, binding.scale1, binding.scale2, binding.scale3, binding.scale4)
        return binding
    }

    override fun releaseBinding() {
        binding = null
        parentBinding = null
        playerManager = null
        listener = null
        ownerActivity = null
        history = null
        scales = emptyList()
    }

    private fun hasDependencies(): Boolean = parentBinding != null && playerManager != null && listener != null

    override fun initView() {
        // Restored fragments cannot reuse a previous activity's binding/player.
        if (!hasDependencies()) {
            dismissAllowingStateLoss()
            return
        }
        binding!!.decode.text = parent.control.action.decode.text
        binding!!.ending.text = parent.control.action.ending.text
        binding!!.opening.text =
            parent.control.action.opening.text
        binding!!.loop.isActivated = parent.control.action.loop.isActivated
        binding!!.timer.isActivated = Timer.get().isRunning()
        setTrackVisible()
        setTitleVisible()
        setScaleText()
        setPlayer()
        setParse()
    }

    override fun initEvent() {
        if (!hasDependencies()) return
        val binding = binding!!
        binding.timer.setOnClickListener(this::onTimer)
        binding.speed.addOnChangeListener(this::setSpeed)
        for (view in scales) view.setOnClickListener(this::setScale)
        binding.text.setOnClickListener { dismiss(parent.control.action.subtitleTracks) }
        binding.audio.setOnClickListener { dismiss(parent.control.action.audio) }
        binding.video.setOnClickListener { dismiss(parent.control.action.videoTracks) }
        binding.title.setOnClickListener { dismiss(parent.control.action.actionTitle) }
        binding.player.setOnClickListener { dismiss(parent.control.action.player) }
        binding.danmaku.setOnClickListener { dismiss(parent.control.action.danmakuSettings) }
        binding.cast.setOnClickListener(this::onCast)
        binding.loop.setOnClickListener { active(binding.loop, parent.control.action.loop) }
        binding.decode.setOnClickListener { click(binding.decode, parent.control.action.decode) }
        binding.ending.setOnClickListener { click(binding.ending, parent.control.action.ending) }
        binding.opening.setOnClickListener { click(binding.opening, parent.control.action.opening) }
        binding.player.setOnLongClickListener { longClick(binding.player, parent.control.action.player) }
        binding.ending.setOnLongClickListener { longClick(binding.ending, parent.control.action.ending) }
        binding.opening.setOnLongClickListener { longClick(binding.opening, parent.control.action.opening) }
    }

    private fun onTimer(view: View) {
        handoff { TimerDialog.create().show(it) }
        dismiss()
    }

    private fun onCast(view: View) {
        handoff { (it as? com.wuming.android.tv.ui.activity.VideoActivity)?.onCastDialog() }
        dismiss()
    }

    private fun setSpeed(
        slider: Slider,
        value: Float,
        fromUser: Boolean,
    ) {
        if (!hasDependencies() || binding == null) return
        parent.control.action.speed.text = player.setSpeed(value)
        history?.setSpeed(player.getSpeed())
    }

    private fun setScaleText() {
        for (i in scales.indices) {
            scales[i].text = scale[i]
            scales[i].isActivated =
                scales[i].text == parent.control.action.scale.text
        }
    }

    private fun setParse() {
        setParseVisible(parse)
        binding!!.parse.setHasFixedSize(true)
        binding!!.parse.itemAnimator = null
        binding!!.parse.addItemDecoration(SpaceItemDecoration(R.dimen.vs_8))
        binding!!.parse.adapter = ParseAdapter(this, ViewType.LIGHT)
    }

    private fun setScale(view: View) {
        if (!hasDependencies() || binding == null) return
        for (textView in scales) textView.isActivated = false
        listener?.onScale(view.tag.toString().toInt())
        view.isActivated =
            true
    }

    private fun active(
        view: View,
        target: TextView,
    ) {
        target.performClick()
        view.isActivated = target.isActivated
    }

    private fun click(
        view: TextView,
        target: TextView,
    ) {
        target.performClick()
        view.text = target.text
    }

    private fun longClick(
        view: TextView,
        target: TextView,
    ): Boolean {
        target.performLongClick()
        view.text = target.text
        return true
    }

    private fun dismiss(view: View) {
        val target = WeakReference(view)
        handoff { target.get()?.performClick() }
        dismiss()
    }

    private fun handoff(action: (FragmentActivity) -> Unit) {
        val host = ownerActivity ?: return
        val target = WeakReference(host)
        LifecycleDelayedAction.post(host, 200, Runnable {
            val current = target.get() ?: return@Runnable
            if (!current.isFinishing && !current.isDestroyed &&
                current.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                !current.supportFragmentManager.isStateSaved
            ) action(current)
        })
    }

    open fun setPlayer() {
        if (!hasDependencies() || binding == null) return
        binding!!.speed.value = maxOf(player.getSpeed(), 0.5f)
        binding!!.player.text = parent.control.action.player.text
        binding!!.decode.visibility = parent.control.action.decode.visibility
        binding!!.danmaku.visibility =
            parent.control.action.danmakuSettings.visibility
    }

    open fun setParseVisible(visible: Boolean) {
        if (!hasDependencies() || binding == null) return
        binding!!.parse.visibility = if (visible) View.VISIBLE else View.GONE
        binding!!.parseText.visibility =
            if (visible) View.VISIBLE else View.GONE
    }

    open fun setTrackVisible() {
        if (!hasDependencies() || binding == null) return
        val binding = binding!!
        binding.text.visibility = parent.control.action.subtitleTracks.visibility
        binding.audio.visibility = parent.control.action.audio.visibility
        binding.video.visibility =
            parent.control.action.videoTracks.visibility
        binding.track.visibility =
            if (binding.text.visibility == View.GONE && binding.audio.visibility == View.GONE &&
                binding.video.visibility == View.GONE
            ) {
                View.GONE
            } else {
                View.VISIBLE
            }
    }

    open fun setTitleVisible() {
        if (!hasDependencies() || binding == null) return
        binding!!.title.visibility = parent.control.action.actionTitle.visibility
    }

    override fun onItemClick(item: Parse) {
        val views = binding ?: return
        val adapter = views.parse.adapter ?: return
        listener?.onParse(item)
        UiSafe.notifyItemRangeChanged(views.parse, adapter)
    }

    interface Listener {
        fun onScale(tag: Int)

        fun onParse(item: Parse)
    }
}
