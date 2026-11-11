package com.wuming.android.tv.library

import com.wuming.android.tv.App
import com.wuming.android.tv.bean.Site
import com.wuming.android.tv.setting.AppSettings
import com.wuming.tv.core.library.recommend.ProviderRecommendationSession
import java.util.concurrent.Callable

/** Android storage/IPC port for the observed Douban recommendation contract. */
object ProviderRecommendationBridge {
    private val session = ProviderRecommendationSession()
    @JvmStatic fun seeds(site: Site, category: String, page: String): List<String>? {
        if (!ProviderRecommendationSession.applies(site.api, site.name, category)) return null
        val privateViewing = AppSettings.isIncognito()
        val names = if (privateViewing) emptyList() else UserLibrary.get().histories().map { it.vodName }
        return session.seeds("${com.wuming.android.tv.api.config.VodConfig.get().config?.id}:${site.key}", page.toIntOrNull() ?: 1, names, privateViewing)
    }

    @JvmStatic @Synchronized
    fun <T> withSeeds(seeds: List<String>?, call: Callable<T>): T {
        if (seeds == null) return call.call()
        val app = App.get()
        return withSeeds(app.getSharedPreferences(app.packageName + "_preferences", 0), seeds, call)
    }

    @JvmStatic @Synchronized
    fun <T> withSeeds(preferences: android.content.SharedPreferences, seeds: List<String>, call: Callable<T>): T {
        val previous = preferences.getString("historyvodname", null)
        try {
            preferences.edit().putString("historyvodname", com.google.gson.Gson().toJson(seeds.take(10))).commit()
            return call.call()
        } finally {
            val edit = preferences.edit()
            if (previous == null) edit.remove("historyvodname") else edit.putString("historyvodname", previous)
            edit.commit()
        }
    }
}
