package com.wuming.android.tv.ui.custom

import androidx.collection.ArrayMap
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.PresenterSelector
import java.util.LinkedHashSet

open class CustomSelector : PresenterSelector() {
    private val presenters = LinkedHashSet<Presenter>()
    private val cacheMap = ArrayMap<Key, Presenter>()
    private val singleMap = ArrayMap<Class<*>, Presenter>()
    private val nestedMap = ArrayMap<Class<*>, ArrayMap<Class<out Presenter>, Presenter>>()

    open fun addPresenter(
        cls: Class<*>,
        presenter: Presenter,
    ) {
        singleMap[cls] = presenter
        presenters.add(presenter)
        cacheMap.clear()
    }

    open fun addPresenter(
        cls: Class<*>,
        presenter: Presenter,
        childType: Class<out Presenter>,
    ) {
        nestedMap.getOrPut(cls) { ArrayMap() }[childType] = presenter
        presenters.add(presenter)
        cacheMap.clear()
    }

    override fun getPresenter(item: Any?): Presenter? {
        if (item == null) return null
        val cls = item.javaClass
        singleMap[cls]?.let { return it }
        val map = nestedMap[cls] ?: return null
        if (map.isEmpty()) return null
        if (map.size == 1) {
            val only = map.valueAt(0)
            cacheMap[Key(cls, map.keyAt(0))] = only
            return only
        }
        if (item is ListRow) {
            val adapter = item.adapter ?: return null
            val child = adapter.getPresenter(item) ?: return null
            val childCls = child.javaClass
            val key = Key(cls, childCls)
            cacheMap[key]?.let { return it }
            var type: Class<*>? = childCls
            while (type != null && type != Any::class.java) {
                val found = map[type]
                if (found != null) {
                    cacheMap[key] = found
                    return found
                }
                type = type.superclass
            }
        }
        return null
    }

    override fun getPresenters(): Array<Presenter> = presenters.toTypedArray()

    private data class Key(
        val parent: Class<*>,
        val child: Class<*>,
    )
}
