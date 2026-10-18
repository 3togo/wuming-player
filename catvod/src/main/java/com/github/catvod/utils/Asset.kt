package com.github.catvod.utils

import com.github.catvod.Init
import java.io.InputStream

object Asset {

    @JvmStatic
    fun open(fileName: String): InputStream? {
        return try {
            Init.context()?.assets?.open(fileName.replace("assets://", ""))
        } catch (e: Exception) {
            null
        }
    }

    @JvmStatic
    fun read(fileName: String): String {
        return try {
            Path.read(open(fileName))
        } catch (e: Exception) {
            ""
        }
    }
}
