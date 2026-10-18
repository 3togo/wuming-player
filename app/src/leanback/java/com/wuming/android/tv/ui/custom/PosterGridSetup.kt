package com.wuming.android.tv.ui.custom

import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.LeanbackProduct
import com.wuming.android.tv.R

/** Common RecyclerView setup for full-page TV poster grids. */
object PosterGridSetup {
    @JvmStatic
    fun configure(grid: RecyclerView, adapter: RecyclerView.Adapter<*>) {
        val columns = LeanbackProduct.getColumn()
        grid.setHasFixedSize(true)
        grid.itemAnimator = null
        grid.adapter = adapter
        grid.layoutManager = GridLayoutManager(grid.context, columns)
        grid.addItemDecoration(SpaceItemDecoration(columns, R.dimen.vs_16))
    }
}
