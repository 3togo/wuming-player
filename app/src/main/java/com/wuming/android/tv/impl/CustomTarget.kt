package com.wuming.android.tv.impl

import android.graphics.drawable.Drawable
import com.bumptech.glide.request.transition.Transition

open class CustomTarget<T : Any> : com.bumptech.glide.request.target.CustomTarget<T> {
    constructor() : super()
    constructor(width: Int, height: Int) : super(width, height)

    override fun onResourceReady(resource: T, transition: Transition<in T>?) {}

    override fun onLoadCleared(placeholder: Drawable?) {}
}
