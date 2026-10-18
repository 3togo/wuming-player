package com.wuming.android.tv.gson

import com.wuming.android.tv.App
import com.wuming.android.tv.bean.Danmaku
import com.github.catvod.utils.Json
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import java.lang.reflect.Type

class DanmakuAdapter : JsonDeserializer<List<Danmaku>> {
    @Throws(JsonParseException::class)
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): List<Danmaku> {
        val items: List<Danmaku> = if (json.isJsonPrimitive) parsePrimitive(json.asString.trim(), typeOfT) else App.gson().fromJson(json, typeOfT)
        val result = ArrayList<Danmaku>(items.size)
        for (d in items) if (!d.isEmpty()) result.add(d)
        return result
    }

    private fun parsePrimitive(text: String, type: Type): List<Danmaku> =
        if (Json.isArray(text)) App.gson().fromJson(text, type) else listOf(Danmaku.from(text))
}
