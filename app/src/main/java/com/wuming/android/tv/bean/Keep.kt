package com.wuming.android.tv.bean

import androidx.annotation.NonNull
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.wuming.android.tv.App
import com.wuming.android.tv.impl.Diffable
import com.wuming.tv.core.library.ContentKeyCodec
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

@Entity(indices = [Index("type"), Index("cid"), Index(value = ["cid", "type"])])
class Keep : Diffable<Keep> {

    @NonNull
    @PrimaryKey
    @SerializedName("key")
    var key: String = ""

    @SerializedName("siteName")
    var siteName: String? = null

    @SerializedName("vodName")
    var vodName: String? = null

    @SerializedName("vodPic")
    var vodPic: String? = null

    @SerializedName("createTime")
    var createTime: Long = 0

    @SerializedName("type")
    var type: Int = 0

    @SerializedName("cid")
    var cid: Int = 0

    val siteKey: String
        get() = ContentKeyCodec.sourceKey(key)

    val vodId: String
        get() = ContentKeyCodec.contentId(key)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Keep) return false
        return key == other.key
    }

    override fun hashCode(): Int = key.hashCode()

    override fun isSameItem(other: Keep): Boolean = equals(other)

    override fun isSameContent(other: Keep): Boolean =
        vodName == other.vodName && vodPic == other.vodPic && createTime == other.createTime

    companion object {
        @JvmStatic
        fun arrayFrom(str: String?): List<Keep> {
            val listType = object : TypeToken<List<Keep>>() {}.type
            val items: List<Keep>? = App.gson().fromJson(str, listType)
            return BeanUtils.emptyListIfNull(items)
        }
    }
}
