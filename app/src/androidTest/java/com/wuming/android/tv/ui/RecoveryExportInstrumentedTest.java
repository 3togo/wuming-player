package com.wuming.android.tv.ui;

import static org.junit.Assert.*;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.wuming.android.tv.R;
import com.wuming.android.tv.ui.activity.DatabaseRecoveryTestActivity;
import com.wuming.tv.core.library.DatabaseUpgradePolicy;
import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipInputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RecoveryExportInstrumentedTest {
    @Test public void unsupportedDatabaseAndWalExportWithoutChangingOriginals() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File dir = new File(context.getCacheDir(), "recovery-smoke-" + UUID.randomUUID());
        assertTrue(dir.mkdir());
        File original = new File(dir, "tv");
        File archive = new File(dir, "export.zip");
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(original, null)) {
            db.execSQL("CREATE TABLE retained (value TEXT)");
            db.setVersion(999);
            db.execSQL("INSERT INTO retained VALUES ('base')");
            assertTrue(db.enableWriteAheadLogging());
            try (android.database.Cursor pragma = db.rawQuery("PRAGMA wal_autocheckpoint=0", null)) {
                assertTrue(pragma.moveToFirst());
            }
            db.execSQL("INSERT INTO retained VALUES ('wal')");
            Map<String, byte[]> before = snapshot(dir);
            assertTrue(before.containsKey("tv-wal"));
            assertTrue(before.containsKey("tv-shm"));
            assertThrows(DatabaseUpgradePolicy.UnsupportedVersion.class,
                    () -> DatabaseUpgradePolicy.requireSupported(original, 30, 46));
            Intents.init();
            try {
                Intent destination = new Intent().setData(FileProvider.getUriForFile(
                        context, context.getPackageName() + ".provider", archive));
                intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(
                        new Instrumentation.ActivityResult(Activity.RESULT_OK, destination));
                Intent intent = new Intent(context, DatabaseRecoveryTestActivity.class)
                        .putExtra("fixture", dir.getName()).putExtra("schema", true);
                try (ActivityScenario<DatabaseRecoveryTestActivity> scenario = TestActivityLauncher.launch(intent)) {
                    scenario.onActivity(activity -> {
                        assertEquals(activity.getString(R.string.database_recovery_schema),
                                ((TextView) activity.findViewById(R.id.recovery_message)).getText().toString());
                        assertTrue(activity.findViewById(R.id.recovery_export).performClick());
                    });
                    AtomicBoolean complete = new AtomicBoolean();
                    long deadline = SystemClock.elapsedRealtime() + 15_000;
                    while (!complete.get() && SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity(activity -> complete.set(activity.getString(R.string.database_recovery_exported)
                                .equals(((TextView)activity.findViewById(R.id.recovery_status)).getText().toString())));
                        SystemClock.sleep(100);
                    }
                    assertTrue("Recovery UI must report completed export", complete.get());
                    scenario.onActivity(activity -> assertTrue(activity.findViewById(R.id.recovery_retry).performClick()));
                    complete.set(false);
                    deadline = SystemClock.elapsedRealtime() + 5000;
                    while (!complete.get() && SystemClock.elapsedRealtime() < deadline) {
                        scenario.onActivity(activity -> complete.set(activity.getString(R.string.database_recovery_schema)
                                .equals(((TextView)activity.findViewById(R.id.recovery_status)).getText().toString())));
                        SystemClock.sleep(100);
                    }
                    assertTrue("Unsupported retry must stay in recovery", complete.get());
                }
            } finally { Intents.release(); }
            Map<String, byte[]> after = snapshot(dir);
            assertEquals(before.keySet(), after.keySet());
            for (String name : before.keySet()) assertArrayEquals(name, before.get(name), after.get(name));
            File restored = new File(dir, "restored");
            assertTrue(restored.mkdir());
            Map<String, byte[]> exported = new LinkedHashMap<>();
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive.toPath()))) {
                for (java.util.zip.ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                    assertTrue(before.containsKey(entry.getName()));
                    byte[] bytes = zip.readAllBytes();
                    exported.put(entry.getName(), bytes);
                    assertArrayEquals(before.get(entry.getName()), bytes);
                    Files.write(new File(restored, entry.getName()).toPath(), bytes);
                }
            }
            assertEquals(before.keySet(), exported.keySet());
            try (SQLiteDatabase recovered = SQLiteDatabase.openDatabase(new File(restored, "tv").getPath(), null, SQLiteDatabase.OPEN_READWRITE);
                 android.database.Cursor rows = recovered.rawQuery("SELECT count(*) FROM retained", null)) {
                assertTrue(rows.moveToFirst());
                assertEquals(2, rows.getInt(0));
                assertEquals(999, recovered.getVersion());
            }
        } finally {
            try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(dir.toPath())) {
                for (java.nio.file.Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    private static Map<String, byte[]> snapshot(File dir) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
            File file = new File(dir, "tv" + suffix);
            if (file.exists()) result.put(file.getName(), Files.readAllBytes(file.toPath()));
        }
        return result;
    }
}
