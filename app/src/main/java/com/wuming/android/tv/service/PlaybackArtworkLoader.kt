package com.wuming.android.tv.service

import android.content.Context
import androidx.media3.common.util.BitmapLoader
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.CacheBitmapLoader
import com.github.catvod.net.OkHttp
import com.wuming.android.tv.Constant

/** Uses the same DNS, proxy and HTTP configuration as in-app artwork requests. */
object PlaybackArtworkLoader {
    @JvmStatic
    fun create(context: Context): BitmapLoader = CacheBitmapLoader(
        DataSourceBitmapLoader.Builder(context)
            .setExecutorService(DataSourceBitmapLoader.DEFAULT_EXECUTOR_SERVICE.get())
            .setDataSourceFactory(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(OkHttp.client(Constant.TIMEOUT_VOD))))
            .build(),
    )
}
