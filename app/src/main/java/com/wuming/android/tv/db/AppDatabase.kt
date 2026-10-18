package com.wuming.android.tv.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.github.catvod.utils.Path
import com.wuming.android.tv.App
import com.wuming.android.tv.bean.Backup
import com.wuming.android.tv.bean.Config
import com.wuming.android.tv.bean.Device
import com.wuming.android.tv.bean.Drive
import com.wuming.android.tv.bean.EpisodeNameLog
import com.wuming.android.tv.bean.History
import com.wuming.android.tv.bean.Keep
import com.wuming.android.tv.bean.Live
import com.wuming.android.tv.bean.Site
import com.wuming.android.tv.bean.SitePerformance
import com.wuming.android.tv.bean.Track
import com.wuming.android.tv.bean.ViewingRecord
import com.wuming.android.tv.cloud.CloudAuditEvent
import com.wuming.android.tv.cloud.CloudCacheRecord
import com.wuming.android.tv.db.SearchHistory
import com.wuming.android.tv.db.dao.CloudAuditDao
import com.wuming.android.tv.db.dao.CloudCacheDao
import com.wuming.android.tv.db.dao.ConfigDao
import com.wuming.android.tv.db.dao.DeviceDao
import com.wuming.android.tv.db.dao.DriveDao
import com.wuming.android.tv.db.dao.EpisodeNameLogDao
import com.wuming.android.tv.db.dao.HistoryDao
import com.wuming.android.tv.db.dao.KeepDao
import com.wuming.android.tv.db.dao.LiveDao
import com.wuming.android.tv.db.dao.MediaCatalogDao
import com.wuming.android.tv.db.dao.SearchHistoryDao
import com.wuming.android.tv.db.dao.SiteDao
import com.wuming.android.tv.db.dao.SitePerformanceDao
import com.wuming.android.tv.db.dao.TrackDao
import com.wuming.android.tv.db.dao.ViewingRecordDao
import com.wuming.android.tv.media.catalog.MediaResourceEntity
import com.wuming.android.tv.media.catalog.MediaSourceEntity
import com.wuming.android.tv.utils.FileUtil
import com.wuming.android.tv.utils.Formatters
import com.wuming.android.tv.utils.Task
import com.wuming.tv.core.library.BackupArchive
import com.wuming.tv.core.library.ContentKeyCodec
import com.wuming.tv.core.library.DatabaseUpgradePolicy
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.ArrayList

@Database(
    entities = [Keep::class, Site::class, Live::class, Track::class, com.wuming.android.tv.bean.Config::class, com.wuming.android.tv.bean.Device::class, History::class, SearchHistory::class, Drive::class, SitePerformance::class, CloudCacheRecord::class, CloudAuditEvent::class, ViewingRecord::class, MediaResourceEntity::class, MediaSourceEntity::class, EpisodeNameLog::class],
    version = AppDatabase.Companion.VERSION
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun getKeepDao(): KeepDao

    abstract fun getSiteDao(): SiteDao

    abstract fun getLiveDao(): LiveDao

    abstract fun getTrackDao(): TrackDao

    abstract fun getConfigDao(): ConfigDao

    abstract fun getDeviceDao(): DeviceDao

    abstract fun getHistoryDao(): HistoryDao

    abstract fun getSearchHistoryDao(): SearchHistoryDao

    abstract fun getDriveDao(): DriveDao

    abstract fun getSitePerformanceDao(): SitePerformanceDao

    abstract fun getCloudCacheDao(): CloudCacheDao

    abstract fun getCloudAuditDao(): CloudAuditDao

    abstract fun getViewingRecordDao(): ViewingRecordDao

    abstract fun getMediaCatalogDao(): MediaCatalogDao

    abstract fun getEpisodeNameLogDao(): EpisodeNameLogDao

    @Suppress("NON_FINAL_MEMBER_IN_OBJECT")
    companion object {
        const val VERSION: Int = 46
        const val NAME: String = "tv"
        const val SYMBOL: String = ContentKeyCodec.DEFAULT_DELIMITER

        @Volatile
        private var instance: AppDatabase? = null

        @JvmStatic
        open fun get(): AppDatabase {
            if (AppDatabase.Companion.instance == null) {
                kotlin.synchronized(AppDatabase::class.java) {
                    if (AppDatabase.Companion.instance ==
                        null
                    ) {
                        AppDatabase.Companion.instance = AppDatabase.Companion.create(App.get())
                    }
                }
            }
            return AppDatabase.Companion.instance!!
        }

        @JvmStatic open fun backup() = backup(com.wuming.android.tv.impl.Callback())

        @JvmStatic open fun backup(callback: com.wuming.android.tv.impl.Callback?) {
            Task.execute({
                try {
                    val backup: Backup = Backup.create()
                    if (backup.getConfig().isEmpty()) {
                        throw IOException(
                            "No configuration to back up"
                        )
                    }
                    val file: java.io.File = java.io.File(
                        com.github.catvod.utils.Path.Companion.tv(),
                        "tv-" + LocalDate.now().format(Formatters.DATE) + ".bk.gz"
                    )
                    BackupArchive().write(
                        file.toPath(),
                        backup.toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                    )
                } catch (error: java.lang.Exception) {
                    com.wuming.android.tv.utils.AppLog.e("Backup", "Backup failed", error)
                    App.post(callback!!::error)
                    return@execute
                }
                try {
                    AppDatabase.Companion.cleanOld()
                } catch (retentionError: java.lang.Exception) {
                    com.wuming.android.tv.utils.AppLog.e(
                        "Backup",
                        "Backup saved; retention failed",
                        retentionError
                    )
                }
                App.post(callback!!::success)
            })
        }

        @JvmStatic
        open fun restore(file: java.io.File, callback: com.wuming.android.tv.impl.Callback?) {
            Task.execute({
                try {
                    val payload: kotlin.ByteArray = BackupArchive().read(file.toPath())
                    val backup: Backup = Backup.objectFrom(
                        kotlin.text.String(payload, java.nio.charset.StandardCharsets.UTF_8)
                    )
                    if (backup.getConfig().isEmpty()) throw IOException("Invalid backup")
                    backup.restore()
                    DataCache.get().reload()
                } catch (error: java.lang.Exception) {
                    com.wuming.android.tv.utils.AppLog.e("Backup", "Restore failed", error)
                    App.post(callback!!::error)
                    return@execute
                }
                App.post(callback!!::success)
            })
        }

        @JvmStatic
        private fun cleanOld() {
            val items: MutableList<java.io.File> = ArrayList<java.io.File>()
            var files: kotlin.Array<java.io.File>? = com.github.catvod.utils.Path.Companion.tv().listFiles()
            if (files == null) files = emptyArray()
            for (file: java.io.File in files) {
                if (file.getName().startsWith("tv") &&
                    file.getName().endsWith(".bk.gz")
                ) {
                    items.add(file)
                }
            }
            if (!items.isEmpty()) {
                items.sortWith(
                    Comparator { f1: java.io.File, f2: java.io.File ->
                        java.lang.Long.compare(f2.lastModified(), f1.lastModified())
                    }
                )
            }
            if (items.size >
                7
            ) {
                for (i: Int in 7..<items.size) {
                    com.github.catvod.utils.Path.Companion.clear(
                        items.get(i)
                    )
                }
            }
        }

        @JvmStatic
        private fun create(context: android.content.Context): AppDatabase {
            // Use app-internal storage for the database. Path.tv() now always returns
            // internal storage, so this is consistent and avoids needing WRITE_EXTERNAL_STORAGE
            // permission on Android 9 and below.
            val dbDir: java.io.File = java.io.File(context.getFilesDir(), "TV/database")
            if (!dbDir.exists()) dbDir.mkdirs()
            val dbFile: java.io.File = java.io.File(dbDir, AppDatabase.Companion.NAME)
            AppDatabase.Companion.migrateIfNeeded(context, dbFile)
            try {
                DatabaseUpgradePolicy.requireSupported(dbFile, 30, AppDatabase.Companion.VERSION)
            } catch (error: IOException) {
                com.wuming.android.tv.ui.activity.DatabaseRecoveryActivity.open(
                    context,
                    error is DatabaseUpgradePolicy.UnsupportedVersion
                )
                throw java.lang.IllegalStateException(
                    "Database preserved; recovery required",
                    error
                )
            }
            return Room.databaseBuilder<AppDatabase>(
                context,
                AppDatabase::class.java,
                dbFile.getAbsolutePath()
            )
                .addMigrations(*Migrations.all())
                .build()
        }

        /**
         * Migrate database from old locations if the new location is empty.
         * On Android 9 and below, external storage access requires WRITE_EXTERNAL_STORAGE
         * permission which may not be granted, so external storage migration is best-effort.
         */
        @JvmStatic
        private fun migrateIfNeeded(context: android.content.Context, newDbFile: java.io.File) {
            if (newDbFile.exists()) return

            val newDir: java.io.File? = newDbFile.getParentFile()
            if (newDir != null && !newDir.exists()) newDir.mkdirs()

            // Try to migrate from old external storage location (used by previous app versions).
            // This may fail on Android 9 without WRITE_EXTERNAL_STORAGE permission.
            try {
                val oldExternalDir: java.io.File = java.io.File(
                    android.os.Environment.getExternalStorageDirectory(),
                    "TV"
                )
                val oldDb: java.io.File = java.io.File(oldExternalDir, AppDatabase.Companion.NAME)
                if (oldDb.exists() && oldDb.length() > 0) {
                    AppDatabase.Companion.copyDbFiles(oldDb, newDbFile)
                    com.orhanobut.logger.Logger.t(
                        "AppDatabase"
                    ).d("Migrated database from external storage")
                    return
                }
                val oldDbDir: java.io.File = java.io.File(oldExternalDir, "database")
                val oldDbInDir: java.io.File = java.io.File(oldDbDir, AppDatabase.Companion.NAME)
                if (oldDbInDir.exists() && oldDbInDir.length() > 0) {
                    AppDatabase.Companion.copyDbFiles(oldDbInDir, newDbFile)
                    com.orhanobut.logger.Logger.t(
                        "AppDatabase"
                    ).d("Migrated database from external database dir")
                    return
                }
            } catch (e: java.lang.Exception) {
                // External storage not accessible (missing permission on Android 9, or scoped storage)
                com.orhanobut.logger.Logger.t(
                    "AppDatabase"
                ).d("Cannot access external storage for migration: " + e.message)
            }

            try {
                // Try to migrate from Path.tv() location (now always internal storage)
                val pathTvDir: java.io.File = java.io.File(
                    com.github.catvod.utils.Path.Companion.tv(),
                    "database"
                )
                val pathTvDb: java.io.File = java.io.File(pathTvDir, AppDatabase.Companion.NAME)
                if (pathTvDb.exists() && pathTvDb.length() > 0 &&
                    !(pathTvDb.getAbsolutePath() == newDbFile.getAbsolutePath())
                ) {
                    AppDatabase.Companion.copyDbFiles(pathTvDb, newDbFile)
                    com.orhanobut.logger.Logger.t(
                        "AppDatabase"
                    ).d("Migrated database from Path.tv()")
                    return
                }

                // Try to migrate from default Room internal location
                val internalDb: java.io.File? = context.getDatabasePath(AppDatabase.Companion.NAME)
                if (internalDb != null && internalDb.exists() && internalDb.length() > 0) {
                    AppDatabase.Companion.copyDbFiles(internalDb, newDbFile)
                    com.orhanobut.logger.Logger.t(
                        "AppDatabase"
                    ).d("Migrated database from internal storage")
                }
            } catch (e: java.lang.Exception) {
                com.orhanobut.logger.Logger.e(e, "Failed to migrate database")
            }
        }

        @JvmStatic
        private fun copyDbFiles(src: java.io.File, dst: java.io.File) {
            com.github.catvod.utils.Path.Companion.copy(src, dst)
            val srcShm: java.io.File = java.io.File(src.getPath() + "-shm")
            val srcWal: java.io.File = java.io.File(src.getPath() + "-wal")
            if (srcShm.exists()) {
                com.github.catvod.utils.Path.Companion.copy(
                    srcShm,
                    java.io.File(dst.getPath() + "-shm")
                )
            }
            if (srcWal.exists()) {
                com.github.catvod.utils.Path.Companion.copy(
                    srcWal,
                    java.io.File(dst.getPath() + "-wal")
                )
            }
        }
    }
}
