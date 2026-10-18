package com.github.catvod.bean

import android.content.Context
import androidx.annotation.NonNull
import com.github.catvod.crawler.R
import com.github.catvod.utils.Json
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.net.InetAddress

class Doh {

    @SerializedName("name")
    private var name: String? = null

    @SerializedName("url")
    private var url: String? = null

    @SerializedName("ips")
    private var ips: List<String>? = null

    fun name(name: String): Doh {
        this.name = name
        return this
    }

    fun url(url: String): Doh {
        this.url = url
        return this
    }

    fun getName(): String = if (name.isNullOrEmpty()) "" else name!!

    fun getUrl(): String = if (url.isNullOrEmpty()) "" else url!!

    fun getIps(): List<String> = ips ?: emptyList()

    fun getHosts(): List<InetAddress>? {
        val list = mutableListOf<InetAddress>()
        for (ip in getIps()) {
            try {
                list.add(InetAddress.getByName(ip))
            } catch (e: Exception) {
                android.util.Log.w("Doh", "Invalid IP: $ip", e)
            }
        }
        return if (list.isEmpty()) null else list
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Doh) return false
        return url == other.url
    }

    override fun hashCode(): Int = url?.hashCode() ?: 0

    @NonNull
    override fun toString(): String = Json.GSON.toJson(this)

    companion object {

        @JvmStatic
        fun get(context: Context): List<Doh> {
            val items = mutableListOf<Doh>()
            val urls = context.resources.getStringArray(R.array.doh_url)
            val names = context.resources.getStringArray(R.array.doh_name)
            for (i in names.indices) items.add(Doh().name(names[i]).url(urls[i]))
            return items
        }

        @JvmStatic
        fun objectFrom(str: String?): Doh {
            val item: Doh? = Json.GSON.fromJson(str, Doh::class.java)
            return item ?: Doh()
        }

        @JvmStatic
        fun arrayFrom(element: JsonElement?): List<Doh> {
            return try {
                val listType = object : TypeToken<List<Doh>>() {}.type
                val items: List<Doh>? = Json.GSON.fromJson(element, listType)
                items ?: mutableListOf()
            } catch (e: Exception) {
                mutableListOf()
            }
        }
    }
}
