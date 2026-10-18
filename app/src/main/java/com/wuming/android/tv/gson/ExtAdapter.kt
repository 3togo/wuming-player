package com.wuming.android.tv.gson

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import java.lang.reflect.Type

class ExtAdapter : JsonDeserializer<String> {
    @Throws(JsonParseException::class)
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): String {
        if (json.isJsonPrimitive) return json.asJsonPrimitive.asString
        if (json.isJsonArray || json.isJsonObject) return json.toString()
        return ""
    }
}
