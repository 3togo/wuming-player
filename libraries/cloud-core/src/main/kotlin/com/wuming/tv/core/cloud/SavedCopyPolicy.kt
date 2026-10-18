package com.wuming.tv.core.cloud

/** Provider-neutral ownership and age checks; remote membership is verified by adapters. */
object SavedCopyPolicy {
    @JvmStatic
    fun owned(provider: String, account: String, owner: String,
              actualProvider: String, actualAccount: String, source: String?, active: Boolean): Boolean {
        return active && account.isNotEmpty() && owner.isNotEmpty()
            && provider == actualProvider && account == actualAccount
            && source != null && source.startsWith(owner)
    }

    @JvmStatic
    fun expired(updated: Long, now: Long, holdMillis: Long): Boolean {
        return now >= updated && now - updated >= holdMillis
    }
}
