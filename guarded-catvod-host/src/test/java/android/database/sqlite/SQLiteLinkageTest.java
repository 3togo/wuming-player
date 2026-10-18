package android.database.sqlite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.database.Cursor;
import org.junit.Test;

public class SQLiteLinkageTest {

    @Test
    public void reportsProviderDatabasesAsEmptyInsteadOfFailingLinkage() {
        SQLiteDatabase database = SQLiteDatabase.openDatabase("/missing/tv", null, SQLiteDatabase.OPEN_READWRITE);
        database.execSQL("UPDATE Config SET time = ?", new Object[] { 1L, "url" });

        ContentValues values = new ContentValues();
        values.put("url", "https://example.test");
        values.put("type", 1);
        values.put("time", 2L);
        assertEquals(3, values.size());

        assertEquals(1L, database.insert("Config", null, values));
        Cursor cursor = database.rawQuery("SELECT COUNT(*) FROM Config", new String[0]);
        assertFalse(cursor.moveToFirst());
        assertEquals(0, cursor.getInt(0));
        cursor.close();
        assertTrue(database.isOpen());
        database.close();
        assertFalse(database.isOpen());
    }

    @Test
    public void suppliesOnlyTheTypesNeededForProviderLinkage() {
        assertEquals(4, Cursor.class.getDeclaredMethods().length);
        assertEquals(0, SQLiteDatabase.CursorFactory.class.getDeclaredMethods().length);
    }
}
