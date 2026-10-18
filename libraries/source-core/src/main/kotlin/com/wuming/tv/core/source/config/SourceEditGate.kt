package com.wuming.tv.core.source.config

/** Temporary local UI gate for editing bundled source addresses. */
object SourceEditGate {
    @JvmStatic fun accepts(password: String): Boolean = password == "123..321"
}
