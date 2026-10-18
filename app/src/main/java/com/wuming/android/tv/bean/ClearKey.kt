package com.wuming.android.tv.bean

import android.util.Base64
import com.wuming.android.tv.App
import com.github.catvod.utils.Util
import com.google.gson.annotations.SerializedName

class ClearKey {
    @SerializedName("keys")
    private var keys: MutableList<Keys>? = null

    @SerializedName("type")
    private var type: String? = null

    companion object {
        @JvmStatic
        @Throws(Exception::class)
        fun objectFrom(str: String?): ClearKey {
            val item: ClearKey? = App.gson().fromJson(str, ClearKey::class.java)
            if (item?.keys == null) throw Exception()
            return item
        }

        @JvmStatic
        fun get(line: String): ClearKey {
            val item = ClearKey()
            item.keys = ArrayList()
            item.type = "temporary"
            item.addKeys(line)
            return item
        }
    }

    private fun addKeys(line: String) {
        val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        for (s in line.split(",")) {
            val a = s.split(":")
            val kid = Util.base64(Util.hex2byte(a[0].trim()), flags).replace("=", "")
            val k = Util.base64(Util.hex2byte(a[1].trim()), flags).replace("=", "")
            keys!!.add(Keys(kid, k))
        }
    }

    override fun toString(): String = App.gson().toJson(this)

    class Keys(
        @SerializedName("kid") private var kid: String,
        @SerializedName("k") private var k: String
    ) {
        @SerializedName("kty")
        private var kty: String = "oct"
    }
}
