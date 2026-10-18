package com.wuming.android.tv.packages

import com.wuming.android.tv.App
import com.wuming.tv.core.source.packages.FilePackageStorage
import com.wuming.tv.core.source.packages.SourcePackageLibrary
import java.io.File

object AndroidSourcePackages {
    val library by lazy { SourcePackageLibrary(FilePackageStorage(File(App.get().filesDir, "source-packages"))) }
}
