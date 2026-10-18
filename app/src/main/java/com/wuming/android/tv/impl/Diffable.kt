package com.wuming.android.tv.impl

interface Diffable<T> {
    fun isSameItem(other: T): Boolean
    fun isSameContent(other: T): Boolean
}
