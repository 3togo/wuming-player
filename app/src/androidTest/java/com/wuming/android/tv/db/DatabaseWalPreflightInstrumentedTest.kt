package com.wuming.android.tv.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wuming.tv.core.library.DatabaseUpgradePolicy
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseWalPreflightInstrumentedTest {
    @Test fun committedPlatformWalVersionIsCheckedWithoutChangingOriginals() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "wal-preflight-${UUID.randomUUID()}")
        assertTrue(directory.mkdir())
        val file = File(directory, "tv")
        try {
            SQLiteDatabase.openDatabase(file.path, null,
                SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING).use { db ->
                db.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { assertTrue(it.moveToFirst()) }
                db.beginTransaction()
                try {
                    db.execSQL("CREATE TABLE retained (value TEXT)")
                    db.version = AppDatabase.VERSION
                    db.execSQL("INSERT INTO retained VALUES ('synthetic retained row')")
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                assertNotEquals(AppDatabase.VERSION, ByteBuffer.wrap(file.readBytes()).getInt(60))
                assertTrue(File(directory, "tv-wal").length() > 32)
                val supported = snapshot(directory)
                DatabaseUpgradePolicy.requireSupported(file, 30, AppDatabase.VERSION)
                assertUnchanged(supported, directory)

                db.version = AppDatabase.VERSION + 1
                val newer = snapshot(directory)
                val failure = assertThrows(DatabaseUpgradePolicy.UnsupportedVersion::class.java) {
                    DatabaseUpgradePolicy.requireSupported(file, 30, AppDatabase.VERSION)
                }
                assertEquals(AppDatabase.VERSION + 1, failure.version)
                assertUnchanged(newer, directory)
                db.rawQuery("SELECT value FROM retained", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("synthetic retained row", it.getString(0))
                }
            }
        } finally { directory.deleteRecursively() }
    }

    private fun snapshot(directory: File) = directory.listFiles()!!.associate { it.name to it.readBytes() }

    private fun assertUnchanged(before: Map<String, ByteArray>, directory: File) {
        val after = snapshot(directory)
        assertEquals(before.keys, after.keys)
        for ((name, bytes) in before) assertArrayEquals(name, bytes, after.getValue(name))
    }
}
