package com.wuming.android.tv.gson

import com.wuming.android.tv.bean.Filter
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import java.lang.reflect.Type

class FilterAdapter : JsonDeserializer<LinkedHashMap<String, List<Filter>>> {
    @Throws(JsonParseException::class)
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): LinkedHashMap<String, List<Filter>> {
        val filterMap = LinkedHashMap<String, List<Filter>>()
        val filters = json.asJsonObject ?: return filterMap
        for ((key, _) in filters.entrySet()) {
            val items = ArrayList<Filter>()
            val element = filters.get(key)
            if (element.isJsonObject) {
                val filter = Filter.objectFrom(element)
                if (filter != null) items.add(filter.check().trans())
            } else {
                for (item in element.asJsonArray) {
                    val filter = Filter.objectFrom(item)
                    if (filter != null) items.add(filter.check().trans())
                }
            }
            filterMap[key] = items
        }
        return filterMap
    }
}
