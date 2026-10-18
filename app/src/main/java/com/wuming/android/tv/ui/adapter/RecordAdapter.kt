package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.App
import com.wuming.android.tv.databinding.AdapterSearchRecordBinding
import com.wuming.android.tv.db.AppDatabase
import com.wuming.android.tv.utils.Task
import java.lang.ref.WeakReference
import java.util.concurrent.Future

open class RecordAdapter(
    private val listener: OnClickListener,
) : RecyclerView.Adapter<RecordAdapter.ViewHolder>() {
    private val items = ArrayList<String>()
    private var loadTask: Future<*>? = null
    private var loadGeneration = 0

    interface OnClickListener {
        fun onItemClick(text: String)

        fun onDataChanged(size: Int)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        val generation = ++loadGeneration
        loadTask = Task.submit(loadHistory(WeakReference(this), generation))
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        ++loadGeneration
        loadTask?.cancel(true)
        loadTask = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    companion object {
        private const val MAX_ITEMS = 50

        // Keep the worker static: only the weak reference may retain the adapter.
        private fun loadHistory(
            adapterRef: WeakReference<RecordAdapter>,
            generation: Int,
        ): Runnable =
            Runnable {
                val history = AppDatabase.get().getSearchHistoryDao().getRecent(MAX_ITEMS)
                val keywords = ArrayList<String>()
                for (h in history) keywords.add(h.keyword)
                if (Thread.currentThread().isInterrupted) return@Runnable
                App.post {
                    val adapter = adapterRef.get()
                    if (adapter != null && adapter.loadGeneration == generation) {
                        adapter.loadTask = null
                        adapter.items.clear()
                        adapter.items.addAll(keywords)
                        adapter.notifyDataSetChanged()
                        adapter.listener.onDataChanged(adapter.itemCount)
                    }
                }
            }
    }

    open fun add(item: String) {
        Task.execute { AppDatabase.get().getSearchHistoryDao().insertOrUpdate(item) }
        items.remove(item)
        items.add(0, item)
        if (items.size > MAX_ITEMS) {
            val removed = items.size - MAX_ITEMS
            items.subList(MAX_ITEMS, items.size).clear()
            notifyItemRangeRemoved(MAX_ITEMS, removed)
        }
        notifyItemInserted(0)
        listener.onDataChanged(itemCount)
    }

    open fun clear() {
        items.clear()
        notifyDataSetChanged()
        listener.onDataChanged(0)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(AdapterSearchRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false), this)

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        holder.bind(items[position])
    }

    private fun removeItem(position: Int) {
        if (position < 0 || position >= items.size) return
        val keyword = items.removeAt(position)
        notifyItemRemoved(position)
        listener.onDataChanged(itemCount)
        Task.execute {
            val history = AppDatabase.get().getSearchHistoryDao().findByKeyword(keyword)
            if (history != null) AppDatabase.get().getSearchHistoryDao().delete(history)
        }
    }

    open class ViewHolder(
        private val binding: AdapterSearchRecordBinding,
        private val adapter: RecordAdapter,
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.text.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) adapter.listener.onItemClick(adapter.items[pos])
            }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) adapter.removeItem(pos)
                true
            }
        }

        open fun bind(text: String) {
            binding.text.text = text
        }
    }
}
