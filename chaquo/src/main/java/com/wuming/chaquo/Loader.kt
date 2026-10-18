package com.wuming.chaquo

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.github.catvod.utils.Path

class Loader {
    private val app: PyObject

    init {
        if (!Python.isStarted()) Python.start(Platform.create())
        app = Python.getInstance().getModule("app")
    }

    fun spider(api: String): Spider {
        val obj = app.callAttr("spider", Path.py().absolutePath, api)
        return Spider(app, obj, api)
    }
}
