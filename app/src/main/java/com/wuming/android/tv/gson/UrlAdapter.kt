package com.wuming.android.tv.gson

import com.wuming.android.tv.bean.Url
import com.google.gson.JsonArray
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import java.lang.reflect.Type

class UrlAdapter : JsonDeserializer<Url> {
    @Throws(JsonParseException::class)
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): Url {
        if (json.isJsonArray) return convert(json.asJsonArray)
        if (json.isJsonObject) return Url.objectFrom(json)
        return Url.create().add(json.asString)
    }

    private fun convert(array: JsonArray): Url {
        val url = Url.create()
        var i = 0
        while (i + 1 < array.size()) {
            url.add(array[i].asString, array[i + 1].asString)
            i += 2
        }
        return url
    }
}
