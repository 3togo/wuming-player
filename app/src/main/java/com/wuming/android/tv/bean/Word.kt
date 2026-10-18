package com.wuming.android.tv.bean

import com.wuming.android.tv.App
import com.wuming.android.tv.impl.Diffable
import com.github.catvod.utils.Trans
import com.google.gson.annotations.SerializedName

class Word {
    @SerializedName("data")
    private var data: List<Data>? = null

    fun trans(): Word {
        if (Trans.pass()) return this
        getData().forEach { it.trans() }
        return this
    }

    fun getData(): List<Data> = BeanUtils.emptyListIfNull(data)

    class Data : Diffable<Data> {
        @SerializedName(value = "title", alternate = ["name"])
        private var title: String? = null

        constructor()

        constructor(title: String?) {
            this.title = title
        }

        fun getTitle(): String = BeanUtils.emptyIfNull(title)

        override fun isSameItem(other: Data): Boolean = getTitle() == other.getTitle()

        override fun isSameContent(other: Data): Boolean = getTitle() == other.getTitle()

        fun trans(): Data {
            this.title = Trans.s2t(title)
            return this
        }
    }

    companion object {
        @JvmStatic
        fun objectFrom(str: String?): Word {
            val word: Word? = App.gson().fromJson(str, Word::class.java)
            return word?.trans() ?: Word()
        }
    }
}
