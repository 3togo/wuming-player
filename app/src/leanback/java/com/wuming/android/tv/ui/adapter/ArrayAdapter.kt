package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R
import com.wuming.android.tv.databinding.AdapterArrayBinding
import com.wuming.android.tv.utils.ResUtil
import java.util.ArrayList

open class ArrayAdapter(
    private val mListener: OnClickListener?,
) : RecyclerView.Adapter<ArrayAdapter.ViewHolder>() {
    companion object {
        private const val TYPE_NONE = 0
        private const val TYPE_REVERSE = 1
        private const val TYPE_PLAY = 2
    }

    private val mItems = ArrayList<String>()
    private val forward = ResUtil.getString(R.string.play_forward)
    private val backward = ResUtil.getString(R.string.play_backward)
    private var nextFocusUp = View.NO_ID
    private var nextFocusDown = View.NO_ID

    open fun addAll(items: List<String>) {
        mItems.clear()
        mItems.addAll(items)
        notifyDataSetChanged()
    }

    open fun clear() {
        mItems.clear()
        notifyDataSetChanged()
    }

    open fun setNextFocus(
        up: Int,
        down: Int,
    ) {
        nextFocusUp = up
        nextFocusDown = down
    }

    override fun getItemCount(): Int = mItems.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(AdapterArrayBinding.inflate(LayoutInflater.from(parent.context), parent, false), mListener)

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val text = mItems[position]
        holder.binding.text.text = text
        val actionType =
            if (position == 0) {
                TYPE_REVERSE
            } else if (text == backward || text == forward) {
                TYPE_PLAY
            } else {
                TYPE_NONE
            }
        holder.bind(text, actionType, holder.binding.text)
        holder.itemView.nextFocusUpId = nextFocusUp
        holder.itemView.nextFocusDownId = nextFocusDown
    }

    interface OnClickListener {
        fun onRevSort()

        fun onRevPlay(view: TextView)
    }

    open class ViewHolder(
        val binding: AdapterArrayBinding,
        listener: OnClickListener?,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: String? = null
        private var actionType = 0
        private var textView: TextView? = null

        init {
            binding.root.setOnClickListener {
                if (listener == null) return@setOnClickListener
                if (actionType == TYPE_REVERSE) {
                    listener.onRevSort()
                } else if (actionType == TYPE_PLAY) {
                    textView?.let(listener::onRevPlay)
                }
            }
        }

        open fun bind(
            text: String,
            actionType: Int,
            textView: TextView?,
        ) {
            item = text
            this.actionType = actionType
            this.textView =
                textView
        }
    }
}
