package com.wuming.tv.core.catalog

/** Host dispatch surface, kept distinct from observed provider success in CatalogVerification. */
data class CatalogHostContract(val name: String, val methods: Set<String>) {
    fun supports(method: String): Boolean = method in methods
    fun summary(): String = if (methods.isEmpty()) "Host method coverage is unspecified."
        else "$name exposes ${methods.sorted().joinToString()}; not exposed: ${OPTIONAL.filterNot(::supports).joinToString()}. Provider behavior still requires verification."

    companion object {
        private val OPTIONAL = listOf("searchContent(page)", "action", "liveContent", "proxy")
        val UNSPECIFIED = CatalogHostContract("Unknown host", emptySet())
        val JVM = CatalogHostContract("Public JVM catalogue host", setOf("homeContent", "categoryContent", "searchContent", "detailContent", "playerContent", "destroy"))
        val GUARDED = CatalogHostContract("Guarded catalogue host", JVM.methods + setOf("homeVideoContent", "proxy"))
    }
}
