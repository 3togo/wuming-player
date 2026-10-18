package com.github.catvod.utils

/** Retains the separately compiled spider API; script conversion is shared. */
object Trans {
    @JvmStatic fun pass(): Boolean = com.wuming.tv.core.text.ChineseScripts.pass()
    @JvmStatic fun s2t(text: String?): String? = com.wuming.tv.core.text.ChineseScripts.s2t(text)
    @JvmStatic fun t2s(text: String?): String? = com.wuming.tv.core.text.ChineseScripts.t2s(text)
    @JvmStatic fun s2t(pass: Boolean, text: String): String = com.wuming.tv.core.text.ChineseScripts.s2t(pass, text)
    @JvmStatic fun t2s(pass: Boolean, text: String): String = com.wuming.tv.core.text.ChineseScripts.t2s(pass, text)
}
