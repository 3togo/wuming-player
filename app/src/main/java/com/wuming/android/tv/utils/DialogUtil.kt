package com.wuming.android.tv.utils

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import com.wuming.android.tv.R

object DialogUtil {
    @JvmStatic
    fun inflater(context: Context): LayoutInflater {
        val wrapper = ContextThemeWrapper(context, R.style.AppDialogContentStyle)
        return LayoutInflater.from(wrapper)
    }
}
