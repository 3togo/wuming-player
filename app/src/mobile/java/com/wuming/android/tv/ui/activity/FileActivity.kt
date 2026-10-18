package com.wuming.android.tv.ui.activity

import android.view.MenuItem
import androidx.annotation.NonNull
import com.wuming.android.tv.ui.base.BaseFileActivity
import java.io.File

class FileActivity : BaseFileActivity() {
    override fun configureBrowser() {
        setSupportActionBar(mBinding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setTitle("")
    }

    override fun resetBrowserPosition() {
        mBinding.recycler.scrollToPosition(0)
    }

    override fun onDirectoryChanged(directory: File) {
        mBinding.title.text = directory.absolutePath
    }

    override fun onOptionsItemSelected(@NonNull item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) onBackInvoked()
        return super.onOptionsItemSelected(item)
    }
}
