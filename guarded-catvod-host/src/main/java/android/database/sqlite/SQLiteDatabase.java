package android.database.sqlite;

import android.content.ContentValues;
import android.database.Cursor;

/**
 * Inert SQLite bridge for the isolated provider process. The guarded host is a plain JVM without
 * Android's SQLite engine, so provider databases report no rows instead of aborting the request:
 * providers then fall back to fetching their configuration, exactly as they do on a first launch.
 */
public final class SQLiteDatabase extends SQLiteClosable {
    public static final int OPEN_READWRITE = 0x00000000;
    public static final int OPEN_READONLY = 0x00000001;
    public static final int CREATE_IF_NECESSARY = 0x10000000;

    private final String path;
    private boolean open = true;

    private SQLiteDatabase(String path) {
        this.path = path;
    }

    public static SQLiteDatabase openDatabase(String path, CursorFactory factory, int flags) {
        return new SQLiteDatabase(path == null ? "" : path);
    }

    public static SQLiteDatabase openOrCreateDatabase(String path, CursorFactory factory) {
        return openDatabase(path, factory, CREATE_IF_NECESSARY);
    }

    public String getPath() {
        return path;
    }

    public boolean isOpen() {
        return open;
    }

    public void execSQL(String sql) {
    }

    public void execSQL(String sql, Object[] bindArgs) {
    }

    public long insert(String table, String nullColumnHack, ContentValues values) {
        return 1;
    }

    public Cursor rawQuery(String sql, String[] selectionArgs) {
        return EmptyCursor.INSTANCE;
    }

    @Override
    public void close() {
        open = false;
    }

    /** Signature-only marker: the host never builds Android cursors. */
    public interface CursorFactory {
    }

    private static final class EmptyCursor implements Cursor {
        static final EmptyCursor INSTANCE = new EmptyCursor();

        public boolean moveToFirst() {
            return false;
        }

        public String getString(int index) {
            return null;
        }

        public int getInt(int index) {
            return 0;
        }

        public void close() {
        }
    }
}
