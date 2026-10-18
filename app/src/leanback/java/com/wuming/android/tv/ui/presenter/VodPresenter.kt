package com.wuming.android.tv.ui.presenter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.leanback.widget.Presenter
import com.wuming.android.tv.LeanbackProduct
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.Style
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.databinding.AdapterVodListBinding
import com.wuming.android.tv.databinding.AdapterVodOvalBinding
import com.wuming.android.tv.databinding.AdapterVodRectBinding
import com.wuming.android.tv.ui.base.BaseVodHolder
import com.wuming.android.tv.ui.base.ViewType
import com.wuming.android.tv.ui.base.VodClickListener
import com.wuming.android.tv.ui.custom.GridFocusRestorer
import com.wuming.android.tv.ui.holder.LeanbackVodRectHolder
import com.wuming.android.tv.ui.holder.VodListHolder
import com.wuming.android.tv.ui.holder.VodOvalHolder

open class VodPresenter
    @JvmOverloads
    constructor(
        private val listener: OnClickListener,
        private val style: Style = Style.rect(),
        search: Boolean = false,
    ) : Presenter() {
        private val size = if (search) LeanbackProduct.getSearchSpec(style) else LeanbackProduct.getSpec(style)
        private var keyword: String? = null

        open fun keyword(keyword: String?): VodPresenter {
            this.keyword = keyword
            return this
        }

        interface OnClickListener : VodClickListener

        override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder =
            when (style.getViewType()) {
                ViewType.LIST -> {
                    VodListHolder(AdapterVodListBinding.inflate(LayoutInflater.from(parent.context), parent, false), listener)
                }

                ViewType.OVAL -> {
                    VodOvalHolder(
                        AdapterVodOvalBinding.inflate(LayoutInflater.from(parent.context), parent, false),
                        listener,
                    ).size(size)
                }

                else -> {
                    LeanbackVodRectHolder(
                        AdapterVodRectBinding.inflate(LayoutInflater.from(parent.context), parent, false),
                        listener,
                    ).size(size).also {
                        it.setKeyword(keyword)
                    }
                }
            }

        override fun onBindViewHolder(
            viewHolder: Presenter.ViewHolder,
            item: Any?,
        ) {
            val vod = item as Vod
            viewHolder.view.setTag(R.id.tag_vod_focus_key, GridFocusRestorer.vodKey(vod))
            (viewHolder as BaseVodHolder).initView(vod)
        }

        override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
            viewHolder.view.setTag(R.id.tag_vod_focus_key, null)
            (viewHolder as BaseVodHolder).unbind()
        }
    }
