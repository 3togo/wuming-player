package com.wuming.android.tv.api.config

import com.wuming.android.tv.bean.Rule

class RuleConfig {

    @Volatile
    private var _ads: List<String> = emptyList()

    @Volatile
    private var _rules: List<Rule> = emptyList()

    @Volatile
    private var dirty = false

    val ads: List<String>
        @JvmName("getAds")
        get() {
            if (dirty) merge()
            return _ads
        }

    val rules: List<Rule>
        @JvmName("getRules")
        get() {
            if (dirty) merge()
            return _rules
        }

    fun invalidate() {
        dirty = true
    }

    @Synchronized
    private fun merge() {
        if (!dirty) return
        val adsList = ArrayList(VodConfig.get().ads)
        adsList.addAll(LiveConfig.get().ads)
        _ads = adsList
        val rulesList = ArrayList(VodConfig.get().rules)
        rulesList.addAll(LiveConfig.get().rules)
        _rules = rulesList
        dirty = false
    }

    companion object {
        @JvmStatic
        fun get(): RuleConfig = Loader.instance
    }

    private object Loader {
        val instance = RuleConfig()
    }
}
