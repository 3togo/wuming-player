package com.wuming.android.tv.impl

import com.permissionx.guolindev.callback.RequestCallback
import com.permissionx.guolindev.request.ChainTask
import com.permissionx.guolindev.request.ExplainScope
import com.permissionx.guolindev.request.ForwardScope
import java.util.function.Consumer

class PermissionCallback : RequestCallback, ChainTask {
    private var result: Consumer<Boolean>? = null

    constructor()

    constructor(result: Consumer<Boolean>) {
        this.result = result
    }

    override fun onResult(allGranted: Boolean, grantedList: List<String>, deniedList: List<String>) {
        result?.accept(allGranted)
    }

    override fun finish() {
        result?.accept(true)
    }

    override fun getExplainScope(): ExplainScope? = null

    override fun getForwardScope(): ForwardScope? = null

    override fun request() {}

    override fun requestAgain(permissions: List<String>) {}
}
