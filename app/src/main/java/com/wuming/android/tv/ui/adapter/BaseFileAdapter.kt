package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.App
import com.wuming.android.tv.databinding.AdapterRestoreBinding
import com.wuming.android.tv.utils.Formatters
import com.github.catvod.utils.Path
import java.io.File
import java.time.Instant

abstract class BaseFileAdapter<VH : RecyclerView.ViewHolder>(
    @JvmField protected val listener: OnClickListener?,
) : RecyclerView.Adapter<VH>() {
    @JvmField protected val mItems: MutableList<File> = ArrayList()

    interface OnClickListener {
        fun onItemClick(item: File)

        fun onDeleteClick(item: File)
    }

    companion object {
        @JvmStatic fun resId(name: String): Int = App.get().resources.getIdentifier(name, "id", App.get().packageName)
    }

    open fun addAll() {
        mItems.clear()
        for (file in Path.tv().listFiles() ?: emptyArray()) {
            if (file.name.startsWith("tv") && file.name.endsWith(".bk.gz")) mItems.add(file)
        }
        if (mItems.isNotEmpty()) mItems.sortWith { a, b -> java.lang.Long.compare(b.lastModified(), a.lastModified()) }
        notifyDataSetChanged()
    }

    open fun remove(item: File): Int {
        val position = mItems.indexOf(item)
        if (position == -1) return -1
        Path.clear(item)
        mItems.removeAt(position)
        notifyItemRemoved(position)
        return itemCount
    }

    override fun getItemCount(): Int = mItems.size

    open fun get(position: Int): File = mItems[position]

    open fun getItems(): MutableList<File> = mItems

    open class FileViewHolder(
        private val binding: AdapterRestoreBinding,
        private val adapter: BaseFileAdapter<*>,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var nameView: View? = null
        private var timeView: View? = null
        private var textView: View? = null
        private var deleteView: View? = null
        private var viewsResolved = false

        open fun bind(item: File) {
            resolveViews()
            val name = nameView
            if (name is TextView) {
                name.text = item.name
                (timeView as? TextView)?.text = Formatters.TIME_SEC.format(Instant.ofEpochMilli(item.lastModified()))
            } else {
                (textView as? TextView)?.text = item.name
            }
        }

        private fun resolveViews() {
            if (viewsResolved) return
            viewsResolved = true
            val nameId = resId("name")
            val timeId = resId("time")
            val textId = resId("text")
            val deleteId = resId("delete")
            nameView = if (nameId != 0) binding.root.findViewById(nameId) else null
            timeView = if (timeId != 0) binding.root.findViewById(timeId) else null
            textView = if (textId != 0) binding.root.findViewById(textId) else null
            deleteView = if (deleteId != 0) binding.root.findViewById(deleteId) else null
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) adapter.listener?.onItemClick(adapter.mItems[pos])
            }
            textView?.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) adapter.listener?.onItemClick(adapter.mItems[pos])
            }
            deleteView?.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) adapter.listener?.onDeleteClick(adapter.mItems[pos])
            }
        }
    }
}
