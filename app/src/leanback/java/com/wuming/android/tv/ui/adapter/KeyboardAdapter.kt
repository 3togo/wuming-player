package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R
import com.wuming.android.tv.databinding.AdapterKeyboardIconBinding
import com.wuming.android.tv.databinding.AdapterKeyboardTextBinding
import java.util.ArrayList
import java.util.Arrays

open class KeyboardAdapter(
    private val listener: OnClickListener,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    companion object {
        private val icons =
            listOf(
                R.drawable.ic_keyboard_remote,
                R.drawable.ic_keyboard_left,
                R.drawable.ic_keyboard_right,
                R.drawable.ic_keyboard_back,
                R.drawable.ic_keyboard_search,
                R.drawable.ic_keyboard,
                R.drawable.ic_setting_home,
            )
        private val enList =
            listOf(
                "A",
                "B",
                "C",
                "D",
                "E",
                "F",
                "G",
                "H",
                "I",
                "J",
                "K",
                "L",
                "M",
                "N",
                "O",
                "P",
                "Q",
                "R",
                "S",
                "T",
                "U",
                "V",
                "W",
                "X",
                "Y",
                "Z",
                "0",
                "1",
                "2",
                "3",
                "4",
                "5",
                "6",
                "7",
                "8",
                "9",
            )
    }

    private val items = ArrayList<Any>()

    init {
        items.addAll(icons)
        items.addAll(enList)
    }

    interface OnClickListener {
        fun onTextClick(text: String)

        fun onIconClick(resId: Int)

        fun onLongClick(resId: Int): Boolean
    }

    override fun getItemViewType(position: Int): Int = if (items[position] is String) 0 else 1

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder =
        if (viewType ==
            0
        ) {
            TextHolder(AdapterKeyboardTextBinding.inflate(LayoutInflater.from(parent.context), parent, false), this)
        } else {
            IconHolder(AdapterKeyboardIconBinding.inflate(LayoutInflater.from(parent.context), parent, false), this)
        }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (getItemViewType(position)) {
            0 -> (holder as TextHolder).bind(items[position].toString())
            1 -> (holder as IconHolder).bind(items[position] as Int)
        }
    }

    open class TextHolder(
        private val binding: AdapterKeyboardTextBinding,
        adapter: KeyboardAdapter,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var textValue: String? = null

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                val text = textValue
                if (pos != RecyclerView.NO_POSITION &&
                    text != null
                ) {
                    adapter.listener.onTextClick(text)
                }
            }
        }

        open fun bind(value: String) {
            textValue = value
            binding.text.text = value
        }
    }

    open class IconHolder(
        private val binding: AdapterKeyboardIconBinding,
        adapter: KeyboardAdapter,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var iconResId = 0

        init {
            itemView.setOnClickListener { if (bindingAdapterPosition != RecyclerView.NO_POSITION) adapter.listener.onIconClick(iconResId) }
            itemView.setOnLongClickListener {
                if (bindingAdapterPosition !=
                    RecyclerView.NO_POSITION
                ) {
                    adapter.listener.onLongClick(iconResId)
                } else {
                    false
                }
            }
        }

        open fun bind(resId: Int) {
            iconResId = resId
            binding.icon.setImageResource(resId)
        }
    }
}
