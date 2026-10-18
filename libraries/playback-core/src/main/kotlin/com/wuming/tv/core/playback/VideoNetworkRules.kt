package com.wuming.tv.core.playback

/** Host adapters measure the active network and persist preferences. These rules
 * decide whether video traffic is allowed and which warning/constraint applies. */
object VideoNetworkRules {
    enum class Blocked { MOBILE_DATA, ROAMING_DATA }
    enum class Warning { MOBILE_DATA, ROAMING_DATA }
    @JvmStatic
    fun blocked(cellular: Boolean, roaming: Boolean, mobileAllowed: Boolean, roamingAllowed: Boolean): Blocked? = when {
        !cellular -> null
        !mobileAllowed -> Blocked.MOBILE_DATA
        roaming && !roamingAllowed -> Blocked.ROAMING_DATA
        else -> null
    }
    @JvmStatic
    fun warning(cellular: Boolean, roaming: Boolean, mobileAllowed: Boolean, roamingAllowed: Boolean): Warning? = when {
        !cellular || !mobileAllowed -> null
        roaming && roamingAllowed -> Warning.ROAMING_DATA
        roaming -> null
        else -> Warning.MOBILE_DATA
    }
    @JvmStatic
    fun requiresUnmeteredNetwork(mobileAllowed: Boolean): Boolean = !mobileAllowed
}
