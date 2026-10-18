package com.wuming.android.tv.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.viewbinding.ViewBinding
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.BeanUtils
import com.wuming.android.tv.databinding.ActivityCrashBinding
import com.wuming.android.tv.ui.base.BaseActivity
import com.github.catvod.utils.Prefers
import cat.ereza.customactivityoncrash.CustomActivityOnCrash
import cat.ereza.customactivityoncrash.config.CaocConfig

class CrashActivity : BaseActivity() {

    private var mBinding: ActivityCrashBinding? = null
    private var errorDialog: AlertDialog? = null

    override fun customWall(): Boolean = false

    override fun getBinding(): ViewBinding {
        mBinding = ActivityCrashBinding.inflate(layoutInflater)
        return mBinding!!
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCrash()
    }

    override fun initEvent() {
        mBinding?.details?.setOnClickListener { showError() }
        mBinding?.restart?.setOnClickListener { restartApplication() }
        mBinding?.restart?.requestFocus()
    }

    private fun restartApplication() {
        val config = CustomActivityOnCrash.getConfigFromIntent(intent)
        if (config != null) {
            CustomActivityOnCrash.restartApplication(this, config)
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(launch)
        }
        finishAffinity()
    }

    private fun setCrash() {
        val log = CustomActivityOnCrash.getActivityLogFromIntent(intent)
        if (BeanUtils.isEmpty(log)) return
        val lines = log!!.split("\n")
        for (i in lines.indices.reversed()) {
            if (lines[i].isEmpty()) continue
            if (lines[i].contains(HomeActivity::class.java.simpleName)) {
                Prefers.put("crash", true)
                break
            }
        }
    }

    private fun showError() {
        errorDialog = AlertDialog.Builder(this)
            .setTitle(R.string.crash_details_title)
            .setMessage(CustomActivityOnCrash.getAllErrorDetailsFromIntent(this, intent))
            .setPositiveButton(R.string.crash_details_close, null)
            .show()
        errorDialog?.window?.decorView?.let(com.wuming.android.tv.utils.TextScale::applyDialog)
    }

    override fun onDestroy() {
        errorDialog?.let { if (it.isShowing) it.dismiss() }
        errorDialog = null
        mBinding = null
        super.onDestroy()
    }
}
