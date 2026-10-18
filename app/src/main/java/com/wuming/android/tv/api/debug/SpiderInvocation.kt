package com.wuming.android.tv.api.debug

class SpiderInvocation(
    operation: SpiderOperation?,
    arguments: List<String>? = emptyList(),
    parameters: Map<String, String>? = emptyMap(),
    flags: List<String>? = emptyList(),
    val filter: Boolean = true,
    val quick: Boolean = false
) {
    val operation: SpiderOperation = requireNotNull(operation) { "Spider operation is required" }
    val arguments: List<String> = java.util.List.copyOf(arguments ?: emptyList())
    val parameters: Map<String, String> = java.util.Map.copyOf(parameters ?: emptyMap())
    val flags: List<String> = java.util.List.copyOf(flags ?: emptyList())

    fun operation(): SpiderOperation = operation
    fun arguments(): List<String> = arguments
    fun parameters(): Map<String, String> = parameters
    fun flags(): List<String> = flags
    fun filter(): Boolean = filter
    fun quick(): Boolean = quick

    override fun equals(other: Any?): Boolean = other is SpiderInvocation &&
        operation == other.operation && arguments == other.arguments &&
        parameters == other.parameters && flags == other.flags &&
        filter == other.filter && quick == other.quick

    override fun hashCode(): Int = listOf(operation, arguments, parameters, flags, filter, quick).hashCode()

    companion object {
        @JvmStatic
        fun home(): SpiderInvocation = SpiderInvocation(SpiderOperation.HOME)
    }
}
